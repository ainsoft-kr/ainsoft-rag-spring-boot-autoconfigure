package com.ainsoft.rag.spring

class RagAdminIngestRequest() {
    var tenantId: String = ""
    var docId: String = ""
    var text: String = ""
    var acl: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()
    var sourceUri: String? = null
    var page: Int? = null
    var pageMarkers: List<RagAdminPageMarkerRequest>? = null
    var incrementalIngest: Boolean = true

    constructor(
        tenantId: String,
        docId: String,
        text: String,
        acl: List<String>,
        metadata: Map<String, String> = emptyMap(),
        sourceUri: String? = null,
        page: Int? = null,
        pageMarkers: List<RagAdminPageMarkerRequest>? = null,
        incrementalIngest: Boolean = true
    ) : this() {
        this.tenantId = tenantId
        this.docId = docId
        this.text = text
        this.acl = acl
        this.metadata = metadata
        this.sourceUri = sourceUri
        this.page = page
        this.pageMarkers = pageMarkers
        this.incrementalIngest = incrementalIngest
    }

    fun copy(
        tenantId: String = this.tenantId,
        docId: String = this.docId,
        text: String = this.text,
        acl: List<String> = this.acl,
        metadata: Map<String, String> = this.metadata,
        sourceUri: String? = this.sourceUri,
        page: Int? = this.page,
        pageMarkers: List<RagAdminPageMarkerRequest>? = this.pageMarkers,
        incrementalIngest: Boolean = this.incrementalIngest
    ): RagAdminIngestRequest = RagAdminIngestRequest(
        tenantId = tenantId,
        docId = docId,
        text = text,
        acl = acl,
        metadata = metadata,
        sourceUri = sourceUri,
        page = page,
        pageMarkers = pageMarkers,
        incrementalIngest = incrementalIngest
    )
}

class RagAdminPageMarkerRequest() {
    var page: Int = 0
    var offsetStart: Int = 0
    var offsetEnd: Int = 0

    constructor(page: Int, offsetStart: Int, offsetEnd: Int) : this() {
        this.page = page
        this.offsetStart = offsetStart
        this.offsetEnd = offsetEnd
    }
}

class RagAdminSearchRequest() {
    var tenantId: String = ""
    var principals: List<String> = emptyList()
    var query: String = ""
    var topK: Int = 8
    var filter: Map<String, String> = emptyMap()
    var providerHealthDetail: Boolean = true
    var recentProviderWindowMillis: Long? = null
    var searchNoMatchMinFinalConfidence: Double? = null
    var searchNoMatchMinTopHitScore: Double? = null
    var searchExactMatchOnly: Boolean = false
    var searchExactMatchMode: String? = null
    var diagnosticScoreThreshold: Double = Double.NEGATIVE_INFINITY
    var diagnosticMaxSamples: Int = 5

    constructor(
        tenantId: String,
        principals: List<String>,
        query: String,
        topK: Int = 8,
        filter: Map<String, String> = emptyMap(),
        providerHealthDetail: Boolean = true,
        recentProviderWindowMillis: Long? = null,
        searchNoMatchMinFinalConfidence: Double? = null,
        searchNoMatchMinTopHitScore: Double? = null,
        searchExactMatchOnly: Boolean = false,
        searchExactMatchMode: String? = null,
        diagnosticScoreThreshold: Double = Double.NEGATIVE_INFINITY,
        diagnosticMaxSamples: Int = 5
    ) : this() {
        this.tenantId = tenantId
        this.principals = principals
        this.query = query
        this.topK = topK
        this.filter = filter
        this.providerHealthDetail = providerHealthDetail
        this.recentProviderWindowMillis = recentProviderWindowMillis
        this.searchNoMatchMinFinalConfidence = searchNoMatchMinFinalConfidence
        this.searchNoMatchMinTopHitScore = searchNoMatchMinTopHitScore
        this.searchExactMatchOnly = searchExactMatchOnly
        this.searchExactMatchMode = searchExactMatchMode
        this.diagnosticScoreThreshold = diagnosticScoreThreshold
        this.diagnosticMaxSamples = diagnosticMaxSamples
    }
}

class RagAdminDocumentReindexRequest() {
    var text: String? = null
    var sourceUri: String? = null
    var metadata: Map<String, String>? = null
    var acl: List<String>? = null
    var charset: String = "UTF-8"

    constructor(
        text: String? = null,
        sourceUri: String? = null,
        metadata: Map<String, String>? = null,
        acl: List<String>? = null,
        charset: String = "UTF-8"
    ) : this() {
        this.text = text
        this.sourceUri = sourceUri
        this.metadata = metadata
        this.acl = acl
        this.charset = charset
    }
}
