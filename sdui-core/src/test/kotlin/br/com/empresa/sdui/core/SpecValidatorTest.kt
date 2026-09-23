package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Action
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.SlotDefinition
import br.com.empresa.sdui.core.model.SlotLayout
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecStatus
import br.com.empresa.sdui.core.model.Targeting
import br.com.empresa.sdui.core.model.VersionRange
import br.com.empresa.sdui.core.validate.PiiGuard
import br.com.empresa.sdui.core.validate.SpecValidator
import br.com.empresa.sdui.core.validate.VisualGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SpecValidatorTest {

    private val catalog = Catalog(
        MvpCatalog.TYPES.map { ComponentType(it.type, it.typeVersion, "ACTIVE", "3", emptyList()) },
    )

    private val matrix = CapabilityMatrix()

    private fun validSkeleton(layout: String = MvpCatalog.SKELETON_LAYOUT) = Skeleton(
        skeletonId = "home.default",
        revision = 1,
        surface = "home",
        layout = layout,
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

    private fun baseSpec(sections: List<Section>) = Spec(
        specId = "spec_home_ios_test",
        revision = 1,
        specRevisionId = "rev_test_1",
        parentRevision = null,
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
            priority = 100,
            band = "current",
        ),
        sections = sections,
        checksum = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        publishedAt = Instant.parse("2026-09-09T20:00:00Z"),
        publishedBy = "tester.checker",
        madeBy = "tester.maker",
        experience = "home_experience",
    )

    @Test
    fun `recusa especificacao com layout nao homologado atributos visuais e types fora do catalogo`() {
        // Regressão dos critérios do ADR-016:
        // - layout two_pane
        // - props com chaves visuais proibidas: columns e orientation
        // - type de componente fora do catálogo nomeado pela forma: category_grid
        // - action arbitrária de rede fora do catálogo: callApi
        //
        // NOTA DE DESIGN SOBRE 'section sem typeVersion':
        // No modelo tipado de domínio em Kotlin, 'Section.typeVersion' é um primitivo 'Int' não-nulo
        // obrigatório no construtor da data class. Portanto, uma Section sem typeVersion é IMPOSSÍVEL
        // POR CONSTRUÇÃO no sistema de tipos (Eixo B de capabilities garantido na compilação).

        val twoPaneSkeleton = validSkeleton(layout = "two_pane")

        val invalidSections = listOf(
            Section(
                id = "sec_header",
                slot = "header",
                type = "top_bar",
                typeVersion = 1,
                layout = "fixed",
                props = emptyMap(),
            ),
            Section(
                id = "sec_accounts",
                slot = "accounts",
                type = "account_card",
                typeVersion = 1,
                layout = "list",
                props = emptyMap(),
            ),
            Section(
                id = "sec_category",
                slot = "shortcuts",
                type = "category_grid",
                typeVersion = 1,
                layout = "two_pane",
                props = mapOf(
                    "columns" to 3,
                    "orientation" to "horizontal",
                ),
                actions = listOf(
                    Action(
                        id = "act_call_api",
                        type = "callApi",
                        label = "Executar chamada de API",
                    ),
                ),
            ),
        )

        val spec = baseSpec(invalidSections)

        val errors = SpecValidator.validateDraft(spec, twoPaneSkeleton, catalog, matrix)

        // 1. Asserção para layout two_pane
        assertThat(errors)
            .`as`("Deve recusar layout 'two_pane' no skeleton ou na section (ADR-016)")
            .anyMatch { it.contains("two_pane") }

        // 2. Asserção para props com 'columns'
        assertThat(errors)
            .`as`("Deve recusar atributo visual 'columns' nas props (ADR-016 / ADR-010)")
            .anyMatch { it.contains("campo proibido 'columns'") }

        // 3. Asserção para props com 'orientation'
        assertThat(errors)
            .`as`("Deve recusar atributo visual 'orientation' nas props (ADR-016 / ADR-010)")
            .anyMatch { it.contains("campo proibido 'orientation'") }

        // 4. Asserção para type 'category_grid'
        assertThat(errors)
            .`as`("Deve recusar type 'category_grid' fora do catálogo fechado (ADR-016)")
            .anyMatch { it.contains("category_grid") }

        // 5. Asserção para action 'callApi'
        assertThat(errors)
            .`as`("Deve recusar action 'callApi' fora do catálogo permitido (ADR-016 / ADR-011)")
            .anyMatch { it.contains("callApi") }
    }

    @Test
    fun `recusa actions de mutacao e chamadas arbitrarias de rede`() {
        // Regressão do ADR-015: surfaces hostis e actions de mutação
        val hostileActions = listOf("callApi", "addToCart", "completeOnboarding")

        for (actionType in hostileActions) {
            val sections = listOf(
                Section(
                    id = "sec_header",
                    slot = "header",
                    type = "top_bar",
                    typeVersion = 1,
                    layout = "fixed",
                    props = emptyMap(),
                ),
                Section(
                    id = "sec_accounts",
                    slot = "accounts",
                    type = "account_card",
                    typeVersion = 1,
                    layout = "list",
                    props = emptyMap(),
                ),
                Section(
                    id = "sec_action_test",
                    slot = "shortcuts",
                    type = "shortcut_shelf",
                    typeVersion = 1,
                    layout = "shelf",
                    props = emptyMap(),
                    actions = listOf(
                        Action(
                            id = "act_hostile",
                            type = actionType,
                            label = "Ação hostil",
                        ),
                    ),
                ),
            )

            val spec = baseSpec(sections)
            val errors = SpecValidator.validateDraft(spec, validSkeleton(), catalog, matrix)

            assertThat(errors)
                .`as`("SpecValidator deve recusar action '$actionType' fora do catálogo permitido (ADR-015)")
                .anyMatch { it.contains("action act_hostile type $actionType fora do catalogo") }
        }
    }

    @Test
    fun `visualGuard recusa seletores de renderizacao e estilizacao`() {
        // Regressão do ADR-010 e ADR-019: sem seletores de renderização ou tokens visuais no payload
        val forbiddenSelectors = listOf("componentType", "appearance", "presentation", "style", "size", "variant")
        for (key in forbiddenSelectors) {
            val violations = VisualGuard.violations(mapOf(key to "value"))
            assertThat(violations)
                .`as`("VisualGuard deve recusar seletor '$key' (ADR-010 / ADR-019)")
                .anyMatch { it.contains("campo proibido '$key'") }
        }
    }

    @Test
    fun `piiGuard recusa campos de autenticacao sensiveis pin otp passcode`() {
        // Regressão do ADR-015: PiiGuard deve recusar pin, otp e passcode
        val piiFields = listOf("pin", "otp", "passcode")
        for (field in piiFields) {
            val violations = PiiGuard.violations(mapOf(field to "123456"))
            assertThat(violations)
                .`as`("PiiGuard deve recusar campo de autenticação sensível '$field' (ADR-015)")
                .anyMatch { it.contains("chave regulada '$field'") }
        }
    }

    @Test
    fun `identidade insegura e revisao de skeleton divergente sao erros de validacao`() {
        for (id in listOf("rev_userId_1", "rev user", "rev\"header", "x".repeat(129))) {
            assertThat(
                SpecValidator.validateDraft(
                    baseSpec(emptyList()).copy(specRevisionId = id),
                    validSkeleton(),
                    catalog,
                    matrix
                )
            )
                .anyMatch { it.contains("specRevisionId") }
        }
        assertThat(
            SpecValidator.validateDraft(
                baseSpec(emptyList()).copy(skeletonRevision = 2),
                validSkeleton(),
                catalog,
                matrix
            )
        )
            .contains("skeletonRevision divergente ou inexistente")
    }

    @Test
    fun `faixa aberta com minor maximo nao lanca overflow e cobre transicoes da matriz`() {
        val spec = baseSpec(emptyList())
        val extreme =
            spec.copy(targeting = spec.targeting.copy(appVersion = VersionRange(SemVer(8, Int.MAX_VALUE, 0), null)))
        assertThat(SpecValidator.validateDraft(extreme, validSkeleton(), catalog, matrix)).isNotEmpty()
        val samples = matrix.versionSamples(ClientPlatform.IOS, VersionRange(SemVer(8, 3, 1), SemVer(9, 0, 0)))
        assertThat(samples).contains(SemVer(8, 4, 0), SemVer(8, 9, 0), SemVer(8, 10, 0))
    }

}
