package br.com.empresa.sdui.contract.screen

/**
 * Registro de seção omitida graciosamente da composição final de tela.
 *
 * ### 1. O que faz
 * Modela a identificação e a justificativa técnica de uma seção que fazia parte da especificação publicada,
 * mas não pôde ser entregue na resposta final ao cliente móvel.
 *
 * ### 2. Para que serve
 * Sustenta o princípio de degradação graciosa da escada de fallback (`ADR-007`). Em vez de falhar a requisição
 * inteira com HTTP 500 ou exibir telas em branco, o BFF compõe as seções saudáveis e reporta explicitamente
 * no envelope quais componentes foram suprimidos. Esse mecanismo permite que as equipes móveis e de observabilidade
 * auditem a perda de conteúdo e monitorem taxas de obsolescência de versões sem qualquer ambiguidade.
 *
 * ### 3. Como funciona
 * Instanciado durante a fase de filtragem de capabilities (quando o cliente móvel não declara suporte à versão
 * exigida pelo componente — motivo `unsupported_type`) ou durante o estágio de hidratação assíncrona (quando a
 * chamada ao serviço upstream falha ou estoura o prazo tolerado em slots não obrigatórios — motivos `hydration_failed`
 * ou `hydration_timeout`).
 *
 * @property id Identificador da seção na especificação que foi omitida.
 * @property slot Nome do slot estrutural do esqueleto onde a seção residiria.
 * @property type Nome canônico do componente de UI que deixou de ser exibido.
 * @property typeVersion Versão de contrato do componente avaliado na omissão.
 * @property reason Código técnico padronizado descrevendo o motivo da omissão.
 */
data class OmittedItemResponse(
    /**
     * Identificador único da seção omitida.
     *
     * ### 1. O que faz
     * Informa o ID da seção presente na especificação que não foi incluída na árvore final.
     *
     * ### 2. Para que serve
     * Permite correlacionar a omissão diretamente com a linha de spec publicada no ambiente de governança.
     *
     * ### 3. Como funciona
     * Copiado do atributo `id` da seção original durante a execução das regras de filtragem ou hidratação.
     */
    val id: String,

    /**
     * Nome do slot de destino original da seção.
     *
     * ### 1. O que faz
     * Indica o slot estrutural do esqueleto que deixou de receber esta seção.
     *
     * ### 2. Para que serve
     * Permite aos desenvolvedores móveis entenderem qual região visual da interface foi afetada pela omissão.
     *
     * ### 3. Como funciona
     * Transporta o valor de `slot` configurado na especificação de origem.
     */
    val slot: String,

    /**
     * Nome canônico do componente de UI omitido.
     *
     * ### 1. O que faz
     * Nomeia o componente de interface que não foi renderizado (ex.: "credit_offer", "coverage_card").
     *
     * ### 2. Para que serve
     * Permite agrupar métricas de falha ou incompatibilidade por tipo de componente em dashboards de BI e telemetria.
     *
     * ### 3. Como funciona
     * Preenchido com o tipo formal do componente registrado no catálogo da aplicação.
     */
    val type: String,

    /**
     * Versão do contrato do componente omitido.
     *
     * ### 1. O que faz
     * Especifica a versão inteira do contrato do componente avaliado no momento da omissão.
     *
     * ### 2. Para que serve
     * Permite identificar se a omissão ocorreu porque o aplicativo do usuário está em versão desatualizada (Eixo B).
     *
     * ### 3. Como funciona
     * Copiado do `typeVersion` associado à seção na especificação da tela.
     */
    val typeVersion: Int,

    /**
     * Justificativa técnica padronizada da omissão.
     *
     * ### 1. O que faz
     * Transporta o código controlado que fundamenta a exclusão da seção da composição final.
     *
     * ### 2. Para que serve
     * Permite que clientes e analisadores de telemetria processem o motivo de forma programática, sem parsing de texto livre.
     *
     * ### 3. Como funciona
     * Preenchido a partir do vocabulário fechado de omissões do BFF:
     * - `unsupported_type`: O cliente não possui a capability `type@typeVersion` necessária;
     * - `hydration_failed`: A chamada ao serviço de dados upstream falhou com erro;
     * - `hydration_timeout`: A hidratação assíncrona excedeu o tempo limite configurado no bulkhead.
     */
    val reason: String,
)
