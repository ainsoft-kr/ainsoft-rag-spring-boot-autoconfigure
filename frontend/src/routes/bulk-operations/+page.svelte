<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Bulk Operations"
  page="bulk-operations"
  copy="Batch workspace for bulk ingest, delete, and metadata patch operations."
>
  <section class="grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Bulk Text Ingest</h2><p>문서 배열 JSON을 넣어 여러 문서를 한 번에 색인합니다.</p></div></div>
      <div class="stack">
        <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
        <label>Documents JSON<textarea id="bulk-ingest-json">{`[
  {
    "docId": "bulk-001",
    "text": "bulk sample document",
    "acl": ["group:admin"],
    "metadata": {
      "surface": "bulk"
    }
  }
]`}</textarea></label>
        <div class="actions"><button id="btn-bulk-ingest">Run Bulk Text Ingest</button></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>Bulk Delete / Patch</h2><p>쉼표 또는 줄바꿈 기준 doc id 목록을 입력합니다.</p></div></div>
      <div class="stack">
        <label>Delete Doc IDs<textarea id="bulk-delete-docs">bulk-001</textarea></label>
        <div class="actions"><button class="secondary" id="btn-bulk-delete">Run Bulk Delete</button></div>
        <label>Patch Doc IDs<textarea id="bulk-patch-docs">bulk-001</textarea></label>
        <label>Patch Metadata (`key=value`)<textarea id="bulk-patch-metadata">surface=patched</textarea></label>
        <div class="actions"><button id="btn-bulk-patch">Run Metadata Patch</button></div>
        <div class="notice" id="bulk-notice"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>bulk 작업 응답 전문입니다.</p></div></div>
      <pre id="output-bulk">{'{}'}</pre>
    </article>
  </section>
</AdminPage>
