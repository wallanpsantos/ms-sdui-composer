package br.com.empresa.sdui.core.model

/**
 * Um slot do skeleton e as regras de quem pode ocupa-lo.
 *
 * [allowedTypes] e [maxInstances] sao validados na publicacao, nao em runtime. [required] marca
 * slot portante — header e accounts —, cujo vazio derruba a composicao em vez de gerar omissao.
 * [layout] e semantico; o skeleton nunca descreve medida de tela.
 */
data class SlotDefinition(
    val id: String,
    val layout: SlotLayout,
    val title: String? = null,
    val maxInstances: Int,
    val allowedTypes: List<String>,
    val required: Boolean,
    val allowedLayouts: List<SlotLayout> = MvpCatalog.DEFAULT_ALLOWED_LAYOUTS[id] ?: listOf(layout),
)

/**
 * A estrutura da surface: quais slots existem e em que ordem.
 *
 * Versionado a parte do spec para que a mesma estrutura sirva a varias revisoes de conteudo. A
 * ordem declarada aqui e a ordem final das sections na resposta — Filter ordena por ela.
 */
data class Skeleton(
    val skeletonId: String,
    val revision: Int,
    val surface: String,
    val layout: String,
    val slots: List<SlotDefinition>,
    val status: SpecStatus,
) {
    val slotOrder: Map<String, Int> = slots.mapIndexed { index, slot -> slot.id to index }.toMap()
    val requiredSlotIds: Set<String> = slots.filter { it.required }.map { it.id }.toSet()

    fun slot(id: String): SlotDefinition? = slots.firstOrNull { it.id == id }

    fun wireSlots(): List<SlotDefinition> = slots
}
