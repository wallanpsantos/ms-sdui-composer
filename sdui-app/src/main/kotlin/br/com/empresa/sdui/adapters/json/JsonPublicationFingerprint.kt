package br.com.empresa.sdui.adapters.json

import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.orchestrator.port.outbound.PublicationFingerprint
import tools.jackson.databind.SerializationFeature
import java.security.MessageDigest
import java.util.*

/** Canonicaliza mapas; o hash cobre identidade, targeting, props, actions e estrutura revisada. */
class JsonPublicationFingerprint : PublicationFingerprint {
    private val mapper = DomainJson.mapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    override fun of(spec: Spec, skeleton: Skeleton): String {
        // Publicar o mesmo skeleton em outro pedido nao muda seu conteudo revisado.
        val bytes = mapper.writeValueAsBytes(listOf(spec, skeleton.copy(status = SpecStatus.DRAFT)))
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
