package br.com.empresa.sdui.core.model

import java.time.Instant

data class ComposedScreen(
    val surface: String,
    val platform: ClientPlatform,
    val schemaVersion: String,
    val specRevisionId: String,
    val skeletonId: String,
    val skeletonHash: String,
    val etag: String,
    val generatedAt: Instant,
    val locale: String,
    val channel: Channel,
    val fallback: Boolean,
    val fallbackReason: FallbackReason,
    val omitted: List<OmittedSection>,
    val client: ClientContext,
    val targeting: Targeting,
    val experience: String,
    val skeleton: Skeleton,
    val sections: List<Section>,
) {
    fun analyticsSectionCount(): Int = sections.size
}

data class TreeCacheKey(
    val surface: String,
    val platform: ClientPlatform,
    val schema: String,
    val appMajorMinor: String,
    val capsHash: String,
    val channel: Channel,
) {
    fun wire(): String =
        RedisKeys.tree(surface, platform, schema, appMajorMinor, capsHash, channel)
}

object RedisKeys {
    fun spec(specRevisionId: String, platform: ClientPlatform): String =
        "sdui:spec:$specRevisionId:${platform.wire()}"

    fun tree(
        surface: String,
        platform: ClientPlatform,
        schema: String,
        appMajorMinor: String,
        capsHash: String,
        channel: Channel,
    ): String = "sdui:tree:$surface:${platform.wire()}:$schema:$appMajorMinor:$capsHash:${channel.wire()}"

    fun section(projection: String, id: String): String = "sdui:section:$projection:$id"

    fun lastGood(surface: String, platform: ClientPlatform, channel: Channel): String =
        "sdui:lastgood:$surface:${platform.wire()}:${channel.wire()}"

    fun singleflight(treeKey: String): String = "sdui:sf:$treeKey"

    fun containsUserId(key: String): Boolean = key.contains("userId", ignoreCase = true)
}

object ETagFactory {
    fun of(specRevisionId: String, platform: ClientPlatform, schemaVersion: String): String =
        "W/\"$specRevisionId-${platform.wire()}-$schemaVersion\""
}
