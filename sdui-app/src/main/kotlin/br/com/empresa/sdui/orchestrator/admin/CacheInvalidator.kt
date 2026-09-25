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
 * Coordenador de invalidação assíncrona e desacoplada de caches (ADR-021).
 *
 * ### 1. O que faz
 * Aplica as invalidações necessárias nas camadas de cache de especificações, cache de árvore hidratada
 * e repositório de last-good após a alteração bem-sucedida de um ponteiro de versão.
 *
 * ### 2. Para que serve
 * Garante a consistência entre o banco de dados autoritativo e os caches rápidos. Ao executar as
 * invalidações **estritamente fora e após o commit** da transação, impede que leituras do hot path
 * recebam estados que ainda poderiam sofrer rollback em caso de falha no banco.
 *
 * ### 3. Como funciona
 * Os serviços de publicação e rollback registram a intenção de invalidação no [outbox] dentro da
 * transação do banco e chamam [apply] logo após o commit. Se o processo reiniciar ou o Redis estiver
 * temporariamente inacessível, o registro permanece como pendente no outbox e a rotina [drainPending]
 * o reprocessa em segundo plano de forma segura e idempotente.
 *
 * @property specCache Cache de especificações de tela a ser invalidado para revisões aposentadas.
 * @property treeCache Cache de árvores hidratadas a ter entradas limpas por surface, plataforma e canal.
 * @property lastGood Armazenamento de last-good a ter a versão de lápide atualizada.
 * @property outbox Repositório transacional de outbox para controle de pendências de invalidação.
 * @property metrics Gravador de métricas para monitoramento das taxas de sucesso e falha de invalidação.
 */
class CacheInvalidator(
    private val specCache: SpecCache,
    private val treeCache: HydratedScreenCache,
    private val lastGood: LastGoodScreenStore,
    private val outbox: CacheInvalidationOutbox,
    private val metrics: MetricsRecorder,
) {
    private val pendingAfterDrain = AtomicInteger()

    /**
     * Retorna a quantidade de invalidações que continuaram pendentes após o último ciclo de drenagem.
     *
     * ### 1. O que faz
     * Informa o número de itens na fila de outbox que não puderam ser aplicados.
     *
     * ### 2. Para que serve
     * Alimenta gauges de monitoramento para detecção precoce de dessincronização de cache.
     *
     * ### 3. Como funciona
     * Lê o valor inteiro atômico mantido em memória.
     *
     * @return Total de pendências registradas no último ciclo.
     */
    fun pendingCount(): Int = pendingAfterDrain.get()

    /**
     * Aplica uma ordem de invalidação e atualiza seu status no outbox.
     *
     * ### 1. O que faz
     * Limpa as entradas de cache obsoletas e marca o registro como concluído.
     *
     * ### 2. Para que serve
     * Expurgar revisões substituídas e impedir que falhas de composições futuras sirvam a versão recém-desativada.
     *
     * ### 3. Como funciona
     * 1. Remove a revisão aposentada de [specCache], se informada.
     * 2. Invalida as árvores de tela em [treeCache] correspondentes à surface, plataforma e canal.
     * 3. Registra a lápide com a nova versão do ponteiro em [lastGood].
     * 4. Marca o evento no [outbox] como aplicado e incrementa métricas de sucesso.
     * 5. Em caso de falha de conexão com os caches, captura o erro, registra log WARNING e retorna `false`.
     *
     * @param invalidation Dados descritivos da invalidação a ser executada.
     * @return `true` se a invalidação e confirmação no outbox foram concluídas com sucesso, `false` caso contrário.
     */
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
     * Reaplica em lote até [limit] registros de invalidação pendentes.
     *
     * ### 1. O que faz
     * Varre o outbox em busca de eventos pendentes e tenta executá-los sequencialmente.
     *
     * ### 2. Para que serve
     * Atua como mecanismo de reconciliação em segundo plano (relay) garantindo consistência eventual.
     *
     * ### 3. Como funciona
     * Consulta até [limit] itens pendentes no [outbox], invoca [apply] para cada um, atualiza o
     * indicador atômico de pendências e retorna a contagem de falhas remanescentes.
     *
     * @param limit Quantidade máxima de registros a drenar nesta execução (padrão: 100).
     * @return Número de invalidações que continuaram falhando.
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

    /**
     * Constantes e logger estático para o executor de invalidação de cache.
     *
     * ### 1. O que faz
     * Mantém constantes de lote, identificação de estágio para métricas e logger JDK.
     *
     * ### 2. Para que serve
     * Padroniza configurações internas e telemetria sem dependência de bibliotecas de terceiros.
     *
     * ### 3. Como funciona
     * Define o teto padrão de drenagem (`DEFAULT_DRAIN_LIMIT`), tag de estágio do outbox e o [System.Logger].
     */
    private companion object {
        const val DEFAULT_DRAIN_LIMIT: Int = 100
        const val STAGE_OUTBOX: String = "invalidation_outbox"
        val LOG: System.Logger = System.getLogger("br.com.empresa.sdui.orchestrator.admin.CacheInvalidator")
    }
}
