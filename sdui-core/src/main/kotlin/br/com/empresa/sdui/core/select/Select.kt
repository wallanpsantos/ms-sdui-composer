package br.com.empresa.sdui.core.select

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import java.time.Instant

/**
 * Segundo passo: escolhe qual revisao publicada serve este cliente.
 *
 * Prefere a revisao apontada pelo pointer do canal; se ela nao atende o contexto, procura entre as
 * publicadas a de maior prioridade e, no empate, a mais recente. Nunca cruza plataforma. Devolver
 * null e resultado legitimo — significa que nenhuma revisao atende este cliente, e o chamador cai
 * para a escada de fallback.
 */
object Select {
    fun select(
        pointer: Pointer?,
        candidates: List<Spec>,
        context: ClientContext,
        effectiveCaps: Set<Capability>,
        channel: Channel,
    ): Spec? {
        // `channel` is the request channel already bound by the pointer the caller loaded.
        // Spec.channel is authorship metadata; promotion reuses a published revision as-is.
        val published = candidates.filter { spec ->
            spec.status == SpecStatus.PUBLISHED &&
                    spec.surface == MvpCatalog.SURFACE_HOME &&
                    spec.platform == context.platform
        }
        val pointed = pointer
            ?.takeIf { it.channel == channel && it.platform == context.platform }
            ?.specRevisionId
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
}
