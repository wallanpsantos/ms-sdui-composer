package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.SectionAnalyticsResponse
import br.com.empresa.sdui.contract.component.ActionResponse
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SectionResponse(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val layout: String? = null,
    val props: JsonNode,
    val actions: List<ActionResponse> = emptyList(),
    val analytics: SectionAnalyticsResponse,
)
