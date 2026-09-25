package br.com.empresa.sdui.orchestrator.port.outbound

import br.com.empresa.sdui.core.model.AuditEvent
import br.com.empresa.sdui.core.model.Catalog
import br.com.empresa.sdui.core.model.Channel
import br.com.empresa.sdui.core.model.ClientPlatform
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.IdempotencyRecord
import br.com.empresa.sdui.core.model.Pointer
import br.com.empresa.sdui.core.model.PublishRequest
import br.com.empresa.sdui.core.model.PublishRequestStatus
import br.com.empresa.sdui.core.model.Skeleton
import br.com.empresa.sdui.core.model.Spec
import br.com.empresa.sdui.core.model.SpecDiff
import java.time.Duration
import java.time.Instant

/**
 * Exceção indicativa de conflito de concorrência otimista na camada de persistência.
 *
 * ### 1. O que faz
 * Sinaliza que a gravação de uma entidade falhou porque a versão esperada ou o estado do documento
 * divergiu do persistido entre o momento da leitura e a tentativa de escrita.
 *
 * ### 2. Para que serve
 * Protege a integridade transacional de ponteiros e pedidos de publicação contra mutações concorrentes,
 * sendo convertida na borda administrativa no código `HTTP 409 Conflict`.
 *
 * ### 3. Como funciona
 * Lançada pelos adapters quando uma instrução atômica CAS (*Compare-And-Swap*) no banco ou mapa em memória
 * falha por incompatibilidade da versão informada.
 */
class StoreConflict(message: String) : RuntimeException(message)

/**
 * Exceção que indica a recusa de persistência motivada por restrições da própria infraestrutura de armazenamento.
 *
 * ### 1. O que faz
 * Rejeita o documento persistido por regras de infraestrutura (ex.: excesso do limite de tamanho do documento BSON/JSON).
 *
 * ### 2. Para que serve
 * Traduzida pela borda administrativa para `HTTP 400 Bad Request`, indicando que o payload excede as capacidades
 * físicas toleradas e que retries automáticos não solucionarão o problema sem redução do conteúdo.
 *
 * ### 3. Como funciona
 * Disparada pelos adaptadores de banco antes ou durante a serialização física quando limites de tamanho são violados.
 */
class StoreRejected(message: String) : RuntimeException(message)

/**
 * Parâmetros de paginação para consultas administrativas na camada de armazenamento.
 *
 * ### 1. O que faz
 * Define o deslocamento (*offset*) e a quantidade máxima de registros (*limit*) a serem retornados em uma listagem.
 *
 * ### 2. Para que serve
 * Evita degradações severas de latência e consumo de memória (GC pauses) que ocorreriam ao carregar
 * coleções inteiras de milhares de revisões de telas em uma única consulta.
 *
 * ### 3. Como funciona
 * Valida estritamente as regras de limite no momento da instanciação (`offset >= 0` e `limit` entre 1 e [MAX_LIMIT]).
 */
data class PageRequest(
    /**
     * Posição inicial da página (deslocamento baseado em zero).
     *
     * ### 1. O que faz
     * Especifica quantos registros devem ser pulados na listagem.
     *
     * ### 2. Para que serve
     * Permite navegar sequencialmente pelas páginas de resultados.
     *
     * ### 3. Como funciona
     * Inteiro não negativo validado no construtor.
     */
    val offset: Int,

    /**
     * Quantidade máxima de itens a retornar na página.
     *
     * ### 1. O que faz
     * Delimita o tamanho da fatia de dados resgatada.
     *
     * ### 2. Para que serve
     * Impede que consultas abusas sobrecarreguem o heap da aplicação.
     *
     * ### 3. Como funciona
     * Inteiro positivo limitado pelo teto de [MAX_LIMIT].
     */
    val limit: Int,
) {
    init {
        require(offset >= 0) { "offset deve ser >= 0" }
        require(limit in 1..MAX_LIMIT) { "limit deve estar entre 1 e $MAX_LIMIT" }
    }

    companion object {
        /** Limite padrão de registros quando não especificado pelo cliente (100 itens). */
        const val DEFAULT_LIMIT: Int = 100

        /** Limite máximo absoluto permitido para uma única página (500 itens). */
        const val MAX_LIMIT: Int = 500

        /** Primeira página canônica com offset 0 e tamanho padrão [DEFAULT_LIMIT]. */
        val FIRST: PageRequest = PageRequest(0, DEFAULT_LIMIT)
    }
}

/**
 * Aplica paginação em memória a uma lista ordenada existente.
 *
 * ### 1. O que faz
 * Extrai uma sublista correspondente à janela definida em [page].
 *
 * ### 2. Para que serve
 * Padroniza o fatiamento de resultados para adaptadores que não dispõem de paginação nativa em banco.
 *
 * ### 3. Como funciona
 * Retorna lista vazia se `page.offset >= size`; caso contrário, extrai a sublista delimitada pelo menor valor entre o tamanho total e `offset + limit`.
 */
fun <T> List<T>.page(page: PageRequest): List<T> =
    if (page.offset >= size) emptyList() else subList(page.offset, minOf(size, page.offset + page.limit)).toList()

/**
 * Porta de saída (*outbound SPI*) para persistência e recuperação de especificações de tela (`Spec`).
 *
 * ### 1. O que faz
 * Declara as operações de gravação, busca por revisão, listagem paginada e controle atômico de revisões de specs.
 *
 * ### 2. Para que serve
 * Desacopla a regra de negócio do orquestrador dos detalhes de implementação de persistência
 * (seja em memória com `ConcurrentHashMap` ou persistente via MongoDB). Garante que revisões com status
 * `PUBLISHED` sejam tratadas como estritamente imutáveis.
 *
 * ### 3. Como funciona
 * As implementações realizam consultas indexadas por `specId` e `revision`, oferecendo garantias atômicas
 * em [compareAndSet] e gerando números sequenciais monótonos para novas revisões via [nextRevision].
 */
interface SpecStore {
    /**
     * Salva incondicionalmente um spec.
     *
     * ### 1. O que faz
     * Persiste o documento de especificação informado.
     *
     * ### 2. Para que serve
     * Usado na inicialização de sementes (*seed*), testes ou gravações diretas de rascunhos.
     *
     * ### 3. Como funciona
     * Armazena ou substitui o documento no banco subjacente e o retorna.
     */
    fun save(spec: Spec): Spec

    /**
     * Atualiza atomicamente um spec com base na versão esperada observada na leitura.
     *
     * ### 1. O que faz
     * Grava o spec [updated] se e somente se o estado persistido corresponder a [expected].
     *
     * ### 2. Para que serve
     * Impede que duas edições concorrentes de rascunhos sobrescrevam silenciosamente o trabalho alheio.
     *
     * ### 3. Como funciona
     * Cria o documento se [expected] for nulo; caso contrário, substitui apenas se o estado atual bater com o esperado, lançando [StoreConflict] em caso de divergência.
     */
    fun compareAndSet(expected: Spec?, updated: Spec): Spec

    /**
     * Localiza um spec pelo identificador composto de revisão (`specRevisionId`).
     *
     * ### 1. O que faz
     * Busca direta pela chave única da revisão (ex.: `home.default.ios@1`).
     *
     * ### 2. Para que serve
     * Utilizada no hot path de composição quando o ponteiro já indicou a revisão exata a ser montada.
     *
     * ### 3. Como funciona
     * Consulta pontual indexada retornando o [Spec] correspondente ou nulo se inexistente.
     */
    fun findByRevisionId(specRevisionId: String): Spec?

    /**
     * Localiza um spec pelo ID do documento e número da revisão.
     *
     * ### 1. O que faz
     * Recupera uma versão histórica específica de um spec.
     *
     * ### 2. Para que serve
     * Alimenta a visualização de histórico e cálculos de diff estrutural no painel de governança.
     *
     * ### 3. Como funciona
     * Busca com predicado composto por [specId] e [revision].
     */
    fun findBySpecIdAndRevision(specId: String, revision: Int): Spec?

    /**
     * Lista todas as revisões já criadas para um determinado specId.
     *
     * ### 1. O que faz
     * Retorna a coleção completa de versões associadas a um identificador de spec.
     *
     * ### 2. Para que serve
     * Suporte a rotinas de manutenção ou inspeção completa de histórico.
     *
     * ### 3. Como funciona
     * Consulta todas as entradas com a chave [specId] sem paginação.
     */
    fun listBySpecId(specId: String): List<Spec>

    /**
     * Lista todas as especificações que possuem status `PUBLISHED` para uma dada surface e plataforma.
     *
     * ### 1. O que faz
     * Recupera o catálogo de versões ativas e aptas para consumo nos clientes móveis.
     *
     * ### 2. Para que serve
     * Alimenta o mecanismo de seleção e compatibilidade de telas no pipeline de composição.
     *
     * ### 3. Como funciona
     * Filtra os documentos onde status é publicado e a plataforma coincide com [platform].
     */
    fun listPublished(surface: String, platform: ClientPlatform): List<Spec>

    /**
     * Lista especificações com filtros opcionais de plataforma e canal.
     *
     * ### 1. O que faz
     * Retorna os specs que correspondam aos critérios fornecidos.
     *
     * ### 2. Para que serve
     * Viabiliza consultas gerais da governança administrativa.
     *
     * ### 3. Como funciona
     * Aplica filtros combinados em memória ou via índices do banco.
     */
    fun list(platform: ClientPlatform?, channel: Channel?): List<Spec>

    /**
     * Gera o próximo número ordinal sequencial para uma nova revisão do spec informado.
     *
     * ### 1. O que faz
     * Incrementa monotonicamente o contador de revisões de um [specId].
     *
     * ### 2. Para que serve
     * Garante números de revisão estritamente crescentes sem duplicidades para o mesmo spec.
     *
     * ### 3. Como funciona
     * Avalia o maior número de revisão persistido e retorna `maior + 1`.
     */
    fun nextRevision(specId: String): Int

    /**
     * Retorna uma página de especificações com ordenação estável por `specId` e `revision`.
     *
     * ### 1. O que faz
     * Consulta paginada protegida contra sobrecarga de memória.
     *
     * ### 2. Para que serve
     * Navegação segura no painel de administração.
     *
     * ### 3. Como funciona
     * Ordena via [SPEC_ORDER] e recorta a lista conforme [page].
     */
    fun list(platform: ClientPlatform?, channel: Channel?, page: PageRequest): List<Spec> =
        list(platform, channel).sortedWith(SPEC_ORDER).page(page)

    /**
     * Retorna uma página das revisões de um spec ordenadas crescentemente por número de revisão.
     *
     * ### 1. O que faz
     * Lista paginada das versões de um spec específico.
     *
     * ### 2. Para que serve
     * Visualização progressiva do histórico de versões de uma tela.
     *
     * ### 3. Como funciona
     * Ordena pelo campo de revisão e extrai a página via [page].
     */
    fun listBySpecId(specId: String, page: PageRequest): List<Spec> =
        listBySpecId(specId).sortedBy { it.revision }.page(page)

    companion object {
        /** Comparador estável para ordenação de specs por identificador e número de revisão. */
        val SPEC_ORDER: Comparator<Spec> = compareBy<Spec> { it.specId }.thenBy { it.revision }
    }
}

/**
 * Porta de saída (*outbound SPI*) para persistência e consulta de esqueletos estruturais (`Skeleton`).
 *
 * ### 1. O que faz
 * Gerencia o ciclo de vida e versionamento dos esqueletos de layout de telas.
 *
 * ### 2. Para que serve
 * Permite que a estrutura de posições e slots de uma surface seja versionada e armazenada de
 * forma independente das especificações de conteúdo das seções.
 *
 * ### 3. Como funciona
 * Provê operações de gravação com concorrência otimista e recuperação tanto por versão exata quanto pela versão corrente.
 */
interface SkeletonStore {
    /** Salva incondicionalmente um esqueleto de layout. */
    fun save(skeleton: Skeleton): Skeleton

    /** Substitui o esqueleto atomicamente se o estado atual coincidir com [expected]. */
    fun compareAndSet(expected: Skeleton?, updated: Skeleton): Skeleton

    /** Localiza um esqueleto por identificador e revisão opcional. */
    fun find(skeletonId: String, revision: Int? = null): Skeleton?

    /** Recupera a versão corrente ativa do esqueleto indicado. */
    fun current(skeletonId: String): Skeleton?
}

/**
 * Extensão utilitária para localizar o esqueleto referenciado por uma especificação.
 *
 * ### 1. O que faz
 * Recupera o [Skeleton] exato amarrado ao [spec] informado.
 *
 * ### 2. Para que serve
 * Garante que a composição utilize a revisão imutável exata de esqueleto exigida pelo spec, nunca um rascunho temporário.
 *
 * ### 3. Como funciona
 * Invoca `SkeletonStore.find` passando `spec.skeletonId` e `spec.skeletonRevision`.
 */
fun SkeletonStore.findFor(spec: Spec): Skeleton? =
    find(spec.skeletonId, spec.skeletonRevision)

/**
 * Porta funcional para cálculo do hash resumo (*fingerprint*) da publicação.
 *
 * ### 1. O que faz
 * Gera um hash criptográfico (ex.: SHA-256) representativo da união do spec com seu esqueleto.
 *
 * ### 2. Para que serve
 * Assegura que o artefato revisado pelo *checker* na interface seja rigorosamente idêntico ao que será ativado pelo ponteiro.
 *
 * ### 3. Como funciona
 * Concatena as representações canônicas de [spec] e [skeleton] e calcula o digest correspondente.
 */
fun interface PublicationFingerprint {
    /** Calcula a impressão digital a partir do spec e esqueleto informados. */
    fun of(spec: Spec, skeleton: Skeleton): String
}

/**
 * Porta de saída (*outbound SPI*) para persistência do catálogo oficial de componentes.
 *
 * ### 1. O que faz
 * Armazena e recupera a totalidade dos tipos de componentes homologados no servidor.
 *
 * ### 2. Para que serve
 * Mantém o catálogo como um bloco único e coerente, já que componentes precisam ser validados em conjunto.
 *
 * ### 3. Como funciona
 * Persiste o catálogo completo e fornece a versão corrente ativa através de [current].
 */
interface CatalogStore {
    /** Grava o catálogo de componentes atualizado. */
    fun save(catalog: Catalog): Catalog

    /** Retorna o catálogo de componentes atualmente em vigor. */
    fun current(): Catalog
}

/**
 * Porta de saída (*outbound SPI*) para persistência dos ponteiros de publicação (`Pointer`).
 *
 * ### 1. O que faz
 * Registra qual revisão de spec está ativa para cada combinação de surface, plataforma e canal.
 *
 * ### 2. Para que serve
 * É o coração da governança de Server-Driven UI: mudar o ponteiro é a operação atômica que altera
 * o que os clientes recebem em produção sem re-deploy. Suporta publicação e reversão (*rollback*).
 *
 * ### 3. Como funciona
 * Utiliza controle atômico de versão monótona através de [compareAndSet], impedindo que operações concorrentes
 * se sobreponham acidentalmente.
 */
interface PointerStore {
    /** Localiza o ponteiro ativo para a combinação de surface, plataforma e canal. */
    fun find(surface: String, platform: ClientPlatform, channel: Channel): Pointer?

    /** Grava o ponteiro sem conferência de versão (reservado para seeds de inicialização e testes). */
    fun save(pointer: Pointer): Pointer

    /**
     * Atualiza o ponteiro atomicamente caso sua versão persistida coincida com [expectedVersion].
     *
     * ### 1. O que faz
     * Grava [updated] garantindo a inexistência de mutações intermediárias.
     *
     * ### 2. Para que serve
     * Protege contra publicação simultânea ou corridas entre aprovação e rollback.
     *
     * ### 3. Como funciona
     * Lança [StoreConflict] caso a versão em banco divirja de [expectedVersion].
     */
    fun compareAndSet(expectedVersion: Long?, updated: Pointer): Pointer
}

/**
 * Porta de saída (*outbound SPI*) para persistência de pedidos de publicação (`PublishRequest`).
 *
 * ### 1. O que faz
 * Registra o histórico e o status dos pedidos de promoção de telas submetidos ao fluxo *maker-checker*.
 *
 * ### 2. Para que serve
 * Garante que a transição de status (de `OPEN` para `APPROVED` ou `REJECTED`) seja estritamente atômica.
 *
 * ### 3. Como funciona
 * Usa [compareAndSetStatus] para garantir que apenas o primeiro revisor a julgar o pedido efetive a mudança.
 */
interface PublishRequestStore {
    /** Salva uma solicitação de publicação. */
    fun save(request: PublishRequest): PublishRequest

    /** Localiza um pedido pelo seu identificador único. */
    fun find(requestId: String): PublishRequest?

    /**
     * Atualiza atomicamente o status de um pedido caso o status atual coincida com [expected].
     *
     * ### 1. O que faz
     * Aplica transições de máquina de estados controladas no pedido.
     *
     * ### 2. Para que serve
     * Previne aprovações ou rejeições concorrentes sobre o mesmo pedido em aberto.
     *
     * ### 3. Como funciona
     * Retorna o pedido atualizado ou nulo se o status corrente divergir de [expected].
     */
    fun compareAndSetStatus(
        requestId: String,
        expected: PublishRequestStatus,
        updated: PublishRequest,
    ): PublishRequest?
}

/**
 * Porta de saída (*outbound SPI*) para armazenamento de diffs calculados entre revisões de specs.
 *
 * ### 1. O que faz
 * Persiste e recupera o resumo de alterações entre duas versões de uma especificação.
 *
 * ### 2. Para que serve
 * Evita recalcular diffs repetidamente durante os processos de julgamento pelo checker e auditoria.
 *
 * ### 3. Como funciona
 * Indexa e busca diffs por [specId], revisão de origem [from] e revisão de destino [to].
 */
interface DiffStore {
    /** Salva o diff estrutural calculado. */
    fun save(diff: SpecDiff): SpecDiff

    /** Localiza o diff pré-computado entre duas revisões de um spec. */
    fun find(specId: String, from: Int, to: Int): SpecDiff?
}

/**
 * Porta de saída (*outbound SPI*) para persistência da trilha de auditoria (*audit log*).
 *
 * ### 1. O que faz
 * Registra de forma estritamente aditiva (*append-only*) todas as ações de governança realizadas.
 *
 * ### 2. Para que serve
 * Assegura rastreabilidade total de autoria, horário e justificativas para fins regulatórios e de segurança.
 *
 * ### 3. Como funciona
 * Apenas recebe novos eventos via [append] e oferece consultas paginadas/recentes através de [recent].
 */
interface AuditLogStore {
    /** Adiciona um novo evento de auditoria ao final da trilha. */
    fun append(event: AuditEvent)

    /** Lista todos os eventos de auditoria persistidos. */
    fun list(): List<AuditEvent>

    /** Retorna os [limit] eventos mais recentes ordenados do mais novo para o mais antigo. */
    fun recent(limit: Int): List<AuditEvent> = list().sortedByDescending { it.ts }.take(limit)
}

/**
 * Resultado do protocolo atômico de reserva de chave de idempotência.
 *
 * ### 1. O que faz
 * Modela os desfechos possíveis ao tentar adquirir uma chave de idempotência antes de executar um comando administrativo.
 *
 * ### 2. Para que serve
 * Garante que apenas uma chamada obtenha a posse da chave para mutação, prevenindo o padrão falho *check-then-act*.
 *
 * ### 3. Como funciona
 * Dividido em três casos disjuntos: posse adquirida ([Reserved]), chave já utilizada ou em voo ([Existing])
 * ou saturação da capacidade segura de armazenamento de chaves ([CapacityExhausted]).
 */
sealed interface IdempotencyReservation {
    /** A chamada adquiriu com sucesso a reserva da chave e possui permissão para executar a operação. */
    data class Reserved(val token: String) : IdempotencyReservation

    /** Já existe um registro ativo para a chave: em voo ou com resultado definitivo concluído. */
    data class Existing(val record: IdempotencyRecord) : IdempotencyReservation

    /** A capacidade segura de retenção de chaves foi temporariamente esgotada no servidor. */
    data object CapacityExhausted : IdempotencyReservation
}

/**
 * Porta de saída (*outbound SPI*) para controle estrito de idempotência de operações administrativas.
 *
 * ### 1. O que faz
 * Gerencia o ciclo de vida completo de chaves de idempotência sob o protocolo atômico de reserva antecipada.
 *
 * ### 2. Para que serve
 * Impede que comandos críticos (abrir pedido, aprovar, rejeitar ou reverter) sejam executados mais de uma vez
 * decorrentes de retries de rede ou duplicidade de requisições.
 *
 * ### 3. Como funciona
 * Opera no ciclo: `reserve` (adquire antes da ação) -> `complete` (efetiva no mesmo commit) ou `release` (libera em falha transitória).
 */
interface IdempotencyStore {
    /** Localiza o registro de idempotência correspondente à chave fornecida. */
    fun find(key: String): IdempotencyRecord?

    /**
     * Tenta reservar atômica e antecipadamente uma chave de idempotência para uma dada operação.
     *
     * ### 1. O que faz
     * Trava a chave contra execuções concorrentes antes que o efeito colateral seja disparado.
     *
     * ### 2. Para que serve
     * Elimina a brecha de corrida (*race condition*) onde duas requisições paralelas passariam por uma consulta ingênua.
     *
     * ### 3. Como funciona
     * Retorna [IdempotencyReservation] informando se a chave foi reservada, se já existe ou se a capacidade esgotou.
     */
    fun reserve(key: String, operation: String, fingerprint: String): IdempotencyReservation

    /** Finaliza a reserva associando o resultado definitivo da transação protegida. */
    fun complete(record: IdempotencyRecord, token: String)

    /** Libera a reserva da chave em caso de erro anterior ao commit, permitindo reenvio legítimo posterior. */
    fun release(key: String, token: String)
}

/**
 * Porta de saída (*outbound SPI*) para cache em memória ou distribuído de árvores de UI hidratadas.
 *
 * ### 1. O que faz
 * Armazena temporariamente instâncias serializáveis de `ComposedScreen` indexadas por chaves determinísticas de contexto.
 *
 * ### 2. Para que serve
 * Viabiliza o atendimento ultrarrápido da Home e outras surfaces, absorvendo picos sem acessar repositórios persistentes.
 *
 * ### 3. Como funciona
 * Consulta e armazena telas sob um tempo de vida ([ttl]), oferecendo método de [invalidate] orientado a publicações.
 */
interface HydratedScreenCache {
    /** Recupera a árvore em cache para a chave composta informada. */
    fun get(treeKey: String): ComposedScreen?

    /** Armazena a árvore composta associando-lhe um tempo de expiração ([ttl]). */
    fun put(treeKey: String, screen: ComposedScreen, ttl: Duration)

    /** Invalida preventivamente entradas de cache associadas à surface, plataforma e canal especificados. */
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel)
}

/**
 * Registro envelopado de uma árvore de tela de fallback com seu carimbo temporal de armazenamento.
 *
 * ### 1. O que faz
 * Encapsula a árvore de UI [screen] acompanhada do instante [storedAt] em que foi salva como last good.
 *
 * ### 2. Para que serve
 * Permite ao orquestrador validar se o snapshot de fallback está dentro do limite tolerável de idade antes de servi-lo.
 *
 * ### 3. Como funciona
 * O carimbo [storedAt] é confrontado contra a política de `maxFallbackAge` do serviço.
 */
data class StoredScreen(
    /** A árvore de UI guardada para contingência. */
    val screen: ComposedScreen,
    /** O instante exato em que a composição foi persistida como último bom snapshot. */
    val storedAt: Instant,
)

/**
 * Porta de saída (*outbound SPI*) para armazenamento da última árvore válida composta (*Last Good Screen*).
 *
 * ### 1. O que faz
 * Mantém um snapshot confiável da tela por surface, plataforma e canal para uso na escada de fallback.
 *
 * ### 2. Para que serve
 * Evita o retorno de `HTTP 503` em momentos de falha de dependências lentas ou timeouts de rede, entregando uma
 * tela íntegra com a indicação `fallback: true` no envelope.
 *
 * ### 3. Como funciona
 * Protegido por versionamento de ponteiro: gravações só são aceitas se `screen.pointerVersion` for igual ou superior
 * à versão guardada, impedindo que requisições lentas reintroduzam versões que já sofreram rollback.
 */
interface LastGoodScreenStore {
    /** Recupera o snapshot do último bom resultado conhecido. */
    fun get(surface: String, platform: ClientPlatform, channel: Channel): StoredScreen?

    /** Armazena a tela se a versão do ponteiro sob a qual foi composta for válida. */
    fun put(screen: ComposedScreen)

    /** Descarta snapshots defasados e estabelece barreira de versão para escritas atrasadas. */
    fun invalidate(surface: String, platform: ClientPlatform, channel: Channel, pointerVersion: Long)
}

/**
 * Porta de saída (*outbound SPI*) para cache de especificações de tela por revisão e plataforma.
 *
 * ### 1. O que faz
 * Mantém cópias em memória de revisões publicadas de specs.
 *
 * ### 2. Para que serve
 * Acelera a fase de seleção do pipeline sem exigir varreduras repetitivas nos repositórios primários.
 *
 * ### 3. Como funciona
 * Specs publicados são imutáveis; logo, o cache nunca fica inconsistente, sendo limpo apenas via [invalidate] se necessário.
 */
interface SpecCache {
    /** Recupera o spec pela sua revisão e plataforma móvel. */
    fun get(specRevisionId: String, platform: ClientPlatform): Spec?

    /** Armazena o spec no cache local. */
    fun put(spec: Spec)

    /** Remove a revisão do spec do cache. */
    fun invalidate(specRevisionId: String, platform: ClientPlatform)
}

/**
 * Porta de saída (*outbound SPI*) para armazenamento e recuperação de projeções seguras de dados.
 *
 * ### 1. O que faz
 * Provê acesso a dados de negócio pré-calculados e sanitizados (livres de PII) para enriquecer seções da tela.
 *
 * ### 2. Para que serve
 * Permite a injeção dinâmica de propriedades em seções específicas no estágio de hidratação sem acoplamento a domínios regulados.
 *
 * ### 3. Como funciona
 * Armazena e recupera mapas de propriedades indexados por nome de projeção e identificador sob um [ttl].
 */
interface ProjectionStore {
    /** Recupera os atributos da projeção segura para o identificador fornecido. */
    fun get(projection: String, id: String): Map<String, Any?>?

    /** Grava as propriedades da projeção com prazo de validade determinado. */
    fun put(projection: String, id: String, props: Map<String, Any?>, ttl: Duration)
}

/**
 * Porta de saída (*outbound SPI*) para demarcação de limites transacionais desacoplados de frameworks.
 *
 * ### 1. O que faz
 * Executa uma unidade de trabalho atômica sem acoplar o orquestrador a tecnologias específicas (como Spring `@Transactional`).
 *
 * ### 2. Para que serve
 * Assegura atomicidade em mutações críticas de governança, garantindo que efeitos colaterais de cache
 * ocorram estritamente após o sucesso do commit.
 *
 * ### 3. Como funciona
 * Envolve a execução do bloco funcional [work] em uma transação do banco subjacente.
 */
interface TransactionalUnitOfWork {
    /** Executa o bloco funcional [work] de forma transacional e atômica. */
    fun <T : Any> execute(work: () -> T): T
}

/**
 * Registro de intenção de invalidação de cache gerado por mutação de ponteiro.
 *
 * ### 1. O que faz
 * Modela o evento de expurgo de cache necessário após a publicação ou reversão de uma surface.
 *
 * ### 2. Para que serve
 * Integra o padrão *Transactional Outbox*, garantindo que nenhuma invalidação de cache seja perdida caso o processo falhe.
 *
 * ### 3. Como funciona
 * Gravado na mesma transação que atualiza o ponteiro e processado assincronamente pelo relay.
 */
data class CacheInvalidation(
    /** Identificador único do registro de invalidação. */
    val id: String,
    /** Surface cujo cache deve ser limpo. */
    val surface: String,
    /** Plataforma móvel afetada. */
    val platform: ClientPlatform,
    /** Canal de publicação afetado. */
    val channel: Channel,
    /** Versão resultante do ponteiro após a mutação. */
    val pointerVersion: Long,
    /** Revisão do spec que foi desativada pela publicação/rollback, se houver. */
    val retiredSpecRevisionId: String?,
    /** Instante de criação do registro de invalidação. */
    val createdAt: Instant,
)

/**
 * Porta de saída (*outbound SPI*) para o padrão Outbox de invalidações transacionais de cache.
 *
 * ### 1. O que faz
 * Gerencia a fila persistente de invalidações de cache pendentes de aplicação.
 *
 * ### 2. Para que serve
 * Garante a consistência eventual de caches distribuídos mesmo sob reinicialização abrupta de pods ou instabilidades de rede.
 *
 * ### 3. Como funciona
 * Grava registros via [record], permite listagem de pendências via [pending] e baixa definitiva via [markApplied].
 */
interface CacheInvalidationOutbox {
    /** Registra uma nova intenção de invalidação de cache na outbox. */
    fun record(invalidation: CacheInvalidation)

    /** Retorna as invalidações pendentes de processamento até o limite especificado. */
    fun pending(limit: Int): List<CacheInvalidation>

    /** Marca a invalidação como concluída com sucesso após confirmação do expurgo no cache. */
    fun markApplied(id: String)
}

/**
 * Resultado da execução controlada através do mecanismo de singleflight.
 *
 * ### 1. O que faz
 * Informa como a requisição concluiu sua passagem pelo coordenador de concorrência compartilhada.
 *
 * ### 2. Para que serve
 * Permite distinguir computações originais ([Leader]), reaproveitamentos de resultados de terceiros ([Waiter])
 * e desistências por tempo de espera esgotado ([WaitTimeout]).
 *
 * ### 3. Como funciona
 * Tipo soma genérico selado avaliado pelo pipeline para direcionamento métrico e acionamento de contingência.
 */
sealed interface SingleflightOutcome<out T> {
    /** A requisição assumiu a liderança, realizou a computação e disponibilizou o valor para os demais. */
    data class Leader<T>(val value: T) : SingleflightOutcome<T>

    /** A requisição aguardou e aproveitou integralmente o resultado computado pelo líder concorrente. */
    data class Waiter<T>(val value: T) : SingleflightOutcome<T>

    /**
     * O tempo de espera pelo líder expirou antes da conclusão da computação.
     *
     * Conforme a diretriz de qualidade inegociável, o waiter que sofre timeout **nunca** cancela o líder compartilhado.
     */
    data object WaitTimeout : SingleflightOutcome<Nothing>
}

/**
 * Porta de saída (*outbound SPI*) para deduplicação concorrente de requisições idênticas (*Singleflight*).
 *
 * ### 1. O que faz
 * Garante que apenas uma thread processe uma determinada chave computacional por vez, enfileirando as demais.
 *
 * ### 2. Para que serve
 * Mitiga o efeito de *cache stampede*, evitando que dezenas de requisições simultâneas saturem os bancos
 * quando uma entrada popular de cache expira.
 *
 * ### 3. Como funciona
 * Executa [compute] de forma exclusiva sob a chave [key] respeitando o tempo limite de espera [timeout].
 */
interface ComposeSingleflight {
    /** Executa ou compartilha a computação sob a chave informada. */
    fun <T> runExclusive(key: String, timeout: Duration, compute: () -> T): SingleflightOutcome<T>
}

/**
 * Porta de saída (*outbound SPI*) para resolução de canal de distribuição (*Canary Policy*).
 *
 * ### 1. O que faz
 * Determina se o cliente móvel deve ser direcionado ao canal `canary` ou permanecer no canal `stable`.
 *
 * ### 2. Para que serve
 * Centraliza a política de rollout gradual de telas no servidor, desonerando os aplicativos da tomada de decisão.
 *
 * ### 3. Como funciona
 * Avalia a plataforma, o número de build e a intenção solicitada para retornar o [Channel] definitivo.
 */
interface CanaryPolicy {
    /** Resolve o canal de distribuição efetivo para o dispositivo requisitante. */
    fun channelFor(platform: ClientPlatform, build: String, requested: Channel): Channel
}

/**
 * Porta de saída (*outbound SPI*) para gravação de métricas e instrumentos de telemetria.
 *
 * ### 1. O que faz
 * Abstrai o registro de contadores, timers de latência e medidores de volume de dados.
 *
 * ### 2. Para que serve
 * Mantém o núcleo do orquestrador desacoplado de bibliotecas concretas de observabilidade (como Micrometer ou Prometheus).
 *
 * ### 3. Como funciona
 * Encaminha as medições para os adaptadores de métricas com suporte a resoluções de milissegundos e nanossegundos.
 */
interface MetricsRecorder {
    /** Incrementa um contador de métrica pelo seu nome e tags associadas. */
    fun increment(name: String, tags: Map<String, String> = emptyMap())

    /** Registra uma duração em milissegundos para um timer de métrica. */
    fun recordTime(name: String, durationMs: Long, tags: Map<String, String> = emptyMap())

    /** Registra uma quantidade de bytes transferidos. */
    fun recordBytes(name: String, bytes: Long, tags: Map<String, String> = emptyMap())

    /** Registra uma duração em nanossegundos, convertendo para milissegundos por padrão. */
    fun recordNanos(name: String, durationNanos: Long, tags: Map<String, String> = emptyMap()) =
        recordTime(name, durationNanos / NANOS_PER_MILLI, tags)
}

private const val NANOS_PER_MILLI: Long = 1_000_000
