@file:Suppress("DEPRECATION")

package br.com.empresa.sdui.api

import br.com.empresa.sdui.SduiAppTestConfiguration
import br.com.empresa.sdui.api.http.CorrelationIdFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(classes = [SduiAppTestConfiguration::class])
@AutoConfigureMockMvc
class CorrelationIdFilterTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val filter: CorrelationIdFilter,
) {
    @Test
    fun `filtro propaga X-Request-Id valido para o MDC durante a execucao`() {
        val request = MockHttpServletRequest("GET", "/v1/surfaces/home")
        request.addHeader("X-Request-Id", "req-test-12345")
        val response = MockHttpServletResponse()

        var capturedMdc: String? = null
        val chain = MockFilterChain { _, _ ->
            capturedMdc = MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)
        }

        filter.doFilter(request, response, chain)

        assertThat(capturedMdc).isEqualTo("req-test-12345")
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)).isNull()
    }

    @Test
    fun `filtro gera UUID v4 quando X-Request-Id esta ausente e limpa MDC no finally`() {
        val request = MockHttpServletRequest("GET", "/v1/surfaces/home")
        val response = MockHttpServletResponse()

        var capturedMdc: String? = null
        val chain = MockFilterChain { _, _ ->
            capturedMdc = MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)
        }

        filter.doFilter(request, response, chain)

        assertThat(capturedMdc).isNotBlank()
        assertThat(capturedMdc).matches("""^[0-9a-fA-F-]{36}$""")
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)).isNull()
    }

    @Test
    fun `filtro sanitiza e substitui X-Request-Id malformado ou perigoso por UUID`() {
        val request = MockHttpServletRequest("GET", "/v1/surfaces/home")
        request.addHeader("X-Request-Id", "invalido\r\nCRLF-injection")
        val response = MockHttpServletResponse()

        var capturedMdc: String? = null
        val chain = MockFilterChain { _, _ ->
            capturedMdc = MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)
        }

        filter.doFilter(request, response, chain)

        assertThat(capturedMdc).isNotEqualTo("invalido\r\nCRLF-injection")
        assertThat(capturedMdc).matches("""^[0-9a-fA-F-]{36}$""")
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_REQUEST_ID)).isNull()
    }

    @Test
    fun `chamada web da Home nao vaza headers com prefixo X na resposta`() {
        val result = mockMvc.get("/v1/surfaces/home") {
            CanonicalHeaders.ios().forEach { (k, v) -> header(k, v) }
            header("X-Request-Id", "client-req-999")
        }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val xHeaders = result.response.headerNames.filter { it.startsWith("X-", ignoreCase = true) }
        assertThat(xHeaders).`as`("headers com prefixo X- sao estritamente proibidos na resposta da Home").isEmpty()
    }
}
