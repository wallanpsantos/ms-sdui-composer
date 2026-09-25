package br.com.empresa.sdui.core.diff

import br.com.empresa.sdui.core.model.DiffEntry
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff

/**
 * Fábrica de cálculo de diferenças estruturais entre revisões de especificações de tela.
 *
 * ### 1. O que faz
 * Compara duas instâncias de especificação visual ([Spec]) — a revisão atualmente publicada e a revisão
 * candidata proposta — gerando um objeto detalhado de auditoria e inspeção de mudanças ([SpecDiff]).
 *
 * ### 2. Para que serve
 * Viabiliza o princípio de governança maker-checker: fornece ao revisor (checker) uma visão clara, transparente
 * e estruturada de tudo o que foi incluído, removido ou modificado em uma proposta de publicação, permitindo
 * uma tomada de decisão fundamentada antes da liberação em produção.
 *
 * ### 3. Como funciona
 * 1. **Diferenças por ID de Seção:** Rastreia os IDs de seção presentes na versão anterior e na versão atual.
 *    - Seções presentes apenas na atual são catalogadas como `"added"` em [SpecDiff.added].
 *    - Seções presentes apenas na anterior são catalogadas como `"removed"` em [SpecDiff.removed].
 *    - Seções presentes em ambas cuja estrutura de dados tenha sido modificada (`before != section`) são catalogadas
 *      como `"changed"` em [SpecDiff.changed].
 * 2. **Auditoria de Ocupação de Slots Portantes:** Varre todos os slots do [Skeleton] marcados como obrigatórios
 *    (`slot.required`). Compara a contagem de seções alocadas no slot na versão anterior versus na versão atual.
 *    Se houver variação no número de seções de um slot obrigatório, registra uma entrada em [SpecDiff.requiredOccupancy].
 *    Esse alerta é crucial porque a redução de ocupação a zero em um slot portante (como `header` ou `accounts`)
 *    tornaria a tela inválida no guard de composição, derrubando a Home para fallback sem que nenhuma seção existente
 *    tivesse necessariamente sido alterada de forma inválida.
 */
object SpecDiffFactory {
    /**
     * Calcula o relatório analítico de diferenças entre a revisão anterior e a revisão atual.
     *
     * ### 1. O que faz
     * Processa as seções das especificações [previous] e [current] e avalia o impacto sobre as regras do [skeleton].
     *
     * ### 2. Para que serve
     * Produz a instância de [SpecDiff] que é armazenada no pedido de publicação (`PublishRequest`) e inspecionada
     * pelas ferramentas administrativas de governança.
     *
     * ### 3. Como funciona
     * Suporta a ausência de revisão anterior ([previous] nulo), situação em que todas as seções da especificação
     * atual são contabilizadas como adições e a ocupação anterior de slots parte de zero.
     * Identifica inclusões e exclusões de IDs de seção via álgebra de conjuntos (`currIds - prevIds` e `prevIds - currIds`).
     * Compara a igualdade estrutural profunda de cada seção existente.
     * Calcula a variação de ocupação para cada slot obrigatório (`required == true`) do [skeleton] e consolida todas
     * as listas em uma nova instância imutável de [SpecDiff].
     *
     * @param previous Versão anterior da especificação atualmente ativa (ou `null` para primeira publicação).
     * @param current Nova versão da especificação submetida para aprovação.
     * @param skeleton Esqueleto da tela contendo a definição e obrigatoriedade de cada slot.
     * @return [SpecDiff] contendo todas as listas de mutações estruturais e deltas de slots portantes.
     */
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
            if (before != section) {
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
