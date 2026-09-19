package br.com.empresa.sdui.contract.targeting

data class TargetingResponse(
    val platform: String,
    val appVersionMin: String,
    val appVersionMax: String? = null,
    val osVersionMin: String? = null,
    val schemaVersion: String,
    val band: String,
)
