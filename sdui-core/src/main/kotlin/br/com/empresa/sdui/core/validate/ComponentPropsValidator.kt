package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.Section

/**
 * Valida as props dos contratos de componente novos (ADR-020): obrigatoriedade, tipos, limites
 * de itens e referencias a actions.
 *
 * Os sete types legados continuam sem validador de props — o contrato deles esta fixado pela
 * fixture canonica e pelos testes de `sdui-contract`, e endurecer aqui quebraria specs ja
 * publicadas. Os contratos novos nascem estritos: e mais barato recusar na publicacao do que
 * descobrir no renderer um campo que o app nao conhece.
 *
 * Nenhum dos tres carrega operacao de negocio. Quantidade, favorito, carrinho e checkout sao
 * destinos nativos; o composer so entrega a intencao de ir ate eles (`navigate` ou
 * `open_bottom_sheet`).
 */
object ComponentPropsValidator {
    /** Vitrine limitada: catalogo inteiro nao cabe num envelope; paginacao e do destino nativo. */
    const val MAX_PRODUCTS: Int = 12
    const val MAX_CATEGORIES: Int = 12
    const val MAX_TRANSACTIONS: Int = 5

    private val DIRECTIONS: Set<String> = setOf("credit", "debit")

    /** Chaves de operacao comercial que o composer nao executa nem representa. */
    private val COMMERCE_OPERATION_KEYS: Set<String> = setOf(
        "quantity", "favorite", "favorited", "addtocart", "cart", "cartid", "checkout", "stock", "sku",
    )

    fun validate(section: Section): List<String> = when (section.capability) {
        ComponentContracts.TRANSACTION_SUMMARY -> transactionSummary(section)
        ComponentContracts.CATALOG_NAVIGATION -> catalogNavigation(section)
        ComponentContracts.PRODUCT_COLLECTION -> productCollection(section)
        else -> emptyList()
    }

    private fun transactionSummary(section: Section): List<String> {
        val errors = Errors(section)
        errors.requireText(section.props, "title")
        val items = errors.requireItems(section.props, "items", min = 1, max = MAX_TRANSACTIONS)
        items.forEachIndexed { index, item ->
            val path = "items[$index]"
            errors.requireText(item, "id", path)
            errors.requireText(item, "description", path)
            errors.requireText(item, "amountDisplay", path)
            val direction = item["direction"]
            if (direction !is String || direction !in DIRECTIONS) {
                errors += "$path.direction deve ser um de $DIRECTIONS"
            }
            errors.optionalText(item, "detail", path)
            errors.optionalText(item, "icon", path)
        }
        errors.uniqueIds(items)
        errors.pairedTrigger(section.props, "viewAllLabel", "viewAllActionId")
        errors.pairedTrigger(section.props, "filterLabel", "filterActionId")
        return errors.list
    }

    private fun catalogNavigation(section: Section): List<String> {
        val errors = Errors(section)
        val search = errors.pairedTrigger(section.props, "searchPlaceholder", "searchActionId")
        val filter = errors.pairedTrigger(section.props, "filterLabel", "filterActionId")
        val categories = if (section.props.containsKey("categories")) {
            errors.requireItems(section.props, "categories", min = 1, max = MAX_CATEGORIES)
        } else {
            emptyList()
        }
        categories.forEachIndexed { index, item ->
            val path = "categories[$index]"
            errors.requireText(item, "id", path)
            errors.requireText(item, "label", path)
            errors.requireText(item, "actionId", path)
            val selected = item["selected"]
            if (selected != null && selected !is Boolean) errors += "$path.selected deve ser booleano"
        }
        errors.uniqueIds(categories)
        if (categories.count { it["selected"] == true } > 1) {
            errors += "no maximo uma categoria pode estar selected"
        }
        if (!search && !filter && categories.isEmpty()) {
            errors += "catalog_navigation exige busca, filtro ou categorias"
        }
        errors.noCommerceOperation(section.props)
        return errors.list
    }

    private fun productCollection(section: Section): List<String> {
        val errors = Errors(section)
        errors.optionalText(section.props, "title")
        val items = errors.requireItems(section.props, "items", min = 1, max = MAX_PRODUCTS)
        items.forEachIndexed { index, item ->
            val path = "items[$index]"
            errors.requireText(item, "id", path)
            errors.requireText(item, "name", path)
            errors.requireText(item, "priceDisplay", path)
            errors.requireText(item, "actionId", path)
            errors.optionalText(item, "priceLabel", path)
            errors.optionalText(item, "imageUrl", path)
            errors.optionalText(item, "badge", path)
        }
        errors.uniqueIds(items)
        errors.pairedTrigger(section.props, "viewAllLabel", "viewAllActionId")
        errors.noCommerceOperation(section.props)
        return errors.list
    }

    /** Acumulador com os helpers de checagem, prefixando cada erro com a section. */
    private class Errors(private val section: Section) {
        val list = mutableListOf<String>()

        operator fun plusAssign(message: String) {
            list += "section ${section.id} (${section.type}@${section.typeVersion}): $message"
        }

        fun requireText(node: Map<*, *>, key: String, path: String = "props") {
            val value = node[key]
            if (value !is String || value.isBlank()) this += "$path.$key obrigatorio e textual"
        }

        fun optionalText(node: Map<*, *>, key: String, path: String = "props") {
            val value = node[key] ?: return
            if (value !is String || value.isBlank()) this += "$path.$key deve ser texto nao vazio"
        }

        fun requireItems(node: Map<*, *>, key: String, min: Int, max: Int): List<Map<*, *>> {
            val value = node[key]
            if (value !is List<*>) {
                this += "props.$key obrigatorio e deve ser lista"
                return emptyList()
            }
            if (value.size < min || value.size > max) {
                this += "props.$key deve ter entre $min e $max itens, tem ${value.size}"
            }
            val maps = value.filterIsInstance<Map<*, *>>()
            if (maps.size != value.size) this += "props.$key so aceita objetos"
            return maps
        }

        fun uniqueIds(items: List<Map<*, *>>) {
            val ids = items.mapNotNull { it["id"] as? String }
            if (ids.size != ids.toSet().size) this += "ids de itens repetidos"
        }

        /** Rotulo e action andam juntos: um gatilho sem action seria um toque sem efeito. */
        fun pairedTrigger(node: Map<*, *>, labelKey: String, actionKey: String): Boolean {
            val hasLabel = node.containsKey(labelKey)
            val hasAction = node.containsKey(actionKey)
            if (hasLabel != hasAction) {
                this += "props.$labelKey e props.$actionKey devem aparecer juntos"
                return false
            }
            if (!hasLabel) return false
            requireText(node, labelKey)
            requireText(node, actionKey)
            return true
        }

        fun noCommerceOperation(props: Map<String, Any?>) {
            val found = mutableSetOf<String>()
            PropWalk.walkKeys(props) { _, key ->
                if (key.lowercase() in COMMERCE_OPERATION_KEYS) found += key
            }
            if (found.isNotEmpty()) this += "operacao comercial fora do escopo do composer: $found"
        }
    }
}
