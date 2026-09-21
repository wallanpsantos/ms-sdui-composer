package br.com.empresa.sdui.contract.screen

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Um slot do skeleton: posicao nomeada que o cliente reserva para receber sections.
 *
 * [layout] e semantico (fixed, shelf, list, pager, grid), nunca medida de tela: o servidor nao
 * envia pixel, breakpoint ou form factor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlotResponse(
    val id: String,
    val layout: String,
    val title: String? = null,
)

/**
 * Esqueleto da surface: a ordem dos slots que o cliente monta antes de encaixar as sections.
 *
 * Vem separado das sections para o cliente poder desenhar a estrutura e os estados de carregamento
 * sem depender do conteudo.
 */
data class SkeletonResponse(
    val id: String,
    val layout: String,
    val slots: List<SlotResponse>,
)
