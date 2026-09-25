package br.com.empresa.sdui.adapters.configuration

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.core.exc.StreamConstraintsException

class HttpJsonLimitsTest {
    private val mapper = SduiConfiguration().jsonMapper()

    @Test
    fun `JSON pequeno em bytes mas largo em tokens e recusado`() {
        val json = "[" + List(50_001) { "0" }.joinToString(",") + "]"
        assertThatThrownBy { mapper.readTree(json) }.isInstanceOf(StreamConstraintsException::class.java)
    }

    @Test
    fun `profundidade e limitada antes de materializar props`() {
        val json = "[".repeat(65) + "0" + "]".repeat(65)
        assertThatThrownBy { mapper.readTree(json) }.isInstanceOf(StreamConstraintsException::class.java)
    }
}
