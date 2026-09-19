package br.com.empresa.sdui.core.validate

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

    fun asStringMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        return value.entries.associate { (k, v) -> k.toString() to v }
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

    fun referencesForeignSection(props: Map<String, Any?>, ownId: String, otherIds: Set<String>): Boolean {
        var found = false
        walkStrings(props) { text ->
            if (otherIds.any { other -> other != ownId && text.contains(other) }) {
                found = true
            }
        }
        walkKeys(props) { _, key ->
            if (key in setOf("sectionId", "otherSectionId", "slotIndex", "position")) {
                found = true
            }
        }
        return found
    }
}
