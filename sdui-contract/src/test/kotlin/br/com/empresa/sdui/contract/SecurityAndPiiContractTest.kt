@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class SecurityAndPiiContractTest {

    companion object {
        private val CPF_REGEX = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11}\b""")
        private val CARD_PAN_REGEX = Regex("""\b\d{16}\b""")

        private lateinit var rootNode: JsonNode
        private lateinit var rawJson: String

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            rawJson = SecurityAndPiiContractTest::class.java.getResourceAsStream("/fixtures/contrato-sdui-home-definitivo.json")
                ?.bufferedReader()
                ?.readText()
                ?: error("Fixture não encontrada")

            val mapper = JsonMapper.builder().build()
            rootNode = mapper.readTree(rawJson)
        }
    }

    @Test
    fun `fixture nao contem padroes de CPF ou PAN de cartao de 16 digitos`() {
        assertThat(CPF_REGEX.containsMatchIn(rawJson))
            .`as`("Nenhum dado com formato de CPF pode trafegar no contrato SDUI")
            .isFalse()

        assertThat(CARD_PAN_REGEX.containsMatchIn(rawJson))
            .`as`("Nenhum número completo de cartão (PAN 16 dígitos) pode trafegar no contrato SDUI")
            .isFalse()
    }

    @Test
    fun `cartoes contem apenas last4 com exatamente quatro digitos`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            if (sec.get("type").asText() == "card_product") {
                val props = sec.get("props")
                assertThat(props.has("last4")).isTrue()
                val last4 = props.get("last4").asText()
                assertThat(last4).matches("""^\d{4}$""")
                assertThat(props.has("pan")).isFalse()
                assertThat(props.has("cvv")).isFalse()
            }
        }
    }

    @Test
    fun `valores monetarios trafegam exclusivamente como strings formatadas para exibicao`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val props = sec.get("props")

            if (props.has("rows")) {
                val rows = props.get("rows")
                for (j in 0 until rows.size()) {
                    val row = rows.get(j)
                    if (row.has("valueDisplay")) {
                        val valueDisplay = row.get("valueDisplay")
                        assertThat(valueDisplay.isTextual)
                            .`as`("Campos de valor de exibição devem ser strings formatadas com moeda, nunca números brutos")
                            .isTrue()
                        assertThat(valueDisplay.asText()).contains("R$")
                    }
                    if (row.has("valueDisplayRevealed")) {
                        val valueDisplayRevealed = row.get("valueDisplayRevealed")
                        assertThat(valueDisplayRevealed.isTextual).isTrue()
                        assertThat(valueDisplayRevealed.asText()).contains("R$")
                    }
                }
            }
        }
    }
}
