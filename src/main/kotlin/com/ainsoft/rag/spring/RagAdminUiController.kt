package com.ainsoft.rag.spring

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.util.StreamUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Controller
class RagAdminUiController(
    private val properties: RagAdminProperties,
    private val securityService: RagAdminSecurityService
) {
    private val objectMapper = jacksonObjectMapper()
    private val templateCache = ConcurrentHashMap<String, String>()
    private val pageFeatures = mapOf(
        "index" to "overview",
        "search" to "search",
        "text-ingest" to "text-ingest",
        "file-ingest" to "file-ingest",
        "web-ingest" to "web-ingest",
        "documents" to "documents",
        "tenants" to "tenants",
        "provider-history" to "provider-history",
        "search-audit" to "search-audit",
        "job-history" to "job-history",
        "access-security" to "access-security",
        "users" to "users",
        "config" to "config",
        "bulk-operations" to "bulk-operations",
        "graph" to "graph"
    )

    @ResponseBody
    @GetMapping(
        value = [
            "\${rag.admin.base-path:/rag-admin}",
            "\${rag.admin.base-path:/rag-admin}/",
            "\${rag.admin.base-path:/rag-admin}/index.html"
        ],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun index(request: HttpServletRequest): String = renderPage("index", request)

    @ResponseBody
    @GetMapping(
        value = [
            "\${rag.admin.base-path:/rag-admin}/{page}",
            "\${rag.admin.base-path:/rag-admin}/{page}.html"
        ],
        produces = [MediaType.TEXT_HTML_VALUE]
    )
    fun page(
        request: HttpServletRequest,
        @PathVariable page: String
    ): String {
        val resolvedPage = when (page.removeSuffix(".html")) {
            "overview" -> "index"
            else -> page.removeSuffix(".html")
        }
        if (resolvedPage == "login") {
            return renderLoginPage(request)
        }
        if (resolvedPage !in pageFeatures) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Admin page '$page' not found")
        }
        return renderPage(resolvedPage, request)
    }

    private fun renderPage(pageName: String, request: HttpServletRequest): String {
        val access = securityService.resolveAccess(request)
        securityService.requireFeature(request, pageFeatures.getValue(pageName))
        val assetBasePath = "${normalizePath(properties.basePath)}/assets"
        return loadTemplate(pageName)
            .replace("href=\"assets/", "href=\"$assetBasePath/")
            .replace("src=\"assets/", "src=\"$assetBasePath/")
            .replace("__RAG_ADMIN_CONFIG__", adminConfigJson(request))
    }

    private fun renderLoginPage(request: HttpServletRequest): String {
        val access = securityService.resolveAccess(request)
        val basePath = normalizePath(properties.basePath)
        val assetBasePath = "$basePath/assets"
        val loginAction = properties.resolvedLoginPath()
        val redirect = request.getParameter("redirect")?.takeIf { it.isNotBlank() } ?: basePath
        val notice = when {
            !properties.security.enabled -> """
              <div class="login-banner success">
                Security is disabled, so the admin UI is open without login.
              </div>
            """.trimIndent()
            access.authenticated -> """
              <div class="login-banner success">
                Signed in as <strong>${escapeHtml(access.username ?: "unknown")}</strong>
                with role <strong>${escapeHtml(access.role ?: "ANONYMOUS")}</strong>.
              </div>
            """.trimIndent()
            request.getParameter("error") != null -> """
              <div class="login-banner error">
                Invalid username or password.
              </div>
            """.trimIndent()
            request.getParameter("logout") != null -> """
              <div class="login-banner success">
                You have been signed out.
              </div>
            """.trimIndent()
            else -> ""
        }
        val userHint = when {
            !properties.security.enabled -> """
              <p class="login-note">
                Security is disabled, so no login configuration is required.
              </p>
            """.trimIndent()
            securityService.userCount() == 0 -> """
              <p class="login-note">
                Configure at least one user under <code>rag.admin.security.users</code>.
              </p>
            """.trimIndent()
            else -> """
              <p class="login-note">
                Available roles are controlled by <code>featureRoles</code>.
              </p>
            """.trimIndent()
        }

        return """
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Ainsoft RAG Admin Login</title>
    <link rel="stylesheet" href="$assetBasePath/app.css" />
    <style>
      body.login-page {
        display: grid;
        place-items: center;
        padding: 32px;
      }

      .login-shell {
        width: min(980px, 100%);
        display: grid;
        grid-template-columns: minmax(0, 1.1fr) minmax(320px, 420px);
        gap: 24px;
        align-items: stretch;
      }

      .login-hero,
      .login-card {
        border-radius: 28px;
        box-shadow: var(--shadow);
        overflow: hidden;
      }

      .login-hero {
        padding: 32px;
        color: #f8fbff;
        background:
          radial-gradient(circle at top right, rgba(34, 197, 94, 0.18), transparent 28%),
          radial-gradient(circle at bottom left, rgba(99, 102, 241, 0.22), transparent 30%),
          linear-gradient(135deg, #0f172a, #111827 56%, #172554);
        display: grid;
        gap: 22px;
      }

      .login-hero h1 {
        font-size: clamp(2.2rem, 4vw, 3.8rem);
        line-height: 1.04;
        letter-spacing: -0.05em;
      }

      .login-hero p {
        margin: 0;
        max-width: 54ch;
        color: rgba(226, 232, 240, 0.84);
        font-size: 1rem;
        line-height: 1.6;
      }

      .login-metrics {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 14px;
      }

      .login-metric {
        padding: 16px;
        border-radius: 18px;
        background: rgba(255, 255, 255, 0.08);
        border: 1px solid rgba(255, 255, 255, 0.08);
      }

      .login-metric span {
        display: block;
        color: rgba(226, 232, 240, 0.64);
        font-size: 0.82rem;
        margin-bottom: 6px;
      }

      .login-metric strong {
        font-size: 1.1rem;
      }

      .login-card {
        padding: 30px;
        background: rgba(255, 255, 255, 0.92);
        backdrop-filter: blur(18px);
        display: grid;
        align-content: center;
        gap: 18px;
      }

      .login-card h2 {
        font-size: 1.55rem;
        letter-spacing: -0.03em;
      }

      .login-form {
        display: grid;
        gap: 14px;
      }

      .login-form label {
        display: grid;
        gap: 8px;
        font-size: 0.92rem;
        color: var(--muted-strong);
      }

      .login-form input,
      .login-form button {
        border-radius: 16px;
        padding: 14px 16px;
        font: inherit;
      }

      .login-form input {
        border: 1px solid var(--line-strong);
        background: white;
        color: var(--text);
      }

      .login-form button {
        border: 0;
        background: linear-gradient(135deg, var(--accent), var(--accent-strong));
        color: white;
        font-weight: 800;
        cursor: pointer;
        box-shadow: 0 16px 28px rgba(55, 48, 163, 0.24);
      }

      .login-form button:hover {
        filter: brightness(1.04);
      }

      .login-banner {
        padding: 12px 14px;
        border-radius: 14px;
        font-size: 0.92rem;
        line-height: 1.5;
      }

      .login-banner.success {
        background: rgba(5, 150, 105, 0.12);
        color: #065f46;
      }

      .login-banner.error {
        background: rgba(220, 38, 38, 0.12);
        color: #991b1b;
      }

      .login-note {
        margin: 0;
        color: var(--muted);
        font-size: 0.9rem;
      }

      .login-footer {
        display: flex;
        justify-content: space-between;
        gap: 16px;
        flex-wrap: wrap;
        color: var(--muted);
        font-size: 0.88rem;
      }

      @media (max-width: 880px) {
        .login-shell {
          grid-template-columns: 1fr;
        }
      }
    </style>
  </head>
  <body class="login-page">
    <main class="login-shell">
      <section class="login-hero">
        <div class="brand">
          <span class="brand-mark">RA</span>
          <div>
            <div class="brand-title">RAG Admin</div>
            <p class="brand-copy">Operator console for retrieval, ingest, audit, and platform controls.</p>
          </div>
        </div>
        <div>
          <h1>Sign in to the admin workspace.</h1>
          <p>
            Session-based access keeps the browser flow simple. Once you sign in, the admin UI and API
            share the same authenticated session.
          </p>
        </div>
        <div class="login-metrics">
          <div class="login-metric">
            <span>Base Path</span>
            <strong>${escapeHtml(basePath)}</strong>
          </div>
          <div class="login-metric">
            <span>Security</span>
            <strong>${if (properties.security.enabled) "Enabled" else "Disabled"}</strong>
          </div>
          <div class="login-metric">
            <span>Current Role</span>
            <strong>${escapeHtml(access.role ?: "ANONYMOUS")}</strong>
          </div>
          <div class="login-metric">
            <span>Available Users</span>
            <strong>${securityService.userCount()}</strong>
          </div>
        </div>
      </section>

      <section class="login-card">
        <h2>Admin Login</h2>
        $notice
        $userHint
        <form class="login-form" method="post" action="$loginAction">
          <input type="hidden" name="redirect" value="${escapeHtml(redirect)}" />
          <label>
            Username
            <input name="username" autocomplete="username" spellcheck="false" />
          </label>
          <label>
            Password
            <input name="password" type="password" autocomplete="current-password" />
          </label>
          <button type="submit">Sign in</button>
        </form>
        <div class="login-footer">
          <span>Session login, no JWT required.</span>
          <span>Role policies are enforced after authentication.</span>
        </div>
      </section>
    </main>
  </body>
</html>
        """.trimIndent()
    }

    private fun loadTemplate(pageName: String): String =
        templateCache.computeIfAbsent(pageName) {
            ClassPathResource("META-INF/resources/rag-admin/$pageName.html")
                .inputStream
                .use { input -> StreamUtils.copyToString(input, StandardCharsets.UTF_8) }
        }

    private fun adminConfigJson(request: HttpServletRequest): String {
        val access = securityService.resolveAccess(request)
        return objectMapper.writeValueAsString(
            mapOf(
                "basePath" to normalizePath(properties.basePath),
                "apiBasePath" to normalizePath(properties.apiBasePath),
                "defaultTenantId" to properties.defaultTenantId,
                "defaultAclPrincipals" to properties.defaultAclPrincipals,
                "defaultSearchPrincipals" to properties.defaultSearchPrincipals,
                "defaultSearchQuery" to properties.defaultSearchQuery,
                "defaultRecentProviderWindowMillis" to properties.defaultRecentProviderWindowMillis,
                "securityEnabled" to properties.security.enabled,
                "authenticated" to access.authenticated,
                "currentUser" to access.username,
                "currentRole" to access.role,
                "currentRoles" to access.roles.sorted(),
                "allowedFeatures" to access.allowedFeatures.sorted(),
                "loginPath" to properties.resolvedLoginPath(),
                "logoutPath" to properties.resolvedLogoutPath()
            )
        )
    }

    private fun normalizePath(value: String): String {
        val normalized = if (value.startsWith("/")) value else "/$value"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
