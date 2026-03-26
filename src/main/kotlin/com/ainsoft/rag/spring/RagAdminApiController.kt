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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.nio.charset.Charset
import java.time.Instant
import java.text.Normalizer
import java.util.concurrent.Executor

@RestController
@RequestMapping("\${rag.admin.api-base-path:/api/rag/admin}")
class RagAdminApiController(
    private val engine: RagEngine,
    private val properties: RagProperties,
    private val ragConfig: RagConfig,
    private val embeddingProvider: EmbeddingProvider,
    private val adminService: RagAdminService,
    private val securityService: RagAdminSecurityService,
    private val accountManagementService: RagAdminAccountManagementService,
    private val streamExecutor: Executor
) {
    private val objectMapper = jacksonObjectMapper()
    private val plainTextParser = PlainTextParser()
    private val tikaDocumentParser = TikaDocumentParser()

    @PostMapping("/ingest")
    fun ingest(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminIngestRequest
    ): RagAdminIngestResponse {
        val access = securityService.requireFeature(requestContext, "text-ingest")
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        require(request.text.isNotBlank()) { "text must not be blank" }
        val normalizedTenantId = normalizeIdentifier(request.tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(request.docId, "docId")
        val jobId = adminService.startJob(
            jobType = "ingest",
            tenantId = normalizedTenantId,
            role = access.role,
            description = "text ingest for $normalizedDocId",
            payload = mapOf("tenantId" to normalizedTenantId, "docId" to normalizedDocId)
        )
        val outcome = adminService.ingestDocument(
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            request = UpsertDocumentRequest(
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
            ),
            incrementalIngest = request.incrementalIngest
        )
        adminService.completeJob(
            jobId,
            "SUCCESS",
            when (outcome.status) {
                "skipped" -> "document already ingested"
                "changed" -> "document changed"
                else -> "document ingested"
            }
        )
        adminService.recordProviderSnapshot("ingest")
        return RagAdminIngestResponse(
            status = outcome.status,
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            message = outcome.message,
            previousPreview = outcome.previousPreview,
            currentPreview = outcome.currentPreview,
            changeSummary = outcome.changeSummary
        )
    }

    @PostMapping(
        "/ingest-file",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun ingestFile(
        requestContext: HttpServletRequest,
        @RequestParam tenantId: String,
        @RequestParam docId: String,
        @RequestParam acl: List<String>,
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) sourceUri: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false, defaultValue = "UTF-8") charset: String,
        @RequestParam(required = false) metadata: String?,
        @RequestParam(required = false, defaultValue = "true") incrementalIngest: Boolean
    ): RagAdminIngestResponse {
        val access = securityService.requireFeature(requestContext, "file-ingest")
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
        val jobId = adminService.startJob(
            jobType = "ingest-file",
            tenantId = normalizedTenantId,
            role = access.role,
            description = "file ingest for $normalizedDocId",
            payload = mapOf(
                "tenantId" to normalizedTenantId,
                "docId" to normalizedDocId,
                "filename" to (normalizedFilename ?: "")
            )
        )
        val parsed = if (isBinaryDoc(file.originalFilename)) {
            tikaDocumentParser.parseBytes(file.bytes, sourceUri = effectiveSourceUri, page = page)
        } else {
            val text = file.bytes.toString(Charset.forName(charset))
            plainTextParser.parseText(text, sourceUri = effectiveSourceUri, page = page)
        }

        val outcome = adminService.ingestDocument(
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            request = UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = parsed.normalizedText,
                metadata = parseMetadata(metadata),
                acl = Acl(acl),
                sourceUri = parsed.sourceUri,
                page = parsed.page,
                pageMarkers = parsed.pageMarkers
            ),
            incrementalIngest = incrementalIngest
        )
        adminService.completeJob(
            jobId,
            "SUCCESS",
            when (outcome.status) {
                "skipped" -> "file already ingested"
                "changed" -> "file changed"
                else -> "file ingested"
            }
        )
        adminService.recordProviderSnapshot("ingest-file")
        return RagAdminIngestResponse(
            status = outcome.status,
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            message = outcome.message,
            previousPreview = outcome.previousPreview,
            currentPreview = outcome.currentPreview,
            changeSummary = outcome.changeSummary
        )
    }

    @PostMapping("/web-ingest")
    fun webIngest(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminWebIngestRequest
    ): RagAdminWebIngestResponse {
        val access = securityService.requireFeature(requestContext, "web-ingest")
        return adminService.webIngest(access.role, request)
    }

    @PostMapping(
        "/web-ingest/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun webIngestStream(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminWebIngestRequest
    ): SseEmitter {
        val access = securityService.requireFeature(requestContext, "web-ingest")
        val emitter = SseEmitter(0L)
        streamExecutor.execute {
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("start")
                        .data(
                            RagAdminWebIngestStreamEnvelope(
                                type = "start",
                                message = "starting web ingest"
                            )
                        )
                )
                val response = adminService.webIngest(
                    access.role,
                    request,
                    progressSink = { event ->
                        emitter.send(
                            SseEmitter.event()
                                .name("progress")
                                .data(
                                    RagAdminWebIngestStreamEnvelope(
                                        type = "progress",
                                        event = event
                                    )
                                )
                        )
                    }
                )
                emitter.send(
                    SseEmitter.event()
                        .name("result")
                        .data(
                            RagAdminWebIngestStreamEnvelope(
                                type = "result",
                                response = response
                            )
                        )
                )
                emitter.send(
                    SseEmitter.event()
                        .name("done")
                        .data(
                            RagAdminWebIngestStreamEnvelope(
                                type = "done",
                                response = response
                            )
                        )
                )
                emitter.complete()
            } catch (_: IOException) {
                emitter.complete()
            } catch (_: WebIngestCancelledException) {
                emitter.complete()
            } catch (error: Exception) {
                runCatching {
                    emitter.send(
                        SseEmitter.event()
                            .name("error")
                            .data(
                                RagAdminWebIngestStreamEnvelope(
                                    type = "error",
                                    message = error.message ?: "web ingest failed"
                                )
                            )
                    )
                }
                emitter.complete()
            }
        }
        return emitter
    }

    @PostMapping("/search")
    fun search(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminSearchRequest
    ): RagAdminSearchResponse {
        val access = securityService.requireFeature(requestContext, "search")
        val normalizedRequest = normalizeSearchRequest(request)
        require(normalizedRequest.principals.isNotEmpty()) { "principals must not be empty" }
        require(normalizedRequest.query.isNotBlank()) { "query must not be blank" }

        val response = engine.searchDetailed(
            SearchRequest(
                tenantId = normalizedRequest.tenantId,
                principals = normalizedRequest.principals,
                query = normalizedRequest.query,
                topK = normalizedRequest.topK,
                filter = normalizedRequest.filter
            )
        )
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = normalizedRequest.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }

        val result = RagAdminSearchResponse(
            tenantId = normalizedRequest.tenantId,
            query = normalizedRequest.query,
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
            providerTelemetry = globalProviderHealth.toResponse(normalizedRequest.providerHealthDetail),
            recentProviderWindowMillis = normalizedRequest.recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(normalizedRequest.providerHealthDetail)
        ).applyExactMatchFilterIfRequested(normalizedRequest)
        .suppressWeakMatches(
            finalConfidenceThreshold = normalizedRequest.searchNoMatchMinFinalConfidence ?: properties.searchNoMatchMinFinalConfidence,
            topHitScoreThreshold = normalizedRequest.searchNoMatchMinTopHitScore ?: properties.searchNoMatchMinTopHitScore
        )
        adminService.recordSearchAudit("search", access.role, normalizedRequest, result.hits.size, result.telemetry)
        return result
    }

    @PostMapping("/diagnose-search")
    fun diagnoseSearch(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminSearchRequest
    ): RagAdminSearchDiagnosticsResponse {
        val access = securityService.requireFeature(requestContext, "search")
        val normalizedRequest = normalizeSearchRequest(request)
        require(normalizedRequest.principals.isNotEmpty()) { "principals must not be empty" }
        require(normalizedRequest.query.isNotBlank()) { "query must not be blank" }

        val searchRequest = SearchRequest(
            tenantId = normalizedRequest.tenantId,
            principals = normalizedRequest.principals,
            query = normalizedRequest.query,
            topK = normalizedRequest.topK,
            filter = normalizedRequest.filter
        )
        val diagnostics = SearchDiagnostics.analyze(
            indexPath = ragConfig.indexPath,
            embeddingProvider = embeddingProvider,
            request = searchRequest,
            maxSamples = normalizedRequest.diagnosticMaxSamples,
            scoreThreshold = normalizedRequest.diagnosticScoreThreshold
        )
        val search = engine.searchDetailed(searchRequest)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = normalizedRequest.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }

        val result = RagAdminSearchDiagnosticsResponse(
            tenantId = normalizedRequest.tenantId,
            query = normalizedRequest.query,
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
            providerTelemetry = globalProviderHealth.toResponse(normalizedRequest.providerHealthDetail),
            recentProviderWindowMillis = normalizedRequest.recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(normalizedRequest.providerHealthDetail)
        )
        adminService.recordSearchAudit(
            auditType = "diagnose-search",
            role = access.role,
            request = normalizedRequest,
            resultCount = result.vectorMatchesWithAcl + result.lexicalMatchesWithAcl,
            telemetry = result.telemetry
        )
        return result
    }

    @GetMapping("/stats")
    fun stats(
        requestContext: HttpServletRequest,
        @RequestParam tenantId: String?,
        @RequestParam(required = false) recentProviderWindowMillis: Long?
    ): RagAdminStatsResponse {
        securityService.requireFeature(requestContext, "overview")
        val stats = engine.stats(tenantId)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        val response = stats.toResponse(
            providerTelemetry = globalProviderHealth.toResponse(),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse()
        )
        adminService.recordProviderSnapshot("stats")
        return response
    }

    @GetMapping("/provider-health")
    fun providerHealth(
        requestContext: HttpServletRequest,
        @RequestParam(required = false) recentProviderWindowMillis: Long?,
        @RequestParam(required = false, defaultValue = "true") detailed: Boolean
    ): RagAdminProviderHealthResponse {
        securityService.requireFeature(requestContext, "provider-history")
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        val response = RagAdminProviderHealthResponse(
            providerTelemetry = globalProviderHealth.toResponse(detailed),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(detailed)
        )
        adminService.recordProviderSnapshot("provider-health")
        return response
    }

    @GetMapping("/documents")
    fun documents(
        requestContext: HttpServletRequest,
        @RequestParam(required = false) tenantId: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int
    ): RagAdminDocumentListResponse {
        securityService.requireFeature(requestContext, "documents")
        return adminService.listDocuments(tenantId, query, limit)
    }

    @GetMapping("/documents/{tenantId}/{docId}")
    fun documentDetail(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String,
        @PathVariable docId: String
    ): RagAdminDocumentDetail {
        securityService.requireFeature(requestContext, "documents")
        return adminService.getDocumentDetail(tenantId, docId)
    }

    @DeleteMapping("/documents/{tenantId}/{docId}")
    fun deleteDocument(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String,
        @PathVariable docId: String
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "documents")
        val jobId = adminService.startJob(
            "delete-document",
            tenantId,
            access.role,
            "delete document $docId",
            mapOf("tenantId" to tenantId, "docId" to docId)
        )
        val response = adminService.deleteDocument(tenantId, docId)
        adminService.completeJob(jobId, "SUCCESS", response.message)
        return response
    }

    @PostMapping("/documents/{tenantId}/{docId}/reindex")
    fun reindexDocument(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String,
        @PathVariable docId: String,
        @RequestBody(required = false) request: RagAdminDocumentReindexRequest?
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "documents")
        val jobId = adminService.startJob(
            "reindex-document",
            tenantId,
            access.role,
            "reindex document $docId",
            mapOf("tenantId" to tenantId, "docId" to docId),
            retryKind = "reindex-document"
        )
        val response = adminService.reindexDocument(tenantId, docId, request ?: RagAdminDocumentReindexRequest())
        adminService.completeJob(jobId, "SUCCESS", response.message)
        return response
    }

    @GetMapping("/documents/{tenantId}/{docId}/source-preview")
    fun sourcePreview(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String,
        @PathVariable docId: String,
        @RequestParam(required = false) chunkId: String?,
        @RequestParam(required = false, defaultValue = "160") contextChars: Int,
        @RequestParam(required = false, defaultValue = "UTF-8") charset: String,
        @RequestParam(required = false) profileName: String?
    ): RagAdminSourcePreviewResponse {
        securityService.requireFeature(requestContext, "documents")
        return adminService.sourcePreview(tenantId, docId, chunkId, contextChars, charset, profileName)
    }

    @GetMapping("/tenants")
    fun tenants(requestContext: HttpServletRequest): RagAdminTenantListResponse {
        securityService.requireFeature(requestContext, "tenants")
        return adminService.listTenants()
    }

    @GetMapping("/tenants/{tenantId}")
    fun tenantDetail(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String
    ): RagAdminTenantDetail {
        securityService.requireFeature(requestContext, "tenants")
        return adminService.tenantDetail(tenantId)
    }

    @DeleteMapping("/tenants/{tenantId}")
    fun deleteTenant(
        requestContext: HttpServletRequest,
        @PathVariable tenantId: String
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "tenants")
        val jobId = adminService.startJob(
            "delete-tenant",
            tenantId,
            access.role,
            "delete tenant $tenantId",
            mapOf("tenantId" to tenantId)
        )
        val response = adminService.deleteTenant(tenantId)
        adminService.completeJob(jobId, "SUCCESS", response.message)
        return response
    }

    @PostMapping("/operations/snapshot")
    fun snapshot(
        requestContext: HttpServletRequest,
        @RequestParam tag: String
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "tenants")
        val jobId = adminService.startJob("snapshot", null, access.role, "snapshot $tag", mapOf("tag" to tag))
        val response = adminService.snapshot(tag)
        adminService.completeJob(jobId, "SUCCESS", response.message)
        return response
    }

    @PostMapping("/operations/restore")
    fun restore(
        requestContext: HttpServletRequest,
        @RequestParam tag: String
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "tenants")
        val jobId = adminService.startJob("restore", null, access.role, "restore $tag", mapOf("tag" to tag))
        val response = adminService.restore(tag)
        adminService.completeJob(jobId, "SUCCESS", response.message)
        return response
    }

    @PostMapping("/operations/optimize")
    fun optimize(requestContext: HttpServletRequest): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "tenants")
        val jobId = adminService.startJob("optimize", null, access.role, "optimize index", emptyMap())
        val response = adminService.optimize()
        adminService.completeJob(jobId, if (response.success) "SUCCESS" else "FAILED", response.message)
        return response
    }

    @PostMapping("/operations/rebuild-metadata")
    fun rebuildMetadata(
        requestContext: HttpServletRequest,
        @RequestParam(required = false) tenantId: String?
    ): RagAdminOperationResponse {
        val access = securityService.requireFeature(requestContext, "tenants")
        val jobId = adminService.startJob(
            "rebuild-metadata",
            tenantId,
            access.role,
            "rebuild tenant metadata",
            mapOf("tenantId" to tenantId)
        )
        val response = adminService.rebuildMetadata(tenantId)
        adminService.completeJob(jobId, if (response.success) "SUCCESS" else "FAILED", response.message)
        return response
    }

    @GetMapping("/provider-history")
    fun providerHistory(
        requestContext: HttpServletRequest,
        @RequestParam(required = false, defaultValue = "120") limit: Int
    ): RagAdminProviderHistoryResponse {
        securityService.requireFeature(requestContext, "provider-history")
        return adminService.getProviderHistory(limit)
    }

    @GetMapping("/search-audit")
    fun searchAudit(
        requestContext: HttpServletRequest,
        @RequestParam(required = false, defaultValue = "100") limit: Int
    ): List<RagAdminSearchAuditEntry> {
        securityService.requireFeature(requestContext, "search-audit")
        return adminService.listSearchAudits(limit)
    }

    @GetMapping("/job-history")
    fun jobHistory(
        requestContext: HttpServletRequest,
        @RequestParam(required = false, defaultValue = "100") limit: Int
    ): List<RagAdminJobHistoryEntry> {
        securityService.requireFeature(requestContext, "job-history")
        return adminService.listJobHistory(limit)
    }

    @PostMapping("/job-history/{jobId}/retry")
    fun retryJob(
        requestContext: HttpServletRequest,
        @PathVariable jobId: String
    ): RagAdminOperationResponse {
        securityService.requireFeature(requestContext, "job-history")
        return adminService.retryJob(jobId)
    }

    @GetMapping("/access-security")
    fun accessSecurity(requestContext: HttpServletRequest): RagAdminAccessSecurityResponse {
        securityService.requireFeature(requestContext, "access-security")
        return adminService.accessSecurity(securityService.resolveAccess(requestContext))
    }

    @GetMapping("/config")
    fun config(requestContext: HttpServletRequest): RagAdminConfigInspectorResponse {
        securityService.requireFeature(requestContext, "config")
        return adminService.getConfigInspector()
    }

    @PostMapping("/bulk/text-ingest")
    fun bulkTextIngest(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminBulkTextIngestRequest
    ): RagAdminBulkOperationResponse {
        securityService.requireFeature(requestContext, "bulk-operations")
        return adminService.bulkTextIngest(request)
    }

    @PostMapping("/bulk/delete")
    fun bulkDelete(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminBulkDeleteRequest
    ): RagAdminBulkOperationResponse {
        securityService.requireFeature(requestContext, "bulk-operations")
        return adminService.bulkDelete(request)
    }

    @PostMapping("/bulk/metadata-patch")
    fun bulkMetadataPatch(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminBulkMetadataPatchRequest
    ): RagAdminBulkOperationResponse {
        securityService.requireFeature(requestContext, "bulk-operations")
        return adminService.bulkMetadataPatch(request)
    }

    @GetMapping("/users")
    fun listUsers(
        requestContext: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) auditAction: String?,
        @RequestParam(required = false) auditQuery: String?,
        @RequestParam(required = false) auditFrom: String?,
        @RequestParam(required = false) auditTo: String?,
        @RequestParam(required = false, defaultValue = "20") auditLimit: Int,
        @RequestParam(required = false, defaultValue = "0") auditOffset: Int
    ): RagAdminUsersResponse {
        securityService.requireFeature(requestContext, "users")
        val auditEntries = adminService.listUserAudits(
            limit = auditLimit,
            offset = auditOffset,
            action = auditAction,
            query = auditQuery,
            fromEpochMillis = parseEpochMillis(auditFrom),
            toEpochMillis = parseEpochMillis(auditTo)?.plus(86_399_999L)
        )
        return accountManagementService.listUsers(
            query = q,
            sort = sort,
            direction = direction
        ).copy(
            recentActions = auditEntries.map { it.toResponse() },
            auditTotalCount = adminService.listUserAudits(
                limit = Int.MAX_VALUE,
                offset = 0,
                action = auditAction,
                query = auditQuery,
                fromEpochMillis = parseEpochMillis(auditFrom),
                toEpochMillis = parseEpochMillis(auditTo)?.plus(86_399_999L)
            ).size,
            auditOffset = auditOffset,
            auditLimit = auditLimit,
            auditAction = auditAction,
            auditQuery = auditQuery,
            auditFrom = auditFrom,
            auditTo = auditTo
        )
    }

    @PostMapping("/users")
    fun createUser(
        requestContext: HttpServletRequest,
        @RequestBody request: RagAdminUserUpsertRequest
    ): RagAdminUserResponse {
        val access = securityService.requireFeature(requestContext, "users")
        return accountManagementService.createUser(access, request)
    }

    @PutMapping("/users/{username}")
    fun updateUser(
        requestContext: HttpServletRequest,
        @PathVariable username: String,
        @RequestBody request: RagAdminUserUpsertRequest
    ): RagAdminUserResponse {
        val access = securityService.requireFeature(requestContext, "users")
        return accountManagementService.updateUser(access, username, request)
    }

    @DeleteMapping("/users/{username}")
    fun deleteUser(
        requestContext: HttpServletRequest,
        @PathVariable username: String
    ) {
        val access = securityService.requireFeature(requestContext, "users")
        accountManagementService.deleteUser(access, username)
    }

    @PostMapping("/users/{username}/password")
    fun resetPassword(
        requestContext: HttpServletRequest,
        @PathVariable username: String,
        @RequestBody request: RagAdminUserPasswordResetRequest
    ): RagAdminUserResponse {
        val access = securityService.requireFeature(requestContext, "users")
        return accountManagementService.resetPassword(access, username, request.password)
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

    private fun normalizeSearchRequest(request: RagAdminSearchRequest): RagAdminSearchRequest =
        RagAdminSearchRequest(
            tenantId = normalizeIdentifier(request.tenantId, "tenantId"),
            principals = request.principals.map { it.trim() }.filter { it.isNotBlank() },
            query = request.query,
            topK = request.topK,
            filter = request.filter,
            providerHealthDetail = request.providerHealthDetail,
            recentProviderWindowMillis = request.recentProviderWindowMillis,
            searchNoMatchMinFinalConfidence = request.searchNoMatchMinFinalConfidence,
            searchNoMatchMinTopHitScore = request.searchNoMatchMinTopHitScore,
            searchExactMatchOnly = request.searchExactMatchOnly,
            searchExactMatchMode = request.searchExactMatchMode,
            diagnosticScoreThreshold = request.diagnosticScoreThreshold,
            diagnosticMaxSamples = request.diagnosticMaxSamples
        )

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

    private fun parseEpochMillis(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class RagAdminUserResponse(
    val username: String,
    val displayName: String? = null,
    val enabled: Boolean,
    val roles: List<String>,
    val passwordConfigured: Boolean,
    val isAdmin: Boolean
)

data class RagAdminUsersResponse(
    val items: List<RagAdminUserResponse>,
    val totalCount: Int,
    val enabledCount: Int,
    val adminCount: Int,
    val recentActions: List<RagAdminUserAuditResponse> = emptyList(),
    val auditTotalCount: Int = 0,
    val auditOffset: Int = 0,
    val auditLimit: Int = 20,
    val auditAction: String? = null,
    val auditQuery: String? = null,
    val auditFrom: String? = null,
    val auditTo: String? = null
)

data class RagAdminUserAuditResponse(
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

internal fun RagAdminUserAuditEntry.toResponse(): RagAdminUserAuditResponse =
    RagAdminUserAuditResponse(
        id = id,
        timestampEpochMillis = timestampEpochMillis,
        action = action,
        actorUsername = actorUsername,
        actorRole = actorRole,
        targetUsername = targetUsername,
        success = success,
        message = message,
        details = details
    )

data class RagAdminIngestResponse(
    val status: String,
    val tenantId: String,
    val docId: String,
    val message: String? = null,
    val previousPreview: String? = null,
    val currentPreview: String? = null,
    val changeSummary: String? = null
)

data class RagAdminWebIngestStreamEnvelope(
    val type: String,
    val message: String? = null,
    val event: RagAdminWebIngestProgressEvent? = null,
    val response: RagAdminWebIngestResponse? = null
)


data class RagAdminSearchResponse(
    val tenantId: String,
    val query: String,
    val hits: List<RagAdminSearchHitResponse>,
    val telemetry: RagAdminSearchTelemetryResponse,
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null,
    val exactMatchDebug: RagAdminExactMatchDebugResponse? = null
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

data class RagAdminExactMatchDebugResponse(
    val enabled: Boolean,
    val compareMode: String,
    val combineMode: String,
    val queryTokens: List<String>,
    val matchedHitCount: Int,
    val filteredHitCount: Int,
    val hits: List<RagAdminExactMatchHitDebugResponse>
)

data class RagAdminExactMatchHitDebugResponse(
    val docId: String,
    val chunkId: String,
    val matchedTokens: List<String>,
    val candidateTokens: List<String>
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

internal fun com.ainsoft.rag.api.SearchTelemetry.toResponse(): RagAdminSearchTelemetryResponse =
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

internal fun com.ainsoft.rag.api.ProviderTelemetryStats.toResponse(
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

internal fun IndexStats.toResponse(
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

private fun RagAdminSearchResponse.suppressWeakMatches(
    finalConfidenceThreshold: Double,
    topHitScoreThreshold: Double
): RagAdminSearchResponse {
    val topHitScore = hits.maxOfOrNull { it.score } ?: 0.0
    val finalConfidence = telemetry.finalConfidence
    val belowConfidence = finalConfidence != null && finalConfidence < finalConfidenceThreshold
    val belowScore = hits.isNotEmpty() && topHitScore < topHitScoreThreshold
    if (!belowConfidence || !belowScore) {
        return this
    }

    val reason = buildString {
        append("no sufficiently strong matches found")
        append(" (finalConfidence=")
        append(String.format("%.3f", finalConfidence))
        append(" < ")
        append(String.format("%.3f", finalConfidenceThreshold))
        append(", topHitScore=")
        append(String.format("%.3f", topHitScore))
        append(" < ")
        append(String.format("%.3f", topHitScoreThreshold))
        append(")")
    }
    return copy(
        hits = emptyList(),
        telemetry = telemetry.copy(notes = telemetry.notes + reason)
    )
}

private fun RagAdminSearchResponse.applyExactMatchFilterIfRequested(
    request: RagAdminSearchRequest
): RagAdminSearchResponse {
    if (!request.searchExactMatchOnly) {
        return this
    }
    val mode = resolveExactMatchMode(request.searchExactMatchMode)
    val query = request.query.trim()
    if (query.isBlank()) {
        return this
    }
    val queryTokens = tokenizeExactMatchTerms(query, useAnalyzer = true)
    val hitDebug = mutableListOf<RagAdminExactMatchHitDebugResponse>()
    val filteredHits = when (mode.compareMode) {
        "literal" -> {
            val normalizedQuery = normalizeExactMatchText(query)
            hits.filter { hit ->
                val candidateText = normalizeExactMatchText(buildSearchableText(hit))
                val matched = candidateText.contains(normalizedQuery)
                if (matched) {
                    hitDebug.add(
                        RagAdminExactMatchHitDebugResponse(
                            docId = hit.docId,
                            chunkId = hit.chunkId,
                            matchedTokens = queryTokens.toList(),
                            candidateTokens = tokenizeExactMatchTerms(buildSearchableText(hit), useAnalyzer = true).toList()
                        )
                    )
                }
                matched
            }
        }
        else -> {
            if (queryTokens.isEmpty()) {
                return this
            }
            hits.filter { hit ->
                val candidateTokens = tokenizeExactMatchTerms(buildSearchableText(hit), useAnalyzer = true)
                val matchedTokens = queryTokens.filter(candidateTokens::contains)
                val matched = when (mode.combineMode) {
                    "or" -> matchedTokens.isNotEmpty()
                    else -> matchedTokens.size == queryTokens.size
                }
                if (matched) {
                    hitDebug.add(
                        RagAdminExactMatchHitDebugResponse(
                            docId = hit.docId,
                            chunkId = hit.chunkId,
                            matchedTokens = matchedTokens.sorted(),
                            candidateTokens = candidateTokens.toList().sorted()
                        )
                    )
                }
                matched
            }
        }
    }
    val note = if (filteredHits.isEmpty()) {
        "exact match mode enabled but no hits matched ${mode.compareMode}/${mode.combineMode}"
    } else {
        "exact match mode enabled and ${filteredHits.size} hits matched ${mode.compareMode}/${mode.combineMode}"
    }
    return copy(
        hits = filteredHits,
        telemetry = telemetry.copy(notes = telemetry.notes + note),
        exactMatchDebug = RagAdminExactMatchDebugResponse(
            enabled = true,
            compareMode = mode.compareMode,
            combineMode = mode.combineMode,
            queryTokens = queryTokens.toList().sorted(),
            matchedHitCount = hitDebug.size,
            filteredHitCount = filteredHits.size,
            hits = hitDebug
        )
    )
}

private data class ExactMatchMode(val compareMode: String, val combineMode: String)

private fun resolveExactMatchMode(rawMode: String?): ExactMatchMode {
    val normalized = rawMode?.trim()?.lowercase().orEmpty()
    val parts = normalized.split("-", limit = 2).filter { it.isNotBlank() }
    val compareMode = parts.getOrNull(0)?.takeIf { it == "literal" || it == "nori" || it == "normalized" } ?: "nori"
    val combineMode = parts.getOrNull(1)?.takeIf { it == "and" || it == "or" } ?: "and"
    return ExactMatchMode(compareMode, combineMode)
}

private fun buildSearchableText(hit: RagAdminSearchHitResponse): String = buildString {
    append(hit.text.orEmpty())
    append(' ')
    append(hit.docId)
    append(' ')
    append(hit.chunkId)
    append(' ')
    append(hit.sourceUri.orEmpty())
    append(' ')
    append(hit.metadata.entries.joinToString(" ") { (key, value) -> "$key=$value" })
}

private fun normalizeExactMatchText(raw: String): String =
    raw.lowercase()
        .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        .replace(Regex("[^\\p{L}\\p{Nd}\\uAC00-\\uD7A3]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun tokenizeExactMatchTerms(raw: String, useAnalyzer: Boolean): Set<String> {
    if (!useAnalyzer) {
        return normalizeExactMatchText(raw)
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeKoreanFallbackToken(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val normalizedTokens = mutableSetOf<String>()
    KoreanAnalyzer().use { analyzer ->
        analyzer.tokenStream("search", raw).use { tokenStream ->
            val term = tokenStream.addAttribute(CharTermAttribute::class.java)
            tokenStream.reset()
            while (tokenStream.incrementToken()) {
                val token = term.toString().trim()
                if (token.isNotBlank()) {
                    normalizedTokens.add(token)
                }
            }
            tokenStream.end()
        }
    }
    if (normalizedTokens.isEmpty()) {
        return normalizeExactMatchText(raw)
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { normalizeKoreanFallbackToken(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }
    return normalizedTokens
}

private fun normalizeKoreanFallbackToken(token: String): String {
    var current = token.lowercase()
    current = current.trim { !it.isLetterOrDigit() && it !in '\uAC00'..'\uD7A3' }
    if (current.isBlank()) {
        return current
    }
    return stripKoreanSuffixes(current)
}

private fun stripKoreanSuffixes(token: String): String {
    val suffixes = listOf(
        "자마자", "면서", "는데", "지만", "다고", "라고", "하고", "다가", "니까", "네", "죠", "요",
        "겠다", "했", "했어", "했다", "였", "였다", "었", "었다", "았", "았다", "겠", "다",
        "고", "자", "기", "서", "면", "며", "니", "는다", "ㄴ다", "는", "ㄴ",
        "은", "는", "이", "가", "을", "를", "도", "만", "와", "과", "로", "으로", "에", "의",
        "에서", "에게", "한테", "께", "까지", "부터", "보다", "처럼", "같이", "라도", "마다", "조차", "마저"
    ).distinctBy { it.length }.sortedByDescending { it.length }

    var current = token
    var changed = true
    while (changed) {
        changed = false
        for (suffix in suffixes) {
            if (current.length > suffix.length + 1 && current.endsWith(suffix)) {
                current = current.dropLast(suffix.length)
                changed = true
                break
            }
        }
    }
    return current
}
