package br.com.empresa.sdui.core.limit

import br.com.empresa.sdui.core.model.ClientPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RateLimitKey(
    val identity: String,
    val platform: ClientPlatform,
)

/**
 * Token bucket por identidade.
 *
 * A identidade chega de headers nao autenticados, entao este limitador protege a capacidade do
 * servico, nao a identidade do chamador: quem troca o header ganha um bucket novo. O teto por
 * cliente depende de autenticacao no gateway. O que esta garantido aqui e que o numero de buckets
 * residentes nao cresce com o numero de identidades ja vistas.
 */
class TokenBucketRateLimiter(
    private val capacity: Long = 100,
    private val refillPerSecond: Long = 100,
    private val maxKeys: Int = 100_000,
    private val idleEvictionMs: Long = 600_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Bucket(val tokens: Double, val lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<RateLimitKey, Bucket>()
    private val evicting = AtomicBoolean(false)

    fun tryConsume(key: RateLimitKey): Boolean {
        val now = clockMs()
        if (buckets.size >= maxKeys) evict(now)
        var granted = false
        // compute aplica a funcao de remapeamento sob o lock do bin da chave: exclusao mutua por
        // identidade, sem um lock unico serializando todo o hot path.
        buckets.compute(key) { _, current ->
            val available = refill(current ?: Bucket(capacity.toDouble(), now), now)
            granted = available >= 1.0
            Bucket(if (granted) available - 1.0 else available, now)
        }
        return granted
    }

    fun residentKeys(): Int = buckets.size

    private fun refill(bucket: Bucket, now: Long): Double {
        val elapsedSec = (now - bucket.lastRefillMs).coerceAtLeast(0) / 1000.0
        return (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())
    }

    /**
     * Descarta buckets ociosos e buckets ja recompostos ate a capacidade. Um bucket cheio e
     * indistinguivel de um bucket inexistente, entao a poda nunca devolve credito a quem esta
     * consumindo. Uma unica thread poda por vez; as demais seguem sem esperar.
     */
    private fun evict(now: Long) {
        if (!evicting.compareAndSet(false, true)) return
        try {
            buckets.entries.removeIf { entry ->
                now - entry.value.lastRefillMs >= idleEvictionMs ||
                    refill(entry.value, now) >= capacity.toDouble()
            }
        } finally {
            evicting.set(false)
        }
    }
}
