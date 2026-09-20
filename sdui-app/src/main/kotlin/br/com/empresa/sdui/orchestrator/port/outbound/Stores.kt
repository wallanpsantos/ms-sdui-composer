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
import br.com.empresa.sdui.core.model.SpecStatus
import java.time.Duration

/** Persistencia de specs. Uma revisao PUBLISHED e imutavel — a implementacao deve recusar sobrescrita. */
interface SpecStore {
    fun save(spec: Spec): Spec
    fun findByRevisionId(specRevisionId: String): Spec?
    fun findBySpecIdAndRevision(specId: String, revision: Int): Spec?
    fun listBySpecId(specId: String): List<Spec>
    fun listPublished(surface: String, platform: ClientPlatform): List<Spec>
    fun list(platform: ClientPlatform?, channel: Channel?): List<Spec>
    fun nextRevision(specId: String): Int
}

/** Persistencia de skeletons, versionados independentemente dos specs. */
interface SkeletonStore {
    fun save(skeleton: Skeleton): Skeleton
    fun find(skeletonId: String, revision: Int? = null): Skeleton?
    fun current(skeletonId: String): Skeleton?
}

/** O catalogo de componentes vigente. Guardado inteiro, porque so faz sentido validado como conjunto. */
interface CatalogStore {
    fun save(catalog: Catalog): Catalog
    fun current(): Catalog
}

/** Qual revisao esta em vigor por surface, plataforma e canal. O estado que o rollback move. */
interface PointerStore {
    fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer?
    fun save(pointer: Pointer): Pointer
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
}

/** Resultados ja produzidos por chave de idempotencia, para o retry nao duplicar o efeito. */
interface IdempotencyStore {
    fun find(key: String): IdempotencyRecord?
    fun put(record: IdempotencyRecord)
}

/**
 * Cache das arvores compostas: o que faz a maioria das requisicoes terminar sem tocar nos stores.
 *
 * A chave nunca identifica usuario. [invalidate] e chamado quando uma publicacao muda o que
 * aquela combinacao de surface, plataforma e canal deve servir.
 */
interface HydratedScreenCache {
    fun get(treeKey: String): ComposedScreen?
    fun put(treeKey: String, screen: ComposedScreen, ttl: Duration)
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel)
}

/**
 * Guarda a ultima arvore composta com sucesso por surface, plataforma e canal.
 *
 * O degrau da escada de fallback que evita o 503: preferimos entregar uma home correta porem
 * defasada, marcada como fallback no envelope, a nao entregar nada.
 */
interface LastGoodScreenStore {
    fun get(surface: String, platform: ClientPlatform, channel: Channel): ComposedScreen?
    fun put(screen: ComposedScreen)
}

/**
 * Cache de specs por revisao e plataforma, mantido em dia pela publicacao e pelo rollback.
 *
 * No fluxo atual ele nao poupa leitura: a selecao ja carregou os specs publicados antes de este
 * cache ser consultado. Passa a valer quando a selecao resolver a revisao direto pelo pointer,
 * sem listar os publicados — o que e o caminho de otimizacao previsto para quando a persistencia
 * sair da memoria.
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
 * Como uma requisicao saiu do singleflight: liderou a computacao, aproveitou a de outra, ou
 * desistiu de esperar. O terceiro caso e tratado como indisponibilidade e cai no fallback.
 */
sealed interface SingleflightOutcome<out T> {
    data class Leader<T>(val value: T) : SingleflightOutcome<T>
    data class Waiter<T>(val value: T) : SingleflightOutcome<T>
    class WaitTimeout<T> : SingleflightOutcome<T>
}

/**
 * Faz com que apenas uma requisicao componha uma chave por vez, enquanto as demais esperam.
 *
 * Protege as dependencias do pico que acontece quando uma entrada popular de cache expira e
 * varias requisicoes tentam recompo-la ao mesmo tempo.
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
 * Nome de metrica e constante e a dimensao vai em tag — nome interpolado criaria uma serie por
 * valor e multiplicaria a cardinalidade no registry.
 */
interface MetricsRecorder {
    fun increment(name: String, tags: Map<String, String> = emptyMap())
    fun recordTime(name: String, durationMs: Long, tags: Map<String, String> = emptyMap())
    fun recordBytes(name: String, bytes: Long, tags: Map<String, String> = emptyMap())
}

fun Spec.requireMutable() {
    check(status != SpecStatus.PUBLISHED) { "spec PUBLISHED e imutavel" }
}

fun Skeleton.requireMutable() {
    check(status != SpecStatus.PUBLISHED) { "skeleton PUBLISHED e imutavel" }
}
