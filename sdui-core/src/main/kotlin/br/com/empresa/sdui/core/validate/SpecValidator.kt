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
 * Validador holístico e certificador de integridade de especificações visuais ([Spec]).
 *
 * ### 1. O que faz
 * Executa a auditoria completa de uma especificação de tela antes de sua aprovação e publicação,
 * unificando verificações de integridade criptográfica, coerência de layout, conformidade de catálogo,
 * barreiras de segurança arquitetural e garantia preditiva de slots portantes.
 *
 * ### 2. Para que serve
 * Atua como o gatekeeper supremo da governança administrativa do Server-Driven UI (maker-checker):
 * - **Identidade e Criptografia:** Garante identificadores de revisão válidos ([RevisionIds.isValid])
 *   e formato canônico estrito de checksum SHA-256 (`sha256:<hex>`), permitindo que o compose do hot path
 *   utilize o checksum diretamente como `skeletonHash` sem valores fictícios ou de reserva;
 * - **Coerência Estrutural:** Assegura alinhamento bidirecional estrito entre a especificação, o esqueleto
 *   ([Skeleton]) referenciado e a definição da superfície ([SurfaceDefinition]);
 * - **Regras de Seção e Posicionamento:** Garante unicidade de IDs de seções, respeita a lotação máxima de
 *   instâncias por slot (`maxInstances`) e restringe tipos ao vocabulário homologado da superfície e catálogo;
 * - **Barreiras de Segurança:** Submete todas as propriedades a [VisualGuard] (anti-CSS), [PiiGuard] (privacidade/PCI),
 *   [ActionGuard] (ações e CTAs), [ComponentPropsValidator] (contratos ADR-020) e [PropWalk.MAX_PROPS_DEPTH] (anti-DoS);
 * - **Independência de Seções:** Garante que seções sejam autocontidas e não façam referências a seções irmãs;
 * - **Garantia Preditiva de Slots Portantes:** Simula o pipeline de capabilities contra os extremos da faixa
 *   de versões de clientes atendida ([CapabilityMatrix.versionSamples]), garantindo que slots obrigatórios
 *   nunca fiquem vazios para nenhuma versão de aplicativo válida.
 *
 * ### 3. Como funciona
 * Encapsula a lógica de verificação em blocos modulares: [identityErrors], [sectionErrors] e [requiredSlotErrors].
 * Não interrompe a execução no primeiro erro: acumula todas as irregularidades em uma lista abrangente, permitindo
 * ao operador administrativo corrigir todas as divergências em uma única iteração.
 */
object SpecValidator {
    /**
     * Expressão regular que valida o formato canônico exigido para o checksum SHA-256 da especificação.
     * O formato obrigatório é `sha256:<64_hex_digits>`, garantindo integridade criptográfica.
     */
    private val CHECKSUM = Regex("""^sha256:[0-9a-f]+$""")

    /**
     * Valida integralmente uma proposta ou rascunho de especificação ([Spec]).
     *
     * ### 1. O que faz
     * Dispara todas as etapas de auditoria estrutural, de posicionamento, de guardas de segurança e
     * simulação de compatibilidade de clientes.
     *
     * ### 2. Para que serve
     * Valida rascunhos na governança administrativa antes que possam ser submetidos ao checker para
     * transição de status para canary ou produção.
     *
     * ### 3. Como funciona
     * Concatena os resultados de [identityErrors] (identidade e integridade), [sectionErrors] (posicionamento,
     * profundidade, guardas de props e actions) e [requiredSlotErrors] (simulação preditiva de slots portantes),
     * retornando a lista final de violações.
     *
     * @param spec Especificação de tela a ser validada.
     * @param skeleton Esqueleto associado que define a estrutura de slots da tela.
     * @param catalog Catálogo oficial de componentes aprovados.
     * @param matrix Matriz de capabilities de versões de aplicativos clientes.
     * @return Lista contendo todas as mensagens de erro encontradas (vazia se a especificação estiver em conformidade).
     */
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

    /**
     * Valida se os slots portantes obrigatórios da tela possuem cobertura para todas as versões de clientes.
     *
     * ### 1. O que faz
     * Simula o comportamento do filtro de capacidades ([CapabilityMatrix]) sobre cada combinação de versão
     * atendida pelo targeting do [spec], confirmando que slots obrigatórios (`required = true`) não fiquem vazios.
     *
     * ### 2. Para que serve
     * Previne falhas catastróficas em produção: caso um cliente receba uma tela sem um slot portante obrigatório
     * (como `header` ou `accounts` na Home), a guarda do compose rejeitaria a montagem, ativando fallback ou HTTP 503.
     * Detectar essa condição no momento do cadastro garante que o spec seja adaptado antes do deploy.
     *
     * ### 3. Como funciona
     * Identifica os slots marcados como [SlotDefinition.required] no [skeleton]. Para cada coorte gerada por
     * [targetingCombos], verifica se existe pelo menos uma seção no spec atribuída ao slot cuja capacidade
     * esteja presente no conjunto de capabilities da coorte. Se algum slot obrigatório ficar desprovido de
     * seções compatíveis, emite um erro detalhando a versão afetada.
     *
     * @param spec Especificação visual sob teste.
     * @param skeleton Esqueleto de tela com as definições de slots obrigatórios.
     * @param matrix Matriz de capabilities por plataforma e versão.
     * @return Lista de violações de cobertura de slots portantes.
     */
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

    /**
     * Valida a identidade da especificação, revisões numéricas, compatibilidade com o skeleton e checksum.
     */
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
     * Valida a integridade das seções: unicidade de IDs, posicionamento em slots e conformidade de conteúdo.
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

    /**
     * Valida as restrições de posicionamento da seção: tipo aceito no slot, tipos da superfície e contagem máxima.
     */
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

    /**
     * Executa as baterias de guardas de conteúdo sobre as propriedades, ações e layout da seção.
     */
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

    /**
     * Representação de uma coorte de teste com rótulo identificador e capacidades efetivas calculadas.
     */
    private data class Combo(val label: String, val caps: Set<Capability>)

    /**
     * Gera as coortes de teste para simulação de compatibilidade de targeting.
     *
     * ### 1. O que faz
     * Amostra versões do aplicativo cliente na faixa de targeting do spec e constrói o conjunto de
     * capacidades ativas para cada uma.
     *
     * ### 2. Para que serve
     * Permite testar os extremos da faixa (versão mínima, versão canônica e versão máxima) sem precisar
     * iterar exaustivamente sobre todas as versões possíveis.
     *
     * ### 3. Como funciona
     * Utiliza [CapabilityMatrix.versionSamples] para extrair os pontos extremos de versão. Para cada versão,
     * agrega as capacidades concedidas pelo servidor às capacidades obrigatórias declaradas no targeting
     * do spec (`spec.targeting.requiredCapabilities`), produzindo uma lista de instâncias de [Combo].
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
