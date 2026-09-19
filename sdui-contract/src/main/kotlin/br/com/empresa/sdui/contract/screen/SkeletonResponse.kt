package br.com.empresa.sdui.contract.screen

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlotResponse(
    val id: String,
    val layout: String,
    val title: String? = null,
)

data class SkeletonResponse(
    val id: String,
    val layout: String,
    val slots: List<SlotResponse>,
)
