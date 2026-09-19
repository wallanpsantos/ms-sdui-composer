package br.com.empresa.sdui.core.model

data class Actor(
    val id: String,
    val role: ActorRole,
)

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

data class ContextViolation(
    val header: String,
    val reason: String,
)

sealed interface ContextValidation {
    data class Valid(val context: ClientContext) : ContextValidation
    data class Invalid(val violations: List<ContextViolation>) : ContextValidation
}

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
