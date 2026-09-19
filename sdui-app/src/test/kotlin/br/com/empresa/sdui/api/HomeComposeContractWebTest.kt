package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

@SpringBootTest(classes = [SduiAppTestConfiguration::class])
@AutoConfigureMockMvc
class HomeComposeContractWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    @Test
    fun `headers canonico iOS devolvem 200 semanticamente igual a fixture exceto generatedAt`() {
        val response = getHome(CanonicalHeaders.ios())
        assertThat(response.status).isEqualTo(200)
        val actual = jsonMapper.readTree(response.body)
        val expected = jsonMapper.readTree(
            javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"),
        )
        assertSemanticallyEqual(actual, expected)
        assertThat(actual.get("envelope").get("generatedAt").asText()).isNotBlank()
        assertThat(response.headers["ETag"]?.first()).isEqualTo(actual.get("envelope").get("etag").asText())
    }

    @Test
    fun `API-Version e UI-Schema-Version sao eixos independentes`() {
        val ok = getHome(CanonicalHeaders.ios())
        assertThat(ok.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(ok.body).get("envelope").get("schemaVersion").asText()).isEqualTo("3")

        val missingApi = getHome(CanonicalHeaders.ios() - "API-Version")
        assertThat(missingApi.status).isEqualTo(400)

        val missingSchema = getHome(CanonicalHeaders.ios() - "UI-Schema-Version")
        assertThat(missingSchema.status).isEqualTo(400)
    }

    @Test
    fun `header obrigatorio ausente ou malformado retorna 400 com corpo estavel`() {
        val required = listOf(
            "UI-Schema-Version",
            "Client-Platform",
            "Client-Version",
            "Client-Build",
            "Accept-Language",
            "API-Version",
        )
        for (header in required) {
            val response = getHome(CanonicalHeaders.ios() - header)
            assertThat(response.status).`as`("faltando %s", header).isEqualTo(400)
            val body = jsonMapper.readTree(response.body)
            assertThat(body.get("code").asText()).isEqualTo("INVALID_HEADERS")
        }
        assertThat(getHome(CanonicalHeaders.ios() + ("Client-Platform" to "web")).status).isEqualTo(400)
        assertThat(getHome(CanonicalHeaders.ios() + ("Client-Version" to "abc")).status).isEqualTo(400)
        assertThat(getHome(CanonicalHeaders.ios() + ("Client-Build" to "build-x")).status).isEqualTo(400)
        assertThat(getHome(CanonicalHeaders.ios() + ("Accept-Language" to "!!!")).status).isEqualTo(400)
    }

    @Test
    fun `resposta nao emite header proprio com prefixo X`() {
        val response = getHome(CanonicalHeaders.ios())
        assertThat(response.status).isEqualTo(200)
        val custom = response.headers.keys.filter { it.startsWith("X-", ignoreCase = true) }
        assertThat(custom).isEmpty()
        val body = jsonMapper.readTree(response.body)
        assertThat(body.toString()).doesNotContain("\"X-")
    }

    @Test
    fun `payload de compose nao serializa required nem atributos visuais nem PII nem tipos genericos`() {
        val body = jsonMapper.readTree(getHome(CanonicalHeaders.ios()).body)
        assertThat(body.toString()).doesNotContain("\"required\"")
        val forbidden = listOf(
            "color", "typography", "margin", "padding", "gap", "width", "height",
            "radius", "orientation", "shimmer", "ripple", "haptic", "columns", "itemWidth",
        )
        PropScan.scan(body).forEach { key ->
            assertThat(key.lowercase()).isNotIn(forbidden)
        }
        assertThat(body.toString()).doesNotContain("cpf", "row", "column", "container")
        val sections = body.get("sections")
        val types = (0 until sections.size()).map {
            val section = sections.get(it)
            "${section.get("type").asText()}@${section.get("typeVersion").asInt()}"
        }
        assertThat(types).containsExactly(
            "top_bar@1", "shortcut_shelf@1", "account_card@1", "card_product@1",
            "card_product@1", "credit_offer@1", "coverage_card@1", "decision_card@1",
        )
    }

    @Test
    fun `chaves de juncao existem no envelope e no analytics das sections`() {
        val body = jsonMapper.readTree(getHome(CanonicalHeaders.ios()).body)
        val envelope = body.get("envelope")
        val client = envelope.get("client")
        val combined = envelope.toString() + client.toString() + body.get("sections").toString()
        listOf(
            "specRevisionId", "schemaVersion", "appVersion", "build", "platform",
            "channel", "component", "componentVersion", "slot", "sectionId",
        ).forEach { key ->
            assertThat(combined).contains(key)
        }
    }

    private fun getHome(headers: Map<String, String>): HttpExchange {
        val result = mockMvc.get("/v1/surfaces/home") {
            headers.forEach { (name, value) -> header(name, value) }
        }.andReturn()
        return HttpExchange(
            status = result.response.status,
            body = result.response.contentAsString,
            headers = result.response.headerNames.associateWith { result.response.getHeaders(it) },
        )
    }

    private fun assertSemanticallyEqual(actual: JsonNode, expected: JsonNode) {
        val actualCopy = actual.deepCopy<ObjectNode>()
        val expectedCopy = expected.deepCopy<ObjectNode>()
        (actualCopy.get("envelope") as ObjectNode).remove("generatedAt")
        (expectedCopy.get("envelope") as ObjectNode).remove("generatedAt")
        assertThat(actualCopy).isEqualTo(expectedCopy)
    }

    data class HttpExchange(val status: Int, val body: String, val headers: Map<String, Collection<String>>)
}

object PropScan {
    fun scan(node: JsonNode): List<String> {
        val keys = mutableListOf<String>()
        fun rec(current: JsonNode) {
            if (current.isObject) {
                current.properties().forEach { (key, value) ->
                    keys += key
                    rec(value)
                }
            } else if (current.isArray) {
                current.forEach { rec(it) }
            }
        }
        rec(node)
        return keys
    }
}
