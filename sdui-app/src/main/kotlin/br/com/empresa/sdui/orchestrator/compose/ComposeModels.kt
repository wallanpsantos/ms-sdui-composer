package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.Section

/** Entrada do pipeline: headers de negociacao, o ETag que o cliente ja tem e a identidade limitada. */
data class ComposeRequest(
    val headers: NegotiateHeaders,
    val ifNoneMatch: String? = null,
    val identity: String,
)

/**
 * Os desfechos possiveis de uma composicao, incluindo os que nao sao sucesso.
 *
 * Tipo soma para que a borda HTTP traduza cada caso ao status certo sem inventar comportamento, e
 * para o compilador cobrar tratamento quando um caso novo aparecer.
 */
sealed interface ComposeResult {
    data class Success(val screen: ComposedScreen, val fromCache: Boolean) : ComposeResult
    data class NotModified(val etag: String) : ComposeResult
    data class InvalidHeaders(val violations: List<ContextViolation>) : ComposeResult
    data object RateLimited : ComposeResult
    data class Unavailable(val retryAfterSeconds: Long, val reason: FallbackReason) : ComposeResult
}

/** O que um hidratador precisa saber da requisicao, sem acesso ao contexto completo do cliente. */
data class HydrationContext(
    val surface: String,
    val platform: ClientPlatform,
    val specRevisionId: String,
    val locale: String,
    val channel: Channel,
)

/**
 * Resultado da hidratacao de uma section: props prontas ou o motivo da omissao.
 *
 * Falha e valor de retorno, nao excecao, porque uma section que falha nao deve derrubar as demais.
 */
sealed interface HydrationResult {
    data class Ok(val props: Map<String, Any?>) : HydrationResult
    data class Failed(val reason: OmittedReason) : HydrationResult
}

/**
 * Preenche as props de uma section a partir de projecoes seguras.
 *
 * Um hidratador declara em [supports] com quais tipos lida, o que permite acrescentar fontes de
 * dado por tipo de componente sem tocar no pipeline. Nao deve vazar PII para as props.
 */
interface SectionHydrator {
    fun supports(type: String, typeVersion: Int): Boolean
    fun hydrate(context: HydrationContext, section: Section): HydrationResult
}
