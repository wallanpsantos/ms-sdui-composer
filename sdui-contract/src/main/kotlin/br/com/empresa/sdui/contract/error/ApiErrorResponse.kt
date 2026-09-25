package br.com.empresa.sdui.contract.error

/**
 * Resposta padronizada de erro da API para todos os endpoints do BFF.
 *
 * ### 1. O que faz
 * Encapsula as informações de diagnóstico e detalhamento técnico de requisições que resultam em erros HTTP (4xx e 5xx).
 *
 * ### 2. Para que serve
 * Estabelece um contrato único e determinístico para tratamento de falhas em clientes nativos (iOS e Android),
 * alinhado aos princípios do RFC 7807 (Problem Details). Permite que a lógica dos aplicativos tome decisões automáticas
 * (como exibir fluxos de erro, forçar atualização de app ou disparar novas tentativas) através de [code], enquanto
 * oferece mensagens explicativas em [message] e [details] para desenvolvedores, sem nunca vazar stack traces, nomes
 * de tabelas de banco de dados, segredos ou dados pessoais regulados (PII).
 *
 * ### 3. Como funciona
 * Instanciado e serializado pelo manipulador global de exceções (`ApiExceptionHandler`) ou pelos filtros de segurança
 * e limitação de taxa HTTP. Quando uma validação de contrato ou cabeçalho falha na camada de negociação, ou quando um
 * conflito de concorrência ocorre na governança (HTTP 409), esta estrutura é emitida com o status HTTP correspondente.
 *
 * @property code Código de erro estável para interpretação programática do cliente (ex.: "BAD_REQUEST", "UNSUPPORTED_SCHEMA_VERSION").
 * @property message Mensagem legível para humanos descrevendo a natureza da falha sem expor detalhes internos.
 * @property details Lista de inconsistências pontuais ou violações de campos encontradas na requisição.
 */
data class ApiErrorResponse(
    /**
     * Código de erro programático e imutável.
     *
     * ### 1. O que faz
     * Fornece um identificador textual fixo para classificação da falha (ex.: "DEPENDENCY_TIMEOUT", "RATE_LIMIT_EXCEEDED").
     *
     * ### 2. Para que serve
     * Permite que os aplicativos móveis implementem tratamento de erros condicional sem depender de parsing de mensagens de texto.
     *
     * ### 3. Como funciona
     * Originado de constantes e enumerações controladas do domínio, permanecendo estável entre diferentes versões do BFF.
     */
    val code: String,

    /**
     * Mensagem textual legível para humanos.
     *
     * ### 1. O que faz
     * Apresenta uma descrição clara e objetiva sobre o motivo pelo qual a operação falhou.
     *
     * ### 2. Para que serve
     * Facilita a depuração em tempo de desenvolvimento e análise de chamadas de rede em ambiente de homologação e produção.
     *
     * ### 3. Como funciona
     * Redigida em português técnico claro, sanitizada para não expor caminhos de arquivo, classes internas ou dados sensíveis.
     */
    val message: String,

    /**
     * Lista detalhada de violações ou causas pontuais do erro.
     *
     * ### 1. O que faz
     * Agrupa apontamentos específicos sobre cabeçalhos inválidos, campos ausentes ou restrições violadas.
     *
     * ### 2. Para que serve
     * Permite ao desenvolvedor cliente corrigir pontualmente os parâmetros defeituosos da requisição HTTP.
     *
     * ### 3. Como funciona
     * Preenchido com uma coleção de strings descritivas durante a validação de formulários, headers ou regras de governança.
     */
    val details: List<String> = emptyList(),
)
