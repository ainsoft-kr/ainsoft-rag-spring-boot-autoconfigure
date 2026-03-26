package com.ainsoft.rag.graph

interface GraphStore : AutoCloseable {
    fun upsertDocument(document: GraphDocument)
    fun upsertProjection(projection: GraphProjection)

    fun deleteDocument(tenantId: String, docId: String)
    fun deleteTenant(tenantId: String)

    fun getDocument(tenantId: String, docId: String): GraphDocument?
    fun getDocumentGraph(tenantId: String, docId: String, depth: Int = 1): GraphSubgraph
    fun getEntityGraph(tenantId: String, entityId: String, depth: Int = 1): GraphSubgraph
    fun getNeighbors(tenantId: String, nodeId: String, depth: Int = 1): GraphSubgraph
    fun getPath(tenantId: String, fromId: String, toId: String, depth: Int = 4): List<GraphNode>

    fun searchEntities(tenantId: String, query: String, limit: Int = 20): List<GraphEntity>
    fun searchDocuments(tenantId: String, query: String, limit: Int = 20): List<GraphDocument>

    fun stats(tenantId: String? = null): GraphStats

    override fun close() = Unit
}

