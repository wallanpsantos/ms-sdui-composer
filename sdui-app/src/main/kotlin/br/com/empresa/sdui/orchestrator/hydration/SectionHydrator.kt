package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.Section

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
 *
 * [performsIo] diz se a hidratacao espera por alguma dependencia. So essas vao para o fan-out em
 * virtual threads, com semaforo e prazo; um hidratador sem I/O roda na thread da requisicao, onde
 * uma tarefa assincrona so acrescentaria custo (medido em 2026-09-23: 8 tarefas por miss para
 * repassar mapas).
 */
interface SectionHydrator {
    fun supports(type: String, typeVersion: Int): Boolean
    fun hydrate(context: HydrationContext, section: Section): HydrationResult
    val performsIo: Boolean get() = true
}
