@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CanonicalHomeContractTest {

    companion object {
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            val json = CanonicalHomeContractTest::class.java.getResourceAsStream("/fixtures/contrato-sdui-home-definitivo.json")
                ?.bufferedReader()
                ?.readText()
                ?: error("Fixture /fixtures/contrato-sdui-home-definitivo.json não encontrada")

            val mapper = JsonMapper.builder().build()
            rootNode = mapper.readTree(json)
        }
    }

    @Test
    fun `envelope contem exatamente surface home, platform ios e schemaVersion 3`() {
        val envelope = rootNode.get("envelope")
        assertThat(envelope).isNotNull

        assertThat(envelope.get("surface").asText()).isEqualTo("home")
        assertThat(envelope.get("platform").asText()).isEqualTo("ios")
        assertThat(envelope.get("schemaVersion").asText()).isEqualTo("3")
        assertThat(envelope.get("specRevisionId").asText()).isEqualTo("rev_01K8HOMEMAIN")
        assertThat(envelope.get("channel").asText()).isEqualTo("stable")
        assertThat(envelope.get("fallback").asBoolean()).isFalse()
        assertThat(envelope.get("fallbackReason").asText()).isEqualTo("none")
        assertThat(envelope.get("omitted").isArray).isTrue()
        assertThat(envelope.get("omitted").isEmpty).isTrue()

        val client = envelope.get("client")
        assertThat(client.get("platform").asText()).isEqualTo("ios")
        assertThat(client.get("schemaVersionRequested").asText()).isEqualTo("3")

        val targeting = envelope.get("targeting")
        assertThat(targeting.get("platform").asText()).isEqualTo("ios")
        assertThat(targeting.get("schemaVersion").asText()).isEqualTo("3")
        assertThat(targeting.get("band").asText()).isEqualTo("current")

        val analytics = envelope.get("analytics")
        assertThat(analytics.get("event").asText()).isEqualTo("sdui_home_composed")
        assertThat(analytics.get("sectionCount").asInt()).isEqualTo(8)
    }

    @Test
    fun `skeleton possui layout vertical_scroll e os sete slots ordenados`() {
        val skeleton = rootNode.get("skeleton")
        assertThat(skeleton).isNotNull
        assertThat(skeleton.get("id").asText()).isEqualTo("home.default")
        assertThat(skeleton.get("layout").asText()).isEqualTo("vertical_scroll")

        val slots = skeleton.get("slots")
        assertThat(slots.isArray).isTrue()
        assertThat(slots.size()).isEqualTo(7)

        val slotIds = (0 until slots.size()).map { slots.get(it).get("id").asText() }
        assertThat(slotIds).containsExactly(
            "header",
            "shortcuts",
            "accounts",
            "cards",
            "offers",
            "coverage",
            "foryou",
        )
    }

    @Test
    fun `sections contem exatamente oito blocos exercitando os sete types MVP em versao 1`() {
        val sections = rootNode.get("sections")
        assertThat(sections.isArray).isTrue()
        assertThat(sections.size()).isEqualTo(8)

        val typeVersions = (0 until sections.size()).map {
            val sec = sections.get(it)
            "${sec.get("type").asText()}@${sec.get("typeVersion").asInt()}"
        }

        // Exatamente 8 seções cobrindo os 7 types em @1 (card_product@1 instanciado duas vezes)
        assertThat(typeVersions).containsExactly(
            "top_bar@1",
            "shortcut_shelf@1",
            "account_card@1",
            "card_product@1",
            "card_product@1",
            "credit_offer@1",
            "coverage_card@1",
            "decision_card@1",
        )

        val uniqueTypes = typeVersions.toSet()
        assertThat(uniqueTypes).containsExactlyInAnyOrder(
            "top_bar@1",
            "shortcut_shelf@1",
            "account_card@1",
            "card_product@1",
            "credit_offer@1",
            "coverage_card@1",
            "decision_card@1",
        )
    }

    @Test
    fun `cada section possui bloco de analytics preenchido com seu sectionId`() {
        val sections = rootNode.get("sections")
        for (i in 0 until sections.size()) {
            val sec = sections.get(i)
            val secId = sec.get("id").asText()
            val analytics = sec.get("analytics")

            assertThat(analytics).isNotNull
            assertThat(analytics.get("event").asText()).isEqualTo("sdui_section_shown")
            assertThat(analytics.get("sectionId").asText()).isEqualTo(secId)
            assertThat(analytics.get("component").asText()).isEqualTo(sec.get("type").asText())
            assertThat(analytics.get("componentVersion").asInt()).isEqualTo(sec.get("typeVersion").asInt())
        }
    }
}
