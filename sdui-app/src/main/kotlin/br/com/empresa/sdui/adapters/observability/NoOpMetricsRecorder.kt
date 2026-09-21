package br.com.empresa.sdui.adapters.observability

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder

/**
 * Descarta as amostras. Usado quando nenhum MeterRegistry esta disponivel: a ausencia de registry
 * nao deve custar memoria nem derrubar a composicao.
 */
object NoOpMetricsRecorder : MetricsRecorder {
    override fun increment(name: String, tags: Map<String, String>) = Unit
    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) = Unit
    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) = Unit
}
