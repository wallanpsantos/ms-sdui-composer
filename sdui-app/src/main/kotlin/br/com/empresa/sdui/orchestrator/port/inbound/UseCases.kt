package br.com.empresa.sdui.orchestrator.port.inbound

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.orchestrator.compose.ComposeRequest
import br.com.empresa.sdui.orchestrator.compose.ComposeResult

/** Porta de entrada da composicao. A borda HTTP depende desta interface, nunca da implementacao. */
fun interface ComposeScreenUseCase {
    fun compose(request: ComposeRequest): ComposeResult
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

/** Consultas de governanca: catalogo, skeleton, revisoes e diffs. Somente leitura. */
interface CatalogQueryUseCase {
    fun catalog(): Catalog
    fun skeleton(id: String): Skeleton?
    fun specs(platform: ClientPlatform?, channel: Channel?): List<Spec>
    fun revisions(specId: String): List<Spec>
    fun diff(specId: String, from: Int, to: Int): SpecDiff?
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
