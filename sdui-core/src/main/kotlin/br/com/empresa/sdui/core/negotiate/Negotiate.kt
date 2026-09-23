package br.com.empresa.sdui.core.negotiate

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ContextValidation
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SemVer

/**
 * Primeiro passo do pipeline: transforma headers crus em um [ClientContext] valido.
 *
 * Acumula todas as violacoes antes de decidir, em vez de parar na primeira, para o cliente
 * corrigir tudo de uma vez. Nada aqui lanca excecao por entrada malformada — header invalido e
 * comportamento esperado e vira 400, nunca 500.
 */
object Negotiate {
    private val BUILD = Regex("""^\d{1,10}$""")
    private val LOCALE = Regex("""^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$""")
    private val SCHEMA = Regex("""^\d{1,10}$""")

    fun negotiate(headers: NegotiateHeaders): ContextValidation {
        val violations = mutableListOf<ContextViolation>()

        fun requireHeader(name: String, value: String?): String? {
            if (value.isNullOrBlank()) {
                violations += ContextViolation(name, "required")
                return null
            }
            return value.trim()
        }

        val schema = requireHeader("UI-Schema-Version", headers.uiSchemaVersion)
        if (schema != null && !SCHEMA.matches(schema)) {
            violations += ContextViolation("UI-Schema-Version", "invalid")
        } else if (schema != null && schema !in MvpCatalog.SUPPORTED_SCHEMA_VERSIONS) {
            violations += ContextViolation("UI-Schema-Version", "unsupported")
        }

        val platformRaw = requireHeader("Client-Platform", headers.clientPlatform)
        val platform = ClientPlatform.parse(platformRaw)
        if (platformRaw != null && platform == null) {
            violations += ContextViolation("Client-Platform", "invalid")
        }

        val versionRaw = requireHeader("Client-Version", headers.clientVersion)
        val appVersion = SemVer.parseThreePart(versionRaw)
        if (versionRaw != null && appVersion == null) {
            violations += ContextViolation("Client-Version", "invalid")
        }

        val build = requireHeader("Client-Build", headers.clientBuild)
        if (build != null && !BUILD.matches(build)) {
            violations += ContextViolation("Client-Build", "invalid")
        }

        val language = requireHeader("Accept-Language", headers.acceptLanguage)
        val locale = language?.let { primaryLocale(it) }
        if (language != null && (locale == null || !LOCALE.matches(locale))) {
            violations += ContextViolation("Accept-Language", "invalid")
        }

        val apiVersion = requireHeader("API-Version", headers.apiVersion)
        if (apiVersion != null && apiVersion != "1") {
            violations += ContextViolation("API-Version", "unsupported")
        }

        val osRaw = headers.osVersion?.trim()?.takeIf { it.isNotEmpty() }
        val osVersion = osRaw?.let { SemVer.parse(it) }
        if (osRaw != null && osVersion == null) {
            violations += ContextViolation("OS-Version", "invalid")
        }

        if (platform == null || appVersion == null || build == null || schema == null || locale == null || apiVersion == null || violations.isNotEmpty()) {
            return ContextValidation.Invalid(violations)
        }

        return ContextValidation.Valid(
            ClientContext(
                platform = platform,
                appVersion = appVersion,
                build = build,
                osVersion = osVersion,
                osVersionRaw = osRaw ?: "",
                schemaVersion = schema,
                locale = locale,
                apiVersion = apiVersion,
                headerCapabilities = Capability.parseList(headers.componentCapabilities),
                channelHint = Channel.parse(headers.channel),
            ),
        )
    }

    private fun primaryLocale(acceptLanguage: String): String? {
        val primary = acceptLanguage.split(",").firstOrNull()?.trim()?.substringBefore(";")?.trim()
        return primary?.takeIf { it.isNotEmpty() }
    }
}
