package br.com.empresa.sdui.orchestrator.port.inbound

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest

/** Porta de entrada da composicao. A borda HTTP depende desta interface, nunca da implementacao. */
fun interface ComposeScreenUseCase {
    fun compose(request: ComposeRequest): ComposeResult
}

/**
 * Entrada do pipeline: a surface pedida, headers de negociacao, o ETag que o cliente ja tem e a
 * identidade limitada.
 *
 * [surface] ja chega resolvida da allowlist: uma surface desconhecida nao tem como chegar aqui, e
 * por isso nunca vira chave de cache, chave de singleflight nem tag de metrica.
 */
data class ComposeRequest(
    val headers: NegotiateHeaders,
    val ifNoneMatch: String? = null,
    val identity: String,
    val surface: SurfaceDefinition = Surfaces.HOME,
)

/**
 * Os desfechos possiveis de uma composicao, incluindo os que nao sao sucesso.
 *
 * Tipo soma para que a borda HTTP traduza cada caso ao status certo sem inventar comportamento, e
 * para o compilador cobrar tratamento quando um caso novo aparecer.
 */
sealed interface ComposeResult {
    data class Success(val screen: ComposedScreen, val fromCache: Boolean) : ComposeResult
    data class NotModified(val etag: String) : ComposeResult
    data class InvalidHeaders(val violations: List<ContextViolation>) : ComposeResult
    data class RateLimited(val retryAfterSeconds: Long) : ComposeResult
    data class Unavailable(val retryAfterSeconds: Long, val reason: FallbackReason) : ComposeResult
}

/** Comando para criar ou atualizar um rascunho de spec. */
data class DraftSpecCommand(
    val actor: Actor,
    val spec: Spec,
)

/** Comando para criar ou atualizar um rascunho de skeleton. */
data class DraftSkeletonCommand(
    val actor: Actor,
    val skeleton: Skeleton,
)

/** Comando para inserir ou substituir um componente do catalogo. */
data class DraftCatalogCommand(
    val actor: Actor,
    val component: ComponentType,
)

/**
 * Comando do maker: abre um pedido de publicacao de uma revisao num canal.
 *
 * A chave de idempotencia e obrigatoria, como nas demais operacoes de estado. Abrir pedido e
 * justamente a operacao que cria estado novo: sem chave, um retry do maker produz um segundo
 * pedido para a mesma revisao, e o checker passa a ter dois pedidos identicos para decidir.
 */
data class OpenPublishCommand(
    val actor: Actor,
    val specId: String,
    val revision: Int,
    val channel: Channel,
    val idempotencyKey: String,
)

/** Comando do checker: aprova ou rejeita um pedido. A chave de idempotencia e obrigatoria. */
data class DecidePublishCommand(
    val actor: Actor,
    val requestId: String,
    val idempotencyKey: String,
)

/** Comando para devolver o pointer a revisao anterior, ou a uma revisao publicada indicada. */
data class RollbackCommand(
    val actor: Actor,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val targetSpecRevisionId: String?,
    val idempotencyKey: String,
    val reason: String,
)

/**
 * Consultas de governanca: catalogo, skeleton, revisoes e diffs. Somente leitura.
 *
 * As listagens sao paginadas: o historico de specs cresce sem teto, e devolve-lo inteiro com as
 * props de cada revisao custava 127 ms e 60 MiB por chamada com 10 mil revisoes (medicao de
 * 2026-09-23), na mesma JVM que atende as surfaces.
 */
interface CatalogQueryUseCase {
    fun catalog(): Catalog
    fun skeleton(id: String): Skeleton?
    fun specs(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec>
    fun revisions(specId: String, page: PageRequest): List<Spec>
    fun diff(specId: String, from: Int, to: Int): SpecDiff?
}

/**
 * Leitura da trilha de auditoria. Somente leitura, restrita a checker e auditor.
 *
 * O papel e conferido aqui, e nao na borda, como nas demais operacoes administrativas: quem
 * decide quem pode ver a trilha e o caso de uso, e a borda so traduz [AdminDenied] em 403.
 */
fun interface AuditQueryUseCase {
    /** Os eventos mais recentes, do mais novo para o mais antigo, ate [limit]. */
    fun recent(actor: Actor, limit: Int): List<AuditEvent>
}

/** Autoria de rascunhos. Valida na entrada, para o erro aparecer para quem edita e nao em producao. */
interface DraftUseCase {
    fun createSpecDraft(command: DraftSpecCommand): Spec
    fun createSkeletonDraft(command: DraftSkeletonCommand): Skeleton
    fun upsertComponent(command: DraftCatalogCommand): Catalog
}

/**
 * O fluxo maker-checker de publicacao.
 *
 * Separado de [DraftUseCase] porque publicar e a operacao que muda producao: exige papel
 * diferente do de quem propos e move o pointer.
 */
interface PublishUseCase {
    fun open(command: OpenPublishCommand): PublishRequest
    fun approve(command: DecidePublishCommand): PublishRequest
    fun reject(command: DecidePublishCommand, reason: String): PublishRequest
}

/** Volta o pointer para uma revisao anterior. O caminho de reacao quando uma publicacao da errado. */
interface RollbackPointerUseCase {
    fun rollback(command: RollbackCommand): Pointer
}
