package br.com.empresa.sdui.core.model

data class Capability(
    val type: String,
    val typeVersion: Int,
) {
    init {
        require(type.isNotBlank()) { "capability type must not be blank" }
        require(typeVersion > 0) { "capability typeVersion must be > 0" }
    }

    fun wire(): String = "$type@$typeVersion"

    companion object {
        fun parse(raw: String): Capability? {
            val parts = raw.trim().split("@")
            if (parts.size != 2) return null
            val type = parts[0].trim()
            val version = parts[1].trim().toIntOrNull() ?: return null
            if (type.isBlank() || version <= 0) return null
            return Capability(type, version)
        }

        fun parseList(header: String?): List<Capability> {
            if (header.isNullOrBlank()) return emptyList()
            return header.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parse(it) }
        }
    }
}

object MvpCatalog {
    const val SCHEMA_VERSION: String = "3"
    const val SURFACE_HOME: String = "home"
    const val SKELETON_HOME_DEFAULT: String = "home.default"
    const val SKELETON_LAYOUT: String = "vertical_scroll"

    val TYPES: List<Capability> = listOf(
        Capability("top_bar", 1),
        Capability("shortcut_shelf", 1),
        Capability("account_card", 1),
        Capability("card_product", 1),
        Capability("credit_offer", 1),
        Capability("coverage_card", 1),
        Capability("decision_card", 1),
    )

    val TYPE_NAMES: Set<String> = TYPES.map { it.type }.toSet()

    val GENERIC_TYPE_NAMES: Set<String> = setOf(
        "row", "column", "container", "stack", "card", "generic_card", "list_item",
    )

    val SLOT_ORDER: List<String> = listOf(
        "header", "shortcuts", "accounts", "cards", "offers", "coverage", "foryou",
    )

    val REQUIRED_SLOTS: Set<String> = setOf("header", "accounts")

    val ALLOWED_ACTIONS: Set<String> = setOf("navigate", "open_bottom_sheet", "track", "noop")

    val VISUAL_KEYS: Set<String> = setOf(
        "color", "background", "font", "typography",
        "margin", "padding", "gap",
        "width", "height", "radius", "rounded", "cornerRadius", "shadow",
        "orientation", "circle", "rectangle", "shimmer", "ripple", "haptic",
        "dp", "pt", "itemWidth", "itemHeight", "breakpoint", "formFactor",
        "columns",
    )

    val PII_KEYS: Set<String> = setOf(
        "cpf", "pan", "cvv", "password", "senha", "token", "jwt", "secret",
        "accountNumber", "agencia", "conta",
    )
}
