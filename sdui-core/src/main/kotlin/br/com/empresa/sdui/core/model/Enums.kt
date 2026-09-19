package br.com.empresa.sdui.core.model

enum class ClientPlatform {
    IOS,
    ANDROID,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun parse(raw: String?): ClientPlatform? =
            when (raw?.trim()?.lowercase()) {
                "ios" -> IOS
                "android" -> ANDROID
                else -> null
            }
    }
}

enum class Channel {
    STABLE,
    CANARY,
    INTERNAL,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun parse(raw: String?): Channel =
            when (raw?.trim()?.lowercase()) {
                "canary" -> CANARY
                "internal" -> INTERNAL
                else -> STABLE
            }
    }
}

enum class SpecStatus {
    DRAFT,
    PUBLISHED,
    REJECTED,
}

enum class PublishRequestStatus {
    OPEN,
    APPROVED,
    REJECTED,
}

enum class ActorRole {
    MAKER,
    CHECKER,
    AUDITOR,
    ;

    companion object {
        fun parse(raw: String?): ActorRole? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class FallbackReason(val wire: String) {
    NONE("none"),
    REDIS_UNAVAILABLE("redis_unavailable"),
    DEPENDENCY_TIMEOUT("dependency_timeout"),
    NO_COMPATIBLE_SPEC("no_compatible_spec"),
    REQUIRED_SLOT_EMPTY("required_slot_empty"),
    LAST_GOOD("last_good"),
    ;

    companion object {
        val CLOSED: Set<String> = entries.map { it.wire }.toSet()
    }
}

enum class OmittedReason(val wire: String) {
    UNSUPPORTED_TYPE("unsupported_type"),
    HYDRATION_FAILED("hydration_failed"),
    HYDRATION_TIMEOUT("hydration_timeout"),
    ;

    companion object {
        val CLOSED: Set<String> = entries.map { it.wire }.toSet()
    }
}

enum class SlotLayout {
    FIXED,
    SHELF,
    LIST,
    PAGER,
    GRID,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun parse(raw: String?): SlotLayout? =
            entries.firstOrNull { it.wire() == raw?.trim()?.lowercase() }
    }
}
