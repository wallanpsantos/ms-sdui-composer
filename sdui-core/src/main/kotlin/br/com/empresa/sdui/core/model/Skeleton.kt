package br.com.empresa.sdui.core.model

/**
 * Definição estrutural e regras de governança para um slot individual dentro do skeleton.
 *
 * ### 1. O que faz
 * Modela as restrições arquiteturais aplicáveis a uma gaveta ou região de tela: identificador, layout semântico,
 * título opcional, capacidade máxima de seções, componentes autorizados e obrigatoriedade portante.
 *
 * ### 2. Para que serve
 * Impõe governança sobre o arranjo visual. Permite que o validador de especificações certifique na submissão
 * que nenhuma seção seja posicionada em um slot não autorizado ou exceda o número máximo de instâncias permitido.
 * Se [required] for verdadeiro, o slot é portante: sua ausência em tempo de execução impede a renderização regular.
 *
 * ### 3. Como funciona
 * Os limites [allowedTypes] e [maxInstances] são validados na governança administrativa. O layout [layout]
 * descreve a intenção semântica (lista, prateleira, pager) sem impor pixels ou dimensões visuais. Se [allowedLayouts]
 * não for especificado, utiliza as diretivas padrão da superfície definidas em `Surfaces.defaultAllowedLayouts`.
 *
 * @property id Identificador exclusivo do slot (ex.: "header", "accounts", "shortcuts").
 * @property layout Arranjo semântico padrão aplicado aos componentes contidos no slot ([SlotLayout]).
 * @property title Título opcional exibido no topo do slot pelo cliente móvel (ex.: "Para Você").
 * @property maxInstances Quantidade máxima permitida de seções simultâneas neste slot.
 * @property allowedTypes Lista de tipos canônicos de componentes autorizados a ocupar este slot.
 * @property required Indica se o slot é portante obrigatório para a composição da tela.
 * @property allowedLayouts Lista de arranjos semânticos permitidos para este slot na superfície.
 */
data class SlotDefinition(
    /**
     * Identificador textual do slot.
     *
     * ### 1. O que faz
     * Armazena o nome exclusivo do slot na estrutura da tela.
     *
     * ### 2. Para que serve
     * Chave de amarração entre seções (`Section.slot`) e a estrutura do skeleton.
     *
     * ### 3. Como funciona
     * String semântica (ex.: "header", "shortcuts", "cards").
     */
    val id: String,

    /**
     * Arranjo semântico de apresentação do slot.
     *
     * ### 1. O que faz
     * Especifica a intenção de organização dos elementos no slot ([SlotLayout.FIXED], [SlotLayout.SHELF], etc.).
     *
     * ### 2. Para que serve
     * Orienta a escolha do container visual no cliente móvel sem interferir em estilização visual.
     *
     * ### 3. Como funciona
     * Enum do tipo [SlotLayout].
     */
    val layout: SlotLayout,

    /**
     * Título opcional da seção ou grupo.
     *
     * ### 1. O que faz
     * Transporta o texto do cabeçalho de grupo do slot.
     *
     * ### 2. Para que serve
     * Exibição visual de títulos de agrupamento no aplicativo cliente.
     *
     * ### 3. Como funciona
     * String textual opcional fornecida no skeleton.
     */
    val title: String? = null,

    /**
     * Limite máximo de instâncias de seções neste slot.
     *
     * ### 1. O que faz
     * Restringe a quantidade de seções que podem ocupar o slot simultaneamente.
     *
     * ### 2. Para que serve
     * Previne poluição visual e degradação de performance por excesso de cards.
     *
     * ### 3. Como funciona
     * Inteiro positivo avaliado na validação da especificação.
     */
    val maxInstances: Int,

    /**
     * Lista de tipos de componentes autorizados no slot.
     *
     * ### 1. O que faz
     * Enumera os tipos de componentes que têm permissão para ocupar este slot.
     *
     * ### 2. Para que serve
     * Garante coerência arquitetural impedindo, por exemplo, que cards de crédito ocupem a barra superior.
     *
     * ### 3. Como funciona
     * Coleção de strings contendo nomes de componentes do catálogo.
     */
    val allowedTypes: List<String>,

    /**
     * Indicador de slot portante obrigatório (ADR-009).
     *
     * ### 1. O que faz
     * Sinaliza se a presença de ao menos uma seção válida neste slot é estritamente mandatória.
     *
     * ### 2. Para que serve
     * Protege a integridade da experiência do usuário: slots vitais como cabeçalho ou contas não podem faltar.
     *
     * ### 3. Como funciona
     * Se for `true` e o slot ficar vazio após a filtragem e hidratação, a tela aciona a escada de fallback.
     */
    val required: Boolean,

    /**
     * Variações de layout autorizadas para este slot.
     *
     * ### 1. O que faz
     * Enumera os layouts alternativos que uma especificação pode escolher para este slot.
     *
     * ### 2. Para que serve
     * Permite flexibilidade estrutural controlada pela governança de cada superfície.
     *
     * ### 3. Como funciona
     * Inicializado com o retorno de `Surfaces.defaultAllowedLayouts(id)` ou uma lista contendo apenas [layout].
     */
    val allowedLayouts: List<SlotLayout> = Surfaces.defaultAllowedLayouts(id) ?: listOf(layout),
)

/**
 * Gabarito estrutural de apresentação de uma superfície Server-Driven UI (Skeleton).
 *
 * ### 1. O que faz
 * Define a organização hierárquica e a sequência vertical dos slots que compõem uma tela, estabelecendo a ordem
 * definitiva de exibição das seções.
 *
 * ### 2. Para que serve
 * Garante que as seções entregues ao cliente móvel mantenham estabilidade visual rigorosa, independentemente
 * do paralelismo ou ordem de conclusão das rotinas assíncronas de hidratação de dados. O versionamento independente
 * do skeleton permite que múltiplas revisões de conteúdo compartilhem a mesma estrutura física de tela.
 *
 * ### 3. Como funciona
 * Possui identificador [skeletonId], número de [revision], superfície de destino e lista ordenada de [slots].
 * Destaque de performance pós-review: pré-calcula [slotOrder] (mapa de ID para índice ordinal) e [requiredSlotIds]
 * (conjunto de IDs de slots portantes obrigatórios) na criação do objeto, viabilizando ordenação estável via TimSort
 * e validação de slots portantes em tempo constante $O(1)$ sem recalcular coleções a cada requisição.
 *
 * @property skeletonId Identificador do skeleton (ex.: "home.default").
 * @property revision Número sequencial da revisão da estrutura de slots.
 * @property surface Nome da superfície associada (ex.: "home").
 * @property layout Arranjo geral de rolagem da tela (ex.: "vertical_scroll").
 * @property slots Lista ordenada de definições de slots que compõem a superfície.
 * @property status Estado de disponibilidade no ciclo de vida ([SpecStatus]).
 */
data class Skeleton(
    /**
     * Identificador único do skeleton.
     *
     * ### 1. O que faz
     * Armazena a chave de referência da estrutura de slots.
     *
     * ### 2. Para que serve
     * Vinculação com especificações de tela e chaveamento de cache.
     *
     * ### 3. Como funciona
     * String textual identificadora.
     */
    val skeletonId: String,

    /**
     * Número sequencial da revisão do skeleton.
     *
     * ### 1. O que faz
     * Registra o incremento ordinal da estrutura de slots.
     *
     * ### 2. Para que serve
     * Controle de versionamento e migração de layouts.
     *
     * ### 3. Como funciona
     * Inteiro positivo monotonicamente crescente.
     */
    val revision: Int,

    /**
     * Superfície proprietária deste skeleton.
     *
     * ### 1. O que faz
     * Indica para qual tela este skeleton foi projetado.
     *
     * ### 2. Para que serve
     * Validação de coerência entre surface e estrutura de layout.
     *
     * ### 3. Como funciona
     * String correspondente a uma superfície válida em `Surfaces`.
     */
    val surface: String,

    /**
     * Arranjo macro de rolagem do skeleton.
     *
     * ### 1. O que faz
     * Especifica a dinâmica de rolagem raiz da tela (ex.: "vertical_scroll").
     *
     * ### 2. Para que serve
     * Orientação da montagem do contêiner raiz no cliente nativo.
     *
     * ### 3. Como funciona
     * String representativa de layout de tela.
     */
    val layout: String,

    /**
     * Lista ordenada de slots que formam o layout.
     *
     * ### 1. O que faz
     * Coleção sequencial de todas as definições de slots da superfície.
     *
     * ### 2. Para que serve
     * Determina a sequência física de posicionamento visual das seções.
     *
     * ### 3. Como funciona
     * Lista imutável de instâncias de [SlotDefinition].
     */
    val slots: List<SlotDefinition>,

    /**
     * Estado operacional do skeleton.
     *
     * ### 1. O que faz
     * Registra se o skeleton está ativo, em rascunho ou rejeitado.
     *
     * ### 2. Para que serve
     * Impede o uso de layouts descontinuados ou não homologados.
     *
     * ### 3. Como funciona
     * Enum do tipo [SpecStatus].
     */
    val status: SpecStatus,
) {
    /**
     * Mapa estático de ordenação dos slots, pré-calculado no construtor.
     *
     * ### 1. O que faz
     * Mapeia o identificador de cada slot para sua posição numérica ordinal de 0 a N-1.
     *
     * ### 2. Para que serve
     * Otimização pós-review: permite ao passo `Filter` ordenar as seções no hot path em tempo ótimo $O(N \log N)$
     * estável através de TimSort, eliminando buscas lineares repetidas com `indexOf`.
     *
     * ### 3. Como funciona
     * Inicializado imutavelmente através de `slots.mapIndexed { index, slot -> slot.id to index }.toMap()`.
     */
    val slotOrder: Map<String, Int> = slots.mapIndexed { index, slot -> slot.id to index }.toMap()

    /**
     * Conjunto de identificadores de slots portantes obrigatórios, pré-calculado no construtor.
     *
     * ### 1. O que faz
     * Agrupa os IDs de todos os slots que possuem `required = true`.
     *
     * ### 2. Para que serve
     * Otimização pós-review: viabiliza a verificação imediata de slots portantes vazios em tempo constante $O(1)$
     * durante a fase de validação de fallback (`Guard`), prevenindo a criação de filtros e listas intermediárias a cada chamada.
     *
     * ### 3. Como funciona
     * Inicializado imutavelmente através de `slots.filter { it.required }.map { it.id }.toSet()`.
     */
    val requiredSlotIds: Set<String> = slots.filter { it.required }.map { it.id }.toSet()

    /**
     * Recupera a definição de um slot a partir de seu identificador.
     *
     * ### 1. O que faz
     * Localiza o [SlotDefinition] correspondente ao ID informado.
     *
     * ### 2. Para que serve
     * Permite consultar regras de layout, capacidade e tipos permitidos de um slot específico.
     *
     * ### 3. Como funciona
     * Executa busca linear na lista [slots] retornando o primeiro casamento ou `null` caso inexistente.
     *
     * @param id Identificador do slot desejado.
     * @return [SlotDefinition] correspondente ou `null` se não encontrado.
     */
    fun slot(id: String): SlotDefinition? = slots.firstOrNull { it.id == id }
}
