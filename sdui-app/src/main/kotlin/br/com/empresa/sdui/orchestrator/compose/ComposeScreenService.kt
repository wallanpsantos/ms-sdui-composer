package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.cache.CapsHash
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.filter.Filter
import br.com.empresa.sdui.core.limit.RateLimitKey
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextValidation
import br.com.empresa.sdui.core.model.ETagFactory
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.negotiate.Negotiate
import br.com.empresa.sdui.core.select.Select
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.CanaryPolicy
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import java.time.Clock
import java.time.Duration

/**
 * O pipeline de composicao: de headers do cliente ate a arvore de UI pronta.
 *
 * Orquestra Negotiate, Select, Filter, hidratacao e montagem do envelope, e e quem decide quando
 * degradar.
 *
 * A selecao da revisao acontece em toda requisicao, antes da consulta ao cache, porque o targeting
 * discrimina por dimensoes que nao cabem na chave. Um acerto de cache poupa filtragem, hidratacao
 * e montagem — que e o grosso do trabalho — mas nao poupa a leitura do pointer e dos specs
 * publicados. Quando a persistencia deixar de ser em memoria, esse par de leituras por requisicao
 * passa a ser o proximo alvo de otimizacao, resolvendo a revisao direto pelo pointer em vez de
 * listar os publicados.
 *
 * Na falta do cache, o singleflight garante que apenas uma requisicao componha de verdade e as
 * outras aproveitem o mesmo resultado, em vez de todas baterem nas dependencias.
 *
 * Nenhuma falha de dependencia vira 5xx direto: tudo passa pela escada de fallback (ADR-007), que
 * tenta last good antes de assumir indisponibilidade.
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
    private val metrics: MetricsRecorder,
    private val clock: Clock,
    private val treeTtl: Duration = Duration.ofSeconds(60),
    private val singleflightTimeout: Duration = Duration.ofSeconds(2),
    private val retryAfterSeconds: Long = 5,
) : ComposeScreenUseCase {

    override fun compose(request: ComposeRequest): ComposeResult {
        val context = when (val negotiated = Negotiate.negotiate(request.headers)) {
            is ContextValidation.Invalid -> return ComposeResult.InvalidHeaders(negotiated.violations)
            is ContextValidation.Valid -> negotiated.context
        }
        val tags = mapOf(
            "schemaVersion" to context.schemaVersion,
            "appVersion" to context.appVersion.toString(),
            "surface" to MvpCatalog.SURFACE_HOME,
            "platform" to context.platform.wire(),
        )
        if (!rateLimiter.tryConsume(RateLimitKey(request.identity, context.platform))) {
            metrics.increment("compose.rate_limited", tags)
            return ComposeResult.RateLimited
        }
        val channel = canaryPolicy.channelFor(context.platform, context.build, context.channelHint)
        val caps = matrix.effective(context)
        val capsHash = CapsHash.sha256(caps)

        // A selecao vem antes do cache porque o targeting discrimina por versao completa do app e
        // por versao de SO. Uma chave montada a partir do contexto teria de carregar essas duas
        // dimensoes para ser correta, e carrega-las fragmentaria o cache por patch e por versao de
        // sistema. Resolvendo a revisao primeiro, a chave passa a identificar o que foi escolhido
        // em vez de tentar reproduzir a escolha.
        val selected = try {
            selectSpec(context, channel, caps)
        } catch (_: Exception) {
            return fallbackOrUnavailable(context, channel, FallbackReason.REDIS_UNAVAILABLE, tags)
        }
        if (selected == null) {
            metrics.increment(
                "select.no_candidate",
                mapOf(
                    "platform" to context.platform.wire(),
                    "appVersion" to context.appVersion.toString(),
                    "schemaVersion" to context.schemaVersion,
                ),
            )
            return fallbackOrUnavailable(context, channel, FallbackReason.NO_COMPATIBLE_SPEC, tags)
        }

        // Com a revisao em maos o ETag ja e conhecido: uma revalidacao termina aqui, sem tocar no
        // cache de arvore nem compor nada.
        val etag = ETagFactory.of(selected.specRevisionId, context.platform, context.schemaVersion, capsHash)
        if (!request.ifNoneMatch.isNullOrBlank() && request.ifNoneMatch == etag) {
            return ComposeResult.NotModified(etag)
        }

        val treeKey = RedisKeys.tree(
            surface = MvpCatalog.SURFACE_HOME,
            platform = context.platform,
            schema = context.schemaVersion,
            specRevisionId = selected.specRevisionId,
            capsHash = capsHash,
            channel = channel,
        )
        check(!RedisKeys.containsUserId(treeKey)) { "tree key must not contain userId" }

        val cached = try {
            treeCache.get(treeKey)
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            metrics.increment("compose.hit", tags + mapOf("channel" to channel.wire()))
            return ComposeResult.Success(cached.withRequester(context), fromCache = true)
        }

        metrics.increment("compose.miss", tags + mapOf("channel" to channel.wire()))
        val outcome = try {
            singleflight.runExclusive(RedisKeys.singleflight(treeKey), singleflightTimeout) {
                composeFresh(context, channel, caps, selected, etag, treeKey, tags)
            }
        } catch (_: Exception) {
            return fallbackOrUnavailable(context, channel, FallbackReason.DEPENDENCY_TIMEOUT, tags)
        }
        return when (outcome) {
            is SingleflightOutcome.Leader -> outcome.value
            is SingleflightOutcome.Waiter -> {
                metrics.increment("compose.singleflight.wait", tags)
                outcome.value
            }

            is SingleflightOutcome.WaitTimeout -> {
                metrics.increment("compose.singleflight.wait", tags)
                fallbackOrUnavailable(context, channel, FallbackReason.DEPENDENCY_TIMEOUT, tags)
            }
        }
    }

    /**
     * Le pointer e specs publicados e devolve a revisao que atende este cliente, ou null.
     *
     * Recebe [caps] ja calculado em vez de recalcular: o mesmo conjunto precisa valer para a
     * selecao e para o capsHash da chave de cache, senao a chave descreveria um conjunto de
     * capabilities diferente do que decidiu a revisao.
     */
    private fun selectSpec(context: ClientContext, channel: Channel, caps: Set<Capability>): Spec? {
        val pointer = pointerStore.find(MvpCatalog.SURFACE_HOME, context.platform, channel)
        val candidates = specStore.listPublished(MvpCatalog.SURFACE_HOME, context.platform)
        return Select.select(pointer, candidates, context, caps, channel)
    }

    private fun composeFresh(
        context: ClientContext,
        channel: Channel,
        caps: Set<Capability>,
        selected: Spec,
        etag: String,
        treeKey: String,
        tags: Map<String, String>,
    ): ComposeResult = try {
        composeFromStores(context, channel, caps, selected, etag, treeKey, tags)
    } catch (_: Exception) {
        // Skeleton e cache de spec vem do mesmo backend de dados da selecao: uma falha em qualquer
        // um deles e indisponibilidade de dependencia. Tratar em um ponto so evita que parte das
        // leituras caia aqui e o restante suba ate o catch do singleflight, onde seria reportada
        // como DEPENDENCY_TIMEOUT.
        fallbackOrUnavailable(context, channel, FallbackReason.REDIS_UNAVAILABLE, tags)
    }

    private fun composeFromStores(
        context: ClientContext,
        channel: Channel,
        caps: Set<Capability>,
        selected: Spec,
        etag: String,
        treeKey: String,
        tags: Map<String, String>,
    ): ComposeResult {
        val spec = specCache.get(selected.specRevisionId, context.platform) ?: selected
        specCache.put(spec)
        val skeleton = skeletonStore.find(spec.skeletonId, spec.skeletonRevision)
            ?: skeletonStore.current(spec.skeletonId)
            ?: return fallbackOrUnavailable(context, channel, FallbackReason.NO_COMPATIBLE_SPEC, tags)
        val filtered = Filter.filter(spec.sections, skeleton, caps)
        for (omitted in filtered.omitted) {
            metrics.increment(
                "section.omitted",
                mapOf(
                    "type" to omitted.type,
                    "typeVersion" to omitted.typeVersion.toString(),
                    "appVersion" to context.appVersion.toString(),
                    "platform" to context.platform.wire(),
                    "channel" to channel.wire(),
                    "reason" to omitted.reason.wire,
                ),
            )
        }
        val hydrated = hydrator.hydrate(
            context = HydrationContext(
                surface = MvpCatalog.SURFACE_HOME,
                platform = context.platform,
                specRevisionId = spec.specRevisionId,
                locale = context.locale,
                channel = channel,
            ),
            skeleton = skeleton,
            sections = filtered.sections,
            alreadyOmitted = filtered.omitted,
        )
        if (hydrated.requiredSlotFailed) {
            return fallbackOrUnavailable(context, channel, FallbackReason.REQUIRED_SLOT_EMPTY, tags)
        }
        val screen = ComposedScreen(
            surface = MvpCatalog.SURFACE_HOME,
            platform = context.platform,
            schemaVersion = context.schemaVersion,
            specRevisionId = spec.specRevisionId,
            skeletonId = skeleton.skeletonId,
            // O checksum do spec publicado ja e validado no formato sha256: pela governanca
            // (SpecValidator); nao ha literal de reserva, um valor inventado aqui viajaria no
            // envelope como se fosse integridade real.
            skeletonHash = spec.checksum,
            etag = etag,
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
        )
        try {
            treeCache.put(treeKey, screen, treeTtl)
            lastGood.put(screen)
        } catch (_: Exception) {
            // compose succeeded; cache write is best-effort
        }
        return ComposeResult.Success(screen, fromCache = false)
    }

    private fun fallbackOrUnavailable(
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult {
        val stored = try {
            lastGood.get(MvpCatalog.SURFACE_HOME, context.platform, channel)
        } catch (_: Exception) {
            null
        }
        if (stored != null) {
            val effectiveCaps = matrix.effective(context)
            val filtered = Filter.filter(stored.sections, stored.skeleton, effectiveCaps)
            val requiredSlots = stored.skeleton.slots.filter { it.required }.map { it.id }.toSet()
            val requiredPresent = filtered.sections.map { it.slot }.toSet()
            if (requiredSlots.all { it in requiredPresent }) {
                metrics.increment(
                    "compose.fallback",
                    tags + mapOf("channel" to channel.wire(), "fallbackReason" to reason.wire)
                )
                return ComposeResult.Success(
                    stored.withRequester(context).copy(
                        sections = filtered.sections,
                        omitted = stored.omitted + filtered.omitted,
                        fallback = true,
                        fallbackReason = reason,
                    ),
                    fromCache = true,
                )
            }
        }
        return ComposeResult.Unavailable(retryAfterSeconds, reason)
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
    private fun ComposedScreen.withRequester(context: ClientContext): ComposedScreen = copy(
        generatedAt = clock.instant(),
        client = context,
        locale = context.locale,
    )
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
