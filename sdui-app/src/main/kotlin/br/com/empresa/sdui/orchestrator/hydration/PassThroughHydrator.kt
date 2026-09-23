package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Section

/**
 * Hidratador padrao: entrega as props como vieram do spec.
 *
 * Atende qualquer tipo e serve de fallback quando nenhum hidratador especifico declara suporte.
 * No MVP o conteudo ja vem completo no spec, entao este e o caminho normal, nao uma degradacao.
 * Nao espera nada, por isso roda na thread da requisicao em vez de ir para o fan-out.
 */
class PassThroughHydrator : SectionHydrator {
    override fun supports(type: String, typeVersion: Int): Boolean = true

    override fun hydrate(context: HydrationContext, section: Section): HydrationResult =
        HydrationResult.Ok(section.props)

    override val performsIo: Boolean = false
}
