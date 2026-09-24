package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricTags
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * A borda HTTP de leitura das surfaces: GET /v1/surfaces/home e GET /v1/surfaces/catalog.
 *
 * Um mapeamento literal por surface da allowlist, e nao `/v1/surfaces/{surface}`: nao ha como uma
 * surface desconhecida chegar ao pipeline, e o contrato da Home continua servido exatamente pelo
 * mesmo caminho, headers e corpo. Um caminho fora da lista recebe 404 do proprio roteamento, sem
 * criar chave de cache nem tag.
 *
 * Le os headers de negociacao (UI-Schema-Version, Client-Platform, Client-Version, Client-Build,
 * Accept-Language, API-Version, OS-Version, Component-Capabilities, SDUI-Channel e If-None-Match),
 * delega ao caso de uso e traduz cada desfecho ao status certo. Nenhum e obrigatorio no binding:
 * a ausencia e caso de validacao do Negotiate, que responde 400 com a lista completa.
 *
 * Responde com o JSON ja serializado em bytes, sem devolver objeto para o Spring serializar de
 * novo, e instrumenta tempo total, montagem do DTO, serializacao e tamanho do payload. ETag e
 * Cache-Control permitem ao cliente revalidar com If-None-Match e receber 304, e o Vary declara de
 * quais headers a resposta depende para nenhum cache intermediario servir a arvore de um contexto
 * a outro.
 */
@RestController
class SurfaceController(
    private val compose: ComposeScreenUseCase,
    private val mapper: ScreenResponseMapper,
    private val jsonMapper: JsonMapper,
    private val metrics: MetricsRecorder,
    private val trace: ComposeTraceContext,
) {
    @GetMapping(path = ["/v1/surfaces/home"], version = "1")
    fun home(@RequestHeader headers: HttpHeaders): ResponseEntity<*> = serve(Surfaces.HOME, headers)

    @GetMapping(path = ["/v1/surfaces/catalog"], version = "1")
    fun catalog(@RequestHeader headers: HttpHeaders): ResponseEntity<*> = serve(Surfaces.CATALOG, headers)

    private fun serve(surface: SurfaceDefinition, httpHeaders: HttpHeaders): ResponseEntity<*> {
        val started = System.nanoTime()
        val headers = negotiateHeaders(httpHeaders)
        // Versao exata do app vai para o contexto de log, nunca para tag de metrica. Os valores
        // ainda nao foram validados pelo Negotiate: entram truncados.
        trace.open(
            mapOf(
                "surface" to surface.id,
                "platform" to (headers.clientPlatform?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
                "schemaVersion" to (headers.uiSchemaVersion?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
                "appVersion" to (headers.clientVersion?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
            ),
        )
        // O desfecho comeca como erro e so e substituido depois que a composicao devolve: se algo
        // escapar como excecao, a amostra de tempo ainda sai, marcada pelo que de fato aconteceu.
        var outcome = OUTCOME_ERROR
        try {
            val result = compose.compose(
                ComposeRequest(
                    headers = headers,
                    ifNoneMatch = httpHeaders.joined(HttpHeaders.IF_NONE_MATCH),
                    surface = surface,
                ),
            )
            outcome = outcomeOf(result)
            return when (result) {
                is ComposeResult.InvalidHeaders -> ResponseEntity.badRequest().body(
                    ApiErrorResponse(
                        code = "INVALID_HEADERS",
                        message = "headers de negociacao invalidos",
                        details = result.violations.map { "${it.header}:${it.reason}" },
                    ),
                )

                // O `Retry-After` vem do caso de uso ja com jitter. Um valor constante aqui
                // mandaria a coorte inteira voltar no mesmo segundo e repetir o pico que causou a
                // recusa — a chave do limitador e plataforma e build, nao aparelho.
                is ComposeResult.RateLimited -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", result.retryAfterSeconds.toString())
                    .body(ApiErrorResponse("RATE_LIMITED", "rate limit excedido"))

                is ComposeResult.NotModified -> ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header("ETag", result.etag)
                    .header("Cache-Control", CACHE_CONTROL)
                    .header("Vary", VARY)
                    .build<Void>()

                is ComposeResult.Unavailable -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", result.retryAfterSeconds.toString())
                    .body(
                        ApiErrorResponse(
                            code = "COMPOSE_UNAVAILABLE",
                            message = "${surface.id} indisponivel",
                            details = listOf(result.reason.wire),
                        ),
                    )

                is ComposeResult.Success -> ok(result.screen)
            }
        } finally {
            metrics.recordNanos(
                MetricNames.COMPOSE_DURATION,
                System.nanoTime() - started,
                mapOf("surface" to surface.id, "outcome" to outcome),
            )
            trace.close()
        }
    }

    /**
     * Monta o DTO e serializa a arvore uma unica vez, direto para bytes. Os dois tempos sao
     * medidos em separado: o perfil de 2026-09-23 mostrou a serializacao em torno de quatro vezes
     * a montagem, e somados no mesmo timer eles escondiam onde esta o custo.
     */
    private fun ok(screen: ComposedScreen): ResponseEntity<ByteArray> {
        val metricTags = mapOf(
            "schemaVersion" to MetricTags.schema(screen.schemaVersion),
            "surface" to screen.surface,
            "platform" to screen.platform.wire(),
        )
        val mappingStarted = System.nanoTime()
        val body = mapper.toResponse(screen)
        val serializeStarted = System.nanoTime()
        metrics.recordNanos(MetricNames.MAPPING, serializeStarted - mappingStarted, metricTags)
        val json = jsonMapper.writeValueAsBytes(body)
        metrics.recordNanos(MetricNames.SERIALIZE, System.nanoTime() - serializeStarted, metricTags)
        metrics.recordBytes(MetricNames.PAYLOAD_BYTES, json.size.toLong(), metricTags)
        return ResponseEntity.ok()
            .header("ETag", screen.etag)
            .header("Cache-Control", CACHE_CONTROL)
            .header("Vary", VARY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
    }

    /**
     * Classifica o desfecho para a tag da metrica de tempo.
     *
     * Sem essa dimensao, a latencia de acerto de cache e a do caminho degradado caem no mesmo
     * histograma: o p99 melhora quando o cache esquenta e piora quando degrada, sem que se possa
     * separar as duas populacoes — que e exatamente a separacao necessaria num incidente.
     */
    private fun outcomeOf(result: ComposeResult): String = when (result) {
        is ComposeResult.Success -> when {
            result.screen.fallback -> OUTCOME_FALLBACK
            result.fromCache -> OUTCOME_HIT
            else -> OUTCOME_MISS
        }

        is ComposeResult.NotModified -> OUTCOME_NOT_MODIFIED
        is ComposeResult.InvalidHeaders -> OUTCOME_INVALID_HEADERS
        is ComposeResult.RateLimited -> OUTCOME_RATE_LIMITED
        is ComposeResult.Unavailable -> OUTCOME_UNAVAILABLE
    }

    private companion object {
        const val MAX_LOGGED_HEADER_LENGTH: Int = 32
        const val CACHE_CONTROL: String = "private, max-age=60"
        const val VARY: String =
            "API-Version, UI-Schema-Version, Client-Platform, Client-Version, Client-Build, Component-Capabilities"

        const val OUTCOME_HIT: String = "hit"
        const val OUTCOME_MISS: String = "miss"
        const val OUTCOME_FALLBACK: String = "fallback"
        const val OUTCOME_NOT_MODIFIED: String = "not_modified"
        const val OUTCOME_INVALID_HEADERS: String = "invalid_headers"
        const val OUTCOME_RATE_LIMITED: String = "rate_limited"
        const val OUTCOME_UNAVAILABLE: String = "unavailable"
        const val OUTCOME_ERROR: String = "error"

        /** Os headers de negociacao como chegaram, iguais para toda surface. */
        fun negotiateHeaders(headers: HttpHeaders): NegotiateHeaders = NegotiateHeaders(
            uiSchemaVersion = headers.joined("UI-Schema-Version"),
            clientPlatform = headers.joined("Client-Platform"),
            clientVersion = headers.joined("Client-Version"),
            clientBuild = headers.joined("Client-Build"),
            acceptLanguage = headers.joined(HttpHeaders.ACCEPT_LANGUAGE),
            apiVersion = headers.joined("API-Version"),
            osVersion = headers.joined("OS-Version"),
            componentCapabilities = headers.joined("Component-Capabilities"),
            channel = headers.joined("SDUI-Channel"),
        )

        /**
         * O valor do header, ou null se ausente. Linhas repetidas saem unidas por virgula, como o
         * binding de `@RequestHeader` num parametro String fazia.
         */
        fun HttpHeaders.joined(name: String): String? = get(name)?.joinToString(",")
    }
}
