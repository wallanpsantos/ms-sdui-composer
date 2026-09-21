package br.com.empresa.sdui.load

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ComposeHitLoadScenarioTest {
    @Test
    fun `cenario de carga versionado define headers hit miss duracao e meta P99 de 400ms`() {
        val resource = javaClass.getResource("/load/compose-hit-p99.yaml")
        assertThat(resource).isNotNull
        val text = resource!!.readText()
        assertThat(text).contains("p99_ms: 400")
        assertThat(text).contains("surface: home")
        assertThat(text).contains("Client-Platform")
        assertThat(text).contains("hit_ratio: 0.9")
        assertThat(text).contains("payload.bytes")
        val repoCopy: Path = Path.of("src/test/resources/load/compose-hit-p99.yaml")
        if (Files.isRegularFile(repoCopy)) {
            assertThat(Files.readString(repoCopy)).contains("p99_ms: 400")
        }
    }
}
