package br.com.empresa.sdui.contract

import br.com.empresa.sdui.contract.screen.ScreenResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Guarda de contrato sobre as respostas de exemplo de docs/examples/screens (T11).
 *
 * Os exemplos sao propostas para os apps, nao contratos homologados; ainda assim precisam caber
 * no mesmo DTO do envelope v3, sem atributo visual, sem action fora do conjunto fechado e com o
 * evento de analytics da propria surface.
 */
class ExampleResponsesContractTest {
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val examplesDir: Path = Path.of(checkNotNull(System.getProperty("sdui.examplesDir")) {
        "sdui.examplesDir ausente: configurado em sdui-contract/build.gradle.kts"
    })

    private fun responses(): List<Path> = examplesDir.listDirectoryEntries()
        .filter { it.isDirectory() }
        .flatMap { dir -> dir.listDirectoryEntries("response*.json") }
        .sortedBy { it.toString() }

    @Test
    fun `ha resposta de exemplo para as quatro composicoes`() {
        assertThat(responses().map { it.parent.name }.toSet())
            .containsExactlyInAnyOrder("banking.shortcuts_first", "banking.cards_first", "banking.transactions", "fashion.catalog")
    }

    @Test
    fun `toda resposta de exemplo desserializa no envelope v3 e respeita as regras do contrato`() {
        for (file in responses()) {
            val text = Files.readString(file)
            val response = mapper.readValue(text, ScreenResponse::class.java)
            val envelope = response.envelope
            assertThat(envelope.schemaVersion).`as`("%s", file).isEqualTo("3")
            assertThat(envelope.surface).`as`("%s", file).isIn("home", "catalog")
            assertThat(envelope.analytics.event).`as`("%s", file).isEqualTo("sdui_${envelope.surface}_composed")
            assertThat(envelope.analytics.sectionCount).isEqualTo(response.sections.size)
            assertThat(envelope.skeletonHash).matches("^sha256:[0-9a-f]+$")
            assertThat(envelope.etag).startsWith("W/\"${envelope.specRevisionId}-${envelope.platform}-3-")

            val slotIds = response.skeleton.slots.map { it.id }
            for (section in response.sections) {
                assertThat(slotIds).`as`("%s %s", file, section.id).contains(section.slot)
                assertThat(section.analytics.sectionId).isEqualTo(section.id)
                assertThat(section.actions.map { it.type })
                    .allMatch { it in setOf("navigate", "open_bottom_sheet", "track", "noop") }
                section.actions.filter { it.type == "navigate" }
                    .forEach { assertThat(it.payload?.route).startsWith("app://") }
            }
            val ordered = response.sections.map { slotIds.indexOf(it.slot) }
            assertThat(ordered).`as`("%s segue a ordem de slots do skeleton", file).isSorted()

            val keys = mutableListOf<String>()
            collectKeys(mapper.readTree(text), keys)
            assertThat(keys.map { it.lowercase() })
                .`as`("%s sem atributo visual", file)
                .doesNotContainAnyElementsOf(NoVisualAttributesTest.FORBIDDEN_VISUAL_KEYS.map { it.lowercase() })
        }
    }

    private fun collectKeys(node: JsonNode, keys: MutableList<String>) {
        if (node.isObject) {
            for (entry in node.properties()) {
                keys += entry.key
                collectKeys(entry.value, keys)
            }
        } else if (node.isArray) {
            node.forEach { collectKeys(it, keys) }
        }
    }
}
