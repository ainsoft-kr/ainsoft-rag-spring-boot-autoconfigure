package com.ainsoft.rag.spring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class RagAdminAuthController(
    private val properties: RagAdminProperties,
    private val securityService: RagAdminSecurityService
) {
    @PostMapping("\${rag.admin.base-path:/rag-admin}/login")
    fun login(
        request: HttpServletRequest,
        @RequestParam username: String,
        @RequestParam password: String,
        @RequestParam(required = false) redirect: String?
    ): ResponseEntity<Void> {
        if (!properties.security.enabled) {
            return redirectResponse(safeRedirect(redirect))
        }

        val user = securityService.authenticate(username, password)
            ?: return redirectResponse("${properties.resolvedLoginPath()}?error=1&redirect=${encodeRedirect(redirect)}")

        securityService.storeSessionUser(request.getSession(true), user)
        return redirectResponse(safeRedirect(redirect))
    }

    @GetMapping("\${rag.admin.base-path:/rag-admin}/logout")
    @PostMapping("\${rag.admin.base-path:/rag-admin}/logout")
    fun logout(
        request: HttpServletRequest,
        @RequestParam(required = false) redirect: String?
    ): ResponseEntity<Void> {
        securityService.clearSessionUser(request.getSession(false))
        request.getSession(false)?.invalidate()
        val target = "${properties.resolvedLoginPath()}?logout=1"
        return redirectResponse(target)
    }

    private fun safeRedirect(redirect: String?): String {
        val basePath = normalizePath(properties.basePath)
        val target = redirect?.trim().orEmpty()
        return when {
            target.isBlank() -> basePath
            target.startsWith(basePath) && !target.startsWith("//") -> target
            else -> basePath
        }
    }

    private fun encodeRedirect(redirect: String?): String =
        java.net.URLEncoder.encode(safeRedirect(redirect), Charsets.UTF_8)

    private fun redirectResponse(location: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION, location)
            .build()

    private fun normalizePath(value: String): String {
        val normalized = if (value.startsWith("/")) value else "/$value"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }
}
