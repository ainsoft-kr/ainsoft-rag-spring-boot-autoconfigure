package com.ainsoft.rag.spring

import com.ainsoft.rag.api.GraphRetrievalOptions
import com.ainsoft.rag.api.SearchHit
import com.ainsoft.rag.api.SearchPipeline
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.graph.GraphNode
import com.ainsoft.rag.graph.GraphNodeKind
import com.ainsoft.rag.graph.GraphStore

class GraphEnhancedSearchPipeline(
    private val graphStore: GraphStore,
    private val options: GraphRetrievalOptions
) : SearchPipeline {
    override fun execute(
        request: SearchRequest,
        defaultExecute: (SearchRequest) -> SearchResponse
    ): SearchResponse {
        val base = defaultExecute(request)
        if (!options.enabled || options.maxExpansionTerms <= 0) return base

        val expansionTerms = collectExpansionTerms(request, base)
        if (expansionTerms.isEmpty()) return base

        val expanded = defaultExecute(
            request.copy(
                query = (listOf(request.query) + expansionTerms).joinToString(" ").trim()
            )
        )
        val mergedHits = mergeHits(base.hits, expanded.hits, request.topK)
        val addedHits = mergedHits.count { it.metadata["rag.graphExpanded"] == "true" }
        return SearchResponse(
            hits = mergedHits,
            telemetry = expanded.telemetry.copy(
                originalQuery = request.query,
                notes = (
                    expanded.telemetry.notes +
                        "graphRetrievalApplied=true" +
                        "graphExpansionTerms=${expansionTerms.size}" +
                        "graphExpandedHits=$addedHits"
                    ).distinct()
            )
        )
    }

    private fun collectExpansionTerms(
        request: SearchRequest,
        response: SearchResponse
    ): List<String> {
        val queryTokens = normalizeGraphTokens(request.query)
        val rawTerms = linkedSetOf<String>()

        response.hits
            .asSequence()
            .map { it.source.docId }
            .distinct()
            .take(options.maxSeedDocuments)
            .forEach { docId ->
                graphStore.getDocumentGraph(request.tenantId, docId, options.expandDepth).nodes.forEach { node ->
                    addNodeTerms(rawTerms, node)
                }
            }

        graphStore.searchEntities(request.tenantId, request.query, options.maxEntityMatches)
            .forEach { entity ->
                rawTerms += entity.name
                graphStore.getEntityGraph(request.tenantId, entity.entityId, options.expandDepth).nodes.forEach { node ->
                    addNodeTerms(rawTerms, node)
                }
            }

        graphStore.searchDocuments(request.tenantId, request.query, options.maxDocumentMatches)
            .forEach { document ->
                document.title?.let(rawTerms::add)
                rawTerms += document.metadata.values
            }

        return rawTerms
            .mapNotNull(::sanitizeGraphTerm)
            .filter { term ->
                val normalized = normalizeGraphTokens(term)
                normalized.isNotEmpty() && normalized.any { it !in queryTokens }
            }
            .distinct()
            .take(options.maxExpansionTerms)
    }

    private fun addNodeTerms(terms: MutableSet<String>, node: GraphNode) {
        when (node.kind) {
            GraphNodeKind.ENTITY,
            GraphNodeKind.SECTION,
            GraphNodeKind.METADATA,
            GraphNodeKind.SOURCE -> {
                terms += node.label
                terms += node.properties.values
            }
            GraphNodeKind.DOCUMENT -> {
                node.properties["title"]?.let(terms::add)
                node.properties["preview"]?.let(terms::add)
            }
            else -> Unit
        }
    }

    private fun mergeHits(
        baseHits: List<SearchHit>,
        expandedHits: List<SearchHit>,
        topK: Int
    ): List<SearchHit> {
        val byKey = linkedMapOf<String, SearchHit>()
        baseHits.forEach { hit ->
            byKey[hitKey(hit)] = hit
        }
        expandedHits.forEach { hit ->
            val key = hitKey(hit)
            val boosted = hit.copy(
                score = hit.score + options.scoreBoost,
                metadata = hit.metadata + mapOf("rag.graphExpanded" to "true")
            )
            val current = byKey[key]
            byKey[key] = when {
                current == null -> boosted
                boosted.score > current.score -> boosted.copy(metadata = current.metadata + boosted.metadata)
                else -> current.copy(metadata = current.metadata + boosted.metadata)
            }
        }
        return byKey.values
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
    }

    private fun hitKey(hit: SearchHit): String = "${hit.source.docId}|${hit.source.chunkId}"

    private fun sanitizeGraphTerm(raw: String): String? {
        val normalized = raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(96)
        if (normalized.length < 2) return null
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return null
        if (normalized.matches(Regex("[A-Za-z0-9._:-]{18,}"))) return null
        return normalized
    }

    private fun normalizeGraphTokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
}
