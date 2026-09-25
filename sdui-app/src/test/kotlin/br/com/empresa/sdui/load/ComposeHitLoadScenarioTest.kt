package br.com.empresa.sdui.load

import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * O cenario versionado precisa descrever uma carga reproduzivel e citar so metricas que o runtime
 * emite (achado 8 de 2026-09-23: o cenario citava `section.top_bar.ms`, que nao existia).
 *
 * Este teste nao mede o SLO — medir exige carga HTTP real, feita pelo [HttpLoadGenerator] em
 * ambiente dedicado. Ele garante que o cenario e o runtime nao divirjam de novo.
 */
class ComposeHitLoadScenarioTest {
    private val text: String = checkNotNull(javaClass.getResource("/load/compose-hit-p99.yaml")).readText()

    @Test
    fun `cenario define forma da carga, metas e headers canonicos`() {
        listOf(
            "surface: home",
            "endpoint: /v1/surfaces/home",
            "arrival: closed_loop",
            "concurrency: 32",
            "warmup_seconds: 10",
            "duration_seconds: 60",
            "hit_ratio: 0.9",
            "miss_generation: tree_ttl_expiry",
            "p99_ms: 400",
            "Client-Platform",
        ).forEach { assertThat(text).contains(it) }
    }

    @Test
    fun `toda metrica citada no cenario e emitida pelo runtime`() {
        val cited = text.lineSequence()
            .dropWhile { it.trim() != "metrics:" }
            .drop(1)
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .toList()

        assertThat(cited).isNotEmpty()
        assertThat(MetricNames.ALL).containsAll(cited)
    }
}
