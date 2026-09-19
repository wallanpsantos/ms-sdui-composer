package br.com.empresa.sdui.adapters.mongo.document

data class SpecDocument(
    val id: String,
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val parentRevision: Int?,
    val status: String,
    val surface: String,
    val platform: String,
    val channel: String,
    val skeletonId: String,
    val skeletonRevision: Int,
    val targeting: Map<String, Any?>,
    val appVersionMinOrdinal: Long,
    val appVersionMaxOrdinal: Long?,
    val sections: List<Map<String, Any?>>,
    val checksum: String,
    val publishedAt: String?,
    val publishedBy: String?,
    val madeBy: String,
    val experience: String,
)

data class SkeletonDocument(
    val id: String,
    val skeletonId: String,
    val revision: Int,
    val surface: String,
    val layout: String,
    val slots: List<Map<String, Any?>>,
    val status: String,
)

data class CatalogDocument(
    val id: String,
    val components: List<Map<String, Any?>>,
)

data class PointerDocument(
    val id: String,
    val surface: String,
    val platform: String,
    val channel: String,
    val specId: String?,
    val specRevisionId: String?,
    val previousSpecRevisionId: String?,
    val version: Long,
)

data class PublishRequestDocument(
    val id: String,
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val surface: String,
    val platform: String,
    val channel: String,
    val makerId: String,
    val status: String,
    val checkerId: String?,
    val reason: String?,
)

data class DiffDocument(
    val id: String,
    val specId: String,
    val fromRevision: Int?,
    val toRevision: Int,
    val added: List<Map<String, Any?>>,
    val removed: List<Map<String, Any?>>,
    val changed: List<Map<String, Any?>>,
    val requiredOccupancy: List<Map<String, Any?>>,
)

data class AuditDocument(
    val id: String,
    val ts: String,
    val actorId: String,
    val role: String,
    val action: String,
    val surface: String,
    val platform: String,
    val channel: String,
    val specId: String?,
    val fromRevision: String?,
    val toRevision: String?,
    val requestId: String?,
)

data class IdempotencyDocument(
    val id: String,
    val operation: String,
    val resultRef: String,
)

object MongoIndexCatalog {
    val uniqueKeys: List<String> = listOf(
        "pointers.surface+platform+channel",
        "specs.specId+revision",
        "diffs.specId+fromRev+toRev",
    )
}
