package br.com.empresa.sdui.adapters.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import java.util.concurrent.ConcurrentHashMap

/**
 * Teto de valores distintos por tag nas metricas proprias do servico — defesa em profundidade
 * contra cardinalidade (achado P1 de 2026-09-23).
 *
 * A correcao de verdade e nao emitir valor sem vocabulario fechado (versao de app saiu das tags;
 * surface, type e schema passaram por allowlist). Este filtro garante que um erro futuro nessa
 * disciplina nao vire crescimento ilimitado de meters: acima de [maxValuesPerTag] valores para o
 * mesmo par (nome, tag), o meter novo e negado e o fato e registrado uma vez no log. E o mesmo
 * mecanismo do `MeterFilter.maximumAllowableTags` do Micrometer, aplicado a todos os prefixos e
 * tags do servico de uma vez.
 *
 * Meters de terceiros (JVM, HTTP, pools) passam sem avaliacao: so os nomes com prefixo em
 * [prefixes] sao contados.
 */
class CardinalityGuardMeterFilter(
    private val prefixes: Set<String>,
    private val maxValuesPerTag: Int,
) : MeterFilter {
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun accept(id: Meter.Id): MeterFilterReply {
        if (id.name.substringBefore('.') !in prefixes) return MeterFilterReply.NEUTRAL
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
            values += tag.value
        }
        return MeterFilterReply.NEUTRAL
    }

    /** Valores distintos aceitos para um par (nome, tag). Serve aos testes do teto. */
    fun acceptedValues(name: String, tagKey: String): Int = seen["$name|$tagKey"]?.size ?: 0

    private companion object {
        val LOG: System.Logger =
            System.getLogger("br.com.empresa.sdui.adapters.observability.CardinalityGuardMeterFilter")
    }
}
