package br.com.empresa.sdui.core.diff

import br.com.empresa.sdui.core.model.DiffEntry
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff

/**
 * Calcula o que muda entre a revisao publicada e a candidata, para o checker decidir com contexto.
 *
 * Compara por id de section — adicionadas, removidas, alteradas — e, separadamente, a ocupacao dos
 * slots portantes, que e a mudanca capaz de esvaziar a home sem alterar nenhuma section existente.
 */
object SpecDiffFactory {
    fun diff(previous: Spec?, current: Spec, skeleton: Skeleton): SpecDiff {
        val prevIds = previous?.sections?.map { it.id }?.toSet().orEmpty()
        val currIds = current.sections.map { it.id }.toSet()
        val added = (currIds - prevIds).map { DiffEntry(path = "sections.$it", change = "added") }
        val removed = (prevIds - currIds).map { DiffEntry(path = "sections.$it", change = "removed") }
        val changed = mutableListOf<DiffEntry>()
        val occupancy = mutableListOf<DiffEntry>()
        val prevById = previous?.sections?.associateBy { it.id }.orEmpty()
        for (section in current.sections) {
            val before = prevById[section.id] ?: continue
            if (before.type != section.type || before.typeVersion != section.typeVersion || before.props != section.props) {
                changed += DiffEntry(path = "sections.${section.id}", change = "changed")
            }
        }
        for (slot in skeleton.slots.filter { it.required }) {
            val beforeCount = previous?.sections?.count { it.slot == slot.id } ?: 0
            val afterCount = current.sections.count { it.slot == slot.id }
            if (beforeCount != afterCount) {
                occupancy += DiffEntry(
                    path = "slots.${slot.id}.requiredOccupancy",
                    change = "changed",
                    from = beforeCount.toString(),
                    to = afterCount.toString(),
                )
            }
        }
        return SpecDiff(
            specId = current.specId,
            fromRevision = previous?.revision,
            toRevision = current.revision,
            added = added,
            removed = removed,
            changed = changed,
            requiredOccupancy = occupancy,
        )
    }
}
