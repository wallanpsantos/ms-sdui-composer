package br.com.empresa.sdui.contract.client

/**
 * Eco auditável dos dados e parâmetros de contexto declarados pelo cliente na requisição.
 *
 * ### 1. O que faz
 * Encapsula a reprodução fiel dos metadados recebidos nos cabeçalhos HTTP do aplicativo móvel solicitante.
 *
 * ### 2. Para que serve
 * Garante a auditabilidade imediata e determinística da resposta SDUI. Ao investigar bugs de interface, discrepâncias
 * visuais ou incidentes relatados por usuários finais, engenheiros e analistas conseguem identificar instantaneamente,
 * direto no corpo do payload JSON, a versão do app, build e versão de sistema operacional que o BFF interpretou, sem
 * a necessidade de consultar logs de borda ou reconstruir o tráfego da rede.
 *
 * ### 3. Como funciona
 * Populado na primeira fase do pipeline (`Negotiate`) a partir da leitura e sanitização dos cabeçalhos obrigatórios
 * (`Client-Platform`, `Client-Version`, `Client-Build`) e opcionais (`Client-OS-Version`, `UI-Schema-Version`).
 * Em caso de reaproveitamento de árvore via cache (`treeCache`), o método `withRequester` reidrata este objeto com os
 * dados da requisição corrente, impedindo que os metadados do primeiro cliente requisitante vazem para requisições subsequentes.
 *
 * @property platform Plataforma do aplicativo solicitante ("ios" ou "android").
 * @property appVersion Versão semântica de lançamento do aplicativo cliente (ex.: "1.14.0").
 * @property build Número ordinal de compilação do aplicativo (ex.: "1420").
 * @property osVersion Versão do sistema operacional do dispositivo móvel (ex.: "17.4", "14").
 * @property schemaVersionRequested Versão do protocolo de schema SDUI solicitada no cabeçalho UI-Schema-Version.
 */
data class ClientResponse(
    /**
     * Plataforma operacional do dispositivo solicitante.
     *
     * ### 1. O que faz
     * Informa a plataforma do sistema operacional do cliente ("ios" ou "android").
     *
     * ### 2. Para que serve
     * Permite auditar se o pipeline de composição aplicou as regras de isolamento e os seletores de spec da plataforma correta.
     *
     * ### 3. Como funciona
     * Extraído do cabeçalho `Client-Platform` após validação estrita de allowlist na negociação.
     */
    val platform: String,

    /**
     * Versão semântica do aplicativo cliente (Eixo C).
     *
     * ### 1. O que faz
     * Informa a versão de lançamento do aplicativo instalada no dispositivo (ex.: "1.14.0").
     *
     * ### 2. Para que serve
     * Permite auditar as regras de elegibilidade e targeting aplicadas durante a seleção da spec servida.
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho `Client-Version` após validação de formato SemVer ordinal sem overflow numérico.
     */
    val appVersion: String,

    /**
     * Número de compilação do binário do aplicativo.
     *
     * ### 1. O que faz
     * Transporta o número de build sequencial do binário móvel (ex.: "1420").
     *
     * ### 2. Para que serve
     * Permite correlacionar o comportamento da interface com compilações específicas de release ou testes internos.
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho obrigatório `Client-Build`.
     */
    val build: String,

    /**
     * Versão do sistema operacional do dispositivo móvel.
     *
     * ### 1. O que faz
     * Informa a versão do SO em execução no dispositivo (ex.: "17.4", "14").
     *
     * ### 2. Para que serve
     * Permite verificar restrições de compatibilidade nativa de SO (como APIs de sistema mínimo suportadas pela spec).
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho `Client-OS-Version` ou preenchido com valor padrão seguro caso ausente.
     */
    val osVersion: String,

    /**
     * Versão do schema de protocolo SDUI solicitada na requisição (Eixo A).
     *
     * ### 1. O que faz
     * Informa qual versão de protocolo o cliente declarou suportar (ex.: "3").
     *
     * ### 2. Para que serve
     * Garante a conferência de que a negociação de versão do contrato HTTP ocorreu sem divergências.
     *
     * ### 3. Como funciona
     * Ecoado a partir do cabeçalho `UI-Schema-Version` após validação contra as versões de schema aceitas pelo BFF.
     */
    val schemaVersionRequested: String,
)
