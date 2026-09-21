package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.ScreenAnalyticsResponse
import br.com.empresa.sdui.contract.client.ClientResponse
import br.com.empresa.sdui.contract.targeting.TargetingResponse

/**
 * Metadados da composicao: identifica a revisao servida e como ela foi decidida.
 *
 * Carrega os tres eixos de compatibilidade — [schemaVersion] (eixo A), o que o cliente pediu em
 * [client] (eixo C) e a faixa que o spec atende em [targeting]. [fallback] e [fallbackReason]
 * dizem se a resposta veio da escada de degradacao (ADR-007) em vez da composicao normal, e
 * [omitted] lista as sections que ficaram de fora com o motivo de cada uma.
 */
data class ScreenEnvelope(
    val surface: String,
    val platform: String,
    val schemaVersion: String,
    val specRevisionId: String,
    val skeletonId: String,
    val skeletonHash: String,
    val etag: String,
    val generatedAt: String,
    val locale: String,
    val channel: String,
    val fallback: Boolean,
    val fallbackReason: String,
    val omitted: List<OmittedItemResponse> = emptyList(),
    val client: ClientResponse,
    val targeting: TargetingResponse,
    val analytics: ScreenAnalyticsResponse,
)
