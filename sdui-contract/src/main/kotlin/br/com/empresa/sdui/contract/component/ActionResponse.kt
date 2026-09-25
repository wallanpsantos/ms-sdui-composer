package br.com.empresa.sdui.contract.component

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Payload de parâmetros e destinos associados a uma intenção de ação.
 *
 * ### 1. O que faz
 * Encapsula os argumentos específicos necessários para o roteamento ou abertura de modais no cliente nativo.
 *
 * ### 2. Para que serve
 * Fornece os dados essenciais de direcionamento sem expor URLs externas arbitrárias, tokens de autorização
 * ou dados regulados sensíveis. Todos os destinos devem apontar para esquemas internos seguros ou componentes
 * pré-registrados no aplicativo móvel.
 *
 * ### 3. Como funciona
 * Possui campos opcionais anotados com `@JsonInclude(NON_NULL)`. O roteador interno do app examina se a ação
 * fornece um deep link em [route] (com esquema interno `app://`) ou o nome de um modal nativo em [sheet],
 * executando o despacho de acordo com a semântica de sua plataforma.
 *
 * @property route URI de deep link interno seguro do aplicativo (ex.: "app://cards/details?id=123").
 * @property sheet Identificador de um bottom sheet ou modal pré-compilado no aplicativo cliente.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionPayload(
    /**
     * Rota de deep link interno do aplicativo.
     *
     * ### 1. O que faz
     * Especifica uma URI de navegação interna profunda no formato `app://`.
     *
     * ### 2. Para que serve
     * Direciona o fluxo de navegação do aplicativo para telas de produtos ou detalhes sem expor URLs HTTP externas.
     *
     * ### 3. Como funciona
     * Interpretado pelo despachante de navegação nativo (ex.: Navigation Graph no Android, Coordinator/Router no iOS).
     */
    val route: String? = null,

    /**
     * Identificador de bottom sheet nativo.
     *
     * ### 1. O que faz
     * Nomeia um componente de diálogo ou gaveta inferior implementado nativamente no app.
     *
     * ### 2. Para que serve
     * Permite disparar a abertura de modais e sheets interativos nativos a partir de botões ou cards Server-Driven UI.
     *
     * ### 3. Como funciona
     * Mapeado pelo cliente para a fábrica nativa de bottom sheets registrados, dispensando lógica de renderização no servidor.
     */
    val sheet: String? = null,
)

/**
 * Intenção serializada de interação despachável pelo cliente nativo.
 *
 * ### 1. O que faz
 * Modela uma ação atômica e declarativa disparada pela interação do usuário com elementos visuais da tela.
 *
 * ### 2. Para que serve
 * Materializa o conceito arquitetural de **"Intenção Serializada"**: o servidor expressa estritamente **o que**
 * deve acontecer (`navigate`, `open_bottom_sheet`, `track`, `noop`), mas nunca **como** o aplicativo executa a transição,
 * animação ou push de view controllers. Essa separação garante que o aplicativo móvel preserve a fluidez e as convenções
 * de navegação nativas de cada sistema operacional.
 *
 * ### 3. Como funciona
 * As seções de UI (`SectionResponse`) associam elementos clicáveis às ações através do atributo [id]. Durante a publicação
 * de uma nova spec de tela, o validador de governança (`ActionGuard`) inspeciona o grafo de ações para garantir que não
 * existam identificadores órfãos ou duplicados. Quando o usuário clica em um botão, o renderizador nativo localiza a
 * ação pelo [id] e envia seu [type] e [payload] para o despachante central de ações da aplicação (`ActionDispatcher`).
 *
 * @property id Identificador único e imutável da ação na especificação de tela.
 * @property type Tipo canônico da ação pertencente ao vocabulário fechado (ex.: "navigate", "open_bottom_sheet", "track", "noop").
 * @property label Rótulo textual descritivo opcional associado à ação ou usado para acessibilidade (leitores de tela).
 * @property payload Parâmetros e destinos específicos para execução da ação, encapsulados em [ActionPayload].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionResponse(
    /**
     * Identificador único da ação.
     *
     * ### 1. O que faz
     * Fornece um identificador único para referência da ação dentro do payload da tela.
     *
     * ### 2. Para que serve
     * Permite que botões e cards façam referência unívoca à ação sem duplicar dados de navegação no JSON.
     *
     * ### 3. Como funciona
     * Validado na governança pelo `ActionGuard` para garantir integridade referencial com os elementos de UI.
     */
    val id: String,

    /**
     * Tipo padronizado da ação de interação.
     *
     * ### 1. O que faz
     * Declara a categoria de comando a ser executada a partir do vocabulário fechado da plataforma.
     *
     * ### 2. Para que serve
     * Orienta o despachante nativo sobre qual subsistema de controle acionar:
     * - `navigate`: Navegar para uma nova rota do app;
     * - `open_bottom_sheet`: Exibir um modal inferior nativo;
     * - `track`: Disparar um evento analítico sem alterar a tela;
     * - `noop`: Ação sem efeito operacional (apenas visual/desabilitado).
     *
     * ### 3. Como funciona
     * Interpretado via switch/when no despachante nativo para direcionar o evento ao manipulador correspondente.
     */
    val type: String,

    /**
     * Rótulo textual descritivo opcional da ação.
     *
     * ### 1. O que faz
     * Fornece um texto auxiliar descritivo sobre o propósito da ação.
     *
     * ### 2. Para que serve
     * Pode ser utilizado como texto de acessibilidade nativa (Accessibility Label) em botões ou ícones acionáveis.
     *
     * ### 3. Como funciona
     * Omitido da serialização JSON quando nulo (`@JsonInclude(NON_NULL)`).
     */
    val label: String? = null,

    /**
     * Parâmetros de rota e destino da ação.
     *
     * ### 1. O que faz
     * Encapsula as informações específicas de roteamento necessárias para a ação.
     *
     * ### 2. Para que serve
     * Entrega a rota interna ou o nome do sheet a ser aberto pelo aplicativo.
     *
     * ### 3. Como funciona
     * Transporta uma instância de [ActionPayload] quando a ação exige parâmetros (como `navigate` ou `open_bottom_sheet`),
     * permanecendo nulo para ações simples como `noop` e `track`.
     */
    val payload: ActionPayload? = null,
)
