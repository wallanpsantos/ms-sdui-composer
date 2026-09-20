@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
class HomeRuntimeProtectionWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val pointerStore: PointerStore,
    @Autowired private val specStore: SpecStore,
    @Autowired private val treeCache: HydratedScreenCache,
    @Autowired private val lastGood: LastGoodScreenStore,
) {
    @Test
    fun `200 emite ETag, If-None-Match devolve 304 e troca de revisao devolve 200`() {
        val first = getHome(CanonicalHeaders.ios())
        assertThat(first.status).isEqualTo(200)
        val etag = first.headers["ETag"]?.first()
        assertThat(etag).isEqualTo("W/\"rev_01K8HOMEMAIN-ios-3\"")
        val notModified = getHome(CanonicalHeaders.ios(), ifNoneMatch = etag)
        assertThat(notModified.status).isEqualTo(304)
        assertThat(notModified.body).isNullOrEmpty()

        val current = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val next = specStore.findByRevisionId("rev_01K8HOMENEXT")!!
        pointerStore.save(
            Pointer(
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                specId = next.specId,
                specRevisionId = next.specRevisionId,
                previousSpecRevisionId = current.specRevisionId,
                version = 99,
            ),
        )
        (treeCache as InMemoryHydratedScreenCache).clear()
        val afterChange = getHome(CanonicalHeaders.ios() + ("Client-Version" to "8.20.0"), ifNoneMatch = etag)
        assertThat(afterChange.status).isEqualTo(200)
        val body = jsonMapper.readTree(afterChange.body)
        assertThat(body.get("envelope").get("specRevisionId").asText()).isEqualTo("rev_01K8HOMENEXT")
        pointerStore.save(
            Pointer(
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                specId = current.specId,
                specRevisionId = current.specRevisionId,
                previousSpecRevisionId = next.specRevisionId,
                version = 100,
            ),
        )
        treeCache.clear()
    }

    @Test
    fun `android nao seleciona specRevisionId iOS e sem lastgood devolve 503 com Retry-After`() {
        (lastGood as InMemoryLastGoodScreenStore).clear()
        (treeCache as InMemoryHydratedScreenCache).clear()
        val result = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.android().forEach { (n, v) -> header(n, v) }
        }.andReturn()
        assertThat(result.response.status).isEqualTo(503)
        assertThat(result.response.getHeader("Retry-After")).isNotBlank()
        val body = jsonMapper.readTree(result.response.contentAsString)
        assertThat(body.get("code").asText()).isEqualTo("COMPOSE_UNAVAILABLE")
        assertThat(result.response.contentAsString).doesNotContain("rev_01K8HOMEMAIN")
    }

    @Test
    fun `semver ordinal 8_10_0 entra na faixa e 8_9_99 nao reutiliza current`() {
        (treeCache as InMemoryHydratedScreenCache).clear()
        val min = getHome(CanonicalHeaders.ios() + ("Client-Version" to "8.10.0"))
        assertThat(min.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(min.body).get("envelope").get("specRevisionId").asText())
            .isEqualTo("rev_01K8HOMEMAIN")
        val legacy = getHome(CanonicalHeaders.ios() + ("Client-Version" to "8.9.99"))
        assertThat(legacy.status).isEqualTo(200)
        assertThat(jsonMapper.readTree(legacy.body).get("envelope").get("specRevisionId").asText())
            .isEqualTo("rev_01K8HOMELEGACY")
    }

    @Test
    fun `type desconhecido e omitido com razao fechada sem 4xx`() {
        val current = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val extra: Section = current.sections.first().copy(id = "sec_unknown", type = "unknown_widget", typeVersion = 1)
        specStore.save(
            current.copy(
                specId = "spec_home_ios_omit",
                revision = 1,
                specRevisionId = "rev_omit_unknown",
                sections = current.sections + extra,
                targeting = current.targeting.copy(priority = 500),
            ),
        )
        pointerStore.save(
            Pointer(
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                specId = "spec_home_ios_omit",
                specRevisionId = "rev_omit_unknown",
                previousSpecRevisionId = current.specRevisionId,
                version = 7,
            ),
        )
        (treeCache as InMemoryHydratedScreenCache).clear()
        val result = getHome(CanonicalHeaders.ios())
        assertThat(result.status).isEqualTo(200)
        val body = jsonMapper.readTree(result.body)
        val omitted = body.get("envelope").get("omitted")
        assertThat(omitted.size()).isGreaterThan(0)
        assertThat(omitted.get(0).get("reason").asText()).isEqualTo("unsupported_type")
        assertThat(omitted.get(0).get("type").asText()).isEqualTo("unknown_widget")
    }

    @Test
    fun `lastgood devolve 200 com fallback e sem lastgood 503`() {
        val first = getHome(CanonicalHeaders.ios())
        assertThat(first.status).isEqualTo(200)
        (specStore as InMemorySpecStore).clear()
        (treeCache as InMemoryHydratedScreenCache).clear()
        val fallback = getHome(CanonicalHeaders.ios())
        assertThat(fallback.status).isEqualTo(200)
        val body = jsonMapper.readTree(fallback.body)
        assertThat(body.get("envelope").get("fallback").asBoolean()).isTrue()
        assertThat(body.get("envelope").get("fallbackReason").asText()).isIn(
            "no_compatible_spec", "redis_unavailable", "dependency_timeout", "last_good", "required_slot_empty",
        )
        (lastGood as InMemoryLastGoodScreenStore).clear()
        treeCache.clear()
        val unavailable = getHome(CanonicalHeaders.ios())
        assertThat(unavailable.status).isEqualTo(503)
        assertThat(unavailable.headers["Retry-After"]?.first()).isNotBlank()
    }

    @Test
    fun `chave de arvore nao inclui userId`() {
        val result = getHome(CanonicalHeaders.ios())
        assertThat(result.status).isEqualTo(200)
        assertThat(result.body).doesNotContain("userId")
    }

    private fun getHome(headers: Map<String, String>, ifNoneMatch: String? = null): Exchange {
        val result = mockMvc.get("/v1/surfaces/home") {
            headers.forEach { (n, v) -> header(n, v) }
            if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
        }.andReturn()
        return Exchange(
            result.response.status,
            result.response.contentAsString,
            result.response.headerNames.associateWith { result.response.getHeaders(it) })
    }

    data class Exchange(val status: Int, val body: String, val headers: Map<String, Collection<String>>)
}
