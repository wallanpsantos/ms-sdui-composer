package br.com.empresa.sdui.core.validate

/**
 * Percorre props aninhadas para os guards e validadores.
 *
 * As props sao um mapa livre, entao as regras precisam de travessia generica em vez de tipo
 * estatico. Acumula o caminho durante a visita para o erro apontar onde esta o problema, em vez de
 * so dizer que existe.
 *
 * As travessias que respondem sim ou nao ([anyString], [anyKey], [exceedsDepth]) param na primeira
 * ocorrencia: nao descem mais nenhum nivel depois que a resposta ja e conhecida.
 */
object PropWalk {
    /** Maior profundidade de containers aceita nas props publicadas. Ver [exceedsDepth]. */
    const val MAX_PROPS_DEPTH: Int = 16

    fun walkKeys(value: Any?, path: String = "", visit: (path: String, key: String) -> Unit) {
        when (value) {
            is Map<*, *> -> {
                for ((rawKey, child) in value) {
                    val key = rawKey?.toString() ?: continue
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    visit(childPath, key)
                    walkKeys(child, childPath, visit)
                }
            }

            is List<*> -> {
                value.forEachIndexed { index, child ->
                    walkKeys(child, "$path[$index]", visit)
                }
            }
        }
    }

    fun walkStrings(value: Any?, visit: (String) -> Unit) {
        when (value) {
            is String -> visit(value)
            is Map<*, *> -> value.values.forEach { walkStrings(it, visit) }
            is List<*> -> value.forEach { walkStrings(it, visit) }
        }
    }

    /** true na primeira string que satisfaz [predicate]; a travessia termina ali. */
    fun anyString(value: Any?, predicate: (String) -> Boolean): Boolean = when (value) {
        is String -> predicate(value)
        is Map<*, *> -> value.values.any { anyString(it, predicate) }
        is List<*> -> value.any { anyString(it, predicate) }
        else -> false
    }

    /** true na primeira chave, em qualquer nivel, que satisfaz [predicate]. */
    fun anyKey(value: Any?, predicate: (String) -> Boolean): Boolean = when (value) {
        is Map<*, *> -> value.entries.any { (key, child) ->
            (key != null && predicate(key.toString())) || anyKey(child, predicate)
        }

        is List<*> -> value.any { anyKey(it, predicate) }
        else -> false
    }

    /**
     * true quando ha containers aninhados alem de [maxDepth]; o mapa raiz das props conta como 1.
     *
     * A recursao nunca passa de `maxDepth + 1` niveis, entao a verificacao em si nao arrisca
     * estourar a pilha com um documento hostil. Serve para recusar na publicacao o que o mapper
     * da resposta teria de truncar.
     */
    fun exceedsDepth(value: Any?, maxDepth: Int = MAX_PROPS_DEPTH, depth: Int = 0): Boolean = when (value) {
        is Map<*, *> -> depth + 1 > maxDepth || value.values.any { exceedsDepth(it, maxDepth, depth + 1) }
        is List<*> -> depth + 1 > maxDepth || value.any { exceedsDepth(it, maxDepth, depth + 1) }
        else -> false
    }

    /**
     * Toda referencia a action nas props: a chave `actionId` e as nomeadas `<papel>ActionId`
     * (`searchActionId`, `viewAllActionId`), usadas quando um componente tem mais de um gatilho.
     */
    fun collectActionIds(props: Map<String, Any?>): List<String> {
        val ids = mutableListOf<String>()
        fun scan(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    for ((key, child) in node) {
                        if (key is String && isActionReference(key) && child is String) ids += child
                    }
                    node.values.forEach { scan(it) }
                }

                is List<*> -> node.forEach { scan(it) }
            }
        }
        scan(props)
        return ids
    }

    fun isActionReference(key: String): Boolean = key == "actionId" || key.endsWith("ActionId")

    private val FOREIGN_REF_KEYS: Set<String> = setOf("sectionId", "otherSectionId", "slotIndex", "position")

    fun referencesForeignSection(props: Map<String, Any?>, ownId: String, otherIds: Set<String>): Boolean {
        val others = otherIds.filter { it != ownId && it.isNotEmpty() }
        if (others.isNotEmpty() && anyString(props) { text -> others.any { text.contains(it) } }) return true
        return anyKey(props) { it in FOREIGN_REF_KEYS }
    }
}
