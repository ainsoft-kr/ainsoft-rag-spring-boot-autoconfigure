package com.ainsoft.rag.spring

import com.ainsoft.rag.api.LlmProviderConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val defaultProvider: String? = null,
    val providers: Map<String, LlmProviderDefinition> = emptyMap(),
    val queryRewrite: LlmBindingProperties = LlmBindingProperties(),
    val summarizer: LlmBindingProperties = LlmBindingProperties(),
    val embedding: LlmBindingProperties = LlmBindingProperties()
) {
    fun resolveQueryRewrite(): LlmProviderConfig? = resolveBinding(queryRewrite)

    fun resolveSummarizer(): LlmProviderConfig? = resolveBinding(summarizer)

    fun resolveEmbedding(): LlmProviderConfig? = resolveBinding(embedding)

    fun resolveBinding(binding: LlmBindingProperties): LlmProviderConfig? {
        val providerName = binding.provider?.takeIf { it.isNotBlank() }
            ?: defaultProvider?.takeIf { it.isNotBlank() }
        val provider = providerName?.let { providers[it] }

        val kind = binding.kind?.takeIf { it.isNotBlank() }
            ?: provider?.kind
            ?: "openai-compatible"
        val baseUrl = binding.baseUrl?.takeIf { it.isNotBlank() }
            ?: provider?.baseUrl?.takeIf { it.isNotBlank() }
            ?: defaultBaseUrlForKind(kind)
        val apiKey = binding.apiKey?.takeIf { it.isNotBlank() }
            ?: provider?.apiKey?.takeIf { it.isNotBlank() }
        val model = binding.model?.takeIf { it.isNotBlank() }
            ?: provider?.model?.takeIf { it.isNotBlank() }
            ?: defaultModelForKind(kind)
        val requestTimeoutMillis = binding.requestTimeoutMillis
            ?: provider?.requestTimeoutMillis
            ?: 30_000

        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank() || model.isNullOrBlank()) {
            return null
        }

        return LlmProviderConfig(
            kind = kind,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            requestTimeoutMillis = requestTimeoutMillis,
            headers = provider?.headers.orEmpty()
        )
    }

    private fun defaultBaseUrlForKind(kind: String): String? =
        when (kind.trim().lowercase()) {
            "openai", "openai-compatible" -> "https://api.openai.com/v1"
            "anthropic", "claude" -> "https://api.anthropic.com/v1"
            "gemini", "google-gemini" -> "https://generativelanguage.googleapis.com/v1beta/models"
            else -> null
        }

    private fun defaultModelForKind(kind: String): String? =
        when (kind.trim().lowercase()) {
            "openai", "openai-compatible" -> "gpt-4o-mini"
            "anthropic", "claude" -> "claude-3-5-sonnet-latest"
            "gemini", "google-gemini" -> "gemini-2.0-flash"
            else -> null
        }
}

data class LlmProviderDefinition(
    val kind: String = "openai-compatible",
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val requestTimeoutMillis: Long = 30_000,
    val headers: Map<String, String> = emptyMap()
)

data class LlmBindingProperties(
    val provider: String? = null,
    val kind: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val requestTimeoutMillis: Long? = null
)
