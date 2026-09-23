package br.com.empresa.sdui.adapters.configuration

import br.com.empresa.sdui.adapters.memory.InMemoryAuditLogStore
import br.com.empresa.sdui.adapters.memory.InMemoryCacheInvalidationOutbox
import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryDiffStore
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
 * Governanca em memoria (`sdui.persistence.store=memory`, o padrao).
 *
 * Serve a desenvolvimento, testes e demonstracao em instancia unica: nada sobrevive a restart nem
 * e compartilhado entre pods, e a unidade de trabalho da exclusao mutua sem rollback.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["store"], havingValue = "memory", matchIfMissing = true)
class MemoryStoreConfiguration {
    @Bean
    fun specStore(): SpecStore = InMemorySpecStore()

    @Bean
    fun skeletonStore(): SkeletonStore = InMemorySkeletonStore()

    @Bean
    fun catalogStore(): CatalogStore = InMemoryCatalogStore()

    @Bean
    fun pointerStore(): PointerStore = InMemoryPointerStore()

    @Bean
    fun publishRequestStore(): PublishRequestStore = InMemoryPublishRequestStore()

    @Bean
    fun diffStore(): DiffStore = InMemoryDiffStore()

    @Bean
    fun auditLogStore(): AuditLogStore = InMemoryAuditLogStore()

    @Bean
    fun idempotencyStore(clock: Clock, properties: SduiProperties): IdempotencyStore =
        InMemoryIdempotencyStore(
            clock = clock,
            ttl = Duration.ofSeconds(properties.idempotencyTtlSeconds),
            maxEntries = properties.idempotencyMaxKeys,
            reservationTimeout = Duration.ofSeconds(properties.idempotencyReservationTimeoutSeconds),
        )

    @Bean
    fun transactionalUnitOfWork(): TransactionalUnitOfWork = InMemoryTransactionalUnitOfWork()

    @Bean
    fun cacheInvalidationOutbox(): CacheInvalidationOutbox = InMemoryCacheInvalidationOutbox()
}

/** Caches em memoria (`sdui.persistence.cache=memory`, o padrao), locais a cada processo. */
@Configuration
@ConditionalOnProperty(prefix = "sdui.persistence", name = ["cache"], havingValue = "memory", matchIfMissing = true)
class MemoryCacheConfiguration {
    @Bean
    fun hydratedScreenCache(properties: SduiProperties): HydratedScreenCache =
        InMemoryHydratedScreenCache(properties.treeCacheMaxEntries)

    @Bean
    fun lastGoodScreenStore(clock: Clock): LastGoodScreenStore = InMemoryLastGoodScreenStore(clock)

    @Bean
    fun specCache(): SpecCache = InMemorySpecCache()
}
