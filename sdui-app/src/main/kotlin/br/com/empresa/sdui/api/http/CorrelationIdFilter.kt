package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.core.model.Surfaces
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Filtro HTTP que estabelece o identificador de correlacao para cada requisicao.
 *
 * Captura o cabecalho de entrada `X-Request-Id` (ou `Request-Id`). Se ausente ou invalido,
 * gera um UUID v4. O valor e injetado no MDC do SLF4J como `requestId` e removido no finally,
 * garantindo rastreabilidade sem contaminacao de Virtual Threads carrier.
 *
 * Nao emite cabecalho customizado com prefixo X- na resposta para respeitar o contrato da Home (RFC 6648).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawHeader = request.getHeader(HEADER_X_REQUEST_ID)?.trim()
            ?: request.getHeader(HEADER_REQUEST_ID)?.trim()

        val requestId = if (!rawHeader.isNullOrEmpty() && REQUEST_ID_REGEX.matches(rawHeader)) {
            rawHeader
        } else {
            UUID.randomUUID().toString()
        }

        val uri = request.requestURI.orEmpty()
        val entryPoint = when {
            uri.startsWith(SURFACES_PREFIX) -> surfaceEntryPoint(uri)
            uri.startsWith("/admin") -> ENTRY_POINT_ADMIN
            uri.startsWith("/actuator") -> ENTRY_POINT_ACTUATOR
            else -> ENTRY_POINT_HTTP
        }

        MDC.put(MDC_KEY_REQUEST_ID, requestId)
        MDC.put(MDC_KEY_ENTRY_POINT, entryPoint)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY_ENTRY_POINT)
            MDC.remove(MDC_KEY_REQUEST_ID)
        }
    }

    /**
     * Cada surface da allowlist e um ponto de entrada proprio (`home`, `catalog`); qualquer outro
     * caminho sob /v1/surfaces vira `surface`, sem copiar texto do path para o log.
     */
    private fun surfaceEntryPoint(uri: String): String {
        val segment = uri.removePrefix(SURFACES_PREFIX).substringBefore('/')
        return Surfaces.find(segment)?.id ?: ENTRY_POINT_SURFACE
    }

    companion object {
        const val HEADER_X_REQUEST_ID: String = "X-Request-Id"
        const val HEADER_REQUEST_ID: String = "Request-Id"
        const val MDC_KEY_REQUEST_ID: String = "requestId"
        const val MDC_KEY_ENTRY_POINT: String = "entryPoint"

        const val ENTRY_POINT_HOME: String = "home"
        const val ENTRY_POINT_SURFACE: String = "surface"
        private const val SURFACES_PREFIX: String = "/v1/surfaces/"
        const val ENTRY_POINT_ADMIN: String = "admin"
        const val ENTRY_POINT_ACTUATOR: String = "actuator"
        const val ENTRY_POINT_HTTP: String = "http"

        private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
