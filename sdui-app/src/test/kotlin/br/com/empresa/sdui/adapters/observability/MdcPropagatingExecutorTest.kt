package br.com.empresa.sdui.adapters.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MdcPropagatingExecutorTest {

    @Test
    fun `propaga mapa de MDC para a thread executora e limpa no encerramento`() {
        val baseExecutor = Executors.newSingleThreadExecutor()
        val propagatingExecutor = MdcPropagatingExecutor(baseExecutor)

        MDC.put("requestId", "req-async-123")
        MDC.put("entryPoint", "home")

        val capturedMdc = AtomicReference<Map<String, String>>()
        val latch = CountDownLatch(1)

        propagatingExecutor.execute {
            capturedMdc.set(MDC.getCopyOfContextMap())
            latch.countDown()
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        val mdcInThread = capturedMdc.get()
        assertThat(mdcInThread).isNotNull
        assertThat(mdcInThread["requestId"]).isEqualTo("req-async-123")
        assertThat(mdcInThread["entryPoint"]).isEqualTo("home")

        MDC.clear()
        baseExecutor.shutdown()
    }
}
