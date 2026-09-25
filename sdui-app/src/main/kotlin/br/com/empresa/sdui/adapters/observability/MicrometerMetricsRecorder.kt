package br.com.empresa.sdui.adapters.observability

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.TimeUnit

/**
 * Adaptador de métricas operacionais sobre o Micrometer e Prometheus.
 *
 * ### 1. O que faz
 * Implementa a porta de saída [MetricsRecorder] direcionando medições e contadores para o [MeterRegistry].
 *
 * ### 2. Para que serve
 * Isolar o orquestrador e as regras de composição de dependências de bibliotecas de telemetria, expondo
 * métricas operacionais padronizadas no endpoint `/actuator/prometheus`.
 *
 * ### 3. Como funciona
 * Encapsula o [MeterRegistry] do Spring Boot / Micrometer. Mapeia tags simples chave-valor para instâncias
 * de [Tag] e despacha registros para `counter`, `timer` ou `summary` do Micrometer.
 */
class MicrometerMetricsRecorder(
    private val registry: MeterRegistry,
) : MetricsRecorder {
    /**
     * Incrementa em uma unidade o contador especificado.
     *
     * ### 1. O que faz
     * Adiciona 1 ao contador identificado por [name] com as [tags] fornecidas.
     *
     * ### 2. Para que serve
     * Registrar a ocorrência de eventos discretos, como quedas na escada de fallback, bloqueios de bulkhead e skips de cache.
     *
     * ### 3. Como funciona
     * Obtém ou cria o contador no [registry] via `counter(name, tagList(tags))` e invoca `increment()`.
     */
    override fun increment(name: String, tags: Map<String, String>) {
        registry.counter(name, tagList(tags)).increment()
    }

    /**
     * Registra uma medição de tempo em milissegundos.
     *
     * ### 1. O que faz
     * Grava a duração [durationMs] no cronômetro identificado por [name].
     *
     * ### 2. Para que serve
     * Monitorar a latência de operações do sistema em granularidade de milissegundos.
     *
     * ### 3. Como funciona
     * Invoca `timer(name, tagList(tags)).record(durationMs, TimeUnit.MILLISECONDS)`.
     */
    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) {
        registry.timer(name, tagList(tags)).record(durationMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Registra uma medição de tempo de alta precisão em nanossegundos.
     *
     * ### 1. O que faz
     * Grava a duração [durationNanos] no cronômetro identificado por [name] sem truncamento.
     *
     * ### 2. Para que serve
     * Medir com fidelidade sub-milissegundo a latência do hot path de composição e operações de cache Redis.
     *
     * ### 3. Como funciona
     * Invoca `timer(name, tagList(tags)).record(durationNanos, TimeUnit.NANOSECONDS)`, alimentando histogramas do Prometheus.
     */
    override fun recordNanos(name: String, durationNanos: Long, tags: Map<String, String>) {
        registry.timer(name, tagList(tags)).record(durationNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Registra uma medição de volume em bytes.
     *
     * ### 1. O que faz
     * Grava a quantidade [bytes] em um resumo de distribuição (summary).
     *
     * ### 2. Para que serve
     * Rastrear a distribuição de tamanhos de payloads trafegados e respostas geradas.
     *
     * ### 3. Como funciona
     * Localiza ou cria o `DistributionSummary` via `summary(name, tagList(tags))` e invoca `record(bytes.toDouble())`.
     */
    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) {
        registry.summary(name, tagList(tags)).record(bytes.toDouble())
    }

    /**
     * Converte um mapa de tags chave-valor em uma lista de [Tag] do Micrometer.
     *
     * ### 1. O que faz
     * Transforma [Map] em [List] de instâncias [Tag].
     *
     * ### 2. Para que serve
     * Alimentar as consultas de registro do Micrometer com estruturas compatíveis.
     *
     * ### 3. Como funciona
     * Aloca uma [ArrayList] com o tamanho exato do mapa e itera convertendo para `Tag.of(k, v)`.
     */
    private fun tagList(tags: Map<String, String>): List<Tag> {
        if (tags.isEmpty()) return emptyList()
        val list = ArrayList<Tag>(tags.size)
        for ((k, v) in tags) {
            list += Tag.of(k, v)
        }
        return list
    }
}
