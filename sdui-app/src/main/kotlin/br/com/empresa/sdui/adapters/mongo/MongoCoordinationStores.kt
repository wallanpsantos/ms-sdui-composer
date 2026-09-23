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
 * Registro de idempotencia no MongoDB (P06, ADR-021).
 *
 * A reserva e um `insertOne` sobre o `_id` = chave: o indice primario garante que so uma chamada
 * vence, entre instancias e entre restarts. Quem perde le o registro existente e decide entre
 * replay e recusa. Nao ha teto de entradas — a colecao nao disputa o heap — e nada vivo sai por
 * pressao: o indice TTL em `expiresAt` remove so reserva abandonada (depois do prazo de reserva) e
 * resultado fora da janela de deduplicacao.
 *
 * [complete] grava na sessao da transacao corrente: o fecho da chave commita junto com o efeito.
 * [reserve] e [release] rodam fora dela, porque a reserva precisa valer para as outras chamadas
 * antes do commit e o release acontece depois de a transacao ja ter sido abortada.
 */
class MongoIdempotencyStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val clock: Clock,
    private val ttl: Duration,
    private val reservationTimeout: Duration,
) : IdempotencyStore {
    private val records = database.getCollection(MongoSchema.IDEMPOTENCY)

    override fun find(key: String): IdempotencyRecord? =
        records.firstMatch(sessions, Filters.eq(MongoFields.ID, key))
            ?.takeIf { it.expiresAt().isAfter(clock.instant()) }
            ?.toRecord()

    override fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation {
        val now = clock.instant()
        val token = UUID.randomUUID().toString()
        val reservation = document(IdempotencyRecord(key, operation, null, fingerprint), STATE_IN_FLIGHT, now.plus(reservationTimeout), token)
        if (tryInsert(reservation)) return IdempotencyReservation.Reserved(token)
        val existing = records.find(Filters.eq(MongoFields.ID, key)).first()
            // Removido pelo TTL entre a insercao recusada e a leitura: a chave esta livre.
            ?: return if (tryInsert(reservation)) IdempotencyReservation.Reserved(token) else readExisting(key)
        if (existing.expiresAt().isAfter(now)) return IdempotencyReservation.Existing(existing.toRecord())
        // Vencido e ainda nao coletado pelo TTL: troca condicionada ao documento lido, para nao
        // atropelar quem retomou a chave no meio.
        val replaced = records.replaceOne(
            Filters.and(Filters.eq(MongoFields.ID, key), Filters.eq(FIELD_EXPIRES_AT, existing[FIELD_EXPIRES_AT]), Filters.eq(FIELD_OWNER, existing[FIELD_OWNER])),
            reservation,
        )
        return if (replaced.matchedCount == 1L) IdempotencyReservation.Reserved(token) else readExisting(key)
    }

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

    override fun release(key: String, token: String) {
        records.deleteOne(Filters.and(
            Filters.eq(MongoFields.ID, key),
            Filters.eq(FIELD_OWNER, token),
            Filters.eq(FIELD_STATE, STATE_IN_FLIGHT),
        ))
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
            .append(MongoFields.FORMAT, 2)
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
        const val FIELD_OWNER: String = "owner"
        const val FIELD_STATE: String = "state"
        const val FIELD_EXPIRES_AT: String = "expiresAt"
        const val STATE_IN_FLIGHT: String = "IN_FLIGHT"
        const val STATE_COMPLETED: String = "COMPLETED"
    }
}

/**
 * Outbox de invalidacao de cache no MongoDB (P10, ADR-021).
 *
 * [record] grava na mesma transacao que move o pointer. Um registro so sai quando a invalidacao
 * foi aplicada; se o processo cair entre o commit e a invalidacao, o relay de qualquer instancia
 * encontra o registro e o aplica — a aplicacao e idempotente.
 */
class MongoCacheInvalidationOutbox(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : CacheInvalidationOutbox {
    private val invalidations = database.getCollection(MongoSchema.CACHE_INVALIDATIONS)

    override fun record(invalidation: CacheInvalidation) {
        invalidations.insert(
            sessions,
            Document(MongoFields.ID, invalidation.id)
                .append("createdAtMillis", invalidation.createdAt.toEpochMilli())
                .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
                .append(MongoFields.JSON, DomainJson.write(invalidation)),
        )
    }

    override fun pending(limit: Int): List<CacheInvalidation> =
        invalidations.allMatching(sessions, Filters.empty(), Sorts.ascending("createdAtMillis"), limit = limit)
            .map { DomainJson.read(it.getString(MongoFields.JSON), CacheInvalidation::class.java) }

    override fun markApplied(id: String) {
        invalidations.remove(sessions, Filters.eq(MongoFields.ID, id))
    }
}
