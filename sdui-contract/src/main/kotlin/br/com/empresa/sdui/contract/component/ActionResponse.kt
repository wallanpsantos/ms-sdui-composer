package br.com.empresa.sdui.contract.component

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionPayload(
    val route: String? = null,
    val sheet: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionResponse(
    val id: String,
    val type: String,
    val label: String? = null,
    val payload: ActionPayload? = null,
)
