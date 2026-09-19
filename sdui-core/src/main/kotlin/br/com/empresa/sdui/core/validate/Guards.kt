package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.MvpCatalog

object VisualGuard {
    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in MvpCatalog.VISUAL_KEYS.map { it.lowercase() }.toSet()) {
                found += "$path: campo proibido '$key'"
            }
        }
        return found
    }
}

object PiiGuard {
    private val CPF = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11}\b""")
    private val PAN = Regex("""\b\d{16}\b""")

    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in MvpCatalog.PII_KEYS.map { it.lowercase() }.toSet()) {
                found += "$path: chave regulada '$key'"
            }
        }
        PropWalk.walkStrings(root) { text ->
            if (CPF.containsMatchIn(text)) found += "cpf"
            if (PAN.containsMatchIn(text)) found += "pan"
        }
        return found
    }
}

object ActionGuard {
    fun validate(sectionId: String, actions: List<br.com.empresa.sdui.core.model.Action>, props: Map<String, Any?>): List<String> {
        val errors = mutableListOf<String>()
        val ids = actions.map { it.id }.toSet()
        for (action in actions) {
            if (action.type !in MvpCatalog.ALLOWED_ACTIONS) {
                errors += "section $sectionId action ${action.id} type ${action.type} fora do catalogo"
            }
            if (action.type == "navigate") {
                val route = action.payload?.route
                if (route.isNullOrBlank() || !route.startsWith("app://")) {
                    errors += "section $sectionId action ${action.id} navigate exige rota app://"
                }
            }
            if (action.type in setOf("navigate", "open_bottom_sheet") && action.label.isNullOrBlank()) {
                errors += "section $sectionId action ${action.id} CTA exige label"
            }
        }
        for (actionId in PropWalk.collectActionIds(props)) {
            if (actionId !in ids) {
                errors += "section $sectionId referencia actionId $actionId inexistente"
            }
        }
        return errors
    }
}
