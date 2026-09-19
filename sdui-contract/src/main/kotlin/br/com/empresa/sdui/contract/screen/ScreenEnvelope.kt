package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.ScreenAnalyticsResponse
import br.com.empresa.sdui.contract.client.ClientResponse
import br.com.empresa.sdui.contract.targeting.TargetingResponse

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
