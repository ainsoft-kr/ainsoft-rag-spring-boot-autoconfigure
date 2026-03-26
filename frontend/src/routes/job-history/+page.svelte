<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Job History"
  page="job-history"
  copy="Operational job tape for ingest, restore, rebuild, and retry flows."
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Job Table</h2>
          <p>최근 job history를 조회하고 retry 가능한 작업을 추립니다.</p>
        </div>
        <div class="actions">
          <label class="field-inline">
            Limit
            <input id="job-limit" type="number" value="100" style="width: 80px; margin-left: 8px;" />
          </label>
          <button id="btn-job-refresh">Refresh Job History</button>
        </div>
      </div>
      <div class="stack">
        <div class="notice" id="job-notice"></div>
        <div id="job-table"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Job Summary</h2>
          <p>성공/실패/실행 중 상태와 job type 분포를 요약합니다.</p>
        </div>
      </div>
      <div class="analytic-grid" id="job-analytics"></div>
      <div id="job-bars"></div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Feeds</h2>
          <p>job history 응답과 retry 응답을 함께 봅니다.</p>
        </div>
      </div>
      <div class="grid">
        <div class="stack"><strong>Job History</strong><pre id="output-job-history">{'{}'}</pre></div>
        <div class="stack"><strong>Retry Action</strong><pre id="output-job-action">{'{}'}</pre></div>
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
