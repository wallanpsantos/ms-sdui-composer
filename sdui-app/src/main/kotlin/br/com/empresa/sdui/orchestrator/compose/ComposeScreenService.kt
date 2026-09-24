package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.cache.CapsHash
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.filter.Filter
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.BulkheadOutcome
import br.com.empresa.sdui.core.limit.RateLimitKey
import br.com.empresa.sdui.core.limit.TimeBudget
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextValidation
import br.com.empresa.sdui.core.model.ETagFactory
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.RevisionIds
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.negotiate.Negotiate
import br.com.empresa.sdui.core.select.Select
import br.com.empresa.sdui.orchestrator.hydration.HydrationContext
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.CanaryPolicy
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricTags
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.findFor
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom

/**
 * O pipeline de composicao: de headers do cliente ate a arvore de UI pronta, para qualquer surface
 * da allowlist.
 *
 * Orquestra Negotiate, Select, Filter, hidratacao e montagem do envelope, e e quem decide quando
 * degradar. A surface vem resolvida na requisicao e entra em toda leitura, chave e tag: pointer,
 * specs, cache de arvore, singleflight, last good e metricas nunca misturam surfaces.
 *
 * A selecao da revisao acontece em toda requisicao, antes da consulta ao cache, porque o targeting
 * discrimina por dimensoes que nao cabem na chave. Um acerto de cache poupa filtragem, hidratacao
 * e montagem — que e o grosso do trabalho —, mas nao a selecao. Para que ela nao custe uma
 * varredura dos publicados por requisicao, a revisao apontada pelo pointer e resolvida direto
 * (cache de spec, depois store); so quando ela nao atende o cliente os publicados sao listados.
 * O resultado e identico ao de [Select.select] sobre a lista inteira.
 *
 * Na falta do cache, o singleflight garante que apenas uma requisicao componha de verdade e as
 * outras aproveitem o mesmo resultado, em vez de todas baterem nas dependencias. Quem assume a
 * lideranca consulta o cache de novo antes de compor: outra requisicao pode ter acabado de grava-lo
 * entre a primeira consulta e a entrada no singleflight.
 *
 * Nenhuma falha de dependencia vira 5xx direto: tudo passa pela escada de fallback (ADR-007), que
 * tenta last good antes de assumir indisponibilidade.
 *
 * **Politica de resiliencia (ADR-014).** Toda requisicao abre um [TimeBudget] que limita as
 * **esperas** — permissao de bulkhead e espera pelo lider do singleflight —, e nunca o trabalho em
 * si: esperar por outro quando nao ha prazo nao ajuda ninguem, mas abandonar trabalho ja pago so
 * troca latencia por erro. As leituras de store passam por um [Bulkhead] proprio do plano de
 * leitura, e todo desfecho degradado — recusa do bulkhead, orcamento estourado, falha de store,
 * escrita de cache perdida, last good velho demais e o proprio 503 — emite metrica antes de
 * seguir. O servico nao faz retry de dependencia nenhuma: a escada de fallback ja e a politica de
 * degradacao, e repetir chamada multiplicaria a carga exatamente quando a dependencia esta fraca.
 */
class ComposeScreenService(
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val pointerStore: PointerStore,
    private val specCache: SpecCache,
    private val treeCache: HydratedScreenCache,
    private val lastGood: LastGoodScreenStore,
    private val singleflight: ComposeSingleflight,
    private val hydrator: HydrationCoordinator,
    private val matrix: CapabilityMatrix,
    private val canaryPolicy: CanaryPolicy,
    private val rateLimiter: TokenBucketRateLimiter,
    private val readBulkhead: Bulkhead,
    private val metrics: MetricsRecorder,
    private val clock: Clock,
    private val budgets: ComposeBudgets = ComposeBudgets(),
    private val randomFraction: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val fallbackCoordinator: FallbackCoordinator = DefaultFallbackCoordinator(
        lastGood = lastGood,
        matrix = matrix,
        metrics = metrics,
        clock = clock,
        budgets = budgets,
        randomFraction = randomFraction,
    ),
) : ComposeScreenUseCase {

    override fun compose(request: ComposeRequest): ComposeResult {
        val surface = request.surface
        val budget = TimeBudget(budgets.request, nanoTime)
        val context = when (val negotiated = Negotiate.negotiate(request.headers)) {
            is ContextValidation.Invalid -> return ComposeResult.InvalidHeaders(negotiated.violations)
            is ContextValidation.Valid -> negotiated.context
        }
        val tags = MetricTags.compose(surface, context)
        // A coorte sai do contexto validado: build de ate dez digitos e plataforma do enum. Com o
        // texto cru dos headers, variar caixa ou espaco abriria um bucket novo por variacao, com
        // chave do tamanho do header.
        if (!rateLimiter.tryConsume(RateLimitKey(context.build, context.platform))) {
            metrics.increment(MetricNames.COMPOSE_RATE_LIMITED, tags)
            return ComposeResult.RateLimited(fallbackCoordinator.retryAfter(budgets.rateLimitRetryAfterSeconds))
        }
        val channel = canaryPolicy.channelFor(context.platform, context.build, context.channelHint)
        val caps = matrix.effective(context)
        val capsHash = CapsHash.sha256(caps)

        fun degrade(reason: FallbackReason): ComposeResult =
            fallbackCoordinator.fallbackOrUnavailable(surface, context, channel, reason, tags)

        // A selecao vem antes do cache porque o targeting discrimina por versao completa do app e
        // por versao de SO. Uma chave montada a partir do contexto teria de carregar essas duas
        // dimensoes para ser correta, e carrega-las fragmentaria o cache por patch e por versao de
        // sistema. Resolvendo a revisao primeiro, a chave passa a identificar o que foi escolhido
        // em vez de tentar reproduzir a escolha.
        val selection = try {
            when (
                val outcome = readBulkhead.withPermit(budget.stage(budgets.bulkheadWait)) {
                    selectSpec(surface, context, channel, caps)
                }
            ) {
                is BulkheadOutcome.Rejected -> {
                    metrics.increment(MetricNames.COMPOSE_BULKHEAD_REJECTED, tags + (TAG_STAGE to STAGE_SELECT))
                    return degrade(FallbackReason.REDIS_UNAVAILABLE)
                }

                is BulkheadOutcome.Executed -> outcome.value
            }
        } catch (error: Exception) {
            fallbackCoordinator.reportStoreFailure(STAGE_SELECT, error)
            return degrade(FallbackReason.REDIS_UNAVAILABLE)
        }
        val selected = selection.spec
        if (selected == null || !RevisionIds.isValid(selected.specRevisionId)) {
            metrics.increment(MetricNames.SELECT_NO_CANDIDATE, tags)
            return degrade(FallbackReason.NO_COMPATIBLE_SPEC)
        }

        // Com a revisao em maos o ETag ja e conhecido: uma revalidacao termina aqui, sem tocar no
        // cache de arvore nem compor nada.
        val etag = ETagFactory.of(selected.specRevisionId, context.platform, context.schemaVersion, capsHash)
        if (!request.ifNoneMatch.isNullOrBlank() && request.ifNoneMatch == etag) {
            return ComposeResult.NotModified(etag)
        }
        // Orcamento estourado e sinal, nao veredito. Abortar aqui trocaria trabalho ja pago — a
        // selecao — por uma leitura de last good que, num pod recem-subido, nao existe: o primeiro
        // request depois de um deploy viraria 503 so porque a JVM ainda estava fria. Quem consome
        // o orcamento sao as esperas, e e nelas que ele e aplicado.
        if (budget.isExhausted()) {
            metrics.increment(MetricNames.COMPOSE_DEADLINE_EXCEEDED, tags + (TAG_STAGE to STAGE_SELECT))
        }

        val treeKey = RedisKeys.tree(
            surface = surface.id,
            platform = context.platform,
            schema = context.schemaVersion,
            specRevisionId = selected.specRevisionId,
            capsHash = capsHash,
            channel = channel,
        )
        check(!RedisKeys.containsUserId(treeKey)) { "tree key must not contain userId" }
        val channelTags = tags + (TAG_CHANNEL to channel.wire())

        val cached = readTree(treeKey)
        if (cached != null) {
            metrics.increment(MetricNames.COMPOSE_HIT, channelTags)
            return ComposeResult.Success(cached.withRequester(clock, context), fromCache = true)
        }

        metrics.increment(MetricNames.COMPOSE_MISS, channelTags)
        val outcome = try {
            singleflight.runExclusive(
                RedisKeys.singleflight(treeKey),
                budget.stage(budgets.singleflightWait),
            ) {
                // Segunda consulta, ja como lider: quem compos entre a primeira consulta e a
                // entrada no singleflight deixou a arvore no cache, e compor de novo seria trabalho
                // repetido sem ganho.
                val refreshed = readTree(treeKey)
                if (refreshed != null) {
                    metrics.increment(MetricNames.COMPOSE_SINGLEFLIGHT_RECHECK_HIT, channelTags)
                    ComposeResult.Success(refreshed.withRequester(clock, context), fromCache = true)
                } else {
                    composeFresh(
                        ComposeInput(
                            surface,
                            context,
                            channel,
                            caps,
                            selected,
                            selection.pointerVersion,
                            etag,
                            treeKey,
                            tags
                        ),
                        budget,
                    )
                }
            }
        } catch (error: Exception) {
            fallbackCoordinator.reportStoreFailure(STAGE_SINGLEFLIGHT, error)
            return degrade(FallbackReason.DEPENDENCY_TIMEOUT)
        }
        return when (outcome) {
            is SingleflightOutcome.Leader -> outcome.value
            is SingleflightOutcome.Waiter -> {
                metrics.increment(MetricNames.COMPOSE_SINGLEFLIGHT_WAIT, tags)
                // O resultado foi montado com o contexto do lider. As sections valem para quem
                // chegou a mesma chave; os campos que descrevem o requisitante, nao.
                outcome.value.forRequester(context)
            }

            is SingleflightOutcome.WaitTimeout -> {
                metrics.increment(MetricNames.COMPOSE_SINGLEFLIGHT_WAIT, tags)
                metrics.increment(MetricNames.COMPOSE_DEADLINE_EXCEEDED, tags + (TAG_STAGE to STAGE_SINGLEFLIGHT))
                degrade(FallbackReason.DEPENDENCY_TIMEOUT)
            }
        }
    }

    /** A revisao escolhida e a versao do pointer lida para escolhe-la (0 sem pointer). */
    private data class Selection(val spec: Spec?, val pointerVersion: Long)

    /** O que o lider do singleflight precisa para compor, ja resolvido pela requisicao. */
    private data class ComposeInput(
        val surface: SurfaceDefinition,
        val context: ClientContext,
        val channel: Channel,
        val caps: Set<Capability>,
        val selected: Spec,
        val pointerVersion: Long,
        val etag: String,
        val treeKey: String,
        val tags: Map<String, String>,
    )

    /**
     * Le o pointer e devolve a revisao que atende este cliente, ou null.
     *
     * Tenta primeiro a revisao apontada, lida do cache de spec ou do store por id; so lista os
     * publicados quando ela nao serve. Recebe [caps] ja calculado: o mesmo conjunto precisa valer
     * para a selecao e para o capsHash da chave de cache, senao a chave descreveria um conjunto de
     * capabilities diferente do que decidiu a revisao.
     */
    private fun selectSpec(
        surface: SurfaceDefinition,
        context: ClientContext,
        channel: Channel,
        caps: Set<Capability>,
    ): Selection {
        val pointer = pointerStore.find(surface.id, context.platform, channel)
        val pointerVersion = pointer?.version ?: 0L
        val pointedId = Select.pointedRevision(pointer, context, channel, surface.id)
        if (pointedId != null) {
            val pointedSpec = readSpecCache(pointedId, context.platform)
                ?: specStore.findByRevisionId(pointedId)?.also { rememberPublished(it) }
            Select.pointedIfServes(pointer, pointedSpec, context, caps, channel, surface.id)
                ?.let { return Selection(it, pointerVersion) }
        }
        val candidates = specStore.listPublished(surface.id, context.platform)
        return Selection(Select.select(pointer, candidates, context, caps, channel, surface.id), pointerVersion)
    }

    /** Cache de spec e atalho: falha nele vira leitura no store, nunca degradacao da resposta. */
    private fun readSpecCache(specRevisionId: String, platform: ClientPlatform): Spec? = try {
        specCache.get(specRevisionId, platform)
    } catch (error: Exception) {
        fallbackCoordinator.reportStoreFailure(STAGE_SPEC_CACHE, error)
        null
    }

    private fun rememberPublished(spec: Spec) {
        if (spec.status != SpecStatus.PUBLISHED) return
        try {
            specCache.put(spec)
        } catch (error: Exception) {
            fallbackCoordinator.reportCacheWriteFailure(CACHE_SPEC, error)
        }
    }

    private fun readTree(treeKey: String): ComposedScreen? = try {
        treeCache.get(treeKey)
    } catch (error: Exception) {
        fallbackCoordinator.reportStoreFailure(STAGE_TREE_CACHE, error)
        null
    }

    private fun composeFresh(input: ComposeInput, budget: TimeBudget): ComposeResult = try {
        composeFromStores(input, budget)
    } catch (error: Exception) {
        // Skeleton vem do mesmo backend de dados da selecao: uma falha nele e indisponibilidade
        // de dependencia. Tratar em um ponto so evita que parte das leituras caia aqui e o
        // restante suba ate o catch do singleflight, onde seria reportada como DEPENDENCY_TIMEOUT.
        fallbackCoordinator.reportStoreFailure(STAGE_COMPOSE, error)
        input.degrade(FallbackReason.REDIS_UNAVAILABLE)
    }

    private fun ComposeInput.degrade(reason: FallbackReason): ComposeResult =
        fallbackCoordinator.fallbackOrUnavailable(surface, context, channel, reason, tags)

    private fun composeFromStores(input: ComposeInput, budget: TimeBudget): ComposeResult {
        val (surface, context, channel, caps, spec) = input
        // So as leituras de store entram no bulkhead. A hidratacao tem o teto de fan-out dela, e
        // segurar aqui uma permissao de leitura durante a hidratacao misturaria os dois limites:
        // uma fonte de dados lenta passaria a estrangular quem so precisa ler spec e skeleton.
        val skeleton = when (
            val outcome = readBulkhead.withPermit(budget.stage(budgets.bulkheadWait)) { skeletonStore.findFor(spec) }
        ) {
            is BulkheadOutcome.Rejected -> {
                metrics.increment(MetricNames.COMPOSE_BULKHEAD_REJECTED, input.tags + (TAG_STAGE to STAGE_COMPOSE))
                return input.degrade(FallbackReason.REDIS_UNAVAILABLE)
            }

            is BulkheadOutcome.Executed -> outcome.value
        } ?: return input.degrade(FallbackReason.NO_COMPATIBLE_SPEC)

        val filtered = Filter.filter(spec.sections, skeleton, caps)
        for (omitted in filtered.omitted) {
            metrics.increment(
                MetricNames.SECTION_OMITTED,
                input.tags + mapOf(
                    "type" to omitted.type,
                    "typeVersion" to omitted.typeVersion.toString(),
                    TAG_CHANNEL to channel.wire(),
                    "reason" to omitted.reason.wire,
                ),
            )
        }
        val hydrated = hydrator.hydrate(
            context = HydrationContext(
                surface = surface.id,
                platform = context.platform,
                specRevisionId = spec.specRevisionId,
                locale = context.locale,
                channel = channel,
            ),
            skeleton = skeleton,
            sections = filtered.sections,
            alreadyOmitted = filtered.omitted,
        )
        val presentSlots = hydrated.sections.map { it.slot }.toSet()
        if (hydrated.requiredSlotFailed || !skeleton.requiredSlotIds.all { it in presentSlots }) {
            return input.degrade(FallbackReason.REQUIRED_SLOT_EMPTY)
        }
        val screen = ComposedScreen(
            surface = surface.id,
            platform = context.platform,
            schemaVersion = context.schemaVersion,
            specRevisionId = spec.specRevisionId,
            skeletonId = skeleton.skeletonId,
            // O checksum do spec publicado ja e validado no formato sha256: pela governanca
            // (SpecValidator); nao ha literal de reserva, um valor inventado aqui viajaria no
            // envelope como se fosse integridade real.
            skeletonHash = spec.checksum,
            etag = input.etag,
            generatedAt = clock.instant(),
            locale = context.locale,
            channel = channel,
            fallback = false,
            fallbackReason = FallbackReason.NONE,
            omitted = hydrated.omitted,
            client = context,
            targeting = spec.targeting,
            experience = spec.experience,
            skeleton = skeleton,
            sections = hydrated.sections,
            pointerVersion = input.pointerVersion,
        )
        // Escrita de cache e best-effort, mas silencio nao e: um Redis que le e recusa gravar
        // produziria miss de cem por cento indistinguivel de operacao normal nos paineis.
        try {
            treeCache.put(input.treeKey, screen, budgets.treeTtl)
        } catch (error: Exception) {
            fallbackCoordinator.reportCacheWriteFailure(CACHE_TREE, error)
        }
        try {
            lastGood.put(screen)
        } catch (error: Exception) {
            fallbackCoordinator.reportCacheWriteFailure(CACHE_LAST_GOOD, error)
        }
        return ComposeResult.Success(screen, fromCache = false)
    }

    /** Reidrata os campos do requisitante num resultado produzido para outro cliente. */
    private fun ComposeResult.forRequester(context: ClientContext): ComposeResult = when (this) {
        is ComposeResult.Success -> copy(screen = screen.withRequester(clock, context))
        else -> this
    }

    private companion object {
        const val TAG_STAGE: String = "stage"
        const val TAG_CHANNEL: String = "channel"

        const val STAGE_SELECT: String = "select"
        const val STAGE_SPEC_CACHE: String = "spec_cache"
        const val STAGE_TREE_CACHE: String = "tree_cache"
        const val STAGE_SINGLEFLIGHT: String = "singleflight"
        const val STAGE_COMPOSE: String = "compose"

        const val CACHE_TREE: String = "tree"
        const val CACHE_SPEC: String = "spec"
        const val CACHE_LAST_GOOD: String = "last_good"
    }
}

/** Politica que ignora o canal pedido e serve stable a todos. Padrao quando nao ha canary ativo. */
object DefaultCanaryPolicy : CanaryPolicy {
    override fun channelFor(
        platform: ClientPlatform,
        build: String,
        requested: Channel,
    ): Channel = Channel.STABLE
}

/**
 * Libera canary apenas para builds em lista explicita, por plataforma.
 *
 * O cliente pede o canal, mas quem decide e o servidor: sem isso qualquer app se colocaria no
 * canary so mandando um header. O canal interno segue livre, por ser destinado a testes.
 */
class AllowlistCanaryPolicy(
    private val allowed: Map<ClientPlatform, Set<String>>,
) : CanaryPolicy {
    override fun channelFor(
        platform: ClientPlatform,
        build: String,
        requested: Channel,
    ): Channel {
        if (requested == Channel.INTERNAL) return Channel.INTERNAL
        if (requested == Channel.CANARY && allowed[platform]?.contains(build) == true) {
            return Channel.CANARY
        }
        return Channel.STABLE
    }
}
