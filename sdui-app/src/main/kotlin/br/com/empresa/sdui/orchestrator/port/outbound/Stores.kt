package br.com.empresa.sdui.orchestrator.port.outbound

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import java.time.Duration
import java.time.Instant

/**
 * Uma escrita concorrente venceu esta: versao do pointer ou estado do documento mudou entre a
 * leitura e a gravacao. Vira 409 na borda administrativa; nunca e repetida automaticamente.
 */
class StoreConflict(message: String) : RuntimeException(message)

/**
 * O armazenamento recusou o conteudo por uma regra dele, como o teto de tamanho do documento.
 * Vira 400: o autor precisa reduzir o conteudo; reenviar o mesmo nao adianta.
 */
class StoreRejected(message: String) : RuntimeException(message)

/** Janela de uma listagem administrativa: deslocamento e tamanho, ja validados pela borda. */
data class PageRequest(val offset: Int, val limit: Int) {
    init {
        require(offset >= 0) { "offset deve ser >= 0" }
        require(limit in 1..MAX_LIMIT) { "limit deve estar entre 1 e $MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 500
        val FIRST: PageRequest = PageRequest(0, DEFAULT_LIMIT)
    }
}

/** Aplica a janela a uma lista ja ordenada. Usado pelos adapters sem paginacao nativa. */
fun <T> List<T>.page(page: PageRequest): List<T> =
    if (page.offset >= size) emptyList() else subList(page.offset, minOf(size, page.offset + page.limit)).toList()

/** Persistencia de specs. Uma revisao PUBLISHED e imutavel — a implementacao deve recusar sobrescrita. */
interface SpecStore {
    fun save(spec: Spec): Spec

    /** Cria se expected for null; caso contrario substitui somente o conteudo observado. */
    fun compareAndSet(expected: Spec?, updated: Spec): Spec
    fun findByRevisionId(specRevisionId: String): Spec?
    fun findBySpecIdAndRevision(specId: String, revision: Int): Spec?
    fun listBySpecId(specId: String): List<Spec>
    fun listPublished(surface: String, platform: ClientPlatform): List<Spec>
    fun list(platform: ClientPlatform?, channel: Channel?): List<Spec>
    fun nextRevision(specId: String): Int

    /**
     * Uma pagina da listagem administrativa, ordenada por specId e revisao. O padrao ordena a
     * lista inteira; adapters com consulta paginada nativa devem sobrescrever.
     */
    fun list(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        list(platform, channel).sortedWith(SPEC_ORDER).page(page)

    /** As revisoes de um spec em ordem crescente, paginadas. */
    fun listBySpecId(specId: String, page: PageRequest): List<Spec> =
        listBySpecId(specId).sortedBy { it.revision }.page(page)

    companion object {
        val SPEC_ORDER: Comparator<Spec> = compareBy<Spec> { it.specId }.thenBy { it.revision }
    }
}

/** Persistencia de skeletons, versionados independentemente dos specs. */
interface SkeletonStore {
    fun save(skeleton: Skeleton): Skeleton
    fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton
    fun find(skeletonId: String, revision: Int? = null): Skeleton?
    fun current(skeletonId: String): Skeleton?
}

/** Referencia versionada exige a revisao exata; rascunho corrente nunca e substituto. */
fun SkeletonStore.findFor(spec: Spec): Skeleton? =
    find(spec.skeletonId, spec.skeletonRevision)

/** Digest calculado pelo servidor do spec e skeleton apresentados ao checker. */
fun interface PublicationFingerprint {
    fun of(spec: Spec, skeleton: Skeleton): String
}

/** O catalogo de componentes vigente. Guardado inteiro, porque so faz sentido validado como conjunto. */
interface CatalogStore {
    fun save(catalog: Catalog): Catalog
    fun current(): Catalog
}

/** Qual revisao esta em vigor por surface, plataforma e canal. O estado que o rollback move. */
interface PointerStore {
    fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer?

    /** Grava sem conferir versao. Reservado ao seed e a preparacao de testes. */
    fun save(pointer: Pointer): Pointer

    /**
     * Grava [updated] somente se o pointer persistido ainda estiver em [expectedVersion] — null
     * quando nao existe pointer para a combinacao. Lanca [StoreConflict] se outra escrita chegou
     * antes: e a protecao contra duas publicacoes ou rollbacks simultaneos se atropelarem.
     */
    fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer
}

/**
 * Pedidos de publicacao. [compareAndSetStatus] existe para que dois checkers simultaneos nao
 * aprovem o mesmo pedido: so a primeira transicao a partir de OPEN vence.
 */
interface PublishRequestStore {
    fun save(request: PublishRequest): PublishRequest
    fun find(requestId: String): PublishRequest?
    fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest
    ): PublishRequest?
}

/** Diffs calculados na abertura do pedido, consultados na revisao e na auditoria. */
interface DiffStore {
    fun save(diff: SpecDiff): SpecDiff
    fun find(specId: String, from: Int, to: Int): SpecDiff?
}

/** Trilha de auditoria da governanca. So recebe acrescimos. */
interface AuditLogStore {
    fun append(event: AuditEvent)
    fun list(): List<AuditEvent>

    /** Os [limit] eventos mais recentes, do mais novo para o mais antigo. */
    fun recent(limit: Int): List<AuditEvent> = list().sortedByDescending { it.ts }.take(limit)
}

/** Resultado de tentar tomar uma chave de idempotencia. */
sealed interface IdempotencyReservation {
    /** Esta chamada tomou a chave e deve executar a operacao. */
    data class Reserved(val token: String) : IdempotencyReservation

    /** Ja ha registro vivo: em voo ([IdempotencyRecord.resultRef] nulo) ou com resultado. */
    data class Existing(val record: IdempotencyRecord) : IdempotencyReservation

    /**
     * Nao ha capacidade segura para mais uma reserva. Recusar a admissao e o que preserva as
     * reservas vivas: expulsar uma delas deixaria o retry da chave expulsa executar de novo.
     */
    data object CapacityExhausted : IdempotencyReservation
}

/**
 * Chaves de idempotencia das operacoes administrativas.
 *
 * O contrato e de reserva, nao de gravacao no fim: [reserve] toma a chave **antes** de a operacao
 * comecar e so uma chamada vence. Consultar e depois gravar seria check-then-act — duas chamadas
 * concorrentes com a mesma chave passariam as duas pela consulta e executariam o efeito duas
 * vezes, que e exatamente o que a chave existe para impedir.
 *
 * O ciclo completo e reserve -> complete (no mesmo commit do efeito) ou reserve -> release (quando
 * a operacao falha sem efeito). Sem o release, um 400 de validacao queimaria a chave para sempre e
 * o operador nao conseguiria reenviar depois de corrigir o rascunho.
 *
 * Nenhuma implementacao pode descartar registro vivo por pressao de memoria: uma reserva em voo
 * so sai por [release], por conclusao ou por vencer o prazo de reserva; um resultado, so ao
 * vencer a janela de deduplicacao.
 */
interface IdempotencyStore {
    fun find(key: String): IdempotencyRecord?

    /** Tenta tomar a chave para [operation] com os parametros resumidos em [fingerprint]. */
    fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation

    /** Fecha somente a reserva vigente deste token. Vai no mesmo commit do efeito que ela protege. */
    fun complete(record: IdempotencyRecord, token: String)

    /** Devolve somente a reserva em voo deste token; nunca remove resultado ou reserva de outro dono. */
    fun release(key: String, token: String)
}

/**
 * Cache das arvores compostas: o que faz a maioria das requisicoes terminar sem tocar nos stores.
 *
 * A chave nunca identifica usuario e inclui a revisao do spec: uma entrada so e lida por quem ja
 * selecionou aquela revisao. [invalidate] e higiene de memoria quando uma publicacao muda o que
 * aquela combinacao de surface, plataforma e canal deve servir; a correcao nao depende dele.
 */
interface HydratedScreenCache {
    fun get(treeKey: String): ComposedScreen?
    fun put(treeKey: String, screen: ComposedScreen, ttl: Duration)
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel)
}

/** Uma arvore de last good com o instante em que foi guardada. */
data class StoredScreen(val screen: ComposedScreen, val storedAt: Instant)

/**
 * Guarda a ultima arvore composta com sucesso por surface, plataforma e canal.
 *
 * O degrau da escada de fallback que evita o 503: preferimos entregar uma tela correta porem
 * defasada, marcada como fallback no envelope, a nao entregar nada.
 *
 * A leitura devolve [StoredScreen] e nao a arvore crua porque defasagem sem limite deixa de ser
 * degradacao de experiencia e vira problema de correcao: o pipeline precisa do instante para
 * recusar um last good velho demais e para publicar a idade como metrica.
 *
 * **Versao do pointer (ADR-021).** Toda arvore carrega a versao do pointer sob a qual foi
 * composta. [invalidate] grava uma lapide naquela versao, e [put] recusa arvore de versao menor
 * que a guardada: uma composicao que comecou antes da publicacao e terminou depois nao consegue
 * reintroduzir a revisao que o operador acabou de tirar do ar.
 */
interface LastGoodScreenStore {
    fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen?

    /** Grava se [ComposedScreen.pointerVersion] nao for menor que a versao guardada. */
    fun put(screen: ComposedScreen)

    /** Descarta a arvore guardada abaixo de [pointerVersion] e impede escrita atrasada abaixo dela. */
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long)
}

/**
 * Cache de specs por revisao e plataforma, mantido em dia pela publicacao e pelo rollback.
 *
 * Specs publicados sao imutaveis, entao uma entrada nunca fica incorreta — so desnecessaria. O
 * compose consulta este cache para resolver a revisao apontada pelo pointer sem listar os
 * publicados.
 */
interface SpecCache {
    fun get(specRevisionId: String, platform: ClientPlatform): Spec?
    fun put(spec: Spec)
    fun invalidate(specRevisionId: String, platform: ClientPlatform)
}

/**
 * Projecoes seguras que alimentariam a hidratacao. Nunca guarda PII nem dado bruto de dominio.
 *
 * Nenhum hidratador a consome hoje: no MVP o conteudo ja vem completo no spec e o
 * PassThroughHydrator apenas repassa as props. A porta existe para quando houver section cujo
 * conteudo venha de fora do spec.
 */
interface ProjectionStore {
    fun get(projection: String, id: String): Map<String, Any?>?
    fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration)
}

/**
 * Executa um bloco de forma atomica, sem acoplar o orchestrator a nenhum framework de transacao.
 *
 * Escrita de cache nao entra aqui: cache nao e transacional e, aplicado antes do commit,
 * publicaria estado que um rollback desfaria.
 */
interface TransactionalUnitOfWork {
    fun <T : Any> execute(work: () -> T): T
}

/**
 * Uma invalidacao de cache devida por uma mudanca de pointer ja commitada.
 *
 * [pointerVersion] e a versao do pointer depois da mudanca: a lapide do last good e gravada nela.
 */
data class CacheInvalidation(
    val id: String,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val pointerVersion: Long,
    val retiredSpecRevisionId: String?,
    val createdAt: Instant,
)

/**
 * Outbox das invalidacoes de cache (ADR-021).
 *
 * [record] vai dentro da mesma transacao que move o pointer; a aplicacao acontece depois do
 * commit. Se o processo cair entre o commit e a invalidacao, o registro continua pendente e o
 * relay o aplica depois — a invalidacao e idempotente, entao reaplicar nao causa dano.
 */
interface CacheInvalidationOutbox {
    fun record(invalidation: CacheInvalidation)
    fun pending(limit: Int): List<CacheInvalidation>
    fun markApplied(id: String)
}

/**
 * Como uma requisicao saiu do singleflight: liderou a computacao, aproveitou a de outra, ou
 * desistiu de esperar. O terceiro caso e tratado como indisponibilidade e cai no fallback.
 */
sealed interface SingleflightOutcome<out T> {
    data class Leader<T>(val value: T) : SingleflightOutcome<T>
    data class Waiter<T>(val value: T) : SingleflightOutcome<T>
    data object WaitTimeout : SingleflightOutcome<Nothing>
}

/**
 * Faz com que apenas uma requisicao componha uma chave por vez, enquanto as demais esperam.
 *
 * Protege as dependencias do pico que acontece quando uma entrada popular de cache expira e
 * varias requisicoes tentam recompo-la ao mesmo tempo. Local ao processo: com varias replicas,
 * pode haver um lider por pod.
 */
interface ComposeSingleflight {
    fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T>
}

/** Decide o canal efetivo. O cliente pede; o servidor decide. */
interface CanaryPolicy {
    fun channelFor(platform: ClientPlatform, build: String, requested: Channel): Channel
}

/**
 * Porta de metricas, que mantem o orchestrator livre de Micrometer.
 *
 * Nome de metrica e constante (ver [MetricNames]) e a dimensao vai em tag — nome interpolado
 * criaria uma serie por valor e multiplicaria a cardinalidade no registry. Toda tag tem
 * vocabulario finito: versao exata de app, identificadores e texto livre ficam no log.
 */
interface MetricsRecorder {
    fun increment(name: String, tags: Map<String, String> = emptyMap())
    fun recordTime(name: String, durationMs: Long, tags: Map<String, String> = emptyMap())
    fun recordBytes(name: String, bytes: Long, tags: Map<String, String> = emptyMap())

    /** Duracao com resolucao de nanossegundos. O padrao trunca para milissegundos. */
    fun recordNanos(name: String, durationNanos: Long, tags: Map<String, String> = emptyMap()) =
        recordTime(name, durationNanos / NANOS_PER_MILLI, tags)
}

private const val NANOS_PER_MILLI: Long = 1_000_000
