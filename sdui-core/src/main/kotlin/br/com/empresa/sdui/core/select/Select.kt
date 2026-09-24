package br.com.empresa.sdui.core.select

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import java.time.Instant

/**
 * Segundo passo: escolhe qual revisao publicada serve este cliente.
 *
 * Prefere a revisao apontada pelo pointer do canal; se ela nao atende o contexto, procura entre as
 * publicadas a de maior prioridade e, no empate, a mais recente. Nunca cruza plataforma nem
 * surface. Devolver null e resultado legitimo — significa que nenhuma revisao atende este
 * cliente, e o chamador cai para a escada de fallback.
 *
 * A surface nao tem valor padrao: um chamador que a esquecesse selecionaria a Home para outra
 * surface sem erro nenhum (ADR-020).
 */
object Select {
    fun select(
        pointer: Pointer?,
        candidates: List<Spec>,
        context: ClientContext,
        effectiveCaps: Set<Capability>,
        channel: Channel,
        surface: String,
    ): Spec? {
        // `channel` e o canal da requisicao, ja vinculado ao pointer que o chamador carregou.
        // Spec.channel e metadado de autoria: a promocao reaproveita a revisao publicada como esta.
        val published = candidates.filter { spec -> isEligible(spec, context, surface) }
        val pointed = pointedRevision(pointer, context, channel, surface)
            ?.let { id -> published.firstOrNull { it.specRevisionId == id } }
        if (pointed != null && pointed.matches(context, effectiveCaps)) {
            return pointed
        }
        return published
            .filter { it.matches(context, effectiveCaps) }
            .maxWithOrNull(
                compareBy<Spec> { it.targeting.priority }
                    .thenBy { it.publishedAt ?: Instant.EPOCH },
            )
    }

    /**
     * A revisao que o pointer indica para este cliente, ou null quando o pointer nao vale para o
     * canal, a plataforma ou a surface pedidos.
     */
    fun pointedRevision(pointer: Pointer?, context: ClientContext, channel: Channel, surface: String): String? =
        pointer
            ?.takeIf { it.channel == channel && it.platform == context.platform && it.surface == surface }
            ?.specRevisionId

    /**
     * Atalho de [select] para o caso comum: o spec apontado pelo pointer, ja carregado, atende o
     * cliente. Quando devolve o spec, [select] devolveria o mesmo spec com a lista inteira de
     * publicados — e isso que permite ao compose resolver a revisao sem listar os publicados.
     */
    fun pointedIfServes(
        pointer: Pointer?,
        pointedSpec: Spec?,
        context: ClientContext,
        effectiveCaps: Set<Capability>,
        channel: Channel,
        surface: String,
    ): Spec? {
        val spec = pointedSpec ?: return null
        val id = pointedRevision(pointer, context, channel, surface) ?: return null
        if (spec.specRevisionId != id || !isEligible(spec, context, surface)) return null
        return spec.takeIf { it.matches(context, effectiveCaps) }
    }

    private fun isEligible(spec: Spec, context: ClientContext, surface: String): Boolean =
        spec.status == SpecStatus.PUBLISHED && spec.surface == surface && spec.platform == context.platform
}
