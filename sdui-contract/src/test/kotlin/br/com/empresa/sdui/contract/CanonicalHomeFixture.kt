package br.com.empresa.sdui.contract

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

internal object CanonicalHomeFixture {
    const val CLASSPATH_RESOURCE = "/fixtures/contrato-sdui-home-definitivo.json"
    const val ARTIFACT_RELATIVE = "docs/artifacts/contrato-sdui-home-definitivo.json"

    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun loadClasspathText(): String =
        CanonicalHomeFixture::class.java.getResourceAsStream(CLASSPATH_RESOURCE)
            ?.use { it.bufferedReader().readText() }
            ?: error("Fixture $CLASSPATH_RESOURCE não encontrada no classpath de teste")

    fun loadClasspathTree(): JsonNode = mapper.readTree(loadClasspathText())

    fun locateArtifact(): Path {
        val fromProperty = System.getProperty("sdui.canonicalArtifact")
        if (!fromProperty.isNullOrBlank()) {
            val path = Path.of(fromProperty)
            check(Files.isRegularFile(path)) { "sdui.canonicalArtifact não é arquivo: $path" }
            return path
        }
        var dir: Path? = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            val current = dir ?: return@repeat
            val candidate = current.resolve(ARTIFACT_RELATIVE)
            if (Files.isRegularFile(candidate)) return candidate
            dir = current.parent
        }
        error("Artefato $ARTIFACT_RELATIVE não encontrado a partir de ${Path.of("").toAbsolutePath()}")
    }

    fun loadArtifactText(): String = Files.readString(locateArtifact())

    fun loadArtifactTree(): JsonNode = mapper.readTree(loadArtifactText())
}
