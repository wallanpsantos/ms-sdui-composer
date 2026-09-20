package br.com.empresa.sdui.core.model

/**
 * Plataforma do cliente. iOS e Android sao isolados de ponta a ponta: pointer, cache, spec e
 * matriz de capabilities proprios, para que uma publicacao numa plataforma nao afete a outra.
 */
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

/**
 * Canal de entrega. Cada canal tem pointer e cache proprios, o que permite expor uma revisao a
 * builds de canary sem tocar em stable. [parse] cai para STABLE em entrada desconhecida: canal
 * invalido nao deve virar erro de requisicao, so ausencia de canary.
 */
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

/** Ciclo de vida de spec e skeleton. PUBLISHED e terminal e imutavel: mudar exige nova revisao. */
enum class SpecStatus {
    DRAFT,
    PUBLISHED,
    REJECTED,
}

/** Estado de um pedido de publicacao. So sai de OPEN uma vez, por compare-and-set. */
enum class PublishRequestStatus {
    OPEN,
    APPROVED,
    REJECTED,
}

/**
 * Papel na governanca. MAKER propoe, CHECKER aprova ou rejeita, AUDITOR apenas consulta e nao
 * escreve nada. A separacao entre os dois primeiros e o que sustenta o maker-checker.
 */
enum class ActorRole {
    MAKER,
    CHECKER,
    AUDITOR,
    ;

    companion object {
        private val BY_NAME: Map<String, ActorRole> = entries.associateBy { it.name }

        fun parse(raw: String?): ActorRole? =
            raw?.trim()?.uppercase()?.let { BY_NAME[it] }
    }
}

/**
 * Por que a resposta veio degradada, na escada de fallback (ADR-007).
 *
 * [CLOSED] existe para os testes de contrato travarem o vocabulario: o cliente pode instrumentar
 * em cima desses valores sabendo que nenhum termo novo aparece sem revisao de contrato.
 */
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

/**
 * Por que uma section nao foi entregue: o cliente nao renderiza o tipo, ou a hidratacao falhou ou
 * estourou o prazo. Vocabulario fechado, como em [FallbackReason].
 */
enum class OmittedReason(val wire: String) {
    UNSUPPORTED_TYPE("unsupported_type"),
    HYDRATION_FAILED("hydration_failed"),
    HYDRATION_TIMEOUT("hydration_timeout"),
    ;

    companion object {
        val CLOSED: Set<String> = entries.map { it.wire }.toSet()
    }
}

/**
 * Arranjo semantico de um slot. Descreve a intencao — lista, prateleira, pager — e deixa medida,
 * espacamento e estilo para o cliente, conforme a proibicao de aparencia no servidor.
 */
enum class SlotLayout {
    FIXED,
    SHELF,
    LIST,
    PAGER,
    GRID,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        private val BY_WIRE: Map<String, SlotLayout> = entries.associateBy { it.wire() }

        fun parse(raw: String?): SlotLayout? =
            raw?.trim()?.lowercase()?.let { BY_WIRE[it] }
    }
}
