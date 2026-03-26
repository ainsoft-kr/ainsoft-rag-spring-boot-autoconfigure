package com.ainsoft.rag.graph

enum class GraphNodeKind {
    TENANT,
    DOCUMENT,
    CHUNK,
    SECTION,
    ENTITY,
    PRINCIPAL,
    SOURCE,
    METADATA
}

data class GraphNode(
    val id: String,
    val kind: GraphNodeKind,
    val tenantId: String,
    val label: String,
    val properties: Map<String, String> = emptyMap()
)

data class GraphEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val relationType: String,
    val tenantId: String,
    val properties: Map<String, String> = emptyMap()
)

data class GraphProjection(
    val tenantId: String,
    val documentId: String,
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList()
)

data class GraphDocument(
    val tenantId: String,
    val docId: String,
    val title: String? = null,
    val sourceUri: String? = null,
    val contentHash: String? = null,
    val language: String? = null,
    val acl: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class GraphChunk(
    val tenantId: String,
    val docId: String,
    val chunkId: String,
    val sectionPath: List<String> = emptyList(),
    val offsetStart: Int? = null,
    val offsetEnd: Int? = null,
    val contentKind: String = "chunk",
    val preview: String? = null
)

data class GraphSection(
    val tenantId: String,
    val docId: String,
    val sectionId: String,
    val heading: String? = null,
    val ordinal: Int? = null
)

data class GraphEntity(
    val tenantId: String,
    val entityId: String,
    val name: String,
    val type: String? = null,
    val aliases: List<String> = emptyList()
)

data class GraphRelation(
    val tenantId: String,
    val fromId: String,
    val toId: String,
    val relationType: String,
    val weight: Double = 1.0,
    val source: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class GraphSubgraph(
    val centerNodeId: String,
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList()
)

data class GraphStats(
    val tenantId: String? = null,
    val nodes: Long = 0L,
    val edges: Long = 0L,
    val documents: Long = 0L,
    val entities: Long = 0L,
    val principals: Long = 0L,
    val sources: Long = 0L
)

