package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
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
 * Saída consolidada do processo de enriquecimento e hidratação de componentes.
 *
 * ### 1. O que faz
 * Reúne o conjunto de seções que foram enriquecidas com sucesso, as seções omitidas com seus
 * respectivos motivos técnicos e o indicativo de violação de slots obrigatórios.
 *
 * ### 2. Para que serve
 * Permite ao orquestrador do pipeline tomar a decisão de entregar uma tela completa, entregar uma tela
 * com omissão graciosa parcial ou acionar a escada de fallback quando um slot portante é comprometido.
 *
 * ### 3. Como funciona
 * Encapsula as seções mantidas ([sections]), as seções omitidas ([omitted]) e o sinalizador
 * [requiredSlotFailed], que indica se algum slot essencial definido no esqueleto ficou vazio.
 *
 * @property sections Lista de seções hidratadas com sucesso que farão parte da resposta.
 * @property omitted Lista de seções descartadas acompanhadas da justificativa de omissão.
 * @property requiredSlotFailed Flag que indica se ao menos um slot portante/obrigatório falhou.
 */
data class HydratedTree(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
    val requiredSlotFailed: Boolean,
)

/**
 * Coordenador de enriquecimento e hidratação concorrente de seções Server-Driven UI.
 *
 * ### 1. O que faz
 * Gerencia a execução das rotinas de hidratação das seções da tela, combinando processamento inline
 * para componentes estáticos com fan-out concorrente em Virtual Threads para componentes com I/O.
 *
 * ### 2. Para que serve
 * Permite que seções dinâmicas consultem serviços de retaguarda em paralelo com contenção de concorrência
 * via semáforo e prazos estritos por componente, evitando que a lentidão de uma fonte de dados degrade a
 * composição inteira ou esgote recursos do servidor.
 *
 * ### 3. Como funciona
 * 1. **Segregação de Tarefas:** Identifica o [SectionHydrator] registrado para cada seção via [hydratorFor].
 * 2. **Processamento Inline:** Se [SectionHydrator.performsIo] for falso (ex.: [PassThroughHydrator]), a
 *    execução ocorre diretamente na thread da requisição via [hydrateInline], economizando a alocação de threads.
 * 3. **Fan-Out com Semáforo:** Se declarar I/O, a tarefa é despachada via [launch] para uma Virtual Thread.
 *    A concorrência global é contida por [fanOut] com limite de espera calibrado pelo [timeout].
 * 4. **Interrupção Ativa sob Timeout (Diretriz Pós-Review):** Caso o tempo limite expire, o manipulador
 *    assíncrono dispara ativamente `interrupt()` na thread executora, cancelando descritores de I/O
 *    bloqueantes e garantindo a devolução imediata do permit do semáforo no bloco `finally`.
 * 5. **Isolamento de Telemetria:** A gravação de métricas via [recordHydration] é protegida com `runCatching`,
 *    impedindo que falhas do coletor de métricas vazem como `CompletionException` no `join()`.
 * 6. **Omissão Graciosa:** Seções falhas viram [OmittedSection] com [OmittedReason.HYDRATION_FAILED] ou
 *    [OmittedReason.HYDRATION_TIMEOUT]. Se a seção pertencer a um slot obrigatório ([Skeleton.requiredSlotIds]),
 *    sinaliza [HydratedTree.requiredSlotFailed].
 *
 * @property hydrators Lista de hidratadores específicos registrados no sistema.
 * @property fanOut Semáforo que controla o teto de concorrência simultânea de I/O no fan-out.
 * @property timeout Prazo máximo de execução alocado para a hidratação de cada seção individual.
 * @property metrics Gravador de métricas e telemetria operacional.
 * @property executor Executor de tarefas assíncronas baseado em Virtual Threads do Java 25.
 */
class HydrationCoordinator(
    private val hydrators: List<SectionHydrator>,
    private val fanOut: Semaphore,
    private val timeout: Duration,
    private val metrics: MetricsRecorder,
    private val executor: Executor = virtualThreadExecutor(),
) {
    private val passThrough = PassThroughHydrator()

    /**
     * Executa a hidratação completa das seções de uma tela.
     *
     * ### 1. O que faz
     * Coordena o enriquecimento de todas as seções fornecidas, aplicando estratégias concorrentes ou inline.
     *
     * ### 2. Para que serve
     * Produz o conjunto final de seções que irão compor a tela apresentada ao usuário.
     *
     * ### 3. Como funciona
     * Dispara as tarefas de I/O em paralelo através de [launch], processa as seções locais inline via
     * [hydrateInline], consolida os resultados aguardando com `join()`, agrega omissões e verifica a
     * integridade dos slots obrigatórios definidos em [Skeleton.requiredSlotIds].
     *
     * @param context Contexto resumido da requisição para hidratação.
     * @param skeleton Esqueleto estrutural contendo slots e restrições da tela.
     * @param sections Lista de seções filtradas elegíveis para hidratação.
     * @param alreadyOmitted Seções omitidas em estágios anteriores (ex.: no filtro de capacidades).
     * @return [HydratedTree] contendo seções aprovadas, omitidas e status de slots obrigatórios.
     */
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

    /**
     * Localiza o hidratador adequado para o tipo e versão de componente da seção.
     *
     * ### 1. O que faz
     * Varre os hidratadores configurados procurando por suporte ao par `(type, typeVersion)`.
     *
     * ### 2. Para que serve
     * Atua como mecanismo de extensão SPI para inclusão de novas fontes de dados especializadas.
     *
     * ### 3. Como funciona
     * Retorna o primeiro hidratador em [hydrators] que retornar `true` em [SectionHydrator.supports],
     * ou degrada para [PassThroughHydrator] como padrão.
     *
     * @param section Seção para a qual se busca o hidratador.
     * @return A implementação de [SectionHydrator] apropriada.
     */
    private fun hydratorFor(section: Section): SectionHydrator =
        hydrators.firstOrNull { it.supports(section.type, section.typeVersion) } ?: passThrough

    /**
     * Realiza a hidratação síncrona diretamente na thread da requisição.
     *
     * ### 1. O que faz
     * Executa [SectionHydrator.hydrate] inline sem despacho de threads.
     *
     * ### 2. Para que serve
     * Otimiza a latência e o consumo de CPU para componentes que apenas repassam propriedades estáticas.
     *
     * ### 3. Como funciona
     * Mede o tempo decorrido, executa a hidratação protegida por `try-catch`, emite a métrica via
     * [recordHydration] e devolve o resultado ou falha.
     *
     * @param context Contexto de hidratação.
     * @param section Seção a ser processada.
     * @param hydrator Hidratador sem operações de I/O.
     * @return O [HydrationResult] resultante.
     */
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

    /**
     * Despacha a hidratação de uma seção em Virtual Thread com contenção de concorrência e timeout.
     *
     * ### 1. O que faz
     * Executa a rotina de enriquecimento assíncrono sob semáforo de bulkhead e prazo máximo configurado.
     *
     * ### 2. Para que serve
     * Isola o tempo de chamada de serviços externos, garantindo que timeouts provoquem omissão sem travar
     * as demais seções nem esgotar recursos de conexão.
     *
     * ### 3. Como funciona
     * Armazena a referência da thread em [AtomicReference], obtém autorização de [fanOut], invoca
     * [SectionHydrator.hydrate] e garante a liberação do semáforo no `finally`. Aplica `orTimeout` e,
     * no manipulador `.handle`, interrompe a thread caso ocorra erro ou timeout para cancelar descritores
     * de I/O imediatamente.
     *
     * @param context Contexto de hidratação.
     * @param section Seção a ser hidratada.
     * @param hydrator Hidratador com operações de I/O.
     * @return [CompletableFuture] com o resultado da hidratação.
     */
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

    /**
     * Registra com segurança a métrica de latência de hidratação de uma seção.
     *
     * ### 1. O que faz
     * Envia o tempo de execução da seção ao coletor de métricas.
     *
     * ### 2. Para que serve
     * Proporciona observabilidade detalhada de performance por componente sem propagar falhas de telemetria.
     *
     * ### 3. Como funciona
     * Protege a invocação de [MetricsRecorder.recordNanos] com `runCatching`, evitando que erros de
     * instrumentação afetem a composição de tela.
     *
     * @param context Contexto de hidratação.
     * @param section Seção processada.
     * @param startedNanos Carimbo de início em nanossegundos.
     */
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

    /**
     * Mapeia o resultado ou exceção capturada em um desfecho estruturado de hidratação.
     *
     * ### 1. O que faz
     * Traduz retornos normais ou falhas assíncronas para as variantes de [HydrationResult].
     *
     * ### 2. Para que serve
     * Padroniza o motivo da omissão da seção para o contrato Server-Driven UI.
     *
     * ### 3. Como funciona
     * Analisa o erro através de [rootCause]. Se for [TimeoutException], gera [OmittedReason.HYDRATION_TIMEOUT];
     * caso contrário, gera [OmittedReason.HYDRATION_FAILED].
     *
     * @param result Resultado retornado pela tarefa, se houver.
     * @param error Erro ou exceção interceptada.
     * @return [HydrationResult] final.
     */
    private fun outcome(result: HydrationResult?, error: Throwable?): HydrationResult = when {
        error == null -> result ?: HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
        rootCause(error) is TimeoutException -> HydrationResult.Failed(OmittedReason.HYDRATION_TIMEOUT)
        else -> HydrationResult.Failed(OmittedReason.HYDRATION_FAILED)
    }

    /**
     * Localiza a causa raiz desempacotando exceções encadeadas.
     *
     * ### 1. O que faz
     * Percorre a árvore de causas de uma exceção.
     *
     * ### 2. Para que serve
     * Permite identificar a exceção causadora real por trás de envelopes de concorrência (`CompletionException`).
     *
     * ### 3. Como funciona
     * Itera sobre as causas registrando instâncias visitadas para evitar loops em causas circulares.
     *
     * @param error Exceção de topo.
     * @return A causa raiz desempacotada.
     */
    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = HashSet<Throwable>()
        while (true) {
            val cause = current.cause
            if (cause == null || cause === current || !seen.add(current)) return current
            current = cause
        }
    }

    /**
     * Infraestrutura de threads virtuais para o coordenador de hidratação.
     *
     * ### 1. O que faz
     * Centraliza a fábrica de threads virtuais e o executor leve utilizado no fan-out.
     *
     * ### 2. Para que serve
     * Fornece threads nomeadas e rastreáveis para identificação clara em dumps de thread e profiling.
     *
     * ### 3. Como funciona
     * Inicializa a fábrica [Thread.ofVirtual] com o prefixo `sdui-hydrate-`.
     */
    companion object {
        private val threadFactory = Thread.ofVirtual().name("sdui-hydrate-", 0).factory()

        /**
         * Cria um [Executor] que instancia uma nova Virtual Thread a cada submissão de tarefa.
         *
         * ### 1. O que faz
         * Retorna uma instância de [Executor] baseada em Virtual Threads do Java 25.
         *
         * ### 2. Para que serve
         * Fornece a infraestrutura de execução assíncrona não bloqueante de alta escalabilidade para o pipeline.
         *
         * ### 3. Como funciona
         * Despacha a execução da tarefa chamando `threadFactory.newThread(runnable).start()`.
         *
         * @return O [Executor] configurado com Virtual Threads.
         */
        fun virtualThreadExecutor(): Executor = Executor { runnable -> threadFactory.newThread(runnable).start() }
    }
}
