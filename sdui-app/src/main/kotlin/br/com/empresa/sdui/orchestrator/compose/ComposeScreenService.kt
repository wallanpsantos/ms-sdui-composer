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
 * Orquestrador central do pipeline de composição Server-Driven UI.
 *
 * ### 1. O que faz
 * Coordena o ciclo de vida completo da composição de telas do BFF Server-Driven UI: desde o
 * recebimento e validação dos cabeçalhos HTTP do cliente até a entrega do envelope final
 * ([ComposeResult]) contendo a árvore de componentes de interface montada para qualquer surface
 * autorizada na allowlist (ex.: `home`, `catalog`).
 *
 * ### 2. Para que serve
 * Atua como o núcleo operacional da camada de BFF (Backend-for-Frontend). É responsável por
 * decidir quando reaproveitar árvores pré-computadas do cache, quando conter requisições concorrentes
 * idênticas através de singleflight para proteção do backend, e quando acionar a escada de degradação
 * graciosa e fallback (ADR-007) para manter a navegabilidade do cliente diante de instabilidades.
 *
 * ### 3. Como funciona
 * O fluxo de execução do pipeline opera através das seguintes etapas ordenadas:
 * 1. **TimeBudget e Bulkhead:** Abre um [TimeBudget] para a requisição e submete as leituras de
 *    persistência ao semáforo de contenção ([readBulkhead]), evitando a exaustão de conexões.
 * 2. **Negotiate:** Valida os cabeçalhos HTTP através de [Negotiate.negotiate]. Cabeçalhos inválidos
 *    retornam imediatamente [ComposeResult.InvalidHeaders].
 * 3. **Rate Limiting:** Avalia a coorte da requisição via [TokenBucketRateLimiter]. Requisições que
 *    excederem o limite retornam [ComposeResult.RateLimited] com cabeçalho `Retry-After` com jitter.
 * 4. **Canary e Capabilities:** Resolve o canal de distribuição através da [canaryPolicy] e calcula as
 *    capacidades efetivas suportadas pelo app via [CapabilityMatrix.effective], gerando o hash [CapsHash].
 * 5. **Select:** Identifica a especificação publicada ([Spec]) mais adequada através de [selectSpec]. A
 *    seleção ocorre em toda requisição antes da consulta ao cache de árvore para respeitar as dimensões
 *    de targeting (versão do aplicativo e SO).
 * 6. **Validação Condicional (ETag):** Gera o identificador [ETagFactory.of]. Caso coincida com o cabeçalho
 *    `If-None-Match`, encerra a requisição retornando [ComposeResult.NotModified] (HTTP 304).
 * 7. **Cache de Árvore Hidratada:** Consulta o [treeCache]. Em caso de acerto (cache hit), reidrata os
 *    campos voláteis do requisitante (`client`, `locale`, `generatedAt` via `withRequester`) e devolve
 *    [ComposeResult.Success] com `fromCache = true`.
 * 8. **Singleflight Concorrente:** Em caso de cache miss, ingressa no [singleflight]. Apenas uma
 *    requisição líder executa a montagem fresca, enquanto as demais aguardam. Waiters com timeout local
 *    nunca cancelam a computação do líder compartilhado (ADR-014), degradando de forma isolada.
 * 9. **Filter, Hydrate e Guard:** O líder obtém o layout do esqueleto, filtra seções compatíveis via
 *    [Filter.filter], dispara o enriquecimento assíncrono via [HydrationCoordinator] e verifica a presença
 *    de todos os slots obrigatórios.
 * 10. **Persistência de Cache e Last-Good:** Salva a árvore no [treeCache] e atualiza o [lastGood].
 * 11. **Escada de Fallback (ADR-007):** Falhas em dependências, timeouts ou slots obrigatórios vazios
 *     são interceptados e encaminhados ao [FallbackCoordinator], retornando a última versão estável
 *     ou HTTP 503 com `Retry-After` com jitter.
 *
 * @property specStore Porta outbound para consulta persistente de especificações de tela.
 * @property skeletonStore Porta outbound para consulta persistente de esqueletos de layout.
 * @property pointerStore Porta outbound para consulta do ponteiro ativo do canal.
 * @property specCache Cache em memória ou distribuído para especificações de tela.
 * @property treeCache Cache de árvores de tela hidratadas e prontas para entrega.
 * @property lastGood Armazenamento da última composição estável conhecida para recuperação emergencial.
 * @property singleflight Mecanismo de deduplicação e sincronização de requisições concorrentes idênticas.
 * @property hydrator Coordenador do enriquecimento assíncrono de dados das seções.
 * @property matrix Matriz de compatibilidade de capacidades por plataforma e versão.
 * @property canaryPolicy Política de direcionamento e liberação de canais canary.
 * @property rateLimiter Limitador de taxa de requisições baseado no algoritmo token bucket.
 * @property readBulkhead Mecanismo de isolamento de concorrência para leitura de dados.
 * @property metrics Gravador de métricas e instrumentação operacional.
 * @property clock Relógio do sistema utilizado para geração determinística de carimbos de data/hora.
 * @property budgets Configurações de prazos temporais e orçamentos de execução do pipeline.
 * @property randomFraction Provedor de frações aleatórias para cálculo do jitter de resiliência.
 * @property nanoTime Provedor de tempo monotônico em nanossegundos para o orçamento de execução.
 * @property fallbackCoordinator Coordenador responsável pela execução dos degraus da escada de fallback.
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

    /**
     * Executa a composição completa da tela para a requisição fornecida.
     *
     * ### 1. O que faz
     * Processa a [request] de composição, conduzindo-a por todas as etapas do pipeline Server-Driven UI
     * e retornando um [ComposeResult] com a tela montada, resposta condicional ou status de degradação.
     *
     * ### 2. Para que serve
     * Constitui o ponto de entrada primário do caso de uso de montagem de telas ([ComposeScreenUseCase]),
     * invocada diretamente pelos adaptadores de entrada (ex.: controllers HTTP).
     *
     * ### 3. Como funciona
     * Abre o orçamento temporal, valida cabeçalhos, avalia rate limiting, resolve canal e capacidades,
     * seleciona a especificação correspondente, valida o cabeçalho `If-None-Match`, busca do cache de
     * árvore, orquestra a montagem concorrente via singleflight e aciona a escada de fallback em caso de falha.
     *
     * @param request Requisição contendo a surface solicitada, cabeçalhos HTTP e validadores condicionais.
     * @return O resultado da composição encapsulado em uma das variantes de [ComposeResult].
     */
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

    /**
     * Par de resultado da resolução de especificação.
     *
     * ### 1. O que faz
     * Encapsula a especificação de tela ([Spec]) selecionada e a versão correspondente do ponteiro lido.
     *
     * ### 2. Para que serve
     * Transporta de forma atômica a especificação eleita e a versão do ponteiro para uso na montagem da árvore
     * e verificação de integridade de versão (ADR-021).
     *
     * ### 3. Como funciona
     * Armazena [spec] (ou `null` se nenhuma atender aos critérios) e [pointerVersion] (0 se não houver ponteiro).
     *
     * @property spec Especificação selecionada para a requisição, ou `null` se nenhuma for compatível.
     * @property pointerVersion Versão monotônica do ponteiro no momento da leitura.
     */
    private data class Selection(val spec: Spec?, val pointerVersion: Long)

    /**
     * Conjunto imutável de parâmetros requeridos pelo líder do singleflight para montagem de uma nova tela.
     *
     * ### 1. O que faz
     * Agrupa todos os metadados e entidades pré-resolvidas necessárias para a execução de [composeFresh].
     *
     * ### 2. Para que serve
     * Isola e padroniza as entradas da fase de montagem fresca, garantindo que o líder do singleflight
     * possua todo o contexto já validado sem necessidade de refazer resoluções prévias.
     *
     * ### 3. Como funciona
     * Reúne a surface solicitada, o contexto do cliente, o canal resolvido, as capacidades efetivas, a spec
     * selecionada, a versão do ponteiro, o ETag calculado, a chave de cache da árvore e as tags de métricas.
     *
     * @property surface Definição canônica da surface solicitada.
     * @property context Contexto validado do cliente requisitante.
     * @property channel Canal de distribuição resolvido para a requisição.
     * @property caps Conjunto de capacidades efetivas suportadas pelo dispositivo cliente.
     * @property selected Especificação visual eleita na etapa de seleção.
     * @property pointerVersion Versão do ponteiro de publicação no instante da leitura.
     * @property etag Hash ETag calculado para a composição.
     * @property treeKey Chave de cache calculada para armazenamento da árvore hidratada.
     * @property tags Dicionário de tags de telemetria associadas à requisição.
     */
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
     * Consulta o ponteiro e seleciona a revisão de especificação compatível com o cliente.
     *
     * ### 1. O que faz
     * Localiza a especificação visual ([Spec]) adequada consultando primeiramente a revisão apontada
     * diretamente pelo ponteiro e, caso necessário, avaliando todas as especificações publicadas.
     *
     * ### 2. Para que serve
     * Implementa o passo Select do pipeline, garantindo que o dispositivo receba a tela direcionada
     * à sua coorte de versão, sistema operacional e capacidades sem depender de varreduras lineares custosas.
     *
     * ### 3. Como funciona
     * 1. Consulta o ponteiro da surface, plataforma e canal no [pointerStore].
     * 2. Tenta carregar a revisão apontada diretamente do [specCache] ou do [specStore].
     * 3. Valida se a revisão atende ao contexto via [Select.pointedIfServes]; se atender, retorna imediatamente.
     * 4. Caso contrário, lista todas as publicadas e delega a seleção completa para [Select.select].
     *
     * @param surface Definição da surface solicitada.
     * @param context Contexto validado do cliente.
     * @param channel Canal de distribuição da requisição.
     * @param caps Conjunto de capacidades efetivas suportadas.
     * @return Instância de [Selection] contendo a spec eleita (ou `null`) e a versão do ponteiro.
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

    /**
     * Realiza a leitura segura de uma especificação a partir do cache.
     *
     * ### 1. O que faz
     * Tenta recuperar uma [Spec] em cache por seu identificador de revisão e plataforma.
     *
     * ### 2. Para que serve
     * Otimiza a etapa de seleção evitando acessos desnecessários ao armazenamento persistente.
     *
     * ### 3. Como funciona
     * Invoca [specCache.get] protegido por bloco `try-catch`. Qualquer falha é registrada como
     * falha de dependência via [FallbackCoordinator.reportStoreFailure], retornando `null` de forma
     * segura para que a leitura prossiga no repositório persistente sem interromper a composição.
     *
     * @param specRevisionId Identificador único da revisão da especificação.
     * @param platform Plataforma do cliente requisitante.
     * @return A [Spec] encontrada ou `null` se ausente ou em caso de falha de leitura.
     */
    private fun readSpecCache(specRevisionId: String, platform: ClientPlatform): Spec? = try {
        specCache.get(specRevisionId, platform)
    } catch (error: Exception) {
        fallbackCoordinator.reportStoreFailure(STAGE_SPEC_CACHE, error)
        null
    }

    /**
     * Registra preventivamente uma especificação publicada no cache.
     *
     * ### 1. O que faz
     * Armazena a [Spec] no [specCache] caso seu status seja [SpecStatus.PUBLISHED].
     *
     * ### 2. Para que serve
     * Aquece o cache de especificações a partir de leituras feitas no repositório persistente (read-through cache).
     *
     * ### 3. Como funciona
     * Verifica se o status é publicado; em caso afirmativo, submete ao cache dentro de bloco `try-catch`.
     * Falhas de escrita são reportadas via [FallbackCoordinator.reportCacheWriteFailure] sem impactar o fluxo.
     *
     * @param spec Especificação a ser memorizada em cache.
     */
    private fun rememberPublished(spec: Spec) {
        if (spec.status != SpecStatus.PUBLISHED) return
        try {
            specCache.put(spec)
        } catch (error: Exception) {
            fallbackCoordinator.reportCacheWriteFailure(CACHE_SPEC, error)
        }
    }

    /**
     * Consulta uma árvore de tela pré-composta no cache de telas hidratadas.
     *
     * ### 1. O que faz
     * Recupera uma instância de [ComposedScreen] correspondente à chave fornecida.
     *
     * ### 2. Para que serve
     * Viabiliza o aproveitamento de telas já hidratadas e filtradas, contornando o custo de montagem.
     *
     * ### 3. Como funciona
     * Invoca [treeCache.get] protegido contra exceções. Falhas no cache são registradas via
     * [FallbackCoordinator.reportStoreFailure], devolvendo `null` para que a tela seja montada normalmente.
     *
     * @param treeKey Chave determinística de cache da árvore de tela.
     * @return A [ComposedScreen] armazenada ou `null` se não encontrada ou em falha.
     */
    private fun readTree(treeKey: String): ComposedScreen? = try {
        treeCache.get(treeKey)
    } catch (error: Exception) {
        fallbackCoordinator.reportStoreFailure(STAGE_TREE_CACHE, error)
        null
    }

    /**
     * Dispara a montagem fresca de uma tela a partir dos armazenamentos persistentes.
     *
     * ### 1. O que faz
     * Envolve a montagem de tela em tratamento unificado de exceções de infraestrutura.
     *
     * ### 2. Para que serve
     * Garante que qualquer falha inesperada de infraestrutura durante a composição seja tratada em um único
     * ponto e convertida para o status de fallback [FallbackReason.REDIS_UNAVAILABLE].
     *
     * ### 3. Como funciona
     * Invoca [composeFromStores] dentro de bloco `try-catch`. Caso ocorra exceção, reporta a falha
     * via [FallbackCoordinator.reportStoreFailure] e direciona a requisição para degradação graciosa.
     *
     * @param input Parâmetros completos de entrada para a composição.
     * @param budget Orçamento temporal da requisição corrente.
     * @return O resultado da montagem encapsulado em [ComposeResult].
     */
    private fun composeFresh(input: ComposeInput, budget: TimeBudget): ComposeResult = try {
        composeFromStores(input, budget)
    } catch (error: Exception) {
        // Skeleton vem do mesmo backend de dados da selecao: uma falha nele e indisponibilidade
        // de dependencia. Tratar em um ponto so evita que parte das leituras caia aqui e o
        // restante suba ate o catch do singleflight, onde seria reportada como DEPENDENCY_TIMEOUT.
        fallbackCoordinator.reportStoreFailure(STAGE_COMPOSE, error)
        input.degrade(FallbackReason.REDIS_UNAVAILABLE)
    }

    /**
     * Encaminha a requisição de montagem atual para a escada de degradação.
     *
     * ### 1. O que faz
     * Aciona o [FallbackCoordinator] utilizando os parâmetros contidos nesta instância de [ComposeInput].
     *
     * ### 2. Para que serve
     * Simplifica a chamada de fallback durante a montagem de tela quando ocorre falha ou omissão crítica.
     *
     * ### 3. Como funciona
     * Delega para [FallbackCoordinator.fallbackOrUnavailable] repassando surface, contexto, canal, motivo e tags.
     *
     * @param reason Motivo determinante da degradação (ex.: dependência indisponível, slot obrigatório vazio).
     * @return O resultado degradado gerado pelo coordenador de fallback.
     */
    private fun ComposeInput.degrade(reason: FallbackReason): ComposeResult =
        fallbackCoordinator.fallbackOrUnavailable(surface, context, channel, reason, tags)

    /**
     * Executa a sequência estrutural de montagem de tela lendo os artefatos de armazenamento.
     *
     * ### 1. O que faz
     * Recupera o esqueleto, filtra seções, aciona a hidratação assíncrona, valida slots obrigatórios e
     * constrói a árvore final [ComposedScreen], gravando-a no cache de telas e no repositório de last-good.
     *
     * ### 2. Para que serve
     * Realiza a montagem fresca do Server-Driven UI quando ocorre cache miss no pipeline.
     *
     * ### 3. Como funciona
     * 1. Solicita permissão ao [readBulkhead] para recuperar o esqueleto correspondente à [Spec].
     * 2. Executa a filtragem semântica das seções via [Filter.filter], descartando seções sem suporte no cliente.
     * 3. Executa a hidratação das seções via [HydrationCoordinator.hydrate].
     * 4. Valida se algum slot portante/obrigatório falhou ou ficou vazio; se sim, aborta a entrega parcial
     *    e direciona para [FallbackReason.REQUIRED_SLOT_EMPTY].
     * 5. Instancia a [ComposedScreen] preenchendo todos os metadados de auditoria e conformidade.
     * 6. Grava de forma resiliente a nova tela no [treeCache] e no [lastGood].
     * 7. Retorna [ComposeResult.Success] com `fromCache = false`.
     *
     * @param input Parâmetros de entrada contendo contexto, canais e dados resolvidos.
     * @param budget Orçamento de tempo da requisição corrente.
     * @return O resultado [ComposeResult.Success] ou uma variante degradada em caso de falha.
     */
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

    /**
     * Ajusta os campos de auditoria do requisitante em um resultado produzido originariamente para outro cliente.
     *
     * ### 1. O que faz
     * Reidrata os metadados voláteis do requisitante (`client`, `locale`, `generatedAt`) quando a resposta é
     * obtida através de um líder de singleflight ou do cache compartilhado.
     *
     * ### 2. Para que serve
     * Assegura que o envelope SDUI entregue contenha exatamente as informações do dispositivo requisitante atual,
     * impedindo vazamento de dados de auditoria entre requisições simultâneas que compartilham o mesmo layout.
     *
     * ### 3. Como funciona
     * Se o resultado for [ComposeResult.Success], cria uma cópia da tela aplicando [withRequester] com o relógio
     * e o contexto do cliente atual. Caso contrário, mantém o resultado original inalterado.
     *
     * @param context Contexto validado do cliente requisitante atual.
     * @return Nova instância de [ComposeResult] com os dados do requisitante devidamente reidratados.
     */
    private fun ComposeResult.forRequester(context: ClientContext): ComposeResult = when (this) {
        is ComposeResult.Success -> copy(screen = screen.withRequester(clock, context))
        else -> this
    }

    /**
     * Constantes de identificação de estágios e categorias de cache para instrumentação e telemetria.
     *
     * ### 1. O que faz
     * Centraliza os literais de tags de métricas e nomes de estágios operacionais utilizados por [ComposeScreenService].
     *
     * ### 2. Para que serve
     * Garante consistência estrita nos nomes de tags enviados ao coletor de métricas (Micrometer/Prometheus).
     *
     * ### 3. Como funciona
     * Define constantes imutáveis estáticas para os estágios `select`, `singleflight`, `compose` e caches associados.
     */
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

/**
 * Política padrão de canary: serve o canal estável incondicionalmente.
 *
 * ### 1. O que faz
 * Ignora qualquer solicitação de canal diferenciado enviada pelo cliente e sempre resolve para [Channel.STABLE].
 *
 * ### 2. Para que serve
 * Atua como política conservadora padrão quando não há regras de canary ativas ou configuradas no ambiente.
 *
 * ### 3. Como funciona
 * Implementa o contrato [CanaryPolicy] retornando sempre a constante [Channel.STABLE], impedindo adesão
 * não autorizada a versões experimentais de tela.
 */
object DefaultCanaryPolicy : CanaryPolicy {
    /**
     * Resolve o canal de distribuição para o cliente requisitante.
     *
     * ### 1. O que faz
     * Retorna invariavelmente [Channel.STABLE].
     *
     * ### 2. Para que serve
     * Fornece um comportamento previsível e seguro na ausência de segmentação ativa.
     *
     * ### 3. Como funciona
     * Ignora todos os parâmetros de plataforma, build e canal solicitado, devolvendo [Channel.STABLE].
     *
     * @param platform Plataforma do cliente.
     * @param build Número de build da aplicação cliente.
     * @param requested Canal solicitado pelo cliente no cabeçalho HTTP.
     * @return O canal [Channel.STABLE].
     */
    override fun channelFor(
        platform: ClientPlatform,
        build: String,
        requested: Channel,
    ): Channel = Channel.STABLE
}

/**
 * Política de canary baseada em lista de liberação (allowlist) explícita de compilações.
 *
 * ### 1. O que faz
 * Avalia se o build do aplicativo cliente está expressamente autorizado a receber o canal canary
 * para a sua respectiva plataforma.
 *
 * ### 2. Para que serve
 * Impede que clientes móveis forcem a entrada em versões canary simplesmente manipulando o cabeçalho HTTP,
 * garantindo governança estrita e controle do servidor sobre a coorte de usuários expostos a experimentos.
 *
 * ### 3. Como funciona
 * 1. O canal interno ([Channel.INTERNAL]) é sempre permitido por ser reservado para testes controlados.
 * 2. Se o cliente solicitar [Channel.CANARY], verifica se o [build] consta no conjunto autorizado para a [platform].
 * 3. Se autorizado, retorna [Channel.CANARY]; caso contrário, degrada de forma segura para [Channel.STABLE].
 *
 * @property allowed Mapa de identificadores de build autorizados, indexados por plataforma do cliente.
 */
class AllowlistCanaryPolicy(
    private val allowed: Map<ClientPlatform, Set<String>>,
) : CanaryPolicy {
    /**
     * Resolve o canal de distribuição apropriado para a plataforma e build especificados.
     *
     * ### 1. O que faz
     * Determina se o cliente recebe o canal interno, canary ou estável.
     *
     * ### 2. Para que serve
     * Executa a regra de governança de direcionamento de canais na borda do pipeline.
     *
     * ### 3. Como funciona
     * Avalia o canal solicitado contra as permissões configuradas na allowlist, retornando [Channel.INTERNAL],
     * [Channel.CANARY] ou caindo para [Channel.STABLE].
     *
     * @param platform Plataforma do dispositivo cliente ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
     * @param build Número de compilação informado pelo cliente.
     * @param requested Canal solicitado pelo cliente no cabeçalho HTTP.
     * @return O [Channel] efetivamente concedido ao cliente.
     */
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
