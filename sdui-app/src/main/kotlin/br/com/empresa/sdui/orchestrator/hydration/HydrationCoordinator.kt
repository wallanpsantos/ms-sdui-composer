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
import java.util.concurrent.CompletionException
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
        val requiredSlots = skeleton.slots.filter { it.required }.map { it.id }.toSet()
        if (sections.isEmpty()) {
            return HydratedTree(emptyList(), omitted, requiredSlotFailed = false)
        }

        val jobs = sections.map { section ->
            val hydrator = hydrators.firstOrNull { it.supports(section.type, section.typeVersion) }
                ?: PassThroughHydrator()
            val started = System.nanoTime()
            val original = CompletableFuture.supplyAsync(
                {
                    fanOut.acquire()
                    try {
                        hydrator.hydrate(context, section)
                    } finally {
                        fanOut.release()
                    }
                },
                executor,
            )
            original.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle { result, error ->
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    metrics.recordTime(
                        SECTION_HYDRATE_TIMER,
                        elapsedMs,
                        mapOf(
                            "type" to section.type,
                            "typeVersion" to section.typeVersion.toString(),
                            "platform" to context.platform.wire(),
                            "channel" to context.channel.wire(),
                        ),
                    )
                    val hydration = if (error != null) {
                        original.cancel(true)
                        val root = rootCause(error)
                        if (root is TimeoutException) {
                            HydrationResult.Failed(OmittedReason.HYDRATION_TIMEOUT)
                        } else {
                            HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
                        }
                    } else {
                        result ?: HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
                    }
                    section to hydration
                }
        }

        val kept = mutableListOf<Section>()
        var requiredFailed = false
        for (job in jobs) {
            val (section, result) = job.join()
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
            }
        }
        return HydratedTree(kept, omitted, requiredFailed)
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (true) {
            if (current is CompletionException) {
                val cause = current.cause
                if (cause == null || !seen.add(current)) return current
                current = cause
                continue
            }
            val cause = current.cause
            if (cause == null || cause === current || !seen.add(current)) return current
            current = cause
        }
    }

    private companion object {
        /**
         * Nome fixo: o tipo do componente ja viaja na tag `type`. Interpolar o tipo no nome criaria
         * um meter por componente e multiplicaria as series no registry.
         */
        const val SECTION_HYDRATE_TIMER: String = "section.hydrate.ms"
    }
}
