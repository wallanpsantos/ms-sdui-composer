package br.com.empresa.sdui.core.model

/**
 * Identidade e papel do operador que executa uma operação no plano administrativo.
 *
 * ### 1. O que faz
 * Modela as credenciais operacionais de um agente humano ou automação que requisita ações de governança (publicação,
 * aprovação, rejeição ou rollback de especificações).
 *
 * ### 2. Para que serve
 * Sustenta o mecanismo de segregação de funções maker-checker e a rastreabilidade em registros imutáveis de auditoria,
 * garantindo que ações críticas sejam atribuídas ao operador e papel corretos.
 *
 * ### 3. Como funciona
 * Extraído na borda administrativa a partir dos cabeçalhos `Actor-Id` e `Actor-Role`. O papel [role] determina se o ator
 * possui permissão para propor (`ActorRole.MAKER`) ou homologar (`ActorRole.CHECKER`) uma especificação.
 *
 * @property id Identificador único e auditável do operador (ex.: email, matrícula corporativa ou ID de serviço).
 * @property role Papel funcional reivindicado pelo operador na governança.
 */
data class Actor(
    /**
     * Identificador do operador.
     *
     * ### 1. O que faz
     * Armazena o código de identificação do operador.
     *
     * ### 2. Para que serve
     * Rastreabilidade em trilhas de auditoria e controle de autoria de propostas.
     *
     * ### 3. Como funciona
     * String informada no cabeçalho `Actor-Id`.
     */
    val id: String,

    /**
     * Papel funcional do operador.
     *
     * ### 1. O que faz
     * Informa a função exercida pelo operador no fluxo maker-checker.
     *
     * ### 2. Para que serve
     * Validação de autorização para aprovação ou rejeição de pedidos de publicação.
     *
     * ### 3. Como funciona
     * Enum do tipo [ActorRole].
     */
    val role: ActorRole,
)

/**
 * Metadados consolidados, sanitizados e imutáveis do cliente requisitante da tela.
 *
 * ### 1. O que faz
 * Reúne o conjunto completo de parâmetros contextuais validados do dispositivo cliente: plataforma, versões,
 * capabilities declaradas, canal e idioma.
 *
 * ### 2. Para que serve
 * É a entrada de autoridade para todas as fases subsequentes do pipeline (`Select`, `Filter`, `Hydrate` e `Compose`).
 * Unifica os três eixos de compatibilidade: Eixo A ([schemaVersion]), Eixo B ([headerCapabilities]) e
 * Eixo C ([platform], [appVersion], [build], [osVersion]).
 *
 * ### 3. Como funciona
 * Construído na fase inicial de `Negotiate`. Uma vez retornado como [ContextValidation.Valid], assegura a ausência
 * de valores nulos ou corrompidos. Fornece [parsedSchemaVersion] pré-calculada para comparações eficientes de targeting.
 *
 * @property platform Plataforma móvel do cliente (iOS ou Android).
 * @property appVersion Versão semântica ordinal do aplicativo móvel.
 * @property build Número ordinal de compilação do binário do aplicativo.
 * @property osVersion Versão semântica do sistema operacional do dispositivo, ou `null` se não informada.
 * @property osVersionRaw String representativa da versão do sistema operacional móvel.
 * @property schemaVersion Versão textual do protocolo de schema SDUI negociada.
 * @property locale Identificador de localização e idioma solicitado (ex.: "pt-BR").
 * @property apiVersion Versão da API HTTP negociada via cabeçalho `API-Version`.
 * @property headerCapabilities Lista de capacidades informadas pelo cliente no cabeçalho `Component-Capabilities`.
 * @property channelHint Canal de distribuição pretendido pelo cliente (padrão [Channel.STABLE]).
 */
data class ClientContext(
    /**
     * Plataforma operacional do cliente móvel.
     *
     * ### 1. O que faz
     * Identifica o sistema operacional do aplicativo solicitante ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
     *
     * ### 2. Para que serve
     * Garante o isolamento estrito de seletores de spec, pointers e caches entre plataformas.
     *
     * ### 3. Como funciona
     * Validado contra a allowlist de plataformas na fase de negociação.
     */
    val platform: ClientPlatform,

    /**
     * Versão de release semântica do aplicativo cliente.
     *
     * ### 1. O que faz
     * Armazena a versão semântica instalada no dispositivo móvel.
     *
     * ### 2. Para que serve
     * Avalia regras de elegibilidade e targeting de faixas de app (Eixo C).
     *
     * ### 3. Como funciona
     * Objeto [SemVer] gerado a partir do cabeçalho `Client-Version`.
     */
    val appVersion: SemVer,

    /**
     * Número sequencial de compilação do binário do aplicativo.
     *
     * ### 1. O que faz
     * Transporta o número de build do binário cliente.
     *
     * ### 2. Para que serve
     * Identifica coortes de release e audita versões específicas de testes ou distribuição.
     *
     * ### 3. Como funciona
     * Extraído e sanitizado do cabeçalho `Client-Build`.
     */
    val build: String,

    /**
     * Versão semântica do sistema operacional do dispositivo.
     *
     * ### 1. O que faz
     * Armazena a versão do sistema operacional parseada como [SemVer].
     *
     * ### 2. Para que serve
     * Permite ao motor de targeting verificar restrições de compatibilidade nativa de SO.
     *
     * ### 3. Como funciona
     * Parseado de forma flexível a partir de `Client-OS-Version`.
     */
    val osVersion: SemVer?,

    /**
     * Representação textual da versão do sistema operacional.
     *
     * ### 1. O que faz
     * Armazena a versão de SO formatada para resposta de eco e logs.
     *
     * ### 2. Para que serve
     * Facilita a auditoria da versão de SO sem necessidade de reformatar o objeto semântico.
     *
     * ### 3. Como funciona
     * Obtido via [SemVer.toOsString] ou string vazia se ausente.
     */
    val osVersionRaw: String = osVersion?.toOsString() ?: "",

    /**
     * Versão do schema de envelope SDUI negociada.
     *
     * ### 1. O que faz
     * Informa a versão de protocolo de contrato acordada (ex.: "3").
     *
     * ### 2. Para que serve
     * Garante conformidade com o formato estrutural esperado pelo cliente móvel (Eixo A).
     *
     * ### 3. Como funciona
     * Validado contra as versões suportadas pelo BFF na negociação.
     */
    val schemaVersion: String,

    /**
     * Localização e preferência de idioma da requisição.
     *
     * ### 1. O que faz
     * Armazena a tag de idioma solicitada (ex.: "pt-BR").
     *
     * ### 2. Para que serve
     * Ecoado no envelope de resposta para auditoria e internacionalização de conteúdo.
     *
     * ### 3. Como funciona
     * Extraído do cabeçalho HTTP `Accept-Language` com fallback seguro para "pt-BR".
     */
    val locale: String,

    /**
     * Versão da API HTTP informada na requisição.
     *
     * ### 1. O que faz
     * Registra o valor do cabeçalho `API-Version` (ex.: "1").
     *
     * ### 2. Para que serve
     * Rastreia o versionamento da camada REST independentemente da versão de UI Schema.
     *
     * ### 3. Como funciona
     * Validado contra a versão atual da API exposta.
     */
    val apiVersion: String,

    /**
     * Lista de capacidades de componentes declaradas pelo aplicativo.
     *
     * ### 1. O que faz
     * Coleção de pares [Capability] informados no cabeçalho `Component-Capabilities`.
     *
     * ### 2. Para que serve
     * Alimenta a resolução de capabilities efetivas combinadas com a matriz de compatibilidade do servidor.
     *
     * ### 3. Como funciona
     * Lista deduplicada e truncada respeitando o teto de segurança.
     */
    val headerCapabilities: List<Capability>,

    /**
     * Dica de canal de distribuição solicitado pelo cliente.
     *
     * ### 1. O que faz
     * Indica o canal preferencial de entrega ([Channel.STABLE], [Channel.CANARY] ou [Channel.INTERNAL]).
     *
     * ### 2. Para que serve
     * Permite direcionar dispositivos de teste para ponteiros de canary sem afetar a produção.
     *
     * ### 3. Como funciona
     * Extraído do cabeçalho `Channel` com fallback seguro para [Channel.STABLE].
     */
    val channelHint: Channel = Channel.STABLE,
) {
    /**
     * Versão de schema parseada para uso em regras de targeting.
     *
     * ### 1. O que faz
     * Converte [schemaVersion] para a representação semântica [SemVer].
     *
     * ### 2. Para que serve
     * Permite comparações ordinais diretas em faixas de versão de schema do [Targeting].
     *
     * ### 3. Como funciona
     * Calculada na inicialização via [SemVer.parse], garantindo que o schema é semanticamente válido.
     */
    val parsedSchemaVersion: SemVer = requireNotNull(SemVer.parse(schemaVersion)) { "schema invalido" }
}

/**
 * Registro de uma violação ou inconformidade em um cabeçalho HTTP durante a negociação.
 *
 * ### 1. O que faz
 * Armazena o nome do cabeçalho rejeitado e a descrição do motivo da falha.
 *
 * ### 2. Para que serve
 * Fornece mensagens descritivas detalhadas no corpo do erro HTTP 400 Bad Request.
 *
 * ### 3. Como funciona
 * Instanciado pelo validador de negociação quando um cabeçalho obrigatório falta ou falha nas regras de formato.
 *
 * @property header Nome do cabeçalho HTTP rejeitado.
 * @property reason Descrição pedagógica do motivo da rejeição.
 */
data class ContextViolation(
    /**
     * Nome do cabeçalho HTTP avaliado.
     *
     * ### 1. O que faz
     * Identifica qual cabeçalho causou a falha de validação.
     *
     * ### 2. Para que serve
     * Facilita a localização do erro pelas equipes que integram o BFF.
     *
     * ### 3. Como funciona
     * String literal com o nome do header HTTP (ex.: "Client-Platform").
     */
    val header: String,

    /**
     * Motivo da rejeição do cabeçalho.
     *
     * ### 1. O que faz
     * Explica detalhadamente por que o valor foi considerado inválido.
     *
     * ### 2. Para que serve
     * Orientação corretiva imediata para desenvolvedores dos aplicativos clientes.
     *
     * ### 3. Como funciona
     * Mensagem descritiva gerada pelo passo de validação.
     */
    val reason: String,
)

/**
 * Resultado discriminado da etapa de negociação de contexto HTTP.
 *
 * ### 1. O que faz
 * Modela a saída do passo `Negotiate` como um tipo soma (`sealed interface`) com duas ramificações exclusivas:
 * sucesso ([Valid]) ou falha ([Invalid]).
 *
 * ### 2. Para que serve
 * Impede por design que o pipeline execute com parâmetros parciais ou inválidos, exigindo desestruturação
 * e tratamento estrito no ponto de chamada.
 *
 * ### 3. Como funciona
 * Se todas as regras de cabeçalho forem satisfeitas, emite [Valid] encapsulando [ClientContext]. Caso contrário,
 * emite [Invalid] agrupando todas as violações detectadas.
 */
sealed interface ContextValidation {
    /**
     * Desfecho bem-sucedido da negociação de contexto.
     *
     * ### 1. O que faz
     * Transporta o [ClientContext] validado e pronto para consumo.
     *
     * ### 2. Para que serve
     * Autoriza a progressão para a fase de seleção de especificações (`Select`).
     *
     * ### 3. Como funciona
     * Contém a propriedade imutável [context].
     *
     * @property context Contexto validado do cliente requisitante.
     */
    data class Valid(
        /**
         * Contexto validado do cliente.
         *
         * ### 1. O que faz
         * Armazena os dados consolidados do cliente.
         *
         * ### 2. Para que serve
         * Alimenta todas as fases de composição.
         *
         * ### 3. Como funciona
         * Instância de [ClientContext].
         */
        val context: ClientContext,
    ) : ContextValidation

    /**
     * Desfecho de falha na negociação de contexto.
     *
     * ### 1. O que faz
     * Transporta a lista de inconformidades encontradas nos cabeçalhos da requisição.
     *
     * ### 2. Para que serve
     * Interrompe o pipeline e orienta a geração da resposta de erro HTTP 400 Bad Request.
     *
     * ### 3. Como funciona
     * Agrupa uma lista não vazia de instâncias de [ContextViolation].
     *
     * @property violations Lista de violações detectadas durante a validação.
     */
    data class Invalid(
        /**
         * Coleção de violações encontradas nos cabeçalhos HTTP.
         *
         * ### 1. O que faz
         * Armazena cada violação de cabeçalho detectada.
         *
         * ### 2. Para que serve
         * Fornece o detalhamento de erro da resposta HTTP 400.
         *
         * ### 3. Como funciona
         * Lista imutável de [ContextViolation].
         */
        val violations: List<ContextViolation>,
    ) : ContextValidation
}

/**
 * Coleção de cabeçalhos brutos recebidos na borda HTTP antes da validação.
 *
 * ### 1. O que faz
 * Encapsula os valores de cabeçalhos exatamente como foram recebidos na requisição HTTP.
 *
 * ### 2. Para que serve
 * Desacopla o domínio puro do SDUI de bibliotecas HTTP ou classes de servlet do Spring MVC, viabilizando
 * testes unitários rápidos e determinísticos da lógica de negociação.
 *
 * ### 3. Como funciona
 * Todas as propriedades são nuláveis por concepção: a ausência de um cabeçalho é um caso de validação de negócio,
 * não uma falha estrutural de binding do framework.
 *
 * @property uiSchemaVersion Valor bruto do cabeçalho `UI-Schema-Version`.
 * @property clientPlatform Valor bruto do cabeçalho `Client-Platform`.
 * @property clientVersion Valor bruto do cabeçalho `Client-Version`.
 * @property clientBuild Valor bruto do cabeçalho `Client-Build`.
 * @property acceptLanguage Valor bruto do cabeçalho `Accept-Language`.
 * @property apiVersion Valor bruto do cabeçalho `API-Version`.
 * @property osVersion Valor bruto do cabeçalho `Client-OS-Version`.
 * @property componentCapabilities Valor bruto do cabeçalho `Component-Capabilities`.
 * @property channel Valor bruto do cabeçalho `Channel` (opcional).
 */
data class NegotiateHeaders(
    /**
     * Cabeçalho UI-Schema-Version recebido.
     *
     * ### 1. O que faz
     * Armazena o valor bruto da versão de schema solicitada.
     *
     * ### 2. Para que serve
     * Validação do Eixo A de compatibilidade.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val uiSchemaVersion: String?,

    /**
     * Cabeçalho Client-Platform recebido.
     *
     * ### 1. O que faz
     * Armazena o identificador da plataforma ("ios" ou "android").
     *
     * ### 2. Para que serve
     * Direcionamento da seleção para a plataforma correta.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val clientPlatform: String?,

    /**
     * Cabeçalho Client-Version recebido.
     *
     * ### 1. O que faz
     * Armazena a versão semântica de lançamento do app cliente.
     *
     * ### 2. Para que serve
     * Validação do Eixo C de compatibilidade.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val clientVersion: String?,

    /**
     * Cabeçalho Client-Build recebido.
     *
     * ### 1. O que faz
     * Armazena o número sequencial de build do aplicativo.
     *
     * ### 2. Para que serve
     * Auditoria e identificação de build no hot path.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val clientBuild: String?,

    /**
     * Cabeçalho Accept-Language recebido.
     *
     * ### 1. O que faz
     * Armazena a preferência de idioma informada pelo cliente.
     *
     * ### 2. Para que serve
     * Determinação do locale de composição da resposta.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val acceptLanguage: String?,

    /**
     * Cabeçalho API-Version recebido.
     *
     * ### 1. O que faz
     * Armazena a versão de API REST informada.
     *
     * ### 2. Para que serve
     * Validação de versão da API HTTP exposta.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val apiVersion: String?,

    /**
     * Cabeçalho Client-OS-Version recebido.
     *
     * ### 1. O que faz
     * Armazena a versão de sistema operacional informada pelo app.
     *
     * ### 2. Para que serve
     * Verificação de restrições de SO em regras de targeting.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val osVersion: String?,

    /**
     * Cabeçalho Component-Capabilities recebido.
     *
     * ### 1. O que faz
     * Armazena a lista de capacidades em texto separada por vírgulas.
     *
     * ### 2. Para que serve
     * Validação do Eixo B de capacidades de renderização.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP.
     */
    val componentCapabilities: String?,

    /**
     * Cabeçalho Channel recebido (opcional).
     *
     * ### 1. O que faz
     * Armazena a indicação de canal (ex.: "canary", "stable").
     *
     * ### 2. Para que serve
     * Seleção de ponteiros específicos de pré-lançamento.
     *
     * ### 3. Como funciona
     * String opcional recebida na requisição HTTP com padrão `null`.
     */
    val channel: String? = null,
)
