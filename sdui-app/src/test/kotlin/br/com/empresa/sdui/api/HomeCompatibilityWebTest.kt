package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(classes = [SduiAppTestConfiguration::class])
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HomeCompatibilityWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val treeCache: HydratedScreenCache,
    @Autowired private val meters: ObjectProvider<MeterRegistry>,
) {
    @Test
    fun `faixas iOS legacy current next e omissao nao esvazia sections restantes`() {
        (treeCache as InMemoryHydratedScreenCache).clear()
        val legacy = getHome(CanonicalHeaders.ios() + ("Client-Version" to "8.9.1"))
        assertThat(legacy.status).isEqualTo(200)
        val legacyBody = jsonMapper.readTree(legacy.body)
        assertThat(legacyBody.get("envelope").get("specRevisionId").asText()).isEqualTo("rev_01K8HOMELEGACY")
        val legacyIds = sectionIds(legacyBody)
        assertThat(legacyIds).contains("sec_header_1", "sec_account_1")
        assertThat(legacyIds).doesNotContain("sec_foryou_1")
        val omittedTypes = omittedTypes(legacyBody)
        assertThat(omittedTypes).contains("decision_card")
        val header = section(legacyBody, "sec_header_1")
        assertThat(header.get("props").get("greetingName").asText()).isEqualTo("Mariana Silva")
        assertThat(header.get("actions").size()).isGreaterThan(0)
        assertThat(header.toString()).doesNotContain("sec_foryou_1")

        (treeCache as InMemoryHydratedScreenCache).clear()
        val current = getHome(CanonicalHeaders.ios())
        assertThat(current.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(current.body).get("envelope").get("specRevisionId").asText())
            .isEqualTo("rev_01K8HOMEMAIN")

        (treeCache as InMemoryHydratedScreenCache).clear()
        val next = getHome(CanonicalHeaders.ios() + ("Client-Version" to "8.21.0"))
        assertThat(next.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(next.body).get("envelope").get("specRevisionId").asText())
            .isEqualTo("rev_01K8HOMENEXT")
    }

    @Test
    fun `200 emite Cache-Control e Vary sem header proprio X e metricas de compose`() {
        (treeCache as InMemoryHydratedScreenCache).clear()
        val response = getHome(CanonicalHeaders.ios())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers["Cache-Control"]?.first()).contains("private")
        assertThat(response.headers["Vary"]?.first()).contains("API-Version")
        assertThat(response.headers.keys.none { it.startsWith("X-", ignoreCase = true) }).isTrue()
        val registry = meters.ifAvailable
        if (registry != null) {
            val names = registry.meters.map { it.id.name }
            assertThat(names).containsAnyOf("compose.hit", "compose.miss")
            assertThat(names).contains("payload.bytes", "serialize.ms", "compose.duration")
        }
    }

    @Test
    fun `dois requests identicos no mesmo instante devolvem o mesmo specRevisionId`() {
        val first = getHome(CanonicalHeaders.ios())
        val second = getHome(CanonicalHeaders.ios())
        val a = jsonMapper.readTree(first.body).get("envelope").get("specRevisionId").asText()
        val b = jsonMapper.readTree(second.body).get("envelope").get("specRevisionId").asText()
        assertThat(a).isEqualTo(b).isEqualTo("rev_01K8HOMEMAIN")
    }

    private fun getHome(headers: Map<String, String>): Exchange {
        val result = mockMvc.get("/v1/surfaces/home") {
            headers.forEach { (n, v) -> header(n, v) }
        }.andReturn()
        return Exchange(
            status = result.response.status,
            body = result.response.contentAsString,
            headers = result.response.headerNames.associateWith { result.response.getHeaders(it) },
        )
    }

    private fun sectionIds(body: tools.jackson.databind.JsonNode): List<String> {
        val sections = body.get("sections")
        return (0 until sections.size()).map { sections.get(it).get("id").asText() }
    }

    private fun omittedTypes(body: tools.jackson.databind.JsonNode): List<String> {
        val omitted = body.get("envelope").get("omitted")
        return (0 until omitted.size()).map { omitted.get(it).get("type").asText() }
    }

    private fun section(body: tools.jackson.databind.JsonNode, id: String): tools.jackson.databind.JsonNode {
        val sections = body.get("sections")
        return (0 until sections.size()).map { sections.get(it) }.first { it.get("id").asText() == id }
    }

    data class Exchange(val status: Int, val body: String, val headers: Map<String, Collection<String>>)
}
