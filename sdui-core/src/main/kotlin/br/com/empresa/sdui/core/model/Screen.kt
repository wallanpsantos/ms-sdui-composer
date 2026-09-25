package br.com.empresa.sdui.core.model

import java.time.Instant

/**
 * Árvore de UI completa gerada pelo pipeline de composição no modelo de domínio puro.
 *
 * ### 1. O que faz
 * Encapsula o resultado integral da composição de uma tela: metadados de governança, skeleton estrutural,
 * seções ativas devidamente ordenadas e hidratadas, lista de omissões graciosas e contexto auditável do solicitante.
 *
 * ### 2. Para que serve
 * É a unidade primária trafegada entre o orquestrador de composição (`ComposeScreenService`), os mecanismos
 * de cache de árvore (`treeCache`), o armazenamento de contingência last good e a camada de apresentação que
 * serializa o envelope HTTP de resposta. Não contém estado ou PII de usuário, permitindo compartilhamento seguro
 * entre clientes com o mesmo perfil de capabilities e revisão de spec.
 *
 * ### 3. Como funciona
 * Construída sequencialmente pelas etapas do pipeline (`Negotiate` -> `Select` -> `Filter` -> `Hydrate` -> `Guard` -> `Compose`).
 * Transporta [pointerVersion], que registra a versão do ponteiro no momento da seleção, permitindo que a escrita
 * do last good recuse gravações obsoletas iniciadas antes de um rollback ou nova publicação (ADR-021).
 *
 * @property surface Identificador da superfície composta (ex.: "home", "catalog").
 * @property platform Plataforma do cliente móvel ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
 * @property schemaVersion Versão do schema de envelope SDUI adotada.
 * @property specRevisionId Identificador único da revisão de especificação que originou a composição.
 * @property skeletonId Identificador do skeleton de layout aplicado.
 * @property skeletonHash Checksum SHA-256 de integridade da especificação de origem.
 * @property etag Cabeçalho ETag fraco gerado para suporte a cache HTTP condicional.
 * @property generatedAt Instante de conclusão da geração da árvore de tela.
 * @property locale Identificador de localização e idioma do conteúdo (ex.: "pt-BR").
 * @property channel Canal de publicação onde a composição ocorreu ([Channel.STABLE] ou [Channel.CANARY]).
 * @property fallback Indicador booleano de resposta degradada via contingência da escada de fallback.
 * @property fallbackReason Motivo formal da degradação de conteúdo ([FallbackReason]).
 * @property omitted Coleção de seções que foram descartadas do resultado final e seus respectivos motivos.
 * @property client Contexto auditável consolidado do aplicativo cliente solicitante.
 * @property targeting Critérios de targeting da especificação selecionada para a tela.
 * @property experience Identificador da experiência ou variante de experimento A/B ativa.
 * @property skeleton Estrutura de slots e regras de layout da superfície.
 * @property sections Lista imutável de seções válidas e ordenadas prontas para renderização.
 * @property pointerVersion Versão sequencial do ponteiro utilizada para proteção contra concorrência defasada.
 */
data class ComposedScreen(
    /**
     * Nome da superfície atendida.
     *
     * ### 1. O que faz
     * Identifica a superfície à qual a tela pertence (ex.: "home").
     *
     * ### 2. Para que serve
     * Roteamento de caches e segregação de métricas.
     *
     * ### 3. Como funciona
     * String literal validada contra a allowlist de superfícies.
     */
    val surface: String,

    /**
     * Plataforma móvel do cliente.
     *
     * ### 1. O que faz
     * Informa a plataforma de destino da tela composta.
     *
     * ### 2. Para que serve
     * Garante isolamento estrito de caches e seletores.
     *
     * ### 3. Como funciona
     * Enum do tipo [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Versão do schema do protocolo de envelope.
     *
     * ### 1. O que faz
     * Armazena a versão de contrato do envelope gerado.
     *
     * ### 2. Para que serve
     * Alinha a estrutura JSON com as expectativas do cliente móvel.
     *
     * ### 3. Como funciona
     * String de versão (ex.: "3").
     */
    val schemaVersion: String,

    /**
     * Identificador da revisão da especificação utilizada.
     *
     * ### 1. O que faz
     * Informa o ID imutável da spec de conteúdo ativa.
     *
     * ### 2. Para que serve
     * Rastreabilidade, chaveamento de cache e auditoria de produção.
     *
     * ### 3. Como funciona
     * String padronizada validada por `RevisionIds`.
     */
    val specRevisionId: String,

    /**
     * Identificador do skeleton de layout.
     *
     * ### 1. O que faz
     * Identifica a grade estrutural de slots aplicada.
     *
     * ### 2. Para que serve
     * Informa a disposição visual macro da tela.
     *
     * ### 3. Como funciona
     * String identificadora (ex.: "home.default").
     */
    val skeletonId: String,

    /**
     * Hash SHA-256 de integridade da especificação.
     *
     * ### 1. O que faz
     * Transporta o checksum criptográfico do conteúdo da spec.
     *
     * ### 2. Para que serve
     * Garante a integridade e detecta alterações não autorizadas.
     *
     * ### 3. Como funciona
     * String no formato "sha256:<hex>".
     */
    val skeletonHash: String,

    /**
     * Tag de entidade HTTP ETag fraca.
     *
     * ### 1. O que faz
     * Fornece o valor de revalidação condicional de cache HTTP.
     *
     * ### 2. Para que serve
     * Permite ao cliente receber HTTP 304 Not Modified quando o conteúdo for idêntico.
     *
     * ### 3. Como funciona
     * Gerado através de [ETagFactory.of].
     */
    val etag: String,

    /**
     * Instante temporal de geração da composição.
     *
     * ### 1. O que faz
     * Registra o momento exato em que a tela foi processada.
     *
     * ### 2. Para que serve
     * Telemetria de frescor de cache e auditoria de requisições.
     *
     * ### 3. Como funciona
     * Instância de [Instant] obtida a partir do relógio do sistema.
     */
    val generatedAt: Instant,

    /**
     * Idioma e localização aplicados à resposta.
     *
     * ### 1. O que faz
     * Informa o locale adotado na composição.
     *
     * ### 2. Para que serve
     * Internacionalização e auditoria de preferências regionais.
     *
     * ### 3. Como funciona
     * String padronizada (ex.: "pt-BR").
     */
    val locale: String,

    /**
     * Canal de distribuição da tela.
     *
     * ### 1. O que faz
     * Informa se a composição pertence ao canal estável ou canário.
     *
     * ### 2. Para que serve
     * Segregação de métricas e validação de rollout gradual.
     *
     * ### 3. Como funciona
     * Enum do tipo [Channel].
     */
    val channel: Channel,

    /**
     * Indicador de acionamento da escada de fallback.
     *
     * ### 1. O que faz
     * Flag booleana que sinaliza resposta sob contingência.
     *
     * ### 2. Para que serve
     * Alerta o cliente móvel e a observabilidade sobre degradação de serviço.
     *
     * ### 3. Como funciona
     * `true` se a resposta veio de contingência, `false` se operação normal.
     */
    val fallback: Boolean,

    /**
     * Motivo formal da contingência de fallback.
     *
     * ### 1. O que faz
     * Informa a causa da resposta degradada.
     *
     * ### 2. Para que serve
     * Diagnóstico imediato da causa raiz da contingência.
     *
     * ### 3. Como funciona
     * Enum do tipo [FallbackReason].
     */
    val fallbackReason: FallbackReason,

    /**
     * Lista de seções descartadas pelo pipeline.
     *
     * ### 1. O que faz
     * Agrupa as seções que deixaram de ser incluídas na resposta.
     *
     * ### 2. Para que serve
     * Transparência sobre omissões por incompatibilidade de tipo ou falhas de hidratação.
     *
     * ### 3. Como funciona
     * Lista imutável de [OmittedSection].
     */
    val omitted: List<OmittedSection>,

    /**
     * Contexto consolidado do cliente solicitante.
     *
     * ### 1. O que faz
     * Armazena os dados validados do aplicativo cliente.
     *
     * ### 2. Para que serve
     * Rastreabilidade imediata no corpo da resposta sem depender de logs de borda.
     *
     * ### 3. Como funciona
     * Instância de [ClientContext].
     */
    val client: ClientContext,

    /**
     * Regras de targeting da especificação selecionada.
     *
     * ### 1. O que faz
     * Informa os limites de compatibilidade que tornaram esta tela elegível.
     *
     * ### 2. Para que serve
     * Auditoria de segmentação de faixas de app e sistema operacional.
     *
     * ### 3. Como funciona
     * Instância de `Targeting`.
     */
    val targeting: Targeting,

    /**
     * Rótulo da experiência ou variante de experimento A/B.
     *
     * ### 1. O que faz
     * Identifica a variante de apresentação servida (ex.: "default", "variant_a").
     *
     * ### 2. Para que serve
     * Correlação de métricas em testes A/B de interface.
     *
     * ### 3. Como funciona
     * String descritiva da variante.
     */
    val experience: String,

    /**
     * Definição estrutural do skeleton da tela.
     *
     * ### 1. O que faz
     * Transporta o layout de slots que organiza a superfície.
     *
     * ### 2. Para que serve
     * Instruir o cliente nativo sobre o arcabouço estrutural da página.
     *
     * ### 3. Como funciona
     * Instância de `Skeleton`.
     */
    val skeleton: Skeleton,

    /**
     * Coleção ordenada de seções prontas para exibição.
     *
     * ### 1. O que faz
     * Agrupa todas as seções válidas e hidratadas da tela.
     *
     * ### 2. Para que serve
     * Alimenta a renderização de componentes no aplicativo móvel.
     *
     * ### 3. Como funciona
     * Lista imutável de `Section` ordenada de acordo com a sequência de slots do skeleton.
     */
    val sections: List<Section>,

    /**
     * Versão do ponteiro no momento da seleção.
     *
     * ### 1. O que faz
     * Registra o número sequencial de versão do ponteiro que gerou a tela.
     *
     * ### 2. Para que serve
     * Protege o last good contra escritas concorrentes tardias de composições defasadas.
     *
     * ### 3. Como funciona
     * Long sequencial monotonicamente crescente.
     */
    val pointerVersion: Long = 0,
)

/**
 * Construtor e padronizador determinístico de chaves e prefixos para cache Redis e em memória.
 *
 * ### 1. O que faz
 * Centraliza a convenção de nomenclatura de todas as chaves utilizadas para armazenar especificações,
 * árvores compostas, seções de projeção, registros de contingência last good e bloqueios de singleflight.
 *
 * ### 2. Para que serve
 * Impede discrepâncias de formatação entre leitores e escritores de cache, garante o isolamento rigoroso
 * por superfície e plataforma, e oferece a barreira de segurança [containsUserId] para impedir o armazenamento
 * indevido de dados de usuário no cache compartilhado.
 *
 * ### 3. Como funciona
 * Métodos estáticos puros que geram strings formatadas com prefixos padronizados (`sdui:...`). A chave de árvore
 * utiliza a revisão da spec ([tree]) e o hash de capacidades, garantindo que o cache seja consistente com a seleção
 * sem explodir a cardinalidade por combinações infinitas de parâmetros de clientes.
 */
object RedisKeys {
    /**
     * Monta a chave de cache para uma especificação versionada.
     *
     * ### 1. O que faz
     * Constrói a chave canônica para armazenamento de uma [Spec].
     *
     * ### 2. Para que serve
     * Recuperação rápida de especificações ativas sem consulta ao banco de dados.
     *
     * ### 3. Como funciona
     * Formata `sdui:spec:$specRevisionId:${platform.wire()}`.
     *
     * @param specRevisionId Identificador único da revisão da especificação.
     * @param platform Plataforma móvel de destino.
     * @return String representativa da chave no Redis.
     */
    fun spec(specRevisionId: String, platform: ClientPlatform): String =
        "sdui:spec:$specRevisionId:${platform.wire()}"

    /**
     * Monta a chave de cache completa para uma árvore composta.
     *
     * ### 1. O que faz
     * Constrói a chave de cache que referencia uma [ComposedScreen] pronta.
     *
     * ### 2. Para que serve
     * Permite reutilizar árvores inteiras já hidratadas para clientes na mesma revisão, schema, capabilities e canal.
     *
     * ### 3. Como funciona
     * Combina [treePrefix] com a versão de schema, revisão de spec, hash de capabilities e canal de entrega.
     *
     * @param surface Nome da superfície.
     * @param platform Plataforma móvel do cliente.
     * @param schema Versão do schema de envelope.
     * @param specRevisionId Identificador da revisão da especificação utilizada.
     * @param capsHash Hash determinístico das capacidades de renderização do cliente.
     * @param channel Canal de distribuição atendido.
     * @return Chave de cache completa para a árvore.
     */
    fun tree(
        surface: String,
        platform: ClientPlatform,
        schema: String,
        specRevisionId: String,
        capsHash: String,
        channel: Channel,
    ): String = "${treePrefix(surface, platform)}$schema:$specRevisionId:$capsHash:${channel.wire()}"

    /**
     * Constrói o prefixo compartilhado para as chaves de árvore de uma superfície e plataforma.
     *
     * ### 1. O que faz
     * Retorna o início comum de todas as chaves de árvore de determinada superfície e plataforma móvel.
     *
     * ### 2. Para que serve
     * Facilita varreduras e operações de invalidação em lote sem risco de colisão de nomes.
     *
     * ### 3. Como funciona
     * Formata `sdui:tree:$surface:${platform.wire()}:`.
     *
     * @param surface Nome da superfície.
     * @param platform Plataforma do cliente.
     * @return Prefixo padronizado de chaves de árvore.
     */
    fun treePrefix(surface: String, platform: ClientPlatform): String = "sdui:tree:$surface:${platform.wire()}:"

    /**
     * Monta a chave de cache para uma projeção de dados de seção individual.
     *
     * ### 1. O que faz
     * Constrói a chave para armazenar o resultado da hidratação de uma seção dinâmica.
     *
     * ### 2. Para que serve
     * Cache granular de microserviços e adaptadores de dados.
     *
     * ### 3. Como funciona
     * Formata `sdui:section:$projection:$id`.
     *
     * @param projection Nome do tipo de projeção de dados.
     * @param id Identificador da seção de dados.
     * @return Chave de cache para a projeção de seção.
     */
    fun section(projection: String, id: String): String = "sdui:section:$projection:$id"

    /**
     * Monta a chave de cache para a árvore de contingência last good.
     *
     * ### 1. O que faz
     * Gera a chave para recuperar a última composição saudável da superfície.
     *
     * ### 2. Para que serve
     * Suporte à resiliência operacional no penúltimo nível da escada de fallback (ADR-007).
     *
     * ### 3. Como funciona
     * Formata `sdui:lastgood:$surface:${platform.wire()}:${channel.wire()}`.
     *
     * @param surface Nome da superfície.
     * @param platform Plataforma do aplicativo.
     * @param channel Canal de distribuição.
     * @return Chave de cache do last good.
     */
    fun lastGood(surface: String, platform: ClientPlatform, channel: Channel): String =
        "sdui:lastgood:$surface:${platform.wire()}:${channel.wire()}"

    /**
     * Monta o identificador do bloqueio de concorrência singleflight.
     *
     * ### 1. O que faz
     * Gera a chave de controle de execução única para requisições paralelas idênticas.
     *
     * ### 2. Para que serve
     * Impede sobrecarga do backend agrupando múltiplos chamadores sob uma única tarefa líder de composição.
     *
     * ### 3. Como funciona
     * Adiciona o prefixo `sdui:sf:` à chave da árvore de destino.
     *
     * @param treeKey Chave completa da árvore de UI.
     * @return Chave de controle de concorrência do singleflight.
     */
    fun singleflight(treeKey: String): String = "sdui:sf:$treeKey"

    /**
     * Verifica se uma chave contém referências indevidas a identificadores de usuário.
     *
     * ### 1. O que faz
     * Inspeciona uma string de chave em busca de termos indicativos de usuário ("userId").
     *
     * ### 2. Para que serve
     * Validação de conformidade que protege a arquitetura stateless contra vazamento ou segregação imprópria de dados pessoais no cache compartilhado.
     *
     * ### 3. Como funciona
     * Executa checagem case-insensitive por substring `userId`.
     *
     * @param key String de chave a ser inspecionada.
     * @return `true` se contiver termos de usuário (inválida para cache), `false` se segura.
     */
    fun containsUserId(key: String): Boolean = key.contains("userId", ignoreCase = true)
}

/**
 * Fábrica de identificadores de validação de cache HTTP ETag fraco.
 *
 * ### 1. O que faz
 * Gera a cadeia de caracteres do cabeçalho `ETag` (`W/"..."`) para árvores compostas de Server-Driven UI.
 *
 * ### 2. Para que serve
 * Permite aos clientes móveis e caches de borda realizar revalidação condicional eficiente via cabeçalho
 * `If-None-Match`, devolvendo respostas imediatas HTTP 304 Not Modified sem reprocessar ou reserializar o payload JSON.
 *
 * ### 3. Como funciona
 * Combina de forma determinística a revisão de especificação, plataforma, versão de schema e um prefixo truncado
 * do hash de capabilities ([CAPS_PREFIX_LENGTH]). A inclusão das capabilities garante que clientes com capacidades
 * visuais distintas não compartilhem indevidamente a mesma representação de ETag.
 */
object ETagFactory {
    /**
     * Comprimento máximo do fragmento do hash de capabilities incorporado ao ETag.
     *
     * ### 1. O que faz
     * Define a quantidade de caracteres do hash de capabilities preservada no ETag (12).
     *
     * ### 2. Para que serve
     * Mantém o cabeçalho HTTP conciso e compacto sem comprometer a entropia necessária para discriminar conjuntos de capacidades.
     *
     * ### 3. Como funciona
     * Constante numérica utilizada no truncamento da string via `take`.
     */
    private const val CAPS_PREFIX_LENGTH: Int = 12

    /**
     * Constrói o cabeçalho ETag fraco representativo de uma árvore de UI.
     *
     * ### 1. O que faz
     * Formata os metadados determinísticos de composição no padrão ETag fraco `W/"<revisão>-<plataforma>-<schema>-<caps>"`.
     *
     * ### 2. Para que serve
     * Emitido no envelope de resposta para habilitar negociação de cache condicional no protocolo HTTP.
     *
     * ### 3. Como funciona
     * Extrai os 12 primeiros caracteres de [capsHash] e interpola com os identificadores informados entre aspas duplas precedidas por `W/`.
     *
     * @param specRevisionId Identificador da revisão de especificação.
     * @param platform Plataforma do cliente móvel.
     * @param schemaVersion Versão de schema SDUI negociada.
     * @param capsHash Hash determinístico de capacidades do cliente.
     * @return String formatada como ETag fraco (ex.: `W/"spec-123-ios-3-a1b2c3d4e5f6"`).
     */
    fun of(
        specRevisionId: String,
        platform: ClientPlatform,
        schemaVersion: String,
        capsHash: String,
    ): String {
        val caps = capsHash.take(CAPS_PREFIX_LENGTH)
        return "W/\"$specRevisionId-${platform.wire()}-$schemaVersion-$caps\""
    }
}
