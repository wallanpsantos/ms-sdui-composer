package br.com.empresa.sdui.core

import br.com.empresa.sdui.core.compat.CapabilityMatrix
import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.Section
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.validate.CatalogValidator
import br.com.empresa.sdui.core.validate.ComponentPropsValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Contratos novos com capability explicita (T07, ADR-020): catalogo fechado nos contratos
 * aprovados, props validadas, e nenhum type novo concedido automaticamente a app antigo.
 */
class ComponentContractsTest {

    @Test
    fun `catalogo aceita os contratos aprovados e recusa nome arbitrario mesmo inativo`() {
        val approved = Catalog(ComponentContracts.APPROVED.map { active(it) })
        assertThat(CatalogValidator.validate(approved)).isEmpty()

        val arbitrary = Catalog(approved.components + ComponentType("tipo_livre", 1, "DEPRECATED", "3", emptyList()))
        assertThat(CatalogValidator.validate(arbitrary)).anyMatch { it.contains("sem contrato aprovado: tipo_livre@1") }

        val unknownVersion =
            Catalog(approved.components + ComponentType("product_collection", 2, "ACTIVE", "3", emptyList()))
        assertThat(CatalogValidator.validate(unknownVersion)).anyMatch { it.contains("product_collection@2") }
    }

    @Test
    fun `catalogo precisa manter os sete types da Home ativos e sem repeticao`() {
        val withoutAccount = Catalog(MvpCatalog.TYPES.filterNot { it.type == "account_card" }.map { active(it) })
        assertThat(CatalogValidator.validate(withoutAccount)).anyMatch { it.contains("account_card@1") }

        val duplicated = Catalog(MvpCatalog.TYPES.map { active(it) } + active(Capability("top_bar", 1)))
        assertThat(CatalogValidator.validate(duplicated)).anyMatch { it.contains("repetidos") }
    }

    @Test
    fun `universo reconhecido na negociacao coincide com os contratos aprovados na publicacao`() {
        assertThat(CapabilityMatrix().known).isEqualTo(ComponentContracts.APPROVED)
    }

    @Test
    fun `app atual nao recebe type novo sem declarar e recebe quando declara`() {
        val matrix = CapabilityMatrix()
        val novos = setOf(
            ComponentContracts.TRANSACTION_SUMMARY,
            ComponentContracts.CATALOG_NAVIGATION,
            ComponentContracts.PRODUCT_COLLECTION,
        )
        for (platform in ClientPlatform.entries) {
            for (version in listOf(SemVer(8, 4, 0), SemVer(8, 14, 2), SemVer(9, 0, 0))) {
                assertThat(matrix.effective(context(platform, version, emptyList())))
                    .`as`("%s %s sem declarar", platform, version)
                    .doesNotContainAnyElementsOf(novos)
            }
        }
        val declared = matrix.effective(
            context(
                ClientPlatform.IOS,
                SemVer(8, 14, 2),
                listOf(ComponentContracts.TRANSACTION_SUMMARY, Capability("inventado", 1))
            ),
        )
        assertThat(declared).contains(ComponentContracts.TRANSACTION_SUMMARY).doesNotContain(Capability("inventado", 1))
    }

    @Test
    fun `transaction_summary valida itens, direcao, limite e gatilhos pareados`() {
        val valid = section(
            "transaction_summary",
            mapOf(
                "title" to "Ultimas",
                "items" to listOf(tx("t1", "debit"), tx("t2", "credit")),
                "viewAllLabel" to "Ver extrato",
                "viewAllActionId" to "act_all",
            ),
        )
        assertThat(ComponentPropsValidator.validate(valid)).isEmpty()

        val invalid = section(
            "transaction_summary",
            mapOf(
                "items" to (1..6).map { tx("t$it", "estorno") },
                "filterLabel" to "Filtrar",
            ),
        )
        assertThat(ComponentPropsValidator.validate(invalid))
            .anyMatch { it.contains("props.title obrigatorio") }
            .anyMatch { it.contains("entre 1 e 5 itens, tem 6") }
            .anyMatch { it.contains("direction deve ser um de") }
            .anyMatch { it.contains("props.filterLabel e props.filterActionId devem aparecer juntos") }
    }

    @Test
    fun `catalog_navigation exige alguma entrada e uma categoria selecionada no maximo`() {
        assertThat(ComponentPropsValidator.validate(section("catalog_navigation", emptyMap())))
            .anyMatch { it.contains("exige busca, filtro ou categorias") }

        val twoSelected = section(
            "catalog_navigation",
            mapOf(
                "categories" to listOf(
                    mapOf("id" to "a", "label" to "A", "selected" to true, "actionId" to "act_a"),
                    mapOf("id" to "b", "label" to "B", "selected" to true, "actionId" to "act_b"),
                ),
            ),
        )
        assertThat(ComponentPropsValidator.validate(twoSelected)).anyMatch { it.contains("no maximo uma categoria") }

        val search =
            section("catalog_navigation", mapOf("searchPlaceholder" to "Buscar", "searchActionId" to "act_search"))
        assertThat(ComponentPropsValidator.validate(search)).isEmpty()
    }

    @Test
    fun `product_collection limita a vitrine e recusa operacao comercial`() {
        val tooMany = section(
            "product_collection",
            mapOf("items" to (1..13).map { product("p$it") }),
        )
        assertThat(ComponentPropsValidator.validate(tooMany)).anyMatch { it.contains("entre 1 e 12 itens, tem 13") }

        val withCart = section(
            "product_collection",
            mapOf("items" to listOf(product("p1") + ("quantity" to 1) + ("addToCart" to true))),
        )
        assertThat(ComponentPropsValidator.validate(withCart)).anyMatch { it.contains("operacao comercial fora do escopo") }

        val missingAction = section("product_collection", mapOf("items" to listOf(product("p1") - "actionId")))
        assertThat(ComponentPropsValidator.validate(missingAction)).anyMatch { it.contains("items[0].actionId obrigatorio") }
    }

    @Test
    fun `types legados seguem sem validador de props para nao quebrar specs publicadas`() {
        assertThat(ComponentPropsValidator.validate(section("top_bar", emptyMap()))).isEmpty()
    }

    private fun active(capability: Capability) =
        ComponentType(capability.type, capability.typeVersion, "ACTIVE", "3", emptyList())

    private fun section(type: String, props: Map<String, Any?>) =
        Section(id = "sec_$type", slot = "slot", type = type, typeVersion = 1, props = props)

    private fun tx(id: String, direction: String) = mapOf(
        "id" to id,
        "description" to "Compra",
        "amountDisplay" to "- R$ 10,00",
        "direction" to direction,
    )

    private fun product(id: String) = mapOf(
        "id" to id,
        "name" to "Produto $id",
        "priceDisplay" to "R$ 10,00",
        "actionId" to "act_$id",
    )

    private fun context(platform: ClientPlatform, version: SemVer, declared: List<Capability>) = ClientContext(
        platform = platform,
        appVersion = version,
        build = "1",
        osVersion = null,
        schemaVersion = "3",
        locale = "pt-BR",
        apiVersion = "1",
        headerCapabilities = declared,
    )
}
