package br.com.empresa.sdui.contract.screen

import br.com.empresa.sdui.contract.analytics.SectionAnalyticsResponse
import br.com.empresa.sdui.contract.component.ActionResponse
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

/**
 * Seção de UI renderizável: bloco visual atômico e autocontido que preenche um slot estrutural.
 *
 * ### 1. O que faz
 * Representa uma unidade individual de componente Server-Driven UI destinada a compor a superfície de tela.
 *
 * ### 2. Para que serve
 * Constitui a peça fundamental da tríade SDUI (Section, Screen, Action), transportando dados puros de negócio em
 * [props], intenções de interação do usuário em [actions] e metadados de telemetria em [analytics].
 *
 * **Regra Rígida de Pureza Visual (`ADR-010`):** É estritamente proibido trafegar atributos de CSS ou regras visuais
 * diretas (como `color`, `background`, `font`, `margin`, `padding`, `gap`, `width`, `height`, `radius`, `shadow`,
 * `orientation`, `dp`, `pt`) dentro de [props] ou em qualquer outro campo. A apresentação visual é de responsabilidade
 * exclusiva dos Design Systems nativos de iOS e Android. O servidor controla o conteúdo e o fluxo de negócio, nunca o
 * estilo estético.
 *
 * ### 3. Como funciona
 * A tupla composta por [type] e [typeVersion] define a capability necessária para renderização no cliente (Eixo B).
 * O campo [slot] vincula a seção à posição correspondente no esqueleto de tela ([SkeletonResponse]). O nó [props]
 * é manipulado como [JsonNode] agnóstico, passando por guardas rigorosos na publicação (`VisualGuard` e `PiiGuard`)
 * para assegurar que nenhum estilo visual proibido ou dado regulado sensível trafegue na resposta.
 *
 * @property id Identificador único e estável da instância da seção dentro da tela.
 * @property slot Nome do slot estrutural do esqueleto onde a seção deve ser inserida.
 * @property type Nome canônico do componente registrado no Design System nativo (ex.: "account_card").
 * @property typeVersion Versão semântica inteira do contrato do componente de UI (Eixo B de capabilities).
 * @property layout Modificador semântico de disposição da seção quando aplicável (ex.: "carousel", "grid").
 * @property props Dados puros de negócio do componente, sem atributos de estilização visual ou dados sensíveis.
 * @property actions Coleção de intenções de interação disparáveis a partir da seção (ex.: toques de botão).
 * @property analytics Metadados estruturados para emissão uniforme do evento de telemetria da seção pelo cliente.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SectionResponse(
    /**
     * Identificador único e estável da seção.
     *
     * ### 1. O que faz
     * Fornece uma identidade unívoca para a instância do componente na composição da tela.
     *
     * ### 2. Para que serve
     * Permite ao cliente nativo gerenciar o ciclo de vida do componente, controlar a reconciliação em listas
     * (diffing de UI nativo) e correlacionar eventos de analytics e ações com precisão.
     *
     * ### 3. Como funciona
     * Definido na especificação da tela e preservado imutável durante todas as etapas do pipeline de composição.
     */
    val id: String,

    /**
     * Nome do slot de destino no esqueleto estrutural.
     *
     * ### 1. O que faz
     * Aponta para o slot do [SkeletonResponse] onde esta seção deve ser renderizada.
     *
     * ### 2. Para que serve
     * Garante o correto desacoplamento entre o esqueleto estrutural e o conteúdo das seções, permitindo que múltiplos
     * componentes preencham o mesmo slot quando o layout permitir.
     *
     * ### 3. Como funciona
     * Validado na fase de filtragem contra os slots registrados no esqueleto, determinando a ordem final de renderização.
     */
    val slot: String,

    /**
     * Nome canônico do componente no Design System nativo.
     *
     * ### 1. O que faz
     * Identifica o tipo semântico do componente (ex.: "top_bar", "account_card", "credit_offer").
     *
     * ### 2. Para que serve
     * Permite que a fábrica de componentes nativa (Component Registry) instancie a visualização nativa correspondente.
     *
     * ### 3. Como funciona
     * Mapeado diretamente do catálogo de componentes aprovados e verificado contra as capabilities do cliente móvel.
     */
    val type: String,

    /**
     * Versão do contrato do componente de UI.
     *
     * ### 1. O que faz
     * Informa a versão semântica inteira do contrato de props do componente (ex.: 1).
     *
     * ### 2. Para que serve
     * Permite a evolução independente de componentes no Design System sem quebrar clientes legados (Eixo B de capabilities).
     *
     * ### 3. Como funciona
     * Confrontado com as capabilities declaradas pelo cliente (`Client-Capabilities`) no estágio de filtragem; caso o app
     * não suporte esta versão, a seção é omitida graciosamente.
     */
    val typeVersion: Int,

    /**
     * Modificador semântico de disposição da seção.
     *
     * ### 1. O que faz
     * Define uma variação semântica de exibição opcional para o contêiner da seção (ex.: "carousel", "grid", "stack").
     *
     * ### 2. Para que serve
     * Orienta o cliente sobre o comportamento de agregação visual do slot sem definir larguras, alturas ou pixels.
     *
     * ### 3. Como funciona
     * Campo opcional omitido na serialização quando nulo (`@JsonInclude(NON_NULL)`), validado contra o vocabulário de layouts permitidos.
     */
    val layout: String? = null,

    /**
     * Propriedades e dados de negócio puros do componente.
     *
     * ### 1. O que faz
     * Transporta o payload de dados específico do componente no formato de árvore JSON estruturada.
     *
     * ### 2. Para que serve
     * Fornece os textos, valores, imagens de domínio e dados funcionais para o componente nativo exibir, livre de estilos CSS.
     *
     * ### 3. Como funciona
     * Representado como [JsonNode] para desacoplar o módulo de contrato dos esquemas internos de cada componente,
     * submetido aos guardas `VisualGuard` e `PiiGuard` antes da entrega.
     */
    val props: JsonNode,

    /**
     * Lista de intenções de interação vinculadas à seção.
     *
     * ### 1. O que faz
     * Contém as ações de navegação ou comandos disponíveis para disparo a partir desta seção.
     *
     * ### 2. Para que serve
     * Permite que botões e elementos interativos da seção disparem fluxos nativos sem acoplamento de código.
     *
     * ### 3. Como funciona
     * Lista de instâncias de [ActionResponse], referenciadas por identificadores nas propriedades da seção e
     * validadas na publicação pelo `ActionGuard`.
     */
    val actions: List<ActionResponse> = emptyList(),

    /**
     * Metadados estruturados para telemetria da seção.
     *
     * ### 1. O que faz
     * Contém as dimensões analíticas prontas para disparo de eventos de impressão e engajamento da seção.
     *
     * ### 2. Para que serve
     * Padroniza o envio de métricas de visualização (`section_view`) para todas as plataformas móveis.
     *
     * ### 3. Como funciona
     * Transporta uma instância de [SectionAnalyticsResponse] pré-calculada pelo BFF com identificadores da seção e da spec.
     */
    val analytics: SectionAnalyticsResponse,
)
