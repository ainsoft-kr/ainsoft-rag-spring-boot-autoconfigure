package com.ainsoft.rag.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.util.StreamUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.nio.charset.StandardCharsets

@Controller
class RagAdminUiController(
    private val properties: RagAdminProperties
) {
    private val objectMapper = jacksonObjectMapper()
    private val template: String = ClassPathResource("META-INF/resources/rag-admin/index.html")
        .inputStream
        .use { StreamUtils.copyToString(it, StandardCharsets.UTF_8) }

    @ResponseBody
    @GetMapping(
        value = [
            "\${rag.admin.base-path:/rag-admin}",
            "\${rag.admin.base-path:/rag-admin}/",
            "\${rag.admin.base-path:/rag-admin}/index.html"
        ],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun index(): String = template.replace("__RAG_ADMIN_CONFIG__", adminConfigJson())

    private fun adminConfigJson(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "basePath" to normalizePath(properties.basePath),
                "apiBasePath" to normalizePath(properties.apiBasePath),
                "defaultRecentProviderWindowMillis" to properties.defaultRecentProviderWindowMillis
            )
        )

    private fun normalizePath(value: String): String {
        val normalized = if (value.startsWith("/")) value else "/$value"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }
}
