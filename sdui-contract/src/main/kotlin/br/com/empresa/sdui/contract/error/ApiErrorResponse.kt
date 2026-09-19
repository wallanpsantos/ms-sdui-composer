package br.com.empresa.sdui.contract.error

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
)
