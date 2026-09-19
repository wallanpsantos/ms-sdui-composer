package br.com.empresa.sdui.api.admin

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.orchestrator.admin.AdminDenied
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

@RestController
@RequestMapping("/admin/v1")
class AdminController(
    private val catalogQuery: CatalogQueryUseCase,
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val rollback: RollbackPointerUseCase,
    private val auditLog: AuditLogStore,
) {
    @GetMapping("/catalog/components")
    fun catalog(@RequestHeader headers: org.springframework.http.HttpHeaders): Catalog {
        actor(headers)
        return catalogQuery.catalog()
    }

    @PutMapping("/catalog/components/{type}/{ver}")
    fun putComponent(
        @PathVariable type: String,
        @PathVariable ver: Int,
        @RequestBody component: ComponentType,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
    ): Catalog = drafts.upsertComponent(
        DraftCatalogCommand(
            actor(headers),
            component.copy(type = type, typeVersion = ver),
        ),
    )

    @GetMapping("/skeletons/{id}")
    fun skeleton(@PathVariable id: String, @RequestHeader headers: org.springframework.http.HttpHeaders): Skeleton {
        actor(headers)
        return catalogQuery.skeleton(id) ?: throw br.com.empresa.sdui.orchestrator.admin.AdminNotFound(id)
    }

    @PutMapping("/skeletons/{id}")
    fun putSkeleton(
        @PathVariable id: String,
        @RequestBody skeleton: Skeleton,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
    ): Skeleton = drafts.createSkeletonDraft(DraftSkeletonCommand(actor(headers), skeleton.copy(skeletonId = id)))

    @GetMapping("/specs")
    fun specs(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) channel: String?,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.specs(platform?.let { ClientPlatform.parse(it) }, channel?.let { Channel.parse(it) })
    }

    @PostMapping("/specs")
    fun createSpec(
        @RequestBody spec: Spec,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
    ): Spec = drafts.createSpecDraft(DraftSpecCommand(actor(headers), spec))

    @GetMapping("/specs/{id}/revisions")
    fun revisions(@PathVariable id: String, @RequestHeader headers: org.springframework.http.HttpHeaders): List<Spec> {
        actor(headers)
        return catalogQuery.revisions(id)
    }

    @GetMapping("/specs/{id}/revisions/{from}..{to}/diff")
    fun diff(
        @PathVariable id: String,
        @PathVariable from: Int,
        @PathVariable to: Int,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
    ): SpecDiff {
        actor(headers)
        return catalogQuery.diff(id, from, to) ?: throw br.com.empresa.sdui.orchestrator.admin.AdminNotFound("diff")
    }

    @PostMapping("/publish-requests")
    fun openPublish(
        @RequestBody body: OpenPublishBody,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): PublishRequest = publish.open(
        OpenPublishCommand(
            actor = actor(headers),
            specId = body.specId,
            revision = body.revision,
            channel = Channel.parse(body.channel),
            idempotencyKey = idempotencyKey,
        ),
    )

    @PostMapping("/publish-requests/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest = publish.approve(DecidePublishCommand(actor(headers), id, idempotencyKey))

    @PostMapping("/publish-requests/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @RequestBody body: RejectBody,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest = publish.reject(DecidePublishCommand(actor(headers), id, idempotencyKey), body.reason)

    @PostMapping("/pointers/{surface}/{platform}/{channel}:rollback")
    fun rollbackPointer(
        @PathVariable surface: String,
        @PathVariable platform: String,
        @PathVariable channel: String,
        @RequestBody(required = false) body: RollbackBody?,
        @RequestHeader headers: org.springframework.http.HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<*> {
        val moved = rollback.rollback(
            RollbackCommand(
                actor = actor(headers),
                surface = surface,
                platform = ClientPlatform.parse(platform) ?: throw AdminDenied("plataforma invalida"),
                channel = Channel.parse(channel),
                targetSpecRevisionId = body?.targetSpecRevisionId,
                idempotencyKey = idempotencyKey,
                reason = body?.reason ?: "rollback",
            ),
        )
        return ResponseEntity.ok(moved)
    }

    @GetMapping("/audit")
    fun audit(@RequestHeader headers: org.springframework.http.HttpHeaders): List<AuditEvent> {
        val current = actor(headers)
        if (current.role != ActorRole.AUDITOR && current.role != ActorRole.CHECKER) {
            throw AdminDenied("auditoria exige checker ou auditor")
        }
        return auditLog.list()
    }

    private fun actor(headers: org.springframework.http.HttpHeaders): Actor {
        val id = headers.getFirst("Actor-Id") ?: throw AdminDenied("Actor-Id ausente")
        val role = ActorRole.parse(headers.getFirst("Actor-Role")) ?: throw AdminDenied("Actor-Role ausente")
        return Actor(id, role)
    }
}

data class OpenPublishBody(
    val specId: String,
    val revision: Int,
    val channel: String = "stable",
)

data class RejectBody(val reason: String)

data class RollbackBody(
    val targetSpecRevisionId: String? = null,
    val reason: String? = null,
)
