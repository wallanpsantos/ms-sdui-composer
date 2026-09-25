package br.com.empresa.sdui.core.model

/**
 * Parâmetros de destino e argumentos de execução de uma ação de interface.
 *
 * ### 1. O que faz
 * Encapsula os dados de roteamento e despacho disparados quando o usuário interage com um componente de tela.
 *
 * ### 2. Para que serve
 * Permite ao aplicativo cliente direcionar fluxos nativos: transição para telas internas via deep link (`route`),
 * exibição modal de bottom sheets nativos (`sheet`) ou emissão de eventos de telemetria e rastreamento (`event`).
 *
 * ### 3. Como funciona
 * Estrutura imutável com campos opcionais. O aplicativo móvel inspeciona quais campos estão preenchidos para acionar
 * o manipulador nativo correspondente.
 *
 * @property route Rota interna ou URI de navegação no formato app:// (ex.: "app://pix/transfer").
 * @property sheet Identificador do modal ou bottom sheet nativo a ser aberto (ex.: "sheet_pix_receipt").
 * @property event Nome do evento de analytics ou telemetria a ser disparado no clique (ex.: "cta_pix_click").
 */
data class ActionPayload(
    /**
     * Rota de navegação no aplicativo.
     *
     * ### 1. O que faz
     * Armazena a URI de destino da navegação interna.
     *
     * ### 2. Para que serve
     * Instruir o roteador nativo sobre qual tela abrir.
     *
     * ### 3. Como funciona
     * String formatada como URI ou rota interna.
     */
    val route: String? = null,

    /**
     * Identificador de bottom sheet nativo.
     *
     * ### 1. O que faz
     * Especifica o componente modal a ser instanciado pelo cliente.
     *
     * ### 2. Para que serve
     * Exibição de painéis deslizantes contextuais sem navegação de tela cheia.
     *
     * ### 3. Como funciona
     * String identificadora mapeada no catálogo de sheets nativos do app.
     */
    val sheet: String? = null,

    /**
     * Nome do evento de telemetria associado à ação.
     *
     * ### 1. O que faz
     * Identifica o evento de tracking disparado pela interação.
     *
     * ### 2. Para que serve
     * Coleta de métricas de engajamento do usuário.
     *
     * ### 3. Como funciona
     * String com o identificador do evento repassado ao SDK de telemetria móvel.
     */
    val event: String? = null,
)

/**
 * Intenção de interação serializada despachada pelo dispatcher nativo do aplicativo cliente.
 *
 * ### 1. O que faz
 * Modela uma ação executável vinculada a um elemento de interface (como um botão, card ou item de lista).
 *
 * ### 2. Para que serve
 * Desacopla a árvore visual da lógica imperativa de navegação, mantendo o backend focado em declarar intenções
 * e delegando a execução concreta aos módulos nativos do aplicativo móvel.
 *
 * ### 3. Como funciona
 * Possui um identificador [id], um tipo semântico [type] (validado contra a lista autorizada em `MvpCatalog.ALLOWED_ACTIONS`),
 * um texto acessível [label] e os parâmetros operacionais [payload].
 *
 * @property id Identificador único da ação dentro da seção.
 * @property type Tipo semântico da ação ("navigate", "open_bottom_sheet", "track", "noop").
 * @property label Rótulo textual acessível ou descritivo da ação (ex.: "Ver extrato").
 * @property payload Parâmetros de roteamento e argumentos adicionais da ação.
 */
data class Action(
    /**
     * Identificador único da ação.
     *
     * ### 1. O que faz
     * Armazena a chave de identificação da ação.
     *
     * ### 2. Para que serve
     * Diferencia múltiplas ações em uma mesma seção (ex.: botão primário vs. secundário).
     *
     * ### 3. Como funciona
     * String identificadora única no escopo da seção.
     */
    val id: String,

    /**
     * Tipo canônico de ação.
     *
     * ### 1. O que faz
     * Informa a natureza da intenção disparada pela ação.
     *
     * ### 2. Para que serve
     * Direciona o dispatcher do cliente para a rotina nativa apropriada.
     *
     * ### 3. Como funciona
     * Validado contra a allowlist de ações suportadas pelo sistema.
     */
    val type: String,

    /**
     * Rótulo de exibição ou acessibilidade da ação.
     *
     * ### 1. O que faz
     * Armazena o texto amigável do botão ou elemento interativo.
     *
     * ### 2. Para que serve
     * Apresentação visual de texto de CTA e suporte a leitores de tela nativos.
     *
     * ### 3. Como funciona
     * String textual opcional.
     */
    val label: String? = null,

    /**
     * Dados e parâmetros de destino da ação.
     *
     * ### 1. O que faz
     * Transporta os argumentos necessários para executar a ação.
     *
     * ### 2. Para que serve
     * Fornece rotas, identificadores de modais ou nomes de eventos.
     *
     * ### 3. Como funciona
     * Instância opcional de [ActionPayload].
     */
    val payload: ActionPayload? = null,
)

/**
 * Bloco atômico e autocontido de interface de usuário (Section).
 *
 * ### 1. O que faz
 * Modela o elemento fundamental de apresentação no paradigma Server-Driven UI: um componente visual concreto
 * posicionado em um slot estrutural do skeleton, contendo dados de apresentação (`props`), intenções de ação (`actions`)
 * e metadados de versionamento de componente.
 *
 * ### 2. Para que serve
 * Compõe as telas de forma modular. Cada seção é autocontida por regra arquitetural: nenhuma seção depende ou
 * referencia outra seção, permitindo omissão individual, reordenação dinâmica e hidratação assíncrona em paralelo.
 *
 * ### 3. Como funciona
 * Identificada por [id], associada a um [slot] e tipada por [type] e [typeVersion]. Contém o mapa livre de [props]
 * (higienizado por guards para barrar atributos visuais e PII). Destaque de performance pós-review: pré-calcula
 * a propriedade imutável [capability] na inicialização, eliminando a criação repetitiva de objetos no hot path
 * de filtragem de capacidades (`Filter`).
 *
 * @property id Identificador único da seção na tela.
 * @property slot Nome do slot estrutural do skeleton onde a seção será posicionada.
 * @property type Identificador do tipo de componente no catálogo (ex.: "account_card").
 * @property typeVersion Versão do contrato de renderização do componente (ex.: 1).
 * @property layout Arranjo opcional específico de layout para a seção.
 * @property props Mapa de propriedades de dados de apresentação do componente.
 * @property actions Lista de ações e intenções de interação vinculadas à seção.
 */
data class Section(
    /**
     * Identificador único da seção.
     *
     * ### 1. O que faz
     * Armazena a chave exclusiva da seção no contexto da tela.
     *
     * ### 2. Para que serve
     * Rastreabilidade, reconciliação de estado nativo (DiffUtil/Key) e telemetria.
     *
     * ### 3. Como funciona
     * String única declarada na especificação.
     */
    val id: String,

    /**
     * Slot estrutural de ancoragem no skeleton.
     *
     * ### 1. O que faz
     * Indica em qual gaveta visual da tela esta seção deve ser renderizada.
     *
     * ### 2. Para que serve
     * Permite ao skeleton ordenar e agrupar seções por responsabilidade visual.
     *
     * ### 3. Como funciona
     * String correspondente ao identificador de um slot válido no skeleton ativo.
     */
    val slot: String,

    /**
     * Tipo canônico de componente no catálogo.
     *
     * ### 1. O que faz
     * Identifica qual componente visual nativo deve renderizar esta seção.
     *
     * ### 2. Para que serve
     * Mapeamento direto para a view/composable registrado no cliente.
     *
     * ### 3. Como funciona
     * String validada contra o catálogo de componentes homologados.
     */
    val type: String,

    /**
     * Versão do contrato de renderização do componente.
     *
     * ### 1. O que faz
     * Informa a revisão do contrato de propriedades do componente.
     *
     * ### 2. Para que serve
     * Discrimina evoluções no esquema de dados suportado pelo cliente nativo.
     *
     * ### 3. Como funciona
     * Inteiro positivo ordinal.
     */
    val typeVersion: Int,

    /**
     * Ajuste opcional de layout da seção.
     *
     * ### 1. O que faz
     * Fornece diretiva semântica complementar para a apresentação da seção.
     *
     * ### 2. Para que serve
     * Permite refinar a disposição interna quando o componente suportar múltiplos layouts.
     *
     * ### 3. Como funciona
     * String indicativa de layout semântico ou `null`.
     */
    val layout: String? = null,

    /**
     * Propriedades e dados de apresentação da seção.
     *
     * ### 1. O que faz
     * Armazena os valores de texto, números e estados que preenchem o componente.
     *
     * ### 2. Para que serve
     * Alimenta a exibição de dados dinâmicos da interface (ex.: saldo, títulos, limites).
     *
     * ### 3. Como funciona
     * Mapa livre inspecionado recursivamente por guards para assegurar pureza semântica e ausência de PII.
     */
    val props: Map<String, Any?>,

    /**
     * Intenções de ação e navegação da seção.
     *
     * ### 1. O que faz
     * Agrupa as ações interativas disponíveis na seção.
     *
     * ### 2. Para que serve
     * Habilita cliques em botões, links ou toques no próprio card.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de [Action].
     */
    val actions: List<Action> = emptyList(),
) {
    /**
     * Capacidade atômica associada à seção, pré-calculada no construtor.
     *
     * ### 1. O que faz
     * Instancia o par [Capability] correspondente a [type] e [typeVersion] uma única vez na criação da seção.
     *
     * ### 2. Para que serve
     * Otimização de performance pós-review: evita a alocação redundante de objetos [Capability] no hot path
     * durante os loops de filtragem de capacidades executados pelo passo `Filter`.
     *
     * ### 3. Como funciona
     * Inicializado como `Capability(type, typeVersion)` de forma imutável.
     */
    val capability: Capability = Capability(type, typeVersion)
}

/**
 * Registro de auditoria de uma seção que foi descartada ou omitida da tela final.
 *
 * ### 1. O que faz
 * Armazena a identidade e o motivo do descarte de uma seção durante o pipeline de composição.
 *
 * ### 2. Para que serve
 * Oferece transparência e observabilidade para desenvolvedores dos apps clientes e equipes de telemetria,
 * permitindo saber exatamente quais blocos deixaram de aparecer e por qual razão formal (ex.: falta de suporte
 * nativo pelo aplicativo ou timeout em chamadas de microsserviços).
 *
 * ### 3. Como funciona
 * Captura [id], [slot], [type], [typeVersion] e o motivo padronizado [reason] ([OmittedReason]), sendo serializado
 * na coleção `omitted` do envelope de resposta.
 *
 * @property id Identificador da seção omitida.
 * @property slot Identificador do slot estrutural onde a seção estaria posicionada.
 * @property type Tipo do componente que foi descartado.
 * @property typeVersion Versão do contrato do componente.
 * @property reason Motivo formal padronizado para a omissão da seção.
 */
data class OmittedSection(
    /**
     * Identificador da seção descartada.
     *
     * ### 1. O que faz
     * Informa o ID da seção que foi excluída do resultado.
     *
     * ### 2. Para que serve
     * Localização exata do elemento ausente na tela.
     *
     * ### 3. Como funciona
     * String literal do ID da seção de origem.
     */
    val id: String,

    /**
     * Slot associado à seção omitida.
     *
     * ### 1. O que faz
     * Identifica qual slot estrutural sofreu a ausência da seção.
     *
     * ### 2. Para que serve
     * Avaliação de impacto de omissão e verificação de slots portantes.
     *
     * ### 3. Como funciona
     * String com o identificador do slot.
     */
    val slot: String,

    /**
     * Tipo do componente da seção omitida.
     *
     * ### 1. O que faz
     * Informa qual componente não pôde ser entregue.
     *
     * ### 2. Para que serve
     * Diagnóstico de defasagem de catálogo em clientes móveis.
     *
     * ### 3. Como funciona
     * String com o nome do componente no catálogo.
     */
    val type: String,

    /**
     * Versão do contrato do componente omitido.
     *
     * ### 1. O que faz
     * Informa a versão que não pôde ser atendida.
     *
     * ### 2. Para que serve
     * Auditoria de versionamento de componentes em campo.
     *
     * ### 3. Como funciona
     * Inteiro ordinal com a versão do contrato.
     */
    val typeVersion: Int,

    /**
     * Motivo padronizado para a omissão da seção.
     *
     * ### 1. O que faz
     * Especifica a causa técnica formal do descarte da seção.
     *
     * ### 2. Para que serve
     * Alimenta métricas de observabilidade e depuração de integrações móveis.
     *
     * ### 3. Como funciona
     * Enum do tipo [OmittedReason].
     */
    val reason: OmittedReason,
)
