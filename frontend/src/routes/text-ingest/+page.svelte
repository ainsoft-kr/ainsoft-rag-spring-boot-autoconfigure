<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Text Ingest"
  page="text-ingest"
  copy="Direct content upsert desk for operational notes and curated documents."
>
  <section class="grid">
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
        <label>Metadata (`key=value`)<textarea id="ingest-metadata">surface=admin
category=notes</textarea></label>
        <label>Text<textarea id="ingest-text">Ainsoft RAG admin console document.</textarea></label>
        <div class="actions"><button id="btn-ingest">Ingest Text</button></div>
        <div class="notice" id="ingest-notice"></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>Operator Guidance</h2><p>운영 메모, FAQ, runbook, 긴급 공지 같은 텍스트 자산을 빠르게 반영하는 데 적합합니다.</p></div></div>
      <div class="feature-grid">
        <div class="feature-card"><h3>ACL</h3><p>comma 또는 줄바꿈 기준 principal 목록을 넣습니다.</p></div>
        <div class="feature-card"><h3>Metadata</h3><p>surface, category, source 같은 운영 라벨을 붙일 수 있습니다.</p></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>텍스트 ingest 응답 전문입니다.</p></div></div>
      <pre id="output-ingest">{'{}'}</pre>
    </article>
  </section>
</AdminPage>

