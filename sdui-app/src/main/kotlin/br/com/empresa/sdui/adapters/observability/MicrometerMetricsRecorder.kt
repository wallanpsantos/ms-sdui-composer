package br.com.empresa.sdui.adapters.observability

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.TimeUnit

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

    private fun tagList(tags: Map<String, String>): List<Tag> =
        tags.entries.map { Tag.of(it.key, it.value) }
}
