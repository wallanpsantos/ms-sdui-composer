package br.com.empresa.sdui.adapters.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Parametros operacionais do BFF Server-Driven UI vinculados ao prefixo `sdui`.
 *
 * ### 1. O que faz
 * Centraliza e tipa todas as variaveis de configuracao do servico injetadas a partir de arquivos
 * de configuracao (`application.yaml`) ou variaveis de ambiente, mapeando-as para tipos imutaveis.
 *
 * ### 2. Para que serve
 * Permite calibrar dinamicamente os prazos (timeouts), limites de concorrencia (bulkheads), politicas
 * de degradacao e fallback (`ADR-007`), limitacao de taxa (rate limiting), canais de experimentacao
 * (canary iOS e Android) e a infraestrutura de persistencia ativa (`memory`, `mongo`, `redis` via `ADR-021`),
 * sem necessidade de recompilacao do artefato.
 *
 * ### 3. Como funciona
 * O Spring Boot instancia esta data class durante a inicializacao atraves de `@EnableConfigurationProperties`.
 * Se nenhuma propriedade externa for declarada, aplica valores padrao seguros (safe defaults)
 * dimensionados para execucao em memoria e baixa latencia.
 *
 * @property canaryIosBuilds Lista de identificadores de builds iOS autorizados a receber specs canary.
 * @property canaryAndroidBuilds Lista de builds Android autorizados a receber specs canary.
 * @property rateLimitCapacity Capacidade maxima de tokens por bucket no limitador de taxa (token bucket).
 * @property rateLimitRefillPerSecond Taxa de reabastecimento de tokens por segundo em cada bucket.
 * @property rateLimitMaxKeys Teto maximo de chaves e buckets residentes em memoria para evitar estouro de heap.
 * @property treeTtlSeconds Tempo de vida (TTL) em segundos para as arvores de tela hidratadas em cache.
 * @property treeCacheMaxEntries Limite maximo de arvores completas mantidas simultaneamente no cache.
 * @property hydrationTimeoutMs Prazo maximo em milissegundos para o fan-out assincrono de hidratacao de secoes.
 * @property hydrationFanout Quantidade maxima de workers simultaneos permitidos no fan-out de hidratacao.
 * @property retryAfterSeconds Tempo base em segundos retornado no cabecalho `Retry-After` em respostas HTTP 503.
 * @property rateLimitRetryAfterSeconds Tempo base em segundos no cabecalho `Retry-After` para HTTP 429.
 * @property requestBudgetMs Orcamento total de tempo da requisicao de composicao para limitar esperas do pipeline.
 * @property singleflightTimeoutMs Tempo maximo que requisicoes concorrentes aguardam o lider no singleflight.
 * @property readBulkheadPermits Quantidade de permissoes simultaneas no bulkhead de leitura de surfaces.
 * @property readBulkheadWaitMs Tempo maximo de espera por uma permissao do bulkhead antes de degradar o fluxo.
 * @property maxFallbackAgeSeconds Idade maxima de uma arvore de last good para ser aceita como fallback valido.
 * @property idempotencyTtlSeconds Janela de retencao do registro de idempotencia apos sua conclusao com sucesso.
 * @property idempotencyReservationTimeoutSeconds Prazo maximo de expiracao de uma reserva em andamento abandonada.
 * @property idempotencyMaxKeys Teto maximo de registros de idempotencia residentes no armazenamento em memoria.
 * @property metricsMaxTagValues Limite maximo de valores cardinais distintos por tag nas metricas Micrometer.
 * @property seedIos Flag que indica se a carga inicial da surface home iOS deve ser executada no startup.
 * @property demoEnabled Flag que habilita a carga demonstrativa automatica de telas de exemplo na inicializacao.
 * @property persistence Configuracoes de persistencia e cache de governanca (memoria, MongoDB ou Redis).
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

/**
 * Propriedades de configuracao da camada de persistencia e cache de governanca (`ADR-021`).
 *
 * ### 1. O que faz
 * Declara os seletores de backend de dados (`store` e `cache`), o intervalo de relay para
 * invalidacao assincrona e os blocos de configuracao especificos para MongoDB e Redis.
 *
 * ### 2. Para que serve
 * Permite alternar entre execucao totalmente efemera em heap ([StoreMode.MEMORY], [CacheMode.MEMORY])
 * e execucao duravel distribuida ([StoreMode.MONGO], [CacheMode.REDIS]) em clusters produtivos.
 *
 * ### 3. Como funciona
 * Avaliada condicionalmente pelas classes de configuracao do Spring para registrar os beans de persistencia
 * adequados. A selecao e estrita: valores invalidos interrompem o bootstrap da aplicacao.
 *
 * @property store Modo de persistencia para os dados da governanca (`MEMORY` ou `MONGO`).
 * @property cache Modo de cache para arvores hidratadas e specs (`MEMORY` ou `REDIS`).
 * @property invalidationRelayIntervalMs Intervalo em milissegundos do relay que processa invalidacoes pendentes.
 * @property mongo Parametros de conexao e timeouts especificos para o cliente MongoDB.
 * @property redis Parametros de conexao e timeouts especificos para o cliente Redis via Lettuce.
 */
data class PersistenceProperties(
    val store: StoreMode = StoreMode.MEMORY,
    val cache: CacheMode = CacheMode.MEMORY,
    /** Intervalo do relay que reaplica invalidacoes de cache pendentes no outbox. */
    val invalidationRelayIntervalMs: Long = 5_000,
    val mongo: SduiMongoProperties = SduiMongoProperties(),
    val redis: SduiRedisProperties = SduiRedisProperties(),
)

/**
 * Modos de armazenamento suportados para a autoridade de governanca do SDUI.
 *
 * ### 1. O que faz
 * Define as opcoes de backend de persistencia permanente para specs, skeletons, catalogos e auditoria.
 *
 * ### 2. Para que serve
 * Discrimina entre o modo em memoria (padrao, voltado a testes e desenvolvimento) e MongoDB (cluster duravel).
 *
 * ### 3. Como funciona
 * Utilizado na propriedade `sdui.persistence.store` para ativar condicionalmente os beans de governanca.
 */
enum class StoreMode {
    /** Armazenamento efemero em memoria RAM utilizando estruturas thread-safe. */
    MEMORY,

    /** Armazenamento persistente distribuido no MongoDB em replica set (`ADR-021`). */
    MONGO
}

/**
 * Modos de armazenamento suportados para o cache de telas e specs.
 *
 * ### 1. O que faz
 * Enumera os mecanismos de cache disponiveis no servico para aceleracao de leitura.
 *
 * ### 2. Para que serve
 * Permite selecionar entre cache local em heap do processo ou cache distribuido compartilhado no Redis.
 *
 * ### 3. Como funciona
 * Inspecionado condicionalmente via `@ConditionalOnProperty` nas classes de configuracao de cache.
 */
enum class CacheMode {
    /** Cache local em heap do processo atraves de estruturas em memoria. */
    MEMORY,

    /** Cache distribuido em cluster Redis atraves de templates de chave/valor (`ADR-021`). */
    REDIS
}

/**
 * Parametros de conexao, pool e timeouts do driver MongoDB (`ADR-021`).
 *
 * ### 1. O que faz
 * Define as propriedades de conectividade direta com o replica set do MongoDB, limites do pool
 * de conexoes e prazos explicitos do driver (CSOT - Client-Side Operation Timers).
 *
 * ### 2. Para que serve
 * Isola as politicas de resiliencia e timeouts no nivel do driver Mongo, impedindo que chamadas
 * lentas travem threads virtuais indefinidamente ou sobrecarreguem o banco de dados.
 *
 * ### 3. Como funciona
 * Os valores sao lidos nas configuracoes de persistencia duravel para instanciar manualmente o cliente oficial
 * do MongoDB (`MongoClient`) sem utilizar as autoconfiguracoes padrao do Spring Boot.
 *
 * @property uri String de conexao com credenciais, hosts e replica set do MongoDB.
 * @property database Nome da base de dados utilizada pelo servico SDUI.
 * @property connectTimeoutMs Timeout de abertura de socket TCP com o servidor MongoDB.
 * @property serverSelectionTimeoutMs Timeout para selecao e descoberta de no no cluster MongoDB.
 * @property operationTimeoutMs Prazo maximo total para conclusao de qualquer operacao no MongoDB.
 * @property maxPoolSize Quantidade maxima de conexoes abertas no pool por processo.
 * @property minPoolSize Quantidade minima de conexoes ociosas mantidas no pool.
 * @property maxWaitTimeMs Tempo maximo que uma thread aguarda para obter conexao do pool.
 * @property maxDocumentBytes Tamanho maximo permitido para documentos BSON serializados.
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

/**
 * Parametros de conexao e politicas de armazenamento do cliente Redis (`ADR-021`).
 *
 * ### 1. O que faz
 * Define as informacoes de conexao, timeouts de socket e comando do Lettuce, e tempos de vida (TTL)
 * para chaves de cache e fallback armazenadas no Redis.
 *
 * ### 2. Para que serve
 * Garante que o Redis opere estritamente como acelerador de leitura de baixa latencia, falhando
 * rapidamente sob instabilidade para acionar a escada de fallback sem degradar o servico.
 *
 * ### 3. Como funciona
 * Utilizado pelas configuracoes de cache duravel para construir a conexao Lettuce configurada
 * para rejeitar comandos imediatamente quando desconectado (`REJECT_COMMANDS`).
 *
 * @property url URL de conexao contendo host, porta, senha e banco do Redis.
 * @property connectTimeoutMs Timeout de conexao TCP com o Redis.
 * @property commandTimeoutMs Timeout de execucao de comando individual no Redis.
 * @property maxEntryBytes Limite em bytes para gravacao de uma entrada; entradas maiores sao ignoradas.
 * @property specTtlSeconds Tempo de vida em segundos para specs cacheadas no Redis.
 * @property lastGoodTtlSeconds Tempo de vida em segundos para telas no degrau de fallback last good.
 */
data class SduiRedisProperties(
    val url: String = "",
    val connectTimeoutMs: Long = 1_000,
    val commandTimeoutMs: Long = 200,
    /** Entrada maior que isto nao e gravada: vira `cache.write.skipped` e o proximo acesso e miss. */
    val maxEntryBytes: Int = 262_144,
    val specTtlSeconds: Long = 600,
    val lastGoodTtlSeconds: Long = 604_800,
)
