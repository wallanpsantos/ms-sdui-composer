package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Coordenador central de estado transacional e autoridade de governanca em memoria (`ADR-022`).
 *
 * ### 1. O que faz
 * Centraliza e coordena todo o grafo de entidades de governanca (specs, skeletons, catalogo, ponteiros,
 * pedidos de publicacao, diffs, auditoria, outbox e reservas de idempotencia) em memoria RAM.
 *
 * ### 2. Para que serve
 * Fornece uma autoridade transacional ACID completa para desenvolvimento, testes e operacao em
 * instancia unica sem necessidade de banco de dados MongoDB externo, garantindo que operacoes
 * administrativas com multiplas gravacoes sejam publicadas ou descartadas atomicamente.
 *
 * ### 3. Como funciona
 * Mantem um snapshot imutavel de estado commitado ([State]).
 * - Leitores acessam o snapshot commitado sem locks, garantindo latencia previsivel no hot path.
 * - Escritores administrativos disputam um lock reentrante exclusivo ([writers]) para evitar condicoes de corrida.
 * - Transacoes ([transaction]) clonam o estado para um `ThreadLocal`: durante a transacao, a thread
 *   opera sobre seu rascunho privado. Em caso de sucesso, o rascunho e promovido atomicamente a [committed];
 *   sob falha ou excecao, o rascunho e descartado integralmente sem deixar efeitos colaterais residuais.
 */
class InMemoryGovernance {

    /**
     * Representacao interna de uma reserva temporaria de idempotencia.
     *
     * ### 1. O que faz
     * Encapsula o registro de idempotencia, o token exclusivo de reserva e o prazo de expiracao.
     *
     * ### 2. Para que serve
     * Protege operacoes administrativas mutantes em voo contra chamadas concorrentes duplicadas.
     *
     * ### 3. Como funciona
     * Armazenada temporariamente no mapa de reservas ate ser completada ou liberada.
     */
    internal data class Reservation(val record: IdempotencyRecord, val token: String, val expiresAt: Instant)

    /**
     * Snapshot imutavel contendo a totalidade do estado da governanca.
     *
     * ### 1. O que faz
     * Agrupa mapas imutaveis para cada entidade gerenciada pelo servico.
     *
     * ### 2. Para que serve
     * Viabiliza trocas atomicas de estado e leituras consistentes sem bloqueios concorrentes.
     *
     * ### 3. Como funciona
     * Utiliza colecoes imutaveis atualizadas via tecnicas de copy-on-write durante mutacoes.
     */
    internal data class State(
        val specs: Map<String, Spec> = emptyMap(),
        val byRevisionId: Map<String, Spec> = emptyMap(),
        val published: Map<String, List<Spec>> = emptyMap(),
        val skeletons: Map<String, Skeleton> = emptyMap(),
        val catalog: Catalog = Catalog(emptyList()),
        val pointers: Map<String, Pointer> = emptyMap(),
        val requests: Map<String, PublishRequest> = emptyMap(),
        val diffs: Map<String, SpecDiff> = emptyMap(),
        val audit: List<AuditEvent> = emptyList(),
        val reservations: Map<String, Reservation> = emptyMap(),
        val outbox: Map<String, CacheInvalidation> = emptyMap(),
    )

    @Volatile
    private var committed = State()
    private val pending = ThreadLocal<State>()
    private val writers = ReentrantLock()

    /**
     * Executa uma consulta sobre o snapshot de estado visivel para a thread corrente.
     *
     * ### 1. O que faz
     * Fornece acesso de leitura imutavel ao estado de governanca.
     *
     * ### 2. Para que serve
     * Permite que leitores consultem dados consistentes sem disputar locks com escritores.
     *
     * ### 3. Como funciona
     * Retorna o snapshot privado do `ThreadLocal` se houver transacao ativa na thread; caso contrario,
     * retorna o snapshot publico commitado ([committed]).
     */
    internal fun <T> read(block: (State) -> T): T = block(pending.get() ?: committed)

    /**
     * Aplica uma alteracao ao estado sob o lock exclusivo de escrita.
     *
     * ### 1. O que faz
     * Executa uma transformacao funcional sobre o estado de governanca.
     *
     * ### 2. Para que serve
     * Garante linearizabilidade e consistencia estrita em mutacoes isoladas ou transacionais.
     *
     * ### 3. Como funciona
     * Adquire o [writers] lock. Se houver transacao na thread, atualiza o `ThreadLocal`; senao,
     * atualiza diretamente o snapshot [committed].
     */
    internal fun update(block: (State) -> State) = writers.withLock {
        val local = pending.get()
        if (local == null) committed = block(committed) else pending.set(block(local))
    }

    /**
     * Executa uma unidade de trabalho com garantias transacionais atomicas.
     *
     * ### 1. O que faz
     * Delimita uma transacao em memoria envolvendo mutacoes em multiplos stores.
     *
     * ### 2. Para que serve
     * Assegura atomicidade total: se qualquer etapa falhar, nenhuma alteracao e promovida para o estado visivel.
     *
     * ### 3. Como funciona
     * Cria um snapshot isolado no `ThreadLocal` sob lock; executa o bloco; se bem-sucedido, promove
     * o snapshot final para [committed]. O `ThreadLocal` e sempre limpo no bloco `finally`.
     */
    internal fun <T : Any> transaction(work: () -> T): T = writers.withLock {
        if (pending.get() != null) return work()
        pending.set(committed)
        try {
            val result = work()
            committed = checkNotNull(pending.get())
            result
        } finally {
            pending.remove()
        }
    }
}

/**
 * Unidade de trabalho transacional para persistencia em memoria RAM.
 *
 * ### 1. O que faz
 * Implementa o contrato [TransactionalUnitOfWork] delegando para o coordenador de memoria.
 *
 * ### 2. Para que serve
 * Permite que servicos de orquestracao executem operacoes atomicas sem conhecer a infraestrutura subjacente.
 *
 * ### 3. Como funciona
 * Delega a execucao do bloco recebido para [InMemoryGovernance.transaction].
 */
class InMemoryTransactionalUnitOfWork(private val governance: InMemoryGovernance) : TransactionalUnitOfWork {
    /**
     * Executa o bloco de trabalho dentro do escopo transacional de memoria.
     *
     * ### 1. O que faz
     * Coordena o ciclo de vida da transacao em memoria.
     *
     * ### 2. Para que serve
     * Garante o commit conjunto ou rollback de todas as mutacoes executadas no bloco.
     *
     * ### 3. Como funciona
     * Invoca `governance.transaction(work)`.
     */
    override fun <T : Any> execute(work: () -> T): T = governance.transaction(work)
}

/**
 * Repositorio de especificacoes de telas (specs) em memoria RAM (`ADR-022`).
 *
 * ### 1. O que faz
 * Armazena e recupera especificacoes completas de telas ([Spec]), controlando revisoes,
 * unicidade de identificadores e imutabilidade de versoes publicadas.
 *
 * ### 2. Para que serve
 * Fornece a persistencia de specs para ambientes locais e testes, garantindo que rascunhos possam
 * ser iterados e que revisoes ativas nao possam ser alteradas apos publicadas.
 *
 * ### 3. Como funciona
 * Opera sobre o coordenador [InMemoryGovernance], mantendo mapas de lookup rapido por chave composta
 * `specId#revision`, por [Spec.specRevisionId] e por surface/plataforma.
 * Rejeita com [StoreConflict] qualquer tentativa de sobrescrever uma spec que ja esteja em status [SpecStatus.PUBLISHED].
 *
 * @property governance Coordenador central de transacoes e snapshots em memoria.
 */
class InMemorySpecStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : SpecStore {

    /**
     * Salva uma especificacao incondicionalmente no estado de governanca.
     *
     * ### 1. O que faz
     * Insere ou atualiza o spec no mapa de especificacoes.
     *
     * ### 2. Para que serve
     * Persiste rascunhos recem-criados ou migrados.
     *
     * ### 3. Como funciona
     * Delega para [write] com `conditional = false`.
     */
    override fun save(spec: Spec): Spec = write(spec, null, conditional = false)

    /**
     * Atualiza uma especificacao condicionalmente (compare-and-set).
     *
     * ### 1. O que faz
     * Valida que o spec corrente corresponde exatamente ao esperado antes de aplicar a nova versao.
     *
     * ### 2. Para que serve
     * Evita condicoes de corrida e edicoes simultaneas sobre o mesmo rascunho de tela.
     *
     * ### 3. Como funciona
     * Lanca [StoreConflict] caso o rascunho tenha sido alterado desde a ultima leitura.
     */
    override fun compareAndSet(expected: Spec?, updated: Spec): Spec = write(updated, expected, conditional = true)

    private fun write(spec: Spec, expected: Spec?, conditional: Boolean): Spec {
        val key = "${spec.specId}#${spec.revision}"
        governance.update { state ->
            val current = state.specs[key]
            if (conditional && current != expected) throw StoreConflict("rascunho mudou desde a leitura")
            if (current?.status == SpecStatus.PUBLISHED) throw StoreConflict("spec PUBLISHED e imutavel: $key")
            val owner = state.byRevisionId[spec.specRevisionId]
            if (owner != null && (owner.specId != spec.specId || owner.revision != spec.revision)) {
                throw StoreConflict("specRevisionId ja usado")
            }
            val group = group(spec.surface, spec.platform)
            state.copy(
                specs = state.specs + (key to spec),
                byRevisionId = (state.byRevisionId - (current?.specRevisionId ?: spec.specRevisionId)) +
                        (spec.specRevisionId to spec),
                published = if (spec.status == SpecStatus.PUBLISHED) {
                    state.published + (group to (state.published[group].orEmpty() + spec))
                } else state.published,
            )
        }
        return spec
    }

    /**
     * Recupera um spec pelo identificador unico de revisao.
     *
     * ### 1. O que faz
     * Busca a especificacao no indice secundario por [Spec.specRevisionId].
     *
     * ### 2. Para que serve
     * Atende ao estagio de Select do pipeline SDUI apos a resolucao do ponteiro de tela.
     *
     * ### 3. Como funciona
     * Executa leitura sem lock sobre o mapa `byRevisionId`.
     */
    override fun findByRevisionId(specRevisionId: String): Spec? = governance.read { it.byRevisionId[specRevisionId] }

    /**
     * Recupera um spec pelo par de identificador logico e numero de revisao.
     *
     * ### 1. O que faz
     * Busca no mapa principal pela chave `specId#revision`.
     *
     * ### 2. Para que serve
     * Consulta versoes historicas ou rascunhos especificos pelo console administrativo.
     *
     * ### 3. Como funciona
     * Consulta a chave formatada no snapshot de estado.
     */
    override fun findBySpecIdAndRevision(specId: String, revision: Int): Spec? =
        governance.read { it.specs["$specId#$revision"] }

    /**
     * Lista todas as revisoes disponiveis para um determinado specId.
     *
     * ### 1. O que faz
     * Filtra e ordena todas as revisoes cadastradas para o identificador.
     *
     * ### 2. Para que serve
     * Fornece o historico completo de alteracoes para auditoria e rollback.
     *
     * ### 3. Como funciona
     * Varre as entidades filtrando por `specId` e ordenando crescentemente por `revision`.
     */
    override fun listBySpecId(specId: String): List<Spec> = governance.read { state ->
        state.specs.values.filter { it.specId == specId }.sortedBy { it.revision }
    }

    /**
     * Lista todos os specs atualmente publicados para uma surface e plataforma.
     *
     * ### 1. O que faz
     * Recupera as especificacoes ativas registradas no indice de publicadas.
     *
     * ### 2. Para que serve
     * Suporta a resolucao de targeting e selecao de versoes para o cliente nativo.
     *
     * ### 3. Como funciona
     * Consulta diretamente a entrada agrupada por `surface|platform`.
     */
    override fun listPublished(surface: String, platform: ClientPlatform): List<Spec> =
        governance.read { it.published[group(surface, platform)].orEmpty() }

    /**
     * Lista specs filtrando opcionalmente por plataforma e canal.
     *
     * ### 1. O que faz
     * Filtra os specs cadastrados conforme os criterios informados.
     *
     * ### 2. Para que serve
     * Atende a consultas de listagem do painel administrativo.
     *
     * ### 3. Como funciona
     * Itera sobre os valores do snapshot aplicando os predicados opcionais.
     */
    override fun list(platform: ClientPlatform?, channel: Channel?): List<Spec> = governance.read { state ->
        state.specs.values.filter { (platform == null || it.platform == platform) && (channel == null || it.channel == channel) }
    }

    /**
     * Calcula o proximo numero sequencial de revisao para um specId.
     *
     * ### 1. O que faz
     * Incrementa em uma unidade a maior revisao conhecida para a especificacao.
     *
     * ### 2. Para que serve
     * Gera o identificador sequencial para criacao de novos rascunhos.
     *
     * ### 3. Como funciona
     * Obtem o maior numero de revisao existente (ou 0 se nenhum) e soma 1.
     */
    override fun nextRevision(specId: String): Int {
        val last = listBySpecId(specId).lastOrNull()?.revision ?: 0
        if (last == Int.MAX_VALUE) throw StoreConflict("limite de revisoes atingido")
        return last + 1
    }

    /**
     * Limpa todas as especificacoes cadastradas no estado em memoria.
     *
     * ### 1. O que faz
     * Zera os mapas de specs, indices e publicacoes.
     *
     * ### 2. Para que serve
     * Reset de estado para isolamento de suites de teste.
     *
     * ### 3. Como funciona
     * Atualiza o snapshot com mapas vazios.
     */
    fun clear() = governance.update { it.copy(specs = emptyMap(), byRevisionId = emptyMap(), published = emptyMap()) }
    private fun group(surface: String, platform: ClientPlatform): String = "$surface|${platform.wire()}"
}

/**
 * Repositorio de esqueletos estruturais de telas (skeletons) em memoria RAM (`ADR-022`).
 *
 * ### 1. O que faz
 * Armazena e gerencia os templates estruturais de tela ([Skeleton]), slots e restricoes de secoes.
 *
 * ### 2. Para que serve
 * Garante que a composicao de telas respeite a hierarquia de slots obrigatorios e opcionais da surface.
 *
 * ### 3. Como funciona
 * Indexa skeletons por `skeletonId#revision` no mapa compartilhado de [InMemoryGovernance].
 * Rejeita qualquer modificacao condicional se o skeleton ja estiver publicado.
 *
 * @property governance Coordenador central de estado em memoria.
 */
class InMemorySkeletonStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : SkeletonStore {

    /**
     * Salva um skeleton incondicionalmente no armazenamento em memoria.
     *
     * ### 1. O que faz
     * Grava o esqueleto no estado da governanca.
     *
     * ### 2. Para que serve
     * Permite persistir novos skeletons e configuracoes estruturais de surfaces.
     *
     * ### 3. Como funciona
     * Delega para [write] com `conditional = false`.
     */
    override fun save(skeleton: Skeleton): Skeleton = write(skeleton, null, conditional = false)

    /**
     * Atualiza um skeleton condicionalmente validando o valor esperado.
     *
     * ### 1. O que faz
     * Aplica alteracoes estruturais garantindo que o skeleton nao foi modificado concorrentemente.
     *
     * ### 2. Para que serve
     * Protege rascunhos de skeletons contra sobrescritas acidentais no painel administrativo.
     *
     * ### 3. Como funciona
     * Lanca [StoreConflict] se o valor atual divergir do esperado ou se ja estiver publicado.
     */
    override fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton =
        write(updated, expected, conditional = true)

    private fun write(skeleton: Skeleton, expected: Skeleton?, conditional: Boolean): Skeleton {
        val key = "${skeleton.skeletonId}#${skeleton.revision}"
        governance.update { state ->
            val current = state.skeletons[key]
            if (conditional && current != expected) throw StoreConflict("skeleton mudou desde a leitura")
            if (current?.status == SpecStatus.PUBLISHED) throw StoreConflict("skeleton PUBLISHED e imutavel: $key")
            state.copy(skeletons = state.skeletons + (key to skeleton))
        }
        return skeleton
    }

    /**
     * Localiza um skeleton pelo identificador e numero de revisao opcional.
     *
     * ### 1. O que faz
     * Recupera o esqueleto solicitado ou a revisao mais recente se nao especificada.
     *
     * ### 2. Para que serve
     * Suporta a validacao e composicao de telas no pipeline SDUI.
     *
     * ### 3. Como funciona
     * Se [revision] for nulo, invoca [current]; senao, busca pela chave `skeletonId#revision`.
     */
    override fun find(skeletonId: String, revision: Int?): Skeleton? =
        if (revision == null) current(skeletonId) else governance.read { it.skeletons["$skeletonId#$revision"] }

    /**
     * Retorna a versao mais recente (maior revisao) para o skeletonId informado.
     *
     * ### 1. O que faz
     * Encontra a revisao corrente do esqueleto estrutural.
     *
     * ### 2. Para que serve
     * Utilizada como referencia padrao na confeccao de novos rascunhos de specs.
     *
     * ### 3. Como funciona
     * Filtra pelo `skeletonId` e calcula o maximo pela propriedade `revision`.
     */
    override fun current(skeletonId: String): Skeleton? = governance.read { state ->
        state.skeletons.values.filter { it.skeletonId == skeletonId }.maxByOrNull { it.revision }
    }
}

/**
 * Repositorio em memoria para o catalogo oficial de componentes e schemas (`ADR-022`).
 *
 * ### 1. O que faz
 * Mantem a definicao do catalogo de componentes suportados ([Catalog]) no estado da governanca.
 *
 * ### 2. Para que serve
 * Serve como autoridade para validacao de compatibilidade e homologacao de novos tipos de secoes.
 *
 * ### 3. Como funciona
 * Persiste o snapshot unico de [Catalog] no estado gerenciado por [InMemoryGovernance].
 *
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryCatalogStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : CatalogStore {

    /**
     * Atualiza o catalogo oficial de componentes em memoria.
     *
     * ### 1. O que faz
     * Grava o novo snapshot do catalogo.
     *
     * ### 2. Para que serve
     * Permite incluir novos componentes aprovados na governanca.
     *
     * ### 3. Como funciona
     * Executa `update` substituindo a propriedade `catalog` do snapshot.
     */
    override fun save(catalog: Catalog): Catalog {
        governance.update { it.copy(catalog = catalog) }
        return catalog
    }

    /**
     * Retorna o catalogo de componentes atualmente em vigor.
     *
     * ### 1. O que faz
     * Recupera o snapshot do catalogo de componentes.
     *
     * ### 2. Para que serve
     * Atende a consultas do pipeline e do console administrativo.
     *
     * ### 3. Como funciona
     * Retorna `state.catalog` atraves de leitura sem lock.
     */
    override fun current(): Catalog = governance.read { it.catalog }
}

/**
 * Repositorio em memoria para ponteiros de ativacao de telas (`ADR-022`).
 *
 * ### 1. O que faz
 * Mapeia e gerencia os ponteiros de producao ([Pointer]) que indicam qual revisao de spec esta ativa
 * para uma surface, plataforma e canal especificos.
 *
 * ### 2. Para que serve
 * Determina dinamicamente a versao servida aos aplicativos moveis e viabiliza rollbacks instantaneos.
 *
 * ### 3. Como funciona
 * Indexa ponteiros por chaves compostas `surface:platform:channel`. Suporta compare-and-set atomico
 * baseado na versao numerica monotônica do ponteiro para evitar condicoes de corrida em publicacoes simultaneas.
 *
 * @property governance Coordenador central de transacoes em memoria.
 */
class InMemoryPointerStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : PointerStore {

    /**
     * Busca o ponteiro ativo para uma combinacao de surface, plataforma e canal.
     *
     * ### 1. O que faz
     * Localiza o registro do ponteiro no mapa de estado.
     *
     * ### 2. Para que serve
     * Etapa inicial de selecao (Select) do pipeline de entrega SDUI.
     *
     * ### 3. Como funciona
     * Consulta a chave composta `surface:platform:channel` sem bloqueios.
     */
    override fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer? =
        governance.read { it.pointers[key(surface, platform, channel)] }

    /**
     * Salva um ponteiro incondicionalmente.
     *
     * ### 1. O que faz
     * Atualiza a referencia do ponteiro no estado da governanca.
     *
     * ### 2. Para que serve
     * Utilizado na carga inicial de seeds ou operacoes administrativas forçadas.
     *
     * ### 3. Como funciona
     * Sobrescreve a entrada no mapa de ponteiros sob lock de escrita.
     */
    override fun save(pointer: Pointer): Pointer {
        governance.update {
            it.copy(
                pointers = it.pointers + (key(
                    pointer.surface,
                    pointer.platform,
                    pointer.channel
                ) to pointer)
            )
        }
        return pointer
    }

    /**
     * Atualiza um ponteiro atomicamente validando a versao esperada (compare-and-set).
     *
     * ### 1. O que faz
     * Promove a nova versao do ponteiro caso a versao corrente coincida com [expectedVersion].
     *
     * ### 2. Para que serve
     * Impede que duas aprovacoes concorrentes de specs sobreponham uma a outra sem deteccao.
     *
     * ### 3. Como funciona
     * Compara a versao atual com [expectedVersion]; lanca [StoreConflict] sob divergencia.
     */
    override fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer {
        val key = key(updated.surface, updated.platform, updated.channel)
        governance.update {
            if (it.pointers[key]?.version != expectedVersion) throw StoreConflict("pointer mudou desde a leitura")
            it.copy(pointers = it.pointers + (key to updated))
        }
        return updated
    }

    private fun key(surface: String, platform: ClientPlatform, channel: Channel): String =
        "$surface:${platform.wire()}:${channel.wire()}"
}

/**
 * Repositorio em memoria para solicitacoes de publicacao maker-checker (`ADR-022`).
 *
 * ### 1. O que faz
 * Registra e rastreia o ciclo de vida dos pedidos de publicacao de telas ([PublishRequest]).
 *
 * ### 2. Para que serve
 * Assegura a governanca segregada onde autores submetem pedidos e revisores independentes aprovam ou rejeitam.
 *
 * ### 3. Como funciona
 * Mantem solicitacoes indexadas por `requestId`. Suporta operacoes atomicas de transicao de status
 * com compare-and-set ([compareAndSetStatus]) garantindo que pedidos cancelados ou concorrentes nao colidam.
 *
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryPublishRequestStore(private val governance: InMemoryGovernance = InMemoryGovernance()) :
    PublishRequestStore {

    /**
     * Salva uma solicitacao de publicacao.
     *
     * ### 1. O que faz
     * Grava ou atualiza a solicitacao no estado em memoria.
     *
     * ### 2. Para que serve
     * Registra o pedido submetido pelo autor para avaliacao do checker.
     *
     * ### 3. Como funciona
     * Insere a entidade no mapa `requests` indexado por `requestId`.
     */
    override fun save(request: PublishRequest): PublishRequest {
        governance.update { it.copy(requests = it.requests + (request.requestId to request)) }
        return request
    }

    /**
     * Busca uma solicitacao de publicacao pelo identificador unico.
     *
     * ### 1. O que faz
     * Localiza a solicitacao de publicacao.
     *
     * ### 2. Para que serve
     * Permite consultar o status, autor, revisor e timestamps do pedido de publicacao.
     *
     * ### 3. Como funciona
     * Faz lookup direto no mapa de requisicoes sem lock.
     */
    override fun find(requestId: String): PublishRequest? = governance.read { it.requests[requestId] }

    /**
     * Altera atomicamente o status de uma solicitacao validando o status anterior esperado.
     *
     * ### 1. O que faz
     * Executa a transicao de estado (ex.: `PENDING` -> `APPROVED` ou `REJECTED`).
     *
     * ### 2. Para que serve
     * Impede que um mesmo pedido seja aprovado ou rejeitado concorrentemente por multiplos revisores.
     *
     * ### 3. Como funciona
     * Verifica se o status atual no mapa coincide com [expected]; se sim, atualiza e retorna o objeto atualizado,
     * senao retorna `null` indicando conflito de estado.
     */
    override fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest
    ): PublishRequest? {
        var result: PublishRequest? = null
        governance.update {
            if (it.requests[requestId]?.status != expected) it else {
                result = updated
                it.copy(requests = it.requests + (requestId to updated))
            }
        }
        return result
    }
}

/**
 * Repositorio em memoria para diferencas estruturais (diffs) entre revisoes (`ADR-022`).
 *
 * ### 1. O que faz
 * Armazena deltas calculados ([SpecDiff]) entre versoes de especificacoes.
 *
 * ### 2. Para que serve
 * Permite visualizar exatamente quais secoes, slots ou propriedades foram alterados entre revisoes.
 *
 * ### 3. Como funciona
 * Indexa os deltas pela triade `specId:fromRevision:toRevision` no estado compartilhado de [InMemoryGovernance].
 *
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryDiffStore(private val governance: InMemoryGovernance = InMemoryGovernance()) : DiffStore {

    /**
     * Salva um diff estrutural de especificacao.
     *
     * ### 1. O que faz
     * Grava o delta calculado no mapa de diffs.
     *
     * ### 2. Para que serve
     * Disponibiliza a diferenca calculada para exibicao no console administrativo.
     *
     * ### 3. Como funciona
     * Insere o registro indexado pela combinacao de `specId`, revisao de origem e revisao de destino.
     */
    override fun save(diff: SpecDiff): SpecDiff {
        governance.update { it.copy(diffs = it.diffs + ("${diff.specId}:${diff.fromRevision ?: 0}:${diff.toRevision}" to diff)) }
        return diff
    }

    /**
     * Busca a diferenca calculada entre duas revisoes de um specId.
     *
     * ### 1. O que faz
     * Localiza o registro de diferenca estrutural previamente armazenado.
     *
     * ### 2. Para que serve
     * Evita recalculo repetido de diffs para as mesmas revisoes historicas.
     *
     * ### 3. Como funciona
     * Realiza lookup pela chave composta no snapshot imutavel.
     */
    override fun find(specId: String, from: Int, to: Int): SpecDiff? = governance.read { it.diffs["$specId:$from:$to"] }
}

/**
 * Armazenamento append-only em memoria para a trilha de auditoria administrativa (`ADR-022`).
 *
 * ### 1. O que faz
 * Registra eventos cronologicos de auditoria ([AuditEvent]) em uma lista FIFO com janela de retencao finita.
 *
 * ### 2. Para que serve
 * Assegura rastreabilidade e prestacao de contas de todas as acoes administrativas da governanca (quem fez o que, quando e por que).
 *
 * ### 3. Como funciona
 * Limita a quantidade total de eventos residentes a [maxEvents] (padrao 2.000). A cada novo evento anexado,
 * descarta os eventos excedentes mais antigos no topo da fila, consumindo aproximadamente 250 ns por operacao.
 *
 * @property maxEvents Quantidade maxima de eventos mantidos na fila em memoria (padrao: 2.000).
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryAuditLogStore(
    private val maxEvents: Int = 2_000,
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : AuditLogStore {
    init {
        require(maxEvents > 0)
    }

    /**
     * Anexa um novo evento a trilha de auditoria.
     *
     * ### 1. O que faz
     * Adiciona o evento ao final da lista de auditoria respeitando o limite maximo de retencao.
     *
     * ### 2. Para que serve
     * Registra acoes de criacao, aprovacao, rejeicao e rollback de specs de forma imutavel.
     *
     * ### 3. Como funciona
     * Executa `update` aplicando `takeLast(maxEvents - 1) + event`.
     */
    override fun append(event: AuditEvent) =
        governance.update { it.copy(audit = it.audit.takeLast(maxEvents - 1) + event) }

    /**
     * Lista todos os eventos de auditoria retidos na fila em ordem cronologica.
     *
     * ### 1. O que faz
     * Retorna a sequencia completa de eventos em memoria.
     *
     * ### 2. Para que serve
     * Permite exportar ou analisar o historico recente de governanca.
     *
     * ### 3. Como funciona
     * Retorna uma copia imutavel da lista de eventos atraves de leitura sem lock.
     */
    override fun list(): List<AuditEvent> = governance.read { it.audit.toList() }

    /**
     * Retorna os eventos mais recentes ate o limite informado, em ordem cronologica inversa.
     *
     * ### 1. O que faz
     * Fornece os ultimos eventos ocorridos com os mais novos primeiro.
     *
     * ### 2. Para que serve
     * Atende a paginacao e visualizacao inicial da timeline de auditoria no painel administrativo.
     *
     * ### 3. Como funciona
     * Aplica `takeLast(limit).asReversed()` sobre a lista de eventos retida.
     */
    override fun recent(limit: Int): List<AuditEvent> = governance.read { it.audit.takeLast(limit).asReversed() }
}

/**
 * Repositorio em memoria para controle estrito de idempotencia administrativa (`ADR-022`).
 *
 * ### 1. O que faz
 * Garante a semantica exatamente-uma-vez (exactly-once) para requisicoes administrativas mutantes
 * baseadas no cabecalho `Idempotency-Key`.
 *
 * ### 2. Para que serve
 * Impede a duplicacao acidental de criacoes de rascunhos, aprovacoes ou rollbacks provocadas por
 * retries de rede ou cliques repetidos de operadores.
 *
 * ### 3. Como funciona
 * Opera com ciclo de vida em duas fases:
 * 1. **Reserva ([reserve]):** Tenta reservar a chave registrando um token e um timeout de reserva ([reservationTimeout]).
 *    Se ja existir registro com resultado, retorna [IdempotencyReservation.Existing]; se a capacidade ([maxEntries])
 *    for atingida, retorna [IdempotencyReservation.CapacityExhausted]; senao, retorna [IdempotencyReservation.Reserved].
 * 2. **Conclusao ([complete]):** Valida o token exclusivo gerado na reserva e promove a reserva para registro permanente
 *    com tempo de vida estendido ([ttl]).
 * 3. **Liberacao ([release]):** Se a operacao falhar antes de produzir efeitos, devolve a chave para permitir retentativas.
 *
 * @property clock Provedor temporal UTC para medicao de prazos de expiracao.
 * @property ttl Janela de retencao do registro completado (padrao: 24 horas).
 * @property maxEntries Limite maximo de chaves de idempotencia residentes em memoria (padrao: 10.000).
 * @property reservationTimeout Prazo maximo de expiracao de reservas em voo abandonadas (padrao: 5 minutos).
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryIdempotencyStore(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(24),
    private val maxEntries: Int = 10_000,
    private val reservationTimeout: Duration = Duration.ofMinutes(5),
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : IdempotencyStore {
    init {
        require(maxEntries > 0 && !ttl.isNegative && !ttl.isZero && !reservationTimeout.isNegative && !reservationTimeout.isZero)
    }

    /**
     * Consulta um registro de idempotencia pelo valor da chave.
     *
     * ### 1. O que faz
     * Busca o registro associado a chave caso ainda nao tenha expirado.
     *
     * ### 2. Para que serve
     * Permite verificar o resultado previo de uma operacao idempotente ja completada.
     *
     * ### 3. Como funciona
     * Le a entrada no mapa de reservas, conferindo se `expiresAt` e posterior ao instante atual.
     */
    override fun find(key: String): IdempotencyRecord? = governance.read {
        it.reservations[key]?.takeIf { entry -> entry.expiresAt.isAfter(clock.instant()) }?.record
    }

    /**
     * Tenta reservar atomicamente uma chave de idempotencia para execucao.
     *
     * ### 1. O que faz
     * Bloqueia temporariamente a chave contra operacoes concorrentes simultaneas.
     *
     * ### 2. Para que serve
     * Garante exclusao mutua entre chamadas paralelas com mesma chave e fingerprint.
     *
     * ### 3. Como funciona
     * Purga entradas expiradas sob demanda; se houver capacidade, aloca um token UUID e grava a reserva temporaria.
     */
    override fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation {
        var result: IdempotencyReservation = IdempotencyReservation.CapacityExhausted
        governance.update { state ->
            val now = clock.instant()
            val existing = state.reservations[key]
            if (existing != null && existing.expiresAt.isAfter(now)) {
                result = IdempotencyReservation.Existing(existing.record)
                state
            } else {
                val live = state.reservations.filterValues { it.expiresAt.isAfter(now) }
                if (live.size >= maxEntries) state.copy(reservations = live) else {
                    val token = UUID.randomUUID().toString()
                    result = IdempotencyReservation.Reserved(token)
                    state.copy(
                        reservations = live + (key to InMemoryGovernance.Reservation(
                            IdempotencyRecord(key, operation, null, fingerprint), token, now.plus(reservationTimeout),
                        ))
                    )
                }
            }
        }
        return result
    }

    /**
     * Conclui a execucao com sucesso associando o resultado a chave de idempotencia.
     *
     * ### 1. O que faz
     * Transforma a reserva em registro final completado e atualiza o prazo de expiracao para o [ttl] definitivo.
     *
     * ### 2. Para que serve
     * Permite que retries subsequentes recuperem o mesmo resultado sem reexecutar o fluxo.
     *
     * ### 3. Como funciona
     * Valida que o token confere com a reserva ativa; lanca [StoreConflict] se a reserva expirou ou foi substituida.
     */
    override fun complete(record: IdempotencyRecord, token: String) = governance.update { state ->
        val entry = state.reservations[record.key]
        val now = clock.instant()
        if (entry == null || entry.token != token || !entry.expiresAt.isAfter(now) ||
            entry.record.resultRef != null || entry.record.operation != record.operation ||
            entry.record.fingerprint != record.fingerprint
        ) throw StoreConflict("reserva de idempotencia vencida ou substituida")
        state.copy(
            reservations = state.reservations + (record.key to entry.copy(
                record = record,
                expiresAt = now.plus(ttl)
            ))
        )
    }

    /**
     * Libera uma reserva em voo quando a operacao falha sem efeito colateral.
     *
     * ### 1. O que faz
     * Remove a reserva temporaria da memoria.
     *
     * ### 2. Para que serve
     * Permite que chamadas com falha transiente possam ser retentadas imediatamente com a mesma chave.
     *
     * ### 3. Como funciona
     * Remove a chave caso o token informado confira com o token da reserva e nao haja resultado associado.
     */
    override fun release(key: String, token: String) = governance.update { state ->
        val entry = state.reservations[key]
        if (entry?.token == token && entry.record.resultRef == null) state.copy(reservations = state.reservations - key) else state
    }

    /**
     * Retorna o numero de reservas e registros atualmente residentes no store.
     *
     * ### 1. O que faz
     * Informa o total de chaves ativas no mapa em memoria.
     *
     * ### 2. Para que serve
     * Monitoramento operacional e assercoes em testes unitarios.
     *
     * ### 3. Como funciona
     * Consulta `state.reservations.size`.
     */
    fun residentEntries(): Int = governance.read { it.reservations.size }

    /**
     * Limpa integralmente todas as chaves de idempotencia da memoria.
     *
     * ### 1. O que faz
     * Zera o mapa de reservas.
     *
     * ### 2. Para que serve
     * Reset de estado entre baterias de testes automatizados.
     *
     * ### 3. Como funciona
     * Atualiza o snapshot com mapa vazio.
     */
    fun clear() = governance.update { it.copy(reservations = emptyMap()) }
}

/**
 * Fila transacional de saida (Outbox) de invalidacoes de cache em memoria (`ADR-022`).
 *
 * ### 1. O que faz
 * Registra intencoes de invalidacao de cache ([CacheInvalidation]) com garantias de consistencia transacional.
 *
 * ### 2. Para que serve
 * Assegura que falhas transientes na comunicacao com caches nao impeçam a conclusao da publicacao,
 * mantendo as pendencias registradas para posterior execucao pelo relay agendado.
 *
 * ### 3. Como funciona
 * Armazena as intencoes de invalidacao indexadas por id no snapshot de [InMemoryGovernance] sob teto
 * maximo [maxPending]. Metodos de drenagem e conclusao permitem ao relay ler pendencias e remove-las
 * conforme forem processadas com sucesso.
 *
 * @property maxPending Teto maximo de intencoes de invalidacao acumuladas (padrao: 10.000).
 * @property governance Coordenador central de estado em memoria.
 */
class InMemoryCacheInvalidationOutbox(
    private val maxPending: Int = 10_000,
    private val governance: InMemoryGovernance = InMemoryGovernance(),
) : CacheInvalidationOutbox {
    init {
        require(maxPending > 0)
    }

    /**
     * Grava uma nova intencao de invalidacao de cache no outbox.
     *
     * ### 1. O que faz
     * Enfileira o registro de invalidacao no mapa de pendencias.
     *
     * ### 2. Para que serve
     * Persiste a intencao de invalidacao atomicamente no commit da transacao de governanca.
     *
     * ### 3. Como funciona
     * Lanca [StoreConflict] caso a capacidade maxima de pendencias seja atingida; senao, adiciona ao mapa `outbox`.
     */
    override fun record(invalidation: CacheInvalidation) = governance.update {
        if (it.outbox.size >= maxPending && invalidation.id !in it.outbox) throw StoreConflict("outbox sem capacidade")
        it.copy(outbox = it.outbox + (invalidation.id to invalidation))
    }

    /**
     * Retorna a lista de invalidacoes pendentes de aplicacao ate o limite solicitado.
     *
     * ### 1. O que faz
     * Fornece um lote de intencoes de invalidacao a serem processadas pelo relay.
     *
     * ### 2. Para que serve
     * Atende a rotina agendada de drenagem do outbox.
     *
     * ### 3. Como funciona
     * Le as primeiras [limit] entradas do mapa de outbox.
     */
    override fun pending(limit: Int): List<CacheInvalidation> = governance.read { it.outbox.values.take(limit) }

    /**
     * Marca uma invalidacao como aplicada com sucesso, removendo-a do outbox.
     *
     * ### 1. O que faz
     * Exclui a intencao de invalidacao processada da fila.
     *
     * ### 2. Para que serve
     * Finaliza o ciclo de vida da invalidacao pendente no outbox.
     *
     * ### 3. Como funciona
     * Executa `update` removendo a entrada correspondente a [id] do mapa.
     */
    override fun markApplied(id: String) = governance.update { it.copy(outbox = it.outbox - id) }
}
