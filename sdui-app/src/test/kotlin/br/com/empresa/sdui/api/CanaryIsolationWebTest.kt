package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(
    classes = [SduiAppTestConfiguration::class],
    properties = ["sdui.canary-ios-builds=81420"],
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CanaryIsolationWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val specStore: SpecStore,
    @Autowired private val pointerStore: PointerStore,
    @Autowired private val treeCache: HydratedScreenCache,
) {
    @Test
    fun `build allowlist recebe channel canary e os demais permanecem stable`() {
        val canary = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (n, v) -> header(n, v) }
            header("SDUI-Channel", "canary")
        }.andReturn()
        assertThat(canary.response.status).isEqualTo(200)
        val canaryBody = jsonMapper.readTree(canary.response.contentAsString)
        assertThat(canaryBody.get("envelope").get("platform").asText()).isEqualTo("ios")
        assertThat(canaryBody.get("envelope").get("channel").asText()).isEqualTo("canary")
        assertThat(canaryBody.get("envelope").get("specRevisionId").asText()).isEqualTo("rev_01K8HOMEMAIN")

        val stable = mockMvc.get("/v1/surfaces/home") {
            (CanonicalHeaders.ios() + ("Client-Build" to "1")).forEach { (n, v) -> header(n, v) }
            header("SDUI-Channel", "canary")
        }.andReturn()
        assertThat(stable.response.status).isEqualTo(200)
        val stableBody = jsonMapper.readTree(stable.response.contentAsString)
        assertThat(stableBody.get("envelope").get("channel").asText()).isEqualTo("stable")
        assertThat(stableBody.get("envelope").get("specRevisionId").asText()).isEqualTo("rev_01K8HOMEMAIN")
    }

    @Test
    fun `promocao move pointer stable para revisao canary sem republicar e compose devolve a revisao`() {
        val seeded = specStore.findByRevisionId("rev_01K8HOMEMAIN")!!
        val promoted = specStore.save(
            seeded.copy(
                specId = "spec_home_ios_canary_ok",
                revision = 1,
                specRevisionId = "rev_ios_canary_ok",
                channel = Channel.CANARY,
            ),
        )
        pointerStore.save(
            Pointer(
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                specId = promoted.specId,
                specRevisionId = promoted.specRevisionId,
                previousSpecRevisionId = seeded.specRevisionId,
                version = 8,
            ),
        )
        (treeCache as InMemoryHydratedScreenCache).clear()
        val result = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (n, v) -> header(n, v) }
        }.andReturn()
        assertThat(result.response.status).isEqualTo(200)
        val body = jsonMapper.readTree(result.response.contentAsString)
        assertThat(body.get("envelope").get("channel").asText()).isEqualTo("stable")
        assertThat(body.get("envelope").get("specRevisionId").asText()).isEqualTo("rev_ios_canary_ok")
        assertThat(specStore.findByRevisionId("rev_ios_canary_ok")?.channel).isEqualTo(Channel.CANARY)
    }
}
