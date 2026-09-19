package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.diff.SpecDiffFactory
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.model.SemVer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecDiffAndCatalogIndexTest {
    @Test
    fun `diff marca ocupacao de slot required e listas added removed changed`() {
        val skeleton = Skeleton(
            skeletonId = "home.default",
            revision = 1,
            surface = "home",
            layout = "vertical_scroll",
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
                SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
            ),
            status = SpecStatus.PUBLISHED,
        )
        val previous = spec(
            revision = 1,
            sections = listOf(
                section("sec_header_1", "header", "top_bar"),
                section("sec_account_1", "accounts", "account_card"),
                section("sec_foryou_1", "foryou", "decision_card"),
            ),
        )
        val current = spec(
            revision = 2,
            sections = listOf(
                section("sec_header_1", "header", "top_bar", props = mapOf("greetingName" to "Ana")),
                section("sec_foryou_2", "foryou", "decision_card"),
            ),
        )
        val diff = SpecDiffFactory.diff(previous, current, skeleton)
        assertThat(diff.added.map { it.path }).contains("sections.sec_foryou_2")
        assertThat(diff.removed.map { it.path }).contains("sections.sec_account_1", "sections.sec_foryou_1")
        assertThat(diff.changed.map { it.path }).contains("sections.sec_header_1")
        assertThat(diff.requiredOccupancy.map { it.path }).contains("slots.accounts.requiredOccupancy")
        assertThat(diff.requiredOccupancy.single { it.path.contains("accounts") }.from).isEqualTo("1")
        assertThat(diff.requiredOccupancy.single { it.path.contains("accounts") }.to).isEqualTo("0")
    }

    private fun spec(revision: Int, sections: List<Section>) = Spec(
        specId = "spec_home_ios_diff",
        revision = revision,
        specRevisionId = "rev_diff_$revision",
        parentRevision = if (revision == 1) null else 1,
        status = SpecStatus.DRAFT,
        surface = "home",
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        skeletonId = "home.default",
        skeletonRevision = 1,
        targeting = Targeting(
            platform = ClientPlatform.IOS,
            appVersion = VersionRange(SemVer(8, 10, 0), SemVer(8, 19, 99)),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), SemVer(3, 0, 0)),
            requiredCapabilities = MvpCatalog.TYPES,
            priority = 1,
            band = "current",
        ),
        sections = sections,
        checksum = "sha256:x",
        publishedAt = Instant.parse("2026-09-09T20:00:00Z"),
        publishedBy = "c",
        madeBy = "m",
        experience = "exp",
    )

    private fun section(
        id: String,
        slot: String,
        type: String,
        props: Map<String, Any?> = emptyMap(),
    ) = Section(
        id = id,
        slot = slot,
        type = type,
        typeVersion = 1,
        layout = "list",
        props = props,
    )
}
