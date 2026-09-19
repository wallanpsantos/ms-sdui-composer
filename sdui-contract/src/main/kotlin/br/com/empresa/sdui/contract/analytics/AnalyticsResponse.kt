package br.com.empresa.sdui.contract.analytics

data class ScreenAnalyticsResponse(
    val event: String,
    val surface: String,
    val platform: String,
    val experience: String,
    val schemaVersion: String,
    val specRevisionId: String,
    val sectionCount: Int,
    val fallback: Boolean,
)

data class SectionAnalyticsResponse(
    val event: String,
    val component: String,
    val componentVersion: Int,
    val slot: String,
    val sectionId: String,
    val specRevisionId: String,
)
