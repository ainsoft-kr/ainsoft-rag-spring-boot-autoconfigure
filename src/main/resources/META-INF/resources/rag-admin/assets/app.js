(function () {
  const config = window.RAG_ADMIN_CONFIG || {
    basePath: "/rag-admin",
    apiBasePath: "/api/rag/admin",
    defaultRecentProviderWindowMillis: 60000,
    securityEnabled: false,
    currentRole: "ADMIN",
    allowedFeatures: [],
    tokenHeaderName: "X-Rag-Admin-Token",
    tokenQueryParameter: "access_token"
  };

  const routes = [
    { route: "overview", feature: "overview", label: "Overview" },
    { route: "search", feature: "search", label: "Search" },
    { route: "text-ingest", feature: "text-ingest", label: "Text Ingest" },
    { route: "file-ingest", feature: "file-ingest", label: "File Ingest" },
    { route: "documents", feature: "documents", label: "Documents" },
    { route: "tenants", feature: "tenants", label: "Tenants & Index Ops" },
    { route: "provider-history", feature: "provider-history", label: "Provider History" },
    { route: "search-audit", feature: "search-audit", label: "Search Audit" },
    { route: "job-history", feature: "job-history", label: "Job History" },
    { route: "access-security", feature: "access-security", label: "Access & Security" },
    { route: "config", feature: "config", label: "Config" },
    { route: "bulk-operations", feature: "bulk-operations", label: "Bulk Ops" }
  ];
  const storageKey = "rag-admin-shared-context";

  function parseList(value) {
    return String(value || "")
      .split(/\n|,/)
      .map((entry) => entry.trim())
      .filter(Boolean);
  }

  function parseMap(value) {
    return String(value || "")
      .split(/\n|,/)
      .map((entry) => entry.trim())
      .filter(Boolean)
      .reduce((acc, entry) => {
        const idx = entry.indexOf("=");
        if (idx > 0 && idx < entry.length - 1) {
          acc[entry.slice(0, idx).trim()] = entry.slice(idx + 1).trim();
        }
        return acc;
      }, {});
  }

  function formatMap(value) {
    return Object.entries(value || {})
      .map(([key, entryValue]) => `${key}=${entryValue}`)
      .join("\n");
  }

  function parseJsonInput(id, fallback) {
    const value = document.getElementById(id)?.value?.trim();
    if (!value) {
      return fallback;
    }
    return JSON.parse(value);
  }

  function queryToken() {
    return new URLSearchParams(window.location.search).get(config.tokenQueryParameter) || "";
  }

  function defaultContext() {
    return {
      tenantId: "tenant-admin",
      recentProviderWindowMillis: config.defaultRecentProviderWindowMillis,
      accessToken: queryToken()
    };
  }

  function normalizeContext(input) {
    const token = typeof input.accessToken === "string" && input.accessToken.trim()
      ? input.accessToken.trim()
      : queryToken();
    return {
      tenantId: typeof input.tenantId === "string" && input.tenantId.trim()
        ? input.tenantId.trim()
        : "tenant-admin",
      recentProviderWindowMillis: Number(
        input.recentProviderWindowMillis || config.defaultRecentProviderWindowMillis
      ),
      accessToken: token
    };
  }

  function loadContext() {
    try {
      const raw = window.localStorage.getItem(storageKey);
      if (!raw) {
        return defaultContext();
      }
      return normalizeContext(JSON.parse(raw));
    } catch (_error) {
      return defaultContext();
    }
  }

  function saveContext(context) {
    const normalized = normalizeContext(context);
    window.localStorage.setItem(storageKey, JSON.stringify(normalized));
    return normalized;
  }

  function contextInput(name) {
    return document.querySelector(`[data-context="${name}"]`);
  }

  function readContext() {
    const existing = loadContext();
    return saveContext({
      tenantId: contextInput("tenantId")?.value || existing.tenantId,
      recentProviderWindowMillis:
        contextInput("recentProviderWindowMillis")?.value || existing.recentProviderWindowMillis,
      accessToken: contextInput("accessToken")?.value || existing.accessToken
    });
  }

  function bindContext() {
    const context = loadContext();
    document.querySelectorAll("[data-context]").forEach((node) => {
      const key = node.getAttribute("data-context");
      if (key && context[key] !== undefined) {
        node.value = String(context[key]);
      }
      const syncContext = () => {
        readContext();
        setupNavigation();
      };
      node.addEventListener("change", syncContext);
      node.addEventListener("blur", syncContext);
    });
  }

  function setBadges() {
    const base = document.getElementById("badge-base");
    const api = document.getElementById("badge-api");
    const windowBadge = document.getElementById("badge-window");
    const roleBadge = document.getElementById("badge-role");
    if (base) base.textContent = `UI ${config.basePath}`;
    if (api) api.textContent = `API ${config.apiBasePath}`;
    if (windowBadge) {
      windowBadge.textContent = `Default Window ${config.defaultRecentProviderWindowMillis}ms`;
    }
    if (roleBadge) {
      roleBadge.textContent = config.securityEnabled
        ? `Role ${config.currentRole || "ANONYMOUS"}`
        : "Security Disabled";
    }
  }

  function hasFeature(feature) {
    return !config.securityEnabled || (config.allowedFeatures || []).includes(feature);
  }

  function decoratePath(path) {
    const token = loadContext().accessToken;
    if (!token) {
      return path;
    }
    const url = new URL(path, window.location.origin);
    url.searchParams.set(config.tokenQueryParameter, token);
    return `${url.pathname}${url.search}`;
  }

  function routeUrl(route) {
    const path = route === "overview" ? config.basePath : `${config.basePath}/${route}`;
    return decoratePath(path);
  }

  function setupNavigation() {
    const page = document.body.dataset.page || "overview";
    document.querySelectorAll(".nav-menu").forEach((nav) => {
      nav.replaceChildren();
      routes.forEach((route) => {
        if (!hasFeature(route.feature)) {
          return;
        }
        const link = document.createElement("a");
        link.className = "nav-link";
        link.href = routeUrl(route.route);
        link.textContent = route.label;
        if (route.route === page) {
          link.classList.add("active");
        }
        nav.appendChild(link);
      });
    });
  }

  async function request(path, options) {
    const url = new URL(`${config.apiBasePath}${path}`, window.location.origin);
    const context = loadContext();
    if (context.accessToken) {
      url.searchParams.set(config.tokenQueryParameter, context.accessToken);
    }
    const response = await fetch(url.toString(), {
      headers: {
        Accept: "application/json",
        ...(options?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
        ...(context.accessToken && config.tokenHeaderName
          ? { [config.tokenHeaderName]: context.accessToken }
          : {}),
        ...(options?.headers || {})
      },
      ...options
    });
    const text = await response.text();
    let payload = text;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (_error) {
        payload = text;
      }
    }
    if (!response.ok) {
      const message = typeof payload === "string" ? payload : payload?.message;
      throw new Error(message || `Request failed with ${response.status}`);
    }
    return payload;
  }

  function renderJson(id, value) {
    const target = document.getElementById(id);
    if (target) {
      target.textContent = JSON.stringify(value, null, 2);
    }
  }

  function setNotice(id, message, isError) {
    const target = document.getElementById(id);
    if (!target) return;
    target.textContent = message;
    target.className = isError ? "notice error" : "notice";
  }

  async function run(noticeId, job) {
    try {
      await job();
    } catch (error) {
      setNotice(noticeId, error instanceof Error ? error.message : "unexpected error", true);
    }
  }

  function setValue(id, value) {
    const node = document.getElementById(id);
    if (node) {
      node.value = value == null ? "" : String(value);
    }
  }

  function setText(id, value) {
    const node = document.getElementById(id);
    if (node) {
      node.textContent = value == null ? "" : String(value);
    }
  }

  function encodeSegment(value) {
    return encodeURIComponent(value);
  }

  function createButton(label, onClick, variant) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    if (variant === "secondary") {
      button.classList.add("secondary");
    }
    button.addEventListener("click", onClick);
    return button;
  }

  function renderTable(containerId, columns, rows) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!rows.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No data";
      target.appendChild(empty);
      return;
    }

    const wrapper = document.createElement("div");
    wrapper.className = "table-wrap";
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    columns.forEach((column) => {
      const th = document.createElement("th");
      th.textContent = column;
      headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);

    const tbody = document.createElement("tbody");
    rows.forEach((row) => {
      const tr = document.createElement("tr");
      row.forEach((cellValue) => {
        const td = document.createElement("td");
        if (cellValue instanceof window.Node) {
          td.appendChild(cellValue);
        } else {
          td.textContent = cellValue == null ? "" : String(cellValue);
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrapper.appendChild(table);
    target.appendChild(wrapper);
  }

  function populateSelect(id, items, selectedValue) {
    const select = document.getElementById(id);
    if (!select) return;
    select.replaceChildren();
    items.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.value;
      option.textContent = item.label;
      if (selectedValue != null && item.value === selectedValue) {
        option.selected = true;
      }
      select.appendChild(option);
    });
  }

  function currentTenant() {
    return readContext().tenantId;
  }

  function currentWindow() {
    return readContext().recentProviderWindowMillis;
  }

  function initOverview() {
    async function refreshStats() {
      const params = new URLSearchParams();
      if (currentTenant()) {
        params.set("tenantId", currentTenant());
      }
      if (currentWindow() > 0) {
        params.set("recentProviderWindowMillis", String(currentWindow()));
      }
      const stats = await request(`/stats?${params.toString()}`, { method: "GET" });
      renderJson("output-stats", stats);
      setText("stat-tenant", stats.tenantId || currentTenant() || "all");
      setText("stat-docs", stats.docs);
      setText("stat-chunks", stats.chunks);
      setNotice("overview-notice", "stats refreshed", false);
    }

    async function refreshHealth() {
      const params = new URLSearchParams();
      params.set("detailed", "true");
      if (currentWindow() > 0) {
        params.set("recentProviderWindowMillis", String(currentWindow()));
      }
      const health = await request(`/provider-health?${params.toString()}`, { method: "GET" });
      renderJson("output-health", health);
      setNotice("overview-notice", "provider health refreshed", false);
    }

    document.getElementById("btn-stats")?.addEventListener("click", () => run("overview-notice", refreshStats));
    document.getElementById("btn-health")?.addEventListener("click", () => run("overview-notice", refreshHealth));
    run("overview-notice", async () => {
      await refreshStats();
      await refreshHealth();
    });
  }

  function initSearch() {
    function searchPayload() {
      return {
        tenantId: currentTenant(),
        principals: parseList(document.getElementById("search-principals")?.value),
        query: document.getElementById("search-query")?.value?.trim() || "",
        topK: Number(document.getElementById("search-topk")?.value || 5),
        filter: parseMap(document.getElementById("search-filter")?.value),
        providerHealthDetail: true,
        recentProviderWindowMillis: currentWindow() > 0 ? currentWindow() : null
      };
    }

    async function runSearch() {
      const response = await request("/search", {
        method: "POST",
        body: JSON.stringify(searchPayload())
      });
      renderJson("output-search", response);
      setNotice("search-notice", `search completed with ${response.hits.length} hits`, false);
    }

    async function runDiagnostics() {
      const response = await request("/diagnose-search", {
        method: "POST",
        body: JSON.stringify(searchPayload())
      });
      renderJson("output-diagnostics", response);
      setNotice("search-notice", "diagnostics completed", false);
    }

    document.getElementById("btn-search")?.addEventListener("click", () => run("search-notice", runSearch));
    document.getElementById("btn-diagnose")?.addEventListener("click", () => run("search-notice", runDiagnostics));
  }

  function initTextIngest() {
    async function ingestText() {
      const response = await request("/ingest", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docId: document.getElementById("ingest-doc-id")?.value?.trim() || "",
          text: document.getElementById("ingest-text")?.value || "",
          acl: parseList(document.getElementById("ingest-acl")?.value),
          metadata: parseMap(document.getElementById("ingest-metadata")?.value)
        })
      });
      renderJson("output-ingest", response);
      setNotice("ingest-notice", "text document ingested", false);
    }

    document.getElementById("btn-ingest")?.addEventListener("click", () => run("ingest-notice", ingestText));
  }

  function initFileIngest() {
    async function uploadFile() {
      const file = document.getElementById("upload-file")?.files?.[0];
      if (!file) {
        throw new Error("upload file is required");
      }
      const formData = new FormData();
      formData.set("tenantId", currentTenant());
      formData.set("docId", document.getElementById("upload-doc-id")?.value?.trim() || "");
      parseList(document.getElementById("upload-acl")?.value).forEach((item) => formData.append("acl", item));
      formData.set("metadata", document.getElementById("upload-metadata")?.value || "");
      formData.set("file", file);

      const response = await request("/ingest-file", {
        method: "POST",
        body: formData
      });
      renderJson("output-upload", response);
      setNotice("upload-notice", "file ingested", false);
    }

    document.getElementById("btn-upload")?.addEventListener("click", () => run("upload-notice", uploadFile));
  }

  function initDocuments() {
    async function refreshDocuments() {
      const params = new URLSearchParams();
      const tenantId = currentTenant();
      const query = document.getElementById("docs-query")?.value?.trim();
      const limit = document.getElementById("docs-limit")?.value?.trim() || "100";
      if (tenantId) params.set("tenantId", tenantId);
      if (query) params.set("query", query);
      params.set("limit", limit);
      const response = await request(`/documents?${params.toString()}`, { method: "GET" });
      renderJson("output-documents", response);
      renderTable(
        "documents-table",
        ["Doc ID", "Tenant", "Chunks", "Updated", "Source", "Actions"],
        response.items.map((item) => [
          item.docId,
          item.tenantId,
          item.chunkCount,
          item.lastUpdatedIso || "-",
          item.sourceUris.join(", "),
          createButton("Open", () => run("documents-notice", () => loadDetail(item.tenantId, item.docId)), "secondary")
        ])
      );
      setNotice("documents-notice", `loaded ${response.totalCount} documents`, false);
    }

    async function loadDetail(tenantId, docId) {
      const detail = await request(
        `/documents/${encodeSegment(tenantId)}/${encodeSegment(docId)}`,
        { method: "GET" }
      );
      setValue("docs-selected-tenant", detail.tenantId);
      setValue("docs-selected-id", detail.docId);
      setValue("docs-selected-source", detail.sourceUris[0] || "");
      setValue("docs-selected-acl", detail.acl.join(", "));
      setValue("docs-selected-metadata", formatMap(detail.metadata));
      setValue("docs-selected-text", "");
      populateSelect(
        "docs-selected-chunk",
        detail.chunks.map((chunk) => ({
          value: chunk.chunkId,
          label: `${chunk.chunkId}${chunk.page != null ? ` / page ${chunk.page}` : ""}`
        })),
        detail.chunks[0]?.chunkId || ""
      );
      renderJson("output-document-detail", detail);
      setNotice("documents-notice", `loaded ${detail.docId}`, false);
    }

    async function showDetailFromFields() {
      const tenantId = document.getElementById("docs-selected-tenant")?.value?.trim() || currentTenant();
      const docId = document.getElementById("docs-selected-id")?.value?.trim() || "";
      if (!tenantId || !docId) {
        throw new Error("tenantId and docId are required");
      }
      await loadDetail(tenantId, docId);
    }

    async function previewSource() {
      const tenantId = document.getElementById("docs-selected-tenant")?.value?.trim() || currentTenant();
      const docId = document.getElementById("docs-selected-id")?.value?.trim() || "";
      const chunkId = document.getElementById("docs-selected-chunk")?.value || "";
      const contextChars = document.getElementById("docs-preview-context")?.value || "140";
      const charset = document.getElementById("docs-selected-charset")?.value || "UTF-8";
      if (!tenantId || !docId) {
        throw new Error("tenantId and docId are required");
      }
      const params = new URLSearchParams();
      if (chunkId) params.set("chunkId", chunkId);
      params.set("contextChars", contextChars);
      params.set("charset", charset);
      const response = await request(
        `/documents/${encodeSegment(tenantId)}/${encodeSegment(docId)}/source-preview?${params.toString()}`,
        { method: "GET" }
      );
      renderJson("output-source-preview", response);
      setNotice("documents-notice", "source preview loaded", false);
    }

    async function reindexDocument() {
      const tenantId = document.getElementById("docs-selected-tenant")?.value?.trim() || currentTenant();
      const docId = document.getElementById("docs-selected-id")?.value?.trim() || "";
      if (!tenantId || !docId) {
        throw new Error("tenantId and docId are required");
      }
      const metadataText = document.getElementById("docs-selected-metadata")?.value?.trim() || "";
      const aclText = document.getElementById("docs-selected-acl")?.value?.trim() || "";
      const text = document.getElementById("docs-selected-text")?.value || "";
      const sourceUri = document.getElementById("docs-selected-source")?.value?.trim() || "";
      const payload = {
        text: text.trim() ? text : null,
        sourceUri: sourceUri || null,
        metadata: metadataText ? parseMap(metadataText) : null,
        acl: aclText ? parseList(aclText) : null,
        charset: document.getElementById("docs-selected-charset")?.value || "UTF-8"
      };
      const response = await request(
        `/documents/${encodeSegment(tenantId)}/${encodeSegment(docId)}/reindex`,
        { method: "POST", body: JSON.stringify(payload) }
      );
      renderJson("output-document-action", response);
      await loadDetail(tenantId, docId);
      await refreshDocuments();
      setNotice("documents-notice", "document reindexed", false);
    }

    async function deleteDocument() {
      const tenantId = document.getElementById("docs-selected-tenant")?.value?.trim() || currentTenant();
      const docId = document.getElementById("docs-selected-id")?.value?.trim() || "";
      if (!tenantId || !docId) {
        throw new Error("tenantId and docId are required");
      }
      const response = await request(
        `/documents/${encodeSegment(tenantId)}/${encodeSegment(docId)}`,
        { method: "DELETE" }
      );
      renderJson("output-document-action", response);
      renderJson("output-document-detail", {});
      renderJson("output-source-preview", {});
      await refreshDocuments();
      setNotice("documents-notice", "document deleted", false);
    }

    document.getElementById("btn-documents-refresh")?.addEventListener("click", () => run("documents-notice", refreshDocuments));
    document.getElementById("btn-document-detail")?.addEventListener("click", () => run("documents-notice", showDetailFromFields));
    document.getElementById("btn-document-preview")?.addEventListener("click", () => run("documents-notice", previewSource));
    document.getElementById("btn-document-reindex")?.addEventListener("click", () => run("documents-notice", reindexDocument));
    document.getElementById("btn-document-delete")?.addEventListener("click", () => run("documents-notice", deleteDocument));
    run("documents-notice", refreshDocuments);
  }

  function initTenants() {
    async function refreshTenants() {
      const response = await request("/tenants", { method: "GET" });
      renderJson("output-tenants", response);
      renderTable(
        "tenants-table",
        ["Tenant", "Docs", "Chunks", "Updated", "Actions"],
        response.items.map((item) => [
          item.tenantId,
          item.docs,
          item.chunks,
          item.lastUpdatedIso || "-",
          createButton("Open", () => run("tenants-notice", () => loadTenant(item.tenantId)), "secondary")
        ])
      );
      renderTable(
        "snapshot-table",
        ["Snapshot", "Updated", "Actions"],
        response.snapshots.map((snapshot) => [
          snapshot.tag,
          snapshot.updatedAtIso,
          createButton("Use", () => {
            setValue("tenant-restore-tag", snapshot.tag);
            setNotice("tenants-notice", `restore tag set to ${snapshot.tag}`, false);
          }, "secondary")
        ])
      );
      setNotice("tenants-notice", `loaded ${response.items.length} tenants`, false);
    }

    async function loadTenant(tenantId) {
      const response = await request(`/tenants/${encodeSegment(tenantId)}`, { method: "GET" });
      setValue("tenant-selected-id", tenantId);
      const tenantField = contextInput("tenantId");
      if (tenantField) {
        tenantField.value = tenantId;
        readContext();
      }
      renderJson("output-tenant-detail", response);
      setNotice("tenants-notice", `loaded ${tenantId}`, false);
    }

    async function loadSelectedTenant() {
      const tenantId = document.getElementById("tenant-selected-id")?.value?.trim() || currentTenant();
      if (!tenantId) {
        throw new Error("tenantId is required");
      }
      await loadTenant(tenantId);
    }

    async function deleteTenant() {
      const tenantId = document.getElementById("tenant-selected-id")?.value?.trim() || currentTenant();
      if (!tenantId) {
        throw new Error("tenantId is required");
      }
      const response = await request(`/tenants/${encodeSegment(tenantId)}`, { method: "DELETE" });
      renderJson("output-tenant-operations", response);
      await refreshTenants();
      setNotice("tenants-notice", `deleted ${tenantId}`, false);
    }

    async function snapshot() {
      const tag = document.getElementById("tenant-snapshot-tag")?.value?.trim() || "";
      if (!tag) {
        throw new Error("snapshot tag is required");
      }
      const response = await request(`/operations/snapshot?tag=${encodeURIComponent(tag)}`, { method: "POST" });
      renderJson("output-tenant-operations", response);
      await refreshTenants();
      setNotice("tenants-notice", `snapshot ${tag} created`, false);
    }

    async function restore() {
      const tag = document.getElementById("tenant-restore-tag")?.value?.trim() || "";
      if (!tag) {
        throw new Error("restore tag is required");
      }
      const response = await request(`/operations/restore?tag=${encodeURIComponent(tag)}`, { method: "POST" });
      renderJson("output-tenant-operations", response);
      await refreshTenants();
      setNotice("tenants-notice", `snapshot ${tag} restored`, false);
    }

    async function optimize() {
      const response = await request("/operations/optimize", { method: "POST" });
      renderJson("output-tenant-operations", response);
      setNotice("tenants-notice", response.message || "optimize completed", !response.success);
    }

    async function rebuildMetadata() {
      const tenantId = document.getElementById("tenant-selected-id")?.value?.trim();
      const path = tenantId
        ? `/operations/rebuild-metadata?tenantId=${encodeURIComponent(tenantId)}`
        : "/operations/rebuild-metadata";
      const response = await request(path, { method: "POST" });
      renderJson("output-tenant-operations", response);
      await refreshTenants();
      setNotice("tenants-notice", response.message || "metadata rebuild completed", !response.success);
    }

    document.getElementById("btn-tenants-refresh")?.addEventListener("click", () => run("tenants-notice", refreshTenants));
    document.getElementById("btn-tenant-detail")?.addEventListener("click", () => run("tenants-notice", loadSelectedTenant));
    document.getElementById("btn-tenant-delete")?.addEventListener("click", () => run("tenants-notice", deleteTenant));
    document.getElementById("btn-snapshot")?.addEventListener("click", () => run("tenants-notice", snapshot));
    document.getElementById("btn-restore")?.addEventListener("click", () => run("tenants-notice", restore));
    document.getElementById("btn-optimize")?.addEventListener("click", () => run("tenants-notice", optimize));
    document.getElementById("btn-rebuild-metadata")?.addEventListener("click", () => run("tenants-notice", rebuildMetadata));
    run("tenants-notice", refreshTenants);
  }

  function initProviderHistory() {
    async function refreshProviderHistory() {
      const limit = document.getElementById("provider-limit")?.value || "120";
      const recentWindow = currentWindow();
      const healthParams = new URLSearchParams({ detailed: "true" });
      if (recentWindow > 0) {
        healthParams.set("recentProviderWindowMillis", String(recentWindow));
      }
      const [health, history] = await Promise.all([
        request(`/provider-health?${healthParams.toString()}`, { method: "GET" }),
        request(`/provider-history?limit=${encodeURIComponent(limit)}`, { method: "GET" })
      ]);
      renderJson("output-provider-health", health);
      renderJson("output-provider-history", history);
      renderTable(
        "provider-history-table",
        ["Timestamp", "Source", "Endpoints", "Failures"],
        history.history.map((entry) => [
          new Date(entry.timestampEpochMillis).toISOString(),
          entry.source,
          entry.telemetry.endpoints.map((provider) => provider.provider).join(", "),
          entry.telemetry.failureCount
        ])
      );
      renderTable(
        "provider-fallbacks-table",
        ["Timestamp", "Tenant", "Query", "Role", "Reason"],
        history.fallbackEvents.map((entry) => [
          new Date(entry.timestampEpochMillis).toISOString(),
          entry.tenantId,
          entry.query,
          entry.role || "-",
          entry.providerFallbackReason || "-"
        ])
      );
      setNotice("provider-notice", `loaded ${history.history.length} provider samples`, false);
    }

    document.getElementById("btn-provider-refresh")?.addEventListener("click", () => run("provider-notice", refreshProviderHistory));
    run("provider-notice", refreshProviderHistory);
  }

  function initSearchAudit() {
    async function refreshSearchAudit() {
      const limit = document.getElementById("audit-limit")?.value || "100";
      const response = await request(`/search-audit?limit=${encodeURIComponent(limit)}`, { method: "GET" });
      renderJson("output-search-audit", response);
      renderTable(
        "audit-table",
        ["When", "Type", "Tenant", "Query", "Hits", "Role", "Fallback"],
        response.map((entry) => [
          new Date(entry.timestampEpochMillis).toISOString(),
          entry.auditType,
          entry.tenantId,
          entry.query,
          entry.resultCount,
          entry.role || "-",
          entry.providerFallbackApplied ? entry.providerFallbackReason || "yes" : "no"
        ])
      );
      setNotice("audit-notice", `loaded ${response.length} audit rows`, false);
    }

    document.getElementById("btn-audit-refresh")?.addEventListener("click", () => run("audit-notice", refreshSearchAudit));
    run("audit-notice", refreshSearchAudit);
  }

  function initJobHistory() {
    async function refreshJobHistory() {
      const limit = document.getElementById("job-limit")?.value || "100";
      const response = await request(`/job-history?limit=${encodeURIComponent(limit)}`, { method: "GET" });
      renderJson("output-job-history", response);
      renderTable(
        "job-table",
        ["When", "Type", "Tenant", "Status", "Description", "Actions"],
        response.map((entry) => [
          new Date(entry.timestampEpochMillis).toISOString(),
          entry.jobType,
          entry.tenantId || "-",
          entry.status,
          entry.description,
          entry.retrySupported
            ? createButton("Retry", () => run("job-notice", async () => {
                const retryResponse = await request(`/job-history/${encodeSegment(entry.id)}/retry`, { method: "POST" });
                renderJson("output-job-action", retryResponse);
                await refreshJobHistory();
                setNotice("job-notice", `retried ${entry.jobType}`, false);
              }), "secondary")
            : "-"
        ])
      );
      setNotice("job-notice", `loaded ${response.length} jobs`, false);
    }

    document.getElementById("btn-job-refresh")?.addEventListener("click", () => run("job-notice", refreshJobHistory));
    run("job-notice", refreshJobHistory);
  }

  function initAccessSecurity() {
    async function refreshAccessSecurity() {
      const response = await request("/access-security", { method: "GET" });
      renderJson("output-access-security", response);
      renderTable(
        "access-feature-table",
        ["Feature", "Roles"],
        Object.entries(response.featureRoles).map(([feature, roles]) => [feature, roles.join(", ")])
      );
      renderTable(
        "access-audit-table",
        ["When", "Method", "Path", "Role", "Granted", "Message"],
        response.recentAccessAudits.map((entry) => [
          new Date(entry.timestampEpochMillis).toISOString(),
          entry.method,
          entry.path,
          entry.role || "-",
          entry.granted ? "yes" : "no",
          entry.message || "-"
        ])
      );
      setText("access-current-role", response.currentRole || "ANONYMOUS");
      setText("access-token-header", response.tokenHeaderName);
      setText("access-token-query", response.tokenQueryParameter);
      setNotice("access-notice", "access configuration refreshed", false);
    }

    document.getElementById("btn-access-refresh")?.addEventListener("click", () => run("access-notice", refreshAccessSecurity));
    run("access-notice", refreshAccessSecurity);
  }

  function initConfig() {
    async function refreshConfig() {
      const response = await request("/config", { method: "GET" });
      renderJson("output-config", response);
      setNotice("config-notice", "config refreshed", false);
    }

    document.getElementById("btn-config-refresh")?.addEventListener("click", () => run("config-notice", refreshConfig));
    run("config-notice", refreshConfig);
  }

  function initBulkOperations() {
    async function bulkTextIngest() {
      const response = await request("/bulk/text-ingest", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          documents: parseJsonInput("bulk-ingest-json", [])
        })
      });
      renderJson("output-bulk", response);
      setNotice("bulk-notice", `bulk text ingest completed with ${response.successCount} successes`, false);
    }

    async function bulkDelete() {
      const response = await request("/bulk/delete", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docIds: parseList(document.getElementById("bulk-delete-docs")?.value)
        })
      });
      renderJson("output-bulk", response);
      setNotice("bulk-notice", `bulk delete completed with ${response.successCount} successes`, false);
    }

    async function bulkMetadataPatch() {
      const response = await request("/bulk/metadata-patch", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docIds: parseList(document.getElementById("bulk-patch-docs")?.value),
          metadata: parseMap(document.getElementById("bulk-patch-metadata")?.value)
        })
      });
      renderJson("output-bulk", response);
      setNotice("bulk-notice", `metadata patch completed with ${response.successCount} successes`, false);
    }

    document.getElementById("btn-bulk-ingest")?.addEventListener("click", () => run("bulk-notice", bulkTextIngest));
    document.getElementById("btn-bulk-delete")?.addEventListener("click", () => run("bulk-notice", bulkDelete));
    document.getElementById("btn-bulk-patch")?.addEventListener("click", () => run("bulk-notice", bulkMetadataPatch));
  }

  function initPage() {
    setBadges();
    setupNavigation();
    bindContext();

    switch (document.body.dataset.page) {
      case "search":
        initSearch();
        break;
      case "text-ingest":
        initTextIngest();
        break;
      case "file-ingest":
        initFileIngest();
        break;
      case "documents":
        initDocuments();
        break;
      case "tenants":
        initTenants();
        break;
      case "provider-history":
        initProviderHistory();
        break;
      case "search-audit":
        initSearchAudit();
        break;
      case "job-history":
        initJobHistory();
        break;
      case "access-security":
        initAccessSecurity();
        break;
      case "config":
        initConfig();
        break;
      case "bulk-operations":
        initBulkOperations();
        break;
      default:
        initOverview();
        break;
    }
  }

  document.addEventListener("DOMContentLoaded", initPage);
})();
