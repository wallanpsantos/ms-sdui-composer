package br.com.empresa.sdui.contract.error

/**
 * Corpo de erro da API, unico para todos os endpoints.
 *
 * [code] e estavel e destinado a logica do cliente; [message] e texto humano e pode mudar;
 * [details] traz o que ajuda a corrigir a requisicao, como os headers invalidos. Nenhum dos
 * tres carrega stack trace, identificador interno ou dado do usuario.
 */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
)
