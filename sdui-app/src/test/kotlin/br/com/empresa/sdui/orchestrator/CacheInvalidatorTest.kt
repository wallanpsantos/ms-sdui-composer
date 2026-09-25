package br.com.empresa.sdui.orchestrator

import br.com.empresa.sdui.adapters.invalidation.CacheInvalidationRelay
import br.com.empresa.sdui.adapters.memory.InMemoryCacheInvalidationOutbox
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.RecordingMetrics
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.orchestrator.admin.CacheInvalidator
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Protocolo pos-commit de invalidacao (P10, ADR-021): falha na invalidacao deixa o registro
 * pendente no outbox, e o relay aplica depois — simula a queda do cache entre o commit e a
 * invalidacao.
 */
class CacheInvalidatorTest {

    /** Last good que falha as primeiras invalidacoes, como um Redis fora do ar. */
    private class FlakyLastGood(private val delegate: LastGoodScreenStore) : LastGoodScreenStore by delegate {
        var failures: Int = 1

        override fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long) {
            if (failures-- > 0) error("cache fora do ar")
            delegate.invalidate(surface, platform, channel, pointerVersion)
        }
    }

    @Test
    fun `invalidacao que falha fica pendente e o relay a aplica depois`() {
        val outbox = InMemoryCacheInvalidationOutbox()
        val metrics = RecordingMetrics()
        val lastGood = FlakyLastGood(InMemoryLastGoodScreenStore())
        val invalidator =
            CacheInvalidator(InMemorySpecCache(), InMemoryHydratedScreenCache(), lastGood, outbox, metrics)
        val invalidation =
            CacheInvalidation("inv-1", "home", ClientPlatform.IOS, Channel.STABLE, 3, "rev_old", Instant.EPOCH)
        outbox.record(invalidation)

        assertThat(invalidator.apply(invalidation)).isFalse()
        assertThat(outbox.pending(10).map { it.id }).containsExactly("inv-1")
        assertThat(metrics.names()).contains("cache.invalidation.failed")

        val relay = CacheInvalidationRelay(invalidator, intervalMs = 60_000, enabled = true)
        relay.drain()
        relay.close()

        assertThat(outbox.pending(10)).isEmpty()
        assertThat(invalidator.pendingCount()).isZero()
        assertThat(metrics.names()).contains("cache.invalidation.applied")
    }

    @Test
    fun `drenagem sem pendencias nao falha nem registra metrica`() {
        val metrics = RecordingMetrics()
        val invalidator = CacheInvalidator(
            InMemorySpecCache(),
            InMemoryHydratedScreenCache(),
            InMemoryLastGoodScreenStore(),
            InMemoryCacheInvalidationOutbox(),
            metrics,
        )
        assertThat(invalidator.drainPending()).isZero()
        assertThat(metrics.samples).isEmpty()
    }
}
