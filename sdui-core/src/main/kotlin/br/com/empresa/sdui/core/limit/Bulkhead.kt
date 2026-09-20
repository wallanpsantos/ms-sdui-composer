package br.com.empresa.sdui.core.limit

import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Como terminou a tentativa de entrar num bulkhead: executou, ou foi recusada por lotacao.
 *
 * Recusa e valor de retorno, e nao excecao, porque lotacao nao e erro — e a resposta correta de um
 * limitador funcionando. O chamador decide o que fazer com ela, normalmente degradar.
 */
sealed interface BulkheadOutcome<out T> {
    data class Executed<T>(val value: T) : BulkheadOutcome<T>
    data object Rejected : BulkheadOutcome<Nothing>
}

/**
 * Teto de chamadas simultaneas a uma dependencia.
 *
 * Com virtual threads nao existe mais pool limitado fazendo shedding: o servico aceita tantas
 * requisicoes quantas chegarem e todas podem ficar paradas na mesma dependencia lenta. O bulkhead
 * devolve esse limite de forma explicita e local — quando a dependencia degrada, a fila tem
 * tamanho conhecido e o excedente degrada rapido em vez de esperar.
 *
 * Um bulkhead por plano: o de leitura da home nao compartilha permissoes com o plano
 * administrativo, senao uma publicacao lenta consumiria a capacidade de atender o app.
 */
class Bulkhead(permits: Int) {
    private val semaphore = Semaphore(permits)

    /** Permissoes livres agora. Serve a diagnostico e aos testes do teto. */
    fun availablePermits(): Int = semaphore.availablePermits()

    /**
     * Executa [work] segurando uma permissao, ou devolve [BulkheadOutcome.Rejected] se nao
     * conseguir uma dentro de [timeout]. A permissao e sempre devolvida, inclusive se [work]
     * lancar.
     */
    fun <T> withPermit(timeout: Duration, work: () -> T): BulkheadOutcome<T> {
        val waitMs = timeout.toMillis().coerceAtLeast(0L)
        if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return BulkheadOutcome.Rejected
        return try {
            BulkheadOutcome.Executed(work())
        } finally {
            semaphore.release()
        }
    }
}
