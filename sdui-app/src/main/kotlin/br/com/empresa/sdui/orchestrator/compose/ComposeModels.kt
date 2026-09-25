package br.com.empresa.sdui.orchestrator.compose

import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import java.time.Duration

/**
 * Agrupamento centralizado de prazos, limites de tolerância e orçamentos temporais da composição Server-Driven UI.
 *
 * ### 1. O que faz
 * Reúne em uma única estrutura imutável todas as configurações de SLAs, timeouts de espera,
 * tempos de vida de cache e prazos de recuo estocástico utilizados na orquestração de composição.
 * Governa diretamente o ciclo de vida que transforma um [ComposeRequest] em um [ComposeResult],
 * culminando na entrega de uma [ComposedScreen].
 *
 * ### 2. Para que serve
 * Elimina a dispersão de números mágicos e parâmetros padrão soltos pelo código do orquestrador.
 * Ao consolidar todos os limites em um único objeto, assegura a coerência global dos prazos:
 * a espera por um líder no singleflight ([singleflightWait]) e a espera por permissão no bulkhead
 * ([bulkheadWait]) são obrigatoriamente frações muito menores que o prazo total da requisição ([request]).
 * Implementa a diretriz inegociável da `ADR-014`: **o orçamento limita espera, nunca trabalho**.
 * Interromper uma composição em andamento desperdiçaria CPU e I/O já consumidos; limitar esperas
 * evita que clientes fiquem retidos em filas internas.
 *
 * ### 3. Como funciona
 * É injetado como dependência na instanciação do serviço de composição (`ComposeScreenService`)
 * e de seus coordenadores de resiliência. Durante o pipeline, as durações aqui configuradas são
 * convertidas em orçamentos dinâmicos (`TimeBudget`) que controlam a admissão no bulkhead e as
 * janelas de espera por deduplicação.
 */
data class ComposeBudgets(
    /**
     * Tempo de vida (TTL) de uma árvore de UI no cache de composição rápida (`HydratedScreenCache`).
     *
     * ### 1. O que faz
     * Estipula por quanto tempo uma [ComposedScreen] permanece válida no cache em memória ou distribuído.
     *
     * ### 2. Para que serve
     * Permite absorver a grande maioria das requisições subsequentes sem tocar nos bancos de dados,
     * garantindo latências sub-milissegundo para os clientes móveis.
     *
     * ### 3. Como funciona
     * Configurado por padrão em 60 segundos (`Duration.ofSeconds(60)`), equilibrando frescor dos
     * dados e alívio de carga sobre o servidor.
     */
    val treeTtl: Duration = Duration.ofSeconds(60),

    /**
     * Prazo total limite de paciência estipulado para a requisição de composição.
     *
     * ### 1. O que faz
     * Define o teto máximo de duração de espera aceitável pelo cliente antes do encerramento da chamada.
     *
     * ### 2. Para que serve
     * Serve como limite superior de orçamento temporal. É calibrado de forma generosa (1 segundo)
     * para não abortar requisições em momentos de aquecimento da JVM (*cold start*) em pods recém-inicializados.
     *
     * ### 3. Como funciona
     * Utilizado para inicializar o `TimeBudget` da requisição, balizando as frações de tempo
     * atribuídas às etapas de espera interna.
     */
    val request: Duration = Duration.ofSeconds(1),

    /**
     * Tempo máximo que uma requisição secundária (*waiter*) aguarda pela computação do líder no singleflight.
     *
     * ### 1. O que faz
     * Limita a espera em fila por uma recomposição concorrente compartilhada.
     *
     * ### 2. Para que serve
     * Impede que requisições fiquem indefinidamente bloqueadas caso o líder enfrente lentidão.
     * É deliberadamente muito menor que [request] (150 ms) para permitir que o cliente desista
     * a tempo de buscar a tela no último bom estado conhecido (*last good*) antes de esgotar o prazo do cliente.
     *
     * ### 3. Como funciona
     * Passado como timeout para `ComposeSingleflight.runExclusive`. Caso expire, o waiter recebe
     * `WaitTimeout` e recorre à escada de fallback, sem nunca cancelar a computação do líder.
     */
    val singleflightWait: Duration = Duration.ofMillis(150),

    /**
     * Tempo máximo de tolerância para aquisição de uma permissão no semáforo do bulkhead de leitura.
     *
     * ### 1. O que faz
     * Delimita a tolerância de enfileiramento na entrada do plano de leitura de telas.
     *
     * ### 2. Para que serve
     * Protege o serviço contra saturação extrema. Se em 50 ms uma permissão de Virtual Thread não puder
     * ser concedida, a requisição é rapidamente desviada para o fallback em vez de aguardar exaustão de recursos.
     *
     * ### 3. Como funciona
     * Alimenta a chamada de aquisição temporizada do semáforo do bulkhead, contabilizando métricas de rejeição em caso de recusa.
     */
    val bulkheadWait: Duration = Duration.ofMillis(50),

    /**
     * Idade máxima de tolerância para aceitação de uma tela de contingência (*last good screen*).
     *
     * ### 1. O que faz
     * Estabelece o limiar temporal após o qual um snapshot histórico de tela deixa de ser considerado confiável.
     *
     * ### 2. Para que serve
     * Assegura integridade de negócio: após 24 horas (`Duration.ofHours(24)`), a entrega de um layout
     * excessivamente defasado é considerada mais prejudicial que a indisponibilidade visível (`HTTP 503`).
     *
     * ### 3. Como funciona
     * O coordenador de fallback compara `agora - storedAt` contra este valor. Se a diferença exceder o limite,
     * o snapshot é recusado e retorna-se `ComposeResult.Unavailable`.
     */
    val maxFallbackAge: Duration = Duration.ofHours(24),

    /**
     * Intervalo base em segundos para composição do cabeçalho `Retry-After` em respostas `HTTP 503`.
     *
     * ### 1. O que faz
     * Define o tempo central de recuo sugerido aos clientes móveis quando o serviço está temporariamente indisponível.
     *
     * ### 2. Para que serve
     * Fornece o valor base sobre o qual será aplicado o algoritmo de dispersão estocástica (jitter de ±40%),
     * evitando que uma coorte inteira de dispositivos retorne no mesmo milissegundo (*thundering herd*).
     *
     * ### 3. Como funciona
     * Por padrão fixado em 5 segundos, sendo transformado pelo utilitário `RetryAfter.withJitter` antes do envio.
     */
    val retryAfterSeconds: Long = 5,

    /**
     * Intervalo base em segundos para o cabeçalho `Retry-After` em respostas de rate limit (`HTTP 429`).
     *
     * ### 1. O que faz
     * Determina a duração sugerida de recuo quando uma coorte de clientes consome mais requisições que sua cota.
     *
     * ### 2. Para que serve
     * Alivia a pressão imediata sobre o limitador de taxa (*token bucket*).
     *
     * ### 3. Como funciona
     * Por padrão configurado em 2 segundos, também submetido a jitter estatístico para suavizar a curva de reentrada.
     */
    val rateLimitRetryAfterSeconds: Long = 2,
)
