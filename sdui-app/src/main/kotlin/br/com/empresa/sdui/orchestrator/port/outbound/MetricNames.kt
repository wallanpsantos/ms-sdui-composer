package br.com.empresa.sdui.orchestrator.port.outbound

import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SurfaceDefinition

/**
 * Nomes de todas as metricas que o servico emite, num lugar so.
 *
 * O cenario de carga versionado e os runbooks referenciam estes nomes; um teste confere que tudo
 * o que eles citam existe aqui. Nome e sempre constante — a dimensao vai em tag.
 */
object MetricNames {
    const val COMPOSE_DURATION: String = "compose.duration"
    const val COMPOSE_HIT: String = "compose.hit"
    const val COMPOSE_MISS: String = "compose.miss"
    const val COMPOSE_RATE_LIMITED: String = "compose.rate_limited"
    const val COMPOSE_BULKHEAD_REJECTED: String = "compose.bulkhead.rejected"
    const val COMPOSE_DEADLINE_EXCEEDED: String = "compose.deadline.exceeded"
    const val COMPOSE_SINGLEFLIGHT_WAIT: String = "compose.singleflight.wait"
    const val COMPOSE_SINGLEFLIGHT_RECHECK_HIT: String = "compose.singleflight.recheck_hit"
    const val COMPOSE_UNAVAILABLE: String = "compose.unavailable"
    const val COMPOSE_FALLBACK: String = "compose.fallback"
    const val COMPOSE_FALLBACK_AGE: String = "compose.fallback.age.ms"
    const val COMPOSE_FALLBACK_EXPIRED: String = "compose.fallback.expired"
    const val SELECT_NO_CANDIDATE: String = "select.no_candidate"
    const val SECTION_OMITTED: String = "section.omitted"
    const val SECTION_HYDRATE: String = "section.hydrate.ms"
    const val MAPPING: String = "mapping.ms"
    const val SERIALIZE: String = "serialize.ms"
    const val PAYLOAD_BYTES: String = "payload.bytes"
    const val STORE_FAILURE: String = "store.failure"
    const val CACHE_WRITE_FAILURE: String = "cache.write.failure"
    const val CACHE_WRITE_SKIPPED: String = "cache.write.skipped"
    const val CACHE_OPERATION: String = "cache.operation.ms"
    const val CACHE_INVALIDATION_APPLIED: String = "cache.invalidation.applied"
    const val CACHE_INVALIDATION_FAILED: String = "cache.invalidation.failed"
    const val CACHE_INVALIDATION_PENDING: String = "cache.invalidation.pending"
    const val ADMIN_ERROR: String = "admin.error"
    const val ADMIN_CATALOG_UPSERT: String = "admin.catalog.upsert"
    const val ADMIN_SKELETON_UPSERT: String = "admin.skeleton.upsert"
    const val ADMIN_SPEC_DRAFT: String = "admin.spec.draft"
    const val ADMIN_PUBLISH_OPEN: String = "admin.publish.open"
    const val ADMIN_PUBLISH_APPROVED: String = "admin.publish.approved"
    const val ADMIN_PUBLISH_REJECTED: String = "admin.publish.rejected"
    const val ADMIN_ROLLBACK: String = "admin.rollback"
    const val ADMIN_AUDIT_LIST: String = "admin.audit.list"
    const val SERVER_UNEXPECTED_ERROR: String = "server.unexpected_error"
    const val RATE_LIMITER_RESIDENT_KEYS: String = "rate_limiter.resident_keys"
    const val BULKHEAD_AVAILABLE_PERMITS: String = "compose.bulkhead.available_permits"

    val ALL: Set<String> = setOf(
        COMPOSE_DURATION, COMPOSE_HIT, COMPOSE_MISS, COMPOSE_RATE_LIMITED, COMPOSE_BULKHEAD_REJECTED,
        COMPOSE_DEADLINE_EXCEEDED, COMPOSE_SINGLEFLIGHT_WAIT, COMPOSE_SINGLEFLIGHT_RECHECK_HIT,
        COMPOSE_UNAVAILABLE, COMPOSE_FALLBACK, COMPOSE_FALLBACK_AGE, COMPOSE_FALLBACK_EXPIRED,
        SELECT_NO_CANDIDATE, SECTION_OMITTED, SECTION_HYDRATE, MAPPING, SERIALIZE, PAYLOAD_BYTES,
        STORE_FAILURE, CACHE_WRITE_FAILURE, CACHE_WRITE_SKIPPED, CACHE_OPERATION,
        CACHE_INVALIDATION_APPLIED, CACHE_INVALIDATION_FAILED, CACHE_INVALIDATION_PENDING,
        ADMIN_ERROR, ADMIN_CATALOG_UPSERT, ADMIN_SKELETON_UPSERT,
        ADMIN_SPEC_DRAFT, ADMIN_PUBLISH_OPEN, ADMIN_PUBLISH_APPROVED, ADMIN_PUBLISH_REJECTED,
        ADMIN_ROLLBACK, ADMIN_AUDIT_LIST, SERVER_UNEXPECTED_ERROR, RATE_LIMITER_RESIDENT_KEYS,
        BULKHEAD_AVAILABLE_PERMITS,
    )

    /** Prefixos das metricas proprias, usados pela guarda de cardinalidade do adapter Micrometer. */
    val PREFIXES: Set<String> = ALL.map { it.substringBefore('.') }.toSet()
}

/**
 * Tags de vocabulario fechado para as metricas do pipeline (achado P1 de 2026-09-23).
 *
 * A versao exata do app nao entra em tag: variar `Client-Version` criava um meter por valor,
 * inclusive em requisicoes recusadas pelo limitador. Ela fica no contexto de log. O schema pedido
 * so aparece quando e um schema que o servidor compoe; qualquer outro vira [OTHER].
 */
object MetricTags {
    const val OTHER: String = "other"

    fun schema(requested: String): String = if (requested in MvpCatalog.SUPPORTED_SCHEMA_VERSIONS) requested else OTHER

    fun compose(surface: SurfaceDefinition, context: ClientContext): Map<String, String> = mapOf(
        "surface" to surface.id,
        "platform" to context.platform.wire(),
        "schemaVersion" to schema(context.schemaVersion),
    )
}
