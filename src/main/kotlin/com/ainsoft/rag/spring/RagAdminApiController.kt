package com.ainsoft.rag.spring

import com.ainsoft.rag.api.Acl
import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.api.PageMarker
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchDiagnostics
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.ainsoft.rag.impl.providerTelemetrySnapshot
import com.ainsoft.rag.parsers.PlainTextParser
import com.ainsoft.rag.parsers.TikaDocumentParser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.Charset
import java.time.Instant

@RestController
@RequestMapping("\${rag.admin.api-base-path:/api/rag/admin}")
class RagAdminApiController(
    private val engine: RagEngine,
    private val properties: RagProperties,
    private val ragConfig: RagConfig,
    private val embeddingProvider: EmbeddingProvider
) {
    private val plainTextParser = PlainTextParser()
    private val tikaDocumentParser = TikaDocumentParser()

    @PostMapping("/ingest")
    fun ingest(@RequestBody request: RagAdminIngestRequest): RagAdminIngestResponse {
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        require(request.text.isNotBlank()) { "text must not be blank" }
        val normalizedTenantId = normalizeIdentifier(request.tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(request.docId, "docId")
        engine.upsert(
            UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = request.text,
                metadata = request.metadata,
                acl = Acl(request.acl),
                sourceUri = request.sourceUri,
                page = request.page,
                pageMarkers = request.pageMarkers.orEmpty().map {
                    PageMarker(
                        page = it.page,
                        offsetStart = it.offsetStart,
                        offsetEnd = it.offsetEnd
                    )
                }
            )
        )
        return RagAdminIngestResponse(
            status = "ingested",
            tenantId = normalizedTenantId,
            docId = normalizedDocId
        )
    }

    @PostMapping(
        "/ingest-file",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun ingestFile(
        @RequestParam tenantId: String,
        @RequestParam docId: String,
        @RequestParam acl: List<String>,
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) sourceUri: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false, defaultValue = "UTF-8") charset: String,
        @RequestParam(required = false) metadata: String?
    ): RagAdminIngestResponse {
        val normalizedTenantId = normalizeIdentifier(tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(docId, "docId")
        require(acl.isNotEmpty()) { "acl must not be empty" }
        require(!file.isEmpty) { "file must not be empty" }
        require(file.size <= properties.uploadMaxBytes) {
            "file exceeds uploadMaxBytes=${properties.uploadMaxBytes}"
        }
        val contentType = file.contentType?.trim().orEmpty()
        require(
            contentType.isBlank() ||
                properties.uploadAllowedContentTypes.isEmpty() ||
                contentType in properties.uploadAllowedContentTypes
        ) {
            "unsupported contentType='$contentType'"
        }

        val normalizedFilename = normalizeFilename(file.originalFilename)
        val effectiveSourceUri = sourceUri ?: normalizedFilename?.let { "upload://$it" }
        val parsed = if (isBinaryDoc(file.originalFilename)) {
            tikaDocumentParser.parseBytes(file.bytes, sourceUri = effectiveSourceUri, page = page)
        } else {
            val text = file.bytes.toString(Charset.forName(charset))
            plainTextParser.parseText(text, sourceUri = effectiveSourceUri, page = page)
        }

        engine.upsert(
            UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = parsed.normalizedText,
                metadata = parseMetadata(metadata),
                acl = Acl(acl),
                sourceUri = parsed.sourceUri,
                page = parsed.page,
                pageMarkers = parsed.pageMarkers
            )
        )
        return RagAdminIngestResponse(
            status = "ingested",
            tenantId = normalizedTenantId,
            docId = normalizedDocId
        )
    }

    @PostMapping("/search")
    fun search(@RequestBody request: RagAdminSearchRequest): RagAdminSearchResponse {
        require(request.principals.isNotEmpty()) { "principals must not be empty" }
        require(request.query.isNotBlank()) { "query must not be blank" }

        val response = engine.searchDetailed(
            SearchRequest(
                tenantId = request.tenantId,
                principals = request.principals,
                query = request.query,
                topK = request.topK,
                filter = request.filter
            )
        )
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = request.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }

        return RagAdminSearchResponse(
            tenantId = request.tenantId,
            query = request.query,
            hits = response.hits.map { hit ->
                RagAdminSearchHitResponse(
                    docId = hit.source.docId,
                    chunkId = hit.source.chunkId,
                    score = hit.score,
                    text = hit.text,
                    contentKind = hit.contentKind,
                    page = hit.source.page,
                    sourceUri = hit.source.sourceUri,
                    offsetStart = hit.source.offsetStart,
                    offsetEnd = hit.source.offsetEnd,
                    metadata = hit.metadata
                )
            },
            telemetry = response.telemetry.toResponse(),
            providerTelemetry = globalProviderHealth.toResponse(request.providerHealthDetail),
            recentProviderWindowMillis = request.recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(request.providerHealthDetail)
        )
    }

    @PostMapping("/diagnose-search")
    fun diagnoseSearch(@RequestBody request: RagAdminSearchRequest): RagAdminSearchDiagnosticsResponse {
        require(request.principals.isNotEmpty()) { "principals must not be empty" }
        require(request.query.isNotBlank()) { "query must not be blank" }

        val searchRequest = SearchRequest(
            tenantId = request.tenantId,
            principals = request.principals,
            query = request.query,
            topK = request.topK,
            filter = request.filter
        )
        val diagnostics = SearchDiagnostics.analyze(
            indexPath = ragConfig.indexPath,
            embeddingProvider = embeddingProvider,
            request = searchRequest,
            maxSamples = request.diagnosticMaxSamples,
            scoreThreshold = request.diagnosticScoreThreshold
        )
        val search = engine.searchDetailed(searchRequest)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = request.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }

        return RagAdminSearchDiagnosticsResponse(
            tenantId = request.tenantId,
            query = request.query,
            tenantDocs = diagnostics.tenantDocs,
            lexicalMatchesWithoutAcl = diagnostics.lexicalMatchesWithoutAcl,
            vectorMatchesWithoutAcl = diagnostics.vectorMatchesWithoutAcl,
            lexicalMatchesWithAcl = diagnostics.lexicalMatchesWithAcl,
            vectorMatchesWithAcl = diagnostics.vectorMatchesWithAcl,
            lexicalSampleDocIdsWithoutAcl = diagnostics.lexicalSampleDocIdsWithoutAcl,
            vectorSampleDocIdsWithoutAcl = diagnostics.vectorSampleDocIdsWithoutAcl,
            lexicalSampleDocIdsWithAcl = diagnostics.lexicalSampleDocIdsWithAcl,
            vectorSampleDocIdsWithAcl = diagnostics.vectorSampleDocIdsWithAcl,
            telemetry = search.telemetry.toResponse(),
            providerTelemetry = globalProviderHealth.toResponse(request.providerHealthDetail),
            recentProviderWindowMillis = request.recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(request.providerHealthDetail)
        )
    }

    @GetMapping("/stats")
    fun stats(
        @RequestParam tenantId: String?,
        @RequestParam(required = false) recentProviderWindowMillis: Long?
    ): RagAdminStatsResponse {
        val stats = engine.stats(tenantId)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return stats.toResponse(
            providerTelemetry = globalProviderHealth.toResponse(),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse()
        )
    }

    @GetMapping("/provider-health")
    fun providerHealth(
        @RequestParam(required = false) recentProviderWindowMillis: Long?,
        @RequestParam(required = false, defaultValue = "true") detailed: Boolean
    ): RagAdminProviderHealthResponse {
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return RagAdminProviderHealthResponse(
            providerTelemetry = globalProviderHealth.toResponse(detailed),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(detailed)
        )
    }

    private fun parseMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',', '\n')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.length - 1) {
                    null
                } else {
                    entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
                }
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
    }

    private fun normalizeIdentifier(raw: String, fieldName: String): String {
        val normalized = raw.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._:-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        require(normalized.isNotBlank()) { "$fieldName must not be blank after normalization" }
        return normalized
    }

    private fun normalizeFilename(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun isBinaryDoc(filename: String?): Boolean {
        val name = filename?.lowercase() ?: return false
        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".pptx")
    }
}

data class RagAdminIngestRequest(
    val tenantId: String,
    val docId: String,
    val text: String,
    val acl: List<String>,
    val metadata: Map<String, String> = emptyMap(),
    val sourceUri: String? = null,
    val page: Int? = null,
    val pageMarkers: List<RagAdminPageMarkerRequest>? = null
)

data class RagAdminPageMarkerRequest(
    val page: Int,
    val offsetStart: Int,
    val offsetEnd: Int
)

data class RagAdminIngestResponse(
    val status: String,
    val tenantId: String,
    val docId: String
)

data class RagAdminSearchRequest(
    val tenantId: String,
    val principals: List<String>,
    val query: String,
    val topK: Int = 8,
    val filter: Map<String, String> = emptyMap(),
    val providerHealthDetail: Boolean = true,
    val recentProviderWindowMillis: Long? = null,
    val diagnosticScoreThreshold: Double = Double.NEGATIVE_INFINITY,
    val diagnosticMaxSamples: Int = 5
)

data class RagAdminSearchResponse(
    val tenantId: String,
    val query: String,
    val hits: List<RagAdminSearchHitResponse>,
    val telemetry: RagAdminSearchTelemetryResponse,
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null
)

data class RagAdminSearchHitResponse(
    val docId: String,
    val chunkId: String,
    val score: Double,
    val text: String?,
    val contentKind: String,
    val page: Int?,
    val sourceUri: String?,
    val offsetStart: Int?,
    val offsetEnd: Int?,
    val metadata: Map<String, String>
)

data class RagAdminSearchDiagnosticsResponse(
    val tenantId: String,
    val query: String,
    val tenantDocs: Int,
    val lexicalMatchesWithoutAcl: Int,
    val vectorMatchesWithoutAcl: Int,
    val lexicalMatchesWithAcl: Int,
    val vectorMatchesWithAcl: Int,
    val lexicalSampleDocIdsWithoutAcl: List<String>,
    val vectorSampleDocIdsWithoutAcl: List<String>,
    val lexicalSampleDocIdsWithAcl: List<String>,
    val vectorSampleDocIdsWithAcl: List<String>,
    val telemetry: RagAdminSearchTelemetryResponse,
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null
)

data class RagAdminSearchTelemetryResponse(
    val executedQuery: String,
    val originalQuery: String,
    val queryRewriterType: String?,
    val queryRewriteApplied: Boolean,
    val correctiveRetryApplied: Boolean,
    val initialConfidence: Double?,
    val finalConfidence: Double?,
    val rerankerType: String?,
    val summaryCandidatesUsed: Boolean,
    val providerFallbackApplied: Boolean,
    val providerFallbackReason: String?,
    val providersUsed: List<String>,
    val notes: List<String>
)

data class RagAdminStatsResponse(
    val tenantId: String?,
    val docs: Long,
    val chunks: Long,
    val snapshotCount: Int,
    val indexSizeBytes: Long,
    val lastCommitEpochMillis: Long?,
    val lastCommitIso: String?,
    val statsCacheEntries: Int,
    val statsCacheHitCount: Long,
    val statsCacheMissCount: Long,
    val statsCacheEvictionCount: Long,
    val statsCacheExpiredCount: Long,
    val statsCacheHitRatePct: Double,
    val statsCacheTtlMillis: Long,
    val statsCacheMaxEntries: Int,
    val statsCacheMaxEntriesPerTenant: Int,
    val statsCachePersistenceMode: String,
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null
)

data class RagAdminProviderHealthResponse(
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null
)

data class ProviderTelemetryResponse(
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double,
    val endpoints: List<ProviderEndpointResponse>,
    val tenantScopes: List<ProviderScopeResponse>,
    val commandScopes: List<ProviderScopeResponse>
)

data class ProviderEndpointResponse(
    val provider: String,
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double,
    val circuitOpen: Boolean,
    val lastError: String?
)

data class ProviderScopeResponse(
    val scope: String,
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double
)

private fun com.ainsoft.rag.api.SearchTelemetry.toResponse(): RagAdminSearchTelemetryResponse =
    RagAdminSearchTelemetryResponse(
        executedQuery = executedQuery,
        originalQuery = originalQuery,
        queryRewriterType = queryRewriterType,
        queryRewriteApplied = queryRewriteApplied,
        correctiveRetryApplied = correctiveRetryApplied,
        initialConfidence = initialConfidence,
        finalConfidence = finalConfidence,
        rerankerType = rerankerType,
        summaryCandidatesUsed = summaryCandidatesUsed,
        providerFallbackApplied = providerFallbackApplied,
        providerFallbackReason = providerFallbackReason,
        providersUsed = providersUsed,
        notes = notes
    )

private fun com.ainsoft.rag.api.ProviderTelemetryStats.toResponse(
    detailed: Boolean = true
): ProviderTelemetryResponse = ProviderTelemetryResponse(
    requestCount = requestCount,
    successCount = successCount,
    failureCount = failureCount,
    retryCount = retryCount,
    circuitOpenCount = circuitOpenCount,
    avgLatencyMillis = avgLatencyMillis,
    p95LatencyMillis = p95LatencyMillis,
    endpoints = if (detailed) {
        endpoints.map { endpoint ->
            ProviderEndpointResponse(
                provider = endpoint.provider,
                requestCount = endpoint.requestCount,
                successCount = endpoint.successCount,
                failureCount = endpoint.failureCount,
                retryCount = endpoint.retryCount,
                circuitOpenCount = endpoint.circuitOpenCount,
                avgLatencyMillis = endpoint.avgLatencyMillis,
                p95LatencyMillis = endpoint.p95LatencyMillis,
                circuitOpen = endpoint.circuitOpen,
                lastError = endpoint.lastError
            )
        }
    } else {
        emptyList()
    },
    tenantScopes = if (detailed) {
        tenantScopes.map { scope ->
            ProviderScopeResponse(
                scope = scope.scope,
                requestCount = scope.requestCount,
                successCount = scope.successCount,
                failureCount = scope.failureCount,
                retryCount = scope.retryCount,
                circuitOpenCount = scope.circuitOpenCount,
                avgLatencyMillis = scope.avgLatencyMillis,
                p95LatencyMillis = scope.p95LatencyMillis
            )
        }
    } else {
        emptyList()
    },
    commandScopes = if (detailed) {
        commandScopes.map { scope ->
            ProviderScopeResponse(
                scope = scope.scope,
                requestCount = scope.requestCount,
                successCount = scope.successCount,
                failureCount = scope.failureCount,
                retryCount = scope.retryCount,
                circuitOpenCount = scope.circuitOpenCount,
                avgLatencyMillis = scope.avgLatencyMillis,
                p95LatencyMillis = scope.p95LatencyMillis
            )
        }
    } else {
        emptyList()
    }
)

private fun IndexStats.toResponse(
    providerTelemetry: ProviderTelemetryResponse,
    recentProviderWindowMillis: Long?,
    recentProviderTelemetry: ProviderTelemetryResponse?
): RagAdminStatsResponse = RagAdminStatsResponse(
    tenantId = tenantId,
    docs = docs,
    chunks = chunks,
    snapshotCount = snapshotCount,
    indexSizeBytes = indexSizeBytes,
    lastCommitEpochMillis = lastCommitEpochMillis,
    lastCommitIso = lastCommitEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
    statsCacheEntries = statsCacheEntries,
    statsCacheHitCount = statsCacheHitCount,
    statsCacheMissCount = statsCacheMissCount,
    statsCacheEvictionCount = statsCacheEvictionCount,
    statsCacheExpiredCount = statsCacheExpiredCount,
    statsCacheHitRatePct = formatHitRate(statsCacheHitCount, statsCacheMissCount),
    statsCacheTtlMillis = statsCacheTtlMillis,
    statsCacheMaxEntries = statsCacheMaxEntries,
    statsCacheMaxEntriesPerTenant = statsCacheMaxEntriesPerTenant,
    statsCachePersistenceMode = statsCachePersistenceMode,
    providerTelemetry = providerTelemetry,
    recentProviderWindowMillis = recentProviderWindowMillis,
    recentProviderTelemetry = recentProviderTelemetry
)

private fun formatHitRate(hitCount: Long, missCount: Long): Double {
    val total = hitCount + missCount
    if (total == 0L) return 0.0
    return hitCount.toDouble() * 100.0 / total.toDouble()
}
