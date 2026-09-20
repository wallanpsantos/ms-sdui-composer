package br.com.empresa.sdui.adapters.memory

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Recorder de teste: acumula todas as amostras para inspecao. Nao serve a producao, onde a lista
 * cresceria sem limite — la o fallback e NoOpMetricsRecorder.
 */
class RecordingMetrics : MetricsRecorder {
    data class Sample(val name: String, val tags: Map<String, String>, val value: Long? = null)

    val samples = mutableListOf<Sample>()
    private val lock = ReentrantLock()

    override fun increment(name: String, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags) }
    }

    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags, durationMs) }
    }

    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) {
        lock.withLock { samples += Sample(name, tags, bytes) }
    }

    fun names(): List<String> = lock.withLock { samples.map { it.name } }
}
