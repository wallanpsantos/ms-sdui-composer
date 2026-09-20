package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.orchestrator.compose.HydrationContext
import br.com.empresa.sdui.orchestrator.compose.HydrationResult
import br.com.empresa.sdui.orchestrator.compose.SectionHydrator

/**
 * Hidratador padrao: entrega as props como vieram do spec.
 *
 * Atende qualquer tipo e serve de fallback quando nenhum hidratador especifico declara suporte.
 * No MVP o conteudo da home ja vem completo no spec, entao este e o caminho normal, nao uma
 * degradacao.
 */
class PassThroughHydrator : SectionHydrator {
    override fun supports(type: String, typeVersion: Int): Boolean = true

    override fun hydrate(context: HydrationContext, section: Section): HydrationResult =
        HydrationResult.Ok(section.props)
}
