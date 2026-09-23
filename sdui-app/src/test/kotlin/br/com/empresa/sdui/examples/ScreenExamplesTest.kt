package br.com.empresa.sdui.examples

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCacheInvalidationOutbox
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
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
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.adapters.seed.DemoScreensLoader
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.TokenBucketRateLimiter
import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import br.com.empresa.sdui.orchestrator.admin.DraftService
import br.com.empresa.sdui.orchestrator.admin.PublishService
import br.com.empresa.sdui.orchestrator.admin.RollbackService
import br.com.empresa.sdui.orchestrator.compose.ComposeScreenService
import br.com.empresa.sdui.orchestrator.compose.DefaultCanaryPolicy
import br.com.empresa.sdui.orchestrator.hydration.HydrationCoordinator
import br.com.empresa.sdui.orchestrator.hydration.PassThroughHydrator
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Semaphore

/**
 * Os quatro exemplos de docs/examples/screens, do rascunho a resposta (T02, T03, T08, T09, T10).
 *
 * Cada exemplo passa pelo fluxo administrativo real — skeleton e spec como rascunho validado,
 * pedido do maker, aprovacao do checker — e a resposta composta e comparada com o `response.json`
 * versionado, exceto `generatedAt`. A comparacao e semantica (arvore JSON), entao ordem de campos
 * nao importa, mas ordem de slots e de sections importa.
 */
class ScreenExamplesTest {
    private val examplesDir: Path = Path.of(checkNotNull(System.getProperty("sdui.examplesDir")) {
        "sdui.examplesDir ausente: configurado em sdui-app/build.gradle.kts"
    })
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private inner class Demo {
        val clock: Clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
        val specStore = InMemorySpecStore()
        val skeletonStore = InMemorySkeletonStore()
        val catalogStore = InMemoryCatalogStore()
        val pointerStore = InMemoryPointerStore()
        val metrics = RecordingMetrics()
        private val specCache = InMemorySpecCache()
        private val treeCache = InMemoryHydratedScreenCache()
        private val lastGood = InMemoryLastGoodScreenStore(clock)
        private val outbox = InMemoryCacheInvalidationOutbox()
        private val invalidator = CacheInvalidator(specCache, treeCache, lastGood, outbox, metrics)
        private val tx = InMemoryTransactionalUnitOfWork()
        private val idempotency = InMemoryIdempotencyStore(clock)
        private val matrix = CapabilityMatrix()
        private val audit = InMemoryAuditLogStore()
        private val drafts = DraftService(specStore, skeletonStore, catalogStore, matrix)
        private val publish = PublishService(
            specStore, skeletonStore, catalogStore, pointerStore, InMemoryPublishRequestStore(), InMemoryDiffStore(),
            audit, idempotency, specCache, outbox, invalidator, tx, matrix, clock,
        )
        val rollback = RollbackService(pointerStore, specStore, audit, idempotency, specCache, outbox, invalidator, tx, clock)
        val loader = DemoScreensLoader(drafts, publish, specStore, skeletonStore, catalogStore)
        private val service = ComposeScreenService(
            specStore, skeletonStore, pointerStore, specCache, treeCache, lastGood, InMemoryComposeSingleflight(),
            HydrationCoordinator(listOf(PassThroughHydrator()), Semaphore(8), Duration.ofMillis(80), metrics),
            matrix, DefaultCanaryPolicy, TokenBucketRateLimiter(), Bulkhead(8), metrics, clock,
        )
        private val responseMapper = ScreenResponseMapper(mapper)

        init {
            val seed = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
                .use { it.readBytes().decodeToString() }
            HomeSeed(catalogStore, skeletonStore, specStore, pointerStore, mapper).seedFromCanonicalFixture(seed)
        }

        fun compose(platform: String, capabilities: String? = null, surface: SurfaceDefinition = Surfaces.HOME): ComposeResult =
            service.compose(
                ComposeRequest(
                    headers = NegotiateHeaders("3", platform, "8.14.2", "81420", "pt-BR", "1", "18.1", capabilities),
                    identity = "$platform:81420",
                    surface = surface,
                ),
            )

        fun json(result: ComposeResult): JsonNode {
            check(result is ComposeResult.Success) { "esperado sucesso, veio $result" }
            return mapper.readTree(mapper.writeValueAsString(responseMapper.toResponse(result.screen)))
        }
    }

    @Test
    fun `montagem com atalhos antes dos cartoes e publicada pela governanca e bate com o exemplo`() {
        val demo = Demo()
        val outcome = demo.loader.load("banking.shortcuts_first")

        assertThat(outcome.published).isTrue()
        assertMatches(demo.json(demo.compose("android")), "banking.shortcuts_first/response.json")
    }

    @Test
    fun `montagem com cartoes antes dos atalhos troca so ordem e layout e o rollback volta a anterior`() {
        val demo = Demo()
        demo.loader.load("banking.shortcuts_first")
        val shortcutsFirst = demo.json(demo.compose("android"))
        demo.loader.load("banking.cards_first")
        val cardsFirst = demo.json(demo.compose("android"))
        assertMatches(cardsFirst, "banking.cards_first/response.json")

        // As duas montagens sao duas specs da mesma surface: o pointer decide qual vale.
        val pointer = checkNotNull(demo.pointerStore.find(MvpCatalog.SURFACE_HOME, ClientPlatform.ANDROID, Channel.STABLE))
        assertThat(pointer.specRevisionId).isEqualTo("rev_demo_android_cards_first")
        assertThat(pointer.previousSpecRevisionId).isEqualTo("rev_demo_android_shortcuts_first")

        assertThat(slotOrder(shortcutsFirst)).containsExactly("header", "accounts", "shortcuts", "cards", "offers", "coverage")
        assertThat(slotOrder(cardsFirst)).containsExactly("header", "accounts", "cards", "shortcuts", "offers", "coverage", "foryou")
        assertThat(sectionSlots(shortcutsFirst)).containsSubsequence("shortcuts", "cards")
        assertThat(sectionSlots(cardsFirst)).containsSubsequence("cards", "shortcuts")
        assertThat(slotLayout(shortcutsFirst, "shortcuts")).isEqualTo("shelf")
        assertThat(slotLayout(cardsFirst, "shortcuts")).isEqualTo("grid")
        // Nenhuma das duas exige capability nova: as duas servem o mesmo app Android.
        assertThat(sectionTypes(shortcutsFirst).toSet()).isEqualTo(sectionTypes(cardsFirst).toSet())
        assertThat(sectionTypes(cardsFirst)).allMatch { type -> MvpCatalog.TYPES.any { it.type == type } }

        demo.rollback.rollback(
            RollbackCommand(Actor("checker", ActorRole.CHECKER), "home", ClientPlatform.ANDROID, Channel.STABLE, null, "rb-demo", "teste"),
        )
        assertMatches(demo.json(demo.compose("android")), "banking.shortcuts_first/response.json")
    }

    @Test
    fun `home com resumo de transacoes entrega o componente a quem declara e omite para quem nao declara`() {
        val demo = Demo()
        demo.loader.load("banking.transactions")

        assertMatches(demo.json(demo.compose("ios", "transaction_summary@1")), "banking.transactions/response.json")
        val withoutCapability = demo.json(demo.compose("ios"))
        assertMatches(withoutCapability, "banking.transactions/response-without-capability.json")
        val omitted = withoutCapability.get("envelope").get("omitted")
        assertThat(omitted.size()).isEqualTo(1)
        assertThat(omitted.get(0).get("type").asString()).isEqualTo("transaction_summary")
        assertThat(omitted.get(0).get("reason").asString()).isEqualTo("unsupported_type")
    }

    @Test
    fun `catalogo de moda compoe na surface propria sem tocar a Home`() {
        val demo = Demo()
        val homeBefore = demo.json(demo.compose("ios")).get("envelope").get("specRevisionId").asString()
        demo.loader.load("fashion.catalog")

        val catalog = demo.json(demo.compose("ios", "catalog_navigation@1,product_collection@1", Surfaces.CATALOG))
        assertMatches(catalog, "fashion.catalog/response.json")
        assertThat(catalog.get("envelope").get("analytics").get("event").asString()).isEqualTo("sdui_catalog_composed")

        val homeAfter = demo.json(demo.compose("ios")).get("envelope").get("specRevisionId").asString()
        assertThat(homeAfter).isEqualTo(homeBefore)
    }

    @Test
    fun `catalogo sem capability de navegacao omite a secao e sem vitrine nao recebe arvore nenhuma`() {
        val demo = Demo()
        demo.loader.load("fashion.catalog")

        val semNavegacao = demo.json(demo.compose("ios", "product_collection@1", Surfaces.CATALOG))
        assertThat(sectionTypes(semNavegacao)).doesNotContain("catalog_navigation")
        assertThat(semNavegacao.get("envelope").get("omitted").get(0).get("type").asString()).isEqualTo("catalog_navigation")

        // Sem product_collection o spec nao serve (capability exigida no targeting): 503 sem
        // arvore de fallback — a surface nova nunca recebe a Home.
        val semVitrine = demo.compose("ios", null, Surfaces.CATALOG)
        assertThat(semVitrine).isInstanceOf(ComposeResult.Unavailable::class.java)
        assertThat((semVitrine as ComposeResult.Unavailable).reason).isEqualTo(FallbackReason.NO_COMPATIBLE_SPEC)
    }

    @Test
    fun `carga demo e idempotente e publica os quatro exemplos na ordem documentada`() {
        val demo = Demo()
        val first = demo.loader.loadAll()
        assertThat(first.map { it.published }).containsOnly(true)
        assertThat(first.map { it.example }).containsExactlyElementsOf(DemoScreensLoader.EXAMPLES)

        val second = demo.loader.loadAll()
        assertThat(second.map { it.published }).containsOnly(false)
        assertThat(checkNotNull(demo.pointerStore.find("catalog", ClientPlatform.IOS, Channel.STABLE)).specRevisionId)
            .isEqualTo("rev_demo_ios_fashion_catalog")
    }

    @Test
    fun `entradas do modo demo sao copias identicas dos exemplos documentados`() {
        for (example in DemoScreensLoader.EXAMPLES) {
            for (file in listOf("skeleton.json", "spec.json")) {
                val documented = mapper.readTree(Files.readString(examplesDir.resolve(example).resolve(file)))
                val resource = checkNotNull(javaClass.getResourceAsStream("/demo/screens/$example/$file")) {
                    "recurso demo ausente: $example/$file"
                }.use { mapper.readTree(it) }
                assertThat(resource).`as`("%s/%s", example, file).isEqualTo(documented)
            }
        }
    }

    private fun assertMatches(actual: JsonNode, expectedFile: String) {
        val expected = mapper.readTree(Files.readString(examplesDir.resolve(expectedFile)))
        assertThat(withoutGeneratedAt(actual)).`as`(expectedFile).isEqualTo(withoutGeneratedAt(expected))
    }

    private fun withoutGeneratedAt(node: JsonNode): JsonNode {
        val copy = node.deepCopy() as ObjectNode
        (copy.get("envelope") as ObjectNode).remove("generatedAt")
        return copy
    }

    private fun slotOrder(response: JsonNode): List<String> =
        elements(response.get("skeleton").get("slots")).map { it.get("id").asString() }

    private fun slotLayout(response: JsonNode, slot: String): String =
        elements(response.get("skeleton").get("slots")).first { it.get("id").asString() == slot }.get("layout").asString()

    private fun sectionSlots(response: JsonNode): List<String> =
        elements(response.get("sections")).map { it.get("slot").asString() }

    private fun sectionTypes(response: JsonNode): List<String> =
        elements(response.get("sections")).map { it.get("type").asString() }

    private fun elements(array: JsonNode): List<JsonNode> = (0 until array.size()).map { array.get(it) }
}
