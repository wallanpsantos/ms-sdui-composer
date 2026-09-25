package br.com.empresa.sdui.core.validate

import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.MvpCatalog

/**
 * Validador de integridade e conformidade do catálogo de componentes de UI.
 *
 * ### 1. O que faz
 * Audita a lista de componentes declarados em um catálogo ([Catalog]) submetido à governança,
 * garantindo conformidade com contratos homologados, ausência de primitivas genéricas e manutenção
 * dos tipos portantes da aplicação.
 *
 * ### 2. Para que serve
 * Garante que o catálogo oficial do servidor permaneça em estrita sincronia com os recursos que os
 * clientes móveis (iOS e Android) são capazes de renderizar com segurança (ADR-020):
 * - **Rejeição de Primitivas Genéricas:** Bloqueia nomes abstratos de layout como `row`, `column`, `container`
 *   ou `card` genérico ([MvpCatalog.GENERIC_TYPE_NAMES]), preservando o Server-Driven UI orientado a
 *   componentes de negócio semânticos;
 * - **Contratos Homologados:** Garante que todo componente novo pertença ao conjunto fechado de contratos
 *   aprovados ([ComponentContracts.isApproved]), prevenindo nomes livres e arbitrários que inflariam a
 *   cardinalidade de séries temporais de métricas de telemetria;
 * - **Unicidade de Contratos:** Veda componentes repetidos na mesma versão (`type@typeVersion`);
 * - **Garantia da Surface Home:** Exige a presença ativa dos sete componentes basilares do MVP
 *   ([ComponentContracts.LEGACY_HOME]), impedindo que uma atualização de catálogo quebre a tela principal.
 *
 * ### 3. Como funciona
 * Itera sobre os componentes de [Catalog.components] aplicando verificações sequenciais de nome e aprovação
 * contratual. Utiliza agrupamento por chave wire (`type@typeVersion`) para identificar colisões e cruza o
 * conjunto de componentes ativos contra a lista essencial de [ComponentContracts.LEGACY_HOME], retornando
 * a lista acumulada de violações identificadas.
 */
object CatalogValidator {
    /**
     * Executa a validação abrangente das definições do catálogo de componentes.
     *
     * ### 1. O que faz
     * Valida cada componente contra regras de tipos genéricos, homologação formal de contratos, duplicidade
     * cadastral e cobertura dos componentes ativos obrigatórios da Home.
     *
     * ### 2. Para que serve
     * Atua como barreira de validação no fluxo de governança administrativa (maker-checker), impedindo
     * que propostas de alteração de catálogo inconsistentes sejam criadas ou aprovadas para publicação.
     *
     * ### 3. Como funciona
     * 1. Itera por cada componente, verificando se seu tipo está em [MvpCatalog.GENERIC_TYPE_NAMES] e se
     *    possui contrato aprovado em [ComponentContracts.isApproved];
     * 2. Agrupa os componentes pela representação wire (`type@typeVersion`) identificando chaves com mais de uma ocorrência;
     * 3. Filtra componentes com status [ComponentType.STATUS_ACTIVE] e verifica se todos os contratos
     *    obrigatórios de [ComponentContracts.LEGACY_HOME] estão presentes no conjunto ativo;
     * 4. Retorna a lista com todas as mensagens de erro acumuladas (vazia se o catálogo estiver 100% regular).
     *
     * @param catalog Instância de [Catalog] a ser validada.
     * @return Lista contendo as descrições de todas as inconsistências encontradas.
     */
    fun validate(catalog: Catalog): List<String> {
        val errors = mutableListOf<String>()
        for (component in catalog.components) {
            if (component.type.lowercase() in MvpCatalog.GENERIC_TYPE_NAMES) {
                errors += "type generico recusado: ${component.type}"
            } else if (!ComponentContracts.isApproved(component.type, component.typeVersion)) {
                errors += "componente sem contrato aprovado: ${component.wire()}"
            }
        }
        val duplicated = catalog.components.groupBy { it.wire() }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            errors += "componentes repetidos no catalogo: $duplicated"
        }
        val active = catalog.components
            .filter { it.status.equals(ComponentType.STATUS_ACTIVE, ignoreCase = true) }
            .map { it.wire() }
            .toSet()
        val missingLegacy = ComponentContracts.LEGACY_HOME.map { it.wire() }.filterNot { it in active }
        if (missingLegacy.isNotEmpty()) {
            errors += "catalogo ativo deve conter os types da Home: faltam $missingLegacy"
        }
        return errors
    }
}
