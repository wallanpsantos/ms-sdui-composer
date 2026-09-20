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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Converte a arvore do dominio no contrato JSON publicado.
 *
 * A fronteira que mantem o modelo interno livre para evoluir sem mexer no que iOS e Android
 * consomem: qualquer renomeacao ou reorganizacao de dominio para aqui. Tambem e onde as props,
 * que sao mapa livre, viram JsonNode preservando os tipos originais.
 */
class ScreenResponseMapper(
    private val mapper: JsonMapper,
) {
    fun toResponse(screen: ComposedScreen): ScreenResponse {
        // Offset fixo por exigencia do contrato: a fixture canonica publica generatedAt em
        // -03:00 (ver docs/artifacts/contrato-sdui-home-definitivo.json). O Brasil nao observa
        // horario de verao desde 2019, entao o valor e constante; mudar para UTC seria quebra de
        // contrato com os clientes moveis.
        val generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            screen.generatedAt.atOffset(ZoneOffset.of("-03:00")),
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
                    event = "sdui_home_composed",
                    surface = screen.surface,
                    platform = screen.platform.wire(),
                    experience = screen.experience,
                    schemaVersion = screen.schemaVersion,
                    specRevisionId = screen.specRevisionId,
                    sectionCount = screen.analyticsSectionCount(),
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

    private fun toNode(value: Any?): JsonNode {
        if (value == null) return mapper.nodeFactory.nullNode()
        return when (value) {
            is JsonNode -> value
            is String -> mapper.nodeFactory.stringNode(value)
            is Boolean -> mapper.nodeFactory.booleanNode(value)
            is Int -> mapper.nodeFactory.numberNode(value)
            is Long -> mapper.nodeFactory.numberNode(value)
            is Double -> mapper.nodeFactory.numberNode(value)
            is Float -> mapper.nodeFactory.numberNode(value)
            is List<*> -> {
                val array: ArrayNode = mapper.nodeFactory.arrayNode()
                value.forEach { array.add(toNode(it)) }
                array
            }

            is Map<*, *> -> {
                val obj: ObjectNode = mapper.nodeFactory.objectNode()
                value.forEach { (k, v) -> obj.set(k.toString(), toNode(v)) }
                obj
            }

            else -> mapper.nodeFactory.stringNode(value.toString())
        }
    }
}
