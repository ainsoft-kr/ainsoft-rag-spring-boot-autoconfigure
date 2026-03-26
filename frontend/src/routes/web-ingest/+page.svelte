<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Web Ingest"
  page="web-ingest"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div style="display: flex; align-items: center;">
          <h2>Crawl Sheet</h2>
          <span class="help-icon">?
            <div class="tooltip">
              <h3 style="color: var(--accent);">How It Works</h3>
              <p>sitemap.xml을 먼저 읽고, 허용된 domain 안에서 링크를 breadth-first로 방문합니다.</p>
              <h3>Parser</h3>
              <p>Ksoup로 링크와 메타 태그를 읽고 visible text를 정리합니다.</p>
              <h3>Priority</h3>
              <p>사이트의 sitemap.xml에 적힌 URL을 먼저 ingest하고, 이후 일반 링크를 탐색합니다.</p>
              <h3>Safety</h3>
              <p>기본값은 같은 host 내부만 crawl하고, 허용 domain과 최대 페이지 수, depth를 제한합니다.</p>
            </div>
          </span>
        </div>
        <p>같은 host 안에서 링크를 따라가며 문서를 순차 ingest합니다.</p>
      </div>
      <div class="stack">
        <div class="triple">
          <div class="dual" style="gap: 12px;">
            <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
            <label>Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
          </div>
          <div class="dual" style="gap: 12px;">
            <label>Max Pages<input id="web-max-pages" type="number" value="25" min="1" /></label>
            <label>Max Depth<input id="web-max-depth" type="number" value="1" min="0" /></label>
          </div>
          <div class="triple" style="gap: 12px;">
            <label>Same Host<input id="web-same-host" type="checkbox" checked style="width: 24px; height: 24px; margin-top: 8px;" /></label>
            <label>Robots.txt<input id="web-respect-robots" type="checkbox" checked style="width: 24px; height: 24px; margin-top: 8px;" /></label>
            <label>Incremental<input id="web-incremental" type="checkbox" checked style="width: 24px; height: 24px; margin-top: 8px;" /></label>
          </div>
        </div>
        <div class="triple">
          <label>Charset<input id="web-charset" value="UTF-8" /></label>
          <label>User Agent<input id="web-user-agent" value="AinsoftRagBot/1.0" /></label>
          <label>Source Profile<input id="web-source-load-profile" placeholder="default" /></label>
        </div>
        <div class="triple">
          <label>Allowed Domains<textarea id="web-allowed-domains" style="min-height: 60px;">example.com</textarea></label>
          <label>ACL<textarea id="web-acl" style="min-height: 60px;">group:admin</textarea></label>
          <label>Metadata<textarea id="web-metadata" style="min-height: 60px;">surface=web
source=website</textarea></label>
        </div>
        <div class="dual">
          <label>Seed URLs<textarea id="web-urls" style="min-height: 60px;">https://example.com</textarea></label>
          <label>Result Filter
            <select id="web-result-filter">
              <option value="all">All</option>
              <option value="sitemap">Sitemap</option>
              <option value="seed">Seed</option>
              <option value="link">Link</option>
              <option value="changed">Changed</option>
            </select>
          </label>
        </div>
        <div class="actions">
          <button id="btn-web-ingest">Run Web Ingest</button>
          <button id="btn-web-cancel" class="secondary" disabled>Cancel</button>
        </div>
        <div class="notice" id="web-notice"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Crawl Progress</h2><p>사전 수집, crawl, ingest 진행 상태를 단계별로 확인합니다.</p></div></div>
      <div class="stats" id="web-progress-summary"></div>
      <div id="web-progress-log"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>웹 ingest 응답 전문입니다.</p></div></div>
      <pre id="output-web-ingest">{'{}'}</pre>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Result Cards</h2><p>ingested, changed, skipped 결과를 상태별 카드로 표시합니다.</p></div></div>
      <div id="web-result-cards"></div>
    </article>
  </section>
</AdminPage>
