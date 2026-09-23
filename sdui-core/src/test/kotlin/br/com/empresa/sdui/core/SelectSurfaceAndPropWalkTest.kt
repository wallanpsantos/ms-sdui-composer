package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.select.Select
import br.com.empresa.sdui.core.validate.PropWalk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Selecao por surface (T04/T05) e travessias de props com parada antecipada (AGENTS 21.8). */
class SelectSurfaceAndPropWalkTest {
    private val caps = MvpCatalog.TYPES.toSet()

    @Test
    fun `selecao nunca cruza surface nem usa pointer de outra surface`() {
        val home = spec("rev_home", Surfaces.HOME_ID, priority = 100)
        val catalog = spec("rev_catalog", Surfaces.CATALOG_ID, priority = 100)
        val homePointer = pointer("rev_home", Surfaces.HOME_ID)

        assertThat(Select.select(homePointer, listOf(home, catalog), context(), caps, Channel.STABLE, Surfaces.CATALOG_ID)?.specRevisionId)
            .isEqualTo("rev_catalog")
        assertThat(Select.select(null, listOf(catalog), context(), caps, Channel.STABLE, Surfaces.HOME_ID)).isNull()
        assertThat(Select.pointedIfServes(homePointer, home, context(), caps, Channel.STABLE, Surfaces.CATALOG_ID)).isNull()
    }

    @Test
    fun `atalho pelo pointer devolve o mesmo spec que a selecao completa`() {
        val pointed = spec("rev_pointed", Surfaces.HOME_ID, priority = 10)
        val better = spec("rev_better", Surfaces.HOME_ID, priority = 100)
        val ptr = pointer("rev_pointed", Surfaces.HOME_ID)

        val full = Select.select(ptr, listOf(pointed, better), context(), caps, Channel.STABLE, Surfaces.HOME_ID)
        val shortcut = Select.pointedIfServes(ptr, pointed, context(), caps, Channel.STABLE, Surfaces.HOME_ID)
        assertThat(shortcut).isNotNull().isEqualTo(full)

        // Revisao apontada fora da faixa: o atalho recusa e a selecao completa decide.
        val outOfRange = pointed.copy(targeting = pointed.targeting.copy(appVersion = VersionRange(SemVer(1, 0, 0), SemVer(1, 0, 1))))
        assertThat(Select.pointedIfServes(ptr, outOfRange, context(), caps, Channel.STABLE, Surfaces.HOME_ID)).isNull()
        assertThat(Select.select(ptr, listOf(outOfRange, better), context(), caps, Channel.STABLE, Surfaces.HOME_ID)?.specRevisionId)
            .isEqualTo("rev_better")

        // Rascunho apontado nunca serve.
        assertThat(Select.pointedIfServes(ptr, pointed.copy(status = SpecStatus.DRAFT), context(), caps, Channel.STABLE, Surfaces.HOME_ID))
            .isNull()
    }

    @Test
    fun `busca por string e por chave param na primeira ocorrencia`() {
        var visited = 0
        val props = mapOf("a" to "alvo", "b" to List(1_000) { "x$it" })
        val found = PropWalk.anyString(props) { visited++; it == "alvo" }
        assertThat(found).isTrue()
        assertThat(visited).isEqualTo(1)

        var keys = 0
        assertThat(PropWalk.anyKey(mapOf("sectionId" to "x", "resto" to List(1_000) { mapOf("k$it" to it) })) { keys++; it == "sectionId" })
            .isTrue()
        assertThat(keys).isEqualTo(1)
    }

    @Test
    fun `referencia a outra section e detectada por texto ou por chave`() {
        assertThat(PropWalk.referencesForeignSection(mapOf("t" to "ver sec_b"), "sec_a", setOf("sec_a", "sec_b"))).isTrue()
        assertThat(PropWalk.referencesForeignSection(mapOf("position" to 1), "sec_a", setOf("sec_a"))).isTrue()
        assertThat(PropWalk.referencesForeignSection(mapOf("t" to "sec_a"), "sec_a", setOf("sec_a", "sec_b"))).isFalse()
    }

    @Test
    fun `profundidade e medida sem descer alem do teto`() {
        var nested: Any? = "folha"
        repeat(16) { nested = mapOf("n" to nested) }
        assertThat(PropWalk.exceedsDepth(nested)).isFalse()
        assertThat(PropWalk.exceedsDepth(mapOf("n" to nested))).isTrue()
    }

    @Test
    fun `referencias a action incluem gatilhos nomeados`() {
        val ids = PropWalk.collectActionIds(
            mapOf(
                "searchActionId" to "act_search",
                "items" to listOf(mapOf("actionId" to "act_item")),
                "transactionId" to "nao_e_action",
            ),
        )
        assertThat(ids).containsExactlyInAnyOrder("act_search", "act_item")
    }

    private fun context() = ClientContext(
        platform = ClientPlatform.IOS,
        appVersion = SemVer(8, 14, 2),
        build = "81420",
        osVersion = SemVer(18, 1, 0),
        schemaVersion = "3",
        locale = "pt-BR",
        apiVersion = "1",
        headerCapabilities = emptyList(),
    )

    private fun pointer(revision: String, surface: String) = Pointer(
        surface = surface,
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        specId = revision,
        specRevisionId = revision,
        previousSpecRevisionId = null,
        version = 1,
    )

    private fun spec(revision: String, surface: String, priority: Int) = Spec(
        specId = revision,
        revision = 1,
        specRevisionId = revision,
        parentRevision = null,
        status = SpecStatus.PUBLISHED,
        surface = surface,
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        skeletonId = "sk",
        skeletonRevision = 1,
        targeting = Targeting(
            platform = ClientPlatform.IOS,
            appVersion = VersionRange(SemVer(8, 10, 0), null),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), SemVer(3, 0, 0)),
            requiredCapabilities = emptyList(),
            priority = priority,
            band = "t",
        ),
        sections = emptyList(),
        checksum = "sha256:00",
        publishedAt = Instant.parse("2026-09-01T00:00:00Z"),
        publishedBy = "c",
        madeBy = "m",
        experience = "e",
    )
}
