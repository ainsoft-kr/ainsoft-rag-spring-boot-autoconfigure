package com.ainsoft.rag.spring

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.mindrot.jbcrypt.BCrypt
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

data class RagAdminAccessContext(
    val enabled: Boolean,
    val authenticated: Boolean,
    val username: String?,
    val role: String?,
    val roles: Set<String>,
    val allowedFeatures: Set<String>
)

data class RagAdminSessionUser(
    val username: String,
    val roles: Set<String>,
    val displayName: String? = null
)

class RagAdminSecurityService(
    private val properties: RagAdminProperties,
    private val adminService: RagAdminService,
    private val accountStore: RagAdminAccountStore
) {
    fun authenticate(username: String, password: String): RagAdminSessionUser? {
        val security = properties.security
        if (!security.enabled) {
            return RagAdminSessionUser(username = username, roles = setOf("ADMIN"))
        }

        val user = accountStore.findUser(username)?.takeIf { it.enabled } ?: return null
        if (!BCrypt.checkpw(password, user.passwordHash)) {
            return null
        }
        val roles = normalizeRoles(user.roles)
        if (roles.isEmpty()) {
            return null
        }
        return RagAdminSessionUser(
            username = username,
            roles = roles,
            displayName = user.displayName
        )
    }

    fun resolveAccess(request: HttpServletRequest): RagAdminAccessContext {
        val security = properties.security
        if (!security.enabled) {
            return RagAdminAccessContext(
                enabled = false,
                authenticated = true,
                username = "system",
                role = "ADMIN",
                roles = setOf("ADMIN"),
                allowedFeatures = security.featureRoles.keys
            )
        }

        val sessionUser = sessionUser(request)
        val roles = sessionUser?.roles.orEmpty()
        val role = primaryRole(roles)
        return RagAdminAccessContext(
            enabled = true,
            authenticated = sessionUser != null,
            username = sessionUser?.username,
            role = role,
            roles = roles,
            allowedFeatures = security.featureRoles
                .filterValues { allowedRoles -> roles.any { roleName -> roleName in allowedRoles } }
                .keys
                .toSet()
        )
    }

    fun requireFeature(request: HttpServletRequest, feature: String): RagAdminAccessContext {
        val context = resolveAccess(request)
        if (!context.enabled) {
            return context
        }
        if (!context.authenticated) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin login is required")
        }
        if (feature !in context.allowedFeatures) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Role '${context.role}' cannot access feature '$feature'")
        }
        return context
    }

    fun currentRole(request: HttpServletRequest): String? = resolveAccess(request).role

    fun currentUser(request: HttpServletRequest): RagAdminSessionUser? = sessionUser(request)

    fun userCount(): Int = accountStore.allUsers().size

    fun storeSessionUser(session: HttpSession, user: RagAdminSessionUser) {
        session.setAttribute(properties.security.sessionAttributeName, user)
    }

    fun clearSessionUser(session: HttpSession?) {
        session?.removeAttribute(properties.security.sessionAttributeName)
    }

    private fun sessionUser(request: HttpServletRequest): RagAdminSessionUser? =
        request.getSession(false)
            ?.getAttribute(properties.security.sessionAttributeName) as? RagAdminSessionUser

    private fun normalizeRoles(roles: List<String>): Set<String> =
        roles.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .toCollection(LinkedHashSet<String>())

    private fun normalizeRoles(roles: Set<String>): Set<String> =
        roles.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .toCollection(LinkedHashSet<String>())

    private fun primaryRole(roles: Set<String>): String? {
        if (roles.isEmpty()) {
            return null
        }
        return when {
            "ADMIN" in roles -> "ADMIN"
            "OPS" in roles -> "OPS"
            "AUDITOR" in roles -> "AUDITOR"
            else -> roles.sorted().first()
        }
    }
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

        val requestPath = request.requestURI ?: ""
        val basePath = normalize(properties.basePath)
        val apiBasePath = normalize(properties.apiBasePath)
        if (!access.enabled || isPublicPath(requestPath, basePath)) {
            adminService.recordAccess(
                path = requestPath,
                method = request.method,
                role = access.role,
                granted = true,
                message = null
            )
            return true
        }

        if (access.authenticated) {
            adminService.recordAccess(
                path = requestPath,
                method = request.method,
                role = access.role,
                granted = true,
                message = null
            )
            return true
        }

        adminService.recordAccess(
            path = requestPath,
            method = request.method,
            role = null,
            granted = false,
            message = "login required"
        )
        return if (requestPath.startsWith(apiBasePath)) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.use { writer ->
                writer.write("""{"code":"UNAUTHORIZED","message":"Admin login is required"}""")
            }
            false
        } else {
            val target = buildLoginRedirect(requestPath, request.queryString)
            response.sendRedirect(target)
            false
        }
    }

    private fun isPublicPath(path: String, basePath: String): Boolean {
        val loginPath = properties.resolvedLoginPath()
        val logoutPath = properties.resolvedLogoutPath()
        return path == loginPath ||
            path == "$loginPath.html" ||
            path == logoutPath ||
            path == "$logoutPath.html" ||
            path.startsWith("$basePath/assets/")
    }

    private fun buildLoginRedirect(path: String, queryString: String?): String {
        val loginPath = properties.resolvedLoginPath()
        val redirectTarget = buildString {
            append(path)
            if (!queryString.isNullOrBlank()) {
                append('?')
                append(queryString)
            }
        }
        val encoded = URLEncoder.encode(redirectTarget, StandardCharsets.UTF_8)
        return "$loginPath?redirect=$encoded"
    }

    private fun normalize(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
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
        val loginPath = properties.resolvedLoginPath()
        val logoutPath = properties.resolvedLogoutPath()
        registry.addInterceptor(interceptor)
            .addPathPatterns("$basePath/**", apiBasePath, "$apiBasePath/**")
            .excludePathPatterns(
                "$basePath/assets/**",
                loginPath,
                "$loginPath.html",
                logoutPath,
                "$logoutPath.html"
            )
    }

    private fun normalize(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }
}
