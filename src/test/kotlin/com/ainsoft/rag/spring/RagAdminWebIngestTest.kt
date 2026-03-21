package com.ainsoft.rag.spring

import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.api.SearchTelemetry
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockMultipartFile

class RagAdminWebIngestTest {
    private lateinit var server: HttpServer
    private lateinit var engine: RecordingEngine
    private lateinit var service: RagAdminService
    private val rootHtml = AtomicReference<String>()
    private val aboutHtml = AtomicReference<String>()
    private val contactHtml = AtomicReference<String>()

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
        service = RagAdminService(
            engine = engine,
            properties = RagProperties(),
            adminProperties = RagAdminProperties(),
            ragConfig = RagConfig(indexPath = Files.createTempDirectory("rag-web-ingest-test")),
            embeddingProvider = object : EmbeddingProvider {
                override val dimensions: Int = 1

                override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(0f) }
            }
        )

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            respondHtml(exchange, rootHtml.get())
        }
        server.createContext("/contact") { exchange ->
            respondHtml(exchange, contactHtml.get())
        }
        server.createContext("/about") { exchange ->
            respondHtml(exchange, aboutHtml.get())
        }
        server.createContext("/sitemap.xml") { exchange ->
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
            securityService = RagAdminSecurityService(RagAdminProperties(), service)
        )
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        val response = controller.webIngestStream(
            MockHttpServletRequest(),
            RagAdminWebIngestRequest(
                tenantId = "tenant web",
                urls = listOf(baseUrl),
                acl = listOf("group:admin"),
                allowedDomains = listOf("127.0.0.1"),
                maxPages = 5,
                maxDepth = 1,
                sameHostOnly = false
            )
        )

        val output = ByteArrayOutputStream()
        response.body!!.writeTo(output)
        val lines = output.toString(StandardCharsets.UTF_8).trim().lines()

        assertTrue(lines.any { it.contains("\"type\":\"progress\"") })
        assertTrue(lines.any { it.contains("\"type\":\"result\"") })
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
            securityService = RagAdminSecurityService(RagAdminProperties(), service)
        )

    private class RecordingEngine : RagEngine {
        val upserts = mutableListOf<UpsertDocumentRequest>()

        override fun upsert(request: UpsertDocumentRequest) {
            upserts += request
        }

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
