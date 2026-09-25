package br.com.empresa.sdui.core.limit

import kotlin.math.roundToLong

/**
 * Calculador de intervalo de espera para o cabeçalho HTTP `Retry-After` com dispersão estocástica (*jitter*).
 *
 * ### 1. O que faz
 * Determina o tempo em segundos que clientes móveis (iOS e Android) devem aguardar antes de retentar uma
 * requisição que foi recusada por limitação de taxa (HTTP 429) ou degradação de serviço (HTTP 503).
 *
 * ### 2. Para que serve
 * Impede a ocorrência catastrófica do fenômeno de *thundering herd* (efeito manada). No BFF de Server-Driven UI,
 * a chave de rate limiting agrega o tráfego por coorte (`plataforma:build`). Quando uma versão amplamente
 * instalada atinge o limite do bucket ou o serviço entra em degradação pontual, centenas de milhares de aparelhos
 * recebem a recusa simultaneamente no mesmo segundo. Se o cabeçalho `Retry-After` retornasse um valor fixo
 * constante (ex: 5 segundos), toda essa coorte despertaria e retornaria ao servidor exatamente no mesmo
 * milissegundo, repetindo e amplificando o pico de sobrecarga que motivou a recusa original. A introdução
 * de jitter desfaz o alinhamento temporal dos retries, convertendo uma manada sincronizada em uma fila
 * suavemente distribuída ao longo do tempo (`ADR-014` e Seção 20 de `AGENTS.md`).
 *
 * ### 3. Como funciona
 * Aplica uma dispersão simétrica uniforme de $\pm 40\%$ ([JITTER_FRACTION]) em torno da base de recuo configurada.
 * A simetria em torno da base (em vez de um acréscimo puramente aditivo) garante que o valor médio esperado do
 * atraso coincida com a base configurada — um jitter exclusivamente aditivo empurraria a média para cima e
 * aumentaria a latência percebida pelo usuário final durante o restabelecimento do serviço.
 */
object RetryAfter {

    /**
     * Amplitude relativa da dispersão de jitter aplicada sobre o tempo base de recuo.
     *
     * ### 1. O que faz
     * Define a fração decimal que delimita a faixa percentual de variação estocástica (+-40%) sobre o tempo base.
     *
     * ### 2. Para que serve
     * Calibra a largura do intervalo de dispersão temporal. Uma fração de 0.4 sobre uma base de 5 segundos espalha
     * as retentativas no intervalo entre 3 e 7 segundos, garantindo entropia suficiente para dissolver picos de tráfego.
     *
     * ### 3. Como funciona
     * Constante numérica imutável de ponto flutuante definida como `0.4` (40%). É utilizada na computação de
     * amplitude de dispersão dentro de [jittered].
     */
    const val JITTER_FRACTION: Double = 0.4

    /**
     * Calcula o valor de recuo em segundos aplicando jitter simétrico sobre a base de tempo.
     *
     * ### 1. O que faz
     * Perturba a base de tempo estipulada em até $\pm 40\%$ com base na fração aleatória fornecida, assegurando
     * que o tempo resultante nunca seja inferior a 1 segundo.
     *
     * ### 2. Para que serve
     * Produz o valor final a ser injetado no cabeçalho HTTP `Retry-After` das respostas 429 (Too Many Requests)
     * e 503 (Service Unavailable) geradas pelo BFF.
     *
     * ### 3. Como funciona
     * 1. Se a [baseSeconds] for menor ou igual a zero, retorna imediatamente `1L` como piso de segurança.
     * 2. Normaliza [randomFraction] limitando-a estritamente ao intervalo `[0.0, 1.0]` via [Double.coerceIn].
     * 3. Calcula o desvio máximo `spread = baseSeconds * JITTER_FRACTION`.
     * 4. Interpola o valor através da equação simétrica `baseSeconds - spread + fraction * 2.0 * spread`.
     * 5. Arredonda o valor para o inteiro longo mais próximo através de [roundToLong] e impõe o piso de segurança
     *    `coerceAtLeast(1L)`, garantindo que clientes nunca recebam tempo nulo ou negativo de recuo.
     * 6. A função recebe [randomFraction] como argumento de entrada em vez de gerá-la internamente, mantendo o
     *    método matematicamente puro, determinístico e 100% testável em testes unitários. Em ambiente produtivo,
     *    o chamador provê um gerador aleatório concorrente (`ThreadLocalRandom.current().nextDouble()`).
     *
     * @param baseSeconds Tempo base de recuo em segundos configurado para o cenário de contenção.
     * @param randomFraction Fração escalar pseudoaleatória compreendida entre `0.0` e `1.0`.
     * @return Valor final em segundos com jitter aplicado, garantido como >= 1 segundo.
     */
    fun jittered(baseSeconds: Long, randomFraction: Double): Long {
        if (baseSeconds <= 0L) return 1L
        val fraction = randomFraction.coerceIn(0.0, 1.0)
        val spread = baseSeconds * JITTER_FRACTION
        val value = baseSeconds - spread + fraction * 2.0 * spread
        return value.roundToLong().coerceAtLeast(1L)
    }
}
