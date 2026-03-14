package com.ainsoft.rag.spring

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.server.ResponseStatusException

data class RagAdminAccessContext(
    val enabled: Boolean,
    val tokenPresent: Boolean,
    val role: String?,
    val allowedFeatures: Set<String>
)

class RagAdminSecurityService(
    private val properties: RagAdminProperties,
    private val adminService: RagAdminService
) {
    fun resolveAccess(request: HttpServletRequest): RagAdminAccessContext {
        val security = properties.security
        if (!security.enabled) {
            return RagAdminAccessContext(
                enabled = false,
                tokenPresent = false,
                role = "ADMIN",
                allowedFeatures = security.featureRoles.keys
            )
        }

        val token = request.getHeader(security.tokenHeaderName)?.takeIf { it.isNotBlank() }
            ?: request.getParameter(security.tokenQueryParameter)?.takeIf { it.isNotBlank() }
        val role = token?.let(security.tokens::get)
        return RagAdminAccessContext(
            enabled = true,
            tokenPresent = token != null,
            role = role,
            allowedFeatures = security.featureRoles
                .filterValues { roles -> role != null && role in roles }
                .keys
                .toSet()
        )
    }

    fun requireFeature(request: HttpServletRequest, feature: String): RagAdminAccessContext {
        val context = resolveAccess(request)
        if (!context.enabled) {
            return context
        }
        if (!context.tokenPresent || context.role == null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin access token is required")
        }
        if (feature !in context.allowedFeatures) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Role '${context.role}' cannot access feature '$feature'")
        }
        return context
    }

    fun currentRole(request: HttpServletRequest): String? = resolveAccess(request).role
}

class RagAdminSecurityInterceptor(
    private val properties: RagAdminProperties,
    private val securityService: RagAdminSecurityService,
    private val adminService: RagAdminService
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val access = securityService.resolveAccess(request)
        request.setAttribute(RAG_ADMIN_ACCESS_ATTRIBUTE, access)
        val granted = !access.enabled || (access.tokenPresent && access.role != null)
        adminService.recordAccess(
            path = request.requestURI ?: "",
            method = request.method,
            role = access.role,
            granted = granted,
            message = if (granted) null else "missing or invalid access token"
        )
        if (!access.enabled || granted) {
            return true
        }
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json"
        response.writer.use { writer ->
            writer.write("""{"code":"UNAUTHORIZED","message":"Admin access token is required"}""")
        }
        return false
    }

    companion object {
        const val RAG_ADMIN_ACCESS_ATTRIBUTE = "ragAdminAccessContext"
    }
}

class RagAdminWebMvcConfiguration(
    private val properties: RagAdminProperties,
    private val interceptor: RagAdminSecurityInterceptor
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        val basePath = normalize(properties.basePath)
        val apiBasePath = normalize(properties.apiBasePath)
        registry.addInterceptor(interceptor)
            .addPathPatterns("$basePath/**", apiBasePath, "$apiBasePath/**")
    }

    private fun normalize(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }
}
