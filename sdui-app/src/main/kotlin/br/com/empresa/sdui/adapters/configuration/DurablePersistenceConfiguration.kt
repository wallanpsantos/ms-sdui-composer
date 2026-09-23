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
 * Governanca no MongoDB (`sdui.persistence.store=mongo`, ADR-021).
 *
 * O cliente e montado aqui, e nao pela autoconfiguracao do Boot (excluida em SduiApplication),
 * para que prazos e pool sejam os declarados em [SduiMongoProperties]: timeout de operacao do
 * proprio driver (CSOT), selecao de servidor e conexao limitadas, pool com teto e espera maxima.
 * Retry automatico de leitura e escrita do driver fica desligado — o servico nao repete chamada
 * a dependencia (ADR-014); a escada de fallback e o reenvio do operador sao a politica.
 *
 * Sem URI configurada a subida falha: modo persistente nunca vira memoria em silencio. A criacao
 * dos indices na subida tambem exige o banco alcancavel.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["store"], havingValue = "mongo")
class MongoStoreConfiguration {
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

    @Bean
    fun sduiMongoDatabase(client: MongoClient, properties: SduiProperties): MongoDatabase =
        client.getDatabase(properties.persistence.mongo.database).also { MongoSchema.ensureIndexes(it) }

    @Bean
    fun mongoSessionContext(): MongoSessionContext = MongoSessionContext()

    @Bean
    fun specStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): SpecStore =
        MongoSpecStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    @Bean
    fun skeletonStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): SkeletonStore =
        MongoSkeletonStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    @Bean
    fun catalogStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): CatalogStore =
        MongoCatalogStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    @Bean
    fun pointerStore(database: MongoDatabase, sessions: MongoSessionContext): PointerStore =
        MongoPointerStore(database, sessions)

    @Bean
    fun publishRequestStore(database: MongoDatabase, sessions: MongoSessionContext): PublishRequestStore =
        MongoPublishRequestStore(database, sessions)

    @Bean
    fun diffStore(database: MongoDatabase, sessions: MongoSessionContext, properties: SduiProperties): DiffStore =
        MongoDiffStore(database, sessions, properties.persistence.mongo.maxDocumentBytes)

    @Bean
    fun auditLogStore(database: MongoDatabase, sessions: MongoSessionContext): AuditLogStore =
        MongoAuditLogStore(database, sessions)

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

    @Bean
    fun transactionalUnitOfWork(client: MongoClient, sessions: MongoSessionContext): TransactionalUnitOfWork =
        MongoTransactionalUnitOfWork(client, sessions)

    @Bean
    fun cacheInvalidationOutbox(database: MongoDatabase, sessions: MongoSessionContext): CacheInvalidationOutbox =
        MongoCacheInvalidationOutbox(database, sessions)

    /** Componente `sduiStore` do health: diferencia banco inalcancavel de topologia sem transacao. */
    @Bean
    fun sduiStoreHealthIndicator(database: MongoDatabase, properties: SduiProperties): HealthIndicator =
        MongoStoreHealthIndicator(database, properties.persistence.mongo.database)
}

/**
 * Caches no Redis (`sdui.persistence.cache=redis`, ADR-021).
 *
 * Redis acelera leituras e nunca e fonte de verdade: perder o Redis custa latencia (misses) e o
 * degrau de last good, nao governanca. O prazo de comando e curto e declarado; comando emitido
 * com a conexao caida e recusado na hora em vez de enfileirado, para a falha chegar ao pipeline
 * dentro do orcamento da requisicao.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["cache"], havingValue = "redis")
class RedisCacheConfiguration {
    @Bean
    fun sduiRedisConnectionFactory(properties: SduiProperties): LettuceConnectionFactory {
        val redis = properties.persistence.redis
        require(redis.url.isNotBlank()) {
            "sdui.persistence.cache=redis exige sdui.persistence.redis.url (SDUI_PERSISTENCE_REDIS_URL)"
        }
        // A mensagem de URISyntaxException repete a entrada inteira, senha inclusa, e a falha de
        // subida vai para o log. Recusa sem ecoar o valor.
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

    @Bean
    fun hydratedScreenCache(
        redis: RedisTemplate<String, ByteArray>,
        properties: SduiProperties,
        metrics: MetricsRecorder,
    ): HydratedScreenCache = RedisHydratedScreenCache(redis, properties.persistence.redis.maxEntryBytes, metrics, properties.treeCacheMaxEntries)

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

    /** Componente `sduiCache` do health. */
    @Bean
    fun sduiCacheHealthIndicator(connectionFactory: LettuceConnectionFactory): HealthIndicator =
        RedisCacheHealthIndicator(connectionFactory)

    private companion object {
        const val DEFAULT_REDIS_PORT: Int = 6379
    }
}
