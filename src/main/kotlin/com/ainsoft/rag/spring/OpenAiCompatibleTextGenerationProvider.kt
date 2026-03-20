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

internal class OpenAiCompatibleTextGenerationProvider(
    private val config: LlmProviderConfig
) : TextGenerationProvider {
    private val mapper = ObjectMapper()
    private val rootUri = URI.create(config.baseUrl.trimEnd('/') + "/")
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .build()
    private val timeout = Duration.ofMillis(config.requestTimeoutMillis)

    override fun complete(systemPrompt: String, userPrompt: String): String {
        val requestBody = mapOf(
            "model" to config.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.1
        )
        val endpoint = rootUri.resolve("chat/completions")
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
        config.headers.forEach { (name, value) ->
            builder.header(name, value)
        }
        val response = client.send(
            builder.POST(
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody), StandardCharsets.UTF_8)
            ).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        if (response.statusCode() !in 200..299) {
            error(
                "OpenAI-compatible LLM request failed: status=${response.statusCode()} body=${response.body().replace('\n', ' ').take(400)}"
            )
        }
        val choices = mapper.readTree(response.body()).path("choices")
        val firstChoice = choices.takeIf { it.isArray && it.size() > 0 }?.get(0)
        return firstChoice
            ?.path("message")
            ?.path("content")
            ?.asText()
            ?.trim()
            .orEmpty()
    }
}
