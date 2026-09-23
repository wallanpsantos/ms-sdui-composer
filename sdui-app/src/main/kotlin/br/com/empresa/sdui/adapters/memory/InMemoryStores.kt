package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
