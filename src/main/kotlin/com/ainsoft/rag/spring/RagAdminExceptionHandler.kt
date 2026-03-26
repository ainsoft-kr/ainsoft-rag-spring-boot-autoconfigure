package com.ainsoft.rag.spring

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [RagAdminApiController::class])
class RagAdminExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<RagAdminApiErrorResponse> =
        ResponseEntity.badRequest().body(
            RagAdminApiErrorResponse(
                code = "BAD_REQUEST",
                message = ex.message ?: "bad request"
            )
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<RagAdminApiErrorResponse> =
        ResponseEntity.badRequest().body(
            RagAdminApiErrorResponse(
                code = "MISSING_PARAMETER",
                message = ex.message
            )
        )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(ex: HttpMediaTypeNotAcceptableException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.message ?: "No acceptable representation")

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<RagAdminApiErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            RagAdminApiErrorResponse(
                code = "INTERNAL_ERROR",
                message = ex.message ?: "internal error"
            )
        )
}

data class RagAdminApiErrorResponse(
    val code: String,
    val message: String
)
