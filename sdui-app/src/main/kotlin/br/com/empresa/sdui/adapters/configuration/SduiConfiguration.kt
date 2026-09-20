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
import br.com.empresa.sdui.adapters.observability.MicrometerMetricsRecorder
import br.com.empresa.sdui.adapters.observability.NoOpMetricsRecorder
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.admin.CatalogQueryService
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.compose.AllowlistCanaryPolicy
import br.com.empresa.sdui.orchestrator.compose.ComposeBudgets
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
import br.com.empresa.sdui.adapters.observability.MdcPropagatingExecutor
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.MDC
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
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

/**
 * Parametros operacionais, ajustaveis sem recompilar sob o prefixo `sdui`.
 *
 * Reune o que se mexe em producao: prazos, limites de concorrencia, tetos de memoria, allowlists
 * de canary. Os defaults aqui valem quando nada e configurado.
 */
@ConfigurationProperties(prefix = "sdui")
data class SduiProperties(
    val canaryIosBuilds: List<String> = emptyList(),
    val canaryAndroidBuilds: List<String> = emptyList(),
    val rateLimitCapacity: Long = 10_000,
    val rateLimitRefillPerSecond: Long = 10_000,
    /** Teto de buckets residentes no limitador; acima dele os ociosos e os cheios sao descartados. */
    val rateLimitMaxKeys: Int = 100_000,
    val treeTtlSeconds: Long = 60,
    /** Teto de arvores no cache de composicao. Ver [InMemoryHydratedScreenCache]. */
    val treeCacheMaxEntries: Int = 10_000,
    val hydrationTimeoutMs: Long = 80,
    val hydrationFanout: Int = 8,
    /** Base do `Retry-After` do 503, antes do jitter. */
    val retryAfterSeconds: Long = 5,
    /** Base do `Retry-After` do 429, antes do jitter. */
    val rateLimitRetryAfterSeconds: Long = 2,
    /**
     * Prazo total de uma requisicao de composicao. Limita as esperas do pipeline, nunca o trabalho
     * em si. Todas as esperas configuraveis abaixo precisam caber dentro dele.
     */
    val requestBudgetMs: Long = 1_000,
    /**
     * Quanto um waiter espera o lider do singleflight. Menor que [requestBudgetMs] de proposito:
     * desistir e ir para o last good antes do cliente desistir e o que torna a espera util.
     */
    val singleflightTimeoutMs: Long = 150,
    /** Chamadas simultaneas de leitura de store. Ver [Bulkhead]. */
    val readBulkheadPermits: Int = 32,
    /** Espera maxima por uma permissao do bulkhead de leitura antes de degradar. */
    val readBulkheadWaitMs: Long = 50,
    /** Idade maxima de um last good servido como fallback. */
    val maxFallbackAgeSeconds: Long = 86_400,
    /** Validade de uma chave de idempotencia administrativa. */
    val idempotencyTtlSeconds: Long = 86_400,
    /** Teto de chaves de idempotencia residentes. */
    val idempotencyMaxKeys: Int = 10_000,
    val seedIos: Boolean = true,
)

/**
 * Beans do servico.
 *
 * **Estado em memoria.** Todos os stores registrados aqui sao in-memory: specs, skeletons, catalogo,
 * pointer, publish requests, audit log, idempotencia e caches vivem no heap do processo. Nao ha
 * adapter de MongoDB nem de Redis cabeado — as autoconfiguracoes dos dois estao excluidas em
 * `SduiApplication`. Na pratica isso significa que:
 *
 * - o estado nao sobrevive a um restart; o que existe apos subir e o que o seed reconstroi;
 * - o estado nao e compartilhado entre instancias: publicar, aprovar ou fazer rollback em um pod
 *   nao muda nada nos demais, e o pointer pode divergir entre replicas.
 *
 * Enquanto os adapters persistentes nao existirem, o servico so opera corretamente como instancia
 * unica, ou com o plano de administracao dirigido a uma instancia designada.
 */
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
    fun idempotencyStore(clock: Clock, properties: SduiProperties): IdempotencyStore =
        InMemoryIdempotencyStore(
            clock = clock,
            ttl = Duration.ofSeconds(properties.idempotencyTtlSeconds),
            maxEntries = properties.idempotencyMaxKeys,
        )

    @Bean
    fun hydratedScreenCache(properties: SduiProperties): HydratedScreenCache =
        InMemoryHydratedScreenCache(properties.treeCacheMaxEntries)

    @Bean
    fun lastGoodScreenStore(clock: Clock): LastGoodScreenStore = InMemoryLastGoodScreenStore(clock)

    /**
     * Bulkhead do plano de leitura da home.
     *
     * Separado de proposito do plano administrativo: uma publicacao lenta nao pode consumir a
     * capacidade de atender o app.
     */
    @Bean
    fun readBulkhead(properties: SduiProperties): Bulkhead = Bulkhead(properties.readBulkheadPermits)

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
        return if (registry != null) MicrometerMetricsRecorder(registry) else NoOpMetricsRecorder
    }

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
    fun hydrationCoordinator(metrics: MetricsRecorder, properties: SduiProperties): HydrationCoordinator {
        val virtualThreadFactory = Thread.ofVirtual().name("sdui-hydrate-", 0).factory()
        val baseExecutor = Executor { runnable -> virtualThreadFactory.newThread(runnable).start() }
        return HydrationCoordinator(
            hydrators = listOf(PassThroughHydrator()),
            fanOut = Semaphore(properties.hydrationFanout),
            timeout = Duration.ofMillis(properties.hydrationTimeoutMs),
            metrics = metrics,
            executor = MdcPropagatingExecutor(baseExecutor),
        )
    }

    /**
     * Registra medidores de medicao instantanea (USE - Utilization/Saturation) no Micrometer.
     */
    @Bean
    fun sduiMeterBinder(
        rateLimiter: TokenBucketRateLimiter,
        readBulkhead: Bulkhead,
    ): MeterBinder = MeterBinder { registry ->
        Gauge.builder("rate_limiter.resident_keys", rateLimiter) { it.residentKeys().toDouble() }
            .description("Numero de buckets residentes no limitador de taxa")
            .register(registry)
        Gauge.builder("compose.bulkhead.available_permits", readBulkhead) { it.availablePermits().toDouble() }
            .description("Permissoes livres no bulkhead de leitura da Home")
            .register(registry)
    }

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
        readBulkhead = readBulkhead,
        metrics = metrics,
        clock = clock,
        budgets = ComposeBudgets(
            treeTtl = Duration.ofSeconds(properties.treeTtlSeconds),
            request = Duration.ofMillis(properties.requestBudgetMs),
            singleflightWait = Duration.ofMillis(properties.singleflightTimeoutMs),
            bulkheadWait = Duration.ofMillis(properties.readBulkheadWaitMs),
            maxFallbackAge = Duration.ofSeconds(properties.maxFallbackAgeSeconds),
            retryAfterSeconds = properties.retryAfterSeconds,
            rateLimitRetryAfterSeconds = properties.rateLimitRetryAfterSeconds,
        ),
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
        lastGood: LastGoodScreenStore,
        tx: TransactionalUnitOfWork,
        matrix: CapabilityMatrix,
        clock: Clock,
    ): PublishUseCase = PublishService(
        specStore, skeletonStore, catalogStore, pointerStore, publishStore, diffStore,
        auditLog, idempotency, specCache, treeCache, lastGood, tx, matrix, clock,
    )

    @Bean
    fun rollbackPointerUseCase(
        pointerStore: PointerStore,
        specStore: SpecStore,
        auditLog: AuditLogStore,
        idempotency: IdempotencyStore,
        specCache: SpecCache,
        treeCache: HydratedScreenCache,
        lastGood: LastGoodScreenStore,
        tx: TransactionalUnitOfWork,
        clock: Clock,
    ): RollbackPointerUseCase = RollbackService(
        pointerStore, specStore, auditLog, idempotency, specCache, treeCache, lastGood, tx, clock,
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
            MDC.put("entryPoint", "seed")
            try {
                val resource = ClassPathResource("seed/contrato-sdui-home-definitivo.json")
                homeSeed.seedFromCanonicalFixture(resource.inputStream.bufferedReader().use { it.readText() })
            } finally {
                MDC.remove("entryPoint")
            }
        }
    }
}
