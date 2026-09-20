package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Action
import br.com.empresa.sdui.core.model.MvpCatalog

/**
 * Recusa atributo de aparencia nas props.
 *
 * O servidor descreve conteudo e semantica; cor, espacamento, medida e animacao sao decisao do
 * cliente. Varre em profundidade e compara sem diferenciar maiusculas, porque a chave proibida
 * costuma aparecer aninhada e com grafia variada.
 */
object VisualGuard {
    private val LOWER_VISUAL_KEYS: Set<String> = MvpCatalog.VISUAL_KEYS.map { it.lowercase() }.toSet()

    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in LOWER_VISUAL_KEYS) {
                found += "$path: campo proibido '$key'"
            }
        }
        return found
    }
}

/**
 * Recusa dado pessoal ou regulado nas props, por chave e por formato.
 *
 * Duas frentes porque cada uma falha sozinha: a chave pega campos nomeados como cpf ou token, e a
 * varredura de texto pega o valor que escapou dentro de um campo de nome inocente.
 */
object PiiGuard {
    private val LOWER_PII_KEYS: Set<String> = MvpCatalog.PII_KEYS.map { it.lowercase() }.toSet()
    private val CPF = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11}\b""")
    private val PAN = Regex("""\b\d{16}\b""")

    fun violations(root: Any?): List<String> {
        val found = mutableListOf<String>()
        PropWalk.walkKeys(root) { path, key ->
            if (key.lowercase() in LOWER_PII_KEYS) {
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

/**
 * Valida as actions de uma section e as referencias a elas.
 *
 * Confere que o tipo esta no catalogo fechado, que navigate aponta para rota app:// — nunca URL
 * externa —, que todo CTA tem rotulo, e que nenhuma prop referencia um actionId inexistente, que
 * viraria um toque sem efeito no app.
 */
object ActionGuard {
    private val CTA_ACTIONS: Set<String> = setOf("navigate", "open_bottom_sheet")

    fun validate(
        sectionId: String,
        actions: List<Action>,
        props: Map<String, Any?>
    ): List<String> {
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
            if (action.type in CTA_ACTIONS && action.label.isNullOrBlank()) {
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
