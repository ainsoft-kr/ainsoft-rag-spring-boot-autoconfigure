<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Search Audit"
  page="search-audit"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Audit Table</h2>
          <p>최근 검색 감사 로그를 지정한 길이만큼 불러옵니다.</p>
        </div>
        <div class="actions">
          <label class="field-inline">
            Limit
            <input id="audit-limit" type="number" value="100" style="width: 80px; margin-left: 8px;" />
          </label>
          <button id="btn-audit-refresh">Refresh Search Audit</button>
        </div>
      </div>
      <div class="stack">
        <div class="notice" id="audit-notice"></div>
        <div id="audit-table"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Audit Summary</h2>
          <p>검색 성공량, fallback, topK 경향을 요약 카드로 보여 줍니다.</p>
        </div>
      </div>
      <div class="analytic-grid" id="audit-analytics"></div>
      <div id="audit-bars"></div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Output</h2>
          <p>search audit 응답 전문입니다.</p>
        </div>
      </div>
      <pre id="output-search-audit">{'{}'}</pre>
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
