package br.com.empresa.sdui.core.model

/**
 * Quem executa uma operacao administrativa, com o papel que reivindica.
 *
 * Hoje montado a partir dos headers Actor-Id e Actor-Role, sem autenticacao: o maker-checker so
 * separa responsabilidades entre operadores honestos e depende de o plano admin estar fechado
 * por rede.
 */
data class Actor(
    val id: String,
    val role: ActorRole,
)

/**
 * O cliente que pediu a tela, ja validado.
 *
 * Resultado de Negotiate e entrada de todo o resto do pipeline. Reune os tres eixos:
 * [schemaVersion] (A), [headerCapabilities] (B) e [platform] com [appVersion] e [build] (C).
 * Nao existe por construcao um contexto invalido — o que nao passou na negociacao vira
 * ContextValidation.Invalid.
 */
data class ClientContext(
    val platform: ClientPlatform,
    val appVersion: SemVer,
    val build: String,
    val osVersion: SemVer?,
    val osVersionRaw: String = osVersion?.toOsString() ?: "",
    val schemaVersion: String,
    val locale: String,
    val apiVersion: String,
    val headerCapabilities: List<Capability>,
    val channelHint: Channel = Channel.STABLE,
)

/** Um header de negociacao recusado, com o motivo. Vira detail da resposta 400. */
data class ContextViolation(
    val header: String,
    val reason: String,
)

/**
 * Saida de Negotiate: ou um contexto utilizavel, ou a lista do que esta errado.
 *
 * Modelada como tipo soma para que o chamador nao consiga seguir com um contexto invalido.
 */
sealed interface ContextValidation {
    data class Valid(val context: ClientContext) : ContextValidation
    data class Invalid(val violations: List<ContextViolation>) : ContextValidation
}

/**
 * Headers de negociacao como chegaram, ainda crus.
 *
 * Todos nulaveis de proposito: a ausencia de um header e um caso de validacao normal, nao uma
 * falha de binding. Existe para manter Negotiate independente de HTTP e testavel sem servidor.
 */
data class NegotiateHeaders(
    val uiSchemaVersion: String?,
    val clientPlatform: String?,
    val clientVersion: String?,
    val clientBuild: String?,
    val acceptLanguage: String?,
    val apiVersion: String?,
    val osVersion: String?,
    val componentCapabilities: String?,
    val channel: String? = null,
)
