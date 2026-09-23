package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces

/**
 * Valida a estrutura da surface antes da publicacao.
 *
 * Aplica as invariantes de surface (ADR-018), agora parametrizadas pela [SurfaceDefinition] da
 * surface do skeleton (ADR-020):
 * - A surface precisa estar na allowlist de [Surfaces];
 * - Slots devem pertencer ao vocabulario da surface;
 * - IDs unicos (sem repeticao);
 * - O primeiro slot da surface (hoje `header` nas duas) fixado no topo;
 * - Slots portantes da surface presentes e marcados como required;
 * - Layout do slot dentro de allowedLayouts, e allowedLayouts contido na regra da surface;
 * - Tipos permitidos (allowedTypes) contidos nos types da surface;
 * - Ausencia de atributo visual ou PII ate no titulo.
 */
object SkeletonValidator {
    fun validate(skeleton: Skeleton): List<String> {
        val errors = mutableListOf<String>()
        val surface = Surfaces.find(skeleton.surface)
            ?: return listOf("surface desconhecida: '${skeleton.surface}' (permitidas: ${Surfaces.IDS})")
        val slots = skeleton.slots
        val ids = slots.map { it.id }

        if (slots.isEmpty()) {
            errors += "skeleton deve possuir ao menos um slot"
            return errors
        }

        if (ids.firstOrNull() != surface.firstSlot) {
            errors += "slot '${surface.firstSlot}' deve ser obrigatoriamente o primeiro slot do skeleton, encontrado '${ids.firstOrNull()}'"
        }

        val idsSet = ids.toSet()
        if (ids.size != idsSet.size) {
            val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
            errors += "skeleton contem slots com ids duplicados: $duplicates"
        }

        val unknownSlots = ids.filterNot { it in surface.slotIds }
        if (unknownSlots.isNotEmpty()) {
            errors += "slots desconhecidos para a surface '${surface.id}': $unknownSlots (permitidos: ${surface.slotIds})"
        }

        val missingRequired = surface.requiredSlots.filterNot { it in idsSet }
        if (missingRequired.isNotEmpty()) {
            errors += "slots portantes obrigatorios ausentes no skeleton: $missingRequired"
        }

        if (skeleton.layout != surface.skeletonLayout) {
            errors += "layout de skeleton invalido: ${skeleton.layout}"
        }

        for (slot in slots) {
            if (SlotLayout.parse(slot.layout.wire()) == null) {
                errors += "layout de slot invalido: ${slot.id}"
            }
            if (slot.layout !in slot.allowedLayouts) {
                errors += "layout '${slot.layout.wire()}' nao permitido para o slot '${slot.id}'. Permitidos: ${slot.allowedLayouts.map { it.wire() }}"
            }
            val rule = surface.slot(slot.id)
            if (rule != null) {
                val beyondRule = slot.allowedLayouts.filterNot { it in rule.allowedLayouts }
                if (beyondRule.isNotEmpty()) {
                    errors += "allowedLayouts do slot '${slot.id}' excedem a regra da surface: ${beyondRule.map { it.wire() }}"
                }
            }
            errors += VisualGuard.violations(mapOf("layout" to slot.layout.wire(), "title" to slot.title))
            errors += PiiGuard.violations(mapOf("title" to slot.title))
            if (slot.id in surface.requiredSlots && !slot.required) {
                errors += "slot portante ${slot.id} deve ser required"
            }
            if (slot.maxInstances < 1) {
                errors += "slot '${slot.id}' deve aceitar ao menos uma instancia"
            }
            val unknownTypes = slot.allowedTypes.filterNot { it in surface.types }
            if (unknownTypes.isNotEmpty()) {
                errors += "slot '${slot.id}' contem allowedTypes fora do catalogo da surface '${surface.id}': $unknownTypes"
            }
        }
        return errors
    }
}
