package br.com.empresa.sdui.adapters.mongo

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.bson.conversions.Bson

/*
 * Adapters MongoDB da governanca (ADR-021).
 *
 * Cada documento guarda o objeto de dominio serializado em `json` (formato de [DomainJson]) e, ao
 * lado, apenas os campos que alguma consulta filtra ou ordena — sao eles que recebem indice. O
 * conteudo livre das props nunca vira nome de campo no banco: chave de prop com `.` ou `$` nao tem
 * como colidir com a sintaxe de consulta, e o formato gravado nao depende das regras de nomes do
 * MongoDB.
 */

private fun Document.json(): String = getString(MongoFields.JSON)

/** Specs no MongoDB. Revisao PUBLISHED e imutavel: o filtro de gravacao nao casa com ela. */
class MongoSpecStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : SpecStore {
    private val specs = database.getCollection(MongoSchema.SPECS)

    override fun save(spec: Spec): Spec {
        val key = "${spec.specId}#${spec.revision}"
        val document = documentFor(spec, key)
        try {
            // Upsert condicionado a nao estar publicada: sobre uma PUBLISHED o filtro nao casa, o
            // upsert tenta inserir o mesmo _id e o indice primario recusa.
            specs.replace(
                sessions,
                Filters.and(Filters.eq(MongoFields.ID, key), Filters.ne("status", SpecStatus.PUBLISHED.name)),
                document,
                upsert = true,
            )
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) {
                throw StoreConflict("spec PUBLISHED e imutavel ou specRevisionId ja usado: $key")
            }
            throw error
        }
        return spec
    }

    override fun compareAndSet(expected: Spec?, updated: Spec): Spec {
        val key = "${updated.specId}#${updated.revision}"
        if (expected != null && (expected.specId != updated.specId || expected.revision != updated.revision || expected.status == SpecStatus.PUBLISHED)) {
            throw StoreConflict("revisao PUBLISHED e imutavel ou identidade divergente")
        }
        val document = documentFor(updated, key)
        try {
            if (expected == null) {
                specs.insert(sessions, document)
            } else {
                val changed = specs.replace(
                    sessions,
                    Filters.and(Filters.eq(MongoFields.ID, key), Filters.eq(MongoFields.JSON, DomainJson.write(expected))),
                    document,
                    upsert = false,
                )
                if (changed.matchedCount == 0L) throw StoreConflict("rascunho mudou desde a leitura")
            }
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("identidade de revisao ja usada")
            throw error
        }
        return updated
    }

    private fun documentFor(updated: Spec, key: String): Document {
        return Document(MongoFields.ID, key)
            .append("specId", updated.specId)
            .append("revision", updated.revision)
            .append("specRevisionId", updated.specRevisionId)
            .append("surface", updated.surface)
            .append("platform", updated.platform.name)
            .append("channel", updated.channel.name)
            .append("status", updated.status.name)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(updated), maxDocumentBytes, "spec $key"))
    }

    override fun findByRevisionId(specRevisionId: String): Spec? =
        specs.firstMatch(sessions, Filters.eq("specRevisionId", specRevisionId))?.toSpec()

    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? =
        specs.firstMatch(sessions, Filters.eq(MongoFields.ID, "$specId#$revision"))?.toSpec()

    override fun listBySpecId(specId: String): List<Spec> =
        specs.allMatching(sessions, Filters.eq("specId", specId), Sorts.ascending("revision")).map { it.toSpec() }

    override fun listBySpecId(specId: String, page: PageRequest): List<Spec> =
        specs.allMatching(
            sessions,
            Filters.eq("specId", specId),
            Sorts.ascending("revision"),
            skip = page.offset,
            limit = page.limit,
        ).map { it.toSpec() }

    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        specs.allMatching(
            sessions,
            Filters.and(
                Filters.eq("surface", surface),
                Filters.eq("platform", platform.name),
                Filters.eq("status", SpecStatus.PUBLISHED.name),
            ),
        ).map { it.toSpec() }

    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> =
        specs.allMatching(sessions, filterOf(platform, channel), Sorts.ascending("specId", "revision"))
            .map { it.toSpec() }

    override fun list(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        specs.allMatching(
            sessions,
            filterOf(platform, channel),
            Sorts.ascending("specId", "revision"),
            skip = page.offset,
            limit = page.limit,
        ).map { it.toSpec() }

    override fun nextRevision(specId: String): Int {
        val last = specs.firstMatch(sessions, Filters.eq("specId", specId), Sorts.descending("revision"))
            ?.getInteger("revision") ?: 0
        if (last == Int.MAX_VALUE) throw StoreConflict("limite de revisoes atingido")
        return last + 1
    }

    private fun filterOf(platform: ClientPlatform?, channel: Channel?): Bson {
        val filters = listOfNotNull(
            platform?.let { Filters.eq("platform", it.name) },
            channel?.let { Filters.eq("channel", it.name) },
        )
        return if (filters.isEmpty()) Filters.empty() else Filters.and(filters)
    }

    private fun Document.toSpec(): Spec = DomainJson.read(json(), Spec::class.java)
}

/** Skeletons no MongoDB, com a mesma protecao de imutabilidade apos publicacao. */
class MongoSkeletonStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : SkeletonStore {
    private val skeletons = database.getCollection(MongoSchema.SKELETONS)

    override fun save(skeleton: Skeleton): Skeleton {
        val key = "${skeleton.skeletonId}#${skeleton.revision}"
        val document = documentFor(skeleton, key)
        try {
            skeletons.replace(
                sessions,
                Filters.and(Filters.eq(MongoFields.ID, key), Filters.ne("status", SpecStatus.PUBLISHED.name)),
                document,
                upsert = true,
            )
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("skeleton PUBLISHED e imutavel: $key")
            throw error
        }
        return skeleton
    }

    override fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton {
        val key = "${updated.skeletonId}#${updated.revision}"
        if (expected != null && (expected.skeletonId != updated.skeletonId || expected.revision != updated.revision || expected.status == SpecStatus.PUBLISHED)) {
            throw StoreConflict("revisao PUBLISHED e imutavel ou identidade divergente")
        }
        val document = documentFor(updated, key)
        try {
            if (expected == null) {
                skeletons.insert(sessions, document)
            } else {
                val changed = skeletons.replace(
                    sessions,
                    Filters.and(Filters.eq(MongoFields.ID, key), Filters.eq(MongoFields.JSON, DomainJson.write(expected))),
                    document,
                    upsert = false,
                )
                if (changed.matchedCount == 0L) throw StoreConflict("rascunho mudou desde a leitura")
            }
        } catch (error: MongoWriteException) {
            if (error.isDuplicateKey()) throw StoreConflict("identidade de revisao ja usada")
            throw error
        }
        return updated
    }

    private fun documentFor(updated: Skeleton, key: String): Document {
        return Document(MongoFields.ID, key)
            .append("skeletonId", updated.skeletonId)
            .append("revision", updated.revision)
            .append("surface", updated.surface)
            .append("status", updated.status.name)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(updated), maxDocumentBytes, "skeleton $key"))
    }

    override fun find(skeletonId: String, revision: Int?): Skeleton? {
        if (revision == null) return current(skeletonId)
        return skeletons.firstMatch(sessions, Filters.eq(MongoFields.ID, "$skeletonId#$revision"))?.toSkeleton()
    }

    override fun current(skeletonId: String): Skeleton? =
        skeletons.firstMatch(sessions, Filters.eq("skeletonId", skeletonId), Sorts.descending("revision"))
            ?.toSkeleton()

    private fun Document.toSkeleton(): Skeleton = DomainJson.read(json(), Skeleton::class.java)
}

/**
 * Catalogo no MongoDB: um documento so, substituido inteiro. A validacao do conjunto acontece no
 * orchestrator antes da gravacao; duas gravacoes simultaneas terminam na ultima.
 */
class MongoCatalogStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : CatalogStore {
    private val catalog = database.getCollection(MongoSchema.CATALOG)

    override fun save(catalog: Catalog): Catalog {
        val document = Document(MongoFields.ID, CURRENT)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(catalog), maxDocumentBytes, "catalogo"))
        this.catalog.replace(sessions, Filters.eq(MongoFields.ID, CURRENT), document, upsert = true)
        return catalog
    }

    override fun current(): Catalog =
        catalog.firstMatch(sessions, Filters.eq(MongoFields.ID, CURRENT))
            ?.let { DomainJson.read(it.json(), Catalog::class.java) }
            ?: Catalog(emptyList())

    private companion object {
        const val CURRENT: String = "current"
    }
}

/** Pointers no MongoDB, com compare-and-set pela versao gravada no proprio documento. */
class MongoPointerStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : PointerStore {
    private val pointers = database.getCollection(MongoSchema.POINTERS)

    override fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer? =
        pointers.firstMatch(sessions, Filters.eq(MongoFields.ID, key(surface, platform, channel)))
            ?.let { DomainJson.read(it.json(), Pointer::class.java) }

    override fun save(pointer: Pointer): Pointer {
        pointers.replace(sessions, Filters.eq(MongoFields.ID, key(pointer)), document(pointer), upsert = true)
        return pointer
    }

    override fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer {
        val key = key(updated)
        if (expectedVersion == null) {
            try {
                pointers.insert(sessions, document(updated))
            } catch (error: MongoWriteException) {
                if (error.isDuplicateKey()) throw StoreConflict("pointer $key ja existe")
                throw error
            }
            return updated
        }
        val result = pointers.replace(
            sessions,
            Filters.and(Filters.eq(MongoFields.ID, key), Filters.eq("version", expectedVersion)),
            document(updated),
            upsert = false,
        )
        if (result.matchedCount == 0L) {
            throw StoreConflict("pointer $key mudou desde a leitura (esperado v$expectedVersion)")
        }
        return updated
    }

    private fun document(pointer: Pointer): Document = Document(MongoFields.ID, key(pointer))
        .append("surface", pointer.surface)
        .append("platform", pointer.platform.name)
        .append("channel", pointer.channel.name)
        .append("version", pointer.version)
        .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
        .append(MongoFields.JSON, DomainJson.write(pointer))

    private fun key(pointer: Pointer): String = key(pointer.surface, pointer.platform, pointer.channel)

    private fun key(surface: String, platform: ClientPlatform, channel: Channel): String =
        "$surface:${platform.wire()}:${channel.wire()}"
}

/** Pedidos de publicacao no MongoDB. A transicao de status e um findOneAndReplace condicionado. */
class MongoPublishRequestStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : PublishRequestStore {
    private val requests = database.getCollection(MongoSchema.PUBLISH_REQUESTS)

    override fun save(request: PublishRequest): PublishRequest {
        requests.replace(sessions, Filters.eq(MongoFields.ID, request.requestId), document(request), upsert = true)
        return request
    }

    override fun find(requestId: String): PublishRequest? =
        requests.firstMatch(sessions, Filters.eq(MongoFields.ID, requestId))
            ?.let { DomainJson.read(it.json(), PublishRequest::class.java) }

    override fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest,
    ): PublishRequest? {
        val replaced = requests.findAndReplace(
            sessions,
            Filters.and(Filters.eq(MongoFields.ID, requestId), Filters.eq("status", expected.name)),
            document(updated),
        )
        return replaced?.let { updated }
    }

    private fun document(request: PublishRequest): Document = Document(MongoFields.ID, request.requestId)
        .append("status", request.status.name)
        .append("specRevisionId", request.specRevisionId)
        .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
        .append(MongoFields.JSON, DomainJson.write(request))
}

/** Diffs no MongoDB, por spec e par de revisoes. */
class MongoDiffStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
    private val maxDocumentBytes: Int,
) : DiffStore {
    private val diffs = database.getCollection(MongoSchema.DIFFS)

    override fun save(diff: SpecDiff): SpecDiff {
        val key = key(diff.specId, diff.fromRevision ?: 0, diff.toRevision)
        val document = Document(MongoFields.ID, key)
            .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
            .append(MongoFields.JSON, boundedPayload(DomainJson.write(diff), maxDocumentBytes, "diff $key"))
        diffs.replace(sessions, Filters.eq(MongoFields.ID, key), document, upsert = true)
        return diff
    }

    override fun find(specId: String, from: Int, to: Int): SpecDiff? =
        diffs.firstMatch(sessions, Filters.eq(MongoFields.ID, key(specId, from, to)))
            ?.let { DomainJson.read(it.json(), SpecDiff::class.java) }

    private fun key(specId: String, from: Int, to: Int): String = "$specId:$from:$to"
}

/**
 * Trilha de auditoria no MongoDB: so insercao, consulta paginada pelos mais recentes. Nao ha poda
 * automatica — retencao de auditoria e decisao de governanca, nao de memoria (ADR-021).
 */
class MongoAuditLogStore(
    database: MongoDatabase,
    private val sessions: MongoSessionContext,
) : AuditLogStore {
    private val events = database.getCollection(MongoSchema.AUDIT_EVENTS)

    override fun append(event: AuditEvent) {
        events.insert(
            sessions,
            Document(MongoFields.ID, event.id)
                .append("tsMillis", event.ts.toEpochMilli())
                .append("action", event.action)
                .append("surface", event.surface)
                .append("platform", event.platform.name)
                .append("channel", event.channel.name)
                .append("requestId", event.requestId)
                .append(MongoFields.FORMAT, MongoFields.FORMAT_VERSION)
                .append(MongoFields.JSON, DomainJson.write(event)),
        )
    }

    /** Os mais recentes ate o teto de listagem, em ordem cronologica; nunca a colecao inteira. */
    override fun list(): List<AuditEvent> = recent(PageRequest.MAX_LIMIT).asReversed()

    override fun recent(limit: Int): List<AuditEvent> =
        events.allMatching(sessions, Filters.empty(), Sorts.descending("tsMillis"), limit = limit)
            .map { DomainJson.read(it.json(), AuditEvent::class.java) }
}
