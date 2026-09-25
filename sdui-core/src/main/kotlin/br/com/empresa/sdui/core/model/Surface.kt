package br.com.empresa.sdui.core.model

/**
 * Regra canônica de um slot no âmbito de uma superfície específica.
 *
 * ### 1. O que faz
 * Modela as restrições estruturais de um slot para determinada tela: quais arranjos semânticos de layout
 * ele autoriza e se sua presença é portante obrigatória.
 *
 * ### 2. Para que serve
 * Define a política que o validador de skeletons (`SkeletonValidator`) aplica durante a submissão e publicação de layouts.
 * O skeleton versionado pode escolher um subconjunto restrito de layouts, mas jamais alargar os permitidos além do definido aqui.
 *
 * ### 3. Como funciona
 * Encapsula o identificador [id], a lista de layouts semânticos autorizados [allowedLayouts] e o indicador [required].
 * Se [required] for verdadeiro, o slot não pode estar ausente ou vazio na composição final da tela.
 *
 * @property id Identificador exclusivo do slot (ex.: "header", "accounts", "products").
 * @property allowedLayouts Lista de arranjos semânticos autorizados para este slot na superfície.
 * @property required Indica se o slot é portante obrigatório para a renderização da superfície.
 */
data class SlotRule(
    /**
     * Identificador do slot estrutural.
     *
     * ### 1. O que faz
     * Armazena a chave de referência do slot.
     *
     * ### 2. Para que serve
     * Mapeamento entre regras de superfície e os slots do skeleton.
     *
     * ### 3. Como funciona
     * String semântica padronizada.
     */
    val id: String,

    /**
     * Coleção de layouts semânticos permitidos para o slot.
     *
     * ### 1. O que faz
     * Enumera os modelos de container visual autorizados ([SlotLayout.FIXED], [SlotLayout.SHELF], etc.).
     *
     * ### 2. Para que serve
     * Garante coerência de apresentação impedindo layouts incompatíveis com a natureza da região.
     *
     * ### 3. Como funciona
     * Lista imutável de [SlotLayout].
     */
    val allowedLayouts: List<SlotLayout>,

    /**
     * Indicador de obrigatoriedade portante do slot.
     *
     * ### 1. O que faz
     * Informa se a tela pode ou não ser entregue caso este slot fique desprovido de seções.
     *
     * ### 2. Para que serve
     * Aplicação da regra de resiliência e integridade da experiência visual (ADR-009).
     *
     * ### 3. Como funciona
     * `true` para slots indispensáveis, `false` para seções opcionais.
     */
    val required: Boolean,
)

/**
 * Definição formal e canônica de uma superfície de tela atendida pelo BFF SDUI.
 *
 * ### 1. O que faz
 * Modela a especificação completa de uma superfície: vocabulário fechado de slots, tipos de componentes homologados,
 * primeiro slot obrigatório de cabeçalho, evento padrão de analytics e idioma do conteúdo (ADR-020).
 *
 * ### 2. Para que serve
 * Funciona como o contrato delimitador de cada tela do aplicativo. Impede que componentes estranhos ocupem superfícies
 * inadequadas e estabelece a base para geração uniforme de envelopes de resposta e métricas.
 *
 * ### 3. Como funciona
 * Identificada por [id], possui lista de [slots], conjunto de [types] autorizados, primeiro slot esperado [firstSlot],
 * nome do evento de analytics [analyticsEvent] e idioma base [contentLocale]. Pré-calcula [slotIds] e [requiredSlots]
 * para validações instantâneas $O(1)$ no hot path.
 *
 * @property id Identificador exclusivo da superfície (ex.: "home", "catalog").
 * @property slots Lista de regras estruturais aplicáveis aos slots desta superfície.
 * @property types Conjunto de tipos canônicos de componentes autorizados a compor a superfície.
 * @property firstSlot Nome do primeiro slot esperado na ordem de exibição da tela.
 * @property analyticsEvent Nome do evento de telemetria emitido no envelope da resposta.
 * @property contentLocale Código de localização e idioma do conteúdo padrão (ex.: "pt-BR").
 * @property skeletonLayout Arranjo geral de rolagem da tela (padrão [MvpCatalog.SKELETON_LAYOUT]).
 */
data class SurfaceDefinition(
    /**
     * Identificador textual da superfície.
     *
     * ### 1. O que faz
     * Armazena a chave canônica da tela no ecossistema SDUI.
     *
     * ### 2. Para que serve
     * Roteamento de endpoints HTTP, chaves de cache e segregação de métricas.
     *
     * ### 3. Como funciona
     * String literal (ex.: "home", "catalog").
     */
    val id: String,

    /**
     * Regras estruturais dos slots da superfície.
     *
     * ### 1. O que faz
     * Coleção de definições de layout e obrigatoriedade de cada slot.
     *
     * ### 2. Para que serve
     * Validação de integridade de skeletons submetidos pela governança.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de [SlotRule].
     */
    val slots: List<SlotRule>,

    /**
     * Catálogo fechado de componentes autorizados para a superfície.
     *
     * ### 1. O que faz
     * Enumera os tipos de componentes que têm permissão para aparecer nesta tela.
     *
     * ### 2. Para que serve
     * Protege contra poluição arquitetural e mistura indevida de domínios visuais.
     *
     * ### 3. Como funciona
     * Conjunto imutável de strings de tipos do catálogo.
     */
    val types: Set<String>,

    /**
     * Identificador do primeiro slot estrutural da tela.
     *
     * ### 1. O que faz
     * Especifica qual slot deve obrigatoriamente iniciar o layout da tela (ex.: "header").
     *
     * ### 2. Para que serve
     * Validação de coerência da barra superior na ordem de exibição.
     *
     * ### 3. Como funciona
     * String com o identificador do primeiro slot.
     */
    val firstSlot: String,

    /**
     * Identificador do evento de analytics de composição.
     *
     * ### 1. O que faz
     * Nomeia o evento de telemetria transportado no nó de analytics do envelope de resposta.
     *
     * ### 2. Para que serve
     * Rastreamento padronizado de renderização pelas ferramentas de observabilidade móvel.
     *
     * ### 3. Como funciona
     * String com o nome do evento (ex.: "sdui_home_composed").
     */
    val analyticsEvent: String,

    /**
     * Localização e idioma do conteúdo sintetizado.
     *
     * ### 1. O que faz
     * Registra o locale padrão das especificações desta superfície.
     *
     * ### 2. Para que serve
     * Alinhamento internacional de formatação de moeda, números e textos.
     *
     * ### 3. Como funciona
     * String de idioma/região (ex.: "pt-BR").
     */
    val contentLocale: String,

    /**
     * Modelo macro de rolagem do skeleton.
     *
     * ### 1. O que faz
     * Define o arranjo de scroll da raiz da superfície.
     *
     * ### 2. Para que serve
     * Orienta o contêiner raiz no aplicativo nativo.
     *
     * ### 3. Como funciona
     * Padrão preenchido com `MvpCatalog.SKELETON_LAYOUT` ("vertical_scroll").
     */
    val skeletonLayout: String = MvpCatalog.SKELETON_LAYOUT,
) {
    /**
     * Conjunto de identificadores de todos os slots que pertencem a esta superfície.
     *
     * ### 1. O que faz
     * Extrai os IDs de todos os slots declarados em [slots].
     *
     * ### 2. Para que serve
     * Verificação rápida de pertinência de slots em tempo constante $O(1)$.
     *
     * ### 3. Como funciona
     * Inicializado imutavelmente através de `slots.map { it.id }.toSet()`.
     */
    val slotIds: Set<String> = slots.map { it.id }.toSet()

    /**
     * Conjunto de identificadores de slots portantes obrigatórios da superfície.
     *
     * ### 1. O que faz
     * Agrupa os IDs dos slots configurados com `required = true`.
     *
     * ### 2. Para que serve
     * Consulta imediata para aplicação de políticas de resiliência e fallback (ADR-009).
     *
     * ### 3. Como funciona
     * Inicializado imutavelmente através de `slots.filter { it.required }.map { it.id }.toSet()`.
     */
    val requiredSlots: Set<String> = slots.filter { it.required }.map { it.id }.toSet()

    /**
     * Mapa interno indexado por ID para busca rápida de regras de slots.
     */
    private val byId: Map<String, SlotRule> = slots.associateBy { it.id }

    /**
     * Recupera a regra de um slot a partir de seu identificador.
     *
     * ### 1. O que faz
     * Localiza o [SlotRule] correspondente ao ID informado nesta superfície.
     *
     * ### 2. Para que serve
     * Consulta de layouts autorizados e obrigatoriedade de um slot.
     *
     * ### 3. Como funciona
     * Busca em tempo constante $O(1)$ no mapa indexado [byId].
     *
     * @param id Identificador do slot desejado.
     * @return [SlotRule] correspondente ou `null` caso não pertença a esta superfície.
     */
    fun slot(id: String): SlotRule? = byId[id]
}

/**
 * Allowlist e repositório oficial de superfícies suportadas pelo BFF SDUI.
 *
 * ### 1. O que faz
 * Centraliza o registro estático de todas as superfícies autorizadas do sistema ([HOME] e [CATALOG]).
 *
 * ### 2. Para que serve
 * Impede a execução de rotas e processamento de telas desconhecidas, atuando como a barreira de segurança
 * inicial na borda REST (`SurfaceController`). Garante que apenas superfícies expressamente homologadas
 * gerem chaves de cache, persistência e métricas operacionais.
 *
 * ### 3. Como funciona
 * Mantém instâncias completas de [SurfaceDefinition] indexadas por ID em mapa estático [IDS]. Provê o método
 * de resolução segura [find] e utilitário de layouts padrão [defaultAllowedLayouts] para slots compartilhados.
 */
object Surfaces {
    /**
     * Identificador textual da superfície Home.
     *
     * ### 1. O que faz
     * Define a constante "home".
     *
     * ### 2. Para que serve
     * Mapeamento de rotas e identificação da tela principal do aplicativo.
     *
     * ### 3. Como funciona
     * Constante de string utilizada em toda a base de código.
     */
    const val HOME_ID: String = "home"

    /**
     * Identificador textual da superfície Catálogo (ADR-020).
     *
     * ### 1. O que faz
     * Define a constante "catalog".
     *
     * ### 2. Para que serve
     * Mapeamento de rotas da superfície de comércio e produtos.
     *
     * ### 3. Como funciona
     * Constante de string para a superfície de catálogo.
     */
    const val CATALOG_ID: String = "catalog"

    /**
     * Definição estrutural canônica da superfície Home.
     *
     * ### 1. O que faz
     * Especifica os slots, layouts, types e regras da tela inicial bancária.
     *
     * ### 2. Para que serve
     * Aplica o padrão MVP com slots portantes vitais `header` e `accounts`, além do slot opcional `transactions`.
     *
     * ### 3. Como funciona
     * Instância de [SurfaceDefinition] configurada para evento "sdui_home_composed" e locale "pt-BR".
     */
    val HOME: SurfaceDefinition = SurfaceDefinition(
        id = HOME_ID,
        slots = listOf(
            SlotRule("header", listOf(SlotLayout.FIXED), required = true),
            SlotRule("shortcuts", listOf(SlotLayout.SHELF, SlotLayout.GRID), required = false),
            SlotRule("accounts", listOf(SlotLayout.LIST, SlotLayout.FIXED), required = true),
            SlotRule("cards", listOf(SlotLayout.LIST, SlotLayout.PAGER), required = false),
            SlotRule("offers", listOf(SlotLayout.LIST, SlotLayout.PAGER), required = false),
            SlotRule("coverage", listOf(SlotLayout.LIST, SlotLayout.SHELF), required = false),
            SlotRule("foryou", listOf(SlotLayout.PAGER, SlotLayout.LIST), required = false),
            SlotRule("transactions", listOf(SlotLayout.LIST), required = false),
        ),
        types = setOf(
            "top_bar", "shortcut_shelf", "account_card", "card_product",
            "credit_offer", "coverage_card", "decision_card", "transaction_summary",
        ),
        firstSlot = "header",
        analyticsEvent = "sdui_home_composed",
        contentLocale = "pt-BR",
    )

    /**
     * Definição estrutural canônica da superfície Catalog (ADR-020).
     *
     * ### 1. O que faz
     * Especifica os slots, layouts, types e regras da tela de catálogo comercial.
     *
     * ### 2. Para que serve
     * Provê a experiência de vitrine e compras, com slots portantes `header` e `products` (sem slot financeiro obrigatório).
     *
     * ### 3. Como funciona
     * Instância de [SurfaceDefinition] configurada para evento "sdui_catalog_composed" e locale "pt-BR".
     */
    val CATALOG: SurfaceDefinition = SurfaceDefinition(
        id = CATALOG_ID,
        slots = listOf(
            SlotRule("header", listOf(SlotLayout.FIXED), required = true),
            SlotRule("navigation", listOf(SlotLayout.SHELF, SlotLayout.FIXED), required = false),
            SlotRule("featured", listOf(SlotLayout.PAGER, SlotLayout.LIST), required = false),
            SlotRule("products", listOf(SlotLayout.GRID, SlotLayout.LIST), required = true),
        ),
        types = setOf("top_bar", "catalog_navigation", "product_collection"),
        firstSlot = "header",
        analyticsEvent = "sdui_catalog_composed",
        contentLocale = "pt-BR",
    )

    /**
     * Coleção de todas as superfícies autorizadas no BFF.
     *
     * ### 1. O que faz
     * Lista completa contendo as instâncias de [HOME] e [CATALOG].
     *
     * ### 2. Para que serve
     * Fonte de iteração para inicializações, testes de contrato e validações gerais.
     *
     * ### 3. Como funciona
     * Lista imutável contendo todas as superfícies suportadas.
     */
    val ALL: List<SurfaceDefinition> = listOf(HOME, CATALOG)

    /**
     * Mapa estático de indexação por identificador de superfície.
     */
    private val BY_ID: Map<String, SurfaceDefinition> = ALL.associateBy { it.id }

    /**
     * Conjunto de identificadores válidos de superfícies.
     *
     * ### 1. O que faz
     * Reúne todas as chaves de superfície cadastradas.
     *
     * ### 2. Para que serve
     * Validação rápida de existência de superfícies em tempo constante $O(1)$.
     *
     * ### 3. Como funciona
     * Conjunto de chaves derivado do mapa indexado.
     */
    val IDS: Set<String> = BY_ID.keys

    /**
     * Localiza a definição de uma superfície a partir de seu identificador.
     *
     * ### 1. O que faz
     * Recupera a instância de [SurfaceDefinition] correspondente ao ID informado.
     *
     * ### 2. Para que serve
     * Validação na camada REST de requisições de clientes móveis.
     *
     * ### 3. Como funciona
     * Consulta em tempo constante $O(1)$ no mapa indexado, retornando `null` para IDs desconhecidos ou nulos.
     *
     * @param id Identificador da superfície desejada.
     * @return [SurfaceDefinition] correspondente ou `null` se não suportada.
     */
    fun find(id: String?): SurfaceDefinition? = id?.let { BY_ID[it] }

    /**
     * Retorna os layouts semânticos padrão autorizados para um determinado slot.
     *
     * ### 1. O que faz
     * Consulta as superfícies registradas para identificar os layouts padrão permitidos para um ID de slot.
     *
     * ### 2. Para que serve
     * Fornece o valor de fallback para skeletons que não declaram layouts explícitos em seus slots.
     *
     * ### 3. Como funciona
     * Varre a lista [ALL] e devolve os layouts do primeiro slot com o ID correspondente.
     *
     * @param slotId Identificador do slot (ex.: "header").
     * @return Lista de [SlotLayout] permitidos ou `null` caso o slot não pertença a nenhuma superfície.
     */
    fun defaultAllowedLayouts(slotId: String): List<SlotLayout>? =
        ALL.firstNotNullOfOrNull { it.slot(slotId)?.allowedLayouts }
}

/**
 * Inventário canônico de contratos de componentes homologados no ecossistema SDUI.
 *
 * ### 1. O que faz
 * Centraliza a relação de todos os pares `type@typeVersion` de componentes homologados pela engenharia móvel
 * e governança de backend (ADR-020).
 *
 * ### 2. Para que serve
 * Discrimina entre os componentes legados do MVP ([LEGACY_HOME]), concedidos tacitamente a todos os apps existentes,
 * e os novos componentes ([TRANSACTION_SUMMARY], [CATALOG_NAVIGATION], [PRODUCT_COLLECTION]), que exigem declaração
 * explícita de capacidade pelo aplicativo no cabeçalho `Component-Capabilities`.
 *
 * ### 3. Como funciona
 * Combina o conjunto [LEGACY_HOME] com as extensões homologadas em [APPROVED]. Fornece o método [isApproved] para
 * validação instantânea de conformidade contratual durante a publicação de especificações.
 */
object ComponentContracts {
    /**
     * Capacidade do componente de resumo de transações (`transaction_summary@1`).
     *
     * ### 1. O que faz
     * Define o par [Capability] para o extrato compacto de transações.
     *
     * ### 2. Para que serve
     * Extensão contratual para a Home com histórico financeiro rápido.
     *
     * ### 3. Como funciona
     * Instância de `Capability("transaction_summary", 1)`.
     */
    val TRANSACTION_SUMMARY: Capability = Capability("transaction_summary", 1)

    /**
     * Capacidade do componente de navegação de categorias de catálogo (`catalog_navigation@1`).
     *
     * ### 1. O que faz
     * Define o par [Capability] para a esteira de categorias da superfície de catálogo.
     *
     * ### 2. Para que serve
     * Habilita a navegação temática na superfície de compras.
     *
     * ### 3. Como funciona
     * Instância de `Capability("catalog_navigation", 1)`.
     */
    val CATALOG_NAVIGATION: Capability = Capability("catalog_navigation", 1)

    /**
     * Capacidade do componente de coleção e grade de produtos (`product_collection@1`).
     *
     * ### 1. O que faz
     * Define o par [Capability] para a vitrine de produtos e serviços.
     *
     * ### 2. Para que serve
     * Apresentação comercial de produtos em grade ou lista.
     *
     * ### 3. Como funciona
     * Instância de `Capability("product_collection", 1)`.
     */
    val PRODUCT_COLLECTION: Capability = Capability("product_collection", 1)

    /**
     * Conjunto das sete capacidades de componentes do catálogo do MVP.
     *
     * ### 1. O que faz
     * Reúne os componentes originais da versão 1 da Home.
     *
     * ### 2. Para que serve
     * Presumido como suportado por qualquer aplicativo em campo via matriz do servidor.
     *
     * ### 3. Como funciona
     * Derivado diretamente de `MvpCatalog.TYPES.toSet()`.
     */
    val LEGACY_HOME: Set<Capability> = MvpCatalog.TYPES.toSet()

    /**
     * Conjunto completo de todas as capacidades de componentes homologadas.
     *
     * ### 1. O que faz
     * Une o catálogo legado do MVP com todas as novas extensões homologadas.
     *
     * ### 2. Para que serve
     * Fronteira estrita de validação: componentes fora deste conjunto são rejeitados na governança.
     *
     * ### 3. Como funciona
     * União de conjuntos imutável entre [LEGACY_HOME] e os novos contratos.
     */
    val APPROVED: Set<Capability> = LEGACY_HOME + setOf(TRANSACTION_SUMMARY, CATALOG_NAVIGATION, PRODUCT_COLLECTION)

    /**
     * Conjunto dos nomes textuais de todos os tipos homologados.
     *
     * ### 1. O que faz
     * Mapeia os identificadores semânticos dos tipos contidos em [APPROVED].
     *
     * ### 2. Para que serve
     * Consulta rápida de nomes de tipo autorizados.
     *
     * ### 3. Como funciona
     * Extrai a propriedade `type` de cada elemento de [APPROVED].
     */
    val APPROVED_TYPE_NAMES: Set<String> = APPROVED.map { it.type }.toSet()

    /**
     * Avalia se um determinado componente em uma versão específica é homologado.
     *
     * ### 1. O que faz
     * Verifica se o par `type@typeVersion` pertence ao catálogo aprovado do sistema.
     *
     * ### 2. Para que serve
     * Utilizado na validação de governança para barrar especificações com componentes não aprovados.
     *
     * ### 3. Como funciona
     * Avalia se qualquer elemento de [APPROVED] coincide exatamente com o tipo e versão informados.
     *
     * @param type Nome identificador do componente.
     * @param typeVersion Versão numérica do contrato do componente.
     * @return `true` se o componente for homologado, `false` caso contrário.
     */
    fun isApproved(type: String, typeVersion: Int): Boolean =
        APPROVED.any { it.type == type && it.typeVersion == typeVersion }
}
