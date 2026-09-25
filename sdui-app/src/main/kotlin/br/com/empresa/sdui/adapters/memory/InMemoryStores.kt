package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.orchestrator.port.outbound.ComposeSingleflight
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.ProjectionStore
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache de telas montadas em memoria RAM com teto de capacidade estrito (`ADR-021`).
 *
 * ### 1. O que faz
 * Armazena arvores completas de telas montadas ([ComposedScreen]) em estruturas concorrentes em heap,
 * impondo controle rigoroso de capacidade e expiracao baseada em tempo de vida (TTL).
 *
 * ### 2. Para que serve
 * Elimina a necessidade de recombinacao e processamento repetido para requisicoes identicas de clientes,
 * retornando a arvore em memoria com ultrabaixa latencia e blindando o processo contra estouro de heap.
 *
 * ### 3. Como funciona
 * Indexa entradas por chaves geradas em [RedisKeys.treeKey]. Como o hash de capacidades (`capsHash`)
 * e influenciado por cabecalhos enviados pelo cliente nativo, o cache impoe um teto maximo estrito
 * ([maxEntries]) atraves de um contador [AtomicInteger] com compare-and-set:
 * - A vaga deve ser reservada atomicamente antes da insercao e liberada na remocao.
 * - Ao atingir o teto de capacidade, uma thread assume o papel de poda ([prune]), descartando entradas
 *   vencidas e, persistindo a saturacao, as que vencem mais cedo ate reduzir a ocupacao para metade do teto.
 * - Escritores concorrentes que nao conseguem reservar vaga durante a saturacao descartam a gravacao
 *   ([skippedWrites]), mantendo o cache estritamente best-effort sem bloquear ou estourar a JVM.
 *
 * @property maxEntries Quantidade maxima de arvores mantidas simultaneamente no heap (padrao: 10.000).
 */
class InMemoryHydratedScreenCache(
    private val maxEntries: Int = 10_000,
) : HydratedScreenCache {
    private data class Entry(val screen: ComposedScreen, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val slots = AtomicInteger()
    private val pruning = AtomicBoolean(false)
    private val skippedWrites = AtomicLong()

    /**
     * Recupera uma arvore de tela montada a partir da chave textual.
     *
     * ### 1. O que faz
     * Busca a tela correspondente no cache em memoria.
     *
     * ### 2. Para que serve
     * Atende imediatamente ao hot path caso a tela ja tenha sido previamente composta e nao tenha expirado.
     *
     * ### 3. Como funciona
     * Verifica que a chave nao contenha identificadores pessoais (PII) via [RedisKeys.containsUserId],
     * avalia o timestamp de expiracao (`expiresAt`); se vencida, remove a entrada e devolve `null`.
     *
     * @param treeKey Chave de cache formatada para a tela.
     * @return [ComposedScreen] valido se encontrado e nao expirado; `null` caso contrario.
     */
    override fun get(treeKey: String): ComposedScreen? {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = items[treeKey] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            remove(treeKey, entry)
            return null
        }
        return entry.screen
    }

    /**
     * Armazena uma arvore de tela no cache com tempo de vida definido.
     *
     * ### 1. O que faz
     * Insere ou atualiza a tela na memoria RAM respeitando o teto de capacidade.
     *
     * ### 2. Para que serve
     * Disponibiliza a tela para consultas subsequentes durante o intervalo de TTL.
     *
     * ### 3. Como funciona
     * Se a chave ja for residente, substitui o valor no lugar sem alocar vaga extra.
     * Senao, tenta reservar vaga via CAS; se o teto estiver esgotado, tenta podar e reservar novamente.
     * Se ainda assim nao houver vaga, incrementa [skippedWrites] e descarta a escrita.
     *
     * @param treeKey Chave identificadora da tela.
     * @param screen Arvore de tela serializada e montada.
     * @param ttl Duracao de validade da entrada no cache.
     */
    override fun put(treeKey: String, screen: ComposedScreen, ttl: Duration) {
        check(!RedisKeys.containsUserId(treeKey))
        val entry = Entry(screen, System.currentTimeMillis() + ttl.toMillis())
        // Chave ja residente troca de valor no lugar, sem ocupar vaga nova.
        if (items.replace(treeKey, entry) != null) return
        if (!reserveSlot() && !(prune() && reserveSlot())) {
            skippedWrites.incrementAndGet()
            return
        }
        // Outro escritor inseriu a mesma chave depois do replace: a vaga dele ja a cobre.
        if (items.putIfAbsent(treeKey, entry) != null) slots.decrementAndGet()
    }

    /**
     * Invalida as telas montadas para uma determinada surface, plataforma e canal.
     *
     * ### 1. O que faz
     * Remove do cache todas as entradas correspondentes aos parametros informados.
     *
     * ### 2. Para que serve
     * Expulsa versoes antigas de telas logo apos a ativacao de uma nova revisao ou operacao de rollback.
     *
     * ### 3. Como funciona
     * Varre as entradas do mapa localizando chaves com o prefixo da surface/plataforma e sufixo do canal,
     * removendo-as e decrementando as vagas atomicas ocupadas.
     *
     * @param surface Nome da surface (ex.: `home`).
     * @param platform Plataforma do cliente ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
     * @param channel Canal de distribuicao ([Channel.STABLE] ou [Channel.CANARY]).
     */
    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
        val prefix = RedisKeys.treePrefix(surface, platform)
        val suffix = ":${channel.wire()}"
        removeWhere { key, _ -> key.startsWith(prefix) && key.endsWith(suffix) }
    }

    /**
     * Retorna a quantidade de entradas atualmente residentes no cache.
     *
     * ### 1. O que faz
     * Informa o total pontual de itens mantidos em memoria.
     *
     * ### 2. Para que serve
     * Auxilia no diagnostico operacional e em assercoes de testes de carga e capacidade.
     *
     * ### 3. Como funciona
     * Retorna a contagem instantanea fornecida por `items.size`.
     */
    fun residentEntries(): Int = items.size

    /**
     * Quantidade de vagas ativamente reservadas no contador linearizavel.
     *
     * ### 1. O que faz
     * Informa o valor do semaforo atomico de ocupacao de slots.
     *
     * ### 2. Para que serve
     * Permite inspecionar a concorrencia estrita sob gravacoes simultaneas.
     *
     * ### 3. Como funciona
     * Le diretamente o contador atomico de [slots].
     */
    fun occupiedSlots(): Int = slots.get()

    /**
     * Total acumulado de escritas no cache descartadas por saturacao do teto.
     *
     * ### 1. O que faz
     * Retorna o contador historico de descartes desde a inicializacao.
     *
     * ### 2. Para que serve
     * Instrumenta a metrica Micrometer `sdui.cache.write.skipped` para avaliar dimensionamento de memoria.
     *
     * ### 3. Como funciona
     * Retorna o valor de [skippedWrites].
     */
    fun skippedWrites(): Long = skippedWrites.get()

    /**
     * Remove todas as entradas do cache e reseta as vagas.
     *
     * ### 1. O que faz
     * Esvazia completamente a estrutura em memoria.
     *
     * ### 2. Para que serve
     * Garante isolamento completo entre baterias de testes automatizados.
     *
     * ### 3. Como funciona
     * Invoca remocao condicional com predicado universal.
     */
    fun clear() = removeWhere { _, _ -> true }

    private fun reserveSlot(): Boolean {
        while (true) {
            val taken = slots.get()
            if (taken >= maxEntries) return false
            if (slots.compareAndSet(taken, taken + 1)) return true
        }
    }

    /** Uma thread por vez; devolve false para quem nao conseguiu podar. */
    private fun prune(): Boolean {
        if (!pruning.compareAndSet(false, true)) return false
        try {
            val now = System.currentTimeMillis()
            removeWhere { _, entry -> entry.expiresAt < now }
            val excess = slots.get() - maxEntries / 2
            if (excess > 0) {
                items.entries
                    .sortedBy { it.value.expiresAt }
                    .take(excess)
                    .forEach { remove(it.key, it.value) }
            }
            return true
        } finally {
            pruning.set(false)
        }
    }

    private inline fun removeWhere(predicate: (String, Entry) -> Boolean) {
        for ((key, entry) in items) {
            if (predicate(key, entry)) remove(key, entry)
        }
    }

    private fun remove(key: String, entry: Entry) {
        if (items.remove(key, entry)) slots.decrementAndGet()
    }
}

/**
 * Armazenamento em memoria da ultima tela valida montada para fallback (`ADR-007`).
 *
 * ### 1. O que faz
 * Mantem em heap a representacao arquivada da ultima tela composta com sucesso ([StoredScreen])
 * por surface, plataforma e canal.
 *
 * ### 2. Para que serve
 * Fornece o patamar de contingencia da escada de fallback (200 OK contingencial) quando o pipeline
 * de composicao em tempo real sofre indisponibilidade de stores ou estouro de prazo.
 *
 * ### 3. Como funciona
 * Armazena as telas indexadas por [RedisKeys.lastGood], associadas a versao do ponteiro sob o qual
 * foram montadas. Na invalidacao, registra uma lapide com versao atualizada: uma composicao iniciada
 * antes de uma publicacao nao pode sobrescrever a contingencia com dados de revisao desativada.
 *
 * @property clock Provedor temporal utilizado para carimbar o instante de arquivamento.
 */
class InMemoryLastGoodScreenStore(
    private val clock: Clock = Clock.systemUTC(),
) : LastGoodScreenStore {
    private data class Slot(val stored: StoredScreen?, val version: Long)

    private val items = ConcurrentHashMap<String, Slot>()

    /**
     * Recupera a ultima tela valida arquivada para a surface informada.
     *
     * ### 1. O que faz
     * Busca o registro de contingencia correspondente aos parametros.
     *
     * ### 2. Para que serve
     * Alimenta o coordenador de fallback para verificar a idade e integridade da tela de recuperacao.
     *
     * ### 3. Como funciona
     * Faz lookup direto no mapa concorrente pela chave calculada em [RedisKeys.lastGood].
     */
    override fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen? =
        items[RedisKeys.lastGood(surface, platform, channel)]?.stored

    /**
     * Salva uma tela montada como a nova referencia de ultima tela valida.
     *
     * ### 1. O que faz
     * Grava a arvore carimbada com o instante UTC e a versao do ponteiro corrente.
     *
     * ### 2. Para que serve
     * Assegura que o servico disponha sempre de uma versao recente e testada para situacoes de contingencia.
     *
     * ### 3. Como funciona
     * Executa operacao atomica `compute`: se ja houver registro residente com versao de ponteiro
     * superior a da tela informada, rejeita a gravacao para impedir regressao de revisao.
     */
    override fun put(screen: ComposedScreen) {
        items.compute(RedisKeys.lastGood(screen.surface, screen.platform, screen.channel)) { _, current ->
            if (current != null && current.version > screen.pointerVersion) {
                current
            } else {
                Slot(StoredScreen(screen, clock.instant()), screen.pointerVersion)
            }
        }
    }

    /**
     * Invalida a contingencia atual avancando a versao do ponteiro com uma lapide nula.
     *
     * ### 1. O que faz
     * Marca o slot de contingencia como vazio para a nova versao do ponteiro.
     *
     * ### 2. Para que serve
     * Impede que telas associadas a revisoes revertidas ou descontinuadas sejam entregues como fallback.
     *
     * ### 3. Como funciona
     * Utiliza `compute` atomico para gravar `Slot(null, pointerVersion)` se a versao informada for superior.
     */
    override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long) {
        items.compute(RedisKeys.lastGood(surface, platform, channel)) { _, current ->
            if (current != null && current.version >= pointerVersion) current else Slot(null, pointerVersion)
        }
    }

    /**
     * Limpa todos os registros de last good em memoria.
     *
     * ### 1. O que faz
     * Esvazia o mapa concorrente.
     *
     * ### 2. Para que serve
     * Garante ambiente isolado durante testes automatizados.
     *
     * ### 3. Como funciona
     * Invoca `items.clear()`.
     */
    fun clear() = items.clear()
}

/**
 * Cache em memoria de especificacoes de telas ([Spec]).
 *
 * ### 1. O que faz
 * Armazena instancias imutaveis de [Spec] indexadas por identificador de revisao e plataforma.
 *
 * ### 2. Para que serve
 * Elimina consultas repetidas ao store de governanca durante a etapa de selecao (Select) do pipeline.
 *
 * ### 3. Como funciona
 * Mantem um [ConcurrentHashMap] com chaves construidas via [RedisKeys.spec].
 */
class InMemorySpecCache : SpecCache {
    private val items = ConcurrentHashMap<String, Spec>()

    /**
     * Recupera um spec a partir do id da revisao e da plataforma.
     *
     * ### 1. O que faz
     * Localiza a especificacao correspondente no cache.
     *
     * ### 2. Para que serve
     * Acelera o fluxo de composicao evitando deserializacao ou I/O repetido.
     *
     * ### 3. Como funciona
     * Executa consulta direta no mapa concorrente.
     */
    override fun get(specRevisionId: String, platform: ClientPlatform): Spec? =
        items[RedisKeys.spec(specRevisionId, platform)]

    /**
     * Salva uma especificacao no cache.
     *
     * ### 1. O que faz
     * Insere o objeto [Spec] no mapa em memoria.
     *
     * ### 2. Para que serve
     * Disponibiliza a especificacao para consultas subsequentes no hot path.
     *
     * ### 3. Como funciona
     * Armazena a referencia no mapa sob a chave formatada por [RedisKeys].
     */
    override fun put(spec: Spec) {
        items[RedisKeys.spec(spec.specRevisionId, spec.platform)] = spec
    }

    /**
     * Remove um spec do cache.
     *
     * ### 1. O que faz
     * Exclui a chave correspondente da memoria.
     *
     * ### 2. Para que serve
     * Expulsa especificacoes que sofreram alteracao ou foram invalidadas.
     *
     * ### 3. Como funciona
     * Invoca `items.remove(...)`.
     */
    override fun invalidate(specRevisionId: String, platform: ClientPlatform) {
        items.remove(RedisKeys.spec(specRevisionId, platform))
    }

    /**
     * Esvazia integralmente o cache de specs.
     *
     * ### 1. O que faz
     * Zera o mapa concorrente.
     *
     * ### 2. Para que serve
     * Suporte ao isolamento de estado entre baterias de testes.
     *
     * ### 3. Como funciona
     * Invoca `items.clear()`.
     */
    fun clear() = items.clear()
}

/**
 * Armazenamento em memoria para projecoes de secoes com varredura e poda ativas (`ADR-021`).
 *
 * ### 1. O que faz
 * Mantem propriedades dinamicas e atributos projetados de secoes com controle de expiracao temporal.
 *
 * ### 2. Para que serve
 * Fornece dados transientes para o pipeline de hidratacao de componentes com garantias de liberacao de memoria.
 *
 * ### 3. Como funciona
 * A cada leitura ou gravacao, verifica se o intervalo de varredura ([sweepIntervalMs]) transcorreu:
 * se expirado, remove entradas cujo prazo expirou. Se o numero de entradas ultrapassar [maxEntries],
 * executa [pruneToHalf], descartando as vencidas e as que vencem mais cedo ate sobrar metade da capacidade maxima.
 *
 * @property maxEntries Limite maximo aproximado de projecoes residentes no heap (padrao: 10.000).
 * @property sweepIntervalMs Intervalo em milissegundos entre varreduras ativas de expiradas (padrao: 60s).
 * @property clockMs Provedor de timestamp em milissegundos para medicao de prazos.
 */
class InMemoryProjectionStore(
    private val maxEntries: Int = 10_000,
    private val sweepIntervalMs: Long = 60_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ProjectionStore {
    private data class Entry(val props: Map<String, Any?>, val expiresAt: Long)

    private val items = ConcurrentHashMap<String, Entry>()
    private val pruning = AtomicBoolean(false)
    private val lastSweep = AtomicLong(0)

    /**
     * Recupera as propriedades projetadas para uma secao.
     *
     * ### 1. O que faz
     * Busca os atributos parciais de hidratacao pelo nome da projecao e identificador.
     *
     * ### 2. Para que serve
     * Atende ao enriquecimento dinamico de secoes durante a hidratacao.
     *
     * ### 3. Como funciona
     * Dispara varredura periodica se devido, consulta a chave e valida o prazo de expiracao;
     * se vencida, remove a entrada e devolve `null`.
     */
    override fun get(projection: String, id: String): Map<String, Any?>? {
        val now = clockMs()
        sweepIfDue(now)
        val key = RedisKeys.section(projection, id)
        val entry = items[key] ?: return null
        if (entry.expiresAt < now) {
            items.remove(key, entry)
            return null
        }
        return entry.props
    }

    /**
     * Grava propriedades projetadas para uma secao com tempo de vida definido.
     *
     * ### 1. O que faz
     * Salva o mapa de propriedades em heap carimbado com o timestamp de expiracao.
     *
     * ### 2. Para que serve
     * Disponibiliza os dados hidratados para consultas posteriores dentro da janela de TTL.
     *
     * ### 3. Como funciona
     * Dispara varredura, executa poda preventiva se o teto de [maxEntries] for atingido e insere a entrada.
     */
    override fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration) {
        val now = clockMs()
        sweepIfDue(now)
        if (items.size >= maxEntries) pruneToHalf()
        items[RedisKeys.section(projection, id)] = Entry(props, now + ttl.toMillis())
    }

    private fun sweepIfDue(now: Long) {
        val last = lastSweep.get()
        if (now - last < sweepIntervalMs) return
        if (!lastSweep.compareAndSet(last, now)) return
        items.entries.removeIf { it.value.expiresAt < now }
    }

    /**
     * Descarta as entradas vencidas e, se ainda assim faltar folga, as que vencem primeiro, ate
     * sobrar metade do teto. Uma unica thread poda por vez. O teto que resulta e aproximado: serve
     * a projecoes, cujo volume e limitado pelo servidor, e nao a caches chaveados por header.
     */
    private fun pruneToHalf() {
        if (!pruning.compareAndSet(false, true)) return
        try {
            val now = clockMs()
            items.entries.removeIf { it.value.expiresAt < now }
            val excess = items.size - maxEntries / 2
            if (excess > 0) {
                items.entries
                    .sortedBy { it.value.expiresAt }
                    .take(excess)
                    .forEach { items.remove(it.key, it.value) }
            }
        } finally {
            pruning.set(false)
        }
    }

    /**
     * Quantidade de projecoes atualmente residentes no store.
     *
     * ### 1. O que faz
     * Informa o total de itens mantidos em memoria.
     *
     * ### 2. Para que serve
     * Diagnostico operacional e validacao de testes de poda.
     *
     * ### 3. Como funciona
     * Retorna `items.size`.
     */
    fun residentEntries(): Int = items.size

    /**
     * Remove todas as projecoes armazenadas.
     *
     * ### 1. O que faz
     * Zera o mapa concorrente.
     *
     * ### 2. Para que serve
     * Reset de estado entre baterias de testes.
     *
     * ### 3. Como funciona
     * Invoca `items.clear()`.
     */
    fun clear() = items.clear()
}

/**
 * Coordenador de composicao exclusiva (Singleflight) em memoria (`ADR-006`).
 *
 * ### 1. O que faz
 * Deduplica requisicoes concorrentes simultaneas para a mesma chave de tela no ambito da instancia local.
 *
 * ### 2. Para que serve
 * Evita o efeito manada (thundering herd / stampede) sob miss de cache, garantindo que apenas a primeira
 * requisicao execute o processamento pesado enquanto as demais aguardam o resultado compartilhado.
 *
 * ### 3. Como funciona
 * Mantem um [ConcurrentHashMap] de instâncias [CompletableFuture] em execucao:
 * - A primeira requisicao a chegar registra o futuro via `putIfAbsent` e assume o papel de lider ([SingleflightOutcome.Leader]).
 * - Requisicoes subsequentes para a mesma chave aguardam o lider como waiters ([SingleflightOutcome.Waiter]).
 * - Se um waiter atingir o prazo maximo de espera ([timeout]), retorna [SingleflightOutcome.WaitTimeout]
 *   **sem cancelar a computacao do lider** (regra inegociavel pos-review: cancelar o lider penalizaria
 *   indevidamente todas as outras requisicoes irmas que continuam aguardando).
 */
class InMemoryComposeSingleflight : ComposeSingleflight {
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    /**
     * Executa a computacao de forma exclusiva ou aguarda o resultado em andamento.
     *
     * ### 1. O que faz
     * Coordena o acesso concorrente a computacao identificada pela chave.
     *
     * ### 2. Para que serve
     * Assegura que montagens identicas de telas nao sejam computadas em paralelo na mesma maquina.
     *
     * ### 3. Como funciona
     * Insere um futuro no mapa via `putIfAbsent`. Se lider, invoca `compute()`, completa o futuro e
     * remove a chave no `finally`. Se waiter, aguarda via `existing.get(timeout)` e trata estouro de prazo.
     *
     * @param key Chave unica de composicao da tela.
     * @param timeout Prazo maximo de espera do waiter pelo lider.
     * @param compute Bloco de computacao a ser executado caso seja o lider.
     * @return [SingleflightOutcome] indicando se a chamada foi lider, waiter ou sofreu timeout de espera.
     */
    override fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T> {
        val created = CompletableFuture<Any?>()
        val existing = inflight.putIfAbsent(key, created)
        if (existing == null) {
            try {
                val value = compute()
                created.complete(value)
                return SingleflightOutcome.Leader(value)
            } catch (error: Throwable) {
                created.completeExceptionally(error)
                throw error
            } finally {
                inflight.remove(key, created)
            }
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val value = existing.get(timeout.toMillis(), TimeUnit.MILLISECONDS) as T
            SingleflightOutcome.Waiter(value)
        } catch (_: TimeoutException) {
            SingleflightOutcome.WaitTimeout
        } catch (ex: ExecutionException) {
            val cause = ex.cause ?: ex
            if (cause is Exception) throw cause else throw RuntimeException(cause)
        }
    }
}
