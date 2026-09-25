package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.adapters.observability.MicrometerMetricsRecorder
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.compose.ComposeBudgets
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.compose.DefaultCanaryPolicy
import br.com.empresa.sdui.orchestrator.hydration.HydrationContext
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.HydrationResult
import br.com.empresa.sdui.orchestrator.hydration.SectionHydrator
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.MetricTags
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Propagacao da surface pelo pipeline (T05) e as duas correcoes de singleflight medidas em
 * 2026-09-23 (achado 4): segunda consulta ao cache pelo lider e campos do requisitante no waiter.
 * Tambem fixa o vocabulario finito das tags (achado 1).
 */
class SurfaceIsolationAndSingleflightTest {

    private class CountingHydrator(private val delayMs: Long = 0, private val started: CountDownLatch? = null) :
        SectionHydrator {
        val calls = AtomicInteger()
        override fun supports(type: String, typeVersion: Int) = true
        override fun hydrate(context: HydrationContext, section: Section): HydrationResult {
            calls.incrementAndGet()
            started?.countDown()
            if (delayMs > 0) Thread.sleep(delayMs)
            return HydrationResult.Ok(section.props)
        }
    }

    /** Cache que pausa a thread marcada logo depois de ela ler o primeiro miss. */
    private class GatedCache(private val delegate: InMemoryHydratedScreenCache) : HydratedScreenCache by delegate {
        @Volatile
        var gated: Thread? = null
        val missObserved = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun get(treeKey: String): ComposedScreen? {
            val value = delegate.get(treeKey)
            if (value == null && Thread.currentThread() === gated && missObserved.count > 0) {
                missObserved.countDown()
                release.await()
            }
            return value
        }
    }

    private class Harness(
        hydrators: List<SectionHydrator> = emptyList(),
        treeCache: HydratedScreenCache = InMemoryHydratedScreenCache(),
        metrics: MetricsRecorder = RecordingMetrics(),
        hydrationTimeout: Duration = Duration.ofMillis(80),
    ) {
        val clock: Clock = Clock.systemUTC()
        val lastGood = InMemoryLastGoodScreenStore(clock)
        val specStore = InMemorySpecStore()
        val service: ComposeScreenService

        init {
            val skeletonStore = InMemorySkeletonStore()
            val pointerStore = InMemoryPointerStore()
            val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
            val seed = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
                .use { it.readBytes().decodeToString() }
            HomeSeed(InMemoryCatalogStore(), skeletonStore, specStore, pointerStore, mapper).seedFromCanonicalFixture(
                seed
            )
            service = ComposeScreenService(
                specStore = specStore,
                skeletonStore = skeletonStore,
                pointerStore = pointerStore,
                specCache = InMemorySpecCache(),
                treeCache = treeCache,
                lastGood = lastGood,
                singleflight = InMemoryComposeSingleflight(),
                hydrator = HydrationCoordinator(hydrators, Semaphore(8), hydrationTimeout, metrics),
                matrix = CapabilityMatrix(),
                canaryPolicy = DefaultCanaryPolicy,
                rateLimiter = TokenBucketRateLimiter(capacity = 1_000_000, refillPerSecond = 1_000_000),
                readBulkhead = Bulkhead(64),
                metrics = metrics,
                clock = clock,
                budgets = ComposeBudgets(request = Duration.ofSeconds(10), singleflightWait = Duration.ofSeconds(5)),
            )
        }

        fun compose(
            version: String = "8.14.2",
            build: String = "81420",
            schema: String = "3",
            surface: SurfaceDefinition = Surfaces.HOME,
        ): ComposeResult = service.compose(
            ComposeRequest(
                headers = NegotiateHeaders(schema, "ios", version, build, "pt-BR", "1", "18.1", null),
                surface = surface,
            ),
        )
    }

    @Test
    fun `surface nova nunca recebe arvore nem last good da Home`() {
        val harness = Harness()
        assertThat(harness.compose()).isInstanceOf(ComposeResult.Success::class.java)
        assertThat(harness.lastGood.get("home", ClientPlatform.IOS, Channel.STABLE))
            .isNotNull()

        val catalog = harness.compose(surface = Surfaces.CATALOG)

        assertThat(catalog).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat((catalog as ComposeResult.Unavailable).reason).isEqualTo(FallbackReason.NO_COMPATIBLE_SPEC)
    }

    @Test
    fun `lider consulta o cache de novo e nao recompoe o que outra requisicao acabou de gravar`() {
        val hydrator = CountingHydrator()
        val cache = GatedCache(InMemoryHydratedScreenCache())
        val harness = Harness(hydrators = listOf(hydrator), treeCache = cache)

        val a = Thread.ofVirtual().unstarted { harness.compose() }
        cache.gated = a
        a.start()
        cache.missObserved.await()
        assertThat(harness.compose()).isInstanceOf(ComposeResult.Success::class.java)
        val afterB = hydrator.calls.get()
        cache.release.countDown()
        a.join()

        assertThat(afterB).isEqualTo(8)
        assertThat(hydrator.calls.get()).`as`("A reaproveita a arvore gravada por B").isEqualTo(8)
    }

    @Test
    fun `waiter do singleflight recebe os proprios campos de requisitante`() {
        val started = CountDownLatch(1)
        val harness = Harness(
            hydrators = listOf(CountingHydrator(delayMs = 100, started = started)),
            hydrationTimeout = Duration.ofSeconds(5),
        )
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val leader = pool.submit<ComposeResult> { harness.compose(build = "81420") }
            started.await()
            val waiter = pool.submit<ComposeResult> { harness.compose(build = "99999", version = "8.15.0") }

            val waiterScreen = (waiter.get() as ComposeResult.Success).screen
            val leaderScreen = (leader.get() as ComposeResult.Success).screen
            assertThat(waiterScreen.client.build).isEqualTo("99999")
            assertThat(waiterScreen.client.appVersion.toString()).isEqualTo("8.15.0")
            assertThat(leaderScreen.client.build).isEqualTo("81420")
            assertThat(waiterScreen.sections).isEqualTo(leaderScreen.sections)
        }
    }

    @Test
    fun `tags do pipeline tem vocabulario fechado mesmo com versao e schema variando`() {
        val registry = SimpleMeterRegistry()
        val harness = Harness(metrics = MicrometerMetricsRecorder(registry))
        harness.compose()
        repeat(200) { i -> harness.compose(version = "8.${10 + i % 10}.${i / 10}") }
        repeat(50) { i -> harness.compose(schema = "${4 + i}") }

        val tagKeys = registry.meters.flatMap { meter -> meter.id.tags.map { it.key } }.toSet()
        assertThat(tagKeys).doesNotContain("appVersion")
        val schemas =
            registry.meters.flatMap { meter -> meter.id.tags.filter { it.key == "schemaVersion" }.map { it.value } }
                .toSet()
        assertThat(schemas).containsExactly("3")
        assertThat(MetricTags.schema("4")).isEqualTo(MetricTags.OTHER)
        assertThat(registry.meters.size).isLessThan(20)
    }
}
