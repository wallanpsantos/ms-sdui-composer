package br.com.empresa.sdui.core.compat

import br.com.empresa.sdui.core.model.Capability
import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentContracts
import br.com.empresa.sdui.core.model.SemVer
import br.com.empresa.sdui.core.model.VersionRange

/**
 * Matriz de compatibilidade de capacidades e resolução de componentes suportados por plataforma e versão.
 *
 * ### 1. O que faz
 * Modela as regras de suporte a componentes Server-Driven UI no ecossistema de clientes, combinando as
 * suposições padrão do servidor baseadas na versão do aplicativo com as capacidades estendidas declaradas
 * explicitamente pelo cliente via cabeçalho HTTP `Component-Capabilities`.
 *
 * ### 2. Para que serve
 * Resolve a fragmentação entre diferentes versões de aplicativos móveis em circulação no mercado. Permite
 * que versões legadas (como versões antigas de iOS) recebam com segurança um subconjunto restrito de componentes
 * sem risco de renderização falha, enquanto versões recentes ou variantes de teste recebem novos componentes
 * gradualmente, viabilizando evolução contínua da interface.
 *
 * ### 3. Como funciona
 * Mantém um índice hierárquico [indexed] por plataforma e versão. No momento da requisição, calcula as
 * capacidades presumidas do servidor via [serverCaps] e acrescenta as capacidades declaradas pelo cliente.
 *
 * **Regra de Sanitização e Blindagem de Cache (Seção 19 de `AGENTS.md`):**
 * As capacidades informadas no cabeçalho `Component-Capabilities` são estritamente filtradas contra o universo
 * de capacidades conhecidas ([known]). Uma capacidade arbitrária inventada por um cliente mal-intencionado
 * nunca casaria com nenhuma seção de spec, mas seria incorporada ao `capsHash`, gerando uma chave de cache
 * de árvore nova para cada requisição. A filtragem contra [known] impede ataques de poluição e exaustão de cache.
 *
 * @param byPlatformVersion Mapa opcional de mapeamento plataforma/versão para capabilities (padrão via [defaultMatrix]).
 */
class CapabilityMatrix(
    /**
     * Mapeamento original plano entre pares (plataforma, faixa de versão) e seus respectivos conjuntos de capacidades.
     *
     * ### 1. O que faz
     * Armazena as associações base de regras de compatibilidade declaradas no momento da instanciação.
     *
     * ### 2. Para que serve
     * Serve como fonte de dados declarativa para geração dos índices otimizados e conjuntos de referência.
     *
     * ### 3. Como funciona
     * Mapa imutável associando tuplas de [ClientPlatform] e representação textual de versão a conjuntos de [Capability].
     */
    private val byPlatformVersion: Map<Pair<ClientPlatform, String>, Set<Capability>> = defaultMatrix(),
) {
    /**
     * Estrutura interna indexada hierarquicamente por plataforma e string de versão.
     *
     * ### 1. O que faz
     * Organiza as regras de compatibilidade em um mapa aninhado indexado por [ClientPlatform].
     *
     * ### 2. Para que serve
     * Otimiza as buscas de capacidades no hot path de composição, evitando percorrer toda a lista de chaves da matriz plana.
     *
     * ### 3. Como funciona
     * Agrupa as entradas de [byPlatformVersion] pela plataforma e indexa as versões sob cada plataforma em tempo de inicialização.
     */
    private val indexed: Map<ClientPlatform, Map<String, Set<Capability>>> =
        byPlatformVersion.entries
            .groupBy({ it.key.first }, { it.key.second to it.value })
            .mapValues { (_, versions) -> versions.toMap() }

    /**
     * Universo finito de todas as capacidades de componentes que o servidor homologa e reconhece.
     *
     * ### 1. O que faz
     * Consolida a união de todas as capabilities presentes na matriz de versões com os contratos
     * formalmente aprovados no catálogo ([ComponentContracts.APPROVED]).
     *
     * ### 2. Para que serve
     * Atua como barreira de segurança inegociável contra poluição de cache: qualquer capability arbitrária enviada
     * no cabeçalho HTTP que não conste neste conjunto é sumariamente descartada antes da geração da chave de cache.
     *
     * ### 3. Como funciona
     * Une os valores mapeados em [byPlatformVersion] com o conjunto estático [ComponentContracts.APPROVED].
     * Contratos novos não entram em nenhuma faixa padrão de [defaultMatrix] — eles só alcançam clientes que
     * os declarem ativamente no cabeçalho e que estejam contidos neste conjunto.
     */
    val known: Set<Capability> = byPlatformVersion.values.flatten().toSet() + ComponentContracts.APPROVED

    /**
     * Calcula o conjunto de capacidades efetivas aplicáveis à requisição do cliente.
     *
     * ### 1. O que faz
     * Combina as capacidades que o servidor assume para a versão do aplicativo com as capacidades válidas
     * declaradas no cabeçalho do cliente.
     *
     * ### 2. Para que serve
     * Fornece o conjunto final de [Capability] utilizado no filtro de seções (`Filter`) e na assinatura
     * de cache de árvores hidratadas (`CapsHash`).
     *
     * ### 3. Como funciona
     * Obtém as capacidades base via [serverCaps]. Filtra `context.headerCapabilities` mantendo apenas aquelas
     * pertencentes ao universo [known]. Retorna a união entre as capacidades do servidor e as declaradas filtradas.
     *
     * @param context Contexto validado do cliente requisitante.
     * @return Conjunto imutável de capacidades suportadas prontas para uso no pipeline.
     */
    fun effective(context: ClientContext): Set<Capability> {
        val server = serverCaps(context.platform, context.appVersion)
        val declared = context.headerCapabilities.filter { it in known }
        return server + declared
    }

    /**
     * Resolve as capacidades pré-configuradas pelo servidor para uma plataforma e versão de aplicativo.
     *
     * ### 1. O que faz
     * Busca na matriz de regras as capacidades correspondentes à versão maior/menor (`majorMinor`) do cliente.
     *
     * ### 2. Para que serve
     * Define o patamar mínimo garantido de suporte sem exigir que o cliente móvel liste exaustivamente todas
     * as suas capacidades em cada chamada HTTP.
     *
     * ### 3. Como funciona
     * Consulta o mapa de versões indexado para a plataforma:
     * 1. Busca por correspondência exata com [SemVer.majorMinor] (ex.: "8.4").
     * 2. Se ausente, recorre à regra genérica de curinga (`"*"`).
     * 3. Caso a plataforma ou regra não existam, assume com segurança o catálogo do MVP ([ComponentContracts.LEGACY_HOME]).
     *
     * @param platform Plataforma nativa do cliente ([ClientPlatform.IOS] ou [ClientPlatform.ANDROID]).
     * @param appVersion Versão semântica do aplicativo.
     * @return Conjunto de capacidades associadas à versão informada.
     */
    fun serverCaps(platform: ClientPlatform, appVersion: SemVer): Set<Capability> {
        val platformMap = indexed[platform] ?: return ComponentContracts.LEGACY_HOME
        return platformMap[appVersion.majorMinor]
            ?: platformMap["*"]
            ?: ComponentContracts.LEGACY_HOME
    }

    /**
     * Produz um conjunto de versões representativas (pontas e transições) para testes dentro de uma faixa.
     *
     * ### 1. O que faz
     * Amostra as versões limítrofes e pontos de inflexão registrados na matriz dentro do intervalo [range].
     *
     * ### 2. Para que serve
     * Permite validar o comportamento de regras de targeting em todas as transições críticas de versão sem
     * precisar gerar exaustivamente dezenas de milhares de versões intermediárias.
     *
     * ### 3. Como funciona
     * Inclui a versão mínima (`range.min`) e a máxima (`range.max`). Varre as chaves de versão registradas
     * no índice da plataforma que pertençam ao intervalo. Para cada transição, calcula o próximo número de
     * versão com proteção estrita contra overflow numérico (verificando `minor < Int.MAX_VALUE` e `major < Int.MAX_VALUE`),
     * adicionando o ponto ao conjunto resultante caso pertença a [range].
     *
     * @param platform Plataforma a ser consultada.
     * @param range Intervalo de versões semânticas a ser amostrado.
     * @return Conjunto de instâncias de [SemVer] representando os pontos de teste da faixa.
     */
    fun versionSamples(platform: ClientPlatform, range: VersionRange): Set<SemVer> = buildSet {
        add(range.min)
        range.max?.let { add(it) }
        for (key in indexed[platform].orEmpty().keys) {
            val start = SemVer.parse(key) ?: continue
            if (range.contains(start)) add(start)
            val next = when {
                start.minor < Int.MAX_VALUE -> SemVer(start.major, start.minor + 1, 0)
                start.major < Int.MAX_VALUE -> SemVer(start.major + 1, 0, 0)
                else -> null
            }
            if (next != null && range.contains(next)) add(next)
        }
    }

    /**
     * Utilitários e fábrica estática da matriz canônica de capacidades do ecossistema.
     *
     * ### 1. O que faz
     * Agrupa os métodos de construção da matriz padrão utilizada em produção e testes.
     *
     * ### 2. Para que serve
     * Centraliza a configuração das faixas legadas e das versões modernas em um ponto único de verdade.
     *
     * ### 3. Como funciona
     * Provê o método [defaultMatrix] que associa versões conhecidas de iOS aos seus subconjuntos restritos.
     */
    companion object {
        /**
         * Cria o mapeamento padrão de capacidades por plataforma e versão de aplicativo.
         *
         * ### 1. O que faz
         * Retorna a matriz canônica configurando o suporte total ([ComponentContracts.LEGACY_HOME]) como curinga (`"*"`)
         * para Android e iOS, e restringindo versões antigas de iOS (8.4 a 8.9) a um subconjunto de 3 componentes.
         *
         * ### 2. Para que serve
         * Estabelece a linha de base de compatibilidade do BFF, garantindo que versões antigas de app não recebam
         * componentes introduzidos posteriormente no ciclo de vida da plataforma.
         *
         * ### 3. Como funciona
         * Constrói e retorna um mapa imutável onde iOS nas versões "8.4" a "8.9" recebe apenas `top_bar`,
         * `shortcut_shelf` e `account_card`, enquanto todas as demais versões recebem o catálogo completo do MVP.
         *
         * @return Mapa associando pares de plataforma e versão aos respectivos conjuntos de [Capability].
         */
        fun defaultMatrix(): Map<Pair<ClientPlatform, String>, Set<Capability>> {
            val all = ComponentContracts.LEGACY_HOME
            val iosLegacy = setOf(
                Capability("top_bar", 1),
                Capability("shortcut_shelf", 1),
                Capability("account_card", 1),
            )
            return mapOf(
                (ClientPlatform.IOS to "*") to all,
                (ClientPlatform.ANDROID to "*") to all,
                (ClientPlatform.IOS to "8.4") to iosLegacy,
                (ClientPlatform.IOS to "8.5") to iosLegacy,
                (ClientPlatform.IOS to "8.6") to iosLegacy,
                (ClientPlatform.IOS to "8.7") to iosLegacy,
                (ClientPlatform.IOS to "8.8") to iosLegacy,
                (ClientPlatform.IOS to "8.9") to iosLegacy,
            )
        }
    }
}
