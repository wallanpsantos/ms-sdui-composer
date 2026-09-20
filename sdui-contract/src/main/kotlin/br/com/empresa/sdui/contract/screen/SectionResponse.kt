package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.SectionAnalyticsResponse
import br.com.empresa.sdui.contract.component.ActionResponse
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

/**
 * Uma section renderizavel: bloco autocontido que ocupa um slot do skeleton.
 *
 * [type] e [typeVersion] formam a capability que o cliente precisa saber renderizar (eixo B);
 * [props] vai como JsonNode porque o conteudo e livre por tipo de componente, mas nunca carrega
 * atributo visual nem PII — VisualGuard e PiiGuard barram isso na publicacao.
 */
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
