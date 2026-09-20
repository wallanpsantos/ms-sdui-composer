package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryDiffStore
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryIdempotencyStore
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemoryPublishRequestStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.memory.InMemoryTransactionalUnitOfWork
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.StoredScreen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Cobre a idempotencia do plano administrativo e a invalidacao do last good (ADR-014).
 *
 * Sao as duas correcoes que nao aparecem numa leitura casual do codigo: consultar-e-gravar deixava
 * dois retries concorrentes executarem o efeito duas vezes, e a publicacao invalidava o cache de
 * arvore sem tocar no last good, de onde a revisao retirada voltaria na proxima falha.
 */
class AdminIdempotencyTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    /** Last good que anota as invalidacoes, para o teste afirmar que elas aconteceram. */
    private class RecordingLastGood(private val delegate: LastGoodScreenStore) : LastGoodScreenStore {
        val invalidated = mutableListOf<String>()

        override fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen? =
            delegate.get(surface, platform, channel)

        override fun put(screen: ComposedScreen) = delegate.put(screen)

        override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel) {
            invalidated += "$surface:${platform.wire()}:${channel.wire()}"
            delegate.invalidate(surface, platform, channel)
        }
    }

    private class Governance {
        val clock = MutableClock(Instant.parse("2026-09-20T12:00:00Z"))
        val specStore = InMemorySpecStore()
        val skeletonStore = InMemorySkeletonStore()
        val catalogStore = InMemoryCatalogStore()
        val pointerStore = InMemoryPointerStore()
        val publishStore = InMemoryPublishRequestStore()
        val diffStore = InMemoryDiffStore()
        val auditLog = InMemoryAuditLogStore()
        val idempotency = InMemoryIdempotencyStore(clock)
        val specCache = InMemorySpecCache()
        val treeCache = InMemoryHydratedScreenCache()
        val lastGood = RecordingLastGood(InMemoryLastGoodScreenStore(clock))
        val matrix = CapabilityMatrix()
        private val tx = InMemoryTransactionalUnitOfWork()

        val drafts = DraftService(specStore, skeletonStore, catalogStore, matrix)

        val publish = PublishService(
            specStore, skeletonStore, catalogStore, pointerStore, publishStore, diffStore,
            auditLog, idempotency, specCache, treeCache, lastGood, tx, matrix, clock,
        )

        val rollback = RollbackService(
            pointerStore, specStore, auditLog, idempotency, specCache, treeCache, lastGood, tx, clock,
        )

        init {
            val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
            val json = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
                .use { it.readBytes().decodeToString() }
            HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, mapper).seedFromCanonicalFixture(json)
        }

        /** Um rascunho novo a partir da revisao canonica, pronto para abrir publicacao. */
        fun draft(specId: String): Spec {
            val current = checkNotNull(specStore.findByRevisionId("rev_01K8HOMEMAIN"))
            return drafts.createSpecDraft(
                DraftSpecCommand(
                    Actor("maker-1", ActorRole.MAKER),
                    current.copy(
                        specId = specId,
                        revision = specStore.nextRevision(specId),
                        specRevisionId = "rev_${UUID.randomUUID()}",
                        status = SpecStatus.DRAFT,
                        parentRevision = null,
                    ),
                ),
            )
        }
    }

    @Test
    fun `open concorrente com a mesma chave produz um unico pedido`() {
        val gov = Governance()
        val draft = gov.draft("spec_home_ios_concorrente")
        val command = OpenPublishCommand(
            actor = Actor("maker-1", ActorRole.MAKER),
            specId = draft.specId,
            revision = draft.revision,
            channel = Channel.STABLE,
            idempotencyKey = "open-concorrente",
        )

        val start = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val tasks = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    runCatching { gov.publish.open(command) }
                },
            )
        }
        start.countDown()
        val results = tasks.map { it.get() }
        pool.close()

        val criados = results.mapNotNull { it.getOrNull() }.map(PublishRequest::requestId).toSet()
        assertThat(criados).hasSize(1)
        // Quem perdeu a reserva ou recebeu o mesmo resultado, ou foi recusado por estar em voo —
        // em nenhum caso abriu um segundo pedido para a mesma revisao.
        assertThat(results.count { it.isSuccess }).isGreaterThanOrEqualTo(1)
        assertThat(gov.publishStore.find(criados.first())).isNotNull()
    }

    @Test
    fun `chave e devolvida quando a operacao falha e pode ser reusada`() {
        val gov = Governance()
        val chave = "open-falha-1"

        val falha = runCatching {
            gov.publish.open(
                OpenPublishCommand(
                    actor = Actor("maker-1", ActorRole.MAKER),
                    specId = "spec_que_nao_existe",
                    revision = 1,
                    channel = Channel.STABLE,
                    idempotencyKey = chave,
                ),
            )
        }
        assertThat(falha.isFailure).isTrue()
        // Sem o release, a chave ficaria queimada e o maker teria de inventar outra depois de
        // corrigir o rascunho.
        assertThat(gov.idempotency.find(chave)).isNull()

        val draft = gov.draft("spec_home_ios_reuso")
        val aberto = gov.publish.open(
            OpenPublishCommand(
                actor = Actor("maker-1", ActorRole.MAKER),
                specId = draft.specId,
                revision = draft.revision,
                channel = Channel.STABLE,
                idempotencyKey = chave,
            ),
        )
        assertThat(aberto.requestId).isNotBlank()
        assertThat(gov.idempotency.find(chave)?.resultRef).isEqualTo(aberto.requestId)
    }

    @Test
    fun `approve invalida o last good alem do cache de arvore`() {
        val gov = Governance()
        val draft = gov.draft("spec_home_ios_approve")
        val aberto = gov.publish.open(
            OpenPublishCommand(
                actor = Actor("maker-1", ActorRole.MAKER),
                specId = draft.specId,
                revision = draft.revision,
                channel = Channel.STABLE,
                idempotencyKey = "open-approve",
            ),
        )

        gov.publish.approve(
            DecidePublishCommand(Actor("checker-1", ActorRole.CHECKER), aberto.requestId, "approve-1"),
        )

        assertThat(gov.lastGood.invalidated).contains("${MvpCatalog.SURFACE_HOME}:ios:stable")
    }

    @Test
    fun `rollback invalida o last good da combinacao movida`() {
        val gov = Governance()

        gov.rollback.rollback(
            RollbackCommand(
                actor = Actor("checker-1", ActorRole.CHECKER),
                surface = MvpCatalog.SURFACE_HOME,
                platform = ClientPlatform.IOS,
                channel = Channel.STABLE,
                targetSpecRevisionId = "rev_01K8HOMELEGACY",
                idempotencyKey = "rb-lastgood-1",
                reason = "teste",
            ),
        )

        assertThat(gov.lastGood.invalidated).contains("${MvpCatalog.SURFACE_HOME}:ios:stable")
        assertThat(gov.pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS, Channel.STABLE)?.specRevisionId)
            .isEqualTo("rev_01K8HOMELEGACY")
    }

    @Test
    fun `reserva de chave e exclusiva, expira e respeita o teto`() {
        val clock = MutableClock(Instant.parse("2026-09-20T12:00:00Z"))
        val store = InMemoryIdempotencyStore(clock, ttl = Duration.ofHours(1), maxEntries = 4)

        assertThat(store.reserve("k1", "publish.open")).isTrue()
        assertThat(store.reserve("k1", "publish.open")).isFalse()
        assertThat(store.find("k1")?.resultRef).isNull()

        store.complete(IdempotencyRecord("k1", "publish.open", "pr_1"))
        assertThat(store.find("k1")?.resultRef).isEqualTo("pr_1")
        // release nunca desfaz resultado ja fechado: e ele que torna o retry idempotente.
        store.release("k1")
        assertThat(store.find("k1")?.resultRef).isEqualTo("pr_1")

        clock.now = clock.now.plus(Duration.ofHours(2))
        assertThat(store.find("k1")).isNull()
        assertThat(store.reserve("k1", "publish.open")).isTrue()

        // O mapa e alimentado por header: precisa de teto, como o cache de arvore e o limitador.
        repeat(40) { store.complete(IdempotencyRecord("bulk-$it", "publish.open", "pr_$it")) }
        assertThat(store.reserve("gatilho-da-poda", "publish.open")).isTrue()
        assertThat(store.residentEntries()).isLessThanOrEqualTo(40)
    }
}
