package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.memory.InMemoryComposeSingleflight
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.compose.AllowlistCanaryPolicy
import br.com.empresa.sdui.orchestrator.port.outbound.SingleflightOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SingleflightAndCanaryTest {
    @Test
    fun `miss concorrente elege um unico lider`() {
        val singleflight = InMemoryComposeSingleflight()
        val leaders = AtomicInteger()
        val waiters = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val tasks = (1..8).map {
            pool.submit<SingleflightOutcome<Int>> {
                start.await()
                singleflight.runExclusive("k", Duration.ofSeconds(2)) {
                    Thread.sleep(50)
                    42
                }
            }
        }
        start.countDown()
        val results = tasks.map { it.get() }
        pool.close()
        results.forEach { outcome ->
            when (outcome) {
                is SingleflightOutcome.Leader -> leaders.incrementAndGet()
                is SingleflightOutcome.Waiter -> waiters.incrementAndGet()
                is SingleflightOutcome.WaitTimeout<*> -> error("timeout inesperado")
            }
        }
        assertThat(leaders.get()).isEqualTo(1)
        assertThat(waiters.get()).isEqualTo(7)
        assertThat(results.map {
            when (it) {
                is SingleflightOutcome.Leader -> it.value
                is SingleflightOutcome.Waiter -> it.value
                else -> -1
            }
        }.toSet()).containsExactly(42)
    }

    @Test
    fun `canary so atende build na allowlist e nao vira flag`() {
        val policy = AllowlistCanaryPolicy(mapOf(ClientPlatform.IOS to setOf("81420")))
        assertThat(policy.channelFor(ClientPlatform.IOS, "81420", Channel.CANARY)).isEqualTo(Channel.CANARY)
        assertThat(policy.channelFor(ClientPlatform.IOS, "1", Channel.CANARY)).isEqualTo(Channel.STABLE)
        assertThat(policy.channelFor(ClientPlatform.ANDROID, "81420", Channel.CANARY)).isEqualTo(Channel.STABLE)
        assertThat(policy.channelFor(ClientPlatform.IOS, "81420", Channel.STABLE)).isEqualTo(Channel.STABLE)
    }
}
