package br.com.empresa.sdui.api

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** As props sao mapa livre: o mapper tem de entregar cada valor com o tipo JSON que ele tinha. */
class ScreenResponseMapperTest {
    private val mapper = ScreenResponseMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())

    @Test
    fun `numero continua numero inclusive alem de Long e decimal exato`() {
        val grande = BigInteger("123456789012345678901234567890")
        val props = mapOf(
            "inteiro" to 7L,
            "grande" to grande,
            "exato" to BigDecimal("10.25"),
            "real" to 1.5,
            "texto" to "7",
        )

        val node = mapper.toResponse(screen(props)).sections.single().props

        assertThat(node.get("inteiro").isNumber).isTrue()
        assertThat(node.get("grande").isBigInteger).isTrue()
        assertThat(node.get("grande").bigIntegerValue()).isEqualTo(grande)
        assertThat(node.get("exato").isNumber).isTrue()
        assertThat(node.get("exato").decimalValue()).isEqualByComparingTo(BigDecimal("10.25"))
        assertThat(node.get("real").isDouble).isTrue()
        assertThat(node.get("texto").isString).isTrue()
    }

    private fun screen(props: Map<String, Any?>) = ComposedScreen(
        surface = MvpCatalog.SURFACE_HOME,
        platform = ClientPlatform.IOS,
        schemaVersion = "3",
        specRevisionId = "rev_1",
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        skeletonHash = "sha256:abc123",
        etag = "W/\"rev_1-ios-3\"",
        generatedAt = Instant.parse("2026-09-09T20:00:00Z"),
        locale = "pt-BR",
        channel = Channel.STABLE,
        fallback = false,
        fallbackReason = FallbackReason.NONE,
        omitted = emptyList(),
        client = ClientContext(
            platform = ClientPlatform.IOS,
            appVersion = SemVer(8, 14, 2),
            build = "81420",
            osVersion = SemVer(18, 1, 0),
            schemaVersion = "3",
            locale = "pt-BR",
            apiVersion = "1",
            headerCapabilities = emptyList(),
        ),
        targeting = Targeting(
            platform = ClientPlatform.IOS,
            appVersion = VersionRange(SemVer(8, 0, 0), null),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), null),
            requiredCapabilities = emptyList(),
            priority = 100,
            band = "ga",
        ),
        experience = "home_default",
        skeleton = Skeleton(
            skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
            revision = 1,
            surface = MvpCatalog.SURFACE_HOME,
            layout = MvpCatalog.SKELETON_LAYOUT,
            slots = listOf(SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true)),
            status = SpecStatus.PUBLISHED,
        ),
        sections = listOf(Section(id = "s1", slot = "header", type = "top_bar", typeVersion = 1, props = props)),
    )
}
