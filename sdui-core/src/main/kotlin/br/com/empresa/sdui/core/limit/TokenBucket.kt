package br.com.empresa.sdui.core.limit

import br.com.empresa.sdui.core.model.ClientPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RateLimitKey(
    val identity: String,
    val platform: ClientPlatform,
)

class TokenBucketRateLimiter(
    private val capacity: Long = 100,
    private val refillPerSecond: Long = 100,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<RateLimitKey, Bucket>()
    private val lock = ReentrantLock()

    fun tryConsume(key: RateLimitKey): Boolean {
        lock.withLock {
            val now = clockMs()
            val bucket = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
            val elapsedSec = (now - bucket.lastRefillMs).coerceAtLeast(0) / 1000.0
            bucket.tokens = (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())
            bucket.lastRefillMs = now
            if (bucket.tokens < 1.0) return false
            bucket.tokens -= 1.0
            return true
        }
    }
}
