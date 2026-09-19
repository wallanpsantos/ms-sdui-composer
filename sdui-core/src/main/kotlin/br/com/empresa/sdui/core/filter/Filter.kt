package br.com.empresa.sdui.core.filter

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.capability

data class FilterResult(
    val sections: List<Section>,
    val omitted: List<OmittedSection>,
)

object Filter {
    fun filter(
        sections: List<Section>,
        skeleton: Skeleton,
        effectiveCaps: Set<Capability>,
    ): FilterResult {
        val slotOrder = skeleton.slots.mapIndexed { index, slot -> slot.id to index }.toMap()
        val kept = mutableListOf<Section>()
        val omitted = mutableListOf<OmittedSection>()
        for (section in sections) {
            if (section.capability() in effectiveCaps) {
                kept += section
            } else {
                omitted += OmittedSection(
                    id = section.id,
                    slot = section.slot,
                    type = section.type,
                    typeVersion = section.typeVersion,
                    reason = OmittedReason.UNSUPPORTED_TYPE,
                )
            }
        }
        val ordered = kept.sortedWith(
            compareBy<Section> { slotOrder[it.slot] ?: Int.MAX_VALUE }
                .thenBy { kept.indexOf(it) },
        )
        return FilterResult(ordered, omitted)
    }
}
