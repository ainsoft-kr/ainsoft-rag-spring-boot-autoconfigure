package com.ainsoft.rag.spring

import com.ainsoft.rag.graph.GraphRagExtractionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LlmPropertiesTest {
    @Test
    fun `graph extraction preset selects default model for technical docs`() {
        val props = LlmProperties(
            providers = mapOf(
                "graph" to LlmProviderDefinition(
                    kind = "openai-compatible",
                    baseUrl = "https://example.com/v1",
                    apiKey = "test-key"
                )
            ),
            graphExtraction = LlmBindingProperties(
                provider = "graph",
                preset = "technical"
            )
        )

        val resolved = props.resolveGraphExtraction()

        assertEquals(GraphRagExtractionPreset.TECHNICAL, resolved.preset)
        assertNotNull(resolved.config)
        assertEquals("gpt-4o", resolved.config.model)
    }

    @Test
    fun `explicit graph extraction model overrides preset default`() {
        val props = LlmProperties(
            providers = mapOf(
                "graph" to LlmProviderDefinition(
                    kind = "openai-compatible",
                    baseUrl = "https://example.com/v1",
                    apiKey = "test-key",
                    model = "custom-model"
                )
            ),
            graphExtraction = LlmBindingProperties(
                provider = "graph",
                preset = "technical",
                model = "manual-model"
            )
        )

        val resolved = props.resolveGraphExtraction()

        assertEquals(GraphRagExtractionPreset.TECHNICAL, resolved.preset)
        assertNotNull(resolved.config)
        assertEquals("manual-model", resolved.config.model)
    }
}
