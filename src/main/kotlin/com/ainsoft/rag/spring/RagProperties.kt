package com.ainsoft.rag.spring

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag")
data class RagProperties(
    val indexPath: String = "./rag-index",
    val storeChunkText: Boolean = true,
    val uploadMaxBytes: Long = 10 * 1024 * 1024,
    val uploadAllowedContentTypes: List<String> = listOf(
        "text/plain",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    ),
    val sourceLoadTimeoutMillis: Long = 10_000,
    val sourceLoadAllowHosts: List<String> = emptyList(),
    val sourceLoadAuthHeaders: Map<String, String> = emptyMap(),
    val sourceLoadInsecureSkipTlsVerify: Boolean = false,
    val sourceLoadCustomCaCertPath: String? = null,
    val sourceLoadDefaultProfile: String? = null,
    val sourceLoadProfiles: Map<String, SourceLoadProfile> = emptyMap(),
    val statsCacheTtlMillis: Long = 30_000,
    val statsCacheMaxEntries: Int = 32,
    val statsCacheMaxEntriesPerTenant: Int = 4,
    val contextualRetrievalEnabled: Boolean = true,
    val contextualIncludeDocId: Boolean = true,
    val contextualIncludeMetadataContext: Boolean = true,
    val contextualMaxDocumentSummaryChars: Int = 240,
    val rerankerEnabled: Boolean = true,
    val rerankerType: String = "heuristic",
    val rerankerTopN: Int = 24,
    val rerankerAlpha: Double = 0.65,
    val rerankerApiBaseUrl: String = "https://api.cohere.com",
    val rerankerApiKey: String? = null,
    val rerankerModel: String = "rerank-v3.5",
    val rerankerRequestTimeoutMillis: Long = 30_000,
    val rerankerOnnxQueryInputName: String? = null,
    val rerankerOnnxDocumentInputName: String? = null,
    val rerankerOnnxOutputName: String? = null,
    val rerankerOnnxExpectedDimensions: Int = 0,
    val rerankerOnnxExpectedInputSchema: String? = null,
    val rerankerOnnxExpectedTokenizer: String? = null,
    val rerankerOnnxExpectedScoreSemantics: String? = null,
    val rerankerOnnxExpectedTokenizerVocabChecksum: String? = null,
    val rerankerOnnxTokenizerSchemaChecksum: String? = null,
    val rerankerOnnxModelContractChecksum: String? = null,
    val correctiveRetrievalEnabled: Boolean = true,
    val correctiveMinConfidence: Double = 0.08,
    val correctiveMinResultsBeforeSkip: Int = 3,
    val correctiveExpandedCandidateMultiplier: Int = 2,
    val queryRewriteEnabled: Boolean = true,
    val queryRewriterType: String = "heuristic",
    val queryRewriteApiBaseUrl: String = "https://api.openai.com/v1",
    val queryRewriteApiKey: String? = null,
    val queryRewriteModel: String = "gpt-4o-mini",
    val queryRewriteRequestTimeoutMillis: Long = 30_000,
    val hierarchicalSummariesEnabled: Boolean = true,
    val hierarchicalMaxSectionSummaries: Int = 4,
    val hierarchicalMaxSummaryChars: Int = 280,
    val hierarchicalTargetChunksPerSection: Int = 3,
    val summarizerType: String = "rule-based",
    val summarizerApiBaseUrl: String = "https://api.openai.com/v1",
    val summarizerApiKey: String? = null,
    val summarizerModel: String = "gpt-4o-mini",
    val summarizerRequestTimeoutMillis: Long = 30_000,
    val statsCacheStoreType: String = "memory",
    val statsCacheFilePath: String? = null,
    val statsCacheFileMaxBytes: Long = 0L,
    val statsCacheFileRotateCount: Int = 2,
    val statsCacheFileCleanupOnStart: Boolean = false,
    val providerHealthAutoExportPath: String? = null,
    val providerHealthAutoExportIntervalMillis: Long = 0L,
    val providerHealthAutoExportWindowMillis: Long? = null,
    val providerHealthAutoExportFormat: String = "json",
    val providerHealthAutoExportRetainCount: Int = 5,
    val providerHealthAutoExportIncludeScopeSuffix: Boolean = false,
    val providerHealthAutoExportPushUrl: String? = null,
    val providerHealthAutoExportPushFormat: String = "json",
    val providerHealthAutoExportPushTimeoutMillis: Long = 10_000,
    val providerHealthAutoExportPushHeaders: Map<String, String> = emptyMap(),
    val providerHealthAutoExportPushMaxRetries: Int = 2,
    val providerHealthAutoExportPushRetryBackoffMillis: Long = 250L,
    val providerHealthAutoExportPushDeadLetterPath: String? = null,
    val providerHealthAutoExportPushDeadLetterRetainCount: Int = 5,
    val providerHealthAutoExportPushAsyncEnabled: Boolean = false,
    val providerHealthAutoExportPushQueueCapacity: Int = 32,
    val providerHealthAutoExportPushDropOldestOnOverflow: Boolean = true,
    val providerHealthAutoExportPushHmacSecret: String? = null,
    val providerHealthAutoExportPushHmacHeaderName: String = "X-Rag-Signature",
    val providerHealthAutoExportPushTimestampHeaderName: String = "X-Rag-Timestamp",
    val chunkerType: String = "basic",
    val basicMaxChars: Int = 1800,
    val basicOverlapChars: Int = 200,
    val slidingWindowSize: Int = 240,
    val slidingOverlap: Int = 40,
    val adaptiveSectionRegex: String = com.ainsoft.rag.chunking.Chunkers.DEFAULT_ADAPTIVE_SECTION_REGEX,
    val adaptiveMinChunkSize: Int = 200,
    val adaptiveMaxChunkSize: Int = 1000,
    val regexSplitPattern: String = "(?m)^##?\\s+",
    val regexGroupByPattern: String? = null,
    val embeddingDimensions: Int = 256,
    val embeddingProvider: String = "hash",
    val openAiApiKey: String? = null,
    val openAiModel: String = "text-embedding-3-small",
    val openAiBaseUrl: String = "https://api.openai.com/v1"
) {
    fun resolveSourceLoadProfile(profileName: String?): SourceLoadProfile {
        val requestedName = profileName?.takeIf { it.isNotBlank() } ?: sourceLoadDefaultProfile
        val override = requestedName?.let {
            sourceLoadProfiles[it] ?: error("Unknown rag.sourceLoad profile '$it'")
        }
        return SourceLoadProfile(
            timeoutMillis = override?.timeoutMillis ?: sourceLoadTimeoutMillis,
            allowHosts = override?.allowHosts ?: sourceLoadAllowHosts,
            authHeaders = override?.authHeaders ?: sourceLoadAuthHeaders,
            insecureSkipTlsVerify = override?.insecureSkipTlsVerify ?: sourceLoadInsecureSkipTlsVerify,
            customCaCertPath = override?.customCaCertPath ?: sourceLoadCustomCaCertPath
        )
    }

    fun resolvedStatsCacheFilePath(): Path =
        Path.of(statsCacheFilePath ?: Path.of(indexPath).resolve("stats-cache.json").toString())
}

data class SourceLoadProfile(
    val timeoutMillis: Long? = null,
    val allowHosts: List<String>? = null,
    val authHeaders: Map<String, String>? = null,
    val insecureSkipTlsVerify: Boolean? = null,
    val customCaCertPath: String? = null
)
