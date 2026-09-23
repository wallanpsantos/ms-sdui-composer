package br.com.empresa.sdui.core.model

/** Identidade publicavel, segura em chave de cache e ETag. */
object RevisionIds {
    private val FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9._:#-]{0,127}$")

    fun isValid(value: String): Boolean = FORMAT.matches(value) && !RedisKeys.containsUserId(value)
}
