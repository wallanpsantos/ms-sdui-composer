@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class ActionContractGuardTest {

    companion object {
        private val ALLOWED_ACTION_TYPES = setOf("navigate", "open_bottom_sheet", "track", "noop")
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            rootNode = CanonicalHomeFixture.loadClasspathTree()
        }
    }

    @Test
    fun `todas as actions da fixture pertencem exclusivamente ao catalogo fechado do MVP`() {
        val sections = rootNode.get("sections")
        var actionCount = 0

        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val actions = sec.get("actions") ?: continue

            for (j in 0 until actions.size()) {
                val action = actions.get(j)
                val type = action.get("type").asText()
                actionCount++

                assertThat(type)
                    .`as`("Ação '${action.get("id")?.asText()}' possui tipo não homologado: '$type'")
                    .isIn(ALLOWED_ACTION_TYPES)
            }
        }

        assertThat(actionCount).`as`("A fixture deve exercitar ações").isGreaterThan(0)
    }

    @Test
    fun `todas as rotas de navigate usam esquema seguro app e proibem urls externas`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val actions = sec.get("actions") ?: continue

            for (j in 0 until actions.size()) {
                val action = actions.get(j)
                if (action.get("type").asText() == "navigate") {
                    val payload = action.get("payload")
                    assertThat(payload).isNotNull
                    val route = payload.get("route").asText()

                    assertThat(route)
                        .`as`("A rota de navigate '$route' deve iniciar com 'app://' (deep link nativo)")
                        .startsWith("app://")

                    assertThat(route)
                        .`as`("URLs externas com http/https são proibidas em ações no MVP")
                        .doesNotStartWith("http://")
                        .doesNotStartWith("https://")
                }
            }
        }
    }

    @Test
    fun `ctas visiveis e interativos possuem label legivel preenchido`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val actions = sec.get("actions") ?: continue

            for (j in 0 until actions.size()) {
                val action = actions.get(j)
                val type = action.get("type").asText()

                // Actions navigate, open_bottom_sheet e noop visíveis exigem label
                if (type in setOf("navigate", "open_bottom_sheet", "noop")) {
                    val label = action.get("label")?.asText()
                    assertThat(label)
                        .`as`("Ação interativa '${action.get("id").asText()}' deve possuir label visível")
                        .isNotBlank()
                }
            }
        }
    }

    @Test
    fun `em shortcut_shelf todo actionId de item referencia uma action da propria section`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            if (sec.get("type").asText() == "shortcut_shelf") {
                val actions = sec.get("actions")
                val actionIds = (0 until actions.size()).map { actions.get(it).get("id").asText() }.toSet()

                val items = sec.get("props").get("items")
                for (j in 0 until items.size()) {
                    val item = items.get(j)
                    val actionId = item.get("actionId").asText()

                    assertThat(actionId)
                        .`as`(
                            "O item '${
                                item.get("id").asText()
                            }' referencia actionId '$actionId' não existente na section"
                        )
                        .isIn(actionIds)
                }
            }
        }
    }
}
