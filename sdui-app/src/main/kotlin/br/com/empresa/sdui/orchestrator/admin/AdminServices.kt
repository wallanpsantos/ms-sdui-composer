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

/**
 * Serviço de consulta somente-leitura da trilha de auditoria append-only de governança.
 *
 * ### 1. O que faz
 * Recupera os eventos recentes da trilha de auditoria para usuários autorizados.
 *
 * ### 2. Para que serve
 * Permite que usuários com papéis de conferente ([ActorRole.CHECKER]) ou auditor ([ActorRole.AUDITOR])
 * inspecionem as ações de publicação, aprovação, rejeição e rollback realizadas na plataforma.
 *
 * ### 3. Como funciona
 * Exige estritamente papéis autorizados via [requireRole] e consulta os registros mais recentes no [auditLog].
 *
 * @property auditLog Porta de armazenamento para a trilha append-only de auditoria.
 */
class AuditQueryService(
    private val auditLog: AuditLogStore,
) : AuditQueryUseCase {
    /**
     * Recupera os eventos de auditoria mais recentes até o limite especificado.
     *
     * ### 1. O que faz
     * Lista os registros cronológicos inversos de ações de governança realizadas.
     *
     * ### 2. Para que serve
     * Fornece transparência operacional e rastreabilidade para conformidade regulatória.
     *
     * ### 3. Como funciona
     * Valida a permissão do ator via [requireRole] e consulta os eventos no repositório de auditoria.
     *
     * @param actor Usuário solicitante da consulta com suas credenciais e papéis.
     * @param limit Quantidade máxima de registros a retornar.
     * @return Lista de instâncias de [AuditEvent].
     */
    override fun recent(actor: Actor, limit: Int): List<AuditEvent> {
        requireRole(actor.role, ActorRole.CHECKER, ActorRole.AUDITOR)
        return auditLog.recent(limit)
    }
}

/**
 * Serviço de consulta de governança para o catálogo de componentes, esqueletos, especificações e diffs visuais.
 *
 * ### 1. O que faz
 * Provê métodos de leitura sobre todas as definições estruturais do ecossistema Server-Driven UI.
 *
 * ### 2. Para que serve
 * Permite a inspeção de componentes homologados, layouts ativos, histórico de versões de tela e
 * diferenças estruturais entre revisões sem alterar o estado do sistema.
 *
 * ### 3. Como funciona
 * Delega as leituras aos respectivos repositórios persistentes ([catalogStore], [skeletonStore], [specStore] e [diffStore]).
 *
 * @property catalogStore Porta de consulta do catálogo de componentes.
 * @property skeletonStore Porta de consulta de esqueletos de layout.
 * @property specStore Porta de consulta de especificações de tela.
 * @property diffStore Porta de consulta de diferenças estruturais entre revisões de especificações.
 */
class CatalogQueryService(
    private val catalogStore: CatalogStore,
    private val skeletonStore: SkeletonStore,
    private val specStore: SpecStore,
    private val diffStore: DiffStore,
) : CatalogQueryUseCase {
    /**
     * Obtém a versão vigente do catálogo de componentes.
     *
     * ### 1. O que faz
     * Retorna a entidade agregada [Catalog] contendo todos os componentes registrados.
     *
     * ### 2. Para que serve
     * Permite que editores e ferramentas de governança visualizem componentes homologados e suas capacidades.
     *
     * ### 3. Como funciona
     * Consulta a versão corrente do catálogo via [CatalogStore.current].
     *
     * @return Instância de [Catalog].
     */
    override fun catalog(): Catalog = catalogStore.current()

    /**
     * Busca um esqueleto de layout pelo seu identificador.
     *
     * ### 1. O que faz
     * Retorna o esqueleto de layout correspondente ao identificador fornecido.
     *
     * ### 2. Para que serve
     * Permite inspecionar a estrutura de slots e restrições de uma surface.
     *
     * ### 3. Como funciona
     * Consulta o esqueleto via [SkeletonStore.current] pelo identificador.
     *
     * @param id Identificador do esqueleto.
     * @return O [Skeleton] correspondente ou `null` se inexistente.
     */
    override fun skeleton(id: String): Skeleton? = skeletonStore.current(id)

    /**
     * Lista especificações de tela filtradas por plataforma e canal com suporte a paginação.
     *
     * ### 1. O que faz
     * Recupera páginas de especificações de acordo com os filtros fornecidos.
     *
     * ### 2. Para que serve
     * Alimenta interfaces de administração e governança para navegação do catálogo de telas.
     *
     * ### 3. Como funciona
     * Executa consulta paginada via [SpecStore.list].
     *
     * @param platform Filtro opcional por plataforma.
     * @param channel Filtro opcional por canal.
     * @param page Configuração de paginação solicitada.
     * @return Lista paginada de [Spec].
     */
    override fun specs(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        specStore.list(platform, channel, page)

    /**
     * Lista todas as revisões cadastradas para um mesmo identificador de especificação (`specId`).
     *
     * ### 1. O que faz
     * Retorna o histórico de revisões de uma tela em ordem decrescente.
     *
     * ### 2. Para que serve
     * Permite acompanhar a evolução e o ciclo de vida de uma tela ao longo do tempo.
     *
     * ### 3. Como funciona
     * Recupera as revisões de forma paginada via [SpecStore.listBySpecId].
     *
     * @param specId Identificador lógico da especificação.
     * @param page Parâmetros de paginação.
     * @return Lista paginada de revisões de [Spec].
     */
    override fun revisions(specId: String, page: PageRequest): List<Spec> = specStore.listBySpecId(specId, page)

    /**
     * Recupera a diferença estrutural pré-calculada entre duas revisões de uma mesma especificação.
     *
     * ### 1. O que faz
     * Obtém o objeto [SpecDiff] comparando a revisão de origem e a revisão de destino.
     *
     * ### 2. Para que serve
     * Subsidia a revisão técnica do checker antes de aprovar a publicação de uma nova revisão.
     *
     * ### 3. Como funciona
     * Consulta a diferença estrutural pré-calculada via [DiffStore.find].
     *
     * @param specId Identificador lógico da especificação.
     * @param from Número da revisão base.
     * @param to Número da revisão de destino.
     * @return O [SpecDiff] correspondente ou `null` se não encontrado.
     */
    override fun diff(specId: String, from: Int, to: Int): SpecDiff? = diffStore.find(specId, from, to)
}

/**
 * Serviço de autoria e edição de rascunhos de especificação, esqueleto e catálogo.
 *
 * ### 1. O que faz
 * Cria e atualiza versões em estado de rascunho ([SpecStatus.DRAFT]), validando as entidades
 * contra as regras estruturais e de governança antes da persistência.
 *
 * ### 2. Para que serve
 * Permite que autores (makers) trabalhem em novas telas ou alterações sem afetar versões já
 * publicadas em produção, garantindo feedback imediato de validação.
 *
 * ### 3. Como funciona
 * Valida estritamente cada rascunho com os validadores correspondentes ([SpecValidator],
 * [SkeletonValidator], [CatalogValidator]). Proíbe edições em entidades já publicadas,
 * forçando a evolução através de novas revisões imutáveis.
 *
 * @property specStore Porta de persistência de especificações.
 * @property skeletonStore Porta de persistência de esqueletos.
 * @property catalogStore Porta de persistência do catálogo.
 * @property matrix Matriz de compatibilidade utilizada para validação de capacidades no rascunho.
 */
class DraftService(
    private val specStore: SpecStore,
    private val skeletonStore: SkeletonStore,
    private val catalogStore: CatalogStore,
    private val matrix: CapabilityMatrix,
) : DraftUseCase {
    /**
     * Cria ou atualiza um rascunho de especificação de tela.
     *
     * ### 1. O que faz
     * Valida e grava uma nova revisão de [Spec] com status [SpecStatus.DRAFT].
     *
     * ### 2. Para que serve
     * Permite a preparação de layouts e componentes para submissão posterior ao fluxo maker-checker.
     *
     * ### 3. Como funciona
     * Exige papel de [ActorRole.MAKER] ou [ActorRole.CHECKER], verifica imutabilidade se já publicada,
     * atribui a próxima revisão caso seja nova, valida contra o esqueleto e catálogo e persiste via
     * compare-and-set atômico.
     *
     * @param command Comando contendo a especificação e metadados do autor.
     * @return A [Spec] persistida em status de rascunho.
     */
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

    /**
     * Cria ou atualiza um rascunho de esqueleto de layout.
     *
     * ### 1. O que faz
     * Valida e persiste um [Skeleton] em status de rascunho.
     *
     * ### 2. Para que serve
     * Permite definir e evoluir os slots e a estrutura base de uma surface.
     *
     * ### 3. Como funciona
     * Exige papéis autorizados, recusa alterações caso já publicado, valida a coerência dos slots via
     * [SkeletonValidator.validate] e persiste por compare-and-set.
     *
     * @param command Comando contendo a definição do esqueleto.
     * @return O [Skeleton] persistido.
     */
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

    /**
     * Adiciona ou atualiza a definição de um componente no catálogo homologado.
     *
     * ### 1. O que faz
     * Insere ou substitui um componente no catálogo geral.
     *
     * ### 2. Para que serve
     * Expande os tipos de seções disponíveis para uso na construção de telas Server-Driven UI.
     *
     * ### 3. Como funciona
     * Exige papéis autorizados, substitui o componente anterior pelo par `(type, typeVersion)`,
     * valida a integridade do catálogo com [CatalogValidator.validate] e salva a nova versão.
     *
     * @param command Comando com a definição do componente.
     * @return O [Catalog] atualizado.
     */
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
 * Serviço responsável pelo fluxo de governança maker-checker de publicações (ADR-008).
 *
 * ### 1. O que faz
 * Orquestra as etapas de abertura, aprovação e rejeição de pedidos de publicação de telas,
 * garantindo a movimentação atômica do ponteiro de versão e registro de auditoria.
 *
 * ### 2. Para que serve
 * Impõe o princípio da segregação de funções: o autor (maker) que propõe uma alteração não pode
 * aprová-la em canais de produção ([Channel.CANARY] e [Channel.STABLE]), garantindo que toda mudança
 * seja auditada e validada tecnicamente por um conferente independente ([ActorRole.CHECKER]).
 *
 * ### 3. Como funciona
 * - **Abertura ([open]):** Valida o rascunho, calcula diff e hash de conteúdo revisado ([PublicationFingerprint.of]).
 * - **Aprovação ([approve]):** Checker valida integridade, confere se o conteúdo não foi alterado desde a abertura
 *   (ADR-022), move o ponteiro de versão via compare-and-set atômico no banco, registra auditoria e outbox de cache.
 *   Após o commit, aquece o cache de spec e dispara a invalidação assíncrona (ADR-021).
 * - **Rejeição ([reject]):** Checker recusa a publicação registrando justificativa formal e auditoria.
 *
 * @property specStore Porta de persistência de especificações.
 * @property skeletonStore Porta de persistência de esqueletos.
 * @property catalogStore Porta de persistência do catálogo.
 * @property pointerStore Porta de persistência e compare-and-set do ponteiro.
 * @property publishStore Porta de persistência de pedidos de publicação.
 * @property diffStore Porta de persistência de diffs de especificação.
 * @property auditLog Porta de gravação da trilha de auditoria.
 * @property idempotency Porta de controle e reserva de idempotência.
 * @property specCache Cache de especificações.
 * @property outbox Outbox transacional de invalidações de cache.
 * @property invalidator Executor das invalidações de cache.
 * @property tx Unidade transacional de trabalho.
 * @property matrix Matriz de capacidades para validação técnica.
 * @property clock Relógio do sistema.
 * @property publicationFingerprint Provedor de assinatura de conteúdo revisado.
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

    /**
     * Abre um pedido formal de publicação para um rascunho de especificação.
     *
     * ### 1. O que faz
     * Cria e registra um [PublishRequest] com status [PublishRequestStatus.OPEN].
     *
     * ### 2. Para que serve
     * Submete a proposta de tela para a fila de revisão de conferentes (checkers).
     *
     * ### 3. Como funciona
     * Exige papel [ActorRole.MAKER], gera a chave de idempotência e executa [openReserved] dentro de transação.
     *
     * @param command Comando contendo os dados da especificação e o canal desejado.
     * @return O [PublishRequest] aberto.
     */
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

    /**
     * Executa a lógica transacional de abertura de pedido de publicação sob chave reservada.
     *
     * ### 1. O que faz
     * Valida rascunho, esqueleto e catálogo, calcula o diff e gera a assinatura de conteúdo.
     *
     * ### 2. Para que serve
     * Congela os dados revisados para garantir que alterações posteriores invalidem o pedido.
     *
     * ### 3. Como funciona
     * Valida as entidades, gera o diff via [SpecDiffFactory.diff], calcula o hash com [publicationFingerprint],
     * salva o pedido no [publishStore] e fecha a chave de idempotência.
     *
     * @param command Comando de abertura.
     * @param fingerprint Resumo dos parâmetros.
     * @param token Token de reserva de idempotência.
     * @return O [PublishRequest] persistido.
     */
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
     * Reproduz o resultado de uma operação idempotente anterior a partir do identificador salvo.
     *
     * ### 1. O que faz
     * Recupera e retorna o [PublishRequest] associado ao registro de idempotência.
     *
     * ### 2. Para que serve
     * Garante que retries idênticos recebam a resposta correta sem reexecutar transações no banco.
     *
     * ### 3. Como funciona
     * Inspeciona `record.resultRef` e consulta o pedido no [publishStore].
     *
     * @param record Registro de idempotência prévio.
     * @return O [PublishRequest] previamente persistido.
     */
    private fun replayPublish(record: IdempotencyRecord): PublishRequest {
        val ref = record.resultRef ?: throw AdminInFlight(record.key)
        return publishStore.find(ref) ?: throw AdminNotFound("publish $ref")
    }

    /**
     * Aprova formalmente a publicação de uma especificação de tela.
     *
     * ### 1. O que faz
     * Transiciona o pedido para aprovado, promove a especificação a publicada e avança o ponteiro de versão.
     *
     * ### 2. Para que serve
     * Efetiva a entrega da nova versão de tela para o canal especificado de forma segura e auditável.
     *
     * ### 3. Como funciona
     * Exige papel [ActorRole.CHECKER], executa [approveReserved] dentro de transação no banco e,
     * estritamente após o commit, aquece o cache de spec e dispara a invalidação assíncrona.
     *
     * @param command Comando de decisão contendo identificador do pedido e do checker.
     * @return O [PublishRequest] aprovado.
     */
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
            specCache.warm(outcome.spec)
            invalidator.apply(outcome.invalidation)
            outcome.request
        }
    }

    /**
     * Executa as operações transacionais de aprovação no banco de dados.
     *
     * ### 1. O que faz
     * Realiza a validação cruzada, altera status, avança o ponteiro e registra auditoria e outbox.
     *
     * ### 2. Para que serve
     * Garante atomicidade: se qualquer etapa falhar, nenhuma alteração em ponteiro ou spec persiste.
     *
     * ### 3. Como funciona
     * 1. Confere se o checker não é o próprio maker (salvo em canal interno).
     * 2. Confere se o conteúdo não foi alterado desde a revisão via [publicationFingerprint] (ADR-022).
     * 3. Confere presença de diff obrigatório se houver revisão pai.
     * 4. Altera o status da spec e do esqueleto para publicado.
     * 5. Move o ponteiro via compare-and-set monotônico.
     * 6. Registra evento na trilha append-only de auditoria.
     * 7. Agenda a invalidação de cache no outbox transacional.
     *
     * @param command Comando de decisão.
     * @param fingerprint Resumo dos parâmetros.
     * @param token Token de reserva de idempotência.
     * @return [PublishOutcome] com as entidades geradas.
     */
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
            // Pedido sem hash revisado (anterior ao ADR-022) tambem cai aqui e precisa ser reaberto.
            if (open.reviewedContentHash != publicationFingerprint.of(spec, skeleton)) {
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

    /**
     * Rejeita um pedido de publicação com justificativa técnica ou operacional.
     *
     * ### 1. O que faz
     * Transiciona o status do pedido para [PublishRequestStatus.REJECTED] e registra a justificativa.
     *
     * ### 2. Para que serve
     * Permite ao checker recusar pedidos incorretos ou que violem diretrizes de design e arquitetura.
     *
     * ### 3. Como funciona
     * Exige papel [ActorRole.CHECKER], impede auto-rejeição sem papel apropriado, atualiza o status via
     * compare-and-set, grava o evento na trilha de auditoria e fecha o registro de idempotência no mesmo commit.
     *
     * @param command Comando de decisão contendo a identificação do checker e do pedido.
     * @param reason Justificativa formal para a recusa.
     * @return O [PublishRequest] atualizado.
     */
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
}

/**
 * Entidade de transporte interno com os resultados da transação de aprovação.
 *
 * ### 1. O que faz
 * Encapsula o pedido aprovado, a especificação publicada e os dados de invalidação gerados no commit.
 *
 * ### 2. Para que serve
 * Permite que o aquecimento de cache e a execução das invalidações ocorram estritamente fora da transação.
 *
 * ### 3. Como funciona
 * Reúne [request], [spec] e [invalidation] imutáveis.
 *
 * @property request Pedido com status aprovado.
 * @property spec Especificação publicada.
 * @property invalidation Intenção de invalidação de cache.
 */
private data class PublishOutcome(
    val request: PublishRequest,
    val spec: Spec,
    val invalidation: CacheInvalidation,
)

/**
 * Serviço de reversão atômica de versão de tela (Rollback).
 *
 * ### 1. O que faz
 * Reverte o ponteiro de exibição para uma revisão publicada anterior sem destruir histórico.
 *
 * ### 2. Para que serve
 * Permite restaurar instantaneamente a estabilidade de uma surface diante de incidentes em produção.
 *
 * ### 3. Como funciona
 * Valida se a revisão de destino é compatível e está publicada ([SpecStatus.PUBLISHED]), incrementa a
 * versão do [Pointer] via compare-and-set atômico no banco, registra auditoria e outbox e, após o
 * commit, aquece a spec e invalida o cache de árvore e last-good.
 *
 * @property pointerStore Porta de persistência de ponteiros.
 * @property specStore Porta de persistência de especificações.
 * @property auditLog Porta de gravação da trilha de auditoria.
 * @property idempotency Porta de controle de idempotência.
 * @property specCache Cache de especificações.
 * @property outbox Outbox transacional de invalidações.
 * @property invalidator Executor das invalidações de cache.
 * @property tx Unidade transacional de trabalho.
 * @property clock Relógio do sistema.
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
    /**
     * Executa a reversão do ponteiro de exibição para uma versão anterior estável.
     *
     * ### 1. O que faz
     * Aponta a surface e canal para a revisão anterior preservando o histórico imutável.
     *
     * ### 2. Para que serve
     * Resposta rápida a incidentes de UI sem necessidade de deploy de código.
     *
     * ### 3. Como funciona
     * Exige papel [ActorRole.CHECKER], verifica a allowlist de surfaces via [Surfaces.find], executa
     * sob idempotência, atualiza o ponteiro na transação e aplica invalidações pós-commit.
     *
     * @param command Comando de reversão contendo surface, plataforma, canal e revisão alvo opcional.
     * @return O [Pointer] atualizado.
     */
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
            specCache.warm(outcome.target)
            invalidator.apply(outcome.invalidation)
            outcome.pointer
        }
    }

    /**
     * Executa a lógica transacional do rollback no banco de dados.
     *
     * ### 1. O que faz
     * Valida os critérios de destino e atualiza atomicamente o ponteiro, auditoria e outbox.
     *
     * ### 2. Para que serve
     * Garante que o rollback seja atômico e imune a condições de corrida com aprovações simultâneas.
     *
     * ### 3. Como funciona
     * Carrega o ponteiro atual, resolve a revisão alvo (específica ou `previousSpecRevisionId`),
     * valida compatibilidade de plataforma e surface, avança a versão do ponteiro, registra [AuditEvent]
     * e salva no outbox.
     *
     * @param command Comando de reversão.
     * @param fingerprint Resumo dos parâmetros.
     * @param token Token de reserva.
     * @return [RollbackOutcome] contendo os dados gerados.
     */
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
 * Entidade de transporte interno com os resultados da transação de rollback.
 *
 * ### 1. O que faz
 * Encapsula o ponteiro revertido, a especificação restaurada e a invalidação de cache gerada.
 *
 * ### 2. Para que serve
 * Permite realizar ações pós-commit de cache sem comprometer a transação de banco de dados.
 *
 * ### 3. Como funciona
 * Reúne [pointer], [target] e [invalidation].
 *
 * @property pointer Instância do ponteiro atualizada.
 * @property target Especificação restaurada.
 * @property invalidation Intenção de invalidação a ser despachada.
 */
private data class RollbackOutcome(
    val pointer: Pointer,
    val target: Spec,
    val invalidation: CacheInvalidation,
)

/**
 * Aquece o cache de especificações com a revisão que passou a vigorar, após o commit.
 *
 * ### 1. O que faz
 * Insere a [Spec] no cache de especificações de forma assíncrona/não bloqueante.
 *
 * ### 2. Para que serve
 * Evita miss no cache na primeira requisição que acessar a nova versão da tela.
 *
 * ### 3. Como funciona
 * Executa [SpecCache.put] protegido por `runCatching` para que falhas de cache não interrompam a resposta.
 *
 * @param spec Especificação a ser memorizada em cache.
 */
private fun SpecCache.warm(spec: Spec) {
    runCatching { put(spec) }
}

/**
 * Constrói o registro de invalidação de cache derivado da movimentação de um ponteiro.
 *
 * ### 1. O que faz
 * Produz a entidade [CacheInvalidation] com as coordenadas de chave a serem limpas nos caches.
 *
 * ### 2. Para que serve
 * Garante que a árvore hidratada antiga e o last-good da revisão anterior sejam removidos ou invalidados.
 *
 * ### 3. Como funciona
 * Instancia [CacheInvalidation] preenchendo surface, plataforma, canal, nova versão do ponteiro e revisão aposentada.
 *
 * @param moved Ponteiro recém-atualizado.
 * @param retiredRevisionId Identificador da revisão substituída.
 * @param now Carimbo de tempo da operação.
 * @return Instância de [CacheInvalidation].
 */
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
 * Executa uma operação garantindo semântica estrita de idempotência (at-most-once execution).
 *
 * ### 1. O que faz
 * Gerencia o ciclo de reserva, execução e replay sob uma chave de idempotência.
 *
 * ### 2. Para que serve
 * Evita execução duplicada de operações administrativas críticas diante de retries de rede.
 *
 * ### 3. Como funciona
 * Tenta reservar a chave via [IdempotencyStore.reserve]. Se reservada, executa [execute] liberando em falha;
 * se já existir com os mesmos parâmetros, chama [replay]; se houver divergência, acusa erro.
 *
 * @param key Chave única de idempotência.
 * @param operation Nome da operação executada.
 * @param fingerprint Resumo dos parâmetros.
 * @param replay Função de reprodução de resultado anterior.
 * @param execute Bloco de código a executar caso a chave seja reservada com sucesso.
 * @return O resultado retornado pela execução ou replay.
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
 * Executa um bloco de código garantindo a liberação da reserva de idempotência caso ocorra falha.
 *
 * ### 1. O que faz
 * Devolve a chave ao pool de idempotência caso o bloco lance exceção.
 *
 * ### 2. Para que serve
 * Permite ao operador reenviar a requisição corrigida utilizando a mesma chave após um erro de validação (HTTP 400).
 *
 * ### 3. Como funciona
 * Envolve a execução de [work] em bloco `try-catch`. Em caso de erro, invoca [IdempotencyStore.release]
 * suprimindo eventuais exceções de liberação antes de propagar o erro original.
 *
 * @param key Chave de idempotência.
 * @param token Token da reserva.
 * @param work Ação a ser executada sob a reserva.
 * @return O resultado gerado por [work].
 */
private inline fun <T> IdempotencyStore.releasingOnFailure(key: String, token: String, work: () -> T): T =
    try {
        work()
    } catch (error: Throwable) {
        runCatching { release(key, token) }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

/**
 * Gera um resumo estável SHA-256 a partir dos parâmetros de uma operação idempotente.
 *
 * ### 1. O que faz
 * Concatena as partes fornecidas e calcula o hash hexadecimal SHA-256.
 *
 * ### 2. Para que serve
 * Garante que uma mesma chave de idempotência não seja reaproveitada com parâmetros diferentes.
 *
 * ### 3. Como funciona
 * Junta os textos com o caractere separador `|`, calcula o digest SHA-256 e formata em hexadecimal via [HexFormat].
 *
 * @param parts Parâmetros que caracterizam univocamente a requisição.
 * @return String hexadecimal contendo o hash calculado.
 */
private fun fingerprintOf(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

/**
 * Assegura que o ator possui um dos papéis de segurança requeridos.
 *
 * ### 1. O que faz
 * Valida o papel do usuário contra a lista de papéis permitidos.
 *
 * ### 2. Para que serve
 * Impõe controle de acesso baseado em papéis (RBAC) na camada de administração.
 *
 * ### 3. Como funciona
 * Lança [AdminDenied] caso [actual] não conste no conjunto [allowed].
 *
 * @param actual Papel do ator na requisição.
 * @param allowed Papéis autorizados para a ação.
 */
private fun requireRole(actual: ActorRole, vararg allowed: ActorRole) {
    if (actual !in allowed) throw AdminDenied("papel $actual insuficiente")
}

/**
 * Constantes com os identificadores padronizados das operações administrativas idempotentes.
 *
 * Coincidem rigorosamente com os nomes das ações registradas na trilha de auditoria para garantir
 * consistência e rastreabilidade nos repositórios.
 */
private const val OPERATION_OPEN: String = "publish.open"
private const val OPERATION_APPROVE: String = "publish.approve"
private const val OPERATION_REJECT: String = "publish.reject"
private const val OPERATION_ROLLBACK: String = "pointer.rollback"
