package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.orchestrator.compose.HydrationContext
import br.com.empresa.sdui.orchestrator.compose.HydrationResult
import br.com.empresa.sdui.orchestrator.compose.SectionHydrator
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class HydratedTree(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
    val requiredSlotFailed: Boolean,
)

class HydrationCoordinator(
    private val hydrators: List<SectionHydrator>,
    private val fanOut: Semaphore,
    private val timeout: Duration,
    private val metrics: MetricsRecorder,
    private val executor: Executor = Executor { runnable -> Thread.ofVirtual().name("sdui-hydrate").start(runnable) },
) {
    fun hydrate(
        context: HydrationContext,
        skeleton: Skeleton,
        sections: List<Section>,
        alreadyOmitted: List<OmittedSection>,
    ): HydratedTree {
        val omitted = alreadyOmitted.toMutableList()
        val kept = mutableListOf<Section>()
        var requiredFailed = false
        val requiredSlots = skeleton.slots.filter { it.required }.map { it.id }.toSet()

        for (section in sections) {
            val hydrator = hydrators.firstOrNull { it.supports(section.type, section.typeVersion) }
                ?: PassThroughHydrator()
            val started = System.nanoTime()
            val future = CompletableFuture.supplyAsync(
                {
                    fanOut.acquire()
                    try {
                        hydrator.hydrate(context, section)
                    } finally {
                        fanOut.release()
                    }
                },
                executor,
            ).handle { result, error ->
                if (error != null) HydrationResult.Failed(OmittedReason.HYDRATION_FAILED) else result
            }
            val result = try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                HydrationResult.Failed(OmittedReason.HYDRATION_TIMEOUT)
            } catch (_: Exception) {
                future.cancel(true)
                HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            metrics.recordTime(
                "section.${section.type}.ms",
                elapsedMs,
                mapOf(
                    "type" to section.type,
                    "typeVersion" to section.typeVersion.toString(),
                    "platform" to context.platform.wire(),
                    "channel" to context.channel.wire(),
                ),
            )
            when (result) {
                is HydrationResult.Ok -> kept += section.copy(props = result.props)
                is HydrationResult.Failed -> {
                    omitted += OmittedSection(
                        id = section.id,
                        slot = section.slot,
                        type = section.type,
                        typeVersion = section.typeVersion,
                        reason = result.reason,
                    )
                    if (section.slot in requiredSlots) {
                        requiredFailed = true
                    }
                }
                null -> {
                    omitted += OmittedSection(
                        id = section.id,
                        slot = section.slot,
                        type = section.type,
                        typeVersion = section.typeVersion,
                        reason = OmittedReason.HYDRATION_FAILED,
                    )
                    if (section.slot in requiredSlots) requiredFailed = true
                }
            }
        }
        return HydratedTree(kept, omitted, requiredFailed)
    }
}
