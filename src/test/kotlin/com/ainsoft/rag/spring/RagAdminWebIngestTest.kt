package com.ainsoft.rag.spring

import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchHit
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.api.SearchTelemetry
import com.ainsoft.rag.api.SourceRef
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.ainsoft.rag.graph.GraphProjectionService
import com.ainsoft.rag.graph.InMemoryGraphStore
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains
import org.hamcrest.Matchers.containsString
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RagAdminWebIngestTest {
    private lateinit var server: HttpServer
    private lateinit var engine: RecordingEngine
    private lateinit var service: RagAdminService
    private val rootHtml = AtomicReference<String>()
    private val aboutHtml = AtomicReference<String>()
    private val contactHtml = AtomicReference<String>()
    private val sitemapDelayMillis = AtomicLong(0L)

    @BeforeTest
    fun setUp() {
        engine = RecordingEngine()
        rootHtml.set(
            """
                <html>
                  <head><title>Home</title></head>
                  <body>
                    <main>Welcome to the root page.</main>
                    <a href="/about">About</a>
                    <a href="/contact">Contact</a>
                  </body>
                </html>
            """.trimIndent()
        )
        aboutHtml.set(
            """
                <html>
                  <head><title>About</title></head>
                  <body>
                    <main>About this site.</main>
                  </body>
                </html>
            """.trimIndent()
        )
        contactHtml.set(
            """
                <html>
                  <head><title>Contact</title></head>
                  <body>
                    <main>Contact page.</main>
                  </body>
                </html>
            """.trimIndent()
        )
        sitemapDelayMillis.set(0L)
        service = RagAdminService(
            engine = engine,
            properties = RagProperties(),
            adminProperties = RagAdminProperties(),
            ragConfig = RagConfig(indexPath = Files.createTempDirectory("rag-web-ingest-test")),
            embeddingProvider = object : EmbeddingProvider {
                override val dimensions: Int = 1

                override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
            },
            graphStore = InMemoryGraphStore(),
            graphProjectionService = GraphProjectionService()
        )

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            respondHtml(exchange, rootHtml.get())
        }
        server.createContext("/contact") { exchange ->
            respondHtml(exchange, contactHtml.get())
        }
        server.createContext("/about") { exchange ->
            respondHtml(exchange, aboutHtml.get())
        }
        server.createContext("/redirect-root") { exchange ->
            respondRedirect(exchange, "http://localhost:${server.address.port}/redirect-target")
        }
        server.createContext("/redirect-target") { exchange ->
            respondHtml(
                exchange,
                """
                    <html>
                      <head><title>Redirect Target</title></head>
                      <body>
                        <main>Redirect target page.</main>
                        <a href="http://localhost:${server.address.port}/redirect-child">Child</a>
                      </body>
                    </html>
                """.trimIndent()
            )
        }
        server.createContext("/redirect-child") { exchange ->
            respondHtml(
                exchange,
                """
                    <html>
                      <head><title>Redirect Child</title></head>
                      <body>
                        <main>Redirect child page.</main>
                      </body>
                    </html>
                """.trimIndent()
            )
        }
        server.createContext("/sitemap.xml") { exchange ->
            val delayMillis = sitemapDelayMillis.get()
            if (delayMillis > 0) {
                Thread.sleep(delayMillis)
            }
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>http://127.0.0.1:${server.address.port}/about</loc></url>
                </urlset>
            """.trimIndent()
            respondXml(exchange, body)
        }
        server.createContext("/robots.txt") { exchange ->
            val body = """
                User-agent: *
                Disallow: /contact
                Sitemap: http://127.0.0.1:${server.address.port}/sitemap.xml
            """.trimIndent()
            respondPlainText(exchange, body)
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `web multimodal helpers preserve image and table hints`() {
        val page = RagAdminWebPage(
            url = "https://example.com/spec",
            title = "Spec",
            description = "Spec page",
            text = "Spec page text",
            depth = 0,
            source = "seed",
            links = emptyList(),
            imageAltTexts = listOf("Pump layout"),
            tableSnippets = listOf("Checklist: item / owner")
        )

        val normalized = buildWebNormalizedText(page)
        val metadata = buildWebMetadata(emptyMap(), page)

        assertContains(normalized, "[image] Pump layout")
        assertContains(normalized, "[table] Checklist: item / owner")
        assertEquals("text,image,table", metadata["rag.modalities"])
        assertEquals("1", metadata["crawl.imageCount"])
        assertEquals("1", metadata["crawl.tableCount"])
    }

    @Test
    fun `web ingest crawls and ingests linked pages`() {
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        val response = service.webIngest(
            role = "ADMIN",
            request = RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                metadata = mapOf("surface" to "web"),
                allowedDomains = listOf("127.0.0.1"),
                maxPages = 5,
                maxDepth = 1,
                sameHostOnly = false,
                respectRobotsTxt = false
            )
        )

        assertEquals("ingested", response.status)
        assertEquals(3, response.ingestedPages)
        assertTrue(response.failures.isEmpty())
        assertEquals(3, engine.upserts.size)
        assertEquals("tenant-web", engine.upserts.first().tenantId)
        assertTrue(response.progress.first().phase == "sitemap")
        assertEquals("sitemap", response.results.first().source)
        assertTrue(engine.upserts.first().docId.startsWith("web-127.0.0.1-"))
        assertEquals("http://127.0.0.1:${server.address.port}/about", engine.upserts.first().sourceUri)
    }

    @Test
    fun `web ingest respects robots txt by default`() {
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        val response = service.webIngest(
            role = "ADMIN",
            request = RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                metadata = mapOf("surface" to "web"),
                allowedDomains = listOf("127.0.0.1"),
                maxPages = 5,
                maxDepth = 1,
                sameHostOnly = false
            )
        )

        assertEquals("partial", response.status)
        assertEquals(3, response.ingestedPages)
        assertTrue(response.failures.any { it.url.endsWith("/contact") })
        assertEquals(3, engine.upserts.size)
    }

    @Test
    fun `web ingest follows redirected host links when same host only is enabled`() {
        val baseUrl = "http://127.0.0.1:${server.address.port}/redirect-root"
        val response = service.webIngest(
            role = "ADMIN",
            request = RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                metadata = mapOf("surface" to "web"),
                maxPages = 5,
                maxDepth = 1,
                sameHostOnly = true,
                respectRobotsTxt = false
            )
        )

        assertEquals("ingested", response.status)
        assertEquals(3, response.ingestedPages)
        assertTrue(response.failures.isEmpty())
        val sourceUris = engine.upserts.mapNotNull { it.sourceUri }
        assertTrue(sourceUris.contains("http://localhost:${server.address.port}/redirect-target"))
        assertTrue(sourceUris.contains("http://localhost:${server.address.port}/redirect-child"))
        assertTrue(response.results.any { it.url == "http://localhost:${server.address.port}/redirect-child" })
    }

    @Test
    fun `search normalizes tenant ids after web ingest`() {
        val controller = newController()
        val requestContext = MockHttpServletRequest()
        val baseUrl = "http://127.0.0.1:${server.address.port}/"

        service.webIngest(
            role = "ADMIN",
            request = RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                metadata = mapOf("surface" to "web"),
                allowedDomains = listOf("127.0.0.1"),
                maxPages = 5,
                maxDepth = 1,
                sameHostOnly = false,
                respectRobotsTxt = false
            )
        )

        val response = controller.search(
            requestContext = requestContext,
            request = RagAdminSearchRequest(
                tenantId = "tenant web",
                principals = listOf("group:admin"),
                query = "about",
                topK = 5,
                searchNoMatchMinFinalConfidence = -1.0,
                searchNoMatchMinTopHitScore = -1.0
            )
        )

        assertEquals("tenant-web", response.tenantId)
        assertTrue(response.hits.isNotEmpty())
        assertTrue(response.hits.any { it.sourceUri?.contains("/about") == true })
    }

    @Test
    fun `web ingest skips unchanged pages on subsequent runs`() {
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        val request = RagAdminWebIngestRequest(
            tenantId = "tenant web",
            urls = listOf(baseUrl),
            acl = listOf("group:admin"),
            metadata = mapOf("surface" to "web"),
            allowedDomains = listOf("127.0.0.1"),
            maxPages = 5,
            maxDepth = 1,
            sameHostOnly = false,
            respectRobotsTxt = false,
            incrementalIngest = true
        )

        val first = service.webIngest(role = "ADMIN", request = request)
        rootHtml.set(
            """
                <html>
                  <head><title>Home Updated</title></head>
                  <body>
                    <main>Welcome to the root page.</main>
                    <a href="/about">About</a>
                    <a href="/contact">Contact</a>
                  </body>
                </html>
            """.trimIndent()
        )
        val second = service.webIngest(role = "ADMIN", request = request)

        assertEquals("ingested", first.status)
        assertEquals(3, first.ingestedPages)
        assertEquals("partial", second.status)
        assertEquals(0, second.ingestedPages)
        assertEquals(1, second.changedPages)
        assertEquals(2, second.skippedPages)
        val changed = second.results.first { it.status == "changed" && it.url == baseUrl }
        assertTrue(changed.previousPreview != null)
        assertTrue(changed.currentPreview != null)
        assertTrue(changed.changeSummary?.isNotBlank() == true)
        assertTrue(second.results.count { it.status == "skipped" } >= 2)
        assertEquals(4, engine.upserts.size)
    }

    @Test
    fun `web ingest continues when sitemap request times out`() {
        sitemapDelayMillis.set(250L)
        service = RagAdminService(
            engine = engine,
            properties = RagProperties(sourceLoadTimeoutMillis = 50L),
            adminProperties = RagAdminProperties(),
            ragConfig = RagConfig(indexPath = Files.createTempDirectory("rag-web-ingest-timeout-test")),
            embeddingProvider = object : EmbeddingProvider {
                override val dimensions: Int = 1

                override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
            },
            graphStore = InMemoryGraphStore(),
            graphProjectionService = GraphProjectionService()
        )
        val baseUrl = "http://127.0.0.1:${server.address.port}/"

        val response = service.webIngest(
            role = "ADMIN",
            request = RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                metadata = mapOf("surface" to "web"),
                allowedDomains = listOf("127.0.0.1"),
                maxPages = 5,
                maxDepth = 0,
                sameHostOnly = false,
                respectRobotsTxt = false,
                incrementalIngest = false
            )
        )

        assertEquals("ingested", response.status)
        assertEquals(1, response.ingestedPages)
        assertTrue(response.progress.any { it.phase == "sitemap" && it.message.contains("Failed to load sitemap.xml") })
        assertTrue(response.failures.isEmpty())
    }

    @Test
    fun `web ingest stream emits progress lines`() {
        val controller = RagAdminApiController(
            engine = engine,
            properties = RagProperties(),
            ragConfig = RagConfig(indexPath = Files.createTempDirectory("rag-web-ingest-controller-test")),
            embeddingProvider = object : EmbeddingProvider {
                override val dimensions: Int = 1

                override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
            },
            adminService = service,
            securityService = RagAdminSecurityService(RagAdminProperties(), service, InMemoryRagAdminAccountStore()),
            accountManagementService = RagAdminAccountManagementService(
                RagAdminProperties(),
                InMemoryRagAdminAccountStore(),
                service
            ),
            streamExecutor = java.util.concurrent.Executor(Runnable::run)
        )
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
        val requestBody =
            """
            {
              "tenantId": "tenant web",
              "urls": ["$baseUrl"],
              "acl": ["group:admin"],
              "allowedDomains": ["127.0.0.1"],
              "maxPages": 5,
              "maxDepth": 1,
              "sameHostOnly": false
            }
            """.trimIndent()

        val mvcResult = mockMvc.perform(
            post("/api/rag/admin/web-ingest/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(requestBody)
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:progress")))
            .andExpect(content().string(containsString("\"type\":\"result\"")))
    }

    @Test
    fun `text ingest skips unchanged content`() {
        val controller = newController()
        val request = MockHttpServletRequest()
        val ingestRequest = RagAdminIngestRequest(
            tenantId = "tenant text",
            docId = "doc-001",
            text = "hello world",
            acl = listOf("group:admin"),
            metadata = mapOf("surface" to "text"),
            incrementalIngest = true
        )

        val first = controller.ingest(request, ingestRequest)
        val second = controller.ingest(request, ingestRequest)
        val changed = controller.ingest(
            request,
            ingestRequest.copy(text = "hello changed world")
        )

        assertEquals("ingested", first.status)
        assertEquals("skipped", second.status)
        assertEquals("changed", changed.status)
        assertEquals(2, engine.upserts.size)
    }

    @Test
    fun `file ingest skips unchanged content`() {
        val controller = newController()
        val request = MockHttpServletRequest()
        val file = MockMultipartFile(
            "file",
            "note.txt",
            "text/plain",
            "file content".toByteArray(StandardCharsets.UTF_8)
        )

        val first = controller.ingestFile(
            requestContext = request,
            tenantId = "tenant file",
            docId = "file-001",
            acl = listOf("group:admin"),
            file = file,
            sourceUri = null,
            page = null,
            charset = "UTF-8",
            metadata = "surface=file",
            incrementalIngest = true
        )
        val second = controller.ingestFile(
            requestContext = request,
            tenantId = "tenant file",
            docId = "file-001",
            acl = listOf("group:admin"),
            file = file,
            sourceUri = null,
            page = null,
            charset = "UTF-8",
            metadata = "surface=file",
            incrementalIngest = true
        )

        assertEquals("ingested", first.status)
        assertEquals("skipped", second.status)
        assertEquals(1, engine.upserts.size)
    }

    @Test
    fun `bulk text ingest skips unchanged documents`() {
        val request = RagAdminBulkTextIngestRequest(
            tenantId = "tenant bulk",
            documents = listOf(
                RagAdminBulkTextDocument(
                    docId = "bulk-001",
                    text = "bulk document one",
                    acl = listOf("group:admin"),
                    metadata = mapOf("surface" to "bulk")
                ),
                RagAdminBulkTextDocument(
                    docId = "bulk-002",
                    text = "bulk document two",
                    acl = listOf("group:admin"),
                    metadata = mapOf("surface" to "bulk")
                )
            ),
            incrementalIngest = true
        )

        val first = service.bulkTextIngest(request)
        val second = service.bulkTextIngest(request)

        assertEquals(2, first.successCount)
        assertEquals(2, second.successCount)
        assertTrue(second.results.all { it.message.contains("already ingested") || it.message.contains("skipped", ignoreCase = true) })
        assertEquals(2, engine.upserts.size)
    }

    private fun respondHtml(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondXml(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/xml; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondPlainText(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondRedirect(exchange: com.sun.net.httpserver.HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(301, -1)
        exchange.close()
    }

    private fun newController(): RagAdminApiController =
        RagAdminApiController(
            engine = engine,
            properties = RagProperties(),
            ragConfig = RagConfig(indexPath = Files.createTempDirectory("rag-ingest-controller-test")),
            embeddingProvider = object : EmbeddingProvider {
                override val dimensions: Int = 1

                override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
            },
            adminService = service,
            securityService = RagAdminSecurityService(RagAdminProperties(), service, InMemoryRagAdminAccountStore()),
            accountManagementService = RagAdminAccountManagementService(
                RagAdminProperties(),
                InMemoryRagAdminAccountStore(),
                service
            ),
            streamExecutor = java.util.concurrent.Executor(Runnable::run)
        )

    private class RecordingEngine : RagEngine {
        val upserts = mutableListOf<UpsertDocumentRequest>()

        override fun upsert(request: UpsertDocumentRequest) {
            upserts += request
        }

        override fun deleteDocument(tenantId: String, docId: String): Long = 0L

        override fun deleteTenant(tenantId: String): Long = 0L

        override fun searchDetailed(request: SearchRequest): SearchResponse {
            val normalizedQuery = request.query.trim().lowercase()
            val hits = upserts
                .asReversed()
                .filter { doc -> doc.tenantId == request.tenantId }
                .filter { doc -> doc.acl.allow.any { it in request.principals } }
                .filter { doc ->
                    request.filter.all { (key, value) -> doc.metadata[key] == value }
                }
                .filter { doc ->
                    normalizedQuery.isBlank() || doc.normalizedText.lowercase().contains(normalizedQuery)
                }
                .take(request.topK)
                .map { doc ->
                    SearchHit(
                        source = SourceRef(
                            docId = doc.docId,
                            chunkId = "${doc.docId}-chunk-1",
                            sourceUri = doc.sourceUri,
                            page = doc.page
                        ),
                        score = 1.0,
                        text = doc.normalizedText,
                        metadata = doc.metadata
                    )
                }
            return SearchResponse(
                hits = hits,
                telemetry = SearchTelemetry(
                    executedQuery = request.query,
                    originalQuery = request.query,
                    finalConfidence = if (hits.isEmpty()) 0.0 else 1.0
                )
            )
        }

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
