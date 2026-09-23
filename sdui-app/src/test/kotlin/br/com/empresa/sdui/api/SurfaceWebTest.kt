package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * Leitura e governanca da surface nova pela borda HTTP (T06), com o modo demo ligado.
 *
 * O caminho da Home nao muda; o catalogo tem mapeamento literal proprio; um caminho fora da
 * allowlist nao chega ao pipeline.
 */
@SpringBootTest(classes = [SduiAppTestConfiguration::class], properties = ["sdui.demo-enabled=true"])
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SurfaceWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val catalogHeaders = CanonicalHeaders.ios() + ("Component-Capabilities" to "catalog_navigation@1,product_collection@1")

    @Test
    fun `catalogo responde com envelope proprio, ETag, Vary e revalidacao 304`() {
        val first = get("/v1/surfaces/catalog", catalogHeaders)
        assertThat(first.response.status).isEqualTo(200)
        val body = jsonMapper.readTree(first.response.contentAsString)
        assertThat(body.get("envelope").get("surface").asString()).isEqualTo("catalog")
        assertThat(body.get("envelope").get("analytics").get("event").asString()).isEqualTo("sdui_catalog_composed")
        assertThat(body.get("skeleton").get("id").asString()).isEqualTo("catalog.default")
        val etag = checkNotNull(first.response.getHeader("ETag"))
        assertThat(first.response.getHeader("Vary")).contains("Component-Capabilities")
        assertThat(first.response.headerNames.filter { it.startsWith("X-", ignoreCase = true) }).isEmpty()

        val revalidated = get("/v1/surfaces/catalog", catalogHeaders + ("If-None-Match" to etag))
        assertThat(revalidated.response.status).isEqualTo(304)
    }

    @Test
    fun `surface fora da allowlist nao tem rota`() {
        assertThat(get("/v1/surfaces/checkout", CanonicalHeaders.ios()).response.status).isEqualTo(404)
    }

    @Test
    fun `catalogo sem spec para a plataforma responde 503 sem servir a Home`() {
        val android = get("/v1/surfaces/catalog", CanonicalHeaders.android())
        assertThat(android.response.status).isEqualTo(503)
        val error = jsonMapper.readTree(android.response.contentAsString)
        assertThat(error.get("code").asString()).isEqualTo("COMPOSE_UNAVAILABLE")
        assertThat(error.get("message").asString()).isEqualTo("catalog indisponivel")
        assertThat(android.response.getHeader("Retry-After")).isNotBlank()
    }

    @Test
    fun `negociacao da Home vale igual para o catalogo`() {
        val missingApi = get("/v1/surfaces/catalog", catalogHeaders - "API-Version")
        assertThat(missingApi.response.status).isEqualTo(400)
    }

    @Test
    fun `rollback de surface desconhecida responde 404 e listagem valida a janela`() {
        val rollback = mockMvc.post("/admin/v1/pointers/checkout/ios/stable:rollback") {
            header("Actor-Id", "checker-1")
            header("Actor-Role", "CHECKER")
            header("Idempotency-Key", "rb-unknown")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"teste"}"""
        }.andReturn()
        assertThat(rollback.response.status).isEqualTo(404)

        val invalid = admin("/admin/v1/specs?limit=0")
        assertThat(invalid.response.status).isEqualTo(400)
        val page = admin("/admin/v1/specs?limit=2")
        assertThat(page.response.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(page.response.contentAsString).size()).isEqualTo(2)
        val beyond = admin("/admin/v1/specs?offset=10000&limit=10")
        assertThat(jsonMapper.readTree(beyond.response.contentAsString).size()).isZero()
    }

    @Test
    fun `auditoria lista os eventos mais recentes primeiro e respeita o limite`() {
        val audit = mockMvc.get("/admin/v1/audit?limit=3") {
            header("Actor-Id", "auditor-1")
            header("Actor-Role", "AUDITOR")
        }.andReturn()
        assertThat(audit.response.status).isEqualTo(200)
        val events = jsonMapper.readTree(audit.response.contentAsString)
        assertThat(events.size()).isEqualTo(3)
        // A carga demo aprovou quatro exemplos: o ultimo evento e o do catalogo.
        assertThat(events.get(0).get("surface").asString()).isEqualTo("catalog")
    }

    private fun get(path: String, headers: Map<String, String>) = mockMvc.get(path) {
        headers.forEach { (name, value) -> header(name, value) }
    }.andReturn()

    private fun admin(path: String) = mockMvc.get(path) {
        header("Actor-Id", "auditor-1")
        header("Actor-Role", "AUDITOR")
    }.andReturn()
}
