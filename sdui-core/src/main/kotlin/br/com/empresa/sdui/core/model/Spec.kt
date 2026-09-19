package br.com.empresa.sdui.core.model

import java.time.Instant

data class Targeting(
    val platform: ClientPlatform,
    val appVersion: VersionRange,
    val osVersion: VersionRange?,
    val schemaVersion: VersionRange,
    val requiredCapabilities: List<Capability>,
    val priority: Int,
    val band: String,
) {
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean {
        if (platform != context.platform) return false
        val requestedSchema = SemVer.parse(context.schemaVersion) ?: return false
        if (!schemaVersion.contains(requestedSchema)) return false
        if (!appVersion.contains(context.appVersion)) return false
        val os = osVersion
        val clientOs = context.osVersion
        if (os != null && clientOs != null && !os.contains(clientOs)) return false
        return requiredCapabilities.all { it in effectiveCaps }
    }

    fun wireMin(): String = appVersion.min.toString()
    fun wireMax(): String? = appVersion.max?.toString()
    fun wireOsMin(): String? = osVersion?.min?.toOsString()
}

data class Spec(
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val parentRevision: Int?,
    val status: SpecStatus,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val skeletonId: String,
    val skeletonRevision: Int,
    val targeting: Targeting,
    val sections: List<Section>,
    val checksum: String,
    val publishedAt: Instant?,
    val publishedBy: String?,
    val madeBy: String,
    val experience: String,
) {
    fun matches(context: ClientContext, effectiveCaps: Set<Capability>): Boolean =
        status == SpecStatus.PUBLISHED && targeting.matches(context, effectiveCaps)
}

data class Pointer(
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val specId: String?,
    val specRevisionId: String?,
    val previousSpecRevisionId: String?,
    val version: Long,
)

data class PublishRequest(
    val requestId: String,
    val specId: String,
    val revision: Int,
    val specRevisionId: String,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val makerId: String,
    val status: PublishRequestStatus,
    val checkerId: String? = null,
    val reason: String? = null,
)

data class DiffEntry(
    val path: String,
    val change: String,
    val from: String? = null,
    val to: String? = null,
)

data class SpecDiff(
    val specId: String,
    val fromRevision: Int?,
    val toRevision: Int,
    val added: List<DiffEntry>,
    val removed: List<DiffEntry>,
    val changed: List<DiffEntry>,
    val requiredOccupancy: List<DiffEntry>,
)

data class AuditEvent(
    val id: String,
    val ts: java.time.Instant,
    val actorId: String,
    val role: ActorRole,
    val action: String,
    val surface: String,
    val platform: ClientPlatform,
    val channel: Channel,
    val specId: String?,
    val fromRevision: String?,
    val toRevision: String?,
    val requestId: String?,
)

data class IdempotencyRecord(
    val key: String,
    val operation: String,
    val resultRef: String,
)
