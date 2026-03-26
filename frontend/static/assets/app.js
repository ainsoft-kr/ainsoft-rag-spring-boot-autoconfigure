(function () {
  const config = window.RAG_ADMIN_CONFIG || {
    basePath: "/rag-admin",
    apiBasePath: "/api/rag/admin",
    defaultTenantId: "tenant-admin",
    defaultAclPrincipals: ["group:admin"],
    defaultSearchPrincipals: ["group:admin"],
    defaultSearchQuery: "hybrid retrieval",
    defaultRecentProviderWindowMillis: 60000,
    securityEnabled: false,
    currentRole: "ADMIN",
    allowedFeatures: [],
    currentUser: "ADMIN",
    loginPath: "/rag-admin/login",
    logoutPath: "/rag-admin/logout"
  };

  const routes = [
    { route: "overview", feature: "overview", label: "Overview", caption: "Live board", section: "Monitor", board: "Control Tower" },
    { route: "provider-history", feature: "provider-history", label: "Provider History", caption: "Latency and fallback", section: "Monitor", board: "Provider Telemetry" },
    { route: "search-audit", feature: "search-audit", label: "Search Audit", caption: "Query trail", section: "Monitor", board: "Audit Tape" },
    { route: "job-history", feature: "job-history", label: "Job History", caption: "Runbook queue", section: "Monitor", board: "Execution Tape" },
    { route: "search", feature: "search", label: "Search", caption: "Diagnostic retrieval", section: "Retrieval", board: "Search Desk" },
    { route: "documents", feature: "documents", label: "Documents", caption: "Browser and preview", section: "Retrieval", board: "Document Ledger" },
    { route: "graph", feature: "graph", label: "Graph", caption: "Document and entity relationships", section: "Retrieval", board: "Graph Explorer" },
    { route: "text-ingest", feature: "text-ingest", label: "Text Ingest", caption: "Direct upsert", section: "Ingest", board: "Manual Ingest" },
    { route: "file-ingest", feature: "file-ingest", label: "File Ingest", caption: "Upload pipeline", section: "Ingest", board: "Upload Ingest" },
    { route: "web-ingest", feature: "web-ingest", label: "Web Ingest", caption: "Site crawl ingest", section: "Ingest", board: "Web Crawler" },
    { route: "bulk-operations", feature: "bulk-operations", label: "Bulk Ops", caption: "Batch changes", section: "Ingest", board: "Batch Operations" },
    { route: "tenants", feature: "tenants", label: "Tenants & Index Ops", caption: "Snapshot and optimize", section: "Operations", board: "Tenant Operations" },
    { route: "config", feature: "config", label: "Config", caption: "Read-only settings", section: "Operations", board: "Configuration Board" },
    { route: "access-security", feature: "access-security", label: "Access & Security", caption: "Roles and audit", section: "Operations", board: "Security Board" },
    { route: "users", feature: "users", label: "Users", caption: "Accounts and passwords", section: "Operations", board: "User Vault" }
  ];
  const storageKey = "rag-admin-shared-context";
  const legacyDefaultTenantId = "tenant-admin";

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

  function parseOptionalNumber(value) {
    const raw = String(value ?? "").trim();
    if (!raw) {
      return null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function formatThresholdSummary(finalConfidence, topHitScore, exactMatchOnly) {
    const compareMode = document.getElementById("search-exact-compare")?.value || "nori";
    const combineMode = document.getElementById("search-exact-combine")?.value || "and";
    return `finalConfidence ${Number(finalConfidence).toFixed(2)}+, topHitScore ${Number(topHitScore).toFixed(2)}+, exact match ${exactMatchOnly ? "on" : "off"}, compare ${compareMode}, combine ${combineMode}`;
  }

  function formatMap(value) {
    return Object.entries(value || {})
      .map(([key, entryValue]) => `${key}=${entryValue}`)
      .join("\n");
  }

  function escapeHtml(value) {
    return String(value || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll("\"", "&quot;")
      .replaceAll("'", "&#39;");
  }

  function escapeRegExp(value) {
    return String(value || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function highlightTokens(text, tokens, className = "exact-match-token") {
    let output = escapeHtml(text || "");
    (tokens || [])
      .filter(Boolean)
      .sort((a, b) => String(b).length - String(a).length)
      .forEach((token) => {
        const safeToken = escapeHtml(token);
        const re = new RegExp(escapeRegExp(safeToken), "gi");
        output = output.replace(re, (match) => `<mark class="${className}">${match}</mark>`);
      });
    return output;
  }

  function downloadTextFile(filename, content, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => window.URL.revokeObjectURL(url), 0);
  }

  function uniqueSortedTokens(tokens) {
    return Array.from(new Set((tokens || []).map((token) => String(token || "").trim()).filter(Boolean))).sort();
  }

  function parseJsonInput(id, fallback) {
    const value = document.getElementById(id)?.value?.trim();
    if (!value) {
      return fallback;
    }
    return JSON.parse(value);
  }

  function defaultContext() {
    return {
      tenantId: config.defaultTenantId || legacyDefaultTenantId,
      recentProviderWindowMillis: config.defaultRecentProviderWindowMillis
    };
  }

  function normalizeContext(input) {
    const normalizedTenantId = typeof input.tenantId === "string" && input.tenantId.trim()
      ? input.tenantId.trim()
      : "";
    return {
      tenantId:
        normalizedTenantId && !(normalizedTenantId === legacyDefaultTenantId && (config.defaultTenantId || legacyDefaultTenantId) !== legacyDefaultTenantId)
          ? normalizedTenantId
          : (config.defaultTenantId || legacyDefaultTenantId),
      recentProviderWindowMillis: Number(
        input.recentProviderWindowMillis || config.defaultRecentProviderWindowMillis
      )
    };
  }

  function formatInlineList(values) {
    return (values || []).map((value) => String(value || "").trim()).filter(Boolean).join(", ");
  }

  function formatMultilineList(values) {
    return (values || []).map((value) => String(value || "").trim()).filter(Boolean).join("\n");
  }

  function setConfiguredValue(id, value, legacyValues = []) {
    const node = document.getElementById(id);
    if (!node || value == null) return;
    const normalizedValue = String(value);
    const currentValue = String(node.value || "");
    if (!currentValue.trim() || legacyValues.includes(currentValue)) {
      node.value = normalizedValue;
    }
  }

  function applyConfiguredDefaults() {
    setConfiguredValue("search-principals", formatInlineList(config.defaultSearchPrincipals), ["group:admin"]);
    setConfiguredValue("search-query", config.defaultSearchQuery || "hybrid retrieval", ["hybrid retrieval"]);
    setConfiguredValue("ingest-acl", formatInlineList(config.defaultAclPrincipals), ["group:admin"]);
    setConfiguredValue("upload-acl", formatInlineList(config.defaultAclPrincipals), ["group:admin"]);
    setConfiguredValue("web-acl", formatMultilineList(config.defaultAclPrincipals), ["group:admin"]);
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
        contextInput("recentProviderWindowMillis")?.value || existing.recentProviderWindowMillis
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
        updateShellMeta();
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
    const userBadge = document.getElementById("badge-user");
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
    if (userBadge) {
      userBadge.textContent = config.currentUser || "ANONYMOUS";
    }
    updateShellMeta();
  }

  function hasFeature(feature) {
    return !config.securityEnabled || (config.allowedFeatures || []).includes(feature);
  }

  function decoratePath(path) {
    return path;
  }

  function routeUrl(route) {
    const path = route === "overview" ? config.basePath : `${config.basePath}/${route}`;
    return decoratePath(path);
  }

  function currentRouteMeta() {
    const page = document.body.dataset.page || "overview";
    return routes.find((route) => route.route === page) || routes[0];
  }

  function setupNavigation() {
    const page = document.body.dataset.page || "overview";
    document.querySelectorAll(".nav-menu").forEach((nav) => {
      nav.replaceChildren();
      const visibleRoutes = routes.filter((route) => hasFeature(route.feature));
      const groups = visibleRoutes.reduce((acc, route) => {
        acc[route.section] = acc[route.section] || [];
        acc[route.section].push(route);
        return acc;
      }, {});
      Object.entries(groups).forEach(([section, sectionRoutes]) => {
        const group = document.createElement("div");
        group.className = "nav-group";

        const title = document.createElement("div");
        title.className = "nav-group-title";
        title.textContent = section;
        group.appendChild(title);

        sectionRoutes.forEach((route) => {
          const link = document.createElement("a");
          link.className = "nav-link";
          link.href = routeUrl(route.route);
          if (route.route === page) {
            link.classList.add("active");
          }

          const heading = document.createElement("span");
          heading.className = "nav-link-title";
          heading.textContent = route.label;
          link.appendChild(heading);

          const meta = document.createElement("span");
          meta.className = "nav-link-meta";
          meta.textContent = route.caption;
          link.appendChild(meta);

          group.appendChild(link);
        });

        nav.appendChild(group);
      });
    });
  }

  function updateShellMeta() {
    const context = loadContext();
    setText("shell-user", config.currentUser || "ANONYMOUS");
    setText("shell-role", config.currentRole || "ANONYMOUS");
    setText("shell-tenant", context.tenantId || "-");
    setText("shell-window", `${context.recentProviderWindowMillis || 0} ms`);
    setText("topbar-user", config.currentUser || "ANONYMOUS");
    setText("topbar-role", config.currentRole || "ANONYMOUS");
    setText("topbar-tenant", context.tenantId || "-");
    setText("topbar-window", `${context.recentProviderWindowMillis || 0} ms`);
  }

  function renderTrendBars(values) {
    const trend = document.createElement("div");
    trend.className = "strip-trend";
    values.forEach((value) => {
      const bar = document.createElement("span");
      bar.style.height = `${value}px`;
      trend.appendChild(bar);
    });
    return trend;
  }

  function decorateContentShell() {
    const area = document.querySelector(".content-area");
    if (!area || area.querySelector(".topbar")) {
      return;
    }

    const route = currentRouteMeta();
    const topbar = document.createElement("section");
    topbar.className = "topbar";
    topbar.innerHTML = `
      <div class="topbar-copy">
        <span class="topbar-label">${route.section}</span>
        <div class="topbar-title">${route.board} / ${route.label}</div>
      </div>
      <div class="topbar-meta">
        <span class="topbar-pill">User <strong id="topbar-user"></strong></span>
        <span class="topbar-pill">Tenant <strong id="topbar-tenant"></strong></span>
        <span class="topbar-pill">Role <strong id="topbar-role"></strong></span>
        <span class="topbar-pill">Window <strong id="topbar-window"></strong></span>
        <a class="topbar-pill topbar-link" href="${config.logoutPath}">Logout</a>
      </div>
    `;

    area.prepend(topbar);

    if (document.body.dataset.page === "overview") {
      const strip = document.createElement("section");
      strip.className = "market-strip";
      const context = loadContext();
      const metrics = [
        {
          label: "Tenant Scope",
          value: context.tenantId || "-",
          foot: "Shared across pages",
          trend: [20, 28, 24, 34, 40, 36]
        },
        {
          label: "Feature Access",
          value: String((config.allowedFeatures || []).length),
          foot: "Visible menu entries",
          trend: [18, 22, 26, 24, 30, 34]
        },
        {
          label: "Role State",
          value: config.currentRole || "OPEN",
          foot: config.securityEnabled ? "Security enabled" : "Security disabled",
          trend: [16, 20, 18, 24, 28, 32]
        },
        {
          label: "Provider Window",
          value: `${context.recentProviderWindowMillis || 0} ms`,
          foot: "Telemetry horizon",
          trend: [12, 16, 20, 28, 26, 30]
        }
      ];

      metrics.forEach((metric) => {
        const card = document.createElement("article");
        card.className = "strip-card";

        const head = document.createElement("div");
        head.className = "strip-card-head";
        head.innerHTML = `<span>${metric.label}</span><span>${route.section}</span>`;
        card.appendChild(head);

        const strong = document.createElement("strong");
        strong.textContent = metric.value;
        card.appendChild(strong);
        card.appendChild(renderTrendBars(metric.trend));

        const foot = document.createElement("div");
        foot.className = "strip-foot";
        foot.textContent = metric.foot;
        card.appendChild(foot);

        strip.appendChild(card);
      });

      area.insertBefore(strip, topbar.nextSibling);
    }

    updateShellMeta();
  }

  async function request(path, options) {
    const url = new URL(`${config.apiBasePath}${path}`, window.location.origin);
    const response = await fetch(url.toString(), {
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        ...(options?.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
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

  function humanizeId(value) {
    return String(value || "")
      .replace(/^output-/, "")
      .replace(/-/g, " ")
      .replace(/\b\w/g, (char) => char.toUpperCase());
  }

  function ensureJsonShell(target) {
    if (!target || !target.parentElement) {
      return null;
    }
    let shell = target.closest(".json-shell");
    if (shell) {
      return shell;
    }
    shell = document.createElement("div");
    shell.className = "json-shell";

    const toolbar = document.createElement("div");
    toolbar.className = "json-toolbar";

    const title = document.createElement("div");
    title.className = "json-title";
    title.textContent = humanizeId(target.id);
    toolbar.appendChild(title);

    const actions = document.createElement("div");
    actions.className = "json-actions";

    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "json-button";
    toggle.textContent = "Expand";
    toggle.addEventListener("click", () => {
      const expanded = shell.classList.toggle("expanded");
      toggle.textContent = expanded ? "Collapse" : "Expand";
    });
    actions.appendChild(toggle);

    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "json-button";
    copy.textContent = "Copy";
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(target.textContent || "");
        copy.textContent = "Copied";
        window.setTimeout(() => {
          copy.textContent = "Copy";
        }, 1200);
      } catch (_error) {
        copy.textContent = "Unavailable";
        window.setTimeout(() => {
          copy.textContent = "Copy";
        }, 1200);
      }
    });
    actions.appendChild(copy);

    toolbar.appendChild(actions);

    const parent = target.parentElement;
    parent.insertBefore(shell, target);
    shell.appendChild(toolbar);
    shell.appendChild(target);
    return shell;
  }

  function renderJson(id, value) {
    const target = document.getElementById(id);
    if (target) {
      const shell = ensureJsonShell(target);
      target.textContent = JSON.stringify(value, null, 2);
      if (shell) {
        const compact = (target.textContent || "").split("\n").length <= 14;
        shell.classList.toggle("expanded", compact);
        const toggle = shell.querySelector(".json-button");
        if (toggle) {
          toggle.textContent = compact ? "Collapse" : "Expand";
        }
      }
    }
  }

  function readJson(id) {
    const target = document.getElementById(id);
    if (!target) return null;
    const text = target.textContent || "";
    if (!text.trim()) return null;
    try {
      return JSON.parse(text);
    } catch (_error) {
      return null;
    }
  }

  function renderTimeline(containerId, events) {
    renderDataCards(
      containerId,
      (events || []).map((event, index) => ({
        title: event.phase || `step ${index + 1}`,
        meta: event.when || `#${index + 1}`,
        body: event.message || "-",
        className:
          event.level === "error"
            ? "status-failed"
            : event.level === "warn"
              ? "status-changed"
              : event.level === "skip"
                ? "status-skipped"
                : "status-ingested",
        chips: [
          event.source || "trace",
          event.kind || "stage",
          event.status || "ok"
        ]
      }))
    );
  }

  function renderPreviewDiff(previousPreview, currentPreview, summary) {
    const previous = previousPreview ? escapeHtml(previousPreview) : "";
    const current = currentPreview ? escapeHtml(currentPreview) : "";
    const isLong = Math.max(previousPreview ? previousPreview.length : 0, currentPreview ? currentPreview.length : 0) > 280;
    const detailsAttrs = isLong ? "" : " open";
    if (!previous && !current) {
      return summary ? `<div class="diff-summary">${escapeHtml(summary)}</div>` : "";
    }
    return `
      <details class="diff-details"${detailsAttrs}>
        <summary class="diff-summary-toggle">${isLong ? "Show diff preview" : "Hide diff preview"}</summary>
        <div class="diff-preview">
          <div class="diff-line diff-old"><span>Old</span><pre>${previous || "(missing)"}</pre></div>
          <div class="diff-line diff-new"><span>New</span><pre>${current || "(missing)"}</pre></div>
        </div>
      </details>
      ${summary ? `<div class="diff-summary">${escapeHtml(summary)}</div>` : ""}
    `;
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

  function formatDateInputValue(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }

  function setText(id, value) {
    const node = document.getElementById(id);
    if (node) {
      node.textContent = value == null ? "" : String(value);
    }
  }

  function setTabCount(id, value) {
    const node = document.getElementById(id);
    if (!node) return;
    const numeric = Number(value) || 0;
    node.textContent = String(numeric);
    node.hidden = numeric <= 0;
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

  function formatCompactNumber(value) {
    if (value == null || Number.isNaN(Number(value))) {
      return "-";
    }
    return new Intl.NumberFormat("en-US", {
      notation: "compact",
      maximumFractionDigits: 1
    }).format(Number(value));
  }

  function formatBytes(value) {
    const numeric = Number(value || 0);
    if (!numeric) {
      return "0 B";
    }
    const units = ["B", "KB", "MB", "GB", "TB"];
    let amount = numeric;
    let unitIndex = 0;
    while (amount >= 1024 && unitIndex < units.length - 1) {
      amount /= 1024;
      unitIndex += 1;
    }
    return `${amount.toFixed(amount >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
  }

  function sparklineSvg(values) {
    const safeValues = values.length ? values : [0, 0, 0, 0];
    const max = Math.max(...safeValues, 1);
    const step = safeValues.length === 1 ? 100 : 100 / (safeValues.length - 1);
    const points = safeValues
      .map((value, index) => {
        const x = Math.round(index * step);
        const y = Math.round(48 - (value / max) * 40);
        return `${x},${y}`;
      })
      .join(" ");
    const fillPoints = `0,52 ${points} 100,52`;
    return `
      <svg class="sparkline" viewBox="0 0 100 52" preserveAspectRatio="none" aria-hidden="true">
        <polygon class="sparkline-fill" points="${fillPoints}"></polygon>
        <polyline points="${points}"></polyline>
      </svg>
    `;
  }

  function renderAnalyticsCards(containerId, cards) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    cards.forEach((card) => {
      const node = document.createElement("article");
      node.className = "analytic-card";
      node.innerHTML = `
        <div class="analytic-card-head">
          <span>${card.label}</span>
          <span>${card.meta || ""}</span>
        </div>
        <strong>${card.value}</strong>
        ${sparklineSvg(card.series || [])}
        <div class="analytic-foot">${card.foot || ""}</div>
      `;
      target.appendChild(node);
    });
  }

  function renderBarList(containerId, items) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No chart data";
      target.appendChild(empty);
      return;
    }
    const bars = document.createElement("div");
    bars.className = "bars";
    items.forEach((item) => {
      const row = document.createElement("div");
      row.className = "bar-row";
      row.innerHTML = `
        <div class="bar-row-head">
          <span>${item.label}</span>
          <span>${item.valueLabel}</span>
        </div>
        <div class="bar-track">
          <span class="bar-fill${item.warn ? " warn" : ""}" style="width:${item.ratio}%"></span>
        </div>
      `;
      bars.appendChild(row);
    });
    target.appendChild(bars);
  }

  function renderActivityList(containerId, items) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No recent activity";
      target.appendChild(empty);
      return;
    }
    const list = document.createElement("div");
    list.className = "activity-list";
    items.forEach((item) => {
      const row = document.createElement("div");
      row.className = "activity-item";
      row.innerHTML = `
        <span class="activity-dot${item.warn ? " warn" : ""}"></span>
        <div class="activity-card">
          <div class="activity-head">
            <span>${item.title}</span>
            <span>${item.when}</span>
          </div>
          <div>${item.body}</div>
          <div class="activity-meta">${item.meta}</div>
        </div>
      `;
      list.appendChild(row);
    });
    target.appendChild(list);
  }

  function renderDataCards(containerId, items) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No card data";
      target.appendChild(empty);
      return;
    }
    const list = document.createElement("div");
    list.className = "card-list";
    items.forEach((item) => {
      const card = document.createElement("article");
      card.className = `data-card${item.className ? ` ${item.className}` : ""}`;
      const chips = (item.chips || []).map((chip) => `<span class="mini-chip">${chip}</span>`).join("");
      card.innerHTML = `
        <div class="data-card-head">
          <div class="data-card-title">${item.title}</div>
          <div class="data-card-meta">${item.meta || ""}</div>
        </div>
        <div class="data-card-body">${item.bodyHtml != null ? item.bodyHtml : (item.body || "")}</div>
        ${chips ? `<div class="chip-row">${chips}</div>` : ""}
      `;
      list.appendChild(card);
    });
    target.appendChild(list);
  }

  function renderRoleMatrix(containerId, featureRoles) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    const features = Object.keys(featureRoles || {});
    if (!features.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No role matrix";
      target.appendChild(empty);
      return;
    }
    const roles = Array.from(
      new Set(features.flatMap((feature) => featureRoles[feature] || []))
    ).sort();
    const matrix = document.createElement("div");
    matrix.className = "matrix";
    const grid = document.createElement("div");
    grid.className = "matrix-grid";
    grid.style.gridTemplateColumns = `minmax(180px, 2fr) repeat(${roles.length}, minmax(110px, 1fr))`;

    const corner = document.createElement("div");
    corner.className = "matrix-cell matrix-head";
    corner.textContent = "Feature";
    grid.appendChild(corner);
    roles.forEach((role) => {
      const head = document.createElement("div");
      head.className = "matrix-cell matrix-head";
      head.textContent = role;
      grid.appendChild(head);
    });

    features.forEach((feature) => {
      const label = document.createElement("div");
      label.className = "matrix-cell matrix-row-head";
      label.textContent = feature;
      grid.appendChild(label);
      roles.forEach((role) => {
        const cell = document.createElement("div");
        const allowed = (featureRoles[feature] || []).includes(role);
        cell.className = `matrix-cell ${allowed ? "matrix-allow" : "matrix-deny"}`;
        cell.textContent = allowed ? "ALLOW" : "DENY";
        grid.appendChild(cell);
      });
    });

    matrix.appendChild(grid);
    target.appendChild(matrix);
  }

  function renderLineChart(containerId, values, options) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!values.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No chart data";
      target.appendChild(empty);
      return;
    }
    const width = 100;
    const height = 180;
    const max = Math.max(...values, 1);
    const min = Math.min(...values, 0);
    const range = Math.max(max - min, 1);
    const step = values.length === 1 ? width : width / (values.length - 1);
    const points = values.map((value, index) => {
      const x = Math.round(index * step * 100) / 100;
      const y = Math.round((height - 16 - ((value - min) / range) * (height - 36)) * 100) / 100;
      return { x, y, value };
    });
    const polyline = points.map((point) => `${point.x},${point.y}`).join(" ");
    const area = `0,${height} ${polyline} ${width},${height}`;

    const card = document.createElement("div");
    card.className = "chart-card";
    const title = options?.title || "Trend";
    const subtitle = options?.subtitle || "";
    const latest = values[values.length - 1];
    card.innerHTML = `
      <div class="chart-shell">
        <div class="panel-header">
          <div>
            <h2>${title}</h2>
            <p>${subtitle}</p>
          </div>
        </div>
        <svg class="chart-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" aria-hidden="true">
          <g class="chart-grid">
            <line x1="0" y1="${height}" x2="${width}" y2="${height}"></line>
            <line x1="0" y1="${height * 0.66}" x2="${width}" y2="${height * 0.66}"></line>
            <line x1="0" y1="${height * 0.33}" x2="${width}" y2="${height * 0.33}"></line>
          </g>
          <polygon class="chart-area" points="${area}"></polygon>
          <polyline class="chart-line" points="${polyline}"></polyline>
          ${points.map((point) => `<circle class="chart-dot" cx="${point.x}" cy="${point.y}" r="2.4"></circle>`).join("")}
        </svg>
        <div class="chart-caption">
          <span>${options?.leftLabel || "Start"}</span>
          <span>${options?.valueFormatter ? options.valueFormatter(latest) : latest}</span>
          <span>${options?.rightLabel || "Latest"}</span>
        </div>
      </div>
    `;
    target.appendChild(card);
  }

  function renderTimeline(containerId, items) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No timeline data";
      target.appendChild(empty);
      return;
    }
    const timeline = document.createElement("div");
    timeline.className = "timeline";
    items.forEach((item) => {
      const row = document.createElement("div");
      row.className = "timeline-item";
      const chips = (item.chips || []).map((chip) => `<span class="mini-chip">${chip}</span>`).join("");
      row.innerHTML = `
        <span class="timeline-node${item.warn ? " warn" : ""}"></span>
        <div class="timeline-content">
          <div class="timeline-head">
            <span>${item.title}</span>
            <span>${item.when || ""}</span>
          </div>
          <div>${item.body || ""}</div>
          <div class="timeline-meta">${item.meta || ""}</div>
          ${chips ? `<div class="chip-row">${chips}</div>` : ""}
        </div>
      `;
      timeline.appendChild(row);
    });
    target.appendChild(timeline);
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

    const normalizedRows = rows.map((row) => ({
      cells: row,
      text: row.map((cell) =>
        cell instanceof window.Node ? (cell.textContent || "").trim() : String(cell == null ? "" : cell)
      )
    }));
    let sortIndex = -1;
    let sortDir = 1;
    let filterTerm = "";

    const toolbar = document.createElement("div");
    toolbar.className = "table-toolbar";
    const count = document.createElement("div");
    count.className = "table-count";
    const filter = document.createElement("input");
    filter.type = "search";
    filter.placeholder = "Filter rows";
    toolbar.appendChild(count);
    toolbar.appendChild(filter);

    const wrapper = document.createElement("div");
    wrapper.className = "table-wrap";
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    columns.forEach((column, columnIndex) => {
      const th = document.createElement("th");
      th.textContent = column;
      th.classList.add("sortable");
      th.addEventListener("click", () => {
        if (sortIndex === columnIndex) {
          sortDir *= -1;
        } else {
          sortIndex = columnIndex;
          sortDir = 1;
        }
        renderRows();
      });
      headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);

    const tbody = document.createElement("tbody");

    function renderRows() {
      tbody.replaceChildren();
      headerRow.querySelectorAll("th").forEach((th, index) => {
        th.classList.remove("asc", "desc");
        if (index === sortIndex) {
          th.classList.add(sortDir > 0 ? "asc" : "desc");
        }
      });

      let visibleRows = normalizedRows.filter((row) =>
        !filterTerm || row.text.some((value) => value.toLowerCase().includes(filterTerm))
      );
      if (sortIndex >= 0) {
        visibleRows = [...visibleRows].sort((left, right) => {
          const a = left.text[sortIndex] || "";
          const b = right.text[sortIndex] || "";
          return a.localeCompare(b, undefined, { numeric: true }) * sortDir;
        });
      }
      count.textContent = `${visibleRows.length} rows`;
      visibleRows.forEach((row) => {
        const tr = document.createElement("tr");
        row.cells.forEach((cellValue) => {
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
    }

    filter.addEventListener("input", () => {
      filterTerm = filter.value.trim().toLowerCase();
      renderRows();
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    wrapper.appendChild(table);
    target.appendChild(toolbar);
    target.appendChild(wrapper);
    renderRows();
  }

  function normalizeRelationFilters(value) {
    return parseList(value).map((item) => item.toLowerCase());
  }

  const graphInstances = new Map();

  function destroyGraphInstance(containerId) {
    const existing = graphInstances.get(containerId);
    if (existing && typeof existing.destroy === "function") {
      existing.destroy();
    }
    graphInstances.delete(containerId);
  }

  function graphLayoutOptions(nodeCount, edgeCount, selectedNodeId) {
    const layout = document.getElementById("graph-layout")?.value || "cose";
    const scale = document.getElementById("graph-scale")?.value || "normal";
    const preset = document.getElementById("graph-preset")?.value || "explore";
    const density = nodeCount > 0 ? edgeCount / nodeCount : 0;
    const scaleFactor = scale === "dense" ? 0.85 : scale === "compact" ? 0.72 : 1;
    const presetScale = preset === "dense" ? 0.8 : preset === "path" ? 1.1 : preset === "entity" ? 0.92 : 1;
    const pull = Math.max(60, Math.min(220, Math.round((110 + density * 10) * scaleFactor * presetScale)));
    const repulsion = Math.max(3200, Math.min(20000, Math.round((7000 + nodeCount * 650) / (scaleFactor * presetScale))));
    const iterations = Math.max(240, Math.min(1600, Math.round((700 + nodeCount * 18) * scaleFactor * presetScale)));
    const elastic = Math.max(16, Math.min(104, Math.round((34 + density * 4) * scaleFactor * presetScale)));
    if (layout === "circle") {
      return {
        name: "circle",
        fit: true,
        padding: 52,
        animate: nodeCount <= 80
      };
    }
    if (layout === "concentric") {
      return {
        name: "concentric",
        fit: true,
        padding: 52,
        animate: nodeCount <= 80,
        concentric: (node) => (node.data("kind") === "DOCUMENT" ? 5 : node.data("kind") === "ENTITY" ? 4 : 3),
        levelWidth: () => 1
      };
    }
    if (layout === "grid") {
      return {
        name: "grid",
        fit: true,
        padding: 52,
        avoidOverlap: true,
        animate: false,
        rows: Math.max(1, Math.ceil(Math.sqrt(nodeCount))),
        cols: Math.max(1, Math.ceil(Math.sqrt(nodeCount)))
      };
    }
    return {
      name: "cose",
      animate: nodeCount <= 120,
      animationDuration: 420,
      fit: true,
      padding: 52,
      randomize: nodeCount > 14,
      nodeRepulsion: repulsion,
      idealEdgeLength: pull,
      edgeElasticity: elastic,
      nestingFactor: 0.95,
      gravity: 0.18,
      numIter: iterations,
      initialTemp: 900,
      coolingFactor: 0.95,
      minTemp: 1.0
    };
  }

  function applyGraphPreset(presetName) {
    const preset = String(presetName || "explore").toLowerCase();
    const layout = document.getElementById("graph-layout");
    const scale = document.getElementById("graph-scale");
    const relation = document.getElementById("graph-relation-filter");
    const depth = document.getElementById("graph-depth");
    if (preset === "dense") {
      if (layout) layout.value = "concentric";
      if (scale) scale.value = "dense";
      if (relation) relation.value = "";
      if (depth) depth.value = "2";
      return;
    }
    if (preset === "path") {
      if (layout) layout.value = "circle";
      if (scale) scale.value = "compact";
      if (relation) relation.value = "MENTIONS,RELATED_TO,CITES";
      if (depth) depth.value = "3";
      return;
    }
    if (preset === "entity") {
      if (layout) layout.value = "grid";
      if (scale) scale.value = "dense";
      if (relation) relation.value = "MENTIONS,RELATED_TO";
      if (depth) depth.value = "2";
      return;
    }
    if (layout) layout.value = "cose";
    if (scale) scale.value = "normal";
    if (relation) relation.value = "";
    if (depth) depth.value = "1";
  }

  function renderGraphCanvas(containerId, subgraph, selectedNodeId, onSelectNode, relationFilter) {
    const target = document.getElementById(containerId);
    if (!target) return;
    target.replaceChildren();
    destroyGraphInstance(containerId);

    const nodes = Array.isArray(subgraph?.nodes) ? subgraph.nodes : [];
    const edges = Array.isArray(subgraph?.edges) ? subgraph.edges : [];
    const relationFilters = normalizeRelationFilters(relationFilter);
    const visibleEdges = relationFilters.length
      ? edges.filter((edge) => relationFilters.includes(String(edge.relationType || "").toLowerCase()))
      : edges;
    if (!nodes.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No graph data";
      target.appendChild(empty);
      return;
    }

    if (typeof window.cytoscape === "function") {
      const cyHost = document.createElement("div");
      cyHost.className = "graph-cytoscape";
      target.appendChild(cyHost);

      const cy = window.cytoscape({
        container: cyHost,
        elements: [
          ...nodes.map((node) => ({
            data: {
              id: node.id,
              label: node.label || node.id,
              kind: node.kind || "",
              tenantId: node.tenantId || "",
              ...node.properties
            },
            classes: `kind-${String(node.kind || "").toLowerCase()}`
          })),
          ...visibleEdges.map((edge) => ({
            data: {
              id: edge.id,
              source: edge.fromId,
              target: edge.toId,
              label: edge.relationType || "",
              relationType: edge.relationType || "",
              tenantId: edge.tenantId || "",
              ...edge.properties
            },
            classes: `relation-${String(edge.relationType || "").toLowerCase()}`
          }))
        ],
        style: [
          {
            selector: "node",
            style: {
              label: "data(label)",
              color: "#111827",
              "font-size": 10,
              "text-wrap": "wrap",
              "text-max-width": 120,
              "background-color": "#4f46e5",
              "border-width": 2,
              "border-color": "#a5b4fc",
              width: 34,
              height: 34
            }
          },
          {
            selector: "node.kind-document",
            style: {
              "background-color": "#22c55e",
              "border-color": "#86efac",
              width: 42,
              height: 42
            }
          },
          {
            selector: "node.kind-entity",
            style: {
              "background-color": "#f97316",
              "border-color": "#fdba74",
              width: 36,
              height: 36
            }
          },
          {
            selector: "node.kind-principal, node.kind-tenant",
            style: {
              "background-color": "#6366f1",
              "border-color": "#a5b4fc"
            }
          },
          {
            selector: "node.selected",
            style: {
              "border-width": 4,
              "border-color": "#111827",
              "overlay-opacity": 0.08,
              "overlay-color": "#4f46e5"
            }
          },
          {
            selector: "edge",
            style: {
              width: 1.8,
              "curve-style": "bezier",
              "target-arrow-shape": "triangle",
              "target-arrow-color": "#4f46e5",
              "line-color": "#93c5fd",
              "arrow-scale": 0.85,
              label: "data(label)",
              "font-size": 9,
              color: "#334155",
              "text-background-color": "#ffffff",
              "text-background-opacity": 0.85,
              "text-background-padding": 2
            }
          },
          {
            selector: "edge.selected",
            style: {
              width: 3,
              "line-color": "#1d4ed8",
              "target-arrow-color": "#1d4ed8"
            }
          }
        ],
        layout: graphLayoutOptions(nodes.length, visibleEdges.length, selectedNodeId),
        wheelSensitivity: 0.2,
        minZoom: 0.2,
        maxZoom: 2.8
      });
      graphInstances.set(containerId, cy);
      const nodeSet = new Set(nodes.map((node) => node.id));
      if (selectedNodeId && nodeSet.has(selectedNodeId)) {
        cy.$id(selectedNodeId).addClass("selected");
      }
      cy.once("layoutstop", () => {
        if (selectedNodeId && nodeSet.has(selectedNodeId)) {
          const selected = cy.$id(selectedNodeId);
          if (selected) {
            cy.center(selected);
            cy.fit(selected, 80);
          }
        } else {
          cy.fit(undefined, 48);
        }
      });
      cy.on("tap", "node", (event) => {
        const nodeId = event.target.id();
        cy.nodes().removeClass("selected");
        event.target.addClass("selected");
        if (typeof onSelectNode === "function") {
          onSelectNode(nodeId);
        }
      });
      cy.on("tap", "edge", (event) => {
        cy.edges().removeClass("selected");
        event.target.addClass("selected");
        if (typeof onSelectNode === "function") {
          const sourceId = event.target.data("source");
          if (sourceId && nodeSet.has(sourceId)) {
            onSelectNode(sourceId);
          }
        }
      });
      cy.on("tap", (event) => {
        if (event.target === cy) {
          cy.nodes().removeClass("selected");
          cy.edges().removeClass("selected");
        }
      });
      cy.on("mouseover", "node", (event) => event.target.style("z-index", 999));
      cy.on("mouseout", "node", (event) => event.target.style("z-index", 0));
      return;
    }

    const width = 840;
    const height = 520;
    const radius = Math.max(Math.min(width, height) / 2 - 96, 140);
    const centerX = width / 2;
    const centerY = height / 2;
    const orderedNodes = [...nodes].sort((left, right) => String(left.kind || left.label || left.id).localeCompare(String(right.kind || right.label || right.id)));
    const positions = new Map();
    orderedNodes.forEach((node, index) => {
      const angle = (Math.PI * 2 * index) / orderedNodes.length - Math.PI / 2;
      const x = Math.round((centerX + Math.cos(angle) * radius) * 100) / 100;
      const y = Math.round((centerY + Math.sin(angle) * radius) * 100) / 100;
      positions.set(node.id, { x, y });
    });

    const nodeById = new Map(orderedNodes.map((node) => [node.id, node]));
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
    svg.setAttribute("class", "graph-svg");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Graph visualization");

    const defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
    const marker = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    marker.setAttribute("id", "graph-arrow");
    marker.setAttribute("viewBox", "0 0 10 10");
    marker.setAttribute("refX", "8");
    marker.setAttribute("refY", "5");
    marker.setAttribute("markerWidth", "6");
    marker.setAttribute("markerHeight", "6");
    marker.setAttribute("orient", "auto-start-reverse");
    const markerPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    markerPath.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
    markerPath.setAttribute("fill", "rgba(79, 70, 229, 0.75)");
    marker.appendChild(markerPath);
    defs.appendChild(marker);
    svg.appendChild(defs);

    visibleEdges.forEach((edge) => {
      const from = positions.get(edge.fromId);
      const to = positions.get(edge.toId);
      if (!from || !to) return;
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", from.x);
      line.setAttribute("y1", from.y);
      line.setAttribute("x2", to.x);
      line.setAttribute("y2", to.y);
      line.setAttribute("class", "graph-edge");
      line.setAttribute("marker-end", "url(#graph-arrow)");
      svg.appendChild(line);
    });

    orderedNodes.forEach((node) => {
      const pos = positions.get(node.id);
      if (!pos) return;
      const group = document.createElementNS("http://www.w3.org/2000/svg", "g");
      group.setAttribute("class", `graph-node kind-${String(node.kind || "").toLowerCase()}${selectedNodeId && selectedNodeId === node.id ? " selected" : ""}`);
      group.setAttribute("transform", `translate(${pos.x},${pos.y})`);
      group.addEventListener("click", () => {
        if (typeof onSelectNode === "function") {
          onSelectNode(node.id);
        }
      });

      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("r", String(node.kind === "DOCUMENT" ? 24 : node.kind === "ENTITY" ? 20 : 16));
      circle.setAttribute("class", "graph-node-circle");
      group.appendChild(circle);

      const title = document.createElementNS("http://www.w3.org/2000/svg", "text");
      title.setAttribute("class", "graph-node-label");
      title.setAttribute("text-anchor", "middle");
      title.setAttribute("y", "40");
      title.textContent = String(node.label || node.id);
      group.appendChild(title);

      const kind = document.createElementNS("http://www.w3.org/2000/svg", "text");
      kind.setAttribute("class", "graph-node-kind");
      kind.setAttribute("text-anchor", "middle");
      kind.setAttribute("y", "-30");
      kind.textContent = String(node.kind || "");
      group.appendChild(kind);

      svg.appendChild(group);
    });

    target.appendChild(svg);
  }

  function renderGraphInspector(containerId, subgraph, selectedNodeId, onSelectNode) {
    const nodes = Array.isArray(subgraph?.nodes) ? subgraph.nodes : [];
    const edges = Array.isArray(subgraph?.edges) ? subgraph.edges : [];
    const selected = nodes.find((node) => node.id === selectedNodeId) || nodes[0];
    if (!selected) {
      renderDataCards(containerId, []);
      return;
    }
    const inbound = edges.filter((edge) => edge.toId === selected.id).slice(0, 4).map((edge) => `${edge.relationType} ← ${edge.fromId}`);
    const outbound = edges.filter((edge) => edge.fromId === selected.id).slice(0, 4).map((edge) => `${edge.relationType} → ${edge.toId}`);
    const actionButtons = `
      <div class="chip-row">
        <button type="button" class="mini-chip graph-action" data-graph-action="root" data-node-id="${escapeHtml(selected.id)}">Use as Root</button>
        <button type="button" class="mini-chip graph-action" data-graph-action="focus" data-node-id="${escapeHtml(selected.id)}">Focus Path Start</button>
      </div>
    `;
    renderDataCards(containerId, [
      {
        title: selected.label || selected.id,
        meta: `${selected.kind || "-"} · ${selected.id}`,
        bodyHtml: `
          <div>${Object.entries(selected.properties || {}).map(([key, value]) => `${escapeHtml(key)}=${escapeHtml(value)}`).join(", ") || "No properties"}</div>
          ${actionButtons}
        `,
        chips: [selected.kind || "NODE", ...(selected.properties ? Object.keys(selected.properties).slice(0, 3) : [])]
      },
      {
        title: "Inbound",
        meta: `${inbound.length} edges`,
        body: inbound.join(", ") || "No inbound edges",
        chips: inbound.slice(0, 3)
      },
      {
        title: "Outbound",
        meta: `${outbound.length} edges`,
        body: outbound.join(", ") || "No outbound edges",
        chips: outbound.slice(0, 3)
      }
    ]);
    const target = document.getElementById(containerId);
    target?.querySelectorAll("[data-graph-action]").forEach((button) => {
      button.addEventListener("click", () => {
        const nodeId = button.getAttribute("data-node-id") || selected.id;
        const action = button.getAttribute("data-graph-action");
        if (typeof onSelectNode === "function" && nodeId) {
          onSelectNode(nodeId, action);
        }
      });
    });
  }

  function summarizeGraphStats(stats) {
    const nodes = Number(stats?.nodes || 0);
    const edges = Number(stats?.edges || 0);
    const documents = Number(stats?.documents || 0);
    const entities = Number(stats?.entities || 0);
    const principals = Number(stats?.principals || 0);
    const sources = Number(stats?.sources || 0);
    renderAnalyticsCards("graph-analytics", [
      { label: "Nodes", meta: stats?.tenantId || "All", value: formatCompactNumber(nodes), series: [nodes, edges, documents], foot: "Graph nodes" },
      { label: "Edges", meta: "Topology", value: formatCompactNumber(edges), series: [edges, entities, principals], foot: "Graph edges" },
      { label: "Documents", meta: "Corpus", value: formatCompactNumber(documents), series: [documents, sources], foot: "Document nodes" },
      { label: "Entities", meta: "Extracted", value: formatCompactNumber(entities), series: [entities, principals], foot: "Entity nodes" }
    ]);
    renderBarList("graph-bars", [
      { label: "Principals", valueLabel: `${principals} nodes`, ratio: Math.max(8, Math.min(100, principals ? 100 : 8)), warn: false },
      { label: "Sources", valueLabel: `${sources} nodes`, ratio: Math.max(8, Math.min(100, sources ? 100 : 8)), warn: false }
    ]);
  }

  function buildPathGraph(pathNodes) {
    const nodes = Array.isArray(pathNodes) ? pathNodes : [];
    const edges = [];
    nodes.slice(0, -1).forEach((node, index) => {
      const next = nodes[index + 1];
      if (!node || !next) return;
      edges.push({
        id: `${node.id}->${next.id}#PATH`,
        fromId: node.id,
        toId: next.id,
        relationType: "PATH",
        tenantId: node.tenantId || next.tenantId || ""
      });
    });
    return {
      centerNodeId: nodes[0]?.id || "",
      nodes,
      edges
    };
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
    let latestStats = null;
    let latestHealth = null;

    function renderOverviewCharts() {
      if (!latestStats || !latestHealth) {
        return;
      }
      const provider = latestHealth.providerTelemetry || latestStats.providerTelemetry;
      renderAnalyticsCards("overview-analytics", [
        {
          label: "Index Size",
          meta: "Storage",
          value: formatBytes(latestStats.indexSizeBytes),
          series: [latestStats.docs, latestStats.chunks, latestStats.snapshotCount, latestStats.indexSizeBytes / 1024],
          foot: `${latestStats.snapshotCount} snapshots on disk`
        },
        {
          label: "Cache Hit Rate",
          meta: "Stats Cache",
          value: `${Number(latestStats.statsCacheHitRatePct || 0).toFixed(1)}%`,
          series: [
            latestStats.statsCacheHitCount,
            latestStats.statsCacheMissCount,
            latestStats.statsCacheEvictionCount,
            latestStats.statsCacheExpiredCount
          ],
          foot: `${formatCompactNumber(latestStats.statsCacheEntries)} cached entries`
        },
        {
          label: "Provider Requests",
          meta: "Global",
          value: formatCompactNumber(provider.requestCount),
          series: [
            provider.requestCount,
            provider.successCount,
            provider.failureCount,
            provider.retryCount
          ],
          foot: `${provider.failureCount} failures / ${provider.retryCount} retries`
        },
        {
          label: "P95 Latency",
          meta: "Providers",
          value: `${Number(provider.p95LatencyMillis || 0).toFixed(1)} ms`,
          series: provider.endpoints.slice(0, 6).map((endpoint) => endpoint.p95LatencyMillis || 0),
          foot: `${provider.endpoints.length} endpoints monitored`
        }
      ]);
      const maxLatency = Math.max(...provider.endpoints.map((endpoint) => endpoint.p95LatencyMillis || 0), 1);
      renderBarList(
        "overview-endpoints",
        provider.endpoints.slice(0, 6).map((endpoint) => ({
          label: endpoint.provider,
          valueLabel: `${Number(endpoint.p95LatencyMillis || 0).toFixed(1)} ms`,
          ratio: Math.max(6, Math.round(((endpoint.p95LatencyMillis || 0) / maxLatency) * 100)),
          warn: Boolean(endpoint.circuitOpen || endpoint.failureCount > 0)
        }))
      );
    }

    async function refreshStats() {
      const params = new URLSearchParams();
      if (currentTenant()) {
        params.set("tenantId", currentTenant());
      }
      if (currentWindow() > 0) {
        params.set("recentProviderWindowMillis", String(currentWindow()));
      }
      const stats = await request(`/stats?${params.toString()}`, { method: "GET" });
      latestStats = stats;
      renderJson("output-stats", stats);
      setText("stat-tenant", stats.tenantId || currentTenant() || "all");
      setText("stat-docs", stats.docs);
      setText("stat-chunks", stats.chunks);
      renderOverviewCharts();
      setNotice("overview-notice", "stats refreshed", false);
    }

    async function refreshHealth() {
      const params = new URLSearchParams();
      params.set("detailed", "true");
      if (currentWindow() > 0) {
        params.set("recentProviderWindowMillis", String(currentWindow()));
      }
      const health = await request(`/provider-health?${params.toString()}`, { method: "GET" });
      latestHealth = health;
      renderJson("output-health", health);
      renderOverviewCharts();
      setNotice("overview-notice", "provider health refreshed", false);
    }

    async function refreshActivity() {
      const tasks = [];
      if (hasFeature("search-audit")) {
        tasks.push(
          request("/search-audit?limit=4", { method: "GET" }).then((items) =>
            items.map((entry) => ({
              timestamp: entry.timestampEpochMillis,
              title: `Search / ${entry.auditType}`,
              body: `${entry.query} (${entry.resultCount} hits)`,
              meta: `${entry.tenantId} · ${entry.role || "anonymous"} · topK ${entry.topK}`,
              warn: Boolean(entry.providerFallbackApplied),
              when: new Date(entry.timestampEpochMillis).toISOString()
            }))
          ).catch(() => [])
        );
      }
      if (hasFeature("job-history")) {
        tasks.push(
          request("/job-history?limit=4", { method: "GET" }).then((items) =>
            items.map((entry) => ({
              timestamp: entry.timestampEpochMillis,
              title: `Job / ${entry.jobType}`,
              body: `${entry.description}`,
              meta: `${entry.tenantId || "-"} · ${entry.status}${entry.message ? ` · ${entry.message}` : ""}`,
              warn: entry.status !== "SUCCESS",
              when: new Date(entry.timestampEpochMillis).toISOString()
            }))
          ).catch(() => [])
        );
      }
      const groups = await Promise.all(tasks);
      const merged = groups
        .flat()
        .sort((a, b) => b.timestamp - a.timestamp)
        .slice(0, 6);
      renderActivityList("overview-activity", merged);
    }

    document.getElementById("btn-stats")?.addEventListener("click", () => run("overview-notice", refreshStats));
    document.getElementById("btn-health")?.addEventListener("click", () => run("overview-notice", refreshHealth));
    run("overview-notice", async () => {
      await refreshStats();
      await refreshHealth();
      await refreshActivity();
    });
  }

  function initSearch() {
    let lastSearchResponse = null;

    function currentThresholdValues() {
      return {
        finalConfidence: parseOptionalNumber(document.getElementById("search-final-confidence")?.value) ?? 0.45,
        topHitScore: parseOptionalNumber(document.getElementById("search-top-hit-score")?.value) ?? 0.03,
        exactMatchOnly: Boolean(document.getElementById("search-exact-match")?.checked),
        compareMode: document.getElementById("search-exact-compare")?.value || "nori",
        combineMode: document.getElementById("search-exact-combine")?.value || "and"
      };
    }

    function updateThresholdSummary() {
      const summary = document.getElementById("search-threshold-summary");
      if (!summary) {
        return;
      }
      const thresholds = currentThresholdValues();
      summary.textContent = formatThresholdSummary(
        thresholds.finalConfidence,
        thresholds.topHitScore,
        thresholds.exactMatchOnly
      );
    }

    function applyThresholdPreset(name) {
      const finalConfidence = document.getElementById("search-final-confidence");
      const topHitScore = document.getElementById("search-top-hit-score");
      if (!finalConfidence || !topHitScore) {
        return;
      }
      if (name === "strict") {
        finalConfidence.value = "0.55";
        topHitScore.value = "0.05";
      } else if (name === "balanced") {
        finalConfidence.value = "0.45";
        topHitScore.value = "0.03";
      } else if (name === "permissive") {
        finalConfidence.value = "0.25";
        topHitScore.value = "0.01";
      }
      updateThresholdSummary();
    }

    function renderSearchSummary(response) {
      const telemetry = response.telemetry || {};
      renderAnalyticsCards("search-analytics", [
        {
          label: "Hits",
          meta: "Results",
          value: formatCompactNumber(response.hits.length),
          series: response.hits.map((hit) => Number(hit.score || 0) * 10),
          foot: `${telemetry.executedQuery || response.query}`
        },
        {
          label: "Providers Used",
          meta: "Telemetry",
          value: formatCompactNumber((telemetry.providersUsed || []).length),
          series: [
            (telemetry.providersUsed || []).length,
            telemetry.providerFallbackApplied ? 1 : 0,
            telemetry.correctiveRetryApplied ? 1 : 0,
            telemetry.queryRewriteApplied ? 1 : 0
          ],
          foot: telemetry.providerFallbackApplied ? "Fallback applied" : "Primary path"
        },
        {
          label: "Top Score",
          meta: "Ranking",
          value: response.hits.length ? Number(response.hits[0].score || 0).toFixed(3) : "0.000",
          series: response.hits.slice(0, 8).map((hit) => Number(hit.score || 0) * 10),
          foot: `${response.hits.length} hits returned`
        },
        {
          label: "Notes",
          meta: "Telemetry",
          value: formatCompactNumber((telemetry.notes || []).length),
          series: (telemetry.notes || []).map((_, index) => index + 1),
          foot: telemetry.providerFallbackReason || "No fallback reason"
        }
      ]);
      const providerCounts = (response.providerTelemetry?.endpoints || []).map((endpoint) => ({
        label: endpoint.provider,
        valueLabel: `${Number(endpoint.avgLatencyMillis || 0).toFixed(1)} ms`,
        ratio: Math.max(
          8,
          Math.round(
            ((endpoint.avgLatencyMillis || 0) /
              Math.max(...response.providerTelemetry.endpoints.map((item) => item.avgLatencyMillis || 0), 1)) *
              100
          )
        ),
        warn: Boolean(endpoint.failureCount > 0 || endpoint.circuitOpen)
      }));
      renderBarList("search-provider-bars", providerCounts);
      renderDataCards(
        "search-hit-cards",
        response.hits.slice(0, 6).map((hit) => ({
          title: `${hit.docId} / ${hit.chunkId}`,
          meta: `score ${Number(hit.score || 0).toFixed(3)}`,
          body: hit.text || "(no preview text)",
          chips: [
            hit.contentKind || "text",
            hit.page != null ? `page ${hit.page}` : "no page",
            hit.sourceUri || "no source"
          ]
        }))
      );
      renderLineChart(
        "search-score-chart",
        response.hits.slice(0, 12).map((hit) => Number(hit.score || 0)),
        {
          title: "Hit Score Curve",
          subtitle: "Top ranked hit scores in descending order",
          leftLabel: "Rank 1",
          rightLabel: `Rank ${Math.min(response.hits.length, 12)}`,
          valueFormatter: (value) => Number(value || 0).toFixed(3)
        }
      );
    }

    function renderExactMatchDebug(response) {
      const debug = response?.exactMatchDebug;
      if (!debug) {
        renderDataCards("search-exact-debug-cards", []);
        return;
      }
      renderDataCards(
        "search-exact-debug-cards",
        [
          {
            title: "Mode",
            meta: `${debug.compareMode}/${debug.combineMode}`,
            body: `enabled=${debug.enabled} · matched hits=${debug.matchedHitCount} · filtered hits=${debug.filteredHitCount}`,
            chips: ["exact mode", debug.compareMode, debug.combineMode]
          },
          {
            title: "Query Tokens",
            meta: "Normalized terms",
            body: debug.queryTokens.join(" ") || "(none)",
            chips: debug.queryTokens.length ? debug.queryTokens.map((token) => `query:${token}`) : ["-"]
          },
          ...(debug.hits || []).slice(0, 6).map((hit) => ({
            title: `${hit.docId} / ${hit.chunkId}`,
            meta: `matched ${hit.matchedTokens.length}`,
            bodyHtml:
              `<div>${highlightTokens(hit.candidateTokens.slice(0, 20).join(" "), hit.matchedTokens)}</div>` +
              `<div class="diff-summary">matched: ${escapeHtml(hit.matchedTokens.join(", ") || "-")}</div>`,
            chips: hit.matchedTokens.length ? hit.matchedTokens.map((token) => `match:${token}`) : ["no match"]
          }))
        ]
      );
    }

    function exactMatchTokensFromResponse(response) {
      const debug = response?.exactMatchDebug;
      return {
        queryTokens: debug?.queryTokens || [],
        hitTokens: uniqueSortedTokens((debug?.hits || []).flatMap((hit) => hit.matchedTokens || [])),
        debug
      };
    }

    function searchPayload() {
      return {
        tenantId: currentTenant(),
        principals: parseList(document.getElementById("search-principals")?.value),
        query: document.getElementById("search-query")?.value?.trim() || "",
        topK: Number(document.getElementById("search-topk")?.value || 5),
        filter: parseMap(document.getElementById("search-filter")?.value),
        searchNoMatchMinFinalConfidence: parseOptionalNumber(document.getElementById("search-final-confidence")?.value),
        searchNoMatchMinTopHitScore: parseOptionalNumber(document.getElementById("search-top-hit-score")?.value),
        searchExactMatchOnly: Boolean(document.getElementById("search-exact-match")?.checked),
        searchExactMatchMode: `${document.getElementById("search-exact-compare")?.value || "nori"}-${document.getElementById("search-exact-combine")?.value || "and"}`,
        providerHealthDetail: true,
        recentProviderWindowMillis: currentWindow() > 0 ? currentWindow() : null
      };
    }

    async function runSearch() {
      const response = await request("/search", {
        method: "POST",
        body: JSON.stringify(searchPayload())
      });
      lastSearchResponse = response;
      renderJson("output-search", response);
      renderSearchSummary(response);
      renderExactMatchDebug(response);
      setNotice(
        "search-notice",
        response.hits.length
          ? `search completed with ${response.hits.length} hits`
          : "no sufficiently strong matches found",
        response.hits.length === 0
      );
    }

    async function runDiagnostics() {
      const response = await request("/diagnose-search", {
        method: "POST",
        body: JSON.stringify(searchPayload())
      });
      lastSearchResponse = null;
      renderJson("output-diagnostics", response);
      renderExactMatchDebug(null);
      renderDataCards(
        "search-diagnostic-cards",
        [
          {
            title: "Tenant Coverage",
            meta: "Corpus",
            body: `tenantDocs=${response.tenantDocs}, lexicalWithAcl=${response.lexicalMatchesWithAcl}, vectorWithAcl=${response.vectorMatchesWithAcl}`,
            chips: [`lexical ${response.lexicalMatchesWithAcl}`, `vector ${response.vectorMatchesWithAcl}`]
          },
          {
            title: "ACL Delta",
            meta: "Filter effect",
            body: `without ACL lexical=${response.lexicalMatchesWithoutAcl}, vector=${response.vectorMatchesWithoutAcl}`,
            chips: [
              `lexical drop ${response.lexicalMatchesWithoutAcl - response.lexicalMatchesWithAcl}`,
              `vector drop ${response.vectorMatchesWithoutAcl - response.vectorMatchesWithAcl}`
            ]
          },
          {
            title: "Sample Docs",
            meta: "Diagnostics",
            body: `lexical=${(response.lexicalSampleDocIdsWithAcl || []).join(", ") || "-"}`,
            chips: (response.vectorSampleDocIdsWithAcl || []).slice(0, 4)
          },
          {
            title: "Provider Notes",
            meta: "Telemetry",
            body: response.telemetry.providerFallbackReason || "No fallback reason",
            chips: response.telemetry.providersUsed || []
          }
        ]
      );
      setNotice("search-notice", "diagnostics completed", false);
    }

    document.getElementById("btn-search")?.addEventListener("click", () => run("search-notice", runSearch));
    document.getElementById("btn-diagnose")?.addEventListener("click", () => run("search-notice", runDiagnostics));
    document.querySelectorAll("[data-threshold-preset]")?.forEach((button) => {
      button.addEventListener("click", () => applyThresholdPreset(button.getAttribute("data-threshold-preset") || "balanced"));
    });
    document.getElementById("search-final-confidence")?.addEventListener("input", updateThresholdSummary);
    document.getElementById("search-top-hit-score")?.addEventListener("input", updateThresholdSummary);
    document.getElementById("search-exact-match")?.addEventListener("change", updateThresholdSummary);
    document.getElementById("search-exact-compare")?.addEventListener("change", updateThresholdSummary);
    document.getElementById("search-exact-combine")?.addEventListener("change", updateThresholdSummary);
    document.getElementById("btn-copy-exact-query")?.addEventListener("click", async () => {
      const { queryTokens } = exactMatchTokensFromResponse(lastSearchResponse);
      if (!queryTokens.length) {
        setNotice("search-notice", "no query tokens to copy", true);
        return;
      }
      try {
        await navigator.clipboard.writeText(queryTokens.join("\n"));
        setNotice("search-notice", "query tokens copied", false);
      } catch (_error) {
        setNotice("search-notice", "clipboard unavailable", true);
      }
    });
    document.getElementById("btn-copy-exact-hits")?.addEventListener("click", async () => {
      const { hitTokens } = exactMatchTokensFromResponse(lastSearchResponse);
      if (!hitTokens.length) {
        setNotice("search-notice", "no hit tokens to copy", true);
        return;
      }
      try {
        await navigator.clipboard.writeText(hitTokens.join("\n"));
        setNotice("search-notice", "hit tokens copied", false);
      } catch (_error) {
        setNotice("search-notice", "clipboard unavailable", true);
      }
    });
    document.getElementById("btn-download-exact-json")?.addEventListener("click", () => {
      const { debug } = exactMatchTokensFromResponse(lastSearchResponse);
      if (!debug) {
        setNotice("search-notice", "no exact debug data to download", true);
        return;
      }
      downloadTextFile("exact-match-debug.json", `${JSON.stringify(debug, null, 2)}\n`, "application/json;charset=utf-8");
      setNotice("search-notice", "exact debug JSON downloaded", false);
    });
    document.getElementById("btn-download-exact-csv")?.addEventListener("click", () => {
      const debug = lastSearchResponse?.exactMatchDebug;
      if (!debug) {
        setNotice("search-notice", "no exact debug data to download", true);
        return;
      }
      const rows = [
        ["compareMode", "combineMode", "docId", "chunkId", "matchedTokens", "candidateTokens"],
        ...(debug.hits || []).map((hit) => [
          debug.compareMode,
          debug.combineMode,
          hit.docId,
          hit.chunkId,
          (hit.matchedTokens || []).join(" | "),
          (hit.candidateTokens || []).join(" | ")
        ])
      ];
      const csv = rows
        .map((row) =>
          row
            .map((cell) => `"${String(cell ?? "").replaceAll("\"", '""')}"`)
            .join(",")
        )
        .join("\n");
      downloadTextFile("exact-match-debug.csv", `${csv}\n`, "text/csv;charset=utf-8");
      setNotice("search-notice", "exact debug CSV downloaded", false);
    });
    updateThresholdSummary();
  }

  function initTextIngest() {
    let resultMode = "all";

    function resultFilter() {
      return document.getElementById("ingest-result-filter")?.value || "all";
    }

    function matchesFilter(status) {
      const filter = resultFilter();
      return filter === "all" || filter === status;
    }

    let traceEvents = [];
    let lastOutput = null;

    function setTrace(events) {
      traceEvents = events;
      renderTimeline("ingest-progress-log", traceEvents);
    }

    function syncResultView() {
      const allPanel = document.getElementById("ingest-result-panel-all");
      const changedPanel = document.getElementById("ingest-result-panel-changed");
      const allTab = document.getElementById("ingest-tab-all");
      const changedTab = document.getElementById("ingest-tab-changed");
      if (allPanel) allPanel.hidden = resultMode === "changed";
      if (changedPanel) changedPanel.hidden = resultMode !== "changed";
      allTab?.classList.toggle("active", resultMode === "all");
      changedTab?.classList.toggle("active", resultMode === "changed");
    }

    function renderChangedOnlyCards(response) {
      const status = response.status || "unknown";
      setTabCount("ingest-tab-changed-count", status === "changed" ? 1 : 0);
      if (status !== "changed") {
        renderDataCards("ingest-result-cards-changed", []);
        return;
      }
      renderDataCards(
        "ingest-result-cards-changed",
        [
          {
            title: status,
            meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
            body: response.message || "text ingest completed",
            bodyHtml: renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary),
            className: "status-changed",
            chips: ["text ingest", "changed"]
          }
        ]
      );
    }

    function renderTextIngestResults(response) {
      lastOutput = response;
      const status = response.status || "unknown";
      setTabCount("ingest-tab-changed-count", status === "changed" ? 1 : 0);
      renderDataCards(
        "ingest-result-cards",
        matchesFilter(status)
          ? [
              {
                title: status,
                meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
                body: response.message || "text ingest completed",
                bodyHtml: status === "changed"
                  ? renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary)
                  : escapeHtml(response.message || "text ingest completed"),
                className:
                  status === "changed"
                    ? "status-changed"
                    : status === "skipped"
                      ? "status-skipped"
                      : status === "failed"
                        ? "status-failed"
                        : "status-ingested",
                chips: [
                  "text ingest",
                  status
                ]
              }
            ]
          : []
      );
      renderChangedOnlyCards(response);
    }

    function renderTextIngestSummary(response) {
      renderDataCards(
        "ingest-summary",
        [
          {
            title: response.status || "unknown",
            meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
            body: response.message || "text ingest complete",
            bodyHtml: response.status === "changed"
              ? renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary)
              : escapeHtml(response.message || "text ingest complete"),
            className:
              response.status === "changed"
                ? "status-changed"
                : response.status === "skipped"
                  ? "status-skipped"
                  : "status-ingested",
            chips: [
              Boolean(document.getElementById("ingest-incremental")?.checked) ? "incremental on" : "incremental off",
              "text ingest",
              response.status || "unknown"
            ]
          }
        ]
      );
    }

    async function ingestText() {
      const docId = document.getElementById("ingest-doc-id")?.value?.trim() || "";
      const text = document.getElementById("ingest-text")?.value || "";
      const acl = parseList(document.getElementById("ingest-acl")?.value);
      const metadata = parseMap(document.getElementById("ingest-metadata")?.value);
      const incremental = Boolean(document.getElementById("ingest-incremental")?.checked);
      setTrace([
        {
          phase: "queued",
          when: "now",
          message: "validating text ingest request",
          source: "text",
          kind: "request",
          status: "pending"
        },
        {
          phase: "validated",
          when: "now",
          message: `docId ${docId || "(missing)"} / acl ${acl.length} principals / metadata ${Object.keys(metadata).length} entries`,
          source: "text",
          kind: "validation",
          status: incremental ? "incremental on" : "incremental off"
        },
        {
          phase: "dispatching",
          when: "now",
          message: `sending ${text.length} characters to ingest pipeline`,
          source: "text",
          kind: "request",
          status: "running"
        }
      ]);
      const response = await request("/ingest", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docId,
          text,
          acl,
          metadata,
          incrementalIngest: incremental
        })
      });
      renderJson("output-ingest", response);
      setTrace([
        ...traceEvents,
        {
          phase: "normalized",
          when: "now",
          message: "text normalized and hashed",
          source: "text",
          kind: "normalize",
          status: "running"
        },
        {
          phase: "upserted",
          when: "now",
          message: response.status === "skipped" ? "no upsert required" : "document stored",
          source: "text",
          kind: "upsert",
          status: response.status || "unknown",
          level: response.status === "failed" ? "error" : response.status === "skipped" ? "skip" : "ok"
        },
        {
          phase: "completed",
          when: "now",
          message: response.message || `status: ${response.status || "processed"}`,
          source: "text",
          kind: "result",
          status: response.status || "unknown",
          level: response.status === "failed" ? "error" : response.status === "skipped" ? "skip" : "ok"
        }
      ]);
      renderTextIngestSummary(response);
      renderTextIngestResults(response);
      setNotice("ingest-notice", response.message || `text document ${response.status || "processed"}`, false);
    }

    document.getElementById("btn-ingest")?.addEventListener("click", () => run("ingest-notice", ingestText));
    document.getElementById("ingest-tab-all")?.addEventListener("click", () => {
      resultMode = "all";
      syncResultView();
      if (lastOutput) renderTextIngestResults(lastOutput);
    });
    document.getElementById("ingest-tab-changed")?.addEventListener("click", () => {
      resultMode = "changed";
      syncResultView();
      if (lastOutput) renderTextIngestResults(lastOutput);
    });
    document.getElementById("ingest-result-filter")?.addEventListener("change", () => {
      if (lastOutput) renderTextIngestResults(lastOutput);
    });
    syncResultView();
  }

  function initFileIngest() {
    let resultMode = "all";

    function resultFilter() {
      return document.getElementById("upload-result-filter")?.value || "all";
    }

    function matchesFilter(status) {
      const filter = resultFilter();
      return filter === "all" || filter === status;
    }

    let traceEvents = [];
    let lastOutput = null;

    function setTrace(events) {
      traceEvents = events;
      renderTimeline("upload-progress-log", traceEvents);
    }

    function syncResultView() {
      const allPanel = document.getElementById("upload-result-panel-all");
      const changedPanel = document.getElementById("upload-result-panel-changed");
      const allTab = document.getElementById("upload-tab-all");
      const changedTab = document.getElementById("upload-tab-changed");
      if (allPanel) allPanel.hidden = resultMode === "changed";
      if (changedPanel) changedPanel.hidden = resultMode !== "changed";
      allTab?.classList.toggle("active", resultMode === "all");
      changedTab?.classList.toggle("active", resultMode === "changed");
    }

    function renderChangedOnlyCards(response) {
      const status = response.status || "unknown";
      setTabCount("upload-tab-changed-count", status === "changed" ? 1 : 0);
      if (status !== "changed") {
        renderDataCards("upload-result-cards-changed", []);
        return;
      }
      renderDataCards(
        "upload-result-cards-changed",
        [
          {
            title: status,
            meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
            body: response.message || "file ingest completed",
            bodyHtml: renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary),
            className: "status-changed",
            chips: ["file ingest", "changed"]
          }
        ]
      );
    }

    function renderFileIngestResults(response) {
      lastOutput = response;
      const status = response.status || "unknown";
      setTabCount("upload-tab-changed-count", status === "changed" ? 1 : 0);
      renderDataCards(
        "upload-result-cards",
        matchesFilter(status)
          ? [
              {
                title: status,
                meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
                body: response.message || "file ingest completed",
                bodyHtml: status === "changed"
                  ? renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary)
                  : escapeHtml(response.message || "file ingest completed"),
                className:
                  status === "changed"
                    ? "status-changed"
                    : status === "skipped"
                      ? "status-skipped"
                      : status === "failed"
                        ? "status-failed"
                        : "status-ingested",
                chips: [
                  "file ingest",
                  status
                ]
              }
            ]
          : []
      );
      renderChangedOnlyCards(response);
    }

    function renderFileIngestSummary(response) {
      renderDataCards(
        "upload-summary",
        [
          {
            title: response.status || "unknown",
            meta: `${response.tenantId || "-"} · ${response.docId || "-"}`,
            body: response.message || "file ingest complete",
            bodyHtml: response.status === "changed"
              ? renderPreviewDiff(response.previousPreview, response.currentPreview, response.changeSummary)
              : escapeHtml(response.message || "file ingest complete"),
            className:
              response.status === "changed"
                ? "status-changed"
                : response.status === "skipped"
                  ? "status-skipped"
                  : "status-ingested",
            chips: [
              Boolean(document.getElementById("upload-incremental")?.checked) ? "incremental on" : "incremental off",
              "file ingest",
              response.status || "unknown"
            ]
          }
        ]
      );
    }

    async function uploadFile() {
      const docId = document.getElementById("upload-doc-id")?.value?.trim() || "";
      const incremental = Boolean(document.getElementById("upload-incremental")?.checked);
      setTrace([
        {
          phase: "queued",
          when: "now",
          message: "preparing upload payload",
          source: "file",
          kind: "request",
          status: "pending"
        },
        {
          phase: "parsing",
          when: "now",
          message: "detecting file type and parsing content",
          source: "file",
          kind: "parse",
          status: "running"
        }
      ]);
      const file = document.getElementById("upload-file")?.files?.[0];
      if (!file) {
        throw new Error("upload file is required");
      }
      const formData = new FormData();
      formData.set("tenantId", currentTenant());
      formData.set("docId", docId);
      parseList(document.getElementById("upload-acl")?.value).forEach((item) => formData.append("acl", item));
      formData.set("metadata", document.getElementById("upload-metadata")?.value || "");
      formData.set("incrementalIngest", String(incremental));
      formData.set("file", file);

      const response = await request("/ingest-file", {
        method: "POST",
        body: formData
      });
      renderJson("output-upload", response);
      setTrace([
        ...traceEvents,
        {
          phase: "normalized",
          when: "now",
          message: `${file.name} (${Math.max(1, Math.round(file.size / 1024))} KB) normalized`,
          source: "file",
          kind: "normalize",
          status: "running"
        },
        {
          phase: "validated",
          when: "now",
          message: `docId ${docId || "(missing)"} / incremental ${incremental ? "on" : "off"}`,
          source: "file",
          kind: "validation",
          status: "running"
        },
        {
          phase: "completed",
          when: "now",
          message: response.message || `status: ${response.status || "processed"}`,
          source: "file",
          kind: "result",
          status: response.status || "unknown",
          level: response.status === "failed" ? "error" : response.status === "skipped" ? "skip" : "ok"
        }
      ]);
      renderFileIngestSummary(response);
      renderFileIngestResults(response);
      setNotice("upload-notice", response.message || `file ${response.status || "processed"}`, false);
    }

    document.getElementById("btn-upload")?.addEventListener("click", () => run("upload-notice", uploadFile));
    document.getElementById("upload-tab-all")?.addEventListener("click", () => {
      resultMode = "all";
      syncResultView();
      if (lastOutput) renderFileIngestResults(lastOutput);
    });
    document.getElementById("upload-tab-changed")?.addEventListener("click", () => {
      resultMode = "changed";
      syncResultView();
      if (lastOutput) renderFileIngestResults(lastOutput);
    });
    document.getElementById("upload-result-filter")?.addEventListener("change", () => {
      if (lastOutput) renderFileIngestResults(lastOutput);
    });
    syncResultView();
  }

  function initWebIngest() {
    let activeController = null;
    let lastResult = null;
    let lastProgressEvents = [];

    function resultFilter() {
      return document.getElementById("web-result-filter")?.value || "all";
    }

    function filteredResults(response) {
      const results = response?.results || [];
      const filter = resultFilter();
      if (filter === "all") {
        return results;
      }
      if (filter === "changed") {
        return results.filter((item) => item.status === "changed");
      }
      return results.filter((item) => item.source === filter);
    }

    function resultSummary(response) {
      return (response?.results || []).reduce((acc, item) => {
        const key = item.source || "unknown";
        acc[key] = (acc[key] || 0) + 1;
        return acc;
      }, {});
    }

    function renderWebIngestProgress(response) {
      const counts = resultSummary(response);
      const visibleResults = filteredResults(response);
      const allResults = response.results || [];

      renderDataCards("web-progress-summary", [
        {
          title: "Seed URLs",
          meta: "Crawler input",
          body: `${(response.urls || []).length} urls`,
          chips: (response.urls || []).slice(0, 3)
        },
        {
          title: "Crawled Pages",
          meta: "Fetch result",
          body: `${response.crawledPages || 0} pages`,
          chips: [
            `ingested ${response.ingestedPages || 0}`,
            `changed ${response.changedPages || 0}`,
            `failures ${(response.failures || []).length}`
          ]
        },
        {
          title: "Results",
          meta: `Showing ${resultFilter()}`,
          body: `${visibleResults.length} / ${allResults.length}`,
          chips: [
            `sitemap ${counts.sitemap || 0}`,
            `seed ${counts.seed || 0}`,
            `link ${counts.link || 0}`,
            `changed ${response.changedPages || 0}`,
            `skipped ${response.skippedPages || 0}`
          ]
        }
      ]);

      renderTimeline(
        "web-progress-log",
        (response.progress || []).map((event) => ({
          title: event.phase,
          when: event.current != null && event.total != null ? `${event.current}/${event.total}` : "stage",
          body: event.message,
          meta: event.url || "-",
          chips: [
            event.depth != null ? `depth ${event.depth}` : "depth n/a",
            event.phase
          ]
        }))
      );
    }

    function renderWebIngestResults(response) {
      const results = filteredResults(response);
      renderDataCards(
        "web-result-cards",
        results.map((item) => {
          const status = item.status || "unknown";
          const previousPreview = item.previousPreview ? escapeHtml(item.previousPreview) : "";
          const currentPreview = item.currentPreview ? escapeHtml(item.currentPreview) : "";
          const diffHtml = item.status === "changed" && (previousPreview || currentPreview)
            ? `
              <div class="diff-preview">
                <div class="diff-line diff-old"><span>Old</span><pre>${previousPreview || "(missing)"}</pre></div>
                <div class="diff-line diff-new"><span>New</span><pre>${currentPreview || "(missing)"}</pre></div>
              </div>
            `
            : "";
          return {
            title: item.title || item.url,
            meta: `${item.source || "unknown"} · ${status}`,
            body: item.message || item.url,
            bodyHtml: item.status === "changed" ? `${diffHtml}${item.changeSummary ? `<div class="diff-summary">${escapeHtml(item.changeSummary)}</div>` : ""}` : escapeHtml(item.message || item.url || ""),
            className:
              status === "changed"
                ? "status-changed"
                : status === "skipped"
                  ? "status-skipped"
                  : status === "failed"
                    ? "status-failed"
                    : "status-ingested",
            chips: [
              item.depth != null ? `depth ${item.depth}` : "depth n/a",
              item.docId || "no docId",
              item.url || "-"
            ]
          };
        })
      );
    }

    function splitSseBlock(block) {
      const lines = block
        .split(/\r?\n/)
        .map((line) => line.trimEnd())
        .filter(Boolean);
      const event = { event: "message", data: [] };

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event.event = line.slice(6).trim() || "message";
        } else if (line.startsWith("data:")) {
          event.data.push(line.slice(5).trimStart());
        }
      }

      return event.data.length ? { event: event.event, data: event.data.join("\n") } : null;
    }

    function webIngestPayload() {
      return {
        tenantId: currentTenant(),
        urls: parseList(document.getElementById("web-urls")?.value),
        allowedDomains: parseList(document.getElementById("web-allowed-domains")?.value),
        acl: parseList(document.getElementById("web-acl")?.value),
        metadata: parseMap(document.getElementById("web-metadata")?.value),
        respectRobotsTxt: Boolean(document.getElementById("web-respect-robots")?.checked),
        incrementalIngest: Boolean(document.getElementById("web-incremental")?.checked),
        sourceLoadProfile: document.getElementById("web-source-load-profile")?.value?.trim() || null,
        userAgent: document.getElementById("web-user-agent")?.value?.trim() || "AinsoftRagBot/1.0",
        maxPages: Number(document.getElementById("web-max-pages")?.value || 25),
        maxDepth: Number(document.getElementById("web-max-depth")?.value || 1),
        sameHostOnly: Boolean(document.getElementById("web-same-host")?.checked),
        charset: document.getElementById("web-charset")?.value?.trim() || "UTF-8"
      };
    }

    function requestUrl(path) {
    const url = new URL(`${config.apiBasePath}${path}`, window.location.origin);
    return { url: url.toString() };
  }

    function setRunning(isRunning) {
      const runButton = document.getElementById("btn-web-ingest");
      const cancelButton = document.getElementById("btn-web-cancel");
      if (runButton) {
        runButton.disabled = isRunning;
      }
      if (cancelButton) {
        cancelButton.disabled = !isRunning;
      }
    }

    function applyResultFilter() {
      if (!lastResult) {
        return;
      }
      renderJson("output-web-ingest", {
        ...lastResult,
        results: filteredResults(lastResult)
      });
      renderWebIngestResults(lastResult);
    }

    document.getElementById("web-result-filter")?.addEventListener("change", applyResultFilter);

    async function webIngest() {
      if (activeController) {
        activeController.abort();
      }
      const controller = new AbortController();
      activeController = controller;
      const payload = webIngestPayload();
      lastResult = null;
      lastProgressEvents = [];
      renderDataCards("web-progress-summary", []);
      renderTimeline("web-progress-log", []);
      renderJson("output-web-ingest", {});
      setRunning(true);
      setNotice("web-notice", "web ingest streaming...", false);

      const { url } = requestUrl("/web-ingest/stream");
      try {
        const response = await fetch(url, {
          method: "POST",
          credentials: "same-origin",
          headers: {
            Accept: "text/event-stream",
            "Content-Type": "application/json"
          },
          body: JSON.stringify(payload),
          signal: controller.signal
        });

        const contentType = response.headers.get("content-type") || "";
        if (!response.ok || !response.body) {
          throw new Error(`Request failed with ${response.status}`);
        }
        if (!contentType.includes("text/event-stream")) {
          throw new Error("Streaming response was not text/event-stream.");
        }

        const decoder = new TextDecoder();
        const reader = response.body.getReader();
        let buffer = "";
        let currentBlock = "";
        let finalResult = null;
        let sawTerminalEnvelope = false;

        const flushEvent = (parsedEvent) => {
          if (!parsedEvent) {
            return;
          }
          const event = JSON.parse(parsedEvent.data);
          if (event.type === "progress" && event.event) {
            lastProgressEvents.push(event.event);
            if (["sitemap", "robots", "fetch", "skip", "ingest-failed"].includes(event.event.phase)) {
              setNotice("web-notice", event.event.message || "web ingest issue detected", false);
            }
            renderDataCards("web-progress-summary", [
              {
                title: "Seed URLs",
                meta: "Crawler input",
                body: `${payload.urls.length} urls`,
                chips: payload.urls.slice(0, 3)
              },
              {
                title: "Crawled Pages",
                meta: "Fetch result",
                body: `${lastProgressEvents.filter((item) => item.phase === "crawl").length} pages`,
                chips: [
                  `ingested ${lastProgressEvents.filter((item) => item.phase === "ingest").length}`,
                  `changed ${lastProgressEvents.filter((item) => item.phase === "changed").length}`,
                  `skipped ${lastProgressEvents.filter((item) => item.phase === "skip-existing").length}`,
                  `failures ${lastProgressEvents.filter((item) => item.phase === "ingest-failed" || item.phase === "fetch" || item.phase === "skip").length}`
                ]
              },
              {
                title: "Progress Events",
                meta: "Timeline",
                body: `${lastProgressEvents.length} events`,
                chips: lastProgressEvents.slice(-3).map((item) => item.phase)
              }
            ]);

            renderTimeline(
              "web-progress-log",
              lastProgressEvents.map((item) => ({
                title: item.phase,
                when: item.current != null && item.total != null ? `${item.current}/${item.total}` : "stage",
                body: item.message,
                meta: item.url || "-",
                chips: [
                  item.depth != null ? `depth ${item.depth}` : "depth n/a",
                  item.phase
                ]
              }))
            );
            setNotice(
              "web-notice",
              `live: ${lastProgressEvents.some((item) => item.phase === "sitemap") ? "sitemap" : "crawl"} · ${lastProgressEvents.filter((item) => item.phase === "ingest").length} ingested`,
              false
            );
          } else if (event.type === "result" && event.response) {
            finalResult = event.response;
            sawTerminalEnvelope = true;
          } else if (event.type === "done" && event.response) {
            finalResult = event.response;
            sawTerminalEnvelope = true;
          } else if (event.type === "error") {
            throw new Error(event.message || "web ingest failed");
          }
        };

        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const chunks = buffer.split(/\r?\n/);
          buffer = chunks.pop() || "";
          for (const line of chunks) {
            if (line === "") {
              flushEvent(splitSseBlock(currentBlock));
              currentBlock = "";
            } else {
              currentBlock += `${line}\n`;
            }
          }
        }
        buffer += decoder.decode();
        const tail = [currentBlock, buffer].filter(Boolean).join("\n");
        if (tail) {
          flushEvent(splitSseBlock(tail));
        }

        if (controller.signal.aborted) {
          setNotice("web-notice", "web ingest canceled", false);
          return;
        }
        if (!finalResult) {
          finalResult = {
            status: sawTerminalEnvelope ? "partial" : "partial",
            crawledPages: lastProgressEvents.filter((item) => item.phase === "crawl").length,
            ingestedPages: lastProgressEvents.filter((item) => item.phase === "ingest").length,
            changedPages: lastProgressEvents.filter((item) => item.phase === "changed").length,
            skippedPages: lastProgressEvents.filter((item) => item.phase === "skip-existing").length,
            progress: lastProgressEvents,
            results: [],
            failures: []
          };
          setNotice(
            "web-notice",
            "web ingest finished without a final result envelope; showing partial progress",
            false
          );
        }

        lastResult = finalResult;
        renderJson("output-web-ingest", {
          ...finalResult,
          results: filteredResults(finalResult)
        });
        renderWebIngestProgress(finalResult);
        renderWebIngestResults(finalResult);
        setNotice(
          "web-notice",
          `sitemap ${finalResult.progress?.find((event) => event.phase === "sitemap") ? "checked" : "skipped"} · ${finalResult.crawledPages} crawled · ${finalResult.ingestedPages} ingested · ${finalResult.changedPages || 0} changed · ${finalResult.skippedPages || 0} skipped`,
          false
        );
      } catch (error) {
        if (error?.name === "AbortError") {
          setNotice("web-notice", "web ingest canceled", false);
          return;
        }
        throw error;
      } finally {
        if (activeController === controller) {
          setRunning(false);
          activeController = null;
        }
      }
    }

    document.getElementById("btn-web-ingest")?.addEventListener("click", () => run("web-notice", webIngest));
    document.getElementById("btn-web-cancel")?.addEventListener("click", () => {
      if (activeController) {
        activeController.abort();
      }
    });
  }

  function initDocuments() {
    function renderDocumentTimeline(detail) {
      renderTimeline(
        "document-timeline",
        (detail.chunks || []).map((chunk) => ({
          title: `${chunk.chunkId}${chunk.page != null ? ` / page ${chunk.page}` : ""}`,
          when: chunk.offsetStart != null && chunk.offsetEnd != null
            ? `${chunk.offsetStart}-${chunk.offsetEnd}`
            : "offset n/a",
          body: chunk.text || "(no stored text preview)",
          meta: `${chunk.contentKind || "text"} · ${chunk.sourceUri || "no source"}`,
          warn: chunk.contentKind && chunk.contentKind.toLowerCase().includes("binary"),
          chips: [
            chunk.contentKind || "text",
            chunk.page != null ? `page ${chunk.page}` : "no page"
          ]
        }))
      );
    }

    function renderDocumentSpotlight(detail) {
      renderDataCards("document-detail-cards", [
        {
          title: detail.docId,
          meta: detail.lastUpdatedIso || "updated n/a",
          body: `${detail.chunkCount} chunks · ${detail.sourceUris[0] || "no source"}`,
          chips: detail.acl.slice(0, 6)
        },
        {
          title: "Metadata",
          meta: `${Object.keys(detail.metadata || {}).length} fields`,
          body: Object.entries(detail.metadata || {})
            .slice(0, 6)
            .map(([key, value]) => `${key}=${value}`)
            .join(", ") || "No metadata",
          chips: Object.keys(detail.metadata || {}).slice(0, 6)
        },
        {
          title: "Sources",
          meta: `${detail.sourceUris.length} URIs`,
          body: detail.sourceUris.join(", ") || "No source URIs",
          chips: detail.sourceUris.slice(0, 4)
        },
        {
          title: "Chunk Layout",
          meta: "Pages and content",
          body: (detail.chunks || [])
            .slice(0, 4)
            .map((chunk) => `${chunk.chunkId}${chunk.page != null ? `@${chunk.page}` : ""}`)
            .join(", "),
          chips: Array.from(new Set((detail.chunks || []).map((chunk) => chunk.contentKind))).slice(0, 6)
        }
      ]);
    }

    function renderDocumentSummary(response) {
      const items = response.items || [];
      const totalChunks = items.reduce((sum, item) => sum + Number(item.chunkCount || 0), 0);
      const sourceCount = new Set(items.flatMap((item) => item.sourceUris || [])).size;
      const contentCounts = items.reduce((acc, item) => {
        (item.contentKinds || []).forEach((kind) => {
          acc[kind] = (acc[kind] || 0) + 1;
        });
        return acc;
      }, {});
      renderAnalyticsCards("document-analytics", [
        {
          label: "Documents",
          meta: "Loaded",
          value: formatCompactNumber(items.length),
          series: items.slice(0, 8).map((item) => item.chunkCount || 0),
          foot: `${response.totalCount} total rows`
        },
        {
          label: "Chunks",
          meta: "Aggregate",
          value: formatCompactNumber(totalChunks),
          series: items.slice(0, 8).map((item) => item.chunkCount || 0),
          foot: "Across current filter"
        },
        {
          label: "Sources",
          meta: "Distinct",
          value: formatCompactNumber(sourceCount),
          series: items.slice(0, 8).map((item) => (item.sourceUris || []).length),
          foot: "Distinct source URIs"
        },
        {
          label: "Content Kinds",
          meta: "Surface",
          value: formatCompactNumber(Object.keys(contentCounts).length),
          series: Object.values(contentCounts).slice(0, 8),
          foot: "Kinds across loaded docs"
        }
      ]);
      const maxCount = Math.max(...Object.values(contentCounts), 1);
      renderBarList(
        "document-bars",
        Object.entries(contentCounts).map(([kind, count]) => ({
          label: kind,
          valueLabel: `${count} docs`,
          ratio: Math.max(8, Math.round((count / maxCount) * 100)),
          warn: kind.toLowerCase().includes("binary")
        }))
      );
    }

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
      renderDocumentSummary(response);
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
      renderDocumentTimeline(detail);
      renderDocumentSpotlight(detail);
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
      renderTimeline("document-timeline", []);
      renderDataCards("document-detail-cards", []);
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

  function initGraph() {
    let lastSubgraph = null;
    let selectedNodeId = null;

    function currentGraphRelationFilter() {
      return document.getElementById("graph-relation-filter")?.value || "";
    }

    function syncSelection(nodeId, action) {
      if (!nodeId) return;
      selectedNodeId = nodeId;
      const rootInput = document.getElementById("graph-root-id");
      const pathFromInput = document.getElementById("graph-path-from");
      if (rootInput) rootInput.value = nodeId;
      if (pathFromInput) pathFromInput.value = nodeId;
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
        renderGraphInspector("graph-node-cards", lastSubgraph, selectedNodeId, syncSelection);
      }
      const noticeAction = action === "root" ? "root" : action === "focus" ? "path start" : "node";
      setNotice("graph-notice", `selected ${noticeAction} ${nodeId}`, false);
    }

    function renderGraphView(response) {
      lastSubgraph = response;
      selectedNodeId = response?.centerNodeId || response?.nodes?.[0]?.id || selectedNodeId;
      renderGraphCanvas("graph-canvas", response, selectedNodeId, syncSelection, currentGraphRelationFilter());
      renderGraphInspector("graph-node-cards", response, selectedNodeId, syncSelection);
    }

    async function loadStats() {
      const tenantId = currentTenant();
      const params = new URLSearchParams();
      if (tenantId) params.set("tenantId", tenantId);
      const response = await request(`/graph/stats?${params.toString()}`, { method: "GET" });
      renderJson("output-graph-stats", response);
      summarizeGraphStats(response);
      setNotice("graph-notice", `loaded graph stats for ${tenantId || "all tenants"}`, false);
    }

    async function loadDocumentGraph() {
      const tenantId = document.getElementById("graph-tenant-id")?.value?.trim() || currentTenant();
      const docId = document.getElementById("graph-doc-id")?.value?.trim() || "";
      const depth = document.getElementById("graph-depth")?.value || "1";
      if (!tenantId || !docId) throw new Error("tenantId and docId are required");
      const params = new URLSearchParams();
      params.set("depth", depth);
      const response = await request(`/graph/document/${encodeSegment(tenantId)}/${encodeSegment(docId)}?${params.toString()}`, { method: "GET" });
      renderJson("output-graph-document", response);
      renderGraphView(response);
      setNotice("graph-notice", `loaded document graph for ${docId}`, false);
    }

    async function loadEntityGraph() {
      const tenantId = document.getElementById("graph-tenant-id")?.value?.trim() || currentTenant();
      const entityId = document.getElementById("graph-entity-id")?.value?.trim() || "";
      const depth = document.getElementById("graph-depth")?.value || "1";
      if (!tenantId || !entityId) throw new Error("tenantId and entityId are required");
      const params = new URLSearchParams();
      params.set("depth", depth);
      const response = await request(`/graph/entity/${encodeSegment(tenantId)}/${encodeSegment(entityId)}?${params.toString()}`, { method: "GET" });
      renderJson("output-graph-entity", response);
      renderGraphView(response);
      setNotice("graph-notice", `loaded entity graph for ${entityId}`, false);
    }

    async function loadSubgraph() {
      const tenantId = document.getElementById("graph-tenant-id")?.value?.trim() || currentTenant();
      const rootId = document.getElementById("graph-root-id")?.value?.trim() || "";
      const depth = document.getElementById("graph-depth")?.value || "1";
      if (!tenantId || !rootId) throw new Error("tenantId and rootId are required");
      const params = new URLSearchParams();
      params.set("tenantId", tenantId);
      params.set("rootId", rootId);
      params.set("depth", depth);
      const response = await request(`/graph/subgraph?${params.toString()}`, { method: "GET" });
      renderJson("output-graph-subgraph", response);
      renderGraphView(response);
      setNotice("graph-notice", `loaded subgraph from ${rootId}`, false);
    }

    async function loadPath() {
      const tenantId = document.getElementById("graph-tenant-id")?.value?.trim() || currentTenant();
      const from = document.getElementById("graph-path-from")?.value?.trim() || selectedNodeId || "";
      const to = document.getElementById("graph-path-to")?.value?.trim() || "";
      const depth = document.getElementById("graph-depth")?.value || "4";
      if (!tenantId || !from || !to) throw new Error("tenantId, from, and to are required");
      const params = new URLSearchParams();
      params.set("tenantId", tenantId);
      params.set("from", from);
      params.set("to", to);
      params.set("depth", depth);
      const response = await request(`/graph/path?${params.toString()}`, { method: "GET" });
      const pathGraph = buildPathGraph(response);
      renderJson("output-graph-path", pathGraph);
      renderGraphView(pathGraph);
      setNotice("graph-notice", `loaded path from ${from} to ${to}`, false);
    }

    document.getElementById("btn-graph-stats")?.addEventListener("click", () => run("graph-notice", loadStats));
    document.getElementById("btn-graph-document")?.addEventListener("click", () => run("graph-notice", loadDocumentGraph));
    document.getElementById("btn-graph-entity")?.addEventListener("click", () => run("graph-notice", loadEntityGraph));
    document.getElementById("btn-graph-subgraph")?.addEventListener("click", () => run("graph-notice", loadSubgraph));
    document.getElementById("btn-graph-path")?.addEventListener("click", () => run("graph-notice", loadPath));
    document.getElementById("graph-relation-filter")?.addEventListener("input", () => {
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("graph-layout")?.addEventListener("change", () => {
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("graph-preset")?.addEventListener("change", (event) => {
      applyGraphPreset(event.target.value);
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("graph-scale")?.addEventListener("change", () => {
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("btn-graph-preset-explore")?.addEventListener("click", () => {
      applyGraphPreset("explore");
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("btn-graph-preset-dense")?.addEventListener("click", () => {
      applyGraphPreset("dense");
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("btn-graph-preset-path")?.addEventListener("click", () => {
      applyGraphPreset("path");
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });
    document.getElementById("btn-graph-preset-entity")?.addEventListener("click", () => {
      applyGraphPreset("entity");
      if (lastSubgraph) {
        renderGraphCanvas("graph-canvas", lastSubgraph, selectedNodeId, syncSelection, currentGraphRelationFilter());
      }
    });

    run("graph-notice", loadStats);
    const tenantInput = document.getElementById("graph-tenant-id");
    if (tenantInput && !tenantInput.value) {
      tenantInput.value = currentTenant();
    }
  }

  function initTenants() {
    function renderSnapshotTimeline(snapshots) {
      renderTimeline(
        "snapshot-timeline",
        (snapshots || []).map((snapshot) => ({
          title: snapshot.tag,
          when: snapshot.updatedAtIso,
          body: `snapshot tag ${snapshot.tag}`,
          meta: `updated ${snapshot.updatedAtIso}`,
          warn: false,
          chips: ["restore ready", "snapshot"]
        }))
      );
    }

    function renderTenantSpotlight(detail) {
      renderDataCards("tenant-detail-cards", [
        {
          title: detail.tenantId,
          meta: detail.lastCommitIso || "commit n/a",
          body: `${detail.docs} docs · ${detail.chunks} chunks`,
          chips: [`docs ${detail.docs}`, `chunks ${detail.chunks}`]
        },
        {
          title: "Recovery Points",
          meta: `${(detail.snapshots || []).length} snapshots`,
          body: (detail.snapshots || []).map((snapshot) => snapshot.tag).join(", ") || "No snapshots",
          chips: (detail.snapshots || []).slice(0, 5).map((snapshot) => snapshot.tag)
        },
        {
          title: "Top Documents",
          meta: `${(detail.documents || []).length} loaded`,
          body: (detail.documents || []).slice(0, 4).map((doc) => `${doc.docId} (${doc.chunkCount})`).join(", ") || "No documents",
          chips: (detail.documents || []).slice(0, 4).map((doc) => doc.docId)
        }
      ]);
    }

    function renderTenantSummary(response) {
      const items = response.items || [];
      const totalDocs = items.reduce((sum, item) => sum + Number(item.docs || 0), 0);
      const totalChunks = items.reduce((sum, item) => sum + Number(item.chunks || 0), 0);
      renderAnalyticsCards("tenant-analytics", [
        {
          label: "Tenants",
          meta: "Loaded",
          value: formatCompactNumber(items.length),
          series: items.slice(0, 8).map((item) => item.docs || 0),
          foot: "Current index tenants"
        },
        {
          label: "Docs",
          meta: "Aggregate",
          value: formatCompactNumber(totalDocs),
          series: items.slice(0, 8).map((item) => item.docs || 0),
          foot: "Across visible tenants"
        },
        {
          label: "Chunks",
          meta: "Aggregate",
          value: formatCompactNumber(totalChunks),
          series: items.slice(0, 8).map((item) => item.chunks || 0),
          foot: "Chunk footprint"
        },
        {
          label: "Snapshots",
          meta: "Recovery",
          value: formatCompactNumber((response.snapshots || []).length),
          series: (response.snapshots || []).slice(0, 8).map((_, index) => index + 1),
          foot: "Available snapshot tags"
        }
      ]);
      const maxChunks = Math.max(...items.map((item) => item.chunks || 0), 1);
      renderBarList(
        "tenant-bars",
        items.slice(0, 8).map((item) => ({
          label: item.tenantId,
          valueLabel: `${item.chunks} chunks`,
          ratio: Math.max(8, Math.round(((item.chunks || 0) / maxChunks) * 100)),
          warn: (item.docs || 0) === 0
        }))
      );
      renderSnapshotTimeline(response.snapshots || []);
    }

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
      renderTenantSummary(response);
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
      renderTenantSpotlight(response);
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
      renderAnalyticsCards("provider-analytics", [
        {
          label: "Global P95",
          meta: "Latency",
          value: `${Number(health.providerTelemetry.p95LatencyMillis || 0).toFixed(1)} ms`,
          series: history.history.slice(0, 8).reverse().map((entry) => entry.telemetry.p95LatencyMillis || 0),
          foot: `${health.providerTelemetry.endpoints.length} active endpoints`
        },
        {
          label: "Failures",
          meta: "History",
          value: formatCompactNumber(health.providerTelemetry.failureCount),
          series: history.history.slice(0, 8).reverse().map((entry) => entry.telemetry.failureCount || 0),
          foot: `${history.fallbackEvents.length} fallback events tracked`
        },
        {
          label: "Retries",
          meta: "Health",
          value: formatCompactNumber(health.providerTelemetry.retryCount),
          series: history.history.slice(0, 8).reverse().map((entry) => entry.telemetry.retryCount || 0),
          foot: `${health.providerTelemetry.circuitOpenCount} circuit-open events`
        },
        {
          label: "Requests",
          meta: "Throughput",
          value: formatCompactNumber(health.providerTelemetry.requestCount),
          series: history.history.slice(0, 8).reverse().map((entry) => entry.telemetry.requestCount || 0),
          foot: `${history.history.length} samples in memory`
        }
      ]);
      const maxEndpointLatency = Math.max(
        ...health.providerTelemetry.endpoints.map((endpoint) => endpoint.avgLatencyMillis || 0),
        1
      );
      renderBarList(
        "provider-endpoint-bars",
        health.providerTelemetry.endpoints.slice(0, 8).map((endpoint) => ({
          label: endpoint.provider,
          valueLabel: `${Number(endpoint.avgLatencyMillis || 0).toFixed(1)} ms avg`,
          ratio: Math.max(6, Math.round(((endpoint.avgLatencyMillis || 0) / maxEndpointLatency) * 100)),
          warn: Boolean(endpoint.circuitOpen || endpoint.failureCount > 0)
        }))
      );
      renderLineChart(
        "provider-latency-chart",
        history.history.slice(0, 12).reverse().map((entry) => Number(entry.telemetry.p95LatencyMillis || 0)),
        {
          title: "P95 Latency Trend",
          subtitle: "Recent provider history samples",
          leftLabel: "Oldest",
          rightLabel: "Latest",
          valueFormatter: (value) => `${Number(value || 0).toFixed(1)} ms`
        }
      );
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
      const fallbackCount = response.filter((entry) => entry.providerFallbackApplied).length;
      const avgHits = response.length
        ? response.reduce((sum, entry) => sum + Number(entry.resultCount || 0), 0) / response.length
        : 0;
      const avgTopK = response.length
        ? response.reduce((sum, entry) => sum + Number(entry.topK || 0), 0) / response.length
        : 0;
      renderAnalyticsCards("audit-analytics", [
        {
          label: "Audit Rows",
          meta: "Loaded",
          value: formatCompactNumber(response.length),
          series: response.slice(0, 8).reverse().map((entry) => entry.resultCount || 0),
          foot: "Recent search executions"
        },
        {
          label: "Fallback Events",
          meta: "Provider",
          value: formatCompactNumber(fallbackCount),
          series: response.slice(0, 8).reverse().map((entry) => (entry.providerFallbackApplied ? 1 : 0)),
          foot: "Provider fallback applied"
        },
        {
          label: "Average Hits",
          meta: "Results",
          value: avgHits.toFixed(1),
          series: response.slice(0, 8).reverse().map((entry) => entry.resultCount || 0),
          foot: "Per audit row"
        },
        {
          label: "Average Top K",
          meta: "Query",
          value: avgTopK.toFixed(1),
          series: response.slice(0, 8).reverse().map((entry) => entry.topK || 0),
          foot: "Requested retrieval depth"
        }
      ]);
      const typeCounts = response.reduce((acc, entry) => {
        acc[entry.auditType] = (acc[entry.auditType] || 0) + 1;
        return acc;
      }, {});
      const maxTypeCount = Math.max(...Object.values(typeCounts), 1);
      renderBarList(
        "audit-bars",
        Object.entries(typeCounts).map(([type, count]) => ({
          label: type,
          valueLabel: `${count} rows`,
          ratio: Math.max(8, Math.round((count / maxTypeCount) * 100)),
          warn: type.includes("diagnose")
        }))
      );
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
      const statusCounts = response.reduce((acc, entry) => {
        acc[entry.status] = (acc[entry.status] || 0) + 1;
        return acc;
      }, {});
      const retryableCount = response.filter((entry) => entry.retrySupported).length;
      renderAnalyticsCards("job-analytics", [
        {
          label: "Total Jobs",
          meta: "Loaded",
          value: formatCompactNumber(response.length),
          series: response.slice(0, 8).reverse().map((_, index) => index + 1),
          foot: "Recent execution records"
        },
        {
          label: "Success Jobs",
          meta: "Status",
          value: formatCompactNumber(statusCounts.SUCCESS || 0),
          series: response.slice(0, 8).reverse().map((entry) => (entry.status === "SUCCESS" ? 1 : 0)),
          foot: "Completed successfully"
        },
        {
          label: "Failed Jobs",
          meta: "Status",
          value: formatCompactNumber(statusCounts.FAILED || 0),
          series: response.slice(0, 8).reverse().map((entry) => (entry.status === "FAILED" ? 1 : 0)),
          foot: "Require investigation"
        },
        {
          label: "Retryable",
          meta: "Ops",
          value: formatCompactNumber(retryableCount),
          series: response.slice(0, 8).reverse().map((entry) => (entry.retrySupported ? 1 : 0)),
          foot: "Can be rerun from UI"
        }
      ]);
      const typeCounts = response.reduce((acc, entry) => {
        acc[entry.jobType] = (acc[entry.jobType] || 0) + 1;
        return acc;
      }, {});
      const maxJobTypeCount = Math.max(...Object.values(typeCounts), 1);
      renderBarList(
        "job-bars",
        Object.entries(typeCounts).map(([type, count]) => ({
          label: type,
          valueLabel: `${count} jobs`,
          ratio: Math.max(8, Math.round((count / maxJobTypeCount) * 100)),
          warn: type.includes("delete") || type.includes("restore")
        }))
      );
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
      const featureRoles = response.featureRoles || {};
      const roles = Array.from(new Set(Object.values(featureRoles).flat())).sort();
      const auditEntries = response.recentAccessAudits || [];
      const grantedCount = auditEntries.filter((entry) => entry.granted).length;
      renderAnalyticsCards("access-analytics", [
        {
          label: "Features",
          meta: "Policy",
          value: formatCompactNumber(Object.keys(featureRoles).length),
          series: Object.values(featureRoles).slice(0, 8).map((list) => list.length),
          foot: "Protected feature entries"
        },
        {
          label: "Roles",
          meta: "Policy",
          value: formatCompactNumber(roles.length),
          series: roles.map((_, index) => index + 1),
          foot: "Distinct roles in policy"
        },
        {
          label: "Granted Access",
          meta: "Audit",
          value: formatCompactNumber(grantedCount),
          series: auditEntries.slice(0, 8).reverse().map((entry) => (entry.granted ? 1 : 0)),
          foot: `${auditEntries.length - grantedCount} denied`
        },
        {
          label: "Current Role",
          meta: "Session",
          value: response.currentRole || "ANONYMOUS",
          series: roles.map((role) => (role === response.currentRole ? 1 : 0)),
          foot: response.authenticated ? "Authenticated session" : "Not signed in"
        }
      ]);
      renderRoleMatrix("role-matrix", featureRoles);
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
      setText("access-current-user", response.currentUser || "ANONYMOUS");
      setText("access-authenticated", response.authenticated ? "yes" : "no");
      setText("access-current-role", response.currentRole || "ANONYMOUS");
      setText("access-current-roles", (response.currentRoles || []).join(", ") || "-");
      setText("access-login-path", response.loginPath || config.loginPath);
      setText("access-logout-path", response.logoutPath || config.logoutPath);
      setNotice("access-notice", "access configuration refreshed", false);
    }

    document.getElementById("btn-access-refresh")?.addEventListener("click", () => run("access-notice", refreshAccessSecurity));
    run("access-notice", refreshAccessSecurity);
  }

  function initUsers() {
    let editingUsername = null;
    let userCache = [];
    let latestResponse = {
      items: [],
      totalCount: 0,
      enabledCount: 0,
      adminCount: 0,
      recentActions: []
    };
    let searchTerm = "";
    let sortField = "username";
    let sortDirection = "asc";
    let pageSize = 10;
    let pageIndex = 0;
    let auditPageSize = 10;
    let auditPageIndex = 0;
    let refreshTimer = null;
    let resetTargetUser = null;
    let selectedAuditEntry = null;
    let selectedUserUsername = null;
    let auditFilterAction = "all";
    let auditFilterSearch = "";
    let auditFrom = "";
    let auditTo = "";

    function totalPages(items) {
      return Math.max(1, Math.ceil(items.length / pageSize));
    }

    function clampPageIndex(items) {
      const maxIndex = Math.max(0, totalPages(items) - 1);
      pageIndex = Math.min(Math.max(pageIndex, 0), maxIndex);
    }

    function setBodyModalState() {
      const hasOpenModal = Array.from(document.querySelectorAll(".modal")).some((node) => !node.hidden);
      document.body.classList.toggle("modal-open", hasOpenModal);
    }

    function openModal(id) {
      const node = document.getElementById(id);
      if (!node) return;
      node.hidden = false;
      setBodyModalState();
    }

    function closeModal(id) {
      const node = document.getElementById(id);
      if (!node) return;
      node.hidden = true;
      setBodyModalState();
    }

    function scheduleRefresh(selectUsername) {
      if (refreshTimer) {
        window.clearTimeout(refreshTimer);
      }
      refreshTimer = window.setTimeout(() => {
        run("users-notice", () => refreshUsers(selectUsername));
      }, 220);
    }

    function updateAuditPagination(totalCount) {
      const pages = Math.max(1, Math.ceil(Math.max(0, totalCount) / auditPageSize));
      auditPageIndex = Math.min(Math.max(auditPageIndex, 0), pages - 1);
      setText(
        "users-audit-page-info",
        `Page ${auditPageIndex + 1} of ${pages} · ${totalCount} total`
      );
      const prev = document.getElementById("btn-users-audit-prev");
      const next = document.getElementById("btn-users-audit-next");
      if (prev) {
        prev.disabled = auditPageIndex <= 0;
      }
      if (next) {
        next.disabled = auditPageIndex >= pages - 1;
      }
    }

    function updatePagination(items) {
      const currentTotal = totalPages(items);
      clampPageIndex(items);
      setText(
        "users-page-info",
        `Page ${pageIndex + 1} of ${currentTotal} · ${items.length} total`
      );
      const prev = document.getElementById("btn-users-prev");
      const next = document.getElementById("btn-users-next");
      if (prev) {
        prev.disabled = pageIndex <= 0;
      }
      if (next) {
        next.disabled = pageIndex >= currentTotal - 1;
      }
    }

    function updateSortControls() {
      const sortFieldNode = document.getElementById("users-sort-field");
      const sortDirectionNode = document.getElementById("users-sort-direction");
      if (sortFieldNode) {
        sortFieldNode.value = sortField;
      }
      if (sortDirectionNode) {
        sortDirectionNode.value = sortDirection;
      }
    }

    function setAuditRange(fromValue, toValue) {
      auditFrom = fromValue || "";
      auditTo = toValue || "";
      auditPageIndex = 0;
      setValue("users-audit-from", auditFrom);
      setValue("users-audit-to", auditTo);
      scheduleRefresh();
    }

    function setRecentAuditPreset(days) {
      const end = new Date();
      const start = new Date(end);
      start.setDate(start.getDate() - Math.max(0, days - 1));
      setAuditRange(formatDateInputValue(start), formatDateInputValue(end));
    }

    function setMonthToDateAuditPreset() {
      const end = new Date();
      const start = new Date(end.getFullYear(), end.getMonth(), 1);
      setAuditRange(formatDateInputValue(start), formatDateInputValue(end));
    }

    function setMode() {
      setText("users-mode", editingUsername ? `Editing ${editingUsername}` : "Creating new user");
      setText("users-save-label", editingUsername ? "Update User" : "Create User");
      setText(
        "users-password-hint",
        editingUsername ? "Leave blank to keep the current password." : "Password is required for new users."
      );
      setText("btn-users-save", editingUsername ? "Update User" : "Create User");
      const username = document.getElementById("users-username");
      if (username) {
        username.disabled = Boolean(editingUsername);
      }
    }

    function clearForm() {
      editingUsername = null;
      setValue("users-username", "");
      setValue("users-display-name", "");
      setValue("users-password", "");
      setValue("users-roles", "ADMIN");
      const enabled = document.getElementById("users-enabled");
      if (enabled) {
        enabled.checked = true;
      }
      setMode();
    }

    function populateForm(user) {
      editingUsername = user.username;
      setValue("users-username", user.username);
      setValue("users-display-name", user.displayName || "");
      setValue("users-password", "");
      setValue("users-roles", (user.roles || []).join("\n") || "ADMIN");
      const enabled = document.getElementById("users-enabled");
      if (enabled) {
        enabled.checked = Boolean(user.enabled);
      }
      setMode();
      setNotice("users-notice", `editing ${user.username}`, false);
    }

    function readForm() {
      return {
        username: document.getElementById("users-username")?.value?.trim() || "",
        displayName: document.getElementById("users-display-name")?.value?.trim() || "",
        password: document.getElementById("users-password")?.value || "",
        enabled: Boolean(document.getElementById("users-enabled")?.checked),
        roles: parseList(document.getElementById("users-roles")?.value).map((role) => role.toUpperCase())
      };
    }

    function buildUsersQuery(options = {}) {
      const params = new URLSearchParams();
      const query = String(searchTerm || "").trim();
      if (query) {
        params.set("q", query);
      }
      if (sortField) {
        params.set("sort", sortField);
      }
      if (sortDirection) {
        params.set("direction", sortDirection);
      }
      if (auditFilterAction && auditFilterAction !== "all") {
        params.set("auditAction", auditFilterAction);
      }
      const auditQuery = String(auditFilterSearch || "").trim();
      if (auditQuery) {
        params.set("auditQuery", auditQuery);
      }
      if (auditFrom) {
        params.set("auditFrom", auditFrom);
      }
      if (auditTo) {
        params.set("auditTo", auditTo);
      }
      params.set("auditLimit", String(options.auditLimit ?? auditPageSize));
      params.set("auditOffset", String(options.auditOffset ?? (auditPageIndex * auditPageSize)));
      return params.toString() ? `?${params.toString()}` : "";
    }

    function filteredAuditEntries() {
      return latestResponse.recentActions || [];
    }

    function openResetPasswordModal(user) {
      resetTargetUser = user;
      setText("users-reset-target", `Reset password for ${user.username}`);
      setValue("users-reset-password", "");
      openModal("users-reset-modal");
      window.setTimeout(() => {
        document.getElementById("users-reset-password")?.focus();
      }, 0);
    }

    function closeResetPasswordModal() {
      resetTargetUser = null;
      closeModal("users-reset-modal");
    }

    function openAuditDetail(entry) {
      selectedAuditEntry = entry;
      const detailSummary = formatAuditDetailSummary(entry);
      setText(
        "users-audit-meta",
        `${entry.action} · ${entry.targetUsername} · ${new Date(entry.timestampEpochMillis).toLocaleString()}`
      );
      setText(
        "users-audit-subtitle",
        `${entry.success ? "Success" : "Failure"} · ${entry.actorUsername || "-"}`
      );
      renderJson("users-audit-detail", {
        ...entry,
        summary: detailSummary
      });
      setText("users-audit-meta", `${entry.action} · ${entry.targetUsername} · ${new Date(entry.timestampEpochMillis).toLocaleString()}`);
      const metaNode = document.getElementById("users-audit-meta");
      if (metaNode) {
        metaNode.replaceChildren();
        const lines = [
          `${entry.action} by ${entry.actorUsername || "system"} (${entry.actorRole || "unknown"})`,
          `Target: ${entry.targetUsername}`,
          `Result: ${entry.success ? "success" : "failed"}`,
          `Summary: ${detailSummary}`
        ];
        lines.forEach((line) => {
          const div = document.createElement("div");
          div.textContent = line;
          metaNode.appendChild(div);
        });
      }
      openModal("users-audit-modal");
    }

    function formatAuditDetailSummary(entry) {
      const details = entry.details || {};
      const entries = Object.entries(details);
      if (!entries.length) {
        return entry.message || "No field-level changes captured.";
      }
      return entries
        .map(([key, value]) => `${key}: ${value}`)
        .join(" · ");
    }

    function closeAuditDetailModal() {
      selectedAuditEntry = null;
      closeModal("users-audit-modal");
    }

    function selectedUser() {
      if (!selectedUserUsername) {
        return userCache[0] || null;
      }
      return userCache.find((user) => user.username === selectedUserUsername) || null;
    }

    function renderSelectedUserPanel() {
      const target = document.getElementById("users-selected-panel");
      if (!target) return;
      target.replaceChildren();
      const user = selectedUser();
      if (!user) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "No user selected";
        target.appendChild(empty);
        return;
      }
      const userAudits = (latestResponse.recentActions || []).filter((entry) => entry.targetUsername === user.username);
      renderDataCards("users-selected-panel", [
        {
          title: user.username,
          meta: user.enabled ? "Enabled" : "Disabled",
          bodyHtml: `
            <div><strong>${escapeHtml(user.displayName || "-")}</strong></div>
            <div>Roles: ${escapeHtml((user.roles || []).join(", ") || "-")}</div>
            <div>Password: ${user.passwordConfigured ? "configured" : "missing"}</div>
          `,
          chips: [user.enabled ? "active" : "inactive", user.isAdmin ? "admin" : "member", `${userAudits.length} events`]
        },
        {
          title: "Recent activity",
          meta: "Inline trail",
          bodyHtml: userAudits.length
            ? userAudits
                .slice(0, 4)
                .map((entry) => `${escapeHtml(entry.action)} · ${escapeHtml(entry.message || "-")}`)
                .join("<br />")
            : "No activity recorded for this user.",
          chips: userAudits.slice(0, 4).map((entry) => entry.action)
        }
      ]);
      renderRoleMatrix("users-selected-matrix", config.featureRoles || {});
      renderTimeline(
        "users-selected-timeline",
        userAudits.slice(0, 8).map((entry, index) => ({
          phase: entry.action,
          when: new Date(entry.timestampEpochMillis).toLocaleString(),
          body: formatAuditDetailSummary(entry),
          meta: `${entry.targetUsername} · ${entry.success ? "success" : "failed"}`,
          warn: !entry.success,
          chips: [entry.actorUsername || "system", entry.actorRole || "audit", `#${index + 1}`]
        }))
      );
    }

    function renderUsersTable(items) {
      const target = document.getElementById("users-table");
      if (!target) return;
      target.replaceChildren();
      if (!items.length) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "No data";
        target.appendChild(empty);
        return;
      }

      const toolbar = document.createElement("div");
      toolbar.className = "table-toolbar";
      const count = document.createElement("div");
      count.className = "table-count";
      count.textContent = `${items.length} rows`;
      const hint = document.createElement("div");
      hint.className = "helper";
      hint.textContent = `Sorted by ${sortField} ${sortDirection === "desc" ? "descending" : "ascending"}`;
      toolbar.appendChild(count);
      toolbar.appendChild(hint);

      const wrapper = document.createElement("div");
      wrapper.className = "table-wrap";
      const table = document.createElement("table");
      const thead = document.createElement("thead");
      const headerRow = document.createElement("tr");
      const columns = [
        { key: "username", label: "Username" },
        { key: "displayName", label: "Display Name" },
        { key: "enabled", label: "Enabled" },
        { key: "roles", label: "Roles" },
        { key: "actions", label: "Actions", sortable: false }
      ];
      columns.forEach((column) => {
        const th = document.createElement("th");
        if (column.sortable === false) {
          th.textContent = column.label;
        } else {
          const button = document.createElement("button");
          button.type = "button";
          button.className = "table-head-button";
          button.textContent = column.label;
          if (sortField === column.key) {
            button.dataset.active = "true";
            button.setAttribute("aria-sort", sortDirection === "desc" ? "descending" : "ascending");
            button.textContent = `${column.label} ${sortDirection === "desc" ? "↓" : "↑"}`;
          }
          button.addEventListener("click", () => {
            if (sortField === column.key) {
              sortDirection = sortDirection === "asc" ? "desc" : "asc";
            } else {
              sortField = column.key;
              sortDirection = column.key === "enabled" ? "desc" : "asc";
            }
            updateSortControls();
            scheduleRefresh();
          });
          th.appendChild(button);
        }
        headerRow.appendChild(th);
      });
      thead.appendChild(headerRow);
      const tbody = document.createElement("tbody");
      items.forEach((user) => {
        const tr = document.createElement("tr");
        const cells = [
          user.username,
          user.displayName || "-",
          user.enabled ? "yes" : "no",
          (user.roles || []).join(", ") || "-",
          (() => {
            const actions = document.createElement("div");
            actions.className = "button-row";
            actions.appendChild(createButton("View", () => {
              selectedUserUsername = user.username;
              renderSelectedUserPanel();
            }, "secondary"));
            actions.appendChild(createButton("Edit", () => populateForm(user), "secondary"));
            actions.appendChild(createButton("Reset Password", () => openResetPasswordModal(user), "secondary"));
            actions.appendChild(createButton("Delete", () => run("users-notice", async () => {
              if (!window.confirm(`Delete ${user.username}?`)) {
                return;
              }
              await request(`/users/${encodeSegment(user.username)}`, { method: "DELETE" });
              if (editingUsername === user.username) {
                clearForm();
              }
              await refreshUsers();
              setNotice("users-notice", `deleted ${user.username}`, false);
            }), "secondary"));
            return actions;
          })()
        ];
        cells.forEach((cellValue) => {
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
      target.appendChild(toolbar);
      target.appendChild(wrapper);
    }

    function renderUserSummary(response) {
      const admins = response.items.filter((user) => user.enabled && user.isAdmin);
      renderAnalyticsCards("users-analytics", [
        {
          label: "Users",
          meta: "Accounts",
          value: formatCompactNumber(response.totalCount),
          series: response.items.slice(0, 8).map((user) => (user.enabled ? 1 : 0)),
          foot: `${response.enabledCount} enabled`
        },
        {
          label: "Admins",
          meta: "Role",
          value: formatCompactNumber(response.adminCount),
          series: admins.slice(0, 8).map((user) => (user.enabled ? 1 : 0)),
          foot: `${admins.length} enabled ADMIN accounts`
        },
        {
          label: "Passwords",
          meta: "Secret",
          value: formatCompactNumber(response.items.filter((user) => user.passwordConfigured).length),
          series: response.items.slice(0, 8).map((user) => (user.passwordConfigured ? 1 : 0)),
          foot: "Stored as BCrypt hashes"
        },
        {
          label: "Active Roles",
          meta: "Policy",
          value: formatCompactNumber(new Set(response.items.flatMap((user) => user.roles || [])).size),
          series: response.items.slice(0, 8).map((user) => (user.roles || []).length),
          foot: "Distinct role labels"
        }
      ]);
    }

    function renderAuditTrail(response) {
      const recentActions = filteredAuditEntries();
      setText("users-audit-count", recentActions.length);
      updateAuditPagination(response.auditTotalCount || recentActions.length);
      setText(
        "users-audit-summary",
        `Showing ${recentActions.length} audit event${recentActions.length === 1 ? "" : "s"} of ${response.auditTotalCount || recentActions.length}`
      );
      renderTable(
        "users-audit-table",
        ["When", "Action", "Actor", "Target", "Outcome", "Details"],
        recentActions.map((entry) => [
          new Date(entry.timestampEpochMillis).toLocaleString(),
          entry.action,
          [entry.actorUsername, entry.actorRole].filter(Boolean).join(" / ") || "-",
          entry.targetUsername,
          entry.success ? "success" : "failed",
          (() => {
            const actions = document.createElement("div");
            actions.className = "button-row";
            actions.appendChild(createButton("View", () => openAuditDetail(entry), "secondary"));
            actions.appendChild(createButton("Focus User", () => {
              selectedUserUsername = entry.targetUsername;
              renderSelectedUserPanel();
            }, "secondary"));
            return actions;
          })()
        ])
      );
      renderTimeline(
        "users-audit-timeline",
        recentActions.map((entry, index) => ({
          phase: entry.action,
          when: new Date(entry.timestampEpochMillis).toLocaleString(),
          message: `${entry.targetUsername}${entry.message ? ` · ${entry.message}` : ""}`,
          level: entry.success ? "ok" : "error",
          source: entry.actorUsername || "system",
          kind: entry.actorRole || "audit",
          status: entry.success ? "success" : "failed",
          chips: [entry.targetUsername, entry.actorRole || "unknown", `#${index + 1}`]
        }))
      );
    }

    function renderCurrentPage(response) {
      const items = userCache.slice();
      clampPageIndex(items);
      const start = pageIndex * pageSize;
      const pageItems = items.slice(start, start + pageSize);
      updatePagination(items);
      renderUsersTable(pageItems);
      renderAuditTrail(response);
      renderUserSummary(response);
      renderSelectedUserPanel();
      renderJson("output-users", {
        ...response,
        searchTerm,
        sortField,
        sortDirection,
        pageSize,
        pageIndex,
        visibleCount: pageItems.length,
        filteredCount: items.length
      });
    }

    function auditCsvRows(entries) {
      const escapeCell = (value) => {
        const text = String(value == null ? "" : value);
        const escaped = text.replaceAll("\"", "\"\"");
        return /[",\n]/.test(escaped) ? `"${escaped}"` : escaped;
      };
      const header = [
        "timestamp",
        "action",
        "actorUsername",
        "actorRole",
        "targetUsername",
        "success",
        "message",
        "details"
      ];
      const rows = entries.map((entry) => [
        new Date(entry.timestampEpochMillis).toISOString(),
        entry.action,
        entry.actorUsername || "",
        entry.actorRole || "",
        entry.targetUsername,
        entry.success ? "true" : "false",
        entry.message || "",
        Object.entries(entry.details || {})
          .map(([key, value]) => `${key}=${value}`)
          .join("; ")
      ]);
      return [header, ...rows]
        .map((row) => row.map(escapeCell).join(","))
        .join("\n");
    }

    async function downloadAuditCsv() {
      const auditLimit = Math.max(Number(latestResponse.auditTotalCount || 0), 1);
      const response = await request(`/users${buildUsersQuery({ auditLimit, auditOffset: 0 })}`, { method: "GET" });
      const entries = response.recentActions || [];
      const csv = auditCsvRows(entries);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `rag-users-audit-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      setNotice("users-notice", `exported ${entries.length} audit rows`, false);
    }

    async function refreshUsers(selectUsername) {
      const response = await request(`/users${buildUsersQuery()}`, { method: "GET" });
      latestResponse = {
        ...response,
        items: response.items || [],
        recentActions: response.recentActions || [],
        auditTotalCount: Number(response.auditTotalCount || 0),
        auditOffset: Number(response.auditOffset || 0),
        auditLimit: Number(response.auditLimit || auditPageSize)
      };
      userCache = latestResponse.items;
      if (!selectedUserUsername && userCache.length) {
        selectedUserUsername = userCache[0].username;
      }
      if (selectUsername) {
        const selectedIndex = userCache.findIndex((user) => user.username === selectUsername);
        if (selectedIndex >= 0) {
          pageIndex = Math.floor(selectedIndex / pageSize);
          selectedUserUsername = selectUsername;
        }
      }
      renderCurrentPage(latestResponse);
      if (selectUsername) {
        const selected = userCache.find((user) => user.username === selectUsername);
        if (selected) {
          populateForm(selected);
        }
      }
      setNotice("users-notice", `loaded ${response.totalCount} users`, false);
    }

    async function submitResetPassword() {
      if (!resetTargetUser) {
        throw new Error("no user selected");
      }
      const password = document.getElementById("users-reset-password")?.value?.trim() || "";
      if (!password) {
        throw new Error("password is required");
      }
      await request(`/users/${encodeSegment(resetTargetUser.username)}/password`, {
        method: "POST",
        body: JSON.stringify({ password })
      });
      const username = resetTargetUser.username;
      closeResetPasswordModal();
      await refreshUsers(username);
      setNotice("users-notice", `password reset for ${username}`, false);
    }

    async function saveUser() {
      const payload = readForm();
      if (!payload.username) {
        throw new Error("username is required");
      }
      if (!payload.roles.length) {
        throw new Error("at least one role is required");
      }
      const requestBody = {
        username: payload.username,
        displayName: payload.displayName || null,
        enabled: payload.enabled,
        roles: payload.roles
      };
      if (payload.password) {
        requestBody.password = payload.password;
      }
      const action = editingUsername ? "updated" : "created";
      const response = editingUsername
        ? await request(`/users/${encodeSegment(editingUsername)}`, {
            method: "PUT",
            body: JSON.stringify(requestBody)
          })
        : await request("/users", {
            method: "POST",
            body: JSON.stringify(requestBody)
          });
      renderJson("output-users-action", response);
      editingUsername = payload.username;
      setMode();
      await refreshUsers(payload.username);
      setNotice("users-notice", `${action} ${payload.username}`, false);
    }

    document.getElementById("btn-users-refresh")?.addEventListener("click", () => run("users-notice", refreshUsers));
    document.getElementById("btn-users-clear")?.addEventListener("click", () => {
      clearForm();
      setNotice("users-notice", "form cleared", false);
    });
    document.getElementById("btn-users-save")?.addEventListener("click", () => run("users-notice", saveUser));
    document.getElementById("btn-users-prev")?.addEventListener("click", () => {
      pageIndex = Math.max(0, pageIndex - 1);
      renderCurrentPage(latestResponse);
    });
    document.getElementById("btn-users-next")?.addEventListener("click", () => {
      pageIndex = Math.min(totalPages(userCache) - 1, pageIndex + 1);
      renderCurrentPage(latestResponse);
    });
    document.getElementById("users-search")?.addEventListener("input", (event) => {
      searchTerm = event.target.value || "";
      pageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-page-size")?.addEventListener("change", (event) => {
      pageSize = Math.max(1, Number(event.target.value) || 10);
      pageIndex = 0;
      renderCurrentPage(latestResponse);
    });
    document.getElementById("users-sort-field")?.addEventListener("change", (event) => {
      sortField = event.target.value || "username";
      pageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-sort-direction")?.addEventListener("change", (event) => {
      sortDirection = event.target.value || "asc";
      pageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-audit-action")?.addEventListener("change", (event) => {
      auditFilterAction = event.target.value || "all";
      auditPageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-audit-search")?.addEventListener("input", (event) => {
      auditFilterSearch = event.target.value || "";
      auditPageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-audit-from")?.addEventListener("change", (event) => {
      auditFrom = event.target.value || "";
      auditPageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("users-audit-to")?.addEventListener("change", (event) => {
      auditTo = event.target.value || "";
      auditPageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("btn-users-audit-clear")?.addEventListener("click", () => {
      auditFilterAction = "all";
      auditFilterSearch = "";
      setAuditRange("", "");
      setValue("users-audit-action", "all");
      setValue("users-audit-search", "");
      setNotice("users-notice", "audit filters cleared", false);
    });
    document.getElementById("btn-users-audit-preset-7d")?.addEventListener("click", () => {
      setRecentAuditPreset(7);
      setNotice("users-notice", "audit preset set to recent 7 days", false);
    });
    document.getElementById("btn-users-audit-preset-30d")?.addEventListener("click", () => {
      setRecentAuditPreset(30);
      setNotice("users-notice", "audit preset set to recent 30 days", false);
    });
    document.getElementById("btn-users-audit-preset-month")?.addEventListener("click", () => {
      setMonthToDateAuditPreset();
      setNotice("users-notice", "audit preset set to this month", false);
    });
    document.getElementById("btn-users-export-audit")?.addEventListener("click", () => run("users-notice", downloadAuditCsv));
    document.getElementById("btn-users-audit-prev")?.addEventListener("click", () => {
      auditPageIndex = Math.max(0, auditPageIndex - 1);
      scheduleRefresh();
    });
    document.getElementById("btn-users-audit-next")?.addEventListener("click", () => {
      const pages = Math.max(1, Math.ceil(Math.max(0, latestResponse.auditTotalCount || 0) / auditPageSize));
      auditPageIndex = Math.min(pages - 1, auditPageIndex + 1);
      scheduleRefresh();
    });
    document.getElementById("users-audit-page-size")?.addEventListener("change", (event) => {
      auditPageSize = Math.max(1, Number(event.target.value) || 10);
      auditPageIndex = 0;
      scheduleRefresh();
    });
    document.getElementById("btn-users-reset-submit")?.addEventListener("click", () => run("users-notice", submitResetPassword));
    document.getElementById("btn-users-reset-cancel")?.addEventListener("click", closeResetPasswordModal);
    document.getElementById("users-reset-password")?.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        run("users-notice", submitResetPassword);
      }
    });
    document.querySelectorAll('[data-modal-close="users-reset-modal"]').forEach((node) => {
      node.addEventListener("click", closeResetPasswordModal);
    });
    document.querySelectorAll('[data-modal-close="users-audit-modal"]').forEach((node) => {
      node.addEventListener("click", closeAuditDetailModal);
    });
    setMode();
    clearForm();
    pageSize = Math.max(1, Number(document.getElementById("users-page-size")?.value || 10));
    auditPageSize = Math.max(1, Number(document.getElementById("users-audit-page-size")?.value || 10));
    searchTerm = document.getElementById("users-search")?.value || "";
    sortField = document.getElementById("users-sort-field")?.value || "username";
    sortDirection = document.getElementById("users-sort-direction")?.value || "asc";
    auditFrom = document.getElementById("users-audit-from")?.value || "";
    auditTo = document.getElementById("users-audit-to")?.value || "";
    updateSortControls();
    run("users-notice", refreshUsers);
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
    let resultMode = "all";
    let lastOutput = null;

    function resultFilter() {
      return document.getElementById("bulk-result-filter")?.value || "all";
    }

    function matchesFilter(status) {
      const filter = resultFilter();
      return filter === "all" || filter === status;
    }

    let traceEvents = [];

    function setTrace(events) {
      traceEvents = events;
      renderTimeline("bulk-progress-log", traceEvents);
    }

    function syncResultView() {
      const allPanel = document.getElementById("bulk-result-panel-all");
      const changedPanel = document.getElementById("bulk-result-panel-changed");
      const allTab = document.getElementById("bulk-tab-all");
      const changedTab = document.getElementById("bulk-tab-changed");
      if (allPanel) allPanel.hidden = resultMode === "changed";
      if (changedPanel) changedPanel.hidden = resultMode !== "changed";
      allTab?.classList.toggle("active", resultMode === "all");
      changedTab?.classList.toggle("active", resultMode === "changed");
    }

    function bulkStatusClass(item) {
      if (item.status === "changed") return "status-changed";
      if (item.status === "skipped") return "status-skipped";
      if (item.status === "deleted") return "status-deleted";
      if (item.status === "patched") return "status-patched";
      if (item.success === false) return "status-failed";
      return "status-ingested";
    }

    function renderBulkResultCards(response) {
      lastOutput = response;
      const changedCount = (response.results || []).filter((item) => item.status === "changed").length;
      setTabCount("bulk-tab-changed-count", changedCount);
      const items = [];
      if ((response.results || []).length > 0) {
        items.push(...response.results
          .filter((item) => matchesFilter(item.status || (item.success ? "ingested" : "failed")))
          .map((item) => ({
            title: item.docId || "unknown",
            meta: item.status || (item.success ? "success" : "failed"),
            body: item.message || "bulk item processed",
            bodyHtml: item.status === "changed"
              ? renderPreviewDiff(item.previousPreview, item.currentPreview, item.changeSummary)
              : escapeHtml(item.message || "bulk item processed"),
            className: bulkStatusClass(item),
            chips: [
              item.success ? "success" : "failed",
              item.status || "n/a"
            ]
          })));
      } else {
        items.push({
          title: response.operation || "bulk operation",
          meta: `success ${response.successCount || 0} / failure ${response.failureCount || 0}`,
          body: "No item-level results returned.",
          className: "status-ingested",
          chips: ["bulk", response.operation || "operation"]
        });
      }
      renderDataCards("bulk-result-cards", items);
      renderDataCards(
        "bulk-result-cards-changed",
        (response.results || [])
          .filter((item) => item.status === "changed")
          .map((item) => ({
            title: item.docId || "unknown",
            meta: item.status || "changed",
            body: item.message || "bulk item processed",
            bodyHtml: renderPreviewDiff(item.previousPreview, item.currentPreview, item.changeSummary),
            className: "status-changed",
            chips: [
              item.success ? "success" : "failed",
              item.status || "n/a"
            ]
          }))
      );
    }

    function renderBulkSummary(response) {
      const skippedCount = (response.results || []).filter((item) => item.status === "skipped").length;
      const changedCount = (response.results || []).filter((item) => item.status === "changed").length;
      const failedCount = (response.results || []).filter((item) => item.success === false).length;
      setTabCount("bulk-tab-changed-count", changedCount);
      renderDataCards(
        "bulk-summary",
        [
          {
            title: response.operation || "bulk operation",
            meta: `success ${response.successCount || 0} / failure ${response.failureCount || 0}`,
            body: "bulk ingest and follow-up actions are summarized here",
            bodyHtml: `<div class="chip-row">${
              [
                `changed ${changedCount}`,
                `skipped ${skippedCount}`,
                `failed ${failedCount}`
              ].map((chip) => `<span class="mini-chip">${escapeHtml(chip)}</span>`).join("")
            }</div>`,
            className: failedCount > 0 ? "status-failed" : "status-ingested",
            chips: [
              `changed ${changedCount}`,
              `skipped ${skippedCount}`,
              `failed ${failedCount}`
            ]
          }
        ]
      );
    }

    async function bulkTextIngest() {
      const documents = parseJsonInput("bulk-ingest-json", []);
      const incremental = Boolean(document.getElementById("bulk-ingest-incremental")?.checked);
      setTrace([
        {
          phase: "queued",
          when: "now",
          message: "bulk ingest payload prepared",
          source: "bulk",
          kind: "request",
          status: "pending"
        },
        {
          phase: "validated",
          when: "now",
          message: `${documents.length} documents queued for ingest`,
          source: "bulk",
          kind: "validation",
          status: incremental ? "incremental on" : "incremental off"
        },
        {
          phase: "dispatching",
          when: "now",
          message: "sending document array to ingest pipeline",
          source: "bulk",
          kind: "request",
          status: "running"
        }
      ]);
      const response = await request("/bulk/text-ingest", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          documents,
          incrementalIngest: incremental
        })
      });
      renderJson("output-bulk", response);
      setTrace([
        ...traceEvents,
        ...(response.results || []).map((item) => ({
          phase: item.docId || "document",
          when: "now",
          message: `${item.status || (item.success ? "ingested" : "failed")}: ${item.message || "bulk item processed"}`,
          source: "bulk",
          kind: "item",
          status: item.status || (item.success ? "ingested" : "failed"),
          level: item.success === false ? "error" : item.status === "skipped" ? "skip" : item.status === "changed" ? "warn" : "ok"
        })),
        {
          phase: "completed",
          when: "now",
          message: `success ${response.successCount || 0}, failure ${response.failureCount || 0}`,
          source: "bulk",
          kind: "result",
          status: response.failureCount > 0 ? "failed" : "ingested",
          level: response.failureCount > 0 ? "error" : "ok"
        }
      ]);
      renderBulkSummary(response);
      renderBulkResultCards(response);
      const skippedCount = (response.results || []).filter((item) => /skipped/i.test(item.message || "")).length;
      const changedCount = (response.results || []).filter((item) => /changed/i.test(item.message || "")).length;
      const suffix = skippedCount || changedCount
        ? `, ${changedCount} changed, ${skippedCount} skipped`
        : "";
      setNotice("bulk-notice", `bulk text ingest completed with ${response.successCount} successes${suffix}`, false);
    }

    async function bulkDelete() {
      setTrace([
        {
          phase: "queued",
          when: "now",
          message: "bulk delete request prepared",
          source: "bulk",
          kind: "delete"
        }
      ]);
      const response = await request("/bulk/delete", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docIds: parseList(document.getElementById("bulk-delete-docs")?.value)
        })
      });
      renderJson("output-bulk", response);
      setTrace([
        ...traceEvents,
        {
          phase: "completed",
          when: "now",
          message: `deleted ${response.successCount || 0} docs`,
          source: "bulk",
          kind: "result",
          status: response.failureCount > 0 ? "failed" : "deleted",
          level: response.failureCount > 0 ? "error" : "ok"
        }
      ]);
      renderBulkSummary(response);
      renderBulkResultCards(response);
      setNotice("bulk-notice", `bulk delete completed with ${response.successCount} successes`, false);
    }

    async function bulkMetadataPatch() {
      setTrace([
        {
          phase: "queued",
          when: "now",
          message: "metadata patch request prepared",
          source: "bulk",
          kind: "patch"
        }
      ]);
      const response = await request("/bulk/metadata-patch", {
        method: "POST",
        body: JSON.stringify({
          tenantId: currentTenant(),
          docIds: parseList(document.getElementById("bulk-patch-docs")?.value),
          metadata: parseMap(document.getElementById("bulk-patch-metadata")?.value)
        })
      });
      renderJson("output-bulk", response);
      setTrace([
        ...traceEvents,
        {
          phase: "completed",
          when: "now",
          message: `patched ${response.successCount || 0} docs`,
          source: "bulk",
          kind: "result",
          status: response.failureCount > 0 ? "failed" : "patched",
          level: response.failureCount > 0 ? "error" : "ok"
        }
      ]);
      renderBulkSummary(response);
      renderBulkResultCards(response);
      setNotice("bulk-notice", `metadata patch completed with ${response.successCount} successes`, false);
    }

    document.getElementById("btn-bulk-ingest")?.addEventListener("click", () => run("bulk-notice", bulkTextIngest));
    document.getElementById("btn-bulk-delete")?.addEventListener("click", () => run("bulk-notice", bulkDelete));
    document.getElementById("btn-bulk-patch")?.addEventListener("click", () => run("bulk-notice", bulkMetadataPatch));
    document.getElementById("bulk-tab-all")?.addEventListener("click", () => {
      resultMode = "all";
      syncResultView();
      if (lastOutput) renderBulkResultCards(lastOutput);
    });
    document.getElementById("bulk-tab-changed")?.addEventListener("click", () => {
      resultMode = "changed";
      syncResultView();
      if (lastOutput) renderBulkResultCards(lastOutput);
    });
    document.getElementById("bulk-result-filter")?.addEventListener("change", () => {
      if (lastOutput) {
        renderBulkResultCards(lastOutput);
        renderBulkSummary(lastOutput);
      }
    });
    syncResultView();
  }

  function initPage() {
    setBadges();
    setupNavigation();
    bindContext();
    applyConfiguredDefaults();
    decorateContentShell();

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
      case "web-ingest":
        initWebIngest();
        break;
      case "documents":
        initDocuments();
        break;
      case "graph":
        initGraph();
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
      case "users":
        initUsers();
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
