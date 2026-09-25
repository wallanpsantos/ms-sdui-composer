package br.com.empresa.sdui.core.model

/**
 * Representação imutável e comparável de versões semânticas ordinais.
 *
 * ### 1. O que faz
 * Modela versões semânticas estruturadas no formato canônico `major.minor.patch`, permitindo comparações ordinais
 * diretas entre versões de aplicativos, sistemas operacionais e schemas.
 *
 * ### 2. Para que serve
 * É a base de cálculo para o Eixo C de compatibilidade (faixa de aplicativo cliente e versão de SO) e para o
 * Eixo A (versão de schema SDUI). Permite ao algoritmo de targeting decidir com precisão matemática se uma
 * especificação de tela (`Spec`) pode ser entregue a um dispositivo solicitante.
 *
 * ### 3. Como funciona
 * Implementa [Comparable] comparando sequencialmente [major], [minor] e [patch]. A blindagem contra falhas
 * no hot path utiliza conversão segura via `toIntOrNull()`, assegurando que entradas corrompidas ou strings
 * anômalas resultem em `null` (convertido em HTTP 400 na negociação), eliminando exceções `NumberFormatException`
 * ou erros HTTP 500 no servidor.
 *
 * @property major Número da versão maior (indica quebras de compatibilidade ou mudanças estruturais).
 * @property minor Número da versão menor (indica novas funcionalidades mantendo compatibilidade retroativa).
 * @property patch Número da correção de falhas (indica correções de bugs sem adição de funcionalidades).
 */
data class SemVer(
    /**
     * Componente major da versão semântica.
     *
     * ### 1. O que faz
     * Armazena o identificador numérico da versão principal.
     *
     * ### 2. Para que serve
     * Discrimina marcos arquiteturais e quebras de contrato de API ou protocolo.
     *
     * ### 3. Como funciona
     * Validado no construtor para garantir valor maior ou igual a zero.
     */
    val major: Int,

    /**
     * Componente minor da versão semântica.
     *
     * ### 1. O que faz
     * Armazena o identificador numérico da versão secundária.
     *
     * ### 2. Para que serve
     * Discrimina adições incrementais de funcionalidades com compatibilidade preservada.
     *
     * ### 3. Como funciona
     * Validado no construtor para garantir valor maior ou igual a zero.
     */
    val minor: Int,

    /**
     * Componente patch da versão semântica.
     *
     * ### 1. O que faz
     * Armazena o identificador numérico da revisão de correção.
     *
     * ### 2. Para que serve
     * Discrimina correções pontuais de defeitos sem impacto em contratos públicos.
     *
     * ### 3. Como funciona
     * Validado no construtor para garantir valor maior ou igual a zero.
     */
    val patch: Int,
) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "semver components must be >= 0" }
    }

    /**
     * Representação numérica ordinal ponderada em 64 bits (Long).
     *
     * ### 1. O que faz
     * Converte os três componentes semânticos em um único inteiro escalar ordenável.
     *
     * ### 2. Para que serve
     * Otimiza a criação de índices de ordenação, comparações diretas de intervalos numéricos e consultas eficientes
     * em repositórios e estruturas em memória.
     *
     * ### 3. Como funciona
     * Aplica uma ponderação decimal fixa por potências de milhão: `major * 10^12 + minor * 10^6 + patch`,
     * garantindo monotonicidade estrita para qualquer combinação válida de componentes.
     */
    val ordinal: Long = major.toLong() * 1_000_000_000_000L + minor.toLong() * 1_000_000L + patch.toLong()

    /**
     * Representação textual simplificada contendo apenas major e minor.
     *
     * ### 1. O que faz
     * Formata a versão semântica suprimindo o componente patch (ex.: "1.14").
     *
     * ### 2. Para que serve
     * Utilizada como fragmento estável em chaves de cache do Redis e caches em memória, agrupando compilações
     * que compartilham a mesma superfície de capacidades.
     *
     * ### 3. Como funciona
     * Concatena [major] e [minor] separados por ponto via interpolação de strings.
     */
    val majorMinor: String = "$major.$minor"

    /**
     * Compara esta instância com outra versão semântica.
     *
     * ### 1. O que faz
     * Determina a ordem relativa entre duas instâncias de [SemVer].
     *
     * ### 2. Para que serve
     * Permite a ordenação de coleções de versões e a verificação de elegibilidade em regras de targeting.
     *
     * ### 3. Como funciona
     * Compara sequencialmente [major], em seguida [minor] em caso de igualdade, e por fim [patch].
     *
     * @param other Outra instância de [SemVer] para comparação.
     * @return Valor negativo se menor, zero se igual, ou positivo se maior.
     */
    override fun compareTo(other: SemVer): Int {
        val c1 = major.compareTo(other.major)
        if (c1 != 0) return c1
        val c2 = minor.compareTo(other.minor)
        if (c2 != 0) return c2
        return patch.compareTo(other.patch)
    }

    /**
     * Converte a versão semântica para sua representação canônica em string.
     *
     * ### 1. O que faz
     * Formata os componentes como texto no padrão `major.minor.patch`.
     *
     * ### 2. Para que serve
     * Apresentação amigável em logs, mensagens de auditoria e serialização em respostas textuais.
     *
     * ### 3. Como funciona
     * Interpola `$major.$minor.$patch`.
     *
     * @return String no formato "X.Y.Z".
     */
    override fun toString(): String = "$major.$minor.$patch"

    /**
     * Converte a versão para o formato convencional de sistemas operacionais móveis.
     *
     * ### 1. O que faz
     * Formata a versão omitindo o componente patch quando este for zero (ex.: "17.4" em vez de "17.4.0").
     *
     * ### 2. Para que serve
     * Mantém conformidade com as convenções de versionamento do iOS e Android, onde patches nulos são frequentemente
     * omitidos nos cabeçalhos de dispositivo (`Client-OS-Version`).
     *
     * ### 3. Como funciona
     * Avalia se [patch] é zero; caso afirmativo retorna `"$major.$minor"`, caso contrário delega para [toString].
     *
     * @return Versão formatada amigável para SO móvel.
     */
    fun toOsString(): String = if (patch == 0) "$major.$minor" else toString()

    /**
     * Utilitários estáticos de parsing e conversão segura para [SemVer].
     *
     * ### 1. O que faz
     * Fornece métodos de fábrica para converter representações textuais em instâncias de [SemVer].
     *
     * ### 2. Para que serve
     * Isola a complexidade de validação de formato e previne que entradas externas maliciosas ou corrompidas
     * causem falhas não tratadas na aplicação.
     *
     * ### 3. Como funciona
     * Utiliza expressões regulares pré-compiladas combinadas com parsing numérico defensivo via `toIntOrNull()`.
     */
    companion object {
        private val THREE = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
        private val MAJOR_OPTIONAL_MINOR = Regex("""^(\d+)(?:\.(\d+))?$""")

        /**
         * Analisa e converte uma string de versão arbitrária em [SemVer], tolerando componentes ausentes.
         *
         * ### 1. O que faz
         * Efetua o parsing flexível aceitando os formatos `major`, `major.minor` ou `major.minor.patch`.
         *
         * ### 2. Para que serve
         * Utilizado na sanitização de cabeçalhos de clientes onde versões de SO ou app podem omitir componentes menores.
         *
         * ### 3. Como funciona
         * Tenta primeiro a resolução estrita de três partes via [parseThreePart]. Se não houver correspondência,
         * avalia o padrão com minor opcional, preenchendo os componentes omitidos com zero. Retorna `null` caso
         * a entrada não atenda a nenhum dos formatos ou ocorra overflow numérico.
         *
         * @param raw String bruta recebida na requisição ou cabeçalho.
         * @return Instância de [SemVer] válida ou `null` se inválida.
         */
        fun parse(raw: String?): SemVer? {
            parseThreePart(raw)?.let { return it }
            val match = MAJOR_OPTIONAL_MINOR.matchEntire(raw?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
            return SemVer(major, minor, 0)
        }

        /**
         * Analisa estritamente uma string no formato canônico de três partes `major.minor.patch`.
         *
         * ### 1. O que faz
         * Realiza o parsing exigindo a presença explícita dos três segmentos numéricos separados por ponto.
         *
         * ### 2. Para que serve
         * Valida formatos de versão rigorosos, como os exigidos para versões de aplicativo (`Client-Version`) e specs.
         *
         * ### 3. Como funciona
         * Aplica a regex `^(\d+)\.(\d+)\.(\d+)$` e converte cada grupo capturado via `toIntOrNull()`. Se qualquer
         * conversão falhar ou o padrão não casar, retorna `null` sem lançar exceções.
         *
         * @param raw String contendo a versão com 3 segmentos.
         * @return Instância de [SemVer] ou `null` caso a estrutura seja inválida.
         */
        fun parseThreePart(raw: String?): SemVer? {
            val value = raw?.trim().orEmpty()
            val match = THREE.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch)
        }
    }
}

/**
 * Faixa contínua de versões semânticas com limite inferior obrigatório e teto opcional.
 *
 * ### 1. O que faz
 * Modela um intervalo fechado na base e potencialmente aberto no topo `[min, max]` de versões semânticas.
 *
 * ### 2. Para que serve
 * Utilizado pelas regras de `Targeting` para definir quais versões de aplicativo, SO ou schema são elegíveis
 * para renderizar uma determinada especificação (`Spec`).
 *
 * ### 3. Como funciona
 * Valida na inicialização que [min] é menor ou igual a [max] quando este estiver presente. Fornece a operação
 * [contains] para verificar a inclusão de uma versão no intervalo de compatibilidade.
 *
 * @property min Versão semântica mínima obrigatória suportada pela faixa.
 * @property max Versão semântica máxima suportada pela faixa, ou `null` para indicar faixa aberta (versões futuras).
 */
data class VersionRange(
    /**
     * Limite inferior da faixa de versões.
     *
     * ### 1. O que faz
     * Define a menor versão semântica aceita pelo intervalo.
     *
     * ### 2. Para que serve
     * Impede que aplicativos mais antigos recebam especificações que dependem de recursos inexistentes em seus binários.
     *
     * ### 3. Como funciona
     * Utilizado como limite inclusivo na verificação de elegibilidade.
     */
    val min: SemVer,

    /**
     * Limite superior da faixa de versões (opcional).
     *
     * ### 1. O que faz
     * Define o teto máximo de versão semântica aceito pela regra.
     *
     * ### 2. Para que serve
     * Permite restringir especificações a versões legadas de app ou estabelecer limites de descontinuação.
     *
     * ### 3. Como funciona
     * Se for `null`, a faixa é considerada aberta até o infinito. Se preenchido, atua como limite inclusivo.
     */
    val max: SemVer?,
) {
    init {
        require(max == null || min <= max) { "VersionRange min ($min) deve ser <= max ($max)" }
    }

    /**
     * Avalia se uma determinada versão semântica pertence ao intervalo definido.
     *
     * ### 1. O que faz
     * Testa a inclusão matemática de uma versão no intervalo `[min, max]`.
     *
     * ### 2. Para que serve
     * Permite ao motor de targeting verificar se o cliente solicitante satisfaz a restrição de versão da spec.
     *
     * ### 3. Como funciona
     * Retorna `false` se [version] for estritamente menor que [min]. Em seguida, se [max] for nulo retorna `true`
     * (faixa aberta); caso contrário, retorna se [version] é menor ou igual a [max].
     *
     * @param version Instância de [SemVer] a ser testada.
     * @return `true` se a versão estiver contida na faixa, `false` caso contrário.
     */
    fun contains(version: SemVer): Boolean {
        if (version < min) return false
        val ceiling = max ?: return true
        return version <= ceiling
    }
}
