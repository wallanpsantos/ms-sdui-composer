package br.com.empresa.sdui.contract.screen

data class OmittedItemResponse(
    val id: String,
    val slot: String,
    val type: String,
    val typeVersion: Int,
    val reason: String,
)
