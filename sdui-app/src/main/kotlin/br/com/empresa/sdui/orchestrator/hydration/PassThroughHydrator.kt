package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.orchestrator.compose.HydrationContext
import br.com.empresa.sdui.orchestrator.compose.HydrationResult
import br.com.empresa.sdui.orchestrator.compose.SectionHydrator

class PassThroughHydrator : SectionHydrator {
    override fun supports(type: String, typeVersion: Int): Boolean = true

    override fun hydrate(context: HydrationContext, section: Section): HydrationResult =
        HydrationResult.Ok(section.props)
}
