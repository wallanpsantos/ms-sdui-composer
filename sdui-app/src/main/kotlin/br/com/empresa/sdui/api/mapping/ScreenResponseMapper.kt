package br.com.empresa.sdui.api.mapping

import br.com.empresa.sdui.contract.analytics.ScreenAnalyticsResponse
import br.com.empresa.sdui.contract.analytics.SectionAnalyticsResponse
import br.com.empresa.sdui.contract.client.ClientResponse
import br.com.empresa.sdui.contract.component.ActionPayload
import br.com.empresa.sdui.contract.component.ActionResponse
import br.com.empresa.sdui.contract.screen.OmittedItemResponse
import br.com.empresa.sdui.contract.screen.ScreenEnvelope
import br.com.empresa.sdui.contract.screen.ScreenResponse
import br.com.empresa.sdui.contract.screen.SectionResponse
import br.com.empresa.sdui.contract.screen.SkeletonResponse
import br.com.empresa.sdui.contract.screen.SlotResponse
import br.com.empresa.sdui.contract.targeting.TargetingResponse
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.Surfaces
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Mapeador de fronteira responsavel por traduzir a arvore de dominio no contrato JSON publicado.
 *
 * ### 1. O que faz
 * Converte a entidade de dominio [ComposedScreen] no envelope formal [ScreenResponse], mapeando
 * propriedades de rastreamento, envelope, metadados do cliente, targeting, skeleton e colecao de secoes.
 *
 * ### 2. Para que serve
 * Funciona como a fronteira de isolamento anti-corrupcao que preserva o contrato estrito consumido
 * pelos clientes moveis iOS e Android, permitindo que as entidades e regras internas do `sdui-core`
 * evoluam livremente sem quebrar a serializacao Jackson 3 da camada externa.
 *
 * ### 3. Como funciona
 * - **Formatacao de Data Canônica:** Formata o instante [ComposedScreen.generatedAt] em ISO-8601 com
 *   o deslocamento de fuso horario fixo `-03:00` ([BRASIL_OFFSET]), atendendo rigorosamente a fixture
 *   canônica sem depender de horario de verao.
 * - **Projecao Segura de Props:** Transforma mapas heterogeneos de propriedades de componentes em arvores
 *   [JsonNode] Jackson tipadas via [toNode], com protecao contra estouro de pilha por recursao excessiva.
 * - **Telemetria de Superficie:** Vincula o evento analitico padronizado da surface correspondente via [analyticsEvent].
 *
 * @property mapper Instancia de [JsonMapper] utilizada para a criacao de nos e navegacao no grafo JSON.
 */
class ScreenResponseMapper(
    private val mapper: JsonMapper,
) {
    /**
     * Converte o modelo de tela do dominio no DTO de resposta contratual.
     *
     * ### 1. O que faz
     * Traduz uma instancia de [ComposedScreen] no DTO [ScreenResponse], desmembrando suas estruturas
     * em envelope, skeleton e secoes.
     *
     * ### 2. Para que serve
     * Produz o objeto contratual definitivo compativel com a especificacao JSON Server-Driven UI v3.
     *
     * ### 3. Como funciona
     * 1. Formata a data de geracao para o formato ISO com timezone `-03:00`.
     * 2. Monta o [ScreenEnvelope] agregando identificadores, versoes, fallback, itens omitidos,
     *    contexto do cliente, targeting aplicado e evento analitico da surface.
     * 3. Mapeia o [SkeletonResponse] com a lista ordenada de slots e seus respectivos layouts.
     * 4. Mapeia a lista de [SectionResponse], convertendo propriedades dinâmicas com [toNode], acoes
     *    interativas em [ActionResponse] e evento analitico de impressao da secao (`sdui_section_shown`).
     *
     * @param screen Objeto de dominio contendo a tela composta pelo pipeline.
     * @return O DTO [ScreenResponse] pronto para serializacao JSON.
     */
    fun toResponse(screen: ComposedScreen): ScreenResponse {
        // Offset fixo por exigencia do contrato: a fixture canonica publica generatedAt em
        // -03:00 (ver fixture contrato-sdui-home-definitivo.json). O Brasil nao observa
        // horario de verao desde 2019, entao o valor e constante; mudar para UTC seria quebra de
        // contrato com os clientes moveis.
        val generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            screen.generatedAt.atOffset(BRASIL_OFFSET),
        )
        return ScreenResponse(
            envelope = ScreenEnvelope(
                surface = screen.surface,
                platform = screen.platform.wire(),
                schemaVersion = screen.schemaVersion,
                specRevisionId = screen.specRevisionId,
                skeletonId = screen.skeletonId,
                skeletonHash = screen.skeletonHash,
                etag = screen.etag,
                generatedAt = generatedAt,
                locale = screen.locale,
                channel = screen.channel.wire(),
                fallback = screen.fallback,
                fallbackReason = screen.fallbackReason.wire,
                omitted = screen.omitted.map {
                    OmittedItemResponse(it.id, it.slot, it.type, it.typeVersion, it.reason.wire)
                },
                client = ClientResponse(
                    platform = screen.client.platform.wire(),
                    appVersion = screen.client.appVersion.toString(),
                    build = screen.client.build,
                    osVersion = screen.client.osVersionRaw,
                    schemaVersionRequested = screen.client.schemaVersion,
                ),
                targeting = TargetingResponse(
                    platform = screen.targeting.platform.wire(),
                    appVersionMin = screen.targeting.wireMin(),
                    appVersionMax = screen.targeting.wireMax(),
                    osVersionMin = screen.targeting.wireOsMin(),
                    schemaVersion = screen.schemaVersion,
                    band = screen.targeting.band,
                ),
                analytics = ScreenAnalyticsResponse(
                    event = analyticsEvent(screen.surface),
                    surface = screen.surface,
                    platform = screen.platform.wire(),
                    experience = screen.experience,
                    schemaVersion = screen.schemaVersion,
                    specRevisionId = screen.specRevisionId,
                    sectionCount = screen.sections.size,
                    fallback = screen.fallback,
                ),
            ),
            skeleton = SkeletonResponse(
                id = screen.skeleton.skeletonId,
                layout = screen.skeleton.layout,
                slots = screen.skeleton.slots.map { slot ->
                    SlotResponse(id = slot.id, layout = slot.layout.wire(), title = slot.title)
                },
            ),
            sections = screen.sections.map { section ->
                SectionResponse(
                    id = section.id,
                    slot = section.slot,
                    type = section.type,
                    typeVersion = section.typeVersion,
                    layout = section.layout,
                    props = toNode(section.props),
                    actions = section.actions.map { action ->
                        ActionResponse(
                            id = action.id,
                            type = action.type,
                            label = action.label,
                            payload = action.payload?.let { payload ->
                                ActionPayload(route = payload.route, sheet = payload.sheet)
                            },
                        )
                    },
                    analytics = SectionAnalyticsResponse(
                        event = "sdui_section_shown",
                        component = section.type,
                        componentVersion = section.typeVersion,
                        slot = section.slot,
                        sectionId = section.id,
                        specRevisionId = screen.specRevisionId,
                    ),
                )
            },
        )
    }

    /**
     * Determina o nome do evento analitico padrao disparado na exibicao da surface.
     *
     * ### 1. O que faz
     * Recupera o identificador do evento de composicao associado a surface informada.
     *
     * ### 2. Para que serve
     * Garante que eventos analiticos de tela (`sdui_home_composed`, etc.) sejam emitidos de forma consistente.
     *
     * ### 3. Como funciona
     * Consulta a allowlist [Surfaces] pelo identificador da [surface]. Se a surface nao for encontrada,
     * lanca um erro de estado ilegal, ja que nenhuma surface desconhecida deve ultrapassar a selecao.
     *
     * @param surface Identificador da surface (ex: `home`, `catalog`).
     * @return Nome padronizado do evento analitico no contrato.
     */
    private fun analyticsEvent(surface: String): String =
        Surfaces.find(surface)?.analyticsEvent ?: error("surface fora da allowlist: $surface")

    /**
     * Converte recursivamente qualquer valor primitivo, mapa ou colecao Kotlin em um [JsonNode] do Jackson.
     *
     * ### 1. O que faz
     * Percorre estruturas de dados aninhadas e produz os nos JSON tipados correspondentes.
     *
     * ### 2. Para que serve
     * Permite mapear as propriedades dinâmicas (`props`) dos componentes preservando rigorosamente
     * os tipos numericos, booleanos e textuais exigidos pelos clientes moveis.
     *
     * ### 3. Como funciona
     * - Trata valores nulos com [tools.jackson.databind.node.NullNode].
     * - Controla a profundidade de recursao via [depth]; caso exceda [MAX_RECURSION_DEPTH], interrompe e
     *   insere uma string marcadora `[truncated]` para blindar contra estouro de pilha (StackOverflowError).
     * - Preserva tipos numericos especiais como [BigInteger] e [BigDecimal] para evitar perdas de precisao.
     * - Mapeia [List] para [ArrayNode] e [Map] para [ObjectNode], processando recursivamente cada elemento.
     *
     * @param value Objeto de origem a ser convertido em no JSON.
     * @param depth Nivel de profundidade recursiva atual (iniciado em 0).
     * @return Instancia correspondente de [JsonNode].
     */
    private fun toNode(value: Any?, depth: Int = 0): JsonNode {
        if (value == null) return mapper.nodeFactory.nullNode()
        if (depth > MAX_RECURSION_DEPTH) return mapper.nodeFactory.stringNode("[truncated]")
        return when (value) {
            is JsonNode -> value
            is String -> mapper.nodeFactory.stringNode(value)
            is Boolean -> mapper.nodeFactory.booleanNode(value)
            is Int -> mapper.nodeFactory.numberNode(value)
            is Long -> mapper.nodeFactory.numberNode(value)
            is Double -> mapper.nodeFactory.numberNode(value)
            is Float -> mapper.nodeFactory.numberNode(value)
            // Inteiro alem de Long e decimal exato chegam assim pelo Jackson do admin e do adapter
            // persistente; no ramo generico virariam texto e o cliente receberia outro tipo.
            is BigInteger -> mapper.nodeFactory.numberNode(value)
            is BigDecimal -> mapper.nodeFactory.numberNode(value)
            is List<*> -> {
                val array: ArrayNode = mapper.nodeFactory.arrayNode()
                value.forEach { array.add(toNode(it, depth + 1)) }
                array
            }

            is Map<*, *> -> {
                val obj: ObjectNode = mapper.nodeFactory.objectNode()
                value.forEach { (k, v) -> obj.set(k.toString(), toNode(v, depth + 1)) }
                obj
            }

            else -> mapper.nodeFactory.stringNode(value.toString())
        }
    }

    /**
     * Constantes estaticas de configuracao do mapeador de respostas.
     *
     * ### 1. O que faz
     * Centraliza o deslocamento de fuso horario canônico e o limite de profundidade de parsing de props.
     *
     * ### 2. Para que serve
     * Padroniza restricoes de contrato e de protecao de memoria em um ponto unico.
     *
     * ### 3. Como funciona
     * Define [BRASIL_OFFSET] como constante `-03:00` e [MAX_RECURSION_DEPTH] como teto de 32 niveis.
     */
    private companion object {
        /** Deslocamento fixo de fuso horario do Brasil (-03:00) exigido pelo contrato das fixtures. */
        val BRASIL_OFFSET: ZoneOffset = ZoneOffset.of("-03:00")

        /** Profundidade maxima de recursao para prevencao de loops ciclicos ou estouro de pilha em props. */
        const val MAX_RECURSION_DEPTH: Int = 32
    }
}
