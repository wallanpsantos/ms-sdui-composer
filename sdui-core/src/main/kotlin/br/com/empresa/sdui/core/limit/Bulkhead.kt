package br.com.empresa.sdui.core.limit

import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Resultado discriminado da tentativa de admissão e execução de uma operação sob um [Bulkhead].
 *
 * ### 1. O que faz
 * Modela algebricamente os dois desfechos possíveis ao submeter trabalho a um limitador de concorrência:
 * execução bem-sucedida com retorno de valor ([Executed]) ou recusa imediata por saturação de capacidade ([Rejected]).
 *
 * ### 2. Para que serve
 * Adota o princípio de tipagem funcional explícita onde saturação de capacidade é modelada como um valor de
 * retorno legítimo e não como uma exceção. Em alta volumetria sob concorrência intensa, a recusa por esgotamento
 * de recursos é o comportamento operacional esperado de um limitador saudável; tratar a recusa como exceção
 * acarretaria o custo severo de alocação de stack traces desnecessários na JVM. O chamador (orquestrador)
 * recebe a recusa de forma determinística e aciona a escada de degradação graciosa (`ADR-007`).
 *
 * ### 3. Como funciona
 * Interface selada fechada (`sealed interface`) com variância covariante (`out T`), garantindo exaustividade em
 * expressões `when` no Kotlin. Possui duas ramificações concretas: [Executed], que encapsula o resultado do tipo
 * [T], e [Rejected], um objeto singleton que representa a ausência de permissões disponíveis dentro do timeout.
 *
 * @param T Tipo do valor produzido pelo bloco de processamento protegido.
 */
sealed interface BulkheadOutcome<out T> {

    /**
     * Representação de execução autorizada e concluída com êxito sob a proteção do [Bulkhead].
     *
     * ### 1. O que faz
     * Encapsula o resultado de retorno produzido pelo processamento do trabalho protegido.
     *
     * ### 2. Para que serve
     * Transporta o valor de resposta computado de forma segura e fortemente tipada, indicando que a permissão
     * foi obtida, o trabalho executou e o recurso foi devidamente liberado.
     *
     * ### 3. Como funciona
     * Armazena o [value] gerado pela função lambda fornecida ao bulkhead. Sendo uma `data class`, oferece
     * desestruturação nativa e métodos canônicos de igualdade e representação textual.
     *
     * @param T Tipo do dado retornado.
     * @property value Objeto resultante do processamento.
     */
    data class Executed<T>(val value: T) : BulkheadOutcome<T>

    /**
     * Representação de recusa de admissão por esgotamento de permissões do [Bulkhead].
     *
     * ### 1. O que faz
     * Sinaliza que a requisição não conseguiu obter uma permissão de execução concorrente no tempo limite estipulado.
     *
     * ### 2. Para que serve
     * Notifica o orquestrador sobre a saturação momentânea da dependência ou etapa protegida, permitindo acionar
     * imediatamente a política de degradação (escada de fallback, ADR-007) ou emitir HTTP 503 com `Retry-After`
     * sem desperdiçar recursos aguardando em filas infinitas.
     *
     * ### 3. Como funciona
     * Implementado como um `data object` singleton (sem alocações adicionais em memória). É devolvido de forma
     * imediata pelo método [Bulkhead.withPermit] quando a tentativa de aquisição no semáforo expira.
     */
    data object Rejected : BulkheadOutcome<Nothing>
}

/**
 * Limitador de concorrência e isolador de capacidade baseado em semáforo ([Semaphore]).
 *
 * ### 1. O que faz
 * Impõe um teto rígido ao número de chamadas concorrentes simultâneas a um recurso ou dependência do sistema.
 *
 * ### 2. Para que serve
 * No ecossistema Java 25 com Virtual Threads (`spring.threads.virtual.enabled: true`), o runtime pode criar
 * centenas de milhares de threads leves sob pico de tráfego. Sem um pool de threads nativo limitando a fila
 * de entrada (*thread pool shedding*), todas as requisições poderiam invadir simultaneamente uma dependência lenta,
 * provocando sobrecarga severa de CPU, exaustão de descritores de sockets e indisponibilidade generalizada.
 * O bulkhead restabelece limites locais explícitos por plano operacional: o plano de composição de telas da Home
 * possui seu próprio bulkhead isolado do plano de administração (`/admin/v1/**`), garantindo que uma publicação
 * de especificação ou rollback administrativo lento não consuma a capacidade de atendimento dos clientes móveis.
 *
 * ### 3. Como funciona
 * Encapsula internamente uma instância de [Semaphore] inicializada com a quantidade máxima de permissões simultâneas
 * (`permits`). Cada requisição que entra solicita uma permissão com prazo delimitado através de [withPermit]. Se a
 * permissão for obtida dentro do tempo concedido, o bloco de trabalho é executado e a permissão é incondicionalmente
 * devolvida em um bloco `finally`. Caso o tempo expire antes da obtenção, a chamada é prontamente rejeitada com
 * [BulkheadOutcome.Rejected], protegendo a dependência contra formação de filas ocultas.
 *
 * @param permits Quantidade máxima de execuções concorrentes simultâneas autorizadas para este compartimento.
 */
class Bulkhead(permits: Int) {
    private val semaphore = Semaphore(permits)

    /**
     * Consulta a quantidade de permissões atualmente disponíveis no compartimento.
     *
     * ### 1. O que faz
     * Retorna o número instantâneo de slots de concorrência livres para aquisição imediata no [Bulkhead].
     *
     * ### 2. Para que serve
     * Atende a requisitos de observabilidade, métricas de capacidade em tempo real (Micrometer) e asserções
     * determinísticas em suítes de testes automatizados de saturação de concorrência.
     *
     * ### 3. Como funciona
     * Interroga diretamente o método [Semaphore.availablePermits] do semáforo interno. Trata-se de uma leitura
     * não-bloqueante de precisão instantânea refletindo o estado concorrente no momento da consulta.
     *
     * @return Número de permissões livres neste instante (entre 0 e o teto configurado).
     */
    fun availablePermits(): Int = semaphore.availablePermits()

    /**
     * Executa um bloco de trabalho segurando temporariamente uma permissão de concorrência do [Bulkhead].
     *
     * ### 1. O que faz
     * Tenta adquirir uma permissão do semáforo dentro do tempo limite [timeout] e, se concedida, executa a
     * computação [work], devolvendo o resultado em [BulkheadOutcome.Executed]. Caso contrário, devolve
     * [BulkheadOutcome.Rejected].
     *
     * ### 2. Para que serve
     * Protege operações críticas (leituras de stores, hidratação de seções, orquestração) contra sobrecarga,
     * garantindo tempo de espera delimitado e devolução incondicional da permissão adquirida mesmo em casos
     * de falha ou lançamento de exceções.
     *
     * ### 3. Como funciona
     * 1. Converte o [timeout] fornecido em milissegundos, assegurando valor não-negativo através de [Long.coerceAtLeast].
     * 2. Invoca [Semaphore.tryAcquire] com o tempo calculado. Se a aquisição falhar por esgotamento de tempo,
     *    aborta imediatamente e retorna [BulkheadOutcome.Rejected].
     * 3. Se a permissão for obtida com sucesso, executa o bloco [work] envolto por uma estrutura `try-finally`.
     * 4. O bloco `finally` executa [Semaphore.release] obrigatoriamente, garantindo que nenhum vazamento de
     *    permissões ocorra caso [work] lance uma exceção em tempo de execução.
     * 5. Retorna o resultado envelopado em [BulkheadOutcome.Executed].
     *
     * @param T Tipo do retorno gerado pelo bloco de código executado.
     * @param timeout Tempo limite máximo que a thread aceita aguardar para obter uma permissão livre.
     * @param work Expressão lambda contendo o processamento a ser executado sob proteção de concorrência.
     * @return [BulkheadOutcome.Executed] com o valor produzido ou [BulkheadOutcome.Rejected] em caso de saturação.
     */
    fun <T> withPermit(timeout: Duration, work: () -> T): BulkheadOutcome<T> {
        val waitMs = timeout.toMillis().coerceAtLeast(0L)
        if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return BulkheadOutcome.Rejected
        return try {
            BulkheadOutcome.Executed(work())
        } finally {
            semaphore.release()
        }
    }
}
