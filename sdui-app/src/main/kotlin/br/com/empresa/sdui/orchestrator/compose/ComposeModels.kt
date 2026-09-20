package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.Section
import java.time.Duration

/**
 * Os prazos e tetos do pipeline de composicao, reunidos num lugar so.
 *
 * Estavam espalhados por parametros default do servico, e por isso o conjunto nunca era lido como
 * conjunto: a espera do waiter no singleflight era vinte e cinco vezes o prazo de hidratacao sem
 * que nada no codigo tornasse a desproporcao visivel. Juntos, os valores se conferem entre si —
 * [request] e o teto da borda e todos os demais precisam caber dentro dele.
 *
 * [singleflightWait] e deliberadamente menor que [request]: quem espera outro compor deve desistir
 * e ir para o last good **antes** de o cliente desistir, senao a espera apenas soma ao tempo total
 * sem melhorar o desfecho.
 */
data class ComposeBudgets(
    /** Validade de uma arvore no cache de composicao. */
    val treeTtl: Duration = Duration.ofSeconds(60),
    /** Prazo total da requisicao, derivado do SLO da borda. */
    val request: Duration = Duration.ofMillis(250),
    /** Quanto um waiter espera o lider do singleflight antes de degradar. */
    val singleflightWait: Duration = Duration.ofMillis(150),
    /** Quanto se espera por uma permissao do bulkhead de leitura antes de degradar. */
    val bulkheadWait: Duration = Duration.ofMillis(50),
    /** Idade maxima de um last good. Acima disso, 503 e melhor que entregar o passado. */
    val maxFallbackAge: Duration = Duration.ofHours(24),
    /** Base do `Retry-After` do 503, antes do jitter. */
    val retryAfterSeconds: Long = 5,
    /** Base do `Retry-After` do 429, antes do jitter. */
    val rateLimitRetryAfterSeconds: Long = 2,
)

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
    data class RateLimited(val retryAfterSeconds: Long) : ComposeResult
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
