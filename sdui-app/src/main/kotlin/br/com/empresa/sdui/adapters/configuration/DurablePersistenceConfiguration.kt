package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.health.MongoStoreHealthIndicator
import br.com.empresa.sdui.adapters.health.RedisCacheHealthIndicator
import br.com.empresa.sdui.adapters.mongo.MongoAuditLogStore
import br.com.empresa.sdui.adapters.mongo.MongoCacheInvalidationOutbox
import br.com.empresa.sdui.adapters.mongo.MongoCatalogStore
import br.com.empresa.sdui.adapters.mongo.MongoDiffStore
import br.com.empresa.sdui.adapters.mongo.MongoIdempotencyStore
import br.com.empresa.sdui.adapters.mongo.MongoPointerStore
import br.com.empresa.sdui.adapters.mongo.MongoPublishRequestStore
import br.com.empresa.sdui.adapters.mongo.MongoSchema
import br.com.empresa.sdui.adapters.mongo.MongoSessionContext
import br.com.empresa.sdui.adapters.mongo.MongoSkeletonStore
import br.com.empresa.sdui.adapters.mongo.MongoSpecStore
import br.com.empresa.sdui.adapters.mongo.MongoTransactionalUnitOfWork
import br.com.empresa.sdui.adapters.redis.RedisHydratedScreenCache
import br.com.empresa.sdui.adapters.redis.RedisLastGoodScreenStore
import br.com.empresa.sdui.adapters.redis.RedisSpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener
import io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Configuracao de persistencia duravel e governanca no MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Instancia e registra os adaptadores de governanca baseados no driver oficial do MongoDB
 * quando a propriedade `sdui.persistence.store` for definida como `mongo`.
 *
 * ### 2. Para que serve
 * Fornece autoridade duravel, consistente e distribuida para operacoes de publicacao, drafts,
 * trilha de auditoria e controle de idempotencia em ambientes com multiplas instancias do BFF.
 *
 * ### 3. Como funciona
 * Desativa as autoconfiguracoes genericas do Spring Boot, construindo manualmente o [MongoClient]
 * com limites rigorosos de conexao (pool, timeouts de socket e operacao CSOT), e registra cada
 * store do dominio sobre sessoes transacionais e esquemas de colecao com indices unicos.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["store"], havingValue = "mongo")
class MongoStoreConfiguration {

    /**
     * Cliente oficial do MongoDB com politicas de resiliencia explicitas.
     *
     * ### 1. O que faz
     * Cria e registra a instancia singleton de [MongoClient] configurada para o servico.
     *
     * ### 2. Para que serve
     * Estabelece comunicacao direta com o cluster MongoDB com garantias estritas de timeout e observabilidade.
     *
     * ### 3. Como funciona
     * Valida a presenca de URI nao vazia, desabilita retries automaticos do driver (`retryWrites=false`,
     * `retryReads=false` conforme `ADR-014`), aplica timeouts de conexao e operacao e conecta listeners Micrometer.
     */
    @Bean(destroyMethod = "close")
    fun sduiMongoClient(properties: SduiProperties, meterRegistry: ObjectProvider<MeterRegistry>): MongoClient {
        val mongo = properties.persistence.mongo
        require(mongo.uri.isNotBlank()) {
            "sdui.persistence.store=mongo exige sdui.persistence.mongo.uri (SDUI_PERSISTENCE_MONGO_URI)"
        }
        val registry = meterRegistry.ifAvailable
        val builder = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.uri))
            .applyToClusterSettings { it.serverSelectionTimeout(mongo.serverSelectionTimeoutMs, TimeUnit.MILLISECONDS) }
            .applyToSocketSettings { it.connectTimeout(mongo.connectTimeoutMs, TimeUnit.MILLISECONDS) }
            .applyToConnectionPoolSettings { pool ->
                pool.maxSize(mongo.maxPoolSize)
                    .minSize(mongo.minPoolSize)
                    .maxWaitTime(mongo.maxWaitTimeMs, TimeUnit.MILLISECONDS)
                if (registry != null) pool.addConnectionPoolListener(MongoMetricsConnectionPoolListener(registry))
            }
            .timeout(mongo.operationTimeoutMs, TimeUnit.MILLISECONDS)
            .retryWrites(false)
            .retryReads(false)
        if (registry != null) builder.addCommandListener(MongoMetricsCommandListener(registry))
        return MongoClients.create(builder.build())
    }

    /**
     * Referencia a base de dados do MongoDB com indices garantidos.
     *
     * ### 1. O que faz
     * Conecta a base de dados configurada e dispara a criacao dos indices necessarios.
     *
     * ### 2. Para que serve
     * Assegura que as colecoes de governanca possuam restricoes de unicidade e indices de consulta corretos.
     *
     * ### 3. Como funciona
     * Obtem o banco via `client.getDatabase(...)` e executa [MongoSchema.ensureIndexes] na inicializacao.
     */
    @Bean
    fun sduiMongoDatabase(client: MongoClient, properties: SduiProperties): MongoDatabase =
        client.getDatabase(properties.persistence.mongo.database).also { MongoSchema.ensureIndexes(it) }

    /**
     * Contexto de sessoes transacionais do MongoDB.
     *
     * ### 1. O que faz
     * Cria e registra o bean [MongoSessionContext] para controle de sessoes de transacao.
     *
     * ### 2. Para que serve
     * Permite que multiplos stores compartilhem a mesma sessao de transacao na thread corrente.
     *
     * ### 3. Como funciona
     * Mantem um `ThreadLocal` de sessao cliente associado ao ciclo de vida da transacao.
     */
    @Bean
    fun mongoSessionContext(): MongoSessionContext = MongoSessionContext()

    /**
     * Repositorio de especificacoes (specs) persistido no MongoDB.
     *
     * ### 1. O que faz
     * Registra o componente [SpecStore] sobre a colecao `specs` do MongoDB.
     *
     * ### 2. Para que serve
     * Armazena revisoes de specs de telas com controle de concorrencia otimista e historico imutavel.
     *
     * ### 3. Como funciona
     * Instancia [MongoSpecStore] validando o tamanho maximo de documento BSON (`maxDocumentBytes`).
     */
    @Bean
    fun specStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): SpecStore =
        MongoSpecStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    /**
     * Repositorio de esqueletos (skeletons) persistido no MongoDB.
     *
     * ### 1. O que faz
     * Registra o componente [SkeletonStore] sobre a colecao `skeletons` do MongoDB.
     *
     * ### 2. Para que serve
     * Mantem a estrutura de slots e regras de secoes das surfaces persistida de forma duravel.
     *
     * ### 3. Como funciona
     * Instancia [MongoSkeletonStore] com validacao de schema e limites de bytes de entrada.
     */
    @Bean
    fun skeletonStore(
        database: MongoDatabase,
        sessions: MongoSessionContext,
        properties: SduiProperties
    ): SkeletonStore =
        MongoSkeletonStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    /**
     * Repositorio de catalogo de componentes no MongoDB.
     *
     * ### 1. O que faz
     * Registra o bean [CatalogStore] persistido no MongoDB.
     *
     * ### 2. Para que serve
     * Compartilha o catalogo oficial de componentes e schemas homologados entre todos os nos do servico.
     *
     * ### 3. Como funciona
     * Retorna [MongoCatalogStore] persistindo o snapshot do catalogo com limite de tamanho seguro.
     */
    @Bean
    fun catalogStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): CatalogStore =
        MongoCatalogStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    /**
     * Repositorio de ponteiros de ativacao de telas no MongoDB.
     *
     * ### 1. O que faz
     * Registra a implementacao duravel de [PointerStore].
     *
     * ### 2. Para que serve
     * Rastreia atomicamente qual revisao de tela esta ativa em producao para cada combinacao de surface, plataforma e canal.
     *
     * ### 3. Como funciona
     * Instancia [MongoPointerStore] com atualizacoes atomicas suportadas por indices unicos.
     */
    @Bean
    fun pointerStore(database: MongoDatabase, sessions: MongoSessionContext): PointerStore =
        MongoPointerStore(database, sessions)

    /**
     * Repositorio de solicitacoes de publicacao no MongoDB.
     *
     * ### 1. O que faz
     * Cria e registra o bean [PublishRequestStore] sobre o banco MongoDB.
     *
     * ### 2. Para que serve
     * Persiste as solicitacoes do fluxo de governanca maker-checker com estados e transicoes formais.
     *
     * ### 3. Como funciona
     * Retorna [MongoPublishRequestStore] gerenciando mudancas de status concorrentes com compare-and-set.
     */
    @Bean
    fun publishRequestStore(database: MongoDatabase, sessions: MongoSessionContext): PublishRequestStore =
        MongoPublishRequestStore(database, sessions)

    /**
     * Repositorio de diferencas estruturais (diffs) de specs no MongoDB.
     *
     * ### 1. O que faz
     * Registra o bean [DiffStore] persistido no banco MongoDB.
     *
     * ### 2. Para que serve
     * Mantem o historico de diferencas entre revisoes de specs para auditoria e visualizacao administrativa.
     *
     * ### 3. Como funciona
     * Retorna [MongoDiffStore] gravando deltas calculados de forma duravel.
     */
    @Bean
    fun diffStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): DiffStore =
        MongoDiffStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    /**
     * Repositorio de trilha de auditoria no MongoDB.
     *
     * ### 1. O que faz
     * Registra o componente [AuditLogStore] operando sobre a colecao `audit_log`.
     *
     * ### 2. Para que serve
     * Garante registro duravel, imutavel e append-only de todas as acoes administrativas da governanca.
     *
     * ### 3. Como funciona
     * Instancia [MongoAuditLogStore] ordenando eventos por timestamp e sequencia.
     */
    @Bean
    fun auditLogStore(database: MongoDatabase, sessions: MongoSessionContext): AuditLogStore =
        MongoAuditLogStore(database, sessions)

    /**
     * Repositorio distribuido de idempotencia no MongoDB.
     *
     * ### 1. O que faz
     * Registra a implementacao distribuida de [IdempotencyStore] sobre o MongoDB.
     *
     * ### 2. Para que serve
     * Assegura que requisicoes mutantes com mesma `Idempotency-Key` nao sejam executadas concorrentemente em multiplos pods.
     *
     * ### 3. Como funciona
     * Instancia [MongoIdempotencyStore] com reserva atomica, timeouts de transacao e indices com TTL automatico.
     */
    @Bean
    fun idempotencyStore(
        database: MongoDatabase,
        sessions: MongoSessionContext,
        clock: Clock,
        properties: SduiProperties,
    ): IdempotencyStore = MongoIdempotencyStore(
        database = database,
        sessions = sessions,
        clock = clock,
        ttl = Duration.ofSeconds(properties.idempotencyTtlSeconds),
        reservationTimeout = Duration.ofSeconds(properties.idempotencyReservationTimeoutSeconds),
    )

    /**
     * Unidade de trabalho transacional ACID no MongoDB.
     *
     * ### 1. O que faz
     * Cria e registra o bean [TransactionalUnitOfWork] baseado em transacoes multi-documento do MongoDB.
     *
     * ### 2. Para que serve
     * Garante que publicacoes de telas atualizem specs, ponteiros e logs de auditoria em um unico commit atomico.
     *
     * ### 3. Como funciona
     * Instancia [MongoTransactionalUnitOfWork] iniciando uma sessao cliente e coordenando `commitTransaction` / `abortTransaction`.
     */
    @Bean
    fun transactionalUnitOfWork(client: MongoClient, sessions: MongoSessionContext): TransactionalUnitOfWork =
        MongoTransactionalUnitOfWork(client, sessions)

    /**
     * Fila transacional de saida (Outbox) de invalidacoes no MongoDB.
     *
     * ### 1. O que faz
     * Registra a implementacao de [CacheInvalidationOutbox] no MongoDB.
     *
     * ### 2. Para que serve
     * Enfileira intencoes de invalidacao de cache dentro da mesma transacao da publicacao de specs.
     *
     * ### 3. Como funciona
     * Retorna [MongoCacheInvalidationOutbox] permitindo drenagem assincrona pelo relay de invalidacao.
     */
    @Bean
    fun cacheInvalidationOutbox(database: MongoDatabase, sessions: MongoSessionContext): CacheInvalidationOutbox =
        MongoCacheInvalidationOutbox(database, sessions)

    /**
     * Indicador de saude operacional do armazenamento MongoDB.
     *
     * ### 1. O que faz
     * Registra o componente [HealthIndicator] para inspecao de conectividade com o MongoDB no Actuator.
     *
     * ### 2. Para que serve
     * Expoe status UP/DOWN e metadados de topologia da base de dados sem bloquear chamadas.
     *
     * ### 3. Como funciona
     * Instancia [MongoStoreHealthIndicator] executando `ping` e validando o suporte a replica set.
     */
    @Bean
    fun sduiStoreHealthIndicator(database: MongoDatabase, properties: SduiProperties): HealthIndicator =
        MongoStoreHealthIndicator(database, properties.persistence.mongo.database)
}

/**
 * Configuracao de caches distribuidos no Redis (`ADR-021`).
 *
 * ### 1. O que faz
 * Instancia e configura os adaptadores de cache de telas, specs e fallback baseados no cliente Lettuce para Redis
 * quando a propriedade `sdui.persistence.cache` for definida como `redis`.
 *
 * ### 2. Para que serve
 * Fornece armazenamento em cache de alta velocidade compartilhado entre todas as instancias do servico,
 * reduzindo a carga sobre a autoridade de governanca e mantendo baixa latencia no hot path.
 *
 * ### 3. Como funciona
 * Cria uma conexao Lettuce com timeout de comando estrito e rejeicao rapida (`REJECT_COMMANDS`) em caso de desconexao.
 * Se o Redis ficar indisponivel, o servico degrada graciosamente para re-composicao ou fallback sem travar threads.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["cache"], havingValue = "redis")
class RedisCacheConfiguration {

    /**
     * Fabrica de conexoes Lettuce com configuracoes de resiliencia.
     *
     * ### 1. O que faz
     * Cria a instancia de [LettuceConnectionFactory] configurada para comunicacao com o Redis.
     *
     * ### 2. Para que serve
     * Estabelece o pool de sockets TCP/SSL com o Redis aplicando timeouts restritos e rejeicao rapida de comandos.
     *
     * ### 3. Como funciona
     * Faz o parsing seguro da URL sem vazar credenciais nos logs, define [ClientOptions.DisconnectedBehavior.REJECT_COMMANDS]
     * e habilita SSL caso o scheme seja `rediss`.
     */
    @Bean
    fun sduiRedisConnectionFactory(properties: SduiProperties): LettuceConnectionFactory {
        val redis = properties.persistence.redis
        require(redis.url.isNotBlank()) {
            "sdui.persistence.cache=redis exige sdui.persistence.redis.url (SDUI_PERSISTENCE_REDIS_URL)"
        }
        val uri = try {
            URI(redis.url)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException(
                "sdui.persistence.redis.url malformada (valor omitido por conter credencial); " +
                        "codifique caracteres especiais da senha em percent-encoding",
            )
        }
        val standalone = RedisStandaloneConfiguration(uri.host, if (uri.port > 0) uri.port else DEFAULT_REDIS_PORT)
        uri.path?.trim('/')?.toIntOrNull()?.let { standalone.database = it }
        uri.userInfo?.let { info ->
            val user = info.substringBefore(':', missingDelimiterValue = "")
            val password = info.substringAfter(':', missingDelimiterValue = info)
            if (user.isNotEmpty()) standalone.username = user
            if (password.isNotEmpty()) standalone.password = RedisPassword.of(password)
        }
        val commandTimeout = Duration.ofMillis(redis.commandTimeoutMs)
        val clientOptions = ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(redis.connectTimeoutMs)).build())
            .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
            .build()
        val clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(commandTimeout)
            .clientOptions(clientOptions)
            .apply { if (uri.scheme == "rediss") useSsl() }
            .build()
        return LettuceConnectionFactory(standalone, clientConfig)
    }

    /**
     * Template Redis tipado para operacoes de chave textual e valor binario.
     *
     * ### 1. O que faz
     * Cria e inicializa o [RedisTemplate] com serializadores de string e array de bytes.
     *
     * ### 2. Para que serve
     * Otimiza a serializacao no hot path, evitando overhead de conversoes genericas do Spring Data Redis.
     *
     * ### 3. Como funciona
     * Configura [RedisSerializer.string] para chaves e [RedisSerializer.byteArray] para valores binarios brutos.
     */
    @Bean
    fun sduiRedisTemplate(connectionFactory: LettuceConnectionFactory): RedisTemplate<String, ByteArray> =
        RedisTemplate<String, ByteArray>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = RedisSerializer.string()
            valueSerializer = RedisSerializer.byteArray()
            hashKeySerializer = RedisSerializer.string()
            hashValueSerializer = RedisSerializer.byteArray()
            afterPropertiesSet()
        }

    /**
     * Cache de telas hidratadas distribuido no Redis.
     *
     * ### 1. O que faz
     * Registra a implementacao de [HydratedScreenCache] operando sobre o Redis.
     *
     * ### 2. Para que serve
     * Compartilha arvores prontas entre todas as replicas do servico, acelerando o retorno de composicao.
     *
     * ### 3. Como funciona
     * Retorna [RedisHydratedScreenCache] aplicando limite maximo de bytes por entrada e telemetria de escritas descartadas.
     */
    @Bean
    fun hydratedScreenCache(
        redis: RedisTemplate<String, ByteArray>,
        properties: SduiProperties,
        metrics: MetricsRecorder,
    ): HydratedScreenCache = RedisHydratedScreenCache(
        redis,
        properties.persistence.redis.maxEntryBytes,
        metrics,
        properties.treeCacheMaxEntries
    )

    /**
     * Cache de especificacoes (specs) distribuido no Redis.
     *
     * ### 1. O que faz
     * Registra o componente [SpecCache] operando sobre o Redis.
     *
     * ### 2. Para que serve
     * Evita consultas repetidas a base de dados de governanca durante o estagio de Select.
     *
     * ### 3. Como funciona
     * Retorna [RedisSpecCache] com TTL configurado em [SduiRedisProperties.specTtlSeconds].
     */
    @Bean
    fun specCache(
        redis: RedisTemplate<String, ByteArray>,
        properties: SduiProperties,
        metrics: MetricsRecorder,
    ): SpecCache = RedisSpecCache(
        redis,
        Duration.ofSeconds(properties.persistence.redis.specTtlSeconds),
        properties.persistence.redis.maxEntryBytes,
        metrics,
    )

    /**
     * Armazenamento distribuido de contingencia (Last Good) no Redis.
     *
     * ### 1. O que faz
     * Registra a implementacao de [LastGoodScreenStore] sobre o Redis.
     *
     * ### 2. Para que serve
     * Mantem a arvore de contingencia compartilhada acessivel por qualquer replica caso haja falha de dependencia.
     *
     * ### 3. Como funciona
     * Instancia [RedisLastGoodScreenStore] com invalidacao condicional baseada na versao do ponteiro de tela.
     */
    @Bean
    fun lastGoodScreenStore(
        redis: RedisTemplate<String, ByteArray>,
        clock: Clock,
        properties: SduiProperties,
        metrics: MetricsRecorder,
    ): LastGoodScreenStore = RedisLastGoodScreenStore(
        redis,
        clock,
        Duration.ofSeconds(properties.persistence.redis.lastGoodTtlSeconds),
        properties.persistence.redis.maxEntryBytes,
        metrics,
    )

    /**
     * Indicador de saude operacional do cache Redis.
     *
     * ### 1. O que faz
     * Registra o componente [HealthIndicator] para inspecao da conectividade com o Redis.
     *
     * ### 2. Para que serve
     * Expoe o status do cache no endpoint `/actuator/health` sem degradar o servico sob latencia.
     *
     * ### 3. Como funciona
     * Retorna [RedisCacheHealthIndicator] enviando comando ping ao servidor Redis.
     */
    @Bean
    fun sduiCacheHealthIndicator(connectionFactory: LettuceConnectionFactory): HealthIndicator =
        RedisCacheHealthIndicator(connectionFactory)

    /**
     * Constantes estaticas de configuracao do Redis.
     *
     * ### 1. O que faz
     * Declara valores padrao de infraestrutura do Redis.
     *
     * ### 2. Para que serve
     * Fornece portas e parametros de rede padrao quando nao informados na configuracao.
     *
     * ### 3. Como funciona
     * Define [DEFAULT_REDIS_PORT] como 6379.
     */
    private companion object {
        /** Porta TCP padrao de conexao do Redis. */
        const val DEFAULT_REDIS_PORT: Int = 6379
    }
}
