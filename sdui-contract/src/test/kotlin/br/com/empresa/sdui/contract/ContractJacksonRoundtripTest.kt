@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import br.com.empresa.sdui.contract.screen.ScreenResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class ContractJacksonRoundtripTest {

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Test
    fun `tipos Jackson 3 round-trip da fixture sem perder envelope skeleton ou sections`() {
        val json = CanonicalHomeFixture.loadClasspathText()
        val originalTree = mapper.readTree(json)

        val screenResponse = mapper.readValue(json, ScreenResponse::class.java)

        assertThat(screenResponse.envelope.surface).isEqualTo("home")
        assertThat(screenResponse.envelope.platform).isEqualTo("ios")
        assertThat(screenResponse.envelope.schemaVersion).isEqualTo("3")
        assertThat(screenResponse.envelope.specRevisionId).isEqualTo("rev_01K8HOMEMAIN")
        assertThat(screenResponse.envelope.skeletonId).isEqualTo("home.default")
        assertThat(screenResponse.envelope.channel).isEqualTo("stable")
        assertThat(screenResponse.envelope.fallback).isFalse()
        assertThat(screenResponse.envelope.omitted).isEmpty()
        assertThat(screenResponse.envelope.client.platform).isEqualTo("ios")
        assertThat(screenResponse.envelope.targeting.band).isEqualTo("current")
        assertThat(screenResponse.envelope.analytics.sectionCount).isEqualTo(8)

        assertThat(screenResponse.skeleton.id).isEqualTo("home.default")
        assertThat(screenResponse.skeleton.layout).isEqualTo("vertical_scroll")
        assertThat(screenResponse.skeleton.slots).hasSize(7)
        assertThat(screenResponse.skeleton.slots.map { it.id }).containsExactly(
            "header", "shortcuts", "accounts", "cards", "offers", "coverage", "foryou",
        )

        assertThat(screenResponse.sections).hasSize(8)
        val sectionTypes = screenResponse.sections.map { "${it.type}@${it.typeVersion}" }
        assertThat(sectionTypes).containsExactly(
            "top_bar@1",
            "shortcut_shelf@1",
            "account_card@1",
            "card_product@1",
            "card_product@1",
            "credit_offer@1",
            "coverage_card@1",
            "decision_card@1",
        )
        screenResponse.sections.forEach { section ->
            assertThat(section.props.isObject).isTrue()
            assertThat(section.props.isEmpty).isFalse()
            assertThat(section.analytics.sectionId).isEqualTo(section.id)
        }

        val serializedJson = mapper.writeValueAsString(screenResponse)
        val roundtripTree = mapper.readTree(serializedJson)
        val roundtripResponse = mapper.readValue(serializedJson, ScreenResponse::class.java)

        assertThat(roundtripResponse.envelope.specRevisionId).isEqualTo(screenResponse.envelope.specRevisionId)
        assertThat(roundtripResponse.sections).hasSize(screenResponse.sections.size)
        assertThat(roundtripResponse.skeleton.slots).hasSize(screenResponse.skeleton.slots.size)

        assertTreePreserved(originalTree, roundtripTree, "$")
    }

    private fun assertTreePreserved(original: JsonNode, roundtrip: JsonNode, path: String) {
        when {
            original.isObject -> {
                assertThat(roundtrip.isObject).`as`("%s deve permanecer objeto", path).isTrue()
                original.properties().forEach { (key, value) ->
                    val childPath = "$path.$key"
                    assertThat(roundtrip.has(key))
                        .`as`("campo %s foi perdido no round-trip Jackson", childPath)
                        .isTrue()
                    assertTreePreserved(value, roundtrip.get(key), childPath)
                }
            }

            original.isArray -> {
                assertThat(roundtrip.isArray).`as`("%s deve permanecer array", path).isTrue()
                assertThat(roundtrip.size())
                    .`as`("tamanho de %s mudou no round-trip", path)
                    .isEqualTo(original.size())
                for (index in 0 until original.size()) {
                    assertTreePreserved(original.get(index), roundtrip.get(index), "$path[$index]")
                }
            }

            else -> {
                assertThat(roundtrip)
                    .`as`("valor de %s mudou no round-trip", path)
                    .isEqualTo(original)
            }
        }
    }
}
