package com.ainsoft.rag.spring

import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.api.RagComponents
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.api.SearchTelemetry
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest

class RagAdminAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RagAdminAutoConfiguration::class.java))
        .withUserConfiguration(TestBeans::class.java)

    @Test
    fun `admin beans are registered by default`() {
        contextRunner.run { context ->
            assertTrue(context.containsBean("ragAdminUiController"))
            assertTrue(context.containsBean("ragAdminApiController"))
            assertTrue(context.containsBean("ragAdminExceptionHandler"))
        }
    }

    @Test
    fun `admin beans can be disabled`() {
        contextRunner
            .withPropertyValues("rag.admin.enabled=false")
            .run { context ->
                assertTrue(!context.containsBean("ragAdminUiController"))
                assertTrue(!context.containsBean("ragAdminApiController"))
                assertTrue(!context.containsBean("ragAdminExceptionHandler"))
            }
    }

    @Test
    fun `ui template reflects configured paths`() {
        contextRunner
            .withPropertyValues(
                "rag.admin.base-path=/console/rag",
                "rag.admin.api-base-path=/internal/rag-admin",
                "rag.admin.default-recent-provider-window-millis=12345"
            )
            .run { context ->
                val html = context.getBean(RagAdminUiController::class.java).index(MockHttpServletRequest())
                assertTrue(html.contains("\"basePath\":\"/console/rag\""))
                assertTrue(html.contains("\"apiBasePath\":\"/internal/rag-admin\""))
                assertTrue(html.contains("\"defaultRecentProviderWindowMillis\":12345"))
                assertTrue(html.contains("href=\"/console/rag/assets/app.css\""))
                assertTrue(html.contains("src=\"/console/rag/assets/app.js\""))
            }
    }

    @Test
    fun `additional admin pages are rendered`() {
        contextRunner.run { context ->
            val controller = context.getBean(RagAdminUiController::class.java)
            val html = controller.page(MockHttpServletRequest(), "documents")
            assertTrue(html.contains("Document Browser"))
        }
    }
}

@Configuration(proxyBeanMethods = false)
private class TestBeans {
    @Bean
    fun ragProperties(): RagProperties = RagProperties()

    @Bean
    fun ragConfig(): RagConfig = RagConfig(indexPath = Files.createTempDirectory("rag-admin-test"))

    @Bean
    fun embeddingProvider(): EmbeddingProvider = object : EmbeddingProvider {
        override val dimensions: Int = 1

        override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
    }

    @Bean
    fun ragComponents(): RagComponents = RagComponents()

    @Bean
    fun ragEngine(): RagEngine = object : RagEngine {
        override fun upsert(request: UpsertDocumentRequest) = Unit

        override fun deleteDocument(tenantId: String, docId: String): Long = 0L

        override fun deleteTenant(tenantId: String): Long = 0L

        override fun searchDetailed(request: SearchRequest): SearchResponse = SearchResponse(
            hits = emptyList(),
            telemetry = SearchTelemetry(
                executedQuery = request.query,
                originalQuery = request.query
            )
        )

        override fun snapshot(tag: String) = Unit

        override fun restore(tag: String) = Unit

        override fun stats(tenantId: String?): IndexStats = IndexStats(
            tenantId = tenantId,
            docs = 0L,
            chunks = 0L,
            lastCommitEpochMillis = null
        )

        override fun close() = Unit
    }
}
