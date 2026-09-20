@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.core.cache.CapsHash
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
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
class AndroidIsolationWebTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val pointerStore: PointerStore,
    @Autowired private val treeCache: HydratedScreenCache,
) {
    @Test
    fun `pointers android existem e mover android nao altera compose iOS`() {
        val stable = pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.ANDROID, Channel.STABLE)
        val canary = pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.ANDROID, Channel.CANARY)
        val internal = pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.ANDROID, Channel.INTERNAL)
        assertThat(stable).isNotNull
        assertThat(canary).isNotNull
        assertThat(internal).isNotNull
        assertThat(stable?.specRevisionId).isNull()
        assertThat(stable?.specRevisionId).isNotEqualTo("rev_01K8HOMEMAIN")

        val before = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (n, v) -> header(n, v) }
        }.andReturn()
        val beforeId = jsonMapper.readTree(before.response.contentAsString)
            .get("envelope").get("specRevisionId").asText()
        assertThat(beforeId).isEqualTo("rev_01K8HOMEMAIN")

        pointerStore.save(
            Pointer(
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.ANDROID,
                channel = Channel.STABLE,
                specId = "spec_home_android_placeholder",
                specRevisionId = "rev_android_must_not_leak",
                previousSpecRevisionId = null,
                version = 2,
            ),
        )
        (treeCache as InMemoryHydratedScreenCache).clear()
        val after = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (n, v) -> header(n, v) }
        }.andReturn()
        val afterId = jsonMapper.readTree(after.response.contentAsString)
            .get("envelope").get("specRevisionId").asText()
        assertThat(afterId).isEqualTo("rev_01K8HOMEMAIN")
        assertThat(after.response.contentAsString).doesNotContain("rev_android_must_not_leak")

        val iosKey = RedisKeys.tree(
            "home",
            ClientPlatform.IOS,
            "3",
            "8.14",
            CapsHash.sha256(MvpCatalog.TYPES),
            Channel.STABLE,
        )
        val androidKey = RedisKeys.tree(
            "home",
            ClientPlatform.ANDROID,
            "3",
            "8.14",
            CapsHash.sha256(MvpCatalog.TYPES),
            Channel.STABLE,
        )
        assertThat(iosKey).isNotEqualTo(androidKey)
        assertThat(androidKey).contains(":android:")
        assertThat(RedisKeys.containsUserId(androidKey)).isFalse()
    }
}
