<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin File Ingest"
  page="file-ingest"
  copy="Upload-focused ingest pipeline for binary and text assets."
>
  <section class="grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Upload Sheet</h2><p>업로드 기반 ingest 역시 Job History에서 추적됩니다.</p></div></div>
      <div class="stack">
        <div class="dual">
          <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
          <label>Recent Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
        </div>
        <div class="dual">
          <label>Upload Doc ID<input id="upload-doc-id" value="admin-upload-001" /></label>
          <label>Upload ACL<input id="upload-acl" value="group:admin" /></label>
        </div>
        <div class="dual">
          <label>Incremental Ingest<input id="upload-incremental" type="checkbox" checked /></label>
          <div></div>
        </div>
        <label>Upload Metadata (`key=value`)<textarea id="upload-metadata">surface=upload</textarea></label>
        <label>File<input id="upload-file" type="file" /></label>
        <div class="actions"><button id="btn-upload">Upload File</button></div>
        <div class="notice" id="upload-notice"></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>How It Works</h2><p>업로드 문서는 파일 타입에 따라 파싱되고, 같은 내용이면 재업로드를 건너뜁니다.</p></div></div>
      <div class="feature-grid">
        <div class="feature-card"><h3>Incremental</h3><p>같은 tenant와 docId에 대해 내용이 같으면 `skipped`로 처리합니다.</p></div>
        <div class="feature-card"><h3>Binary parsing</h3><p>pdf/docx/pptx는 parser pipeline을 통해 normalized text로 변환됩니다.</p></div>
        <div class="feature-card"><h3>Traceability</h3><p>업로드 후 문서 탐색기에서 docId 기준으로 바로 추적할 수 있습니다.</p></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>파일 ingest 응답 전문입니다.</p></div></div>
      <pre id="output-upload">{'{}'}</pre>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Run Summary</h2><p>업로드 실행의 처리 결과를 요약합니다.</p></div></div>
      <div id="upload-summary"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Progress Trace</h2><p>업로드 파싱과 ingest 과정을 단계별로 보여줍니다.</p></div></div>
      <div id="upload-progress-log"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Result Cards</h2><p>ingested, changed, skipped 상태를 카드로 보여줍니다.</p></div></div>
      <div class="tab-bar">
        <button class="tab-button active" id="upload-tab-all" type="button">All Results</button>
        <button class="tab-button" id="upload-tab-changed" type="button">Changed Only <span class="tab-count" id="upload-tab-changed-count" hidden>0</span></button>
      </div>
      <div class="stack">
        <label>Result Filter
          <select id="upload-result-filter">
            <option value="all">All</option>
            <option value="ingested">Ingested</option>
            <option value="changed">Changed</option>
            <option value="skipped">Skipped</option>
            <option value="failed">Failed</option>
          </select>
        </label>
      </div>
      <div class="tab-panel" id="upload-result-panel-all">
        <div id="upload-result-cards"></div>
      </div>
      <div class="tab-panel" id="upload-result-panel-changed" hidden>
        <div id="upload-result-cards-changed"></div>
      </div>
    </article>
  </section>
</AdminPage>
