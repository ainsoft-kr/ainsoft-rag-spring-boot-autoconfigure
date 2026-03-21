package com.ainsoft.rag.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag.admin")
data class RagAdminProperties(
    val enabled: Boolean = true,
    val basePath: String = "/rag-admin",
    val apiBasePath: String = "/api/rag/admin",
    val defaultRecentProviderWindowMillis: Long = 60_000,
    val historyMaxEntries: Int = 200,
    val providerHistoryMaxEntries: Int = 240,
    val providerHistorySampleIntervalMillis: Long = 0L,
    val security: RagAdminSecurityProperties = RagAdminSecurityProperties()
)

data class RagAdminSecurityProperties(
    val enabled: Boolean = false,
    val tokenHeaderName: String = "X-Rag-Admin-Token",
    val tokenQueryParameter: String = "access_token",
    val tokens: Map<String, String> = emptyMap(),
    val featureRoles: Map<String, List<String>> = defaultFeatureRoles()
)

private fun defaultFeatureRoles(): Map<String, List<String>> = mapOf(
    "overview" to listOf("ADMIN", "OPS", "AUDITOR"),
    "search" to listOf("ADMIN", "OPS", "AUDITOR"),
    "text-ingest" to listOf("ADMIN", "OPS"),
    "file-ingest" to listOf("ADMIN", "OPS"),
    "web-ingest" to listOf("ADMIN", "OPS"),
    "documents" to listOf("ADMIN", "OPS", "AUDITOR"),
    "tenants" to listOf("ADMIN", "OPS"),
    "provider-history" to listOf("ADMIN", "OPS", "AUDITOR"),
    "search-audit" to listOf("ADMIN", "OPS", "AUDITOR"),
    "job-history" to listOf("ADMIN", "OPS", "AUDITOR"),
    "access-security" to listOf("ADMIN"),
    "config" to listOf("ADMIN", "OPS", "AUDITOR"),
    "bulk-operations" to listOf("ADMIN", "OPS")
)
