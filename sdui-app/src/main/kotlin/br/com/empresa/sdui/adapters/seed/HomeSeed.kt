package br.com.empresa.sdui.adapters.seed

import br.com.empresa.sdui.core.model.Action
import br.com.empresa.sdui.core.model.ActionPayload
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Carrega o catalogo, o skeleton e os specs da home a partir da fixture canonica do contrato.
 *
 * Como nao ha persistencia, e o seed que deixa o servico utilizavel ao subir. Partir da mesma
 * fixture que os testes de contrato usam garante que o que sobe e o que foi acordado com as
 * equipes moveis, em vez de uma copia que envelhece em paralelo.
 *
 * Alem da revisao corrente, semeia uma legacy e uma seguinte, para exercitar a selecao por faixa
 * de versao e o canary sem depender de dado montado a mao.
 */
class HomeSeed(
    private val catalogStore: CatalogStore,
    private val skeletonStore: SkeletonStore,
    private val specStore: SpecStore,
    private val pointerStore: PointerStore,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun seedFromCanonicalFixture(fixtureJson: String) {
        seedCatalog()
        val skeleton = seedSkeleton()
        seedCardsFirstSkeleton()
        val root = mapper.readTree(fixtureJson)
        seedIosCurrent(root, skeleton)
        seedIosLegacy()
        seedIosNext()
        seedPointers()
    }

    fun seedCatalog() {
        catalogStore.save(
            Catalog(
                MvpCatalog.TYPES.map { cap ->
                    ComponentType(
                        type = cap.type,
                        typeVersion = cap.typeVersion,
                        status = "ACTIVE",
                        sinceSchema = MvpCatalog.SCHEMA_VERSION,
                        requiredProps = emptyList(),
                    )
                },
            ),
        )
    }

    fun seedSkeleton(): Skeleton = savePublishedSkeleton(
        MvpCatalog.SKELETON_HOME_DEFAULT,
        listOf(
            HEADER_SLOT,
            shortcutsSlot(SlotLayout.SHELF),
            ACCOUNTS_SLOT,
            CARDS_SLOT,
            OFFERS_SLOT,
            COVERAGE_SLOT,
            FORYOU_SLOT,
        ),
    )

    fun seedCardsFirstSkeleton(): Skeleton = savePublishedSkeleton(
        MvpCatalog.SKELETON_HOME_CARDS_FIRST,
        listOf(
            HEADER_SLOT,
            ACCOUNTS_SLOT,
            CARDS_SLOT,
            shortcutsSlot(SlotLayout.GRID),
            OFFERS_SLOT,
            COVERAGE_SLOT,
            FORYOU_SLOT,
        ),
    )

    private fun savePublishedSkeleton(skeletonId: String, slots: List<SlotDefinition>): Skeleton =
        skeletonStore.save(
            Skeleton(
                skeletonId = skeletonId,
                revision = 1,
                surface = MvpCatalog.SURFACE_HOME,
                layout = MvpCatalog.SKELETON_LAYOUT,
                slots = slots,
                status = SpecStatus.PUBLISHED,
            ),
        )

    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    private fun seedIosCurrent(root: JsonNode, skeleton: Skeleton) {
        val envelope = root.get("envelope")
        val sectionsNode = root.get("sections")
        val sections = (0 until sectionsNode.size()).map { index -> toSection(sectionsNode.get(index)) }
        val targeting = envelope.get("targeting")
        val spec = Spec(
            specId = IOS_CURRENT_SPEC_ID,
            revision = 1,
            specRevisionId = envelope.get("specRevisionId").asText(),
            parentRevision = null,
            status = SpecStatus.PUBLISHED,
            surface = MvpCatalog.SURFACE_HOME,
            platform = ClientPlatform.IOS,
            channel = Channel.STABLE,
            skeletonId = skeleton.skeletonId,
            skeletonRevision = skeleton.revision,
            targeting = Targeting(
                platform = ClientPlatform.IOS,
                appVersion = VersionRange(
                    requiredVersion(targeting, "appVersionMin"),
                    SemVer.parse(targeting.get("appVersionMax").asText()),
                ),
                osVersion = VersionRange(requiredVersion(targeting, "osVersionMin"), null),
                schemaVersion = VersionRange(
                    requireNotNull(SemVer.parse(MvpCatalog.SCHEMA_VERSION)) { "SCHEMA_VERSION inválido no catálogo" },
                    SemVer.parse(MvpCatalog.SCHEMA_VERSION),
                ),
                requiredCapabilities = MvpCatalog.TYPES,
                priority = 100,
                band = targeting.get("band").asText(),
            ),
            sections = sections,
            checksum = envelope.get("skeletonHash").asText(),
            publishedAt = Instant.parse("2026-09-09T20:00:00Z"),
            publishedBy = "seed.checker",
            madeBy = "seed.maker",
            experience = envelope.get("analytics").get("experience").asText(),
        )
        specStore.save(spec)
    }

    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    private fun toSection(node: JsonNode): Section {
        val actions = node.get("actions")
        return Section(
            id = node.get("id").asText(),
            slot = node.get("slot").asText(),
            type = node.get("type").asText(),
            typeVersion = node.get("typeVersion").asInt(),
            layout = node.get("layout").textOrNull(),
            props = JsonMaps.toMap(node.get("props")),
            actions = (0 until actions.size()).map { index -> toAction(actions.get(index)) },
        )
    }

    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    private fun toAction(node: JsonNode): Action = Action(
        id = node.get("id").asText(),
        type = node.get("type").asText(),
        label = node.get("label").textOrNull(),
        payload = node.get("payload")?.takeIf { it.isObject }?.let { payload ->
            ActionPayload(
                route = payload.get("route").textOrNull(),
                sheet = payload.get("sheet").textOrNull(),
            )
        },
    )

    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    private fun requiredVersion(targeting: JsonNode, field: String): SemVer =
        requireNotNull(SemVer.parse(targeting.get(field).asText())) { "$field inválido na fixture seed" }

    private fun seedIosLegacy() {
        val current = iosCurrent()
        specStore.save(
            current.copy(
                specId = "spec_home_ios_legacy",
                revision = 1,
                specRevisionId = "rev_01K8HOMELEGACY",
                targeting = current.targeting.copy(
                    appVersion = VersionRange(SemVer(8, 4, 0), SemVer(8, 9, 99)),
                    band = "legacy",
                    priority = 50,
                    requiredCapabilities = listOf(
                        Capability("top_bar", 1),
                        Capability("shortcut_shelf", 1),
                        Capability("account_card", 1),
                    ),
                ),
                experience = "home_ios_legacy",
            ),
        )
    }

    private fun seedIosNext() {
        val current = iosCurrent()
        specStore.save(
            current.copy(
                specId = "spec_home_ios_next",
                revision = 1,
                specRevisionId = "rev_01K8HOMENEXT",
                targeting = current.targeting.copy(
                    appVersion = VersionRange(SemVer(8, 20, 0), null),
                    band = "next",
                    priority = 80,
                ),
                experience = "home_ios_next",
            ),
        )
    }

    private fun iosCurrent(): Spec =
        specStore.listPublished(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS)
            .first { it.specId == IOS_CURRENT_SPEC_ID }

    fun seedPointers() {
        val iosCurrent = specStore.findByRevisionId("rev_01K8HOMEMAIN")
        for (channel in Channel.entries) {
            pointerStore.save(initialPointer(ClientPlatform.IOS, channel, iosCurrent))
            pointerStore.save(initialPointer(ClientPlatform.ANDROID, channel, null))
        }
    }

    private fun initialPointer(platform: ClientPlatform, channel: Channel, spec: Spec?): Pointer = Pointer(
        surface = MvpCatalog.SURFACE_HOME,
        platform = platform,
        channel = channel,
        specId = spec?.specId,
        specRevisionId = spec?.specRevisionId,
        previousSpecRevisionId = null,
        version = 1,
    )

    private companion object {
        const val IOS_CURRENT_SPEC_ID: String = "spec_home_ios_current"

        val HEADER_SLOT = SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true)
        val ACCOUNTS_SLOT =
            SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true)
        val CARDS_SLOT =
            SlotDefinition("cards", SlotLayout.LIST, "Cartão de crédito", 3, listOf("card_product"), required = false)
        val OFFERS_SLOT =
            SlotDefinition("offers", SlotLayout.LIST, "Crédito", 4, listOf("credit_offer"), required = false)
        val COVERAGE_SLOT =
            SlotDefinition("coverage", SlotLayout.LIST, "Seguros", 3, listOf("coverage_card"), required = false)
        val FORYOU_SLOT =
            SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false)

        /** O unico slot que muda entre os dois skeletons, alem da ordem: prateleira ou grade. */
        fun shortcutsSlot(layout: SlotLayout) =
            SlotDefinition("shortcuts", layout, null, 1, listOf("shortcut_shelf"), required = false)

        /** Texto de um campo opcional da fixture; ausente ou de outro tipo vira null. */
        @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
        fun JsonNode?.textOrNull(): String? = this?.takeIf { it.isTextual }?.asText()
    }
}
