package br.com.empresa.sdui.adapters.observability

import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder

/**
 * Implementação no-op (descarte) da porta de métricas [MetricsRecorder].
 *
 * ### 1. O que faz
 * Descarta silenciosamente todas as medições de tempo, contadores e volumes de bytes solicitadas.
 *
 * ### 2. Para que serve
 * Servir de contingência em ambientes ou cenários de teste onde nenhum `MeterRegistry` está presente,
 * evitando alocação inútil de memória no heap ou falhas de composição por ausência de telemetria.
 *
 * ### 3. Como funciona
 * Implementa [MetricsRecorder] com métodos de retorno imediato (`Unit`), sem retenção de estado interno.
 */
object NoOpMetricsRecorder : MetricsRecorder {
    /**
     * Descarta a operação de incremento de contador.
     *
     * ### 1. O que faz
     * Não executa nenhuma ação para o contador informado.
     *
     * ### 2. Para que serve
     * Atender ao contrato de [MetricsRecorder] sem registrar contadores.
     *
     * ### 3. Como funciona
     * Retorna imediatamente [Unit].
     */
    override fun increment(name: String, tags: Map<String, String>) = Unit

    /**
     * Descarta a medição de tempo em milissegundos.
     *
     * ### 1. O que faz
     * Não registra a duração em milissegundos informada.
     *
     * ### 2. Para que serve
     * Atender ao contrato de [MetricsRecorder] sem criar timers.
     *
     * ### 3. Como funciona
     * Retorna imediatamente [Unit].
     */
    override fun recordTime(name: String, durationMs: Long, tags: Map<String, String>) = Unit

    /**
     * Descarta a medição de volume em bytes.
     *
     * ### 1. O que faz
     * Não armazena a contagem de bytes informada.
     *
     * ### 2. Para que serve
     * Atender ao contrato de [MetricsRecorder] sem criar resumos de distribuição.
     *
     * ### 3. Como funciona
     * Retorna imediatamente [Unit].
     */
    override fun recordBytes(name: String, bytes: Long, tags: Map<String, String>) = Unit

    /**
     * Descarta a medição de tempo em nanossegundos.
     *
     * ### 1. O que faz
     * Não armazena a latência em nanossegundos informada.
     *
     * ### 2. Para que serve
     * Atender ao contrato de [MetricsRecorder] sem registrar histogramas de precisão.
     *
     * ### 3. Como funciona
     * Retorna imediatamente [Unit].
     */
    override fun recordNanos(name: String, durationNanos: Long, tags: Map<String, String>) = Unit
}
