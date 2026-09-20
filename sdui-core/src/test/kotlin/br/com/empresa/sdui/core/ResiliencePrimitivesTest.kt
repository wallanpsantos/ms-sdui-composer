package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.limit.Bulkhead
import br.com.empresa.sdui.core.limit.BulkheadOutcome
import br.com.empresa.sdui.core.limit.RetryAfter
import br.com.empresa.sdui.core.limit.TimeBudget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResiliencePrimitivesTest {

    @Test
    fun `retry after espalha a base em torno de si e nunca desce de um segundo`() {
        assertThat(RetryAfter.jittered(5, 0.0)).isEqualTo(3)
        assertThat(RetryAfter.jittered(5, 1.0)).isEqualTo(7)
        assertThat(RetryAfter.jittered(5, 0.5)).isEqualTo(5)
        // Base 2 e a do 429: a faixa util e 1..3, e nao a constante que sincronizava a coorte.
        assertThat(RetryAfter.jittered(2, 0.0)).isEqualTo(1)
        assertThat(RetryAfter.jittered(2, 1.0)).isEqualTo(3)
        // Um Retry-After de zero mandaria o cliente voltar imediatamente.
        assertThat(RetryAfter.jittered(0, 0.0)).isEqualTo(1)
        assertThat(RetryAfter.jittered(1, 0.0)).isEqualTo(1)
    }

    @Test
    fun `retry after produz mais de um valor ao longo da faixa`() {
        val values = (0..10).map { RetryAfter.jittered(5, it / 10.0) }.toSet()
        assertThat(values).hasSizeGreaterThan(1)
        assertThat(values).allSatisfy { assertThat(it).isBetween(3L, 7L) }
    }

    @Test
    fun `orcamento encolhe o teto da etapa e se declara esgotado`() {
        var now = 0L
        val budget = TimeBudget(Duration.ofMillis(250)) { now }

        assertThat(budget.stage(Duration.ofMillis(150))).isEqualTo(Duration.ofMillis(150))
        assertThat(budget.isExhausted()).isFalse()

        // 200ms gastos: a etapa seguinte recebe os 50ms que sobraram, nao o teto dela.
        now = Duration.ofMillis(200).toNanos()
        assertThat(budget.elapsed()).isEqualTo(Duration.ofMillis(200))
        assertThat(budget.stage(Duration.ofMillis(150))).isEqualTo(Duration.ofMillis(50))

        now = Duration.ofMillis(300).toNanos()
        assertThat(budget.isExhausted()).isTrue()
        assertThat(budget.remaining()).isEqualTo(Duration.ZERO)
        assertThat(budget.stage(Duration.ofMillis(150))).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `bulkhead limita simultaneos e recusa o excedente sem lanca-lo`() {
        val bulkhead = Bulkhead(2)
        val holding = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val executed = AtomicInteger()

        repeat(2) {
            // execute e nao submit: com submit, um lambda que devolve valor fica ambiguo entre
            // Runnable e Callable.
            pool.execute {
                bulkhead.withPermit(Duration.ofSeconds(5)) {
                    executed.incrementAndGet()
                    holding.countDown()
                    release.await()
                }
            }
        }
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

        // Com as duas permissoes seguras, o terceiro nao entra — e recebe recusa, nao excecao.
        val rejected = bulkhead.withPermit(Duration.ofMillis(20)) { executed.incrementAndGet() }
        assertThat(rejected).isEqualTo(BulkheadOutcome.Rejected)
        assertThat(executed.get()).isEqualTo(2)

        release.countDown()
        pool.close()
        assertThat(bulkhead.availablePermits()).isEqualTo(2)
    }

    @Test
    fun `bulkhead devolve a permissao quando o trabalho lanca`() {
        val bulkhead = Bulkhead(1)
        runCatching { bulkhead.withPermit(Duration.ofMillis(10)) { error("falha no trabalho") } }
        assertThat(bulkhead.availablePermits()).isEqualTo(1)

        val outcome = bulkhead.withPermit(Duration.ofMillis(10)) { "ok" }
        assertThat(outcome).isEqualTo(BulkheadOutcome.Executed("ok"))
    }
}
