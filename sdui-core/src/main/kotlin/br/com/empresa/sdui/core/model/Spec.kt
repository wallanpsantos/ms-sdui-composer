package br.com.empresa.sdui.core.model

import java.time.Instant

/**
 * Critérios multidimensionais de elegibilidade e targeting de uma especificação de tela.
 *
 * ### 1. O que faz
 * Modela as regras que determinam se uma especificação (`Spec`) é compatível com o perfil técnico do cliente
 * que solicitou a interface.
 *
 * ### 2. Para que serve
 * Permite segmentar a entrega de telas pelos três eixos de compatibilidade arquitetural: Eixo A (faixa de schema SDUI
 * [schemaVersion]), Eixo B (capacidades de componentes obrigatórias [requiredCapabilities]) e Eixo C (plataforma [platform],
 * faixa de versões de aplicativo [appVersion] e sistema operacional [osVersion]).
 *
 * ### 3. Como funciona
 * Avalia no método [matches] se o [ClientContext] e as capacidades efetivas atendem a todos os requisitos declarados.
 * Se qualquer critério falhar (como app defasado ou falta de um componente obrigatório), a especificação é descartada
 * por completo durante a fase de seleção (`Select`), permitindo a escolha de uma especificação alternativa compatível.
 *
 * @property platform Plataforma móvel de destino ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
 * @property appVersion Faixa de versões de aplicativo suportadas ([VersionRange]).
 * @property osVersion Faixa opcional de versões de sistema operacional suportadas.
 * @property schemaVersion Faixa de versões do protocolo de schema SDUI aceitas.
 * @property requiredCapabilities Lista de capacidades de componentes que o aplicativo deve obrigatoriamente suportar.
 * @property priority Prioridade numérica para desempate entre múltiplas especificações elegíveis (maior vence).
 * @property band Rótulo semântico da faixa de targeting (ex.: "current", "legacy", "beta").
 */
data class Targeting(
    /**
     * Plataforma móvel exigida.
     *
     * ### 1. O que faz
     * Especifica o sistema operacional móvel alvo.
     *
     * ### 2. Para que serve
     * Garante que uma spec desenhada para iOS nunca seja selecionada por um app Android e vice-versa.
     *
     * ### 3. Como funciona
     * Comparado por igualdade estrita contra `ClientContext.platform`.
     */
    val platform: ClientPlatform,

    /**
     * Faixa de versões do aplicativo cliente.
     *
     * ### 1. O que faz
     * Define o intervalo `[min, max]` de versões semânticas de app que podem receber esta spec.
     *
     * ### 2. Para que serve
     * Permite direcionar telas novas exclusivamente para versões atualizadas de aplicativo.
     *
     * ### 3. Como funciona
     * Avaliado via `VersionRange.contains(context.appVersion)`.
     */
    val appVersion: VersionRange,

    /**
     * Faixa de versões de sistema operacional.
     *
     * ### 1. O que faz
     * Define restrições opcionais de versão de SO móvel (ex.: iOS 17+).
     *
     * ### 2. Para que serve
     * Suporte a componentes que dependem de APIs nativas recentes do SO.
     *
     * ### 3. Como funciona
     * Se definido e o contexto trouxer a versão de SO, avalia se está contida na faixa.
     */
    val osVersion: VersionRange?,

    /**
     * Faixa de versões de schema SDUI aceitas.
     *
     * ### 1. O que faz
     * Estabelece as versões do envelope de protocolo suportadas pela spec.
     *
     * ### 2. Para que serve
     * Assegura compatibilidade de protocolo (Eixo A).
     *
     * ### 3. Como funciona
     * Avalia se a versão negociada do cliente pertence ao intervalo de schema.
     */
    val schemaVersion: VersionRange,

    /**
     * Capacidades de componentes estritamente mandatórias para a spec.
     *
     * ### 1. O que faz
     * Lista os componentes que o aplicativo deve obrigatoriamente renderizar para receber a tela.
     *
     * ### 2. Para que serve
     * Descarta a spec inteira caso o cliente não suporte um componente essencial.
     *
     * ### 3. Como funciona
     * Exige que todas as capacidades da lista estejam contidas no conjunto de capabilities efetivas.
     */
    val requiredCapabilities: List<Capability>,

    /**
     * Peso de precedência da regra de targeting.
     *
     * ### 1. O que faz
     * Define a ordem de preferência quando mais de uma especificação atende ao mesmo cliente.
     *
     * ### 2. Para que serve
     * Desempate determinístico em coortes sobrepostas.
     *
     * ### 3. Como funciona
     * Valor inteiro ordenado em ordem decrescente (maior número indica maior prioridade).
     */
    val priority: Int,

    /**
     * Identificador descritivo da coorte de targeting.
     *
     * ### 1. O que faz
     * Rotula a faixa de targeting (ex.: "current", "all_versions").
     *
     * ### 2. Para que serve
     * Facilita a auditoria e análise de segmentação no catálogo de governança.
     *
     * ### 3. Como funciona
     * String textual puramente descritiva.
     */
    val band: String,
) {
    /**
     * Avalia se o contexto de um cliente satisfaz todos os critérios desta regra de targeting.
     *
     * ### 1. O que faz
     * Testa de forma combinada plataforma, versão de schema, versão de app, versão de SO e capabilities obrigatórias.
     *
     * ### 2. Para que serve
     * Utilizado na fase de seleção (`Select`) para filtrar especificações elegíveis ao dispositivo solicitante.
     *
     * ### 3. Como funciona
     * Executa checagens em curto-circuito: retorna `false` na primeira discordância de critérios.
     *
     * @param context Contexto auditável consolidado do cliente requisitante.
     * @param effectiveCaps Conjunto efetivo de capacidades homologadas suportadas pelo cliente.
     * @return `true` se todos os critérios forem satisfeitos, `false` caso contrário.
     */
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean {
        if (platform != context.platform) return false
        if (!schemaVersion.contains(context.parsedSchemaVersion)) return false
        if (!appVersion.contains(context.appVersion)) return false
        val os = osVersion
        val clientOs = context.osVersion
        if (os != null && clientOs != null && !os.contains(clientOs)) return false
        return requiredCapabilities.all { it in effectiveCaps }
    }

    /**
     * Retorna a versão mínima do app em formato textual canônico.
     *
     * ### 1. O que faz
     * Converte o limite inferior da faixa de aplicativo em string "X.Y.Z".
     *
     * ### 2. Para que serve
     * Serialização e visualização administrativa de regras de targeting.
     *
     * ### 3. Como funciona
     * Invoca `appVersion.min.toString()`.
     *
     * @return String representativa da versão mínima.
     */
    fun wireMin(): String = appVersion.min.toString()

    /**
     * Retorna a versão máxima do app em formato textual canônico, se existente.
     *
     * ### 1. O que faz
     * Converte o teto da faixa de aplicativo em string, ou retorna `null` para faixas abertas.
     *
     * ### 2. Para que serve
     * Visualização em interfaces administrativas e relatórios de auditoria.
     *
     * ### 3. Como funciona
     * Invoca `appVersion.max?.toString()`.
     *
     * @return String da versão máxima ou `null`.
     */
    fun wireMax(): String? = appVersion.max?.toString()

    /**
     * Retorna a versão mínima de sistema operacional em formato amigável para SO móvel.
     *
     * ### 1. O que faz
     * Converte o limite inferior da faixa de SO móvel suprimindo o patch se este for zero.
     *
     * ### 2. Para que serve
     * Exibição padronizada da versão mínima de SO compatível.
     *
     * ### 3. Como funciona
     * Invoca `osVersion?.min?.toOsString()`.
     *
     * @return Versão mínima de SO formatada ou `null`.
     */
    fun wireOsMin(): String? = osVersion?.min?.toOsString()
}

/**
 * Especificação declarativa completa e versionada de uma superfície Server-Driven UI.
 *
 * ### 1. O que faz
 * Representa a autoridade de conteúdo de uma tela: reúne a lista de seções modelo que a compõem, as regras de
 * targeting que decidem a quem ela se destina, a referência ao skeleton estrutural e metadados de autoria e integridade.
 *
 * ### 2. Para que serve
 * É a entidade central da governança e do hot path. Quando atinge o estado [SpecStatus.PUBLISHED], torna-se
 * estritamente imutável, servindo como fonte de verdade para cache de especificações, tags de métricas,
 * cálculo de ETags e trilhas de auditoria.
 *
 * ### 3. Como funciona
 * Identificada por [specId] e seu número sequencial de [revision], combinados no identificador canônico [specRevisionId]
 * (`<specId>:<revision>`). Transporta o [checksum] criptográfico de seu conteúdo e só é selecionada para composição
 * caso esteja publicada e satisfaça as regras de [targeting] via [matches].
 *
 * @property specId Identificador conceitual da especificação (ex.: "home_spec").
 * @property revision Número sequencial da revisão desta especificação.
 * @property specRevisionId Identificador composto estável da revisão (ex.: "home_spec:1").
 * @property parentRevision Número da revisão precedente que originou esta versão, ou `null` se inicial.
 * @property status Estado no ciclo de vida de governança ([SpecStatus]).
 * @property surface Nome da superfície atendida (ex.: "home").
 * @property platform Plataforma móvel vinculada ([ClientPlatform]).
 * @property channel Canal de distribuição associado ([Channel]).
 * @property skeletonId Identificador do skeleton de slots utilizado pela especificação.
 * @property skeletonRevision Versão da revisão do skeleton associado.
 * @property targeting Critérios de elegibilidade e targeting aplicáveis a esta especificação.
 * @property sections Lista imutável de seções modelo que estruturam a tela.
 * @property checksum Hash SHA-256 de integridade criptográfica do conteúdo da especificação.
 * @property publishedAt Instante temporal em que a publicação foi efetivada, ou `null` se rascunho.
 * @property publishedBy Identificador do revisor (checker) que autorizou a publicação.
 * @property madeBy Identificador do proponente (maker) que concebeu a especificação.
 * @property experience Identificador de variante de experiência ou experimento A/B.
 */
data class Spec(
    /**
     * Identificador conceitual da especificação.
     *
     * ### 1. O que faz
     * Nomeia a especificação agrupando suas diversas revisões ao longo do tempo.
     *
     * ### 2. Para que serve
     * Consulta e agrupamento histórico de versões no banco de governança.
     *
     * ### 3. Como funciona
     * String textual identificadora.
     */
    val specId: String,

    /**
     * Número sequencial da revisão.
     *
     * ### 1. O que faz
     * Registra o incremento ordinal de versão da especificação.
     *
     * ### 2. Para que serve
     * Ordenação cronológica e controle de evolução de conteúdo.
     *
     * ### 3. Como funciona
     * Inteiro positivo monotonicamente crescente.
     */
    val revision: Int,

    /**
     * Identificador canônico composto da revisão da especificação.
     *
     * ### 1. O que faz
     * Combina o ID da spec e a revisão no formato estável `specId:revision`.
     *
     * ### 2. Para que serve
     * Referência estável utilizada em chaves de cache, logs, ponteiros e envelopes de resposta.
     *
     * ### 3. Como funciona
     * String validada contra regras de formato e segurança por `RevisionIds`.
     */
    val specRevisionId: String,

    /**
     * Revisão de origem anterior.
     *
     * ### 1. O que faz
     * Rastreia qual revisão serviu de base para a criação desta nova versão.
     *
     * ### 2. Para que serve
     * Construção de diffs estruturais e auditoria de mudanças.
     *
     * ### 3. Como funciona
     * Inteiro opcional indicando a versão pai.
     */
    val parentRevision: Int?,

    /**
     * Situação no ciclo de vida de governança.
     *
     * ### 1. O que faz
     * Registra se a especificação é um rascunho, publicada ou rejeitada.
     *
     * ### 2. Para que serve
     * Garante que apenas versões [SpecStatus.PUBLISHED] sejam selecionadas pelo pipeline.
     *
     * ### 3. Como funciona
     * Enum do tipo [SpecStatus].
     */
    val status: SpecStatus,

    /**
     * Superfície de UI associada.
     *
     * ### 1. O que faz
     * Vincula a especificação à sua superfície correspondente (ex.: "home").
     *
     * ### 2. Para que serve
     * Validação de permissões e roteamento no motor de seleção.
     *
     * ### 3. Como funciona
     * String validada contra a allowlist de superfícies.
     */
    val surface: String,

    /**
     * Plataforma móvel vinculada à especificação.
     *
     * ### 1. O que faz
     * Informa se a especificação atende a clientes iOS ou Android.
     *
     * ### 2. Para que serve
     * Garante o isolamento estrito de especificações entre sistemas operacionais móveis.
     *
     * ### 3. Como funciona
     * Enum do tipo [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Canal de distribuição da especificação.
     *
     * ### 1. O que faz
     * Indica o ambiente de destino (estável, canário ou interno).
     *
     * ### 2. Para que serve
     * Permite a publicação segregada em coortes de teste sem impactar a produção.
     *
     * ### 3. Como funciona
     * Enum do tipo [Channel].
     */
    val channel: Channel,

    /**
     * Identificador do skeleton de layout associado.
     *
     * ### 1. O que faz
     * Aponta para o gabarito estrutural de slots que organizará as seções.
     *
     * ### 2. Para que serve
     * Vinculação entre conteúdo e arquitetura de slots.
     *
     * ### 3. Como funciona
     * String identificadora de skeleton.
     */
    val skeletonId: String,

    /**
     * Versão da revisão do skeleton associado.
     *
     * ### 1. O que faz
     * Informa a revisão exata do skeleton utilizada.
     *
     * ### 2. Para que serve
     * Garante que mudanças futuras no skeleton não alterem silenciosamente o layout desta revisão de spec.
     *
     * ### 3. Como funciona
     * Inteiro ordinal de revisão.
     */
    val skeletonRevision: Int,

    /**
     * Regras de targeting e elegibilidade da especificação.
     *
     * ### 1. O que faz
     * Transporta os critérios que qualificam o cliente para receber esta especificação.
     *
     * ### 2. Para que serve
     * Seleção orientada por plataforma, faixas de versão e capabilities.
     *
     * ### 3. Como funciona
     * Instância de [Targeting].
     */
    val targeting: Targeting,

    /**
     * Lista de seções modelo que compõem a tela.
     *
     * ### 1. O que faz
     * Armazena todas as seções definidas para a superfície antes da filtragem e hidratação.
     *
     * ### 2. Para que serve
     * Matéria-prima do pipeline de composição.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de `Section`.
     */
    val sections: List<Section>,

    /**
     * Checksum SHA-256 do conteúdo da especificação.
     *
     * ### 1. O que faz
     * Armazena o hash criptográfico gerado a partir do payload de seções e targeting.
     *
     * ### 2. Para que serve
     * Garante a integridade física da especificação e previne adulterações ou corrupção de dados.
     *
     * ### 3. Como funciona
     * String no formato canônico "sha256:<hex>".
     */
    val checksum: String,

    /**
     * Data e hora da publicação oficial.
     *
     * ### 1. O que faz
     * Registra o momento exato em que a especificação foi aprovada e disponibilizada.
     *
     * ### 2. Para que serve
     * Auditoria temporal de lançamentos de tela.
     *
     * ### 3. Como funciona
     * Instância de [Instant] ou `null` se a spec ainda for rascunho.
     */
    val publishedAt: Instant?,

    /**
     * Identificador do operador que aprovou a publicação.
     *
     * ### 1. O que faz
     * Registra o ID do revisor (checker) que homologou a versão.
     *
     * ### 2. Para que serve
     * Conformidade com a segregação de funções do fluxo maker-checker.
     *
     * ### 3. Como funciona
     * String com o identificador do operador ou `null` se não publicada.
     */
    val publishedBy: String?,

    /**
     * Identificador do autor que concebeu a especificação.
     *
     * ### 1. O que faz
     * Registra o ID do criador da proposta (maker).
     *
     * ### 2. Para que serve
     * Rastreabilidade de autoria de propostas de interface.
     *
     * ### 3. Como funciona
     * String identificadora do operador.
     */
    val madeBy: String,

    /**
     * Rótulo descritivo da experiência ou variante de experimento.
     *
     * ### 1. O que faz
     * Identifica a variante de apresentação da tela (ex.: "default", "experiment_a").
     *
     * ### 2. Para que serve
     * Marcação analítica para telemetria e testes A/B.
     *
     * ### 3. Como funciona
     * String descritiva repassada ao envelope de resposta.
     */
    val experience: String,
) {
    /**
     * Avalia se a especificação está ativa e é elegível para o cliente solicitante.
     *
     * ### 1. O que faz
     * Verifica simultaneamente se a especificação possui status [SpecStatus.PUBLISHED] e atende às regras de targeting.
     *
     * ### 2. Para que serve
     * Utilizado pelo motor de seleção para determinar se a especificação pode ser entregue ao requisitante.
     *
     * ### 3. Como funciona
     * Avalia `status == SpecStatus.PUBLISHED && targeting.matches(context, effectiveCaps)`.
     *
     * @param context Contexto auditável consolidado do cliente móvel.
     * @param effectiveCaps Conjunto efetivo de capacidades suportadas pelo cliente.
     * @return `true` se estiver publicada e atender aos critérios de targeting, `false` caso contrário.
     */
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean =
        status == SpecStatus.PUBLISHED && targeting.matches(context, effectiveCaps)
}

/**
 * Braço ou variante de teste no modelo de experimentação de interface (ADR-017).
 *
 * ### 1. O que faz
 * Modela uma opção de variante de teste A/B, mapeando um peso proporcional de tráfego para uma revisão
 * inteira de especificação de tela.
 *
 * ### 2. Para que serve
 * Viabiliza testes A/B seguros onde variantes completas de tela são comparadas de forma balanceada sem mistura
 * de componentes de especificações distintas.
 *
 * ### 3. Como funciona
 * Encapsula o nome da variante [name], a chave da revisão de especificação [specRevisionId] e o peso relativo [weight]
 * utilizado no particionamento do tráfego.
 *
 * @property name Nome da variante do experimento (ex.: "control", "variant_new_checkout").
 * @property specRevisionId Identificador da revisão completa de especificação entregue por este braço.
 * @property weight Peso numérico atribuído à variante para cálculo probabilístico de alocação de tráfego.
 */
data class ExperimentArm(
    /**
     * Nome da variante de teste.
     *
     * ### 1. O que faz
     * Identifica o rótulo do braço de teste.
     *
     * ### 2. Para que serve
     * Rastreabilidade em métricas de analytics e conversão.
     *
     * ### 3. Como funciona
     * String descritiva única no experimento.
     */
    val name: String,

    /**
     * Revisão de especificação vinculada a este braço.
     *
     * ### 1. O que faz
     * Informa qual especificação completa deve ser servida para os usuários sorteados nesta variante.
     *
     * ### 2. Para que serve
     * Desacopla a experimentação de alterações em código: cada variante entrega uma spec independente.
     *
     * ### 3. Como funciona
     * String no formato `specId:revision`.
     */
    val specRevisionId: String,

    /**
     * Peso de ponderação do braço de teste.
     *
     * ### 1. O que faz
     * Define a proporção de tráfego que deve ser direcionada para esta variante.
     *
     * ### 2. Para que serve
     * Balanceamento configurável de tráfego (ex.: 50/50, 90/10).
     *
     * ### 3. Como funciona
     * Inteiro positivo proporcional à soma dos pesos de todos os braços.
     */
    val weight: Int,
)

/**
 * Configuração de experimentação ativa associada ao ponteiro de uma superfície (ADR-017).
 *
 * ### 1. O que faz
 * Reúne os parâmetros de governança de um experimento de tela A/B ativo em determinado ponteiro.
 *
 * ### 2. Para que serve
 * Permite realizar testes controlados de layout e conteúdo em produção sem necessidade de redeploy da aplicação.
 *
 * ### 3. Como funciona
 * Identificado por [id], possui data limite de expiração [endsAt] e uma coleção de braços de teste [arms].
 *
 * @property id Identificador do experimento.
 * @property endsAt Instante temporal de encerramento programado do experimento.
 * @property arms Coleção de braços de experimentação que competem pelo tráfego da superfície.
 */
data class ExperimentConfig(
    /**
     * Identificador do experimento.
     *
     * ### 1. O que faz
     * Armazena a chave exclusiva do teste A/B.
     *
     * ### 2. Para que serve
     * Correlação de métricas em plataformas de experimentação e observabilidade.
     *
     * ### 3. Como funciona
     * String textual identificadora.
     */
    val id: String,

    /**
     * Data e hora de expiração do teste.
     *
     * ### 1. O que faz
     * Define o momento em que a experimentação deixa de ser válida.
     *
     * ### 2. Para que serve
     * Previne que testes temporários continuem ativos indefinidamente por esquecimento operacional.
     *
     * ### 3. Como funciona
     * Instância de [Instant] comparada durante a seleção de rota.
     */
    val endsAt: Instant,

    /**
     * Braços de teste que integram o experimento.
     *
     * ### 1. O que faz
     * Agrupa as variantes que compõem o teste.
     *
     * ### 2. Para que serve
     * Distribuição probabilística de usuários entre as variantes.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de [ExperimentArm].
     */
    val arms: List<ExperimentArm>,
)

/**
 * Ponteiro de direcionamento de produção para uma superfície, plataforma e canal.
 *
 * ### 1. O que faz
 * Aponta qual revisão de especificação (`Spec`) está em vigor e sendo ativamente servida para determinada
 * superfície, plataforma e canal.
 *
 * ### 2. Para que serve
 * É o único estado mutável do ecossistema de governança, funcionando como a chave central de roteamento,
 * ativação e reversão instantânea de versões de tela (rollback atômico). Garante o isolamento estrito de canais
 * (alterar o ponteiro canário não afeta o estável) e plataformas (iOS e Android possuem ponteiros independentes).
 *
 * ### 3. Como funciona
 * Controla a atomicidade através do número sequencial [version] (compare-and-set em banco). Armazena o identificador
 * da revisão anterior [previousSpecRevisionId] para permitir reversões atômicas de emergência. Pode conter uma
 * configuração opcional de experimentação [experiment] para distribuição de tráfego entre variantes.
 *
 * @property surface Nome da superfície controlada pelo ponteiro (ex.: "home").
 * @property platform Plataforma móvel governada ([ClientPlatform]).
 * @property channel Canal de distribuição do ponteiro ([Channel]).
 * @property specId Identificador conceitual da especificação ativa, ou `null` se nenhuma definida.
 * @property specRevisionId Identificador da revisão de especificação em vigor (ex.: "home_spec:1").
 * @property previousSpecRevisionId Identificador da revisão anterior para suporte a rollback imediato.
 * @property version Versão sequencial de concorrência otimista do ponteiro (incrementada a cada mutação).
 * @property experiment Configuração opcional de teste A/B ativa no ponteiro.
 */
data class Pointer(
    /**
     * Superfície vinculada ao ponteiro.
     *
     * ### 1. O que faz
     * Identifica a superfície gerenciada pelo ponteiro.
     *
     * ### 2. Para que serve
     * Roteamento de composições de tela.
     *
     * ### 3. Como funciona
     * String validada contra `Surfaces`.
     */
    val surface: String,

    /**
     * Plataforma móvel do ponteiro.
     *
     * ### 1. O que faz
     * Discrimina se o ponteiro direciona tráfego para iOS ou Android.
     *
     * ### 2. Para que serve
     * Assegura isolamento rigoroso entre plataformas de clientes.
     *
     * ### 3. Como funciona
     * Enum do tipo [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Canal de distribuição atendido.
     *
     * ### 1. O que faz
     * Informa se o ponteiro comanda o canal estável, canário ou interno.
     *
     * ### 2. Para que serve
     * Permite evolução e testes independentes por canal de entrega.
     *
     * ### 3. Como funciona
     * Enum do tipo [Channel].
     */
    val channel: Channel,

    /**
     * Identificador da especificação ativa.
     *
     * ### 1. O que faz
     * Armazena o ID conceitual da especificação em vigor.
     *
     * ### 2. Para que serve
     * Consulta e agrupamento da especificação no repositório.
     *
     * ### 3. Como funciona
     * String identificadora ou `null`.
     */
    val specId: String?,

    /**
     * Identificador da revisão de especificação em vigor.
     *
     * ### 1. O que faz
     * Informa a revisão exata que os clientes devem receber.
     *
     * ### 2. Para que serve
     * Chave primária de busca de conteúdo e chaveamento de cache.
     *
     * ### 3. Como funciona
     * String no formato `specId:revision` ou `null`.
     */
    val specRevisionId: String?,

    /**
     * Identificador da revisão anterior.
     *
     * ### 1. O que faz
     * Guarda o histórico da revisão que precedeu a atual.
     *
     * ### 2. Para que serve
     * Possibilita rollbacks imediatos de um passo sem necessidade de buscar eventos históricos.
     *
     * ### 3. Como funciona
     * String no formato `specId:revision` atualizada a cada publicação.
     */
    val previousSpecRevisionId: String?,

    /**
     * Número ordinal de versão do ponteiro.
     *
     * ### 1. O que faz
     * Registra o incremento sequencial de modificações sofridas pelo ponteiro.
     *
     * ### 2. Para que serve
     * Controle de concorrência otimista (OCC) e validação de descarte de last good defasado.
     *
     * ### 3. Como funciona
     * Inteiro de 64 bits (Long) incrementado atomicamente a cada atualização de ponteiro.
     */
    val version: Long,

    /**
     * Configuração opcional de teste A/B ativa.
     *
     * ### 1. O que faz
     * Associa regras de experimentação à resolução de rotas do ponteiro.
     *
     * ### 2. Para que serve
     * Habilita testes controlados de tela em produção.
     *
     * ### 3. Como funciona
     * Instância de [ExperimentConfig] ou `null` se o tráfego for 100% monolítico.
     */
    val experiment: ExperimentConfig? = null,
)

/**
 * Pedido formal de publicação de uma especificação no fluxo maker-checker.
 *
 * ### 1. O que faz
 * Modela a solicitação de promoção de uma revisão de especificação de rascunho para publicação oficial.
 *
 * ### 2. Para que serve
 * Impõe o princípio da segregação de funções da governança: o autor da especificação ([makerId]) submete
 * a solicitação, mas a autorização formal de ativação depende do julgamento de um revisor independente ([checkerId]).
 *
 * ### 3. Como funciona
 * Controla o ciclo de vida via [status] ([PublishRequestStatus]). Para blindar o fluxo contra alterações sorrateiras
 * entre a revisão humana e a aprovação final, armazena [reviewedContentHash] (ADR-022), assegurando que o conteúdo
 * aprovado seja estritamente idêntico ao inspecionado pelo revisor.
 *
 * @property requestId Identificador exclusivo da solicitação de publicação.
 * @property specId Identificador conceitual da especificação proposta.
 * @property revision Número sequencial da revisão proposta para publicação.
 * @property specRevisionId Identificador composto da revisão (ex.: "home_spec:2").
 * @property surface Nome da superfície de destino.
 * @property platform Plataforma móvel visada ([ClientPlatform]).
 * @property channel Canal de distribuição pretendido ([Channel]).
 * @property makerId Identificador do operador que abriu a proposta de publicação.
 * @property status Estado atual do pedido no fluxo ([PublishRequestStatus]).
 * @property checkerId Identificador do revisor que aprovou ou rejeitou o pedido, ou `null` se pendente.
 * @property reason Justificativa descritiva da decisão tomada pelo checker.
 * @property reviewedContentHash Checksum criptográfico do conteúdo inspecionado pelo checker durante a revisão.
 */
data class PublishRequest(
    /**
     * Identificador do pedido de publicação.
     *
     * ### 1. O que faz
     * Armazena a chave exclusiva da solicitação.
     *
     * ### 2. Para que serve
     * Rastreabilidade e consultas do status do pedido no plano administrativo.
     *
     * ### 3. Como funciona
     * String identificadora única gerada na abertura.
     */
    val requestId: String,

    /**
     * Identificador da especificação vinculada.
     *
     * ### 1. O que faz
     * Aponta para a spec que se deseja publicar.
     *
     * ### 2. Para que serve
     * Amarração de governança com o catálogo de especificações.
     *
     * ### 3. Como funciona
     * String identificadora de spec.
     */
    val specId: String,

    /**
     * Número da revisão proposta.
     *
     * ### 1. O que faz
     * Informa a versão exata que se pretende promover a [SpecStatus.PUBLISHED].
     *
     * ### 2. Para que serve
     * Determinação do alvo de publicação.
     *
     * ### 3. Como funciona
     * Inteiro ordinal de revisão.
     */
    val revision: Int,

    /**
     * Identificador composto da revisão.
     *
     * ### 1. O que faz
     * Formata `specId:revision` para referência unificada.
     *
     * ### 2. Para que serve
     * Consulta e comparação rápida em índices e caches.
     *
     * ### 3. Como funciona
     * String no formato canônico de revisão.
     */
    val specRevisionId: String,

    /**
     * Superfície de destino da publicação.
     *
     * ### 1. O que faz
     * Informa a tela afetada pela proposta.
     *
     * ### 2. Para que serve
     * Roteamento de filas de revisão por equipe de produto.
     *
     * ### 3. Como funciona
     * String validada contra `Surfaces`.
     */
    val surface: String,

    /**
     * Plataforma móvel visada.
     *
     * ### 1. O que faz
     * Informa se a proposta se destina a iOS ou Android.
     *
     * ### 2. Para que serve
     * Garante segregação de revisão por plataforma técnica.
     *
     * ### 3. Como funciona
     * Enum do tipo [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Canal pretendido para a publicação.
     *
     * ### 1. O que faz
     * Especifica se a promoção visa canais de teste ou produção estável.
     *
     * ### 2. Para que serve
     * Aplicação de regras diferenciadas de aprovação conforme o risco do canal.
     *
     * ### 3. Como funciona
     * Enum do tipo [Channel].
     */
    val channel: Channel,

    /**
     * Identificador do operador proponente (maker).
     *
     * ### 1. O que faz
     * Registra o autor que solicitou a publicação.
     *
     * ### 2. Para que serve
     * Aplica a regra de maker-checker: impede que o próprio proponente aprove seu pedido fora do canal interno.
     *
     * ### 3. Como funciona
     * String com o identificador auditável do operador.
     */
    val makerId: String,

    /**
     * Situação do pedido de publicação.
     *
     * ### 1. O que faz
     * Informa o estágio de julgamento do pedido ([PublishRequestStatus.OPEN], [PublishRequestStatus.APPROVED], etc.).
     *
     * ### 2. Para que serve
     * Controle de fluxo de trabalho e concorrência na aprovação.
     *
     * ### 3. Como funciona
     * Enum do tipo [PublishRequestStatus].
     */
    val status: PublishRequestStatus,

    /**
     * Identificador do operador revisor (checker).
     *
     * ### 1. O que faz
     * Registra o autor da decisão de aprovação ou rejeição.
     *
     * ### 2. Para que serve
     * Trilha de auditoria e conformidade regulatória.
     *
     * ### 3. Como funciona
     * Preenchido no momento da transição de estado da solicitação.
     */
    val checkerId: String? = null,

    /**
     * Justificativa descritiva da decisão.
     *
     * ### 1. O que faz
     * Armazena o parecer do revisor justificando a aprovação ou os motivos da reprovação.
     *
     * ### 2. Para que serve
     * Comunicação transparente entre revisor e proponente.
     *
     * ### 3. Como funciona
     * Texto explicativo opcional.
     */
    val reason: String? = null,

    /**
     * Hash do conteúdo no momento da inspeção humana de revisão (ADR-022).
     *
     * ### 1. O que faz
     * Registra o checksum criptográfico da especificação no momento exato em que o revisor visualizou as alterações.
     *
     * ### 2. Para que serve
     * Blindagem contra aprovação de alterações silenciosas que ocorram após a conferência humana.
     *
     * ### 3. Como funciona
     * String no formato "sha256:<hex>". Pedidos legados anteriores ao ADR-022 com valor nulo exigem reabertura antes da aprovação.
     */
    val reviewedContentHash: String? = null,
)

/**
 * Registro de uma alteração atômica entre duas revisões de uma especificação.
 *
 * ### 1. O que faz
 * Modela a modificação de uma propriedade, adição ou remoção de um elemento entre versões de uma tela.
 *
 * ### 2. Para que serve
 * Permite aos revisores humanos e ferramentas de auditoria inspecionar detalhadamente o que mudou
 * em uma proposta antes de autorizar a publicação.
 *
 * ### 3. Como funciona
 * Identifica o caminho do atributo modificado [path], a classificação da alteração [change] e os valores
 * prévio [from] e resultante [to].
 *
 * @property path Caminho semântico do elemento alterado (ex.: `sections[0].props.title`).
 * @property change Tipo de mutação detectada (ex.: "added", "removed", "modified").
 * @property from Valor anterior do campo em formato textual, ou `null` se adição.
 * @property to Novo valor do campo em formato textual, ou `null` se remoção.
 */
data class DiffEntry(
    /**
     * Caminho JSON-path ou hierárquico do campo alterado.
     *
     * ### 1. O que faz
     * Localiza com precisão o atributo que sofreu modificação.
     *
     * ### 2. Para que serve
     * Facilita a inspeção visual e navegação no relatório de diferenças.
     *
     * ### 3. Como funciona
     * String indicativa de caminho hierárquico.
     */
    val path: String,

    /**
     * Natureza da modificação.
     *
     * ### 1. O que faz
     * Categoriza se houve adição, remoção ou alteração de valor.
     *
     * ### 2. Para que serve
     * Filtros de visualização no comparador de especificações.
     *
     * ### 3. Como funciona
     * String descritiva da ação de diff.
     */
    val change: String,

    /**
     * Valor original anterior à mudança.
     *
     * ### 1. O que faz
     * Armazena o estado do campo na revisão de origem.
     *
     * ### 2. Para que serve
     * Comparação lado a lado do valor antigo.
     *
     * ### 3. Como funciona
     * String formatada ou `null`.
     */
    val from: String? = null,

    /**
     * Novo valor resultante da mudança.
     *
     * ### 1. O que faz
     * Armazena o estado do campo na revisão proposta.
     *
     * ### 2. Para que serve
     * Conferência do valor que entrará em vigor.
     *
     * ### 3. Como funciona
     * String formatada ou `null`.
     */
    val to: String? = null,
)

/**
 * Relatório consolidado de diferenças estruturais e de conteúdo entre duas revisões de especificação.
 *
 * ### 1. O que faz
 * Agrupa todas as alterações ocorridas entre uma revisão base e a revisão proposta para publicação.
 *
 * ### 2. Para que serve
 * Fornece a base analítica para a tomada de decisão do revisor (checker) na governança. Destaca de forma explícita
 * alterações na ocupação de slots portantes ([requiredOccupancy]), que representam o maior risco de indisponibilidade
 * ou quebra da experiência do usuário na tela inicial.
 *
 * ### 3. Como funciona
 * Estruturado em listas de [DiffEntry] categorizadas por adições ([added]), remoções ([removed]), alterações ([changed])
 * e variações na ocupação de slots portantes mandatórios ([requiredOccupancy]).
 *
 * @property specId Identificador conceitual da especificação comparada.
 * @property fromRevision Número da revisão de origem, ou `null` se for uma nova especificação do zero.
 * @property toRevision Número da revisão proposta alvo da comparação.
 * @property added Lista de elementos adicionados na nova revisão.
 * @property removed Lista de elementos excluídos na nova revisão.
 * @property changed Lista de propriedades que tiveram seus valores alterados.
 * @property requiredOccupancy Alterações específicas que afetam a quantidade de seções em slots portantes obrigatórios.
 */
data class SpecDiff(
    /**
     * Identificador da especificação avaliada.
     *
     * ### 1. O que faz
     * Armazena a chave da spec analisada.
     *
     * ### 2. Para que serve
     * Identificação do escopo do relatório de diff.
     *
     * ### 3. Como funciona
     * String identificadora de spec.
     */
    val specId: String,

    /**
     * Revisão de referência anterior.
     *
     * ### 1. O que faz
     * Indica qual versão serviu de baseline para a comparação.
     *
     * ### 2. Para que serve
     * Contextualização de qual ponto no tempo originou as diferenças.
     *
     * ### 3. Como funciona
     * Inteiro opcional indicando a versão de partida.
     */
    val fromRevision: Int?,

    /**
     * Revisão proposta sob avaliação.
     *
     * ### 1. O que faz
     * Indica qual versão contém as modificações propostas.
     *
     * ### 2. Para que serve
     * Alvo de aprovação ou rejeição da governança.
     *
     * ### 3. Como funciona
     * Inteiro ordinal com a versão de chegada.
     */
    val toRevision: Int,

    /**
     * Coleção de elementos introduzidos na nova revisão.
     *
     * ### 1. O que faz
     * Agrupa novas seções, props ou ações adicionadas.
     *
     * ### 2. Para que serve
     * Conferência de novos blocos que passarão a ser exibidos.
     *
     * ### 3. Como funciona
     * Lista imutável de [DiffEntry].
     */
    val added: List<DiffEntry>,

    /**
     * Coleção de elementos removidos na nova revisão.
     *
     * ### 1. O que faz
     * Agrupa seções, ações ou props excluídas.
     *
     * ### 2. Para que serve
     * Alerta sobre a retirada de recursos existentes na tela.
     *
     * ### 3. Como funciona
     * Lista imutável de [DiffEntry].
     */
    val removed: List<DiffEntry>,

    /**
     * Coleção de atributos modificados entre as revisões.
     *
     * ### 1. O que faz
     * Agrupa propriedades que tiveram valores alterados.
     *
     * ### 2. Para que serve
     * Verificação de mudanças em títulos, textos, links ou layouts.
     *
     * ### 3. Como funciona
     * Lista imutável de [DiffEntry].
     */
    val changed: List<DiffEntry>,

    /**
     * Alterações na volumetria de seções em slots portantes obrigatórios.
     *
     * ### 1. O que faz
     * Destaca modificações que impactam a presença de seções em slots como "header" ou "accounts".
     *
     * ### 2. Para que serve
     * Prevenção ativa de incidentes de indisponibilidade decorrentes de slots portantes vazios (ADR-009).
     *
     * ### 3. Como funciona
     * Lista imutável de [DiffEntry] focada exclusivamente em slots com `required = true`.
     */
    val requiredOccupancy: List<DiffEntry>,
)

/**
 * Evento imutável de trilha de auditoria sobre ações de governança e ciclo de vida de telas.
 *
 * ### 1. O que faz
 * Registra formalmente uma ação administrativa executada no sistema: criação de rascunhos, submissões,
 * aprovações, rejeições ou rollbacks de ponteiros.
 *
 * ### 2. Para que serve
 * Garante rastreabilidade integral e conformidade com auditorias de segurança e regulatórias, assegurando
 * que todas as alterações produtivas possam ser reconstituídas cronologicamente com atribuição clara de autoria.
 *
 * ### 3. Como funciona
 * Estrutura append-only gravada a cada alteração de estado no sistema. Captura o instante [ts], identificador do operador
 * [actorId], papel exercido [role], ação realizada [action], superfície, plataforma, canal e as revisões de origem e destino.
 *
 * @property id Identificador exclusivo do registro de auditoria.
 * @property ts Instante temporal em que o evento ocorreu ([Instant]).
 * @property actorId Identificador do operador responsável pela ação.
 * @property role Papel funcional reivindicado pelo operador no momento da ação ([ActorRole]).
 * @property action Ação administrativa realizada (ex.: "publish", "rollback", "approve").
 * @property surface Superfície afetada pela operação.
 * @property platform Plataforma móvel visada ([ClientPlatform]).
 * @property channel Canal de distribuição impactado ([Channel]).
 * @property specId Identificador conceitual da especificação relacionada, ou `null`.
 * @property fromRevision Revisão original do ponteiro antes da operação.
 * @property toRevision Nova revisão do ponteiro pós-operação.
 * @property requestId Identificador do pedido de publicação associado, se houver.
 */
data class AuditEvent(
    /**
     * Identificador do evento de auditoria.
     *
     * ### 1. O que faz
     * Armazena a chave primária única do registro.
     *
     * ### 2. Para que serve
     * Indexação e consulta no histórico append-only.
     *
     * ### 3. Como funciona
     * String única gerada no momento do registro.
     */
    val id: String,

    /**
     * Instante temporal do evento.
     *
     * ### 1. O que faz
     * Registra o timestamp com precisão de milissegundos.
     *
     * ### 2. Para que serve
     * Reconstrução cronológica precisa de incidentes ou mudanças.
     *
     * ### 3. Como funciona
     * Instância de [Instant] obtida no instante da operação.
     */
    val ts: Instant,

    /**
     * Identificador do operador responsável.
     *
     * ### 1. O que faz
     * Informa quem disparou a ação administrativa.
     *
     * ### 2. Para que serve
     * Atribuição de responsabilidade e auditoria de acesso.
     *
     * ### 3. Como funciona
     * String auditável informada no cabeçalho `Actor-Id`.
     */
    val actorId: String,

    /**
     * Papel funcional exercido pelo operador.
     *
     * ### 1. O que faz
     * Registra a função com a qual a ação foi autorizada ([ActorRole.MAKER], [ActorRole.CHECKER], etc.).
     *
     * ### 2. Para que serve
     * Verificação de conformidade das permissões operacionais.
     *
     * ### 3. Como funciona
     * Enum do tipo [ActorRole].
     */
    val role: ActorRole,

    /**
     * Nome da ação executada.
     *
     * ### 1. O que faz
     * Identifica o tipo de operação realizada (ex.: "open_request", "approve_request", "rollback").
     *
     * ### 2. Para que serve
     * Filtros analíticos de eventos de governança.
     *
     * ### 3. Como funciona
     * String literal padronizada.
     */
    val action: String,

    /**
     * Superfície de UI afetada.
     *
     * ### 1. O que faz
     * Informa a tela sobre a qual a ação recaiu.
     *
     * ### 2. Para que serve
     * Segregação de eventos por tela de negócio.
     *
     * ### 3. Como funciona
     * String identificadora de superfície.
     */
    val surface: String,

    /**
     * Plataforma móvel impactada.
     *
     * ### 1. O que faz
     * Indica se a operação atuou sobre o ecossistema iOS ou Android.
     *
     * ### 2. Para que serve
     * Auditoria segregada por plataforma.
     *
     * ### 3. Como funciona
     * Enum do tipo [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Canal de publicação impactado.
     *
     * ### 1. O que faz
     * Informa se a mudança incidiu sobre canal estável, canário ou interno.
     *
     * ### 2. Para que serve
     * Auditoria segregada por coorte de distribuição.
     *
     * ### 3. Como funciona
     * Enum do tipo [Channel].
     */
    val channel: Channel,

    /**
     * Especificação associada ao evento.
     *
     * ### 1. O que faz
     * Identifica a spec alvo da ação, se aplicável.
     *
     * ### 2. Para que serve
     * Rastreabilidade por especificação.
     *
     * ### 3. Como funciona
     * String identificadora ou `null`.
     */
    val specId: String?,

    /**
     * Revisão precedente da operação.
     *
     * ### 1. O que faz
     * Registra o estado anterior do ponteiro ou da spec.
     *
     * ### 2. Para que serve
     * Rastreia de onde o sistema partiu antes da alteração.
     *
     * ### 3. Como funciona
     * String com a versão prévia ou `null`.
     */
    val fromRevision: String?,

    /**
     * Revisão resultante da operação.
     *
     * ### 1. O que faz
     * Registra o novo estado atingido após a alteração.
     *
     * ### 2. Para que serve
     * Rastreia para onde o sistema avançou com a publicação.
     *
     * ### 3. Como funciona
     * String com a nova versão ou `null`.
     */
    val toRevision: String?,

    /**
     * Identificador do pedido de publicação associado.
     *
     * ### 1. O que faz
     * Amarração com a solicitação formal de publicação.
     *
     * ### 2. Para que serve
     * Correlação entre o pedido maker e a aprovação checker.
     *
     * ### 3. Como funciona
     * String com o ID do pedido ou `null`.
     */
    val requestId: String?,
)

/**
 * Registro de reserva ou desfecho de uma operação administrativa sob chave de idempotência.
 *
 * ### 1. O que faz
 * Modela o estado de processamento de uma requisição administrativa identificada pelo cabeçalho `Idempotency-Key`,
 * assegurando que retentativas de rede não executem efeitos colaterais duplicados.
 *
 * ### 2. Para que serve
 * Garante a semântica "exatamente uma vez" para ações críticas como abertura de propostas, aprovação de publicações
 * e acionamento de rollbacks. Se a rede oscilar ou o cliente reenviar a mesma requisição, o sistema devolve o mesmo
 * resultado sem conflitar ou repetir a mutação no banco de dados.
 *
 * ### 3. Como funciona
 * Opera em duas fases: reserva a chave previamente ([resultRef] nulo) com inserção atômica no armazenamento,
 * e a conclui após o commit com a referência do resultado ([resultRef]). Valida os parâmetros da requisição
 * através de [fingerprint]: caso a mesma chave seja reapresentada com parâmetros divergentes, a operação é rejeitada
 * como erro do cliente, impedindo reutilização indevida de chaves.
 *
 * @property key Chave de idempotência informada no cabeçalho HTTP `Idempotency-Key`.
 * @property operation Nome da operação administrativa protegida (ex.: "publish", "rollback").
 * @property resultRef Referência do recurso gerado ou modificado pela operação, ou `null` se reserva em andamento.
 * @property fingerprint Hash resumo dos parâmetros da requisição para prevenção de reutilização conflitante de chaves.
 */
data class IdempotencyRecord(
    /**
     * Chave de idempotência da requisição.
     *
     * ### 1. O que faz
     * Armazena o identificador único fornecido pelo chamador.
     *
     * ### 2. Para que serve
     * Deduplicação de requisições concorrentes ou retentativas de rede.
     *
     * ### 3. Como funciona
     * String informada no cabeçalho `Idempotency-Key`.
     */
    val key: String,

    /**
     * Identificador da operação executada.
     *
     * ### 1. O que faz
     * Registra o tipo de ação sob a proteção da chave.
     *
     * ### 2. Para que serve
     * Segregação de escopo de chaves por tipo de operação.
     *
     * ### 3. Como funciona
     * String indicativa de operação (ex.: "open_publish_request").
     */
    val operation: String,

    /**
     * Referência do resultado da operação concluída.
     *
     * ### 1. O que faz
     * Armazena o ponteiro ou ID do recurso gerado pela operação.
     *
     * ### 2. Para que serve
     * Diferencia uma operação ainda em execução (`null`) de uma já consolidada e pronta para replay.
     *
     * ### 3. Como funciona
     * String de referência ou `null` enquanto a operação estiver em voo.
     */
    val resultRef: String?,

    /**
     * Resumo criptográfico dos parâmetros da requisição.
     *
     * ### 1. O que faz
     * Armazena o hash dos dados de entrada da operação.
     *
     * ### 2. Para que serve
     * Assegura que retentativas usem exatamente os mesmos argumentos, rejeitando adulterações com a mesma chave.
     *
     * ### 3. Como funciona
     * String hash dos parâmetros da chamada (padrão string vazia).
     */
    val fingerprint: String = "",
)
