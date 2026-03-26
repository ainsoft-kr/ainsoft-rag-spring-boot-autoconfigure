package com.ainsoft.rag.graph

import com.ainsoft.rag.api.UpsertDocumentRequest
import java.net.URI
import java.util.Locale

class GraphProjectionService(
    private val extractionService: GraphRagExtractionStrategy = GraphRagExtractionService()
) {
    fun projectDocument(
        tenantId: String,
        docId: String,
        request: UpsertDocumentRequest,
        previewChars: Int = 240
    ): GraphProjection {
        val normalizedTenantId = tenantId.trim()
        val normalizedDocId = docId.trim()
        val extraction = extractionService.extract(request, previewChars)
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()

        fun addNode(node: GraphNode) {
            nodes[node.id] = node
        }

        fun addEdge(edge: GraphEdge) {
            edges[edge.id] = edge
        }

        val documentNodeId = GraphSchema.documentNodeId(normalizedTenantId, normalizedDocId)
        addNode(
            GraphNode(
                id = documentNodeId,
                kind = GraphNodeKind.DOCUMENT,
                tenantId = normalizedTenantId,
                label = normalizedDocId,
                properties = buildMap {
                    put(GraphSchema.Properties.TENANT_ID, normalizedTenantId)
                    put(GraphSchema.Properties.GRAPH_ID, documentNodeId)
                    put(GraphSchema.Properties.LABEL, normalizedDocId)
                    put(GraphSchema.Properties.KIND, GraphNodeKind.DOCUMENT.name)
                    put(GraphSchema.Properties.DOC_ID, normalizedDocId)
                    put(GraphSchema.Properties.CONTENT_HASH, request.metadata["rag.contentHash"].orEmpty())
                    request.sourceUri?.let { put(GraphSchema.Properties.SOURCE_URI, it) }
                    request.page?.let { put("page", it.toString()) }
                    put(GraphSchema.Properties.PREVIEW, normalizePreview(request.normalizedText, previewChars))
                }
            )
        )

        request.sourceUri?.takeIf { it.isNotBlank() }?.let { sourceUri ->
            val sourceNodeId = GraphSchema.sourceNodeId(normalizedTenantId, sourceUri)
            addNode(
                GraphNode(
                    id = sourceNodeId,
                    kind = GraphNodeKind.SOURCE,
                    tenantId = normalizedTenantId,
                    label = sourceLabel(sourceUri),
                    properties = mapOf(
                        GraphSchema.Properties.TENANT_ID to normalizedTenantId,
                        GraphSchema.Properties.GRAPH_ID to sourceNodeId,
                        GraphSchema.Properties.LABEL to sourceLabel(sourceUri),
                        GraphSchema.Properties.KIND to GraphNodeKind.SOURCE.name,
                        GraphSchema.Properties.SOURCE_URI to sourceUri,
                        "scheme" to sourceScheme(sourceUri)
                    )
                )
            )
            addEdge(
                GraphEdge(
                    id = GraphSchema.edgeId(documentNodeId, sourceNodeId, GraphSchema.Relations.DERIVED_FROM),
                    fromId = documentNodeId,
                    toId = sourceNodeId,
                    relationType = GraphSchema.Relations.DERIVED_FROM,
                    tenantId = normalizedTenantId
                )
            )
        }

        request.acl.allow
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { principal ->
                val principalNodeId = GraphSchema.principalNodeId(normalizedTenantId, principal)
                addNode(
                    GraphNode(
                        id = principalNodeId,
                        kind = GraphNodeKind.PRINCIPAL,
                        tenantId = normalizedTenantId,
                        label = principal,
                        properties = mapOf(
                            GraphSchema.Properties.TENANT_ID to normalizedTenantId,
                            GraphSchema.Properties.GRAPH_ID to principalNodeId,
                            GraphSchema.Properties.LABEL to principal,
                            GraphSchema.Properties.KIND to GraphNodeKind.PRINCIPAL.name,
                            GraphSchema.Properties.PRINCIPAL to principal
                        )
                )
                )
                addEdge(
                    GraphEdge(
                        id = GraphSchema.edgeId(documentNodeId, principalNodeId, GraphSchema.Relations.ALLOWED_FOR),
                        fromId = documentNodeId,
                        toId = principalNodeId,
                        relationType = GraphSchema.Relations.ALLOWED_FOR,
                        tenantId = normalizedTenantId
                    )
                )
            }

        val tenantNodeId = GraphSchema.tenantNodeId(normalizedTenantId)
        addNode(
            GraphNode(
                id = tenantNodeId,
                kind = GraphNodeKind.TENANT,
                tenantId = normalizedTenantId,
                label = normalizedTenantId,
                properties = mapOf(
                    GraphSchema.Properties.TENANT_ID to normalizedTenantId,
                    GraphSchema.Properties.GRAPH_ID to tenantNodeId,
                    GraphSchema.Properties.LABEL to normalizedTenantId,
                    GraphSchema.Properties.KIND to GraphNodeKind.TENANT.name
                )
            )
        )
        addEdge(
            GraphEdge(
                id = GraphSchema.edgeId(tenantNodeId, documentNodeId, GraphSchema.Relations.OWNS),
                fromId = tenantNodeId,
                toId = documentNodeId,
                relationType = GraphSchema.Relations.OWNS,
                tenantId = normalizedTenantId
            )
        )

        val sectionNodeIds = linkedMapOf<Int, String>()
        extraction.blocks.forEach { block ->
            val sectionId = "section-${block.index}"
            val sectionNodeId = GraphSchema.sectionNodeId(normalizedTenantId, normalizedDocId, sectionId)
            sectionNodeIds[block.index] = sectionNodeId
            addNode(
                GraphNode(
                    id = sectionNodeId,
                    kind = GraphNodeKind.SECTION,
                    tenantId = normalizedTenantId,
                    label = block.sectionLabel,
                    properties = buildMap {
                        put(GraphSchema.Properties.TENANT_ID, normalizedTenantId)
                        put(GraphSchema.Properties.GRAPH_ID, sectionNodeId)
                        put(GraphSchema.Properties.LABEL, block.sectionLabel)
                        put(GraphSchema.Properties.KIND, GraphNodeKind.SECTION.name)
                        put(GraphSchema.Properties.SECTION_ID, sectionId)
                        put(GraphSchema.Properties.ORDINAL, block.index.toString())
                    }
                )
            )
            addEdge(
                GraphEdge(
                    id = GraphSchema.edgeId(documentNodeId, sectionNodeId, GraphSchema.Relations.HAS_SECTION),
                    fromId = documentNodeId,
                    toId = sectionNodeId,
                    relationType = GraphSchema.Relations.HAS_SECTION,
                    tenantId = normalizedTenantId
                )
            )
            val chunkNodeId = GraphSchema.chunkNodeId(normalizedTenantId, normalizedDocId, "chunk-${block.index}")
            addNode(
                GraphNode(
                    id = chunkNodeId,
                    kind = GraphNodeKind.CHUNK,
                    tenantId = normalizedTenantId,
                    label = "chunk-${block.index}",
                    properties = buildMap {
                        put(GraphSchema.Properties.TENANT_ID, normalizedTenantId)
                        put(GraphSchema.Properties.GRAPH_ID, chunkNodeId)
                        put(GraphSchema.Properties.LABEL, "chunk-${block.index}")
                        put(GraphSchema.Properties.KIND, GraphNodeKind.CHUNK.name)
                        put(GraphSchema.Properties.CHUNK_ID, "chunk-${block.index}")
                        put(GraphSchema.Properties.CONTENT_KIND, "chunk")
                        put(GraphSchema.Properties.OFFSET_START, block.offsetStart.toString())
                        put(GraphSchema.Properties.OFFSET_END, block.offsetEnd.toString())
                        put(GraphSchema.Properties.PREVIEW, block.preview)
                        put(GraphSchema.Properties.SECTION_PATH, block.sectionLabel)
                    }
                )
            )
            addEdge(
                GraphEdge(
                    id = GraphSchema.edgeId(sectionNodeId, chunkNodeId, GraphSchema.Relations.HAS_CHUNK),
                    fromId = sectionNodeId,
                    toId = chunkNodeId,
                    relationType = GraphSchema.Relations.HAS_CHUNK,
                    tenantId = normalizedTenantId
                )
            )

            val entities = extraction.entitiesByBlock[block.index].orEmpty()
            entities.forEach { entity ->
                val entityNodeId = GraphSchema.entityNodeId(normalizedTenantId, entity.normalizedName)
                addNode(
                    GraphNode(
                        id = entityNodeId,
                        kind = GraphNodeKind.ENTITY,
                        tenantId = normalizedTenantId,
                        label = entity.displayName,
                        properties = mapOf(
                            GraphSchema.Properties.TENANT_ID to normalizedTenantId,
                            GraphSchema.Properties.GRAPH_ID to entityNodeId,
                            GraphSchema.Properties.LABEL to entity.displayName,
                            GraphSchema.Properties.KIND to GraphNodeKind.ENTITY.name,
                            GraphSchema.Properties.ENTITY_ID to entity.normalizedName,
                            GraphSchema.Properties.ENTITY_NAME to entity.displayName,
                            GraphSchema.Properties.NORMALIZED_NAME to entity.normalizedName,
                            GraphSchema.Properties.ENTITY_TYPE to entity.type
                        )
                    )
                )
                addEdge(
                    GraphEdge(
                        id = GraphSchema.edgeId(chunkNodeId, entityNodeId, GraphSchema.Relations.MENTIONS),
                        fromId = chunkNodeId,
                        toId = entityNodeId,
                        relationType = GraphSchema.Relations.MENTIONS,
                        tenantId = normalizedTenantId,
                        properties = mapOf(GraphSchema.Properties.CONFIDENCE to "0.55")
                    )
                )
            }

            entities.zipWithNext().forEach { (left, right) ->
                if (left.normalizedName != right.normalizedName) {
                    val leftId = GraphSchema.entityNodeId(normalizedTenantId, left.normalizedName)
                    val rightId = GraphSchema.entityNodeId(normalizedTenantId, right.normalizedName)
                    addEdge(
                        GraphEdge(
                            id = GraphSchema.edgeId(leftId, rightId, GraphSchema.Relations.RELATED_TO),
                            fromId = leftId,
                            toId = rightId,
                            relationType = GraphSchema.Relations.RELATED_TO,
                            tenantId = normalizedTenantId,
                            properties = mapOf(GraphSchema.Properties.SOURCE to "cooccurrence")
                        )
                    )
                }
            }

            extraction.sourceUris.forEach { sourceUri ->
                val sourceNodeId = GraphSchema.sourceNodeId(normalizedTenantId, sourceUri)
                addNode(
                    GraphNode(
                        id = sourceNodeId,
                        kind = GraphNodeKind.SOURCE,
                        tenantId = normalizedTenantId,
                        label = sourceLabel(sourceUri),
                        properties = mapOf(
                            GraphSchema.Properties.SOURCE_URI to sourceUri,
                            "scheme" to sourceScheme(sourceUri)
                        )
                    )
                )
                addEdge(
                    GraphEdge(
                        id = GraphSchema.edgeId(chunkNodeId, sourceNodeId, GraphSchema.Relations.CITES),
                        fromId = chunkNodeId,
                        toId = sourceNodeId,
                        relationType = GraphSchema.Relations.CITES,
                        tenantId = normalizedTenantId
                    )
                )
            }
        }

        request.metadata.entries
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .forEach { (key, value) ->
                val metadataNodeId = GraphSchema.metadataNodeId(normalizedTenantId, key, value)
                addNode(
                    GraphNode(
                        id = metadataNodeId,
                        kind = GraphNodeKind.METADATA,
                        tenantId = normalizedTenantId,
                        label = "$key=$value",
                        properties = mapOf(
                            GraphSchema.Properties.TENANT_ID to normalizedTenantId,
                            GraphSchema.Properties.GRAPH_ID to metadataNodeId,
                            GraphSchema.Properties.LABEL to "$key=$value",
                            GraphSchema.Properties.KIND to GraphNodeKind.METADATA.name,
                            GraphSchema.Properties.KEY to key,
                            GraphSchema.Properties.VALUE to value
                        )
                    )
                )
                addEdge(
                    GraphEdge(
                        id = GraphSchema.edgeId(documentNodeId, metadataNodeId, GraphSchema.Relations.HAS_METADATA),
                        fromId = documentNodeId,
                        toId = metadataNodeId,
                        relationType = GraphSchema.Relations.HAS_METADATA,
                        tenantId = normalizedTenantId
                    )
                )
            }

        return GraphProjection(
            tenantId = normalizedTenantId,
            documentId = normalizedDocId,
            nodes = nodes.values.toList(),
            edges = edges.values.toList()
        )
    }

    private fun normalizePreview(text: String, maxChars: Int): String {
        val collapsed = text.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars - 3).trimEnd() + "..."
    }

    private fun sourceLabel(sourceUri: String): String =
        runCatching { URI(sourceUri).host?.takeIf { it.isNotBlank() } ?: sourceUri }.getOrDefault(sourceUri)

    private fun sourceScheme(sourceUri: String): String =
        runCatching { URI(sourceUri).scheme.orEmpty() }.getOrDefault("")
}
