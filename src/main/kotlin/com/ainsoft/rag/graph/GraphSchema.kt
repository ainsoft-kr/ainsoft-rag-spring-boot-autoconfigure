package com.ainsoft.rag.graph

object GraphSchema {
    object Labels {
        const val TENANT = "Tenant"
        const val DOCUMENT = "Document"
        const val CHUNK = "Chunk"
        const val SECTION = "Section"
        const val ENTITY = "Entity"
        const val PRINCIPAL = "Principal"
        const val SOURCE = "Source"
        const val METADATA = "Metadata"
    }

    object Relations {
        const val OWNS = "OWNS"
        const val HAS_SECTION = "HAS_SECTION"
        const val HAS_CHUNK = "HAS_CHUNK"
        const val MENTIONS = "MENTIONS"
        const val RELATED_TO = "RELATED_TO"
        const val ALLOWED_FOR = "ALLOWED_FOR"
        const val DERIVED_FROM = "DERIVED_FROM"
        const val HAS_METADATA = "HAS_METADATA"
        const val CITES = "CITES"
    }

    object Properties {
        const val TENANT_ID = "tenantId"
        const val GRAPH_ID = "graphId"
        const val LABEL = "label"
        const val KIND = "kind"
        const val DOC_ID = "docId"
        const val CHUNK_ID = "chunkId"
        const val SECTION_ID = "sectionId"
        const val ENTITY_ID = "entityId"
        const val ENTITY_NAME = "name"
        const val NORMALIZED_NAME = "normalizedName"
        const val ENTITY_TYPE = "entityType"
        const val PRINCIPAL = "principal"
        const val SOURCE_URI = "sourceUri"
        const val TITLE = "title"
        const val CONTENT_HASH = "contentHash"
        const val LANGUAGE = "language"
        const val PREVIEW = "preview"
        const val CONTENT_KIND = "contentKind"
        const val OFFSET_START = "offsetStart"
        const val OFFSET_END = "offsetEnd"
        const val SECTION_PATH = "sectionPath"
        const val CONFIDENCE = "confidence"
        const val SOURCE = "source"
        const val VALUE = "value"
        const val KEY = "key"
        const val ORDINAL = "ordinal"
    }

    fun documentNodeId(tenantId: String, docId: String): String = "document:$tenantId|$docId"
    fun chunkNodeId(tenantId: String, docId: String, chunkId: String): String = "chunk:$tenantId|$docId|$chunkId"
    fun sectionNodeId(tenantId: String, docId: String, sectionId: String): String = "section:$tenantId|$docId|$sectionId"
    fun entityNodeId(tenantId: String, entityId: String): String = "entity:$tenantId|${entityId.lowercase()}"
    fun principalNodeId(tenantId: String, principal: String): String = "principal:$tenantId|${principal.lowercase()}"
    fun sourceNodeId(tenantId: String, sourceUri: String): String = "source:$tenantId|${sourceUri.lowercase()}"
    fun metadataNodeId(tenantId: String, key: String, value: String): String = "metadata:$tenantId|${key.lowercase()}=$value"
    fun tenantNodeId(tenantId: String): String = "tenant:$tenantId"
    fun edgeId(fromId: String, toId: String, relationType: String): String = "$fromId->$toId#$relationType"
}
