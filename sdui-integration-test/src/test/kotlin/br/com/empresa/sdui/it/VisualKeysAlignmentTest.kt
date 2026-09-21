package br.com.empresa.sdui.it

import br.com.empresa.sdui.core.model.MvpCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Garante que a lista de atributos visuais proibidos no módulo sdui-contract
 * (NoVisualAttributesTest.FORBIDDEN_VISUAL_KEYS) e a lista do sdui-core (MvpCatalog.VISUAL_KEYS)
 * permanecem estritamente idênticas e sincronizadas (ADR-010 / ADR-016).
 *
 * Como sdui-contract não pode depender de sdui-core para preservar a separação de camadas
 * arquiteturais do projeto, cada módulo mantém sua definição e este teste falha caso haja qualquer
 * divergência entre elas.
 */
class VisualKeysAlignmentTest {

    @Test
    fun `chaves visuais do contrato e do catalogo estao estritamente unificadas`() {
        val contractKeys = extractContractForbiddenKeys()
        assertThat(contractKeys)
            .`as`("A lista de chaves visuais em NoVisualAttributesTest deve ser idêntica a MvpCatalog.VISUAL_KEYS")
            .isEqualTo(MvpCatalog.VISUAL_KEYS)
    }

    private fun extractContractForbiddenKeys(): Set<String> {
        val targetRelative = Path.of("sdui-contract/src/test/kotlin/br/com/empresa/sdui/contract/NoVisualAttributesTest.kt")
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        var file: Path? = null

        while (current != null) {
            val candidate = current.resolve(targetRelative)
            if (Files.isRegularFile(candidate)) {
                file = candidate
                break
            }
            current = current.parent
        }

        val contractFile = checkNotNull(file) {
            "Arquivo NoVisualAttributesTest.kt não encontrado a partir de ${Path.of("").toAbsolutePath()}"
        }

        val content = Files.readString(contractFile)
        val match = Regex("""FORBIDDEN_VISUAL_KEYS\s*=\s*setOf\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(content)
            ?: error("Não foi possível localizar FORBIDDEN_VISUAL_KEYS em $contractFile")

        return Regex(""""([^"]+)"""")
            .findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
    }
}
