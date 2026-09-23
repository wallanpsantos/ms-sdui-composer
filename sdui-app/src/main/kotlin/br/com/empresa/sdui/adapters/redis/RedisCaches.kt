package br.com.empresa.sdui.adapters.redis

import br.com.empresa.sdui.adapters.json.DomainJson
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Formato dos valores gravados no Redis, versionado (P08, ADR-021).
 *
 * Cada valor e um envelope JSON com o numero do formato. Versao diferente da esperada e tratada
 * como ausencia: a entrada e ignorada e reescrita na proxima composicao, sem erro para o cliente.
 * Nenhum conteudo regulado entra aqui: a arvore e composta de specs que ja passaram por
 * `PiiGuard` na publicacao e nao carrega dado de usuario.
 */
object RedisCacheCodec {
    const val FORMAT_VERSION: Int = 1

    data class TreeEntry(val v: Int, val screen: ComposedScreen)
    data class SpecEntry(val v: Int, val spec: Spec)

    fun encodeScreen(screen: ComposedScreen): ByteArray = DomainJson.writeBytes(TreeEntry(FORMAT_VERSION, screen))

    fun decodeScreen(bytes: ByteArray): ComposedScreen? =
        DomainJson.read(bytes, TreeEntry::class.java).takeIf { it.v == FORMAT_VERSION }?.screen

    fun encodeSpec(spec: Spec): ByteArray = DomainJson.writeBytes(SpecEntry(FORMAT_VERSION, spec))

    fun decodeSpec(bytes: ByteArray): Spec? =
        DomainJson.read(bytes, SpecEntry::class.java).takeIf { it.v == FORMAT_VERSION }?.spec
}

/**
 * Mede cada operacao de cache com nome constante e tags finitas (cache, operacao, desfecho). A
 * falha e registrada e relancada: quem decide o que fazer com ela e o pipeline.
 */
internal class CacheOperationTimer(private val metrics: MetricsRecorder) {
    fun <T> time(cache: String, operation: String, block: () -> T): T {
        val started = System.nanoTime()
        var outcome = "error"
        try {
            val value = block()
            outcome = "ok"
            return value
        } finally {
            metrics.recordNanos(
                MetricNames.CACHE_OPERATION,
                System.nanoTime() - started,
                mapOf("cache" to cache, "operation" to operation, "outcome" to outcome),
            )
        }
    }

    fun skipped(cache: String) {
        metrics.increment(MetricNames.CACHE_WRITE_SKIPPED, mapOf("cache" to cache))
    }
}

/**
 * Cache de arvore com indice limitado por escopo. O score do ZSET e o vencimento da arvore.
 * Insercao, poda de membros vencidos e invalidacao sao atomicas no Redis (ADR-022).
 */
class RedisHydratedScreenCache(
    private val redis: RedisTemplate<String, ByteArray>,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
    private val maxEntries: Int = 10_000,
) : HydratedScreenCache {
    private val timer = CacheOperationTimer(metrics)

    init {
        require(maxEntries > 0)
    }

    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val bytes = timer.time(CACHE, "get") { redis.opsForValue().get(treeKey) } ?: return null
        return decodeOrNull(bytes)
    }

    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        require(ttl.toMillis() > 0)
        val bytes = RedisCacheCodec.encodeScreen(screen)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        val admitted = timer.time(CACHE, "put") {
            redis.execute(
                PUT_SCRIPT,
                listOf(treeKey, indexKey(screen.surface, screen.platform, screen.channel)),
                bytes,
                ttl.toMillis().toString().toByteArray(),
                maxEntries.toString().toByteArray(),
            )
        }
        if (admitted != 1L) timer.skipped(CACHE)
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        timer.time(CACHE, "invalidate") {
            redis.execute(INVALIDATE_SCRIPT, listOf(indexKey(surface, platform, channel)))
        }
    }

    private fun indexKey(surface: String, platform: ClientPlatform, channel: Channel): String =
        "sdui:treeidx:v2:$surface:${platform.wire()}:${channel.wire()}"

    private companion object {
        const val CACHE: String = "tree"

        /** ARGV: payload, ttl em ms, teto. TIME evita divergencia de relogio entre instancias. */
        val PUT_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local ttl = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if not redis.call('ZSCORE', KEYS[2], KEYS[1]) and redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
            local last = redis.call('ZREVRANGE', KEYS[2], 0, 0, 'WITHSCORES')
            redis.call('PEXPIRE', KEYS[2], math.ceil(tonumber(last[2]) - now + ttl))
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** O indice tem teto: nenhuma lista de chaves e transferida para o heap da aplicacao. */
        val INVALIDATE_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local keys = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, key in ipairs(keys) do redis.call('DEL', key) end
            redis.call('DEL', KEYS[1])
            return #keys
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}

/** Cache de specs publicados no Redis. Spec publicado e imutavel: a entrada so pode ficar inutil. */
class RedisSpecCache(
    private val redis: RedisTemplate<String, ByteArray>,
    private val ttl: Duration,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
) : SpecCache {
    private val timer = CacheOperationTimer(metrics)

    override fun get(specRevisionId: String, platform: ClientPlatform): Spec? {
        val bytes = timer.time(CACHE, "get") { redis.opsForValue().get(RedisKeys.spec(specRevisionId, platform)) }
            ?: return null
        return runCatching { RedisCacheCodec.decodeSpec(bytes) }.getOrNull()
    }

    override fun put(spec: Spec) {
        val bytes = RedisCacheCodec.encodeSpec(spec)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        timer.time(CACHE, "put") { redis.opsForValue().set(RedisKeys.spec(spec.specRevisionId, spec.platform), bytes, ttl) }
    }

    override fun invalidate(specRevisionId: String, platform: ClientPlatform) {
        timer.time(CACHE, "invalidate") { redis.delete(RedisKeys.spec(specRevisionId, platform)) }
    }

    private companion object {
        const val CACHE: String = "spec"
    }
}

/**
 * Last good no Redis, versionado pelo pointer (P10, ADR-021).
 *
 * Cada chave e um hash com `v` (versao do pointer), `p` (arvore) e `t` (instante da gravacao). Os
 * scripts Lua comparam e gravam atomicamente: gravacao de versao menor que a guardada e recusada,
 * e a invalidacao troca a arvore por uma lapide na versao nova. Assim nem uma composicao atrasada
 * nem uma invalidacao reaplicada pelo relay devolvem ao fallback a revisao que saiu do ar.
 */
class RedisLastGoodScreenStore(
    private val redis: RedisTemplate<String, ByteArray>,
    private val clock: Clock,
    private val ttl: Duration,
    private val maxEntryBytes: Int,
    metrics: MetricsRecorder,
) : LastGoodScreenStore {
    private val timer = CacheOperationTimer(metrics)

    override fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen? {
        val key = RedisKeys.lastGood(surface, platform, channel)
        val fields = timer.time(CACHE, "get") {
            redis.opsForHash<String, ByteArray>().multiGet(key, listOf(FIELD_PAYLOAD, FIELD_STORED_AT))
        }
        val payload = fields.getOrNull(0) ?: return null
        val storedAt = fields.getOrNull(1)?.let { String(it, Charsets.UTF_8).toLongOrNull() } ?: return null
        val screen = decodeOrNull(payload) ?: return null
        return StoredScreen(screen, Instant.ofEpochMilli(storedAt))
    }

    override fun put(screen: ComposedScreen) {
        val bytes = RedisCacheCodec.encodeScreen(screen)
        if (bytes.size > maxEntryBytes) {
            timer.skipped(CACHE)
            return
        }
        val key = RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)
        timer.time(CACHE, "put") {
            redis.execute(
                PUT_SCRIPT,
                listOf(key),
                screen.pointerVersion.toString().toByteArray(),
                bytes,
                clock.instant().toEpochMilli().toString().toByteArray(),
                ttl.toMillis().toString().toByteArray(),
            )
        }
    }

    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long) {
        val key = RedisKeys.lastGood(surface, platform, channel)
        timer.time(CACHE, "invalidate") {
            redis.execute(
                TOMBSTONE_SCRIPT,
                listOf(key),
                pointerVersion.toString().toByteArray(),
                ttl.toMillis().toString().toByteArray(),
            )
        }
    }

    private companion object {
        const val CACHE: String = "last_good"
        const val FIELD_PAYLOAD: String = "p"
        const val FIELD_STORED_AT: String = "t"

        /** Grava se a versao guardada nao for maior. ARGV: versao, arvore, instante, ttl em ms. */
        val PUT_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local current = redis.call('HGET', KEYS[1], 'v')
            if current and tonumber(current) > tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'v', ARGV[1], 'p', ARGV[2], 't', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** Lapide na versao nova, salvo se ja houver arvore dessa versao ou maior. ARGV: versao, ttl. */
        val TOMBSTONE_SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local current = redis.call('HGET', KEYS[1], 'v')
            if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'v', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}

/** Valor ilegivel ou de outro formato vira ausencia: o cache se refaz na proxima composicao. */
private fun decodeOrNull(bytes: ByteArray): ComposedScreen? =
    runCatching { RedisCacheCodec.decodeScreen(bytes) }.getOrNull()
