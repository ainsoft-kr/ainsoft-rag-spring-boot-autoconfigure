package com.ainsoft.rag.spring

import com.ainsoft.rag.api.GraphRetrievalOptions
import com.ainsoft.rag.api.SearchHit
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.api.SearchTelemetry
import com.ainsoft.rag.api.SourceRef
import com.ainsoft.rag.graph.GraphEdge
import com.ainsoft.rag.graph.GraphNode
import com.ainsoft.rag.graph.GraphNodeKind
import com.ainsoft.rag.graph.GraphProjection
import com.ainsoft.rag.graph.InMemoryGraphStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEnhancedSearchPipelineTest {
    @Test
    fun `graph pipeline expands query and merges graph boosted hits`() {
        val store = InMemoryGraphStore()
        store.upsertProjection(
            GraphProjection(
                tenantId = "tenant-a",
                documentId = "doc-a",
                nodes = listOf(
                    GraphNode(
                        id = "document:tenant-a|doc-a",
                        kind = GraphNodeKind.DOCUMENT,
                        tenantId = "tenant-a",
                        label = "doc-a",
                        properties = mapOf("title" to "Pump Maintenance")
                    ),
                    GraphNode(
                        id = "entity:tenant-a|cooling-pump",
                        kind = GraphNodeKind.ENTITY,
                        tenantId = "tenant-a",
                        label = "Cooling Pump",
                        properties = mapOf("type" to "equipment")
                    )
                ),
                edges = listOf(
                    GraphEdge(
                        id = "edge-1",
                        fromId = "document:tenant-a|doc-a",
                        toId = "entity:tenant-a|cooling-pump",
                        relationType = "MENTIONS",
                        tenantId = "tenant-a"
                    )
                )
            )
        )

        val pipeline = GraphEnhancedSearchPipeline(
            graphStore = store,
            options = GraphRetrievalOptions(enabled = true, maxExpansionTerms = 6, scoreBoost = 0.1)
        )
        val queries = mutableListOf<String>()
        val request = SearchRequest(
            tenantId = "tenant-a",
            principals = listOf("group:all"),
            query = "pump issue",
            topK = 3
        )

        val response = pipeline.execute(request) { delegated ->
            queries += delegated.query
            if (delegated.query == request.query) {
                SearchResponse(
                    hits = listOf(
                        SearchHit(
                            source = SourceRef(docId = "doc-a", chunkId = "chunk-1"),
                            score = 0.8,
                            text = "pump issue baseline"
                        )
                    ),
                    telemetry = SearchTelemetry(
                        executedQuery = delegated.query,
                        originalQuery = delegated.query
                    )
                )
            } else {
                SearchResponse(
                    hits = listOf(
                        SearchHit(
                            source = SourceRef(docId = "doc-a", chunkId = "chunk-1"),
                            score = 0.7,
                            text = "pump issue baseline"
                        ),
                        SearchHit(
                            source = SourceRef(docId = "doc-b", chunkId = "chunk-2"),
                            score = 0.76,
                            text = "cooling pump failure escalation"
                        )
                    ),
                    telemetry = SearchTelemetry(
                        executedQuery = delegated.query,
                        originalQuery = delegated.query
                    )
                )
            }
        }

        assertEquals(2, queries.size)
        assertTrue(queries.last().contains("Cooling Pump"))
        assertEquals(2, response.hits.size)
        assertEquals("doc-b", response.hits[0].source.docId)
        assertEquals("true", response.hits[0].metadata["rag.graphExpanded"])
        assertTrue(response.telemetry.notes.any { it == "graphRetrievalApplied=true" })
    }
}
