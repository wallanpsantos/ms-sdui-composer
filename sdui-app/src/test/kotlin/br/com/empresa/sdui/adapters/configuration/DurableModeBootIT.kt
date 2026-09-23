package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.mongo.MongoSpecStore
import br.com.empresa.sdui.adapters.redis.RedisHydratedScreenCache
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.compose.ComposeRequest
import br.com.empresa.sdui.orchestrator.compose.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import com.mongodb.client.MongoClients
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.util.*

/**
 * Wiring final do modo persistente e restart (P11). So roda com MongoDB (replica set) e Redis
 * reais configurados. Sobe o contexto duas vezes sobre o mesmo banco: o primeiro publica os
 * exemplos pelo modo demo, o segundo encontra tudo la, nao repete publicacao e nao troca nenhum
 * store por implementacao em memoria.
 */
@EnabledIfEnvironmentVariable(named = "SDUI_IT_MONGO_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SDUI_IT_REDIS_URL", matches = ".+")
class DurableModeBootIT {

    @Test
    fun `segundo boot le a governanca do primeiro sem republicar nem cair para memoria`() {
        val database = "sdui_boot_${UUID.randomUUID().toString().take(8)}"
        val properties = arrayOf(
            "spring.main.web-application-type=none",
            "sdui.persistence.store=mongo",
            "sdui.persistence.cache=redis",
            "sdui.persistence.mongo.uri=${System.getenv("SDUI_IT_MONGO_URI")}",
            "sdui.persistence.mongo.database=$database",
            "sdui.persistence.redis.url=${System.getenv("SDUI_IT_REDIS_URL")}",
            "sdui.demo-enabled=true",
        )
        try {
            boot(properties).use { context ->
                assertThat(context.getBean(SpecStore::class.java)).isInstanceOf(MongoSpecStore::class.java)
                assertThat(context.getBean(HydratedScreenCache::class.java)).isInstanceOf(RedisHydratedScreenCache::class.java)
                assertThat(composeCatalog(context)).isInstanceOf(ComposeResult.Success::class.java)
            }
            boot(properties).use { context ->
                val pointer = context.getBean(PointerStore::class.java).find("catalog", ClientPlatform.IOS, Channel.STABLE)
                assertThat(pointer?.specRevisionId).isEqualTo("rev_demo_ios_fashion_catalog")
                val approvals = context.getBean(AuditLogStore::class.java).recent(50).count { it.action == "publish.approve" }
                assertThat(approvals).`as`("demo nao republica no segundo boot").isEqualTo(4)
                assertThat(composeCatalog(context)).isInstanceOf(ComposeResult.Success::class.java)
            }
        } finally {
            MongoClients.create(System.getenv("SDUI_IT_MONGO_URI")).use { it.getDatabase(database).drop() }
        }
    }

    private fun boot(properties: Array<String>): ConfigurableApplicationContext =
        SpringApplicationBuilder(SduiAppTestConfiguration::class.java).properties(*properties).run()

    private fun composeCatalog(context: ConfigurableApplicationContext): ComposeResult =
        context.getBean(ComposeScreenUseCase::class.java).compose(
            ComposeRequest(
                headers = NegotiateHeaders(
                    "3", "ios", "8.14.2", "81420", "pt-BR", "1", "18.1",
                    "catalog_navigation@1,product_collection@1",
                ),
                identity = "ios:81420",
                surface = Surfaces.CATALOG,
            ),
        )
}
