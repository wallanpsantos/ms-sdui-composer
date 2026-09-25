package br.com.empresa.sdui.adapters.mongo

import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import br.com.empresa.sdui.orchestrator.port.outbound.StoreRejected
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import com.mongodb.ErrorCategory
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.TransactionOptions
import com.mongodb.WriteConcern
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.FindOneAndReplaceOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

/**
 * Mantém a referência da sessão transacional ativa do MongoDB vinculada à thread corrente.
 *
 * ### 1. O que faz
 * Armazena e gerencia o ciclo de vida da [ClientSession] associada à thread de execução atual.
 *
 * ### 2. Para que serve
 * Permite que os repositórios de governança detectem se uma operação deve ingressar na transação
 * multi-documento aberta por [MongoTransactionalUnitOfWork] ou se deve rodar de forma avulsa.
 *
 * ### 3. Como funciona
 * Encapsula uma variável [ThreadLocal] de [ClientSession]. Quando uma unidade de trabalho inicia,
 * ela associa a sessão através de [bound]; ao término, o contexto é restaurado para evitar vazamentos.
 */
class MongoSessionContext {
    private val current = ThreadLocal<ClientSession?>()

    /**
     * Recupera a sessão transacional do MongoDB vinculada à thread atual.
     *
     * ### 1. O que faz
     * Devolve a [ClientSession] em vigor ou `null` se não houver transação ativa na thread.
     *
     * ### 2. Para que serve
     * Permite que as operações de leitura e gravação decidam se utilizam uma transação do MongoDB.
     *
     * ### 3. Como funciona
     * Consulta o valor presente no [ThreadLocal] privado da classe.
     */
    fun current(): ClientSession? = current.get()

    /**
     * Vincula temporariamente uma sessão à thread durante a execução de um bloco de código.
     *
     * ### 1. O que faz
     * Registra a sessão transacional na thread corrente e executa a função lambda fornecida.
     *
     * ### 2. Para que serve
     * Garante que todas as chamadas a repositórios dentro do bloco executem sob a mesma transação.
     *
     * ### 3. Como funciona
     * Seta a sessão no [ThreadLocal], invoca [block] em bloco `try` e limpa o valor no `finally`.
     */
    internal fun <T> bound(session: ClientSession, block: () -> T): T {
        current.set(session)
        try {
            return block()
        } finally {
            current.remove()
        }
    }
}

/**
 * Transação programática do MongoDB para governança (`ADR-013`, `ADR-021`).
 *
 * ### 1. O que faz
 * Demarca e executa uma unidade de trabalho com garantia transacional ACID sobre coleções do MongoDB.
 *
 * ### 2. Para que serve
 * Garante que alterações em specs, skeletons, ponteiros, registros de auditoria e de idempotência
 * sejam commitados atomicamente ou integralmente abortados, preservando a consistência do sistema.
 *
 * ### 3. Como funciona
 * Cria uma [ClientSession] com `ReadConcern.SNAPSHOT` e `WriteConcern.MAJORITY`, vinculando-a ao
 * [MongoSessionContext]. Em caso de concorrência com erro transiente, converte para [StoreConflict]
 * (HTTP 409) sem retry embutido no servidor (`ADR-014`), delegando a repetição ao operador.
 */
class MongoTransactionalUnitOfWork(
    private val client: MongoClient,
    private val sessions: MongoSessionContext,
) : TransactionalUnitOfWork {
    /**
     * Executa o trabalho fornecido dentro de uma transação do MongoDB.
     *
     * ### 1. O que faz
     * Coordena a abertura, execução e confirmação da transação multi-documento.
     *
     * ### 2. Para que serve
     * Prover ponto único de demarcação transacional para as operações de escrita de governança.
     *
     * ### 3. Como funciona
     * Se já houver transação na thread, reusa-a. Caso contrário, inicia sessão e transação, liga no
     * [sessions], executa [work], faz commit e traduz exceções transientes para [StoreConflict].
     */
    override fun <T : Any> execute(work: () -> T): T {
        if (sessions.current() != null) return work()
        client.startSession().use { session ->
            session.startTransaction(
                TransactionOptions.builder()
                    .readConcern(ReadConcern.SNAPSHOT)
                    .writeConcern(WriteConcern.MAJORITY)
                    .build(),
            )
            try {
                val result = sessions.bound(session, work)
                session.commitTransaction()
                return result
            } catch (error: MongoException) {
                abortQuietly(session)
                if (error.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    throw StoreConflict("transacao concorrente no MongoDB (codigo ${error.code})")
                }
                throw error
            } catch (error: Throwable) {
                abortQuietly(session)
                throw error
            }
        }
    }

    /**
     * Aborta silenciosamente a transação se ainda estiver ativa.
     *
     * ### 1. O que faz
     * Solicita o cancelamento da transação pendente sem propagar novas exceções de falha de abort.
     *
     * ### 2. Para que serve
     * Evitar que exceções secundárias mascarem o erro primário causador do descarte da transação.
     *
     * ### 3. Como funciona
     * Confere se a [session] possui transação em aberto e invoca `abortTransaction` sob `runCatching`.
     */
    private fun abortQuietly(session: ClientSession) {
        if (!session.hasActiveTransaction()) return
        runCatching { session.abortTransaction() }
    }
}

/**
 * Nomes canônicos de coleções e criação idempotente de índices no MongoDB.
 *
 * ### 1. O que faz
 * Centraliza as constantes de nomes de coleções e define os índices necessários para a governança.
 *
 * ### 2. Para que serve
 * Assegurar unicidade de chaves, performance em listagens e expiração automática por TTL no MongoDB.
 *
 * ### 3. Como funciona
 * Expõe constantes de identificação de coleções e o método [ensureIndexes], que aplica definições
 * de índices idempotentes na inicialização sem necessitar de migrações externas para o caso padrão.
 */
object MongoSchema {
    /** Coleção de especificações de telas (specs). */
    const val SPECS: String = "specs"

    /** Coleção de skeletons estruturais de superfícies. */
    const val SKELETONS: String = "skeletons"

    /** Coleção do catálogo de componentes e capabilities ativas. */
    const val CATALOG: String = "catalog"

    /** Coleção de ponteiros ativos de publicação por superfície e plataforma. */
    const val POINTERS: String = "pointers"

    /** Coleção de pedidos formais de publicação (fluxo maker-checker). */
    const val PUBLISH_REQUESTS: String = "publish_requests"

    /** Coleção de diffs semânticos pré-calculados entre revisões de specs. */
    const val DIFFS: String = "diffs"

    /** Coleção append-only de eventos de auditoria de governança. */
    const val AUDIT_EVENTS: String = "audit_events"

    /** Coleção de reservas e controles de idempotência de operações. */
    const val IDEMPOTENCY: String = "idempotency"

    /** Coleção outbox de intenções de invalidação de cache distribuído. */
    const val CACHE_INVALIDATIONS: String = "cache_invalidations"

    /**
     * Cria ou assegura a existência dos índices em todas as coleções do MongoDB.
     *
     * ### 1. O que faz
     * Executa a criação de índices compostos, únicos e TTL nas coleções de governança e coordenação.
     *
     * ### 2. Para que serve
     * Garantir integridade de unicidade, queries rápidas e expiração automática sem bloquear inicializações.
     *
     * ### 3. Como funciona
     * Aciona `createIndex` no [database] para cada coleção; operações já existentes operam como no-op.
     */
    fun ensureIndexes(database: MongoDatabase) {
        val specs = database.getCollection(SPECS)
        specs.createIndex(Indexes.ascending("specRevisionId"), IndexOptions().unique(true).name("ux_spec_revision"))
        specs.createIndex(Indexes.ascending("surface", "platform", "status"), IndexOptions().name("ix_spec_published"))
        specs.createIndex(Indexes.ascending("specId", "revision"), IndexOptions().name("ix_spec_revisions"))
        database.getCollection(SKELETONS)
            .createIndex(Indexes.compoundIndex(Indexes.ascending("skeletonId"), Indexes.descending("revision")))
        database.getCollection(AUDIT_EVENTS)
            .createIndex(Indexes.descending("tsMillis"), IndexOptions().name("ix_audit_ts"))
        // TTL: o proprio MongoDB remove reserva abandonada e resultado fora da janela. Nenhuma
        // reserva viva e removida por pressao — o documento so sai quando expiresAt passa.
        database.getCollection(IDEMPOTENCY)
            .createIndex(
                Indexes.ascending("expiresAt"),
                IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("ttl_idempotency")
            )
        database.getCollection(CACHE_INVALIDATIONS)
            .createIndex(Indexes.ascending("createdAtMillis"), IndexOptions().name("ix_invalidation_created"))
    }
}

/**
 * Nomes de campos de controle padrão nos documentos BSON de governança.
 *
 * ### 1. O que faz
 * Define as constantes estruturais presentes no cabeçalho e corpo dos documentos no MongoDB.
 *
 * ### 2. Para que serve
 * Padronizar a identificação primária, versionamento de formato e carga útil dos registros persistidos.
 *
 * ### 3. Como funciona
 * Disponibiliza constantes para o identificador `_id`, versão de formato `_v` e carga serializada `json`.
 */
internal object MongoFields {
    /** Identificador primário do documento BSON. */
    const val ID: String = "_id"

    /** Campo que indica a versão do schema de armazenamento do documento. */
    const val FORMAT: String = "_v"

    /** Campo que armazena a representação JSON serializada do domínio. */
    const val JSON: String = "json"

    /** Versão atual do formato de documento, viabilizando estratégias de expand/contract. */
    const val FORMAT_VERSION: Int = 1
}

/**
 * Valida o tamanho do documento JSON antes de enviá-lo para gravação no MongoDB.
 *
 * ### 1. O que faz
 * Confere se a quantidade de bytes UTF-8 da string JSON respeita o teto máximo permitido.
 *
 * ### 2. Para que serve
 * Evitar sobrecarga de rede, estouro de heap em listagens e aproximação perigosa do limite do BSON.
 *
 * ### 3. Como funciona
 * Mede o array de bytes do [json]; se exceder [maxBytes], dispara [StoreRejected].
 */
internal fun boundedPayload(json: String, maxBytes: Int, what: String): String {
    val size = json.toByteArray(Charsets.UTF_8).size
    if (size > maxBytes) throw StoreRejected("$what tem $size bytes; o teto de armazenamento e $maxBytes")
    return json
}

/**
 * Identifica se a exceção de escrita no MongoDB decorreu de chave duplicada.
 *
 * ### 1. O que faz
 * Avalia se uma [MongoWriteException] representa violação de unicidade de chave ou índice.
 *
 * ### 2. Para que serve
 * Discriminar colisões de concorrência ou duplicações propositais de falhas gerais de I/O.
 *
 * ### 3. Como funciona
 * Compara a categoria do erro com [ErrorCategory.DUPLICATE_KEY].
 */
internal fun MongoWriteException.isDuplicateKey(): Boolean = error.category == ErrorCategory.DUPLICATE_KEY

/**
 * Localiza o primeiro documento que satisfaz o filtro fornecido.
 *
 * ### 1. O que faz
 * Executa uma busca pontual retornando o primeiro documento encontrado.
 *
 * ### 2. Para que serve
 * Recuperar registros por chave ou critérios de unicidade considerando a sessão ativa.
 *
 * ### 3. Como funciona
 * Consulta a coleção vinculando a sessão de [sessions] se presente, aplicando ordenação se informada.
 */
internal fun MongoCollection<Document>.firstMatch(
    sessions: MongoSessionContext,
    filter: Bson,
    sort: Bson? = null,
): Document? {
    val session = sessions.current()
    val iterable = if (session != null) find(session, filter) else find(filter)
    return (if (sort != null) iterable.sort(sort) else iterable).first()
}

/**
 * Lista todos os documentos que atendem ao filtro, com paginação e ordenação opcionais.
 *
 * ### 1. O que faz
 * Retorna uma lista de documentos BSON casados com o critério de busca.
 *
 * ### 2. Para que serve
 * Alimentar listagens de specs, eventos de auditoria e itens pendentes de outbox.
 *
 * ### 3. Como funciona
 * Aplica paginação via `skip` e `limit`, associando a [ClientSession] corrente quando presente.
 */
internal fun MongoCollection<Document>.allMatching(
    sessions: MongoSessionContext,
    filter: Bson,
    sort: Bson? = null,
    skip: Int = 0,
    limit: Int = 0,
): List<Document> {
    val session = sessions.current()
    var iterable = if (session != null) find(session, filter) else find(filter)
    if (sort != null) iterable = iterable.sort(sort)
    if (skip > 0) iterable = iterable.skip(skip)
    if (limit > 0) iterable = iterable.limit(limit)
    return iterable.into(ArrayList())
}

/**
 * Substitui um documento existente ou insere um novo conforme opção de upsert.
 *
 * ### 1. O que faz
 * Executa `replaceOne` no MongoDB associando a sessão transacional se disponível.
 *
 * ### 2. Para que serve
 * Atualizar integralmente entidades de domínio ou gravá-las caso ainda não existam.
 *
 * ### 3. Como funciona
 * Constrói as [ReplaceOptions] com [upsert] e aciona `replaceOne` com ou sem sessão ativa.
 */
internal fun MongoCollection<Document>.replace(
    sessions: MongoSessionContext,
    filter: Bson,
    document: Document,
    upsert: Boolean,
): UpdateResult {
    val options = ReplaceOptions().upsert(upsert)
    val session = sessions.current()
    return if (session != null) replaceOne(session, filter, document, options) else replaceOne(
        filter,
        document,
        options
    )
}

/**
 * Insere um novo documento na coleção.
 *
 * ### 1. O que faz
 * Executa a inserção simples de um documento BSON.
 *
 * ### 2. Para que serve
 * Persistir novas entidades de governança, auditoria ou reservas de idempotência.
 *
 * ### 3. Como funciona
 * Direciona para `insertOne` anexando a sessão corrente obtida de [sessions], se presente.
 */
internal fun MongoCollection<Document>.insert(sessions: MongoSessionContext, document: Document) {
    val session = sessions.current()
    if (session != null) insertOne(session, document) else insertOne(document)
}

/**
 * Localiza e substitui atomicamente um documento, retornando a versão atualizada.
 *
 * ### 1. O que faz
 * Executa uma operação atômica de find-and-replace no MongoDB.
 *
 * ### 2. Para que serve
 * Efetuar transições atômicas de status, como em pedidos de publicação, sem corridas de leitura/escrita.
 *
 * ### 3. Como funciona
 * Configura [ReturnDocument.AFTER] e aciona `findOneAndReplace` na sessão ativa ou standalone.
 */
internal fun MongoCollection<Document>.findAndReplace(
    sessions: MongoSessionContext,
    filter: Bson,
    document: Document,
): Document? {
    val options = FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)
    val session = sessions.current()
    return if (session != null) {
        findOneAndReplace(session, filter, document, options)
    } else {
        findOneAndReplace(filter, document, options)
    }
}

/**
 * Remove um documento correspondente ao filtro especificado.
 *
 * ### 1. O que faz
 * Executa `deleteOne` para apagar um único registro.
 *
 * ### 2. Para que serve
 * Excluir reservas de idempotência canceladas ou itens de outbox já processados com sucesso.
 *
 * ### 3. Como funciona
 * Executa `deleteOne` utilizando a sessão transacional ativa de [sessions], se houver.
 */
internal fun MongoCollection<Document>.remove(sessions: MongoSessionContext, filter: Bson): DeleteResult {
    val session = sessions.current()
    return if (session != null) deleteOne(session, filter) else deleteOne(filter)
}
