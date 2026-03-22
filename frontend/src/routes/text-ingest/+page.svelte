<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Text Ingest"
  page="text-ingest"
  copy="Direct content upsert desk for operational notes and curated documents."
>
  <section class="grid text-ingest-grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Input Sheet</h2><p>Text ingest 작업은 Job History에 자동 기록됩니다.</p></div></div>
      <div class="stack">
        <div class="dual">
          <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
          <label>Recent Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
        </div>
        <div class="dual">
          <label>Doc ID<input id="ingest-doc-id" value="admin-note-001" /></label>
          <label>ACL<input id="ingest-acl" value="group:admin" /></label>
        </div>
        <div class="dual">
          <label>Incremental Ingest<input id="ingest-incremental" type="checkbox" checked /></label>
          <div></div>
        </div>
        <label>Metadata (`key=value`)<textarea id="ingest-metadata">surface=admin
category=notes</textarea></label>
        <label>Text<textarea id="ingest-text">Ainsoft RAG admin console document.</textarea></label>
        <div class="actions"><button id="btn-ingest">Ingest Text</button></div>
        <div class="notice" id="ingest-notice"></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>How It Works</h2><p>텍스트 문서는 빠르게 업서트하고, incremental 설정에 따라 중복을 건너뜁니다.</p></div></div>
      <div class="feature-grid">
        <div class="feature-card"><h3>Incremental</h3><p>같은 tenant와 docId의 내용이 같으면 `skipped`로 빠집니다.</p></div>
        <div class="feature-card"><h3>ACL</h3><p>comma 또는 줄바꿈 기준 principal 목록을 넣습니다.</p></div>
        <div class="feature-card"><h3>Metadata</h3><p>surface, category, source 같은 운영 라벨을 붙일 수 있습니다.</p></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>텍스트 ingest 응답 전문입니다.</p></div></div>
      <pre id="output-ingest">{'{}'}</pre>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Run Summary</h2><p>최근 실행의 상태를 한눈에 확인합니다.</p></div></div>
      <div id="ingest-summary"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Progress Trace</h2><p>작업 흐름을 단계별로 보여줍니다.</p></div></div>
      <div id="ingest-progress-log"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Result Cards</h2><p>ingested, changed, skipped 결과를 카드로 요약합니다.</p></div></div>
      <div class="tab-bar">
        <button class="tab-button active" id="ingest-tab-all" type="button">All Results</button>
        <button class="tab-button" id="ingest-tab-changed" type="button">Changed Only <span class="tab-count" id="ingest-tab-changed-count" hidden>0</span></button>
      </div>
      <div class="stack">
        <label>Result Filter
          <select id="ingest-result-filter">
            <option value="all">All</option>
            <option value="ingested">Ingested</option>
            <option value="changed">Changed</option>
            <option value="skipped">Skipped</option>
            <option value="failed">Failed</option>
          </select>
        </label>
      </div>
      <div class="tab-panel" id="ingest-result-panel-all">
        <div id="ingest-result-cards"></div>
      </div>
      <div class="tab-panel" id="ingest-result-panel-changed" hidden>
        <div id="ingest-result-cards-changed"></div>
      </div>
    </article>
  </section>
</AdminPage>
