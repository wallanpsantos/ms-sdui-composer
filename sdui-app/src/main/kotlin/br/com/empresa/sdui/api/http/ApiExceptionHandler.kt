package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.orchestrator.port.inbound.AdminConflict
import br.com.empresa.sdui.orchestrator.port.inbound.AdminDenied
import br.com.empresa.sdui.orchestrator.port.inbound.AdminIdempotencyMismatch
import br.com.empresa.sdui.orchestrator.port.inbound.AdminInFlight
import br.com.empresa.sdui.orchestrator.port.inbound.AdminNotFound
import br.com.empresa.sdui.orchestrator.port.inbound.AdminUnavailable
import br.com.empresa.sdui.orchestrator.port.inbound.AdminValidation
import br.com.empresa.sdui.orchestrator.port.outbound.MetricNames
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
import br.com.empresa.sdui.orchestrator.port.outbound.StoreConflict
import br.com.empresa.sdui.orchestrator.port.outbound.StoreRejected
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Tratador global de excecoes HTTP da camada de apresentacao e governanca.
 *
 * ### 1. O que faz
 * Intercepta excecoes de negocio e persistencia lancadas pelas camadas de orquestracao e dominio,
 * convertendo-as em respostas HTTP estruturadas utilizando o contrato padronizado [ApiErrorResponse]
 * (em conformidade conceitual com o RFC 7807).
 *
 * ### 2. Para que serve
 * Isola as camadas internas do servico (que nao conhecem o protocolo HTTP) da borda REST, assegurando
 * que mensagens internas, detalhes de infraestrutura ou stack traces nunca vazem para clientes externos.
 * Padroniza os codigos de status HTTP (400, 403, 404, 409, 422, 503, 500) e rotula metricas operacionais.
 *
 * ### 3. Como funciona
 * - **Mapeamento Declarativo:** Utiliza anotacoes [ExceptionHandler] do Spring MVC para capturar tipos
 *   especificos de excecao disparados durante o processamento da requisicao.
 * - **Concorrencia e Idempotencia:** Diferencia claramente conflitos de versao ([AdminConflict], [StoreConflict]),
 *   reutilizacao indevida de chave ([AdminIdempotencyMismatch]) e operacoes concorrentes em andamento ([AdminInFlight]).
 * - **Rede de Seguranca Global:** Captura qualquer excecao generica inesperada ([Exception]), logando o erro
 *   com rastreabilidade e emitindo HTTP 500 com corpo opaco `INTERNAL_ERROR`.
 * - **Telemetria de Erros:** Todo tratamento emite contadores de erro no Micrometer atraves do metodo [adminError].
 *
 * @property metrics Gravador de metricas operacionais Micrometer.
 */
@RestControllerAdvice
class ApiExceptionHandler(
    private val metrics: MetricsRecorder,
) : ResponseEntityExceptionHandler() {

    /**
     * Trata violacoes de autorizacao e permissoes administrativas.
     *
     * ### 1. O que faz
     * Converte [AdminDenied] em uma resposta HTTP 403 Forbidden.
     *
     * ### 2. Para que serve
     * Sinaliza que o operador nao possui permissao ou nao enviou cabecalhos de identidade obrigatorios.
     *
     * ### 3. Como funciona
     * Emite metrica rotulada com `denied` e devolve [ApiErrorResponse] com codigo `FORBIDDEN`.
     *
     * @param ex Excecao capturada de permissao negada.
     * @return [ResponseEntity] com status HTTP 403.
     */
    @ExceptionHandler(AdminDenied::class)
    fun denied(ex: AdminDenied): ResponseEntity<ApiErrorResponse> =
        adminError("denied", HttpStatus.FORBIDDEN, ApiErrorResponse("FORBIDDEN", ex.message ?: "forbidden"))

    /**
     * Trata conflitos de estado de governanca administrativa.
     *
     * ### 1. O que faz
     * Converte [AdminConflict] em uma resposta HTTP 409 Conflict.
     *
     * ### 2. Para que serve
     * Indica conflitos de transicao de estado (ex: tentar aprovar um pedido de publicacao ja encerrado).
     *
     * ### 3. Como funciona
     * Incrementa a metrica de erro sob a tag `conflict` e responde HTTP 409 com mensagem descritiva.
     *
     * @param ex Excecao de conflito de negocio.
     * @return [ResponseEntity] com status HTTP 409.
     */
    @ExceptionHandler(AdminConflict::class)
    fun conflict(ex: AdminConflict): ResponseEntity<ApiErrorResponse> =
        adminError("conflict", HttpStatus.CONFLICT, ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))

    /**
     * Trata conflitos de escrita concorrente e compare-and-set nos stores de persistencia.
     *
     * ### 1. O que faz
     * Converte [StoreConflict] em uma resposta HTTP 409 Conflict.
     *
     * ### 2. Para que serve
     * Informa ao operador que uma alteracao concorrente moveu o ponteiro ou atualizou o documento
     * no banco de dados antes da conclusao da sua transacao.
     *
     * ### 3. Como funciona
     * Responde HTTP 409 permitindo que o cliente administrativo recarregue o estado mais recente e decida
     * se reaplica a operacao, sem que o servidor tente retries automaticos arriscados.
     *
     * @param ex Excecao de conflito de armazenamento disparada pelo adapter.
     * @return [ResponseEntity] com status HTTP 409.
     */
    @ExceptionHandler(StoreConflict::class)
    fun storeConflict(ex: StoreConflict): ResponseEntity<ApiErrorResponse> =
        adminError("conflict", HttpStatus.CONFLICT, ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))

    /**
     * Trata rejeicoes de armazenamento por violacao de capacidade ou integridade.
     *
     * ### 1. O que faz
     * Converte [StoreRejected] em uma resposta HTTP 400 Bad Request.
     *
     * ### 2. Para que serve
     * Informa que o documento enviado excede limites fisicos ou regras estruturais do store.
     *
     * ### 3. Como funciona
     * Encapsula a mensagem da excecao nos detalhes da resposta com codigo `VALIDATION`.
     *
     * @param ex Excecao de rejeicao de persistencia.
     * @return [ResponseEntity] com status HTTP 400.
     */
    @ExceptionHandler(StoreRejected::class)
    fun storeRejected(ex: StoreRejected): ResponseEntity<ApiErrorResponse> =
        adminError(
            "validation",
            HttpStatus.BAD_REQUEST,
            ApiErrorResponse("VALIDATION", "rascunho invalido", listOf(ex.message ?: "recusado pelo armazenamento")),
        )

    /**
     * Trata o reuso indevido de chave de idempotencia com parâmetros divergentes.
     *
     * ### 1. O que faz
     * Converte [AdminIdempotencyMismatch] em HTTP 422 Unprocessable Content.
     *
     * ### 2. Para que serve
     * Protege contra reaproveitamento acidental ou malicioso de uma mesma chave de idempotencia para operacoes distintas.
     *
     * ### 3. Como funciona
     * Retorna HTTP 422 com codigo `IDEMPOTENCY_KEY_REUSED`, orientando o chamador a utilizar uma nova chave UUID.
     *
     * @param ex Excecao de divergencia de idempotencia.
     * @return [ResponseEntity] com status HTTP 422.
     */
    @ExceptionHandler(AdminIdempotencyMismatch::class)
    fun idempotencyMismatch(ex: AdminIdempotencyMismatch): ResponseEntity<ApiErrorResponse> =
        adminError(
            "idempotency_mismatch",
            HttpStatus.UNPROCESSABLE_CONTENT,
            ApiErrorResponse("IDEMPOTENCY_KEY_REUSED", ex.message ?: "chave de idempotencia reusada"),
        )

    /**
     * Trata a falta de capacidade operacional temporaria no plano administrativo.
     *
     * ### 1. O que faz
     * Converte [AdminUnavailable] em HTTP 503 Service Unavailable acompanhado do cabecalho `Retry-After`.
     *
     * ### 2. Para que serve
     * Preserva reservas ativas e integridade do servico quando limites operacionais temporarios sao atingidos.
     *
     * ### 3. Como funciona
     * Calcula um tempo de retry com jitter aleatorio via [adminRetryAfterSeconds] e anexa o cabecalho `Retry-After`.
     *
     * @param ex Excecao de indisponibilidade administrativa.
     * @return [ResponseEntity] com status HTTP 503 e cabecalho `Retry-After`.
     */
    @ExceptionHandler(AdminUnavailable::class)
    fun unavailable(ex: AdminUnavailable): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to "unavailable"))
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", adminRetryAfterSeconds())
            .body(ApiErrorResponse("ADMIN_UNAVAILABLE", ex.message ?: "indisponivel"))
    }

    /**
     * Trata tentativas de submissao concorrente com a mesma chave de idempotencia em processamento.
     *
     * ### 1. O que faz
     * Converte [AdminInFlight] em HTTP 409 Conflict com codigo especializado `IDEMPOTENT_IN_FLIGHT`.
     *
     * ### 2. Para que serve
     * Notifica o operador de que a requisicao anterior ainda esta em execucao, orientando-o a aguardar.
     *
     * ### 3. Como funciona
     * Distingue um conflito de alteracao simultânea de um retry prematuro da mesma chamada.
     *
     * @param ex Excecao de operacao idempotente em voo.
     * @return [ResponseEntity] com status HTTP 409.
     */
    @ExceptionHandler(AdminInFlight::class)
    fun inFlight(ex: AdminInFlight): ResponseEntity<ApiErrorResponse> =
        adminError(
            "in_flight",
            HttpStatus.CONFLICT,
            ApiErrorResponse("IDEMPOTENT_IN_FLIGHT", ex.message ?: "operacao em voo"),
        )

    /**
     * Trata falhas de validacao de entrada administrativa.
     *
     * ### 1. O que faz
     * Converte [AdminValidation] em HTTP 400 Bad Request contendo a lista detalhada de violacoes.
     *
     * ### 2. Para que serve
     * Fornece feedback claro e estruturado para o cliente sobre parâmetros, campos ou corpos invalidos.
     *
     * ### 3. Como funciona
     * Mapeia a lista [AdminValidation.errors] no campo `details` do [ApiErrorResponse].
     *
     * @param ex Excecao de validacao contendo a relacao de inconsistencias.
     * @return [ResponseEntity] com status HTTP 400.
     */
    @ExceptionHandler(AdminValidation::class)
    fun validation(ex: AdminValidation): ResponseEntity<ApiErrorResponse> =
        adminError(
            "validation",
            HttpStatus.BAD_REQUEST,
            ApiErrorResponse("VALIDATION", "rascunho invalido", ex.errors),
        )

    /**
     * Trata situacoes em que uma entidade solicitada nao foi encontrada.
     *
     * ### 1. O que faz
     * Converte [AdminNotFound] em HTTP 404 Not Found.
     *
     * ### 2. Para que serve
     * Comunica a ausencia de um recurso buscado por ID, como specs, skeletons ou revisoes.
     *
     * ### 3. Como funciona
     * Retorna [ApiErrorResponse] com codigo `NOT_FOUND` e mensagem de identificacao do recurso ausente.
     *
     * @param ex Excecao de recurso nao encontrado.
     * @return [ResponseEntity] com status HTTP 404.
     */
    @ExceptionHandler(AdminNotFound::class)
    fun notFound(ex: AdminNotFound): ResponseEntity<ApiErrorResponse> =
        adminError("not_found", HttpStatus.NOT_FOUND, ApiErrorResponse("NOT_FOUND", ex.message ?: "not found"))

    /**
     * Rede de seguranca final para capturar excecoes inesperadas nao tratadas especificamente.
     *
     * ### 1. O que faz
     * Captura qualquer instancia de [Exception] e responde com HTTP 500 Internal Server Error padronizado.
     *
     * ### 2. Para que serve
     * Garante que falhas imprevistas nao exponham stack traces na resposta e mantenham o contrato estruturado da API.
     *
     * ### 3. Como funciona
     * Registra o erro detalhado nos logs do servidor com o logger SLF4J, incrementa a metrica
     * `sdui.server.unexpected.error` e devolve um envelope generico com codigo `INTERNAL_ERROR`.
     *
     * @param ex Excecao generica imprevista.
     * @return [ResponseEntity] com status HTTP 500.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.SERVER_UNEXPECTED_ERROR)
        logger.error("falha nao tratada ao atender a requisicao", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse("INTERNAL_ERROR", "erro interno"))
    }

    /**
     * Metodo auxiliar para registro de telemetria e construcao de respostas de erro administrativo.
     *
     * ### 1. O que faz
     * Incrementa o contador de erros administrativos rotulado pelo tipo de erro e monta o [ResponseEntity].
     *
     * ### 2. Para que serve
     * Elimina codigo boilerplate duplicado entre os diversos handlers de excecao.
     *
     * ### 3. Como funciona
     * Invoca `metrics.increment` com o nome [MetricNames.ADMIN_ERROR] e a tag correspondente, retornando
     * a resposta encapsulada com o status [status] e corpo [body].
     *
     * @param error Identificador de categoria do erro para metrica.
     * @param status Status HTTP a ser retornado.
     * @param body Envelope [ApiErrorResponse] a ser serializado.
     * @return Resposta HTTP montada.
     */
    private fun adminError(
        error: String,
        status: HttpStatus,
        body: ApiErrorResponse,
    ): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to error))
        return ResponseEntity.status(status).body(body)
    }
}
