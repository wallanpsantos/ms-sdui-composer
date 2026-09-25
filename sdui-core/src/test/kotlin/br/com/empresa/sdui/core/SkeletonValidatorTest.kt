package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.validate.SkeletonValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SkeletonValidatorTest {

    private fun validDefaultSkeleton() = Skeleton(
        skeletonId = MvpCatalog.SKELETON_HOME_DEFAULT,
        revision = 1,
        surface = MvpCatalog.SURFACE_HOME,
        layout = MvpCatalog.SKELETON_LAYOUT,
        slots = listOf(
            SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            SlotDefinition("shortcuts", SlotLayout.SHELF, null, 1, listOf("shortcut_shelf"), required = false),
            SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
            SlotDefinition("cards", SlotLayout.LIST, "Cartão de crédito", 3, listOf("card_product"), required = false),
            SlotDefinition("offers", SlotLayout.LIST, "Crédito", 4, listOf("credit_offer"), required = false),
            SlotDefinition("coverage", SlotLayout.LIST, "Seguros", 3, listOf("coverage_card"), required = false),
            SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
        ),
        status = SpecStatus.PUBLISHED,
    )

    private fun validCardsFirstSkeleton() = Skeleton(
        skeletonId = MvpCatalog.SKELETON_HOME_CARDS_FIRST,
        revision = 1,
        surface = MvpCatalog.SURFACE_HOME,
        layout = MvpCatalog.SKELETON_LAYOUT,
        slots = listOf(
            SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
            SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
            SlotDefinition("cards", SlotLayout.LIST, "Cartão de crédito", 3, listOf("card_product"), required = false),
            SlotDefinition("shortcuts", SlotLayout.GRID, null, 1, listOf("shortcut_shelf"), required = false),
            SlotDefinition("offers", SlotLayout.LIST, "Crédito", 4, listOf("credit_offer"), required = false),
            SlotDefinition("coverage", SlotLayout.LIST, "Seguros", 3, listOf("coverage_card"), required = false),
            SlotDefinition("foryou", SlotLayout.PAGER, "Para você", 2, listOf("decision_card"), required = false),
        ),
        status = SpecStatus.PUBLISHED,
    )

    @Test
    fun `valida com sucesso skeleton padrao home default`() {
        val errors = SkeletonValidator.validate(validDefaultSkeleton())
        assertThat(errors)
            .`as`("Skeleton padrão home.default deve ser válido")
            .isEmpty()
    }

    @Test
    fun `valida com sucesso skeleton com montagem variavel cards first e grid`() {
        // ADR-018: A montagem variável permite cards antes de shortcuts e shortcuts com layout grid
        val errors = SkeletonValidator.validate(validCardsFirstSkeleton())
        assertThat(errors)
            .`as`("Skeleton cards_first com reordenação e shortcuts em grid deve ser aceito (ADR-018)")
            .isEmpty()
    }

    @Test
    fun `recusa skeleton quando header nao e o primeiro slot`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = listOf(
                SlotDefinition("shortcuts", SlotLayout.SHELF, null, 1, listOf("shortcut_shelf"), required = false),
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
            ),
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve exigir que o slot header seja obrigatoriamente o primeiro")
            .anyMatch { it.contains("slot 'header' deve ser obrigatoriamente o primeiro slot") }
    }

    @Test
    fun `recusa skeleton sem slot portante obrigatorio accounts`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("shortcuts", SlotLayout.SHELF, null, 1, listOf("shortcut_shelf"), required = false),
            ),
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve recusar skeleton sem o slot portante 'accounts'")
            .anyMatch { it.contains("slots portantes obrigatorios ausentes") && it.contains("accounts") }
    }

    @Test
    fun `recusa skeleton quando slot portante nao esta marcado como required`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = validDefaultSkeleton().slots.map { slot ->
                if (slot.id == "accounts") slot.copy(required = false) else slot
            },
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Slot portante accounts deve ser obrigatoriamente required")
            .anyMatch { it.contains("slot portante accounts deve ser required") }
    }

    @Test
    fun `recusa skeleton com layout nao permitido para o slot`() {
        // header só permite FIXED; testamos com SHELF
        val skeleton = validDefaultSkeleton().copy(
            slots = validDefaultSkeleton().slots.map { slot ->
                if (slot.id == "header") slot.copy(layout = SlotLayout.SHELF) else slot
            },
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve recusar layout shelf para o slot header")
            .anyMatch { it.contains("layout 'shelf' nao permitido para o slot 'header'") }
    }

    @Test
    fun `recusa skeleton com ids de slots duplicados`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = listOf(
                SlotDefinition("header", SlotLayout.FIXED, null, 1, listOf("top_bar"), required = true),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta", 1, listOf("account_card"), required = true),
                SlotDefinition("accounts", SlotLayout.LIST, "Conta 2", 1, listOf("account_card"), required = true),
            ),
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve recusar slots com ids duplicados")
            .anyMatch { it.contains("slots com ids duplicados") }
    }

    @Test
    fun `recusa skeleton com slot fora do vocabulario da surface`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = validDefaultSkeleton().slots + SlotDefinition(
                id = "custom_promo_slot",
                layout = SlotLayout.LIST,
                title = "Promo",
                maxInstances = 1,
                allowedTypes = listOf("decision_card"),
                required = false,
                allowedLayouts = listOf(SlotLayout.LIST),
            ),
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve recusar slot desconhecido fora do vocabulário da surface")
            .anyMatch { it.contains("slots desconhecidos para a surface 'home'") && it.contains("custom_promo_slot") }
    }

    @Test
    fun `recusa skeleton com allowedTypes fora do catalogo`() {
        val skeleton = validDefaultSkeleton().copy(
            slots = validDefaultSkeleton().slots.map { slot ->
                if (slot.id == "shortcuts") slot.copy(allowedTypes = listOf("category_grid")) else slot
            },
        )

        val errors = SkeletonValidator.validate(skeleton)
        assertThat(errors)
            .`as`("Deve recusar allowedTypes fora do catálogo de componentes")
            .anyMatch { it.contains("allowedTypes fora do catalogo") && it.contains("category_grid") }
    }
}
