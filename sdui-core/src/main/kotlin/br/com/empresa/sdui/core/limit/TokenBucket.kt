package br.com.empresa.sdui.core.limit

import br.com.empresa.sdui.core.model.ClientPlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Chave composta que identifica uma coorte ou cliente sujeito a controle de taxa no hot path.
 *
 * ### 1. O que faz
 * Agrupa o identificador de requisição do cliente e sua respectiva plataforma operacional móvel em uma
 * estrutura imutável utilizada como chave do limitador de taxa.
 *
 * ### 2. Para que serve
 * Permite segmentar as cotas de requisições de forma justa e isolada. Como os headers do BFF no hot path
 * de composição não são autenticados via JWT no nível de domínio (a autenticação do usuário ocorre no API Gateway),
 * a chave deriva de headers não-autenticados (`Client-Platform`, `Client-Build`, ou endereço IP). Esse limitador
 * existe primordialmente para proteger a capacidade computacional do serviço BFF contra abusos e DoS, e não
 * para impor limites de negócio por usuário correntista.
 *
 * ### 3. Como funciona
 * `Data class` imutável que implementa nativamente igualdade por valor (`equals` e `hashCode`), permitindo
 * indexação eficiente em tabelas hash concorrentes ([ConcurrentHashMap]).
 *
 * @property identity Identificador textual da coorte ou cliente (ex: combinação de versão/build ou IP).
 * @property platform Plataforma do cliente móvel ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]), garantindo isolamento total de limites entre sistemas operacionais.
 */
data class RateLimitKey(
    val identity: String,
    val platform: ClientPlatform,
)

/**
 * Limitador de taxa (*rate limiter*) thread-safe em memória baseado no algoritmo de Token Bucket por identidade.
 *
 * ### 1. O que faz
 * Controla a admissão de requisições por chave de cliente ou coorte, debitando tokens de um balde com recarga
 * contínua e rejeitando o tráfego excedente com contenção de memória rígida.
 *
 * ### 2. Para que serve
 * Protege a infraestrutura do BFF contra picos de tráfego, rajadas (*bursts*) e ataques de negação de serviço (DoS),
 * garantindo estabilidade e disponibilidade previsível. Em conformidade com a Seção 19 do `AGENTS.md`, elimina
 * qualquer uso de locks globais no hot path: o controle de concorrência é granular por chave. Além disso, impõe
 * um teto rígido de retenção em memória ([maxKeys]), prevenindo vazamentos de heap provocados por rotação maliciosa
 * ou acidental de identificadores arbitrários em headers HTTP.
 *
 * ### 3. Como funciona
 * Armazena o estado de cada limitador em um [ConcurrentHashMap]. Ao receber uma requisição em [tryConsume], localiza
 * atomicamente o bucket da chave utilizando [ConcurrentHashMap.compute], que bloqueia apenas a entrada específica
 * daquela chave (através da trava do bin da tabela hash), permitindo que centenas de outras requisições para clientes
 * distintos operem em paralelo sem contenção. Quando o mapa atinge a capacidade máxima [maxKeys], dispara um ciclo
 * cooperativo e assíncrono de poda ([evict]) protegido por operação atômica de compare-and-set ([AtomicLong]),
 * descartando clientes ociosos ou cujos baldes já foram integralmente restabelecidos.
 *
 * @param capacity Capacidade máxima de tokens acumuláveis em cada bucket (tamanho do burst admitido).
 * @param refillPerSecond Taxa de reposição de tokens por segundo para cada chave ativa.
 * @param maxKeys Teto máximo de chaves distintas mantidas simultaneamente no mapa em memória.
 * @param idleEvictionMs Tempo de inatividade em milissegundos após o qual uma chave ociosa torna-se elegível para poda.
 * @param minEvictIntervalMs Intervalo mínimo de tempo entre varreduras consecutivas de poda para amortizar o custo computacional de O(N).
 * @param clockMs Função provedora do carimbo temporal em milissegundos (injetável para testes unitários determinísticos).
 */
class TokenBucketRateLimiter(
    private val capacity: Long = 100,
    private val refillPerSecond: Long = 100,
    private val maxKeys: Int = 100_000,
    private val idleEvictionMs: Long = 600_000,
    private val minEvictIntervalMs: Long = 1_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Estado mutável instantâneo do saldo de tokens e carimbo de recarga de uma identidade.
     *
     * ### 1. O que faz
     * Encapsula o saldo numérico fracionário de tokens disponíveis e o registro do instante temporal da última reposição.
     *
     * ### 2. Para que serve
     * Permite o cálculo matemático contínuo do acréscimo de tokens em função do tempo decorrido, suportando taxas
     * fracionárias de consumo e recomposição sem a necessidade de agendamentos periódicos em background (background threads).
     *
     * ### 3. Como funciona
     * Estrutura imutável substituída a cada consumo sob a trava de granularidade fina do nó da tabela hash.
     * Mantém [tokens] como `Double` para precisão fracionária e [lastRefillMs] em milissegundos para medição de intervalo.
     *
     * @property tokens Saldo atualizado de tokens restantes no balde.
     * @property lastRefillMs Carimbo de data/hora em milissegundos da última operação de consumo ou recarga.
     */
    private class Bucket(val tokens: Double, val lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<RateLimitKey, Bucket>()
    private val lastEvictMs = AtomicLong(0)

    /**
     * Tenta debitar atomicamente 1 token da cota alocada para a chave informada.
     *
     * ### 1. O que faz
     * Avalia o saldo disponível para a [key] especificada após a recomposição temporal de tokens, debitando
     * exatamente 1 token se houver saldo suficiente e concedendo a permissão de admissão.
     *
     * ### 2. Para que serve
     * Atua como a porta de entrada de admissão de tráfego no filtro de rate limit do BFF. Se retornar `true`, a
     * requisição segue para o pipeline de composição de tela; se retornar `false`, a requisição é interceptada
     * imediatamente e responde com HTTP 429 Too Many Requests e cabeçalho `Retry-After`.
     *
     * ### 3. Como funciona
     * 1. Consulta o relógio corrente via [clockMs].
     * 2. Se a quantidade de chaves residentes atingir ou superar [maxKeys], aciona [evict] para podar entradas ociosas.
     * 3. Se após a tentativa de poda o mapa persistir saturado e a [key] não for previamente conhecida, recusa a requisição
     *    retornando `false`. Isso blinda a memória do servidor contra estouro de heap gerado por ataques de chaves efêmeras.
     * 4. Executa [ConcurrentHashMap.compute] sobre a [key]. Sob o bloqueio exclusivo do bin daquela chave, recalcula o
     *    saldo via [refill]. Se o saldo for `>= 1.0`, debita 1.0 token, atualiza o timestamp e marca `granted = true`.
     * 5. Retorna o valor booleano resultante da concessão.
     *
     * @param key Chave de identificação composta da requisição do cliente.
     * @return `true` se 1 token foi concedido com sucesso; `false` se a cota foi excedida ou a memória estiver saturada.
     */
    fun tryConsume(key: RateLimitKey): Boolean {
        val now = clockMs()
        if (buckets.size >= maxKeys) evict(now)
        // Saturado mesmo depois da poda significa que ha maxKeys identidades ativas. Recusar
        // identidade nova mantem o teto de memoria rigido; quem ja tem bucket continua atendido
        // normalmente. A checagem nao e atomica com o compute, entao sob corrida o mapa pode
        // passar do teto por algumas entradas — o que importa e nao crescer sem limite.
        if (buckets.size >= maxKeys && !buckets.containsKey(key)) return false
        var granted = false
        // compute aplica a funcao de remapeamento sob o lock do bin da chave: exclusao mutua por
        // identidade, sem um lock unico serializando todo o hot path.
        buckets.compute(key) { _, current ->
            val available = refill(current ?: Bucket(capacity.toDouble(), now), now)
            granted = available >= 1.0
            Bucket(if (granted) available - 1.0 else available, now)
        }
        return granted
    }

    /**
     * Informa a quantidade de identidades e buckets atualmente mantidos na memória residente.
     *
     * ### 1. O que faz
     * Retorna a contagem instantânea de chaves ativas presentes no mapa concorrente interno.
     *
     * ### 2. Para que serve
     * Fornece visibilidade para métricas de ocupação de memória do limitador de taxa e suporta validações
     * determinísticas nos testes de regressão dos mecanismos de saturação e poda.
     *
     * ### 3. Como funciona
     * Consulta diretamente a propriedade de tamanho ([ConcurrentHashMap.size]) da coleção concorrente interna.
     *
     * @return Total de entradas ativas mantidas na tabela hash neste instante.
     */
    fun residentKeys(): Int = buckets.size

    /**
     * Recalcula matematicamente o saldo de tokens de um bucket com base no tempo decorrido.
     *
     * ### 1. O que faz
     * Computa a quantidade de novos tokens gerados desde a última recarga e soma ao saldo pré-existente, respeitando
     * a capacidade máxima estipulada.
     *
     * ### 2. Para que serve
     * Implementa o princípio central do algoritmo Token Bucket contínuo: em vez de um timer acordando a cada segundo
     * para reabastecer coleções, o reabastecimento é avaliado sob demanda (*lazy evaluation*) no instante exato do consumo.
     *
     * ### 3. Como funciona
     * Calcula o intervalo em segundos decorrido entre [now] e `bucket.lastRefillMs`. Multiplica esse intervalo pela
     * taxa [refillPerSecond], adiciona aos tokens anteriores e aplica a restrição superior através de [Double.coerceAtMost]
     * em relação a [capacity].
     *
     * @param bucket Instância do balde contendo o estado anterior.
     * @param now Carimbo de tempo em milissegundos do instante corrente.
     * @return Saldo recalculado de tokens disponível (limitado entre 0.0 e a capacidade total).
     */
    private fun refill(bucket: Bucket, now: Long): Double {
        val elapsedSec = (now - bucket.lastRefillMs).coerceAtLeast(0) / 1000.0
        return (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity.toDouble())
    }

    /**
     * Executa a purga atômica e cooperativa de chaves ociosas e buckets saturados do mapa residente.
     *
     * ### 1. O que faz
     * Percorre as entradas da tabela de limites e remove aquelas que ultrapassaram o tempo limite de inatividade
     * ([idleEvictionMs]) ou cujos tokens já atingiram a capacidade total ([capacity]).
     *
     * ### 2. Para que serve
     * Garante a invariante inegociável de contenção de recursos: impede que o mapa de buckets cresça indefinidamente
     * com clientes que já completaram suas sessões ou que acessaram o serviço esporadicamente. Além disso, descartar
     * um bucket cheio é matematicamente inócuo, pois um cliente novo inicia exatamente com a capacidade máxima.
     *
     * ### 3. Como funciona
     * 1. Verifica se o tempo decorrido desde a última varredura é menor que [minEvictIntervalMs]. Em caso afirmativo,
     *    aborta imediatamente para evitar degradação de performance por varreduras consecutivas O(N) em alta carga.
     * 2. Utiliza [AtomicLong.compareAndSet] sobre [lastEvictMs] para garantir que uma única thread execute a poda por ciclo.
     * 3. Invoca [MutableCollection.removeIf] na coleção de entradas do mapa, avaliando o critério de descarte:
     *    inatividade prolongada (`>= idleEvictionMs`) ou reconstituição completa do saldo (`>= capacity`).
     *
     * @param now Carimbo temporal em milissegundos do momento em que a poda foi disparada.
     */
    private fun evict(now: Long) {
        val last = lastEvictMs.get()
        if (now - last < minEvictIntervalMs) return
        if (!lastEvictMs.compareAndSet(last, now)) return
        buckets.entries.removeIf { entry ->
            now - entry.value.lastRefillMs >= idleEvictionMs ||
                    refill(entry.value, now) >= capacity.toDouble()
        }
    }
}
