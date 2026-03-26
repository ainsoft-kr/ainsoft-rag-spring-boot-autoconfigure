<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Config Inspector"
  page="config"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Output</h2>
          <p>설정 응답 전문입니다. 현재 프로세스에 적용된 설정을 확인하고 새로고침할 수 있습니다.</p>
        </div>
        <div class="actions">
          <button id="btn-config-refresh">Refresh Config</button>
        </div>
      </div>
      <div class="stack">
        <div class="notice" id="config-notice"></div>
        <pre id="output-config">{'{}'}</pre>
      </div>
    </article>
  </section>
</AdminPage>
