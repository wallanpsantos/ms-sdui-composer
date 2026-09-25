package br.com.empresa.sdui.core.model

import br.com.empresa.sdui.core.model.ActorRole.Companion.BY_NAME
import br.com.empresa.sdui.core.model.Channel.Companion.BY_WIRE
import br.com.empresa.sdui.core.model.SlotLayout.Companion.BY_WIRE


/**
 * Plataforma operacional móvel do aplicativo cliente.
 *
 * ### 1. O que faz
 * Discrimina os sistemas operacionais suportados pelo ecossistema SDUI ([IOS] e [ANDROID]).
 *
 * ### 2. Para que serve
 * Garante o isolamento estrito de ponta a ponta entre iOS e Android: ponteiros (`Pointer`), caches de árvore,
 * seletores de especificação e matrizes de capacidades são completamente independentes para cada plataforma,
 * garantindo que uma publicação ou alteração em uma plataforma jamais afete a outra.
 *
 * ### 3. Como funciona
 * Representado como enum estático. Oferece serialização canônica em minúsculas via [wire] e método de fábrica
 * defensivo [parse] que valida entradas de cabeçalho contra a allowlist permitida.
 */
enum class ClientPlatform {
    /**
     * Sistema operacional Apple iOS.
     *
     * ### 1. O que faz
     * Identifica requisições e especificações destinadas a dispositivos Apple iOS.
     *
     * ### 2. Para que serve
     * Habilita a seleção e montagem de árvores de UI compatíveis com os renderizadores nativos de iOS (Swift/SwiftUI).
     *
     * ### 3. Como funciona
     * Serializado como "ios" no wire format.
     */
    IOS,

    /**
     * Sistema operacional Google Android.
     *
     * ### 1. O que faz
     * Identifica requisições e especificações destinadas a dispositivos Android.
     *
     * ### 2. Para que serve
     * Habilita a seleção e montagem de árvores de UI compatíveis com os renderizadores nativos de Android (Kotlin/Jetpack Compose).
     *
     * ### 3. Como funciona
     * Serializado como "android" no wire format.
     */
    ANDROID,
    ;

    /**
     * Retorna o identificador textual em minúsculas para tráfego de rede e chaves.
     *
     * ### 1. O que faz
     * Converte o nome do enum para caracteres minúsculos.
     *
     * ### 2. Para que serve
     * Utilizado na composição de chaves de cache do Redis, rotas e payloads JSON.
     *
     * ### 3. Como funciona
     * Invoca `name.lowercase()`.
     *
     * @return String representativa da plataforma ("ios" ou "android").
     */
    fun wire(): String = name.lowercase()

    /**
     * Utilitários estáticos de parsing para [ClientPlatform].
     *
     * ### 1. O que faz
     * Fornece o conversor seguro a partir de strings brutas.
     *
     * ### 2. Para que serve
     * Sanitiza cabeçalhos HTTP `Client-Platform` durante a fase de negociação.
     *
     * ### 3. Como funciona
     * Avalia strings case-insensitive retornando a plataforma correspondente ou `null`.
     */
    companion object {
        /**
         * Converte uma string arbitrária na plataforma móvel correspondente.
         *
         * ### 1. O que faz
         * Realiza o parsing seguro da plataforma a partir de texto.
         *
         * ### 2. Para que serve
         * Valida o cabeçalho `Client-Platform` rejeitando plataformas não autorizadas.
         *
         * ### 3. Como funciona
         * Trata espaços e converte para minúsculas, comparando com "ios" e "android". Retorna `null` para qualquer outro valor.
         *
         * @param raw String informada na requisição HTTP.
         * @return Instância de [ClientPlatform] ou `null` se inválida.
         */
        fun parse(raw: String?): ClientPlatform? =
            when (raw?.trim()?.lowercase()) {
                "ios" -> IOS
                "android" -> ANDROID
                else -> null
            }
    }
}

/**
 * Canal de distribuição e publicação de conteúdo de UI.
 *
 * ### 1. O que faz
 * Define o ambiente ou coorte de entrega de especificações de tela ([STABLE], [CANARY], [INTERNAL]).
 *
 * ### 2. Para que serve
 * Permite expor e validar novas revisões de especificações para grupos controlados de usuários ou builds de teste
 * (como equipes internas ou usuários canário) sem alterar o ponteiro da base estável de produção.
 *
 * ### 3. Como funciona
 * Otimizado pós-review com mapa estático pré-computado [BY_WIRE] para lookups em tempo constante $O(1)$.
 * Possui duas estratégias de parsing: [parse] (tolerante, com fallback para [STABLE] para requisições de clientes) e
 * [parseOrNull] (estrito, sem fallback, para operações de governança administrativa onde ambiguidades são proibidas).
 */
enum class Channel {
    /**
     * Canal padrão de produção estável.
     *
     * ### 1. O que faz
     * Atende à vasta maioria dos usuários finais do aplicativo em produção.
     *
     * ### 2. Para que serve
     * Garante estabilidade e confiabilidade na entrega de layouts homologados.
     *
     * ### 3. Como funciona
     * Canal padrão assumido caso nenhum canal seja informado ou o canal solicitado seja inválido.
     */
    STABLE,

    /**
     * Canal de liberação gradual e testes em produção (canary).
     *
     * ### 1. O que faz
     * Direciona tráfego para revisões em fase de homologação controlada.
     *
     * ### 2. Para que serve
     * Permite validar novos layouts ou componentes com uma fatia reduzida de usuários antes do rollout geral.
     *
     * ### 3. Como funciona
     * Selecionado via cabeçalho `Channel: canary` por compilações canárias do aplicativo.
     */
    CANARY,

    /**
     * Canal de testes internos e desenvolvimento.
     *
     * ### 1. O que faz
     * Canal restrito a colaboradores e ambientes de homologação interna.
     *
     * ### 2. Para que serve
     * Permite testes antecipados de especificações em elaboração sem risco de vazamento para usuários externos.
     *
     * ### 3. Como funciona
     * Habilitado para revisões internas e testes de novos contratos.
     */
    INTERNAL,
    ;

    /**
     * Retorna a representação textual em minúsculas para tráfego e chaves.
     *
     * ### 1. O que faz
     * Serializa o nome do canal em formato padronizado minúsculo.
     *
     * ### 2. Para que serve
     * Utilizado na composição de chaves de cache e identificadores de ponteiro.
     *
     * ### 3. Como funciona
     * Invoca `name.lowercase()`.
     *
     * @return String em minúsculas correspondente ao canal.
     */
    fun wire(): String = name.lowercase()

    /**
     * Utilitários estáticos de parsing e busca otimizada para [Channel].
     *
     * ### 1. O que faz
     * Oferece métodos de resolução de canal com estratégias diferenciadas por contexto.
     *
     * ### 2. Para que serve
     * Isola a lógica de fallback do hot path e a rigidez da governança.
     *
     * ### 3. Como funciona
     * Utiliza o mapa estático pré-computado [BY_WIRE] indexado na carga da classe.
     */
    companion object {
        /**
         * Mapa estático pré-calculado para busca $O(1)$ por representação wire.
         *
         * ### 1. O que faz
         * Indexa todas as instâncias de [Channel] pela sua string [wire].
         *
         * ### 2. Para que serve
         * Elimina alocações repetidas de strings e varreduras lineares a cada requisição.
         *
         * ### 3. Como funciona
         * Inicializado estaticamente através de `entries.associateBy { it.wire() }`.
         */
        private val BY_WIRE: Map<String, Channel> = entries.associateBy { it.wire() }

        /**
         * Converte uma string em [Channel] com fallback seguro para [STABLE].
         *
         * ### 1. O que faz
         * Realiza o parsing tolerante de canal para uso no hot path de requisições de clientes móveis.
         *
         * ### 2. Para que serve
         * Garante que um valor desconhecido ou malformado enviado pelo cliente não resulte em erro de requisição,
         * direcionando o usuário com segurança para o conteúdo de produção estável.
         *
         * ### 3. Como funciona
         * Consulta [parseOrNull]; se o retorno for `null`, devolve [STABLE].
         *
         * @param raw String informada no cabeçalho `Channel`.
         * @return Instância de [Channel] resolvida.
         */
        fun parse(raw: String?): Channel = parseOrNull(raw) ?: STABLE

        /**
         * Converte estritamente uma string em [Channel], sem fallback.
         *
         * ### 1. O que faz
         * Realiza o parsing rigoroso de canal para operações administrativas e de governança.
         *
         * ### 2. Para que serve
         * Impede que erros de digitação em comandos de publicação ou rollback atuem inadvertidamente sobre o canal [STABLE].
         *
         * ### 3. Como funciona
         * Sanitiza a string, converte para minúsculas e consulta diretamente o mapa [BY_WIRE] em tempo constante $O(1)$.
         *
         * @param raw String contendo o identificador do canal.
         * @return [Channel] correspondente ou `null` se inválido ou ausente.
         */
        fun parseOrNull(raw: String?): Channel? = raw?.trim()?.lowercase()?.let { BY_WIRE[it] }
    }
}

/**
 * Estados do ciclo de vida de especificações (`Spec`) e skeletons (`Skeleton`).
 *
 * ### 1. O que faz
 * Modela os estágios formais pelos quais uma especificação ou estrutura de slots pode transitar.
 *
 * ### 2. Para que serve
 * Garante a imutabilidade e a integridade do catálogo de versões. Uma especificação [PUBLISHED] torna-se
 * estritamente imutável, exigindo a criação de uma nova revisão para quaisquer alterações subsequentes.
 *
 * ### 3. Como funciona
 * Enumeração contendo [DRAFT], [PUBLISHED] e [REJECTED].
 */
enum class SpecStatus {
    /**
     * Rascunho em elaboração.
     *
     * ### 1. O que faz
     * Identifica uma especificação em fase de edição e testes preliminares.
     *
     * ### 2. Para que serve
     * Permite alterações antes da submissão para aprovação.
     *
     * ### 3. Como funciona
     * Estado inicial de uma nova especificação submetida pelo autor.
     */
    DRAFT,

    /**
     * Especificação publicada, ativa e imutável.
     *
     * ### 1. O que faz
     * Marca uma especificação como aprovada e disponível para compor telas.
     *
     * ### 2. Para que serve
     * Assegura referência estável para chaves de cache, ETags e telemetria.
     *
     * ### 3. Como funciona
     * Estado terminal que proíbe qualquer mutação de conteúdo na revisão.
     */
    PUBLISHED,

    /**
     * Especificação rejeitada pela governança.
     *
     * ### 1. O que faz
     * Registra que a revisão proposta foi reprovada pelo revisor.
     *
     * ### 2. Para que serve
     * Impede que rascunhos inadequados ou defeituosos entrem em circulação.
     *
     * ### 3. Como funciona
     * Estado terminal atribuído pelo checker durante o fluxo de aprovação.
     */
    REJECTED,
}

/**
 * Estados do ciclo de vida de um pedido de publicação no fluxo maker-checker.
 *
 * ### 1. O que faz
 * Modela a máquina de estados de uma solicitação de publicação de especificação (`PublishRequest`).
 *
 * ### 2. Para que serve
 * Controla a atomicidade e a integridade concorrente das decisões de governança, garantindo que um pedido
 * seja decidido uma única vez via compare-and-set atômico no repositório.
 *
 * ### 3. Como funciona
 * O pedido inicia em [OPEN] e transiciona de forma irreversível para [APPROVED] ou [REJECTED].
 */
enum class PublishRequestStatus {
    /**
     * Pedido em aberto aguardando avaliação.
     *
     * ### 1. O que faz
     * Indica que uma especificação foi submetida pelo maker e aguarda julgamento.
     *
     * ### 2. Para que serve
     * Fila de trabalho visível para revisores habilitados.
     *
     * ### 3. Como funciona
     * Estado inicial mutável somente por ação de aprovação ou rejeição.
     */
    OPEN,

    /**
     * Pedido aprovado pelo checker.
     *
     * ### 1. O que faz
     * Confirma a autorização formal de publicação da revisão de spec.
     *
     * ### 2. Para que serve
     * Desencadeia a atualização atômica do ponteiro de produção e invalidação de caches.
     *
     * ### 3. Como funciona
     * Estado terminal após validação de integridade de conteúdo e segregação de funções.
     */
    APPROVED,

    /**
     * Pedido rejeitado pelo checker.
     *
     * ### 1. O que faz
     * Documenta a recusa formal da proposta de publicação.
     *
     * ### 2. Para que serve
     * Notifica o proponente sobre inconformidades apontadas pelo revisor.
     *
     * ### 3. Como funciona
     * Estado terminal acompanhado de justificativa textual.
     */
    REJECTED,
}

/**
 * Papéis funcionais de governança operacional e controle de acesso.
 *
 * ### 1. O que faz
 * Define as responsabilidades operacionais atribuíveis a agentes humanos ou automações no plano administrativo.
 *
 * ### 2. Para que serve
 * Garante a aplicação do princípio da segregação de funções (maker-checker): o operador que cria uma proposta
 * ([MAKER]) não possui autorização para aprová-la ([CHECKER]) em canais produtivos ([Channel.STABLE] ou [Channel.CANARY]).
 *
 * ### 3. Como funciona
 * Otimizado com mapa estático [BY_NAME] para lookup em tempo constante $O(1)$ no método [parse].
 */
enum class ActorRole {
    /**
     * Proponente de alterações (maker).
     *
     * ### 1. O que faz
     * Cria, edita e submete propostas de especificações de tela para homologação.
     *
     * ### 2. Para que serve
     * Delimita a autoria de alterações no catálogo de telas.
     *
     * ### 3. Como funciona
     * Papel autorizado a abrir solicitações de publicação, mas proibido de aprová-las fora do canal interno.
     */
    MAKER,

    /**
     * Revisor e homologador de alterações (checker).
     *
     * ### 1. O que faz
     * Avalia o impacto, integridade de slots portantes e segurança de propostas abertas.
     *
     * ### 2. Para que serve
     * Assegura o segundo par de olhos obrigatório antes que alterações entrem em produção.
     *
     * ### 3. Como funciona
     * Papel com autoridade para aprovar ou rejeitar pedidos de publicação submetidos por makers distintos.
     */
    CHECKER,

    /**
     * Auditor de governança com permissão exclusiva de leitura.
     *
     * ### 1. O que faz
     * Realiza consultas analíticas, inspeção de histórico e conferência de logs.
     *
     * ### 2. Para que serve
     * Fornece visibilidade para times de conformidade, segurança e auditoria sem conceder privilégios de escrita.
     *
     * ### 3. Como funciona
     * Papel estritamente read-only em todas as rotas administrativas.
     */
    AUDITOR,
    ;

    /**
     * Utilitários estáticos de parsing otimizado para [ActorRole].
     *
     * ### 1. O que faz
     * Converte representações textuais em papéis de governança.
     *
     * ### 2. Para que serve
     * Valida o cabeçalho `Actor-Role` nas rotas do plano administrativo.
     *
     * ### 3. Como funciona
     * Utiliza o mapa pré-computado [BY_NAME] para busca instantânea $O(1)$.
     */
    companion object {
        /**
         * Mapa estático de lookups indexado pelo nome do enum em maiúsculas.
         *
         * ### 1. O que faz
         * Armazena instâncias de [ActorRole] mapeadas por seu respectivo nome textual.
         *
         * ### 2. Para que serve
         * Evita varreduras lineares ou repetição de alocações de memória a cada requisição administrativa.
         *
         * ### 3. Como funciona
         * Inicializado estaticamente através de `entries.associateBy { it.name }`.
         */
        private val BY_NAME: Map<String, ActorRole> = entries.associateBy { it.name }

        /**
         * Converte uma string no respectivo papel funcional [ActorRole].
         *
         * ### 1. O que faz
         * Realiza o parsing do papel administrativo a partir de texto.
         *
         * ### 2. Para que serve
         * Valida credenciais no cabeçalho `Actor-Role`.
         *
         * ### 3. Como funciona
         * Trata espaços em branco, converte para maiúsculas e recupera o valor do mapa [BY_NAME] em tempo constante $O(1)$.
         *
         * @param raw String recebida no cabeçalho `Actor-Role`.
         * @return [ActorRole] correspondente ou `null` caso inválido.
         */
        fun parse(raw: String?): ActorRole? =
            raw?.trim()?.uppercase()?.let { BY_NAME[it] }
    }
}

/**
 * Motivos formais de degradação da resposta na escada de fallback (ADR-007).
 *
 * ### 1. O que faz
 * Enumera as causas padronizadas que levaram o pipeline Server-Driven UI a entregar uma resposta degradada
 * ou acionar contingência.
 *
 * ### 2. Para que serve
 * Permite instrumentação precisa de métricas, alarmes operacionais e auditoria detalhada no cabeçalho e corpo da resposta,
 * informando aos clientes e à observabilidade exatamente qual nível da escada de fallback foi alcançado.
 *
 * ### 3. Como funciona
 * Vocabulário fechado serializável em formato canônico ([wire]). O conjunto [CLOSED] trava os termos aceitos
 * para testes estritos de contrato entre cliente e servidor.
 *
 * @property wire Representação textual padronizada para tráfego JSON e métricas.
 */
enum class FallbackReason(
    /**
     * Representação canônica em linha do motivo de fallback.
     *
     * ### 1. O que faz
     * Armazena a string literal do motivo de degradação.
     *
     * ### 2. Para que serve
     * Serialização no envelope JSON e tags de métricas do Micrometer.
     *
     * ### 3. Como funciona
     * String constante associada a cada enum entry.
     */
    val wire: String,
) {
    /**
     * Nenhum fallback acionado: composição regular bem-sucedida.
     *
     * ### 1. O que faz
     * Indica operação normal em que a tela foi composta a partir da especificação ativa e hidratada normalmente.
     *
     * ### 2. Para que serve
     * Marca respostas ideais sem qualquer degradação de conteúdo.
     *
     * ### 3. Como funciona
     * Serializado como "none".
     */
    NONE("none"),

    /**
     * Indisponibilidade do cluster ou serviço de cache Redis.
     *
     * ### 1. O que faz
     * Registra que o Redis não pôde ser alcançado para leitura ou escrita.
     *
     * ### 2. Para que serve
     * Notifica degradação para execução em memória direta sem cache distribuído.
     *
     * ### 3. Como funciona
     * Serializado como "redis_unavailable".
     */
    REDIS_UNAVAILABLE("redis_unavailable"),

    /**
     * Estouro de tempo limite em dependência externa durante o pipeline.
     *
     * ### 1. O que faz
     * Indica que a composição excedeu o prazo limite no singleflight ou chamada remota.
     *
     * ### 2. Para que serve
     * Alimenta métricas de latência e orienta a entrega da árvore last good em cache.
     *
     * ### 3. Como funciona
     * Serializado como "dependency_timeout".
     */
    DEPENDENCY_TIMEOUT("dependency_timeout"),

    /**
     * Nenhuma especificação publicada é compatível com os critérios de targeting do cliente.
     *
     * ### 1. O que faz
     * Registra que o cliente móvel não atende aos requisitos de versão ou capabilities de nenhuma spec ativa.
     *
     * ### 2. Para que serve
     * Identifica desajustes de targeting ou versões defasadas de app em campo.
     *
     * ### 3. Como funciona
     * Serializado como "no_compatible_spec".
     */
    NO_COMPATIBLE_SPEC("no_compatible_spec"),

    /**
     * Um slot portante obrigatório ficou completamente vazio após a filtragem ou hidratação.
     *
     * ### 1. O que faz
     * Registra a violação estrutural de um slot indispensável (como `header` ou `accounts` na Home).
     *
     * ### 2. Para que serve
     * Aplica o ADR-009 impedindo a exibição de telas bancárias quebradas ou desprovidas de dados essenciais.
     *
     * ### 3. Como funciona
     * Serializado como "required_slot_empty".
     */
    REQUIRED_SLOT_EMPTY("required_slot_empty"),

    /**
     * Resposta recuperada a partir do cache last good de contingência.
     *
     * ### 1. O que faz
     * Indica que uma versão prévia estável da árvore foi entregue após falha na composição em tempo real.
     *
     * ### 2. Para que serve
     * Assegura alta disponibilidade da experiência do usuário mesmo diante de panes de dependências a jusante.
     *
     * ### 3. Como funciona
     * Serializado como "last_good".
     */
    LAST_GOOD("last_good"),
    ;

    /**
     * Utilitários estáticos e vocabulário fechado para [FallbackReason].
     *
     * ### 1. O que faz
     * Reúne o conjunto estrito de todas as representações textuais de motivos de fallback.
     *
     * ### 2. Para que serve
     * Garante a validação de contrato e integridade com as equipes de engenharia móvel.
     *
     * ### 3. Como funciona
     * Define o conjunto imutável [CLOSED].
     */
    companion object {
        /**
         * Conjunto de todas as strings wire válidas de motivos de fallback.
         *
         * ### 1. O que faz
         * Reúne os valores canônicos em linha de todas as entradas do enum.
         *
         * ### 2. Para que serve
         * Utilizado em testes de contrato ArchUnit e de serialização para garantir que nenhum termo novo surja sem revisão.
         *
         * ### 3. Como funciona
         * Gerado na carga estática mapeando as propriedades [wire] de `entries`.
         */
        val CLOSED: Set<String> = entries.map { it.wire }.toSet()
    }
}

/**
 * Motivos formais para a omissão de uma seção individual durante a composição da tela.
 *
 * ### 1. O que faz
 * Enumera as justificativas formais pelas quais uma seção específica (`Section`) foi descartada da resposta final.
 *
 * ### 2. Para que serve
 * Permite ao aplicativo cliente e aos painéis de telemetria auditar por que determinado bloco visual deixou de ser
 * renderizado na tela (ex.: incompatibilidade de versão nativa ou timeout na hidratação de dados).
 *
 * ### 3. Como funciona
 * Vocabulário fechado serializável em wire format ([wire]), com conjunto estrito [CLOSED] para proteção de contrato.
 *
 * @property wire Representação textual padronizada para tráfego JSON e observabilidade.
 */
enum class OmittedReason(
    /**
     * Representação canônica em linha do motivo de omissão.
     *
     * ### 1. O que faz
     * Armazena a string literal do motivo de descarte da seção.
     *
     * ### 2. Para que serve
     * Serialização no array `omitted` do envelope JSON de resposta.
     *
     * ### 3. Como funciona
     * String constante associada a cada enum entry.
     */
    val wire: String,
) {
    /**
     * Componente não suportado pelas capacidades de renderização declaradas pelo aplicativo.
     *
     * ### 1. O que faz
     * Registra que o cliente não possui capacidade nativa para renderizar o par `type@typeVersion` da seção.
     *
     * ### 2. Para que serve
     * Habilita a evolução independente do backend sem quebrar versões antigas do app instaladas em clientes.
     *
     * ### 3. Como funciona
     * Identificado durante o passo `Filter` comparando a capability da seção com o conjunto do cliente.
     */
    UNSUPPORTED_TYPE("unsupported_type"),

    /**
     * Falha de execução na hidratação de dados dinâmicos da seção.
     *
     * ### 1. O que faz
     * Registra que o adapter de projeção de dados retornou erro ou falhou ao processar os dados da seção.
     *
     * ### 2. Para que serve
     * Omissão graciosa de blocos secundários sem derrubar a tela inteira.
     *
     * ### 3. Como funciona
     * Capturado na etapa de `Hydrate` em caso de exceções não recuperáveis no serviço de dados.
     */
    HYDRATION_FAILED("hydration_failed"),

    /**
     * Esgotamento do prazo limite de hidratação de dados da seção.
     *
     * ### 1. O que faz
     * Registra que o serviço de projeção excedeu o tempo máximo estipulado no orçamento de tempo.
     *
     * ### 2. Para que serve
     * Libera o semáforo de fan-out e impede que serviços lentos degradem o tempo de resposta da superfície.
     *
     * ### 3. Como funciona
     * Acionado por timeout assíncrono no coordenador de hidratação.
     */
    HYDRATION_TIMEOUT("hydration_timeout"),
    ;

    /**
     * Utilitários estáticos e vocabulário fechado para [OmittedReason].
     *
     * ### 1. O que faz
     * Reúne o conjunto estrito de todas as representações textuais de motivos de omissão.
     *
     * ### 2. Para que serve
     * Validação de conformidade contratual em testes automatizados.
     *
     * ### 3. Como funciona
     * Define o conjunto imutável [CLOSED].
     */
    companion object {
        /**
         * Conjunto de todas as strings wire válidas de motivos de omissão.
         *
         * ### 1. O que faz
         * Reúne os valores canônicos em linha de todas as entradas do enum.
         *
         * ### 2. Para que serve
         * Utilizado em testes de contrato para travar o vocabulário e impedir regressões.
         *
         * ### 3. Como funciona
         * Gerado na carga estática mapeando as propriedades [wire] de `entries`.
         */
        val CLOSED: Set<String> = entries.map { it.wire }.toSet()
    }
}

/**
 * Arranjo estrutural e semântico dos componentes dentro de um slot.
 *
 * ### 1. O que faz
 * Expressa a intenção arquitetural de organização dos componentes visuais em um slot ([FIXED], [SHELF], [LIST], [PAGER], [GRID]).
 *
 * ### 2. Para que serve
 * Orienta o cliente nativo sobre o modelo de container a utilizar (ex.: prateleira horizontal vs. lista vertical),
 * preservando a pureza visual do SDUI ao delegar margens, espaçamentos e medidas em pixels aos componentes móveis.
 *
 * ### 3. Como funciona
 * Otimizado pós-review com mapa estático pré-computado [BY_WIRE] para lookups em tempo constante $O(1)$ no método [parse].
 */
enum class SlotLayout {
    /**
     * Posição fixa individual ou estática.
     *
     * ### 1. O que faz
     * Define que o componente ocupa uma posição única e ancorada no slot.
     *
     * ### 2. Para que serve
     * Comum em cabeçalhos (`header`) e barras superiores de controle.
     *
     * ### 3. Como funciona
     * Renderizado como nó estático sem paginação ou rolagem própria.
     */
    FIXED,

    /**
     * Prateleira horizontal rolável (carrossel).
     *
     * ### 1. O que faz
     * Organiza as seções em uma esteira horizontal deslizante.
     *
     * ### 2. Para que serve
     * Típico de atalhos rápidos (`shortcuts`) e ofertas secundárias em carrossel.
     *
     * ### 3. Como funciona
     * Mapeia para RecyclerView horizontal no Android ou LazyHStack no SwiftUI.
     */
    SHELF,

    /**
     * Lista linear vertical sequencial.
     *
     * ### 1. O que faz
     * Empilha as seções uma abaixo da outra verticalmente.
     *
     * ### 2. Para que serve
     * Padrão para listagem de contas (`accounts`), produtos e transações.
     *
     * ### 3. Como funciona
     * Inserido no fluxo principal de rolagem vertical da tela.
     */
    LIST,

    /**
     * Paginador de tela cheia ou carrossel com encaixe pontual (snap).
     *
     * ### 1. O que faz
     * Apresenta seções como páginas com transição assistida por gestos.
     *
     * ### 2. Para que serve
     * Utilizado para banners de destaque, ofertas exclusivas e módulos "Para Você" (`foryou`).
     *
     * ### 3. Como funciona
     * Mapeia para ViewPager2 no Android ou TabView paginado no SwiftUI.
     */
    PAGER,

    /**
     * Grade bidimensional de elementos.
     *
     * ### 1. O que faz
     * Organiza as seções em linhas e colunas adaptativas.
     *
     * ### 2. Para que serve
     * Utilizado em grades de atalhos expandidos ou coleções de produtos no catálogo.
     *
     * ### 3. Como funciona
     * Renderizado via LazyVerticalGrid no Compose ou LazyVGrid no SwiftUI.
     */
    GRID,
    ;

    /**
     * Retorna o identificador textual em minúsculas para tráfego e serialização.
     *
     * ### 1. O que faz
     * Serializa o layout semântico em minúsculas.
     *
     * ### 2. Para que serve
     * Utilizado no payload de slots do skeleton do envelope SDUI.
     *
     * ### 3. Como funciona
     * Invoca `name.lowercase()`.
     *
     * @return String em minúsculas correspondente ao layout.
     */
    fun wire(): String = name.lowercase()

    /**
     * Utilitários estáticos de parsing otimizado para [SlotLayout].
     *
     * ### 1. O que faz
     * Fornece conversão de texto para enum em tempo constante $O(1)$.
     *
     * ### 2. Para que serve
     * Leitura de layouts declarados em arquivos de especificação e skeletons.
     *
     * ### 3. Como funciona
     * Utiliza o mapa estático pré-computado [BY_WIRE].
     */
    companion object {
        /**
         * Mapa estático de lookups indexado pela representação wire do layout.
         *
         * ### 1. O que faz
         * Mapeia strings em minúsculas diretamente para instâncias de [SlotLayout].
         *
         * ### 2. Para que serve
         * Elimina alocações e iterações repetidas no hot path.
         *
         * ### 3. Como funciona
         * Inicializado estaticamente através de `entries.associateBy { it.wire() }`.
         */
        private val BY_WIRE: Map<String, SlotLayout> = entries.associateBy { it.wire() }

        /**
         * Converte uma string no respectivo arranjo semântico [SlotLayout].
         *
         * ### 1. O que faz
         * Realiza o parsing seguro do layout de slot a partir de texto.
         *
         * ### 2. Para que serve
         * Valida e interpreta definições de layout em skeletons de superfície.
         *
         * ### 3. Como funciona
         * Trata espaços em branco, converte para minúsculas e recupera o valor do mapa [BY_WIRE] em tempo constante $O(1)$.
         *
         * @param raw String com o nome do layout.
         * @return Instância de [SlotLayout] ou `null` se inválido ou ausente.
         */
        fun parse(raw: String?): SlotLayout? =
            raw?.trim()?.lowercase()?.let { BY_WIRE[it] }
    }
}
