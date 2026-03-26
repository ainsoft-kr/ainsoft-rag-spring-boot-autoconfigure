package com.ainsoft.rag.graph

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class InMemoryGraphStore : GraphStore {
    private val documents = ConcurrentHashMap<String, GraphDocument>()
    private val nodes = ConcurrentHashMap<String, GraphNode>()
    private val edges = ConcurrentHashMap<String, GraphEdge>()

    override fun upsertDocument(document: GraphDocument) {
        val nodeId = documentNodeId(document.tenantId, document.docId)
        documents[documentKey(document.tenantId, document.docId)] = document
        nodes[nodeId] = GraphNode(
            id = nodeId,
            kind = GraphNodeKind.DOCUMENT,
            tenantId = document.tenantId,
            label = document.title ?: document.docId,
            properties = buildMap {
                put("docId", document.docId)
                document.title?.let { put("title", it) }
                document.sourceUri?.let { put("sourceUri", it) }
                document.contentHash?.let { put("contentHash", it) }
                document.language?.let { put("language", it) }
                if (document.acl.isNotEmpty()) put("acl", document.acl.sorted().joinToString(","))
                document.metadata.forEach { (key, value) -> put("meta.$key", value) }
            }
        )
    }

    override fun upsertProjection(projection: GraphProjection) {
        documents[documentKey(projection.tenantId, projection.documentId)] = GraphDocument(
            tenantId = projection.tenantId,
            docId = projection.documentId,
            title = projection.nodes.firstOrNull { it.kind == GraphNodeKind.DOCUMENT }?.label
                ?: projection.documentId,
            sourceUri = projection.nodes.firstOrNull { it.kind == GraphNodeKind.SOURCE }?.properties?.get("sourceUri"),
            acl = projection.nodes
                .filter { it.kind == GraphNodeKind.PRINCIPAL }
                .mapNotNull { it.properties["principal"] }
                .distinct(),
            metadata = projection.nodes
                .filter { it.kind == GraphNodeKind.METADATA }
                .associate { node ->
                    val key = node.properties["key"].orEmpty()
                    val value = node.properties["value"].orEmpty()
                    key to value
                }
        )
        projection.nodes.forEach { node -> nodes[node.id] = node }
        projection.edges.forEach { edge -> edges[edge.id] = edge }
    }

    override fun deleteDocument(tenantId: String, docId: String) {
        val prefix = "$tenantId|$docId"
        documents.remove(documentKey(tenantId, docId))
        nodes.keys.removeIf { key ->
            key.startsWith("document:$prefix") ||
                key.startsWith("chunk:$prefix")
        }
        edges.keys.removeIf { key ->
            val edge = edges[key] ?: return@removeIf false
            edge.tenantId == tenantId && (edge.fromId.contains(prefix) || edge.toId.contains(prefix))
        }
    }

    override fun deleteTenant(tenantId: String) {
        documents.keys.removeIf { it.startsWith("$tenantId|") }
        nodes.keys.removeIf { key ->
            key.startsWith("document:$tenantId|") ||
                key.startsWith("chunk:$tenantId|") ||
                key.startsWith("entity:$tenantId|") ||
                key.startsWith("principal:$tenantId|") ||
                key.startsWith("source:$tenantId|") ||
                key.startsWith("metadata:$tenantId|") ||
                key == tenantNodeId(tenantId)
        }
        edges.keys.removeIf { key ->
            edges[key]?.tenantId == tenantId
        }
    }

    override fun getDocument(tenantId: String, docId: String): GraphDocument? =
        documents[documentKey(tenantId, docId)]

    override fun getDocumentGraph(tenantId: String, docId: String, depth: Int): GraphSubgraph =
        subgraph(tenantId, documentNodeId(tenantId, docId), depth.coerceAtLeast(0))

    override fun getEntityGraph(tenantId: String, entityId: String, depth: Int): GraphSubgraph =
        subgraph(tenantId, entityNodeId(tenantId, entityId), depth.coerceAtLeast(0))

    override fun getNeighbors(tenantId: String, nodeId: String, depth: Int): GraphSubgraph =
        subgraph(tenantId, nodeId, depth.coerceAtLeast(0))

    override fun getPath(tenantId: String, fromId: String, toId: String, depth: Int): List<GraphNode> {
        val maxDepth = depth.coerceAtLeast(1)
        val queue = ArrayDeque<PathState>()
        val visited = linkedSetOf<String>()
        queue.add(PathState(fromId, listOf(fromId)))
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            if (state.nodeId == toId) {
                return state.path.mapNotNull(nodes::get)
            }
            if (state.path.size > maxDepth + 1) continue
            if (!visited.add(state.nodeId)) continue
            adjacent(state.nodeId, tenantId).forEach { next ->
                if (next !in state.path) {
                    queue.add(PathState(next, state.path + next))
                }
            }
        }
        return emptyList()
    }

    override fun searchEntities(tenantId: String, query: String, limit: Int): List<GraphEntity> =
        nodes.values.asSequence()
            .filter { it.tenantId == tenantId && it.kind == GraphNodeKind.ENTITY }
            .filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.properties.values.any { value -> value.contains(query, ignoreCase = true) }
            }
            .take(limit.coerceAtLeast(0))
            .map {
                GraphEntity(
                    tenantId = it.tenantId,
                    entityId = it.id.substringAfter('|', it.label),
                    name = it.label,
                    type = it.properties["type"],
                    aliases = emptyList()
                )
            }
            .toList()

    override fun searchDocuments(tenantId: String, query: String, limit: Int): List<GraphDocument> =
        documents.values.asSequence()
            .filter { it.tenantId == tenantId }
            .filter {
                it.docId.contains(query, ignoreCase = true) ||
                    (it.title?.contains(query, ignoreCase = true) == true) ||
                    it.metadata.values.any { value -> value.contains(query, ignoreCase = true) }
            }
            .take(limit.coerceAtLeast(0))
            .toList()

    override fun stats(tenantId: String?): GraphStats {
        val filteredNodes = nodes.values.filter { tenantId == null || it.tenantId == tenantId }
        val filteredEdges = edges.values.filter { tenantId == null || it.tenantId == tenantId }
        return GraphStats(
            tenantId = tenantId,
            nodes = filteredNodes.size.toLong(),
            edges = filteredEdges.size.toLong(),
            documents = documents.values.count { tenantId == null || it.tenantId == tenantId }.toLong(),
            entities = filteredNodes.count { it.kind == GraphNodeKind.ENTITY }.toLong(),
            principals = filteredNodes.count { it.kind == GraphNodeKind.PRINCIPAL }.toLong(),
            sources = filteredNodes.count { it.kind == GraphNodeKind.SOURCE }.toLong()
        )
    }

    override fun close() = Unit

    private fun subgraph(tenantId: String, rootNodeId: String, depth: Int): GraphSubgraph {
        if (depth < 0) return GraphSubgraph(centerNodeId = rootNodeId)
        val collectedNodes = linkedMapOf<String, GraphNode>()
        val collectedEdges = linkedMapOf<String, GraphEdge>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = linkedSetOf<String>()
        queue.add(rootNodeId to 0)
        while (queue.isNotEmpty()) {
            val (nodeId, currentDepth) = queue.removeFirst()
            if (!visited.add(nodeId)) continue
            nodes[nodeId]?.takeIf { it.tenantId == tenantId }?.let { collectedNodes[nodeId] = it }
            if (currentDepth >= depth) continue
            adjacent(nodeId, tenantId).forEach { neighborId ->
                nodes[neighborId]?.takeIf { it.tenantId == tenantId }?.let { collectedNodes[neighborId] = it }
                edges.values
                    .filter { it.tenantId == tenantId && ((it.fromId == nodeId && it.toId == neighborId) || (it.fromId == neighborId && it.toId == nodeId)) }
                    .forEach { edge -> collectedEdges[edge.id] = edge }
                queue.add(neighborId to (currentDepth + 1))
            }
        }
        return GraphSubgraph(
            centerNodeId = rootNodeId,
            nodes = collectedNodes.values.toList(),
            edges = collectedEdges.values.toList()
        )
    }

    private fun adjacent(nodeId: String, tenantId: String): List<String> =
        edges.values
            .asSequence()
            .filter { it.tenantId == tenantId && (it.fromId == nodeId || it.toId == nodeId) }
            .flatMap { edge -> sequenceOf(edge.fromId, edge.toId) }
            .filter { it != nodeId }
            .distinct()
            .toList()

    private fun documentKey(tenantId: String, docId: String): String = "$tenantId|$docId"
    private fun documentNodeId(tenantId: String, docId: String): String = "document:$tenantId|$docId"
    private fun entityNodeId(tenantId: String, entityId: String): String = "entity:$tenantId|${entityId.lowercase()}"
    private fun tenantNodeId(tenantId: String): String = "tenant:$tenantId"

    private data class PathState(
        val nodeId: String,
        val path: List<String>
    )
}
