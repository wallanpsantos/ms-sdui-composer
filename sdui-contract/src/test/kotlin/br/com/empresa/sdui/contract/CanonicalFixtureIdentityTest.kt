@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CanonicalFixtureIdentityTest {

    @Test
    fun `fixture de teste e artefato docs sao o mesmo documento JSON`() {
        val classpathTree = CanonicalHomeFixture.loadClasspathTree()
        val artifactTree = CanonicalHomeFixture.loadArtifactTree()

        assertThat(classpathTree)
            .`as`(
                "A fixture versionada em %s deve ser semanticamente idêntica a %s",
                CanonicalHomeFixture.CLASSPATH_RESOURCE,
                CanonicalHomeFixture.locateArtifact(),
            )
            .isEqualTo(artifactTree)
    }

    @Test
    fun `raiz do contrato tem exatamente envelope skeleton e oito sections`() {
        val root = CanonicalHomeFixture.loadClasspathTree()
        assertThat(root.propertyNames().toList()).containsExactly("envelope", "skeleton", "sections")

        val sections = root.get("sections")
        assertThat(sections.isArray).isTrue()
        assertThat(sections.size()).isEqualTo(8)
        assertThat(root.get("envelope").get("surface").asText()).isEqualTo("home")
        assertThat(root.get("envelope").get("platform").asText()).isEqualTo("ios")
        assertThat(root.get("envelope").get("schemaVersion").asText()).isEqualTo("3")
    }

    @Test
    fun `nenhuma section deixa de ter id slot type typeVersion props actions e analytics`() {
        val sections = CanonicalHomeFixture.loadClasspathTree().get("sections")
        val required = listOf("id", "slot", "type", "typeVersion", "props", "actions", "analytics")
        for (index in 0 until sections.size()) {
            val section = sections.get(index)
            required.forEach { field ->
                assertThat(section.has(field))
                    .`as`("sections[$index] (%s) precisa do campo %s", section.get("id")?.asText(), field)
                    .isTrue()
            }
        }
    }
}
