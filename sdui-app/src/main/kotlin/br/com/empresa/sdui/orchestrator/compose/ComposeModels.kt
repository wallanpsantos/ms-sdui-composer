package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders

data class ComposeRequest(
    val headers: NegotiateHeaders,
    val ifNoneMatch: String? = null,
    val identity: String,
)

sealed interface ComposeResult {
    data class Success(val screen: ComposedScreen, val fromCache: Boolean) : ComposeResult
    data class NotModified(val etag: String) : ComposeResult
    data class InvalidHeaders(val violations: List<ContextViolation>) : ComposeResult
    data object RateLimited : ComposeResult
    data class Unavailable(val retryAfterSeconds: Long, val reason: FallbackReason) : ComposeResult
}

data class HydrationContext(
    val surface: String,
    val platform: br.com.empresa.sdui.core.model.ClientPlatform,
    val specRevisionId: String,
    val locale: String,
    val channel: Channel,
)

sealed interface HydrationResult {
    data class Ok(val props: Map<String, Any?>) : HydrationResult
    data class Failed(val reason: br.com.empresa.sdui.core.model.OmittedReason) : HydrationResult
}

interface SectionHydrator {
    fun supports(type: String, typeVersion: Int): Boolean
    fun hydrate(context: HydrationContext, section: br.com.empresa.sdui.core.model.Section): HydrationResult
}
