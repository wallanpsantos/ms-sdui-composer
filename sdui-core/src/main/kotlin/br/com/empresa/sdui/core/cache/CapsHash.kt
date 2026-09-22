package br.com.empresa.sdui.core.cache

import br.com.empresa.sdui.core.model.Capability
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Formatador hex compartilhado: imutavel e seguro para uso concorrente. Evita uma chamada de
 * String.format por byte do digest, que acontecia a cada requisicao no caminho de composicao.
 */
private val HEX: HexFormat = HexFormat.of()

/**
 * Resume um conjunto de capabilities num hash estavel, para compor a chave de cache.
 *
 * Ordena antes de aplicar o digest: dois clientes com as mesmas capabilities em ordem diferente
 * precisam cair na mesma entrada de cache, senao o cache fragmenta sem motivo.
 */
object CapsHash {
    fun sha256(capabilities: Collection<Capability>): String {
        val ordered = capabilities.map { it.wire() }.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray(Charsets.UTF_8))
        return HEX.formatHex(digest)
    }
}
