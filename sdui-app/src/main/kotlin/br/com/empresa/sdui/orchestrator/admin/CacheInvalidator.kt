package br.com.empresa.sdui.orchestrator.admin

import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import java.util.concurrent.atomic.AtomicInteger

/**
 * Aplica as invalidacoes de cache devidas por uma mudanca de pointer ja commitada (ADR-021).
 *
 * A publicacao e o rollback gravam a invalidacao no outbox dentro da transacao e chamam [apply]
 * logo depois do commit. Se o processo cair no meio, ou o cache estiver fora, o registro fica
 * pendente e [drainPending] — chamado periodicamente pelo relay — o aplica depois.
 *
 * Aplicar duas vezes e seguro: o cache de arvore e o de spec sao chaveados por revisao, e a lapide
 * do last good so avanca de versao. Nao ha retry no caminho da requisicao; a reaplicacao e reparo
 * em segundo plano de um efeito ja commitado, sem ninguem esperando por ele.
 */
class CacheInvalidator(
    private val specCache: SpecCache,
    private val treeCache: HydratedScreenCache,
    private val lastGood: LastGoodScreenStore,
    private val outbox: CacheInvalidationOutbox,
    private val metrics: MetricsRecorder,
) {
    private val pendingAfterDrain = AtomicInteger()

    /** Quantas invalidacoes continuavam pendentes ao fim da ultima drenagem. Vira gauge. */
    fun pendingCount(): Int = pendingAfterDrain.get()

    /** Aplica e marca como aplicada. Em falha, deixa pendente, registra e devolve false. */
    fun apply(invalidation: CacheInvalidation): Boolean {
        val tags = mapOf(
            "surface" to invalidation.surface,
            "platform" to invalidation.platform.wire(),
            "channel" to invalidation.channel.wire(),
        )
        return try {
            invalidation.retiredSpecRevisionId?.let { specCache.invalidate(it, invalidation.platform) }
            treeCache.invalidate(invalidation.surface, invalidation.platform, invalidation.channel)
            lastGood.invalidate(
                invalidation.surface,
                invalidation.platform,
                invalidation.channel,
                invalidation.pointerVersion,
            )
            outbox.markApplied(invalidation.id)
            metrics.increment(MetricNames.CACHE_INVALIDATION_APPLIED, tags)
            true
        } catch (error: Exception) {
            metrics.increment(MetricNames.CACHE_INVALIDATION_FAILED, tags)
            LOG.log(
                System.Logger.Level.WARNING,
                "invalidacao de cache pendente para ${invalidation.surface}:${invalidation.platform.wire()}:" +
                        "${invalidation.channel.wire()} v${invalidation.pointerVersion}",
                error,
            )
            false
        }
    }

    /**
     * Reaplica ate [limit] invalidacoes pendentes, em ordem de criacao, e devolve quantas falharam.
     * Uma falha ao listar o outbox tambem conta como pendencia: o relay tenta de novo no proximo
     * ciclo.
     */
    fun drainPending(limit: Int = DEFAULT_DRAIN_LIMIT): Int {
        val pending = try {
            outbox.pending(limit)
        } catch (error: Exception) {
            // Mesmo contador das demais falhas de store, com o mesmo conjunto de tags: o registro
            // Prometheus exige chaves de tag identicas para todas as series de um nome.
            metrics.increment(MetricNames.STORE_FAILURE, mapOf("stage" to STAGE_OUTBOX))
            LOG.log(System.Logger.Level.WARNING, "outbox de invalidacao indisponivel", error)
            return pendingAfterDrain.get()
        }
        val failed = pending.count { !apply(it) }
        pendingAfterDrain.set(failed)
        return failed
    }

    private companion object {
        const val DEFAULT_DRAIN_LIMIT: Int = 100
        const val STAGE_OUTBOX: String = "invalidation_outbox"
        val LOG: System.Logger = System.getLogger("br.com.empresa.sdui.orchestrator.admin.CacheInvalidator")
    }
}
