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
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Specs em memoria. Faz valer a imutabilidade de PUBLISHED recusando sobrescrita de uma revisao
 * ja publicada.
 *
 * Mantem dois indices atualizados sob o mesmo lock da gravacao: por `specRevisionId` e, para as
 * publicadas, por surface e plataforma. Sem eles cada requisicao filtrava todas as revisoes ja
 * vistas desde o boot, inclusive rascunhos de outras plataformas — 224 a 296 µs por acerto de
 * cache com 10 mil revisoes (medicao de 2026-09-23). Publicada nunca muda nem sai, entao o indice
 * de publicadas so recebe acrescimos.
 */
class InMemorySpecStore : SpecStore {
    private val items = ConcurrentHashMap<String, Spec>()
    private val byRevisionId = ConcurrentHashMap<String, Spec>()

    @Volatile
    private var published: Map<String, List<Spec>> = emptyMap()
    private val lock = ReentrantLock()

    override fun save(spec: Spec): Spec = lock.withLock {
        val key = "${spec.specId}#${spec.revision}"
        val existing = items[key]
        if (existing?.status == SpecStatus.PUBLISHED) {
            error("spec PUBLISHED e imutavel: $key")
        }
        items[key] = spec
        if (existing != null && existing.specRevisionId != spec.specRevisionId) {
            byRevisionId.remove(existing.specRevisionId, existing)
        }
        byRevisionId[spec.specRevisionId] = spec
        if (spec.status == SpecStatus.PUBLISHED) {
            val group = group(spec.surface, spec.platform)
            published = published + (group to (published[group].orEmpty() + spec))
        }
        spec
    }

    override fun findByRevisionId(specRevisionId: String): Spec? = byRevisionId[specRevisionId]

    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? =
        items["$specId#$revision"]

    override fun listBySpecId(specId: String): List<Spec> =
        items.values.filter { it.specId == specId }.sortedBy { it.revision }

    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        published[group(surface, platform)].orEmpty()

    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> =
        items.values.filter { spec ->
            (platform == null || spec.platform == platform) && (channel == null || spec.channel == channel)
        }

    override fun nextRevision(specId: String): Int =
        (items.values.filter { it.specId == specId }.maxOfOrNull { it.revision } ?: 0) + 1

    fun clear() = lock.withLock {
        items.clear()
        byRevisionId.clear()
        published = emptyMap()
    }

    private fun group(surface: String, platform: ClientPlatform): String = "$surface|${platform.wire()}"
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

/** Pointers em memoria, um por surface, plataforma e canal, com compare-and-set por versao. */
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

    override fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer {
        val key = key(updated.surface, updated.platform, updated.channel)
        var conflict = false
        // compute aplica a funcao sob o lock do bin da chave: a comparacao e a troca sao uma so.
        items.compute(key) { _, current ->
            if (current?.version != expectedVersion) {
                conflict = true
                current
            } else {
                updated
            }
        }
        if (conflict) throw StoreConflict("pointer $key mudou desde a leitura (esperado v$expectedVersion)")
        return updated
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

/**
 * Trilha de auditoria em memoria, com teto de retencao para evitar vazamento de heap.
 *
 * Lista com remocao do mais antigo no teto, e nao anel: o deslocamento de ate [maxEvents]
 * referencias custou 274 ns por append no teto (medicao de 2026-09-23), irrelevante no plano
 * administrativo. A retencao de governanca de verdade e do adapter persistente (ADR-021).
 */
class InMemoryAuditLogStore(
    private val maxEvents: Int = 2_000,
) : AuditLogStore {
    private val events = mutableListOf<AuditEvent>()
    private val lock = ReentrantLock()

    override fun append(event: AuditEvent) {
        lock.withLock {
            if (events.size >= maxEvents) {
                events.removeAt(0)
            }
            events += event
        }
    }

    override fun list(): List<AuditEvent> = lock.withLock { events.toList() }

    override fun recent(limit: Int): List<AuditEvent> = lock.withLock { events.takeLast(limit).asReversed().toList() }
}

/**
 * Registros de idempotencia em memoria, com reserva atomica, validade e teto de entradas.
 *
 * A reserva confere e grava sob o mesmo lock: quem toma a chave executa, quem chega depois recebe
 * o registro existente. O teto existe porque a chave vem de header e o mapa nao pode crescer sem
 * limite — mas, ao contrario de um cache, este mapa guarda coordenacao: expulsar uma reserva em
 * voo deixaria o retry da mesma chave executar a operacao de novo, e expulsar um resultado dentro
 * da janela quebraria a deduplicacao prometida. Por isso, no teto, so os registros vencidos saem;
 * se ainda assim nao houver espaco, a nova admissao e recusada ([IdempotencyReservation.CapacityExhausted])
 * e vira 503 na borda (achado P1 de 2026-09-23).
 *
 * Validade: um resultado vale por [ttl]; uma reserva em voo vale por [reservationTimeout], depois
 * do qual e tratada como abandonada (processo que caiu no meio) e a chave pode ser retomada.
 */
class InMemoryIdempotencyStore(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(24),
    private val maxEntries: Int = 10_000,
    private val reservationTimeout: Duration = Duration.ofMinutes(5),
) : IdempotencyStore {
    private data class Entry(val record: IdempotencyRecord, val expiresAt: Instant)

    private val items = HashMap<String, Entry>()
    private val lock = ReentrantLock()

    override fun find(key: String): IdempotencyRecord? = lock.withLock {
        val entry = items[key] ?: return null
        if (entry.expiresAt.isBefore(clock.instant())) {
            items.remove(key)
            return null
        }
        entry.record
    }

    override fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation =
        lock.withLock {
            val now = clock.instant()
            val existing = items[key]
            if (existing != null && !existing.expiresAt.isBefore(now)) {
                return IdempotencyReservation.Existing(existing.record)
            }
            if (existing == null && items.size >= maxEntries) {
                items.values.removeIf { it.expiresAt.isBefore(now) }
                if (items.size >= maxEntries) return IdempotencyReservation.CapacityExhausted
            }
            items[key] = Entry(IdempotencyRecord(key, operation, null, fingerprint), now.plus(reservationTimeout))
            IdempotencyReservation.Reserved
        }

    override fun complete(record: IdempotencyRecord) {
        lock.withLock { items[record.key] = Entry(record, clock.instant().plus(ttl)) }
    }

    override fun release(key: String) {
        lock.withLock {
            // So devolve reserva em voo. Um resultado ja fechado e o que faz o retry ser
            // idempotente; remove-lo por engano deixaria a operacao seguinte executar de novo.
            if (items[key]?.record?.resultRef == null) items.remove(key)
        }
    }

    /** Quantas chaves estao residentes agora. Serve a diagnostico e aos testes do teto. */
    fun residentEntries(): Int = lock.withLock { items.size }

    fun clear() = lock.withLock { items.clear() }
}

/**
 * Cache de arvore com teto de entradas estrito.
 *
 * A chave inclui o capsHash, que depende de um header do cliente; sem teto, um chamador que varia
 * esse header grava um [ComposedScreen] novo por requisicao e nada e removido antes de alguem pedir
 * exatamente aquela chave de volta.
 *
 * O teto e estrito sob concorrencia porque a vaga e reservada por compare-and-set num contador
 * antes da insercao e devolvida depois da remocao: o contador nunca passa de [maxEntries] e nunca
 * fica abaixo do numero de chaves residentes. Conferir `size` do mapa e inserir em seguida nao
 * bastava — `ConcurrentHashMap.size` e estimativa sob escrita concorrente, e 8 escritores num teto
 * de 1.000 chegaram a 1.093 (execucao Gradle de 2026-09-23). No teto, uma unica thread poda
 * (vencidas e, se preciso, as que vencem primeiro, ate metade do teto) e as demais **descartam** a
 * propria escrita. Escrita descartada e so um miss a mais; cache e best-effort.
 */
class InMemoryHydratedScreenCache(
    private val maxEntries: Int = 10_000,
) : HydratedScreenCache {
    private data class Entry(val screen: ComposedScreen, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val slots = AtomicInteger()
    private val pruning = AtomicBoolean(false)
    private val skippedWrites = AtomicLong()

    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = items[treeKey] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            remove(treeKey, entry)
            return null
        }
        return entry.screen
    }

    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = Entry(screen, System.currentTimeMillis() + ttl.toMillis())
        // Chave ja residente troca de valor no lugar, sem ocupar vaga nova.
        if (items.replace(treeKey, entry) != null) return
        if (!reserveSlot() && !(prune() && reserveSlot())) {
            skippedWrites.incrementAndGet()
            return
        }
        // Outro escritor inseriu a mesma chave depois do replace: a vaga dele ja a cobre.
        if (items.putIfAbsent(treeKey, entry) != null) slots.decrementAndGet()
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        removeWhere { key, _ ->
            key.startsWith("sdui:tree:$surface:") &&
                    key.contains(":${platform.wire()}:") &&
                    key.endsWith(":${channel.wire()}")
        }
    }

    /** Quantas entradas o cache guarda agora. Serve a diagnostico e aos testes do teto. */
    fun residentEntries(): Int = items.size

    /**
     * Vagas ocupadas. Linearizavel e sempre entre o numero de chaves residentes e [maxEntries];
     * igual a [residentEntries] quando nao ha escrita em andamento.
     */
    fun occupiedSlots(): Int = slots.get()

    /** Escritas descartadas no teto desde o boot. Exposto como metrica pela configuracao. */
    fun skippedWrites(): Long = skippedWrites.get()

    fun clear() = removeWhere { _, _ -> true }

    private fun reserveSlot(): Boolean {
        while (true) {
            val taken = slots.get()
            if (taken >= maxEntries) return false
            if (slots.compareAndSet(taken, taken + 1)) return true
        }
    }

    /** Uma thread por vez; devolve false para quem nao conseguiu podar. */
    private fun prune(): Boolean {
        if (!pruning.compareAndSet(false, true)) return false
        try {
            val now = System.currentTimeMillis()
            removeWhere { _, entry -> entry.expiresAt < now }
            val excess = slots.get() - maxEntries / 2
            if (excess > 0) {
                items.entries
                    .sortedBy { it.value.expiresAt }
                    .take(excess)
                    .forEach { remove(it.key, it.value) }
            }
            return true
        } finally {
            pruning.set(false)
        }
    }

    private inline fun removeWhere(predicate: (String, Entry) -> Boolean) {
        for ((key, entry) in items) {
            if (predicate(key, entry)) remove(key, entry)
        }
    }

    private fun remove(key: String, entry: Entry) {
        if (items.remove(key, entry)) slots.decrementAndGet()
    }
}

/**
 * Ultima arvore boa em memoria, por surface, plataforma e canal, carimbada com o instante da
 * gravacao e com a versao do pointer sob a qual foi composta.
 *
 * Nao expira sozinha: uma arvore defasada continua sendo melhor resposta que um 503, e ela so e
 * servida quando a composicao ja falhou. Quem decide ate que ponto a defasagem ainda e aceitavel e
 * o pipeline, que compara a idade com o orcamento de fallback — e para isso precisa do carimbo.
 *
 * A invalidacao grava uma lapide na versao nova do pointer. Gravacao de versao menor e ignorada,
 * sob o lock do bin da chave: a composicao que comecou antes da publicacao nao ressuscita a
 * revisao retirada (ADR-021).
 */
class InMemoryLastGoodScreenStore(
    private val clock: Clock = Clock.systemUTC(),
) : LastGoodScreenStore {
    private data class Slot(val stored: StoredScreen?, val version: Long)

    private val items = ConcurrentHashMap<String, Slot>()

    override fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen? =
        items[RedisKeys.lastGood(surface, platform, channel)]?.stored

    override fun put(screen: ComposedScreen) {
        items.compute(RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)) { _, current ->
            if (current != null && current.version > screen.pointerVersion) {
                current
            } else {
                Slot(StoredScreen(screen, clock.instant()), screen.pointerVersion)
            }
        }
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long) {
        items.compute(RedisKeys.lastGood(surface, platform, channel)) { _, current ->
            if (current != null && current.version >= pointerVersion) current else Slot(null, pointerVersion)
        }
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

    fun clear() = items.clear()
}

/**
 * Projecoes em memoria, com expiracao preguicosa, varredura periodica e teto.
 *
 * A varredura remove as vencidas no maximo uma vez a cada [sweepIntervalMs], disparada por
 * leitura ou escrita: sem ela, projecoes vencidas abaixo do teto e nunca mais consultadas ficariam
 * residentes para sempre.
 */
class InMemoryProjectionStore(
    private val maxEntries: Int = 10_000,
    private val sweepIntervalMs: Long = 60_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ProjectionStore {
    private data class Entry(val props: Map<String, Any?>, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val pruning = AtomicBoolean(false)
    private val lastSweep = AtomicLong(0)

    override fun get(projection: String, id: String): Map<String, Any?>? {
        val now = clockMs()
        sweepIfDue(now)
        val key = RedisKeys.section(projection, id)
        val entry = items[key] ?: return null
        if (entry.expiresAt < now) {
            items.remove(key, entry)
            return null
        }
        return entry.props
    }

    override fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration) {
        val now = clockMs()
        sweepIfDue(now)
        if (items.size >= maxEntries) items.pruneToHalf(pruning, maxEntries, clockMs) { it.expiresAt }
        items[RedisKeys.section(projection, id)] = Entry(props, now + ttl.toMillis())
    }

    private fun sweepIfDue(now: Long) {
        val last = lastSweep.get()
        if (now - last < sweepIntervalMs) return
        if (!lastSweep.compareAndSet(last, now)) return
        items.entries.removeIf { it.value.expiresAt < now }
    }

    fun residentEntries(): Int = items.size
    fun clear() = items.clear()
}

/**
 * Invalidacoes pendentes em memoria. Sem durabilidade: um restart perde o outbox junto com os
 * proprios caches em memoria, entao nada fica para trás. Tem teto para nao crescer se um cache
 * persistente ficar fora do ar por muito tempo; acima dele, a mais antiga cede lugar.
 */
class InMemoryCacheInvalidationOutbox(
    private val maxPending: Int = 10_000,
) : CacheInvalidationOutbox {
    private val pending = LinkedHashMap<String, CacheInvalidation>()
    private val lock = ReentrantLock()

    override fun record(invalidation: CacheInvalidation) {
        lock.withLock {
            if (pending.size >= maxPending) pending.remove(pending.keys.first())
            pending[invalidation.id] = invalidation
        }
    }

    override fun pending(limit: Int): List<CacheInvalidation> = lock.withLock { pending.values.take(limit) }

    override fun markApplied(id: String) {
        lock.withLock { pending.remove(id) }
    }
}

/**
 * Descarta as entradas vencidas e, se ainda assim faltar folga, as que vencem primeiro, ate sobrar
 * metade do teto. Uma unica thread poda por vez. O teto que resulta e aproximado: serve a
 * projecoes, cujo volume e limitado pelo servidor, e nao a caches chaveados por header.
 */
private fun <V : Any, T : Comparable<T>> ConcurrentHashMap<String, V>.pruneToHalf(
    pruning: AtomicBoolean,
    maxEntries: Int,
    now: () -> T,
    expiresAt: (V) -> T,
) {
    if (!pruning.compareAndSet(false, true)) return
    try {
        val instant = now()
        entries.removeIf { expiresAt(it.value) < instant }
        val excess = size - maxEntries / 2
        if (excess > 0) {
            entries
                .sortedBy { expiresAt(it.value) }
                .take(excess)
                .forEach { remove(it.key, it.value) }
        }
    } finally {
        pruning.set(false)
    }
}

/**
 * Unidade de trabalho em memoria: serializa os blocos por lock.
 *
 * Da exclusao mutua, mas nao atomicidade — nao ha rollback. Uma falha no meio do bloco deixa os
 * stores com o efeito parcial ja aplicado. A atomicidade real e do adapter MongoDB (ADR-021).
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
            SingleflightOutcome.WaitTimeout
        } catch (ex: ExecutionException) {
            val cause = ex.cause ?: ex
            if (cause is Exception) throw cause else throw RuntimeException(cause)
        }
    }
}
