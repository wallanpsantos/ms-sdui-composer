package br.com.empresa.sdui.adapters.json

import br.com.empresa.sdui.adapters.memory.InMemoryCatalogStore
import br.com.empresa.sdui.adapters.memory.InMemoryPointerStore
import br.com.empresa.sdui.adapters.memory.InMemorySkeletonStore
import br.com.empresa.sdui.adapters.memory.InMemorySpecStore
import br.com.empresa.sdui.adapters.redis.RedisCacheCodec
import br.com.empresa.sdui.adapters.seed.HomeSeed
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.OmittedReason
import br.com.empresa.sdui.core.model.OmittedSection
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.validate.PiiGuard
import br.com.empresa.sdui.orchestrator.port.outbound.CacheInvalidation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

/**
 * Formato de persistencia dos adapters MongoDB e Redis (P03, P08): o dominio atravessa o JSON sem
 * perda, sem gravar propriedade derivada e sem depender do mapper HTTP. Roda sem infraestrutura.
 */
class DomainJsonRoundTripTest {
    private val specStore = InMemorySpecStore()
    private val skeletonStore = InMemorySkeletonStore()
    private val catalogStore = InMemoryCatalogStore()

    init {
        val seed = checkNotNull(javaClass.getResourceAsStream("/seed/contrato-sdui-home-definitivo.json"))
            .use { it.readBytes().decodeToString() }
        HomeSeed(catalogStore, skeletonStore, specStore, InMemoryPointerStore(), JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
            .seedFromCanonicalFixture(seed)
    }

    @Test
    fun `spec, skeleton e catalogo voltam iguais e sem propriedade derivada no formato gravado`() {
        val spec = checkNotNull(specStore.findByRevisionId("rev_01K8HOMEMAIN"))
        val specJson = DomainJson.write(spec)
        assertThat(DomainJson.read(specJson, Spec::class.java)).isEqualTo(spec)
        assertThat(specJson).doesNotContain("\"capability\"", "\"ordinal\"", "\"majorMinor\"")

        val skeleton = checkNotNull(skeletonStore.current("home.default"))
        val skeletonJson = DomainJson.write(skeleton)
        assertThat(DomainJson.read(skeletonJson, Skeleton::class.java)).isEqualTo(skeleton)
        assertThat(skeletonJson).doesNotContain("\"slotOrder\"", "\"requiredSlotIds\"")

        val catalog = catalogStore.current()
        assertThat(DomainJson.read(DomainJson.write(catalog), Catalog::class.java)).isEqualTo(catalog)
    }

    @Test
    fun `numeros das props voltam como Long ou Double, como no seed`() {
        val spec = checkNotNull(specStore.findByRevisionId("rev_01K8HOMEMAIN"))
        val withNumbers = spec.copy(
            sections = spec.sections.map { it.copy(props = it.props + ("inteiro" to 7L) + ("decimal" to 1.5)) },
        )
        val back = DomainJson.read(DomainJson.write(withNumbers), Spec::class.java)
        assertThat(back.sections.first().props["inteiro"]).isEqualTo(7L)
        assertThat(back.sections.first().props["decimal"]).isEqualTo(1.5)
        assertThat(back).isEqualTo(withNumbers)
    }

    @Test
    fun `pointer, invalidacao e instantes preservam precisao`() {
        val pointer = Pointer("home", ClientPlatform.IOS, Channel.CANARY, "s", "rev_2", "rev_1", 7)
        assertThat(DomainJson.read(DomainJson.write(pointer), Pointer::class.java)).isEqualTo(pointer)

        val invalidation = CacheInvalidation(
            "i", "catalog", ClientPlatform.ANDROID, Channel.STABLE, 4, null,
            Instant.parse("2026-09-23T12:34:56.123456789Z"),
        )
        assertThat(DomainJson.read(DomainJson.write(invalidation), CacheInvalidation::class.java)).isEqualTo(invalidation)
    }

    @Test
    fun `arvore do cache Redis volta igual, rejeita outro formato e nao carrega chave regulada`() {
        val spec = checkNotNull(specStore.findByRevisionId("rev_01K8HOMEMAIN"))
        val screen = screen(spec)
        val bytes = RedisCacheCodec.encodeScreen(screen)
        assertThat(RedisCacheCodec.decodeScreen(bytes)).isEqualTo(screen)
        assertThat(PiiGuard.violations(DomainJson.mapper.readValue(bytes, Map::class.java))).isEmpty()

        val otherFormat = String(bytes, Charsets.UTF_8).replaceFirst("\"v\":1", "\"v\":99").toByteArray(Charsets.UTF_8)
        assertThat(RedisCacheCodec.decodeScreen(otherFormat)).isNull()

        assertThat(RedisCacheCodec.decodeSpec(RedisCacheCodec.encodeSpec(spec))).isEqualTo(spec)
    }

    private fun screen(spec: Spec) = ComposedScreen(
        surface = spec.surface,
        platform = spec.platform,
        schemaVersion = "3",
        specRevisionId = spec.specRevisionId,
        skeletonId = spec.skeletonId,
        skeletonHash = spec.checksum,
        etag = "W/\"x\"",
        generatedAt = Instant.parse("2026-09-23T12:00:00.5Z"),
        locale = "pt-BR",
        channel = Channel.STABLE,
        fallback = false,
        fallbackReason = FallbackReason.NONE,
        omitted = listOf(OmittedSection("sec_x", "foryou", "decision_card", 1, OmittedReason.UNSUPPORTED_TYPE)),
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
        targeting = spec.targeting,
        experience = spec.experience,
        skeleton = checkNotNull(skeletonStore.current(spec.skeletonId)),
        sections = spec.sections,
        pointerVersion = 3,
    )
}
