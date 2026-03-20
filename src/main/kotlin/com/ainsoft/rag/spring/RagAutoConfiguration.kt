package com.ainsoft.rag.spring

import com.ainsoft.rag.api.ContextualRetrievalOptions
import com.ainsoft.rag.api.CorrectiveRetrievalOptions
import com.ainsoft.rag.api.HierarchicalSummaryOptions
import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.api.ProviderHealthExportHook
import com.ainsoft.rag.api.RagComponents
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.RagOptions
import com.ainsoft.rag.api.TextGenerationProviderFactory
import com.ainsoft.rag.api.RerankerOptions
import com.ainsoft.rag.cache.InMemoryStatsCacheStore
import com.ainsoft.rag.cache.StatsCacheStore
import com.ainsoft.rag.cache.file.JsonFileStatsCacheStore
import com.ainsoft.rag.chunking.AdaptiveChunking
import com.ainsoft.rag.chunking.Chunker
import com.ainsoft.rag.chunking.Chunkers
import com.ainsoft.rag.chunking.RegexChunking
import com.ainsoft.rag.chunking.SlidingWindowChunking
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.ainsoft.rag.embeddings.OpenAiEmbeddingProvider
import com.ainsoft.rag.impl.CompositeProviderHealthExportHook
import com.ainsoft.rag.impl.ProviderHealthAutoExportLifecycle
import com.ainsoft.rag.impl.createProviderHealthExportHook
import com.ainsoft.rag.impl.createProviderHealthPushExportHook
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(RagProperties::class, LlmProperties::class)
class RagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragConfig(props: RagProperties): RagConfig {
        val queryRewriteConfig = props.llm.resolveQueryRewrite()
        val summarizerConfig = props.llm.resolveSummarizer()
        val path = Path.of(props.indexPath)
        path.createDirectories()
        return RagConfig(
            indexPath = path,
            options = RagOptions(
                storeChunkText = props.storeChunkText,
                statsCacheTtlMillis = props.statsCacheTtlMillis,
                statsCacheMaxEntries = props.statsCacheMaxEntries,
                statsCacheMaxEntriesPerTenant = props.statsCacheMaxEntriesPerTenant,
                contextualRetrieval = ContextualRetrievalOptions(
                    enabled = props.contextualRetrievalEnabled,
                    includeDocId = props.contextualIncludeDocId,
                    includeMetadataContext = props.contextualIncludeMetadataContext,
                    maxDocumentSummaryChars = props.contextualMaxDocumentSummaryChars
                ),
                reranker = RerankerOptions(
                    enabled = props.rerankerEnabled,
                    type = props.rerankerType,
                    topN = props.rerankerTopN,
                    alpha = props.rerankerAlpha,
                    apiBaseUrl = props.rerankerApiBaseUrl,
                    apiKey = props.rerankerApiKey,
                    model = props.rerankerModel,
                    requestTimeoutMillis = props.rerankerRequestTimeoutMillis,
                    onnxQueryInputName = props.rerankerOnnxQueryInputName,
                    onnxDocumentInputName = props.rerankerOnnxDocumentInputName,
                    onnxOutputName = props.rerankerOnnxOutputName,
                    onnxExpectedDimensions = props.rerankerOnnxExpectedDimensions,
                    onnxExpectedInputSchema = props.rerankerOnnxExpectedInputSchema,
                    onnxExpectedTokenizer = props.rerankerOnnxExpectedTokenizer,
                    onnxExpectedScoreSemantics = props.rerankerOnnxExpectedScoreSemantics,
                    onnxExpectedTokenizerVocabChecksum = props.rerankerOnnxExpectedTokenizerVocabChecksum,
                    onnxTokenizerSchemaChecksum = props.rerankerOnnxTokenizerSchemaChecksum,
                    onnxModelContractChecksum = props.rerankerOnnxModelContractChecksum
                ),
                correctiveRetrieval = CorrectiveRetrievalOptions(
                    enabled = props.correctiveRetrievalEnabled,
                    minConfidence = props.correctiveMinConfidence,
                    minResultsBeforeSkip = props.correctiveMinResultsBeforeSkip,
                    expandedCandidateMultiplier = props.correctiveExpandedCandidateMultiplier,
                    queryRewriteEnabled = props.queryRewriteEnabled,
                    queryRewriterType = if (queryRewriteConfig != null) "openai-compatible" else props.queryRewriterType,
                    queryRewriteApiBaseUrl = queryRewriteConfig?.baseUrl ?: props.queryRewriteApiBaseUrl,
                    queryRewriteApiKey = queryRewriteConfig?.apiKey ?: props.queryRewriteApiKey,
                    queryRewriteModel = queryRewriteConfig?.model ?: props.queryRewriteModel,
                    queryRewriteRequestTimeoutMillis = queryRewriteConfig?.requestTimeoutMillis
                        ?: props.queryRewriteRequestTimeoutMillis
                ),
                hierarchicalSummaries = HierarchicalSummaryOptions(
                    enabled = props.hierarchicalSummariesEnabled,
                    maxSectionSummaries = props.hierarchicalMaxSectionSummaries,
                    maxSummaryChars = props.hierarchicalMaxSummaryChars,
                    targetChunksPerSection = props.hierarchicalTargetChunksPerSection,
                    summarizerType = if (summarizerConfig != null) "openai-compatible" else props.summarizerType,
                    summarizerApiBaseUrl = summarizerConfig?.baseUrl ?: props.summarizerApiBaseUrl,
                    summarizerApiKey = summarizerConfig?.apiKey ?: props.summarizerApiKey,
                    summarizerModel = summarizerConfig?.model ?: props.summarizerModel,
                    summarizerRequestTimeoutMillis = summarizerConfig?.requestTimeoutMillis
                        ?: props.summarizerRequestTimeoutMillis
                )
            )
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun textGenerationProviderFactory(): TextGenerationProviderFactory = TextGenerationProviderFactory { config ->
        when (config.kind.trim().lowercase()) {
            "openai", "openai-compatible" -> OpenAiCompatibleTextGenerationProvider(config)
            "anthropic", "claude" -> AnthropicTextGenerationProvider(config)
            "gemini", "google-gemini" -> GoogleTextGenerationProvider(config, GoogleTextGenerationProvider.AuthMode.API_KEY_QUERY)
            "vertex", "vertex-ai", "vertex-gemini" -> GoogleTextGenerationProvider(config, GoogleTextGenerationProvider.AuthMode.BEARER_HEADER)
            else -> error(
                "Unsupported LLM provider kind='${config.kind}'. Supported: openai-compatible, anthropic, gemini, vertex"
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun embeddingProvider(props: RagProperties): EmbeddingProvider {
        val llmEmbedding = props.llm.resolveEmbedding()
        return when (props.embeddingProvider.trim().lowercase()) {
            "hash" -> HashEmbeddingProvider(props.embeddingDimensions)
            "openai" -> {
                val apiKey = props.openAiApiKey?.takeIf { it.isNotBlank() }
                    ?: llmEmbedding?.apiKey
                    ?: System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
                    ?: error(
                        "OpenAI embedding provider requires rag.openAiApiKey or OPENAI_API_KEY env variable"
                    )

                OpenAiEmbeddingProvider(
                    apiKey = apiKey,
                    model = llmEmbedding?.model ?: props.openAiModel,
                    dimensions = props.embeddingDimensions,
                    baseUrl = llmEmbedding?.baseUrl ?: props.openAiBaseUrl
                )
            }

            else -> error(
                "Unsupported rag.embeddingProvider='${props.embeddingProvider}'. Supported: hash, openai"
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun chunker(props: RagProperties): Chunker {
        return when (props.chunkerType.trim().lowercase()) {
            "basic" -> Chunkers.basic(
                maxChars = props.basicMaxChars,
                overlapChars = props.basicOverlapChars
            )

            "sliding", "sliding-window" -> SlidingWindowChunking(
                windowSize = props.slidingWindowSize,
                overlap = props.slidingOverlap
            )

            "adaptive" -> AdaptiveChunking(
                sectionRegex = props.adaptiveSectionRegex,
                minChunkSize = props.adaptiveMinChunkSize,
                maxChunkSize = props.adaptiveMaxChunkSize
            )

            "regex" -> RegexChunking(
                splitPattern = props.regexSplitPattern,
                groupByPattern = props.regexGroupByPattern
            )

            else -> error(
                "Unsupported rag.chunkerType='${props.chunkerType}'. Supported: basic, sliding, adaptive, regex"
            )
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun ragEngine(
        config: RagConfig,
        embeddingProvider: EmbeddingProvider,
        chunker: Chunker,
        props: RagProperties,
        statsCacheStore: StatsCacheStore<IndexStats>,
        components: RagComponents
    ): RagEngine = RagEngine.open(
        config,
        embeddingProvider,
        chunker,
        statsCacheStore,
        when (props.statsCacheStoreType.trim().lowercase()) {
            "file", "json-file" -> "file_json"
            else -> "memory_only"
        },
        components
    )

    @Bean
    @ConditionalOnMissingBean
    fun ragComponents(): RagComponents = RagComponents()

    @Bean(destroyMethod = "close")
    fun providerHealthAutoExportLifecycle(
        props: RagProperties,
        exportHooks: ObjectProvider<ProviderHealthExportHook>
    ): ProviderHealthAutoExportLifecycle {
        val hooks = buildList {
            props.providerHealthAutoExportPath?.takeIf { it.isNotBlank() }?.let {
                add(
                    createProviderHealthExportHook(
                        path = Path.of(it),
                        format = props.providerHealthAutoExportFormat,
                        retainCount = props.providerHealthAutoExportRetainCount,
                        includeScopeSuffix = props.providerHealthAutoExportIncludeScopeSuffix
                    )
                )
            }
            props.providerHealthAutoExportPushUrl?.takeIf { it.isNotBlank() }?.let {
                add(
                    createProviderHealthPushExportHook(
                        pushUrl = it,
                        format = props.providerHealthAutoExportPushFormat,
                        timeoutMillis = props.providerHealthAutoExportPushTimeoutMillis,
                        headers = props.providerHealthAutoExportPushHeaders,
                        maxRetries = props.providerHealthAutoExportPushMaxRetries,
                        retryBackoffMillis = props.providerHealthAutoExportPushRetryBackoffMillis,
                        deadLetterPath = props.providerHealthAutoExportPushDeadLetterPath?.let(Path::of),
                        deadLetterRetainCount = props.providerHealthAutoExportPushDeadLetterRetainCount,
                        hmacSecret = props.providerHealthAutoExportPushHmacSecret,
                        hmacHeaderName = props.providerHealthAutoExportPushHmacHeaderName,
                        timestampHeaderName = props.providerHealthAutoExportPushTimestampHeaderName,
                        asyncQueueEnabled = props.providerHealthAutoExportPushAsyncEnabled,
                        asyncQueueCapacity = props.providerHealthAutoExportPushQueueCapacity,
                        asyncQueueDropOldest = props.providerHealthAutoExportPushDropOldestOnOverflow
                    )
                )
            }
            addAll(exportHooks.orderedStream().toList())
        }
        val hook = when (hooks.size) {
            0 -> null
            1 -> hooks.single()
            else -> CompositeProviderHealthExportHook(hooks)
        }
        return ProviderHealthAutoExportLifecycle(
            hook = hook,
            intervalMillis = props.providerHealthAutoExportIntervalMillis,
            recentWindowMillis = props.providerHealthAutoExportWindowMillis
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun statsCacheStore(props: RagProperties): StatsCacheStore<IndexStats> {
        return when (props.statsCacheStoreType.trim().lowercase()) {
            "memory", "in-memory" -> InMemoryStatsCacheStore()
            "file", "json-file" -> {
                val mapper = jacksonObjectMapper()
                JsonFileStatsCacheStore(
                    path = props.resolvedStatsCacheFilePath(),
                    encode = { mapper.writeValueAsString(it) },
                    decode = { mapper.readValue(it, IndexStats::class.java) },
                    options = JsonFileStatsCacheStore.Options(
                        maxBytes = props.statsCacheFileMaxBytes,
                        rotateCount = props.statsCacheFileRotateCount,
                        cleanupOnStart = props.statsCacheFileCleanupOnStart
                    )
                )
            }

            else -> error(
                "Unsupported rag.statsCacheStoreType='${props.statsCacheStoreType}'. Supported: memory, file"
            )
        }
    }
}
