package br.com.empresa.sdui.contract.screen

/**
 * Corpo da resposta HTTP de uma superfície SDUI: a árvore de UI pronta para renderização.
 *
 * ### 1. O que faz
 * Representa a estrutura de dados raiz entregue aos clientes móveis pelos endpoints de superfícies
 * (ex.: `GET /v1/surfaces/home` e `GET /v1/surfaces/catalog`).
 *
 * ### 2. Para que serve
 * Define o contrato canônico consumido pelas bibliotecas renderizadoras nativas (iOS e Android), estruturado
 * na tríade Server-Driven UI:
 * 1. [envelope]: Metadados de auditoria, rastreabilidade de spec, integridade e telemetria da composição;
 * 2. [skeleton]: Gabarito estrutural ordenado de slots conceituais onde as seções serão acopladas;
 * 3. [sections]: Lista ordenada dos blocos de conteúdo e componentes de negócio prontos para renderização.
 *
 * A forma canônica deste contrato é validada rigorosamente pela fixture `fixtures/contrato-sdui-home-definitivo.json`
 * e guardada pela suíte de testes de `sdui-contract`. Qualquer alteração de campos quebra a compatibilidade binária
 * com os aplicativos em produção.
 *
 * ### 3. Como funciona
 * O orquestrador central do BFF (`ComposeScreenService`) monta esta árvore após percorrer as fases do pipeline:
 * `Negotiate` -> `Select` -> `Filter` -> `Hydrate` -> `Guard / Fallback` -> `Compose`. O cliente nativo recebe o
 * payload JSON, valida os metadados do envelope, monta o arcabouço estrutural do skeleton e projeta cada seção em
 * seu respectivo slot visual.
 *
 * @property envelope Metadados de controle, governança, rastreabilidade e telemetria da composição.
 * @property skeleton Arcabouço estrutural de slots ordenados que organiza visualmente a tela.
 * @property sections Coleção ordenada de seções de conteúdo validadas e prontas para exibição.
 */
data class ScreenResponse(
    /**
     * Metadados operacionais e de governança da resposta SDUI.
     *
     * ### 1. O que faz
     * Agrupa os metadados de auditoria, integridade, compatibilidade e rastreabilidade da tela composta.
     *
     * ### 2. Para que serve
     * Permite ao cliente nativo validar a versão do contrato (Eixo A), verificar se a resposta foi degradada
     * pela escada de fallback (`ADR-007`) e registrar métricas padronizadas de telemetria.
     *
     * ### 3. Como funciona
     * Transporta uma instância de [ScreenEnvelope] preenchida ao final do pipeline de orquestração com os dados
     * da revisão de spec selecionada e parâmetros do dispositivo requisitante.
     */
    val envelope: ScreenEnvelope,

    /**
     * Esqueleto estrutural de slots da tela.
     *
     * ### 1. O que faz
     * Define o gabarito estrutural de slots ordenados que compõem o layout visual da superfície.
     *
     * ### 2. Para que serve
     * Permite que o cliente móvel construa a hierarquia de contêineres e exiba placeholders ou shimmers estruturais
     * antes de posicionar as seções, mitigando o layout shift perceptível pelo usuário.
     *
     * ### 3. Como funciona
     * Transporta uma instância de [SkeletonResponse] derivada da especificação ativa, garantindo a separação estrita
     * entre o arcabouço estrutural e o conteúdo das seções.
     */
    val skeleton: SkeletonResponse,

    /**
     * Lista ordenada de seções de interface renderizáveis.
     *
     * ### 1. O que faz
     * Contém a coleção de seções atômicas de UI que ocupam os slots definidos pelo esqueleto.
     *
     * ### 2. Para que serve
     * Entrega os componentes funcionais com seus dados de negócio em `props`, intenções de navegação em `actions`
     * e eventos de impressão em `analytics`, livres de propriedades de CSS ou formatação visual direta (`ADR-010`).
     *
     * ### 3. Como funciona
     * Lista de instâncias de [SectionResponse] que foram validadas contra as capabilities do cliente (Eixo B),
     * hidratadas com dados dinâmicos de backend e aprovadas pelos guardas de integridade (`VisualGuard` e `PiiGuard`).
     */
    val sections: List<SectionResponse>,
)
