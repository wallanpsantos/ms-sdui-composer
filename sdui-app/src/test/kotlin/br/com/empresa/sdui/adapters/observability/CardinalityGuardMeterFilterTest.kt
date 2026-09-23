package br.com.empresa.sdui.adapters.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Defesa em profundidade do achado P1: teto de valores por tag nas metricas proprias. */
class CardinalityGuardMeterFilterTest {

    @Test
    fun `nega meter novo alem do teto de valores por tag e deixa terceiros passarem`() {
        val filter = CardinalityGuardMeterFilter(prefixes = setOf("compose"), maxValuesPerTag = 3)
        val registry = SimpleMeterRegistry().apply { config().meterFilter(filter) }

        repeat(10) { i -> registry.counter("compose.hit", "surface", "s$i").increment() }
        repeat(10) { i -> registry.counter("jvm.custom", "tag", "t$i").increment() }

        assertThat(registry.meters.count { it.id.name == "compose.hit" }).isEqualTo(3)
        assertThat(filter.acceptedValues("compose.hit", "surface")).isEqualTo(3)
        assertThat(registry.meters.count { it.id.name == "jvm.custom" }).isEqualTo(10)
    }

    @Test
    fun `valor ja aceito continua sendo contado depois do teto`() {
        val filter = CardinalityGuardMeterFilter(prefixes = setOf("compose"), maxValuesPerTag = 1)
        val registry = SimpleMeterRegistry().apply { config().meterFilter(filter) }

        registry.counter("compose.miss", "surface", "home").increment()
        registry.counter("compose.miss", "surface", "outra").increment()
        registry.counter("compose.miss", "surface", "home").increment()

        assertThat(registry.counter("compose.miss", "surface", "home").count()).isEqualTo(2.0)
        assertThat(registry.meters.count { it.id.name == "compose.miss" }).isEqualTo(1)
    }
}
