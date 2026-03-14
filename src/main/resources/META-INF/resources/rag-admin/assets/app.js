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
    { route: "overview", feature: "overview", label: "Overview", caption: "Live board", section: "Monitor", board: "Control Tower" },
    { route: "provider-history", feature: "provider-history", label: "Provider History", caption: "Latency and fallback", section: "Monitor", board: "Provider Telemetry" },
    { route: "search-audit", feature: "search-audit", label: "Search Audit", caption: "Query trail", section: "Monitor", board: "Audit Tape" },
    { route: "job-history", feature: "job-history", label: "Job History", caption: "Runbook queue", section: "Monitor", board: "Execution Tape" },
    { route: "search", feature: "search", label: "Search", caption: "Diagnostic retrieval", section: "Retrieval", board: "Search Desk" },
    { route: "documents", feature: "documents", label: "Documents", caption: "Browser and preview", section: "Retrieval", board: "Document Ledger" },
    { route: "text-ingest", feature: "text-ingest", label: "Text Ingest", caption: "Direct upsert", section: "Ingest", board: "Manual Ingest" },
    { route: "file-ingest", feature: "file-ingest", label: "File Ingest", caption: "Upload pipeline", section: "Ingest", board: "Upload Ingest" },
    { route: "bulk-operations", feature: "bulk-operations", label: "Bulk Ops", caption: "Batch changes", section: "Ingest", board: "Batch Operations" },
    { route: "tenants", feature: "tenants", label: "Tenants & Index Ops", caption: "Snapshot and optimize", section: "Operations", board: "Tenant Operations" },
    { route: "config", feature: "config", label: "Config", caption: "Read-only settings", section: "Operations", board: "Configuration Board" },
    { route: "access-security", feature: "access-security", label: "Access & Security", caption: "Roles and audit", section: "Operations", board: "Security Board" }
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
    updateShellMeta();
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
    setText("shell-role", config.currentRole || "ANONYMOUS");
    setText("shell-tenant", context.tenantId || "-");
    setText("shell-window", `${context.recentProviderWindowMillis || 0} ms`);
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
        <span class="topbar-pill">Starter Embedded</span>
        <span class="topbar-pill">Feature ${route.feature}</span>
        <span class="topbar-pill">${config.securityEnabled ? "Protected" : "Open Access"}</span>
      </div>
    `;

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

    area.prepend(strip);
    area.prepend(topbar);
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
      card.className = "data-card";
      const chips = (item.chips || []).map((chip) => `<span class="mini-chip">${chip}</span>`).join("");
      card.innerHTML = `
        <div class="data-card-head">
          <div class="data-card-title">${item.title}</div>
          <div class="data-card-meta">${item.meta || ""}</div>
        </div>
        <div class="data-card-body">${item.body || ""}</div>
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
      renderSearchSummary(response);
      setNotice("search-notice", `search completed with ${response.hits.length} hits`, false);
    }

    async function runDiagnostics() {
      const response = await request("/diagnose-search", {
        method: "POST",
        body: JSON.stringify(searchPayload())
      });
      renderJson("output-diagnostics", response);
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
          foot: response.securityEnabled ? "Security enabled" : "Security disabled"
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
