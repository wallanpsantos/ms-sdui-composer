package br.com.empresa.sdui.contract.client

/**
 * Eco do que o cliente declarou nos headers de negociacao.
 *
 * Devolvido no envelope para tornar a composicao auditavel: ao investigar uma tela errada, da
 * para ver de que contexto o servidor partiu sem precisar reconstruir a requisicao.
 */
data class ClientResponse(
    val platform: String,
    val appVersion: String,
    val build: String,
    val osVersion: String,
    val schemaVersionRequested: String,
)
