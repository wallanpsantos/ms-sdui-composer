package br.com.empresa.sdui.adapters.seed

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

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
        node.isNumber -> {
            val text = node.asText()
            if (text.contains('.') || text.contains('e') || text.contains('E')) node.asDouble() else node.asLong()
        }

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

    fun toNode(mapper: JsonMapper, value: Any?, depth: Int = 0): JsonNode {
        if (value == null) return mapper.nodeFactory.nullNode()
        if (depth > 32) return mapper.nodeFactory.stringNode("[truncated]")
        return when (value) {
            is JsonNode -> value
            is String -> mapper.nodeFactory.stringNode(value)
            is Boolean -> mapper.nodeFactory.booleanNode(value)
            is Int -> mapper.nodeFactory.numberNode(value)
            is Long -> mapper.nodeFactory.numberNode(value)
            is Double -> mapper.nodeFactory.numberNode(value)
            is Float -> mapper.nodeFactory.numberNode(value)
            is List<*> -> {
                val array: ArrayNode = mapper.nodeFactory.arrayNode()
                value.forEach { array.add(toNode(mapper, it, depth + 1)) }
                array
            }

            is Map<*, *> -> {
                val obj: ObjectNode = mapper.nodeFactory.objectNode()
                value.forEach { (k, v) -> obj.set(k.toString(), toNode(mapper, v, depth + 1)) }
                obj
            }

            else -> mapper.nodeFactory.stringNode(value.toString())
        }
    }
}
