@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class NoVisualAttributesTest {

    companion object {
        private val FORBIDDEN_VISUAL_KEYS = setOf(
            "color", "background", "font", "typography",
            "margin", "padding", "gap",
            "width", "height", "radius", "rounded", "cornerRadius", "shadow",
            "orientation", "circle", "rectangle", "shimmer", "ripple", "haptic",
            "dp", "pt", "itemWidth", "itemHeight", "breakpoint", "formFactor",
        )

        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            rootNode = CanonicalHomeFixture.loadClasspathTree()
        }
    }

    @Test
    fun `fixture nao contem campos proibidos de aparencia, css ou geometria`() {
        val foundViolations = mutableListOf<String>()

        fun scan(node: JsonNode, path: String) {
            if (node.isObject) {
                for (entry in node.properties()) {
                    val key = entry.key
                    val childPath = if (path.isEmpty()) key else "$path.$key"

                    if (FORBIDDEN_VISUAL_KEYS.contains(key.lowercase())) {
                        foundViolations.add("$childPath: campo proibido '$key'")
                    }
                    scan(entry.value, childPath)
                }
            } else if (node.isArray) {
                node.forEachIndexed { index, child ->
                    scan(child, "$path[$index]")
                }
            }
        }

        scan(rootNode, "")

        assertThat(foundViolations)
            .`as`("O contrato não pode conter campos de CSS, geometria ou aparência (Joud Awad / Fowler)")
            .isEmpty()
    }

    @Test
    fun `somente shortcut_shelf utiliza variant para densidade de produto`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val type = sec.get("type").asText()
            val props = sec.get("props")

            if (props.has("variant")) {
                assertThat(type)
                    .`as`("Apenas shortcut_shelf tem autorização para usar variant como densidade de itens (ADR-010)")
                    .isEqualTo("shortcut_shelf")
                assertThat(props.get("variant").asText())
                    .isIn("compact", "regular")
            }
        }
    }
}
