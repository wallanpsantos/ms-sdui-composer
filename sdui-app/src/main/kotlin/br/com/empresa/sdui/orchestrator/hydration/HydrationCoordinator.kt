package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.orchestrator.compose.HydrationContext
import br.com.empresa.sdui.orchestrator.compose.HydrationResult
import br.com.empresa.sdui.orchestrator.compose.SectionHydrator
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Saida da hidratacao: o que foi preenchido, o que caiu e se um slot portante ficou vazio.
 *
 * [requiredSlotFailed] e o sinal que leva a composicao inteira para o fallback, em vez de entregar
 * uma tela sem o bloco que a sustenta.
 */
data class HydratedTree(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
    val requiredSlotFailed: Boolean,
)

/**
 * Hidrata as sections com prazo e teto de concorrencia para quem espera I/O.
 *
 * Section cujo hidratador declara [SectionHydrator.performsIo] vai para uma virtual thread e
 * disputa permissao no semaforo de fan-out, que limita quantas chamadas de dados acontecem ao
 * mesmo tempo. O prazo e por section: quem estoura vira omissao com HYDRATION_TIMEOUT e as demais
 * seguem, o que mantem o tempo total da requisicao previsivel mesmo com uma fonte lenta.
 *
 * Hidratador sem I/O — o pass-through, que so repassa as props do spec — roda na thread da
 * requisicao. Virtual thread e ferramenta para concorrencia com espera, nao para acelerar CPU:
 * sem espera, a tarefa, o future, o timeout e a copia de MDC eram custo puro (medicao de
 * 2026-09-23 em docs/performance/medicoes-2026-09-23.md). A semantica e a telemetria sao as
 * mesmas nos dois caminhos: o mesmo timer por section e a mesma omissao em caso de falha.
 */
class HydrationCoordinator(
    private val hydrators: List<SectionHydrator>,
    private val fanOut: Semaphore,
    private val timeout: Duration,
    private val metrics: MetricsRecorder,
    private val executor: Executor = virtualThreadExecutor(),
) {
    private val passThrough = PassThroughHydrator()

    fun hydrate(
        context: HydrationContext,
        skeleton: Skeleton,
        sections: List<Section>,
        alreadyOmitted: List<OmittedSection>,
    ): HydratedTree {
        val omitted = alreadyOmitted.toMutableList()
        val requiredSlots = skeleton.requiredSlotIds
        if (sections.isEmpty()) {
            return HydratedTree(emptyList(), omitted, requiredSlotFailed = false)
        }

        // Dispara primeiro as que esperam I/O, para que corram em paralelo enquanto as locais
        // sao resolvidas aqui; a ordem das sections na resposta continua a do Filter.
        val assigned = sections.map { section -> section to hydratorFor(section) }
        val jobs = assigned.map { (section, hydrator) ->
            if (hydrator.performsIo) launch(context, section, hydrator) else null
        }

        val kept = ArrayList<Section>(sections.size)
        var requiredFailed = false
        for ((index, pair) in assigned.withIndex()) {
            val (section, hydrator) = pair
            val result = jobs[index]?.join() ?: hydrateInline(context, section, hydrator)
            when (result) {
                is HydrationResult.Ok -> kept += if (result.props === section.props) section else section.copy(props = result.props)
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

    private fun hydratorFor(section: Section): SectionHydrator =
        hydrators.firstOrNull { it.supports(section.type, section.typeVersion) } ?: passThrough

    /** Hidratacao sem espera, na thread da requisicao. Falha vira omissao, como no caminho assincrono. */
    private fun hydrateInline(
        context: HydrationContext,
        section: Section,
        hydrator: SectionHydrator,
    ): HydrationResult {
        val started = System.nanoTime()
        val result = try {
            hydrator.hydrate(context, section)
        } catch (_: Exception) {
            HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
        }
        recordHydration(context, section, started)
        return result
    }

    /** Dispara a hidratacao de uma section numa virtual thread, sob o semaforo de fan-out e o prazo. */
    private fun launch(
        context: HydrationContext,
        section: Section,
        hydrator: SectionHydrator,
    ): CompletableFuture<HydrationResult> {
        val started = System.nanoTime()
        val taskThread = AtomicReference<Thread?>()
        val original = CompletableFuture.supplyAsync(
            {
                taskThread.set(Thread.currentThread())
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val remainingMs = (timeout.toMillis() - elapsedMs).coerceAtLeast(0)
                if (remainingMs <= 0 || !fanOut.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("Fan-out semaphore acquire timeout")
                }
                try {
                    if (System.nanoTime() - started >= timeout.toNanos() || Thread.currentThread().isInterrupted) {
                        throw TimeoutException("Timeout before hydrator invocation")
                    }
                    hydrator.hydrate(context, section)
                } finally {
                    fanOut.release()
                }
            },
            executor,
        )
        return original.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle { result, error ->
                if (error != null) {
                    taskThread.get()?.interrupt()
                }
                recordHydration(context, section, started)
                outcome(result, error)
            }
    }

    /** Metrica protegida: falha do registry nunca vira falha de hidratacao nem escapa do join. */
    private fun recordHydration(context: HydrationContext, section: Section, startedNanos: Long) {
        runCatching {
            metrics.recordNanos(
                MetricNames.SECTION_HYDRATE,
                System.nanoTime() - startedNanos,
                mapOf(
                    "type" to section.type,
                    "typeVersion" to section.typeVersion.toString(),
                    "platform" to context.platform.wire(),
                    "channel" to context.channel.wire(),
                ),
            )
        }
    }

    private fun outcome(result: HydrationResult?, error: Throwable?): HydrationResult = when {
        error == null -> result ?: HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
        rootCause(error) is TimeoutException -> HydrationResult.Failed(OmittedReason.HYDRATION_TIMEOUT)
        else -> HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
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

    companion object {
        private val threadFactory = Thread.ofVirtual().name("sdui-hydrate-", 0).factory()

        /** Uma virtual thread nova por section, com nome rastreavel nos dumps. */
        fun virtualThreadExecutor(): Executor = Executor { runnable -> threadFactory.newThread(runnable).start() }
    }
}
