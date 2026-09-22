package br.com.empresa.sdui.core.limit

import java.time.Duration

/**
 * O prazo total de uma requisicao, consultavel entre as etapas do pipeline.
 *
 * Cada etapa da composicao ja tinha o seu proprio teto, mas nenhum deles conhecia o custo das
 * anteriores: a soma dos tetos era o pior caso real, e ninguem o declarava. Este orcamento e a
 * declaracao — o prazo nasce do SLO da borda e cada etapa recebe [stage], que e o menor entre o
 * teto daquela etapa e o que ainda resta.
 *
 * Nao interrompe nada. Uma chamada sincrona de store ja em andamento nao e cancelavel a partir
 * daqui; prazo por chamada e responsabilidade do adapter (timeout de socket do driver) quando a
 * persistencia deixar de ser em memoria. O que este tipo garante e que o pipeline nao *inicie*
 * etapa nova sem prazo e que as esperas configuraveis — singleflight e hidratacao — nunca
 * ultrapassem o que o cliente ainda esta disposto a aguardar.
 *
 * Usa [nanoTime] e nao relogio de parede porque o valor e uma diferenca: relogio de parede pode
 * andar para tras num ajuste de NTP e produzir prazo negativo ou eterno.
 */
class TimeBudget(
    private val total: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startNanos: Long = nanoTime()

    /** Quanto ja se gastou desde a criacao do orcamento. */
    fun elapsed(): Duration = Duration.ofNanos(nanoTime() - startNanos)

    /** Quanto ainda resta, nunca negativo. */
    fun remaining(): Duration {
        val left = total.toNanos() - (nanoTime() - startNanos)
        return if (left <= 0L) Duration.ZERO else Duration.ofNanos(left)
    }

    /** true quando nao ha mais prazo: iniciar outra etapa so adiaria a mesma falha. */
    fun isExhausted(): Boolean = (nanoTime() - startNanos) >= total.toNanos()

    /** O prazo desta etapa: o menor entre o teto dela e o que resta do orcamento. */
    fun stage(cap: Duration): Duration {
        val left = remaining()
        return if (left < cap) left else cap
    }
}
