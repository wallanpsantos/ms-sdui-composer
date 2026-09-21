@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CardsFirstHomeContractTest {

    companion object {
        private const val RESOURCE = "/fixtures/contrato-sdui-home-cards-first.json"
        private val mapper: JsonMapper = JsonMapper.builder().build()
        private lateinit var rootNode: JsonNode

        @JvmStatic
        @BeforeAll
        fun loadFixture() {
            val text = CardsFirstHomeContractTest::class.java.getResourceAsStream(RESOURCE)
                ?.use { it.bufferedReader().readText() }
                ?: error("Fixture $RESOURCE não encontrada no classpath de teste")
            rootNode = mapper.readTree(text)
        }
    }

    @Test
    fun `envelope contem surface home e skeletonId home cards_first`() {
        val envelope = rootNode.get("envelope")
        assertThat(envelope).isNotNull

        assertThat(envelope.get("surface").asText()).isEqualTo("home")
        assertThat(envelope.get("platform").asText()).isEqualTo("ios")
        assertThat(envelope.get("schemaVersion").asText()).isEqualTo("3")
        assertThat(envelope.get("skeletonId").asText()).isEqualTo("home.cards_first")
        assertThat(envelope.get("specRevisionId").asText()).isEqualTo("rev_01K8HOMECARDSFIRST")
        assertThat(envelope.get("channel").asText()).isEqualTo("stable")
        assertThat(envelope.get("fallback").asBoolean()).isFalse()
    }

    @Test
    fun `skeleton possui montagem variavel com cards antes de shortcuts e shortcuts em grid`() {
        val skeleton = rootNode.get("skeleton")
        assertThat(skeleton).isNotNull
        assertThat(skeleton.get("id").asText()).isEqualTo("home.cards_first")
        assertThat(skeleton.get("layout").asText()).isEqualTo("vertical_scroll")

        val slots = skeleton.get("slots")
        assertThat(slots.isArray).isTrue
        assertThat(slots.size()).isEqualTo(7)

        val slotIds = (0 until slots.size()).map { slots.get(it).get("id").asText() }
        assertThat(slotIds).containsExactly(
            "header",
            "accounts",
            "cards",
            "shortcuts",
            "offers",
            "coverage",
            "foryou",
        )

        val shortcutsSlot = slots.get(3)
        assertThat(shortcutsSlot.get("id").asText()).isEqualTo("shortcuts")
        assertThat(shortcutsSlot.get("layout").asText()).isEqualTo("grid")
    }

    @Test
    fun `sections estao ordenadas de acordo com a montagem cards_first`() {
        val sections = rootNode.get("sections")
        assertThat(sections.isArray).isTrue
        assertThat(sections.size()).isEqualTo(8)

        val sectionSlots = (0 until sections.size()).map { sections.get(it).get("slot").asText() }
        assertThat(sectionSlots).containsExactly(
            "header",
            "accounts",
            "cards",
            "cards",
            "shortcuts",
            "offers",
            "coverage",
            "foryou",
        )

        val shortcutSec = sections.get(4)
        assertThat(shortcutSec.get("slot").asText()).isEqualTo("shortcuts")
        assertThat(shortcutSec.get("layout").asText()).isEqualTo("grid")
        assertThat(shortcutSec.get("props").has("variant")).isFalse()
    }

    @Test
    fun `roundtrip jackson 3 deserializa fixture cards_first com sucesso`() {
        val text = rootNode.toString()
        val screenResponse = mapper.readValue(text, ScreenResponse::class.java)

        assertThat(screenResponse.envelope.skeletonId).isEqualTo("home.cards_first")
        assertThat(screenResponse.envelope.surface).isEqualTo("home")
        assertThat(screenResponse.skeleton.id).isEqualTo("home.cards_first")
        assertThat(screenResponse.skeleton.slots.map { it.id }).containsExactly(
            "header",
            "accounts",
            "cards",
            "shortcuts",
            "offers",
            "coverage",
            "foryou",
        )
        assertThat(screenResponse.sections).hasSize(8)
    }
}
