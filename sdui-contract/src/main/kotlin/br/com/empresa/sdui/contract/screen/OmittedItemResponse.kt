package br.com.empresa.sdui.contract.screen

/**
 * Uma section que ficou de fora, com o motivo.
 *
 * A omissao e graciosa por decisao de arquitetura (ADR-007): em vez de falhar a resposta inteira,
 * o servidor entrega o que da e declara o que faltou. [reason] usa o vocabulario fechado de
 * OmittedReason — unsupported_type, hydration_failed ou hydration_timeout — para o cliente poder
 * instrumentar sem interpretar texto livre.
 */
data class OmittedItemResponse(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val reason: String,
)
