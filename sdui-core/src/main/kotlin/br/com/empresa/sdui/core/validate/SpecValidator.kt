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

object SpecValidator {
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
        val counts = mutableMapOf<String, Int>()
        val sectionIds = spec.sections.map { it.id }.toSet()
        for (section in spec.sections) {
            val slot = skeleton.slot(section.slot)
            if (slot == null) {
                errors += "placement slot inexistente: ${section.slot}"
                continue
            }
            if (section.type !in slot.allowedTypes) {
                errors += "type ${section.type} nao permitido no slot ${section.slot}"
            }
            counts[section.slot] = (counts[section.slot] ?: 0) + 1
            if ((counts[section.slot] ?: 0) > slot.maxInstances) {
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
                    section.slot == slot.id && Capability(section.type, section.typeVersion) in combo.caps
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
        if (max > min) {
            samples += SemVer(min.major, min.minor, min.patch)
        }
        return samples.map { version ->
            val ctx = ClientContext(
                platform = spec.platform,
                appVersion = version,
                build = "1",
                osVersion = spec.targeting.osVersion?.min,
                schemaVersion = spec.targeting.schemaVersion.min.toString().substringBefore("."),
                locale = "pt-BR",
                apiVersion = "1",
                headerCapabilities = emptyList(),
            )
            val schema = spec.targeting.schemaVersion.min
            val context = ctx.copy(schemaVersion = schema.major.toString())
            Combo(
                label = "${spec.platform.wire()} ${version} caps=${
                    matrix.effective(context).joinToString { it.wire() }
                }",
                caps = matrix.effective(context),
            )
        }
    }
}
