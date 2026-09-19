package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
import br.com.empresa.sdui.adapters.memory.InMemoryDiffStore
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryIdempotencyStore
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemoryProjectionStore
import br.com.empresa.sdui.adapters.memory.InMemoryPublishRequestStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.memory.InMemoryTransactionalUnitOfWork
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.adapters.observability.MicrometerMetricsRecorder
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.admin.CatalogQueryService
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.compose.AllowlistCanaryPolicy
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.PassThroughHydrator
import br.com.empresa.sdui.orchestrator.port.inbound.CatalogQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CanaryPolicy
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Semaphore

@ConfigurationProperties(prefix = "sdui")
data class SduiProperties(
    val canaryIosBuilds: List<String> = emptyList(),
    val canaryAndroidBuilds: List<String> = emptyList(),
    val rateLimitCapacity: Long = 10_000,
    val rateLimitRefillPerSecond: Long = 10_000,
    val treeTtlSeconds: Long = 60,
    val hydrationTimeoutMs: Long = 80,
    val hydrationFanout: Int = 8,
    val retryAfterSeconds: Long = 5,
    val seedIos: Boolean = true,
)

@Configuration
@EnableConfigurationProperties(SduiProperties::class)
class SduiConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(JsonMapper::class)
    fun jsonMapper(): JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Bean
    fun specStore(): SpecStore = InMemorySpecStore()

    @Bean
    fun skeletonStore(): SkeletonStore = InMemorySkeletonStore()

    @Bean
    fun catalogStore(): CatalogStore = InMemoryCatalogStore()

    @Bean
    fun pointerStore(): PointerStore = InMemoryPointerStore()

    @Bean
    fun publishRequestStore(): PublishRequestStore = InMemoryPublishRequestStore()

    @Bean
    fun diffStore(): DiffStore = InMemoryDiffStore()

    @Bean
    fun auditLogStore(): AuditLogStore = InMemoryAuditLogStore()

    @Bean
    fun idempotencyStore(): IdempotencyStore = InMemoryIdempotencyStore()

    @Bean
    fun hydratedScreenCache(): HydratedScreenCache = InMemoryHydratedScreenCache()

    @Bean
    fun lastGoodScreenStore(): LastGoodScreenStore = InMemoryLastGoodScreenStore()

    @Bean
    fun specCache(): SpecCache = InMemorySpecCache()

    @Bean
    fun projectionStore(): ProjectionStore = InMemoryProjectionStore()

    @Bean
    fun transactionalUnitOfWork(): TransactionalUnitOfWork = InMemoryTransactionalUnitOfWork()

    @Bean
    fun composeSingleflight(): ComposeSingleflight = InMemoryComposeSingleflight()

    @Bean
    fun capabilityMatrix(): CapabilityMatrix = CapabilityMatrix()

    @Bean
    fun metricsRecorder(meterRegistry: ObjectProvider<MeterRegistry>): MetricsRecorder {
        val registry = meterRegistry.ifAvailable
        return if (registry != null) MicrometerMetricsRecorder(registry) else RecordingMetrics()
    }

    @Bean
    fun rateLimiter(properties: SduiProperties): TokenBucketRateLimiter =
        TokenBucketRateLimiter(properties.rateLimitCapacity, properties.rateLimitRefillPerSecond)

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
        metrics: MetricsRecorder,
        clock: Clock,
        properties: SduiProperties,
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
        metrics = metrics,
        clock = clock,
        treeTtl = Duration.ofSeconds(properties.treeTtlSeconds),
        retryAfterSeconds = properties.retryAfterSeconds,
    )

    @Bean
    fun catalogQueryUseCase(
        catalogStore: CatalogStore,
        skeletonStore: SkeletonStore,
        specStore: SpecStore,
        diffStore: DiffStore,
    ): CatalogQueryUseCase = CatalogQueryService(catalogStore, skeletonStore, specStore, diffStore)

    @Bean
    fun draftUseCase(
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
        matrix: CapabilityMatrix,
    ): DraftUseCase = DraftService(specStore, skeletonStore, catalogStore, matrix)

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
        treeCache: HydratedScreenCache,
        tx: TransactionalUnitOfWork,
        matrix: CapabilityMatrix,
        clock: Clock,
    ): PublishUseCase = PublishService(
        specStore, skeletonStore, catalogStore, pointerStore, publishStore, diffStore,
        auditLog, idempotency, specCache, treeCache, tx, matrix, clock,
    )

    @Bean
    fun rollbackPointerUseCase(
        pointerStore: PointerStore,
        specStore: SpecStore,
        auditLog: AuditLogStore,
        idempotency: IdempotencyStore,
        specCache: SpecCache,
        treeCache: HydratedScreenCache,
        tx: TransactionalUnitOfWork,
        clock: Clock,
    ): RollbackPointerUseCase = RollbackService(
        pointerStore, specStore, auditLog, idempotency, specCache, treeCache, tx, clock,
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
    fun homeSeedRunner(homeSeed: HomeSeed, properties: SduiProperties): ApplicationRunner = ApplicationRunner {
        if (properties.seedIos) {
            val resource = ClassPathResource("seed/contrato-sdui-home-definitivo.json")
            homeSeed.seedFromCanonicalFixture(resource.inputStream.bufferedReader().use { it.readText() })
        }
    }
}
