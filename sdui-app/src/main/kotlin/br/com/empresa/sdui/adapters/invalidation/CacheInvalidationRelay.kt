package br.com.empresa.sdui.adapters.invalidation

import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Drena periodicamente o outbox de invalidacoes de cache (P10, ADR-021).
 *
 * Cobre o intervalo entre o commit de uma publicacao ou rollback e a invalidacao que devia vir
 * logo depois: se o processo caiu ou o cache estava fora, o registro ficou pendente e e aplicado
 * aqui, por qualquer instancia. Nao ha requisicao esperando por isso — e reparo em segundo plano,
 * com intervalo fixo, sem backoff agressivo nem concorrencia propria.
 *
 * So agenda quando ha algum componente persistente ([enabled]); em memoria o outbox e os caches
 * morrem juntos com o processo e nao sobra nada a reparar.
 */
class CacheInvalidationRelay(
    private val invalidator: CacheInvalidator,
    private val intervalMs: Long,
    private val enabled: Boolean,
) : AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sdui-invalidation-relay").factory())

    fun start() {
        if (!enabled) return
        executor.scheduleWithFixedDelay(::drain, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    /** Uma drenagem. Publico para o teste de recuperacao e para acionamento manual em runbook. */
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

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(CacheInvalidationRelay::class.java)
    }
}
