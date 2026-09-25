package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.invalidation.CacheInvalidationRelay
import br.com.empresa.sdui.adapters.json.JsonPublicationFingerprint
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
import br.com.empresa.sdui.orchestrator.port.outbound.PublicationFingerprint
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
 * Configuracao central de beans e infraestrutura do BFF Server-Driven UI.
 *
 * ### 1. O que faz
 * Registra e conecta os componentes centrais do servico que nao dependem da escolha do backend
 * de persistencia: casos de uso de composicao e governanca, politicas de resiliencia (bulkhead,
 * rate limiter, singleflight), observabilidade Micrometer, orquestradores e seeds de inicializacao.
 *
 * ### 2. Para que serve
 * Desacopla o orquestrador e a camada de entrega da infraestrutura concreta, garantindo a
 * inicializacao correta e tipada de todos os servicos a partir das propriedades declaradas
 * em [SduiProperties].
 *
 * ### 3. Como funciona
 * O Spring IoC inicializa esta classe atraves de `@Configuration` e `@EnableConfigurationProperties`.
 * Os beans de persistencia e cache sao delegados para configuracoes modulares condicionadas
 * (`MemoryPersistenceConfiguration` ou `DurablePersistenceConfiguration`, via `ADR-021`),
 * enquanto esta classe estabelece os componentes imutaveis e os servicos de aplicacao do dominio.
 */
@Configuration
@EnableConfigurationProperties(SduiProperties::class)
class SduiConfiguration {

    /**
     * Provedor de relogio do sistema em UTC.
     *
     * ### 1. O que faz
     * Registra uma instancia singleton de [Clock] configurada com a zona UTC.
     *
     * ### 2. Para que serve
     * Padroniza a geracao de timestamps de auditoria, medicao de tempo de vida (TTL) de caches
     * e validacao de expiracao de fallbacks em toda a aplicacao.
     *
     * ### 3. Como funciona
     * Retorna `Clock.systemUTC()`, permitindo facil substituicao por relogios controlados em testes.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Mapeador Jackson 3 para uso geral em infraestrutura e seed.
     *
     * ### 1. O que faz
     * Instancia um [JsonMapper] do Jackson 3 configurado com o modulo Kotlin e restricoes de seguranca de leitura.
     *
     * ### 2. Para que serve
     * Habilita a desserializacao de arquivos de seed e envelopes de infraestrutura de forma isolada,
     * sem compartilhar estado ou configuracoes globais com o mapper do Spring MVC.
     *
     * ### 3. Como funciona
     * Configura [StreamReadConstraints] com profundidade maxima de 64 niveis e teto de 50.000 tokens
     * para prevencao contra ataques de exaustao de memoria (Denial of Service).
     */
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
     * Bulkhead do plano de leitura das surfaces móveis.
     *
     * ### 1. O que faz
     * Cria e registra um semaforo de isolamento de recursos [Bulkhead] para operacoes de consulta e composicao.
     *
     * ### 2. Para que serve
     * Impede que rajadas de requisicoes de leitura de surfaces esgotem todos os recursos do servico,
     * isolando o trafego do cliente movel do plano administrativo de governanca.
     *
     * ### 3. Como funciona
     * Inicializa o [Bulkhead] com a quantidade de permissoes simultaneas declaradas em [SduiProperties.readBulkheadPermits].
     */
    @Bean
    fun readBulkhead(properties: SduiProperties): Bulkhead = Bulkhead(properties.readBulkheadPermits)

    /**
     * Armazenamento temporario de projecoes de hidratacao.
     *
     * ### 1. O que faz
     * Fornece o store em memoria para dados e atributos parciais de secoes hidratadas.
     *
     * ### 2. Para que serve
     * Atende ao pipeline de composicao na resolucao de projecoes dinamicas enquanto adaptadores
     * externos de hidratacao remota nao estiverem ativos (`ADR-021`).
     *
     * ### 3. Como funciona
     * Retorna uma nova instancia de [InMemoryProjectionStore] com politica automatica de descarte.
     */
    @Bean
    fun projectionStore(): ProjectionStore = InMemoryProjectionStore()

    /**
     * Mecanismo de deduplicacao de composicao concorrente (Singleflight).
     *
     * ### 1. O que faz
     * Cria o coordenador de composicao exclusiva [ComposeSingleflight] local a instancia.
     *
     * ### 2. Para que serve
     * Elimina tempestades de requisicoes (thundering herd / stampede) sob cache miss, garantindo que
     * apenas a primeira requisicao monte a arvore enquanto as subsequentes aguardam o resultado.
     *
     * ### 3. Como funciona
     * Instancia [InMemoryComposeSingleflight] baseado em `ConcurrentHashMap` e `CompletableFuture`.
     */
    @Bean
    fun composeSingleflight(): ComposeSingleflight = InMemoryComposeSingleflight()

    /**
     * Matriz canonica de capacidades tecnicas suportadas.
     *
     * ### 1. O que faz
     * Registra o componente [CapabilityMatrix] responsavel pelo catalogo de componentes suportados.
     *
     * ### 2. Para que serve
     * Cruza os tipos e versoes de secoes homologados com a plataforma e versao informada pelo cliente nativo.
     *
     * ### 3. Como funciona
     * Mantem a tabela imutavel de capabilities aprovadas utilizadas no estagio de Filter do pipeline.
     */
    @Bean
    fun capabilityMatrix(): CapabilityMatrix = CapabilityMatrix()

    /**
     * Gravador de metricas operacionais Micrometer.
     *
     * ### 1. O que faz
     * Disponibiliza o adaptador de metricas [MetricsRecorder] para coleta de dados de telemetria.
     *
     * ### 2. Para que serve
     * Permite instrumentar latencias, taxas de acerto de cache, desfechos de fallback e taxas de erro.
     *
     * ### 3. Como funciona
     * Inspeciona a presenca de um [MeterRegistry] do Spring; se disponivel, instancia [MicrometerMetricsRecorder],
     * senao utiliza o fallback sem efeito colateral [NoOpMetricsRecorder].
     */
    @Bean
    fun metricsRecorder(meterRegistry: ObjectProvider<MeterRegistry>): MetricsRecorder {
        val registry = meterRegistry.ifAvailable
        return if (registry != null) MicrometerMetricsRecorder(registry) else NoOpMetricsRecorder
    }

    /**
     * Filtro de protecao contra explosao de cardinalidade em metricas.
     *
     * ### 1. O que faz
     * Cria um [MeterFilter] que intercepta o registro de tags metricas nos prefixos do SDUI.
     *
     * ### 2. Para que serve
     * Impede que valores variaveis (como identificadores de usuarios ou versoes malformadas) sobrecarreguem
     * a memoria do registry de metricas do Prometheus/Micrometer.
     *
     * ### 3. Como funciona
     * Instancia [CardinalityGuardMeterFilter] limitando a quantidade de valores distintos por tag
     * conforme configurado em [SduiProperties.metricsMaxTagValues].
     */
    @Bean
    fun sduiCardinalityGuard(properties: SduiProperties): MeterFilter =
        CardinalityGuardMeterFilter(MetricNames.PREFIXES, properties.metricsMaxTagValues)

    /**
     * Limitador de taxa de requisicoes por cliente (Rate Limiter).
     *
     * ### 1. O que faz
     * Registra a instancia global de [TokenBucketRateLimiter] para protecao do pipeline de leitura.
     *
     * ### 2. Para que serve
     * Mitiga ataques de negacao de servico e requisicoes em rajada, retornando HTTP 429 sob saturacao.
     *
     * ### 3. Como funciona
     * Configura buckets por cliente com capacidade, taxa de reabastecimento e teto de chaves residentes
     * derivados de [SduiProperties].
     */
    @Bean
    fun rateLimiter(properties: SduiProperties): TokenBucketRateLimiter =
        TokenBucketRateLimiter(
            capacity = properties.rateLimitCapacity,
            refillPerSecond = properties.rateLimitRefillPerSecond,
            maxKeys = properties.rateLimitMaxKeys,
        )

    /**
     * Politica de avaliacao de elegibilidade de builds para canary.
     *
     * ### 1. O que faz
     * Registra o avaliador de liberacao canario [CanaryPolicy] baseado em allowlists por plataforma.
     *
     * ### 2. Para que serve
     * Determina se a requisicao de um cliente movel pertence a uma coorte autorizada a receber versoes canary da tela.
     *
     * ### 3. Como funciona
     * Instancia [AllowlistCanaryPolicy] mapeando as plataformas [ClientPlatform.IOS] e [ClientPlatform.ANDROID]
     * para os conjuntos de builds configurados em [SduiProperties.canaryIosBuilds] e [SduiProperties.canaryAndroidBuilds].
     */
    @Bean
    fun canaryPolicy(properties: SduiProperties): CanaryPolicy =
        AllowlistCanaryPolicy(
            mapOf(
                ClientPlatform.IOS to properties.canaryIosBuilds.toSet(),
                ClientPlatform.ANDROID to properties.canaryAndroidBuilds.toSet(),
            ),
        )

    /**
     * Coordenador assincrono de hidratacao de secoes.
     *
     * ### 1. O que faz
     * Instancia o [HydrationCoordinator] responsavel por paralelizar a busca de dados dinamicos de secoes.
     *
     * ### 2. Para que serve
     * Otimiza a latencia de composicao atraves de fan-out concorrente seguro em Virtual Threads sem travar a requisicao.
     *
     * ### 3. Como funciona
     * Utiliza [MdcPropagatingExecutor] sobre Virtual Threads do Java 25, aplicando semaforo de controle de fan-out
     * e timeout estrito baseados em [SduiProperties.hydrationFanout] e [SduiProperties.hydrationTimeoutMs].
     */
    @Bean
    fun hydrationCoordinator(metrics: MetricsRecorder, properties: SduiProperties): HydrationCoordinator =
        HydrationCoordinator(
            hydrators = listOf(PassThroughHydrator()),
            fanOut = Semaphore(properties.hydrationFanout),
            timeout = Duration.ofMillis(properties.hydrationTimeoutMs),
            metrics = metrics,
            executor = MdcPropagatingExecutor(HydrationCoordinator.virtualThreadExecutor()),
        )

    /**
     * Coordenador de invalidacao de caches de tela e specs.
     *
     * ### 1. O que faz
     * Registra a instancia de [CacheInvalidator] encarregada de sincronizar a limpeza de caches locais e remotos.
     *
     * ### 2. Para que serve
     * Garante que ativacoes de novas revisoes e rollbacks de ponteiros expulsem imediatamente entradas defasadas
     * de [SpecCache], [HydratedScreenCache] e [LastGoodScreenStore].
     *
     * ### 3. Como funciona
     * Recebe os stores de cache e o outbox de invalidacoes, executando a limpeza e enfileirando pendencias
     * no outbox em caso de falha transiente na comunicacao com o Redis.
     */
    @Bean
    fun cacheInvalidator(
        specCache: SpecCache,
        treeCache: HydratedScreenCache,
        lastGood: LastGoodScreenStore,
        outbox: CacheInvalidationOutbox,
        metrics: MetricsRecorder,
    ): CacheInvalidator = CacheInvalidator(specCache, treeCache, lastGood, outbox, metrics)

    /**
     * Agendador de repasse (relay) de invalidacoes de cache pendentes.
     *
     * ### 1. O que faz
     * Instancia o servico [CacheInvalidationRelay] encarregado de reprocessar invalidacoes gravadas no outbox.
     *
     * ### 2. Para que serve
     * Assegura a consistencia eventual dos caches mesmo em caso de indisponibilidade momentanea do Redis
     * durante o commit de publicacao de specs.
     *
     * ### 3. Como funciona
     * Executa periodicamente conforme [PersistenceProperties.invalidationRelayIntervalMs]
     * caso o armazenamento ou cache operem em modo persistente (`StoreMode.MONGO` ou `CacheMode.REDIS`).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    fun cacheInvalidationRelay(invalidator: CacheInvalidator, properties: SduiProperties): CacheInvalidationRelay {
        val persistent = properties.persistence.store != StoreMode.MEMORY ||
                properties.persistence.cache != CacheMode.MEMORY
        return CacheInvalidationRelay(invalidator, properties.persistence.invalidationRelayIntervalMs, persistent)
    }

    /**
     * Vinculador de medidores de saturacao e utilizacao (USE) no Micrometer.
     *
     * ### 1. O que faz
     * Registra o [MeterBinder] que expoe metricas instantaneas no [MeterRegistry].
     *
     * ### 2. Para que serve
     * Habilita o monitoramento em tempo real da capacidade interna: chaves no rate limiter, permissoes do bulkhead,
     * escritas descartadas no cache de arvore e invalidacoes pendentes no outbox.
     *
     * ### 3. Como funciona
     * Constroi Gauges e FunctionCounters sobre as instancias de [TokenBucketRateLimiter], [Bulkhead],
     * [CacheInvalidator] e [InMemoryHydratedScreenCache].
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

    /**
     * Prazos e orcamentos temporais consolidados da composicao de telas.
     *
     * ### 1. O que faz
     * Cria a instancia imutavel de [ComposeBudgets] a partir das configuracoes operacionais.
     *
     * ### 2. Para que serve
     * Padroniza os timeouts de estagio, prazos de singleflight, bulkhead, retencao de fallback e TTLs de arvore.
     *
     * ### 3. Como funciona
     * Converte os tempos declarados em [SduiProperties] em objetos tipados [Duration].
     */
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

    /**
     * Coordenador da escada de fallback de composicao (`ADR-007`).
     *
     * ### 1. O que faz
     * Cria e registra o componente [FallbackCoordinator] para degradacao controlada de requisicoes de tela.
     *
     * ### 2. Para que serve
     * Garante a entrega de arvore de contingencia (last good) ou emissao de HTTP 503 com `Retry-After` com jitter
     * quando a composicao em tempo real falha ou estoura o orcamento de tempo.
     *
     * ### 3. Como funciona
     * Implementa [DefaultFallbackCoordinator], verificando a compatibilidade de capacidades e idade maxima
     * das telas recuperadas de [LastGoodScreenStore].
     */
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

    /**
     * Caso de uso principal de composicao e entrega de telas SDUI.
     *
     * ### 1. O que faz
     * Monta e disponibiliza o bean de [ComposeScreenUseCase] responsavel pelo fluxo completo de montagem de telas.
     *
     * ### 2. Para que serve
     * Atende as chamadas `GET /v1/surfaces/{surface}` orquestrando as 6 etapas do pipeline:
     * Negotiate -> Select -> Filter -> Hydrate -> Guard -> Compose.
     *
     * ### 3. Como funciona
     * Instancia o [ComposeScreenService] injetando todos os repositorios, caches, coordenadores de resiliencia,
     * matriz de capacidades tecnicas, politicas de canary e orcamentos temporais.
     */
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

    /**
     * Caso de uso de consultas administrativas ao catalogo e skeletons.
     *
     * ### 1. O que faz
     * Cria e registra o bean [CatalogQueryUseCase] para visualizacao da estrutura e diferencas de telas.
     *
     * ### 2. Para que serve
     * Suporta os endpoints administrativos de inspecao de catalogo de componentes, revisoes de skeletons e diffs de specs.
     *
     * ### 3. Como funciona
     * Instancia [CatalogQueryService] com os adaptadores [CatalogStore], [SkeletonStore], [SpecStore] e [DiffStore].
     */
    @Bean
    fun catalogQueryUseCase(
        catalogStore: CatalogStore,
        skeletonStore: SkeletonStore,
        specStore: SpecStore,
        diffStore: DiffStore,
    ): CatalogQueryUseCase = CatalogQueryService(catalogStore, skeletonStore, specStore, diffStore)

    /**
     * Caso de uso de consulta da trilha de auditoria administrativa.
     *
     * ### 1. O que faz
     * Registra o bean [AuditQueryUseCase] para leitura de eventos de auditoria append-only.
     *
     * ### 2. Para que serve
     * Permite consultar o historico imutavel de acoes executadas no plano administrativo (criacao de draft, aprovacao, rollback).
     *
     * ### 3. Como funciona
     * Cria o [AuditQueryService] conectando diretamente ao [AuditLogStore].
     */
    @Bean
    fun auditQueryUseCase(auditLog: AuditLogStore): AuditQueryUseCase = AuditQueryService(auditLog)

    /**
     * Caso de uso de criacao e validacao de rascunhos (drafts) de telas.
     *
     * ### 1. O que faz
     * Registra a instancia de [DraftUseCase] responsavel pelo ciclo inicial de confeccao de specs.
     *
     * ### 2. Para que serve
     * Permite que operadores criem e editem novos rascunhos de specs com validacao de schema e capabilities.
     *
     * ### 3. Como funciona
     * Instancia [DraftService] validando as regras do skeleton correspondente e as capacidades homologadas.
     */
    @Bean
    fun draftUseCase(
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
        matrix: CapabilityMatrix,
    ): DraftUseCase = DraftService(specStore, skeletonStore, catalogStore, matrix)

    /**
     * Gerador de impressao digital imutavel (fingerprint) para publicacoes.
     *
     * ### 1. O que faz
     * Registra a implementacao concreta de [PublicationFingerprint] baseada em hash Jackson 3 e SHA-256.
     *
     * ### 2. Para que serve
     * Garante a integridade criptografica do conteudo submetido a publicacao na governanca maker-checker.
     *
     * ### 3. Como funciona
     * Instancia [JsonPublicationFingerprint], produzindo digests deterministicos com ordenacao de chaves.
     */
    @Bean
    fun publicationFingerprint(): PublicationFingerprint = JsonPublicationFingerprint()

    /**
     * Caso de uso de publicacao de specs sob governanca maker-checker estrita.
     *
     * ### 1. O que faz
     * Registra o bean [PublishUseCase] para orquestracao do ciclo de vida de publicacao de specs.
     *
     * ### 2. Para que serve
     * Assegura que mudancas de telas passem por submissao, aprovacao independente, verificacao de integridade
     * e ativacao de ponteiros de forma atomica com invalidacao imediata de caches.
     *
     * ### 3. Como funciona
     * Instancia [PublishService] com transacao atomica ([TransactionalUnitOfWork]), chave de idempotencia e auditoria.
     */
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

    /**
     * Caso de uso de reversao emergencial (rollback) de ponteiros de surfaces.
     *
     * ### 1. O que faz
     * Disponibiliza o bean [RollbackPointerUseCase] para reverter rapidamente ponteiros de producao.
     *
     * ### 2. Para que serve
     * Mitiga incidentes operacionais ao redirecionar imediatamente o trafego para uma revisao estavel anterior.
     *
     * ### 3. Como funciona
     * Instancia [RollbackService] executando a troca atomica de ponteiro e disparando a invalidacao imediata dos caches.
     */
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

    /**
     * Inicializador de dados essenciais e fixture canonica da Home.
     *
     * ### 1. O que faz
     * Registra o bean [HomeSeed] responsavel por popular o catalogo e o skeleton inicial.
     *
     * ### 2. Para que serve
     * Garante que o servico contenha os dados minimos operacionais da surface Home ao iniciar pela primeira vez.
     *
     * ### 3. Como funciona
     * Le e carrega o arquivo de fixture canonica em formato JSON, gravando as entidades nos stores correspondentes.
     */
    @Bean
    fun homeSeed(
        catalogStore: CatalogStore,
        skeletonStore: SkeletonStore,
        specStore: SpecStore,
        pointerStore: PointerStore,
        jsonMapper: JsonMapper,
    ): HomeSeed = HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, jsonMapper)

    /**
     * Carregador de cenarios de telas demonstrativas.
     *
     * ### 1. O que faz
     * Cria e registra o bean [DemoScreensLoader] para provisionar exemplos de telas.
     *
     * ### 2. Para que serve
     * Suporta testes de integracao, homologacao e demonstracoes da flexibilidade do Server-Driven UI.
     *
     * ### 3. Como funciona
     * Invoca os servicos de draft e publicacao para carregar telas pré-configuradas no catalogo e nos stores.
     */
    @Bean
    fun demoScreensLoader(
        drafts: DraftUseCase,
        publish: PublishUseCase,
        specStore: SpecStore,
        skeletonStore: SkeletonStore,
        catalogStore: CatalogStore,
    ): DemoScreensLoader = DemoScreensLoader(drafts, publish, specStore, skeletonStore, catalogStore)

    /**
     * Executor de inicializacao da aplicacao Spring Boot.
     *
     * ### 1. O que faz
     * Registra um [ApplicationRunner] que executa rotinas de seed e demonstracao na subida do servico.
     *
     * ### 2. Para que serve
     * Assegura que os dados de bootstrap da surface Home e eventuais cenarios de demo sejam carregados de forma idempotente.
     *
     * ### 3. Como funciona
     * Inspeciona as flags [SduiProperties.seedIos] e [SduiProperties.demoEnabled], executando as cargas
     * sob demarcacao contextual no MDC (`entryPoint`).
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

    /**
     * Utilitario de demarcacao de ponto de entrada no contexto de logging (MDC).
     *
     * ### 1. O que faz
     * Envolve a execucao de um bloco de codigo configurando a chave `entryPoint` no MDC do SLF4J.
     *
     * ### 2. Para que serve
     * Facilita a correlacao de logs gerados durante o startup (ex.: `seed`, `demo`) em ferramentas de observabilidade.
     *
     * ### 3. Como funciona
     * Insere `entryPoint` no MDC, executa a lambda recebida e remove a chave no bloco `finally`.
     */
    private inline fun withEntryPoint(entryPoint: String, block: () -> Unit) {
        MDC.put("entryPoint", entryPoint)
        try {
            block()
        } finally {
            MDC.remove("entryPoint")
        }
    }
}
