package br.com.empresa.sdui.contract.targeting

/**
 * Faixa de clientes que o spec servido atende.
 *
 * Expoe o criterio do eixo C — versao minima e maxima do app, versao minima de SO — mais a [band]
 * que nomeia o publico. Serve para diagnostico: junto com ClientResponse mostra por que este
 * cliente recebeu esta revisao e nao outra.
 */
data class TargetingResponse(
    val platform: String,
    val appVersionMin: String,
    val appVersionMax: String? = null,
    val osVersionMin: String? = null,
    val schemaVersion: String,
    val band: String,
)
