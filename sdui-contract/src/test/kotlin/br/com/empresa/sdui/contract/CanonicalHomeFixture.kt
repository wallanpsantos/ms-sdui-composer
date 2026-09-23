package br.com.empresa.sdui.contract

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal object CanonicalHomeFixture {
    const val CLASSPATH_RESOURCE = "/fixtures/contrato-sdui-home-definitivo.json"

    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun loadClasspathText(): String =
        CanonicalHomeFixture::class.java.getResourceAsStream(CLASSPATH_RESOURCE)
            ?.use { it.bufferedReader().readText() }
            ?: error("Fixture $CLASSPATH_RESOURCE não encontrada no classpath de teste")

    fun loadClasspathTree(): JsonNode = mapper.readTree(loadClasspathText())
}
