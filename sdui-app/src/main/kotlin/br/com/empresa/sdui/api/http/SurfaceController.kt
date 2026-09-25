package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.api.mapping.ScreenResponseMapper
import br.com.empresa.sdui.api.trace.ComposeTraceContext
import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.core.model.ComposedScreen
import br.com.empresa.sdui.core.model.NegotiateHeaders
import br.com.empresa.sdui.core.model.SurfaceDefinition
import br.com.empresa.sdui.core.model.Surfaces
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeRequest
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeResult
import br.com.empresa.sdui.orchestrator.port.inbound.ComposeScreenUseCase
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricTags
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * Controlador REST de borda para composicao e entrega de surfaces de UI do BFF Server-Driven UI.
 *
 * ### 1. O que faz
 * Atua como a porta de entrada HTTP de leitura do BFF SDUI, expondo endpoints estritos para as
 * surfaces homologadas na allowlist ([Surfaces.HOME] e [Surfaces.CATALOG]), convertendo os headers
 * de protocolo em requisicoes de composicao e serializando as respostas finais para consumo por
 * clientes moveis iOS e Android.
 *
 * ### 2. Para que serve
 * Serve como o ponto de contato unico e padronizado entre os aplicativos clientes nativos e o
 * orquestrador central do backend SDUI. Garante isolamento estrito de protocolos, mediacao de
 * compatibilidade SemVer, suporte a cache HTTP eficiente (via headers de validacao condicional) e
 * protecao de latencia no caminho critico (hot path) sem acoplamento a modelos internos do orquestrador.
 *
 * ### 3. Como funciona
 * - **Roteamento Explicito:** Utiliza mapeamentos literais por surface (`/v1/surfaces/home` e
 *   `/v1/surfaces/catalog`) sob versionamento HTTP `API-Version: 1`, rejeitando surfaces nao
 *   cadastradas diretamente no roteamento com HTTP 404 antes de acionar o pipeline.
 * - **Negociacao de Headers:** Extrai os cabecalhos de negociacao ([NegotiateHeaders]) sem binding
 *   obrigatorio de parâmetros, delegando a validacao de integridade para a fase de negociacao do
 *   caso de uso [ComposeScreenUseCase].
 * - **Validacao Condicional (ETag):** Encaminha o cabecalho `If-None-Match` recebido. Se o orquestrador
 *   detectar que a arvore atual possui a mesma ETag, retorna instantaneamente `304 Not Modified` com
 *   headers de cache, sem retransmitir o corpo JSON.
 * - **Serializacao de Passo Unico (Zero Re-Serialization):** Otimizacao inegociavel pos-review que
 *   serializa a arvore montada diretamente para um array de bytes (`ByteArray`) utilizando o [JsonMapper]
 *   Jackson 3 e devolve como `ResponseEntity<ByteArray>` com `MediaType.APPLICATION_JSON`, evitando que
 *   o Spring MVC execute uma segunda serializacao reflexiva no hot path.
 * - **Rastreabilidade e Metricas:** Inicializa o contexto [ComposeTraceContext] com dados tecnicos
 *   truncados (surface, plataforma, versao), registrando latencia discriminada por desfecho
 *   (hit, miss, fallback, not_modified, rate_limited, unavailable, error) no encerramento da chamada.
 *
 * @property compose Caso de uso orquestrador responsavel pelo ciclo de vida da composicao de tela.
 * @property mapper Mapeador responsavel pela traducao do modelo de dominio para o DTO contratual.
 * @property jsonMapper Serializador Jackson 3 configurado para a emissao direta de bytes JSON.
 * @property metrics Gravador de metricas operacionais Micrometer.
 * @property trace Contexto de rastreabilidade e correlacao atrelado a thread virtual da requisicao.
 */
@RestController
class SurfaceController(
    private val compose: ComposeScreenUseCase,
    private val mapper: ScreenResponseMapper,
    private val jsonMapper: JsonMapper,
    private val metrics: MetricsRecorder,
    private val trace: ComposeTraceContext,
) {
    /**
     * Endpoint HTTP GET dedicado a surface Home (`/v1/surfaces/home`).
     *
     * ### 1. O que faz
     * Recebe requisicoes de clientes moveis para a tela principal (Home) na versao de API 1.
     *
     * ### 2. Para que serve
     * Ponto de contato canonico de leitura da Home no MVP, roteando a chamada para a definicao [Surfaces.HOME].
     *
     * ### 3. Como funciona
     * Captura os cabecalhos brutos da requisicao via [HttpHeaders] e delega a execucao para o metodo
     * interno [serve], garantindo tratamento uniforme de negociacao, cache, rastreamento e metricas.
     *
     * @param headers Conjunto completo de cabecalhos HTTP recebidos na requisicao.
     * @return [ResponseEntity] contendo os bytes JSON da tela, status de erro ou `304 Not Modified`.
     */
    @GetMapping(path = ["/v1/surfaces/home"], version = "1")
    fun home(@RequestHeader headers: HttpHeaders): ResponseEntity<*> = serve(Surfaces.HOME, headers)

    /**
     * Endpoint HTTP GET dedicado a surface Catalog (`/v1/surfaces/catalog`).
     *
     * ### 1. O que faz
     * Recebe requisicoes de clientes moveis para a surface de catalogo na versao de API 1 (`ADR-020`).
     *
     * ### 2. Para que serve
     * Permite a apresentacao de vitrines e componentes de catalogo sob o mesmo ciclo de vida SDUI da Home.
     *
     * ### 3. Como funciona
     * Roteia a requisicao para a definicao [Surfaces.CATALOG], reutilizando o fluxo consolidado em [serve].
     *
     * @param headers Conjunto completo de cabecalhos HTTP recebidos na requisicao.
     * @return [ResponseEntity] contendo os bytes JSON da tela, status de erro ou `304 Not Modified`.
     */
    @GetMapping(path = ["/v1/surfaces/catalog"], version = "1")
    fun catalog(@RequestHeader headers: HttpHeaders): ResponseEntity<*> = serve(Surfaces.CATALOG, headers)

    /**
     * Metodo centralizador do ciclo de atendimento HTTP para surfaces.
     *
     * ### 1. O que faz
     * Orquestra a medicao de tempo, extracao de cabecalhos, invocacao do caso de uso e traducao do
     * resultado [ComposeResult] em uma resposta [ResponseEntity] HTTP correspondente.
     *
     * ### 2. Para que serve
     * Unifica a logica de transporte HTTP entre todas as surfaces conhecidas, garantindo que regras
     * de auditoria, tratamento de erros, cabecalhos de cache e instrumentacao de latencia sejam identicos.
     *
     * ### 3. Como funciona
     * 1. Marca o timestamp inicial em nanossegundos (`System.nanoTime`).
     * 2. Extrai os cabecalhos de negociacao via [negotiateHeaders] e abre o contexto no [ComposeTraceContext].
     * 3. Invoca [ComposeScreenUseCase.compose] com o [ComposeRequest] montado.
     * 4. Mapeia o resultado retornado:
     *    - [ComposeResult.InvalidHeaders]: Responde HTTP 400 Bad Request com [ApiErrorResponse].
     *    - [ComposeResult.RateLimited]: Responde HTTP 429 Too Many Requests com cabecalho `Retry-After` (com jitter calculado).
     *    - [ComposeResult.NotModified]: Responde HTTP 304 Not Modified com `ETag`, `Cache-Control` e `Vary`.
     *    - [ComposeResult.Unavailable]: Responde HTTP 503 Service Unavailable com `Retry-After` e motivo de indisponibilidade.
     *    - [ComposeResult.Success]: Delega para [ok] a geracao da resposta HTTP 200 com payload serializado em bytes.
     * 5. No bloco `finally`, registra a duracao da operacao no [MetricsRecorder] tagueada pelo desfecho real e fecha o [trace].
     *
     * @param surface Definicao da surface homologada a ser servida.
     * @param httpHeaders Cabecalhos HTTP brutos da requisicao.
     * @return [ResponseEntity] tipada com o codigo HTTP, headers de controle e corpo apropriado.
     */
    private fun serve(surface: SurfaceDefinition, httpHeaders: HttpHeaders): ResponseEntity<*> {
        val started = System.nanoTime()
        val headers = negotiateHeaders(httpHeaders)
        // Versao exata do app vai para o contexto de log, nunca para tag de metrica. Os valores
        // ainda nao foram validados pelo Negotiate: entram truncados.
        trace.open(
            mapOf(
                "surface" to surface.id,
                "platform" to (headers.clientPlatform?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
                "schemaVersion" to (headers.uiSchemaVersion?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
                "appVersion" to (headers.clientVersion?.take(MAX_LOGGED_HEADER_LENGTH) ?: ""),
            ),
        )
        // O desfecho comeca como erro e so e substituido depois que a composicao devolve: se algo
        // escapar como excecao, a amostra de tempo ainda sai, marcada pelo que de fato aconteceu.
        var outcome = OUTCOME_ERROR
        try {
            val result = compose.compose(
                ComposeRequest(
                    headers = headers,
                    ifNoneMatch = httpHeaders.joined(HttpHeaders.IF_NONE_MATCH),
                    surface = surface,
                ),
            )
            outcome = outcomeOf(result)
            return when (result) {
                is ComposeResult.InvalidHeaders -> ResponseEntity.badRequest().body(
                    ApiErrorResponse(
                        code = "INVALID_HEADERS",
                        message = "headers de negociacao invalidos",
                        details = result.violations.map { "${it.header}:${it.reason}" },
                    ),
                )

                // O `Retry-After` vem do caso de uso ja com jitter. Um valor constante aqui
                // mandaria a coorte inteira voltar no mesmo segundo e repetir o pico que causou a
                // recusa — a chave do limitador e plataforma e build, nao aparelho.
                is ComposeResult.RateLimited -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", result.retryAfterSeconds.toString())
                    .body(ApiErrorResponse("RATE_LIMITED", "rate limit excedido"))

                is ComposeResult.NotModified -> ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header("ETag", result.etag)
                    .header("Cache-Control", CACHE_CONTROL)
                    .header("Vary", VARY)
                    .build<Void>()

                is ComposeResult.Unavailable -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", result.retryAfterSeconds.toString())
                    .body(
                        ApiErrorResponse(
                            code = "COMPOSE_UNAVAILABLE",
                            message = "${surface.id} indisponivel",
                            details = listOf(result.reason.wire),
                        ),
                    )

                is ComposeResult.Success -> ok(result.screen)
            }
        } finally {
            metrics.recordNanos(
                MetricNames.COMPOSE_DURATION,
                System.nanoTime() - started,
                mapOf("surface" to surface.id, "outcome" to outcome),
            )
            trace.close()
        }
    }

    /**
     * Converte uma arvore de tela composta com sucesso em uma resposta HTTP 200 OK com corpo em bytes.
     *
     * ### 1. O que faz
     * Mapeia o modelo de dominio [ComposedScreen] para o DTO de resposta e o serializa diretamente para bytes JSON.
     *
     * ### 2. Para que serve
     * Elimina a duplicidade de serializacao no hot path do Spring MVC, garantindo maxima vazao e minima
     * alocacao de memoria intermediaria sob alta carga com Virtual Threads.
     *
     * ### 3. Como funciona
     * - Coleta tags de telemetria baseadas na versao do schema, surface e plataforma do cliente.
     * - Executa e cronometra o mapeamento do dominio para o DTO via [ScreenResponseMapper.toResponse].
     * - Executa e cronometra a serializacao para [ByteArray] via [JsonMapper.writeValueAsBytes].
     * - Registra metricas segregadas de tempo de mapeamento, tempo de serializacao e tamanho do payload em bytes.
     * - Retorna [ResponseEntity.ok] configurada com `ETag`, `Cache-Control: private, max-age=60`, `Vary` declarando
     *   todos os cabecalhos variantes e `Content-Type: application/json`.
     *
     * @param screen Arvore de tela composta com sucesso pelo pipeline do orquestrador.
     * @return [ResponseEntity] contendo o array [ByteArray] com o JSON serializado e cabecalhos de cache.
     */
    private fun ok(screen: ComposedScreen): ResponseEntity<ByteArray> {
        val metricTags = mapOf(
            "schemaVersion" to MetricTags.schema(screen.schemaVersion),
            "surface" to screen.surface,
            "platform" to screen.platform.wire(),
        )
        val mappingStarted = System.nanoTime()
        val body = mapper.toResponse(screen)
        val serializeStarted = System.nanoTime()
        metrics.recordNanos(MetricNames.MAPPING, serializeStarted - mappingStarted, metricTags)
        val json = jsonMapper.writeValueAsBytes(body)
        metrics.recordNanos(MetricNames.SERIALIZE, System.nanoTime() - serializeStarted, metricTags)
        metrics.recordBytes(MetricNames.PAYLOAD_BYTES, json.size.toLong(), metricTags)
        return ResponseEntity.ok()
            .header("ETag", screen.etag)
            .header("Cache-Control", CACHE_CONTROL)
            .header("Vary", VARY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
    }

    /**
     * Classifica o resultado da composicao para emissao discriminada na tag de metrica.
     *
     * ### 1. O que faz
     * Traduz uma instancia de [ComposeResult] em uma string representativa do desfecho operacional.
     *
     * ### 2. Para que serve
     * Segrega os histogramas de latencia no Micrometer, permitindo diferenciar a performance de acertos de
     * cache (hit), resolucoes normais (miss), respostas degradadas (fallback) e erros/bloqueios.
     *
     * ### 3. Como funciona
     * Avalia o tipo concreto de [result] e propriedades internas:
     * - Sucesso com flag de fallback: retorna `fallback`.
     * - Sucesso originado do cache: retorna `hit`.
     * - Sucesso de composicao nova: retorna `miss`.
     * - Demais casos retornam constantes literais estaveis: `not_modified`, `invalid_headers`, `rate_limited` ou `unavailable`.
     *
     * @param result Resultado retornado pela execucao de composicao da tela.
     * @return String identificadora do desfecho para rotulacao de metricas.
     */
    private fun outcomeOf(result: ComposeResult): String = when (result) {
        is ComposeResult.Success -> when {
            result.screen.fallback -> OUTCOME_FALLBACK
            result.fromCache -> OUTCOME_HIT
            else -> OUTCOME_MISS
        }

        is ComposeResult.NotModified -> OUTCOME_NOT_MODIFIED
        is ComposeResult.InvalidHeaders -> OUTCOME_INVALID_HEADERS
        is ComposeResult.RateLimited -> OUTCOME_RATE_LIMITED
        is ComposeResult.Unavailable -> OUTCOME_UNAVAILABLE
    }

    /**
     * Constantes de protocolo e funcoes utilitarias estaticas do controlador HTTP de surfaces.
     *
     * ### 1. O que faz
     * Centraliza strings literais de cabecalhos HTTP, identificadores de desfecho de telemetria e
     * funcoes auxiliares de extracao de cabecalhos.
     *
     * ### 2. Para que serve
     * Evita alocacao dinamica de strings repetitivas no hot path e padroniza a interpretacao de headers
     * recebidos das requisicoes clientes.
     *
     * ### 3. Como funciona
     * Define constantes estaticas para controle de cache (`Cache-Control`, `Vary`), limites de log e
     * rotulos de telemetria, alem de helpers de extensao sobre [HttpHeaders].
     */
    private companion object {
        /** Tamanho maximo de caracteres de cabecalhos inseridos no contexto de trace de log. */
        const val MAX_LOGGED_HEADER_LENGTH: Int = 32

        /** Diretiva de controle de cache privado emitida nas respostas com sucesso ou 304. */
        const val CACHE_CONTROL: String = "private, max-age=60"

        /** Declaracao de cabecalhos dos quais o conteudo da resposta varia em caches intermediarios. */
        const val VARY: String =
            "API-Version, UI-Schema-Version, Client-Platform, Client-Version, Client-Build, Component-Capabilities"

        /** Rotulo de telemetria para resposta servida do cache de arvores hidratadas. */
        const val OUTCOME_HIT: String = "hit"

        /** Rotulo de telemetria para resposta composta a frio a partir dos stores. */
        const val OUTCOME_MISS: String = "miss"

        /** Rotulo de telemetria para resposta servida a partir da escada de fallback (last good). */
        const val OUTCOME_FALLBACK: String = "fallback"

        /** Rotulo de telemetria para resposta HTTP 304 Not Modified validada via ETag. */
        const val OUTCOME_NOT_MODIFIED: String = "not_modified"

        /** Rotulo de telemetria para requisicao rejeitada por headers invalidos na negociacao. */
        const val OUTCOME_INVALID_HEADERS: String = "invalid_headers"

        /** Rotulo de telemetria para requisicao bloqueada por rate limiting (HTTP 429). */
        const val OUTCOME_RATE_LIMITED: String = "rate_limited"

        /** Rotulo de telemetria para requisicao degradada para indisponibilidade (HTTP 503). */
        const val OUTCOME_UNAVAILABLE: String = "unavailable"

        /** Rotulo de telemetria padrao inicial caso ocorra excecao inesperada durante a composicao. */
        const val OUTCOME_ERROR: String = "error"

        /**
         * Extrai e encapsula todos os cabecalhos de negociacao HTTP em um objeto [NegotiateHeaders].
         *
         * ### 1. O que faz
         * Le os cabecalhos HTTP pertinentes da requisicao e os agrega na estrutura tipada [NegotiateHeaders].
         *
         * ### 2. Para que serve
         * Desacopla a camada de dominio do ecossistema de transporte do Spring MVC ([HttpHeaders]),
         * preparando os dados brutos para posterior validacao de compatibilidade no pipeline SDUI.
         *
         * ### 3. Como funciona
         * Utiliza o helper [joined] para capturar cada cabecalho padronizado (`UI-Schema-Version`, `Client-Platform`,
         * `Client-Version`, `Client-Build`, `Accept-Language`, `API-Version`, `OS-Version`, `Component-Capabilities`,
         * `SDUI-Channel`), unificando possiveis valores repetidos com virgula.
         *
         * @param headers Cabecalhos HTTP originais da requisicao.
         * @return Instancia preenchida de [NegotiateHeaders] pronta para processamento.
         */
        fun negotiateHeaders(headers: HttpHeaders): NegotiateHeaders = NegotiateHeaders(
            uiSchemaVersion = headers.joined("UI-Schema-Version"),
            clientPlatform = headers.joined("Client-Platform"),
            clientVersion = headers.joined("Client-Version"),
            clientBuild = headers.joined("Client-Build"),
            acceptLanguage = headers.joined(HttpHeaders.ACCEPT_LANGUAGE),
            apiVersion = headers.joined("API-Version"),
            osVersion = headers.joined("OS-Version"),
            componentCapabilities = headers.joined("Component-Capabilities"),
            channel = headers.joined("SDUI-Channel"),
        )

        /**
         * Extrai os valores associados a um cabecalho HTTP, concatenando entradas multiplas por virgula.
         *
         * ### 1. O que faz
         * Obtem a lista de valores de um cabecalho em [HttpHeaders] e a une em uma unica string separada por virgula.
         *
         * ### 2. Para que serve
         * Preserva a semantica padrao de parsing HTTP do Spring MVC para headers repetidos, garantindo
         * que listas de capacidades ou identificadores sejam capturadas sem perdas.
         *
         * ### 3. Como funciona
         * Consulta `HttpHeaders.get(name)` e aplica `joinToString(",")` caso a lista exista; retorna `null` se ausente.
         *
         * @param name Nome do cabecalho HTTP a ser consultado.
         * @return String contendo os valores unidos por virgula, ou `null` se o cabecalho nao foi enviado.
         */
        fun HttpHeaders.joined(name: String): String? = get(name)?.joinToString(",")
    }
}
