package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCacheInvalidationOutbox
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryDiffStore
import br.com.empresa.sdui.adapters.memory.InMemoryGovernance
import br.com.empresa.sdui.adapters.memory.InMemoryHydratedScreenCache
import br.com.empresa.sdui.adapters.memory.InMemoryIdempotencyStore
import br.com.empresa.sdui.adapters.memory.InMemoryLastGoodScreenStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemoryPublishRequestStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecCache
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.memory.InMemoryTransactionalUnitOfWork
import br.com.empresa.sdui.orchestrator.port.outbound.AuditLogStore
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidationOutbox
import br.com.empresa.sdui.orchestrator.port.outbound.CatalogStore
import br.com.empresa.sdui.orchestrator.port.outbound.DiffStore
import br.com.empresa.sdui.orchestrator.port.outbound.HydratedScreenCache
import br.com.empresa.sdui.orchestrator.port.outbound.IdempotencyStore
import br.com.empresa.sdui.orchestrator.port.outbound.LastGoodScreenStore
import br.com.empresa.sdui.orchestrator.port.outbound.PointerStore
import br.com.empresa.sdui.orchestrator.port.outbound.PublishRequestStore
import br.com.empresa.sdui.orchestrator.port.outbound.SkeletonStore
import br.com.empresa.sdui.orchestrator.port.outbound.SpecCache
import br.com.empresa.sdui.orchestrator.port.outbound.SpecStore
import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * Configuracao de persistencia e governanca em memoria RAM (`ADR-021`).
 *
 * ### 1. O que faz
 * Instancia e registra todos os componentes de governanca (specs, skeletons, catalogos, ponteiros,
 * auditoria, idempotencia e transacao) em memoria efemera.
 *
 * ### 2. Para que serve
 * Habilita a execucao padrao (`sdui.persistence.store=memory`) voltada a desenvolvimento local,
 * testes automatizados e demonstracoes em instancia unica sem dependencia do MongoDB.
 *
 * ### 3. Como funciona
 * Ativada condicionalmente pelo Spring quando `sdui.persistence.store` for `memory` (ou ausente).
 * Cria uma autoridade central [InMemoryGovernance] compartilhada que garante atomicidade via snapshots imutaveis.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["store"], havingValue = "memory", matchIfMissing = true)
class MemoryStoreConfiguration {

    /**
     * Autoridade central de estado de governanca em memoria.
     *
     * ### 1. O que faz
     * Cria o gerenciador de snapshots de estado [InMemoryGovernance].
     *
     * ### 2. Para que serve
     * Compartilha o grafo de entidades de governanca entre todos os stores em memoria da instancia.
     *
     * ### 3. Como funciona
     * Utiliza snapshots imutaveis com isolamento de transacao por thread local e atualizacoes atomicas com lock reentrante.
     */
    @Bean
    fun memoryGovernance(): InMemoryGovernance = InMemoryGovernance()

    /**
     * Repositorio de especificacoes de telas (specs) em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [SpecStore] sobre o estado em memoria.
     *
     * ### 2. Para que serve
     * Permite salvar, versionar e consultar especificacoes completas de telas.
     *
     * ### 3. Como funciona
     * Retorna [InMemorySpecStore] integrado ao coordenador [InMemoryGovernance].
     */
    @Bean
    fun specStore(governance: InMemoryGovernance): SpecStore = InMemorySpecStore(governance)

    /**
     * Repositorio de esqueletos estruturais (skeletons) em memoria.
     *
     * ### 1. O que faz
     * Registra o componente [SkeletonStore] operando sobre a memoria RAM.
     *
     * ### 2. Para que serve
     * Armazena as definicoes de slots estruturais e componentes permitidos por surface.
     *
     * ### 3. Como funciona
     * Instancia [InMemorySkeletonStore] permitindo versionamento e consultas por id de skeleton.
     */
    @Bean
    fun skeletonStore(governance: InMemoryGovernance): SkeletonStore = InMemorySkeletonStore(governance)

    /**
     * Repositorio do catalogo de componentes em memoria.
     *
     * ### 1. O que faz
     * Cria e registra o bean [CatalogStore] em heap.
     *
     * ### 2. Para que serve
     * Disponibiliza a lista de componentes aprovados e respectivos schemas de propriedades.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryCatalogStore] conectado a [InMemoryGovernance].
     */
    @Bean
    fun catalogStore(governance: InMemoryGovernance): CatalogStore = InMemoryCatalogStore(governance)

    /**
     * Repositorio de ponteiros de ativacao de telas em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [PointerStore] para ambientes em memoria.
     *
     * ### 2. Para que serve
     * Mapeia surface, plataforma e canal (`stable`/`canary`) para a versao e revisao ativa de spec.
     *
     * ### 3. Como funciona
     * Instancia [InMemoryPointerStore] com suporte a operacoes concorrentes de compare-and-set.
     */
    @Bean
    fun pointerStore(governance: InMemoryGovernance): PointerStore = InMemoryPointerStore(governance)

    /**
     * Repositorio de solicitacoes de publicacao em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [PublishRequestStore] em heap.
     *
     * ### 2. Para que serve
     * Rastreia pedidos de publicacao submetidos ao fluxo maker-checker com estados `PENDING`, `APPROVED`, etc.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryPublishRequestStore] integrado a [InMemoryGovernance].
     */
    @Bean
    fun publishRequestStore(governance: InMemoryGovernance): PublishRequestStore =
        InMemoryPublishRequestStore(governance)

    /**
     * Repositorio de diferencas estruturais (diffs) de specs em memoria.
     *
     * ### 1. O que faz
     * Registra o bean [DiffStore] operando sobre memoria local.
     *
     * ### 2. Para que serve
     * Armazena diferencas calculadas entre revisoes para auditoria e revisao humana no console administrativo.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryDiffStore] indexando deltas estruturais no estado compartilhado.
     */
    @Bean
    fun diffStore(governance: InMemoryGovernance): DiffStore = InMemoryDiffStore(governance)

    /**
     * Armazenamento de trilha de auditoria em memoria.
     *
     * ### 1. O que faz
     * Cria e disponibiliza o [AuditLogStore] baseado em colecao append-only em heap.
     *
     * ### 2. Para que serve
     * Registra eventos de governanca (submissoes, aprovacoes, reversoes) para fins de conformidade e rastreabilidade.
     *
     * ### 3. Como funciona
     * Instancia [InMemoryAuditLogStore] com politica de retencao FIFO com teto maximo de eventos.
     */
    @Bean
    fun auditLogStore(governance: InMemoryGovernance): AuditLogStore = InMemoryAuditLogStore(governance = governance)

    /**
     * Armazenamento de registros de idempotencia em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao em memoria de [IdempotencyStore].
     *
     * ### 2. Para que serve
     * Impede a reexecucao duplicada de chamadas administrativas mutantes com mesma `Idempotency-Key`.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryIdempotencyStore] com reserva atomica em duas fases, timeout de expiracao e janela de TTL.
     */
    @Bean
    fun idempotencyStore(clock: Clock, properties: SduiProperties, governance: InMemoryGovernance): IdempotencyStore =
        InMemoryIdempotencyStore(
            clock = clock,
            ttl = Duration.ofSeconds(properties.idempotencyTtlSeconds),
            maxEntries = properties.idempotencyMaxKeys,
            reservationTimeout = Duration.ofSeconds(properties.idempotencyReservationTimeoutSeconds),
            governance = governance,
        )

    /**
     * Unidade de trabalho transacional em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [TransactionalUnitOfWork] para operacoes em memoria.
     *
     * ### 2. Para que serve
     * Assegura atomicidade completa na publicacao de telas, unificando mutacoes de specs, ponteiros e auditoria.
     *
     * ### 3. Como funciona
     * Instancia [InMemoryTransactionalUnitOfWork] que comita ou descarta o snapshot transacional de [InMemoryGovernance].
     */
    @Bean
    fun transactionalUnitOfWork(governance: InMemoryGovernance): TransactionalUnitOfWork =
        InMemoryTransactionalUnitOfWork(governance)

    /**
     * Fila transacional de saida (Outbox) de invalidacoes de cache em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [CacheInvalidationOutbox] para persistencia em memoria.
     *
     * ### 2. Para que serve
     * Enfileira intencoes de invalidacao de cache para serem processadas de forma confiavel pelo relay.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryCacheInvalidationOutbox] armazenando registros de invalidacao no estado da governanca.
     */
    @Bean
    fun cacheInvalidationOutbox(governance: InMemoryGovernance): CacheInvalidationOutbox =
        InMemoryCacheInvalidationOutbox(governance = governance)
}

/**
 * Configuracao de caches em memoria RAM do processo (`ADR-021`).
 *
 * ### 1. O que faz
 * Instancia os adaptadores locais em memoria para os caches de telas hidratadas, last good e specs.
 *
 * ### 2. Para que serve
 * Fornece aceleracao de leitura de ultrabaixa latencia no hot path quando o uso do Redis nao esta ativado (`sdui.persistence.cache=memory`).
 *
 * ### 3. Como funciona
 * Ativado condicionalmente por `@ConditionalOnProperty` quando `sdui.persistence.cache` for `memory` (ou ausente).
 * Opera diretamente sobre estruturas concorrentes no heap da JVM com tetos estritos de capacidade.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["cache"], havingValue = "memory", matchIfMissing = true)
class MemoryCacheConfiguration {

    /**
     * Cache de arvores de telas montadas em memoria.
     *
     * ### 1. O que faz
     * Cria e registra o bean [HydratedScreenCache] baseado em heap local.
     *
     * ### 2. Para que serve
     * Armazena arvores de tela prontas para entrega, evitando recombinacao repetida a cada requisicao.
     *
     * ### 3. Como funciona
     * Retorna [InMemoryHydratedScreenCache] com teto estrito de entradas via semaforo atomico e remocao de expiradas.
     */
    @Bean
    fun hydratedScreenCache(properties: SduiProperties): HydratedScreenCache =
        InMemoryHydratedScreenCache(properties.treeCacheMaxEntries)

    /**
     * Armazenamento de contingencia de ultima tela valida (Last Good) em memoria.
     *
     * ### 1. O que faz
     * Registra o componente [LastGoodScreenStore] operando sobre a memoria RAM.
     *
     * ### 2. Para que serve
     * Garante o patamar de fallback gracioso da escada de resiliencia (`ADR-007`) sob falhas de dependencias.
     *
     * ### 3. Como funciona
     * Instancia [InMemoryLastGoodScreenStore] indexando telas por surface, plataforma e canal com timestamp UTC.
     */
    @Bean
    fun lastGoodScreenStore(clock: Clock): LastGoodScreenStore = InMemoryLastGoodScreenStore(clock)

    /**
     * Cache de especificacoes (specs) em memoria.
     *
     * ### 1. O que faz
     * Registra a implementacao de [SpecCache] em heap.
     *
     * ### 2. Para que serve
     * Otimiza a etapa de Select do pipeline SDUI, evitando consultas repetidas ao store de governanca.
     *
     * ### 3. Como funciona
     * Retorna [InMemorySpecCache] chaveado por id de revisao de spec e plataforma.
     */
    @Bean
    fun specCache(): SpecCache = InMemorySpecCache()
}
