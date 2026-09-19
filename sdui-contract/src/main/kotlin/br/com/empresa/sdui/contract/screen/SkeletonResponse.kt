package br.com.empresa.sdui.contract.screen

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
