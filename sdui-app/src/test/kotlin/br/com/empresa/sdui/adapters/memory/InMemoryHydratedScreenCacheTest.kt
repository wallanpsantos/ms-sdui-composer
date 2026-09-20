package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryHydratedScreenCacheTest {

    @Test
    fun `cache nao cresce alem do teto quando o capsHash da requisicao varia`() {
        val cache = InMemoryHydratedScreenCache(maxEntries = 100)
        repeat(1_000) { i ->
            cache.put(treeKey("hash_$i"), screen(), Duration.ofSeconds(60))
        }
        assertThat(cache.residentEntries()).isLessThanOrEqualTo(100)
    }

    @Test
    fun `entrada viva continua legivel depois da poda`() {
        val cache = InMemoryHydratedScreenCache(maxEntries = 100)
        val quente = treeKey("hash_quente")
        // Grava a chave quente por ultimo: a poda descarta as que expiram primeiro.
        repeat(200) { i -> cache.put(treeKey("hash_$i"), screen(), Duration.ofSeconds(1)) }
        cache.put(quente, screen(), Duration.ofSeconds(600))
        assertThat(cache.get(quente)).isNotNull()
    }

    @Test
    fun `invalidate remove apenas a combinacao de surface plataforma e channel`() {
        val cache = InMemoryHydratedScreenCache()
        val ios = treeKey("h1", ClientPlatform.IOS, Channel.STABLE)
        val android = treeKey("h1", ClientPlatform.ANDROID, Channel.STABLE)
        val canary = treeKey("h1", ClientPlatform.IOS, Channel.CANARY)
        listOf(ios, android, canary).forEach { cache.put(it, screen(), Duration.ofSeconds(60)) }

        cache.invalidate(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS, Channel.STABLE)

        assertThat(cache.get(ios)).isNull()
        assertThat(cache.get(android)).isNotNull()
        assertThat(cache.get(canary)).isNotNull()
    }

    private fun treeKey(
        capsHash: String,
        platform: ClientPlatform = ClientPlatform.IOS,
        channel: Channel = Channel.STABLE,
    ) = RedisKeys.tree(MvpCatalog.SURFACE_HOME, platform, "3", "8.14", capsHash, channel)

    private fun screen() = ComposedScreen(
        surface = MvpCatalog.SURFACE_HOME,
        platform = ClientPlatform.IOS,
        schemaVersion = "3",
        specRevisionId = "rev_1",
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        skeletonHash = "sha256:abc123",
        etag = "W/\"rev_1-ios-3\"",
        generatedAt = Instant.parse("2026-09-09T20:00:00Z"),
        locale = "pt-BR",
        channel = Channel.STABLE,
        fallback = false,
        fallbackReason = FallbackReason.NONE,
        omitted = emptyList(),
        client = ClientContext(
            platform = ClientPlatform.IOS,
            appVersion = SemVer(8, 14, 2),
            build = "81420",
            osVersion = SemVer(18, 1, 0),
            schemaVersion = "3",
            locale = "pt-BR",
            apiVersion = "1",
            headerCapabilities = emptyList(),
        ),
        targeting = Targeting(
            platform = ClientPlatform.IOS,
            appVersion = VersionRange(SemVer(8, 0, 0), null),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), null),
            requiredCapabilities = emptyList(),
            priority = 100,
            band = "ga",
        ),
        experience = "home_default",
        skeleton = Skeleton(
            skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            ),
            status = SpecStatus.PUBLISHED,
        ),
        sections = emptyList(),
    )
}
