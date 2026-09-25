package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.Section

/**
 * Validador estrito de propriedades (`props`) para os novos contratos de componentes (ADR-020).
 *
 * ### 1. O que faz
 * Inspeciona minuciosamente a estrutura das propriedades de seções visuais pertencentes aos contratos
 * homologados pós-MVP: [ComponentContracts.TRANSACTION_SUMMARY], [ComponentContracts.CATALOG_NAVIGATION]
 * e [ComponentContracts.PRODUCT_COLLECTION].
 *
 * ### 2. Para que serve
 * Assegura que novos componentes introduzidos no ecossistema SDUI mantenham estabilidade e previsibilidade:
 * - **Tipagem e Obrigatoriedade:** Garante que campos essenciais (títulos, identificadores, valores monetários
 *   formatados) estejam presentes com os tipos de dados primitivos corretos;
 * - **Limites Físicos de Payload:** Restringe o número máximo de itens em coleções ([MAX_PRODUCTS],
 *   [MAX_CATEGORIES], [MAX_TRANSACTIONS]), prevenindo respostas excessivamente pesadas e mantendo 60fps na rolagem nativa;
 * - **Pareamento Estrito de Ações:** Obriga que rótulos de botões venham sempre acompanhados de seus
 *   respectivos identificadores de ação (`actionId`), evitando botões órfãos ou toques sem resposta;
 * - **Isolamento de E-Commerce:** Rejeita chaves de operações comerciais transacionais ([COMMERCE_OPERATION_KEYS]),
 *   preservando o papel do composer como controlador de apresentação e delegando fluxos transacionais (carrinho, estoque)
 *   aos destinos nativos;
 * - **Preservação do Legado:** Não aplica restrições adicionais sobre os 7 componentes do MVP, cuja semântica
 *   permanece blindada pelas fixtures canônicas da Home.
 *
 * ### 3. Como funciona
 * Avalia o [Section.capability] no método [validate]. Encaminha a seção para rotinas de inspeção específicas
 * ([transactionSummary], [catalogNavigation], [productCollection]). As checagens utilizam o acumulador interno
 * [Errors], que formata mensagens prefixadas com o ID e tipo da seção e aplica verificações estruturais e
 * varreduras via [PropWalk].
 */
object ComponentPropsValidator {
    /**
     * Limite máximo de produtos exibíveis em uma coleção ou vitrine de produtos.
     *
     * ### 1. O que faz
     * Fixa em 12 itens o teto permitido na lista de produtos de um [ComponentContracts.PRODUCT_COLLECTION].
     *
     * ### 2. Para que serve
     * Evita que o envelope JSON transporte catálogos inteiros de produtos, delegando paginação e exploração
     * detalhada às telas de destino nativas do aplicativo.
     *
     * ### 3. Como funciona
     * Utilizado na validação de tamanho da lista `items` em [productCollection].
     */
    const val MAX_PRODUCTS: Int = 12

    /**
     * Limite máximo de categorias de navegação permitidas.
     *
     * ### 1. O que faz
     * Estabelece o teto de 12 categorias simultâneas para [ComponentContracts.CATALOG_NAVIGATION].
     *
     * ### 2. Para que serve
     * Mantém a usabilidade e a ergonomia de carrosséis e abas de categorias sem poluir visualmente a interface móvel.
     *
     * ### 3. Como funciona
     * Aplicado na validação da lista `categories` em [catalogNavigation].
     */
    const val MAX_CATEGORIES: Int = 12

    /**
     * Limite máximo de transações financeiras em resumo de extrato.
     *
     * ### 1. O que faz
     * Fixa em 5 o número máximo de lançamentos no extrato compacto [ComponentContracts.TRANSACTION_SUMMARY].
     *
     * ### 2. Para que serve
     * Garante que o componente opere estritamente como resumo na Home ou Dashboard, incentivando o redirecionamento
     * para o extrato nativo completo via CTA dedicado.
     *
     * ### 3. Como funciona
     * Consumido como limite superior na validação de `items` em [transactionSummary].
     */
    const val MAX_TRANSACTIONS: Int = 5

    /**
     * Conjunto de direções financeiras aceitas em lançamentos de transação.
     */
    private val DIRECTIONS: Set<String> = setOf("credit", "debit")

    /**
     * Conjunto de chaves indicativas de operações de e-commerce vedadas no composer.
     */
    private val COMMERCE_OPERATION_KEYS: Set<String> = setOf(
        "quantity", "favorite", "favorited", "addtocart", "cart", "cartid", "checkout", "stock", "sku",
    )

    /**
     * Valida as propriedades da seção contra o contrato de seu componente.
     *
     * ### 1. O que faz
     * Roteia a seção visual para o validador específico de seu contrato de componente, se aplicável.
     *
     * ### 2. Para que serve
     * Garante conformidade estrita de propriedades para componentes da ADR-020, ignorando tipos legados
     * ou não sujeitos a validação neste módulo.
     *
     * ### 3. Como funciona
     * Avalia [Section.capability]:
     * - [ComponentContracts.TRANSACTION_SUMMARY] -> executa [transactionSummary];
     * - [ComponentContracts.CATALOG_NAVIGATION] -> executa [catalogNavigation];
     * - [ComponentContracts.PRODUCT_COLLECTION] -> executa [productCollection];
     * - Outros componentes -> retorna lista vazia.
     *
     * @param section Seção visual a ser validada.
     * @return Lista contendo as violações de propriedades encontradas (vazia se válida).
     */
    fun validate(section: Section): List<String> = when (section.capability) {
        ComponentContracts.TRANSACTION_SUMMARY -> transactionSummary(section)
        ComponentContracts.CATALOG_NAVIGATION -> catalogNavigation(section)
        ComponentContracts.PRODUCT_COLLECTION -> productCollection(section)
        else -> emptyList()
    }

    /**
     * Valida o contrato de resumo financeiro de transações (`transaction_summary@1`).
     */
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

    /**
     * Valida o contrato de navegação de catálogo e categorias (`catalog_navigation@1`).
     */
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

    /**
     * Valida o contrato de vitrine ou coleção de produtos (`product_collection@1`).
     */
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

    /**
     * Classe utilitária acumuladora de mensagens de erro de validação de propriedades.
     *
     * ### 1. O que faz
     * Fornece asserções tipadas e centralizadas sobre nós de propriedades, prefixando os erros
     * com o identificador e tipo da seção sob análise.
     *
     * ### 2. Para que serve
     * Padroniza as mensagens de erro de validação e elimina código duplicado de checagem de tipos
     * em estruturas heterogêneas de mapas e listas.
     *
     * ### 3. Como funciona
     * Acumula mensagens na lista mutável [list], oferecendo funções especializadas para validação de
     * texto obrigatório/opcional, listas homogêneas de mapas, unicidade de IDs e pareamento de CTAs.
     */
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

        /**
         * Assegura que rótulo e identificador de ação sejam declarados conjuntamente.
         */
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
