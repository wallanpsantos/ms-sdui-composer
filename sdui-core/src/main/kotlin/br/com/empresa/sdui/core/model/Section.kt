package br.com.empresa.sdui.core.model

/** Destino de uma action no modelo de dominio. Rota app://, bottom sheet nativo ou evento. */
data class ActionPayload(
    val route: String? = null,
    val sheet: String? = null,
    val event: String? = null,
)

/** Intencao que o dispatcher nativo executa. [type] vem do catalogo fechado de MvpCatalog. */
data class Action(
    val id: String,
    val type: String,
    val label: String? = null,
    val payload: ActionPayload? = null,
)

/**
 * Bloco autocontido de UI: um dos tres conceitos da triade SDUI, com Screen e Action.
 *
 * Ocupa um [slot] do skeleton e declara o que precisa ser renderizado via [type] e [typeVersion].
 * [props] e um mapa livre porque o conteudo varia por tipo de componente — a disciplina sobre ele
 * vem dos guards, nao do tipo estatico. Autocontida por regra: uma section nao referencia outra.
 */
data class Section(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val layout: String? = null,
    val props: Map<String, Any?>,
    val actions: List<Action> = emptyList(),
) {
    val capability: Capability = Capability(type, typeVersion)
}

/**
 * Registro de uma section que o pipeline decidiu nao entregar.
 *
 * Omitir em vez de falhar e a regra do MVP (ADR-007). A excecao sao os slots portantes: se um
 * deles fica vazio, a composicao inteira cai para a escada de fallback.
 */
data class OmittedSection(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val reason: OmittedReason,
) {
    val capability: Capability = Capability(type, typeVersion)
    fun capability(): Capability = capability
}

fun Section.capability(): Capability = capability
