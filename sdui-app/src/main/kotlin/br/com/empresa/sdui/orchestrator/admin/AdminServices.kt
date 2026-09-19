package br.com.empresa.sdui.orchestrator.admin

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.diff.SpecDiffFactory
import br.com.empresa.sdui.core.model.ActorRole
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
import br.com.empresa.sdui.core.validate.CatalogValidator
import br.com.empresa.sdui.core.validate.SkeletonValidator
import br.com.empresa.sdui.core.validate.SpecValidator
import br.com.empresa.sdui.orchestrator.port.inbound.CatalogQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftCatalogCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSkeletonCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import java.time.Clock
import java.util.*

class AdminDenied(message: String) : RuntimeException(message)
class AdminConflict(message: String) : RuntimeException(message)
class AdminValidation(val errors: List<String>) : RuntimeException(errors.joinToString("; "))
class AdminNotFound(message: String) : RuntimeException(message)

class CatalogQueryService(
    private val catalogStore: CatalogStore,
    private val skeletonStore: SkeletonStore,
    private val specStore: SpecStore,
    private val diffStore: DiffStore,
) : CatalogQueryUseCase {
    override fun catalog(): Catalog = catalogStore.current()
    override fun skeleton(id: String): Skeleton? = skeletonStore.current(id)
    override fun specs(platform: ClientPlatform?, channel: Channel?): List<Spec> = specStore.list(platform, channel)
    override fun revisions(specId: String): List<Spec> = specStore.listBySpecId(specId)
    override fun diff(specId: String, from: Int, to: Int): SpecDiff? = diffStore.find(specId, from, to)
}

class DraftService(
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val catalogStore: CatalogStore,
    private val matrix: CapabilityMatrix,
) : DraftUseCase {
    override fun createSpecDraft(command: DraftSpecCommand): Spec {
        requireRole(command.actor.role, ActorRole.MAKER, ActorRole.CHECKER)
        val existing = specStore.findBySpecIdAndRevision(command.spec.specId, command.spec.revision)
        if (existing?.status == SpecStatus.PUBLISHED) {
            throw AdminValidation(listOf("spec PUBLISHED e imutavel; crie nova revisao"))
        }
        val skeleton = skeletonStore.find(command.spec.skeletonId, command.spec.skeletonRevision)
            ?: skeletonStore.current(command.spec.skeletonId)
            ?: throw AdminNotFound("skeleton ${command.spec.skeletonId}")
        val errors = SpecValidator.validateDraft(
            command.spec.copy(status = SpecStatus.DRAFT),
            skeleton,
            catalogStore.current(),
            matrix
        )
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        val revision = if (existing == null) specStore.nextRevision(command.spec.specId) else command.spec.revision
        return specStore.save(
            command.spec.copy(
                revision = revision,
                specRevisionId = command.spec.specRevisionId.ifBlank { "${command.spec.specId}#$revision" },
                status = SpecStatus.DRAFT,
                madeBy = command.actor.id,
            ),
        )
    }

    override fun createSkeletonDraft(command: DraftSkeletonCommand): Skeleton {
        requireRole(command.actor.role, ActorRole.MAKER, ActorRole.CHECKER)
        val current = skeletonStore.current(command.skeleton.skeletonId)
        if (current?.status == SpecStatus.PUBLISHED && current.revision == command.skeleton.revision) {
            throw AdminValidation(listOf("skeleton PUBLISHED e imutavel; crie nova revisao"))
        }
        val errors = SkeletonValidator.validate(command.skeleton)
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        return skeletonStore.save(command.skeleton.copy(status = SpecStatus.DRAFT))
    }

    override fun upsertComponent(command: DraftCatalogCommand): Catalog {
        requireRole(command.actor.role, ActorRole.MAKER, ActorRole.CHECKER)
        val current = catalogStore.current()
        val replaced = current.components.filterNot {
            it.type == command.component.type && it.typeVersion == command.component.typeVersion
        } + command.component
        val catalog = Catalog(replaced)
        val errors = CatalogValidator.validate(catalog)
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        return catalogStore.save(catalog)
    }
}

class PublishService(
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val catalogStore: CatalogStore,
    private val pointerStore: PointerStore,
    private val publishStore: PublishRequestStore,
    private val diffStore: DiffStore,
    private val auditLog: AuditLogStore,
    private val idempotency: IdempotencyStore,
    private val specCache: SpecCache,
    private val treeCache: HydratedScreenCache,
    private val tx: TransactionalUnitOfWork,
    private val matrix: CapabilityMatrix,
    private val clock: Clock,
) : PublishUseCase {

    override fun open(command: OpenPublishCommand): PublishRequest {
        requireRole(command.actor.role, ActorRole.MAKER)
        val spec = specStore.findBySpecIdAndRevision(command.specId, command.revision)
            ?: throw AdminNotFound("spec ${command.specId}#${command.revision}")
        if (spec.status == SpecStatus.PUBLISHED) {
            throw AdminValidation(listOf("revisao ja publicada"))
        }
        val skeleton = skeletonStore.find(spec.skeletonId, spec.skeletonRevision)
            ?: skeletonStore.current(spec.skeletonId)
            ?: throw AdminNotFound("skeleton")
        val catalogErrors = CatalogValidator.validate(catalogStore.current())
        val specErrors = SpecValidator.validateDraft(spec, skeleton, catalogStore.current(), matrix)
        if (catalogErrors.isNotEmpty() || specErrors.isNotEmpty()) {
            throw AdminValidation(catalogErrors + specErrors)
        }
        val previous = specStore.listBySpecId(spec.specId)
            .filter { it.status == SpecStatus.PUBLISHED }
            .maxByOrNull { it.revision }
        val diff = SpecDiffFactory.diff(previous, spec, skeleton)
        diffStore.save(diff)
        val request = PublishRequest(
            requestId = "pr_${UUID.randomUUID()}",
            specId = spec.specId,
            revision = spec.revision,
            specRevisionId = spec.specRevisionId,
            surface = spec.surface,
            platform = spec.platform,
            channel = command.channel,
            makerId = command.actor.id,
            status = PublishRequestStatus.OPEN,
        )
        return publishStore.save(request)
    }

    override fun approve(command: DecidePublishCommand): PublishRequest {
        requireRole(command.actor.role, ActorRole.CHECKER)
        idempotency.find(command.idempotencyKey)?.let { record ->
            return publishStore.find(record.resultRef) ?: throw AdminNotFound("publish ${record.resultRef}")
        }
        val open = publishStore.find(command.requestId) ?: throw AdminNotFound("publish ${command.requestId}")
        if (open.makerId == command.actor.id && open.channel != Channel.INTERNAL) {
            throw AdminDenied("maker nao aprova o proprio pedido")
        }
        if (open.status != PublishRequestStatus.OPEN) {
            throw AdminConflict("pedido nao esta aberto")
        }
        val spec = specStore.findBySpecIdAndRevision(open.specId, open.revision)
            ?: throw AdminNotFound("spec")
        val skeleton = skeletonStore.find(spec.skeletonId, spec.skeletonRevision)
            ?: skeletonStore.current(spec.skeletonId)
            ?: throw AdminNotFound("skeleton")
        val errors = SpecValidator.validateDraft(spec, skeleton, catalogStore.current(), matrix)
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        diffStore.find(spec.specId, spec.parentRevision ?: (spec.revision - 1).coerceAtLeast(0), spec.revision)
            ?: spec.parentRevision?.let { throw AdminValidation(listOf("diff ausente")) }
        return tx.execute {
            val approved = open.copy(status = PublishRequestStatus.APPROVED, checkerId = command.actor.id)
            val won = publishStore.compareAndSetStatus(open.requestId, PublishRequestStatus.OPEN, approved)
                ?: throw AdminConflict("approve concorrente")
            val published = specStore.save(
                spec.copy(
                    status = SpecStatus.PUBLISHED,
                    publishedAt = clock.instant(),
                    publishedBy = command.actor.id,
                ),
            )
            if (skeleton.status != SpecStatus.PUBLISHED) {
                skeletonStore.save(skeleton.copy(status = SpecStatus.PUBLISHED))
            }
            val currentPointer = pointerStore.find(published.surface, published.platform, open.channel)
            pointerStore.save(
                Pointer(
                    surface = published.surface,
                    platform = published.platform,
                    channel = open.channel,
                    specId = published.specId,
                    specRevisionId = published.specRevisionId,
                    previousSpecRevisionId = currentPointer?.specRevisionId,
                    version = (currentPointer?.version ?: 0) + 1,
                ),
            )
            specCache.put(published)
            treeCache.invalidate(published.surface, published.platform, open.channel)
            auditLog.append(
                AuditEvent(
                    id = UUID.randomUUID().toString(),
                    ts = clock.instant(),
                    actorId = command.actor.id,
                    role = command.actor.role,
                    action = "publish.approve",
                    surface = published.surface,
                    platform = published.platform,
                    channel = open.channel,
                    specId = published.specId,
                    fromRevision = currentPointer?.specRevisionId,
                    toRevision = published.specRevisionId,
                    requestId = open.requestId,
                ),
            )
            idempotency.put(IdempotencyRecord(command.idempotencyKey, "approve", won.requestId))
            won
        }
    }

    override fun reject(command: DecidePublishCommand, reason: String): PublishRequest {
        requireRole(command.actor.role, ActorRole.CHECKER)
        val open = publishStore.find(command.requestId) ?: throw AdminNotFound("publish ${command.requestId}")
        if (open.makerId == command.actor.id && open.channel != Channel.INTERNAL) {
            throw AdminDenied("maker nao rejeita o proprio pedido como checker unico sem papel")
        }
        val rejected = open.copy(status = PublishRequestStatus.REJECTED, checkerId = command.actor.id, reason = reason)
        val won = publishStore.compareAndSetStatus(open.requestId, PublishRequestStatus.OPEN, rejected)
            ?: throw AdminConflict("pedido nao esta aberto")
        auditLog.append(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                ts = clock.instant(),
                actorId = command.actor.id,
                role = command.actor.role,
                action = "publish.reject",
                surface = open.surface,
                platform = open.platform,
                channel = open.channel,
                specId = open.specId,
                fromRevision = null,
                toRevision = open.specRevisionId,
                requestId = open.requestId,
            ),
        )
        return won
    }
}

class RollbackService(
    private val pointerStore: PointerStore,
    private val specStore: SpecStore,
    private val auditLog: AuditLogStore,
    private val idempotency: IdempotencyStore,
    private val specCache: SpecCache,
    private val treeCache: HydratedScreenCache,
    private val tx: TransactionalUnitOfWork,
    private val clock: Clock,
) : RollbackPointerUseCase {
    override fun rollback(command: RollbackCommand): Pointer {
        requireRole(command.actor.role, ActorRole.CHECKER)
        idempotency.find(command.idempotencyKey)?.let { record ->
            return pointerStore.find(command.surface, command.platform, command.channel)
                ?: throw AdminNotFound("pointer ${record.resultRef}")
        }
        val pointer = pointerStore.find(command.surface, command.platform, command.channel)
            ?: throw AdminNotFound("pointer")
        val targetId = command.targetSpecRevisionId ?: pointer.previousSpecRevisionId
        ?: throw AdminValidation(listOf("sem revisao anterior"))
        val target = specStore.findByRevisionId(targetId) ?: throw AdminNotFound("spec $targetId")
        if (target.status != SpecStatus.PUBLISHED) {
            throw AdminValidation(listOf("alvo nao publicado"))
        }
        if (target.platform != command.platform) {
            throw AdminValidation(listOf("rollback nao cruza plataforma"))
        }
        return tx.execute {
            val moved = pointer.copy(
                specId = target.specId,
                specRevisionId = target.specRevisionId,
                previousSpecRevisionId = pointer.specRevisionId,
                version = pointer.version + 1,
            )
            val saved = pointerStore.save(moved)
            specCache.invalidate(pointer.specRevisionId ?: target.specRevisionId, command.platform)
            specCache.put(target)
            treeCache.invalidate(command.surface, command.platform, command.channel)
            auditLog.append(
                AuditEvent(
                    id = UUID.randomUUID().toString(),
                    ts = clock.instant(),
                    actorId = command.actor.id,
                    role = command.actor.role,
                    action = "pointer.rollback",
                    surface = command.surface,
                    platform = command.platform,
                    channel = command.channel,
                    specId = target.specId,
                    fromRevision = pointer.specRevisionId,
                    toRevision = target.specRevisionId,
                    requestId = command.idempotencyKey,
                ),
            )
            idempotency.put(
                IdempotencyRecord(
                    command.idempotencyKey,
                    "rollback",
                    "${command.surface}:${command.platform.wire()}:${command.channel.wire()}"
                )
            )
            saved
        }
    }
}

private fun requireRole(actual: ActorRole, vararg allowed: ActorRole) {
    if (actual !in allowed) throw AdminDenied("papel $actual insuficiente")
}
