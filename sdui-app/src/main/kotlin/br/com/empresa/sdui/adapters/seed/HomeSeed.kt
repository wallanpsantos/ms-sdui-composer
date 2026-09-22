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

    fun seedSkeleton(): Skeleton {
        val skeleton = Skeleton(
            skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("shortcuts", SlotLayout.SHELF, null, 1, listOf("shortcut_shelf"), required = false),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
                SlotDefinition(
                    "cards",
                    SlotLayout.LIST,
                    "Cartão de crédito",
                    3,
                    listOf("card_product"),
                    required = false
                ),
                SlotDefinition("offers", SlotLayout.LIST, "Crédito", 4, listOf("credit_offer"), required = false),
                SlotDefinition("coverage", SlotLayout.LIST, "Seguros", 3, listOf("coverage_card"), required = false),
                SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
            ),
            status = SpecStatus.PUBLISHED,
        )
        return skeletonStore.save(skeleton)
    }

    fun seedCardsFirstSkeleton(): Skeleton {
        val skeleton = Skeleton(
            skeletonId = MvpCatalog.SKELETON_HOME_CARDS_FIRST,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
                SlotDefinition(
                    "cards",
                    SlotLayout.LIST,
                    "Cartão de crédito",
                    3,
                    listOf("card_product"),
                    required = false
                ),
                SlotDefinition("shortcuts", SlotLayout.GRID, null, 1, listOf("shortcut_shelf"), required = false),
                SlotDefinition("offers", SlotLayout.LIST, "Crédito", 4, listOf("credit_offer"), required = false),
                SlotDefinition("coverage", SlotLayout.LIST, "Seguros", 3, listOf("coverage_card"), required = false),
                SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
            ),
            status = SpecStatus.PUBLISHED,
        )
        return skeletonStore.save(skeleton)
    }

    @Suppress("DEPRECATION") // Jackson 3 depreciou isTextual/asText; migrar para isString/asString
    private fun seedIosCurrent(root: JsonNode, skeleton: Skeleton) {
        val envelope = root.get("envelope")
        val sectionsNode = root.get("sections")
        val sections = (0 until sectionsNode.size()).map { index ->
            val node = sectionsNode.get(index)
            val actions = node.get("actions")
            Section(
                id = node.get("id").asText(),
                slot = node.get("slot").asText(),
                type = node.get("type").asText(),
                typeVersion = node.get("typeVersion").asInt(),
                layout = node.get("layout")?.takeIf { it.isTextual }?.asText(),
                props = JsonMaps.toMap(node.get("props")),
                actions = (0 until actions.size()).map { actionIndex ->
                    val action = actions.get(actionIndex)
                    val payloadNode = action.get("payload")
                    Action(
                        id = action.get("id").asText(),
                        type = action.get("type").asText(),
                        label = action.get("label")?.takeIf { it.isTextual }?.asText(),
                        payload = payloadNode?.takeIf { it.isObject }?.let {
                            ActionPayload(
                                route = it.get("route")?.takeIf { node -> node.isTextual }?.asText(),
                                sheet = it.get("sheet")?.takeIf { node -> node.isTextual }?.asText(),
                            )
                        },
                    )
                },
            )
        }
        val targeting = envelope.get("targeting")
        val spec = Spec(
            specId = "spec_home_ios_current",
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
                    requireNotNull(SemVer.parse(targeting.get("appVersionMin").asText())) { "appVersionMin inválido na fixture seed" },
                    SemVer.parse(targeting.get("appVersionMax").asText()),
                ),
                osVersion = VersionRange(
                    requireNotNull(SemVer.parse(targeting.get("osVersionMin").asText())) { "osVersionMin inválido na fixture seed" },
                    null,
                ),
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

    private fun seedIosLegacy() {
        val current = specStore.listPublished(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS)
            .first { it.specId == "spec_home_ios_current" }
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
        val current = specStore.listPublished(MvpCatalog.SURFACE_HOME, ClientPlatform.IOS)
            .first { it.specId == "spec_home_ios_current" }
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

    fun seedPointers() {
        val iosCurrent = specStore.findByRevisionId("rev_01K8HOMEMAIN")
        for (channel in Channel.entries) {
            pointerStore.save(
                Pointer(
                    surface = MvpCatalog.SURFACE_HOME,
                    platform = ClientPlatform.IOS,
                    channel = channel,
                    specId = iosCurrent?.specId,
                    specRevisionId = iosCurrent?.specRevisionId,
                    previousSpecRevisionId = null,
                    version = 1,
                ),
            )
            pointerStore.save(
                Pointer(
                    surface = MvpCatalog.SURFACE_HOME,
                    platform = ClientPlatform.ANDROID,
                    channel = channel,
                    specId = null,
                    specRevisionId = null,
                    previousSpecRevisionId = null,
                    version = 1,
                ),
            )
        }
    }
}
