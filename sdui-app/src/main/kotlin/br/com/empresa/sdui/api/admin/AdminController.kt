package br.com.empresa.sdui.api.admin

import br.com.empresa.sdui.core.model.Actor
import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.ComponentType
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import br.com.empresa.sdui.orchestrator.port.inbound.AdminDenied
import br.com.empresa.sdui.orchestrator.port.inbound.AdminNotFound
import br.com.empresa.sdui.orchestrator.port.inbound.AdminValidation
import br.com.empresa.sdui.orchestrator.port.inbound.AuditQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.CatalogQueryUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.DraftSpecCommand
import br.com.empresa.sdui.orchestrator.port.inbound.DraftUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.OpenPublishCommand
import br.com.empresa.sdui.orchestrator.port.inbound.PublishUseCase
import br.com.empresa.sdui.orchestrator.port.inbound.RollbackPointerUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.PageRequest

/**
 * Controlador REST da borda HTTP de governanca: catalogo, skeletons, specs, publicacao e rollback.
 *
 * ### 1. O que faz
 * Expoe os endpoints do plano administrativo (`/admin/v1/**`) para consulta e manutencao de componentes,
 * skeletons, specs de telas, gerenciamento do ciclo de vida de publicacao com aprovacao segregada
 * (maker-checker), rollback atomico de ponteiros e consulta a trilha de auditoria append-only.
 *
 * ### 2. Para que serve
 * Separa categoricamente o plano administrativo do plano de leitura de alta vazao das surfaces.
 * Permite que equipes de produto, design e engenharia operem o ciclo de vida dos layouts SDUI de forma
 * segura, auditavel, com garantia de idempotencia (ADR-022) e protecao contra concorrencia destrutiva.
 *
 * ### 3. Como funciona
 * - **Identidade do Operador:** Todo endpoint exige os cabecalhos `Actor-Id` e `Actor-Role`. A ausencia ou
 *   invalidez dispara [AdminDenied] (HTTP 403). Como nao ha autenticacao criptografica nativa nestes headers,
 *   a seguranca fisica exige isolamento de rede (ingress privado/VPN).
 * - **Maker-Checker Estrito:** Pedidos de publicacao abertos por um autor nao podem ser aprovados pelo
 *   proprio autor em canais com restricao de governanca (`canary` e `stable`).
 * - **Idempotencia em Operacoes com Efeito Colateral:** Endpoints de abertura de publicacao, aprovacao,
 *   rejeicao e rollback exigem o cabecalho `Idempotency-Key` (ADR-022). O orquestrador reserva a chave antes
 *   de executar a mutacao e registra a conclusao, protegendo o estado contra retries duplicados de rede.
 * - **Paginacao e Validacao Estrita:** Listagens utilizam [PageRequest] (`offset` e `limit`, padrao 100, teto 500).
 *   Segmentos de path e parâmetros desconhecidos sao rejeitados com HTTP 404 ou HTTP 400 sem fallback para `stable`,
 *   evitando que erros de digitacao alterem o canal de producao.
 * - **Telemetria Blindada:** Metricas operacionais utilizam exclusivamente valores validados e de vocabulario
 *   fechado (types aprovados, canais enum, surfaces conhecidas), prevenindo explosao de cardinalidade no Micrometer.
 *
 * @property catalogQuery Caso de uso para consultas ao catalogo, skeletons e specs.
 * @property drafts Caso de uso para gravacao de rascunhos de componentes, skeletons e specs.
 * @property publish Caso de uso para o ciclo de vida de publicacao (abertura, aprovacao, rejeicao).
 * @property rollback Caso de uso para movimentacao e reversao atomica de ponteiros de publicacao.
 * @property auditQuery Caso de uso para inspecao da trilha de auditoria append-only.
 * @property metrics Gravador de metricas operacionais Micrometer.
 */
@RestController
@RequestMapping("/admin/v1")
class AdminController(
    private val catalogQuery: CatalogQueryUseCase,
    private val drafts: DraftUseCase,
    private val publish: PublishUseCase,
    private val rollback: RollbackPointerUseCase,
    private val auditQuery: AuditQueryUseCase,
    private val metrics: MetricsRecorder,
) {
    private val logger = LoggerFactory.getLogger(AdminController::class.java)

/**
 * Consulta os componentes cadastrados no catalogo global.
 *
 * ### 1. O que faz
 * Recupera o catalogo completo de tipos de componentes ([Catalog]) suportados pelo servidor.
 *
 * ### 2. Para que serve
 * Permite a inspecao dos tipos de componentes, suas versoes ativas e definicoes de contrato aprovadas.
 *
 * ### 3. Como funciona
 * Valida o operador solicitante via cabecalhos HTTP e delega para [CatalogQueryUseCase.catalog].
 *
 * @param headers Cabecalhos HTTP contendo a identificacao do ator (`Actor-Id` e `Actor-Role`).
 * @return Instancia de [Catalog] com a lista de componentes aprovados.
*/
    @GetMapping("/catalog/components")
    fun catalog(@RequestHeader headers: HttpHeaders): Catalog {
        actor(headers)
        return catalogQuery.catalog()
    }

/**
 * Cadastra ou atualiza a definicao de um componente no catalogo.
 *
 * ### 1. O que faz
 * Realiza o upsert de um componente ([ComponentType]) identificado por tipo e versao.
 *
 * ### 2. Para que serve
 * Habilita a adicao controlada de novos componentes visuais no catalogo aprovado do servidor.
 *
 * ### 3. Como funciona
 * Extrai o ator dos headers, sobrepoe o tipo e a versao informados na URL sobre o corpo recebido e invoca
 * [DraftUseCase.upsertComponent]. Incrementa a metrica `sdui.admin.catalog.upsert` rotulada com o tipo
 * aprovado e registra em log a acao do operador.
 *
 * @param type Identificador do tipo do componente (ex: `top_bar`, `account_card`).
 * @param ver Versao semantica ordinal do tipo do componente.
 * @param component Dados contratuais do tipo de componente.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return O [Catalog] consolidado apos a gravacao.
*/
    @PutMapping("/catalog/components/{type}/{ver}")
    fun putComponent(
        @PathVariable type: String,
        @PathVariable ver: Int,
        @RequestBody component: ComponentType,
        @RequestHeader headers: HttpHeaders,
    ): Catalog {
        val currentActor = actor(headers)
        val catalog = drafts.upsertComponent(
            DraftCatalogCommand(
                currentActor,
                component.copy(type = type, typeVersion = ver),
            ),
        )
        // O validador de catalogo so aceita contratos aprovados: `type` aqui e de vocabulario fechado.
        metrics.increment(MetricNames.ADMIN_CATALOG_UPSERT, mapOf("type" to type))
        logger.info("catalog component upserted: type={}, version={}, actor={}", type, ver, currentActor.id)
        return catalog
    }

/**
 * Consulta a definicao estrutural de um skeleton pelo seu identificador.
 *
 * ### 1. O que faz
 * Busca um esqueleto de tela ([Skeleton]) previamente cadastrado a partir de seu ID.
 *
 * ### 2. Para que serve
 * Permite inspecionar a organizacao de slots e layouts estruturais de uma surface.
 *
 * ### 3. Como funciona
 * Valida o ator e invoca [CatalogQueryUseCase.skeleton]. Caso o skeleton nao exista, lanca [AdminNotFound] (HTTP 404).
 *
 * @param id Identificador unico do skeleton (ex: `home.default`).
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return O [Skeleton] correspondente ao identificador.
 * @throws AdminNotFound Se o skeleton nao for encontrado no repositorio.
*/
    @GetMapping("/skeletons/{id}")
    fun skeleton(@PathVariable id: String, @RequestHeader headers: HttpHeaders): Skeleton {
        actor(headers)
        return catalogQuery.skeleton(id) ?: throw AdminNotFound(id)
    }

/**
 * Cria ou atualiza a definicao de um skeleton de tela.
 *
 * ### 1. O que faz
 * Salva uma versao de rascunho de um [Skeleton] estrutural.
 *
 * ### 2. Para que serve
 * Define ou modifica os slots ordenados, obrigatoriedades e layouts base das telas Server-Driven.
 *
 * ### 3. Como funciona
 * Valida o ator, vincula o identificador do path ao corpo e delega para [DraftUseCase.createSkeletonDraft].
 * Incrementa a metrica de telemetria correspondente e registra evento informativo no log.
 *
 * @param id Identificador unico do skeleton.
 * @param skeleton Estrutura contendo layout geral e definicoes de slots.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return O [Skeleton] persistido.
*/
    @PutMapping("/skeletons/{id}")
    fun putSkeleton(
        @PathVariable id: String,
        @RequestBody skeleton: Skeleton,
        @RequestHeader headers: HttpHeaders,
    ): Skeleton {
        val currentActor = actor(headers)
        val saved = drafts.createSkeletonDraft(DraftSkeletonCommand(currentActor, skeleton.copy(skeletonId = id)))
        metrics.increment(MetricNames.ADMIN_SKELETON_UPSERT)
        logger.info("skeleton draft upserted: skeletonId={}, actor={}", id, currentActor.id)
        return saved
    }

/**
 * Lista specs cadastrados com suporte a filtros por plataforma, canal e paginacao.
 *
 * ### 1. O que faz
 * Retorna uma lista de especificacoes de tela ([Spec]) conforme os criterios informados na query string.
 *
 * ### 2. Para que serve
 * Habilita telas de administracao e ferramentas de CLI a listar specs existentes para auditoria e governanca.
 *
 * ### 3. Como funciona
 * Valida o ator e realiza o parsing rigoroso dos parâmetros [platform] e [channel]. Qualquer valor
 * desconhecido ou malformado lanca imediatamente [AdminValidation] (HTTP 400). Aplica paginacao
 * segura validada via [page].
 *
 * @param platform Filtro opcional por plataforma cliente (`ios` ou `android`).
 * @param channel Filtro opcional por canal de distribuicao (`draft`, `canary`, `stable`).
 * @param offset Indice inicial da listagem (padrao 0).
 * @param limit Quantidade maxima de itens retornados (padrao 100, maximo 500).
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return Lista paginada de [Spec].
*/
    @GetMapping("/specs")
    fun specs(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.specs(
            platform?.let { ClientPlatform.parse(it) ?: throw unknown("platform") },
            channel?.let { Channel.parseOrNull(it) ?: throw unknown("channel") },
            page(offset, limit),
        )
    }

/**
 * Cria um novo rascunho de especificacao de tela (Spec).
 *
 * ### 1. O que faz
 * Registra um novo rascunho de [Spec], associando-o a proxima revisao sequencial disponivel.
 *
 * ### 2. Para que serve
 * Permite que operadores de produto definam novas composicoes de secoes, layouts e regras de targeting.
 *
 * ### 3. Como funciona
 * Valida o ator e despacha [DraftSpecCommand] para [DraftUseCase.createSpecDraft]. O caso de uso valida
 * a aderencia ao skeleton, capacidades suportadas e integridade do checksum SHA-256. Emite metrica
 * tagueada pela surface e plataforma aprovadas.
 *
 * @param spec Dados completos do rascunho da especificacao.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return A instancia de [Spec] gravada com sua revisao oficial atribuida.
*/
    @PostMapping("/specs")
    fun createSpec(
        @RequestBody spec: Spec,
        @RequestHeader headers: HttpHeaders,
    ): Spec {
        val currentActor = actor(headers)
        val created = drafts.createSpecDraft(DraftSpecCommand(currentActor, spec))
        // Tags do rascunho gravado: a surface ja passou pela allowlist do validador.
        metrics.increment(
            MetricNames.ADMIN_SPEC_DRAFT,
            mapOf("surface" to created.surface, "platform" to created.platform.wire()),
        )
        // Valores do rascunho gravado: sem revisao existente, o servico atribui a proxima, e o
        // corpo traria outra.
        logger.info(
            "spec draft created: specId={}, revision={}, surface={}, platform={}, actor={}",
            created.specId,
            created.revision,
            created.surface,
            created.platform.wire(),
            currentActor.id,
        )
        return created
    }

/**
 * Lista o historico de revisoes de uma especificacao de tela.
 *
 * ### 1. O que faz
 * Recupera as revisoes cadastradas de um spec identificado por [id], em ordem paginada.
 *
 * ### 2. Para que serve
 * Permite visualizar a evolucao historica de alteracoes de layout e targeting de uma surface.
 *
 * ### 3. Como funciona
 * Valida o ator e consulta [CatalogQueryUseCase.revisions] utilizando os limites validados por [page].
 *
 * @param id Identificador da especificacao de tela.
 * @param offset Indice de deslocamento para paginacao.
 * @param limit Quantidade de revisoes a retornar.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return Lista de [Spec] contendo o historico de revisoes.
*/
    @GetMapping("/specs/{id}/revisions")
    fun revisions(
        @PathVariable id: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<Spec> {
        actor(headers)
        return catalogQuery.revisions(id, page(offset, limit))
    }

/**
 * Compara duas revisoes distintas de uma especificacao de tela (Diff).
 *
 * ### 1. O que faz
 * Calcula e apresenta a diferenca estrutural ([SpecDiff]) entre a revisao [from] e a revisao [to].
 *
 * ### 2. Para que serve
 * Auxilia revisores e aprovadores no processo de governanca maker-checker a inspecionar secoes
 * adicionadas, removidas ou alteradas antes de autorizar a publicacao.
 *
 * ### 3. Como funciona
 * Valida o ator e invoca [CatalogQueryUseCase.diff]. Caso alguma das revisoes nao exista, lanca [AdminNotFound].
 *
 * @param id Identificador da especificacao de tela.
 * @param from Numero da revisao base da comparacao.
 * @param to Numero da revisao alvo da comparacao.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return Objeto [SpecDiff] detalhando as diferencas identificadas.
 * @throws AdminNotFound Se o diff nao puder ser calculado por ausencia de uma das revisoes.
*/
    @GetMapping("/specs/{id}/revisions/{from}..{to}/diff")
    fun diff(
        @PathVariable id: String,
        @PathVariable from: Int,
        @PathVariable to: Int,
        @RequestHeader headers: HttpHeaders,
    ): SpecDiff {
        actor(headers)
        return catalogQuery.diff(id, from, to) ?: throw AdminNotFound("diff")
    }

/**
 * Abre um novo pedido formal de publicacao para uma revisao de spec.
 *
 * ### 1. O que faz
 * Registra uma solicitacao de publicacao ([PublishRequest]) para um canal especifico (`draft`, `canary` ou `stable`).
 *
 * ### 2. Para que serve
 * Inicia o fluxo de esteira de governanca para promover uma revisao de spec aos clientes consumidores.
 *
 * ### 3. Como funciona
 * - Exige o cabecalho `Idempotency-Key` (ADR-022) para assegurar que retries de rede nao dupliquem pedidos.
 * - Extrai e valida o ator solicitante (`maker`).
 * - Realiza o parsing seguro do canal de destino via [Channel.parseOrNull].
 * - Despacha o comando [OpenPublishCommand] para [PublishUseCase.open].
 * - Registra metricas operacionais e log estruturado com a acao efetuada.
 *
 * @param body Dados contendo identificador do spec, revisao e canal pretendido.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @param idempotencyKey Chave unica de idempotencia da operacao.
 * @return O [PublishRequest] aberto com status inicial (pendente ou publicado, se auto-aprovado em draft).
*/
    @PostMapping("/publish-requests")
    fun openPublish(
        @RequestBody body: OpenPublishBody,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val created = publish.open(
            OpenPublishCommand(
                actor = currentActor,
                specId = body.specId,
                revision = body.revision,
                channel = Channel.parseOrNull(body.channel) ?: throw unknown("channel"),
                idempotencyKey = idempotencyKey,
            ),
        )
        // Tag e log vem do pedido persistido, e nao do corpo: o texto cru varia em caixa e espaco,
        // e cada grafia abriria uma serie de metrica.
        metrics.increment(MetricNames.ADMIN_PUBLISH_OPEN, mapOf("channel" to created.channel.wire()))
        logger.info(
            "publish request opened: id={}, specId={}, revision={}, channel={}, actor={}",
            created.requestId,
            created.specId,
            created.revision,
            created.channel.wire(),
            currentActor.id,
        )
        return created
    }

/**
 * Aprova e efetiva um pedido de publicacao pendente.
 *
 * ### 1. O que faz
 * Autoriza a promocao do spec e atualiza o ponteiro ([Pointer]) correspondente da surface/plataforma/canal.
 *
 * ### 2. Para que serve
 * Concretiza a etapa "checker" da esteira maker-checker, promovendo a revisao aprovada para o canal selecionado.
 *
 * ### 3. Como funciona
 * - Exige a chave de idempotencia [idempotencyKey].
 * - Valida a segregacao de funcoes: em canais protegidos, o orquestrador bloqueia se o operador aprovador
 *   for identico ao autor da solicitacao de publicacao.
 * - Atualiza atomicamente o ponteiro ativo, invalida os caches de spec e arvore fora da transacao e
 *   registra a aprovacao na trilha de auditoria append-only.
 *
 * @param id Identificador unico da solicitacao de publicacao.
 * @param headers Cabecalhos HTTP com a identidade do operador aprovador.
 * @param idempotencyKey Chave unica de idempotencia da operacao.
 * @return O [PublishRequest] atualizado com status de aprovado.
*/
    @PostMapping("/publish-requests/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val approved = publish.approve(DecidePublishCommand(currentActor, id, idempotencyKey))
        metrics.increment(MetricNames.ADMIN_PUBLISH_APPROVED, mapOf("channel" to approved.channel.wire()))
        logger.info(
            "publish request approved: id={}, actor={}, role={}, targetSpecRevisionId={}",
            id,
            currentActor.id,
            currentActor.role,
            approved.specRevisionId,
        )
        return approved
    }

/**
 * Rejeita um pedido de publicacao pendente.
 *
 * ### 1. O que faz
 * Marca o pedido de publicacao como rejeitado, registrando formalmente o motivo da recusa.
 *
 * ### 2. Para que serve
 * Interrompe o fluxo de promocao de um layout inadequado ou inconsistente, assegurando trilha de auditoria.
 *
 * ### 3. Como funciona
 * Valida o ator, exige o preenchimento de [RejectBody.reason] e despacha para [PublishUseCase.reject].
 * A operacao honra [idempotencyKey], emite metricas operacionais e gera log de advertencia (`logger.warn`).
 *
 * @param id Identificador unico da solicitacao de publicacao.
 * @param body Objeto contendo a justificativa textual obrigatoria da recusa.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @param idempotencyKey Chave unica de idempotencia da operacao.
 * @return O [PublishRequest] marcado como rejeitado.
*/
    @PostMapping("/publish-requests/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @RequestBody body: RejectBody,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): PublishRequest {
        val currentActor = actor(headers)
        val rejected = publish.reject(DecidePublishCommand(currentActor, id, idempotencyKey), body.reason)
        metrics.increment(MetricNames.ADMIN_PUBLISH_REJECTED, mapOf("channel" to rejected.channel.wire()))
        logger.warn(
            "publish request rejected: id={}, actor={}, role={}, reason={}",
            id,
            currentActor.id,
            currentActor.role,
            body.reason,
        )
        return rejected
    }

/**
 * Executa o rollback atomico do ponteiro de publicacao de uma surface.
 *
 * ### 1. O que faz
 * Move o ponteiro de publicacao ([Pointer]) de uma triade (surface, platform, channel) para uma revisao
 * anterior ou especificamente informada.
 *
 * ### 2. Para que serve
 * Recurso emergencial de resiliencia operacional para reverter imediatamente um layout com defeito ou
 * degradacao em producao para uma revisao conhecida e saudavel (last good).
 *
 * ### 3. Como funciona
 * - Valida a existencia de surface, plataforma e canal contra a allowlist canônica; segmentos desconhecidos
 *   disparam imediatamente [AdminNotFound] (HTTP 404).
 * - Exige [idempotencyKey] para prevencao de retries descontrolados.
 * - Delega para [RollbackPointerUseCase.rollback], que atualiza o ponteiro atomicamente via compare-and-set,
 *   invalida caches em memoria/Redis e invalida a versao em cache do last good de fallback (ADR-014).
 * - Emite metrica `sdui.admin.rollback` tagueada e registra log estruturado com a razao do rollback.
 *
 * @param surface Identificador da surface alvo (ex: `home`, `catalog`).
 * @param platform Identificador da plataforma cliente (`ios`, `android`).
 * @param channel Identificador do canal (`canary`, `stable`).
 * @param body Dados opcionais contendo a revisao alvo especifica e o motivo da reversao.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @param idempotencyKey Chave unica de idempotencia da operacao.
 * @return O [Pointer] resultante apos a conclusao do rollback.
*/
    @PostMapping("/pointers/{surface}/{platform}/{channel}:rollback")
    fun rollbackPointer(
        @PathVariable surface: String,
        @PathVariable platform: String,
        @PathVariable channel: String,
        @RequestBody(required = false) body: RollbackBody?,
        @RequestHeader headers: HttpHeaders,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
    ): Pointer {
        val currentActor = actor(headers)
        // O path identifica o pointer: segmento desconhecido e pointer inexistente.
        val knownSurface = Surfaces.find(surface) ?: throw AdminNotFound("surface desconhecida")
        val knownPlatform = ClientPlatform.parse(platform) ?: throw AdminNotFound("plataforma desconhecida")
        val knownChannel = Channel.parseOrNull(channel) ?: throw AdminNotFound("canal desconhecido")
        val moved = rollback.rollback(
            RollbackCommand(
                actor = currentActor,
                surface = knownSurface.id,
                platform = knownPlatform,
                channel = knownChannel,
                targetSpecRevisionId = body?.targetSpecRevisionId,
                idempotencyKey = idempotencyKey,
                reason = body?.reason ?: "rollback",
            ),
        )
        // Tags e log do pointer movido, e nao do path: plataforma e canal chegam como texto livre e
        // so o valor normalizado mantem a cardinalidade fechada.
        metrics.increment(
            MetricNames.ADMIN_ROLLBACK,
            mapOf("surface" to moved.surface, "platform" to moved.platform.wire(), "channel" to moved.channel.wire()),
        )
        logger.warn(
            "pointer rollback executed: surface={}, platform={}, channel={}, actor={}, targetSpecRevisionId={}, reason={}",
            moved.surface,
            moved.platform.wire(),
            moved.channel.wire(),
            currentActor.id,
            body?.targetSpecRevisionId,
            body?.reason,
        )
        return moved
    }

/**
 * Consulta os eventos mais recentes da trilha de auditoria append-only.
 *
 * ### 1. O que faz
 * Retorna a lista de eventos administrativos ([AuditEvent]) ordenados do mais recente para o mais antigo.
 *
 * ### 2. Para que serve
 * Fornece visibilidade para compliance, seguranca e operacao sobre quem realizou cada alteracao de governanca.
 *
 * ### 3. Como funciona
 * Valida o ator solicitante, normaliza a quantidade solicitada em [limit] via [page] (padrao 100) e
 * consulta [AuditQueryUseCase.recent]. Incrementa a metrica `sdui.admin.audit.list`.
 *
 * @param limit Quantidade maxima de eventos a retornar.
 * @param headers Cabecalhos HTTP com a identidade do operador.
 * @return Lista dos eventos de auditoria registrados.
*/
    @GetMapping("/audit")
    fun audit(
        @RequestParam(required = false) limit: Int?,
        @RequestHeader headers: HttpHeaders,
    ): List<AuditEvent> {
        val events = auditQuery.recent(actor(headers), page(0, limit).limit)
        metrics.increment(MetricNames.ADMIN_AUDIT_LIST)
        return events
    }

/**
 * Normaliza e valida os parâmetros de paginacao da requisicao.
 *
 * ### 1. O que faz
 * Constroi um [PageRequest] seguro a partir dos valores opcionais de [offset] e [limit].
 *
 * ### 2. Para que serve
 * Impede consultas com faixas negativas ou volumes excessivos que possam comprometer a memoria do servidor.
 *
 * ### 3. Como funciona
 * Aplica valores default caso nulos (offset = 0, limit = 100). Valida se offset >= 0 e se o limit
 * esta contido no intervalo de 1 ate [PageRequest.MAX_LIMIT] (500). Valores fora da faixa lancam [AdminValidation].
 *
 * @param offset Indice de deslocamento solicitado.
 * @param limit Limite de registros solicitado.
 * @return Instancia validada de [PageRequest].
 * @throws AdminValidation Se os parâmetros estiverem fora dos limites permitidos.
*/
    private fun page(offset: Int?, limit: Int?): PageRequest {
        val resolvedOffset = offset ?: 0
        val resolvedLimit = limit ?: PageRequest.DEFAULT_LIMIT
        if (resolvedOffset < 0 || resolvedLimit !in 1..PageRequest.MAX_LIMIT) {
            throw AdminValidation(listOf("offset >= 0 e limit entre 1 e ${PageRequest.MAX_LIMIT}"))
        }
        return PageRequest(resolvedOffset, resolvedLimit)
    }

/**
 * Fabrica uma excecao de validacao administrativa para campos com valores fora do vocabulario.
 *
 * ### 1. O que faz
 * Cria uma instancia de [AdminValidation] sinalizando que um campo informado possui valor desconhecido.
 *
 * ### 2. Para que serve
 * Padroniza as mensagens de erro de validacao da camada HTTP quando parâmetros de consulta nao sao aceitos.
 *
 * ### 3. Como funciona
 * Retorna [AdminValidation] encapsulando a mensagem de erro formatada com o nome do [field].
 *
 * @param field Nome do campo com valor invalido.
 * @return Excecao [AdminValidation] pronta para ser disparada.
*/
    private fun unknown(field: String): AdminValidation = AdminValidation(listOf("$field desconhecido"))

/**
 * Extrai e valida a identidade e papel do operador a partir dos cabecalhos HTTP da requisicao.
 *
 * ### 1. O que faz
 * Converte os cabecalhos `Actor-Id` e `Actor-Role` em uma entidade [Actor] tipada.
 *
 * ### 2. Para que serve
 * Assegura que toda operacao no plano administrativo possua um autor rastreavel com permissao definida.
 *
 * ### 3. Como funciona
 * Obtem o valor de `Actor-Id` e realiza o parsing de `Actor-Role` via [ActorRole.parse]. Se algum estiver
 * ausente ou for invalido, lanca [AdminDenied] (que se traduz em HTTP 403 Forbidden).
 *
 * @param headers Cabecalhos HTTP da requisicao.
 * @return Entidade [Actor] representando o operador autenticado.
 * @throws AdminDenied Se a identificacao do operador for incompleta ou invalida.
*/
    private fun actor(headers: HttpHeaders): Actor {
        val id = headers.getFirst("Actor-Id") ?: throw AdminDenied("Actor-Id ausente")
        val role = ActorRole.parse(headers.getFirst("Actor-Role")) ?: throw AdminDenied("Actor-Role ausente")
        return Actor(id, role)
    }
}

/**
 * Corpo da requisicao HTTP para abertura de solicitacao formal de publicacao.
 *
 * ### 1. O que faz
 * Encapsula os dados necessarios para submeter uma revisao de spec para aprovacao e publicacao.
 *
 * ### 2. Para que serve
 * DTO de entrada para o endpoint `POST /admin/v1/publish-requests`.
 *
 * ### 3. Como funciona
 * Transporta o identificador do spec, a revisao pretendida e o canal alvo (com default para `stable`).
 *
 * @property specId Identificador do spec a ser promovido.
 * @property revision Numero da revisao do spec.
 * @property channel Nome textual do canal alvo (ex: `draft`, `canary`, `stable`).
*/
data class OpenPublishBody(
    val specId: String,
    val revision: Int,
    val channel: String = "stable",
)

/**
 * Corpo da requisicao HTTP para rejeicao de um pedido de publicacao.
 *
 * ### 1. O que faz
 * Carrega a justificativa obrigatoria da recusa de uma promocao de layout.
 *
 * ### 2. Para que serve
 * DTO de entrada para o endpoint `POST /admin/v1/publish-requests/{id}/reject`.
 *
 * ### 3. Como funciona
 * Fornece a string de justificativa para ser gravada de forma indelével na trilha de auditoria.
 *
 * @property reason Justificativa textual detalhando o motivo da recusa.
*/
data class RejectBody(val reason: String)

/**
 * Corpo opcional da requisicao HTTP para execucao de rollback de ponteiro.
 *
 * ### 1. O que faz
 * Fornece parâmetros de direcionamento e justificativa para a reversao de um ponteiro.
 *
 * ### 2. Para que serve
 * DTO de entrada para o endpoint `POST /admin/v1/pointers/{surface}/{platform}/{channel}:rollback`.
 *
 * ### 3. Como funciona
 * Se [targetSpecRevisionId] for informado, aponta diretamente para essa revisao. Se for nulo, o
 * servico de rollback reverte para a revisao imediatamente anterior do proprio ponteiro.
 *
 * @property targetSpecRevisionId Identificador opcional da revisao especifica de destino.
 * @property reason Justificativa opcional da operacao de rollback.
*/
data class RollbackBody(
    val targetSpecRevisionId: String? = null,
    val reason: String? = null,
)
