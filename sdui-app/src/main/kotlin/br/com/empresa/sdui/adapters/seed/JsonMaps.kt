package br.com.empresa.sdui.adapters.seed

import tools.jackson.databind.JsonNode

/**
 * Converte entre JsonNode e as estruturas Kotlin que o dominio usa nas props.
 *
 * Existe porque o core e puro e nao conhece Jackson: a traducao precisa acontecer no adapter.
 * Preserva a distincao entre inteiro e decimal na ida, para um valor nao mudar de tipo ao passar
 * pelo servidor. Recusa aninhamento acima de [MAX_DEPTH], o mesmo teto do mapper da resposta,
 * para que uma fixture malformada falhe com mensagem clara em vez de estourar a pilha.
 */
object JsonMaps {
    const val MAX_DEPTH: Int = 32

    fun toValue(node: JsonNode, depth: Int = 0): Any? {
        require(depth <= MAX_DEPTH) { "JSON aninhado alem de $MAX_DEPTH niveis" }
        return when {
            node.isNull -> null
            node.isBoolean -> node.asBoolean()
            node.isNumber -> if (node.isFloatingPointNumber) node.asDouble() else node.asLong()
            node.isString -> node.asString()
            node.isArray -> (0 until node.size()).map { toValue(node.get(it), depth + 1) }
            node.isObject -> node.properties().associate { it.key to toValue(it.value, depth + 1) }
            else -> node.asString()
        }
    }

    fun toMap(node: JsonNode): Map<String, Any?> {
        val value = toValue(node)
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>) ?: emptyMap()
    }
}
