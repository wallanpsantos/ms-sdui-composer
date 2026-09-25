package br.com.empresa.sdui.adapters.mongo

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Registro durável de idempotência de operações no MongoDB (`P06`, `ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [IdempotencyStore] persistindo reservas em voo e resultados de operações.
 *
 * ### 2. Para que serve
 * Assegurar semântica exactly-once e prevenir duplicidade na execução de comandos administrativos
 * mutantes (abrir, aprovar, rejeitar ou fazer rollback) entre instâncias concorrentes.
 *
 * ### 3. Como funciona
 * A reserva tenta um `insertOne` no MongoDB chaveado pelo [MongoFields.ID] com estado `IN_FLIGHT`.
 * Se colidir, lê o registro: se concluído dentro da janela de validade, devolve o resultado prévio
 * para replay; se vencido, tenta reaproveitar a chave via CAS. Na conclusão ([complete]), atualiza o
 * estado para `COMPLETED` com o TTL configurado dentro da transação do comando; em caso de falha antes
 * do commit, a reserva é liberada ([release]). Registros abandonados expiram via índice TTL nativo.
 */
class MongoIdempotencyStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val clock: Clock,
    private val ttl: Duration,
    private val reservationTimeout: Duration,
) : IdempotencyStore {
    private val records = database.getCollection(MongoSchema.IDEMPOTENCY)

    /**
     * Localiza um registro de idempotência se ainda estiver dentro do prazo de validade.
     *
     * ### 1. O que faz
     * Consulta um registro de idempotência previamente concluído pela chave informada.
     *
     * ### 2. Para que serve
     * Permitir a repetição (replay) segura da resposta de comandos já executados com a mesma chave.
     *
     * ### 3. Como funciona
     * Busca por [MongoFields.ID] e filtra registros cujo campo `expiresAt` seja posterior ao instante atual.
     */
    override fun find(key: String): IdempotencyRecord? =
        records.firstMatch(sessions, Filters.eq(MongoFields.ID, key))
            ?.takeIf { it.expiresAt().isAfter(clock.instant()) }
            ?.toRecord()

    /**
     * Tenta reservar exclusivamente uma chave de idempotência para uma operação.
     *
     * ### 1. O que faz
     * Adquire a reserva da [key] criando um registro em voo associado a um token único.
     *
     * ### 2. Para que serve
     * Bloquear execuções simultâneas concorrentes do mesmo comando administrativo.
     *
     * ### 3. Como funciona
     * Gera um token UUID e tenta inserir o documento com estado `IN_FLIGHT`. Em caso de chave existente,
     * avalia expiração: se expirado, tenta substituição atômica via CAS; caso contrário, retorna
     * [IdempotencyReservation.Existing] para tratamento de concorrência ou replay.
     */
    override fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation {
        val now = clock.instant()
        val token = UUID.randomUUID().toString()
        val reservation = document(
            IdempotencyRecord(key, operation, null, fingerprint),
            STATE_IN_FLIGHT,
            now.plus(reservationTimeout),
            token
        )
        if (tryInsert(reservation)) return IdempotencyReservation.Reserved(token)
        val existing = records.find(Filters.eq(MongoFields.ID, key)).first()
        // Removido pelo TTL entre a insercao recusada e a leitura: a chave esta livre.
            ?: return if (tryInsert(reservation)) IdempotencyReservation.Reserved(token) else readExisting(key)
        if (existing.expiresAt().isAfter(now)) return IdempotencyReservation.Existing(existing.toRecord())
        // Vencido e ainda nao coletado pelo TTL: troca condicionada ao documento lido, para nao
        // atropelar quem retomou a chave no meio.
        val replaced = records.replaceOne(
            Filters.and(
                Filters.eq(MongoFields.ID, key),
                Filters.eq(FIELD_EXPIRES_AT, existing[FIELD_EXPIRES_AT]),
                Filters.eq(FIELD_OWNER, existing[FIELD_OWNER])
            ),
            reservation,
        )
        return if (replaced.matchedCount == 1L) IdempotencyReservation.Reserved(token) else readExisting(key)
    }

    /**
     * Conclui a reserva de idempotência gravando o resultado final da operação.
     *
     * ### 1. O que faz
     * Transiciona o registro de `IN_FLIGHT` para `COMPLETED` associando o resultado e novo prazo TTL.
     *
     * ### 2. Para que serve
     * Consolidar o efeito da operação mutante junto à confirmação transacional do MongoDB.
     *
     * ### 3. Como funciona
     * Executa `replaceOne` condicionado à posse do [token], estado `IN_FLIGHT`, operação e fingerprint
     * idênticos. Se nenhum registro for alterado, lança [StoreConflict].
     */
    override fun complete(record: IdempotencyRecord, token: String) {
        val now = clock.instant()
        val result = records.replace(
            sessions,
            Filters.and(
                Filters.eq(MongoFields.ID, record.key),
                Filters.eq(FIELD_OWNER, token),
                Filters.eq(FIELD_STATE, STATE_IN_FLIGHT),
                Filters.gt(FIELD_EXPIRES_AT, Date.from(now)),
                Filters.eq("operation", record.operation),
                Filters.eq("fingerprint", record.fingerprint),
            ),
            document(record, STATE_COMPLETED, now.plus(ttl), token),
            upsert = false,
        )
        if (result.matchedCount == 0L) throw StoreConflict("reserva de idempotencia perdida ou vencida")
    }

    /**
     * Libera antecipadamente uma reserva em voo após falha sem efeitos colaterais.
     *
     * ### 1. O que faz
     * Remove o documento de reserva `IN_FLIGHT` vinculado ao token informado.
     *
     * ### 2. Para que serve
     * Permitir nova tentativa imediata do operador caso a transação tenha falhado antes do commit.
     *
     * ### 3. Como funciona
     * Executa `deleteOne` com filtro de [key], [token] e estado `IN_FLIGHT`.
     */
    override fun release(key: String, token: String) {
        records.deleteOne(
            Filters.and(
                Filters.eq(MongoFields.ID, key),
                Filters.eq(FIELD_OWNER, token),
                Filters.eq(FIELD_STATE, STATE_IN_FLIGHT),
            )
        )
    }

    private fun tryInsert(document: Document): Boolean = try {
        records.insertOne(document)
        true
    } catch (error: MongoWriteException) {
        if (!error.isDuplicateKey()) throw error
        false
    }

    private fun readExisting(key: String): IdempotencyReservation {
        val current = records.find(Filters.eq(MongoFields.ID, key)).first()
            ?: error("registro de idempotencia $key sumiu durante a reserva")
        return IdempotencyReservation.Existing(current.toRecord())
    }

    private fun document(record: IdempotencyRecord, state: String, expiresAt: Instant, token: String): Document =
        Document(MongoFields.ID, record.key)
            .append(MongoFields.FORMAT, FORMAT_VERSION)
            .append(FIELD_OWNER, token)
            .append("operation", record.operation)
            .append("fingerprint", record.fingerprint)
            .append("resultRef", record.resultRef)
            .append(FIELD_STATE, state)
            .append(FIELD_EXPIRES_AT, Date.from(expiresAt))

    private fun Document.toRecord(): IdempotencyRecord = IdempotencyRecord(
        key = getString(MongoFields.ID),
        operation = getString("operation"),
        resultRef = getString("resultRef"),
        fingerprint = getString("fingerprint").orEmpty(),
    )

    private fun Document.expiresAt(): Instant = getDate(FIELD_EXPIRES_AT).toInstant()

    private companion object {
        /** Formato deste documento, versionado a parte de [MongoFields.FORMAT_VERSION]. */
        const val FORMAT_VERSION: Int = 2
        const val FIELD_OWNER: String = "owner"
        const val FIELD_STATE: String = "state"
        const val FIELD_EXPIRES_AT: String = "expiresAt"
        const val STATE_IN_FLIGHT: String = "IN_FLIGHT"
        const val STATE_COMPLETED: String = "COMPLETED"
    }
}

/**
 * Caixa de saída (outbox) de invalidações de cache no MongoDB (`P10`, `ADR-021`).
 *
 * ### 1. O que faz
 * Implementa a interface [CacheInvalidationOutbox] armazenando intenções de invalidação de cache.
 *
 * ### 2. Para que serve
 * Garantir entrega garantida (at-least-once) de invalidações de cache distribuído em caso de falha de rede
 * ou término anormal de processos entre a gravação do ponteiro e a invalidação do Redis.
 *
 * ### 3. Como funciona
 * O método [record] grava o evento na mesma transação multi-documento que comita a movimentação do
 * ponteiro. O relay em segundo plano consulta eventos pendentes com [pending] e remove via [markApplied]
 * somente após confirmar a execução bem-sucedida da invalidação nos caches.
 */
class MongoCacheInvalidationOutbox(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : CacheInvalidationOutbox {
    private val invalidations = database.getCollection(MongoSchema.CACHE_INVALIDATIONS)

    /**
     * Registra uma intenção de invalidação de cache na outbox.
     *
     * ### 1. O que faz
     * Insere o documento de invalidação na coleção do MongoDB utilizando a sessão corrente.
     *
     * ### 2. Para que serve
     * Vincular atomicamente a intenção de limpar o cache ao commit da publicação ou rollback.
     *
     * ### 3. Como funciona
     * Serializa a entidade [CacheInvalidation] em JSON e insere na coleção `cache_invalidations`.
     */
    override fun record(invalidation: CacheInvalidation) {
        invalidations.insert(
            sessions,
            Document(MongoFields.ID, invalidation.id)
                .append("createdAtMillis", invalidation.createdAt.toEpochMilli())
                .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
                .append(MongoFields.JSON, DomainJson.write(invalidation)),
        )
    }

    /**
     * Recupera a lista de invalidações pendentes até o limite informado.
     *
     * ### 1. O que faz
     * Consulta as intenções de invalidação pendentes ordenadas crescentemente por data de criação.
     *
     * ### 2. Para que serve
     * Fornecer ao relay assíncrono o lote de tarefas de limpeza de cache a processar.
     *
     * ### 3. Como funciona
     * Exige `limit >= 0`. Se zero, devolve lista vazia. Caso contrário, busca na coleção ordenando
     * por `createdAtMillis` ascendente até atingir [limit].
     */
    override fun pending(limit: Int): List<CacheInvalidation> {
        // No driver, limit 0 quer dizer sem limite. Aqui quer dizer nenhum, como no adapter em memoria.
        require(limit >= 0) { "limit deve ser >= 0" }
        if (limit == 0) return emptyList()
        return invalidations.allMatching(sessions, Filters.empty(), Sorts.ascending("createdAtMillis"), limit = limit)
            .map { DomainJson.read(it.getString(MongoFields.JSON), CacheInvalidation::class.java) }
    }

    /**
     * Marca uma invalidação de cache como concluída, removendo-a da outbox.
     *
     * ### 1. O que faz
     * Remove o documento da outbox pelo seu identificador único [id].
     *
     * ### 2. Para que serve
     * Evitar reprocessamento de invalidações que já foram aplicadas aos caches com sucesso.
     *
     * ### 3. Como funciona
     * Aciona `deleteOne` filtrando por [MongoFields.ID].
     */
    override fun markApplied(id: String) {
        invalidations.remove(sessions, Filters.eq(MongoFields.ID, id))
    }
}
