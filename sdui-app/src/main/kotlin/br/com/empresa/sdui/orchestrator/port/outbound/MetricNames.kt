package br.com.empresa.sdui.orchestrator.port.outbound

import br.com.empresa.sdui.core.model.ClientContext
import br.com.empresa.sdui.core.model.MvpCatalog
import br.com.empresa.sdui.core.model.SurfaceDefinition

/**
 * Catálogo centralizado de nomes canônicos de métricas emitidas pelo serviço.
 *
 * ### 1. O que faz
 * Centraliza em um único ponto imutável todos os identificadores de métricas de telemetria
 * coletadas pelo orquestrador e adaptadores do Server-Driven UI.
 *
 * ### 2. Para que serve
 * Impede a proliferação desordenada de métricas no sistema e garante que os nomes utilizados
 * em painéis de monitoramento (Grafana), alertas operacionais e cenários de teste sejam
 * estritamente aderentes a um vocabulário padronizado. Mais crucialmente, implementa a regra
 * arquitetural inegociável: **nomes de métricas são constantes e nunca interpolados**.
 * Interpolar variáveis dinâmicas (como ID de usuário, versão de app ou build) no nome da métrica
 * cria séries temporais infinitas no Prometheus/Micrometer, levando à exaustão de memória da JVM.
 *
 * ### 3. Como funciona
 * Define constantes estáticas padronizadas no formato hierárquico `domínio.evento[.unidade]`.
 * As dimensões variáveis da métrica nunca alteram seu nome, sendo enviadas exclusivamente
 * através de tags com vocabulário fechado.
 */
object MetricNames {
    /**
     * Duração total da composição de uma tela.
     *
     * ### 1. O que faz
     * Mede o tempo decorrido desde a recepção da requisição até a conclusão da composição.
     *
     * ### 2. Para que serve
     * Monitora a latência geral percebida pelo cliente móvel (SLA/SLO de resposta).
     *
     * ### 3. Como funciona
     * Registrada em milissegundos via timer do recorder com tags de surface, plataforma e schema.
     */
    const val COMPOSE_DURATION: String = "compose.duration"

    /**
     * Contagem de acertos no cache de telas hidratadas.
     *
     * ### 1. O que faz
     * Incrementa o contador toda vez que uma requisição é atendida diretamente da árvore em cache.
     *
     * ### 2. Para que serve
     * Avalia a eficácia do cache de composição, que deve absorver a grande maioria das requisições.
     *
     * ### 3. Como funciona
     * Disparada quando a chave composta localiza uma árvore válida no `HydratedScreenCache`.
     */
    const val COMPOSE_HIT: String = "compose.hit"

    /**
     * Contagem de falhas de acerto no cache de telas hidratadas.
     *
     * ### 1. O que faz
     * Incrementa o contador quando uma tela requer computação fresca por ausência em cache.
     *
     * ### 2. Para que serve
     * Permite calcular a taxa de cache hit/miss ratio e dimensionar a capacidade necessária de processamento.
     *
     * ### 3. Como funciona
     * Disparada no início do fluxo de composição quando a chave de árvore não é localizada.
     */
    const val COMPOSE_MISS: String = "compose.miss"

    /**
     * Contagem de requisições rejeitadas por limite de taxa (*rate limiting*).
     *
     * ### 1. O que faz
     * Contabiliza os eventos em que uma coorte de clientes excede o consumo seguro de requisições.
     *
     * ### 2. Para que serve
     * Sinaliza comportamentos anômalos de clientes (loops de retry, bots) e orienta ajustes no token bucket.
     *
     * ### 3. Como funciona
     * Disparada quando o limitador de taxa retorna rejeição com tempo de recuo (`HTTP 429`).
     */
    const val COMPOSE_RATE_LIMITED: String = "compose.rate_limited"

    /**
     * Contagem de requisições rejeitadas pelo bulkhead de concorrência.
     *
     * ### 1. O que faz
     * Incrementa quando não há permissões disponíveis no semáforo de concorrência do plano de leitura.
     *
     * ### 2. Para que serve
     * Evita sobrecarga catastrófica do serviço sob picos repentinos de tráfego, protegendo a estabilidade.
     *
     * ### 3. Como funciona
     * Incrementada quando a tentativa de aquisição no semáforo de Virtual Threads esgota o orçamento de espera.
     */
    const val COMPOSE_BULKHEAD_REJECTED: String = "compose.bulkhead.rejected"

    /**
     * Contagem de requisições que excederam o orçamento temporal de espera.
     *
     * ### 1. O que faz
     * Registra quando o tempo de espera ultrapassa o orçamento estipulado para a etapa da requisição.
     *
     * ### 2. Para que serve
     * Fornece visibilidade sobre contenção em filas internas e esperas desproporcionais.
     *
     * ### 3. Como funciona
     * Disparada pelo gerenciador de orçamento de tempo (`TimeBudget`) ao constatar prazo esgotado.
     */
    const val COMPOSE_DEADLINE_EXCEEDED: String = "compose.deadline.exceeded"

    /**
     * Duração da espera de um waiter pelo líder no singleflight.
     *
     * ### 1. O que faz
     * Cronometra quanto tempo requisições concorrentes aguardam pela computação compartilhada do líder.
     *
     * ### 2. Para que serve
     * Diagnostica a eficiência da deduplicação de requisições sob efeito manada (*stampede protection*).
     *
     * ### 3. Como funciona
     * Registrada em milissegundos quando uma requisição secundária é despertada pelo término do líder.
     */
    const val COMPOSE_SINGLEFLIGHT_WAIT: String = "compose.singleflight.wait"

    /**
     * Contagem de acertos na rechecagem de cache antes do singleflight.
     *
     * ### 1. O que faz
     * Registra requisições que encontraram a árvore recém-populada no cache antes de entrar na fila de espera.
     *
     * ### 2. Para que serve
     * Mede a eficácia da conferência em duas etapas (*double-checked locking* conceitual) no hot path.
     *
     * ### 3. Como funciona
     * Incrementada quando uma requisição que aguardava na fila detecta que o cache já foi abastecido.
     */
    const val COMPOSE_SINGLEFLIGHT_RECHECK_HIT: String = "compose.singleflight.recheck_hit"

    /**
     * Contagem de respostas de indisponibilidade geral da composição (`HTTP 503`).
     *
     * ### 1. O que faz
     * Contabiliza as requisições que não puderam ser atendidas mesmo após todas as tentativas de degradação.
     *
     * ### 2. Para que serve
     * Métrica crítica de confiabilidade e disponibilidade do serviço para acionamento de alertas de plantão.
     *
     * ### 3. Como funciona
     * Incrementada no encerramento da requisição quando o desfecho final resulta em [br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult.Unavailable].
     */
    const val COMPOSE_UNAVAILABLE: String = "compose.unavailable"

    /**
     * Contagem de árvores servidas a partir do repositório de fallback (*last good*).
     *
     * ### 1. O que faz
     * Registra o acionamento bem-sucedido do degrau de resiliência que entrega a última versão válida.
     *
     * ### 2. Para que serve
     * Demonstra que a aplicação protegeu a experiência do usuário entregando dados válidos em momento de falha.
     *
     * ### 3. Como funciona
     * Incrementada quando a composição falha no fluxo fresco mas encontra um snapshot no `LastGoodScreenStore`.
     */
    const val COMPOSE_FALLBACK: String = "compose.fallback"

    /**
     * Idade em milissegundos da árvore de fallback servida ao cliente.
     *
     * ### 1. O que faz
     * Mede o tempo decorrido desde a gravação do snapshot do last good até o instante em que foi servido.
     *
     * ### 2. Para que serve
     * Monitora o nível de defasagem dos dados entregues aos clientes em situação de degradação.
     *
     * ### 3. Como funciona
     * Calculada como `agora - storedAt` e registrada em milissegundos.
     */
    const val COMPOSE_FALLBACK_AGE: String = "compose.fallback.age.ms"

    /**
     * Contagem de rejeições de árvores de fallback por estarem excessivamente antigas.
     *
     * ### 1. O que faz
     * Incrementa quando um snapshot de last good é descartado por ter ultrapassado a idade máxima segura.
     *
     * ### 2. Para que serve
     * Assegura conformidade com o princípio: acima do teto de tolerância, a indisponibilidade visível
     * (`HTTP 503`) é preferível à entrega de dados gravemente obsoletos.
     *
     * ### 3. Como funciona
     * Disparada quando a idade da árvore guardada excede `maxFallbackAge`.
     */
    const val COMPOSE_FALLBACK_EXPIRED: String = "compose.fallback.expired"

    /**
     * Contagem de falhas na fase de seleção por inexistência de especificação compatível.
     *
     * ### 1. O que faz
     * Registra requisições de clientes para as quais nenhum spec atende à versão do app ou requisitos declarados.
     *
     * ### 2. Para que serve
     * Identifica descompassos entre lançamentos de versões de aplicativos móveis e especificações ativas no servidor.
     *
     * ### 3. Como funciona
     * Incrementada quando o algoritmo de targeting e compatibilidade não localiza candidatos válidos.
     */
    const val SELECT_NO_CANDIDATE: String = "select.no_candidate"

    /**
     * Contagem de seções omitidas na tela por incompatibilidade ou falha de hidratação.
     *
     * ### 1. O que faz
     * Contabiliza slots não portantes que foram graciosamente removidos da composição final.
     *
     * ### 2. Para que serve
     * Permite aferir a estabilidade de componentes e o impacto de degradação parcial na experiência da tela.
     *
     * ### 3. Como funciona
     * Incrementada pelo pipeline durante as etapas de filtragem por capabilities ou omissão por timeout de seção.
     */
    const val SECTION_OMITTED: String = "section.omitted"

    /**
     * Duração da hidratação individual de seções em milissegundos.
     *
     * ### 1. O que faz
     * Mede o tempo gasto na injeção de dados de negócio para cada seção da tela.
     *
     * ### 2. Para que serve
     * Identifica componentes ou projeções com lentidão no pipeline de montagem.
     *
     * ### 3. Como funciona
     * Cronometrada pelo coordenador de hidratação por seção e tagueada com o tipo de componente.
     */
    const val SECTION_HYDRATE: String = "section.hydrate.ms"

    /**
     * Duração do mapeamento do modelo de domínio para os DTOs do contrato.
     *
     * ### 1. O que faz
     * Mede o tempo despendido na transformação de `ComposedScreen` em `ScreenResponseEnvelope`.
     *
     * ### 2. Para que serve
     * Garante que o overhead computacional de mapeamento em memória permaneça residual (sub-milissegundo).
     *
     * ### 3. Como funciona
     * Registrada através de cronômetro de precisão na fase final do pipeline.
     */
    const val MAPPING: String = "mapping.ms"

    /**
     * Duração da serialização JSON do payload final.
     *
     * ### 1. O que faz
     * Mede o tempo necessário para serializar o envelope em bytes JSON via Jackson 3.
     *
     * ### 2. Para que serve
     * Monitora a eficiência da serialização direta em passo único no hot path.
     *
     * ### 3. Como funciona
     * Cronometrada antes do envio do array de bytes para o socket HTTP.
     */
    const val SERIALIZE: String = "serialize.ms"

    /**
     * Tamanho em bytes do payload de resposta entregue ao cliente móvel.
     *
     * ### 1. O que faz
     * Mensura o volume físico transferido na resposta HTTP.
     *
     * ### 2. Para que serve
     * Acompanha a evolução do peso das telas Server-Driven, alertando sobre inchaço desnecessário de props.
     *
     * ### 3. Como funciona
     * Registra o tamanho do array de bytes gerado pela serialização final.
     */
    const val PAYLOAD_BYTES: String = "payload.bytes"

    /**
     * Contagem de falhas ocorridas na comunicação com os repositórios de dados (*stores*).
     *
     * ### 1. O que faz
     * Registra erros e exceções disparadas durante operações contra os armazenamentos subjacentes.
     *
     * ### 2. Para que serve
     * Alerta sobre instabilidades na infraestrutura de persistência (MongoDB, Redis ou repositórios em memória).
     *
     * ### 3. Como funciona
     * Capturada nos blocos de proteção dos casos de uso ao invocar os stores.
     */
    const val STORE_FAILURE: String = "store.failure"

    /**
     * Contagem de falhas durante a tentativa de escrita no cache.
     *
     * ### 1. O que faz
     * Registra exceções ou recusas ao tentar salvar árvores de tela no cache.
     *
     * ### 2. Para que serve
     * Monitora a saúde do subsistema de cache sem interromper o fluxo de resposta da composição.
     *
     * ### 3. Como funciona
     * Incrementada no tratamento não bloqueante de persistência de cache.
     */
    const val CACHE_WRITE_FAILURE: String = "cache.write.failure"

    /**
     * Contagem de gravações de cache deliberadamente ignoradas.
     *
     * ### 1. O que faz
     * Registra composições que optaram por não atualizar o cache (ex.: árvores de fallback ou parciais).
     *
     * ### 2. Para que serve
     * Garante que telas incompletas ou defasadas não poluam as entradas canônicas de cache.
     *
     * ### 3. Como funciona
     * Incrementada quando a política de cache recusa armazenar composições marcadas como degradadas.
     */
    const val CACHE_WRITE_SKIPPED: String = "cache.write.skipped"

    /**
     * Duração das operações individuais de consulta e gravação no cache.
     *
     * ### 1. O que faz
     * Cronometra o tempo de resposta do cache em milissegundos.
     *
     * ### 2. Para que serve
     * Diagnostica a latência de acesso aos mecanismos de cache em memória ou distribuídos.
     *
     * ### 3. Como funciona
     * Medida em torno das chamadas de leitura e escrita do `HydratedScreenCache`.
     */
    const val CACHE_OPERATION: String = "cache.operation.ms"

    /**
     * Contagem de invalidações de cache efetivamente aplicadas com sucesso.
     *
     * ### 1. O que faz
     * Registra o expurgo de entradas de cache após publicações ou reversões de telas.
     *
     * ### 2. Para que serve
     * Confirma a correta execução dos processos de limpeza de memória para atualização de conteúdo.
     *
     * ### 3. Como funciona
     * Incrementada pelo relay do outbox de invalidação ao confirmar a remoção da chave.
     */
    const val CACHE_INVALIDATION_APPLIED: String = "cache.invalidation.applied"

    /**
     * Contagem de falhas na execução de rotinas de invalidação de cache.
     *
     * ### 1. O que faz
     * Sinaliza quando a limpeza de uma entrada de cache não pôde ser concluída.
     *
     * ### 2. Para que serve
     * Alerta sobre o risco de clientes continuarem recebendo versões antigas de telas após publicações.
     *
     * ### 3. Como funciona
     * Incrementada caso ocorra erro no envio de comandos de invalidação para o cache.
     */
    const val CACHE_INVALIDATION_FAILED: String = "cache.invalidation.failed"

    /**
     * Quantidade de registros de invalidação de cache pendentes de processamento no outbox.
     *
     * ### 1. O que faz
     * Fornece o indicador de fila do mecanismo assíncrono de invalidação transacional.
     *
     * ### 2. Para que serve
     * Monitora atrasos (*lag*) entre a mutação do ponteiro e a limpeza dos caches nos nós da aplicação.
     *
     * ### 3. Como funciona
     * Reportada periodicamente com base no tamanho da lista pendente no outbox.
     */
    const val CACHE_INVALIDATION_PENDING: String = "cache.invalidation.pending"

    /**
     * Contagem de erros ocorridos em operações do plano administrativo.
     *
     * ### 1. O que faz
     * Registra falhas, conflitos e validações recusadas nas APIs de governança.
     *
     * ### 2. Para que serve
     * Monitora a estabilidade das ferramentas internas de autoria e publicação de telas.
     *
     * ### 3. Como funciona
     * Incrementada pelos handlers de erro administrativos com tags descrevendo a operação.
     */
    const val ADMIN_ERROR: String = "admin.error"

    /**
     * Contagem de inserções e atualizações de componentes no catálogo.
     *
     * ### 1. O que faz
     * Registra a adição ou modificação de definições de componentes no catálogo.
     *
     * ### 2. Para que serve
     * Fornece métricas de atividade de evolução do Design System no servidor.
     *
     * ### 3. Como funciona
     * Incrementada com sucesso ao final da execução de `DraftUseCase.upsertComponent`.
     */
    const val ADMIN_CATALOG_UPSERT: String = "admin.catalog.upsert"

    /**
     * Contagem de gravações de esqueletos no repositório de layout.
     *
     * ### 1. O que faz
     * Registra a criação ou edição de estruturas de esqueleto no painel de administração.
     *
     * ### 2. Para que serve
     * Acompanha a frequência de mudanças estruturais nas fundações das telas.
     *
     * ### 3. Como funciona
     * Incrementada ao salvar um esqueleto em `DraftUseCase.createSkeletonDraft`.
     */
    const val ADMIN_SKELETON_UPSERT: String = "admin.skeleton.upsert"

    /**
     * Contagem de rascunhos de especificação criados ou modificados.
     *
     * ### 1. O que faz
     * Registra a atividade de autoria de novas revisões de especificações de tela.
     *
     * ### 2. Para que serve
     * Mede a produtividade e intensidade de modificações realizadas pelas equipes editoriais.
     *
     * ### 3. Como funciona
     * Incrementada ao persistir um rascunho em `DraftUseCase.createSpecDraft`.
     */
    const val ADMIN_SPEC_DRAFT: String = "admin.spec.draft"

    /**
     * Contagem de pedidos de publicação formalmente abertos pelo autor (*maker*).
     *
     * ### 1. O que faz
     * Registra a submissão de pedidos de publicação de telas para revisão.
     *
     * ### 2. Para que serve
     * Acompanha o volume de propostas submetidas ao funil de aprovação.
     *
     * ### 3. Como funciona
     * Disparada ao término bem-sucedido de `PublishUseCase.open`.
     */
    const val ADMIN_PUBLISH_OPEN: String = "admin.publish.open"

    /**
     * Contagem de pedidos de publicação aprovados pelo revisor (*checker*).
     *
     * ### 1. O que faz
     * Registra a validação humana positiva e a efetivação de uma nova tela em produção.
     *
     * ### 2. Para que serve
     * Mede a taxa de aprovação de releases de telas e a cadência de entrega.
     *
     * ### 3. Como funciona
     * Disparada no commit da transação de aprovação em `PublishUseCase.approve`.
     */
    const val ADMIN_PUBLISH_APPROVED: String = "admin.publish.approved"

    /**
     * Contagem de pedidos de publicação rejeitados na etapa de revisão.
     *
     * ### 1. O que faz
     * Registra as solicitações de publicação que foram barradas pelo checker com justificativa.
     *
     * ### 2. Para que serve
     * Identifica problemas de conformidade ou necessidade de alinhamento prévio entre autores e revisores.
     *
     * ### 3. Como funciona
     * Disparada ao executar `PublishUseCase.reject`.
     */
    const val ADMIN_PUBLISH_REJECTED: String = "admin.publish.rejected"

    /**
     * Contagem de operações de reversão de emergência (*rollback*) executadas.
     *
     * ### 1. O que faz
     * Registra o retorno a versões anteriores de telas nos canais de publicação.
     *
     * ### 2. Para que serve
     * Métrica crítica de confiabilidade e indicador de qualidade de releases em produção.
     *
     * ### 3. Como funciona
     * Incrementada após o reposicionamento do ponteiro em `RollbackPointerUseCase.rollback`.
     */
    const val ADMIN_ROLLBACK: String = "admin.rollback"

    /**
     * Contagem de consultas efetuadas à trilha de auditoria administrativa.
     *
     * ### 1. O que faz
     * Registra acessos de operadores aos relatórios históricos de ações de governança.
     *
     * ### 2. Para que serve
     * Fornece meta-auditoria, registrando quando e com que frequência a trilha é inspecionada.
     *
     * ### 3. Como funciona
     * Incrementada ao executar `AuditQueryUseCase.recent`.
     */
    const val ADMIN_AUDIT_LIST: String = "admin.audit.list"

    /**
     * Contagem de erros inesperados e não tratados no servidor (`HTTP 500`).
     *
     * ### 1. O que faz
     * Contabiliza exceções não mapeadas interceptadas pelo tratador global de erros.
     *
     * ### 2. Para que serve
     * Alerta imediato de bugs, quebras contratuais imprevistas ou falhas graves de runtime.
     *
     * ### 3. Como funciona
     * Disparada pelos filtros e controladores HTTP ao capturar exceções genéricas.
     */
    const val SERVER_UNEXPECTED_ERROR: String = "server.unexpected_error"

    /**
     * Número de chaves atualmente residentes na memória do limitador de taxa.
     *
     * ### 1. O que faz
     * Informa a quantidade de entradas ativas rastreadas pelo token bucket.
     *
     * ### 2. Para que serve
     * Monitora a ocupação de memória do limitador e garante a eficácia das rotinas de poda periódica.
     *
     * ### 3. Como funciona
     * Medida como gauge a partir do tamanho do mapa interno do rate limiter.
     */
    const val RATE_LIMITER_RESIDENT_KEYS: String = "rate_limiter.resident_keys"

    /**
     * Número de permissões atualmente disponíveis no bulkhead de concorrência.
     *
     * ### 1. O que faz
     * Reflete a capacidade ociosa de requisições de composição simultâneas no processo.
     *
     * ### 2. Para que serve
     * Monitora a pressão instantânea de concorrência e o risco iminente de saturação e shedding.
     *
     * ### 3. Como funciona
     * Reportada via gauge correspondente a `availablePermits()` do semáforo do bulkhead.
     */
    const val BULKHEAD_AVAILABLE_PERMITS: String = "compose.bulkhead.available_permits"

    /**
     * Conjunto completo e consolidado de todos os nomes de métricas oficiais suportados.
     *
     * ### 1. O que faz
     * Reúne todas as constantes de métrica em um conjunto imutável de strings.
     *
     * ### 2. Para que serve
     * Permite validação programática de conformidade e testes automatizados de presença de métricas.
     *
     * ### 3. Como funciona
     * Inicializado estaticamente com todas as constantes declaradas no objeto.
     */
    val ALL: Set<String> = setOf(
        COMPOSE_DURATION, COMPOSE_HIT, COMPOSE_MISS, COMPOSE_RATE_LIMITED, COMPOSE_BULKHEAD_REJECTED,
        COMPOSE_DEADLINE_EXCEEDED, COMPOSE_SINGLEFLIGHT_WAIT, COMPOSE_SINGLEFLIGHT_RECHECK_HIT,
        COMPOSE_UNAVAILABLE, COMPOSE_FALLBACK, COMPOSE_FALLBACK_AGE, COMPOSE_FALLBACK_EXPIRED,
        SELECT_NO_CANDIDATE, SECTION_OMITTED, SECTION_HYDRATE, MAPPING, SERIALIZE, PAYLOAD_BYTES,
        STORE_FAILURE, CACHE_WRITE_FAILURE, CACHE_WRITE_SKIPPED, CACHE_OPERATION,
        CACHE_INVALIDATION_APPLIED, CACHE_INVALIDATION_FAILED, CACHE_INVALIDATION_PENDING,
        ADMIN_ERROR, ADMIN_CATALOG_UPSERT, ADMIN_SKELETON_UPSERT,
        ADMIN_SPEC_DRAFT, ADMIN_PUBLISH_OPEN, ADMIN_PUBLISH_APPROVED, ADMIN_PUBLISH_REJECTED,
        ADMIN_ROLLBACK, ADMIN_AUDIT_LIST, SERVER_UNEXPECTED_ERROR, RATE_LIMITER_RESIDENT_KEYS,
        BULKHEAD_AVAILABLE_PERMITS,
    )

    /**
     * Conjunto de prefixos primários das métricas do serviço para blindagem de cardinalidade.
     *
     * ### 1. O que faz
     * Extrai o domínio de primeiro nível de cada métrica (ex.: `compose`, `section`, `admin`).
     *
     * ### 2. Para que serve
     * Utilizado pela camada adaptadora do Micrometer para filtrar e proteger o registry contra métricas estranhas.
     *
     * ### 3. Como funciona
     * Derivado dinamicamente recortando o texto antes do primeiro ponto (`.`) em cada nome de métrica.
     */
    val PREFIXES: Set<String> = ALL.map { it.substringBefore('.') }.toSet()
}

/**
 * Utilitário de governança e montagem de tags de dimensões para métricas de telemetria.
 *
 * ### 1. O que faz
 * Sanitiza e formata os pares chave-valor de tags anexadas às métricas do pipeline.
 *
 * ### 2. Para que serve
 * Impede explosão de cardinalidade nas séries temporais do Prometheus. Garante que variáveis
 * dinâmicas com cardinalidade aberta (ex.: versões completas do app, números de build arbitrários
 * ou versões de schema desconhecidas) nunca entrem como tags de métricas. Em vez disso, agrupa
 * valores desconhecidos sob categorias controladas como [OTHER] e direciona identificadores granulares para logs.
 *
 * ### 3. Como funciona
 * Valida os atributos de requisição contra listas de valores permitidos antes de compor o mapa de tags.
 */
object MetricTags {
    /**
     * Rótulo de reserva para versões ou atributos não reconhecidos pelo catálogo do servidor.
     *
     * ### 1. O que faz
     * Atua como categoria agregadora para valores não conformes.
     *
     * ### 2. Para que serve
     * Absorve requisições com dados anômalos sem criar uma nova série temporal de métrica para cada valor arbitrário.
     *
     * ### 3. Como funciona
     * Literal estático `"other"`.
     */
    const val OTHER: String = "other"

    /**
     * Sanitiza a versão do schema de UI requisitada pelo cliente para uso seguro em tags.
     *
     * ### 1. O que faz
     * Confere se a versão solicitada consta na lista de schemas oficialmente suportados pelo catálogo.
     *
     * ### 2. Para que serve
     * Protege o registry de métricas contra clientes maliciosos ou desatualizados que enviem schemas inexistentes.
     *
     * ### 3. Como funciona
     * Retorna a própria versão se presente em [MvpCatalog.SUPPORTED_SCHEMA_VERSIONS], ou [OTHER] caso contrário.
     */
    fun schema(requested: String): String = if (requested in MvpCatalog.SUPPORTED_SCHEMA_VERSIONS) requested else OTHER

    /**
     * Monta o conjunto padrão de tags de contexto para métricas do fluxo de composição.
     *
     * ### 1. O que faz
     * Cria um mapa com as dimensões controladas da requisição de composição.
     *
     * ### 2. Para que serve
     * Permite fatiar e analisar a telemetria por tela, plataforma móvel e versão de schema de forma segura.
     *
     * ### 3. Como funciona
     * Extrai a identificação da [surface], o formato textual seguro da plataforma via `platform.wire()`
     * e a versão normalizada do schema via [schema].
     */
    fun compose(surface: SurfaceDefinition, context: ClientContext): Map<String, String> = mapOf(
        "surface" to surface.id,
        "platform" to context.platform.wire(),
        "schemaVersion" to schema(context.schemaVersion),
    )
}
