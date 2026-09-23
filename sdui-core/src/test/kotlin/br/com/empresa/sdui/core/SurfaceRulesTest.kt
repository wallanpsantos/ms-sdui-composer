package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Action
import br.com.empresa.sdui.core.model.ActionPayload
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.validate.SkeletonValidator
import br.com.empresa.sdui.core.validate.SpecValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regras de dominio por surface (T04, ADR-020): a Home preserva as regras do MVP, o catalogo de
 * comercio nao exige slot financeiro, e combinacoes cruzadas sao recusadas.
 */
class SurfaceRulesTest {
    private val matrix = CapabilityMatrix()
    private val catalog = Catalog(
        ComponentContracts.APPROVED.map { ComponentType(it.type, it.typeVersion, "ACTIVE", "3", emptyList()) },
    )

    @Test
    fun `allowlist e finita e a Home continua com header e accounts portantes`() {
        assertThat(Surfaces.IDS).containsExactlyInAnyOrder("home", "catalog")
        assertThat(Surfaces.HOME.requiredSlots).containsExactlyInAnyOrder("header", "accounts")
        assertThat(Surfaces.CATALOG.requiredSlots).containsExactlyInAnyOrder("header", "products")
        assertThat(Surfaces.CATALOG.slotIds).doesNotContain("accounts", "cards", "offers")
        assertThat(Surfaces.find("inventada")).isNull()
        assertThat(Surfaces.find(null)).isNull()
    }

    @Test
    fun `skeleton do catalogo e valido sem slot financeiro`() {
        assertThat(SkeletonValidator.validate(catalogSkeleton())).isEmpty()
    }

    @Test
    fun `skeleton de surface desconhecida e recusado antes de qualquer outra regra`() {
        val errors = SkeletonValidator.validate(catalogSkeleton().copy(surface = "checkout"))
        assertThat(errors).singleElement().asString().contains("surface desconhecida")
    }

    @Test
    fun `slot financeiro no catalogo e type de comercio na home sao recusados`() {
        val withAccounts = catalogSkeleton().let {
            it.copy(slots = it.slots + SlotDefinition("accounts", SlotLayout.LIST, null, 1, listOf("account_card"), required = true))
        }
        assertThat(SkeletonValidator.validate(withAccounts))
            .anyMatch { it.contains("slots desconhecidos para a surface 'catalog'") }
            .anyMatch { it.contains("allowedTypes fora do catalogo da surface 'catalog'") }

        val homeWithProducts = homeSkeleton().let { skeleton ->
            skeleton.copy(
                slots = skeleton.slots.map {
                    if (it.id == "offers") it.copy(allowedTypes = listOf("product_collection")) else it
                },
            )
        }
        assertThat(SkeletonValidator.validate(homeWithProducts))
            .anyMatch { it.contains("allowedTypes fora do catalogo da surface 'home'") && it.contains("product_collection") }
    }

    @Test
    fun `catalogo sem o slot portante products e recusado`() {
        val skeleton = catalogSkeleton().let { it.copy(slots = it.slots.filterNot { slot -> slot.id == "products" }) }
        assertThat(SkeletonValidator.validate(skeleton))
            .anyMatch { it.contains("slots portantes obrigatorios ausentes") && it.contains("products") }
    }

    @Test
    fun `allowedLayouts nao podem ir alem da regra do slot na surface`() {
        val skeleton = catalogSkeleton().let { skeleton ->
            skeleton.copy(
                slots = skeleton.slots.map {
                    if (it.id == "products") it.copy(allowedLayouts = listOf(SlotLayout.GRID, SlotLayout.PAGER)) else it
                },
            )
        }
        assertThat(SkeletonValidator.validate(skeleton))
            .anyMatch { it.contains("allowedLayouts do slot 'products' excedem a regra da surface") }
    }

    @Test
    fun `spec de uma surface com skeleton de outra e recusado`() {
        val spec = catalogSpec().copy(skeletonId = "home.default")
        val errors = SpecValidator.validateDraft(spec, homeSkeleton(), catalog, matrix)
        assertThat(errors).anyMatch { it.contains("pertence a surface 'home', spec a 'catalog'") }
    }

    @Test
    fun `spec de surface desconhecida e recusado`() {
        val errors = SpecValidator.validateDraft(catalogSpec().copy(surface = "wallet"), catalogSkeleton(), catalog, matrix)
        assertThat(errors).anyMatch { it.contains("surface desconhecida: 'wallet'") }
    }

    @Test
    fun `catalogo exigindo product_collection no targeting e publicavel mesmo sem a matriz conceder o type`() {
        assertThat(SpecValidator.validateDraft(catalogSpec(), catalogSkeleton(), catalog, matrix)).isEmpty()
    }

    @Test
    fun `sem exigir a capability no targeting o slot portante do catalogo pode ficar vazio e e recusado`() {
        val spec = catalogSpec().let {
            it.copy(targeting = it.targeting.copy(requiredCapabilities = listOf(Capability("top_bar", 1))))
        }
        assertThat(SpecValidator.validateDraft(spec, catalogSkeleton(), catalog, matrix))
            .anyMatch { it.contains("slot required 'products' pode ficar vazio") }
    }

    @Test
    fun `props aninhadas alem do teto sao recusadas na publicacao`() {
        var deep: Any? = "folha"
        repeat(20) { deep = mapOf("nivel" to deep) }
        val spec = catalogSpec().let { spec ->
            spec.copy(
                sections = spec.sections.map { if (it.id == "sec_header") it.copy(props = mapOf("greetingName" to "Cliente", "extra" to deep)) else it },
            )
        }
        assertThat(SpecValidator.validateDraft(spec, catalogSkeleton(), catalog, matrix))
            .anyMatch { it.contains("excede 16 niveis de props") }
    }

    private fun homeSkeleton() = Skeleton(
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        revision = 1,
        surface = MvpCatalog.SURFACE_HOME,
        layout = MvpCatalog.SKELETON_LAYOUT,
        slots = listOf(
            SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
            SlotDefinition("offers", SlotLayout.LIST, "Credito", 4, listOf("credit_offer"), required = false),
        ),
        status = SpecStatus.PUBLISHED,
    )

    private fun catalogSkeleton() = Skeleton(
        skeletonId = "catalog.default",
        revision = 1,
        surface = Surfaces.CATALOG_ID,
        layout = MvpCatalog.SKELETON_LAYOUT,
        slots = listOf(
            SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            SlotDefinition("navigation", SlotLayout.SHELF, null, 1, listOf("catalog_navigation"), required = false),
            SlotDefinition("products", SlotLayout.GRID, "Produtos", 1, listOf("product_collection"), required = true),
        ),
        status = SpecStatus.PUBLISHED,
    )

    private fun catalogSpec() = Spec(
        specId = "spec_catalog_test",
        revision = 1,
        specRevisionId = "rev_catalog_test",
        parentRevision = null,
        status = SpecStatus.DRAFT,
        surface = Surfaces.CATALOG_ID,
        platform = ClientPlatform.IOS,
        channel = Channel.STABLE,
        skeletonId = "catalog.default",
        skeletonRevision = 1,
        targeting = Targeting(
            platform = ClientPlatform.IOS,
            appVersion = VersionRange(SemVer(8, 10, 0), null),
            osVersion = null,
            schemaVersion = VersionRange(SemVer(3, 0, 0), SemVer(3, 0, 0)),
            requiredCapabilities = listOf(Capability("top_bar", 1), ComponentContracts.PRODUCT_COLLECTION),
            priority = 100,
            band = "demo",
        ),
        sections = listOf(
            Section(
                id = "sec_header",
                slot = "header",
                type = "top_bar",
                typeVersion = 1,
                layout = "fixed",
                props = mapOf("greetingName" to "Cliente"),
            ),
            Section(
                id = "sec_products",
                slot = "products",
                type = "product_collection",
                typeVersion = 1,
                layout = "grid",
                props = mapOf(
                    "items" to listOf(
                        mapOf("id" to "p1", "name" to "Camiseta", "priceDisplay" to "R$ 79,90", "actionId" to "act_p1"),
                    ),
                ),
                actions = listOf(Action("act_p1", "navigate", "Ver produto", ActionPayload(route = "app://shop/products/p1"))),
            ),
        ),
        checksum = "sha256:0a1b2c",
        publishedAt = null,
        publishedBy = null,
        madeBy = "maker",
        experience = "catalog_test",
    )
}
