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
        <label>Upload Metadata (`key=value`)<textarea id="upload-metadata">surface=upload</textarea></label>
        <label>File<input id="upload-file" type="file" /></label>
        <div class="actions"><button id="btn-upload">Upload File</button></div>
        <div class="notice" id="upload-notice"></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>Supported Flow</h2><p>운영 문서나 발표 자료를 빠르게 업로드하고 preview는 Documents 페이지에서 확인합니다.</p></div></div>
      <div class="feature-grid">
        <div class="feature-card"><h3>Binary parsing</h3><p>pdf/docx/pptx는 parser pipeline을 통해 normalized text로 변환됩니다.</p></div>
        <div class="feature-card"><h3>Traceability</h3><p>업로드 후 문서 탐색기에서 docId 기준으로 바로 추적할 수 있습니다.</p></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>파일 ingest 응답 전문입니다.</p></div></div>
      <pre id="output-upload">{'{}'}</pre>
    </article>
  </section>
</AdminPage>
