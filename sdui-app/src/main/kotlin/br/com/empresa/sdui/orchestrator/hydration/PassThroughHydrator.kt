package br.com.empresa.sdui.orchestrator.hydration

import br.com.empresa.sdui.core.model.Section

/**
 * Hidratador padrão de repasse (pass-through) para componentes estáticos.
 *
 * ### 1. O que faz
 * Entrega as propriedades (`props`) da seção exatamente como foram declaradas na especificação ([Section.props]),
 * sem realizar modificações, consultas a serviços externos ou mutações.
 *
 * ### 2. Para que serve
 * Atua como o hidratador padrão para o catálogo Server-Driven UI e como contingência (fallback) quando
 * nenhum [SectionHydrator] especializado declara suporte a um determinado tipo de componente.
 *
 * ### 3. Como funciona
 * Declara suporte universal para qualquer tipo e versão de componente ([supports]), devolve as propriedades
 * originais empacotadas em [HydrationResult.Ok] no método [hydrate], e sinaliza [performsIo] como `false`,
 * permitindo que sua execução ocorra de forma síncrona diretamente na thread da requisição sem criar Virtual Threads.
 */
class PassThroughHydrator : SectionHydrator {
    /**
     * Declara suporte universal a qualquer tipo e versão de componente.
     *
     * ### 1. O que faz
     * Informa que este hidratador pode processar qualquer seção.
     *
     * ### 2. Para que serve
     * Garante que toda seção do catálogo possua ao menos um hidratador apto a processá-la.
     *
     * ### 3. Como funciona
     * Retorna invariavelmente `true`, independentemente dos parâmetros fornecidos.
     *
     * @param type Nome do tipo do componente.
     * @param typeVersion Versão do componente.
     * @return Sempre `true`.
     */
    override fun supports(type: String, typeVersion: Int): Boolean = true

    /**
     * Repassa as propriedades da seção sem modificações.
     *
     * ### 1. O que faz
     * Retorna o mapa de propriedades existente na própria seção.
     *
     * ### 2. Para que serve
     * Preserva os dados estáticos definidos na especificação visual da tela.
     *
     * ### 3. Como funciona
     * Retorna uma instância de [HydrationResult.Ok] contendo a referência original de [Section.props].
     *
     * @param context Contexto simplificado da requisição.
     * @param section Seção contendo as propriedades declaradas na especificação.
     * @return Instância de [HydrationResult.Ok] com as propriedades da seção.
     */
    override fun hydrate(context: HydrationContext, section: Section): HydrationResult =
        HydrationResult.Ok(section.props)

    /**
     * Indica ausência de operações de I/O ou dependências remotas.
     *
     * ### 1. O que faz
     * Informa ao coordenador que a execução é puramente computacional em memória.
     *
     * ### 2. Para que serve
     * Evita o overhead de agendamento e contexto de threads virtuais desnecessárias.
     *
     * ### 3. Como funciona
     * Sobrescreve a propriedade do contrato retornando a constante `false`.
     */
    override val performsIo: Boolean = false
}
