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
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.orchestrator.compose.ComposeBudgets
import br.com.empresa.sdui.orchestrator.compose.ComposeRequest
import br.com.empresa.sdui.orchestrator.compose.ComposeResult
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.compose.DefaultCanaryPolicy
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.PassThroughHydrator
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Semaphore

/**
 * Cobre a politica de resiliencia do pipeline de composicao (ADR-014).
 *
 * Os desfechos degradados so valem alguma coisa se forem observaveis e se acontecerem no momento
 * certo. Cada teste aqui fixa um deles: o 503 tem contador, o last good tem prazo de validade, o
 * `Retry-After` varia, o orcamento interrompe o pipeline e o bulkhead lotado degrada em vez de
 * enfileirar.
 */
class ComposeResilienceTest {

    // ---------------------------------------------------------------- infra do teste

    /** Relogio de teste: o unico jeito de envelhecer o last good sem esperar um dia. */
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    /** Store de specs que pode ser derrubado no meio do teste, como um Redis que cai. */
    private class FlakySpecStore(
        private val delegate: InMemorySpecStore,
    ) : SpecStore by delegate {
        @Volatile
        var failing: Boolean = false

        override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> {
            if (failing) error("store fora do ar")
            return delegate.listPublished(surface, platform)
        }

        fun clear() = delegate.clear()
    }

    private class Harness(
        budgets: ComposeBudgets = ComposeBudgets(),
        bulkhead: Bulkhead = Bulkhead(8),
        randomFraction: () -> Double = { 0.5 },
        nanos: List<Long> = listOf(0L),
    ) {
        val clock = MutableClock(Instant.parse("2026-09-20T12:00:00Z"))
        val metrics = RecordingMetrics()
        val specStore = FlakySpecStore(InMemorySpecStore())
        val skeletonStore = InMemorySkeletonStore()
        val catalogStore = InMemoryCatalogStore()
        val pointerStore = InMemoryPointerStore()
        val specCache = InMemorySpecCache()
        val treeCache = InMemoryHydratedScreenCache()
        val lastGood = InMemoryLastGoodScreenStore(clock)

        private val jsonMapper: JsonMapper =
            JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        private val remainingNanos = ArrayDeque(nanos)

        val service = ComposeScreenService(
            specStore = specStore,
            skeletonStore = skeletonStore,
            pointerStore = pointerStore,
            specCache = specCache,
            treeCache = treeCache,
            lastGood = lastGood,
            singleflight = InMemoryComposeSingleflight(),
            hydrator = HydrationCoordinator(
                hydrators = listOf(PassThroughHydrator()),
                fanOut = Semaphore(8),
                timeout = Duration.ofMillis(80),
                metrics = metrics,
            ),
            matrix = CapabilityMatrix(),
            canaryPolicy = DefaultCanaryPolicy,
            rateLimiter = TokenBucketRateLimiter(capacity = 10_000, refillPerSecond = 10_000),
            readBulkhead = bulkhead,
            metrics = metrics,
            clock = clock,
            budgets = budgets,
            randomFraction = randomFraction,
            // O ultimo valor do roteiro se repete: assim o teste so declara os instantes que
            // importam para a decisao sob exame.
            nanoTime = { if (remainingNanos.size > 1) remainingNanos.removeFirst() else remainingNanos.first() },
        )

        fun seed() {
            val json = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
                .use { it.readBytes().decodeToString() }
            HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, jsonMapper)
                .seedFromCanonicalFixture(json)
        }

        fun compose(): ComposeResult = service.compose(
            ComposeRequest(
                headers = NegotiateHeaders(
                    uiSchemaVersion = "3",
                    clientPlatform = "ios",
                    clientVersion = "8.14.2",
                    clientBuild = "81420",
                    acceptLanguage = "pt-BR",
                    apiVersion = "1",
                    osVersion = "18.1",
                    componentCapabilities = null,
                ),
                identity = "ios:81420",
            ),
        )

        fun names(): List<String> = metrics.names()

        fun tagsOf(metric: String): Map<String, String> =
            metrics.samples.first { it.name == metric }.tags

        fun valueOf(metric: String): Long? =
            metrics.samples.first { it.name == metric }.value
    }

    // ---------------------------------------------------------------- testes

    @Test
    fun `503 sem last good tem contador proprio com o motivo`() {
        val harness = Harness()

        val result = harness.compose()

        assertThat(result).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat(harness.names()).contains("compose.unavailable")
        assertThat(harness.tagsOf("compose.unavailable"))
            .containsEntry("fallbackReason", "no_compatible_spec")
            .containsEntry("platform", "ios")
            .containsKey("channel")
    }

    @Test
    fun `last good dentro do prazo serve com fallback e publica a idade`() {
        val harness = Harness()
        harness.seed()
        assertThat(harness.compose()).isInstanceOf(ComposeResult.Success::class.java)

        // Derruba a fonte e o cache: so resta o last good.
        harness.specStore.clear()
        harness.treeCache.clear()
        harness.clock.now = harness.clock.now.plus(Duration.ofMinutes(10))

        val result = harness.compose()

        assertThat(result).isInstanceOf(ComposeResult.Success::class.java)
        assertThat((result as ComposeResult.Success).screen.fallback).isTrue()
        assertThat(harness.names()).contains("compose.fallback", "compose.fallback.age.ms")
        assertThat(harness.valueOf("compose.fallback.age.ms")).isEqualTo(Duration.ofMinutes(10).toMillis())
    }

    @Test
    fun `last good velho demais e recusado em favor do 503`() {
        val harness = Harness(budgets = ComposeBudgets(maxFallbackAge = Duration.ofHours(24)))
        harness.seed()
        assertThat(harness.compose()).isInstanceOf(ComposeResult.Success::class.java)

        harness.specStore.clear()
        harness.treeCache.clear()
        harness.clock.now = harness.clock.now.plus(Duration.ofHours(25))

        val result = harness.compose()

        assertThat(result).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat(harness.names()).contains("compose.fallback.expired", "compose.unavailable")
        assertThat(harness.names()).doesNotContain("compose.fallback")
    }

    @Test
    fun `retry after do 503 carrega jitter em vez do valor configurado`() {
        val baixo = Harness(randomFraction = { 0.0 }).compose()
        val alto = Harness(randomFraction = { 1.0 }).compose()

        assertThat((baixo as ComposeResult.Unavailable).retryAfterSeconds).isEqualTo(3)
        assertThat((alto as ComposeResult.Unavailable).retryAfterSeconds).isEqualTo(7)
    }

    @Test
    fun `orcamento estourado e sinalizado sem abandonar a composicao`() {
        // Roteiro do relogio: inicio do orcamento, prazo do bulkhead e, na terceira leitura, dois
        // segundos gastos — o dobro do orcamento.
        val harness = Harness(nanos = listOf(0L, 0L, Duration.ofSeconds(2).toNanos()))
        harness.seed()

        val result = harness.compose()

        assertThat(harness.names()).contains("compose.deadline.exceeded")
        assertThat(harness.tagsOf("compose.deadline.exceeded")).containsEntry("stage", "select")
        // Sinal, nao veredito: abortar aqui trocaria a selecao ja paga por um 503 num pod frio.
        assertThat(result).isInstanceOf(ComposeResult.Success::class.java)
        assertThat((result as ComposeResult.Success).screen.fallback).isFalse()
        assertThat(harness.names()).contains("compose.miss")
    }

    @Test
    fun `bulkhead lotado degrada em vez de enfileirar a leitura`() {
        val harness = Harness(bulkhead = Bulkhead(0))
        harness.seed()

        val result = harness.compose()

        assertThat(result).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat(harness.names()).contains("compose.bulkhead.rejected")
        assertThat(harness.tagsOf("compose.bulkhead.rejected")).containsEntry("stage", "select")
    }

    @Test
    fun `falha de store e reportada em vez de engolida`() {
        val harness = Harness()
        harness.seed()
        assertThat(harness.compose()).isInstanceOf(ComposeResult.Success::class.java)

        harness.metrics.samples.clear()
        harness.treeCache.clear()
        harness.specStore.failing = true

        val result = harness.compose()

        // A falha degrada para o last good, e deixa rastro: antes, este caminho era um catch mudo.
        assertThat(result).isInstanceOf(ComposeResult.Success::class.java)
        assertThat((result as ComposeResult.Success).screen.fallback).isTrue()
        assertThat(harness.names()).contains("store.failure")
        assertThat(harness.tagsOf("store.failure")).containsEntry("stage", "select")
    }
}
