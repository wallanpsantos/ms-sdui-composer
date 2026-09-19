package br.com.empresa.sdui.core.model

data class ActionPayload(
    val route: String? = null,
    val sheet: String? = null,
    val event: String? = null,
)

data class Action(
    val id: String,
    val type: String,
    val label: String? = null,
    val payload: ActionPayload? = null,
)

data class Section(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val layout: String? = null,
    val props: Map<String, Any?>,
    val actions: List<Action> = emptyList(),
)

data class OmittedSection(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val reason: OmittedReason,
) {
    fun capability(): Capability = Capability(type, typeVersion)
}

fun Section.capability(): Capability = Capability(type, typeVersion)
