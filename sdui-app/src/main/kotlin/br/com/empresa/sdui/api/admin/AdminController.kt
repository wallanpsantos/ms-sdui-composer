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
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.port.inbound.AdminDenied
import br.com.empresa.sdui.orchestrator.port.inbound.AdminNotFound
import br.com.empresa.sdui.orchestrator.port.inbound.AdminValidation
import br.com.empresa.sdui.orchestrator.port.inbound.AuditQueryUseCase
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
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
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
 * Plano administrativo, separado do plano de leitura das surfaces. A identidade do ator vem dos
 * headers Actor-Id e Actor-Role, sem autenticacao — enquanto isso valer, estes endpoints so podem
 * ficar acessiveis atras de uma barreira de rede.
 *
 * As listagens sao paginadas por `offset` e `limit` (padrao 100, teto 500). Toda tag de metrica
 * vem de valor ja validado — type aprovado, surface da allowlist, plataforma e canal normalizados
 * —, nunca do texto cru do path ou do corpo.
 *
 * Plataforma e canal sao lidos sem valor padrao: desconhecido no path e 404, no corpo ou na query
 * e 400. Cair em stable, como o header do cliente faz, deixaria um erro de digitacao abrir pedido
 * ou fazer rollback no canal de producao, e um filtro invalido listar tudo.
 */
@RestController
@RequestMapping("/admin/v1")
class AdminController(
    private val catalogQuery: CatalogQueryUseCase,
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val rollback: RollbackPointerUseCase,
    private val auditQuery: AuditQueryUseCase,
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
        // O validador de catalogo so aceita contratos aprovados: `type` aqui e de vocabulario fechado.
        metrics.increment(MetricNames.ADMIN_CATALOG_UPSERT, mapOf("type" to type))
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
        metrics.increment(MetricNames.ADMIN_SKELETON_UPSERT)
        logger.info("skeleton draft upserted: skeletonId={}, actor={}", id, currentActor.id)
        return saved
    }

    @GetMapping("/specs")
    fun specs(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.specs(
            platform?.let { ClientPlatform.parse(it) ?: throw unknown("platform") },
            channel?.let { Channel.parseOrNull(it) ?: throw unknown("channel") },
            page(offset, limit),
        )
    }

    @PostMapping("/specs")
    fun createSpec(
        @RequestBody spec: Spec,
        @RequestHeader headers: HttpHeaders,
    ): Spec {
        val currentActor = actor(headers)
        val created = drafts.createSpecDraft(DraftSpecCommand(currentActor, spec))
        // Tags do rascunho gravado: a surface ja passou pela allowlist do validador.
        metrics.increment(
            MetricNames.ADMIN_SPEC_DRAFT,
            mapOf("surface" to created.surface, "platform" to created.platform.wire()),
        )
        // Valores do rascunho gravado: sem revisao existente, o servico atribui a proxima, e o
        // corpo traria outra.
        logger.info(
            "spec draft created: specId={}, revision={}, surface={}, platform={}, actor={}",
            created.specId,
            created.revision,
            created.surface,
            created.platform.wire(),
            currentActor.id,
        )
        return created
    }

    @GetMapping("/specs/{id}/revisions")
    fun revisions(
        @PathVariable id: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.revisions(id, page(offset, limit))
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
                channel = Channel.parseOrNull(body.channel) ?: throw unknown("channel"),
                idempotencyKey = idempotencyKey,
            ),
        )
        // Tag e log vem do pedido persistido, e nao do corpo: o texto cru varia em caixa e espaco,
        // e cada grafia abriria uma serie de metrica.
        metrics.increment(MetricNames.ADMIN_PUBLISH_OPEN, mapOf("channel" to created.channel.wire()))
        logger.info(
            "publish request opened: id={}, specId={}, revision={}, channel={}, actor={}",
            created.requestId,
            created.specId,
            created.revision,
            created.channel.wire(),
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
        metrics.increment(MetricNames.ADMIN_PUBLISH_APPROVED, mapOf("channel" to approved.channel.wire()))
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
        metrics.increment(MetricNames.ADMIN_PUBLISH_REJECTED, mapOf("channel" to rejected.channel.wire()))
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
    ): Pointer {
        val currentActor = actor(headers)
        // O path identifica o pointer: segmento desconhecido e pointer inexistente.
        val knownSurface = Surfaces.find(surface) ?: throw AdminNotFound("surface desconhecida")
        val knownPlatform = ClientPlatform.parse(platform) ?: throw AdminNotFound("plataforma desconhecida")
        val knownChannel = Channel.parseOrNull(channel) ?: throw AdminNotFound("canal desconhecido")
        val moved = rollback.rollback(
            RollbackCommand(
                actor = currentActor,
                surface = knownSurface.id,
                platform = knownPlatform,
                channel = knownChannel,
                targetSpecRevisionId = body?.targetSpecRevisionId,
                idempotencyKey = idempotencyKey,
                reason = body?.reason ?: "rollback",
            ),
        )
        // Tags e log do pointer movido, e nao do path: plataforma e canal chegam como texto livre e
        // so o valor normalizado mantem a cardinalidade fechada.
        metrics.increment(
            MetricNames.ADMIN_ROLLBACK,
            mapOf("surface" to moved.surface, "platform" to moved.platform.wire(), "channel" to moved.channel.wire()),
        )
        logger.warn(
            "pointer rollback executed: surface={}, platform={}, channel={}, actor={}, targetSpecRevisionId={}, reason={}",
            moved.surface,
            moved.platform.wire(),
            moved.channel.wire(),
            currentActor.id,
            body?.targetSpecRevisionId,
            body?.reason,
        )
        return moved
    }

    /** Os eventos mais recentes, do mais novo para o mais antigo, ate `limit` (padrao 100). */
    @GetMapping("/audit")
    fun audit(
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<AuditEvent> {
        val events = auditQuery.recent(actor(headers), page(0, limit).limit)
        metrics.increment(MetricNames.ADMIN_AUDIT_LIST)
        return events
    }

    /** Janela validada da listagem. Valor fora da faixa e erro do chamador, nao truncamento silencioso. */
    private fun page(offset: Int?, limit: Int?): PageRequest {
        val resolvedOffset = offset ?: 0
        val resolvedLimit = limit ?: PageRequest.DEFAULT_LIMIT
        if (resolvedOffset < 0 || resolvedLimit !in 1..PageRequest.MAX_LIMIT) {
            throw AdminValidation(listOf("offset >= 0 e limit entre 1 e ${PageRequest.MAX_LIMIT}"))
        }
        return PageRequest(resolvedOffset, resolvedLimit)
    }

    /** Valor fora do vocabulario no corpo ou na query: erro do chamador, nunca um valor padrao. */
    private fun unknown(field: String): AdminValidation = AdminValidation(listOf("$field desconhecido"))

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
