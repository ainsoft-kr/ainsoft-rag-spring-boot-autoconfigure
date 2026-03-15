<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Config Inspector"
  page="config"
  copy="Read-only configuration board with secret masking."
>
  <section class="grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Read Only Feed</h2><p>현재 프로세스에 적용된 설정을 새로고침합니다.</p></div></div>
      <div class="stack">
        <div class="actions"><button id="btn-config-refresh">Refresh Config</button></div>
        <div class="notice" id="config-notice"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Output</h2><p>설정 응답 전문입니다.</p></div></div>
      <pre id="output-config">{'{}'}</pre>
    </article>
  </section>
</AdminPage>

