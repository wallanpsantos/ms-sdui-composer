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
        val negotiated = Negotiate.negotiate(request.headers)
        if (negotiated is ContextValidation.Invalid) {
            return ComposeResult.InvalidHeaders(negotiated.violations)
        }
        val context = (negotiated as ContextValidation.Valid).context
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
        val treeKey = RedisKeys.tree(
            surface = MvpCatalog.SURFACE_HOME,
            platform = context.platform,
            schema = context.schemaVersion,
            appMajorMinor = context.appVersion.majorMinor,
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
            if (!request.ifNoneMatch.isNullOrBlank() && request.ifNoneMatch == cached.etag) {
                return ComposeResult.NotModified(cached.etag)
            }
            return ComposeResult.Success(cached.copy(generatedAt = clock.instant()), fromCache = true)
        }

        metrics.increment("compose.miss", tags + mapOf("channel" to channel.wire()))
        val outcome = try {
            singleflight.runExclusive(RedisKeys.singleflight(treeKey), singleflightTimeout) {
                composeFresh(request, context, channel, caps, treeKey, tags)
            }
        } catch (_: Exception) {
            return fallbackOrUnavailable(context.platform, channel, FallbackReason.DEPENDENCY_TIMEOUT, tags)
        }
        return when (outcome) {
            is SingleflightOutcome.Leader -> outcome.value
            is SingleflightOutcome.Waiter -> {
                metrics.increment("compose.singleflight.wait", tags)
                outcome.value
            }

            is SingleflightOutcome.WaitTimeout -> {
                metrics.increment("compose.singleflight.wait", tags)
                fallbackOrUnavailable(context.platform, channel, FallbackReason.DEPENDENCY_TIMEOUT, tags)
            }
        }
    }

    private fun composeFresh(
        request: ComposeRequest,
        context: ClientContext,
        channel: Channel,
        caps: Set<Capability>,
        treeKey: String,
        tags: Map<String, String>,
    ): ComposeResult = try {
        composeFromStores(request, context, channel, caps, treeKey, tags)
    } catch (_: Exception) {
        // Pointer, spec, skeleton e cache de spec vem do mesmo backend de dados: uma falha em
        // qualquer um deles e indisponibilidade de dependencia. Tratar em um ponto so evita que
        // parte das leituras caia aqui e o restante suba ate o catch do singleflight, onde seria
        // reportada como DEPENDENCY_TIMEOUT.
        fallbackOrUnavailable(context.platform, channel, FallbackReason.REDIS_UNAVAILABLE, tags)
    }

    private fun composeFromStores(
        request: ComposeRequest,
        context: ClientContext,
        channel: Channel,
        caps: Set<Capability>,
        treeKey: String,
        tags: Map<String, String>,
    ): ComposeResult {
        val pointer = pointerStore.find(MvpCatalog.SURFACE_HOME, context.platform, channel)
        val candidates = specStore.listPublished(MvpCatalog.SURFACE_HOME, context.platform)
        val selected = Select.select(pointer, candidates, context, caps, channel)
        if (selected == null) {
            metrics.increment(
                "select.no_candidate",
                mapOf(
                    "platform" to context.platform.wire(),
                    "appVersion" to context.appVersion.toString(),
                    "schemaVersion" to context.schemaVersion,
                ),
            )
            return fallbackOrUnavailable(context.platform, channel, FallbackReason.NO_COMPATIBLE_SPEC, tags)
        }
        val spec = specCache.get(selected.specRevisionId, context.platform) ?: selected
        specCache.put(spec)
        val skeleton = skeletonStore.find(spec.skeletonId, spec.skeletonRevision)
            ?: skeletonStore.current(spec.skeletonId)
            ?: return fallbackOrUnavailable(context.platform, channel, FallbackReason.NO_COMPATIBLE_SPEC, tags)
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
            return fallbackOrUnavailable(context.platform, channel, FallbackReason.REQUIRED_SLOT_EMPTY, tags)
        }
        val etag = ETagFactory.of(spec.specRevisionId, context.platform, context.schemaVersion)
        if (!request.ifNoneMatch.isNullOrBlank() && request.ifNoneMatch == etag) {
            return ComposeResult.NotModified(etag)
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
        platform: ClientPlatform,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult {
        val stored = try {
            lastGood.get(MvpCatalog.SURFACE_HOME, platform, channel)
        } catch (_: Exception) {
            null
        }
        if (stored != null) {
            metrics.increment(
                "compose.fallback",
                tags + mapOf("channel" to channel.wire(), "fallbackReason" to reason.wire)
            )
            return ComposeResult.Success(
                stored.copy(
                    generatedAt = clock.instant(),
                    fallback = true,
                    fallbackReason = reason,
                ),
                fromCache = true,
            )
        }
        return ComposeResult.Unavailable(retryAfterSeconds, reason)
    }
}

object DefaultCanaryPolicy : CanaryPolicy {
    override fun channelFor(
        platform: ClientPlatform,
        build: String,
        requested: Channel,
    ): Channel = Channel.STABLE
}

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
