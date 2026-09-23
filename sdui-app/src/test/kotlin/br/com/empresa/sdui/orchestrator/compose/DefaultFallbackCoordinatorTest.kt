package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class DefaultFallbackCoordinatorTest {

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val baseInstant = Instant.parse("2026-09-21T10:00:00Z")
    private val clock = TestClock(baseInstant)
    private val metrics = RecordingMetrics()
    private val lastGoodStore = InMemoryLastGoodScreenStore(clock)
    private val matrix = CapabilityMatrix()
    private val budgets = ComposeBudgets(
        maxFallbackAge = Duration.ofHours(1),
        retryAfterSeconds = 10,
    )

    private val coordinator = DefaultFallbackCoordinator(
        lastGood = lastGoodStore,
        matrix = matrix,
        metrics = metrics,
        clock = clock,
        budgets = budgets,
        randomFraction = { 0.5 },
    )

    private val sampleContext = ClientContext(
        platform = ClientPlatform.IOS,
        appVersion = SemVer(8, 14, 2),
        build = "81420",
        osVersion = SemVer(18, 1, 0),
        schemaVersion = "3",
        apiVersion = "1",
        locale = "pt-BR",
        headerCapabilities = emptyList(),
    )

    @Test
    fun `devolve Unavailable quando nao ha last good salvo`() {
        val result = coordinator.fallbackOrUnavailable(
            surface = Surfaces.HOME,
            context = sampleContext,
            channel = Channel.STABLE,
            reason = FallbackReason.REDIS_UNAVAILABLE,
            tags = mapOf("surface" to "home"),
        )

        assertThat(result).isInstanceOf(ComposeResult.Unavailable::class.java)
        val unavailable = result as ComposeResult.Unavailable
        assertThat(unavailable.reason).isEqualTo(FallbackReason.REDIS_UNAVAILABLE)
        assertThat(unavailable.retryAfterSeconds).isEqualTo(10L)
        assertThat(metrics.names()).contains("compose.unavailable")
    }

    @Test
    fun `devolve Success com fallback quando ha last good valido e recente`() {
        val screen = sampleScreen()
        lastGoodStore.put(screen)

        val result = coordinator.fallbackOrUnavailable(
            surface = Surfaces.HOME,
            context = sampleContext,
            channel = Channel.STABLE,
            reason = FallbackReason.DEPENDENCY_TIMEOUT,
            tags = mapOf("surface" to "home"),
        )

        assertThat(result).isInstanceOf(ComposeResult.Success::class.java)
        val success = result as ComposeResult.Success
        assertThat(success.fromCache).isTrue()
        assertThat(success.screen.fallback).isTrue()
        assertThat(success.screen.fallbackReason).isEqualTo(FallbackReason.DEPENDENCY_TIMEOUT)
        assertThat(metrics.names()).contains("compose.fallback")
    }

    @Test
    fun `recusa last good expirado e devolve Unavailable`() {
        val screen = sampleScreen()
        lastGoodStore.put(screen)

        clock.now = baseInstant.plus(Duration.ofHours(2)) // excede maxFallbackAge de 1h

        val result = coordinator.fallbackOrUnavailable(
            surface = Surfaces.HOME,
            context = sampleContext,
            channel = Channel.STABLE,
            reason = FallbackReason.REDIS_UNAVAILABLE,
            tags = mapOf("surface" to "home"),
        )

        assertThat(result).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat(metrics.names()).contains("compose.fallback.expired", "compose.unavailable")
    }

    @Test
    fun `calcula retryAfter com jitter positivo`() {
        val jittered = coordinator.retryAfter(10L)
        assertThat(jittered).isGreaterThan(0L)
    }

    @Test
    fun `reporta falhas de store e cache com metricas adequadas`() {
        coordinator.reportStoreFailure("select", RuntimeException("db error"))
        coordinator.reportCacheWriteFailure("tree", RuntimeException("redis down"))

        assertThat(metrics.names()).contains("store.failure", "cache.write.failure")
    }

    private fun sampleScreen(): ComposedScreen {
        val skeleton = Skeleton(
            skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("accounts", SlotLayout.FIXED, null, 2, listOf("account_card"), required = true),
            ),
            status = SpecStatus.PUBLISHED,
        )
        val sections = listOf(
            Section(
                id = "sec-header",
                slot = "header",
                type = "top_bar",
                typeVersion = 1,
                props = mapOf("title" to "Home"),
            ),
            Section(
                id = "sec-acc",
                slot = "accounts",
                type = "account_card",
                typeVersion = 1,
                props = mapOf("balance" to 100),
            ),
        )
        return ComposedScreen(
            surface = MvpCatalog.SURFACE_HOME,
            platform = ClientPlatform.IOS,
            schemaVersion = "3",
            specRevisionId = "home:ios:1",
            skeletonId = skeleton.skeletonId,
            skeletonHash = "sha256:abcd",
            etag = "W/\"test\"",
            generatedAt = baseInstant,
            locale = "pt-BR",
            channel = Channel.STABLE,
            fallback = false,
            fallbackReason = FallbackReason.NONE,
            omitted = emptyList(),
            client = sampleContext,
            targeting = Targeting(
                platform = ClientPlatform.IOS,
                appVersion = VersionRange(SemVer(8, 0, 0), null),
                osVersion = null,
                schemaVersion = VersionRange(SemVer(3, 0, 0), null),
                requiredCapabilities = emptyList(),
                priority = 100,
                band = "ga",
            ),
            experience = "default",
            skeleton = skeleton,
            sections = sections,
        )
    }
}
