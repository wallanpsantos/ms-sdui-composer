package br.com.empresa.sdui.core.compat

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SemVer

/**
 * O que o servidor assume que cada faixa de app sabe renderizar, mais o que o cliente declara.
 *
 * Existe porque nem todo app atualizado suporta todo componente: versoes antigas de iOS ficam com
 * um subconjunto, e o header Component-Capabilities permite ao cliente corrigir essa suposicao
 * para mais. O resultado alimenta o filtro de sections e o capsHash da chave de cache.
 */
class CapabilityMatrix(
    private val byPlatformVersion: Map<Pair<ClientPlatform, String>, Set<Capability>> = defaultMatrix(),
) {
    /**
     * Universo finito de capabilities que o servidor reconhece. O delta declarado pelo cliente e
     * filtrado por este conjunto: uma capability arbitraria nunca casaria com uma section de spec,
     * mas entraria no capsHash e produziria uma chave de cache de arvore nova a cada requisicao.
     */
    private val known: Set<Capability> = byPlatformVersion.values.flatten().toSet() + MvpCatalog.TYPES

    fun effective(context: ClientContext): Set<Capability> {
        val server = serverCaps(context.platform, context.appVersion)
        val declared = context.headerCapabilities.filter { it in known }
        return server + declared
    }

    fun serverCaps(platform: ClientPlatform, appVersion: SemVer): Set<Capability> =
        byPlatformVersion[platform to appVersion.majorMinor]
            ?: byPlatformVersion[platform to "*"]
            ?: MvpCatalog.TYPES.toSet()

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
