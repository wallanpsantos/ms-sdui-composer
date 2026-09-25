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
 * Carga inicial (seed) do catálogo, skeletons e especificações da superfície Home (`ADR-021`).
 *
 * ### 1. O que faz
 * Popula os repositórios de governança no bootstrap a partir da fixture JSON canônica de contrato.
 *
 * ### 2. Para que serve
 * Inicializar a aplicação em estado funcional garantindo que o catálogo de componentes, os esqueletos
 * de layout e as especificações ativas reflitam fielmente o contrato acordado com os clientes móveis.
 *
 * ### 3. Como funciona
 * Executa de forma estritamente idempotente ([savePublishedSkeleton], [saveSpecIfAbsent], [savePointerIfAbsent]).
 * Semeia o catálogo [MvpCatalog.TYPES], os skeletons `home.default` e `home.cards_first`, desserializa
 * a fixture para a spec corrente do iOS e gera as specs legada e seguinte. Finalmente, inicializa os
 * ponteiros de publicação para iOS e Android em todos os canais sem sobrescrever dados pré-existentes.
 */
class HomeSeed(
    private val catalogStore: CatalogStore,
    private val skeletonStore: SkeletonStore,
    private val specStore: SpecStore,
    private val pointerStore: PointerStore,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    /**
     * Executa a carga completa do ambiente inicial a partir do JSON da fixture canônica.
     *
     * ### 1. O que faz
     * Coordena o processo sequencial de inicialização de catálogo, skeletons, specs e ponteiros.
     *
     * ### 2. Para que serve
     * Deixar o serviço pronto para compor respostas imediatamente após a inicialização.
     *
     * ### 3. Como funciona
     * Dispara [seedCatalog], [seedSkeleton], [seedCardsFirstSkeleton], faz o parse da árvore JSON
     * com [mapper] e orquestra a persistência idempotente das entidades de domínio.
     */
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

    /**
     * Semeia os tipos de componentes do MVP no catálogo se estiver vazio.
     *
     * ### 1. O que faz
     * Insere os 7 componentes aprovados do catálogo MVP no [catalogStore].
     *
     * ### 2. Para que serve
     * Registrar as capabilities conhecidas que os clientes móveis declaram suportar.
     *
     * ### 3. Como funciona
     * Confere se [catalogStore.current] já contém componentes; caso não possua, salva a lista
     * de [MvpCatalog.TYPES] com status ativo.
     */
    fun seedCatalog() {
        if (catalogStore.current().components.isNotEmpty()) return
        catalogStore.save(
            Catalog(
                MvpCatalog.TYPES.map { cap ->
                    ComponentType(
                        type = cap.type,
                        typeVersion = cap.typeVersion,
                        status = ComponentType.STATUS_ACTIVE,
                        sinceSchema = MvpCatalog.SCHEMA_VERSION,
                        requiredProps = emptyList(),
                    )
                },
            ),
        )
    }

    /**
     * Semeia e retorna o skeleton padrão publicado `home.default`.
     *
     * ### 1. O que faz
     * Salva o skeleton padrão com os 7 slots ordenados contratuais.
     *
     * ### 2. Para que serve
     * Definir a estrutura principal da tela com prateleira de atalhos (`shortcuts` em shelf).
     *
     * ### 3. Como funciona
     * Aciona [savePublishedSkeleton] com [MvpCatalog.SKELETON_HOME_DEFAULT] e a lista de slots.
     */
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

    /**
     * Semeia e retorna o skeleton alternativo publicado `home.cards_first`.
     *
     * ### 1. O que faz
     * Salva o skeleton com cartões antes de atalhos e atalhos em formato de grade (`grid`).
     *
     * ### 2. Para que serve
     * Viabilizar testes de variações estruturais de tela e targeting diferenciado.
     *
     * ### 3. Como funciona
     * Aciona [savePublishedSkeleton] com [MvpCatalog.SKELETON_HOME_CARDS_FIRST] e slots reordenados.
     */
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

    private fun savePublishedSkeleton(skeletonId: String, slots: List<SlotDefinition>): Skeleton {
        skeletonStore.find(skeletonId, 1)?.let { return it }
        val skeleton = Skeleton(
            skeletonId = skeletonId,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = slots,
            status = SpecStatus.PUBLISHED,
        )
        return ifAbsent({ skeletonStore.find(skeletonId, 1) }) { skeletonStore.save(skeleton) }
    }

    /**
     * Grava com [save] e, se a gravacao falhar porque outro processo gravou primeiro, devolve o
     * que ele gravou. Qualquer outra falha sobe.
     */
    private fun <T : Any> ifAbsent(existing: () -> T?, save: () -> T): T = try {
        save()
    } catch (error: RuntimeException) {
        existing() ?: throw error
    }

    private fun saveSpecIfAbsent(spec: Spec) {
        if (specStore.findBySpecIdAndRevision(spec.specId, spec.revision) != null) return
        ifAbsent({ specStore.findBySpecIdAndRevision(spec.specId, spec.revision) }) { specStore.save(spec) }
    }

    private fun seedIosCurrent(root: JsonNode, skeleton: Skeleton) {
        val envelope = root.get("envelope")
        val sectionsNode = root.get("sections")
        val sections = (0 until sectionsNode.size()).map { index -> toSection(sectionsNode.get(index)) }
        val targeting = envelope.get("targeting")
        val schema = requireNotNull(SemVer.parse(MvpCatalog.SCHEMA_VERSION)) { "SCHEMA_VERSION inválido no catálogo" }
        val spec = Spec(
            specId = IOS_CURRENT_SPEC_ID,
            revision = 1,
            specRevisionId = envelope.get("specRevisionId").asString(),
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
                    SemVer.parse(targeting.get("appVersionMax").asString()),
                ),
                osVersion = VersionRange(requiredVersion(targeting, "osVersionMin"), null),
                schemaVersion = VersionRange(schema, schema),
                requiredCapabilities = MvpCatalog.TYPES,
                priority = 100,
                band = targeting.get("band").asString(),
            ),
            sections = sections,
            checksum = envelope.get("skeletonHash").asString(),
            publishedAt = Instant.parse("2026-09-09T20:00:00Z"),
            publishedBy = "seed.checker",
            madeBy = "seed.maker",
            experience = envelope.get("analytics").get("experience").asString(),
        )
        saveSpecIfAbsent(spec)
    }

    private fun toSection(node: JsonNode): Section {
        val actions = node.get("actions")
        return Section(
            id = node.get("id").asString(),
            slot = node.get("slot").asString(),
            type = node.get("type").asString(),
            typeVersion = node.get("typeVersion").asInt(),
            layout = node.get("layout").textOrNull(),
            props = JsonMaps.toMap(node.get("props")),
            actions = (0 until actions.size()).map { index -> toAction(actions.get(index)) },
        )
    }

    private fun toAction(node: JsonNode): Action = Action(
        id = node.get("id").asString(),
        type = node.get("type").asString(),
        label = node.get("label").textOrNull(),
        payload = node.get("payload")?.takeIf { it.isObject }?.let { payload ->
            ActionPayload(
                route = payload.get("route").textOrNull(),
                sheet = payload.get("sheet").textOrNull(),
            )
        },
    )

    private fun requiredVersion(targeting: JsonNode, field: String): SemVer =
        requireNotNull(SemVer.parse(targeting.get(field).asString())) { "$field inválido na fixture seed" }

    private fun seedIosLegacy() {
        val current = iosCurrent()
        saveSpecIfAbsent(
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
        saveSpecIfAbsent(
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
        checkNotNull(specStore.findBySpecIdAndRevision(IOS_CURRENT_SPEC_ID, 1)) { "spec corrente do seed ausente" }

    /**
     * Inicializa os ponteiros de publicação para todos os canais e plataformas caso ainda não existam.
     *
     * ### 1. O que faz
     * Cria os registros de ponteiro inicial para [ClientPlatform.IOS] e [ClientPlatform.ANDROID] em todos os [Channel.entries].
     *
     * ### 2. Para que serve
     * Habilitar o direcionamento imediato das chamadas de composição sem desfazer publicações ou rollbacks já existentes.
     *
     * ### 3. Como funciona
     * Para cada canal, se o ponteiro ainda não existir no [pointerStore], cria o ponteiro versão 1
     * apontando para a spec corrente no iOS e nulo no Android de forma atômica via `compareAndSet(null, pointer)`.
     */
    fun seedPointers() {
        val iosCurrent = specStore.findBySpecIdAndRevision(IOS_CURRENT_SPEC_ID, 1)
        for (channel in Channel.entries) {
            savePointerIfAbsent(initialPointer(ClientPlatform.IOS, channel, iosCurrent))
            savePointerIfAbsent(initialPointer(ClientPlatform.ANDROID, channel, null))
        }
    }

    private fun savePointerIfAbsent(pointer: Pointer) {
        if (pointerStore.find(pointer.surface, pointer.platform, pointer.channel) != null) return
        ifAbsent({ pointerStore.find(pointer.surface, pointer.platform, pointer.channel) }) {
            pointerStore.compareAndSet(null, pointer)
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
        fun JsonNode?.textOrNull(): String? = this?.takeIf { it.isString }?.asString()
    }
}
