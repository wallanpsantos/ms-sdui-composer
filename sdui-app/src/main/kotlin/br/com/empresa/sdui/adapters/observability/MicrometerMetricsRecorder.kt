package br.com.empresa.sdui.adapters.observability

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.TimeUnit

/**
 * Implementa a porta de metricas sobre o Micrometer, exposto em /actuator/prometheus.
 *
 * Adapter fino de proposito: manter o Micrometer confinado aqui e o que permite ao orchestrator
 * instrumentar sem depender do framework.
 */
class MicrometerMetricsRecorder(
    private val registry: MeterRegistry,
) : MetricsRecorder {
    override fun increment(name: String, tags: Map<String, String>) {
        registry.counter(name, tagList(tags)).increment()
    }

    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) {
        registry.timer(name, tagList(tags)).record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) {
        registry.summary(name, tagList(tags)).record(bytes.toDouble())
    }

    private fun tagList(tags: Map<String, String>): List<Tag> {
        if (tags.isEmpty()) return emptyList()
        val list = ArrayList<Tag>(tags.size)
        for ((k, v) in tags) {
            list += Tag.of(k, v)
        }
        return list
    }
}
