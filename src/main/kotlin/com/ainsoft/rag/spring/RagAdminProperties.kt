package com.ainsoft.rag.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag.admin")
data class RagAdminProperties(
    val enabled: Boolean = true,
    val basePath: String = "/rag-admin",
    val apiBasePath: String = "/api/rag/admin",
    val defaultTenantId: String = "tenant-admin",
    val defaultAclPrincipals: List<String> = listOf("group:admin"),
    val defaultSearchPrincipals: List<String> = listOf("group:admin"),
    val defaultSearchQuery: String = "hybrid retrieval",
    val defaultRecentProviderWindowMillis: Long = 60_000,
    val historyMaxEntries: Int = 200,
    val providerHistoryMaxEntries: Int = 240,
    val providerHistorySampleIntervalMillis: Long = 0L,
    val security: RagAdminSecurityProperties = RagAdminSecurityProperties()
)

data class RagAdminSecurityProperties(
    val enabled: Boolean = false,
    val loginPath: String = "",
    val logoutPath: String = "",
    val sessionAttributeName: String = "rag-admin-session-user",
    val users: Map<String, RagAdminUserProperties> = emptyMap(),
    @Deprecated("Session login is preferred over header tokens")
    val tokenHeaderName: String = "X-Rag-Admin-Token",
    @Deprecated("Session login is preferred over query tokens")
    val tokenQueryParameter: String = "access_token",
    @Deprecated("Session login is preferred over static tokens")
    val tokens: Map<String, String> = emptyMap(),
    val featureRoles: Map<String, List<String>> = defaultFeatureRoles()
)

data class RagAdminUserProperties(
    val password: String = "",
    val roles: List<String> = listOf("ADMIN"),
    val displayName: String? = null
)

internal fun RagAdminProperties.resolvedLoginPath(): String =
    resolveAdminPath(security.loginPath, "login")

internal fun RagAdminProperties.resolvedLogoutPath(): String =
    resolveAdminPath(security.logoutPath, "logout")

private fun RagAdminProperties.resolveAdminPath(explicitPath: String, suffix: String): String {
    val candidate = explicitPath.trim().takeIf { it.isNotBlank() } ?: "${normalizeAdminPath(basePath)}/$suffix"
    return normalizeAdminPath(candidate)
}

private fun normalizeAdminPath(value: String): String {
    val normalized = if (value.startsWith("/")) value else "/$value"
    return if (normalized.length > 1) normalized.trimEnd('/') else normalized
}

private fun defaultFeatureRoles(): Map<String, List<String>> = mapOf(
    "overview" to listOf("ADMIN", "OPS", "AUDITOR"),
    "search" to listOf("ADMIN", "OPS", "AUDITOR"),
    "text-ingest" to listOf("ADMIN", "OPS"),
    "file-ingest" to listOf("ADMIN", "OPS"),
    "web-ingest" to listOf("ADMIN", "OPS"),
    "documents" to listOf("ADMIN", "OPS", "AUDITOR"),
    "graph" to listOf("ADMIN", "OPS", "AUDITOR"),
    "tenants" to listOf("ADMIN", "OPS"),
    "provider-history" to listOf("ADMIN", "OPS", "AUDITOR"),
    "search-audit" to listOf("ADMIN", "OPS", "AUDITOR"),
    "job-history" to listOf("ADMIN", "OPS", "AUDITOR"),
    "access-security" to listOf("ADMIN"),
    "users" to listOf("ADMIN"),
    "config" to listOf("ADMIN", "OPS", "AUDITOR"),
    "bulk-operations" to listOf("ADMIN", "OPS")
)
