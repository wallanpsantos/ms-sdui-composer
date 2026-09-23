package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.orchestrator.admin.AdminConflict
import br.com.empresa.sdui.orchestrator.admin.AdminDenied
import br.com.empresa.sdui.orchestrator.admin.AdminIdempotencyMismatch
import br.com.empresa.sdui.orchestrator.admin.AdminInFlight
import br.com.empresa.sdui.orchestrator.admin.AdminNotFound
import br.com.empresa.sdui.orchestrator.admin.AdminUnavailable
import br.com.empresa.sdui.orchestrator.admin.AdminValidation
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
 * Traduz as falhas de governanca ao status HTTP correspondente.
 *
 * Fica na borda para que o orchestrator possa sinalizar erro em vocabulario de dominio, sem
 * conhecer HTTP. O corpo e sempre ApiErrorResponse, o mesmo de toda a API.
 */
@RestControllerAdvice
class ApiExceptionHandler(
    private val metrics: MetricsRecorder,
) : ResponseEntityExceptionHandler() {
    @ExceptionHandler(AdminDenied::class)
    fun denied(ex: AdminDenied): ResponseEntity<ApiErrorResponse> =
        adminError("denied", HttpStatus.FORBIDDEN, ApiErrorResponse("FORBIDDEN", ex.message ?: "forbidden"))

    @ExceptionHandler(AdminConflict::class)
    fun conflict(ex: AdminConflict): ResponseEntity<ApiErrorResponse> =
        adminError("conflict", HttpStatus.CONFLICT, ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))

    /**
     * Compare-and-set perdido no store: outra publicacao ou rollback moveu o pointer, ou uma
     * transacao concorrente escreveu o mesmo documento. Mesmo 409 de conflito; o operador relê o
     * estado e decide, o servidor nao repete a escrita.
     */
    @ExceptionHandler(StoreConflict::class)
    fun storeConflict(ex: StoreConflict): ResponseEntity<ApiErrorResponse> =
        adminError("conflict", HttpStatus.CONFLICT, ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))

    /** Conteudo acima do teto de armazenamento: o autor precisa reduzir o documento. */
    @ExceptionHandler(StoreRejected::class)
    fun storeRejected(ex: StoreRejected): ResponseEntity<ApiErrorResponse> =
        adminError(
            "validation",
            HttpStatus.BAD_REQUEST,
            ApiErrorResponse("VALIDATION", "rascunho invalido", listOf(ex.message ?: "recusado pelo armazenamento")),
        )

    /** Chave de idempotencia reusada para outra operacao ou outro alvo: erro do chamador, nao replay. */
    @ExceptionHandler(AdminIdempotencyMismatch::class)
    fun idempotencyMismatch(ex: AdminIdempotencyMismatch): ResponseEntity<ApiErrorResponse> =
        adminError(
            "idempotency_mismatch",
            HttpStatus.UNPROCESSABLE_CONTENT,
            ApiErrorResponse("IDEMPOTENCY_KEY_REUSED", ex.message ?: "chave de idempotencia reusada"),
        )

    /**
     * Sem capacidade segura para admitir a operacao. 503 com `Retry-After`: a recusa preserva as
     * reservas vivas em vez de expulsar uma delas.
     */
    @ExceptionHandler(AdminUnavailable::class)
    fun unavailable(ex: AdminUnavailable): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to "unavailable"))
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", ADMIN_RETRY_AFTER_SECONDS)
            .body(ApiErrorResponse("ADMIN_UNAVAILABLE", ex.message ?: "indisponivel"))
    }

    /**
     * Chave de idempotencia em voo. Tambem 409, mas com codigo proprio: o operador precisa
     * distinguir "outro ator mudou o pedido" de "a sua propria requisicao ainda esta correndo",
     * porque so o segundo caso se resolve esperando.
     */
    @ExceptionHandler(AdminInFlight::class)
    fun inFlight(ex: AdminInFlight): ResponseEntity<ApiErrorResponse> =
        adminError(
            "in_flight",
            HttpStatus.CONFLICT,
            ApiErrorResponse("IDEMPOTENT_IN_FLIGHT", ex.message ?: "operacao em voo"),
        )

    @ExceptionHandler(AdminValidation::class)
    fun validation(ex: AdminValidation): ResponseEntity<ApiErrorResponse> =
        adminError(
            "validation",
            HttpStatus.BAD_REQUEST,
            ApiErrorResponse("VALIDATION", "rascunho invalido", ex.errors),
        )

    @ExceptionHandler(AdminNotFound::class)
    fun notFound(ex: AdminNotFound): ResponseEntity<ApiErrorResponse> =
        adminError("not_found", HttpStatus.NOT_FOUND, ApiErrorResponse("NOT_FOUND", ex.message ?: "not found"))

    /**
     * Rede de seguranca para o que nao foi previsto.
     *
     * Sem ela, uma falha de invariante — `check` ou `error` num store, por exemplo — sobe com a
     * mensagem interna no corpo da resposta. O cliente recebe um codigo estavel e nada mais; o
     * diagnostico fica no log do servidor, que e onde ele pertence.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.SERVER_UNEXPECTED_ERROR)
        logger.error("falha nao tratada ao atender a requisicao", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse("INTERNAL_ERROR", "erro interno"))
    }

    /** Conta a falha de governanca pelo tipo, em tag, e responde com o status dela. */
    private fun adminError(
        error: String,
        status: HttpStatus,
        body: ApiErrorResponse,
    ): ResponseEntity<ApiErrorResponse> {
        metrics.increment(MetricNames.ADMIN_ERROR, mapOf("error" to error))
        return ResponseEntity.status(status).body(body)
    }

    private companion object {
        const val ADMIN_RETRY_AFTER_SECONDS: String = "30"
    }
}

