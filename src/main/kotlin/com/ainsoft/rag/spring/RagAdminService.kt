package com.ainsoft.rag.spring

import com.ainsoft.rag.api.Acl
import com.ainsoft.rag.api.PageMarker
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.RagEngineMaintenance
import com.ainsoft.rag.api.RagEngineMaintenanceResult
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.ainsoft.rag.graph.GraphProjectionService
import com.ainsoft.rag.graph.GraphStore
import com.ainsoft.rag.impl.IndexSchema
import com.ainsoft.rag.impl.providerTelemetrySnapshot
import com.ainsoft.rag.parsers.PlainTextParser
import com.ainsoft.rag.parsers.TikaDocumentParser
import com.ainsoft.rag.support.SourceLoadOptions
import com.ainsoft.rag.support.SourceLoaders
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.MultiDocValues
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.Term

interface RagAdminUserAuditSink {
    fun recordUserAudit(
        action: String,
        actorUsername: String?,
        actorRole: String?,
        targetUsername: String,
        success: Boolean,
        message: String? = null,
        details: Map<String, String> = emptyMap()
    )
}

class RagAdminService(
    private val engine: RagEngine,
    private val properties: RagProperties,
    private val adminProperties: RagAdminProperties,
    private val ragConfig: RagConfig,
    private val embeddingProvider: EmbeddingProvider,
    private val graphStore: GraphStore,
    private val graphProjectionService: GraphProjectionService
) : RagAdminUserAuditSink {
    private val objectMapper = jacksonObjectMapper()
    private val plainTextParser = PlainTextParser()
    private val tikaDocumentParser = TikaDocumentParser()
    private val webCrawler = RagAdminWebCrawler()
    private val searchAudits = ArrayDeque<RagAdminSearchAuditEntry>()
    private val jobHistory = ArrayDeque<RagAdminJobHistoryEntry>()
    private val accessAudits = ArrayDeque<RagAdminAccessAuditEntry>()
    private val userAudits = ArrayDeque<RagAdminUserAuditEntry>()
    private val providerHistory = ArrayDeque<RagAdminProviderHistoryEntry>()
    private val ingestSnapshotCache = ConcurrentHashMap<String, IngestSnapshot>()

    fun recordAccess(
        path: String,
        method: String,
        role: String?,
        granted: Boolean,
        message: String?
    ) {
        append(accessAudits, adminProperties.historyMaxEntries) {
            RagAdminAccessAuditEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                path = path,
                method = method,
                role = role,
                granted = granted,
                message = message
            )
        }
    }

    fun recordSearchAudit(
        auditType: String,
        role: String?,
        request: RagAdminSearchRequest,
        resultCount: Int,
        telemetry: RagAdminSearchTelemetryResponse
    ) {
        append(searchAudits, adminProperties.historyMaxEntries) {
            RagAdminSearchAuditEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                auditType = auditType,
                tenantId = request.tenantId,
                principals = request.principals,
                query = request.query,
                topK = request.topK,
                filter = request.filter,
                resultCount = resultCount,
                role = role,
                executedQuery = telemetry.executedQuery,
                queryRewriteApplied = telemetry.queryRewriteApplied,
                correctiveRetryApplied = telemetry.correctiveRetryApplied,
                providerFallbackApplied = telemetry.providerFallbackApplied,
                providerFallbackReason = telemetry.providerFallbackReason,
                providersUsed = telemetry.providersUsed,
                notes = telemetry.notes
            )
        }
        recordProviderSnapshot("search-audit")
    }

    fun startJob(
        jobType: String,
        tenantId: String?,
        role: String?,
        description: String,
        payload: Map<String, Any?>,
        retryKind: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        append(jobHistory, adminProperties.historyMaxEntries) {
            RagAdminJobHistoryEntry(
                id = id,
                timestampEpochMillis = System.currentTimeMillis(),
                jobType = jobType,
                tenantId = tenantId,
                role = role,
                status = "RUNNING",
                description = description,
                payload = payload,
                retryKind = retryKind,
                retrySupported = retryKind != null
            )
        }
        return id
    }

    fun completeJob(
        jobId: String,
        status: String,
        message: String? = null
    ) {
        synchronized(jobHistory) {
            val updated = jobHistory.map { entry ->
                if (entry.id == jobId) {
                    entry.copy(
                        status = status,
                        message = message,
                        completedAtEpochMillis = System.currentTimeMillis()
                    )
                } else {
                    entry
                }
            }
            jobHistory.clear()
            updated.forEach(jobHistory::addLast)
        }
    }

    fun listSearchAudits(limit: Int = adminProperties.historyMaxEntries): List<RagAdminSearchAuditEntry> =
        synchronized(searchAudits) { searchAudits.toList().sortedByDescending { it.timestampEpochMillis }.take(limit) }

    fun listJobHistory(limit: Int = adminProperties.historyMaxEntries): List<RagAdminJobHistoryEntry> =
        synchronized(jobHistory) { jobHistory.toList().sortedByDescending { it.timestampEpochMillis }.take(limit) }

    fun listAccessAudits(limit: Int = adminProperties.historyMaxEntries): List<RagAdminAccessAuditEntry> =
        synchronized(accessAudits) { accessAudits.toList().sortedByDescending { it.timestampEpochMillis }.take(limit) }

    override fun recordUserAudit(
        action: String,
        actorUsername: String?,
        actorRole: String?,
        targetUsername: String,
        success: Boolean,
        message: String?,
        details: Map<String, String>
    ) {
        append(userAudits, adminProperties.historyMaxEntries) {
            RagAdminUserAuditEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                action = action,
                actorUsername = actorUsername,
                actorRole = actorRole,
                targetUsername = targetUsername,
                success = success,
                message = message,
                details = details
            )
        }
    }

    fun listUserAudits(
        limit: Int = adminProperties.historyMaxEntries,
        offset: Int = 0,
        action: String? = null,
        query: String? = null,
        fromEpochMillis: Long? = null,
        toEpochMillis: Long? = null
    ): List<RagAdminUserAuditEntry> {
        val normalizedAction = action?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val items = synchronized(userAudits) {
            userAudits.toList()
                .sortedByDescending { it.timestampEpochMillis }
                .asSequence()
                .filter { entry -> normalizedAction == null || entry.action == normalizedAction }
                .filter { entry -> fromEpochMillis == null || entry.timestampEpochMillis >= fromEpochMillis }
                .filter { entry -> toEpochMillis == null || entry.timestampEpochMillis <= toEpochMillis }
                .filter { entry ->
                    normalizedQuery == null || listOf(
                        entry.action,
                        entry.actorUsername,
                        entry.actorRole,
                        entry.targetUsername,
                        entry.message,
                        entry.details.entries.joinToString(" ") { "${it.key}=${it.value}" }
                    ).filterNotNull().joinToString(" ").lowercase().contains(normalizedQuery)
                }
                .toList()
        }
        if (limit <= 0) {
            return emptyList()
        }
        val safeOffset = offset.coerceAtLeast(0)
        return items.drop(safeOffset).take(limit)
    }

    fun recordProviderSnapshot(source: String) {
        append(providerHistory, adminProperties.providerHistoryMaxEntries) {
            RagAdminProviderHistoryEntry(
                timestampEpochMillis = System.currentTimeMillis(),
                source = source,
                telemetry = providerTelemetrySnapshot().toResponse(true)
            )
        }
    }

    fun getProviderHistory(limit: Int = adminProperties.providerHistoryMaxEntries): RagAdminProviderHistoryResponse {
        recordProviderSnapshot("provider-history")
        val history = synchronized(providerHistory) {
            providerHistory.toList().sortedByDescending { it.timestampEpochMillis }.take(limit)
        }
        val fallbackEvents = listSearchAudits(limit * 2)
            .filter { it.providerFallbackApplied }
            .take(limit)
            .map {
                RagAdminProviderFallbackAuditEntry(
                    timestampEpochMillis = it.timestampEpochMillis,
                    tenantId = it.tenantId,
                    query = it.query,
                    role = it.role,
                    providerFallbackReason = it.providerFallbackReason,
                    providersUsed = it.providersUsed
                )
            }
        return RagAdminProviderHistoryResponse(
            current = providerTelemetrySnapshot().toResponse(true),
            history = history,
            fallbackEvents = fallbackEvents
        )
    }

    fun getConfigInspector(): RagAdminConfigInspectorResponse =
        RagAdminConfigInspectorResponse(
            rag = sanitizeMap(objectMapper.convertValue(properties, Map::class.java) as Map<String, Any?>),
            admin = sanitizeMap(objectMapper.convertValue(adminProperties, Map::class.java) as Map<String, Any?>)
        )

    fun listDocuments(
        tenantId: String?,
        query: String?,
        limit: Int = 100
    ): RagAdminDocumentListResponse = withReader { reader ->
        val docs = linkedMapOf<String, MutableDocumentAggregate>()
        iterateDocuments(reader, tenantId) { docId, stored, effectiveTenantId ->
            val aggregateKey = "$effectiveTenantId|${stored.get(IndexSchema.DOC_ID)}"
            val aggregate = docs.getOrPut(aggregateKey) {
                MutableDocumentAggregate(
                    tenantId = effectiveTenantId,
                    docId = stored.get(IndexSchema.DOC_ID).orEmpty()
                )
            }
            aggregate.chunkCount++
            aggregate.lastUpdatedEpochMillis = maxOf(aggregate.lastUpdatedEpochMillis, readUpdatedAt(reader, docId))
            aggregate.metadata.putAll(readMetadata(stored))
            aggregate.acl.addAll(readAcl(reader, docId))
            stored.get(IndexSchema.CONTENT_KIND)?.let(aggregate.contentKinds::add)
            stored.get(IndexSchema.SOURCE_URI)?.let(aggregate.sourceUris::add)
            stored.get(IndexSchema.PAGE)?.toIntOrNull()?.let(aggregate.pages::add)
            if (aggregate.previewText == null) {
                aggregate.previewText = stored.get(IndexSchema.BODY_STORED)?.take(280)
            }
        }

        val filtered = docs.values
            .filter {
                query.isNullOrBlank() ||
                    it.docId.contains(query, ignoreCase = true) ||
                    it.metadata.any { entry -> entry.key.contains(query, true) || entry.value.contains(query, true) }
            }
            .sortedWith(
                compareByDescending<MutableDocumentAggregate> { it.lastUpdatedEpochMillis }
                    .thenBy { it.docId }
            )
            .take(limit)
            .map { aggregate ->
                RagAdminDocumentSummary(
                    tenantId = aggregate.tenantId,
                    docId = aggregate.docId,
                    chunkCount = aggregate.chunkCount,
                    metadata = aggregate.metadata.toSortedMap(),
                    acl = aggregate.acl.toSortedSet().toList(),
                    sourceUris = aggregate.sourceUris.toSortedSet().toList(),
                    contentKinds = aggregate.contentKinds.toSortedSet().toList(),
                    pages = aggregate.pages.sorted(),
                    previewText = aggregate.previewText,
                    lastUpdatedEpochMillis = aggregate.lastUpdatedEpochMillis,
                    lastUpdatedIso = epochIso(aggregate.lastUpdatedEpochMillis)
                )
            }
        RagAdminDocumentListResponse(
            totalCount = filtered.size,
            items = filtered
        )
    }

    fun getDocumentDetail(tenantId: String, docId: String): RagAdminDocumentDetail = withReader { reader ->
        val chunks = mutableListOf<RagAdminDocumentChunk>()
        var lastUpdated = 0L
        val acl = linkedSetOf<String>()
        val metadata = linkedMapOf<String, String>()
        val sourceUris = linkedSetOf<String>()

        iterateDocuments(reader, tenantId) { luceneDocId, stored, effectiveTenantId ->
            val effectiveDocId = stored.get(IndexSchema.DOC_ID) ?: return@iterateDocuments
            if (effectiveTenantId != tenantId || effectiveDocId != docId) {
                return@iterateDocuments
            }
            val chunkAcl = readAcl(reader, luceneDocId)
            acl.addAll(chunkAcl)
            metadata.putAll(readMetadata(stored))
            stored.get(IndexSchema.SOURCE_URI)?.let(sourceUris::add)
            val updatedAt = readUpdatedAt(reader, luceneDocId)
            lastUpdated = maxOf(lastUpdated, updatedAt)
            chunks += RagAdminDocumentChunk(
                chunkId = stored.get(IndexSchema.CHUNK_ID).orEmpty(),
                contentKind = stored.get(IndexSchema.CONTENT_KIND).orEmpty(),
                page = stored.get(IndexSchema.PAGE)?.toIntOrNull(),
                sourceUri = stored.get(IndexSchema.SOURCE_URI),
                offsetStart = stored.get(IndexSchema.OFFSET_START)?.toIntOrNull(),
                offsetEnd = stored.get(IndexSchema.OFFSET_END)?.toIntOrNull(),
                text = stored.get(IndexSchema.BODY_STORED),
                metadata = readMetadata(stored)
            )
        }
        require(chunks.isNotEmpty()) { "document not found" }
        RagAdminDocumentDetail(
            tenantId = tenantId,
            docId = docId,
            metadata = metadata.toSortedMap(),
            acl = acl.toSortedSet().toList(),
            sourceUris = sourceUris.toSortedSet().toList(),
            chunkCount = chunks.size,
            lastUpdatedEpochMillis = lastUpdated,
            lastUpdatedIso = epochIso(lastUpdated),
            chunks = chunks.sortedWith(compareBy<RagAdminDocumentChunk> { it.page ?: Int.MAX_VALUE }.thenBy { it.chunkId })
        )
    }

    fun deleteDocument(tenantId: String, docId: String): RagAdminOperationResponse {
        val deleted = engine.deleteDocument(tenantId, docId)
        clearIngestSnapshotCache(tenantId, docId)
        runCatching { graphStore.deleteDocument(tenantId, docId) }
        recordProviderSnapshot("delete-document")
        return RagAdminOperationResponse(
            operation = "deleteDocument",
            success = true,
            message = "deleted $deleted chunks",
            affectedCount = deleted.toInt(),
            tenantId = tenantId
        )
    }

    fun reindexDocument(
        tenantId: String,
        docId: String,
        request: RagAdminDocumentReindexRequest
    ): RagAdminOperationResponse {
        val detail = getDocumentDetail(tenantId, docId)
        val effectiveAcl = request.acl ?: detail.acl
        val effectiveMetadata = request.metadata ?: detail.metadata
        val effectiveSourceUri = request.sourceUri ?: detail.sourceUris.firstOrNull()
        val requestedText = request.text
        val parsed = if (!requestedText.isNullOrBlank()) {
            plainTextParser.parseText(requestedText, sourceUri = effectiveSourceUri)
        } else {
            require(!effectiveSourceUri.isNullOrBlank()) { "sourceUri or text is required for reindex" }
            parseFromSourceUri(effectiveSourceUri, request.charset)
        }

        engine.upsert(
            UpsertDocumentRequest(
                tenantId = tenantId,
                docId = docId,
                normalizedText = parsed.normalizedText,
                metadata = effectiveMetadata,
                acl = Acl(effectiveAcl),
                sourceUri = parsed.sourceUri ?: effectiveSourceUri,
                page = parsed.page,
                pageMarkers = parsed.pageMarkers
            )
        )
        rememberIngestSnapshot(
            tenantId = tenantId,
            docId = docId,
            contentHash = documentContentHash(
                normalizedText = parsed.normalizedText,
                metadata = effectiveMetadata,
                acl = effectiveAcl,
                sourceUri = parsed.sourceUri ?: effectiveSourceUri,
                page = parsed.page,
                pageMarkers = parsed.pageMarkers
            ),
            preview = documentPreview(parsed.normalizedText)
        )
        recordProviderSnapshot("reindex-document")
        return RagAdminOperationResponse(
            operation = "reindexDocument",
            success = true,
            message = "document reindexed",
            affectedCount = 1,
            tenantId = tenantId
        )
    }

    fun webIngest(
        role: String?,
        request: RagAdminWebIngestRequest,
        progressSink: ((RagAdminWebIngestProgressEvent) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): RagAdminWebIngestResponse {
        val normalizedTenantId = normalizeIdentifier(request.tenantId, "tenantId")
        require(request.urls.isNotEmpty()) { "urls must not be empty" }
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        val effectiveMaxPages = (request.maxPages ?: 25).coerceAtLeast(1)
        val effectiveMaxDepth = (request.maxDepth ?: 1).coerceAtLeast(0)
        val resolvedProfile = properties.resolveSourceLoadProfile(request.sourceLoadProfile)

        val normalizedUrls = request.urls.map { normalizeWebUrl(it) }
        val jobId = startJob(
            jobType = "web-ingest",
            tenantId = normalizedTenantId,
            role = role,
            description = "web ingest for ${normalizedUrls.size} urls",
            payload = mapOf(
                "tenantId" to normalizedTenantId,
                "urls" to normalizedUrls,
                "allowedDomains" to request.allowedDomains,
                "maxPages" to effectiveMaxPages,
                "maxDepth" to effectiveMaxDepth,
                "sameHostOnly" to request.sameHostOnly,
                "incrementalIngest" to request.incrementalIngest,
                "sourceLoadProfile" to (request.sourceLoadProfile ?: "default"),
                "userAgent" to request.userAgent
            )
        )
        try {
            val crawl = webCrawler.crawl(
                seedUrls = normalizedUrls,
                allowedDomains = request.allowedDomains,
                maxPages = effectiveMaxPages,
                maxDepth = effectiveMaxDepth,
                sameHostOnly = request.sameHostOnly,
                charset = Charset.forName(request.charset),
                timeout = Duration.ofMillis(resolvedProfile.timeoutMillis ?: properties.sourceLoadTimeoutMillis),
                configuredAllowedHosts = resolvedProfile.allowHosts.orEmpty().toSet(),
                authHeaders = resolvedProfile.authHeaders.orEmpty(),
                insecureSkipTlsVerify = resolvedProfile.insecureSkipTlsVerify ?: false,
                customCaCertPath = resolvedProfile.customCaCertPath,
                respectRobotsTxt = request.respectRobotsTxt,
                userAgent = request.userAgent,
                progressSink = progressSink,
                isCancelled = isCancelled
            )

            val results = mutableListOf<RagAdminWebIngestPageResponse>()
            val progress = crawl.progress.toMutableList()
            val failures = crawl.failures.toMutableList()
            var changedPages = 0
            var skippedPages = 0

            fun emitProgress(event: RagAdminWebIngestProgressEvent) {
                progress += event
                progressSink?.invoke(event)
            }

            crawl.pages.forEachIndexed { index, page ->
                if (isCancelled()) {
                    throw WebIngestCancelledException()
                }
                try {
                    val normalizedDocId = webDocIdFromUrl(page.url)
                    val normalizedText = buildWebNormalizedText(page)
                    val contentHash = webContentHash(page)
                    val preview = webPreviewText(page.title, page.description, normalizedText)
                    val metadata = buildWebMetadata(request.metadata, page) + mapOf(
                        "crawl.contentHash" to contentHash,
                        "rag.contentHash" to contentHash,
                        "rag.preview" to preview
                    )
                    val existingSnapshot = if (request.incrementalIngest) {
                        resolveIngestSnapshot(normalizedTenantId, normalizedDocId)
                    } else {
                        null
                    }
                    if (request.incrementalIngest && existingSnapshot != null && existingSnapshot.contentHash == contentHash) {
                        skippedPages += 1
                        results += RagAdminWebIngestPageResponse(
                            url = page.url,
                            docId = normalizedDocId,
                            title = page.title,
                            depth = page.depth,
                            source = page.source,
                            status = "skipped",
                            message = "already ingested"
                        )
                        emitProgress(
                            RagAdminWebIngestProgressEvent(
                                phase = "skip-existing",
                                message = "Skipped unchanged page ${index + 1} of ${crawl.pages.size}",
                                url = page.url,
                                depth = page.depth,
                                current = index + 1,
                                total = crawl.pages.size
                            )
                        )
                        return@forEachIndexed
                    }
                    val wasChanged = request.incrementalIngest && existingSnapshot != null && existingSnapshot.contentHash != contentHash
                    val previousPreview = if (wasChanged) existingSnapshot.preview else null
                    val currentPreview = preview
                    val changeSummary = when {
                        wasChanged && previousPreview != null -> "content changed"
                        wasChanged -> "existing content changed"
                        else -> null
                    }
                    engine.upsert(
                        UpsertDocumentRequest(
                            tenantId = normalizedTenantId,
                            docId = normalizedDocId,
                            normalizedText = normalizedText,
                            metadata = metadata,
                            acl = Acl(request.acl),
                            sourceUri = page.url
                        )
                    )
                    runCatching {
                        graphStore.upsertProjection(
                            graphProjectionService.projectDocument(
                                tenantId = normalizedTenantId,
                                docId = normalizedDocId,
                                request = UpsertDocumentRequest(
                                    tenantId = normalizedTenantId,
                                    docId = normalizedDocId,
                                    normalizedText = normalizedText,
                                    metadata = metadata,
                                    acl = Acl(request.acl),
                                    sourceUri = page.url
                                )
                            )
                        )
                    }
                    rememberIngestSnapshot(normalizedTenantId, normalizedDocId, contentHash, currentPreview)
                    if (wasChanged) {
                        changedPages += 1
                    }
                    results += RagAdminWebIngestPageResponse(
                        url = page.url,
                        docId = normalizedDocId,
                        title = page.title,
                        depth = page.depth,
                        source = page.source,
                        status = if (wasChanged) "changed" else "ingested",
                        previousPreview = previousPreview,
                        currentPreview = currentPreview,
                        changeSummary = changeSummary
                    )
                    emitProgress(
                        RagAdminWebIngestProgressEvent(
                            phase = if (wasChanged) "changed" else "ingest",
                            message = if (wasChanged) {
                                "Changed page ${index + 1} of ${crawl.pages.size}"
                            } else {
                                "Ingested page ${index + 1} of ${crawl.pages.size}"
                            },
                            url = page.url,
                            depth = page.depth,
                            current = index + 1,
                            total = crawl.pages.size
                        )
                    )
                } catch (error: Exception) {
                    if (isCancelled()) {
                        throw WebIngestCancelledException()
                    }
                    failures += RagAdminWebIngestFailure(
                        url = page.url,
                        depth = page.depth,
                        message = error.message ?: "web ingest failed"
                    )
                    results += RagAdminWebIngestPageResponse(
                        url = page.url,
                        docId = webDocIdFromUrl(page.url),
                        title = page.title,
                        depth = page.depth,
                        source = page.source,
                        status = "failed",
                        message = error.message ?: "web ingest failed"
                    )
                    emitProgress(
                        RagAdminWebIngestProgressEvent(
                            phase = "ingest-failed",
                            message = error.message ?: "web ingest failed",
                            url = page.url,
                            depth = page.depth,
                            current = index + 1,
                            total = crawl.pages.size
                        )
                    )
                }
            }

            val status = when {
                results.isNotEmpty() && results.all { it.status == "skipped" } && failures.isEmpty() -> "skipped"
                results.any { it.status == "ingested" } && results.any { it.status == "changed" } && failures.isEmpty() -> "partial"
                results.any { it.status == "changed" } && failures.isEmpty() && skippedPages > 0 -> "partial"
                results.any { it.status == "changed" } && failures.isEmpty() && skippedPages == 0 && results.all { it.status == "changed" } -> "changed"
                results.any { it.status == "ingested" } && failures.isEmpty() && skippedPages > 0 -> "partial"
                failures.isEmpty() && results.any { it.status == "ingested" } -> "ingested"
                results.any { it.status == "ingested" || it.status == "changed" || it.status == "skipped" } -> "partial"
                else -> "failed"
            }
            completeJob(
                jobId,
                if (status == "failed") "FAILED" else "SUCCESS",
                "web ingest completed with ${results.count { it.status == "ingested" }} pages, ${changedPages} changed, ${skippedPages} skipped"
            )
            recordProviderSnapshot("web-ingest")
            return RagAdminWebIngestResponse(
                status = status,
                tenantId = normalizedTenantId,
                urls = normalizedUrls,
                crawledPages = crawl.pages.size,
                ingestedPages = results.count { it.status == "ingested" },
                changedPages = changedPages,
                skippedPages = skippedPages,
                results = results,
                progress = progress,
                failures = failures
            )
        } catch (error: WebIngestCancelledException) {
            completeJob(jobId, "CANCELLED", "web ingest cancelled")
            throw error
        }
    }

    fun sourcePreview(
        tenantId: String,
        docId: String,
        chunkId: String?,
        contextChars: Int,
        charsetName: String,
        profileName: String?
    ): RagAdminSourcePreviewResponse {
        val detail = getDocumentDetail(tenantId, docId)
        val chunk = chunkId?.let { requested -> detail.chunks.firstOrNull { it.chunkId == requested } } ?: detail.chunks.firstOrNull()
        require(chunk != null) { "chunk not found" }
        require(!chunk.sourceUri.isNullOrBlank()) { "sourceUri is missing" }
        require(chunk.offsetStart != null && chunk.offsetEnd != null) { "offsetStart/offsetEnd are required" }

        val resolvedProfile = properties.resolveSourceLoadProfile(profileName)
        val text = SourceLoaders.loadTextFromUri(
            uriValue = chunk.sourceUri,
            charset = Charset.forName(charsetName),
            options = SourceLoadOptions(
                authHeaders = resolvedProfile.authHeaders ?: emptyMap(),
                timeout = Duration.ofMillis(resolvedProfile.timeoutMillis ?: properties.sourceLoadTimeoutMillis),
                allowedHosts = resolvedProfile.allowHosts?.toSet() ?: emptySet(),
                insecureSkipTlsVerify = resolvedProfile.insecureSkipTlsVerify ?: false,
                customCaCertPath = resolvedProfile.customCaCertPath
            )
        ) ?: error("source load failed or returned no content")

        val start = chunk.offsetStart.coerceIn(0, text.length)
        val end = chunk.offsetEnd.coerceIn(0, text.length)
        val left = (start - contextChars).coerceAtLeast(0)
        val right = (end + contextChars).coerceAtMost(text.length)
        val preview = buildString {
            if (left > 0) append("...")
            append(text.substring(left, start))
            append("[[[")
            append(text.substring(start, end))
            append("]]]")
            append(text.substring(end, right))
            if (right < text.length) append("...")
        }

        return RagAdminSourcePreviewResponse(
            tenantId = tenantId,
            docId = docId,
            chunkId = chunk.chunkId,
            sourceUri = chunk.sourceUri,
            offsetStart = chunk.offsetStart,
            offsetEnd = chunk.offsetEnd,
            preview = preview
        )
    }

    private fun resolveIngestSnapshot(tenantId: String, docId: String): IngestSnapshot? {
        val key = webIngestCacheKey(tenantId, docId)
        ingestSnapshotCache[key]?.let { return it }
        val fromIndex = existingIngestSnapshot(tenantId, docId)
        if (fromIndex != null) {
            ingestSnapshotCache[key] = fromIndex
        }
        return fromIndex
    }

    private fun existingIngestSnapshot(tenantId: String, docId: String): IngestSnapshot? =
        runCatching {
            val detail = getDocumentDetail(tenantId, docId)
            val contentHash = detail.metadata["rag.contentHash"]
                ?.takeIf { it.isNotBlank() }
                ?: detail.metadata["crawl.contentHash"]?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val preview = detail.metadata["rag.preview"]?.takeIf { it.isNotBlank() } ?: when {
                detail.metadata.containsKey("crawl.title") || detail.metadata.containsKey("crawl.description") ->
                    webPreviewText(
                        detail.metadata["crawl.title"],
                        detail.metadata["crawl.description"],
                        detail.chunks.joinToString("\n\n") { it.text.orEmpty() }
                    )
                else -> documentPreview(detail.chunks.joinToString("\n\n") { it.text.orEmpty() })
            }
            IngestSnapshot(
                contentHash = contentHash,
                preview = preview
            )
        }.getOrNull()

    private fun rememberIngestSnapshot(tenantId: String, docId: String, contentHash: String, preview: String) {
        ingestSnapshotCache[webIngestCacheKey(tenantId, docId)] = IngestSnapshot(contentHash, preview)
    }

    private fun clearIngestSnapshotCache(tenantId: String) {
        val prefix = "$tenantId|"
        ingestSnapshotCache.keys.removeIf { it.startsWith(prefix) }
    }

    private fun clearIngestSnapshotCache(tenantId: String, docId: String) {
        ingestSnapshotCache.remove(webIngestCacheKey(tenantId, docId))
    }

    private fun webIngestCacheKey(tenantId: String, docId: String): String = "$tenantId|$docId"

    private fun documentContentHash(
        normalizedText: String,
        metadata: Map<String, String>,
        acl: List<String>,
        sourceUri: String?,
        page: Int?,
        pageMarkers: List<PageMarker>
    ): String {
        val canonical = listOf(
            normalizedText.trim(),
            metadata.toSortedMap().entries.joinToString("|") { (key, value) -> "$key=$value" },
            acl.sorted().joinToString("|"),
            sourceUri.orEmpty().trim(),
            page?.toString().orEmpty(),
            pageMarkers.joinToString("|") { "${it.page}:${it.offsetStart}:${it.offsetEnd}" }
        ).joinToString("\u0000")
        return digest(canonical)
    }

    private fun documentPreview(text: String, maxChars: Int = 320): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 3).trimEnd() + "..."
    }

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun ingestDocument(
        tenantId: String,
        docId: String,
        request: UpsertDocumentRequest,
        incrementalIngest: Boolean = true
    ): RagAdminIngestOutcome {
        val normalizedTenantId = normalizeIdentifier(tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(docId, "docId")
        val contentHash = documentContentHash(
            normalizedText = request.normalizedText,
            metadata = request.metadata,
            acl = request.acl.allow,
            sourceUri = request.sourceUri,
            page = request.page,
            pageMarkers = request.pageMarkers.orEmpty()
        )
        val preview = documentPreview(request.normalizedText)
        val existingSnapshot = if (incrementalIngest) resolveIngestSnapshot(normalizedTenantId, normalizedDocId) else null
        if (incrementalIngest && existingSnapshot != null && existingSnapshot.contentHash == contentHash) {
            return RagAdminIngestOutcome(
                status = "skipped",
                message = "already ingested"
            )
        }
        val wasChanged = incrementalIngest && existingSnapshot != null && existingSnapshot.contentHash != contentHash
        engine.upsert(
            UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = request.normalizedText,
                metadata = request.metadata + mapOf(
                    "rag.contentHash" to contentHash,
                    "rag.preview" to preview
                ),
                acl = request.acl,
                sourceUri = request.sourceUri,
                page = request.page,
                pageMarkers = request.pageMarkers
            )
        )
        rememberIngestSnapshot(normalizedTenantId, normalizedDocId, contentHash, preview)
        runCatching {
            graphStore.upsertProjection(
                graphProjectionService.projectDocument(
                    tenantId = normalizedTenantId,
                    docId = normalizedDocId,
                    request = request
                )
            )
        }
        return RagAdminIngestOutcome(
            status = if (wasChanged) "changed" else "ingested",
            message = when {
                wasChanged -> "content changed"
                else -> "ingested"
            },
            previousPreview = if (wasChanged) existingSnapshot.preview else null,
            currentPreview = preview,
            changeSummary = when {
                wasChanged && existingSnapshot.preview.isNotBlank() -> "content changed"
                wasChanged -> "existing content changed"
                else -> null
            }
        )
    }

    fun listTenants(): RagAdminTenantListResponse = withReader { reader ->
        val tenants = linkedMapOf<String, MutableTenantAggregate>()
        iterateDocuments(reader, null) { luceneDocId, stored, effectiveTenantId ->
            val aggregate = tenants.getOrPut(effectiveTenantId) { MutableTenantAggregate(effectiveTenantId) }
            aggregate.docIds += (stored.get(IndexSchema.DOC_ID) ?: return@iterateDocuments)
            aggregate.chunkCount++
            aggregate.lastUpdatedEpochMillis = maxOf(aggregate.lastUpdatedEpochMillis, readUpdatedAt(reader, luceneDocId))
        }

        RagAdminTenantListResponse(
            items = tenants.values
                .sortedWith(compareByDescending<MutableTenantAggregate> { it.lastUpdatedEpochMillis }.thenBy { it.tenantId })
                .map { tenant ->
                    RagAdminTenantSummary(
                        tenantId = tenant.tenantId,
                        docs = tenant.docIds.size.toLong(),
                        chunks = tenant.chunkCount.toLong(),
                        lastUpdatedEpochMillis = tenant.lastUpdatedEpochMillis,
                        lastUpdatedIso = epochIso(tenant.lastUpdatedEpochMillis)
                    )
                },
            snapshots = listSnapshots()
        )
    }

    fun tenantDetail(tenantId: String): RagAdminTenantDetail {
        val stats = engine.stats(tenantId)
        val docs = listDocuments(tenantId = tenantId, query = null, limit = Int.MAX_VALUE).items
        return RagAdminTenantDetail(
            tenantId = tenantId,
            docs = stats.docs,
            chunks = stats.chunks,
            lastCommitEpochMillis = stats.lastCommitEpochMillis,
            lastCommitIso = stats.lastCommitEpochMillis?.let(Instant::ofEpochMilli)?.toString(),
            snapshots = listSnapshots(),
            documents = docs
        )
    }

    fun deleteTenant(tenantId: String): RagAdminOperationResponse {
        val deleted = engine.deleteTenant(tenantId)
        clearIngestSnapshotCache(tenantId)
        runCatching { graphStore.deleteTenant(tenantId) }
        recordProviderSnapshot("delete-tenant")
        return RagAdminOperationResponse(
            operation = "deleteTenant",
            success = true,
            message = "deleted $deleted chunks for tenant",
            affectedCount = deleted.toInt(),
            tenantId = tenantId
        )
    }

    fun snapshot(tag: String): RagAdminOperationResponse {
        engine.snapshot(tag)
        return RagAdminOperationResponse(
            operation = "snapshot",
            success = true,
            message = "snapshot '$tag' created",
            snapshots = listSnapshots()
        )
    }

    fun restore(tag: String): RagAdminOperationResponse {
        engine.restore(tag)
        recordProviderSnapshot("restore")
        return RagAdminOperationResponse(
            operation = "restore",
            success = true,
            message = "snapshot '$tag' restored",
            snapshots = listSnapshots()
        )
    }

    fun optimize(): RagAdminOperationResponse {
        val result = (engine as? RagEngineMaintenance)?.optimize()
            ?: RagEngineMaintenanceResult("optimize", false, "engine does not support optimize")
        return result.toResponse()
    }

    fun rebuildMetadata(tenantId: String?): RagAdminOperationResponse {
        val result = (engine as? RagEngineMaintenance)?.rebuildTenantMetadata(tenantId)
            ?: RagEngineMaintenanceResult("rebuildTenantMetadata", false, "engine does not support rebuild")
        return result.toResponse()
    }

    fun accessSecurity(access: RagAdminAccessContext?): RagAdminAccessSecurityResponse =
        RagAdminAccessSecurityResponse(
            securityEnabled = adminProperties.security.enabled,
            authenticated = access?.authenticated ?: !adminProperties.security.enabled,
            currentUser = access?.username,
            currentRole = access?.role,
            currentRoles = access?.roles?.sorted().orEmpty(),
            loginPath = adminProperties.resolvedLoginPath(),
            logoutPath = adminProperties.resolvedLogoutPath(),
            featureRoles = adminProperties.security.featureRoles,
            recentAccessAudits = listAccessAudits()
        )

    fun bulkTextIngest(request: RagAdminBulkTextIngestRequest): RagAdminBulkOperationResponse {
        val results = request.documents.map { document ->
            runCatching {
                val upsertRequest = UpsertDocumentRequest(
                    tenantId = request.tenantId,
                    docId = document.docId,
                    normalizedText = document.text,
                    metadata = document.metadata,
                    acl = Acl(document.acl),
                    sourceUri = document.sourceUri,
                    page = document.page,
                    pageMarkers = document.pageMarkers.orEmpty().map {
                        PageMarker(it.page, it.offsetStart, it.offsetEnd)
                    }
                )
                val ingestResult = ingestDocument(
                    tenantId = request.tenantId,
                    docId = document.docId,
                    request = upsertRequest,
                    incrementalIngest = request.incrementalIngest
                )
                RagAdminBulkItemResult(
                    docId = document.docId,
                    success = true,
                    message = ingestResult.message ?: ingestResult.status,
                    status = ingestResult.status,
                    previousPreview = ingestResult.previousPreview,
                    currentPreview = ingestResult.currentPreview,
                    changeSummary = ingestResult.changeSummary
                )
            }.getOrElse { error ->
                RagAdminBulkItemResult(
                    docId = document.docId,
                    success = false,
                    message = error.message ?: "bulk ingest failed",
                    status = "failed"
                )
            }
        }
        recordProviderSnapshot("bulk-text-ingest")
        return RagAdminBulkOperationResponse(
            operation = "bulkTextIngest",
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            results = results
        )
    }

    fun bulkDelete(request: RagAdminBulkDeleteRequest): RagAdminBulkOperationResponse {
        val results = request.docIds.map { docId ->
            runCatching {
                val deleted = engine.deleteDocument(request.tenantId, docId)
                clearIngestSnapshotCache(request.tenantId, docId)
                runCatching { graphStore.deleteDocument(request.tenantId, docId) }
                RagAdminBulkItemResult(docId, true, "deleted $deleted chunks", "deleted")
            }.getOrElse { error ->
                RagAdminBulkItemResult(docId, false, error.message ?: "bulk delete failed", "failed")
            }
        }
        recordProviderSnapshot("bulk-delete")
        return RagAdminBulkOperationResponse(
            operation = "bulkDelete",
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            results = results
        )
    }

    fun bulkMetadataPatch(request: RagAdminBulkMetadataPatchRequest): RagAdminBulkOperationResponse {
        val results = request.docIds.map { docId ->
            runCatching {
                val detail = getDocumentDetail(request.tenantId, docId)
                val response = reindexDocument(
                    tenantId = request.tenantId,
                    docId = docId,
                    request = RagAdminDocumentReindexRequest(
                        metadata = detail.metadata + request.metadata,
                        acl = detail.acl,
                        sourceUri = detail.sourceUris.firstOrNull()
                    )
                )
                RagAdminBulkItemResult(docId, response.success, response.message, if (response.success) "patched" else "failed")
            }.getOrElse { error ->
                RagAdminBulkItemResult(docId, false, error.message ?: "metadata patch failed", "failed")
            }
        }
        return RagAdminBulkOperationResponse(
            operation = "bulkMetadataPatch",
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            results = results
        )
    }

    fun retryJob(jobId: String): RagAdminOperationResponse {
        val job = listJobHistory().firstOrNull { it.id == jobId } ?: error("job not found")
        require(job.retrySupported && !job.retryKind.isNullOrBlank()) { "job retry is not supported" }
        return when (job.retryKind) {
            "reindex-document" -> {
                val tenantId = job.payload["tenantId"] as? String ?: error("tenantId missing")
                val docId = job.payload["docId"] as? String ?: error("docId missing")
                val response = reindexDocument(tenantId, docId, RagAdminDocumentReindexRequest())
                response.copy(operation = "retryJob")
            }
            else -> error("unsupported retry kind '${job.retryKind}'")
        }
    }

    private fun parseFromSourceUri(sourceUri: String, charsetName: String): com.ainsoft.rag.parsers.ParsedDocument {
        val normalizedSource = sourceUri.trim()
        return if (normalizedSource.startsWith("file:") && isBinaryDoc(normalizedSource)) {
            val path = Path.of(java.net.URI(normalizedSource))
            tikaDocumentParser.parseFile(path, sourceUri = normalizedSource)
        } else {
            val text = SourceLoaders.loadTextFromUri(
                uriValue = normalizedSource,
                charset = Charset.forName(charsetName),
                options = SourceLoadOptions(
                    authHeaders = properties.sourceLoadAuthHeaders,
                    timeout = Duration.ofMillis(properties.sourceLoadTimeoutMillis),
                    allowedHosts = properties.sourceLoadAllowHosts.toSet(),
                    insecureSkipTlsVerify = properties.sourceLoadInsecureSkipTlsVerify,
                    customCaCertPath = properties.sourceLoadCustomCaCertPath
                )
            ) ?: error("unable to load sourceUri '$sourceUri'")
            plainTextParser.parseText(text, sourceUri = normalizedSource)
        }
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

    private fun listSnapshots(): List<RagAdminSnapshotSummary> {
        val snapshotRoot = ragConfig.indexPath.resolve("snapshots")
        if (!snapshotRoot.exists() || !snapshotRoot.isDirectory()) {
            return emptyList()
        }
        return snapshotRoot.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
            .map { path ->
                RagAdminSnapshotSummary(
                    tag = path.fileName.toString(),
                    updatedAtEpochMillis = Files.getLastModifiedTime(path).toMillis(),
                    updatedAtIso = Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString()
                )
            }
    }

    private fun sanitizeMap(input: Map<String, Any?>): Map<String, Any?> =
        input.entries.associate { (key, value) ->
            val sanitizedValue = when {
                isSecretKey(key) -> value?.let { "***" }
                value is Map<*, *> -> sanitizeMap(value.entries.associate { it.key.toString() to it.value })
                value is Collection<*> -> value.map { item ->
                    if (item is Map<*, *>) sanitizeMap(item.entries.associate { it.key.toString() to it.value }) else item
                }
                else -> value
            }
            key to sanitizedValue
        }.toSortedMap()

    private fun isSecretKey(key: String): Boolean {
        val lowered = key.lowercase()
        return lowered.contains("password") || lowered.contains("secret") || lowered.contains("token") || lowered.contains("key")
    }

    private inline fun <T> withReader(block: (DirectoryReader) -> T): T {
        FSDirectory.open(ragConfig.indexPath).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                return block(reader)
            }
        }
    }

    private fun iterateDocuments(
        reader: DirectoryReader,
        tenantId: String?,
        consumer: (luceneDocId: Int, stored: org.apache.lucene.document.Document, effectiveTenantId: String) -> Unit
    ) {
        val searcher = org.apache.lucene.search.IndexSearcher(reader)
        val query = tenantId?.let { TermQuery(Term(IndexSchema.TENANT_ID, it)) } ?: MatchAllDocsQuery()
        val topDocs = searcher.search(query, Int.MAX_VALUE)
        val storedFields = searcher.storedFields()
        topDocs.scoreDocs.forEach { scoreDoc ->
            val effectiveTenantId = resolveTenantId(reader, scoreDoc.doc) ?: tenantId ?: return@forEach
            consumer(scoreDoc.doc, storedFields.document(scoreDoc.doc), effectiveTenantId)
        }
    }

    private fun resolveTenantId(reader: DirectoryReader, luceneDocId: Int): String? {
        val values = MultiDocValues.getBinaryValues(reader, IndexSchema.TENANT_DOC_ID_DV) ?: return null
        if (!values.advanceExact(luceneDocId)) return null
        return values.binaryValue().utf8ToString().substringBefore('|')
    }

    private fun readUpdatedAt(reader: DirectoryReader, luceneDocId: Int): Long {
        val values = MultiDocValues.getNumericValues(reader, IndexSchema.UPDATED_AT) ?: return 0L
        return if (values.advanceExact(luceneDocId)) values.longValue() else 0L
    }

    private fun readAcl(reader: DirectoryReader, luceneDocId: Int): List<String> {
        val values = MultiDocValues.getSortedSetValues(reader, IndexSchema.ALLOW) ?: return emptyList()
        if (!values.advanceExact(luceneDocId)) return emptyList()
        val acl = mutableListOf<String>()
        while (true) {
            val ord = values.nextOrd()
            if (ord < 0L) break
            acl += values.lookupOrd(ord).utf8ToString()
        }
        return acl
    }

    private fun readMetadata(stored: org.apache.lucene.document.Document): Map<String, String> =
        stored.fields
            .mapNotNull { field ->
                val name = field.name()
                if (!name.startsWith(IndexSchema.META_PREFIX)) {
                    null
                } else {
                    name.removePrefix(IndexSchema.META_PREFIX) to (field.stringValue() ?: return@mapNotNull null)
                }
            }
            .toMap()

    private fun isBinaryDoc(sourceUri: String): Boolean {
        val lower = sourceUri.lowercase()
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".pptx")
    }

    private fun epochIso(value: Long): String? = if (value <= 0) null else Instant.ofEpochMilli(value).toString()

    private fun RagEngineMaintenanceResult.toResponse(): RagAdminOperationResponse =
        RagAdminOperationResponse(
            operation = operation,
            success = success,
            message = message,
            affectedCount = affectedDocuments,
            tenantId = tenantId,
            snapshots = listSnapshots()
        )

    private inline fun <T> append(
        deque: ArrayDeque<T>,
        limit: Int,
        supplier: () -> T
    ) {
        synchronized(deque) {
            deque.addFirst(supplier())
            while (deque.size > limit) {
                deque.removeLast()
            }
        }
    }

    private data class MutableDocumentAggregate(
        val tenantId: String,
        val docId: String,
        var chunkCount: Int = 0,
        var lastUpdatedEpochMillis: Long = 0L,
        val metadata: MutableMap<String, String> = linkedMapOf(),
        val acl: MutableSet<String> = linkedSetOf(),
        val sourceUris: MutableSet<String> = linkedSetOf(),
        val contentKinds: MutableSet<String> = linkedSetOf(),
        val pages: MutableList<Int> = mutableListOf(),
        var previewText: String? = null
    )

    private data class MutableTenantAggregate(
        val tenantId: String,
        val docIds: MutableSet<String> = linkedSetOf(),
        var chunkCount: Int = 0,
        var lastUpdatedEpochMillis: Long = 0L
    )

    private data class IngestSnapshot(
        val contentHash: String,
        val preview: String
    )
}

data class RagAdminIngestOutcome(
    val status: String,
    val message: String? = null,
    val previousPreview: String? = null,
    val currentPreview: String? = null,
    val changeSummary: String? = null
)

data class RagAdminDocumentListResponse(
    val totalCount: Int,
    val items: List<RagAdminDocumentSummary>
)

data class RagAdminDocumentSummary(
    val tenantId: String,
    val docId: String,
    val chunkCount: Int,
    val metadata: Map<String, String>,
    val acl: List<String>,
    val sourceUris: List<String>,
    val contentKinds: List<String>,
    val pages: List<Int>,
    val previewText: String?,
    val lastUpdatedEpochMillis: Long,
    val lastUpdatedIso: String?
)

data class RagAdminDocumentDetail(
    val tenantId: String,
    val docId: String,
    val metadata: Map<String, String>,
    val acl: List<String>,
    val sourceUris: List<String>,
    val chunkCount: Int,
    val lastUpdatedEpochMillis: Long,
    val lastUpdatedIso: String?,
    val chunks: List<RagAdminDocumentChunk>
)

data class RagAdminDocumentChunk(
    val chunkId: String,
    val contentKind: String,
    val page: Int?,
    val sourceUri: String?,
    val offsetStart: Int?,
    val offsetEnd: Int?,
    val text: String?,
    val metadata: Map<String, String>
)

data class RagAdminSourcePreviewResponse(
    val tenantId: String,
    val docId: String,
    val chunkId: String,
    val sourceUri: String?,
    val offsetStart: Int?,
    val offsetEnd: Int?,
    val preview: String
)

data class RagAdminTenantListResponse(
    val items: List<RagAdminTenantSummary>,
    val snapshots: List<RagAdminSnapshotSummary>
)

data class RagAdminTenantSummary(
    val tenantId: String,
    val docs: Long,
    val chunks: Long,
    val lastUpdatedEpochMillis: Long,
    val lastUpdatedIso: String?
)

data class RagAdminTenantDetail(
    val tenantId: String,
    val docs: Long,
    val chunks: Long,
    val lastCommitEpochMillis: Long?,
    val lastCommitIso: String?,
    val snapshots: List<RagAdminSnapshotSummary>,
    val documents: List<RagAdminDocumentSummary>
)

data class RagAdminSnapshotSummary(
    val tag: String,
    val updatedAtEpochMillis: Long,
    val updatedAtIso: String
)

data class RagAdminOperationResponse(
    val operation: String,
    val success: Boolean,
    val message: String,
    val affectedCount: Int = 0,
    val tenantId: String? = null,
    val snapshots: List<RagAdminSnapshotSummary> = emptyList()
)

data class RagAdminSearchAuditEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val auditType: String,
    val tenantId: String,
    val principals: List<String>,
    val query: String,
    val topK: Int,
    val filter: Map<String, String>,
    val resultCount: Int,
    val role: String?,
    val executedQuery: String,
    val queryRewriteApplied: Boolean,
    val correctiveRetryApplied: Boolean,
    val providerFallbackApplied: Boolean,
    val providerFallbackReason: String?,
    val providersUsed: List<String>,
    val notes: List<String>
)

data class RagAdminJobHistoryEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val jobType: String,
    val tenantId: String?,
    val role: String?,
    val status: String,
    val description: String,
    val payload: Map<String, Any?>,
    val retryKind: String? = null,
    val retrySupported: Boolean = false,
    val message: String? = null,
    val completedAtEpochMillis: Long? = null
)

data class RagAdminAccessAuditEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val path: String,
    val method: String,
    val role: String?,
    val granted: Boolean,
    val message: String?
)

data class RagAdminUserAuditEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val action: String,
    val actorUsername: String?,
    val actorRole: String?,
    val targetUsername: String,
    val success: Boolean,
    val message: String?,
    val details: Map<String, String> = emptyMap()
)

data class RagAdminProviderHistoryEntry(
    val timestampEpochMillis: Long,
    val source: String,
    val telemetry: ProviderTelemetryResponse
)

data class RagAdminProviderFallbackAuditEntry(
    val timestampEpochMillis: Long,
    val tenantId: String,
    val query: String,
    val role: String?,
    val providerFallbackReason: String?,
    val providersUsed: List<String>
)

data class RagAdminProviderHistoryResponse(
    val current: ProviderTelemetryResponse,
    val history: List<RagAdminProviderHistoryEntry>,
    val fallbackEvents: List<RagAdminProviderFallbackAuditEntry>
)

data class RagAdminAccessSecurityResponse(
    val securityEnabled: Boolean,
    val authenticated: Boolean,
    val currentUser: String?,
    val currentRole: String?,
    val currentRoles: List<String>,
    val loginPath: String,
    val logoutPath: String,
    val featureRoles: Map<String, List<String>>,
    val recentAccessAudits: List<RagAdminAccessAuditEntry>
)

data class RagAdminConfigInspectorResponse(
    val rag: Map<String, Any?>,
    val admin: Map<String, Any?>
)

data class RagAdminBulkItemResult(
    val docId: String,
    val success: Boolean,
    val message: String,
    val status: String? = null,
    val previousPreview: String? = null,
    val currentPreview: String? = null,
    val changeSummary: String? = null
)

data class RagAdminBulkOperationResponse(
    val operation: String,
    val successCount: Int,
    val failureCount: Int,
    val results: List<RagAdminBulkItemResult>
)
