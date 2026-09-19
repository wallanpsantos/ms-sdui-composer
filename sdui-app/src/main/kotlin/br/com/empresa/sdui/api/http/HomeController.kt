package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import br.com.empresa.sdui.contract.error.ApiErrorResponse
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
                    identity = "${clientPlatform.orEmpty()}:${clientBuild.orEmpty()}",
                ),
            )
            return when (result) {
                is ComposeResult.InvalidHeaders -> ResponseEntity.badRequest().body(
                    ApiErrorResponse(
                        code = "INVALID_HEADERS",
                        message = "headers de negociacao invalidos",
                        details = result.violations.map { "${it.header}:${it.reason}" },
                    ),
                )

                is ComposeResult.RateLimited -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
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

                is ComposeResult.Success -> {
                    val body = mapper.toResponse(result.screen)
                    val serializeStarted = System.nanoTime()
                    val json = jsonMapper.writeValueAsBytes(body)
                    metrics.recordTime(
                        "serialize.ms",
                        (System.nanoTime() - serializeStarted) / 1_000_000,
                        mapOf(
                            "schemaVersion" to result.screen.schemaVersion,
                            "appVersion" to result.screen.client.appVersion.toString(),
                            "surface" to result.screen.surface,
                            "platform" to result.screen.platform.wire(),
                        ),
                    )
                    metrics.recordBytes(
                        "payload.bytes",
                        json.size.toLong(),
                        mapOf(
                            "schemaVersion" to result.screen.schemaVersion,
                            "appVersion" to result.screen.client.appVersion.toString(),
                            "surface" to result.screen.surface,
                            "platform" to result.screen.platform.wire(),
                        ),
                    )
                    ResponseEntity.ok()
                        .header("ETag", result.screen.etag)
                        .header("Cache-Control", CACHE_CONTROL)
                        .header("Vary", VARY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json)
                }
            }
        } finally {
            metrics.recordTime(
                "compose.duration",
                (System.nanoTime() - started) / 1_000_000,
                mapOf("surface" to "home"),
            )
            trace.close()
        }
    }

    private companion object {
        const val CACHE_CONTROL: String = "private, max-age=60"
        const val VARY: String =
            "API-Version, UI-Schema-Version, Client-Platform, Client-Version, Client-Build, Component-Capabilities"
    }
}
