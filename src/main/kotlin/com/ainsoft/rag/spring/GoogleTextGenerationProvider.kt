package com.ainsoft.rag.spring

import com.ainsoft.rag.api.LlmProviderConfig
import com.ainsoft.rag.api.TextGenerationProvider
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class GoogleTextGenerationProvider(
    private val config: LlmProviderConfig,
    private val authMode: AuthMode
) : TextGenerationProvider {
    enum class AuthMode {
        API_KEY_QUERY,
        BEARER_HEADER
    }

    private val mapper = ObjectMapper()
    private val rootUri = URI.create(config.baseUrl.trimEnd('/') + "/")
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .build()
    private val timeout = Duration.ofMillis(config.requestTimeoutMillis)

    override fun complete(systemPrompt: String, userPrompt: String): String {
        val requestBody = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to userPrompt))
                )
            ),
            "generationConfig" to mapOf("temperature" to 0.1)
        )
        val endpoint = rootUri.resolve("${config.model}:generateContent")
        val builder = HttpRequest.newBuilder(
            when (authMode) {
                AuthMode.API_KEY_QUERY -> URI.create(endpoint.toString() + if (endpoint.query.isNullOrBlank()) "?key=${config.apiKey}" else "&key=${config.apiKey}")
                AuthMode.BEARER_HEADER -> endpoint
            }
        )
            .timeout(timeout)
            .header("Content-Type", "application/json")
        if (authMode == AuthMode.BEARER_HEADER) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        config.headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(
            builder.POST(
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody), StandardCharsets.UTF_8)
            ).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        if (response.statusCode() !in 200..299) {
            error(
                "Google LLM request failed: status=${response.statusCode()} body=${response.body().replace('\n', ' ').take(400)}"
            )
        }
        val candidates = mapper.readTree(response.body()).path("candidates")
        val first = candidates.takeIf { it.isArray && it.size() > 0 }?.get(0)
        val parts = first?.path("content")?.path("parts")
        val firstText = parts?.takeIf { it.isArray && it.size() > 0 }?.get(0)?.path("text")?.asText()
        return firstText?.trim().orEmpty()
    }
}
