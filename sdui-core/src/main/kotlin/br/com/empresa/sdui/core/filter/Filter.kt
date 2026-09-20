package br.com.empresa.sdui.core.filter

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton

/** O que sobrou do filtro, ja na ordem final, e o que foi descartado com o motivo. */
data class FilterResult(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
)

/**
 * Terceiro passo: descarta as sections que este cliente nao sabe renderizar (eixo B).
 *
 * Uma section cujo type@typeVersion nao esta nas capabilities efetivas e omitida com
 * UNSUPPORTED_TYPE em vez de derrubar a composicao. A ordem final segue a ordem de slots do
 * skeleton; section de slot desconhecido vai para o fim em vez de sumir sem registro.
 */
object Filter {
    fun filter(
        sections: List<Section>,
        skeleton: Skeleton,
        effectiveCaps: Set<Capability>,
    ): FilterResult {
        val slotOrder = skeleton.slotOrder
        val (supported, unsupported) = sections.partition { it.capability in effectiveCaps }
        val ordered = supported.sortedBy { slotOrder[it.slot] ?: Int.MAX_VALUE }
        val omitted = unsupported.map { section ->
            OmittedSection(
                id = section.id,
                slot = section.slot,
                type = section.type,
                typeVersion = section.typeVersion,
                reason = OmittedReason.UNSUPPORTED_TYPE,
            )
        }
        return FilterResult(ordered, omitted)
    }
}
