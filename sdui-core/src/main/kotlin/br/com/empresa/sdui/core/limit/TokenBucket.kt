package br.com.empresa.sdui.core.limit

import br.com.empresa.sdui.core.model.ClientPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Identidade limitada. Vem de headers nao autenticados — ver a nota em [TokenBucketRateLimiter]. */
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
    private val minEvictIntervalMs: Long = 1_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Bucket(val tokens: Double, val lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<RateLimitKey, Bucket>()
    private val lastEvictMs = AtomicLong(0)

    fun tryConsume(key: RateLimitKey): Boolean {
        val now = clockMs()
        if (buckets.size >= maxKeys) evict(now)
        // Saturado mesmo depois da poda significa que ha maxKeys identidades ativas. Recusar
        // identidade nova mantem o teto de memoria rigido; quem ja tem bucket continua atendido
        // normalmente. A checagem nao e atomica com o compute, entao sob corrida o mapa pode
        // passar do teto por algumas entradas — o que importa e nao crescer sem limite.
        if (buckets.size >= maxKeys && !buckets.containsKey(key)) return false
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

    /** Quantos buckets estao residentes agora. Serve a diagnostico e aos testes do teto. */
    fun residentKeys(): Int = buckets.size

    private fun refill(bucket: Bucket, now: Long): Double {
        val elapsedSec = (now - bucket.lastRefillMs).coerceAtLeast(0) / 1000.0
        return (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())
    }

    /**
     * Descarta buckets ociosos e buckets ja recompostos ate a capacidade. Um bucket cheio e
     * indistinguivel de um bucket inexistente, entao a poda nunca devolve credito a quem esta
     * consumindo.
     *
     * A varredura e O(n) sobre o mapa, entao roda no maximo uma vez a cada [minEvictIntervalMs]:
     * com o mapa cheio de identidades ativas nenhuma entrada sai, e sem essa guarda toda
     * requisicao pagaria a varredura inteira — justamente sob a carga que o limitador existe para
     * conter. O CAS no instante da ultima poda tambem garante que so uma thread poda por vez.
     */
    private fun evict(now: Long) {
        val last = lastEvictMs.get()
        if (now - last < minEvictIntervalMs) return
        if (!lastEvictMs.compareAndSet(last, now)) return
        buckets.entries.removeIf { entry ->
            now - entry.value.lastRefillMs >= idleEvictionMs ||
                refill(entry.value, now) >= capacity.toDouble()
        }
    }
}
