@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import br.com.empresa.sdui.contract.screen.ScreenResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class ContractJacksonRoundtripTest {

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Test
    fun `desserializa fixture canonica em ScreenResponse com fidelidade total`() {
        val json = javaClass.getResourceAsStream("/fixtures/contrato-sdui-home-definitivo.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("Fixture não encontrada")

        val screenResponse = mapper.readValue(json, ScreenResponse::class.java)

        // Asserções no envelope
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

        // Asserções no skeleton
        assertThat(screenResponse.skeleton.id).isEqualTo("home.default")
        assertThat(screenResponse.skeleton.layout).isEqualTo("vertical_scroll")
        assertThat(screenResponse.skeleton.slots).hasSize(7)
        assertThat(screenResponse.skeleton.slots.map { it.id }).containsExactly(
            "header", "shortcuts", "accounts", "cards", "offers", "coverage", "foryou"
        )

        // Asserções nas sections
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

        // Resselialização (Roundtrip)
        val serializedJson = mapper.writeValueAsString(screenResponse)
        val roundtripResponse = mapper.readValue(serializedJson, ScreenResponse::class.java)

        assertThat(roundtripResponse.envelope.specRevisionId).isEqualTo(screenResponse.envelope.specRevisionId)
        assertThat(roundtripResponse.sections.size).isEqualTo(screenResponse.sections.size)
        assertThat(roundtripResponse.skeleton.slots.size).isEqualTo(screenResponse.skeleton.slots.size)
    }
}
