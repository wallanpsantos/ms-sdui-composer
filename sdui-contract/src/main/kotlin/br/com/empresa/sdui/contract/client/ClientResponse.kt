package br.com.empresa.sdui.contract.client

data class ClientResponse(
    val platform: String,
    val appVersion: String,
    val build: String,
    val osVersion: String,
    val schemaVersionRequested: String,
)
