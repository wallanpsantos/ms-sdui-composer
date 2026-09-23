package br.com.empresa.sdui.adapters.health

import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.data.redis.connection.RedisConnectionFactory

/**
 * Saude do MongoDB de governanca (P02, P11).
 *
 * Separa tres situacoes que um "DOWN" generico misturaria: banco inalcancavel, banco alcancavel
 * sem suporte a transacao multi-documento (standalone — a publicacao falharia) e banco pronto. O
 * detalhe `configured` e sempre true aqui: o indicador so existe no modo `mongo`.
 */
class MongoStoreHealthIndicator(
    private val database: MongoDatabase,
    private val databaseName: String,
) : HealthIndicator {
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
 * Saude do Redis de cache. Cache fora nao derruba a composicao — degrada para miss e para o
 * fallback —, mas precisa aparecer: o indicador responde DOWN com o motivo, e a decisao de tirar
 * ou nao a instancia do balanceamento fica com a configuracao dos grupos de probe.
 */
class RedisCacheHealthIndicator(
    private val connectionFactory: RedisConnectionFactory,
) : HealthIndicator {
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
