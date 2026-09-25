package br.com.empresa.sdui.contract.screen

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Slot estrutural do esqueleto de tela: posição nomeada para acoplamento de seções.
 *
 * ### 1. O que faz
 * Modela a definição de um slot reservado na estrutura visual de uma superfície Server-Driven UI.
 *
 * ### 2. Para que serve
 * Permite que o cliente móvel reserve um espaço lógico na interface e prepare o contêiner de renderização correspondente,
 * antes mesmo da chegada das seções.
 *
 * **Semântica Pura de Layout:** O valor de [layout] representa uma categoria semântica (ex.: `fixed`, `shelf`, `list`,
 * `pager`, `grid`) e nunca dimensões de tela como pixels, densidades (`dp`, `pt`) ou breakpoints de CSS. O cliente móvel
 * é o único responsável por decidir como desenhar essa disposição no ecossistema nativo.
 *
 * ### 3. Como funciona
 * Cada slot possui um identificador único ([id]), uma disposição semântica ([layout]) e opcionalmente um título ([title]).
 * As seções recebidas na composição declaram em qual slot devem ser posicionadas através de `SectionResponse.slot`.
 *
 * @property id Identificador canônico do slot estrutural (ex.: "header", "shortcuts", "accounts").
 * @property layout Padrão semântico de disposição do slot (ex.: "fixed", "shelf", "list", "pager", "grid").
 * @property title Título textual opcional de cabeçalho do slot exibido nativamente (ex.: "Para você").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlotResponse(
    /**
     * Identificador canônico do slot estrutural.
     *
     * ### 1. O que faz
     * Nomeia unicamente a zona de exibição dentro do esqueleto da tela.
     *
     * ### 2. Para que serve
     * Serve como ponto de ancoragem para as seções de UI (`SectionResponse.slot`).
     *
     * ### 3. Como funciona
     * Validado durante a filtragem de composição para assegurar que apenas seções com slots previstos no esqueleto sejam entregues.
     */
    val id: String,

    /**
     * Disposição semântica de layout do slot.
     *
     * ### 1. O que faz
     * Informa o arquétipo de disposição dos componentes dentro do slot (ex.: "fixed", "shelf", "list").
     *
     * ### 2. Para que serve
     * Permite ao cliente nativo escolher o componente de contêiner adequado (ex.: carrossel horizontal vs lista vertical).
     *
     * ### 3. Como funciona
     * Definido na especificação do esqueleto e interpretado pelo motor nativo de layout de forma determinística.
     */
    val layout: String,

    /**
     * Título opcional de cabeçalho do slot.
     *
     * ### 1. O que faz
     * Fornece um texto de título nativo para a seção de layout quando aplicável.
     *
     * ### 2. Para que serve
     * Permite exibir títulos de seção geridos pelo próprio contêiner nativo sem necessidade de um componente de texto dedicado.
     *
     * ### 3. Como funciona
     * Campo opcional omitido da serialização JSON quando nulo (`@JsonInclude(NON_NULL)`).
     */
    val title: String? = null,
)

/**
 * Esqueleto estrutural da superfície SDUI: gabarito de slots que define o layout da tela.
 *
 * ### 1. O que faz
 * Representa o esqueleto arquitetural completo da tela, especificando os slots disponíveis e sua ordenação vertical.
 *
 * ### 2. Para que serve
 * Viabiliza a separação entre estrutura e conteúdo em Server-Driven UI. O cliente móvel utiliza o esqueleto para
 * construir o esqueleto visual (shimmers, placeholders e hierarquia de contêineres) antes de preenchê-lo com as seções,
 * eliminando o layout shift e melhorando a percepção de performance.
 *
 * ### 3. Como funciona
 * Carrega a identificação do esqueleto ([id]), o arranjo geral da tela ([layout]) e a lista ordenada de slots ([slots]).
 * Durante o pipeline de composição, o orquestrador utiliza os slots do esqueleto para ordenar rigorosamente as seções
 * hidratadas e calcular o hash criptográfico de integridade (`skeletonHash`) exposto no envelope.
 *
 * @property id Identificador canônico do esqueleto estrutural (ex.: "home.default", "catalog.default").
 * @property layout Disposição macro da superfície (ex.: "vertical_stack").
 * @property slots Lista ordenada de slots conceituais que compõem o esqueleto da tela.
 */
data class SkeletonResponse(
    /**
     * Identificador canônico do esqueleto estrutural.
     *
     * ### 1. O que faz
     * Identifica unicamente a configuração do esqueleto na governança da aplicação.
     *
     * ### 2. Para que serve
     * Permite catalogar e auditar os modelos de telas disponíveis no ecossistema SDUI.
     *
     * ### 3. Como funciona
     * Definido na especificação da tela e associado ao esqueleto cadastrado no catálogo.
     */
    val id: String,

    /**
     * Disposição macro de layout da superfície.
     *
     * ### 1. O que faz
     * Declara a estratégia geral de organização dos slots na tela (ex.: "vertical_stack").
     *
     * ### 2. Para que serve
     * Orienta o contêiner raiz de visualização do aplicativo móvel sobre a orientação do fluxo de rolagem principal.
     *
     * ### 3. Como funciona
     * Interpretado pela raiz do renderizador nativo (ex.: `UICollectionView` vertical no iOS ou `LazyColumn` no Jetpack Compose).
     */
    val layout: String,

    /**
     * Coleção ordenada de slots estruturais da tela.
     *
     * ### 1. O que faz
     * Relaciona todos os slots que compõem o esqueleto na ordem exata de exibição.
     *
     * ### 2. Para que serve
     * Garante a sequência hierárquica e a estabilidade visual dos blocos de conteúdo da superfície.
     *
     * ### 3. Como funciona
     * Lista de instâncias de [SlotResponse] ordenada de acordo com a definição formal da spec aprovada.
     */
    val slots: List<SlotResponse>,
)
