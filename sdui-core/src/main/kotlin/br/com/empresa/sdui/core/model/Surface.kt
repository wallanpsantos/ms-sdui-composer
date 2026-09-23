package br.com.empresa.sdui.core.model

/**
 * Regra de um slot dentro de uma surface: quais layouts semanticos ele aceita e se e portante.
 *
 * E a politica que o [br.com.empresa.sdui.core.validate.SkeletonValidator] aplica. O skeleton
 * versionado escolhe um layout e pode estreitar os permitidos, mas nunca alarga-los alem daqui.
 */
data class SlotRule(
    val id: String,
    val allowedLayouts: List<SlotLayout>,
    val required: Boolean,
)

/**
 * Uma surface que o composer serve, com o vocabulario fechado que as validacoes aplicam (ADR-020).
 *
 * Cada surface declara seus slots, quais sao portantes, quais types podem ocupa-la e o evento de
 * analytics do envelope. A lista e finita por construcao: uma surface fora de [Surfaces] e
 * recusada antes de virar chave de cache, tag de metrica ou documento persistido.
 *
 * [contentLocale] registra a politica de locale desta entrega: o conteudo das specs e sintetico e
 * de locale fixo, e o envelope apenas ecoa o locale pedido. Localizar props exige incluir a
 * dimensao na chave de cache antes de ativar a traducao.
 */
data class SurfaceDefinition(
    val id: String,
    val slots: List<SlotRule>,
    val types: Set<String>,
    val firstSlot: String,
    val analyticsEvent: String,
    val contentLocale: String,
    val skeletonLayout: String = MvpCatalog.SKELETON_LAYOUT,
) {
    val slotIds: Set<String> = slots.map { it.id }.toSet()
    val requiredSlots: Set<String> = slots.filter { it.required }.map { it.id }.toSet()
    private val byId: Map<String, SlotRule> = slots.associateBy { it.id }

    fun slot(id: String): SlotRule? = byId[id]
}

/**
 * Allowlist de surfaces. Home preserva as regras do MVP (header e accounts portantes) e ganha o
 * slot opcional `transactions`; catalog e a surface de comercio, sem slot financeiro obrigatorio.
 */
object Surfaces {
    const val HOME_ID: String = "home"
    const val CATALOG_ID: String = "catalog"

    val HOME: SurfaceDefinition = SurfaceDefinition(
        id = HOME_ID,
        slots = listOf(
            SlotRule("header", listOf(SlotLayout.FIXED), required = true),
            SlotRule("shortcuts", listOf(SlotLayout.SHELF, SlotLayout.GRID), required = false),
            SlotRule("accounts", listOf(SlotLayout.LIST, SlotLayout.FIXED), required = true),
            SlotRule("cards", listOf(SlotLayout.LIST, SlotLayout.PAGER), required = false),
            SlotRule("offers", listOf(SlotLayout.LIST, SlotLayout.PAGER), required = false),
            SlotRule("coverage", listOf(SlotLayout.LIST, SlotLayout.SHELF), required = false),
            SlotRule("foryou", listOf(SlotLayout.PAGER, SlotLayout.LIST), required = false),
            SlotRule("transactions", listOf(SlotLayout.LIST), required = false),
        ),
        types = setOf(
            "top_bar", "shortcut_shelf", "account_card", "card_product",
            "credit_offer", "coverage_card", "decision_card", "transaction_summary",
        ),
        firstSlot = "header",
        analyticsEvent = "sdui_home_composed",
        contentLocale = "pt-BR",
    )

    val CATALOG: SurfaceDefinition = SurfaceDefinition(
        id = CATALOG_ID,
        slots = listOf(
            SlotRule("header", listOf(SlotLayout.FIXED), required = true),
            SlotRule("navigation", listOf(SlotLayout.SHELF, SlotLayout.FIXED), required = false),
            SlotRule("featured", listOf(SlotLayout.PAGER, SlotLayout.LIST), required = false),
            SlotRule("products", listOf(SlotLayout.GRID, SlotLayout.LIST), required = true),
        ),
        types = setOf("top_bar", "catalog_navigation", "product_collection"),
        firstSlot = "header",
        analyticsEvent = "sdui_catalog_composed",
        contentLocale = "pt-BR",
    )

    val ALL: List<SurfaceDefinition> = listOf(HOME, CATALOG)

    private val BY_ID: Map<String, SurfaceDefinition> = ALL.associateBy { it.id }

    val IDS: Set<String> = BY_ID.keys

    fun find(id: String?): SurfaceDefinition? = id?.let { BY_ID[it] }

    /**
     * Layouts permitidos por padrao para um id de slot, quando o skeleton nao os declara. Os ids
     * compartilhados entre surfaces (hoje so `header`) tem a mesma regra nas duas.
     */
    fun defaultAllowedLayouts(slotId: String): List<SlotLayout>? =
        ALL.firstNotNullOfOrNull { it.slot(slotId)?.allowedLayouts }
}

/**
 * Contratos de componente aprovados: o universo finito de `type@typeVersion` que a publicacao
 * aceita e que a negociacao reconhece no header `Component-Capabilities` (ADR-020).
 *
 * [LEGACY_HOME] sao os sete types do MVP, que o servidor presume em todo app atual.
 * [APPROVED] acrescenta os contratos novos; eles so chegam a um cliente que os declare, porque a
 * matriz do servidor nao os concede a nenhuma faixa de app.
 */
object ComponentContracts {
    val TRANSACTION_SUMMARY: Capability = Capability("transaction_summary", 1)
    val CATALOG_NAVIGATION: Capability = Capability("catalog_navigation", 1)
    val PRODUCT_COLLECTION: Capability = Capability("product_collection", 1)

    val LEGACY_HOME: Set<Capability> = MvpCatalog.TYPES.toSet()

    val APPROVED: Set<Capability> = LEGACY_HOME + setOf(TRANSACTION_SUMMARY, CATALOG_NAVIGATION, PRODUCT_COLLECTION)

    val APPROVED_TYPE_NAMES: Set<String> = APPROVED.map { it.type }.toSet()

    fun isApproved(type: String, typeVersion: Int): Boolean =
        APPROVED.any { it.type == type && it.typeVersion == typeVersion }
}
