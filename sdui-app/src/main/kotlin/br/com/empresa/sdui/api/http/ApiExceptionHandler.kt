package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.orchestrator.admin.AdminConflict
import br.com.empresa.sdui.orchestrator.admin.AdminDenied
import br.com.empresa.sdui.orchestrator.admin.AdminInFlight
import br.com.empresa.sdui.orchestrator.admin.AdminNotFound
import br.com.empresa.sdui.orchestrator.admin.AdminValidation
import br.com.empresa.sdui.orchestrator.port.outbound.MetricsRecorder
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
    fun denied(ex: AdminDenied): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_ADMIN_ERROR, mapOf("error" to "denied"))
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiErrorResponse("FORBIDDEN", ex.message ?: "forbidden"))
    }

    @ExceptionHandler(AdminConflict::class)
    fun conflict(ex: AdminConflict): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_ADMIN_ERROR, mapOf("error" to "conflict"))
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))
    }

    /**
     * Chave de idempotencia em voo. Tambem 409, mas com codigo proprio: o operador precisa
     * distinguir "outro ator mudou o pedido" de "a sua propria requisicao ainda esta correndo",
     * porque so o segundo caso se resolve esperando.
     */
    @ExceptionHandler(AdminInFlight::class)
    fun inFlight(ex: AdminInFlight): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_ADMIN_ERROR, mapOf("error" to "in_flight"))
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiErrorResponse("IDEMPOTENT_IN_FLIGHT", ex.message ?: "operacao em voo"))
    }

    @ExceptionHandler(AdminValidation::class)
    fun validation(ex: AdminValidation): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_ADMIN_ERROR, mapOf("error" to "validation"))
        return ResponseEntity.badRequest().body(ApiErrorResponse("VALIDATION", "rascunho invalido", ex.errors))
    }

    @ExceptionHandler(AdminNotFound::class)
    fun notFound(ex: AdminNotFound): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_ADMIN_ERROR, mapOf("error" to "not_found"))
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiErrorResponse("NOT_FOUND", ex.message ?: "not found"))
    }

    /**
     * Rede de seguranca para o que nao foi previsto.
     *
     * Sem ela, uma falha de invariante — `check` ou `error` num store, por exemplo — sobe com a
     * mensagem interna no corpo da resposta. O cliente recebe um codigo estavel e nada mais; o
     * diagnostico fica no log do servidor, que e onde ele pertence.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        metrics.increment(METRIC_SERVER_ERROR)
        logger.error("falha nao tratada ao atender a requisicao", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse("INTERNAL_ERROR", "erro interno"))
    }

    private companion object {
        const val METRIC_ADMIN_ERROR: String = "admin.error"
        const val METRIC_SERVER_ERROR: String = "server.unexpected_error"
    }
}

