package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.MvpCatalog

object CatalogValidator {
    fun validate(catalog: Catalog): List<String> {
        val errors = mutableListOf<String>()
        val active = catalog.components.filter { it.status.equals("ACTIVE", ignoreCase = true) }
        val wires = active.map { it.capability().wire() }.toSet()
        val expected = MvpCatalog.TYPES.map { it.wire() }.toSet()
        if (wires != expected) {
            errors += "catalogo ativo deve ser exatamente $expected, encontrado $wires"
        }
        for (component in catalog.components) {
            if (component.type.lowercase() in MvpCatalog.GENERIC_TYPE_NAMES) {
                errors += "type generico recusado: ${component.type}"
            }
        }
        return errors
    }
}
