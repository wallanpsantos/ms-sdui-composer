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
 * A sessao transacional da thread corrente, quando ha uma.
 *
 * Os adapters consultam este contexto em cada operacao: dentro de [MongoTransactionalUnitOfWork]
 * tudo vai na mesma sessao e commita junto; fora dela, cada operacao e independente. ThreadLocal
 * basta porque a unidade de trabalho executa o bloco inteiro na thread que a abriu.
 */
class MongoSessionContext {
    private val current = ThreadLocal<ClientSession?>()

    fun current(): ClientSession? = current.get()

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
 * Transacao programatica do MongoDB para a governanca (ADR-013, ADR-021).
 *
 * Efeito, auditoria, fecho da chave de idempotencia e registro da invalidacao de cache commitam
 * juntos ou nao commitam. Exige topologia com transacao multi-documento (replica set ou cluster
 * shardeado); standalone recusa, e o health indicator acusa.
 *
 * Nao usa `ClientSession.withTransaction`: aquele helper repete a transacao por ate dois minutos
 * em erro transitorio, e o servico nao faz retry de dependencia (ADR-014). Erro transitorio vira
 * [StoreConflict] (409) e o operador reenvia com a mesma Idempotency-Key. Se o commit falhar com
 * resultado desconhecido e tiver acontecido, o registro de idempotencia ja esta fechado no banco e
 * o reenvio recebe o replay.
 */
class MongoTransactionalUnitOfWork(
    private val client: MongoClient,
    private val sessions: MongoSessionContext,
) : TransactionalUnitOfWork {
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

    private fun abortQuietly(session: ClientSession) {
        if (!session.hasActiveTransaction()) return
        runCatching { session.abortTransaction() }
    }
}

/** Nomes das colecoes e a criacao idempotente dos indices que as consultas dos adapters usam. */
object MongoSchema {
    const val SPECS: String = "specs"
    const val SKELETONS: String = "skeletons"
    const val CATALOG: String = "catalog"
    const val POINTERS: String = "pointers"
    const val PUBLISH_REQUESTS: String = "publish_requests"
    const val DIFFS: String = "diffs"
    const val AUDIT_EVENTS: String = "audit_events"
    const val IDEMPOTENCY: String = "idempotency"
    const val CACHE_INVALIDATIONS: String = "cache_invalidations"

    /**
     * Cria os indices se faltarem. `createIndex` com a mesma definicao e no-op, entao roda a cada
     * subida sem custo relevante e sem depender de ferramenta de migracao para o caso comum.
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

/** Campos de controle comuns aos documentos de governanca. */
internal object MongoFields {
    const val ID: String = "_id"
    const val FORMAT: String = "_v"
    const val JSON: String = "json"

    /** Versao do formato do documento. Leitura aceita esta e as anteriores (expand/contract). */
    const val FORMAT_VERSION: Int = 1
}

/**
 * Valida o tamanho do documento serializado antes de gravar. O limite duro do MongoDB e 16 MiB;
 * o teto configurado e menor para manter listagens e selecao previsiveis.
 */
internal fun boundedPayload(json: String, maxBytes: Int, what: String): String {
    val size = json.toByteArray(Charsets.UTF_8).size
    if (size > maxBytes) throw StoreRejected("$what tem $size bytes; o teto de armazenamento e $maxBytes")
    return json
}

internal fun MongoWriteException.isDuplicateKey(): Boolean = error.category == ErrorCategory.DUPLICATE_KEY

internal fun MongoCollection<Document>.firstMatch(
    sessions: MongoSessionContext,
    filter: Bson,
    sort: Bson? = null,
): Document? {
    val session = sessions.current()
    val iterable = if (session != null) find(session, filter) else find(filter)
    return (if (sort != null) iterable.sort(sort) else iterable).first()
}

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

internal fun MongoCollection<Document>.insert(sessions: MongoSessionContext, document: Document) {
    val session = sessions.current()
    if (session != null) insertOne(session, document) else insertOne(document)
}

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

internal fun MongoCollection<Document>.remove(sessions: MongoSessionContext, filter: Bson): DeleteResult {
    val session = sessions.current()
    return if (session != null) deleteOne(session, filter) else deleteOne(filter)
}
