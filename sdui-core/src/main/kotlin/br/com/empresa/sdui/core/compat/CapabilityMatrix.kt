package br.com.empresa.sdui.core.compat

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SemVer

class CapabilityMatrix(
    private val byPlatformVersion: Map<Pair<ClientPlatform, String>, Set<Capability>> = defaultMatrix(),
) {
    fun effective(context: ClientContext): Set<Capability> {
        val server = serverCaps(context.platform, context.appVersion)
        return server + context.headerCapabilities.toSet()
    }

    fun serverCaps(platform: ClientPlatform, appVersion: SemVer): Set<Capability> {
        val exact = byPlatformVersion[platform to appVersion.majorMinor]
        if (exact != null) return exact
        return byPlatformVersion[platform to "*"] ?: MvpCatalog.TYPES.toSet()
    }

    companion object {
        fun defaultMatrix(): Map<Pair<ClientPlatform, String>, Set<Capability>> {
            val all = MvpCatalog.TYPES.toSet()
            val iosLegacy = setOf(
                Capability("top_bar", 1),
                Capability("shortcut_shelf", 1),
                Capability("account_card", 1),
            )
            return mapOf(
                (ClientPlatform.IOS to "*") to all,
                (ClientPlatform.ANDROID to "*") to all,
                (ClientPlatform.IOS to "8.4") to iosLegacy,
                (ClientPlatform.IOS to "8.5") to iosLegacy,
                (ClientPlatform.IOS to "8.6") to iosLegacy,
                (ClientPlatform.IOS to "8.7") to iosLegacy,
                (ClientPlatform.IOS to "8.8") to iosLegacy,
                (ClientPlatform.IOS to "8.9") to iosLegacy,
            )
        }
    }
}
