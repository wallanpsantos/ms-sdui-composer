package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.MvpCatalog

/**
 * Garante que o catalogo so contem contratos aprovados e preserva os sete types da Home.
 *
 * O conjunto fechado mudou de "exatamente os sete do MVP" para "contido em
 * [ComponentContracts.APPROVED]" (ADR-020): um componente novo entra no catalogo depois de ter
 * contrato aprovado, e nunca por um nome arbitrario. A regra vale para qualquer status — um
 * componente inativo de nome livre tambem abriria uma serie de metrica por nome enviado. Os sete
 * types legados continuam obrigatoriamente ACTIVE, porque a Home publicada depende deles.
 *
 * Recusa tambem qualquer primitiva generica, como row ou container: o catalogo e de componentes
 * de negocio.
 */
object CatalogValidator {
    private const val ACTIVE: String = "ACTIVE"

    fun validate(catalog: Catalog): List<String> {
        val errors = mutableListOf<String>()
        for (component in catalog.components) {
            if (component.type.lowercase() in MvpCatalog.GENERIC_TYPE_NAMES) {
                errors += "type generico recusado: ${component.type}"
            } else if (!ComponentContracts.isApproved(component.type, component.typeVersion)) {
                errors += "componente sem contrato aprovado: ${component.type}@${component.typeVersion}"
            }
        }
        val wires = catalog.components.map { "${it.type}@${it.typeVersion}" }
        val duplicated = wires.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            errors += "componentes repetidos no catalogo: $duplicated"
        }
        val active = catalog.components
            .filter { it.status.equals(ACTIVE, ignoreCase = true) }
            .map { "${it.type}@${it.typeVersion}" }
            .toSet()
        val missingLegacy = ComponentContracts.LEGACY_HOME.map { it.wire() }.filterNot { it in active }
        if (missingLegacy.isNotEmpty()) {
            errors += "catalogo ativo deve conter os types da Home: faltam $missingLegacy"
        }
        return errors
    }
}
