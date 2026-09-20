package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.cache.CapsHash
import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.filter.Filter
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.RedisKeys
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.select.Select
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SelectFilterKeysTest {
    @Test
    fun `select nao cruza plataforma e e deterministico`() {
        val ios = spec(ClientPlatform.IOS, "rev_ios", 100)
        val android = spec(ClientPlatform.ANDROID, "rev_and", 200)
        val ctx = context(ClientPlatform.IOS)
        val caps = MvpCatalog.TYPES.toSet()
        val selected = Select.select(null, listOf(ios, android), ctx, caps, Channel.STABLE)
        assertThat(selected?.specRevisionId).isEqualTo("rev_ios")
        assertThat(Select.select(null, listOf(ios, android), ctx, caps, Channel.STABLE)?.specRevisionId)
            .isEqualTo(selected?.specRevisionId)
        val androidSelected =
            Select.select(null, listOf(ios, android), context(ClientPlatform.ANDROID), caps, Channel.STABLE)
        assertThat(androidSelected?.specRevisionId).isEqualTo("rev_and")
        assertThat(androidSelected?.specRevisionId).isNotEqualTo("rev_ios")
    }

    @Test
    fun `pointer de canary seleciona revisao publicada mesmo quando spec_channel e stable`() {
        val published = spec(ClientPlatform.IOS, "rev_01K8HOMEMAIN", 100)
        val pointer = Pointer(
            surface = "home",
            platform = ClientPlatform.IOS,
            channel = Channel.CANARY,
            specId = published.specId,
            specRevisionId = published.specRevisionId,
            previousSpecRevisionId = null,
            version = 1,
        )
        val selected = Select.select(
            pointer,
            listOf(published),
            context(ClientPlatform.IOS),
            MvpCatalog.TYPES.toSet(),
            Channel.CANARY,
        )
        assertThat(selected?.specRevisionId).isEqualTo("rev_01K8HOMEMAIN")
        assertThat(selected?.channel).isEqualTo(Channel.STABLE)
    }

    @Test
    fun `promocao move pointer stable para revisao validada em canary sem republicar`() {
        val canaryRevision = spec(ClientPlatform.IOS, "rev_canary_ok", 100).copy(channel = Channel.CANARY)
        val stablePointer = Pointer(
            surface = "home",
            platform = ClientPlatform.IOS,
            channel = Channel.STABLE,
            specId = canaryRevision.specId,
            specRevisionId = canaryRevision.specRevisionId,
            previousSpecRevisionId = "rev_01K8HOMEMAIN",
            version = 2,
        )
        val selected = Select.select(
            stablePointer,
            listOf(canaryRevision, spec(ClientPlatform.IOS, "rev_01K8HOMEMAIN", 50)),
            context(ClientPlatform.IOS),
            MvpCatalog.TYPES.toSet(),
            Channel.STABLE,
        )
        assertThat(selected?.specRevisionId).isEqualTo("rev_canary_ok")
        assertThat(selected?.channel).isEqualTo(Channel.CANARY)
    }

    @Test
    fun `filter omite type desconhecido e preserva ordem dos slots`() {
        val skeleton = Skeleton(
            skeletonId = "home.default",
            revision = 1,
            surface = "home",
            layout = "vertical_scroll",
            slots = MvpCatalog.SLOT_ORDER.map { id ->
                SlotDefinition(
                    id,
                    SlotLayout.LIST,
                    null,
                    3,
                    MvpCatalog.TYPE_NAMES.toList(),
                    id in MvpCatalog.REQUIRED_SLOTS
                )
            },
            status = SpecStatus.PUBLISHED,
        )
        val sections = listOf(
            section("a", "header", "top_bar"),
            section("u", "offers", "unknown_widget"),
            section("b", "accounts", "account_card"),
        )
        val result = Filter.filter(sections, skeleton, MvpCatalog.TYPES.toSet())
        assertThat(result.sections.map { it.id }).containsExactly("a", "b")
        assertThat(result.omitted).hasSize(1)
        assertThat(result.omitted[0].reason.wire).isEqualTo("unsupported_type")
    }

    @Test
    fun `tree key inclui surface platform schema app major minor hash e channel e nunca userId`() {
        val caps = MvpCatalog.TYPES.toSet()
        val key = RedisKeys.tree("home", ClientPlatform.IOS, "3", "8.14", CapsHash.sha256(caps), Channel.STABLE)
        assertThat(key).startsWith("sdui:tree:home:ios:3:8.14:")
        assertThat(key).endsWith(":stable")
        assertThat(key).doesNotContain("userId")
        assertThat(RedisKeys.containsUserId(key)).isFalse()
        val shuffled = CapsHash.sha256(caps.shuffled())
        assertThat(CapsHash.sha256(caps)).isEqualTo(shuffled)
    }

    @Test
    fun `matriz une o delta conhecido do header e descarta capability fora do universo do servidor`() {
        val matrix = CapabilityMatrix()
        val legacy = context(ClientPlatform.IOS).copy(
            appVersion = SemVer(8, 4, 0),
            headerCapabilities = listOf(Capability("card_product", 1), Capability("future_card", 1)),
        )
        val effective = matrix.effective(legacy)
        assertThat(effective).contains(Capability("top_bar", 1), Capability("card_product", 1))
        assertThat(effective).doesNotContain(Capability("future_card", 1))
    }

    @Test
    fun `capabilities desconhecidas no header nao mudam o capsHash e portanto nao criam chave nova`() {
        val matrix = CapabilityMatrix()
        val limpo = context(ClientPlatform.IOS)
        val poluido = limpo.copy(headerCapabilities = List(50) { Capability("lixo_$it", 1) })
        assertThat(CapsHash.sha256(matrix.effective(poluido)))
            .isEqualTo(CapsHash.sha256(matrix.effective(limpo)))
    }

    @Test
    fun `header de capabilities e deduplicado e limitado`() {
        val excedente = (1..200).joinToString(",") { "top_bar@1" }
        assertThat(Capability.parseList(excedente)).containsExactly(Capability("top_bar", 1))
        val distintas = (1..200).joinToString(",") { "tipo_$it@1" }
        assertThat(Capability.parseList(distintas)).hasSize(Capability.MAX_HEADER_CAPABILITIES)
    }

    private fun context(platform: ClientPlatform) = ClientContext(
        platform = platform,
        appVersion = SemVer(8, 14, 2),
        build = "81420",
        osVersion = SemVer(18, 1, 0),
        schemaVersion = "3",
        locale = "pt-BR",
        apiVersion = "1",
        headerCapabilities = emptyList(),
    )

    private fun spec(platform: ClientPlatform, id: String, priority: Int) = Spec(
        specId = "spec_$id",
        revision = 1,
        specRevisionId = id,
        parentRevision = null,
        status = SpecStatus.PUBLISHED,
        surface = "home",
        platform = platform,
        channel = Channel.STABLE,
        skeletonId = "home.default",
        skeletonRevision = 1,
        targeting = Targeting(
            platform = platform,
            appVersion = VersionRange(SemVer(8, 10, 0), SemVer(8, 19, 99)),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), SemVer(3, 0, 0)),
            requiredCapabilities = emptyList(),
            priority = priority,
            band = "current",
        ),
        sections = emptyList(),
        checksum = "sha256:abc123",
        publishedAt = Instant.parse("2026-09-09T20:00:00Z"),
        publishedBy = "c",
        madeBy = "m",
        experience = "exp",
    )

    private fun section(id: String, slot: String, type: String) = Section(
        id = id,
        slot = slot,
        type = type,
        typeVersion = 1,
        layout = "list",
        props = emptyMap(),
    )
}
