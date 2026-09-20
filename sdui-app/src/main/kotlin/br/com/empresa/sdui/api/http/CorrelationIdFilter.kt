package br.com.empresa.sdui.api.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

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

        MDC.put(MDC_KEY_REQUEST_ID, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY_REQUEST_ID)
        }
    }

    companion object {
        const val HEADER_X_REQUEST_ID: String = "X-Request-Id"
        const val HEADER_REQUEST_ID: String = "Request-Id"
        const val MDC_KEY_REQUEST_ID: String = "requestId"
        private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
