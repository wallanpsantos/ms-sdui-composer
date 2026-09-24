package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.RevisionIds
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces

/**
 * Valida um spec antes de ele poder ser publicado.
 *
 * Reune as regras que nao devem chegar a producao: surface conhecida e coerente com o skeleton,
 * placement coerente com o skeleton, tipo dentro do catalogo, da surface e nao generico, props
 * dos contratos novos conforme [ComponentPropsValidator], ausencia de aparencia e de PII nas
 * props, profundidade limitada, actions integras e section autocontida. Alem disso simula as
 * pontas da faixa de targeting e recusa o spec se algum slot portante puder ficar vazio para uma
 * delas — e mais barato falhar aqui do que servir uma tela sem o bloco que a sustenta.
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
        val surface = Surfaces.find(spec.surface)
        return identityErrors(spec, skeleton, surface) +
                sectionErrors(spec, skeleton, catalog, surface) +
                requiredSlotErrors(spec, skeleton, matrix)
    }

    fun requiredSlotErrors(
        spec: Spec,
        skeleton: Skeleton,
        matrix: CapabilityMatrix,
    ): List<String> {
        val errors = mutableListOf<String>()
        val requiredSlots = skeleton.slots.filter { it.required }
        for (combo in targetingCombos(spec, matrix)) {
            for (slot in requiredSlots) {
                val empty = spec.sections.none { section ->
                    section.slot == slot.id && section.capability in combo.caps
                }
                if (empty) {
                    errors += "slot required '${slot.id}' pode ficar vazio para ${combo.label}"
                }
            }
        }
        return errors
    }

    /** Identidade do spec e coerencia dele com o skeleton que referencia e com a surface. */
    private fun identityErrors(spec: Spec, skeleton: Skeleton, surface: SurfaceDefinition?): List<String> =
        buildList {
            if (!RevisionIds.isValid(spec.specRevisionId)) add("specRevisionId invalido para cache e ETag")
            if (spec.revision < 1) add("revision deve ser positiva")
            if (spec.skeletonRevision < 1 || spec.skeletonRevision != skeleton.revision) {
                add("skeletonRevision divergente ou inexistente")
            }
            if (surface == null) add("surface desconhecida: '${spec.surface}' (permitidas: ${Surfaces.IDS})")
            if (skeleton.skeletonId != spec.skeletonId) add("skeletonId divergente")
            if (skeleton.surface != spec.surface) {
                add("skeleton '${skeleton.skeletonId}' pertence a surface '${skeleton.surface}', spec a '${spec.surface}'")
            }
            if (skeleton.layout != (surface?.skeletonLayout ?: MvpCatalog.SKELETON_LAYOUT)) {
                add("layout de skeleton invalido: ${skeleton.layout}")
            }
            if (!CHECKSUM.matches(spec.checksum)) add("checksum deve ser sha256:<hex>: '${spec.checksum}'")
        }

    /**
     * Ids unicos e, section a section, placement e conteudo. O conteudo so e varrido dentro do
     * teto de profundidade: as guardas descem recursivamente pelas props.
     */
    private fun sectionErrors(
        spec: Spec,
        skeleton: Skeleton,
        catalog: Catalog,
        surface: SurfaceDefinition?,
    ): List<String> = buildList {
        val sectionIds = spec.sections.map { it.id }.toSet()
        if (sectionIds.size != spec.sections.size) {
            val duplicates = spec.sections.groupBy { it.id }.filterValues { it.size > 1 }.keys
            add("secoes com id duplicado: $duplicates")
        }
        val counts = mutableMapOf<String, Int>()
        for (section in spec.sections) {
            val slot = skeleton.slot(section.slot)
            if (slot == null) {
                add("placement slot inexistente: ${section.slot}")
                continue
            }
            val count = (counts[section.slot] ?: 0) + 1
            counts[section.slot] = count
            addAll(placementErrors(section, slot, count, surface, catalog))
            if (PropWalk.exceedsDepth(section.props)) {
                add("section ${section.id} excede ${PropWalk.MAX_PROPS_DEPTH} niveis de props")
                continue
            }
            addAll(contentErrors(section, sectionIds))
        }
    }

    /** O type cabe no slot, na surface e no catalogo, e o slot nao passa do teto de instancias. */
    private fun placementErrors(
        section: Section,
        slot: SlotDefinition,
        count: Int,
        surface: SurfaceDefinition?,
        catalog: Catalog,
    ): List<String> = buildList {
        if (section.type !in slot.allowedTypes) add("type ${section.type} nao permitido no slot ${section.slot}")
        if (surface != null && section.type !in surface.types) {
            add("type ${section.type} nao pertence a surface '${surface.id}'")
        }
        if (count > slot.maxInstances) add("slot ${section.slot} excede maxInstances ${slot.maxInstances}")
        if (catalog.find(section.type, section.typeVersion) == null) {
            add("type ${section.type}@${section.typeVersion} fora do catalogo")
        }
        if (section.type.lowercase() in MvpCatalog.GENERIC_TYPE_NAMES) add("type generico recusado: ${section.type}")
    }

    /** Props, actions e layout da section, e que ela nao referencia nenhuma outra. */
    private fun contentErrors(section: Section, sectionIds: Set<String>): List<String> = buildList {
        addAll(VisualGuard.violations(section.props))
        addAll(PiiGuard.violations(section.props))
        addAll(ActionGuard.validate(section.id, section.actions, section.props))
        addAll(ComponentPropsValidator.validate(section))
        if (section.layout != null && SlotLayout.parse(section.layout) == null) {
            add("layout invalido na section ${section.id}: ${section.layout}")
        }
        if (PropWalk.referencesForeignSection(section.props, section.id, sectionIds)) {
            add("section ${section.id} referencia outra section")
        }
    }

    private data class Combo(val label: String, val caps: Set<Capability>)

    /**
     * As pontas da faixa de app que o spec atende, com as capabilities que um cliente ali tem.
     *
     * Um cliente so recebe este spec se tiver todas as `requiredCapabilities` do targeting — Select
     * descarta o spec para quem nao as tem. Por isso elas somam ao conjunto do servidor na
     * simulacao: e o que permite a uma surface nova exigir um componente que a matriz nao concede
     * a nenhuma faixa de app, sem que a validacao aponte um vazio que nunca vai acontecer.
     */
    private fun targetingCombos(spec: Spec, matrix: CapabilityMatrix): List<Combo> {
        val required = spec.targeting.requiredCapabilities.toSet()
        return matrix.versionSamples(spec.platform, spec.targeting.appVersion).map { version ->
            val caps = matrix.serverCaps(spec.platform, version) + required
            Combo(
                label = "${spec.platform.wire()} $version caps=${caps.joinToString { it.wire() }}",
                caps = caps,
            )
        }
    }
}
