package br.com.empresa.sdui.core.model

data class SlotDefinition(
    val id: String,
    val layout: SlotLayout,
    val title: String? = null,
    val maxInstances: Int,
    val allowedTypes: List<String>,
    val required: Boolean,
)

data class Skeleton(
    val skeletonId: String,
    val revision: Int,
    val surface: String,
    val layout: String,
    val slots: List<SlotDefinition>,
    val status: SpecStatus,
) {
    fun slot(id: String): SlotDefinition? = slots.firstOrNull { it.id == id }

    fun wireSlots(): List<SlotDefinition> = slots
}
