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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Specs em memoria. Faz valer a imutabilidade de PUBLISHED recusando sobrescrita de uma revisao
 * ja publicada.
 */
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

/** Skeletons em memoria, com a mesma protecao de imutabilidade apos publicacao. */
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

/** Catalogo em memoria. Substituido inteiro a cada gravacao, por isso um unico campo volatil basta. */
class InMemoryCatalogStore : CatalogStore {
    @Volatile
    private var catalog: Catalog = Catalog(emptyList())

    override fun save(catalog: Catalog): Catalog {
        this.catalog = catalog
        return catalog
    }

    override fun current(): Catalog = catalog
}

/** Pointers em memoria, um por surface, plataforma e canal. */
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

/** Pedidos de publicacao em memoria, com a transicao de status serializada por lock. */
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

/** Diffs em memoria, indexados por spec e par de revisoes. */
class InMemoryDiffStore : DiffStore {
    private val items = ConcurrentHashMap<String, SpecDiff>()

    override fun save(diff: SpecDiff): SpecDiff {
        items["${diff.specId}:${diff.fromRevision ?: 0}:${diff.toRevision}"] = diff
        return diff
    }

    override fun find(specId: String, from: Int, to: Int): SpecDiff? = items["$specId:$from:$to"]
}

/** Trilha de auditoria em memoria, so de acrescimo. */
class InMemoryAuditLogStore : AuditLogStore {
    private val events = mutableListOf<AuditEvent>()
    private val lock = ReentrantLock()

    override fun append(event: AuditEvent) {
        lock.withLock { events += event }
    }

    override fun list(): List<AuditEvent> = lock.withLock { events.toList() }
}

/** Registros de idempotencia em memoria. A primeira gravacao de uma chave vence. */
class InMemoryIdempotencyStore : IdempotencyStore {
    private val items = ConcurrentHashMap<String, IdempotencyRecord>()

    override fun find(key: String): IdempotencyRecord? = items[key]

    override fun put(record: IdempotencyRecord) {
        items.putIfAbsent(record.key, record)
    }
}

/**
 * Cache de arvore com teto de entradas.
 *
 * A chave inclui o capsHash, que depende de um header do cliente; sem teto, um chamador que varia
 * esse header grava um [ComposedScreen] novo por requisicao e nada e removido antes de alguem pedir
 * exatamente aquela chave de volta. O teto torna o consumo maximo previsivel.
 */
class InMemoryHydratedScreenCache(
    private val maxEntries: Int = 10_000,
) : HydratedScreenCache {
    private data class Entry(val screen: ComposedScreen, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val pruning = AtomicBoolean(false)

    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = items[treeKey] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            items.remove(treeKey, entry)
            return null
        }
        return entry.screen
    }

    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        if (items.size >= maxEntries) prune()
        items[treeKey] = Entry(screen, System.currentTimeMillis() + ttl.toMillis())
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        items.keys.removeIf { key ->
            key.startsWith("sdui:tree:$surface:") &&
                    key.contains(":${platform.wire()}:") &&
                    key.endsWith(":${channel.wire()}")
        }
    }

    /** Quantas entradas o cache guarda agora. Serve a diagnostico e aos testes do teto. */
    fun residentEntries(): Int = items.size

    /**
     * Descarta expirados e, se ainda assim faltar folga, as entradas que expiram primeiro. Uma
     * unica thread poda por vez; as demais gravam sem esperar e a poda seguinte as alcanca.
     */
    private fun prune() {
        if (!pruning.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            items.entries.removeIf { it.value.expiresAt < now }
            val excess = items.size - maxEntries / 2
            if (excess > 0) {
                items.entries
                    .sortedBy { it.value.expiresAt }
                    .take(excess)
                    .forEach { items.remove(it.key, it.value) }
            }
        } finally {
            pruning.set(false)
        }
    }

    fun clear() = items.clear()
}

/**
 * Ultima arvore boa em memoria, por surface, plataforma e canal.
 *
 * Sem expiracao de proposito: uma arvore defasada continua sendo melhor resposta que um 503, e
 * ela so e servida quando a composicao ja falhou.
 */
class InMemoryLastGoodScreenStore : LastGoodScreenStore {
    private val items = ConcurrentHashMap<String, ComposedScreen>()

    override fun get(surface: String, platform: ClientPlatform, channel: Channel): ComposedScreen? =
        items[RedisKeys.lastGood(surface, platform, channel)]

    override fun put(screen: ComposedScreen) {
        items[RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)] = screen
    }

    fun clear() = items.clear()
}

/** Cache de specs em memoria, chaveado por revisao e plataforma. */
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

/** Projecoes em memoria, com expiracao preguicosa na leitura. */
class InMemoryProjectionStore : ProjectionStore {
    private data class Entry(val props: Map<String, Any?>, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()

    override fun get(projection: String, id: String): Map<String, Any?>? {
        val key = RedisKeys.section(projection, id)
        val entry = items[key] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            items.remove(key, entry)
            return null
        }
        return entry.props
    }

    override fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration) {
        items[RedisKeys.section(projection, id)] =
            Entry(props, System.currentTimeMillis() + ttl.toMillis())
    }
}

/**
 * Unidade de trabalho em memoria: serializa os blocos por lock.
 *
 * Da exclusao mutua, mas nao atomicidade — nao ha rollback. Uma falha no meio do bloco deixa os
 * stores com o efeito parcial ja aplicado.
 */
class InMemoryTransactionalUnitOfWork : TransactionalUnitOfWork {
    private val lock = ReentrantLock()
    override fun <T : Any> execute(work: () -> T): T = lock.withLock { work() }
}

/**
 * Singleflight local ao processo: a primeira requisicao de uma chave computa, as outras esperam.
 *
 * Quem espera e estoura o proprio prazo devolve WaitTimeout sem cancelar a computacao — cancelar
 * puniria o lider e todos os demais que aguardam por causa de um unico impaciente.
 *
 * Vale so dentro de uma instancia. Com varias replicas, cada uma compoe a sua.
 */
class InMemoryComposeSingleflight : ComposeSingleflight {
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    override fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T> {
        val created = CompletableFuture<Any?>()
        val existing = inflight.putIfAbsent(key, created)
        if (existing == null) {
            try {
                val value = compute()
                created.complete(value)
                return SingleflightOutcome.Leader(value)
            } catch (error: Throwable) {
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
        } catch (ex: java.util.concurrent.ExecutionException) {
            val cause = ex.cause ?: ex
            if (cause is Exception) throw cause else throw RuntimeException(cause)
        }
    }
}
