package com.ainsoft.rag.spring

import com.ainsoft.rag.graph.GraphStore
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("\${rag.admin.api-base-path:/api/rag/admin}/graph")
class GraphAdminApiController(
    private val graphStore: GraphStore
) {
    @GetMapping("/stats")
    fun stats(
        @RequestParam(required = false) tenantId: String?
    ) = graphStore.stats(tenantId)

    @GetMapping("/document/{tenantId}/{docId}")
    fun document(
        @PathVariable tenantId: String,
        @PathVariable docId: String,
        @RequestParam(defaultValue = "1") depth: Int
    ) = graphStore.getDocumentGraph(tenantId, docId, depth)

    @GetMapping("/entity/{tenantId}/{entityId}")
    fun entity(
        @PathVariable tenantId: String,
        @PathVariable entityId: String,
        @RequestParam(defaultValue = "1") depth: Int
    ) = graphStore.getEntityGraph(tenantId, entityId, depth)

    @GetMapping("/subgraph", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun subgraph(
        @RequestParam tenantId: String,
        @RequestParam rootId: String,
        @RequestParam(defaultValue = "1") depth: Int
    ) = graphStore.getNeighbors(tenantId, rootId, depth)

    @GetMapping("/path", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun path(
        @RequestParam tenantId: String,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "4") depth: Int
    ) = graphStore.getPath(tenantId, from, to, depth)
}

