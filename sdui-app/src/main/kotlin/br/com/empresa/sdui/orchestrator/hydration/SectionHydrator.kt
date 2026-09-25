package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.Section

/**
 * Contexto simplificado fornecido às rotinas de enriquecimento de componentes.
 *
 * ### 1. O que faz
 * Reúne o subconjunto de metadados da requisição necessário para que um [SectionHydrator] execute
 * o enriquecimento de dados da seção.
 *
 * ### 2. Para que serve
 * Aplica o princípio do menor privilégio e segregação de contexto: oculta informações sensíveis ou
 * desnecessárias do cliente (como cabeçalhos HTTP brutos e dados de rede), expondo apenas dados
 * seguros e essenciais para localização e consulta de projeções de dados.
 *
 * ### 3. Como funciona
 * Encapsula a identificação da surface solicitada, a plataforma do cliente, o identificador de revisão
 * da especificação de tela, a localidade do usuário e o canal de distribuição.
 *
 * @property surface Nome canônico da surface em processamento (ex.: `home`, `catalog`).
 * @property platform Plataforma do cliente ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
 * @property specRevisionId Identificador da revisão de especificação em vigência.
 * @property locale Código de localidade para internacionalização e formatação (ex.: `pt-BR`).
 * @property channel Canal de distribuição da requisição ([Channel.STABLE], [Channel.CANARY], [Channel.INTERNAL]).
 */
data class HydrationContext(
    val surface: String,
    val platform: ClientPlatform,
    val specRevisionId: String,
    val locale: String,
    val channel: Channel,
)

/**
 * Desfecho selado da operação de hidratação de uma seção individual.
 *
 * ### 1. O que faz
 * Modela os resultados possíveis do enriquecimento de uma seção: propriedades aprovadas ou motivo de omissão.
 *
 * ### 2. Para que serve
 * Trata falhas de enriquecimento como valores de retorno modelados no domínio em vez de exceções,
 * impedindo que a falha de um único componente cause colapso na renderização da tela completa.
 *
 * ### 3. Como funciona
 * Apresenta duas variantes seladas mutuamente exclusivas: [HydrationResult.Ok] com as propriedades
 * finais ou [HydrationResult.Failed] com a justificativa formal para omissão da seção.
 */
sealed interface HydrationResult {
    /**
     * Indica conclusão bem-sucedida do enriquecimento da seção.
     *
     * ### 1. O que faz
     * Transporta o mapa de propriedades finais enriquecidas da seção.
     *
     * ### 2. Para que serve
     * Fornece os dados atualizados que serão incluídos no envelope JSON final da tela.
     *
     * ### 3. Como funciona
     * Armazena o mapa imutável [props] contendo os valores enriquecidos para o componente.
     *
     * @property props Mapa de propriedades a ser associado à seção.
     */
    data class Ok(val props: Map<String, Any?>) : HydrationResult

    /**
     * Indica impossibilidade de enriquecer ou renderizar a seção.
     *
     * ### 1. O que faz
     * Sinaliza que a seção falhou e deve ser descartada da exibição.
     *
     * ### 2. Para que serve
     * Permite que o orquestrador registre a omissão graciosa acompanhada do motivo formal para auditoria.
     *
     * ### 3. Como funciona
     * Transporta a constante [OmittedReason] identificando se a falha decorreu de timeout ou erro de dados.
     *
     * @property reason Justificativa técnica para a omissão da seção.
     */
    data class Failed(val reason: OmittedReason) : HydrationResult
}

/**
 * Contrato SPI (Service Provider Interface) para enriquecimento dinâmico de seções Server-Driven UI.
 *
 * ### 1. O que faz
 * Define as operações para enriquecer as propriedades (`props`) de uma seção a partir de projeções de dados.
 *
 * ### 2. Para que serve
 * Permite estender o ecossistema de componentes Server-Driven UI com novas fontes de dados ou regras
 * de negócio sem modificar o núcleo do pipeline de composição.
 *
 * ### 3. Como funciona
 * O coordenador consulta [supports] para verificar compatibilidade com o tipo e versão do componente.
 * A propriedade [performsIo] orienta se o processamento ocorrerá de forma síncrona inline ou em fan-out
 * assíncrono em Virtual Threads com controle de semáforo. O método [hydrate] executa o enriquecimento.
 */
interface SectionHydrator {
    /**
     * Informa se este hidratador oferece suporte ao componente especificado.
     *
     * ### 1. O que faz
     * Avalia o tipo e versão de componente da seção.
     *
     * ### 2. Para que serve
     * Permite o roteamento determinístico de componentes para seus respectivos hidratadores especializados.
     *
     * ### 3. Como funciona
     * Retorna `true` se o par `(type, typeVersion)` for reconhecido pela implementação; `false` caso contrário.
     *
     * @param type Nome do tipo do componente (ex.: `account_card`, `credit_offer`).
     * @param typeVersion Versão semântica do contrato do componente.
     * @return `true` se o componente for suportado por este hidratador, `false` caso contrário.
     */
    fun supports(type: String, typeVersion: Int): Boolean

    /**
     * Executa o enriquecimento de propriedades da seção.
     *
     * ### 1. O que faz
     * Processa a seção e retorna o resultado da hidratação.
     *
     * ### 2. Para que serve
     * Alimenta a seção com dados dinâmicos garantindo que informações sensíveis (PII) não vazem no payload.
     *
     * ### 3. Como funciona
     * Lê os atributos da [section] e parâmetros de [context], obtém os dados necessários e devolve
     * [HydrationResult.Ok] com as novas props ou [HydrationResult.Failed] caso ocorra erro irrecuperável.
     *
     * @param context Contexto simplificado da requisição.
     * @param section Seção contendo as propriedades originais do spec.
     * @return O resultado encapsulado em [HydrationResult].
     */
    fun hydrate(context: HydrationContext, section: Section): HydrationResult

    /**
     * Indica se este hidratador executa operações bloqueantes de I/O ou espera externa.
     *
     * ### 1. O que faz
     * Sinaliza a natureza da carga de trabalho do hidratador (I/O-bound vs CPU-bound).
     *
     * ### 2. Para que serve
     * Permite ao coordenador otimizar a alocação de recursos, evitando despachar tarefas assíncronas para
     * componentes puramente estáticos.
     *
     * ### 3. Como funciona
     * Retorna `true` por padrão; hidratadores puramente computacionais ou estáticos devem sobrescrever com `false`.
     */
    val performsIo: Boolean get() = true
}
