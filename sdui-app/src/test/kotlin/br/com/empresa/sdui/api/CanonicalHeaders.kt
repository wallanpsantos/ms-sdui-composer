package br.com.empresa.sdui.api

object CanonicalHeaders {
    const val SCHEMA: String = "3"
    const val PLATFORM_IOS: String = "ios"
    const val PLATFORM_ANDROID: String = "android"
    const val VERSION: String = "8.14.2"
    const val BUILD: String = "81420"
    const val LANGUAGE: String = "pt-BR"
    const val API: String = "1"
    const val OS: String = "18.1"

    fun ios(): Map<String, String> = mapOf(
        "UI-Schema-Version" to SCHEMA,
        "Client-Platform" to PLATFORM_IOS,
        "Client-Version" to VERSION,
        "Client-Build" to BUILD,
        "Accept-Language" to LANGUAGE,
        "API-Version" to API,
        "OS-Version" to OS,
    )

    fun android(): Map<String, String> = ios() + ("Client-Platform" to PLATFORM_ANDROID)
}
