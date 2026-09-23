package br.com.empresa.sdui.adapters.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Parametros operacionais, ajustaveis sem recompilar sob o prefixo `sdui`.
 *
 * Reune o que se mexe em producao: prazos, limites de concorrencia, tetos de memoria, allowlists
 * de canary e o modo de persistencia. Os defaults aqui valem quando nada e configurado.
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
    /** Teto global em memoria; no Redis, teto por surface, plataforma e canal. */
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
    /** Chamadas simultaneas de leitura de store. */
    val readBulkheadPermits: Int = 32,
    /** Espera maxima por uma permissao do bulkhead de leitura antes de degradar. */
    val readBulkheadWaitMs: Long = 50,
    /** Idade maxima de um last good servido como fallback. */
    val maxFallbackAgeSeconds: Long = 86_400,
    /** Janela de deduplicacao de uma chave de idempotencia administrativa com resultado. */
    val idempotencyTtlSeconds: Long = 86_400,
    /** Depois disso uma reserva em voo e considerada abandonada e a chave pode ser retomada. */
    val idempotencyReservationTimeoutSeconds: Long = 300,
    /** Teto de chaves de idempotencia residentes no modo em memoria. */
    val idempotencyMaxKeys: Int = 10_000,
    /** Valores distintos aceitos por tag nas metricas proprias antes de negar meters novos. */
    val metricsMaxTagValues: Int = 64,
    val seedIos: Boolean = true,
    /**
     * Publica os quatro exemplos de `docs/examples/screens` pelo fluxo administrativo ao subir.
     * Desligado por padrao: o seed canonico nunca e substituido sem pedido explicito.
     */
    val demoEnabled: Boolean = false,
    val persistence: PersistenceProperties = PersistenceProperties(),
)

/** Onde vive a governanca e onde vivem os caches (ADR-021). Valor invalido falha a subida. */
data class PersistenceProperties(
    val store: StoreMode = StoreMode.MEMORY,
    val cache: CacheMode = CacheMode.MEMORY,
    /** Intervalo do relay que reaplica invalidacoes de cache pendentes no outbox. */
    val invalidationRelayIntervalMs: Long = 5_000,
    val mongo: SduiMongoProperties = SduiMongoProperties(),
    val redis: SduiRedisProperties = SduiRedisProperties(),
)

enum class StoreMode { MEMORY, MONGO }

enum class CacheMode { MEMORY, REDIS }

/**
 * Conexao com o MongoDB. A URI carrega credencial e vem sempre de configuracao externa
 * (`SDUI_PERSISTENCE_MONGO_URI`), nunca do repositorio. Os prazos sao do driver: e ele quem
 * interrompe uma chamada em andamento, o que o orcamento da requisicao nao faz.
 */
data class SduiMongoProperties(
    val uri: String = "",
    val database: String = "sdui",
    val connectTimeoutMs: Long = 2_000,
    val serverSelectionTimeoutMs: Long = 2_000,
    /** Prazo por operacao (CSOT do driver), incluindo espera por conexao do pool. */
    val operationTimeoutMs: Long = 1_000,
    val maxPoolSize: Int = 50,
    val minPoolSize: Int = 0,
    val maxWaitTimeMs: Long = 500,
    /** Teto do documento de governanca serializado; o limite do MongoDB e 16 MiB. */
    val maxDocumentBytes: Int = 1_048_576,
)

/** Conexao com o Redis. A URL pode carregar senha e tambem vem de configuracao externa. */
data class SduiRedisProperties(
    val url: String = "",
    val connectTimeoutMs: Long = 1_000,
    val commandTimeoutMs: Long = 200,
    /** Entrada maior que isto nao e gravada: vira `cache.write.skipped` e o proximo acesso e miss. */
    val maxEntryBytes: Int = 262_144,
    val specTtlSeconds: Long = 600,
    val lastGoodTtlSeconds: Long = 604_800,
)
