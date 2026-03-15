<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Provider History"
  page="provider-history"
  copy="Provider telemetry monitor tuned for latency, failures, and fallback behavior."
>
  <section class="grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Sampling Controls</h2><p>recent window와 이력 길이를 조절합니다.</p></div></div>
      <div class="stack">
        <div class="dual">
          <label>Recent Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
          <label>History Limit<input id="provider-limit" type="number" value="120" /></label>
        </div>
        <div class="actions"><button id="btn-provider-refresh">Refresh Provider History</button></div>
        <div class="notice" id="provider-notice"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Provider Tables</h2><p>provider 샘플과 fallback 이벤트를 표로 봅니다.</p></div></div>
      <div class="stack">
        <div class="analytic-grid" id="provider-analytics"></div>
        <div id="provider-endpoint-bars"></div>
        <div id="provider-latency-chart"></div>
        <div id="provider-history-table"></div>
        <div id="provider-fallbacks-table"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Telemetry Feed</h2><p>현재 provider health와 history 응답 전문입니다.</p></div></div>
      <div class="grid">
        <div class="stack"><strong>Provider Health</strong><pre id="output-provider-health">{'{}'}</pre></div>
        <div class="stack"><strong>Provider History</strong><pre id="output-provider-history">{'{}'}</pre></div>
      </div>
    </article>
  </section>
</AdminPage>

