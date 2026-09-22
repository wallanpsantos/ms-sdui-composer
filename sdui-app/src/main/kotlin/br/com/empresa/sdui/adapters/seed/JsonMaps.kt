package br.com.empresa.sdui.adapters.seed

import tools.jackson.databind.JsonNode

/**
 * Converte entre JsonNode e as estruturas Kotlin que o dominio usa nas props.
 *
 * Existe porque o core e puro e nao conhece Jackson: a traducao precisa acontecer no adapter.
 * Preserva a distincao entre inteiro e decimal na ida, para um valor nao mudar de tipo ao passar
 * pelo servidor.
 */
object JsonMaps {
    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    fun toValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.asBoolean()
        node.isNumber -> if (node.isFloatingPointNumber) node.asDouble() else node.asLong()
        node.isTextual -> node.asText()
        node.isArray -> (0 until node.size()).map { toValue(node.get(it)) }
        node.isObject -> node.properties().associate { it.key to toValue(it.value) }
        else -> node.asText()
    }

    fun toMap(node: JsonNode): Map<String, Any?> {
        val value = toValue(node)
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>) ?: emptyMap()
    }
}
