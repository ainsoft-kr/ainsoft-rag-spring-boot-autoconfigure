package com.ainsoft.rag.spring

import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.embeddings.EmbeddingProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [RagAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(RagEngine::class)
@ConditionalOnProperty(prefix = "rag.admin", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagAdminProperties::class)
class RagAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminUiController(properties: RagAdminProperties): RagAdminUiController =
        RagAdminUiController(properties)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminApiController(
        engine: RagEngine,
        properties: RagProperties,
        ragConfig: RagConfig,
        embeddingProvider: EmbeddingProvider
    ): RagAdminApiController = RagAdminApiController(
        engine = engine,
        properties = properties,
        ragConfig = ragConfig,
        embeddingProvider = embeddingProvider
    )

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminExceptionHandler(): RagAdminExceptionHandler = RagAdminExceptionHandler()
}
