package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Estado de governanca publicado de uma vez ao concluir a transacao (ADR-022).
 * Leitores usam o snapshot commitado sem lock. Apenas escritores administrativos disputam o
 * lock; nao ha I/O nele. Em falha, o snapshot privado da thread e descartado integralmente.
 * Todos os stores de uma instancia e a unidade de trabalho recebem o mesmo coordenador.
 */
class InMemoryGovernance {
    internal data class Reservation(val record: IdempotencyRecord, val token: String, val expiresAt: Instant)

    internal data class State(
        val specs: Map<String, Spec> = emptyMap(),
        val byRevisionId: Map<String, Spec> = emptyMap(),
        val published: Map<String, List<Spec>> = emptyMap(),
        val skeletons: Map<String, Skeleton> = emptyMap(),
        val catalog: Catalog = Catalog(emptyList()),
        val pointers: Map<String, Pointer> = emptyMap(),
        val requests: Map<String, PublishRequest> = emptyMap(),
        val diffs: Map<String, SpecDiff> = emptyMap(),
        val audit: List<AuditEvent> = emptyList(),
        val reservations: Map<String, Reservation> = emptyMap(),
        val outbox: Map<String, CacheInvalidation> = emptyMap(),
    )

    @Volatile private var committed = State()
    private val pending = ThreadLocal<State>()
    private val writers = ReentrantLock()

    internal fun <T> read(block: (State) -> T): T = block(pending.get() ?: committed)

    internal fun update(block: (State) -> State) = writers.withLock {
        val local = pending.get()
        if (local == null) committed = block(committed) else pending.set(block(local))
    }

    internal fun <T : Any> transaction(work: () -> T): T = writers.withLock {
        if (pending.get() != null) return work()
        pending.set(committed)
        try {
            val result = work()
            committed = checkNotNull(pending.get())
            result
        } finally {
            pending.remove()
        }
    }
}

class InMemoryTransactionalUnitOfWork(private val governance: InMemoryGovernance) : TransactionalUnitOfWork {
    override fun <T : Any> execute(work: () -> T): T = governance.transaction(work)
}

/** Indices e unicidade atualizados no mesmo snapshot que o spec. */
class InMemorySpecStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : SpecStore {
    override fun save(spec: Spec): Spec = write(spec, null, conditional = false)

    override fun compareAndSet(expected: Spec?, updated: Spec): Spec = write(updated, expected, conditional = true)

    private fun write(spec: Spec, expected: Spec?, conditional: Boolean): Spec {
        val key = "${spec.specId}#${spec.revision}"
        governance.update { state ->
            val current = state.specs[key]
            if (conditional && current != expected) throw StoreConflict("rascunho mudou desde a leitura")
            if (current?.status == SpecStatus.PUBLISHED) throw StoreConflict("spec PUBLISHED e imutavel: $key")
            val owner = state.byRevisionId[spec.specRevisionId]
            if (owner != null && (owner.specId != spec.specId || owner.revision != spec.revision)) {
                throw StoreConflict("specRevisionId ja usado")
            }
            val group = group(spec.surface, spec.platform)
            state.copy(
                specs = state.specs + (key to spec),
                byRevisionId = (state.byRevisionId - (current?.specRevisionId ?: spec.specRevisionId)) +
                    (spec.specRevisionId to spec),
                published = if (spec.status == SpecStatus.PUBLISHED) {
                    state.published + (group to (state.published[group].orEmpty() + spec))
                } else state.published,
            )
        }
        return spec
    }

    override fun findByRevisionId(specRevisionId: String): Spec? = governance.read { it.byRevisionId[specRevisionId] }
    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? = governance.read { it.specs["$specId#$revision"] }
    override fun listBySpecId(specId: String): List<Spec> = governance.read { state ->
        state.specs.values.filter { it.specId == specId }.sortedBy { it.revision }
    }
    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        governance.read { it.published[group(surface, platform)].orEmpty() }
    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> = governance.read { state ->
        state.specs.values.filter { (platform == null || it.platform == platform) && (channel == null || it.channel == channel) }
    }
    override fun nextRevision(specId: String): Int {
        val last = listBySpecId(specId).lastOrNull()?.revision ?: 0
        if (last == Int.MAX_VALUE) throw StoreConflict("limite de revisoes atingido")
        return last + 1
    }
    fun clear() = governance.update { it.copy(specs = emptyMap(), byRevisionId = emptyMap(), published = emptyMap()) }
    private fun group(surface: String, platform: ClientPlatform): String = "$surface|${platform.wire()}"
}

class InMemorySkeletonStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : SkeletonStore {
    override fun save(skeleton: Skeleton): Skeleton = write(skeleton, null, conditional = false)
    override fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton = write(updated, expected, conditional = true)

    private fun write(skeleton: Skeleton, expected: Skeleton?, conditional: Boolean): Skeleton {
        val key = "${skeleton.skeletonId}#${skeleton.revision}"
        governance.update { state ->
            val current = state.skeletons[key]
            if (conditional && current != expected) throw StoreConflict("skeleton mudou desde a leitura")
            if (current?.status == SpecStatus.PUBLISHED) throw StoreConflict("skeleton PUBLISHED e imutavel: $key")
            state.copy(skeletons = state.skeletons + (key to skeleton))
        }
        return skeleton
    }
    override fun find(skeletonId: String, revision: Int?): Skeleton? =
        if (revision == null) current(skeletonId) else governance.read { it.skeletons["$skeletonId#$revision"] }
    override fun current(skeletonId: String): Skeleton? = governance.read { state ->
        state.skeletons.values.filter { it.skeletonId == skeletonId }.maxByOrNull { it.revision }
    }
}

class InMemoryCatalogStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : CatalogStore {
    override fun save(catalog: Catalog): Catalog {
        governance.update { it.copy(catalog = catalog) }
        return catalog
    }
    override fun current(): Catalog = governance.read { it.catalog }
}

class InMemoryPointerStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : PointerStore {
    override fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer? =
        governance.read { it.pointers[key(surface, platform, channel)] }
    override fun save(pointer: Pointer): Pointer {
        governance.update { it.copy(pointers = it.pointers + (key(pointer.surface, pointer.platform, pointer.channel) to pointer)) }
        return pointer
    }
    override fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer {
        val key = key(updated.surface, updated.platform, updated.channel)
        governance.update {
            if (it.pointers[key]?.version != expectedVersion) throw StoreConflict("pointer mudou desde a leitura")
            it.copy(pointers = it.pointers + (key to updated))
        }
        return updated
    }
    private fun key(surface: String, platform: ClientPlatform, channel: Channel): String = "$surface:${platform.wire()}:${channel.wire()}"
}

class InMemoryPublishRequestStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : PublishRequestStore {
    override fun save(request: PublishRequest): PublishRequest {
        governance.update { it.copy(requests = it.requests + (request.requestId to request)) }
        return request
    }
    override fun find(requestId: String): PublishRequest? = governance.read { it.requests[requestId] }
    override fun compareAndSetStatus(requestId: String, expected: PublishRequestStatus, updated: PublishRequest): PublishRequest? {
        var result: PublishRequest? = null
        governance.update {
            if (it.requests[requestId]?.status != expected) it else {
                result = updated
                it.copy(requests = it.requests + (requestId to updated))
            }
        }
        return result
    }
}

class InMemoryDiffStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : DiffStore {
    override fun save(diff: SpecDiff): SpecDiff {
        governance.update { it.copy(diffs = it.diffs + ("${diff.specId}:${diff.fromRevision ?: 0}:${diff.toRevision}" to diff)) }
        return diff
    }
    override fun find(specId: String, from: Int, to: Int): SpecDiff? = governance.read { it.diffs["$specId:$from:$to"] }
}

class InMemoryAuditLogStore(
    private val maxEvents: Int = 2_000,
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : AuditLogStore {
    init { require(maxEvents > 0) }
    override fun append(event: AuditEvent) = governance.update { it.copy(audit = it.audit.takeLast(maxEvents - 1) + event) }
    override fun list(): List<AuditEvent> = governance.read { it.audit.toList() }
    override fun recent(limit: Int): List<AuditEvent> = governance.read { it.audit.takeLast(limit).asReversed() }
}

class InMemoryIdempotencyStore(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(24),
    private val maxEntries: Int = 10_000,
    private val reservationTimeout: Duration = Duration.ofMinutes(5),
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : IdempotencyStore {
    init { require(maxEntries > 0 && !ttl.isNegative && !ttl.isZero && !reservationTimeout.isNegative && !reservationTimeout.isZero) }

    override fun find(key: String): IdempotencyRecord? = governance.read {
        it.reservations[key]?.takeIf { entry -> entry.expiresAt.isAfter(clock.instant()) }?.record
    }

    override fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation {
        var result: IdempotencyReservation = IdempotencyReservation.CapacityExhausted
        governance.update { state ->
            val now = clock.instant()
            val existing = state.reservations[key]
            if (existing != null && existing.expiresAt.isAfter(now)) {
                result = IdempotencyReservation.Existing(existing.record)
                state
            } else {
                val live = state.reservations.filterValues { it.expiresAt.isAfter(now) }
                if (live.size >= maxEntries) state.copy(reservations = live) else {
                    val token = UUID.randomUUID().toString()
                    result = IdempotencyReservation.Reserved(token)
                    state.copy(reservations = live + (key to InMemoryGovernance.Reservation(
                        IdempotencyRecord(key, operation, null, fingerprint), token, now.plus(reservationTimeout),
                    )))
                }
            }
        }
        return result
    }

    override fun complete(record: IdempotencyRecord, token: String) = governance.update { state ->
        val entry = state.reservations[record.key]
        val now = clock.instant()
        if (entry == null || entry.token != token || !entry.expiresAt.isAfter(now) ||
            entry.record.resultRef != null || entry.record.operation != record.operation ||
            entry.record.fingerprint != record.fingerprint
        ) throw StoreConflict("reserva de idempotencia vencida ou substituida")
        state.copy(reservations = state.reservations + (record.key to entry.copy(record = record, expiresAt = now.plus(ttl))))
    }

    override fun release(key: String, token: String) = governance.update { state ->
        val entry = state.reservations[key]
        if (entry?.token == token && entry.record.resultRef == null) state.copy(reservations = state.reservations - key) else state
    }
    fun residentEntries(): Int = governance.read { it.reservations.size }
    fun clear() = governance.update { it.copy(reservations = emptyMap()) }
}

class InMemoryCacheInvalidationOutbox(
    private val maxPending: Int = 10_000,
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : CacheInvalidationOutbox {
    init { require(maxPending > 0) }
    override fun record(invalidation: CacheInvalidation) = governance.update {
        if (it.outbox.size >= maxPending && invalidation.id !in it.outbox) throw StoreConflict("outbox sem capacidade")
        it.copy(outbox = it.outbox + (invalidation.id to invalidation))
    }
    override fun pending(limit: Int): List<CacheInvalidation> = governance.read { it.outbox.values.take(limit) }
    override fun markApplied(id: String) = governance.update { it.copy(outbox = it.outbox - id) }
}
