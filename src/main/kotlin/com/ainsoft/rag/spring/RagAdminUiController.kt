package com.ainsoft.rag.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.util.StreamUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Controller
class RagAdminUiController(
    private val properties: RagAdminProperties,
    private val securityService: RagAdminSecurityService
) {
    private val objectMapper = jacksonObjectMapper()
    private val templateCache = ConcurrentHashMap<String, String>()
    private val pageFeatures = mapOf(
        "index" to "overview",
        "search" to "search",
        "text-ingest" to "text-ingest",
        "file-ingest" to "file-ingest",
        "documents" to "documents",
        "tenants" to "tenants",
        "provider-history" to "provider-history",
        "search-audit" to "search-audit",
        "job-history" to "job-history",
        "access-security" to "access-security",
        "config" to "config",
        "bulk-operations" to "bulk-operations"
    )

    @ResponseBody
    @GetMapping(
        value = [
            "\${rag.admin.base-path:/rag-admin}",
            "\${rag.admin.base-path:/rag-admin}/",
            "\${rag.admin.base-path:/rag-admin}/index.html"
        ],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun index(request: HttpServletRequest): String = renderPage("index", request)

    @ResponseBody
    @GetMapping(
        value = [
            "\${rag.admin.base-path:/rag-admin}/{page}",
            "\${rag.admin.base-path:/rag-admin}/{page}.html"
        ],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun page(
        request: HttpServletRequest,
        @PathVariable page: String
    ): String {
        val resolvedPage = when (page.removeSuffix(".html")) {
            "overview" -> "index"
            else -> page.removeSuffix(".html")
        }
        if (resolvedPage !in pageFeatures) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Admin page '$page' not found")
        }
        return renderPage(resolvedPage, request)
    }

    private fun renderPage(pageName: String, request: HttpServletRequest): String {
        securityService.requireFeature(request, pageFeatures.getValue(pageName))
        val assetBasePath = "${normalizePath(properties.basePath)}/assets"
        return loadTemplate(pageName)
            .replace("href=\"assets/", "href=\"$assetBasePath/")
            .replace("src=\"assets/", "src=\"$assetBasePath/")
            .replace("__RAG_ADMIN_CONFIG__", adminConfigJson(request))
    }

    private fun loadTemplate(pageName: String): String =
        templateCache.computeIfAbsent(pageName) {
            ClassPathResource("META-INF/resources/rag-admin/$pageName.html")
                .inputStream
                .use { input -> StreamUtils.copyToString(input, StandardCharsets.UTF_8) }
        }

    private fun adminConfigJson(request: HttpServletRequest): String {
        val access = securityService.resolveAccess(request)
        return objectMapper.writeValueAsString(
            mapOf(
                "basePath" to normalizePath(properties.basePath),
                "apiBasePath" to normalizePath(properties.apiBasePath),
                "defaultRecentProviderWindowMillis" to properties.defaultRecentProviderWindowMillis,
                "securityEnabled" to properties.security.enabled,
                "currentRole" to access.role,
                "allowedFeatures" to access.allowedFeatures.sorted(),
                "tokenHeaderName" to properties.security.tokenHeaderName,
                "tokenQueryParameter" to properties.security.tokenQueryParameter
            )
        )
    }

    private fun normalizePath(value: String): String {
        val normalized = if (value.startsWith("/")) value else "/$value"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }
}
