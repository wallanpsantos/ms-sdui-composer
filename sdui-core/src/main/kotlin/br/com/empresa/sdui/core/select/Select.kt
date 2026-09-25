package br.com.empresa.sdui.core.select

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.select.Select.isEligible
import java.time.Instant

/**
 * Segundo passo do pipeline de composição Server-Driven UI: seleção da especificação de tela.
 *
 * ### 1. O que faz
 * Elege a versão ideal da especificação visual ([Spec]) a ser entregue para o cliente com base no ponteiro
 * ativo do canal e no contexto validado da requisição.
 *
 * ### 2. Para que serve
 * Resolve dinamicamente o targeting de versão do aplicativo (faixas de `SemVer`), versão de SO, compilação,
 * plataforma e canal de distribuição (`canary` vs `stable`). Isso permite executar rollouts graduais, testes A/B,
 * experimentos e direcionamento de coortes sem que seja necessária qualquer alteração ou recompilação no código dos clientes móveis.
 *
 * ### 3. Como funciona
 * O algoritmo de decisão segue a seguinte sequência:
 * 1. **Filtro de Elegibilidade:** Filtra as especificações candidatas garantindo que estejam publicadas ([SpecStatus.PUBLISHED]),
 *    destinadas à mesma plataforma ([ClientContext.platform]) e à mesma surface solicitada.
 * 2. **Avaliação do Ponteiro:** Identifica a revisão apontada pelo [Pointer] do canal via [pointedRevision]. Se a revisão
 *    apontada estiver entre as publicadas e satisfizer o contexto do cliente e suas capacidades efetivas (`matches`), ela é retornada imediatamente.
 * 3. **Fallback por Prioridade:** Se a revisão apontada não for compatível, busca entre todas as demais candidatas elegíveis
 *    aquela que satisfaz o cliente (`matches`), ordenando pela maior prioridade de targeting (`Spec.targeting.priority`) e, em
 *    caso de empate, pela data de publicação mais recente (`publishedAt`).
 * 4. **Garantia contra 404 (ADR-007):** Retornar `null` é um resultado legítimo que indica ausência de spec elegível para aquele
 *    dispositivo específico. O BFF **nunca** retorna HTTP 404 para o cliente na Home; em vez disso, o orquestrador aciona a escada
 *    de fallback (tentando cache, last-good ou 503 com retry e jitter).
 * 5. **Obrigatoriedade de Surface (ADR-020):** O parâmetro `surface` não possui valor padrão; essa obrigatoriedade explícita impede
 *    que um chamador esqueça de informar a surface e acabe selecionando a Home silenciosamente para outra tela (ex.: `catalog`).
 */
object Select {
    /**
     * Executa a seleção completa da melhor especificação visual entre as candidatas disponíveis.
     *
     * ### 1. O que faz
     * Avalia todas as especificações candidatas e o ponteiro ativo, selecionando a [Spec] de maior aderência ao contexto do cliente.
     *
     * ### 2. Para que serve
     * Fornece ao orquestrador a especificação correta a ser filtrada e hidratada para montagem da tela.
     *
     * ### 3. Como funciona
     * Filtra candidatas elegíveis com [isEligible]. Avalia a revisão apontada via [pointedRevision]; se for válida e atender
     * ao cliente via `matches(context, effectiveCaps)`, retorna-a com precedência. Caso contrário, itera sobre as candidatas
     * elegíveis que satisfazem `matches`, escolhendo a de maior prioridade de targeting e publicação mais recente.
     * Retorna `null` caso nenhuma especificação satisfaça os critérios.
     *
     * @param pointer Ponteiro do canal apontando para a revisão atualmente designada.
     * @param candidates Lista de todas as especificações registradas a serem consideradas.
     * @param context Contexto validado do cliente requisitante.
     * @param effectiveCaps Conjunto de capacidades efetivas suportadas pelo cliente.
     * @param channel Canal de distribuição da requisição (`canary` ou `stable`).
     * @param surface Nome canônico da surface sendo resolvida (ex.: "home", "catalog").
     * @return A [Spec] selecionada para o cliente, ou `null` se nenhuma atender.
     */
    fun select(
        pointer: Pointer?,
        candidates: List<Spec>,
        context: ClientContext,
        effectiveCaps: Set<Capability>,
        channel: Channel,
        surface: String,
    ): Spec? {
        // `channel` e o canal da requisicao, ja vinculado ao pointer que o chamador carregou.
        // Spec.channel e metadado de autoria: a promocao reaproveita a revisao publicada como esta.
        val published = candidates.filter { spec -> isEligible(spec, context, surface) }
        val pointed = pointedRevision(pointer, context, channel, surface)
            ?.let { id -> published.firstOrNull { it.specRevisionId == id } }
        if (pointed != null && pointed.matches(context, effectiveCaps)) {
            return pointed
        }
        return published
            .filter { it.matches(context, effectiveCaps) }
            .maxWithOrNull(
                compareBy<Spec> { it.targeting.priority }
                    .thenBy { it.publishedAt ?: Instant.EPOCH },
            )
    }

    /**
     * Resolve o identificador de revisão apontado pelo ponteiro para o contexto especificado.
     *
     * ### 1. O que faz
     * Extrai o [Pointer.specRevisionId] caso o ponteiro corresponda ao canal, plataforma e surface informados.
     *
     * ### 2. Para que serve
     * Permite saber se existe uma revisão explicitamente direcionada para o perfil da requisição antes de realizar buscas amplas.
     *
     * ### 3. Como funciona
     * Valida se a instância de [pointer] é não nula e se seus atributos `channel`, `platform` e `surface` coincidem estritamente
     * com os valores fornecidos. Retorna a string do identificador da revisão ou `null` se não houver correspondência exata.
     *
     * @param pointer Ponteiro a ser consultado.
     * @param context Contexto validado do cliente.
     * @param channel Canal da requisição.
     * @param surface Surface requisitada.
     * @return O identificador da revisão apontada ou `null` se o ponteiro não se aplicar.
     */
    fun pointedRevision(pointer: Pointer?, context: ClientContext, channel: Channel, surface: String): String? =
        pointer
            ?.takeIf { it.channel == channel && it.platform == context.platform && it.surface == surface }
            ?.specRevisionId

    /**
     * Atalho otimizado do processo de seleção para o caminho crítico (hot path).
     *
     * ### 1. O que faz
     * Avalia se a especificação apontada pelo ponteiro (já previamente carregada pelo orquestrador) atende integralmente ao cliente.
     *
     * ### 2. Para que serve
     * Otimização de desempenho: no caso comum de operação, a revisão do ponteiro atende à imensa maioria das requisições.
     * Ao validar diretamente esta spec, evita carregar e filtrar a lista inteira de especificações publicadas do repositório.
     *
     * ### 3. Como funciona
     * 1. Verifica se [pointedSpec] não é nulo e se seu identificador coincide com o retornado por [pointedRevision].
     * 2. Confirma a elegibilidade via [isEligible].
     * 3. Executa a checagem de targeting e capacidades via `Spec.matches(context, effectiveCaps)`.
     * 4. Retorna a própria [pointedSpec] caso atenda; se não atender, retorna `null`, sinalizando ao orquestrador a necessidade
     *    de carregar todas as candidatas para o fallback de seleção completa via [select].
     *
     * @param pointer Ponteiro ativo do canal.
     * @param pointedSpec Instância da especificação apontada pelo ponteiro.
     * @param context Contexto validado do cliente.
     * @param effectiveCaps Conjunto de capacidades efetivas do cliente.
     * @param channel Canal da requisição.
     * @param surface Surface solicitada.
     * @return A [pointedSpec] se for compatível com o cliente, ou `null` caso contrário.
     */
    fun pointedIfServes(
        pointer: Pointer?,
        pointedSpec: Spec?,
        context: ClientContext,
        effectiveCaps: Set<Capability>,
        channel: Channel,
        surface: String,
    ): Spec? {
        val spec = pointedSpec ?: return null
        val id = pointedRevision(pointer, context, channel, surface) ?: return null
        if (spec.specRevisionId != id || !isEligible(spec, context, surface)) return null
        return spec.takeIf { it.matches(context, effectiveCaps) }
    }

    /**
     * Verifica a elegibilidade primária de uma especificação para a requisição.
     *
     * ### 1. O que faz
     * Avalia se uma especificação está com status publicado e pertence à mesma surface e plataforma da solicitação.
     *
     * ### 2. Para que serve
     * Impede qualquer vazamento cruzado entre plataformas (isolamento estrito iOS vs Android) ou entre surfaces distintas.
     *
     * ### 3. Como funciona
     * Compara se `spec.status == SpecStatus.PUBLISHED`, se `spec.surface == surface` e se `spec.platform == context.platform`.
     *
     * @param spec Especificação a ser checada.
     * @param context Contexto do cliente contendo a plataforma.
     * @param surface Surface solicitada.
     * @return `true` se a especificação for elegível para avaliação de targeting; `false` caso contrário.
     */
    private fun isEligible(spec: Spec, context: ClientContext, surface: String): Boolean =
        spec.status == SpecStatus.PUBLISHED && spec.surface == surface && spec.platform == context.platform
}
