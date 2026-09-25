@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class RejectedProposalContractTest {

    companion object {
        private const val RESOURCE = "/fixtures/proposta-docs-05-hostil.json"
        private val mapper: JsonMapper = JsonMapper.builder().build()
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            val text = RejectedProposalContractTest::class.java.getResourceAsStream(RESOURCE)
                ?.use { it.bufferedReader().readText() }
                ?: error("Fixture $RESOURCE não encontrada no classpath de teste")
            rootNode = mapper.readTree(text)
        }
    }

    @Test
    fun `detecta atributos visuais proibidos columns e orientation no payload rejeitado`() {
        val foundViolations = mutableListOf<String>()

        fun scan(node: JsonNode, path: String) {
            if (node.isObject) {
                for (entry in node.properties()) {
                    val key = entry.key
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    if (NoVisualAttributesTest.FORBIDDEN_VISUAL_KEYS.contains(key.lowercase())) {
                        foundViolations.add("$childPath: campo proibido '$key'")
                    }
                    scan(entry.value, childPath)
                }
            } else if (node.isArray) {
                node.forEachIndexed { index, child -> scan(child, "$path[$index]") }
            }
        }

        scan(rootNode, "")

        assertThat(foundViolations)
            .`as`("Deve acusar violações de atributos visuais para columns e orientation")
            .anyMatch { it.contains("columns") }
            .anyMatch { it.contains("orientation") }
    }

    @Test
    fun `detecta actions de rede e mutacao callApi e addToCart`() {
        val sections = rootNode.get("sections")
        val hostileActions = mutableListOf<String>()

        val allowedActions = setOf("navigate", "open_bottom_sheet", "track", "noop")

        for (i in 0 until sections.size()) {
            val actions = sections.get(i).get("actions") ?: continue
            for (j in 0 until actions.size()) {
                val actionType = actions.get(j).get("type").asText()
                if (actionType !in allowedActions) {
                    hostileActions.add(actionType)
                }
            }
        }

        assertThat(hostileActions)
            .`as`("Deve detectar actions hostis callApi e addToCart fora do catálogo permitido")
            .contains("callApi", "addToCart")
    }

    @Test
    fun `detecta layout desconhecido two_pane e tipo fora do catalogo category_grid`() {
        val skeleton = rootNode.get("skeleton")
        assertThat(skeleton.get("layout").asText()).isEqualTo("two_pane")

        val sections = rootNode.get("sections")
        val types = (0 until sections.size()).map { sections.get(it).get("type").asText() }
        assertThat(types).contains("category_grid")
    }
}
