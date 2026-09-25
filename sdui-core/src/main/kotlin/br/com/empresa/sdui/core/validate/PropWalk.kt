package br.com.empresa.sdui.core.validate

/**
 * Utilitário de travessia e inspeção recursiva de propriedades de componentes Server-Driven UI.
 *
 * ### 1. O que faz
 * Fornece algoritmos de varredura profunda (traversal) e avaliação condicional sobre estruturas de dados
 * heterogêneas e aninhadas (`Map<*, *>` e `List<*>`), comumente utilizadas no transporte das propriedades
 * (`props`) de seções visuais.
 *
 * ### 2. Para que serve
 * Como o modelo de propriedades de Server-Driven UI é inerentemente flexível e polimórfico (mapa livre
 * de propriedades chave-valor), os guardas arquiteturais (`VisualGuard`, `PiiGuard`, `ActionGuard`) e validadores
 * dependem de uma travessia genérica e segura para:
 * - Localizar atributos visuais e estilísticos proibidos (ADR-010);
 * - Detectar dados regulados e informações pessoais sensíveis (ADR-015);
 * - Validar a integridade referencial de ações vinculadas a botões e gatilhos (CTAs);
 * - Prevenir ataques de negação de serviço (DoS) por estruturas com aninhamento excessivo.
 *
 * Durante a visita, acumula o caminho hierárquico pontuado (ex: `items[0].icon`), permitindo que as mensagens
 * de erro de validação indiquem com exatidão a localização de eventuais inconformidades.
 *
 * ### 3. Como funciona
 * Percorre grafos de mapas e listas através de funções recursivas especializadas. Implementa curto-circuito
 * (short-circuit) nas funções de predicado existencial ([anyString], [anyKey], [exceedsDepth]), interrompendo
 * a descida imediatamente após a primeira ocorrência positiva para maximizar a performance no runtime.
 */
object PropWalk {
    /**
     * Limite máximo estrito de níveis de aninhamento permitidos nas árvores de propriedades (`props`).
     *
     * ### 1. O que faz
     * Estabelece o teto de profundidade de contêineres (`Map` e `List`) aceito durante a validação de uma seção.
     *
     * ### 2. Para que serve
     * Protege o pipeline contra estouro de pilha da JVM (`StackOverflowError`) decorrente de documentos hostis
     * ou estruturas circulares, blindando o servidor e os clientes nativos antes da publicação da especificação.
     *
     * ### 3. Como funciona
     * Valor numérico constante (16) consumido por [exceedsDepth] para limitar o número máximo de chamadas recursivas.
     */
    const val MAX_PROPS_DEPTH: Int = 16

    /**
     * Varre recursivamente todas as chaves de mapas presentes na árvore informada.
     *
     * ### 1. O que faz
     * Percorre a hierarquia de objetos e dispara o retorno [visit] para cada chave de mapa encontrada,
     * informando o caminho JSON acumulado até o nó e a chave individual.
     *
     * ### 2. Para que serve
     * Permite a inspeção exaustiva de nomes de atributos por guardas arquiteturais (como `VisualGuard`),
     * apontando com precisão o caminho completo de qualquer campo irregular.
     *
     * ### 3. Como funciona
     * Avalia o tipo do nó atual em [value]:
     * - `Map<*, *>`: itera por cada chave, calcula o caminho pontuado (`path.key` ou apenas `key`), invoca
     *   o callback [visit] e chama recursivamente [walkKeys] para o valor filho.
     * - `List<*>`: itera sobre cada elemento indexado, compondo o caminho indexado (`path[index]`) e
     *   prosseguindo a descida recursiva.
     * - Outros tipos: atua como caso base, interrompendo a descida.
     *
     * @param value Objeto raiz ou nó intermediário a ser inspecionado.
     * @param path Caminho estrutural acumulado até o nó corrente (inicia vazio na raiz).
     * @param visit Função consumidora invocada a cada chave de mapa descoberta.
     */
    fun walkKeys(value: Any?, path: String = "", visit: (path: String, key: String) -> Unit) {
        when (value) {
            is Map<*, *> -> {
                for ((rawKey, child) in value) {
                    val key = rawKey?.toString() ?: continue
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    visit(childPath, key)
                    walkKeys(child, childPath, visit)
                }
            }

            is List<*> -> {
                value.forEachIndexed { index, child ->
                    walkKeys(child, "$path[$index]", visit)
                }
            }
        }
    }

    /**
     * Percorre recursivamente a estrutura e consome todos os valores textuais (`String`).
     *
     * ### 1. O que faz
     * Visita todos os nós folha do tipo texto em listas e mapas aninhados, submetendo cada valor a [visit].
     *
     * ### 2. Para que serve
     * Habilita a análise do conteúdo das strings das `props` para detecção de dados pessoais sensíveis
     * mascarados ou não (CPF, PAN de cartão de crédito) pelo `PiiGuard`.
     *
     * ### 3. Como funciona
     * Quando o nó é uma `String`, invoca [visit]. Se for um `Map` ou `List`, itera recursivamente sobre
     * seus valores e elementos até que todos os nós folha textuais tenham sido visitados.
     *
     * @param value Objeto raiz ou nó hierárquico a ser inspecionado.
     * @param visit Função consumidora que recebe cada string encontrada.
     */
    fun walkStrings(value: Any?, visit: (String) -> Unit) {
        when (value) {
            is String -> visit(value)
            is Map<*, *> -> value.values.forEach { walkStrings(it, visit) }
            is List<*> -> value.forEach { walkStrings(it, visit) }
        }
    }

    /**
     * Verifica de forma preguiçosa se existe alguma string na estrutura que atenda a um predicado.
     *
     * ### 1. O que faz
     * Avalia recursivamente os valores textuais da árvore de dados contra a condição informada em [predicate].
     *
     * ### 2. Para que serve
     * Permite localizar rapidamente menções a identificadores ou termos restritos sem necessidade de percorrer
     * ou alocar coleções de toda a estrutura quando uma evidência já foi encontrada.
     *
     * ### 3. Como funciona
     * Utiliza o operador de curto-circuito `any`: assim que um nó folha `String` satisfaz [predicate], a chamada
     * retorna `true` imediatamente, cancelando qualquer processamento adicional dos ramos restantes.
     *
     * @param value Estrutura de dados a ser inspecionada.
     * @param predicate Condição de teste aplicada a cada string encontrada.
     * @return `true` se ao menos uma string satisfizer o predicado; caso contrário, `false`.
     */
    fun anyString(value: Any?, predicate: (String) -> Boolean): Boolean = when (value) {
        is String -> predicate(value)
        is Map<*, *> -> value.values.any { anyString(it, predicate) }
        is List<*> -> value.any { anyString(it, predicate) }
        else -> false
    }

    /**
     * Verifica de forma preguiçosa se existe alguma chave de mapa que satisfaça a um predicado.
     *
     * ### 1. O que faz
     * Avalia as chaves de todos os níveis hierárquicos de mapas contra a condição informada em [predicate].
     *
     * ### 2. Para que serve
     * Permite checagens eficientes sobre a presença de chaves proibidas (como propriedades visuais ou
     * operações comerciais não suportadas) com custo mínimo de CPU.
     *
     * ### 3. Como funciona
     * Interrompe a descida recursiva no instante em que encontra a primeira chave que torna [predicate]
     * verdadeiro, retornando `true` imediatamente pelo encadeamento de curto-circuito dos mapas e listas.
     *
     * @param value Estrutura de dados a ser inspecionada.
     * @param predicate Condição de validação aplicada sobre cada chave encontrada.
     * @return `true` se ao menos uma chave satisfizer a condição; caso contrário, `false`.
     */
    fun anyKey(value: Any?, predicate: (String) -> Boolean): Boolean = when (value) {
        is Map<*, *> -> value.entries.any { (key, child) ->
            (key != null && predicate(key.toString())) || anyKey(child, predicate)
        }

        is List<*> -> value.any { anyKey(it, predicate) }
        else -> false
    }

    /**
     * Avalia se a profundidade hierárquica dos contêineres ultrapassa o limite especificado.
     *
     * ### 1. O que faz
     * Conta o número de níveis aninhados de contêineres (`Map` e `List`), verificando se excede [maxDepth].
     *
     * ### 2. Para que serve
     * Blinda a aplicação contra negação de serviço e estouro de pilha durante a fase de publicação e governança,
     * impedindo a entrada de especificações com estruturas aninhadas maliciosas ou complexidade abusiva.
     *
     * ### 3. Como funciona
     * Incrementa o contador [depth] a cada transição de nível em mapas ou listas. Se a verificação
     * `depth + 1 > maxDepth` for verdadeira, interrompe a recursão retornando `true`. Dessa forma, o algoritmo
     * nunca executa mais do que `maxDepth + 1` chamadas na pilha de execução.
     *
     * @param value Estrutura a ser inspecionada.
     * @param maxDepth Limite máximo tolerado de profundidade (padrão [MAX_PROPS_DEPTH]).
     * @param depth Nível de profundidade atual na recursão (inicia em 0).
     * @return `true` se a profundidade exceder o limite estabelecido; caso contrário, `false`.
     */
    fun exceedsDepth(value: Any?, maxDepth: Int = MAX_PROPS_DEPTH, depth: Int = 0): Boolean = when (value) {
        is Map<*, *> -> depth + 1 > maxDepth || value.values.any { exceedsDepth(it, maxDepth, depth + 1) }
        is List<*> -> depth + 1 > maxDepth || value.any { exceedsDepth(it, maxDepth, depth + 1) }
        else -> false
    }

    /**
     * Extrai todas as referências a identificadores de ação declaradas nas propriedades.
     *
     * ### 1. O que faz
     * Localiza e coleta todos os identificadores de ações vinculados nas `props` de um componente.
     *
     * ### 2. Para que serve
     * Alimenta a verificação do `ActionGuard`, permitindo auditar se toda ação referenciada por um
     * botão ou gatilho visual foi devidamente declarada na lista `actions` da seção, evitando toques
     * que não produzem efeito no aplicativo nativo.
     *
     * ### 3. Como funciona
     * Varre as propriedades em profundidade. Toda entrada de mapa cuja chave satisfaz [isActionReference]
     * e cujo valor associado é uma `String` tem seu valor adicionado à lista acumuladora de IDs retornada.
     *
     * @param props Mapa de propriedades da seção visual.
     * @return Lista de identificadores de ação (`actionId`) referenciados no componente.
     */
    fun collectActionIds(props: Map<String, Any?>): List<String> {
        val ids = mutableListOf<String>()
        fun scan(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    for ((key, child) in node) {
                        if (key is String && isActionReference(key) && child is String) ids += child
                    }
                    node.values.forEach { scan(it) }
                }

                is List<*> -> node.forEach { scan(it) }
            }
        }
        scan(props)
        return ids
    }

    /**
     * Determina se o nome de uma propriedade representa uma referência a identificador de ação.
     *
     * ### 1. O que faz
     * Checa se o nome da chave corresponde ao padrão de identificador de ação do design system SDUI.
     *
     * ### 2. Para que serve
     * Padroniza o reconhecimento de gatilhos de ação em componentes simples (`actionId`) e em componentes
     * com múltiplos botões de ação (ex: `searchActionId`, `viewAllActionId`, `filterActionId`).
     *
     * ### 3. Como funciona
     * Avalia se a string [key] é exatamente igual a `"actionId"` ou se possui o sufixo `"ActionId"`.
     *
     * @param key Nome da chave a ser analisada.
     * @return `true` se a chave for uma referência de ação; caso contrário, `false`.
     */
    fun isActionReference(key: String): Boolean = key == "actionId" || key.endsWith("ActionId")

    /**
     * Conjunto de chaves indicativas de acoplamento espacial ou dependência de outras seções.
     */
    private val FOREIGN_REF_KEYS: Set<String> = setOf("sectionId", "otherSectionId", "slotIndex", "position")

    /**
     * Verifica se as propriedades de uma seção fazem referência indevida a outras seções da mesma tela.
     *
     * ### 1. O que faz
     * Inspeciona valores e chaves das `props` para detectar referências a identificadores de seções irmãs
     * ou atributos de acoplamento entre seções.
     *
     * ### 2. Para que serve
     * Assegura o princípio arquitetural de independência e seções autocontidas (ADR-004 e ADR-010): uma
     * seção não pode depender do layout, da ordem ou do conteúdo de outra seção para funcionar ou renderizar.
     *
     * ### 3. Como funciona
     * Filtra os identificadores alheios ([otherIds]), excluindo o próprio [ownId]. Em seguida:
     * 1. Varre os textos via [anyString] verificando se contêm os IDs de outras seções;
     * 2. Varre as chaves via [anyKey] verificando se contêm termos proibidos de referência externa ([FOREIGN_REF_KEYS]).
     * Retorna `true` se qualquer uma das condições for violada.
     *
     * @param props Mapa de propriedades da seção.
     * @param ownId Identificador da própria seção sob análise.
     * @param otherIds Conjunto de identificadores de todas as seções presentes na especificação.
     * @return `true` se houver violação de referência externa; caso contrário, `false`.
     */
    fun referencesForeignSection(props: Map<String, Any?>, ownId: String, otherIds: Set<String>): Boolean {
        val others = otherIds.filter { it != ownId && it.isNotEmpty() }
        if (others.isNotEmpty() && anyString(props) { text -> others.any { text.contains(it) } }) return true
        return anyKey(props) { it in FOREIGN_REF_KEYS }
    }
}
