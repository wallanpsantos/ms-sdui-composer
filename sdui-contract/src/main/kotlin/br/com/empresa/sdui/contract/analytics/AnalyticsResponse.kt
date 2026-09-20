package br.com.empresa.sdui.contract.analytics

/**
 * Evento de analytics da tela, pre-montado pelo servidor.
 *
 * Vem pronto no envelope para que toda plataforma dispare o mesmo evento com os mesmos campos:
 * a padronizacao fica no servidor, nao replicada em iOS e Android.
 */
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

/**
 * Evento de analytics de uma section, disparado quando ela aparece para o usuario.
 *
 * Amarra a exibicao a [specRevisionId], o que permite comparar metricas entre revisoes de spec
 * e avaliar um canary.
 */
data class SectionAnalyticsResponse(
    val event: String,
    val component: String,
    val componentVersion: Int,
    val slot: String,
    val sectionId: String,
    val specRevisionId: String,
)
