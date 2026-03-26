<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Provider History"
  page="provider-history"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Provider Tables</h2>
          <p>recent window와 이력 길이를 조절하고 provider 샘플 및 fallback 이벤트를 확인합니다.</p>
        </div>
        <div class="actions">
          <label class="field-inline">
            Window (ms)
            <input data-context="recentProviderWindowMillis" type="number" value="60000" style="width: 100px; margin-left: 8px;" />
          </label>
          <label class="field-inline" style="margin-left: 16px;">
            Limit
            <input id="provider-limit" type="number" value="120" style="width: 80px; margin-left: 8px;" />
          </label>
          <button id="btn-provider-refresh">Refresh Provider History</button>
        </div>
      </div>
      <div class="stack">
        <div class="notice" id="provider-notice"></div>
        <div id="provider-history-table"></div>
        <div id="provider-fallbacks-table"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Provider Analytics</h2>
          <p>provider 샘플 및 fallback 이벤트를 시각적으로 분석합니다.</p>
        </div>
      </div>
      <div class="stack">
        <div class="analytic-grid" id="provider-analytics"></div>
        <div id="provider-endpoint-bars"></div>
        <div id="provider-latency-chart"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Telemetry Feed</h2>
          <p>현재 provider health와 history 응답 전문입니다.</p>
        </div>
      </div>
      <div class="grid">
        <div class="stack"><strong>Provider Health</strong><pre id="output-provider-health">{'{}'}</pre></div>
        <div class="stack"><strong>Provider History</strong><pre id="output-provider-history">{'{}'}</pre></div>
      </div>
    </article>
  </section>
</AdminPage>

<style>
  .field-inline {
    display: flex;
    align-items: center;
    font-size: 0.88rem;
    color: var(--muted-strong);
    font-weight: 600;
  }
</style>
