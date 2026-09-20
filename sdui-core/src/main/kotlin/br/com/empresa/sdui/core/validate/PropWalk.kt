package br.com.empresa.sdui.core.validate

/**
 * Percorre props aninhadas para os guards e validadores.
 *
 * As props sao um mapa livre, entao as regras precisam de travessia generica em vez de tipo
 * estatico. Acumula o caminho durante a visita para o erro apontar onde esta o problema, em vez de
 * so dizer que existe.
 */
object PropWalk {
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

    fun collectActionIds(props: Map<String, Any?>): List<String> {
        val ids = mutableListOf<String>()
        fun scan(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    val actionId = node["actionId"]
                    if (actionId is String) ids += actionId
                    node.values.forEach { scan(it) }
                }

                is List<*> -> node.forEach { scan(it) }
            }
        }
        scan(props)
        return ids
    }

    private val FOREIGN_REF_KEYS: Set<String> = setOf("sectionId", "otherSectionId", "slotIndex", "position")

    fun referencesForeignSection(props: Map<String, Any?>, ownId: String, otherIds: Set<String>): Boolean {
        var found = false
        walkStrings(props) { text ->
            if (!found && otherIds.any { other -> other != ownId && text.contains(other) }) {
                found = true
            }
        }
        if (found) return true
        walkKeys(props) { _, key ->
            if (!found && key in FOREIGN_REF_KEYS) {
                found = true
            }
        }
        return found
    }
}
