package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.filter.Filter
import br.com.empresa.sdui.core.limit.RetryAfter
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.RevisionIds
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * Contrato para a escada de fallback e tratamento de degradação controlada de telas (ADR-007).
 *
 * ### 1. O que faz
 * Define as operações essenciais para mitigar indisponibilidades de dependências e falhas no
 * pipeline Server-Driven UI através de estratégias escalonadas de degradação.
 *
 * ### 2. Para que serve
 * Segrega a política de resiliência e degradação de erros do fluxo principal de composição (SRP e DIP),
 * permitindo que a recuperação de telas antigas (last-good), o cálculo de `Retry-After` com jitter e o
 * registro de falhas de infraestrutura sejam testados e evoluídos de maneira independente.
 *
 * ### 3. Como funciona
 * Declara métodos para resolver resultados de fallback ou indisponibilidade ([fallbackOrUnavailable]),
 * dispersar tentativas de novos acessos com jitter ([retryAfter]) e registrar falhas operacionais
 * em armazenamentos persistentes e caches ([reportStoreFailure] e [reportCacheWriteFailure]).
 */
interface FallbackCoordinator {
    /**
     * Resolve a entrega de uma tela de contingência ou emite status de indisponibilidade de serviço.
     *
     * ### 1. O que faz
     * Executa a decisão de último recurso do BFF antes de responder erro HTTP 503 ao usuário final.
     *
     * ### 2. Para que serve
     * Garante a navegabilidade da aplicação entregando uma tela funcional preservada no histórico recente,
     * degradando para 503 apenas quando não houver qualquer versão estável utilizável.
     *
     * ### 3. Como funciona
     * Tenta servir uma tela válida via [LastGoodScreenStore]; se ausente ou expirada, constrói um
     * [ComposeResult.Unavailable] com o tempo de `Retry-After` calculado com jitter e o motivo da falha.
     *
     * @param surface Definição canônica da surface solicitada.
     * @param context Contexto validado do cliente requisitante.
     * @param channel Canal de distribuição da requisição.
     * @param reason Motivo determinante da degradação.
     * @param tags Dicionário de tags de telemetria para instrumentação.
     * @return O resultado encapsulado em [ComposeResult].
     */
    fun fallbackOrUnavailable(
        surface: SurfaceDefinition,
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult

    /**
     * Calcula o tempo em segundos para o cabeçalho HTTP `Retry-After` aplicando dispersão estatística.
     *
     * ### 1. O que faz
     * Aplica uma variação uniforme pseudoaleatória sobre o tempo base configurado.
     *
     * ### 2. Para que serve
     * Evita o problema do efeito manada (thundering herd), onde múltiplos clientes recusados simultaneamente
     * retornariam no mesmo segundo exato, gerando novos picos de sobrecarga no serviço.
     *
     * ### 3. Como funciona
     * Invoca o algoritmo [RetryAfter.jittered] aplicando uma dispersão de $\pm 40\%$ sobre [baseSeconds].
     *
     * @param baseSeconds Tempo base de espera em segundos.
     * @return Tempo em segundos com jitter aplicado.
     */
    fun retryAfter(baseSeconds: Long): Long

    /**
     * Registra uma falha de dependência em operações de leitura nos repositórios de dados.
     *
     * ### 1. O que faz
     * Incrementa métricas operacionais e emite mensagem de log estruturada sobre a falha ocorrida.
     *
     * ### 2. Para que serve
     * Notifica os operadores e ferramentas de monitoramento sobre instabilidades em stores (ex.: MongoDB/Redis)
     * sem interromper abruptamente a execução do pipeline.
     *
     * ### 3. Como funciona
     * Emite incremento no contador [MetricNames.STORE_FAILURE] com a tag do estágio afetado e registra
     * o erro no log do sistema em nível WARNING.
     *
     * @param stage Nome descritivo da etapa onde ocorreu a falha.
     * @param error Exceção capturada.
     */
    fun reportStoreFailure(stage: String, error: Throwable)

    /**
     * Registra uma falha em operações de escrita no cache.
     *
     * ### 1. O que faz
     * Registra métricas e logs quando uma tentativa de gravação em cache falha.
     *
     * ### 2. Para que serve
     * Permite identificar cenários onde o cache está silenciosamente recusando escritas (o que levaria
     * a 100% de cache miss sem que o serviço retornasse erro formal aos clientes).
     *
     * ### 3. Como funciona
     * Incrementa o contador [MetricNames.CACHE_WRITE_FAILURE] associando a tag do cache correspondente
     * e grava o erro em log no nível WARNING.
     *
     * @param cache Nome identificador do cache afetado.
     * @param error Exceção capturada.
     */
    fun reportCacheWriteFailure(cache: String, error: Throwable)
}

/**
 * Implementação padrão da escada de fallback Server-Driven UI (ADR-007).
 *
 * ### 1. O que faz
 * Coordena os degraus de degradação da resposta: desde a recuperação de árvores estáveis recentes no
 * armazenamento de last-good até a emissão controlada de HTTP 503 com `Retry-After` com jitter.
 *
 * ### 2. Para que serve
 * Garante que falhas temporárias em serviços de retaguarda ou stores não resultem em telas quebradas
 * ou códigos 500 no hot path, mantendo a melhor experiência viável para o usuário móvel.
 *
 * ### 3. Como funciona
 * A política executa a seguinte ordem de degradação:
 * 1. **Consulta de Last-Good:** Tenta carregar a árvore estável anterior do [lastGood].
 * 2. **Validação Temporal:** Rejeita cópias cuja idade exceda [ComposeBudgets.maxFallbackAge], evitando
 *    entregar layouts defasados que possam desrespeitar regras atuais de negócio (ADR-014).
 * 3. **Filtragem Dinâmica:** Submete as seções salvas a [Filter.filter] considerando as capacidades atuais
 *    do cliente requisitante obtidas via [matrix].
 * 4. **Garantia de Slots Obrigatórios:** Verifica se todos os slots portantes/obrigatórios do esqueleto
 *    estão preservados; se um slot essencial faltar, rejeita o last-good.
 * 5. **Entrega Marcada:** Emite [ComposeResult.Success] com a flag `fallback = true` e o motivo preenchido.
 * 6. **Degradação Final:** Na impossibilidade de servir o last-good, incrementa [MetricNames.COMPOSE_UNAVAILABLE]
 *    e responde [ComposeResult.Unavailable] acompanhado do tempo de recuo com jitter.
 *
 * @property lastGood Armazenamento das últimas árvores válidas compostas.
 * @property matrix Matriz de compatibilidade de capacidades móveis.
 * @property metrics Gravador de métricas e instrumentação.
 * @property clock Relógio para aferição precisa de carimbos de tempo e expiração.
 * @property budgets Prazos e orçamentos temporais de composição.
 * @property randomFraction Função geradora de frações aleatórias para cálculo do jitter de resiliência.
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
     * Resolve a entrega do last-good ou direciona para indisponibilidade de serviço.
     *
     * ### 1. O que faz
     * Conduz o passo final da escada de degradação antes de assumir HTTP 503.
     *
     * ### 2. Para que serve
     * Assegura que falhas de infraestrutura não derrubem a experiência do usuário quando houver
     * uma composição válida recente armazenada.
     *
     * ### 3. Como funciona
     * Invoca [serveLastGood]; se bem-sucedido, retorna imediatamente. Caso contrário, registra métrica
     * de indisponibilidade com a tag do motivo e retorna [ComposeResult.Unavailable].
     *
     * @param surface Definição da surface solicitada.
     * @param context Contexto validado do cliente.
     * @param channel Canal de distribuição.
     * @param reason Motivo que originou a descida para a escada de fallback.
     * @param tags Tags de telemetria associadas à requisição.
     * @return O resultado resolvido da contingência.
     */
    override fun fallbackOrUnavailable(
        surface: SurfaceDefinition,
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        tags: Map<String, String>,
    ): ComposeResult {
        val channelTags = tags + mapOf("channel" to channel.wire())
        serveLastGood(surface, context, channel, reason, channelTags)?.let { return it }
        // O pior desfecho do servico precisa ter contador proprio. Sem ele a taxa de 503 so existe
        // no contador HTTP generico, sem o motivo — que e a unica informacao que diz ao operador
        // onde olhar.
        metrics.increment(MetricNames.COMPOSE_UNAVAILABLE, channelTags + mapOf("fallbackReason" to reason.wire))
        return ComposeResult.Unavailable(retryAfter(budgets.retryAfterSeconds), reason)
    }

    /**
     * Devolve o last-good pronto para ser servido ao cliente, ou null quando ausente, expirado ou inválido.
     *
     * ### 1. O que faz
     * Recupera a árvore do repositório, valida sua vigência temporal, refiltra as capacidades e
     * reidrata os dados contextuais do requisitante atual.
     *
     * ### 2. Para que serve
     * Isola as verificações de conformidade estrutural do fallback, garantindo que versões antigas
     * não violem restrições do cliente requisitante nem entreguem slots portantes vazios.
     *
     * ### 3. Como funciona
     * 1. Consulta o [lastGood] utilizando surface, plataforma e canal.
     * 2. Calcula a idade do registro e rejeita se for superior a [ComposeBudgets.maxFallbackAge].
     * 3. Confere a compatibilidade do schema version, plataforma e identificador de revisão.
     * 4. Executa [Filter.filter] com a matriz de capacidades do requisitante.
     * 5. Confere a presença de todos os slots portantes/obrigatórios no esqueleto da tela.
     * 6. Registra métricas de idade de fallback e retorna a tela marcada com [withRequester].
     *
     * @param surface Definição da surface solicitada.
     * @param context Contexto validado do cliente.
     * @param channel Canal de distribuição resolvido.
     * @param reason Motivo determinante da invocação do fallback.
     * @param channelTags Tags de métricas da requisição.
     * @return [ComposeResult.Success] com `fallback = true`, ou `null` se impróprio para uso.
     */
    private fun serveLastGood(
        surface: SurfaceDefinition,
        context: ClientContext,
        channel: Channel,
        reason: FallbackReason,
        channelTags: Map<String, String>,
    ): ComposeResult.Success? {
        val stored = try {
            lastGood.get(surface.id, context.platform, channel)
        } catch (error: Exception) {
            reportStoreFailure(STAGE_LAST_GOOD, error)
            null
        } ?: return null

        val age = Duration.between(stored.storedAt, clock.instant())
        if (age > budgets.maxFallbackAge) {
            metrics.increment(MetricNames.COMPOSE_FALLBACK_EXPIRED, channelTags)
            return null
        }

        val screen = stored.screen
        if (context.schemaVersion !in MvpCatalog.SUPPORTED_SCHEMA_VERSIONS ||
            screen.schemaVersion != context.schemaVersion ||
            screen.surface != surface.id || screen.platform != context.platform || screen.channel != channel ||
            !RevisionIds.isValid(screen.specRevisionId)
        ) return null
        val filtered = Filter.filter(screen.sections, screen.skeleton, matrix.effective(context))
        val presentSlots = filtered.sections.map { it.slot }.toSet()
        if (!screen.skeleton.requiredSlotIds.all { it in presentSlots }) return null

        metrics.recordTime(MetricNames.COMPOSE_FALLBACK_AGE, age.toMillis().coerceAtLeast(0L), channelTags)
        metrics.increment(MetricNames.COMPOSE_FALLBACK, channelTags + mapOf("fallbackReason" to reason.wire))
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

    /**
     * Calcula o valor de `Retry-After` com jitter uniforme.
     *
     * ### 1. O que faz
     * Retorna o tempo em segundos com variação pseudoaleatória de $\pm 40\%$.
     *
     * ### 2. Para que serve
     * Desincroniza reconexões de clientes sob cenários de indisponibilidade ou rate limit.
     *
     * ### 3. Como funciona
     * Invoca [RetryAfter.jittered] repassando a base e a fração aleatória fornecida por [randomFraction].
     *
     * @param baseSeconds Tempo base de espera em segundos.
     * @return O tempo calculado com jitter em segundos.
     */
    override fun retryAfter(baseSeconds: Long): Long =
        RetryAfter.jittered(baseSeconds, randomFraction())

    /**
     * Reporta falhas de leitura em stores de persistência.
     *
     * ### 1. O que faz
     * Registra métricas e logs para falhas de persistência.
     *
     * ### 2. Para que serve
     * Facilita a observabilidade e diagnóstico de falhas em tempo de execução.
     *
     * ### 3. Como funciona
     * Incrementa o contador [MetricNames.STORE_FAILURE] e emite aviso no logger com o estágio afetado.
     *
     * @param stage Nome do estágio onde ocorreu a falha.
     * @param error Causa da falha.
     */
    override fun reportStoreFailure(stage: String, error: Throwable) {
        metrics.increment(MetricNames.STORE_FAILURE, mapOf("stage" to stage))
        LOG.log(System.Logger.Level.WARNING, "falha de dependencia de dados na etapa $stage", error)
    }

    /**
     * Reporta falhas em operações de escrita de cache.
     *
     * ### 1. O que faz
     * Notifica métricas e emite alertas nos logs para falhas de escrita em cache.
     *
     * ### 2. Para que serve
     * Alerta sobre degradação em caches voláteis (Redis / memória).
     *
     * ### 3. Como funciona
     * Incrementa o contador [MetricNames.CACHE_WRITE_FAILURE] e registra log WARNING.
     *
     * @param cache Nome identificador do cache.
     * @param error Exceção causadora da falha de escrita.
     */
    override fun reportCacheWriteFailure(cache: String, error: Throwable) {
        metrics.increment(MetricNames.CACHE_WRITE_FAILURE, mapOf("cache" to cache))
        LOG.log(System.Logger.Level.WARNING, "escrita de cache perdida em $cache", error)
    }

    /**
     * Constantes e logger estático para o coordenador de fallback.
     *
     * ### 1. O que faz
     * Mantém constantes de identificação de estágio e o registrador de log do JDK.
     *
     * ### 2. Para que serve
     * Padroniza o logging e telemetria do módulo de fallback sem acoplamento com bibliotecas externas.
     *
     * ### 3. Como funciona
     * Inicializa a constante de estágio `last_good` e o logger oficial [System.Logger].
     */
    private companion object {
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
 * Reidrata os campos contextuais do requisitante ao servir uma árvore pré-composta.
 *
 * ### 1. O que faz
 * Sobrescreve os campos voláteis [ComposedScreen.generatedAt], [ComposedScreen.client] e [ComposedScreen.locale]
 * com os dados da requisição corrente.
 *
 * ### 2. Para que serve
 * Impede que uma árvore servida a partir de cache, singleflight ou last-good carregue dados de auditoria
 * de outro dispositivo que compôs a tela originariamente.
 *
 * ### 3. Como funciona
 * Executa uma cópia rasa da [ComposedScreen] preenchendo o instante obtido do [clock] e os dados do [context] atual.
 *
 * @param clock Relógio do sistema para aferição do instante atual.
 * @param context Contexto validado do cliente requisitante.
 * @return Nova instância de [ComposedScreen] adaptada para o cliente atual.
 */
internal fun ComposedScreen.withRequester(clock: Clock, context: ClientContext): ComposedScreen = copy(
    generatedAt = clock.instant(),
    client = context,
    locale = context.locale,
)
