package br.com.empresa.sdui.contract.analytics

/**
 * Evento analítico padronizado para visualização de superfície (tela completa).
 *
 * ### 1. O que faz
 * Modela a carga de dados de telemetria emitida pelo aplicativo móvel no momento em que uma tela SDUI é apresentada.
 *
 * ### 2. Para que serve
 * Garante a padronização centralizada de telemetria no servidor. Em vez de delegar às equipes de iOS e Android a tarefa
 * de inventar nomes de eventos, coletar versões de schema e montar dicionários de telemetria, o BFF compõe o evento com
 * todas as dimensões auditáveis prontas para envio imediato ao pipeline de dados (CDP / Product Analytics).
 *
 * ### 3. Como funciona
 * Embutido no envelope de resposta (`ScreenEnvelope.analytics`). Assim que o ciclo de vida da tela nativa confirma a
 * renderização (`viewDidAppear` no iOS ou `onResume` / `LaunchedEffect` no Android), o cliente extrai este payload e o
 * encaminha diretamente para o SDK de telemetria móvel sem realizar mutações nos dados.
 *
 * @property event Nome padronizado do evento de telemetria (ex.: "screen_view").
 * @property surface Identificador da superfície de interface apresentada (ex.: "home", "catalog").
 * @property platform Plataforma operacional do dispositivo ("ios" ou "android").
 * @property experience Nome da variante ou experiência de negócio atribuída à tela.
 * @property schemaVersion Versão do protocolo de schema SDUI adotada na tela (Eixo A).
 * @property specRevisionId Identificador único e imutável da revisão de especificação entregue.
 * @property sectionCount Quantidade total de seções atômicas renderizadas na tela.
 * @property fallback Indicador booleano sinalizando se a tela foi montada via escada de fallback (`ADR-007`).
 */
data class ScreenAnalyticsResponse(
    /**
     * Nome padronizado do evento de telemetria de tela.
     *
     * ### 1. O que faz
     * Informa a chave canônica do evento analítico (ex.: "screen_view").
     *
     * ### 2. Para que serve
     * Permite ao pipeline de dados corporativo classificar e agregar eventos de impressão de tela de forma consistente.
     *
     * ### 3. Como funciona
     * Definido como constante pelo orquestrador de telemetria do BFF no término da composição.
     */
    val event: String,

    /**
     * Identificador canônico da surface renderizada.
     *
     * ### 1. O que faz
     * Nomeia a superfície de interface visualizada pelo usuário (ex.: "home", "catalog").
     *
     * ### 2. Para que serve
     * Segmenta as métricas de tráfego, engajamento e conversão por tela da aplicação.
     *
     * ### 3. Como funciona
     * Ecoado a partir do nome da surface solicitada na rota HTTP.
     */
    val surface: String,

    /**
     * Plataforma operacional do cliente móvel.
     *
     * ### 1. O que faz
     * Identifica a plataforma de execução do aplicativo ("ios" ou "android").
     *
     * ### 2. Para que serve
     * Habilita a análise comparativa de engajamento e erros de renderização entre ecossistemas móveis.
     *
     * ### 3. Como funciona
     * Preenchido a partir do cabeçalho validado `Client-Platform`.
     */
    val platform: String,

    /**
     * Identificador da experiência ou variante de negócio.
     *
     * ### 1. O que faz
     * Nomeia a variante de negócio aplicada à composição da tela.
     *
     * ### 2. Para que serve
     * Permite aos times de produto comparar o desempenho de diferentes experimentos ou experiências personalizadas.
     *
     * ### 3. Como funciona
     * Extraído da spec de tela associada à requisição durante o estágio de seleção.
     */
    val experience: String,

    /**
     * Versão do protocolo de schema SDUI (Eixo A).
     *
     * ### 1. O que faz
     * Informa a versão do contrato de protocolo utilizada (ex.: "3").
     *
     * ### 2. Para que serve
     * Permite monitorar a taxa de migração de clientes móveis para novas versões de protocolo de envelope.
     *
     * ### 3. Como funciona
     * Herda o valor do `UI-Schema-Version` negociado para a resposta.
     */
    val schemaVersion: String,

    /**
     * Identificador único da revisão de especificação servida.
     *
     * ### 1. O que faz
     * Transporta o identificador imutável da spec publicada.
     *
     * ### 2. Para que serve
     * Permite avaliar o impacto de novos deploys de telas, monitorar fases de canário e correlacionar métricas de conversão.
     *
     * ### 3. Como funciona
     * Propaga o ID da revisão imutável selecionada pelo estágio de `Select`.
     */
    val specRevisionId: String,

    /**
     * Contagem de seções renderizadas na tela.
     *
     * ### 1. O que faz
     * Informa a quantidade de blocos de conteúdo efetivamente entregues no payload.
     *
     * ### 2. Para que serve
     * Auxilia na medição de densidade de conteúdo e diagnóstico de degradação com omissão de seções.
     *
     * ### 3. Como funciona
     * Calculado diretamente a partir do tamanho da lista de seções validadas pelo orquestrador.
     */
    val sectionCount: Int,

    /**
     * Flag indicadora de resposta gerada sob degradação.
     *
     * ### 1. O que faz
     * Sinaliza se a tela foi servida a partir da escada de fallback (`ADR-007`).
     *
     * ### 2. Para que serve
     * Permite filtrar e isolar eventos de telemetria gerados durante períodos de indisponibilidade parcial ou total.
     *
     * ### 3. Como funciona
     * Booleano extraído do estado de fallback da composição (verdadeiro quando originado do last good ou de omissão forçada).
     */
    val fallback: Boolean,
)

/**
 * Evento analítico padronizado para impressão e interação de uma seção individual.
 *
 * ### 1. O que faz
 * Modela os metadados analíticos associados a uma seção atômica da tela para registro de impressões e toques.
 *
 * ### 2. Para que serve
 * Permite que a equipe de dados meça a taxa de visibilidade (impressão/viewport) e a taxa de cliques (CTR) de cada
 * componente individual de UI. Ao amarrar a métrica ao [specRevisionId], torna possível avaliar cientificamente
 * experimentos A/B e a eficácia de publicações canárias de especificação.
 *
 * ### 3. Como funciona
 * Entregue em cada seção (`SectionResponse.analytics`). O aplicativo móvel monitora a viewport através de observadores
 * de scroll nativos; assim que o componente se torna visível na tela, despacha o evento correspondente para a plataforma
 * de telemetria, utilizando os atributos pré-montados sem necessidade de cálculo dinâmico no cliente.
 *
 * @property event Nome padronizado do evento de telemetria da seção (ex.: "section_view").
 * @property component Nome canônico do componente de UI registrado no Design System (ex.: "account_card").
 * @property componentVersion Versão inteira do contrato do componente de UI (Eixo B).
 * @property slot Identificador do slot estrutural ocupado pela seção no esqueleto.
 * @property sectionId Identificador único da seção de UI na especificação.
 * @property specRevisionId Identificador único da revisão de especificação que publicou este componente.
 */
data class SectionAnalyticsResponse(
    /**
     * Nome padronizado do evento analítico da seção.
     *
     * ### 1. O que faz
     * Declara a chave canônica do evento da seção (ex.: "section_view").
     *
     * ### 2. Para que serve
     * Padroniza o tipo de disparo de métrica de visibilidade e engajamento para a seção no pipeline analítico.
     *
     * ### 3. Como funciona
     * Definido pelo BFF para garantir consistência semântica entre plataformas móveis.
     */
    val event: String,

    /**
     * Nome canônico do componente no Design System.
     *
     * ### 1. O que faz
     * Nomeia o componente de interface avaliado (ex.: "top_bar", "account_card", "credit_offer").
     *
     * ### 2. Para que serve
     * Permite agrupar métricas de usabilidade e conversão por tipo de elemento visual.
     *
     * ### 3. Como funciona
     * Copiado do atributo `type` da seção renderizada.
     */
    val component: String,

    /**
     * Versão do contrato do componente de UI (Eixo B).
     *
     * ### 1. O que faz
     * Especifica a versão inteira do contrato do componente.
     *
     * ### 2. Para que serve
     * Permite mensurar a performance e engajamento de versões específicas de um componente após refatorações.
     *
     * ### 3. Como funciona
     * Copiado do atributo `typeVersion` da seção correspondente.
     */
    val componentVersion: Int,

    /**
     * Nome do slot estrutural ocupado pela seção.
     *
     * ### 1. O que faz
     * Informa o slot do esqueleto onde o componente foi posicionado.
     *
     * ### 2. Para que serve
     * Avalia o impacto do posicionamento da seção (ex.: topo vs rodapé) na taxa de conversão e cliques.
     *
     * ### 3. Como funciona
     * Preenchido com o mesmo identificador de `slot` configurado na seção.
     */
    val slot: String,

    /**
     * Identificador único da instância da seção.
     *
     * ### 1. O que faz
     * Fornece o ID estável da seção avaliada.
     *
     * ### 2. Para que serve
     * Permite rastrear instâncias específicas de um componente em testes comparativos na mesma superfície.
     *
     * ### 3. Como funciona
     * Copiado do atributo `id` da seção no payload.
     */
    val sectionId: String,

    /**
     * Identificador único da revisão de especificação.
     *
     * ### 1. O que faz
     * Transporta o identificador da spec publicada que originou a seção.
     *
     * ### 2. Para que serve
     * Conecta a telemetria do componente diretamente ao histórico de publicação e auditoria no backend.
     *
     * ### 3. Como funciona
     * Propaga o ID da revisão de spec ativa que participou da composição.
     */
    val specRevisionId: String,
)
