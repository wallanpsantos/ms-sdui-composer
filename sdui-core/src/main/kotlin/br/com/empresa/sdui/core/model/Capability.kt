package br.com.empresa.sdui.core.model

/**
 * Capacidade atômica de renderização de um componente em uma versão específica (Eixo B de compatibilidade).
 *
 * ### 1. O que faz
 * Modela o par `type@typeVersion`, expressando formalmente que um cliente móvel é capaz de interpretar
 * e renderizar determinado componente visual em sua respectiva versão de contrato.
 *
 * ### 2. Para que serve
 * É a unidade fundamental de negociação de renderização entre o servidor e os clientes nativos (iOS e Android).
 * Permite ao pipeline Server-Driven UI omitir graciosamente seções não suportadas pelo aplicativo antes da entrega
 * (`Filter`), viabilizando a evolução contínua do backend sem exigir atualização síncrona de todos os apps em campo.
 *
 * ### 3. Como funciona
 * Encapsula o nome semântico do componente ([type]) e sua versão inteira positiva ([typeVersion]). Fornece
 * a representação canônica em linha via [wire] (`type@typeVersion`), utilizada no cabeçalho HTTP
 * `Component-Capabilities`, na geração do hash determinístico de capacidades (`capsHash`) e nas validações
 * de elegibilidade do motor de targeting.
 *
 * @property type Nome canônico identificador do componente de UI (ex.: "top_bar", "account_card").
 * @property typeVersion Versão inteira do contrato de apresentação do componente (ex.: 1).
 */
data class Capability(
    /**
     * Identificador textual do tipo do componente.
     *
     * ### 1. O que faz
     * Armazena a chave semântica única do componente nativo.
     *
     * ### 2. Para que serve
     * Mapeia para o componente ou composable registrado no catálogo de componentes do cliente móvel.
     *
     * ### 3. Como funciona
     * Validado na inicialização para proibir valores vazios ou compostos exclusivamente por espaços em branco.
     */
    val type: String,

    /**
     * Versão ordinal do contrato do componente.
     *
     * ### 1. O que faz
     * Armazena o número de versão do contrato de renderização.
     *
     * ### 2. Para que serve
     * Diferencia alterações estruturais de propriedades ou layout em um mesmo tipo de componente.
     *
     * ### 3. Como funciona
     * Validado na inicialização para garantir valor estritamente maior que zero.
     */
    val typeVersion: Int,
) {
    init {
        require(type.isNotBlank()) { "capability type must not be blank" }
        require(typeVersion > 0) { "capability typeVersion must be > 0" }
    }

    /**
     * Formata a capacidade na convenção canônica em linha para tráfego e chaves.
     *
     * ### 1. O que faz
     * Serializa o par no formato canônico `$type@$typeVersion`.
     *
     * ### 2. Para que serve
     * Utilizado para transmissão no cabeçalho `Component-Capabilities`, logs de auditoria e cálculo de hash.
     *
     * ### 3. Como funciona
     * Interpola o nome do tipo e a versão separados pelo caractere `@`.
     *
     * @return String formatada como `type@typeVersion` (ex.: "account_card@1").
     */
    fun wire(): String = "$type@$typeVersion"

    /**
     * Utilitários estáticos de parsing, sanitização e limitação de capacidades.
     *
     * ### 1. O que faz
     * Oferece métodos utilitários para construir instâncias de [Capability] a partir de strings brutas e listas.
     *
     * ### 2. Para que serve
     * Sanitiza a entrada externa de cabeçalhos de rede e impõe limites de segurança contra saturação de memória.
     *
     * ### 3. Como funciona
     * Aplica regras defensivas com teto máximo de processamento [MAX_HEADER_CAPABILITIES] e parsing seguro.
     */
    companion object {
        /**
         * Analisa uma string individual no formato `type@version` e converte para [Capability].
         *
         * ### 1. O que faz
         * Realiza a conversão de uma string individual no par estruturado [Capability].
         *
         * ### 2. Para que serve
         * Transforma representações textuais em objetos de domínio validados para o pipeline.
         *
         * ### 3. Como funciona
         * Divide a string no caractere `@`. Exige exatamente duas partes, faz parsing da versão com `toIntOrNull()`
         * e valida se o tipo não é vazio e a versão é positiva. Retorna `null` caso qualquer regra seja violada.
         *
         * @param raw String no formato "type@version".
         * @return Instância de [Capability] ou `null` se inválida.
         */
        fun parse(raw: String): Capability? {
            val parts = raw.trim().split("@")
            if (parts.size != 2) return null
            val type = parts[0].trim()
            val version = parts[1].trim().toIntOrNull() ?: return null
            if (type.isBlank() || version <= 0) return null
            return Capability(type, version)
        }

        /**
         * Teto de capabilities aceitas a partir do cabeçalho HTTP.
         *
         * ### 1. O que faz
         * Define a quantidade máxima de capacidades processadas por requisição (64).
         *
         * ### 2. Para que serve
         * Protege contra poluição de memória, sobrecarga da CPU no pipeline de filtragem e explosão de cardinalidade
         * em chaves de cache (`capsHash`).
         *
         * ### 3. Como funciona
         * O excedente é descartado silenciosamente durante a fase de negociação sem invalidar a requisição do cliente.
         */
        const val MAX_HEADER_CAPABILITIES: Int = 64

        /**
         * Converte uma lista de capacidades separadas por vírgula em uma lista tipada e deduplicada.
         *
         * ### 1. O que faz
         * Extrai e valida todas as capacidades declaradas em um cabeçalho HTTP `Component-Capabilities`.
         *
         * ### 2. Para que serve
         * Constrói o conjunto efetivo de capacidades declaradas pelo cliente móvel na primeira fase do pipeline.
         *
         * ### 3. Como funciona
         * Divide a string por vírgulas em sequence com limite preventivo, elimina espaços, analisa via [parse],
         * remove duplicatas com `distinct()` e limita o resultado final a [MAX_HEADER_CAPABILITIES].
         *
         * @param header Valor bruto do cabeçalho `Component-Capabilities`.
         * @return Lista deduplicada de [Capability].
         */
        fun parseList(header: String?): List<Capability> {
            if (header.isNullOrBlank()) return emptyList()
            return header.splitToSequence(",")
                .take(MAX_HEADER_CAPABILITIES * 2)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parse(it) }
                .distinct()
                .take(MAX_HEADER_CAPABILITIES)
                .toList()
        }
    }
}

/**
 * Vocabulário fechado do MVP: catálogo canônico, restrições estruturais e listas de pureza visual e PII.
 *
 * ### 1. O que faz
 * Centraliza as constantes imutáveis que definem a governança estrutural do MVP do Server-Driven UI:
 * tipos de componentes aprovados, ordem de slots do skeleton canônico, slots portantes obrigatórios,
 * ações permitidas e listas de recusa para guards.
 *
 * ### 2. Para que serve
 * Garante concordância estrita entre todas as camadas de compilação e execução do serviço, impedindo desvios
 * de arquitetura. Componentes desconhecidos são barrados na publicação, e atributos visuais ou PII são
 * bloqueados antes de atingir a rede ou persistência.
 *
 * ### 3. Como funciona
 * Fornece coleções imutáveis indexadas consultadas durante as fases de validação da governança (`SpecValidator`),
 * execução de guards (`Guards`) e filtragem (`Filter`). Regras de superfícies adicionais vivem em `Surfaces`,
 * e extensões contratuais em `ComponentContracts`.
 */
object MvpCatalog {
    /**
     * Versão do protocolo de schema SDUI adotada pelo MVP.
     *
     * ### 1. O que faz
     * Define o número canônico da versão do schema do envelope ("3").
     *
     * ### 2. Para que serve
     * Valida o alinhamento com os aplicativos clientes via cabeçalho `UI-Schema-Version`.
     *
     * ### 3. Como funciona
     * Utilizada na negociação de envelope do Eixo A.
     */
    const val SCHEMA_VERSION: String = "3"

    /**
     * Identificador canônico da surface Home.
     *
     * ### 1. O que faz
     * Informa o identificador literal da superfície principal ("home").
     *
     * ### 2. Para que serve
     * Mapeia endpoints e chaves de particionamento da Home.
     *
     * ### 3. Como funciona
     * Referencia o identificador constante em `Surfaces.HOME_ID`.
     */
    const val SURFACE_HOME: String = Surfaces.HOME_ID

    /**
     * Identificador do skeleton padrão da Home.
     *
     * ### 1. O que faz
     * Especifica o nome do layout de slots canônico ("home.default").
     *
     * ### 2. Para que serve
     * Utilizado na associação de skeletons padrão para especificações da Home.
     *
     * ### 3. Como funciona
     * Constante textual usada na resolução e seed de skeletons.
     */
    const val SKELETON_HOME_DEFAULT: String = "home.default"

    /**
     * Identificador do skeleton alternativo com foco em cartões.
     *
     * ### 1. O que faz
     * Nomeia a variante de layout de slots onde cartões têm precedência ("home.cards_first").
     *
     * ### 2. Para que serve
     * Permite variações controladas de apresentação da Home.
     *
     * ### 3. Como funciona
     * Identificador utilizado em especificações especializadas.
     */
    const val SKELETON_HOME_CARDS_FIRST: String = "home.cards_first"

    /**
     * Arranjo estrutural de rolagem padrão do skeleton.
     *
     * ### 1. O que faz
     * Define o modo de scroll raiz da superfície ("vertical_scroll").
     *
     * ### 2. Para que serve
     * Instrui o container raiz do aplicativo móvel sobre a física e orientação da rolagem.
     *
     * ### 3. Como funciona
     * Ecoado no nó do skeleton da resposta de composição.
     */
    const val SKELETON_LAYOUT: String = "vertical_scroll"

    /**
     * Conjunto de versões de schema SDUI suportadas pelo servidor.
     *
     * ### 1. O que faz
     * Enumera todas as versões de schema de envelope que o BFF consegue compor.
     *
     * ### 2. Para que serve
     * Rejeita na negociação clientes que solicitem schemas incompatíveis com o servidor.
     *
     * ### 3. Como funciona
     * Conjunto imutável contendo [SCHEMA_VERSION]. Valores não reconhecidos são classificados como 'other'.
     */
    val SUPPORTED_SCHEMA_VERSIONS: Set<String> = setOf(SCHEMA_VERSION)

    /**
     * Lista canônica das capacidades de componentes homologadas no MVP.
     *
     * ### 1. O que faz
     * Enumera os sete componentes homologados na versão 1: `top_bar`, `shortcut_shelf`, `account_card`,
     * `card_product`, `credit_offer`, `coverage_card` e `decision_card`.
     *
     * ### 2. Para que serve
     * Constitui a fronteira do catálogo do MVP. Qualquer componente fora desta lista requer extensão formal de contrato.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de [Capability] com versão 1.
     */
    val TYPES: List<Capability> = listOf(
        Capability("top_bar", 1),
        Capability("shortcut_shelf", 1),
        Capability("account_card", 1),
        Capability("card_product", 1),
        Capability("credit_offer", 1),
        Capability("coverage_card", 1),
        Capability("decision_card", 1),
    )

    /**
     * Conjunto com os nomes textuais dos tipos homologados no MVP.
     *
     * ### 1. O que faz
     * Fornece os identificadores semânticos dos tipos presentes em [TYPES].
     *
     * ### 2. Para que serve
     * Permite consultas rápidas de pertencimento em tempo constante $O(1)$.
     *
     * ### 3. Como funciona
     * Mapeia os elementos de [TYPES] extraindo a propriedade `type` em um conjunto imutável.
     */
    val TYPE_NAMES: Set<String> = TYPES.map { it.type }.toSet()

    /**
     * Lista de identificadores de tipos genéricos estritamente proibidos.
     *
     * ### 1. O que faz
     * Enumera primitivas genéricas de layout ("row", "column", "container", "card", etc.).
     *
     * ### 2. Para que serve
     * Preserva o princípio arquitetural do Server-Driven UI semântico, impedindo que o backend se transforme
     * em um renderizador remoto de primitivas HTML-like ou caixas abstratas.
     *
     * ### 3. Como funciona
     * Validado preventivamente na fase de publicação de especificações pela governança.
     */
    val GENERIC_TYPE_NAMES: Set<String> = setOf(
        "row", "column", "container", "stack", "card", "generic_card", "list_item",
    )

    /**
     * Ordem canônica dos slots no skeleton `home.default`.
     *
     * ### 1. O que faz
     * Define a sequência vertical padrão dos slots: header, shortcuts, accounts, cards, offers, coverage, foryou.
     *
     * ### 2. Para que serve
     * Assegura a ordem estável de exibição das seções no envelope de resposta entregue ao cliente móvel.
     *
     * ### 3. Como funciona
     * Utilizado na fase de ordenação do pipeline (`Filter`) para classificar as seções respeitando o layout do skeleton.
     */
    val SLOT_ORDER: List<String> = listOf(
        "header", "shortcuts", "accounts", "cards", "offers", "coverage", "foryou",
    )

    /**
     * Conjunto de slots portantes obrigatórios da Home (ADR-009).
     *
     * ### 1. O que faz
     * Identifica os slots estruturais vitais da Home: "header" e "accounts".
     *
     * ### 2. Para que serve
     * Aplica a regra de resiliência: uma tela sem cabeçalho ou informações de conta é inútil ou confusa para o usuário.
     *
     * ### 3. Como funciona
     * Se qualquer um destes slots estiver vazio após o filtro de capacidades e hidratação, a resposta é considerada
     * inválida e aciona a escada de fallback (recorrendo ao cache last good ou HTTP 503 com `Retry-After`).
     */
    val REQUIRED_SLOTS: Set<String> = setOf("header", "accounts")

    /**
     * Conjunto de ações de interação autorizadas no MVP.
     *
     * ### 1. O que faz
     * Enumera os tipos de ação permitidos em componentes: "navigate", "open_bottom_sheet", "track", "noop".
     *
     * ### 2. Para que serve
     * Garante que os botões e áreas clicáveis enviem apenas intenções reconhecidas pelo dispatcher nativo do app móvel.
     *
     * ### 3. Como funciona
     * Validado na inspeção de integridade de especificações pela governança.
     */
    val ALLOWED_ACTIONS: Set<String> = setOf("navigate", "open_bottom_sheet", "track", "noop")

    /**
     * Lista estrita de propriedades visuais e de estilo proibidas no payload de props.
     *
     * ### 1. O que faz
     * Enumera chaves de aparência como cor, margens, espaçamentos, tipografia, dimensões em pixels ou pontos.
     *
     * ### 2. Para que serve
     * Garante o isolamento estrito entre servidor e apresentação: decisões visuais pertencem exclusivamente
     * ao design system nativo dos aplicativos móveis.
     *
     * ### 3. Como funciona
     * Constante pré-calculada inspecionada recursivamente em todos os nós de propriedades (`props`) de seções
     * pelos guards do pipeline de composição e governança.
     */
    val VISUAL_KEYS: Set<String> = setOf(
        "color", "background", "font", "typography",
        "margin", "padding", "gap",
        "width", "height", "radius", "rounded", "cornerRadius", "shadow",
        "orientation", "circle", "rectangle", "shimmer", "ripple", "haptic",
        "dp", "pt", "itemWidth", "itemHeight", "breakpoint", "formFactor",
        "columns", "componentType", "appearance", "presentation", "style", "size", "variant",
    )

    /**
     * Lista de termos e chaves associadas a Informações Pessoais Identificáveis (PII) e segredos regulados.
     *
     * ### 1. O que faz
     * Identifica termos sensíveis como CPF, PAN de cartão, CVV, senhas, tokens JWT, agência e conta.
     *
     * ### 2. Para que serve
     * Atende às exigências de conformidade regulatória (LGPD e BACEN), impedindo o tráfego ou armazenamento
     * acidental de dados bancários protegidos em árvores de UI, caches, logs ou métricas.
     *
     * ### 3. Como funciona
     * Constante pré-calculada inspecionada recursivamente pelos guards de segurança antes de qualquer persistência
     * ou envio pela rede.
     */
    val PII_KEYS: Set<String> = setOf(
        "cpf", "pan", "cvv", "password", "senha", "token", "jwt", "secret",
        "accountNumber", "agencia", "conta", "pin", "otp", "passcode",
    )
}
