package br.com.empresa.sdui.orchestrator.port.inbound

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.ContextViolation
import br.com.empresa.sdui.core.model.FallbackReason
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest

/**
 * Porta de entrada primária (*inbound port*) para a composição de telas Server-Driven UI.
 *
 * ### 1. O que faz
 * Declara o contrato abstrato para orquestrar a montagem de uma árvore de UI dinâmica a partir
 * dos headers HTTP recebidos, contexto do cliente e definições da surface solicitada.
 *
 * ### 2. Para que serve
 * Isola a borda HTTP (controladores REST e filtros Web) da lógica de orquestração interna
 * do Server-Driven UI, seguindo estritamente os princípios da Arquitetura Hexagonal (*Ports and Adapters*).
 * A camada de apresentação depende apenas desta interface funcional, desconhecendo implementações
 * concretas, caches ou rotinas de concorrência.
 *
 * ### 3. Como funciona
 * Recebe um [ComposeRequest] validado e despacha a execução através das etapas do pipeline
 * de composição: negociação de contexto, seleção de spec via ponteiro, filtragem por capabilities,
 * hidratação de seções, verificação de guardas e montagem do envelope, devolvendo um [ComposeResult].
 */
fun interface ComposeScreenUseCase {
    /**
     * Orquestra a composição de uma surface conforme os parâmetros informados.
     *
     * ### 1. O que faz
     * Processa a requisição do cliente e retorna o desfecho tipado da composição.
     *
     * ### 2. Para que serve
     * Atua como ponto de entrada único para geração de telas tanto no fluxo normal quanto nos
     * cenários degradados de cache e fallback.
     *
     * ### 3. Como funciona
     * Avalia o rate limit, consulta singleflight para recomposições atômicas, delega a seleção
     * de spec e skeleton, hidrata os slots do layout e encapsula o resultado em [ComposeResult].
     */
    fun compose(request: ComposeRequest): ComposeResult
}

/**
 * Parâmetros de entrada para o caso de uso de composição de tela.
 *
 * ### 1. O que faz
 * Agrupa os headers de negociação HTTP do cliente, o cabeçalho de validação condicional `If-None-Match`
 * e a definição autorizada da surface solicitada.
 *
 * ### 2. Para que serve
 * Fornece todos os subsídios necessários para que o pipeline identifique o dispositivo requisitante,
 * resolva compatibilidades de capacidades de renderização e evite recomposições desnecessárias via ETag.
 *
 * ### 3. Como funciona
 * A propriedade [surface] chega previamente resolvida a partir de uma allowlist imutável (ex.: `home` ou `catalog`),
 * impedindo que surfaces desconhecidas gerem chaves de cache arbitrárias ou tags de métrica descontroladas.
 * A coorte do limitador e do canary deriva estritamente dos [headers] validados.
 */
data class ComposeRequest(
    /**
     * Headers de negociação HTTP sanitizados e validados pelo adaptador web.
     *
     * ### 1. O que faz
     * Transporta plataforma móvel, versão semântica do app, build, capabilities declaradas e schema de UI.
     *
     * ### 2. Para que serve
     * Alimenta a matriz de compatibilidade e orienta a seleção determinística da revisão do spec.
     *
     * ### 3. Como funciona
     * Encapsula instâncias de [NegotiateHeaders] obtidas na camada HTTP.
     */
    val headers: NegotiateHeaders,

    /**
     * Valor opcional do header `If-None-Match` enviado pelo cliente móvel para validação condicional de cache.
     *
     * ### 1. O que faz
     * Transporta o hash ETag da última árvore de UI recebida e armazenada pelo cliente.
     *
     * ### 2. Para que serve
     * Permite responder com `HTTP 304 Not Modified` sem reenviar o payload completo caso o conteúdo permaneça inalterado.
     *
     * ### 3. Como funciona
     * Se o ETag calculado na composição coincidir com esta string, o pipeline retorna [ComposeResult.NotModified].
     */
    val ifNoneMatch: String? = null,

    /**
     * Definição da surface requerida pelo cliente móvel.
     *
     * ### 1. O que faz
     * Identifica a tela que deve ser composta (ex.: Home).
     *
     * ### 2. Para que serve
     * Garante que apenas telas formalmente cadastradas na allowlist do servidor sejam processadas.
     *
     * ### 3. Como funciona
     * Referência imutável a uma [SurfaceDefinition] válida.
     */
    val surface: SurfaceDefinition,
)

/**
 * Modelo de resultado algébrico (*sum type*) que representa todos os desfechos possíveis de uma composição.
 *
 * ### 1. O que faz
 * Modela exaustivamente os estados resultantes do pipeline: sucesso fresco ou em cache, validação condicional
 * ETag, violações de cabeçalho, limitação de taxa (*rate limit*) ou indisponibilidade temporária.
 *
 * ### 2. Para que serve
 * Permite que a camada controladora HTTP mapeie cada resultado diretamente para seu código de status e
 * headers correspondentes sem depender de exceções e com verificação de completude pelo compilador Kotlin (`when` exaustivo).
 *
 * ### 3. Como funciona
 * Estruturada como `sealed interface`, garantindo que novos desfechos adicionados ao core exijam tratamento
 * obrigatório em todas as camadas consumidoras.
 */
sealed interface ComposeResult {
    /**
     * Desfecho de composição bem-sucedida, seja gerada a fresco ou resgatada de cache.
     *
     * ### 1. O que faz
     * Transporta a árvore de UI final montada e indica a origem dos dados (cache ou computação fresca).
     *
     * ### 2. Para que serve
     * Mapeada pela borda HTTP para o status `HTTP 200 OK`, acompanhada dos cabeçalhos ETag e Cache-Control.
     *
     * ### 3. Como funciona
     * Contém a instância de [ComposedScreen] pronta para serialização e uma flag booleana de auditoria.
     */
    data class Success(
        /**
         * Árvore de UI Server-Driven composta e validada.
         *
         * ### 1. O que faz
         * Contém a lista de seções ordenadas, metadados do envelope e indicadores de degradação.
         *
         * ### 2. Para que serve
         * Fornece o documento completo a ser serializado em JSON para o cliente móvel.
         *
         * ### 3. Como funciona
         * Estrutura pura [ComposedScreen] contendo o estado da tela montada.
         */
        val screen: ComposedScreen,

        /**
         * Indica se a tela foi recuperada diretamente do cache em memória/distribuído.
         *
         * ### 1. O que faz
         * Sinaliza a procedência da árvore para fins de telemetria e headers de diagnóstico.
         *
         * ### 2. Para que serve
         * Permite registrar métricas precisas de cache hit vs miss no hot path.
         *
         * ### 3. Como funciona
         * `true` se servida a partir de `HydratedScreenCache`; `false` se computada no fluxo fresco.
         */
        val fromCache: Boolean,
    ) : ComposeResult

    /**
     * Desfecho condicional de integridade via ETag inalterado.
     *
     * ### 1. O que faz
     * Indica que a árvore composta corresponde exatamente à versão que o cliente móvel já possui em cache local.
     *
     * ### 2. Para que serve
     * Mapeada diretamente para a resposta `HTTP 304 Not Modified`, economizando largura de banda móvel e serialização.
     *
     * ### 3. Como funciona
     * Transporta o [etag] validado para confirmação no header de resposta do protocolo HTTP.
     */
    data class NotModified(
        /**
         * Identificador único do conteúdo da tela computada.
         *
         * ### 1. O que faz
         * Guarda o checksum hash representativo da composição.
         *
         * ### 2. Para que serve
         * Confirma ao cliente a validade do seu cache local.
         *
         * ### 3. Como funciona
         * String formatada contendo o hash ETag do envelope.
         */
        val etag: String,
    ) : ComposeResult

    /**
     * Desfecho de recusa por cabeçalhos obrigatórios ausentes, malformados ou incompatíveis.
     *
     * ### 1. O que faz
     * Reúne as violações de contrato detectadas na fase inicial de negociação de protocolo HTTP.
     *
     * ### 2. Para que serve
     * Mapeada pela borda HTTP para o status `HTTP 400 Bad Request` com o detalhamento das violações.
     *
     * ### 3. Como funciona
     * Contém a lista [violations] reportando especificamente quais cabeçalhos violaram o contrato.
     */
    data class InvalidHeaders(
        /**
         * Coleção de violações de formato ou regras contratuais nos headers HTTP.
         *
         * ### 1. O que faz
         * Lista cada inconsistência observada (ex.: SemVer inválido, schema não suportado).
         *
         * ### 2. Para que serve
         * Orienta os desenvolvedores clientes na correção das requisições móveis.
         *
         * ### 3. Como funciona
         * Lista imutável de instâncias de [ContextViolation].
         */
        val violations: List<ContextViolation>,
    ) : ComposeResult

    /**
     * Desfecho de rejeição por ultrapassagem da cota de requisições (*rate limit*).
     *
     * ### 1. O que faz
     * Comunica o esgotamento do saldo de tokens na coorte de clientes do requisitante.
     *
     * ### 2. Para que serve
     * Mapeada na borda HTTP para o status `HTTP 429 Too Many Requests`, acompanhada do header `Retry-After`.
     *
     * ### 3. Como funciona
     * Informa o tempo de espera recomendado em segundos ([retryAfterSeconds]), calculado com jitter estocástico.
     */
    data class RateLimited(
        /**
         * Tempo sugerido em segundos antes de o cliente tentar uma nova requisição.
         *
         * ### 1. O que faz
         * Define a duração do recuo temporário para a coorte limitada.
         *
         * ### 2. Para que serve
         * Popula o cabeçalho HTTP padrão `Retry-After` evitando tempestades de retries sincronizados.
         *
         * ### 3. Como funciona
         * Valor numérico em segundos derivado do limitador de taxa com dispersão estatística de jitter.
         */
        val retryAfterSeconds: Long,
    ) : ComposeResult

    /**
     * Desfecho de indisponibilidade transitória com indicação de motivo e tempo de recuo.
     *
     * ### 1. O que faz
     * Representa a falha irrecuperável do pipeline mesmo após a travessia de toda a escada de fallback.
     *
     * ### 2. Para que serve
     * Mapeada pela borda HTTP para o status `HTTP 503 Service Unavailable`, informando o motivo e `Retry-After`.
     *
     * ### 3. Como funciona
     * Disparada quando o bulkhead é rejeitado, a dependência sofre timeout ou o last good expirou
     * além da idade máxima tolerável (`maxFallbackAge`), acompanhada da respectiva [reason].
     */
    data class Unavailable(
        /**
         * Tempo sugerido em segundos para o recuo do cliente antes da próxima tentativa.
         *
         * ### 1. O que faz
         * Estipula o intervalo do cabeçalho `Retry-After` para alívio da pressão sobre o serviço.
         *
         * ### 2. Para que serve
         * Evita o efeito manada (*thundering herd*) contra o servidor em momento de sobrecarga.
         *
         * ### 3. Como funciona
         * Valor numérico inteiro derivado da política de resiliência e jitter configurados.
         */
        val retryAfterSeconds: Long,

        /**
         * Causa raiz categorizada que motivou a indisponibilidade da composição.
         *
         * ### 1. O que faz
         * Especifica formalmente a razão da recusa (ex.: ausência de spec, store indisponível, timeout).
         *
         * ### 2. Para que serve
         * Facilita a observabilidade e auditoria operacional sem expor dados internos no envelope.
         *
         * ### 3. Como funciona
         * Constante do enum [FallbackReason] indicando o motivo da quebra.
         */
        val reason: FallbackReason,
    ) : ComposeResult
}

/**
 * Comando para criação ou atualização de um rascunho de especificação de UI (`Spec`).
 *
 * ### 1. O que faz
 * Encapsula os dados do autor da alteração e o conteúdo estrutural do rascunho de tela a ser salvo.
 *
 * ### 2. Para que serve
 * Alimenta a porta de governança de rascunhos (`DraftUseCase`), permitindo persistir revisões de trabalho
 * antes da submissão para publicação oficial.
 *
 * ### 3. Como funciona
 * Valida o [actor] e associa o objeto [spec] para armazenamento seguro sem impacto imediato no ambiente produtivo.
 */
data class DraftSpecCommand(
    /**
     * Identidade e privilégios do operador responsável pelo comando.
     *
     * ### 1. O que faz
     * Identifica o autor da ação de governança.
     *
     * ### 2. Para que serve
     * Permite validação de perfis de acesso e registro detalhado na trilha de auditoria.
     *
     * ### 3. Como funciona
     * Objeto [Actor] com identificador, e-mail e papéis administrativos atribuídos.
     */
    val actor: Actor,

    /**
     * Conteúdo da especificação de tela em estágio de rascunho.
     *
     * ### 1. O que faz
     * Agrupa slots, seções, tipos e propriedades de layout da tela proposta.
     *
     * ### 2. Para que serve
     * Fornece o documento de definição de UI a ser auditado e persistido.
     *
     * ### 3. Como funciona
     * Instância do modelo de domínio [Spec].
     */
    val spec: Spec,
)

/**
 * Comando para criação ou atualização de um rascunho de esqueleto estrutural (`Skeleton`).
 *
 * ### 1. O que faz
 * Transporta os dados do autor e a estrutura base de slots ordenados de uma surface.
 *
 * ### 2. Para que serve
 * Permite evoluir os esqueletos de sustentação das telas de forma desacoplada dos conteúdos de negócio das seções.
 *
 * ### 3. Como funciona
 * Encaminha o [skeleton] associado ao [actor] para persistência via `DraftUseCase`.
 */
data class DraftSkeletonCommand(
    /**
     * Operador solicitante da gravação do esqueleto.
     *
     * ### 1. O que faz
     * Identifica quem está propondo ou editando o esqueleto estrutural.
     *
     * ### 2. Para que serve
     * Garante rastreabilidade das fundações de layout no log de auditoria.
     *
     * ### 3. Como funciona
     * Instância de [Actor] com perfil de autoria (*maker*).
     */
    val actor: Actor,

    /**
     * Definição do esqueleto com a lista ordenada de slots e seus layouts correspondentes.
     *
     * ### 1. O que faz
     * Define o contrato de posições da tela (ex.: `header`, `shortcuts`, `accounts`).
     *
     * ### 2. Para que serve
     * Serve como gabarito imutável de validação de completude das especificações.
     *
     * ### 3. Como funciona
     * Instância imutável de [Skeleton].
     */
    val skeleton: Skeleton,
)

/**
 * Comando para inserção ou atualização de um tipo de componente no catálogo de UI do servidor.
 *
 * ### 1. O que faz
 * Adiciona uma nova definição de componente ou atualiza uma versão existente no catálogo oficial.
 *
 * ### 2. Para que serve
 * Mantém o catálogo de componentes do servidor alinhado às capacidades implementadas nos apps móveis.
 *
 * ### 3. Como funciona
 * Encapsula o [component] a ser cadastrado sob a chancela do [actor] autenticado.
 */
data class DraftCatalogCommand(
    /**
     * Ator responsável pela atualização do catálogo de componentes.
     *
     * ### 1. O que faz
     * Identifica o engenheiro de design system ou desenvolvedor que registra o componente.
     *
     * ### 2. Para que serve
     * Assegura conformidade de autoria para auditoria e controle de mudanças.
     *
     * ### 3. Como funciona
     * Instância de [Actor] validada na camada de segurança administrativa.
     */
    val actor: Actor,

    /**
     * Metadados do tipo de componente, incluindo nome e versão de contrato.
     *
     * ### 1. O que faz
     * Descreve o componente registrado (ex.: `account_card` na versão 1).
     *
     * ### 2. Para que serve
     * Alimenta a lista de componentes aprovados para uso em especificações de tela.
     *
     * ### 3. Como funciona
     * Instância de [ComponentType] contendo o identificador do componente.
     */
    val component: ComponentType,
)

/**
 * Comando emitido pelo autor (*maker*) para abrir um pedido formal de publicação de uma revisão de spec.
 *
 * ### 1. O que faz
 * Inicia o fluxo de homologação para disponibilizar uma revisão específica de tela em um determinado canal.
 *
 * ### 2. Para que serve
 * Constitui o primeiro passo do ciclo obrigatório de dupla aprovação (*maker-checker*), registrando a intenção
 * de publicação para julgamento subsequente por um revisor independente.
 *
 * ### 3. Como funciona
 * A presença de [idempotencyKey] é estritamente obrigatória para impedir a criação de pedidos duplicados
 * em caso de retries de rede. O caso de uso valida o autor, calcula o diff e reserva o pedido em estado `OPEN`.
 */
data class OpenPublishCommand(
    /**
     * Operador proponente da publicação (*maker*).
     *
     * ### 1. O que faz
     * Registra quem abriu a solicitação de promoção da revisão.
     *
     * ### 2. Para que serve
     * Impede que este mesmo ator venha a aprovar o próprio pedido posteriormente no canal produtivo.
     *
     * ### 3. Como funciona
     * Instância de [Actor] associada ao registro de publicação.
     */
    val actor: Actor,

    /**
     * Identificador do spec a ser publicado.
     *
     * ### 1. O que faz
     * Aponta para o documento de tela a ser promovido.
     *
     * ### 2. Para que serve
     * Localiza as revisões pertinentes nos repositórios de especificação.
     *
     * ### 3. Como funciona
     * String contendo o identificador único do spec (ex.: `home.default.ios`).
     */
    val specId: String,

    /**
     * Número da revisão específica do spec indicada para publicação.
     *
     * ### 1. O que faz
     * Define o número sequencial da versão a ser promovida.
     *
     * ### 2. Para que serve
     * Garante que uma versão imutável do documento seja a base da publicação.
     *
     * ### 3. Como funciona
     * Inteiro positivo correspondente à revisão persistida.
     */
    val revision: Int,

    /**
     * Canal de distribuição alvo para onde a revisão será enviada (`canary` ou `stable`).
     *
     * ### 1. O que faz
     * Indica o ambiente de destino da tela.
     *
     * ### 2. Para que serve
     * Permite validação faseada de mudanças antes da disponibilização geral para a base de usuários.
     *
     * ### 3. Como funciona
     * Constante do enum [Channel].
     */
    val channel: Channel,

    /**
     * Chave de idempotência enviada pelo cliente HTTP administrativo.
     *
     * ### 1. O que faz
     * Identificador único que previne operações repetidas de abertura de publicação.
     *
     * ### 2. Para que serve
     * Garante que uma falha de conexão na resposta não gere dois pedidos idênticos aguardando decisão.
     *
     * ### 3. Como funciona
     * Chave textual tratada sob o protocolo atômico de reserva de idempotência.
     */
    val idempotencyKey: String,
)

/**
 * Comando emitido pelo revisor (*checker*) para decidir sobre a aprovação ou rejeição de um pedido de publicação.
 *
 * ### 1. O que faz
 * Concretiza a decisão de governança sobre uma solicitação de publicação previamente aberta.
 *
 * ### 2. Para que serve
 * Fecha o ciclo *maker-checker*. Quando aprovado, movimenta o ponteiro da surface para apontar para a nova
 * revisão de tela; quando rejeitado, arquiva o pedido impedindo a transição em produção.
 *
 * ### 3. Como funciona
 * Exige [idempotencyKey] para assegurar que apenas uma decisão seja efetivada mesmo em condições de alta
 * latência ou múltiplos cliques no painel administrativo.
 */
data class DecidePublishCommand(
    /**
     * Operador responsável pelo julgamento da publicação (*checker*).
     *
     * ### 1. O que faz
     * Registra o revisor que deliberou sobre o pedido.
     *
     * ### 2. Para que serve
     * Audita a aprovação e garante a conformidade com as regras de segregação de funções.
     *
     * ### 3. Como funciona
     * Instância de [Actor] com perfil de aprovação e distinta do autor da solicitação.
     */
    val actor: Actor,

    /**
     * Identificador do pedido de publicação sob julgamento.
     *
     * ### 1. O que faz
     * Localiza o pedido formal em aberto no armazenamento.
     *
     * ### 2. Para que serve
     * Assegura que a decisão incida sobre o registro exato avaliado na interface.
     *
     * ### 3. Como funciona
     * String contendo o identificador do pedido gerado na abertura.
     */
    val requestId: String,

    /**
     * Chave de idempotência da decisão administrativa.
     *
     * ### 1. O que faz
     * Blindagem contra reprocessamento da aprovação ou rejeição.
     *
     * ### 2. Para que serve
     * Evita condições de corrida onde múltiplos checkers tentem aprovar o mesmo item simultaneamente.
     *
     * ### 3. Como funciona
     * Token único validado atômica e previamente via `IdempotencyStore.reserve`.
     */
    val idempotencyKey: String,
)

/**
 * Comando administrativo para realizar a reversão imediata (*rollback*) do ponteiro de uma surface.
 *
 * ### 1. O que faz
 * Restaura o ponteiro de produção para uma revisão anterior previamente publicada ou para a última versão estável.
 *
 * ### 2. Para que serve
 * Oferece mecanismo de mitigação operacional urgente caso uma especificação recém-publicada apresente
 * defeitos graves em produção, contornando a necessidade de novos ciclos de compilação ou deploy de código.
 *
 * ### 3. Como funciona
 * Move o [Pointer] da combinação surface/plataforma/canal para [targetSpecRevisionId] (ou versão anterior se nula),
 * invalida os caches de composição e registra o evento append-only na trilha de auditoria sob [reason] justificada.
 */
data class RollbackCommand(
    /**
     * Operador que autorizou e executou a ação emergencial de reversão.
     *
     * ### 1. O que faz
     * Identifica o responsável pelo acionamento do rollback.
     *
     * ### 2. Para que serve
     * Registra a autoria da intervenção crítica nos relatórios de auditoria e incidentes.
     *
     * ### 3. Como funciona
     * Instância de [Actor] com permissão administrativa adequada.
     */
    val actor: Actor,

    /**
     * Identificador da surface que sofrerá a reversão.
     *
     * ### 1. O que faz
     * Determina qual tela terá seu ponteiro alterado (ex.: `home`).
     *
     * ### 2. Para que serve
     * Delimita o escopo de atuação do rollback.
     *
     * ### 3. Como funciona
     * String contendo a chave da surface cadastrada.
     */
    val surface: String,

    /**
     * Plataforma do cliente móvel afetada pela reversão (`ios` ou `android`).
     *
     * ### 1. O que faz
     * Especifica o ecossistema móvel sobre o qual o rollback será aplicado.
     *
     * ### 2. Para que serve
     * Garante o isolamento estrito entre plataformas: reverter o iOS nunca afeta a operação do Android.
     *
     * ### 3. Como funciona
     * Constante do enum [ClientPlatform].
     */
    val platform: ClientPlatform,

    /**
     * Canal onde a reversão será aplicada (`canary` ou `stable`).
     *
     * ### 1. O que faz
     * Indica o ambiente de publicação sob intervenção.
     *
     * ### 2. Para que serve
     * Permite reverter canais de teste sem necessariamente impactar o tráfego geral estável.
     *
     * ### 3. Como funciona
     * Constante do enum [Channel].
     */
    val channel: Channel,

    /**
     * Identificador opcional da revisão de destino desejada para o rollback.
     *
     * ### 1. O que faz
     * Especifica a revisão exata que assumirá o ponteiro de produção.
     *
     * ### 2. Para que serve
     * Permite retroceder para uma versão específica conhecida e homologada.
     *
     * ### 3. Como funciona
     * Se informado, valida se a revisão já foi publicada; se nulo, retrocede automaticamente à revisão imediatamente anterior.
     */
    val targetSpecRevisionId: String?,

    /**
     * Chave de idempotência da requisição de rollback.
     *
     * ### 1. O que faz
     * Assegura que um mesmo comando de emergência não seja duplicado na rede.
     *
     * ### 2. Para que serve
     * Previne transições desordenadas de ponteiros decorrentes de retransmissões automáticas de clientes HTTP.
     *
     * ### 3. Como funciona
     * Chave validada no repositório de idempotência com reserva atômica prévia.
     */
    val idempotencyKey: String,

    /**
     * Justificativa textual detalhada do motivo do acionamento do rollback.
     *
     * ### 1. O que faz
     * Documenta as causas operacionais da intervenção emergencial.
     *
     * ### 2. Para que serve
     * Fornece subsídios para relatórios pós-incidente e auditoria de conformidade regulatória.
     *
     * ### 3. Como funciona
     * String persistida no log imutável de eventos de auditoria.
     */
    val reason: String,
)

/**
 * Porta de entrada para consultas de governança: catálogo, esqueletos, revisões e diffs estruturais.
 *
 * ### 1. O que faz
 * Expõe operações de somente-leitura para visualização e inspeção de artefatos de UI.
 *
 * ### 2. Para que serve
 * Alimenta a interface de administração e ferramentas de apoio, permitindo que operadores comparem
 * versões de specs e inspecionem componentes sem risco de mutação acidental.
 *
 * ### 3. Como funciona
 * As listagens históricas de revisões e specs recebem um [PageRequest] obrigatório, prevenindo
 * degradação de latência e estouro de memória causados pelo carregamento irrestrito de grandes volumes de documentos.
 */
interface CatalogQueryUseCase {
    /**
     * Recupera o catálogo completo de componentes atualmente homologados no servidor.
     *
     * ### 1. O que faz
     * Retorna a coleção consolidada de tipos e versões de componentes aceitos.
     *
     * ### 2. Para que serve
     * Permite que editores de tela conheçam os blocos construtivos disponíveis para composição.
     *
     * ### 3. Como funciona
     * Consulta o repositório central de catálogo e retorna uma instância de [Catalog].
     */
    fun catalog(): Catalog

    /**
     * Localiza um esqueleto estrutural pelo seu identificador.
     *
     * ### 1. O que faz
     * Busca a definição estrutural de slots correspondente ao identificador fornecido.
     *
     * ### 2. Para que serve
     * Permite validar o layout base de uma surface durante a edição de especificações.
     *
     * ### 3. Como funciona
     * Retorna o [Skeleton] mais recente ou nulo se não localizado.
     */
    fun skeleton(id: String): Skeleton?

    /**
     * Lista especificações cadastradas com filtragem opcional por plataforma e canal, com suporte a paginação.
     *
     * ### 1. O que faz
     * Retorna uma página de specs ordenada por identificador e revisão.
     *
     * ### 2. Para que serve
     * Viabiliza a navegação eficiente e segura por grandes históricos de telas no painel administrativo.
     *
     * ### 3. Como funciona
     * Aplica os filtros fornecidos e restringe o resultado aos limites definidos em [page].
     */
    fun specs(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec>

    /**
     * Lista todas as revisões de uma especificação específica com paginação.
     *
     * ### 1. O que faz
     * Retorna as diferentes revisões históricas de uma mesma tela em ordem cronológica de versão.
     *
     * ### 2. Para que serve
     * Permite aos operadores acompanhar a evolução do layout e selecionar versões para publicação ou reversão.
     *
     * ### 3. Como funciona
     * Consulta o repositório de specs e extrai uma fatia ordenada conforme o [page] informado.
     */
    fun revisions(specId: String, page: PageRequest): List<Spec>

    /**
     * Calcula e apresenta as diferenças estruturais e de propriedades entre duas revisões de um spec.
     *
     * ### 1. O que faz
     * Compara o spec na revisão [from] com a revisão [to] e retorna um resumo das mudanças.
     *
     * ### 2. Para que serve
     * Auxilia o revisor (*checker*) a inspecionar detalhadamente o que foi adicionado, alterado ou removido antes de aprovar uma publicação.
     *
     * ### 3. Como funciona
     * Retorna um [SpecDiff] demonstrando seções inseridas, modificadas ou deletadas, ou nulo se alguma revisão for inexistente.
     */
    fun diff(specId: String, from: Int, to: Int): SpecDiff?
}

/**
 * Porta de entrada para consulta da trilha de auditoria administrativa.
 *
 * ### 1. O que faz
 * Fornece acesso aos registros históricos imutáveis de todas as operações administrativas realizadas no sistema.
 *
 * ### 2. Para que serve
 * Atende às exigências de conformidade regulatória, rastreabilidade de incidentes e auditoria de segurança da informação.
 *
 * ### 3. Como funciona
 * Verifica se o [Actor] autenticado possui privilégio de auditor ou checker antes de liberar a listagem,
 * rejeitando solicitações não autorizadas com `AdminDenied`.
 */
fun interface AuditQueryUseCase {
    /**
     * Retorna os eventos de auditoria mais recentes em ordem cronológica decrescente.
     *
     * ### 1. O que faz
     * Extrai os últimos [limit] eventos registrados na trilha de auditoria.
     *
     * ### 2. Para que serve
     * Alimenta a visão de histórico e monitoramento de atividades do painel administrativo.
     *
     * ### 3. Como funciona
     * Consulta a porta outbound de auditoria e retorna a lista ordenada de [AuditEvent].
     */
    fun recent(actor: Actor, limit: Int): List<AuditEvent>
}

/**
 * Porta de entrada para criação e edição de rascunhos de governança de UI.
 *
 * ### 1. O que faz
 * Recebe comandos de autoria de especificações, esqueletos e tipos de componentes.
 *
 * ### 2. Para que serve
 * Permite que autores (*makers*) formulem e aprimorem novos layouts sem impactar o tráfego dos clientes móveis em produção.
 *
 * ### 3. Como funciona
 * Submete os artefatos recebidos aos validadores estritos do core (verificação anti-CSS, anti-PII e consistência
 * de slots) antes de persistir as alterações em estado de rascunho.
 */
interface DraftUseCase {
    /**
     * Salva um novo rascunho de especificação de UI.
     *
     * ### 1. O que faz
     * Valida e grava uma nova revisão de spec com status de rascunho.
     *
     * ### 2. Para que serve
     * Cria a base de conteúdo de tela que posteriormente poderá ser submetida para publicação.
     *
     * ### 3. Como funciona
     * Executa validações no payload de [command] e armazena o [Spec] resultante.
     */
    fun createSpecDraft(command: DraftSpecCommand): Spec

    /**
     * Salva um novo rascunho de esqueleto estrutural.
     *
     * ### 1. O que faz
     * Registra um esqueleto contendo a distribuição de slots de uma tela.
     *
     * ### 2. Para que serve
     * Define o arcabouço estrutural reutilizável sobre o qual os specs serão compostos.
     *
     * ### 3. Como funciona
     * Valida os layouts dos slots e grava o [Skeleton] persistido.
     */
    fun createSkeletonDraft(command: DraftSkeletonCommand): Skeleton

    /**
     * Cadastra ou atualiza a definição de um tipo de componente no catálogo.
     *
     * ### 1. O que faz
     * Adiciona um componente homologado à biblioteca do servidor.
     *
     * ### 2. Para que serve
     * Amplia as possibilidades de blocos visuais disponíveis para as equipes de produto.
     *
     * ### 3. Como funciona
     * Valida a integridade do tipo de componente fornecido em [command] e retorna o [Catalog] atualizado.
     */
    fun upsertComponent(command: DraftCatalogCommand): Catalog
}

/**
 * Porta de entrada para o fluxo de governança e publicação *maker-checker*.
 *
 * ### 1. O que faz
 * Controla as transições de estado do ciclo de vida de publicações de telas nos canais de distribuição.
 *
 * ### 2. Para que serve
 * Impede que alterações em produção ocorram sem revisão qualificada e dupla autorização, salvaguardando a
 * experiência dos usuários finais dos aplicativos.
 *
 * ### 3. Como funciona
 * Segrega formalmente as operações de abertura de pedido ([open]) das decisões de aprovação ([approve])
 * e rejeição ([reject]), aplicando travas atômicas de concorrência e idempotência.
 */
interface PublishUseCase {
    /**
     * Abre uma solicitação formal de publicação de uma revisão de spec em um canal.
     *
     * ### 1. O que faz
     * Valida a existência do spec e registra um novo pedido de publicação com status `OPEN`.
     *
     * ### 2. Para que serve
     * Inicia a fila de homologação e disponibiliza o diff de alterações para inspeção pelos revisores.
     *
     * ### 3. Como funciona
     * Reserva a chave de idempotência de [command], calcula o fingerprint e grava a [PublishRequest].
     */
    fun open(command: OpenPublishCommand): PublishRequest

    /**
     * Aprova um pedido de publicação em aberto e atualiza o ponteiro de produção.
     *
     * ### 1. O que faz
     * Efetiva a publicação da revisão de spec no canal correspondente.
     *
     * ### 2. Para que serve
     * Conclui a validação humana, disponibilizando o novo layout para consumo imediato pelos clientes móveis.
     *
     * ### 3. Como funciona
     * Valida que o aprovador não é o mesmo ator que abriu o pedido, atualiza atomicamente o status para `APPROVED`,
     * movimenta o ponteiro de versão e agenda a invalidação preventiva dos caches de tela.
     */
    fun approve(command: DecidePublishCommand): PublishRequest

    /**
     * Rejeita um pedido de publicação com registro formal de justificativa.
     *
     * ### 1. O que faz
     * Encerra o pedido de publicação alterando seu status para `REJECTED`.
     *
     * ### 2. Para que serve
     * Evita que propostas inadequadas ou defeituosas sejam lançadas para os usuários.
     *
     * ### 3. Como funciona
     * Registra o motivo da recusa na solicitação e na trilha de auditoria, impedindo qualquer mutação no ponteiro.
     */
    fun reject(command: DecidePublishCommand, reason: String): PublishRequest
}

/**
 * Porta de entrada para realização de reversão (*rollback*) de ponteiros de publicação.
 *
 * ### 1. O que faz
 * Expõe a operação de retorno a uma versão de tela anterior estável.
 *
 * ### 2. Para que serve
 * Garante capacidade de resposta imediata a incidentes de produção decorrentes de publicações defeituosas.
 *
 * ### 3. Como funciona
 * Move o ponteiro de forma atômica para a versão histórica segura indicada em [command], invalidando caches
 * e gerando eventos detalhados na auditoria.
 */
interface RollbackPointerUseCase {
    /**
     * Executa a reversão do ponteiro de uma surface conforme os parâmetros do comando.
     *
     * ### 1. O que faz
     * Altera a revisão ativa no canal especificado de volta para uma versão estável homologada.
     *
     * ### 2. Para que serve
     * Restabelece a normalidade operacional de telas em caso de anomalias críticas.
     *
     * ### 3. Como funciona
     * Executa uma unidade de trabalho transacional que atualiza o [Pointer], limpa o `LastGoodScreenStore`
     * e o `HydratedScreenCache` associados e retorna o ponteiro reposicionado.
     */
    fun rollback(command: RollbackCommand): Pointer
}
