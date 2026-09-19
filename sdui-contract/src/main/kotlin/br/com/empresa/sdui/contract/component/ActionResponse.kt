package br.com.empresa.sdui.contract.component

data class ActionPayload(
    val route: String? = null,
    val sheet: String? = null,
)

data class ActionResponse(
    val id: String,
    val type: String,
    val label: String? = null,
    val payload: ActionPayload? = null,
)
