package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.orchestrator.compose.ComposeRequest
import br.com.empresa.sdui.orchestrator.compose.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * A borda HTTP da home: GET /v1/surfaces/home.
 *
 * Le os headers de negociacao, delega ao caso de uso e traduz cada desfecho ao status certo.
 * Responde com o JSON ja serializado em bytes, sem devolver objeto para o Spring serializar de
 * novo, e instrumenta tempo e tamanho do payload. ETag e Cache-Control permitem ao cliente
 * revalidar com If-None-Match e receber 304, e o Vary declara de quais headers a resposta depende
 * para nenhum cache intermediario servir a arvore de um contexto a outro.
 */
@RestController
class HomeController(
    private val compose: ComposeScreenUseCase,
    private val mapper: ScreenResponseMapper,
    private val jsonMapper: JsonMapper,
    private val metrics: MetricsRecorder,
    private val trace: ComposeTraceContext,
) {
    @GetMapping(path = ["/v1/surfaces/home"], version = "1")
    fun home(
        @RequestHeader(name = "UI-Schema-Version", required = false) uiSchemaVersion: String?,
        @RequestHeader(name = "Client-Platform", required = false) clientPlatform: String?,
        @RequestHeader(name = "Client-Version", required = false) clientVersion: String?,
        @RequestHeader(name = "Client-Build", required = false) clientBuild: String?,
        @RequestHeader(name = "Accept-Language", required = false) acceptLanguage: String?,
        @RequestHeader(name = "API-Version", required = false) apiVersion: String?,
        @RequestHeader(name = "OS-Version", required = false) osVersion: String?,
        @RequestHeader(name = "Component-Capabilities", required = false) capabilities: String?,
        @RequestHeader(name = "SDUI-Channel", required = false) channel: String?,
        @RequestHeader(name = "If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<*> {
        val started = System.nanoTime()
        trace.open(
            mapOf(
                "surface" to "home",
                "platform" to (clientPlatform ?: ""),
                "schemaVersion" to (uiSchemaVersion ?: ""),
            ),
        )
        // O desfecho comeca como erro e so e substituido depois que a composicao devolve: se algo
        // escapar como excecao, a amostra de tempo ainda sai, marcada pelo que de fato aconteceu.
        var outcome = OUTCOME_ERROR
        try {
            val result = compose.compose(
                ComposeRequest(
                    headers = NegotiateHeaders(
                        uiSchemaVersion = uiSchemaVersion,
                        clientPlatform = clientPlatform,
                        clientVersion = clientVersion,
                        clientBuild = clientBuild,
                        acceptLanguage = acceptLanguage,
                        apiVersion = apiVersion,
                        osVersion = osVersion,
                        componentCapabilities = capabilities,
                        channel = channel,
                    ),
                    ifNoneMatch = ifNoneMatch,
                    // Identidade nao autenticada: serve para repartir a capacidade entre chamadores
                    // bem-comportados, nao para conter um cliente que troque os headers. O teto por
                    // cliente depende de autenticacao no gateway.
                    identity = "${clientPlatform.orEmpty()}:${clientBuild.orEmpty()}",
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
                            message = "home indisponivel",
                            details = listOf(result.reason.wire),
                        ),
                    )

                is ComposeResult.Success -> ok(result.screen)
            }
        } finally {
            metrics.recordTime(
                "compose.duration",
                (System.nanoTime() - started) / 1_000_000,
                mapOf("surface" to "home", "outcome" to outcome),
            )
            trace.close()
        }
    }

    /** Serializa a arvore uma unica vez, direto para bytes, e mede tempo e tamanho do payload. */
    private fun ok(screen: ComposedScreen): ResponseEntity<ByteArray> {
        val body = mapper.toResponse(screen)
        val serializeStarted = System.nanoTime()
        val json = jsonMapper.writeValueAsBytes(body)
        val metricTags = mapOf(
            "schemaVersion" to screen.schemaVersion,
            "appVersion" to screen.client.appVersion.toString(),
            "surface" to screen.surface,
            "platform" to screen.platform.wire(),
        )
        metrics.recordTime("serialize.ms", (System.nanoTime() - serializeStarted) / 1_000_000, metricTags)
        metrics.recordBytes("payload.bytes", json.size.toLong(), metricTags)
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
    }
}
