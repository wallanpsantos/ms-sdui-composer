package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces

/**
 * Validador estrutural e de invariantes de esqueletos de tela ([Skeleton]).
 *
 * ### 1. O que faz
 * Inspeciona a definição estrutural de um esqueleto de tela antes de sua publicação, garantindo
 * conformidade estrita com as regras e o vocabulário da superfície visual correspondente ([SurfaceDefinition]).
 *
 * ### 2. Para que serve
 * Assegura as invariantes arquiteturais de layout e sustentação de tela (ADR-018 e ADR-020):
 * - **Superfície Conhecida:** Garante que o esqueleto pertença a uma superfície registrada na allowlist
 *   oficial de [Surfaces] (`home`, `catalog`);
 * - **Primeiro Slot Fixo:** Impõe que o primeiro slot físico da tela seja o cabeçalho estrutural
 *   ([SurfaceDefinition.firstSlot], ex: `header`), garantindo consistência visual de topo em todos os clientes;
 * - **Slots Portantes Obrigatórios:** Exige a presença de todos os slots portantes definidos em
 *   [SurfaceDefinition.requiredSlots] (como `header` e `accounts` na Home), com a flag `required = true`,
 *   impedindo que telas sejam publicadas sem a base estrutural que as sustenta;
 * - **Unicidade e Vocabulário de Slots:** Veda IDs de slots duplicados ou que não pertençam ao vocabulário
 *   formal da superfície;
 * - **Conformidade de Layout e Componentes:** Confere se o layout do esqueleto e os layouts permitidos para
 *   cada slot (`allowedLayouts`) estão estritamente contidos nas regras da superfície, assim como os tipos
 *   de componentes aceitos (`allowedTypes`);
 * - **Proteção de Privacidade:** Assegura ausência de dados regulados ou PII nos títulos dos slots através
 *   de [PiiGuard].
 *
 * ### 3. Como funciona
 * Consulta a definição da superfície via [Surfaces.find]. Executa uma série de asserções em nível de tela
 * (ordem do primeiro slot, duplicatas, slots portantes faltantes) e, em seguida, itera slot a slot comparando
 * layouts e tipos contra o contrato da superfície obtido por [SurfaceDefinition.slot]. Retorna uma lista com
 * todos os apontamentos de erro.
 */
object SkeletonValidator {
    /**
     * Executa a validação abrangente da estrutura de um esqueleto de tela.
     *
     * ### 1. O que faz
     * Valida um [Skeleton] contra as regras da superfície declarada, inspecionando slots, ordenação,
     * obrigatoriedades, tipos e privacidade.
     *
     * ### 2. Para que serve
     * Atua como gatekeeper na esteira de governança administrativa de skeletons, impedindo que propostas
     * com layouts corrompidos ou slots faltantes entrem no fluxo de aprovação ou cheguem a clientes móveis.
     *
     * ### 3. Como funciona
     * 1. Localiza a [SurfaceDefinition] em [Surfaces]; se inexistente, retorna erro fatal imediatamente;
     * 2. Confere se a lista de slots não está vazia e se o primeiro slot coincide com [SurfaceDefinition.firstSlot];
     * 3. Valida unicidade de IDs de slot e ausência de slots não previstos em [SurfaceDefinition.slotIds];
     * 4. Garante a declaração de todos os slots de [SurfaceDefinition.requiredSlots];
     * 5. Confere a compatibilidade do layout do skeleton ([Skeleton.layout]) com a superfície;
     * 6. Itera pelos slots verificando:
     *    - Se o layout do slot pertence a `allowedLayouts`;
     *    - Se `allowedLayouts` não extrapola a regra da superfície;
     *    - Se não há PII no título do slot via [PiiGuard.violations];
     *    - Se slots portantes possuem `required = true`;
     *    - Se `maxInstances >= 1`;
     *    - Se `allowedTypes` está contido nos tipos homologados da superfície;
     * 7. Retorna a lista acumulada com todas as mensagens de inconformidade encontradas.
     *
     * @param skeleton Instância de [Skeleton] a ser validada.
     * @return Lista de erros de validação estrutural (vazia se o esqueleto estiver em total conformidade).
     */
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
