package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class InMemorySpecStore : SpecStore {
    private val items = ConcurrentHashMap<String, Spec>()
    private val lock = ReentrantLock()

    override fun save(spec: Spec): Spec = lock.withLock {
        val key = "${spec.specId}#${spec.revision}"
        val existing = items[key]
        if (existing?.status == SpecStatus.PUBLISHED) {
            error("spec PUBLISHED e imutavel: $key")
        }
        items[key] = spec
        spec
    }

    override fun findByRevisionId(specRevisionId: String): Spec? =
        items.values.firstOrNull { it.specRevisionId == specRevisionId }

    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? =
        items["$specId#$revision"]

    override fun listBySpecId(specId: String): List<Spec> =
        items.values.filter { it.specId == specId }.sortedBy { it.revision }

    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        items.values.filter { it.surface == surface && it.platform == platform && it.status == SpecStatus.PUBLISHED }

    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> =
        items.values.filter { spec ->
            (platform == null || spec.platform == platform) && (channel == null || spec.channel == channel)
        }

    override fun nextRevision(specId: String): Int =
        (items.values.filter { it.specId == specId }.maxOfOrNull { it.revision } ?: 0) + 1

    fun clear() = items.clear()
}

class InMemorySkeletonStore : SkeletonStore {
    private val items = ConcurrentHashMap<String, Skeleton>()

    override fun save(skeleton: Skeleton): Skeleton {
        val key = "${skeleton.skeletonId}#${skeleton.revision}"
        val existing = items[key]
        if (existing?.status == SpecStatus.PUBLISHED) {
            error("skeleton PUBLISHED e imutavel: $key")
        }
        items[key] = skeleton
        return skeleton
    }

    override fun find(skeletonId: String, revision: Int?): Skeleton? {
        if (revision != null) return items["$skeletonId#$revision"]
        return current(skeletonId)
    }

    override fun current(skeletonId: String): Skeleton? =
        items.values.filter { it.skeletonId == skeletonId }.maxByOrNull { it.revision }
}

class InMemoryCatalogStore : CatalogStore {
    @Volatile
    private var catalog: Catalog = Catalog(emptyList())

    override fun save(catalog: Catalog): Catalog {
        this.catalog = catalog
        return catalog
    }

    override fun current(): Catalog = catalog
}

class InMemoryPointerStore : PointerStore {
    private val items = ConcurrentHashMap<String, Pointer>()

    private fun key(surface: String, platform: ClientPlatform, channel: Channel) =
        "$surface:${platform.wire()}:${channel.wire()}"

    override fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer? =
        items[key(surface, platform, channel)]

    override fun save(pointer: Pointer): Pointer {
        items[key(pointer.surface, pointer.platform, pointer.channel)] = pointer
        return pointer
    }
}

class InMemoryPublishRequestStore : PublishRequestStore {
    private val items = ConcurrentHashMap<String, PublishRequest>()
    private val lock = ReentrantLock()

    override fun save(request: PublishRequest): PublishRequest {
        items[request.requestId] = request
        return request
    }

    override fun find(requestId: String): PublishRequest? = items[requestId]

    override fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest,
    ): PublishRequest? = lock.withLock {
        val current = items[requestId] ?: return null
        if (current.status != expected) return null
        items[requestId] = updated
        updated
    }
}

class InMemoryDiffStore : DiffStore {
    private val items = ConcurrentHashMap<String, SpecDiff>()

    override fun save(diff: SpecDiff): SpecDiff {
        items["${diff.specId}:${diff.fromRevision}:${diff.toRevision}"] = diff
        return diff
    }

    override fun find(specId: String, from: Int, to: Int): SpecDiff? = items["$specId:$from:$to"]
}

class InMemoryAuditLogStore : AuditLogStore {
    private val events = mutableListOf<AuditEvent>()
    private val lock = ReentrantLock()

    override fun append(event: AuditEvent) {
        lock.withLock { events += event }
    }

    override fun list(): List<AuditEvent> = lock.withLock { events.toList() }
}

class InMemoryIdempotencyStore : IdempotencyStore {
    private val items = ConcurrentHashMap<String, IdempotencyRecord>()

    override fun find(key: String): IdempotencyRecord? = items[key]

    override fun put(record: IdempotencyRecord) {
        items.putIfAbsent(record.key, record)
    }
}

class InMemoryHydratedScreenCache : HydratedScreenCache {
    private data class Entry(val screen: ComposedScreen, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()

    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = items[treeKey] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            items.remove(treeKey)
            return null
        }
        return entry.screen
    }

    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        items[treeKey] = Entry(screen, System.currentTimeMillis() + ttl.toMillis())
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        val suffix = "${platform.wire()}:"
        items.keys.removeIf { key ->
            key.startsWith("sdui:tree:$surface:") && key.contains(suffix) && key.endsWith(":${channel.wire()}")
        }
    }

    fun clear() = items.clear()
}

class InMemoryLastGoodScreenStore : LastGoodScreenStore {
    private val items = ConcurrentHashMap<String, ComposedScreen>()

    override fun get(surface: String, platform: ClientPlatform, channel: Channel): ComposedScreen? =
        items[RedisKeys.lastGood(surface, platform, channel)]

    override fun put(screen: ComposedScreen) {
        items[RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)] = screen
    }

    fun clear() = items.clear()
}

class InMemorySpecCache : SpecCache {
    private val items = ConcurrentHashMap<String, Spec>()

    override fun get(specRevisionId: String, platform: ClientPlatform): Spec? =
        items[RedisKeys.spec(specRevisionId, platform)]

    override fun put(spec: Spec) {
        items[RedisKeys.spec(spec.specRevisionId, spec.platform)] = spec
    }

    override fun invalidate(specRevisionId: String, platform: ClientPlatform) {
        items.remove(RedisKeys.spec(specRevisionId, platform))
    }
}

class InMemoryProjectionStore : ProjectionStore {
    private val items = ConcurrentHashMap<String, Map<String, Any?>>()

    override fun get(projection: String, id: String): Map<String, Any?>? = items[RedisKeys.section(projection, id)]

    override fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration) {
        items[RedisKeys.section(projection, id)] = props
    }
}

class InMemoryTransactionalUnitOfWork : TransactionalUnitOfWork {
    private val lock = ReentrantLock()
    override fun <T> execute(work: () -> T): T = lock.withLock { work() }
}

class InMemoryComposeSingleflight : ComposeSingleflight {
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    override fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T> {
        val created = CompletableFuture<Any?>()
        val existing = inflight.putIfAbsent(key, created)
        if (existing == null) {
            try {
                val value = compute()
                created.complete(value)
                @Suppress("UNCHECKED_CAST")
                return SingleflightOutcome.Leader(value)
            } catch (error: Exception) {
                created.completeExceptionally(error)
                throw error
            } finally {
                inflight.remove(key, created)
            }
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val value = existing.get(timeout.toMillis(), TimeUnit.MILLISECONDS) as T
            SingleflightOutcome.Waiter(value)
        } catch (_: TimeoutException) {
            SingleflightOutcome.WaitTimeout()
        } catch (error: Exception) {
            throw error
        }
    }
}

class RecordingMetrics : MetricsRecorder {
    data class Sample(val name: String, val tags: Map<String, String>, val value: Long? = null)

    val samples = mutableListOf<Sample>()
    private val lock = ReentrantLock()

    override fun increment(name: String, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags) }
    }

    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags, durationMs) }
    }

    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags, bytes) }
    }

    fun names(): List<String> = lock.withLock { samples.map { it.name } }
}
