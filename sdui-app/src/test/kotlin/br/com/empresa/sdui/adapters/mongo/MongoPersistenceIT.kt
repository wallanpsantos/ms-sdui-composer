package br.com.empresa.sdui.adapters.mongo

import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.port.inbound.DecidePublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyReservation
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Adapters MongoDB contra um banco real (P03–P07, P11). So roda com `SDUI_IT_MONGO_URI` apontando
 * para um replica set — standalone nao tem transacao multi-documento. Cada teste usa um banco
 * descartavel, removido ao final.
 *
 * Localmente: `docker compose --profile infra up -d` e
 * `SDUI_IT_MONGO_URI=mongodb://localhost:27017/?replicaSet=rs0&directConnection=true`.
 */
@EnabledIfEnvironmentVariable(named = "SDUI_IT_MONGO_URI", matches = ".+")
class MongoPersistenceIT {
    companion object {
        private lateinit var client: MongoClient

        @JvmStatic
        @BeforeAll
        fun connect() {
            client = MongoClients.create(System.getenv("SDUI_IT_MONGO_URI"))
        }

        @JvmStatic
        @AfterAll
        fun disconnect() {
            client.close()
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var database: MongoDatabase
    private val clock = MutableClock(Instant.parse("2026-09-23T12:00:00Z"))
    private val sessions = MongoSessionContext()

    /** Um conjunto novo de adapters sobre o mesmo banco: o equivalente a um restart ou a outro pod. */
    private inner class Instance {
        val specStore = MongoSpecStore(database, sessions, 1_048_576)
        val skeletonStore = MongoSkeletonStore(database, sessions, 1_048_576)
        val catalogStore = MongoCatalogStore(database, sessions, 1_048_576)
        val pointerStore = MongoPointerStore(database, sessions)
        val publishStore = MongoPublishRequestStore(database, sessions)
        val diffStore = MongoDiffStore(database, sessions, 1_048_576)
        val auditLog = MongoAuditLogStore(database, sessions)
        val idempotency = MongoIdempotencyStore(database, sessions, clock, Duration.ofHours(24), Duration.ofMinutes(5))
        val outbox = MongoCacheInvalidationOutbox(database, sessions)
        val tx = MongoTransactionalUnitOfWork(client, sessions)
        private val specCache = InMemorySpecCache()
        private val invalidator = CacheInvalidator(
            specCache, InMemoryHydratedScreenCache(), InMemoryLastGoodScreenStore(clock), outbox, RecordingMetrics(),
        )
        private val matrix = CapabilityMatrix()
        val drafts = DraftService(specStore, skeletonStore, catalogStore, matrix)
        val publish = PublishService(
            specStore, skeletonStore, catalogStore, pointerStore, publishStore, diffStore,
            auditLog, idempotency, specCache, outbox, invalidator, tx, matrix, clock,
        )
        val rollback = RollbackService(pointerStore, specStore, auditLog, idempotency, specCache, outbox, invalidator, tx, clock)

        fun seed() {
            val json = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
                .use { it.readBytes().decodeToString() }
            HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
                .seedFromCanonicalFixture(json)
        }
    }

    @BeforeEach
    fun createDatabase() {
        database = client.getDatabase("sdui_it_${UUID.randomUUID().toString().take(8)}")
        MongoSchema.ensureIndexes(database)
    }

    @AfterEach
    fun dropDatabase() {
        database.drop()
    }

    @Test
    fun `specs fazem round trip, publicada e imutavel e revisao e unica`() {
        val instance = Instance()
        instance.seed()
        val spec = checkNotNull(instance.specStore.findByRevisionId("rev_01K8HOMEMAIN"))
        assertThat(instance.specStore.listPublished("home", ClientPlatform.IOS).map { it.specRevisionId })
            .contains("rev_01K8HOMEMAIN", "rev_01K8HOMELEGACY", "rev_01K8HOMENEXT")
        assertThatThrownBy { instance.specStore.save(spec.copy(experience = "adulterada")) }.hasMessageContaining("imutavel")
        assertThatThrownBy { instance.specStore.save(spec.copy(specId = "outro", status = SpecStatus.DRAFT)) }
            .hasMessageContaining("specRevisionId")
        assertThat(Instance().specStore.findByRevisionId("rev_01K8HOMEMAIN")).isEqualTo(spec)
    }

    @Test
    fun `pointer so move por compare-and-set e duas instancias nao se atropelam`() {
        val a = Instance()
        val b = Instance()
        a.seed()
        val current = checkNotNull(a.pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE))
        a.pointerStore.compareAndSet(current.version, current.copy(specRevisionId = "rev_01K8HOMENEXT", version = current.version + 1))
        assertThatThrownBy {
            b.pointerStore.compareAndSet(current.version, current.copy(specRevisionId = "rev_01K8HOMELEGACY", version = current.version + 1))
        }.isInstanceOf(StoreConflict::class.java)
        assertThat(b.pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE)?.specRevisionId).isEqualTo("rev_01K8HOMENEXT")
    }

    @Test
    fun `transicao de pedido tem um unico vencedor sob concorrencia`() {
        val instance = Instance()
        val open = PublishRequest("pr_1", "s", 1, "rev", "home", ClientPlatform.IOS, Channel.STABLE, "maker", PublishRequestStatus.OPEN)
        instance.publishStore.save(open)
        val winners = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            pool.invokeAll((1..8).map { i ->
                Callable {
                    Instance().publishStore.compareAndSetStatus(
                        "pr_1",
                        PublishRequestStatus.OPEN,
                        open.copy(status = PublishRequestStatus.APPROVED, checkerId = "checker-$i"),
                    )
                }
            }).mapNotNull { it.get() }
        }
        assertThat(winners).hasSize(1)
    }

    @Test
    fun `idempotencia reserva uma vez, sobrevive a restart e respeita o prazo de reserva`() {
        val first = Instance()
        assertThat(first.idempotency.reserve("k", "publish.open", "fp")).isEqualTo(IdempotencyReservation.Reserved)
        assertThat(Instance().idempotency.reserve("k", "publish.open", "fp")).isInstanceOf(IdempotencyReservation.Existing::class.java)

        first.idempotency.complete(IdempotencyRecord("k", "publish.open", "pr_1", "fp"))
        first.idempotency.release("k")
        val afterRestart = Instance().idempotency.find("k")
        assertThat(afterRestart?.resultRef).isEqualTo("pr_1")
        assertThat(afterRestart?.fingerprint).isEqualTo("fp")

        assertThat(first.idempotency.reserve("abandonada", "pointer.rollback", "fp")).isEqualTo(IdempotencyReservation.Reserved)
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        assertThat(Instance().idempotency.reserve("abandonada", "pointer.rollback", "fp")).isEqualTo(IdempotencyReservation.Reserved)
    }

    @Test
    fun `falha injetada na transacao nao deixa efeito parcial`() {
        val instance = Instance()
        instance.seed()
        val pointer = checkNotNull(instance.pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE))
        assertThatThrownBy {
            instance.tx.execute {
                instance.pointerStore.compareAndSet(pointer.version, pointer.copy(specRevisionId = "rev_01K8HOMENEXT", version = pointer.version + 1))
                instance.auditLog.append(
                    AuditEvent("e1", clock.instant(), "c", ActorRole.CHECKER, "pointer.rollback", "home", ClientPlatform.IOS, Channel.STABLE, null, null, null, null),
                )
                instance.idempotency.complete(IdempotencyRecord("tx-key", "pointer.rollback", "ref", "fp"))
                error("falha injetada depois das escritas")
            }
        }.hasMessageContaining("falha injetada")

        assertThat(instance.pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE)).isEqualTo(pointer)
        assertThat(instance.auditLog.recent(10)).isEmpty()
        assertThat(instance.idempotency.find("tx-key")).isNull()
    }

    @Test
    fun `publicacao e rollback pela governanca persistem e o segundo boot nao sobrescreve`() {
        val first = Instance()
        first.seed()
        val current = checkNotNull(first.specStore.findByRevisionId("rev_01K8HOMEMAIN"))
        val draft = first.drafts.createSpecDraft(
            DraftSpecCommand(
                Actor("maker-1", ActorRole.MAKER),
                current.copy(specId = "spec_it", revision = 1, specRevisionId = "rev_it_1", status = SpecStatus.DRAFT, parentRevision = null),
            ),
        )
        val opened = first.publish.open(OpenPublishCommand(Actor("maker-1", ActorRole.MAKER), draft.specId, draft.revision, Channel.STABLE, "it-open"))
        first.publish.approve(DecidePublishCommand(Actor("checker-1", ActorRole.CHECKER), opened.requestId, "it-approve"))
        assertThat(first.outbox.pending(10)).isEmpty()

        // Segundo boot sobre o mesmo banco: o seed encontra tudo e nao devolve o pointer ao seed.
        val second = Instance()
        second.seed()
        assertThat(second.pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS, Channel.STABLE)?.specRevisionId)
            .isEqualTo("rev_it_1")
        assertThat(second.catalogStore.current().components).hasSize(MvpCatalog.TYPES.size)

        clock.now = clock.now.plusSeconds(1)
        second.rollback.rollback(
            RollbackCommand(Actor("checker-1", ActorRole.CHECKER), "home", ClientPlatform.IOS, Channel.STABLE, null, "it-rb", "teste"),
        )
        assertThat(Instance().pointerStore.find("home", ClientPlatform.IOS, Channel.STABLE)?.specRevisionId)
            .isEqualTo("rev_01K8HOMEMAIN")
        assertThat(Instance().auditLog.recent(10).map { it.action }).containsExactly("pointer.rollback", "publish.approve")
    }

    @Test
    fun `outbox guarda invalidacao ate ser aplicada, visivel de outra instancia`() {
        val instance = Instance()
        instance.outbox.record(CacheInvalidation("inv-1", "home", ClientPlatform.IOS, Channel.STABLE, 2, null, clock.instant()))
        assertThat(Instance().outbox.pending(10).map { it.id }).containsExactly("inv-1")
        Instance().outbox.markApplied("inv-1")
        assertThat(instance.outbox.pending(10)).isEmpty()
    }
}
