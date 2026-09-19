package br.com.empresa.sdui.orchestrator.port.outbound

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import java.time.Duration

interface SpecStore {
    fun save(spec: Spec): Spec
    fun findByRevisionId(specRevisionId: String): Spec?
    fun findBySpecIdAndRevision(specId: String, revision: Int): Spec?
    fun listBySpecId(specId: String): List<Spec>
    fun listPublished(surface: String, platform: ClientPlatform): List<Spec>
    fun list(platform: ClientPlatform?, channel: Channel?): List<Spec>
    fun nextRevision(specId: String): Int
}

interface SkeletonStore {
    fun save(skeleton: Skeleton): Skeleton
    fun find(skeletonId: String, revision: Int? = null): Skeleton?
    fun current(skeletonId: String): Skeleton?
}

interface CatalogStore {
    fun save(catalog: Catalog): Catalog
    fun current(): Catalog
}

interface PointerStore {
    fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer?
    fun save(pointer: Pointer): Pointer
}

interface PublishRequestStore {
    fun save(request: PublishRequest): PublishRequest
    fun find(requestId: String): PublishRequest?
    fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest
    ): PublishRequest?
}

interface DiffStore {
    fun save(diff: SpecDiff): SpecDiff
    fun find(specId: String, from: Int, to: Int): SpecDiff?
}

interface AuditLogStore {
    fun append(event: AuditEvent)
    fun list(): List<AuditEvent>
}

interface IdempotencyStore {
    fun find(key: String): IdempotencyRecord?
    fun put(record: IdempotencyRecord)
}

interface HydratedScreenCache {
    fun get(treeKey: String): ComposedScreen?
    fun put(treeKey: String, screen: ComposedScreen, ttl: Duration)
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel)
}

interface LastGoodScreenStore {
    fun get(surface: String, platform: ClientPlatform, channel: Channel): ComposedScreen?
    fun put(screen: ComposedScreen)
}

interface SpecCache {
    fun get(specRevisionId: String, platform: ClientPlatform): Spec?
    fun put(spec: Spec)
    fun invalidate(specRevisionId: String, platform: ClientPlatform)
}

interface ProjectionStore {
    fun get(projection: String, id: String): Map<String, Any?>?
    fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration)
}

interface TransactionalUnitOfWork {
    fun <T> execute(work: () -> T): T
}

sealed interface SingleflightOutcome<out T> {
    data class Leader<T>(val value: T) : SingleflightOutcome<T>
    data class Waiter<T>(val value: T) : SingleflightOutcome<T>
    class WaitTimeout<T> : SingleflightOutcome<T>
}

interface ComposeSingleflight {
    fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T>
}

interface CanaryPolicy {
    fun channelFor(platform: ClientPlatform, build: String, requested: Channel): Channel
}

interface MetricsRecorder {
    fun increment(name: String, tags: Map<String, String> = emptyMap())
    fun recordTime(name: String, durationMs: Long, tags: Map<String, String> = emptyMap())
    fun recordBytes(name: String, bytes: Long, tags: Map<String, String> = emptyMap())
}

fun Spec.requireMutable() {
    check(status != SpecStatus.PUBLISHED) { "spec PUBLISHED e imutavel" }
}

fun Skeleton.requireMutable() {
    check(status != SpecStatus.PUBLISHED) { "skeleton PUBLISHED e imutavel" }
}
