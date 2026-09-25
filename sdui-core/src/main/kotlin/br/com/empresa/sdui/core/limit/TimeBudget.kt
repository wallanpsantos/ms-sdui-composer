package br.com.empresa.sdui.core.limit

import java.time.Duration

/**
 * Gestor e rastreador do orçamento de tempo total alocado para uma requisição de composição de tela.
 *
 * ### 1. O que faz
 * Monitora continuamente o tempo decorrido e calcula o saldo de tempo restante para a requisição de
 * composição Server-Driven UI, fracionando o orçamento entre as fases do pipeline.
 *
 * ### 2. Para que serve
 * Implementa a política inegociável de resiliência e concorrência estabelecida na `ADR-014` e no
 * `AGENTS.md`: **orçamento limita espera, nunca trabalho**. No ecossistema de BFF Server-Driven UI
 * operando sobre Virtual Threads (Java 25 LTS), as esperas em filas de concorrência — como a
 * admissão no bulkhead e a espera pela computação do líder no singleflight — devem respeitar o prazo
 * máximo que o cliente móvel (iOS ou Android) está disposto a aguardar. Uma composição já iniciada,
 * contudo, nunca é interrompida ou abortada no meio do processamento; abortar trabalho iniciado
 * causaria cascatas de erros HTTP 503 falsos decorrentes de aquecimento de JVM (cold start) ou
 * oscilações transitórias de latência quando o fallback `lastGood` ainda estivesse vazio.
 *
 * ### 3. Como funciona
 * Recebe a duração [total] contratada para a requisição (derivada do SLO de borda) e registra o
 * instante de início através da função monotônica [nanoTime] (por padrão [System.nanoTime]). A adoção
 * de nanoTime em vez de relógios de parede (`currentTimeMillis`) assegura imunidade contra ajustes
 * de sincronização NTP no sistema operacional, os quais poderiam fazer o relógio retroceder e
 * corromper o cálculo gerando prazos negativos ou eternos. Cada etapa de espera consome frações do
 * orçamento por meio de [stage], que seleciona o menor valor entre o teto próprio da etapa e o saldo
 * [remaining] disponível. Caso o prazo total seja ultrapassado, o orquestrador emite a métrica
 * de deadline excedido sem contudo cancelar tarefas síncronas em execução no adapter.
 *
 * @param total Prazo temporal total concedido para o atendimento da requisição pelo BFF.
 * @param nanoTime Provedor do carimbo de tempo monotônico em nanossegundos (injetável para testes unitários determinísticos).
 */
class TimeBudget(
    private val total: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startNanos: Long = nanoTime()

    /**
     * Mede o tempo já transcorrido desde a inicialização do orçamento.
     *
     * ### 1. O que faz
     * Calcula e devolve a duração de tempo real decorrida entre o carimbo de criação da requisição e o
     * instante da chamada.
     *
     * ### 2. Para que serve
     * Fornece telemetria de latência para diagnóstico interno, enriquecimento de logs estruturados e
     * emissão de métricas de observabilidade do pipeline de composição.
     *
     * ### 3. Como funciona
     * Subtrai o carimbo de nanossegundos de início (`startNanos`) da leitura monotônica corrente fornecida
     * por [nanoTime], convertendo a diferença de nanossegundos em uma instância imutável de [Duration].
     *
     * @return [Duration] representando o tempo total já gasto no processamento da requisição.
     */
    fun elapsed(): Duration = Duration.ofNanos(nanoTime() - startNanos)

    /**
     * Informa o saldo de tempo restante disponível no orçamento da requisição.
     *
     * ### 1. O que faz
     * Calcula o tempo residual que a requisição ainda tem permissão para aguardar antes de estourar seu prazo total.
     *
     * ### 2. Para que serve
     * Permite que os componentes do pipeline de composição avaliem se ainda existe saldo temporal suficiente
     * para submeter requisições a esperas concorrentes ou se devem degradar o serviço antecipadamente.
     *
     * ### 3. Como funciona
     * Converte o prazo [total] em nanossegundos e subtrai o tempo decorrido até o instante atual. Caso o
     * resultado seja menor ou igual a zero (indicando estouro de prazo), retorna com segurança [Duration.ZERO],
     * prevenindo durações com valores negativos.
     *
     * @return [Duration] com o tempo restante até a expiração do orçamento (nunca negativo).
     */
    fun remaining(): Duration {
        val left = total.toNanos() - (nanoTime() - startNanos)
        return if (left <= 0L) Duration.ZERO else Duration.ofNanos(left)
    }

    /**
     * Avalia se o orçamento temporal alocado para a requisição foi integralmente consumido.
     *
     * ### 1. O que faz
     * Verifica de maneira direta se o tempo transcorrido já atingiu ou ultrapassou o teto estipulado.
     *
     * ### 2. Para que serve
     * Atua como guarda de decisão rápida nas etapas do orquestrador antes de disparar operações de espera
     * dispendiosas, prevenindo a entrada em filas quando a requisição já estiver virtualmente vencida.
     *
     * ### 3. Como funciona
     * Compara o tempo decorrido `(nanoTime() - startNanos)` diretamente contra o prazo total em nanossegundos.
     * Retorna `true` se o tempo decorrido for maior ou igual ao total, indicando que qualquer nova espera
     * apenas postergaria uma falha inevitável de latência.
     *
     * @return `true` se o prazo total tiver expirado; `false` caso ainda reste tempo disponível.
     */
    fun isExhausted(): Boolean = (nanoTime() - startNanos) >= total.toNanos()

    /**
     * Calcula o tempo limite máximo alocado para uma fase ou etapa específica de espera.
     *
     * ### 1. O que faz
     * Arbitra o prazo concedido a uma etapa de espera individual, limitando-o pelo menor valor entre o
     * teto configurado para a etapa e o saldo restante do orçamento global da requisição.
     *
     * ### 2. Para que serve
     * Garante que uma fase intermediária do pipeline (como a aquisição de permissão no bulkhead ou a espera
     * pelo líder no singleflight) jamais consuma mais tempo do que seu próprio teto pré-configurado, nem
     * exceda o tempo restante que o cliente móvel aguardará, honrando a diretriz da `ADR-014`.
     *
     * ### 3. Como funciona
     * Executa a função `minOf` comparando o saldo retornado por [remaining] contra o [cap] estabelecido
     * para a fase. Se o saldo restante for mais restritivo que o cap, a etapa herda o saldo restante; caso
     * contrário, a etapa opera sob seu próprio cap configurado.
     *
     * @param cap Teto máximo de duração admitido para a etapa de espera específica.
     * @return [Duration] efetiva calculada para a etapa, limitada pelo saldo remanescente do orçamento.
     */
    fun stage(cap: Duration): Duration = minOf(remaining(), cap)
}
