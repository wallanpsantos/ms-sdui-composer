package br.com.empresa.sdui.core.filter

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton

/**
 * Resultado estruturado da etapa de filtragem e ordenação do pipeline de Server-Driven UI.
 *
 * ### 1. O que faz
 * Encapsula o conjunto de seções visuais elegíveis para hidratação e montagem da tela, acompanhado
 * da lista de seções descartadas por incompatibilidade de capabilities com o respectivo motivo de omissão.
 *
 * ### 2. Para que serve
 * Permite ao orquestrador prosseguir no pipeline apenas com as seções que o cliente móvel é comprovadamente
 * capaz de renderizar, ao mesmo tempo em que retém o registro detalhado das seções omitidas para inclusão
 * no envelope final de resposta (`omittedSections`) e envio de telemetria operacional.
 *
 * ### 3. Como funciona
 * Armazena a lista imutável [sections] com as seções aprovadas (já ordenadas na hierarquia de slots do [Skeleton])
 * e a lista [omitted] com as instâncias de [OmittedSection] contendo o identificador, slot, type, typeVersion
 * e o motivo de descarte [OmittedReason.UNSUPPORTED_TYPE].
 *
 * @property sections Lista ordenada de seções suportadas pelo cliente.
 * @property omitted Lista de seções que foram omitidas devido à falta de capacidade do cliente.
 */
data class FilterResult(
    /**
     * Lista de seções visuais aprovadas na filtragem.
     *
     * ### 1. O que faz
     * Contém as seções cujos componentes (`type@typeVersion`) são suportados pelo aplicativo cliente.
     *
     * ### 2. Para que serve
     * Alimenta a etapa subsequente de hidratação de dados dinâmicos e composição do envelope JSON.
     *
     * ### 3. Como funciona
     * Coleção imutável de instâncias de [Section], com garantia de ordenação estável conforme a ordem de slots do [Skeleton].
     */
    val sections: List<Section>,

    /**
     * Lista de seções omitidas durante a filtragem de capabilities.
     *
     * ### 1. O que faz
     * Registra todas as seções que foram descartadas por conter componentes desconhecidos pelo cliente móvel.
     *
     * ### 2. Para que serve
     * Fornece rastreabilidade de omissão para o aplicativo e auditoria de compatibilidade de versões legadas de app.
     *
     * ### 3. Como funciona
     * Lista de objetos [OmittedSection], permitindo que o cliente saiba exatamente quais slots deixaram de ser exibidos.
     */
    val omitted: List<OmittedSection>,
)

/**
 * Terceiro passo do pipeline de composição Server-Driven UI: filtragem de componentes e ordenação de slots.
 *
 * ### 1. O que faz
 * Remove da especificação visual as seções cujos componentes ([Capability] do tipo `type@typeVersion`)
 * não são suportados pelo cliente móvel requisitante (Eixo B de compatibilidade) e ordena as seções remanescentes.
 *
 * ### 2. Para que serve
 * Protege a estabilidade dos aplicativos clientes nativos (iOS e Android): garante que nenhum cliente receba
 * componentes que desconhece ou que ainda não possui implementados na sua base de código local, prevenindo
 * falhas graves como telas em branco, exceções de deserialização de JSON e fechamentos inesperados (crashes).
 *
 * ### 3. Como funciona
 * 1. **Filtragem de Compatibilidade:** Itera sequencialmente sobre cada [Section] da especificação.
 *    Aproveita a otimização pós-review em que a [Capability] já vem pré-calculada no construtor da seção
 *    (`section.capability`), eliminando a alocação de objetos temporários no hot path.
 * 2. **Descarte Gracioso:** Se a capacidade da seção estiver presente no conjunto de capabilities efetivas
 *    ([effectiveCaps]), ela é adicionada à lista de suportadas. Caso contrário, uma [OmittedSection] é
 *    criada com motivo [OmittedReason.UNSUPPORTED_TYPE].
 * 3. **Alocação Sob Demanda:** A lista de omissões só é instanciada se houver de fato algum componente omitido,
 *    evitando alocações desnecessárias no caminho comum de clientes 100% atualizados.
 * 4. **Ordenação Estável Otimizada:** As seções aprovadas são ordenadas via `sortBy` utilizando o mapa estático
 *    pré-calculado `skeleton.slotOrder`. Essa ordenação apoia-se na estabilidade do algoritmo TimSort da JVM,
 *    garantindo que seções alocadas no mesmo slot preservem sua ordem relativa original com complexidade $O(N \log N)$.
 *    Seções associadas a slots não previstos no skeleton são posicionadas ao final (`Int.MAX_VALUE`).
 */
object Filter {
    /**
     * Filtra as seções contra as capacidades do cliente e ordena o resultado conforme o skeleton.
     *
     * ### 1. O que faz
     * Separa as seções da lista informada entre aquelas suportadas pelo cliente e as que devem ser omitidas,
     * ordenando as seções aprovadas na sequência exata definida pelo layout do esqueleto da tela.
     *
     * ### 2. Para que serve
     * Produz o [FilterResult] definitivo que será submetido à hidratação e à verificação de slots portantes (guards).
     *
     * ### 3. Como funciona
     * Recupera o mapa de precedência de slots do [skeleton]. Classifica cada item de [sections] contra [effectiveCaps].
     * Seções não suportadas são convertidas em [OmittedSection] com [OmittedReason.UNSUPPORTED_TYPE].
     * As seções aprovadas são ordenadas via `sortBy` sobre o índice de slot, retornando uma nova instância de [FilterResult].
     *
     * @param sections Lista bruta de seções declaradas na especificação selecionada.
     * @param skeleton Esqueleto da tela contendo a ordem oficial dos slots e regras estruturais.
     * @param effectiveCaps Conjunto consolidado de capacidades suportadas pelo cliente (matriz + headers).
     * @return [FilterResult] contendo as seções aprovadas e ordenadas, além das seções omitidas.
     */
    fun filter(
        sections: List<Section>,
        skeleton: Skeleton,
        effectiveCaps: Set<Capability>,
    ): FilterResult {
        val slotOrder = skeleton.slotOrder
        val supported = ArrayList<Section>(sections.size)
        var omitted: MutableList<OmittedSection>? = null

        for (section in sections) {
            if (section.capability in effectiveCaps) {
                supported.add(section)
            } else {
                if (omitted == null) omitted = ArrayList(2)
                omitted.add(
                    OmittedSection(
                        id = section.id,
                        slot = section.slot,
                        type = section.type,
                        typeVersion = section.typeVersion,
                        reason = OmittedReason.UNSUPPORTED_TYPE,
                    ),
                )
            }
        }
        supported.sortBy { slotOrder[it.slot] ?: Int.MAX_VALUE }
        return FilterResult(supported, omitted ?: emptyList())
    }
}
