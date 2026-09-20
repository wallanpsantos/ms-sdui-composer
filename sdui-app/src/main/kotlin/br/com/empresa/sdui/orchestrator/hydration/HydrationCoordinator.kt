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

/**
 * Saida da hidratacao: o que foi preenchido, o que caiu e se um slot portante ficou vazio.
 *
 * [requiredSlotFailed] e o sinal que leva a composicao inteira para o fallback, em vez de entregar
 * uma home sem header ou sem contas.
 */
data class HydratedTree(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
    val requiredSlotFailed: Boolean,
)

/**
 * Hidrata as sections em paralelo, com prazo e teto de concorrencia.
 *
 * Cada section vai para uma virtual thread e disputa permissao no semaforo de fan-out, que limita
 * quantas chamadas de dados acontecem ao mesmo tempo. O prazo e por section: quem estoura vira
 * omissao com HYDRATION_TIMEOUT e as demais seguem, o que mantem o tempo total da requisicao
 * previsivel mesmo com uma fonte lenta.
 */
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
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    val remainingMs = (timeout.toMillis() - elapsedMs).coerceAtLeast(0)
                    if (remainingMs <= 0 || !fanOut.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)) {
                        throw TimeoutException("Fan-out semaphore acquire timeout")
                    }
                    try {
                        if (System.nanoTime() - started >= timeout.toNanos()) {
                            throw TimeoutException("Timeout before hydrator invocation")
                        }
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
                        // Sem cancel(): uma task de supplyAsync nao e interrompida por
                        // CompletableFuture.cancel, entao a chamada daria falsa impressao de que o
                        // trabalho parou. Ele segue ate o fim e so entao devolve o permit do
                        // semaforo — o que limita o fan-out e nao a duracao da requisicao, que o
                        // orTimeout ja encerrou.
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
