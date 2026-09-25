package br.com.empresa.sdui.core.model

/**
 * Especificação formal de um tipo de componente registrado no catálogo do Server-Driven UI.
 *
 * ### 1. O que faz
 * Modela os metadados de governança de um componente visual: seu identificador de tipo, versão de contrato,
 * status de ciclo de vida, versão mínima do schema SDUI em que foi introduzido e a lista de propriedades obrigatórias.
 *
 * ### 2. Para que serve
 * Permite ao módulo de governança e validação de especificações (`SpecValidator`) certificar se uma seção (`Section`)
 * possui contrato homologado e fornece todas as propriedades mandatórias necessárias para sua renderização correta
 * no aplicativo móvel.
 *
 * ### 3. Como funciona
 * Encapsula as regras estruturais do componente. Provê métodos de conveniência para conversão em [Capability] e
 * formatação em linha [wire] (`type@typeVersion`). O ciclo de vida é controlado via [status], permitindo descontinuar
 * componentes sem removê-los do histórico de auditoria.
 *
 * @property type Nome canônico identificador do componente (ex.: "account_card").
 * @property typeVersion Versão numérica do contrato de renderização do componente (ex.: 1).
 * @property status Estado de disponibilidade operacional do componente (ex.: "ACTIVE").
 * @property sinceSchema Versão do schema de envelope SDUI a partir da qual o componente é suportado.
 * @property requiredProps Lista imutável de chaves de propriedades que devem obrigatoriamente estar presentes nas props.
 */
data class ComponentType(
    /**
     * Identificador textual do tipo do componente.
     *
     * ### 1. O que faz
     * Armazena a chave semântica única do componente nativo.
     *
     * ### 2. Para que serve
     * Mapeia diretamente para a implementação visual correspondente no cliente móvel.
     *
     * ### 3. Como funciona
     * String semântica padronizada (ex.: "top_bar", "credit_offer").
     */
    val type: String,

    /**
     * Versão ordinal do contrato do componente.
     *
     * ### 1. O que faz
     * Armazena a versão da interface de dados e propriedades do componente.
     *
     * ### 2. Para que serve
     * Discrimina modificações contratuais e evolução de campos entre versões do mesmo componente.
     *
     * ### 3. Como funciona
     * Número inteiro positivo sequencial.
     */
    val typeVersion: Int,

    /**
     * Situação do componente no ciclo de vida do catálogo.
     *
     * ### 1. O que faz
     * Registra o estado operacional do componente (ex.: "ACTIVE", "DEPRECATED").
     *
     * ### 2. Para que serve
     * Permite controlar a disponibilidade de componentes para novas especificações sem apagar registros históricos.
     *
     * ### 3. Como funciona
     * String validada contra constantes operacionais como [STATUS_ACTIVE].
     */
    val status: String,

    /**
     * Versão inicial do schema de envelope que suporta o componente.
     *
     * ### 1. O que faz
     * Armazena a versão de protocolo SDUI na qual este componente passou a ser reconhecido.
     *
     * ### 2. Para que serve
     * Evita que componentes desenhados para protocolos mais novos sejam entregues em envelopes legados.
     *
     * ### 3. Como funciona
     * Comparada com a versão negociada do cliente durante a validação.
     */
    val sinceSchema: String,

    /**
     * Coleção de propriedades obrigatórias no payload de props da seção.
     *
     * ### 1. O que faz
     * Enumera as chaves que não podem faltar no mapa de dados da seção.
     *
     * ### 2. Para que serve
     * Garante a integridade de renderização no aplicativo móvel, evitando falhas por dados ausentes.
     *
     * ### 3. Como funciona
     * Inspecionado recursivamente pelo validador de especificações antes da publicação de uma nova spec.
     */
    val requiredProps: List<String>,
) {
    /**
     * Converte esta definição de componente em uma instância de [Capability].
     *
     * ### 1. O que faz
     * Gera o par [Capability] correspondente a este tipo e versão.
     *
     * ### 2. Para que serve
     * Permite interoperabilidade com o motor de filtragem de capacidades e matrizes de compatibilidade.
     *
     * ### 3. Como funciona
     * Instancia `Capability(type, typeVersion)`.
     *
     * @return Instância de [Capability] para o componente.
     */
    fun capability(): Capability = Capability(type, typeVersion)

    /**
     * Gera a representação canônica em linha no formato `type@typeVersion`.
     *
     * ### 1. O que faz
     * Concatena o tipo e a versão sem validações prévias adicionais.
     *
     * ### 2. Para que serve
     * Utilizado para compor mensagens informativas de erro ou logs sobre entradas ainda sob validação.
     *
     * ### 3. Como funciona
     * Interpola `$type@$typeVersion`.
     *
     * @return String representativa no formato "tipo@versao".
     */
    fun wire(): String = "$type@$typeVersion"

    /**
     * Constantes operacionais associadas ao ciclo de vida de componentes.
     *
     * ### 1. O que faz
     * Agrupa valores padronizados de status de componentes.
     *
     * ### 2. Para que serve
     * Padroniza as comparações de estado de componentes no catálogo.
     *
     * ### 3. Como funciona
     * Define constantes estáticas consultadas pela governança.
     */
    companion object {
        /**
         * Status indicativo de componente ativo e liberado para uso.
         *
         * ### 1. O que faz
         * Define a constante literal "ACTIVE".
         *
         * ### 2. Para que serve
         * Marca componentes habilitados para compor novas especificações de tela.
         *
         * ### 3. Como funciona
         * Comparada de forma insensível a maiúsculas/minúsculas pela validação de catálogo.
         */
        const val STATUS_ACTIVE: String = "ACTIVE"
    }
}

/**
 * Catálogo canônico e fechado de componentes do ecossistema Server-Driven UI.
 *
 * ### 1. O que faz
 * Reúne o conjunto finito e auditado de todos os tipos de componentes que o servidor tem permissão para emitir.
 *
 * ### 2. Para que serve
 * Funciona como a fronteira de autoridade de renderização do sistema. Qualquer seção com tipo ou versão
 * não registrado no catálogo é rejeitada sumariamente na fase de submissão de especificações, impedindo que
 * contratos desconhecidos quebrem os aplicativos móveis em produção.
 *
 * ### 3. Como funciona
 * Mantém uma coleção imutável de instâncias de [ComponentType] e provê busca determinística através do método [find].
 * O catálogo é validado como um bloco homogêneo acordado previamente com as equipes móveis.
 *
 * @property components Lista imutável contendo todas as definições de componentes autorizadas.
 */
data class Catalog(
    /**
     * Lista de definições de componentes registradas no catálogo.
     *
     * ### 1. O que faz
     * Armazena os tipos e versões de componentes aceitos.
     *
     * ### 2. Para que serve
     * Fonte de consulta para validações de governança e compatibilidade.
     *
     * ### 3. Como funciona
     * Coleção imutável de [ComponentType].
     */
    val components: List<ComponentType>,
) {
    /**
     * Localiza a definição formal de um componente a partir de seu tipo e versão.
     *
     * ### 1. O que faz
     * Busca na lista de componentes um registro que case exatamente com o tipo e versão informados.
     *
     * ### 2. Para que serve
     * Permite verificar a existência de um componente e obter sua lista de propriedades obrigatórias.
     *
     * ### 3. Como funciona
     * Realiza uma busca linear retornando o primeiro [ComponentType] compatível ou `null` caso inexista.
     *
     * @param type Identificador textual do componente.
     * @param typeVersion Versão numérica do contrato do componente.
     * @return Instância de [ComponentType] se encontrado, ou `null` caso contrário.
     */
    fun find(type: String, typeVersion: Int): ComponentType? =
        components.firstOrNull { it.type == type && it.typeVersion == typeVersion }
}
