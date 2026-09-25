package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Comportamentos dos adapters em memoria que sustentam as correcoes de 2026-09-23: indices do
 * store de specs (achado 3), teto estrito do cache (achado 6), lapide versionada do last good
 * (ADR-021), compare-and-set do pointer (P05), varredura de projecoes (AGENTS 21.3) e outbox.
 */
class InMemoryStoresBehaviorTest {

    @Test
    fun `store de specs indexa publicadas por surface e plataforma e revisao por id`() {
        val store = InMemorySpecStore()
        store.save(spec("rev_draft", SpecStatus.DRAFT))
        store.save(spec("rev_pub", SpecStatus.PUBLISHED))
        store.save(spec("rev_android", SpecStatus.PUBLISHED, platform = ClientPlatform.ANDROID))
        store.save(spec("rev_catalog", SpecStatus.PUBLISHED, surface = "catalog"))

        assertThat(store.listPublished("home", ClientPlatform.IOS).map { it.specRevisionId }).containsExactly("rev_pub")
        assertThat(
            store.listPublished("catalog", ClientPlatform.IOS).map { it.specRevisionId }).containsExactly("rev_catalog")
        assertThat(store.findByRevisionId("rev_draft")?.status).isEqualTo(SpecStatus.DRAFT)

        // Rascunho regravado com outro specRevisionId sai do indice antigo.
        store.save(spec("rev_draft_2", SpecStatus.DRAFT, specId = "rev_draft"))
        assertThat(store.findByRevisionId("rev_draft")).isNull()
        assertThat(store.findByRevisionId("rev_draft_2")).isNotNull()

        // Aprovacao do rascunho entra no indice de publicadas.
        store.save(checkNotNull(store.findByRevisionId("rev_draft_2")).copy(status = SpecStatus.PUBLISHED))
        assertThat(store.listPublished("home", ClientPlatform.IOS).map { it.specRevisionId })
            .containsExactlyInAnyOrder("rev_pub", "rev_draft_2")
        assertThatThrownBy { store.save(spec("rev_pub", SpecStatus.DRAFT)) }.hasMessageContaining("imutavel")
    }

    @Test
    fun `listagem paginada ordena por spec e revisao`() {
        val store = InMemorySpecStore()
        listOf("c", "a", "b").forEach { store.save(spec("rev_$it", SpecStatus.DRAFT, specId = it)) }
        assertThat(store.list(null, null, PageRequest(0, 2)).map { it.specId }).containsExactly("a", "b")
        assertThat(store.list(null, null, PageRequest(2, 2)).map { it.specId }).containsExactly("c")
        assertThat(store.list(null, null, PageRequest(5, 2))).isEmpty()
    }

    @Test
    fun `pointer so move por compare-and-set na versao lida`() {
        val store = InMemoryPointerStore()
        val first = pointer(version = 1)
        store.compareAndSet(null, first)
        assertThatThrownBy { store.compareAndSet(null, first) }.isInstanceOf(StoreConflict::class.java)

        store.compareAndSet(1, first.copy(specRevisionId = "rev_2", version = 2))
        assertThatThrownBy { store.compareAndSet(1, first.copy(specRevisionId = "rev_3", version = 2)) }
            .isInstanceOf(StoreConflict::class.java)
        assertThat(store.find("home", ClientPlatform.IOS, Channel.STABLE)?.specRevisionId).isEqualTo("rev_2")
    }

    @Test
    fun `last good recusa escrita atrasada abaixo da lapide e aceita a versao nova`() {
        val store = InMemoryLastGoodScreenStore(Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC))
        store.put(screen(pointerVersion = 1, revision = "rev_old"))
        assertThat(store.get("home", ClientPlatform.IOS, Channel.STABLE)?.screen?.specRevisionId).isEqualTo("rev_old")

        store.invalidate("home", ClientPlatform.IOS, Channel.STABLE, pointerVersion = 2)
        assertThat(store.get("home", ClientPlatform.IOS, Channel.STABLE)).isNull()

        // Composicao que comecou sob a versao 1 e terminou depois da publicacao.
        store.put(screen(pointerVersion = 1, revision = "rev_old"))
        assertThat(store.get("home", ClientPlatform.IOS, Channel.STABLE)).isNull()

        store.put(screen(pointerVersion = 2, revision = "rev_new"))
        assertThat(store.get("home", ClientPlatform.IOS, Channel.STABLE)?.screen?.specRevisionId).isEqualTo("rev_new")

        // Reaplicar a mesma invalidacao (relay) nao apaga a arvore ja composta na versao nova.
        store.invalidate("home", ClientPlatform.IOS, Channel.STABLE, pointerVersion = 2)
        assertThat(store.get("home", ClientPlatform.IOS, Channel.STABLE)?.screen?.specRevisionId).isEqualTo("rev_new")
    }

    @Test
    fun `cache de arvore mantem o teto sob escritores concorrentes`() {
        val cache = InMemoryHydratedScreenCache(maxEntries = 1_000)
        val sample = screen(pointerVersion = 1, revision = "rev")
        val maxOccupied = AtomicLong()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(8).let { pool ->
            val tasks = (0 until 8).map { writer ->
                pool.submit {
                    start.await()
                    repeat(20_000) { i ->
                        cache.put("sdui:tree:home:ios:3:rev_$writer-$i:caps:stable", sample, Duration.ofSeconds(60))
                        maxOccupied.accumulateAndGet(cache.occupiedSlots().toLong()) { a, b -> maxOf(a, b) }
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get() }
            pool.shutdown()
        }
        // As vagas limitam as chaves residentes por construcao e sao lidas de forma linearizavel;
        // o size do mapa sob escrita concorrente e estimativa e ja reportou 1.093 neste teto.
        assertThat(maxOccupied.get()).isLessThanOrEqualTo(1_000L)
        assertThat(cache.residentEntries()).isEqualTo(cache.occupiedSlots())

        // Toda remocao devolve a vaga: nada vaza depois da invalidacao.
        cache.invalidate("home", ClientPlatform.IOS, Channel.STABLE)
        assertThat(cache.residentEntries()).isZero()
        assertThat(cache.occupiedSlots()).isZero()
    }

    @Test
    fun `cache de arvore regrava chave residente no teto sem descartar`() {
        val cache = InMemoryHydratedScreenCache(maxEntries = 2)
        val key = "sdui:tree:home:ios:3:rev_a:caps:stable"
        cache.put(key, screen(pointerVersion = 1, revision = "rev_a"), Duration.ofSeconds(60))
        cache.put(
            "sdui:tree:home:ios:3:rev_b:caps:stable",
            screen(pointerVersion = 1, revision = "rev_b"),
            Duration.ofSeconds(60)
        )

        cache.put(key, screen(pointerVersion = 2, revision = "rev_a"), Duration.ofSeconds(60))

        assertThat(cache.get(key)?.pointerVersion).isEqualTo(2)
        assertThat(cache.occupiedSlots()).isEqualTo(2)
        assertThat(cache.skippedWrites()).isZero()
    }

    @Test
    fun `projecao vencida e varrida mesmo sem ser consultada`() {
        var now = 0L
        val store = InMemoryProjectionStore(maxEntries = 100, sweepIntervalMs = 1_000, clockMs = { now })
        repeat(10) { store.put("p", "id$it", mapOf("v" to it), Duration.ofMillis(10)) }
        assertThat(store.residentEntries()).isEqualTo(10)

        now = 5_000
        store.put("p", "fresh", mapOf("v" to 1), Duration.ofSeconds(60))
        assertThat(store.residentEntries()).isEqualTo(1)
    }

    @Test
    fun `auditoria devolve os mais recentes primeiro e outbox mantem ordem de criacao`() {
        val audit = InMemoryAuditLogStore(maxEvents = 3)
        (1..5).forEach { audit.append(event("e$it")) }
        assertThat(audit.recent(2).map { it.id }).containsExactly("e5", "e4")
        assertThat(audit.list().map { it.id }).containsExactly("e3", "e4", "e5")

        val outbox = InMemoryCacheInvalidationOutbox(maxPending = 2)
        (1..2).forEach { outbox.record(invalidation("i$it")) }
        assertThatThrownBy { outbox.record(invalidation("i3")) }.isInstanceOf(StoreConflict::class.java)
        assertThat(outbox.pending(10).map { it.id }).containsExactly("i1", "i2")
        outbox.markApplied("i1")
        outbox.record(invalidation("i3"))
        assertThat(outbox.pending(10).map { it.id }).containsExactly("i2", "i3")
    }

    private fun spec(
        revision: String,
        status: SpecStatus,
        surface: String = "home",
        platform: ClientPlatform = ClientPlatform.IOS,
        specId: String = revision,
    ) = Spec(
        specId = specId,
        revision = 1,
        specRevisionId = revision,
        parentRevision = null,
        status = status,
        surface = surface,
        platform = platform,
        channel = Channel.STABLE,
        skeletonId = "sk",
        skeletonRevision = 1,
        targeting = targeting(platform),
        sections = emptyList(),
        checksum = "sha256:00",
        publishedAt = null,
        publishedBy = null,
        madeBy = "m",
        experience = "e",
    )

    private fun targeting(platform: ClientPlatform) = Targeting(
        platform = platform,
        appVersion = VersionRange(SemVer(8, 0, 0), null),
        osVersion = null,
        schemaVersion = VersionRange(SemVer(3, 0, 0), SemVer(3, 0, 0)),
        requiredCapabilities = emptyList(),
        priority = 1,
        band = "t",
    )

    private fun pointer(version: Long) = Pointer(
        surface = "home",
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        specId = "s",
        specRevisionId = "rev_1",
        previousSpecRevisionId = null,
        version = version,
    )

    private fun screen(pointerVersion: Long, revision: String) = ComposedScreen(
        surface = "home",
        platform = ClientPlatform.IOS,
        schemaVersion = "3",
        specRevisionId = revision,
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        skeletonHash = "sha256:00",
        etag = "W/\"x\"",
        generatedAt = Instant.EPOCH,
        locale = "pt-BR",
        channel = Channel.STABLE,
        fallback = false,
        fallbackReason = FallbackReason.NONE,
        omitted = emptyList(),
        client = ClientContext(
            platform = ClientPlatform.IOS,
            appVersion = SemVer(8, 14, 2),
            build = "1",
            osVersion = null,
            schemaVersion = "3",
            locale = "pt-BR",
            apiVersion = "1",
            headerCapabilities = emptyList(),
        ),
        targeting = targeting(ClientPlatform.IOS),
        experience = "e",
        skeleton = Skeleton(
            MvpCatalog.SKELETON_HOME_DEFAULT,
            1,
            "home",
            MvpCatalog.SKELETON_LAYOUT,
            emptyList(),
            SpecStatus.PUBLISHED
        ),
        sections = emptyList(),
        pointerVersion = pointerVersion,
    )

    private fun event(id: String) = AuditEvent(
        id, Instant.EPOCH, "a", ActorRole.CHECKER, "publish.approve", "home",
        ClientPlatform.IOS, Channel.STABLE, null, null, null, null,
    )

    private fun invalidation(id: String) = CacheInvalidation(
        id, "home", ClientPlatform.IOS, Channel.STABLE, 2, null, Instant.EPOCH,
    )
}
