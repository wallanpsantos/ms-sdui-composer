package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.json.JsonPublicationFingerprint
import br.com.empresa.sdui.orchestrator.port.outbound.PublicationFingerprint
import br.com.empresa.sdui.adapters.invalidation.CacheInvalidationRelay
import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryProjectionStore
import br.com.empresa.sdui.adapters.observability.CardinalityGuardMeterFilter
import br.com.empresa.sdui.adapters.observability.MdcPropagatingExecutor
import br.com.empresa.sdui.adapters.observability.MicrometerMetricsRecorder
import br.com.empresa.sdui.adapters.observability.NoOpMetricsRecorder
import br.com.empresa.sdui.adapters.seed.DemoScreensLoader
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.admin.AuditQueryService
import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import br.com.empresa.sdui.orchestrator.admin.CatalogQueryService
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.compose.AllowlistCanaryPolicy
import br.com.empresa.sdui.orchestrator.compose.ComposeBudgets
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.compose.DefaultFallbackCoordinator
import br.com.empresa.sdui.orchestrator.compose.FallbackCoordinator
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.PassThroughHydrator
import br.com.empresa.sdui.orchestrator.port.inbound.AuditQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.CatalogQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CanaryPolicy
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.config.MeterFilter
import org.slf4j.MDC
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * Beans do servico que nao dependem do modo de persistencia: pipeline, governanca, limites,
 * observabilidade, seed e demonstracao.
 *
 * **Modos de persistencia (ADR-021).** Os stores de governanca e os caches vem de configuracoes
 * separadas, escolhidas por `sdui.persistence.store` (`memory` | `mongo`) e
 * `sdui.persistence.cache` (`memory` | `redis`); o padrao e memoria. Um valor fora da lista falha a
 * subida, e o modo persistente nunca cai silenciosamente para memoria. No modo em memoria:
 *
 * - o estado nao sobrevive a um restart; o que existe apos subir e o que o seed reconstroi;
 * - o estado nao e compartilhado entre instancias: publicar, aprovar ou fazer rollback em um pod
 *   nao muda nada nos demais, e o pointer pode divergir entre replicas.
 *
 * Singleflight e limitador de taxa continuam locais ao processo em qualquer modo.
 */
@Configuration
@EnableConfigurationProperties(SduiProperties::class)
class SduiConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(JsonMapper::class)
    fun jsonMapper(): JsonMapper = JsonMapper.builder(
        JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxTokenCount(50_000).build(),
        ).build(),
    )
        .addModule(KotlinModule.Builder().build())
        .build()

    /**
     * Bulkhead do plano de leitura das surfaces.
     *
     * Separado de proposito do plano administrativo: uma publicacao lenta nao pode consumir a
     * capacidade de atender o app.
     */
    @Bean
    fun readBulkhead(properties: SduiProperties): Bulkhead = Bulkhead(properties.readBulkheadPermits)

    /** Sem consumidor de hidratacao hoje; fica em memoria ate existir um (ADR-021). */
    @Bean
    fun projectionStore(): ProjectionStore = InMemoryProjectionStore()

    @Bean
    fun composeSingleflight(): ComposeSingleflight = InMemoryComposeSingleflight()

    @Bean
    fun capabilityMatrix(): CapabilityMatrix = CapabilityMatrix()

    @Bean
    fun metricsRecorder(meterRegistry: ObjectProvider<MeterRegistry>): MetricsRecorder {
        val registry = meterRegistry.ifAvailable
        return if (registry != null) MicrometerMetricsRecorder(registry) else NoOpMetricsRecorder
    }

    /** Teto de valores por tag nas metricas proprias; aplicado pelo Boot a todo registry. */
    @Bean
    fun sduiCardinalityGuard(properties: SduiProperties): MeterFilter =
        CardinalityGuardMeterFilter(MetricNames.PREFIXES, properties.metricsMaxTagValues)

    @Bean
    fun rateLimiter(properties: SduiProperties): TokenBucketRateLimiter =
        TokenBucketRateLimiter(
            capacity = properties.rateLimitCapacity,
            refillPerSecond = properties.rateLimitRefillPerSecond,
            maxKeys = properties.rateLimitMaxKeys,
        )

    @Bean
    fun canaryPolicy(properties: SduiProperties): CanaryPolicy =
        AllowlistCanaryPolicy(
            mapOf(
                ClientPlatform.IOS to properties.canaryIosBuilds.toSet(),
                ClientPlatform.ANDROID to properties.canaryAndroidBuilds.toSet(),
            ),
        )

    @Bean
    fun hydrationCoordinator(metrics: MetricsRecorder, properties: SduiProperties): HydrationCoordinator =
        HydrationCoordinator(
            hydrators = listOf(PassThroughHydrator()),
            fanOut = Semaphore(properties.hydrationFanout),
            timeout = Duration.ofMillis(properties.hydrationTimeoutMs),
            metrics = metrics,
            executor = MdcPropagatingExecutor(HydrationCoordinator.virtualThreadExecutor()),
        )

    @Bean
    fun cacheInvalidator(
        specCache: SpecCache,
        treeCache: HydratedScreenCache,
        lastGood: LastGoodScreenStore,
        outbox: CacheInvalidationOutbox,
        metrics: MetricsRecorder,
    ): CacheInvalidator = CacheInvalidator(specCache, treeCache, lastGood, outbox, metrics)

    /**
     * Reaplica invalidacoes que ficaram pendentes entre um commit e a invalidacao (queda do
     * processo, cache fora). So agenda quando ha algo persistente: em memoria nao ha o que sobrar.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    fun cacheInvalidationRelay(invalidator: CacheInvalidator, properties: SduiProperties): CacheInvalidationRelay {
        val persistent = properties.persistence.store != StoreMode.MEMORY ||
                properties.persistence.cache != CacheMode.MEMORY
        return CacheInvalidationRelay(invalidator, properties.persistence.invalidationRelayIntervalMs, persistent)
    }

    /**
     * Registra medidores de medicao instantanea (USE - Utilization/Saturation) no Micrometer.
     */
    @Bean
    fun sduiMeterBinder(
        rateLimiter: TokenBucketRateLimiter,
        readBulkhead: Bulkhead,
        invalidator: CacheInvalidator,
        treeCache: HydratedScreenCache,
    ): MeterBinder = MeterBinder { registry ->
        Gauge.builder(MetricNames.RATE_LIMITER_RESIDENT_KEYS, rateLimiter) { it.residentKeys().toDouble() }
            .description("Numero de buckets residentes no limitador de taxa")
            .register(registry)
        Gauge.builder(MetricNames.BULKHEAD_AVAILABLE_PERMITS, readBulkhead) { it.availablePermits().toDouble() }
            .description("Permissoes livres no bulkhead de leitura das surfaces")
            .register(registry)
        Gauge.builder(MetricNames.CACHE_INVALIDATION_PENDING, invalidator) { it.pendingCount().toDouble() }
            .description("Invalidacoes de cache que falharam na ultima drenagem do outbox")
            .register(registry)
        if (treeCache is InMemoryHydratedScreenCache) {
            FunctionCounter.builder(MetricNames.CACHE_WRITE_SKIPPED, treeCache) { it.skippedWrites().toDouble() }
                .description("Escritas de arvore descartadas no teto do cache em memoria")
                .tag("cache", "tree")
                .register(registry)
        }
    }

    @Bean
    fun composeBudgets(properties: SduiProperties): ComposeBudgets = ComposeBudgets(
        treeTtl = Duration.ofSeconds(properties.treeTtlSeconds),
        request = Duration.ofMillis(properties.requestBudgetMs),
        singleflightWait = Duration.ofMillis(properties.singleflightTimeoutMs),
        bulkheadWait = Duration.ofMillis(properties.readBulkheadWaitMs),
        maxFallbackAge = Duration.ofSeconds(properties.maxFallbackAgeSeconds),
        retryAfterSeconds = properties.retryAfterSeconds,
        rateLimitRetryAfterSeconds = properties.rateLimitRetryAfterSeconds,
    )

    @Bean
    fun fallbackCoordinator(
        lastGood: LastGoodScreenStore,
        matrix: CapabilityMatrix,
        metrics: MetricsRecorder,
        clock: Clock,
        budgets: ComposeBudgets,
    ): FallbackCoordinator = DefaultFallbackCoordinator(
        lastGood = lastGood,
        matrix = matrix,
        metrics = metrics,
        clock = clock,
        budgets = budgets,
    )

    @Bean
    fun composeScreenUseCase(
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        pointerStore: PointerStore,
        specCache: SpecCache,
        treeCache: HydratedScreenCache,
        lastGood: LastGoodScreenStore,
        singleflight: ComposeSingleflight,
        hydrator: HydrationCoordinator,
        matrix: CapabilityMatrix,
        canaryPolicy: CanaryPolicy,
        rateLimiter: TokenBucketRateLimiter,
        readBulkhead: Bulkhead,
        metrics: MetricsRecorder,
        clock: Clock,
        budgets: ComposeBudgets,
        fallbackCoordinator: FallbackCoordinator,
    ): ComposeScreenUseCase = ComposeScreenService(
        specStore = specStore,
        skeletonStore = skeletonStore,
        pointerStore = pointerStore,
        specCache = specCache,
        treeCache = treeCache,
        lastGood = lastGood,
        singleflight = singleflight,
        hydrator = hydrator,
        matrix = matrix,
        canaryPolicy = canaryPolicy,
        rateLimiter = rateLimiter,
        readBulkhead = readBulkhead,
        metrics = metrics,
        clock = clock,
        budgets = budgets,
        fallbackCoordinator = fallbackCoordinator,
    )

    @Bean
    fun catalogQueryUseCase(
        catalogStore: CatalogStore,
        skeletonStore: SkeletonStore,
        specStore: SpecStore,
        diffStore: DiffStore,
    ): CatalogQueryUseCase = CatalogQueryService(catalogStore, skeletonStore, specStore, diffStore)

    @Bean
    fun auditQueryUseCase(auditLog: AuditLogStore): AuditQueryUseCase = AuditQueryService(auditLog)

    @Bean
    fun draftUseCase(
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
        matrix: CapabilityMatrix,
    ): DraftUseCase = DraftService(specStore, skeletonStore, catalogStore, matrix)

    @Bean
    fun publicationFingerprint(): PublicationFingerprint = JsonPublicationFingerprint()

    @Bean
    fun publishUseCase(
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
        pointerStore: PointerStore,
        publishStore: PublishRequestStore,
        diffStore: DiffStore,
        auditLog: AuditLogStore,
        idempotency: IdempotencyStore,
        specCache: SpecCache,
        outbox: CacheInvalidationOutbox,
        invalidator: CacheInvalidator,
        tx: TransactionalUnitOfWork,
        matrix: CapabilityMatrix,
        clock: Clock,
        publicationFingerprint: PublicationFingerprint,
    ): PublishUseCase = PublishService(
        specStore, skeletonStore, catalogStore, pointerStore, publishStore, diffStore,
        auditLog, idempotency, specCache, outbox, invalidator, tx, matrix, clock, publicationFingerprint,
    )

    @Bean
    fun rollbackPointerUseCase(
        pointerStore: PointerStore,
        specStore: SpecStore,
        auditLog: AuditLogStore,
        idempotency: IdempotencyStore,
        specCache: SpecCache,
        outbox: CacheInvalidationOutbox,
        invalidator: CacheInvalidator,
        tx: TransactionalUnitOfWork,
        clock: Clock,
    ): RollbackPointerUseCase = RollbackService(
        pointerStore, specStore, auditLog, idempotency, specCache, outbox, invalidator, tx, clock,
    )

    @Bean
    fun homeSeed(
        catalogStore: CatalogStore,
        skeletonStore: SkeletonStore,
        specStore: SpecStore,
        pointerStore: PointerStore,
        jsonMapper: JsonMapper,
    ): HomeSeed = HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, jsonMapper)

    @Bean
    fun demoScreensLoader(
        drafts: DraftUseCase,
        publish: PublishUseCase,
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
    ): DemoScreensLoader = DemoScreensLoader(drafts, publish, specStore, skeletonStore, catalogStore)

    /**
     * Seed canonico e, so com `sdui.demo-enabled`, a carga dos quatro exemplos pelo fluxo
     * administrativo. Os dois sao idempotentes: um segundo boot com persistencia nao regrava nada.
     */
    @Bean
    fun homeSeedRunner(
        homeSeed: HomeSeed,
        demoScreensLoader: DemoScreensLoader,
        properties: SduiProperties,
    ): ApplicationRunner = ApplicationRunner {
        if (properties.seedIos) {
            withEntryPoint("seed") {
                val resource = ClassPathResource("seed/contrato-sdui-home-definitivo.json")
                homeSeed.seedFromCanonicalFixture(resource.inputStream.bufferedReader().use { it.readText() })
            }
        }
        if (properties.demoEnabled) {
            withEntryPoint("demo") { demoScreensLoader.loadAll() }
        }
    }

    private inline fun withEntryPoint(entryPoint: String, block: () -> Unit) {
        MDC.put("entryPoint", entryPoint)
        try {
            block()
        } finally {
            MDC.remove("entryPoint")
        }
    }
}
