package br.com.empresa.sdui.api.admin

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.orchestrator.admin.AdminDenied
import br.com.empresa.sdui.orchestrator.admin.AdminNotFound
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
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * A borda HTTP da governanca: catalogo, skeletons, specs, publicacao e rollback.
 *
 * Plano administrativo, separado do plano de leitura da home. A identidade do ator vem dos
 * headers Actor-Id e Actor-Role, sem autenticacao — enquanto isso valer, estes endpoints so podem
 * ficar acessiveis atras de uma barreira de rede.
 */
@RestController
@RequestMapping("/admin/v1")
class AdminController(
    private val catalogQuery: CatalogQueryUseCase,
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val rollback: RollbackPointerUseCase,
    private val auditLog: AuditLogStore,
    private val metrics: MetricsRecorder,
) {
    private val logger = LoggerFactory.getLogger(AdminController::class.java)

    @GetMapping("/catalog/components")
    fun catalog(@RequestHeader headers: HttpHeaders): Catalog {
        actor(headers)
        return catalogQuery.catalog()
    }

    @PutMapping("/catalog/components/{type}/{ver}")
    fun putComponent(
        @PathVariable type: String,
        @PathVariable ver: Int,
        @RequestBody component: ComponentType,
        @RequestHeader headers: HttpHeaders,
    ): Catalog {
        val currentActor = actor(headers)
        val catalog = drafts.upsertComponent(
            DraftCatalogCommand(
                currentActor,
                component.copy(type = type, typeVersion = ver),
            ),
        )
        metrics.increment("admin.catalog.upsert", mapOf("type" to type))
        logger.info("catalog component upserted: type={}, version={}, actor={}", type, ver, currentActor.id)
        return catalog
    }

    @GetMapping("/skeletons/{id}")
    fun skeleton(@PathVariable id: String, @RequestHeader headers: HttpHeaders): Skeleton {
        actor(headers)
        return catalogQuery.skeleton(id) ?: throw AdminNotFound(id)
    }

    @PutMapping("/skeletons/{id}")
    fun putSkeleton(
        @PathVariable id: String,
        @RequestBody skeleton: Skeleton,
        @RequestHeader headers: HttpHeaders,
    ): Skeleton {
        val currentActor = actor(headers)
        val saved = drafts.createSkeletonDraft(DraftSkeletonCommand(currentActor, skeleton.copy(skeletonId = id)))
        metrics.increment("admin.skeleton.upsert")
        logger.info("skeleton draft upserted: skeletonId={}, actor={}", id, currentActor.id)
        return saved
    }

    @GetMapping("/specs")
    fun specs(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) channel: String?,
        @RequestHeader headers: HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.specs(platform?.let { ClientPlatform.parse(it) }, channel?.let { Channel.parse(it) })
    }

    @PostMapping("/specs")
    fun createSpec(
        @RequestBody spec: Spec,
        @RequestHeader headers: HttpHeaders,
    ): Spec {
        val currentActor = actor(headers)
        val created = drafts.createSpecDraft(DraftSpecCommand(currentActor, spec))
        metrics.increment("admin.spec.draft", mapOf("surface" to spec.surface, "platform" to spec.platform.wire()))
        logger.info(
            "spec draft created: specId={}, revision={}, surface={}, platform={}, actor={}",
            spec.specId,
            spec.revision,
            spec.surface,
            spec.platform.wire(),
            currentActor.id,
        )
        return created
    }

    @GetMapping("/specs/{id}/revisions")
    fun revisions(@PathVariable id: String, @RequestHeader headers: HttpHeaders): List<Spec> {
        actor(headers)
        return catalogQuery.revisions(id)
    }

    @GetMapping("/specs/{id}/revisions/{from}..{to}/diff")
    fun diff(
        @PathVariable id: String,
        @PathVariable from: Int,
        @PathVariable to: Int,
        @RequestHeader headers: HttpHeaders,
    ): SpecDiff {
        actor(headers)
        return catalogQuery.diff(id, from, to) ?: throw AdminNotFound("diff")
    }

    @PostMapping("/publish-requests")
    fun openPublish(
        @RequestBody body: OpenPublishBody,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val created = publish.open(
            OpenPublishCommand(
                actor = currentActor,
                specId = body.specId,
                revision = body.revision,
                channel = Channel.parse(body.channel),
                idempotencyKey = idempotencyKey,
            ),
        )
        metrics.increment("admin.publish.open", mapOf("channel" to body.channel))
        logger.info(
            "publish request opened: id={}, specId={}, revision={}, channel={}, actor={}",
            created.requestId,
            body.specId,
            body.revision,
            body.channel,
            currentActor.id,
        )
        return created
    }

    @PostMapping("/publish-requests/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val approved = publish.approve(DecidePublishCommand(currentActor, id, idempotencyKey))
        metrics.increment("admin.publish.approved", mapOf("channel" to approved.channel.wire()))
        logger.info(
            "publish request approved: id={}, actor={}, role={}, targetSpecRevisionId={}",
            id,
            currentActor.id,
            currentActor.role,
            approved.specRevisionId,
        )
        return approved
    }

    @PostMapping("/publish-requests/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @RequestBody body: RejectBody,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val rejected = publish.reject(DecidePublishCommand(currentActor, id, idempotencyKey), body.reason)
        metrics.increment("admin.publish.rejected", mapOf("channel" to rejected.channel.wire()))
        logger.warn(
            "publish request rejected: id={}, actor={}, role={}, reason={}",
            id,
            currentActor.id,
            currentActor.role,
            body.reason,
        )
        return rejected
    }

    @PostMapping("/pointers/{surface}/{platform}/{channel}:rollback")
    fun rollbackPointer(
        @PathVariable surface: String,
        @PathVariable platform: String,
        @PathVariable channel: String,
        @RequestBody(required = false) body: RollbackBody?,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<Pointer> {
        val currentActor = actor(headers)
        val clientPlatform = ClientPlatform.parse(platform) ?: throw AdminDenied("plataforma invalida")
        val parsedChannel = Channel.parse(channel)
        val moved = rollback.rollback(
            RollbackCommand(
                actor = currentActor,
                surface = surface,
                platform = clientPlatform,
                channel = parsedChannel,
                targetSpecRevisionId = body?.targetSpecRevisionId,
                idempotencyKey = idempotencyKey,
                reason = body?.reason ?: "rollback",
            ),
        )
        metrics.increment("admin.rollback", mapOf("surface" to surface, "platform" to platform, "channel" to channel))
        logger.warn(
            "pointer rollback executed: surface={}, platform={}, channel={}, actor={}, targetSpecRevisionId={}, reason={}",
            surface,
            platform,
            channel,
            currentActor.id,
            body?.targetSpecRevisionId,
            body?.reason,
        )
        return ResponseEntity.ok(moved)
    }

    @GetMapping("/audit")
    fun audit(@RequestHeader headers: HttpHeaders): List<AuditEvent> {
        val current = actor(headers)
        if (current.role != ActorRole.AUDITOR && current.role != ActorRole.CHECKER) {
            throw AdminDenied("auditoria exige checker ou auditor")
        }
        metrics.increment("admin.audit.list")
        return auditLog.list()
    }

    private fun actor(headers: HttpHeaders): Actor {
        val id = headers.getFirst("Actor-Id") ?: throw AdminDenied("Actor-Id ausente")
        val role = ActorRole.parse(headers.getFirst("Actor-Role")) ?: throw AdminDenied("Actor-Role ausente")
        return Actor(id, role)
    }
}

/** Corpo para abrir um pedido de publicacao. Canal ausente significa stable. */
data class OpenPublishBody(
    val specId: String,
    val revision: Int,
    val channel: String = "stable",
)

/** Motivo da rejeicao, obrigatorio: a recusa precisa ficar registrada na auditoria. */
data class RejectBody(val reason: String)

/** Alvo opcional do rollback. Sem alvo, volta para a revisao anterior do proprio pointer. */
data class RollbackBody(
    val targetSpecRevisionId: String? = null,
    val reason: String? = null,
)
