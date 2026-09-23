package br.com.empresa.sdui.adapters.redis

import br.com.empresa.sdui.adapters.configuration.PersistenceProperties
import br.com.empresa.sdui.adapters.configuration.RedisCacheConfiguration
import br.com.empresa.sdui.adapters.configuration.SduiProperties
import br.com.empresa.sdui.adapters.configuration.SduiRedisProperties
import br.com.empresa.sdui.adapters.health.RedisCacheHealthIndicator
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.health.contributor.Status
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

/**
 * Caches Redis contra um Redis real (P08–P10). So roda com `SDUI_IT_REDIS_URL`
 * (localmente, `docker compose --profile infra up -d` e `SDUI_IT_REDIS_URL=redis://localhost:6379`).
 * Cada teste usa um prefixo de surface proprio para nao colidir com outras execucoes.
 */
@EnabledIfEnvironmentVariable(named = "SDUI_IT_REDIS_URL", matches = ".+")
class RedisCachesIT {
    companion object {
        private lateinit var factory: LettuceConnectionFactory
        private lateinit var template: RedisTemplate<String, ByteArray>

        @JvmStatic
        @BeforeAll
        fun connect() {
            val properties = SduiProperties(
                persistence = PersistenceProperties(redis = SduiRedisProperties(url = System.getenv("SDUI_IT_REDIS_URL"))),
            )
            val configuration = RedisCacheConfiguration()
            factory = configuration.sduiRedisConnectionFactory(properties).also {
                it.afterPropertiesSet()
                it.start()
            }
            template = configuration.sduiRedisTemplate(factory)
        }

        @JvmStatic
        @AfterAll
        fun disconnect() {
            factory.destroy()
        }
    }

    private val metrics = RecordingMetrics()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var surface: String

    @BeforeEach
    fun uniqueSurface() {
        surface = "it${UUID.randomUUID().toString().take(8)}"
    }

    @Test
    fun `arvore grava, le e e invalidada pelo indice do escopo`() {
        val cache = RedisHydratedScreenCache(template, 262_144, metrics)
        val key = RedisKeys.tree(surface, ClientPlatform.IOS, "3", "rev_1", "caps", Channel.STABLE)
        val other = RedisKeys.tree(surface, ClientPlatform.ANDROID, "3", "rev_1", "caps", Channel.STABLE)
        cache.put(key, screen(1, ClientPlatform.IOS), Duration.ofSeconds(60))
        cache.put(other, screen(1, ClientPlatform.ANDROID), Duration.ofSeconds(60))
        assertThat(cache.get(key)?.specRevisionId).isEqualTo("rev_1")

        cache.invalidate(surface, ClientPlatform.IOS, Channel.STABLE)
        assertThat(cache.get(key)).isNull()
        assertThat(cache.get(other)).isNotNull()
        assertThat(metrics.names()).contains("cache.operation.ms")
    }

    @Test
    fun `entrada acima do teto de bytes nao e gravada e vira metrica`() {
        val cache = RedisHydratedScreenCache(template, 16, metrics)
        val key = RedisKeys.tree(surface, ClientPlatform.IOS, "3", "rev_big", "caps", Channel.STABLE)
        cache.put(key, screen(1, ClientPlatform.IOS), Duration.ofSeconds(60))
        assertThat(cache.get(key)).isNull()
        assertThat(metrics.names()).contains("cache.write.skipped")
    }

    @Test
    fun `last good recusa escrita atrasada e a lapide e idempotente`() {
        val store = RedisLastGoodScreenStore(template, clock, Duration.ofMinutes(5), 262_144, metrics)
        store.put(screen(1, ClientPlatform.IOS, "rev_old"))
        assertThat(store.get(surface, ClientPlatform.IOS, Channel.STABLE)?.screen?.specRevisionId).isEqualTo("rev_old")

        store.invalidate(surface, ClientPlatform.IOS, Channel.STABLE, 2)
        store.put(screen(1, ClientPlatform.IOS, "rev_old"))
        assertThat(store.get(surface, ClientPlatform.IOS, Channel.STABLE)).isNull()

        store.put(screen(2, ClientPlatform.IOS, "rev_new"))
        store.invalidate(surface, ClientPlatform.IOS, Channel.STABLE, 2)
        val stored = store.get(surface, ClientPlatform.IOS, Channel.STABLE)
        assertThat(stored?.screen?.specRevisionId).isEqualTo("rev_new")
        assertThat(stored?.storedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `cache de spec usa o mesmo formato versionado e health responde UP`() {
        val cache = RedisSpecCache(template, Duration.ofMinutes(1), 262_144, metrics)
        assertThat(cache.get("rev_inexistente_$surface", ClientPlatform.IOS)).isNull()
        assertThat(RedisCacheHealthIndicator(factory).health().status).isEqualTo(Status.UP)
    }

    private fun screen(pointerVersion: Long, platform: ClientPlatform, revision: String = "rev_1") = ComposedScreen(
        surface = surface,
        platform = platform,
        schemaVersion = "3",
        specRevisionId = revision,
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        skeletonHash = "sha256:00",
        etag = "W/\"x\"",
        generatedAt = Instant.EPOCH,
        locale = "pt-BR",
        channel = Channel.STABLE,
        fallback = false,
        fallbackReason = FallbackReason.NONE,
        omitted = emptyList(),
        client = ClientContext(platform, SemVer(8, 14, 2), "1", null, schemaVersion = "3", locale = "pt-BR", apiVersion = "1", headerCapabilities = emptyList()),
        targeting = Targeting(platform, VersionRange(SemVer(8, 0, 0), null), null, VersionRange(SemVer(3, 0, 0), null), emptyList(), 1, "t"),
        experience = "e",
        skeleton = Skeleton(MvpCatalog.SKELETON_HOME_DEFAULT, 1, surface, MvpCatalog.SKELETON_LAYOUT, emptyList(), SpecStatus.PUBLISHED),
        sections = emptyList(),
        pointerVersion = pointerVersion,
    )

    @Test
    fun `indice tem teto e remove membro vencido mesmo sob trafego continuo`() {
        val cache = RedisHydratedScreenCache(template, 262_144, metrics, maxEntries = 2)
        val keys = (1..3).map { RedisKeys.tree(surface, ClientPlatform.IOS, "3", "rev_$it", "caps", Channel.STABLE) }
        val index = "sdui:treeidx:v2:$surface:ios:stable"
        keys.forEachIndexed { i, key -> cache.put(key, screen(1, ClientPlatform.IOS, "rev_${i + 1}"), Duration.ofSeconds(60)) }
        assertThat(template.opsForZSet().zCard(index)).isEqualTo(2L)
        assertThat(cache.get(keys[2])).isNull()
        // Simula o TTL da primeira arvore, sem depender de sleep ou do relogio do teste.
        template.delete(keys[0])
        template.opsForZSet().add(index, keys[0].toByteArray(), 0.0)
        cache.put(keys[2], screen(1, ClientPlatform.IOS, "rev_3"), Duration.ofSeconds(60))
        assertThat(template.opsForZSet().zCard(index)).isEqualTo(2L)
        assertThat(template.opsForZSet().score(index, keys[0].toByteArray())).isNull()
        assertThat(cache.get(keys[2])).isNotNull()
        cache.invalidate(surface, ClientPlatform.IOS, Channel.STABLE)
        assertThat(keys.map(cache::get)).containsOnlyNulls()
        assertThat(template.hasKey(index)).isFalse()
    }

}
