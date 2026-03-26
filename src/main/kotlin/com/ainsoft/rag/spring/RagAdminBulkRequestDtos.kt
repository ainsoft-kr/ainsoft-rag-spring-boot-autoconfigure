package com.ainsoft.rag.spring

class RagAdminWebIngestRequest() {
    var tenantId: String = ""
    var urls: List<String> = emptyList()
    var acl: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()
    var allowedDomains: List<String> = emptyList()
    var respectRobotsTxt: Boolean = true
    var incrementalIngest: Boolean = true
    var sourceLoadProfile: String? = null
    var userAgent: String = "AinsoftRagBot/1.0"
    var maxPages: Int? = null
    var maxDepth: Int? = null
    var sameHostOnly: Boolean = true
    var charset: String = "UTF-8"

    constructor(
        tenantId: String,
        urls: List<String>,
        acl: List<String>,
        metadata: Map<String, String> = emptyMap(),
        allowedDomains: List<String> = emptyList(),
        respectRobotsTxt: Boolean = true,
        incrementalIngest: Boolean = true,
        sourceLoadProfile: String? = null,
        userAgent: String = "AinsoftRagBot/1.0",
        maxPages: Int? = null,
        maxDepth: Int? = null,
        sameHostOnly: Boolean = true,
        charset: String = "UTF-8"
    ) : this() {
        this.tenantId = tenantId
        this.urls = urls
        this.acl = acl
        this.metadata = metadata
        this.allowedDomains = allowedDomains
        this.respectRobotsTxt = respectRobotsTxt
        this.incrementalIngest = incrementalIngest
        this.sourceLoadProfile = sourceLoadProfile
        this.userAgent = userAgent
        this.maxPages = maxPages
        this.maxDepth = maxDepth
        this.sameHostOnly = sameHostOnly
        this.charset = charset
    }
}

class RagAdminBulkTextIngestRequest() {
    var tenantId: String = ""
    var documents: List<RagAdminBulkTextDocument> = emptyList()
    var incrementalIngest: Boolean = true

    constructor(
        tenantId: String,
        documents: List<RagAdminBulkTextDocument>,
        incrementalIngest: Boolean = true
    ) : this() {
        this.tenantId = tenantId
        this.documents = documents
        this.incrementalIngest = incrementalIngest
    }
}

class RagAdminBulkTextDocument() {
    var docId: String = ""
    var text: String = ""
    var acl: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()
    var sourceUri: String? = null
    var page: Int? = null
    var pageMarkers: List<RagAdminPageMarkerRequest>? = null

    constructor(
        docId: String,
        text: String,
        acl: List<String>,
        metadata: Map<String, String> = emptyMap(),
        sourceUri: String? = null,
        page: Int? = null,
        pageMarkers: List<RagAdminPageMarkerRequest>? = null
    ) : this() {
        this.docId = docId
        this.text = text
        this.acl = acl
        this.metadata = metadata
        this.sourceUri = sourceUri
        this.page = page
        this.pageMarkers = pageMarkers
    }
}

class RagAdminBulkDeleteRequest() {
    var tenantId: String = ""
    var docIds: List<String> = emptyList()

    constructor(tenantId: String, docIds: List<String>) : this() {
        this.tenantId = tenantId
        this.docIds = docIds
    }
}

class RagAdminBulkMetadataPatchRequest() {
    var tenantId: String = ""
    var docIds: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()

    constructor(tenantId: String, docIds: List<String>, metadata: Map<String, String>) : this() {
        this.tenantId = tenantId
        this.docIds = docIds
        this.metadata = metadata
    }
}
