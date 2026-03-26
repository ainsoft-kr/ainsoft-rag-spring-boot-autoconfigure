package com.ainsoft.rag.spring

import com.ainsoft.rag.graph.InMemoryGraphStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphAutoConfigurationTest {
    private val configuration = GraphAutoConfiguration()

    @Test
    fun `graph store falls back to memory when allowed`() {
        val store = configuration.graphStore(
            RagProperties(
                graphProvider = "falkordb",
                graphFalkorHost = "127.0.0.1",
                graphFalkorPort = 1,
                graphFallbackToMemory = true
            )
        )

        assertTrue(store is InMemoryGraphStore)
    }

    @Test
    fun `graph store fails fast when fallback is disabled`() {
        assertFailsWith<IllegalStateException> {
            configuration.graphStore(
                RagProperties(
                    graphProvider = "falkordb",
                    graphFalkorHost = "127.0.0.1",
                    graphFalkorPort = 1,
                    graphFallbackToMemory = false
                )
            )
        }
    }
}
