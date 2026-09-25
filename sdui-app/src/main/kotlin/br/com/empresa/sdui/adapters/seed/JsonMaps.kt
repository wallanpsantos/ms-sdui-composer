package br.com.empresa.sdui.adapters.seed

import tools.jackson.databind.JsonNode

/**
 * Conversor utilitário de nós [JsonNode] para estruturas de dados puras do Kotlin.
 *
 * ### 1. O que faz
 * Transforma recursivamente árvores de nós JSON do Jackson em coleções nativas (`Map`, `List`) e tipos primitivos.
 *
 * ### 2. Para que serve
 * Manter o módulo de domínio `sdui-core` puro e desacoplado de dependências diretas de bibliotecas de serialização,
 * convertendo o conteúdo dinâmico de `props` das seções em dicionários padrão da linguagem.
 *
 * ### 3. Como funciona
 * Percorre recursivamente a estrutura de nós JSON preservando a precisão numérica (distinguindo inteiros de decimais).
 * Protege contra estouro de pilha impondo profundidade máxima de aninhamento fixada em [MAX_DEPTH].
 */
object JsonMaps {
    /** Profundidade máxima permitida para estruturas JSON aninhadas. */
    const val MAX_DEPTH: Int = 32

    /**
     * Converte um nó [JsonNode] em seu valor primitivo ou estrutural correspondente em Kotlin.
     *
     * ### 1. O que faz
     * Mapeia um nó JSON para tipos da biblioteca padrão do Kotlin.
     *
     * ### 2. Para que serve
     * Traduzir valores heterogêneos de propriedades sem perder o tipo semântico original.
     *
     * ### 3. Como funciona
     * Exige `depth <= MAX_DEPTH`. Trata nulos, booleanos, números ([Double] ou [Long]), strings,
     * arrays (convertidos para listas recursivas) e objetos (convertidos para mapas associativos).
     */
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

    /**
     * Converte um nó [JsonNode] de objeto para um mapa de propriedades.
     *
     * ### 1. O que faz
     * Transforma um nó de objeto JSON em um [Map] indexado por chaves de texto.
     *
     * ### 2. Para que serve
     * Extrair o mapa de `props` das seções de UI serializadas em fixtures ou requisições.
     *
     * ### 3. Como funciona
     * Invoca [toValue] e realiza cast seguro para mapa, retornando `emptyMap()` se o nó não for do tipo objeto.
     */
    fun toMap(node: JsonNode): Map<String, Any?> {
        val value = toValue(node)
        @Suppress("UNCHECKED_CAST")
        return (value as? Map<String, Any?>) ?: emptyMap()
    }
}
