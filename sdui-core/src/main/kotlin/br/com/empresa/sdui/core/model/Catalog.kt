package br.com.empresa.sdui.core.model

data class ComponentType(
    val type: String,
    val typeVersion: Int,
    val status: String,
    val sinceSchema: String,
    val requiredProps: List<String>,
) {
    fun capability(): Capability = Capability(type, typeVersion)
}

data class Catalog(
    val components: List<ComponentType>,
) {
    fun find(type: String, typeVersion: Int): ComponentType? =
        components.firstOrNull { it.type == type && it.typeVersion == typeVersion }

    fun capabilities(): Set<Capability> = components.map { it.capability() }.toSet()
}
