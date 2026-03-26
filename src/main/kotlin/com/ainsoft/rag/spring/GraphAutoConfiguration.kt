package com.ainsoft.rag.spring

import com.ainsoft.rag.graph.GraphProjectionService
import com.ainsoft.rag.graph.GraphRagExtractionStrategy
import com.ainsoft.rag.graph.GraphRagExtractionService
import com.ainsoft.rag.graph.LlmGraphRagExtractionService
import com.ainsoft.rag.graph.GraphStore
import com.ainsoft.rag.graph.InMemoryGraphStore
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.SearchPipeline
import com.ainsoft.rag.api.TextGenerationProviderFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [RagAutoConfiguration::class], before = [RagAdminAutoConfiguration::class])
class GraphAutoConfiguration {
    private val logger = LoggerFactory.getLogger(GraphAutoConfiguration::class.java)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun graphStore(props: RagProperties): GraphStore =
        when (props.graphProvider.trim().lowercase()) {
            "falkor", "falkordb", "graph" -> runCatching { FalkorGraphStore(props) }.getOrElse { error ->
                if (!props.graphFallbackToMemory) {
                    throw IllegalStateException(
                        "FalkorDB graph backend unavailable and rag.graphFallbackToMemory=false: " +
                            (error.message ?: error::class.java.simpleName),
                        error
                    )
                }
                logger.warn(
                    "FalkorDB graph backend unavailable, falling back to in-memory graph store: {}",
                    error.message ?: error::class.java.simpleName
                )
                InMemoryGraphStore()
            }
            else -> InMemoryGraphStore()
        }

    @Bean
    @ConditionalOnMissingBean
    fun graphRagExtractionStrategy(
        llm: LlmProperties,
        textGenerationProviderFactory: TextGenerationProviderFactory
    ): GraphRagExtractionStrategy {
        val resolved = llm.resolveGraphExtraction()
        return if (resolved.config != null) {
            runCatching {
                LlmGraphRagExtractionService(
                    provider = textGenerationProviderFactory.create(resolved.config),
                    preset = resolved.preset
                )
            }.getOrElse { error ->
                logger.warn(
                    "Graph extraction LLM unavailable for preset='{}', falling back to heuristic graph extractor: {}",
                    resolved.preset.name,
                    error.message ?: error::class.java.simpleName
                )
                GraphRagExtractionService().withPreset(resolved.preset)
            }
        } else {
            GraphRagExtractionService().withPreset(resolved.preset)
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun graphProjectionService(graphRagExtractionStrategy: GraphRagExtractionStrategy): GraphProjectionService =
        GraphProjectionService(graphRagExtractionStrategy)

    @Bean
    @ConditionalOnMissingBean(SearchPipeline::class)
    fun graphEnhancedSearchPipeline(
        graphStore: GraphStore,
        ragConfig: RagConfig
    ): SearchPipeline = GraphEnhancedSearchPipeline(graphStore, ragConfig.options.graphRetrieval)
}
