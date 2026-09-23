package br.com.empresa.sdui.api

import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.api.http.AdminRequestLimitFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdminRequestLimitFilterTest {
    private fun request(body: ByteArray) = MockHttpServletRequest("POST", "/admin/v1/specs").apply {
        addHeader("Actor-Id", "maker")
        addHeader("Actor-Role", "MAKER")
        contentType = "application/json"
        setContent(body)
    }

    @Test
    fun `comprimento declarado acima do teto e recusado sem ler o stream`() {
        val input = object : MockHttpServletRequest("POST", "/admin/v1/specs") {
            override fun getContentLengthLong(): Long = 17
            override fun getInputStream(): ServletInputStream = error("nao deve ler")
        }.apply {
            addHeader("Actor-Id", "maker")
            addHeader("Actor-Role", "MAKER")
        }
        val response = MockHttpServletResponse()
        AdminRequestLimitFilter(16, 1, RecordingMetrics()).doFilter(input, response, FilterChain { _, _ -> error("nao deve entrar") })
        assertThat(response.status).isEqualTo(413)
        assertThat(response.contentAsString).contains("REQUEST_TOO_LARGE")
    }

    @Test
    fun `chunked tem limite antes de Jackson e corpo exatamente no teto e preservado`() {
        val filter = AdminRequestLimitFilter(16, 1, RecordingMetrics())
        val chunked = object : MockHttpServletRequest("POST", "/admin/v1/specs") {
            override fun getContentLengthLong(): Long = -1
            override fun getContentLength(): Int = -1
        }.apply {
            addHeader("Actor-Id", "maker")
            addHeader("Actor-Role", "MAKER")
            addHeader("Transfer-Encoding", "chunked")
            setContent(ByteArray(17))
        }
        val oversized = MockHttpServletResponse()
        filter.doFilter(chunked, oversized, FilterChain { _, _ -> error("nao deve desserializar") })
        assertThat(oversized.status).isEqualTo(413)

        val bytes = "1234567890123456".toByteArray()
        var received: ByteArray? = null
        filter.doFilter(request(bytes), MockHttpServletResponse(), FilterChain { req, _ -> received = req.inputStream.readAllBytes() })
        assertThat(received).isEqualTo(bytes)
    }

    @Test
    fun `ator ausente e recusado antes de consumir corpo`() {
        val input = object : MockHttpServletRequest("POST", "/admin/v1/specs") {
            override fun getInputStream(): ServletInputStream = error("nao deve ler")
        }
        val response = MockHttpServletResponse()
        AdminRequestLimitFilter(16, 1, RecordingMetrics()).doFilter(input, response, FilterChain { _, _ -> error("nao deve entrar") })
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `saturacao recusa cedo e falha do controller devolve permissao`() {
        val filter = AdminRequestLimitFilter(16, 1, RecordingMetrics())
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val first = pool.submit {
                assertThatThrownBy {
                    filter.doFilter(request(byteArrayOf()), MockHttpServletResponse(), FilterChain { _, _ ->
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        error("falha do controller")
                    })
                }.hasMessage("falha do controller")
            }
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                val refused = MockHttpServletResponse()
                filter.doFilter(request(byteArrayOf()), refused, FilterChain { _, _ -> error("nao deve entrar") })
                assertThat(refused.status).isEqualTo(503)
                assertThat(refused.getHeader("Retry-After")).isNotBlank()
            } finally {
                finish.countDown()
            }
            first.get(5, TimeUnit.SECONDS)
        }
        var called = false
        filter.doFilter(request(byteArrayOf()), MockHttpServletResponse(), FilterChain { _, _ -> called = true })
        assertThat(called).isTrue()
    }
}
