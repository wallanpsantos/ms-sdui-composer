package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.filter.Filter
import br.com.empresa.sdui.core.limit.RetryAfter
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * Contrato para a escada de fallback e tratamento de degradacao (ADR-007).
 *
 * Segrega a politica de degradacao de erros do fluxo principal de montagem de telas (SRP, DIP),
 * permitindo testes e evolucoes isoladas da escada de resiliencia sem afetar o happy-path.
 */
interface FallbackCoordinator {
    fun fallbackOrUnavailable(
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult

    fun retryAfter(baseSeconds: Long): Long
    fun reportStoreFailure(stage: String, error: Throwable)
    fun reportCacheWriteFailure(cache: String, error: Throwable)
}

/**
 * Implementacao padrao da escada de fallback (ADR-007):
 * 1. Tenta recuperar arvore valida do Last Good Store;
 * 2. Valida se a idade nao excede maxFallbackAge;
 * 3. Aplica Filter com as capabilities suportadas pelo cliente;
 * 4. Assegura que todos os slots portantes/obrigatorios estao presentes;
 * 5. Caso contrario, degrada para 503 Service Unavailable com Retry-After com jitter.
 */
class DefaultFallbackCoordinator(
    private val lastGood: LastGoodScreenStore,
    private val matrix: CapabilityMatrix,
    private val metrics: MetricsRecorder,
    private val clock: Clock,
    private val budgets: ComposeBudgets,
    private val randomFraction: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : FallbackCoordinator {

    /**
     * O ultimo degrau antes do 503: tenta o last good e so desiste quando nao ha um utilizavel.
     *
     * Um last good velho demais e recusado de proposito. Arvore defasada e melhor que 503 durante
     * um incidente de minutos; depois de um dia ela ja nao descreve o produto, e entrega-la seria
     * trocar indisponibilidade visivel por incorrecao silenciosa.
     */
    override fun fallbackOrUnavailable(
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult {
        val channelTags = tags + mapOf("channel" to channel.wire())
        serveLastGood(context, channel, reason, channelTags)?.let { return it }
        // O pior desfecho do servico precisa ter contador proprio. Sem ele a taxa de 503 so existe
        // no contador HTTP generico, sem o motivo — que e a unica informacao que diz ao operador
        // onde olhar.
        metrics.increment(COMPOSE_UNAVAILABLE, channelTags + mapOf("fallbackReason" to reason.wire))
        return ComposeResult.Unavailable(retryAfter(budgets.retryAfterSeconds), reason)
    }

    /** Devolve o last good pronto para servir, ou null quando ausente, vencido ou sem slot portante. */
    private fun serveLastGood(
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        channelTags: Map<String, String>,
    ): ComposeResult.Success? {
        val stored = try {
            lastGood.get(MvpCatalog.SURFACE_HOME, context.platform, channel)
        } catch (error: Exception) {
            reportStoreFailure(STAGE_LAST_GOOD, error)
            null
        } ?: return null

        val age = Duration.between(stored.storedAt, clock.instant())
        if (age > budgets.maxFallbackAge) {
            metrics.increment(FALLBACK_EXPIRED, channelTags)
            return null
        }

        val screen = stored.screen
        val filtered = Filter.filter(screen.sections, screen.skeleton, matrix.effective(context))
        val presentSlots = filtered.sections.map { it.slot }.toSet()
        if (!screen.skeleton.requiredSlotIds.all { it in presentSlots }) return null

        metrics.recordTime(FALLBACK_AGE, age.toMillis().coerceAtLeast(0L), channelTags)
        metrics.increment("compose.fallback", channelTags + mapOf("fallbackReason" to reason.wire))
        return ComposeResult.Success(
            screen.withRequester(clock, context).copy(
                sections = filtered.sections,
                omitted = screen.omitted + filtered.omitted,
                fallback = true,
                fallbackReason = reason,
            ),
            fromCache = true,
        )
    }

    /** `Retry-After` com jitter, para a coorte recusada nao voltar toda no mesmo segundo. */
    override fun retryAfter(baseSeconds: Long): Long =
        RetryAfter.jittered(baseSeconds, randomFraction())

    override fun reportStoreFailure(stage: String, error: Throwable) {
        metrics.increment(STORE_FAILURE, mapOf("stage" to stage))
        LOG.log(System.Logger.Level.WARNING, "falha de dependencia de dados na etapa $stage", error)
    }

    override fun reportCacheWriteFailure(cache: String, error: Throwable) {
        metrics.increment(CACHE_WRITE_FAILURE, mapOf("cache" to cache))
        LOG.log(System.Logger.Level.WARNING, "escrita de cache perdida em $cache", error)
    }

    private companion object {
        /**
         * Nomes fixos: a dimensao vai em tag. Um nome interpolado criaria uma serie por valor e
         * multiplicaria a cardinalidade do registry.
         */
        const val COMPOSE_UNAVAILABLE: String = "compose.unavailable"
        const val STORE_FAILURE: String = "store.failure"
        const val CACHE_WRITE_FAILURE: String = "cache.write.failure"
        const val FALLBACK_AGE: String = "compose.fallback.age.ms"
        const val FALLBACK_EXPIRED: String = "compose.fallback.expired"
        const val STAGE_LAST_GOOD: String = "last_good"

        /**
         * `System.Logger` e nao SLF4J: o orchestrator nao depende de framework e o JDK basta. O
         * Spring Boot instala a ponte de JUL para o backend de log, entao estas linhas chegam ao
         * mesmo destino que as do resto do servico.
         */
        val LOG: System.Logger =
            System.getLogger("br.com.empresa.sdui.orchestrator.compose.DefaultFallbackCoordinator")
    }
}

/**
 * Reidrata os campos que descrevem quem pediu, ao servir uma arvore que outro cliente compos.
 *
 * A chave de cache cobre plataforma, schema, faixa major.minor do app, capabilities e canal —
 * nao cobre build, patch da versao, versao de SO nem locale. Sem isso o envelope devolveria os
 * dados do dispositivo que compos primeiro, justamente nos campos que existem para tornar a
 * composicao auditavel. As sections nao dependem desses campos, e por isso compartilhar a
 * entrada continua correto; se o conteudo passar a ser localizado, o locale tera de entrar na
 * chave em vez de ser sobrescrito aqui.
 */
internal fun ComposedScreen.withRequester(clock: Clock, context: ClientContext): ComposedScreen = copy(
    generatedAt = clock.instant(),
    client = context,
    locale = context.locale,
)
