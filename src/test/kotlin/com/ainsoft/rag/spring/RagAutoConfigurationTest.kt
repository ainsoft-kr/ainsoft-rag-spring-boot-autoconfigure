package com.ainsoft.rag.spring

import com.ainsoft.rag.api.IndexStats
import com.ainsoft.rag.cache.InMemoryStatsCacheStore
import com.ainsoft.rag.cache.file.JsonFileStatsCacheStore
import com.ainsoft.rag.chunking.RegexChunking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RagAutoConfigurationTest {
    @Test
    fun `chunker bean uses configured chunker type`() {
        val props = RagProperties(
            chunkerType = "regex",
            regexSplitPattern = "(?m)^#\\s+"
        )

        val chunker = RagAutoConfiguration().chunker(props)

        assertIs<RegexChunking>(chunker)
    }

    @Test
    fun `source load profiles override global settings`() {
        val props = RagProperties(
            sourceLoadTimeoutMillis = 1000,
            sourceLoadAllowHosts = listOf("global.example.com"),
            sourceLoadProfiles = mapOf(
                "internal" to SourceLoadProfile(
                    timeoutMillis = 2500,
                    allowHosts = listOf("intranet.example.com"),
                    insecureSkipTlsVerify = true,
                    customCaCertPath = "/tmp/internal-ca.pem"
                )
            )
        )

        val resolved = props.resolveSourceLoadProfile("internal")

        assertEquals(2500, resolved.timeoutMillis)
        assertEquals(listOf("intranet.example.com"), resolved.allowHosts)
        assertEquals(true, resolved.insecureSkipTlsVerify)
        assertEquals("/tmp/internal-ca.pem", resolved.customCaCertPath)
    }

    @Test
    fun `stats cache store can use file implementation`() {
        val dir = Files.createTempDirectory("rag-spring-file-cache")
        try {
            val props = RagProperties(
                indexPath = dir.resolve("index").toString(),
                statsCacheStoreType = "file",
                statsCacheFilePath = dir.resolve("stats-cache.json").toString()
            )

            val store = RagAutoConfiguration().statsCacheStore(props)

            assertIs<JsonFileStatsCacheStore<IndexStats>>(store)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stats cache file implementation honors cleanup options`() {
        val dir = Files.createTempDirectory("rag-spring-file-cache-cleanup")
        try {
            val cachePath = dir.resolve("stats-cache.json")
            Files.writeString(cachePath, "x".repeat(512))
            val props = RagProperties(
                indexPath = dir.resolve("index").toString(),
                statsCacheStoreType = "file",
                statsCacheFilePath = cachePath.toString(),
                statsCacheFileMaxBytes = 64,
                statsCacheFileRotateCount = 2,
                statsCacheFileCleanupOnStart = true
            )

            RagAutoConfiguration().statsCacheStore(props)

            assertEquals(true, Files.exists(dir.resolve("stats-cache.json.1")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stats cache store defaults to memory`() {
        val store = RagAutoConfiguration().statsCacheStore(RagProperties())
        assertIs<InMemoryStatsCacheStore<IndexStats>>(store)
    }
}
