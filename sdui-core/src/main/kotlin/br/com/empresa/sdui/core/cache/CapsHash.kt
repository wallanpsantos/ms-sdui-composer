package br.com.empresa.sdui.core.cache

import br.com.empresa.sdui.core.model.Capability
import java.security.MessageDigest

object CapsHash {
    fun sha256(capabilities: Collection<Capability>): String {
        val ordered = capabilities.map { it.wire() }.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

object SkeletonHash {
    fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
