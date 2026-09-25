package br.com.empresa.sdui.adapters.health

import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.data.redis.connection.RedisConnectionFactory

/**
 * Indicador de integridade e prontidão do MongoDB de governança (`P02`, `P11`, `ADR-021`).
 *
 * ### 1. O que faz
 * Avalia a conectividade e a capacidade transacional multi-documento do MongoDB para o Spring Boot Actuator.
 *
 * ### 2. Para que serve
 * Discriminar com clareza três cenários operacionais distintos: banco inacessível, banco acessível porém
 * em modo standalone (incapaz de processar transações de governança, o que quebraria publicações) e banco saudável.
 *
 * ### 3. Como funciona
 * Executa o comando administrativo `hello` no [database]. Se a resposta contiver `setName` (Replica Set)
 * ou indicar cluster shardeado (`isdbgrid`), reporta [Status.UP]. Se for standalone, reporta [Status.DOWN]
 * com detalhe `unsupported: standalone`. Erros de rede ou autenticação geram [Status.DOWN] com o erro correspondente.
 */
class MongoStoreHealthIndicator(
    private val database: MongoDatabase,
    private val databaseName: String,
) : HealthIndicator {
    /**
     * Avalia a saúde da conexão e dos recursos transacionais do MongoDB.
     *
     * ### 1. O que faz
     * Executa comando de diagnóstico e constrói o objeto [Health] do Actuator.
     *
     * ### 2. Para que serve
     * Responder às sondagens de saúde (health checks) de orquestradores de contêineres e telemetria.
     *
     * ### 3. Como funciona
     * Aciona `database.runCommand(Document("hello", 1))`, confere capacidade transacional e monta
     * o payload com detalhes de modo, conectividade e suporte a transações.
     */
    override fun health(): Health = try {
        val hello = database.runCommand(Document("hello", 1))
        val replicaSet = hello.getString("setName")
        val sharded = hello.getString("msg") == "isdbgrid"
        val transactional = replicaSet != null || sharded
        Health.Builder(if (transactional) Status.UP else Status.DOWN)
            .withDetail("mode", "mongo")
            .withDetail("configured", true)
            .withDetail("reachable", true)
            .withDetail("database", databaseName)
            .withDetail("transactions", if (transactional) "supported" else "unsupported: standalone")
            .build()
    } catch (error: Exception) {
        Health.Builder(Status.DOWN)
            .withDetail("mode", "mongo")
            .withDetail("configured", true)
            .withDetail("reachable", false)
            .withDetail("error", error.javaClass.simpleName)
            .build()
    }
}

/**
 * Indicador de integridade da conectividade com o cache Redis (`ADR-021`).
 *
 * ### 1. O que faz
 * Avalia a acessibilidade do servidor Redis através de ping para o Spring Boot Actuator.
 *
 * ### 2. Para que serve
 * Sinalizar o estado da infraestrutura de cache sem interromper a composição, visto que o serviço
 * degrada graciosamente para miss e fallback na indisponibilidade do Redis.
 *
 * ### 3. Como funciona
 * Obtém uma conexão a partir da [connectionFactory] e envia comando de `ping()`. Garante o fechamento
 * do recurso de conexão através de `.use { }`. Em caso de sucesso, reporta [Status.UP]; em falha, reporta [Status.DOWN].
 */
class RedisCacheHealthIndicator(
    private val connectionFactory: RedisConnectionFactory,
) : HealthIndicator {
    /**
     * Avalia a saúde da conectividade com o cluster ou servidor Redis.
     *
     * ### 1. O que faz
     * Executa um ping de diagnóstico e gera o objeto [Health] de resposta.
     *
     * ### 2. Para que serve
     * Alimentar as métricas de prontidão do Actuator em `/actuator/health`.
     *
     * ### 3. Como funciona
     * Abre uma conexão do pool temporariamente via `use`, invoca `ping()` e constrói o resultado [Health].
     */
    override fun health(): Health = try {
        val pong = connectionFactory.connection.use { it.ping() }
        Health.Builder(Status.UP)
            .withDetail("mode", "redis")
            .withDetail("configured", true)
            .withDetail("reachable", pong != null)
            .build()
    } catch (error: Exception) {
        Health.Builder(Status.DOWN)
            .withDetail("mode", "redis")
            .withDetail("configured", true)
            .withDetail("reachable", false)
            .withDetail("error", error.javaClass.simpleName)
            .build()
    }
}
