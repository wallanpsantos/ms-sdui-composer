package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.core.model.ActorRole
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom

/** Limita bytes e concorrencia antes de Jackson construir o grafo administrativo (ADR-022). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class AdminRequestLimitFilter(
    @Value("\${sdui.admin-max-body-bytes:1048576}") private val maxBodyBytes: Int,
    @Value("\${sdui.admin-max-concurrent-requests:8}") maxConcurrentRequests: Int,
    private val metrics: MetricsRecorder,
) : OncePerRequestFilter() {
    private val permits = Semaphore(maxConcurrentRequests)

    init {
        require(maxBodyBytes in 1 until Int.MAX_VALUE)
        require(maxConcurrentRequests > 0)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifEmpty { request.requestURI.removePrefix(request.contextPath) }
        // Inclui parametros de matriz (/admin;param=.../v1), removidos pelo roteamento MVC.
        return !path.startsWith("/admin")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.getHeader("Actor-Id").isNullOrBlank() || ActorRole.parse(request.getHeader("Actor-Role")) == null) {
            reject(response, 403, "FORBIDDEN", "ator ausente ou invalido", "denied")
            return
        }
        if (request.contentLengthLong > maxBodyBytes) {
            reject(response, 413, "REQUEST_TOO_LARGE", "corpo acima do limite", "body_too_large")
            return
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", ThreadLocalRandom.current().nextLong(15, 31).toString())
            reject(response, 503, "ADMIN_UNAVAILABLE", "limite de requisicoes administrativas", "unavailable")
            return
        }
        try {
            // Nao confiar em Content-Length: chunked e comprimento falso tambem tem teto.
            val bytes = request.inputStream.readNBytes(maxBodyBytes + 1)
            if (bytes.size > maxBodyBytes) {
                reject(response, 413, "REQUEST_TOO_LARGE", "corpo acima do limite", "body_too_large")
                return
            }
            filterChain.doFilter(BufferedAdminRequest(request, bytes), response)
        } finally {
            permits.release()
        }
    }

    private fun reject(response: HttpServletResponse, status: Int, code: String, message: String, error: String) {
        runCatching { metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to error)) }
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        // Apenas constantes internas: nenhum texto do chamador e interpolado no JSON.
        response.writer.write("{\"code\":\"$code\",\"message\":\"$message\",\"details\":[]}")
    }
}

private class BufferedAdminRequest(request: HttpServletRequest, private val bytes: ByteArray) :
    HttpServletRequestWrapper(request) {
    private val stream = object : ServletInputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun isFinished(): Boolean = delegate.available() == 0
        override fun isReady(): Boolean = true
        override fun setReadListener(listener: ReadListener) {
            throw IllegalStateException("plano administrativo usa Servlet bloqueante")
        }
    }

    override fun getInputStream(): ServletInputStream = stream
    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(stream, characterEncoding ?: "UTF-8"))
    override fun getContentLength(): Int = bytes.size
    override fun getContentLengthLong(): Long = bytes.size.toLong()
}
