package br.com.empresa.sdui.core.model

/**
 * Um componente que o catalogo publica, numa versao.
 *
 * [sinceSchema] registra a partir de qual versao de schema ele existe, e [requiredProps] o que uma
 * section daquele tipo tem obrigatoriamente de trazer. [status] permite aposentar um componente
 * sem apaga-lo do historico.
 */
data class ComponentType(
    val type: String,
    val typeVersion: Int,
    val status: String,
    val sinceSchema: String,
    val requiredProps: List<String>,
) {
    fun capability(): Capability = Capability(type, typeVersion)
}

/**
 * O conjunto de componentes que o servidor pode mandar renderizar.
 *
 * A fronteira do que existe em SDUI: uma section de tipo fora daqui e recusada na publicacao. Por
 * isso o catalogo e validado como conjunto, e nao componente a componente — o que importa e ele
 * ser exatamente o acordado com as equipes moveis.
 */
data class Catalog(
    val components: List<ComponentType>,
) {
    fun find(type: String, typeVersion: Int): ComponentType? =
        components.firstOrNull { it.type == type && it.typeVersion == typeVersion }
}
