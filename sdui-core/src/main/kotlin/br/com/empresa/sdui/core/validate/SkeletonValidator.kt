package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotLayout

object SkeletonValidator {
    fun validate(skeleton: Skeleton): List<String> {
        val errors = mutableListOf<String>()
        val ids = skeleton.slots.map { it.id }
        if (ids != MvpCatalog.SLOT_ORDER) {
            errors += "skeleton deve declarar slots ${MvpCatalog.SLOT_ORDER}, encontrado $ids"
        }
        if (skeleton.layout != MvpCatalog.SKELETON_LAYOUT) {
            errors += "layout de skeleton invalido: ${skeleton.layout}"
        }
        for (slot in skeleton.slots) {
            if (SlotLayout.parse(slot.layout.wire()) == null) {
                errors += "layout de slot invalido: ${slot.id}"
            }
            errors += VisualGuard.violations(mapOf("layout" to slot.layout.wire(), "title" to slot.title))
            if (slot.id in MvpCatalog.REQUIRED_SLOTS && !slot.required) {
                errors += "slot portante ${slot.id} deve ser required"
            }
        }
        return errors
    }
}
