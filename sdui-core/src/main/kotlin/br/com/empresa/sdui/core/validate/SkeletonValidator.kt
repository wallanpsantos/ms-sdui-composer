package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotLayout

/**
 * Valida a estrutura da surface antes da publicacao.
 *
 * Aplica as invariantes de surface (ADR-018):
 * - Slots devem pertencer ao vocabulario da surface;
 * - IDs unicos (sem repeticao);
 * - Slot 'header' fixado obrigatoriamente no topo (primeiro slot);
 * - Slots portantes obrigatorios (header e accounts) presentes e marcados como required;
 * - Layout do slot dentro da lista de layouts permitidos (allowedLayouts);
 * - Tipos permitidos (allowedTypes) validos contra o catalogo de types conhecidos;
 * - Ausencia de atributo visual ou PII ate no titulo.
 */
object SkeletonValidator {
    fun validate(skeleton: Skeleton): List<String> {
        val errors = mutableListOf<String>()
        val slots = skeleton.slots
        val ids = slots.map { it.id }

        if (slots.isEmpty()) {
            errors += "skeleton deve possuir ao menos um slot"
            return errors
        }

        if (ids.firstOrNull() != "header") {
            errors += "slot 'header' deve ser obrigatoriamente o primeiro slot do skeleton, encontrado '${ids.firstOrNull()}'"
        }

        val idsSet = ids.toSet()
        if (ids.size != idsSet.size) {
            val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
            errors += "skeleton contem slots com ids duplicados: $duplicates"
        }

        val unknownSlots = ids.filterNot { it in MvpCatalog.SLOT_VOCABULARY }
        if (unknownSlots.isNotEmpty()) {
            errors += "slots desconhecidos para a surface '${skeleton.surface}': $unknownSlots (permitidos: ${MvpCatalog.SLOT_VOCABULARY})"
        }

        val missingRequired = MvpCatalog.REQUIRED_SLOTS.filterNot { it in idsSet }
        if (missingRequired.isNotEmpty()) {
            errors += "slots portantes obrigatorios ausentes no skeleton: $missingRequired"
        }

        if (skeleton.layout != MvpCatalog.SKELETON_LAYOUT) {
            errors += "layout de skeleton invalido: ${skeleton.layout}"
        }

        for (slot in slots) {
            if (SlotLayout.parse(slot.layout.wire()) == null) {
                errors += "layout de slot invalido: ${slot.id}"
            }
            if (slot.layout !in slot.allowedLayouts) {
                errors += "layout '${slot.layout.wire()}' nao permitido para o slot '${slot.id}'. Permitidos: ${slot.allowedLayouts.map { it.wire() }}"
            }
            errors += VisualGuard.violations(mapOf("layout" to slot.layout.wire(), "title" to slot.title))
            if (slot.id in MvpCatalog.REQUIRED_SLOTS && !slot.required) {
                errors += "slot portante ${slot.id} deve ser required"
            }
            val unknownTypes = slot.allowedTypes.filterNot { it in MvpCatalog.TYPE_NAMES }
            if (unknownTypes.isNotEmpty()) {
                errors += "slot '${slot.id}' contem allowedTypes fora do catalogo: $unknownTypes"
            }
        }
        return errors
    }
}
