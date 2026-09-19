package br.com.empresa.sdui.adapters.redis

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.RedisKeys

object RedisKeyspace {
    fun spec(specRevisionId: String, platform: ClientPlatform): String = RedisKeys.spec(specRevisionId, platform)
    fun tree(
        surface: String,
        platform: ClientPlatform,
        schema: String,
        appMajorMinor: String,
        capsHash: String,
        channel: Channel,
    ): String = RedisKeys.tree(surface, platform, schema, appMajorMinor, capsHash, channel)

    fun section(projection: String, id: String): String = RedisKeys.section(projection, id)
    fun lastGood(surface: String, platform: ClientPlatform, channel: Channel): String =
        RedisKeys.lastGood(surface, platform, channel)

    fun singleflight(treeKey: String): String = RedisKeys.singleflight(treeKey)

    fun treePrefix(surface: String, platform: ClientPlatform): String =
        "sdui:tree:$surface:${platform.wire()}:"

    fun matchesSurfacePlatformChannel(
        key: String,
        surface: String,
        platform: ClientPlatform,
        channel: Channel
    ): Boolean =
        key.startsWith("sdui:tree:$surface:") &&
                key.contains(":${platform.wire()}:") &&
                key.endsWith(":${channel.wire()}") &&
                !RedisKeys.containsUserId(key)
}
