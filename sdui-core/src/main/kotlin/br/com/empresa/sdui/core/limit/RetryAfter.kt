package br.com.empresa.sdui.core.limit

import kotlin.math.roundToLong

/**
 * Calcula o valor de `Retry-After` com jitter, para a coorte nao voltar toda no mesmo instante.
 *
 * A chave do limitador e plataforma e build — uma coorte, nao um aparelho. Quando um build popular
 * estoura o bucket ou o servico degrada, todos os aparelhos daquele build recebem a recusa no mesmo
 * segundo; um `Retry-After` constante os instruiria a voltar juntos e a repetir o pico que causou a
 * recusa. Espalhar o instante de retorno e o que transforma um rebanho em fila.
 *
 * O jitter e simetrico em torno da base, e nao apenas aditivo, para que a media do atraso continue
 * sendo o valor configurado — um jitter so para cima empurraria toda a distribuicao e alongaria a
 * recuperacao percebida pelo usuario.
 */
object RetryAfter {

    /** Amplitude do jitter, como fracao da base. 0.4 espalha 5s em 3s..7s. */
    const val JITTER_FRACTION: Double = 0.4

    /**
     * Devolve a base perturbada em +-[JITTER_FRACTION], nunca abaixo de 1 segundo.
     *
     * [randomFraction] e recebido de fora, e nao sorteado aqui, para manter a funcao pura: o
     * chamador injeta `ThreadLocalRandom` em producao e um valor fixo no teste.
     */
    fun jittered(baseSeconds: Long, randomFraction: Double): Long {
        if (baseSeconds <= 0L) return 1L
        val fraction = randomFraction.coerceIn(0.0, 1.0)
        val spread = baseSeconds * JITTER_FRACTION
        val value = baseSeconds - spread + fraction * 2.0 * spread
        return value.roundToLong().coerceAtLeast(1L)
    }
}
