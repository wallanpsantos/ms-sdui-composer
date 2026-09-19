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

fun interface ComposeScreenUseCase {
    fun compose(request: ComposeRequest): ComposeResult
}

data class DraftSpecCommand(
    val actor: Actor,
    val spec: Spec,
)

data class DraftSkeletonCommand(
    val actor: Actor,
    val skeleton: Skeleton,
)

data class DraftCatalogCommand(
    val actor: Actor,
    val component: ComponentType,
)

data class OpenPublishCommand(
    val actor: Actor,
    val specId: String,
    val revision: Int,
    val channel: Channel,
    val idempotencyKey: String?,
)

data class DecidePublishCommand(
    val actor: Actor,
    val requestId: String,
    val idempotencyKey: String,
)

data class RollbackCommand(
    val actor: Actor,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val targetSpecRevisionId: String?,
    val idempotencyKey: String,
    val reason: String,
)

interface CatalogQueryUseCase {
    fun catalog(): Catalog
    fun skeleton(id: String): Skeleton?
    fun specs(platform: ClientPlatform?, channel: Channel?): List<Spec>
    fun revisions(specId: String): List<Spec>
    fun diff(specId: String, from: Int, to: Int): SpecDiff?
}

interface DraftUseCase {
    fun createSpecDraft(command: DraftSpecCommand): Spec
    fun createSkeletonDraft(command: DraftSkeletonCommand): Skeleton
    fun upsertComponent(command: DraftCatalogCommand): Catalog
}

interface PublishUseCase {
    fun open(command: OpenPublishCommand): PublishRequest
    fun approve(command: DecidePublishCommand): PublishRequest
    fun reject(command: DecidePublishCommand, reason: String): PublishRequest
}

interface RollbackPointerUseCase {
    fun rollback(command: RollbackCommand): Pointer
}
