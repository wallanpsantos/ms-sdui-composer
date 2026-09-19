package br.com.empresa.sdui.api.http

import br.com.empresa.sdui.contract.error.ApiErrorResponse
import br.com.empresa.sdui.orchestrator.admin.AdminConflict
import br.com.empresa.sdui.orchestrator.admin.AdminDenied
import br.com.empresa.sdui.orchestrator.admin.AdminNotFound
import br.com.empresa.sdui.orchestrator.admin.AdminValidation
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(AdminDenied::class)
    fun denied(ex: AdminDenied): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse("FORBIDDEN", ex.message ?: "forbidden"))

    @ExceptionHandler(AdminConflict::class)
    fun conflict(ex: AdminConflict): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse("CONFLICT", ex.message ?: "conflict"))

    @ExceptionHandler(AdminValidation::class)
    fun validation(ex: AdminValidation): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse("VALIDATION", "rascunho invalido", ex.errors))

    @ExceptionHandler(AdminNotFound::class)
    fun notFound(ex: AdminNotFound): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse("NOT_FOUND", ex.message ?: "not found"))
}
