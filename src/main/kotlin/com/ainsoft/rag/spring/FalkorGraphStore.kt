package com.ainsoft.rag.spring

import com.ainsoft.rag.graph.GraphDocument
import com.ainsoft.rag.graph.GraphEntity
import com.ainsoft.rag.graph.GraphNode
import com.ainsoft.rag.graph.GraphNodeKind
import com.ainsoft.rag.graph.GraphProjection
import com.ainsoft.rag.graph.GraphStats
import com.ainsoft.rag.graph.GraphStore
import com.ainsoft.rag.graph.GraphSubgraph
import com.ainsoft.rag.graph.InMemoryGraphStore
import com.falkordb.FalkorDB
import com.falkordb.Driver
import com.falkordb.Graph
import com.falkordb.Record
import com.falkordb.ResultSet
import com.falkordb.graph_entities.Path

class FalkorGraphStore(
    private val props: RagProperties
) : GraphStore {
    private val mirror = InMemoryGraphStore()
    private val driver: Driver = buildDriver(props)
    private val graph: Graph = driver.graph(graphName(props.graphFalkorGraphNamePrefix))

    init {
        // Verify connectivity eagerly so startup can fail fast when FalkorDB is required.
        graph.query("RETURN 1")
    }

    override fun upsertDocument(document: GraphDocument) {
        mirror.upsertDocument(document)
        upsertNode(
            label = "Document",
            graphId = documentNodeId(document.tenantId, document.docId),
            tenantId = document.tenantId,
            properties = buildMap {
                put("tenantId", document.tenantId)
                put("docId", document.docId)
                document.title?.let { put("title", it) }
                document.sourceUri?.let { put("sourceUri", it) }
                document.contentHash?.let { put("contentHash", it) }
                document.language?.let { put("language", it) }
                if (document.acl.isNotEmpty()) put("acl", document.acl.joinToString(","))
            }
        )
        if (document.sourceUri != null) {
            upsertNode(
                label = "Source",
                graphId = sourceNodeId(document.tenantId, document.sourceUri),
                tenantId = document.tenantId,
                properties = mapOf(
                    "tenantId" to document.tenantId,
                    "sourceUri" to document.sourceUri,
                    "label" to document.sourceUri
                )
            )
            upsertEdge(
                fromGraphId = documentNodeId(document.tenantId, document.docId),
                toGraphId = sourceNodeId(document.tenantId, document.sourceUri),
                relationType = "DERIVED_FROM",
                tenantId = document.tenantId
            )
        }
        document.acl.forEach { principal ->
            upsertNode(
                label = "Principal",
                graphId = principalNodeId(document.tenantId, principal),
                tenantId = document.tenantId,
                properties = mapOf(
                    "tenantId" to document.tenantId,
                    "principal" to principal,
                    "label" to principal
                )
            )
            upsertEdge(
                fromGraphId = documentNodeId(document.tenantId, document.docId),
                toGraphId = principalNodeId(document.tenantId, principal),
                relationType = "ALLOWED_FOR",
                tenantId = document.tenantId
            )
        }
    }

    override fun upsertProjection(projection: GraphProjection) {
        mirror.upsertProjection(projection)
        val nodesById = projection.nodes.associateBy { it.id }
        projection.nodes.forEach { node ->
            upsertNode(
                label = nodeLabel(node.kind),
                graphId = node.id,
                tenantId = node.tenantId,
                properties = node.properties + mapOf(
                    "tenantId" to node.tenantId,
                    "graphId" to node.id,
                    "label" to node.label,
                    "kind" to node.kind.name
                )
            )
        }
        projection.edges.forEach { edge ->
            val fromNode = nodesById[edge.fromId]
            val toNode = nodesById[edge.toId]
            if (fromNode != null && toNode != null) {
                upsertEdge(
                    fromGraphId = fromNode.id,
                    toGraphId = toNode.id,
                    relationType = edge.relationType,
                    tenantId = edge.tenantId,
                    properties = edge.properties + mapOf(
                        "tenantId" to edge.tenantId,
                        "relationType" to edge.relationType
                    )
                )
            }
        }
    }

    override fun deleteDocument(tenantId: String, docId: String) {
        mirror.deleteDocument(tenantId, docId)
        graph.query(
            """
            MATCH (d {tenantId: ${'$'}tenantId, graphId: ${'$'}graphId})
            DETACH DELETE d
            """.trimIndent(),
            mapOf(
                "tenantId" to tenantId,
                "graphId" to documentNodeId(tenantId, docId)
            )
        )
    }

    override fun deleteTenant(tenantId: String) {
        mirror.deleteTenant(tenantId)
        graph.query(
            """
            MATCH (n {tenantId: ${'$'}tenantId})
            DETACH DELETE n
            """.trimIndent(),
            mapOf("tenantId" to tenantId)
        )
    }

    override fun getDocument(tenantId: String, docId: String): GraphDocument? =
        runCatching {
            querySingleNode(tenantId, documentNodeId(tenantId, docId))
                ?.let(::nodeToDocument)
        }.getOrElse {
            mirror.getDocument(tenantId, docId)
        }

    override fun getDocumentGraph(tenantId: String, docId: String, depth: Int): GraphSubgraph =
        runCatching {
            readSubgraph(tenantId, documentNodeId(tenantId, docId), depth)
        }.getOrElse {
            mirror.getDocumentGraph(tenantId, docId, depth)
        }

    override fun getEntityGraph(tenantId: String, entityId: String, depth: Int): GraphSubgraph =
        runCatching {
            readSubgraph(tenantId, entityNodeId(tenantId, entityId), depth)
        }.getOrElse {
            mirror.getEntityGraph(tenantId, entityId, depth)
        }

    override fun getNeighbors(tenantId: String, nodeId: String, depth: Int): GraphSubgraph =
        runCatching {
            readSubgraph(tenantId, nodeId, depth)
        }.getOrElse {
            mirror.getNeighbors(tenantId, nodeId, depth)
        }

    override fun getPath(tenantId: String, fromId: String, toId: String, depth: Int): List<GraphNode> =
        runCatching {
            readPath(tenantId, fromId, toId, depth)
        }.getOrElse {
            mirror.getPath(tenantId, fromId, toId, depth)
        }

    override fun searchEntities(tenantId: String, query: String, limit: Int): List<GraphEntity> =
        runCatching {
            queryNodes(
                """
                MATCH (n:Entity {tenantId: ${'$'}tenantId})
                WHERE toLower(n.label) CONTAINS toLower(${'$'}query)
                   OR toLower(coalesce(n.normalizedName, n.label)) CONTAINS toLower(${'$'}query)
                RETURN n.graphId AS id, n.label AS label, properties(n) AS props
                ORDER BY n.label
                LIMIT ${'$'}limit
                """.trimIndent(),
                tenantId,
                mapOf("query" to query, "limit" to limit)
            ).map { node ->
                GraphEntity(
                    tenantId = tenantId,
                    entityId = node.id,
                    name = node.label,
                    type = node.properties["kind"] ?: "ENTITY",
                    aliases = emptyList()
                )
            }
        }.getOrElse {
            mirror.searchEntities(tenantId, query, limit)
        }

    override fun searchDocuments(tenantId: String, query: String, limit: Int): List<GraphDocument> =
        runCatching {
            queryNodes(
                """
                MATCH (n:Document {tenantId: ${'$'}tenantId})
                WHERE toLower(coalesce(n.title, n.docId)) CONTAINS toLower(${'$'}query)
                   OR toLower(n.docId) CONTAINS toLower(${'$'}query)
                RETURN n.graphId AS id, n.label AS label, properties(n) AS props
                ORDER BY n.label
                LIMIT ${'$'}limit
                """.trimIndent(),
                tenantId,
                mapOf("query" to query, "limit" to limit)
            ).map(::nodeToDocument)
        }.getOrElse {
            mirror.searchDocuments(tenantId, query, limit)
        }

    override fun stats(tenantId: String?): GraphStats =
        runCatching {
            val tenantFilter = tenantId?.trim()?.takeIf { it.isNotBlank() }
            val nodes = countQuery(
                """
                MATCH (n)
                WHERE ${tenantFilter?.let { "n.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(n) AS count
                """.trimIndent(),
                tenantFilter
            )
            val edges = countQuery(
                """
                MATCH ()-[r]->()
                WHERE ${tenantFilter?.let { "r.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(r) AS count
                """.trimIndent(),
                tenantFilter
            )
            val documents = countQuery(
                """
                MATCH (n:Document)
                WHERE ${tenantFilter?.let { "n.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(n) AS count
                """.trimIndent(),
                tenantFilter
            )
            val entities = countQuery(
                """
                MATCH (n:Entity)
                WHERE ${tenantFilter?.let { "n.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(n) AS count
                """.trimIndent(),
                tenantFilter
            )
            val principals = countQuery(
                """
                MATCH (n:Principal)
                WHERE ${tenantFilter?.let { "n.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(n) AS count
                """.trimIndent(),
                tenantFilter
            )
            val sources = countQuery(
                """
                MATCH (n:Source)
                WHERE ${tenantFilter?.let { "n.tenantId = ${'$'}tenantId" } ?: "true"}
                RETURN count(n) AS count
                """.trimIndent(),
                tenantFilter
            )
            GraphStats(
                tenantId = tenantFilter,
                nodes = nodes,
                edges = edges,
                documents = documents,
                entities = entities,
                principals = principals,
                sources = sources
            )
        }.getOrElse {
            mirror.stats(tenantId)
        }

    override fun close() {
        runCatching { driver.close() }
    }

    private fun upsertNode(
        label: String,
        graphId: String,
        tenantId: String,
        properties: Map<String, String>
    ) {
        graph.query(
            """
            MERGE (n:$label {tenantId: ${'$'}tenantId, graphId: ${'$'}graphId})
            SET n += ${'$'}props
            """.trimIndent(),
            mapOf(
                "tenantId" to tenantId,
                "graphId" to graphId,
                "props" to properties
            )
        )
    }

    private fun upsertEdge(
        fromGraphId: String,
        toGraphId: String,
        relationType: String,
        tenantId: String,
        properties: Map<String, String> = emptyMap()
    ) {
        graph.query(
            """
            MATCH (a {tenantId: ${'$'}tenantId, graphId: ${'$'}fromGraphId})
            MATCH (b {tenantId: ${'$'}tenantId, graphId: ${'$'}toGraphId})
            MERGE (a)-[r:$relationType]->(b)
            SET r += ${'$'}props
            """.trimIndent(),
            mapOf(
                "tenantId" to tenantId,
                "fromGraphId" to fromGraphId,
                "toGraphId" to toGraphId,
                "props" to properties
            )
        )
    }

    private fun querySingleNode(tenantId: String, graphId: String): GraphNode? {
        val rows = queryNodes(
            """
            MATCH (n {tenantId: ${'$'}tenantId, graphId: ${'$'}graphId})
            RETURN n.graphId AS id, n.label AS label, properties(n) AS props
            LIMIT 1
            """.trimIndent(),
            tenantId,
            mapOf("graphId" to graphId)
        )
        return rows.firstOrNull()
    }

    private fun readSubgraph(tenantId: String, rootId: String, depth: Int): GraphSubgraph {
        val safeDepth = depth.coerceAtLeast(0)
        val nodes = queryNodes(
            """
            MATCH p = (root {tenantId: ${'$'}tenantId, graphId: ${'$'}rootId})-[*0..${'$'}depth]-(node)
            UNWIND nodes(p) AS n
            RETURN DISTINCT n.graphId AS id, n.label AS label, properties(n) AS props
            """.trimIndent(),
            tenantId,
            mapOf("rootId" to rootId, "depth" to safeDepth)
        )
        val edges = queryEdges(
            """
            MATCH p = (root {tenantId: ${'$'}tenantId, graphId: ${'$'}rootId})-[*1..${'$'}depth]-(node)
            UNWIND relationships(p) AS r
            RETURN DISTINCT startNode(r).graphId AS fromId,
                            endNode(r).graphId AS toId,
                            type(r) AS relationType,
                            properties(r) AS props
            """.trimIndent(),
            tenantId,
            mapOf("rootId" to rootId, "depth" to safeDepth)
        )
        return GraphSubgraph(
            centerNodeId = rootId,
            nodes = nodes,
            edges = edges
        )
    }

    private fun readPath(tenantId: String, fromId: String, toId: String, depth: Int): List<GraphNode> {
        val safeDepth = depth.coerceAtLeast(1)
        val result = graph.readOnlyQuery(
            """
            MATCH p = shortestPath((from {tenantId: ${'$'}tenantId, graphId: ${'$'}fromId})-[*..${'$'}depth]-(to {tenantId: ${'$'}tenantId, graphId: ${'$'}toId}))
            RETURN p AS path
            LIMIT 1
            """.trimIndent(),
            mapOf(
                "tenantId" to tenantId,
                "fromId" to fromId,
                "toId" to toId,
                "depth" to safeDepth
            )
        )
        val path = result.firstOrNull()?.getValue<Path>("path") ?: return emptyList()
        return path.nodes.map { node ->
            GraphNode(
                id = propertyString(node, "graphId") ?: "node:${node.id}",
                kind = GraphNodeKind.valueOf((propertyString(node, "kind") ?: "DOCUMENT").uppercase()),
                tenantId = propertyString(node, "tenantId") ?: tenantId,
                label = propertyString(node, "label") ?: propertyString(node, "docId") ?: "node",
                properties = entityProperties(node)
            )
        }
    }

    private fun queryNodes(
        cypher: String,
        tenantId: String,
        params: Map<String, Any>
    ): List<GraphNode> {
        val result = graph.readOnlyQuery(
            cypher,
            buildMap {
                put("tenantId", tenantId)
                putAll(params)
            }
        )
        return result.mapNotNull { record -> record.toGraphNodeOrNull() }
    }

    private fun queryEdges(
        cypher: String,
        tenantId: String,
        params: Map<String, Any>
    ): List<com.ainsoft.rag.graph.GraphEdge> {
        val result = graph.readOnlyQuery(
            cypher,
            buildMap {
                put("tenantId", tenantId)
                putAll(params)
            }
        )
        return result.mapNotNull { record ->
            val fromId = record.getString("fromId") ?: return@mapNotNull null
            val toId = record.getString("toId") ?: return@mapNotNull null
            val relationType = record.getString("relationType") ?: return@mapNotNull null
            com.ainsoft.rag.graph.GraphEdge(
                id = edgeId(fromId, toId, relationType),
                fromId = fromId,
                toId = toId,
                relationType = relationType,
                tenantId = tenantId,
                properties = record.getValue<Map<String, Any?>>("props")?.entries?.associate { (key, value) ->
                    key to value.toString()
                }.orEmpty()
            )
        }
    }

    private fun countQuery(cypher: String, tenantId: String?): Long {
        val result = graph.readOnlyQuery(
            cypher,
            tenantId?.let { mapOf("tenantId" to it) } ?: emptyMap()
        )
        return result.firstOrNull()?.getValue<Number>("count")?.toLong() ?: 0L
    }

    private fun Record.toGraphNodeOrNull(): GraphNode? {
        val id = getString("id") ?: return null
        val props = getValue<Map<String, Any?>>("props") ?: emptyMap()
        val properties = props.entries.associate { (key, value) ->
            key to value.toString()
        }
        val kind = properties["kind"]?.let {
            runCatching { GraphNodeKind.valueOf(it.uppercase()) }.getOrNull()
        } ?: when {
            id.startsWith("document:") -> GraphNodeKind.DOCUMENT
            id.startsWith("chunk:") -> GraphNodeKind.CHUNK
            id.startsWith("section:") -> GraphNodeKind.SECTION
            id.startsWith("entity:") -> GraphNodeKind.ENTITY
            id.startsWith("principal:") -> GraphNodeKind.PRINCIPAL
            id.startsWith("source:") -> GraphNodeKind.SOURCE
            id.startsWith("tenant:") -> GraphNodeKind.TENANT
            else -> GraphNodeKind.METADATA
        }
        return GraphNode(
            id = id,
            kind = kind,
            tenantId = properties["tenantId"] ?: "unknown",
            label = properties["label"] ?: properties["title"] ?: properties["docId"] ?: id,
            properties = properties
        )
    }

    private fun nodeToDocument(node: GraphNode): GraphDocument = GraphDocument(
        tenantId = node.tenantId,
        docId = node.properties["docId"] ?: node.id.substringAfter('|', node.id),
        title = node.properties["title"] ?: node.label,
        sourceUri = node.properties["sourceUri"],
        contentHash = node.properties["contentHash"],
        language = node.properties["language"],
        acl = node.properties["acl"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        metadata = node.properties.filterKeys { key -> key.startsWith("meta.") }
            .mapKeys { (key, _) -> key.removePrefix("meta.") }
    )

    private fun entityProperties(node: com.falkordb.graph_entities.GraphEntity): Map<String, String> =
        node.entityPropertyNames.associateWith { key -> propertyString(node, key).orEmpty() }

    private fun propertyString(entity: com.falkordb.graph_entities.GraphEntity, key: String): String? =
        entity.getProperty(key)?.value?.toString()

    private fun nodeLabel(kind: GraphNodeKind): String =
        when (kind) {
            GraphNodeKind.TENANT -> "Tenant"
            GraphNodeKind.DOCUMENT -> "Document"
            GraphNodeKind.CHUNK -> "Chunk"
            GraphNodeKind.SECTION -> "Section"
            GraphNodeKind.ENTITY -> "Entity"
            GraphNodeKind.PRINCIPAL -> "Principal"
            GraphNodeKind.SOURCE -> "Source"
            GraphNodeKind.METADATA -> "Metadata"
        }

    private fun buildDriver(props: RagProperties): Driver {
        val username = props.graphFalkorUsername?.trim().orEmpty()
        val password = props.graphFalkorPassword?.trim().orEmpty()
        return if (username.isNotBlank() || password.isNotBlank()) {
            FalkorDB.driver(
                props.graphFalkorHost,
                props.graphFalkorPort,
                username,
                password
            )
        } else {
            FalkorDB.driver(props.graphFalkorHost, props.graphFalkorPort)
        }
    }

    private fun graphName(prefix: String): String =
        prefix.trim().ifBlank { "rag" }

    private fun documentNodeId(tenantId: String, docId: String): String = "document:$tenantId|$docId"
    private fun entityNodeId(tenantId: String, entityId: String): String = "entity:$tenantId|${entityId.lowercase()}"
    private fun principalNodeId(tenantId: String, principal: String): String = "principal:$tenantId|${principal.lowercase()}"
    private fun sourceNodeId(tenantId: String, sourceUri: String): String = "source:$tenantId|${sourceUri.lowercase()}"
    private fun edgeId(fromId: String, toId: String, relationType: String): String = "$fromId->$toId#$relationType"
}
