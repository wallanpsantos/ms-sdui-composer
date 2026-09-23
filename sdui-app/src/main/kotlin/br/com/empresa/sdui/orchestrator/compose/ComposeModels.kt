package br.com.empresa.sdui.orchestrator.compose

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
 *
 * O orcamento limita espera, nunca trabalho. Abandonar uma composicao no meio troca latencia por
 * erro e joga fora o que ja foi pago; deixar de esperar por outro nao custa nada a ninguem.
 */
data class ComposeBudgets(
    /** Validade de uma arvore no cache de composicao. */
    val treeTtl: Duration = Duration.ofSeconds(60),
    /**
     * Prazo total da requisicao: o limite externo de paciencia do cliente, nao a meta interna.
     *
     * Generoso de proposito. Ele so encolhe esperas, e um valor apertado faria o primeiro request
     * de um pod recem-subido — com a JVM ainda fria — desistir de esperar sem motivo.
     */
    val request: Duration = Duration.ofSeconds(1),
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
