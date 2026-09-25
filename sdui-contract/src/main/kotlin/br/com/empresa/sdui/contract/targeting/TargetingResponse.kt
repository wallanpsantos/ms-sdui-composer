package br.com.empresa.sdui.contract.targeting

/**
 * Critérios de segmentação e faixa de versões atendidos pela especificação servida.
 *
 * ### 1. O que faz
 * Expõe as regras de targeting e compatibilidade (Eixo C) configuradas na especificação de tela selecionada.
 *
 * ### 2. Para que serve
 * Permite aos desenvolvedores e times de suporte compreenderem e diagnosticarem por que um determinado dispositivo
 * recebeu essa especificação de tela específica. Em conjunto com `ClientResponse`, torna o processo de resolução de
 * targeting completamente auditável e verificável.
 *
 * ### 3. Como funciona
 * Os valores deste objeto são extraídos diretamente da entidade de domínio `Targeting` vinculada à `Spec` selecionada
 * pelo orquestrador. Durante o runtime, o motor de seleção compara a versão do aplicativo cliente e de seu sistema
 * operacional contra [appVersionMin], [appVersionMax] e [osVersionMin], garantindo que apenas clientes qualificados
 * recebam a respectiva revisão.
 *
 * @property platform Plataforma operacional compatível com a especificação ("ios" ou "android").
 * @property appVersionMin Versão mínima de aplicativo exigida pela especificação (ex.: "1.10.0").
 * @property appVersionMax Versão máxima opcional de aplicativo suportada pela especificação.
 * @property osVersionMin Versão mínima opcional de sistema operacional exigida pela especificação.
 * @property schemaVersion Versão do protocolo de schema SDUI atendida pela especificação (Eixo A).
 * @property band Nome da coorte ou banda de publicação atribuída à especificação (ex.: "stable", "canary").
 */
data class TargetingResponse(
    /**
     * Plataforma elegível para a especificação.
     *
     * ### 1. O que faz
     * Declara a plataforma móvel à qual a especificação se destina ("ios" ou "android").
     *
     * ### 2. Para que serve
     * Garante o isolamento absoluto de targeting, impedindo que specs de iOS sejam selecionadas para Android e vice-versa.
     *
     * ### 3. Como funciona
     * Definido na spec publicada e validado no seletor de pointers da governança.
     */
    val platform: String,

    /**
     * Versão mínima de aplicativo suportada.
     *
     * ### 1. O que faz
     * Estabelece o piso de versão semântica do aplicativo cliente necessária para receber esta especificação.
     *
     * ### 2. Para que serve
     * Impede que aplicativos legados recebam especificações que dependem de componentes ou recursos indisponíveis.
     *
     * ### 3. Como funciona
     * Comparado contra o `Client-Version` enviado pelo cliente utilizando ordenação SemVer ordinal estrita.
     */
    val appVersionMin: String,

    /**
     * Versão máxima de aplicativo suportada.
     *
     * ### 1. O que faz
     * Estabelece o teto opcional de versão de aplicativo suportada por esta especificação.
     *
     * ### 2. Para que serve
     * Permite direcionar especificações legadas exclusivamente para versões antigas de aplicativo durante migrações.
     *
     * ### 3. Como funciona
     * Campo opcional; se preenchido, rejeita clientes com versão estritamente superior ao teto configurado.
     */
    val appVersionMax: String? = null,

    /**
     * Versão mínima de sistema operacional exigida.
     *
     * ### 1. O que faz
     * Informa a versão mínima do sistema operacional móvel requerida pela especificação (ex.: "16.0").
     *
     * ### 2. Para que serve
     * Assegura que recursos avançados de SO necessários para certas visualizações nativas estejam presentes.
     *
     * ### 3. Como funciona
     * Confrontado com o `osVersion` declarado no cabeçalho do cliente durante a etapa de seleção.
     */
    val osVersionMin: String? = null,

    /**
     * Versão do protocolo de schema SDUI da especificação.
     *
     * ### 1. O que faz
     * Declara a versão do contrato de protocolo de envelope que a especificação atende (ex.: "3").
     *
     * ### 2. Para que serve
     * Valida que a especificação é compatível com a versão negociada no Eixo A da requisição.
     *
     * ### 3. Como funciona
     * Comparado com a versão solicitada no cabeçalho `UI-Schema-Version`.
     */
    val schemaVersion: String,

    /**
     * Nome da coorte ou banda de publicação.
     *
     * ### 1. O que faz
     * Nomeia a faixa de distribuição da especificação servida (ex.: "stable", "canary").
     *
     * ### 2. Para que serve
     * Permite identificar instantaneamente se a resposta foi originada de um release geral ou de um rollout canário.
     *
     * ### 3. Como funciona
     * Configurado no registro de publicação do pointer de governança que associou a spec à surface.
     */
    val band: String,
)
