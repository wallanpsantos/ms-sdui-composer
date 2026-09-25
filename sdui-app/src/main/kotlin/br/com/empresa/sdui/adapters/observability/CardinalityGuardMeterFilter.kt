package br.com.empresa.sdui.adapters.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import java.util.concurrent.ConcurrentHashMap

/**
 * Filtro de proteção contra explosão de cardinalidade em métricas do Micrometer.
 *
 * ### 1. O que faz
 * Impõe um teto rígido sobre a quantidade de valores distintos que uma tag pode assumir nas métricas próprias do serviço.
 *
 * ### 2. Para que serve
 * Defesa em profundidade contra esgotamento de memória no servidor e no Prometheus (`achado P1 de 2026-09-23`),
 * impedindo que tags com valores de alta cardinalidade poluam e sobrecarreguem o subsistema de telemetria.
 *
 * ### 3. Como funciona
 * Avalia identificadores [Meter.Id] no método [accept]. Filtra métricas que começam com os prefixos informados
 * em [prefixes]. Rastreia valores aceitos em conjuntos thread-safe indexados por métrica e tag; ao atingir
 * [maxValuesPerTag], nega novos meters retornando [MeterFilterReply.DENY] e emite um alerta único no log.
 */
class CardinalityGuardMeterFilter(
    private val prefixes: Set<String>,
    private val maxValuesPerTag: Int,
) : MeterFilter {
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Avalia se um novo medidor (meter) deve ser aceito pelo registro de métricas.
     *
     * ### 1. O que faz
     * Decide entre [MeterFilterReply.NEUTRAL] (aceitação) e [MeterFilterReply.DENY] (rejeição) para o medidor.
     *
     * ### 2. Para que serve
     * Bloquear a criação de novas séries temporais quando o limite de valores distintos de uma tag é atingido.
     *
     * ### 3. Como funciona
     * Ignora métricas fora de [prefixes]. Para métricas monitoradas, confere atomicamente todas as tags
     * antes de persistir os valores; se alguma tag atingir [maxValuesPerTag], loga aviso único e retorna [MeterFilterReply.DENY].
     */
    override fun accept(id: Meter.Id): MeterFilterReply {
        if (id.name.substringBefore('.') !in prefixes) return MeterFilterReply.NEUTRAL
        // Confere todas as tags antes de registrar qualquer valor: um meter negado por uma tag nao
        // pode ocupar vaga nas outras, senao o teto de uma tag esgotaria o das demais.
        val admitted = ArrayList<Pair<MutableSet<String>, String>>(id.tags.size)
        for (tag in id.tags) {
            val slot = "${id.name}|${tag.key}"
            val values = seen.computeIfAbsent(slot) { ConcurrentHashMap.newKeySet() }
            if (tag.value in values) continue
            if (values.size >= maxValuesPerTag) {
                if (reported.add(slot)) {
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "tag '${tag.key}' de '${id.name}' atingiu $maxValuesPerTag valores; novos meters negados",
                    )
                }
                return MeterFilterReply.DENY
            }
            admitted += values to tag.value
        }
        for ((values, value) in admitted) values += value
        return MeterFilterReply.NEUTRAL
    }

    /**
     * Retorna a quantidade de valores distintos aceitos para um dado par métrica e tag.
     *
     * ### 1. O que faz
     * Informa quantos valores únicos já foram admitidos para [name] e [tagKey].
     *
     * ### 2. Para que serve
     * Viabilizar asserções e testes automatizados sobre a eficácia da proteção de cardinalidade.
     *
     * ### 3. Como funciona
     * Localiza o slot no mapa interno e retorna a contagem do conjunto de valores.
     */
    fun acceptedValues(name: String, tagKey: String): Int = seen["$name|$tagKey"]?.size ?: 0

    private companion object {
        val LOG: System.Logger =
            System.getLogger("br.com.empresa.sdui.adapters.observability.CardinalityGuardMeterFilter")
    }
}
