package com.ainsoft.rag.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag.admin")
data class RagAdminProperties(
    val enabled: Boolean = true,
    val basePath: String = "/rag-admin",
    val apiBasePath: String = "/api/rag/admin",
    val defaultRecentProviderWindowMillis: Long = 60_000
)
