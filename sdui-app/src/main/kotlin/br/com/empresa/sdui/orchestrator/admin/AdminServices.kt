package br.com.empresa.sdui.orchestrator.admin

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.diff.SpecDiffFactory
import br.com.empresa.sdui.core.model.Actor
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
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.core.validate.CatalogValidator
import br.com.empresa.sdui.core.validate.SkeletonValidator
import br.com.empresa.sdui.core.validate.SpecValidator
import br.com.empresa.sdui.orchestrator.port.inbound.AdminConflict
import br.com.empresa.sdui.orchestrator.port.inbound.AdminDenied
import br.com.empresa.sdui.orchestrator.port.inbound.AdminIdempotencyMismatch
import br.com.empresa.sdui.orchestrator.port.inbound.AdminInFlight
import br.com.empresa.sdui.orchestrator.port.inbound.AdminNotFound
import br.com.empresa.sdui.orchestrator.port.inbound.AdminUnavailable
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
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublicationFingerprint
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import br.com.empresa.sdui.orchestrator.port.outbound.findFor
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.*

/** Leitura da trilha de auditoria para checker e auditor. Nao altera estado. */
class AuditQueryService(
    private val auditLog: AuditLogStore,
) : AuditQueryUseCase {
    override fun recent(actor: Actor, limit: Int): List<AuditEvent> {
        requireRole(actor.role, ActorRole.CHECKER, ActorRole.AUDITOR)
        return auditLog.recent(limit)
    }
}

/** Leitura da governanca: catalogo, skeleton, revisoes e diff. Nao altera estado. */
class CatalogQueryService(
    private val catalogStore: CatalogStore,
    private val skeletonStore: SkeletonStore,
    private val specStore: SpecStore,
    private val diffStore: DiffStore,
) : CatalogQueryUseCase {
    override fun catalog(): Catalog = catalogStore.current()
    override fun skeleton(id: String): Skeleton? = skeletonStore.current(id)

    override fun specs(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        specStore.list(platform, channel, page)

    override fun revisions(specId: String, page: PageRequest): List<Spec> = specStore.listBySpecId(specId, page)
    override fun diff(specId: String, from: Int, to: Int): SpecDiff? = diffStore.find(specId, from, to)
}

/**
 * Autoria de rascunhos de spec, skeleton e catalogo.
 *
 * Valida antes de gravar, para o autor ver o erro enquanto edita. Recusa tocar em revisao ja
 * publicada: a correcao de algo publicado e sempre uma revisao nova. A surface do rascunho
 * precisa estar na allowlist — os validadores recusam qualquer outra antes de ela virar dado.
 */
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
        val revision = if (existing == null) specStore.nextRevision(command.spec.specId) else command.spec.revision
        val draft = command.spec.copy(
            revision = revision,
            specRevisionId = command.spec.specRevisionId.ifBlank { "${command.spec.specId}#$revision" },
            status = SpecStatus.DRAFT,
            madeBy = command.actor.id,
        )
        val skeleton = skeletonStore.findFor(draft)
            ?: throw AdminNotFound("skeleton ${draft.skeletonId}#${draft.skeletonRevision}")
        val errors = SpecValidator.validateDraft(draft, skeleton, catalogStore.current(), matrix)
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        return specStore.compareAndSet(existing, draft)
    }

    override fun createSkeletonDraft(command: DraftSkeletonCommand): Skeleton {
        requireRole(command.actor.role, ActorRole.MAKER, ActorRole.CHECKER)
        val existing = skeletonStore.find(command.skeleton.skeletonId, command.skeleton.revision)
        if (existing?.status == SpecStatus.PUBLISHED) {
            throw AdminValidation(listOf("skeleton PUBLISHED e imutavel; crie nova revisao"))
        }
        val errors = SkeletonValidator.validate(command.skeleton)
        if (errors.isNotEmpty()) throw AdminValidation(errors)
        return skeletonStore.compareAndSet(existing, command.skeleton.copy(status = SpecStatus.DRAFT))
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

/**
 * O fluxo maker-checker: abrir, aprovar e rejeitar publicacao.
 *
 * Quem abre nao aprova, fora do canal interno. A aprovacao revalida o spec antes de publicar —
 * o catalogo pode ter mudado desde a abertura — e so entao move o pointer, por compare-and-set,
 * dentro da transacao junto com auditoria, idempotencia e o registro da invalidacao de cache no
 * outbox. A invalidacao em si acontece do lado de fora, depois do commit (ADR-021).
 */
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
    private val outbox: CacheInvalidationOutbox,
    private val invalidator: CacheInvalidator,
    private val tx: TransactionalUnitOfWork,
    private val matrix: CapabilityMatrix,
    private val clock: Clock,
    private val publicationFingerprint: PublicationFingerprint,
) : PublishUseCase {

    override fun open(command: OpenPublishCommand): PublishRequest {
        requireRole(command.actor.role, ActorRole.MAKER)
        val fingerprint = fingerprintOf(
            OPERATION_OPEN,
            command.specId,
            command.revision.toString(),
            command.channel.wire(),
        )
        return idempotency.idempotent(command.idempotencyKey, OPERATION_OPEN, fingerprint, ::replayPublish) { token ->
            openReserved(command, fingerprint, token)
        }
    }

    private fun openReserved(command: OpenPublishCommand, fingerprint: String, token: String): PublishRequest =
        tx.execute {
            val spec = specStore.findBySpecIdAndRevision(command.specId, command.revision)
                ?: throw AdminNotFound("spec ${command.specId}#${command.revision}")
            if (spec.status == SpecStatus.PUBLISHED) {
                throw AdminValidation(listOf("revisao ja publicada"))
            }
            val skeleton = skeletonStore.findFor(spec) ?: throw AdminNotFound("skeleton")
            val catalog = catalogStore.current()
            val catalogErrors = CatalogValidator.validate(catalog)
            val specErrors = SpecValidator.validateDraft(spec, skeleton, catalog, matrix)
            if (catalogErrors.isNotEmpty() || specErrors.isNotEmpty()) {
                throw AdminValidation(catalogErrors + specErrors)
            }
            val previous = specStore.listBySpecId(spec.specId)
                .filter { it.status == SpecStatus.PUBLISHED }
                .maxByOrNull { it.revision }
            val diff = SpecDiffFactory.diff(previous, spec, skeleton)
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
                reviewedContentHash = publicationFingerprint.of(spec, skeleton),
            )
            diffStore.save(diff)
            val saved = publishStore.save(request)
            idempotency.complete(
                IdempotencyRecord(command.idempotencyKey, OPERATION_OPEN, saved.requestId, fingerprint), token,
            )
            saved
        }

    /**
     * Devolve o resultado ja produzido para uma chave, ou recusa se ela ainda estiver em voo.
     *
     * Reserva em voo nao tem resultado para devolver, e responder o estado atual do pedido seria
     * afirmar um desfecho que ainda nao aconteceu.
     */
    private fun replayPublish(record: IdempotencyRecord): PublishRequest {
        val ref = record.resultRef ?: throw AdminInFlight(record.key)
        return publishStore.find(ref) ?: throw AdminNotFound("publish $ref")
    }

    override fun approve(command: DecidePublishCommand): PublishRequest {
        requireRole(command.actor.role, ActorRole.CHECKER)
        val fingerprint = fingerprintOf(OPERATION_APPROVE, command.requestId)
        return idempotency.idempotent(
            command.idempotencyKey,
            OPERATION_APPROVE,
            fingerprint,
            ::replayPublish
        ) { token ->
            val outcome = approveReserved(command, fingerprint, token)
            // Escritas de cache ficam fora da transacao: nao sao transacionais e, aplicadas antes
            // do commit, entregariam aos leitores uma revisao que um rollback ainda pode desfazer.
            // O last good tambem guarda uma revisao; a lapide na versao nova do pointer e o que
            // impede o fallback de reintroduzir o que a publicacao acabou de substituir.
            warm(outcome.spec)
            invalidator.apply(outcome.invalidation)
            outcome.request
        }
    }

    private fun approveReserved(command: DecidePublishCommand, fingerprint: String, token: String): PublishOutcome =
        tx.execute {
            val open = publishStore.find(command.requestId) ?: throw AdminNotFound("publish ${command.requestId}")
            if (open.makerId == command.actor.id && open.channel != Channel.INTERNAL) {
                throw AdminDenied("maker nao aprova o proprio pedido")
            }
            if (open.status != PublishRequestStatus.OPEN) {
                throw AdminConflict("pedido nao esta aberto")
            }
            val spec = specStore.findBySpecIdAndRevision(open.specId, open.revision)
                ?: throw AdminNotFound("spec")
            if (spec.status != SpecStatus.DRAFT) throw AdminConflict("revisao ja publicada")
            val skeleton = skeletonStore.findFor(spec) ?: throw AdminNotFound("skeleton")
            if (open.reviewedContentHash == null || open.reviewedContentHash != publicationFingerprint.of(
                    spec,
                    skeleton
                )
            ) {
                throw AdminConflict("conteudo alterado desde a abertura; reabra o pedido")
            }
            val errors = SpecValidator.validateDraft(spec, skeleton, catalogStore.current(), matrix)
            if (errors.isNotEmpty()) throw AdminValidation(errors)
            // Revisao com pai exige diff calculado: e o que o checker revisa antes de aprovar. A
            // primeira revisao de um spec nao tem pai e portanto nao tem diff.
            val parentRev = spec.parentRevision
            if (parentRev != null) {
                diffStore.find(spec.specId, parentRev, spec.revision)
                    ?: throw AdminValidation(listOf("diff ausente"))
            }
            val approved = open.copy(status = PublishRequestStatus.APPROVED, checkerId = command.actor.id)
            val won = publishStore.compareAndSetStatus(open.requestId, PublishRequestStatus.OPEN, approved)
                ?: throw AdminConflict("approve concorrente")
            val now = clock.instant()
            val published = specStore.compareAndSet(
                spec,
                spec.copy(
                    status = SpecStatus.PUBLISHED,
                    publishedAt = now,
                    publishedBy = command.actor.id,
                ),
            )
            if (skeleton.status != SpecStatus.PUBLISHED) {
                skeletonStore.compareAndSet(skeleton, skeleton.copy(status = SpecStatus.PUBLISHED))
            }
            val currentPointer = pointerStore.find(published.surface, published.platform, open.channel)
            val moved = pointerStore.compareAndSet(
                currentPointer?.version,
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
            auditLog.append(
                AuditEvent(
                    id = UUID.randomUUID().toString(),
                    ts = now,
                    actorId = command.actor.id,
                    role = command.actor.role,
                    action = OPERATION_APPROVE,
                    surface = published.surface,
                    platform = published.platform,
                    channel = open.channel,
                    specId = published.specId,
                    fromRevision = currentPointer?.specRevisionId,
                    toRevision = published.specRevisionId,
                    requestId = open.requestId,
                ),
            )
            val invalidation = invalidationFor(moved, currentPointer?.specRevisionId, now)
            outbox.record(invalidation)
            idempotency.complete(
                IdempotencyRecord(command.idempotencyKey, OPERATION_APPROVE, won.requestId, fingerprint), token,
            )
            PublishOutcome(won, published, invalidation)
        }

    override fun reject(command: DecidePublishCommand, reason: String): PublishRequest {
        requireRole(command.actor.role, ActorRole.CHECKER)
        val fingerprint = fingerprintOf(OPERATION_REJECT, command.requestId)
        return idempotency.idempotent(command.idempotencyKey, OPERATION_REJECT, fingerprint, ::replayPublish) { token ->
            val open = publishStore.find(command.requestId) ?: throw AdminNotFound("publish ${command.requestId}")
            if (open.makerId == command.actor.id && open.channel != Channel.INTERNAL) {
                throw AdminDenied("maker nao rejeita o proprio pedido como checker unico sem papel")
            }
            val rejected =
                open.copy(status = PublishRequestStatus.REJECTED, checkerId = command.actor.id, reason = reason)
            // Transicao, auditoria e fecho da chave no mesmo commit. Separados, uma falha entre a
            // transicao e o fecho deixaria o efeito aplicado sem registro da chave: o retry do
            // checker acharia o pedido fora de OPEN e receberia 409, sem meio de saber se a propria
            // rejeicao dele foi a que valeu.
            tx.execute {
                val won = publishStore.compareAndSetStatus(open.requestId, PublishRequestStatus.OPEN, rejected)
                    ?: throw AdminConflict("pedido nao esta aberto")
                auditLog.append(
                    AuditEvent(
                        id = UUID.randomUUID().toString(),
                        ts = clock.instant(),
                        actorId = command.actor.id,
                        role = command.actor.role,
                        action = OPERATION_REJECT,
                        surface = open.surface,
                        platform = open.platform,
                        channel = open.channel,
                        specId = open.specId,
                        fromRevision = null,
                        toRevision = open.specRevisionId,
                        requestId = open.requestId,
                    ),
                )
                idempotency.complete(
                    IdempotencyRecord(command.idempotencyKey, OPERATION_REJECT, won.requestId, fingerprint), token,
                )
                won
            }
        }
    }

    /** Aquece o cache de spec com a revisao recem-publicada. Falha aqui so custa uma leitura depois. */
    private fun warm(spec: Spec) {
        runCatching { specCache.put(spec) }
    }
}

private data class PublishOutcome(
    val request: PublishRequest,
    val spec: Spec,
    val invalidation: CacheInvalidation,
)

/**
 * Devolve o pointer a uma revisao publicada anterior.
 *
 * O caminho de reacao a uma publicacao ruim: nao apaga nem altera revisao nenhuma, so muda qual
 * esta em vigor, e por isso e seguro de executar sob pressao. Nunca cruza plataforma nem surface,
 * e so aceita surface da allowlist.
 */
class RollbackService(
    private val pointerStore: PointerStore,
    private val specStore: SpecStore,
    private val auditLog: AuditLogStore,
    private val idempotency: IdempotencyStore,
    private val specCache: SpecCache,
    private val outbox: CacheInvalidationOutbox,
    private val invalidator: CacheInvalidator,
    private val tx: TransactionalUnitOfWork,
    private val clock: Clock,
) : RollbackPointerUseCase {
    override fun rollback(command: RollbackCommand): Pointer {
        requireRole(command.actor.role, ActorRole.CHECKER)
        if (Surfaces.find(command.surface) == null) throw AdminNotFound("surface ${command.surface}")
        val fingerprint = fingerprintOf(
            OPERATION_ROLLBACK,
            command.surface,
            command.platform.wire(),
            command.channel.wire(),
            command.targetSpecRevisionId.orEmpty(),
        )
        return idempotency.idempotent(
            command.idempotencyKey,
            OPERATION_ROLLBACK,
            fingerprint,
            replay = { record ->
                if (record.resultRef == null) throw AdminInFlight(command.idempotencyKey)
                // O pointer e o recurso que o rollback move; o replay devolve o estado dele.
                pointerStore.find(command.surface, command.platform, command.channel)
                    ?: throw AdminNotFound("pointer ${record.resultRef}")
            },
        ) { token ->
            val outcome = rollbackReserved(command, fingerprint, token)
            // Mesma razao do approve: o cache so pode refletir o ponteiro depois que ele commitou.
            // A lapide do last good na versao nova impede a proxima falha de composicao de servir
            // de volta exatamente o que o rollback removeu.
            runCatching { specCache.put(outcome.target) }
            invalidator.apply(outcome.invalidation)
            outcome.pointer
        }
    }

    private fun rollbackReserved(command: RollbackCommand, fingerprint: String, token: String): RollbackOutcome {
        val pointer = pointerStore.find(command.surface, command.platform, command.channel)
            ?: throw AdminNotFound("pointer")
        val targetId = command.targetSpecRevisionId
            ?: pointer.previousSpecRevisionId
            ?: throw AdminValidation(listOf("sem revisao anterior"))
        val target = specStore.findByRevisionId(targetId) ?: throw AdminNotFound("spec $targetId")
        if (target.status != SpecStatus.PUBLISHED) {
            throw AdminValidation(listOf("alvo nao publicado"))
        }
        if (target.platform != command.platform) {
            throw AdminValidation(listOf("rollback nao cruza plataforma"))
        }
        if (target.surface != command.surface) {
            throw AdminValidation(listOf("rollback nao cruza surface"))
        }
        return tx.execute {
            val now = clock.instant()
            val persisted = pointerStore.compareAndSet(
                pointer.version,
                pointer.copy(
                    specId = target.specId,
                    specRevisionId = target.specRevisionId,
                    previousSpecRevisionId = pointer.specRevisionId,
                    version = pointer.version + 1,
                ),
            )
            auditLog.append(
                AuditEvent(
                    id = UUID.randomUUID().toString(),
                    ts = now,
                    actorId = command.actor.id,
                    role = command.actor.role,
                    action = OPERATION_ROLLBACK,
                    surface = command.surface,
                    platform = command.platform,
                    channel = command.channel,
                    specId = target.specId,
                    fromRevision = pointer.specRevisionId,
                    toRevision = target.specRevisionId,
                    requestId = command.idempotencyKey,
                ),
            )
            val invalidation = invalidationFor(persisted, pointer.specRevisionId, now)
            outbox.record(invalidation)
            idempotency.complete(
                IdempotencyRecord(
                    command.idempotencyKey,
                    OPERATION_ROLLBACK,
                    "${command.surface}:${command.platform.wire()}:${command.channel.wire()}@${persisted.version}",
                    fingerprint,
                ),
                token,
            )
            RollbackOutcome(persisted, target, invalidation)
        }
    }
}

/**
 * O que um rollback produziu: o pointer movido, a revisao que voltou a vigorar e a invalidacao
 * registrada no outbox.
 *
 * Existe para que as invalidacoes de cache acontecam depois do commit e ainda assim saibam qual
 * revisao foi aposentada — dentro da transacao elas publicariam estado que um erro ainda desfaz.
 */
private data class RollbackOutcome(
    val pointer: Pointer,
    val target: Spec,
    val invalidation: CacheInvalidation,
)

/** A invalidacao devida pela mudanca de pointer: lapide na versao nova e revisao aposentada. */
private fun invalidationFor(moved: Pointer, retiredRevisionId: String?, now: Instant): CacheInvalidation =
    CacheInvalidation(
        id = UUID.randomUUID().toString(),
        surface = moved.surface,
        platform = moved.platform,
        channel = moved.channel,
        pointerVersion = moved.version,
        retiredSpecRevisionId = retiredRevisionId?.takeIf { it != moved.specRevisionId },
        createdAt = now,
    )

/**
 * Executa [execute] sob a chave de idempotencia, ou devolve o replay do resultado ja produzido.
 *
 * O registro existente so vale como replay da mesma operacao com os mesmos parametros; qualquer
 * outro uso da chave e recusado. Sem capacidade segura para reservar, a operacao e recusada em vez
 * de arriscar a garantia das reservas vivas.
 */
private inline fun <T> IdempotencyStore.idempotent(
    key: String,
    operation: String,
    fingerprint: String,
    replay: (IdempotencyRecord) -> T,
    execute: (String) -> T,
): T = when (val reservation = reserve(key, operation, fingerprint)) {
    is IdempotencyReservation.Reserved -> releasingOnFailure(key, reservation.token) { execute(reservation.token) }
    is IdempotencyReservation.Existing -> {
        val record = reservation.record
        val sameParameters = record.fingerprint.isEmpty() || record.fingerprint == fingerprint
        if (record.operation != operation || !sameParameters) throw AdminIdempotencyMismatch(key)
        replay(record)
    }

    is IdempotencyReservation.CapacityExhausted ->
        throw AdminUnavailable("registro de idempotencia sem capacidade para novas chaves")
}

/**
 * Executa [work] sobre uma chave ja reservada e a devolve ao pool se a operacao falhar sem efeito.
 *
 * Devolver a chave permite ao operador corrigir o rascunho e reenviar com a mesma chave, em vez de
 * ter de inventar outra — sem isso um 400 de validacao queimaria a chave para sempre. Se a falha
 * vier depois do commit, o registro ja esta fechado e o release nao o desfaz.
 */
private inline fun <T> IdempotencyStore.releasingOnFailure(key: String, token: String, work: () -> T): T =
    try {
        work()
    } catch (error: Throwable) {
        runCatching { release(key, token) }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

/** Resumo estavel dos parametros de uma operacao idempotente. */
private fun fingerprintOf(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

private fun requireRole(actual: ActorRole, vararg allowed: ActorRole) {
    if (actual !in allowed) throw AdminDenied("papel $actual insuficiente")
}

/**
 * Nomes das operacoes idempotentes, iguais aos da trilha de auditoria.
 *
 * Um literal solto em cada chamada deixaria "approve" e "publish.approve" conviverem no mesmo
 * store, e a operacao gravada na chave deixaria de casar com a acao auditada.
 */
private const val OPERATION_OPEN: String = "publish.open"
private const val OPERATION_APPROVE: String = "publish.approve"
private const val OPERATION_REJECT: String = "publish.reject"
private const val OPERATION_ROLLBACK: String = "pointer.rollback"
