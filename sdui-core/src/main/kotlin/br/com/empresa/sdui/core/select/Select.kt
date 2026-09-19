package br.com.empresa.sdui.core.select

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import java.time.Instant

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
                    spec.surface == "home" &&
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
            .sortedWith(
                compareByDescending<Spec> { it.targeting.priority }
                    .thenByDescending { it.publishedAt ?: Instant.EPOCH },
            )
            .firstOrNull()
    }

    fun neverCrossesPlatform(spec: Spec, platform: ClientPlatform): Boolean = spec.platform == platform
}
