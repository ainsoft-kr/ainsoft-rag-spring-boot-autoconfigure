package com.ainsoft.rag.spring

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import com.ainsoft.rag.support.SourceLoadOptions
import com.ainsoft.rag.support.SourceLoaders
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.Duration
import java.util.ArrayDeque

data class RagAdminWebIngestResponse(
    val status: String,
    val tenantId: String,
    val urls: List<String>,
    val crawledPages: Int,
    val ingestedPages: Int,
    val changedPages: Int = 0,
    val skippedPages: Int = 0,
    val results: List<RagAdminWebIngestPageResponse> = emptyList(),
    val progress: List<RagAdminWebIngestProgressEvent> = emptyList(),
    val failures: List<RagAdminWebIngestFailure> = emptyList()
)

data class RagAdminWebIngestPageResponse(
    val url: String,
    val docId: String,
    val title: String?,
    val depth: Int,
    val source: String,
    val status: String,
    val message: String? = null,
    val previousPreview: String? = null,
    val currentPreview: String? = null,
    val changeSummary: String? = null
)

data class RagAdminWebIngestProgressEvent(
    val phase: String,
    val message: String,
    val url: String? = null,
    val depth: Int? = null,
    val current: Int? = null,
    val total: Int? = null
)

data class RagAdminWebIngestFailure(
    val url: String,
    val depth: Int,
    val message: String
)

class WebIngestCancelledException : RuntimeException("web ingest cancelled")

data class RagAdminWebPage(
    val url: String,
    val title: String?,
    val description: String?,
    val text: String,
    val depth: Int,
    val source: String,
    val links: List<String>
)

data class RagAdminWebCrawlResult(
    val pages: List<RagAdminWebPage>,
    val progress: List<RagAdminWebIngestProgressEvent>,
    val failures: List<RagAdminWebIngestFailure>
)

private data class RobotsRule(
    val userAgents: List<String>,
    val allows: List<String>,
    val disallows: List<String>,
    val sitemaps: List<String>
)

private data class RobotsPolicy(
    val rules: List<RobotsRule>,
    val sitemaps: List<String>
) {
    fun allows(url: String, userAgent: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = robotsPath(uri)
        val matchingRules = rules.filter { rule ->
            rule.userAgents.any { agentMatches(userAgent, it) }
        }
        if (matchingRules.isEmpty()) {
            return true
        }

        var decision: RobotsDecision? = null
        matchingRules
            .flatMap { rule ->
                rule.allows.map { RobotsDecision(pattern = it, allow = true) } +
                    rule.disallows.map { RobotsDecision(pattern = it, allow = false) }
            }
            .mapNotNull { candidate ->
                val pattern = normalizeRobotsPattern(candidate.pattern)
                if (pattern.isBlank() || !path.startsWith(pattern)) null else candidate.copy(pattern = pattern)
            }
            .forEach { candidate ->
                val current = decision
                decision = when {
                    current == null -> candidate
                    candidate.pattern.length > current.pattern.length -> candidate
                    candidate.pattern.length < current.pattern.length -> current
                    candidate.allow && !current.allow -> candidate
                    else -> current
                }
            }

        return decision?.allow ?: true
    }

    companion object {
        fun empty(): RobotsPolicy = RobotsPolicy(emptyList(), emptyList())
    }
}

private data class RobotsDecision(
    val pattern: String,
    val allow: Boolean
)

class RagAdminWebCrawler {
    fun crawl(
        seedUrls: List<String>,
        allowedDomains: List<String>,
        maxPages: Int,
        maxDepth: Int,
        sameHostOnly: Boolean,
        charset: Charset,
        timeout: Duration,
        configuredAllowedHosts: Set<String>,
        authHeaders: Map<String, String> = emptyMap(),
        insecureSkipTlsVerify: Boolean = false,
        customCaCertPath: String? = null,
        respectRobotsTxt: Boolean = true,
        userAgent: String = "AinsoftRagBot/1.0",
        progressSink: ((RagAdminWebIngestProgressEvent) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): RagAdminWebCrawlResult {
        require(seedUrls.isNotEmpty()) { "urls must not be empty" }
        require(maxPages > 0) { "maxPages must be greater than zero" }
        require(maxDepth >= 0) { "maxDepth must not be negative" }

        val normalizedSeeds = seedUrls.map(::normalizeWebUrl)
        val seedHosts = normalizedSeeds.mapNotNull { URI(it).host?.lowercase() }.toSet()
        val normalizedAllowedDomains = allowedDomains.mapNotNull(::normalizeDomainEntry).toSet()
        val normalizedConfiguredAllowedHosts = configuredAllowedHosts.map { it.lowercase() }.toSet()
        val progress = mutableListOf<RagAdminWebIngestProgressEvent>()
        val failures = mutableListOf<RagAdminWebIngestFailure>()
        val robotsCache = mutableMapOf<String, RobotsPolicy>()

        fun emit(
            phase: String,
            message: String,
            url: String? = null,
            depth: Int? = null,
            current: Int? = null,
            total: Int? = null
        ) {
            val event = RagAdminWebIngestProgressEvent(
                phase = phase,
                message = message,
                url = url,
                depth = depth,
                current = current,
                total = total
            )
            progress += event
            progressSink?.invoke(event)
        }

        fun ensureNotCancelled() {
            if (isCancelled()) {
                throw WebIngestCancelledException()
            }
        }

        fun policyFor(url: String): RobotsPolicy {
            val host = URI(url).host?.lowercase() ?: return RobotsPolicy.empty()
            return robotsCache.getOrPut(host) {
                loadRobotsPolicy(
                    seedUrl = url,
                    charset = charset,
                    timeout = timeout,
                    authHeaders = authHeaders,
                    insecureSkipTlsVerify = insecureSkipTlsVerify,
                    customCaCertPath = customCaCertPath,
                    userAgent = userAgent,
                    emit = ::emit
                )
            }
        }

        val sitemapUrls = discoverSitemapUrls(
            seedUrls = normalizedSeeds,
            charset = charset,
            timeout = timeout,
            authHeaders = authHeaders,
            insecureSkipTlsVerify = insecureSkipTlsVerify,
            customCaCertPath = customCaCertPath,
            allowedHosts = normalizedConfiguredAllowedHosts,
            allowedDomains = normalizedAllowedDomains,
            sameHostOnly = sameHostOnly,
            seedHosts = seedHosts,
            respectRobotsTxt = respectRobotsTxt,
            userAgent = userAgent,
            robotsCache = robotsCache,
            ensureNotCancelled = ::ensureNotCancelled,
            emit = ::emit
        )

        val queue = ArrayDeque<WebQueueEntry>()
        val scheduled = linkedSetOf<String>()

        sitemapUrls.forEach { url ->
            queue.add(WebQueueEntry(url = url, depth = 0, source = "sitemap"))
            scheduled += url
        }
        normalizedSeeds.forEach { url ->
            if (scheduled.add(url)) {
                queue.add(WebQueueEntry(url = url, depth = 0, source = "seed"))
            }
        }

        val pages = mutableListOf<RagAdminWebPage>()
        var current = 0
        while (queue.isNotEmpty() && pages.size < maxPages) {
            ensureNotCancelled()
            val entry = queue.removeFirst()
            if (pages.any { it.url == entry.url }) {
                continue
            }
            val policy = if (respectRobotsTxt) policyFor(entry.url) else RobotsPolicy.empty()
            if (respectRobotsTxt && !policy.allows(entry.url, userAgent)) {
                failures += RagAdminWebIngestFailure(
                    url = entry.url,
                    depth = entry.depth,
                    message = "blocked by robots.txt"
                )
                emit(
                    phase = "skip",
                    message = "Blocked by robots.txt",
                    url = entry.url,
                    depth = entry.depth,
                    current = current,
                    total = sitemapUrls.size + normalizedSeeds.size
                )
                continue
            }
            if (!isAllowedUrl(
                    url = entry.url,
                    seedHosts = seedHosts,
                    allowedDomains = normalizedAllowedDomains,
                    configuredAllowedHosts = normalizedConfiguredAllowedHosts,
                    sameHostOnly = sameHostOnly
                )
            ) {
                failures += RagAdminWebIngestFailure(
                    url = entry.url,
                    depth = entry.depth,
                    message = "url is not allowed by crawl policy"
                )
                emit(
                    phase = "skip",
                    message = "Skipped disallowed url",
                    url = entry.url,
                    depth = entry.depth,
                    current = current,
                    total = sitemapUrls.size + normalizedSeeds.size
                )
                continue
            }

            ensureNotCancelled()
            val html = loadHtml(
                url = entry.url,
                charset = charset,
                timeout = timeout,
                authHeaders = authHeaders,
                insecureSkipTlsVerify = insecureSkipTlsVerify,
                customCaCertPath = customCaCertPath,
                userAgent = userAgent
            )
            if (html.isNullOrBlank()) {
                failures += RagAdminWebIngestFailure(
                    url = entry.url,
                    depth = entry.depth,
                    message = "failed to load content"
                )
                emit(
                    phase = "fetch",
                    message = "Failed to load content",
                    url = entry.url,
                    depth = entry.depth,
                    current = current,
                    total = sitemapUrls.size + normalizedSeeds.size
                )
                continue
            }

            ensureNotCancelled()
            val document = Ksoup.parse(html = html, baseUri = entry.url)
            document.select("script,style,noscript").remove()
            val title = document.title().trim().takeIf { it.isNotBlank() }
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            val bodyText = buildString {
                if (title != null) {
                    appendLine(title)
                    appendLine()
                }
                if (description != null) {
                    appendLine(description)
                    appendLine()
                }
                append(document.body().text().trim())
            }.trim()
            val links = if (entry.depth < maxDepth) {
                document.select("a[href]")
                    .mapNotNull { element ->
                        val href = element.absUrl("href").trim()
                        if (!href.startsWith("http://") && !href.startsWith("https://")) {
                            return@mapNotNull null
                        }
                        runCatching { normalizeWebUrl(href) }
                            .getOrNull()
                            ?.takeIf { candidate ->
                                isAllowedUrl(
                                    url = candidate,
                                    seedHosts = seedHosts,
                                    allowedDomains = normalizedAllowedDomains,
                                    configuredAllowedHosts = normalizedConfiguredAllowedHosts,
                                    sameHostOnly = sameHostOnly
                                )
                            }
                    }
                    .distinct()
            } else {
                emptyList()
            }

            pages += RagAdminWebPage(
                url = entry.url,
                title = title,
                description = description,
                text = bodyText.ifBlank { document.text().trim() },
                depth = entry.depth,
                source = entry.source,
                links = links
            )
            current += 1
            emit(
                phase = "crawl",
                message = "Fetched page ${pages.size} of at most $maxPages",
                url = entry.url,
                depth = entry.depth,
                current = current,
                total = sitemapUrls.size + normalizedSeeds.size
            )

            links.forEach { link ->
                if (link !in scheduled && pages.size + queue.size < maxPages) {
                    queue.add(WebQueueEntry(url = link, depth = entry.depth + 1, source = "link"))
                    scheduled += link
                }
            }
        }

        emit(
            phase = "done",
            message = "crawl completed with ${pages.size} pages",
            current = pages.size,
            total = sitemapUrls.size + normalizedSeeds.size
        )

        return RagAdminWebCrawlResult(
            pages = pages,
            progress = progress,
            failures = failures
        )
    }

    private fun discoverSitemapUrls(
        seedUrls: List<String>,
        charset: Charset,
        timeout: Duration,
        authHeaders: Map<String, String>,
        insecureSkipTlsVerify: Boolean,
        customCaCertPath: String?,
        allowedHosts: Set<String>,
        allowedDomains: Set<String>,
        sameHostOnly: Boolean,
        seedHosts: Set<String>,
        respectRobotsTxt: Boolean,
        userAgent: String,
        robotsCache: MutableMap<String, RobotsPolicy>,
        ensureNotCancelled: () -> Unit,
        emit: (String, String, String?, Int?, Int?, Int?) -> Unit
    ): List<String> {
        val discovered = linkedSetOf<String>()
        seedUrls.map(::sitemapUrlFor).distinct().forEachIndexed { index, sitemapUrl ->
            ensureNotCancelled()
            if (!isAllowedUrl(
                    url = sitemapUrl,
                    seedHosts = seedHosts,
                    allowedDomains = allowedDomains,
                    configuredAllowedHosts = allowedHosts,
                    sameHostOnly = sameHostOnly
                )
            ) {
                emit("sitemap", "Skipped disallowed sitemap.xml", sitemapUrl, null, index + 1, seedUrls.size)
                return@forEachIndexed
            }

            if (respectRobotsTxt) {
                val robots = robotsCache.getOrPut(URI(sitemapUrl).host!!.lowercase()) {
                    loadRobotsPolicy(
                        seedUrl = sitemapUrl,
                        charset = charset,
                        timeout = timeout,
                        authHeaders = authHeaders,
                        insecureSkipTlsVerify = insecureSkipTlsVerify,
                        customCaCertPath = customCaCertPath,
                        userAgent = userAgent,
                        emit = emit
                    )
                }
                robots.sitemaps.forEach { discovered += normalizeWebUrl(it) }
            }

            val xml = loadHtml(
                url = sitemapUrl,
                charset = charset,
                timeout = timeout,
                authHeaders = authHeaders,
                insecureSkipTlsVerify = insecureSkipTlsVerify,
                customCaCertPath = customCaCertPath,
                userAgent = userAgent
            )
            if (xml.isNullOrBlank()) {
                emit("sitemap", "No sitemap.xml found", sitemapUrl, null, index + 1, seedUrls.size)
                return@forEachIndexed
            }

            val sitemapDoc = Ksoup.parse(html = xml, parser = Parser.xmlParser(), baseUri = sitemapUrl)
            val sitemapLocs = sitemapDoc.select("loc")
                .mapNotNull { loc -> loc.text().trim().takeIf { it.isNotBlank() } }
                .mapNotNull { loc ->
                    runCatching { normalizeWebUrl(loc) }
                        .getOrNull()
                        ?.takeIf { candidate ->
                            isAllowedUrl(
                                url = candidate,
                                seedHosts = seedHosts,
                                allowedDomains = allowedDomains,
                                configuredAllowedHosts = allowedHosts,
                                sameHostOnly = sameHostOnly
                            )
                        }
                }

            discovered += sitemapLocs
            emit("sitemap", "Loaded ${sitemapLocs.size} urls from sitemap.xml", sitemapUrl, null, index + 1, seedUrls.size)
        }
        return discovered.toList()
    }

    private fun loadHtml(
        url: String,
        charset: Charset,
        timeout: Duration,
        authHeaders: Map<String, String>,
        insecureSkipTlsVerify: Boolean,
        customCaCertPath: String?,
        userAgent: String
    ): String? = SourceLoaders.loadTextFromUri(
        uriValue = url,
        charset = charset,
        options = SourceLoadOptions(
            authHeaders = authHeaders.withUserAgent(userAgent),
            timeout = timeout,
            allowedHosts = emptySet(),
            insecureSkipTlsVerify = insecureSkipTlsVerify,
            customCaCertPath = customCaCertPath
        )
    )

    private fun loadRobotsPolicy(
        seedUrl: String,
        charset: Charset,
        timeout: Duration,
        authHeaders: Map<String, String>,
        insecureSkipTlsVerify: Boolean,
        customCaCertPath: String?,
        userAgent: String,
        emit: (String, String, String?, Int?, Int?, Int?) -> Unit
    ): RobotsPolicy {
        val robotsUrl = robotsUrlFor(seedUrl)
        val text = SourceLoaders.loadTextFromUri(
            uriValue = robotsUrl,
            charset = charset,
            options = SourceLoadOptions(
                authHeaders = authHeaders.withUserAgent(userAgent),
                timeout = timeout,
                allowedHosts = emptySet(),
                insecureSkipTlsVerify = insecureSkipTlsVerify,
                customCaCertPath = customCaCertPath
            )
        ) ?: loadDirectText(
            url = robotsUrl,
            charset = charset,
            timeout = timeout,
            authHeaders = authHeaders.withUserAgent(userAgent)
        )
        if (text.isNullOrBlank()) {
            emit("robots", "No robots.txt found", robotsUrl, null, null, null)
            return RobotsPolicy.empty()
        }

        val policy = parseRobotsTxt(text)
        emit(
            "robots",
            "Loaded robots.txt with ${policy.rules.size} rule groups",
            robotsUrl,
            null,
            null,
            null
        )
        return policy
    }
}

fun normalizeWebUrl(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotBlank()) { "url must not be blank" }
    val uri = URI(trimmed)
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "url must use http or https"
    }
    val host = uri.host?.trim()?.lowercase().orEmpty()
    require(host.isNotBlank()) { "url must include a host" }
    val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
    val scheme = uri.scheme?.lowercase().orEmpty()
    return URI(
        scheme,
        uri.userInfo,
        host,
        uri.port,
        path,
        uri.query,
        null
    ).toString()
}

fun normalizeDomainEntry(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val cleaned = trimmed.lowercase()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("*.")
        .substringBefore("/")
        .substringBefore(":")
        .lowercase()
    if (cleaned.isBlank()) return null
    return cleaned
}

fun webDocIdFromUrl(url: String): String {
    val uri = URI(url)
    val hostPart = (uri.host?.lowercase() ?: "web").replace(Regex("[^A-Za-z0-9._-]"), "-")
    val pathPart = (uri.path?.trim('/')?.replace(Regex("[^A-Za-z0-9._-]+"), "-") ?: "")
        .takeIf { it.isNotBlank() }
        ?: "root"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "web-$hostPart-${pathPart.take(40)}-$digest"
}

fun buildWebNormalizedText(page: RagAdminWebPage): String = page.text.trim()

fun webContentHash(page: RagAdminWebPage): String {
    val payload = webPreviewText(page.title, page.description, page.text)
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun webPreviewText(title: String?, description: String?, text: String, maxChars: Int = 320): String {
    val payload = buildString {
        title?.trim()?.takeIf { it.isNotBlank() }?.let {
            appendLine(it)
            appendLine()
        }
        description?.trim()?.takeIf { it.isNotBlank() }?.let {
            appendLine(it)
            appendLine()
        }
        append(text.trim())
    }.trim()
    return payload.replace(Regex("\\s+"), " ").take(maxChars)
}

fun buildWebMetadata(baseMetadata: Map<String, String>, page: RagAdminWebPage): Map<String, String> {
    val webMetadata = linkedMapOf(
        "crawl.source" to page.source,
        "crawl.url" to page.url,
        "crawl.depth" to page.depth.toString()
    )
    page.title?.takeIf { it.isNotBlank() }?.let { webMetadata["crawl.title"] = it }
    page.description?.takeIf { it.isNotBlank() }?.let { webMetadata["crawl.description"] = it }
    return baseMetadata + webMetadata
}

fun isAllowedUrl(
    url: String,
    seedHosts: Set<String>,
    allowedDomains: Set<String>,
    configuredAllowedHosts: Set<String>,
    sameHostOnly: Boolean
): Boolean {
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
    if (sameHostOnly && host !in seedHosts) {
        return false
    }
    if (configuredAllowedHosts.isNotEmpty() && host !in configuredAllowedHosts) {
        return false
    }
    if (allowedDomains.isNotEmpty() && allowedDomains.none { allowedDomain ->
            host == allowedDomain || host.endsWith(".$allowedDomain")
        }
    ) {
        return false
    }
    return true
}

private fun sitemapUrlFor(seedUrl: String): String {
    val uri = URI(seedUrl)
    return URI(
        uri.scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        "/sitemap.xml",
        null,
        null
    ).toString()
}

private fun robotsUrlFor(seedUrl: String): String {
    val uri = URI(seedUrl)
    return URI(
        uri.scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        "/robots.txt",
        null,
        null
    ).toString()
}

private fun parseRobotsTxt(text: String): RobotsPolicy {
    val blocks = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.substringBefore('#').trim()
        if (line.isBlank()) {
            if (current.isNotEmpty()) {
                blocks += current
                current = mutableListOf()
            }
            return@forEach
        }
        current += line
    }
    if (current.isNotEmpty()) {
        blocks += current
    }

    val rules = mutableListOf<RobotsRule>()
    val sitemaps = linkedSetOf<String>()
    blocks.forEach { block ->
        val userAgents = mutableListOf<String>()
        val allows = mutableListOf<String>()
        val disallows = mutableListOf<String>()
        val blockSitemaps = mutableListOf<String>()
        block.forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "user-agent" -> if (value.isNotBlank()) userAgents += value
                "allow" -> if (value.isNotBlank()) allows += value
                "disallow" -> if (value.isNotBlank()) disallows += value
                "sitemap" -> if (value.isNotBlank()) blockSitemaps += value
            }
        }
        if (userAgents.isNotEmpty() && (allows.isNotEmpty() || disallows.isNotEmpty())) {
            rules += RobotsRule(
                userAgents = userAgents.map { it.trim() },
                allows = allows.map(::normalizeRobotsPattern),
                disallows = disallows.map(::normalizeRobotsPattern),
                sitemaps = blockSitemaps.map { it.trim() }
            )
        }
        blockSitemaps.forEach { sitemaps += it }
    }

    return RobotsPolicy(rules = rules, sitemaps = sitemaps.toList())
}

private fun loadDirectText(
    url: String,
    charset: Charset,
    timeout: Duration,
    authHeaders: Map<String, String>
): String? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
    return try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = timeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = timeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        authHeaders.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            return null
        }
        connection.inputStream.use { inputStream ->
            InputStreamReader(inputStream, charset).use { reader ->
                reader.readText()
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun normalizeRobotsPattern(pattern: String): String {
    val trimmed = pattern.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}

private fun robotsPath(uri: URI): String {
    val query = uri.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return (uri.path.takeIf { it.isNotBlank() } ?: "/") + query
}

private fun agentMatches(userAgent: String, token: String): Boolean {
    val normalizedUserAgent = userAgent.lowercase()
    val normalizedToken = token.lowercase()
    return normalizedToken == "*" || normalizedUserAgent.contains(normalizedToken)
}

private fun Map<String, String>.withUserAgent(userAgent: String): Map<String, String> {
    if (userAgent.isBlank()) return this
    val merged = LinkedHashMap(this)
    merged["User-Agent"] = userAgent
    return merged
}

private data class WebQueueEntry(
    val url: String,
    val depth: Int,
    val source: String
)
