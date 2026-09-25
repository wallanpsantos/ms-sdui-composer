package br.com.empresa.sdui.adapters.invalidation

import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Drenador periódico em segundo plano da outbox de invalidações de cache (`P10`, `ADR-021`).
 *
 * ### 1. O que faz
 * Monitora e processa periodicamente registros pendentes na outbox de invalidação de cache distribuído.
 *
 * ### 2. Para que serve
 * Cobrir a janela de consistência eventual entre o commit de uma publicação ou rollback e a invalidação
 * efetiva dos caches (Redis e memória), assegurando que nenhum nó continue servindo conteúdo obsoleto caso
 * o pod autor da mudança tenha caído ou o cache estivesse temporariamente fora do ar.
 *
 * ### 3. Como funciona
 * Utiliza um [ScheduledExecutorService] de Virtual Thread única (`sdui-invalidation-relay`).
 * Se [enabled] for verdadeiro, agenda execuções periódicas de [drain] a cada [intervalMs].
 * Delega o processamento atômico das entradas pendentes para [CacheInvalidator.drainPending],
 * marcando o contexto de rastreabilidade MDC com `entryPoint = "invalidation_relay"`.
 */
class CacheInvalidationRelay(
    private val invalidator: CacheInvalidator,
    private val intervalMs: Long,
    private val enabled: Boolean,
) : AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sdui-invalidation-relay").factory())

    /**
     * Inicia a execução periódica do relay em segundo plano.
     *
     * ### 1. O que faz
     * Agenda a rotina [drain] com intervalo fixo no executor agendador.
     *
     * ### 2. Para que serve
     * Ativar o mecanismo de autocura de cache quando a persistência durável está habilitada.
     *
     * ### 3. Como funciona
     * Se [enabled] for falso, encerra imediatamente sem agendamento. Caso contrário, registra [drain]
     * via `scheduleWithFixedDelay` com período [intervalMs].
     */
    fun start() {
        if (!enabled) return
        executor.scheduleWithFixedDelay(::drain, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Executa um ciclo de drenagem das invalidações pendentes na outbox.
     *
     * ### 1. O que faz
     * Invoca o processamento das tarefas de invalidação e atualiza o estado da outbox.
     *
     * ### 2. Para que serve
     * Processar lotes de invalidação acumulados; exposto publicamente para ensaios operacionais e runbooks.
     *
     * ### 3. Como funciona
     * Configura `entryPoint = "invalidation_relay"` no MDC, executa `invalidator.drainPending()`,
     * alerta no log caso restem itens pendentes ou ocorram exceções, e limpa o MDC no `finally`.
     */
    fun drain() {
        MDC.put("entryPoint", "invalidation_relay")
        try {
            val failed = invalidator.drainPending()
            if (failed > 0) LOG.warn("invalidacoes de cache ainda pendentes apos drenagem: {}", failed)
        } catch (error: Exception) {
            LOG.warn("drenagem do outbox de invalidacao falhou", error)
        } finally {
            MDC.remove("entryPoint")
        }
    }

    /**
     * Encerra o executor do relay liberando recursos de agendamento.
     *
     * ### 1. O que faz
     * Interrompe o executor agendador de tarefas em segundo plano.
     *
     * ### 2. Para que serve
     * Garantir encerramento gracioso e liberação de recursos na finalização do contexto da aplicação.
     *
     * ### 3. Como funciona
     * Invoca `executor.shutdownNow()`.
     */
    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(CacheInvalidationRelay::class.java)
    }
}
