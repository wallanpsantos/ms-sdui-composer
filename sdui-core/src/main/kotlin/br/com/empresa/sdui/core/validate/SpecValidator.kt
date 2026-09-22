package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec

/**
 * Valida um spec antes de ele poder ser publicado.
 *
 * Reune as regras que nao devem chegar a producao: placement coerente com o skeleton, tipo dentro
 * do catalogo e nao generico, ausencia de aparencia e de PII nas props, actions integras e section
 * autocontida. Alem disso simula as pontas da faixa de targeting e recusa o spec se algum slot
 * portante puder ficar vazio para uma delas — e mais barato falhar aqui do que servir uma home sem
 * header ou sem contas.
 *
 * Devolve a lista de erros em vez de lancar, para o chamador reportar tudo de uma vez.
 */
object SpecValidator {
    /**
     * O envelope publica este valor como `skeletonHash`. Exigir o formato na governanca e o que
     * permite ao compose usar o checksum direto, sem valor de reserva no hot path.
     */
    private val CHECKSUM = Regex("""^sha256:[0-9a-f]+$""")

    fun validateDraft(
        spec: Spec,
        skeleton: Skeleton,
        catalog: Catalog,
        matrix: CapabilityMatrix,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (skeleton.skeletonId != spec.skeletonId) {
            errors += "skeletonId divergente"
        }
        if (skeleton.layout != MvpCatalog.SKELETON_LAYOUT) {
            errors += "layout de skeleton invalido: ${skeleton.layout}"
        }
        if (!CHECKSUM.matches(spec.checksum)) {
            errors += "checksum deve ser sha256:<hex>: '${spec.checksum}'"
        }
        val counts = mutableMapOf<String, Int>()
        val sectionIds = spec.sections.map { it.id }.toSet()
        if (sectionIds.size != spec.sections.size) {
            val duplicates = spec.sections.groupBy { it.id }.filterValues { it.size > 1 }.keys
            errors += "secoes com id duplicado: $duplicates"
        }
        for (section in spec.sections) {
            val slot = skeleton.slot(section.slot)
            if (slot == null) {
                errors += "placement slot inexistente: ${section.slot}"
                continue
            }
            if (section.type !in slot.allowedTypes) {
                errors += "type ${section.type} nao permitido no slot ${section.slot}"
            }
            val currentCount = (counts[section.slot] ?: 0) + 1
            counts[section.slot] = currentCount
            if (currentCount > slot.maxInstances) {
                errors += "slot ${section.slot} excede maxInstances ${slot.maxInstances}"
            }
            if (catalog.find(section.type, section.typeVersion) == null) {
                errors += "type ${section.type}@${section.typeVersion} fora do catalogo"
            }
            if (section.type.lowercase() in MvpCatalog.GENERIC_TYPE_NAMES) {
                errors += "type generico recusado: ${section.type}"
            }
            errors += VisualGuard.violations(section.props)
            errors += PiiGuard.violations(section.props)
            errors += ActionGuard.validate(section.id, section.actions, section.props)
            if (section.layout != null && SlotLayout.parse(section.layout) == null) {
                errors += "layout invalido na section ${section.id}: ${section.layout}"
            }
            if (PropWalk.referencesForeignSection(section.props, section.id, sectionIds)) {
                errors += "section ${section.id} referencia outra section"
            }
        }
        for (slot in skeleton.slots) {
            if (SlotLayout.parse(slot.layout.wire()) == null) {
                errors += "layout invalido no slot ${slot.id}"
            }
        }
        errors += requiredSlotErrors(spec, skeleton, matrix)
        return errors
    }

    fun requiredSlotErrors(
        spec: Spec,
        skeleton: Skeleton,
        matrix: CapabilityMatrix,
    ): List<String> {
        val errors = mutableListOf<String>()
        val combos = targetingCombos(spec, matrix)
        for (combo in combos) {
            for (slot in skeleton.slots.filter { it.required }) {
                val occupying = spec.sections.filter { section ->
                    section.slot == slot.id && section.capability in combo.caps
                }
                if (occupying.isEmpty()) {
                    errors += "slot required '${slot.id}' pode ficar vazio para ${combo.label}"
                }
            }
        }
        return errors
    }

    private data class Combo(val label: String, val caps: Set<Capability>)

    private fun targetingCombos(spec: Spec, matrix: CapabilityMatrix): List<Combo> {
        val min = spec.targeting.appVersion.min
        val max = spec.targeting.appVersion.max ?: SemVer(min.major, min.minor + 50, 0)
        val samples = linkedSetOf(min, max)
        val schemaVersion = spec.targeting.schemaVersion.min.major.toString()
        return samples.map { version ->
            val context = ClientContext(
                platform = spec.platform,
                appVersion = version,
                build = "1",
                osVersion = spec.targeting.osVersion?.min,
                schemaVersion = schemaVersion,
                locale = "pt-BR",
                apiVersion = "1",
                headerCapabilities = emptyList(),
            )
            val effective = matrix.effective(context)
            Combo(
                label = "${spec.platform.wire()} $version caps=${effective.joinToString { it.wire() }}",
                caps = effective,
            )
        }
    }
}
