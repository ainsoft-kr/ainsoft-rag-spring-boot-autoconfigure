<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Search"
  page="search"
  copy="Diagnostic retrieval board with audit-aware search flow."
>
  <section class="grid">
    <article class="panel">
      <div class="panel-header"><div><h2>Search Controls</h2><p>ACL principal 기준 검색과 진단을 실행합니다.</p></div></div>
      <div class="stack">
        <div class="dual">
          <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
          <label>Recent Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
        </div>
        <div class="dual">
          <label>Principals<input id="search-principals" value="group:admin" /></label>
          <label>Top K<input id="search-topk" type="number" value="5" /></label>
        </div>
        <label>Query<input id="search-query" value="hybrid retrieval" /></label>
        <label>Metadata Filter (`key=value`)<textarea id="search-filter" placeholder="surface=admin"></textarea></label>
        <div class="actions">
          <button id="btn-search">Run Search</button>
          <button class="secondary" id="btn-diagnose">Run Diagnostics</button>
        </div>
        <div class="notice" id="search-notice"></div>
      </div>
    </article>

    <article class="panel">
      <div class="panel-header"><div><h2>Desk Notes</h2><p>여기서 실행한 검색은 Search Audit 페이지에서 역할, 쿼리, 결과 수 기준으로 다시 조회할 수 있습니다.</p></div></div>
      <div class="feature-grid">
        <div class="feature-card"><h3>Query</h3><p>질의와 metadata filter를 함께 보내 hybrid retrieval 조건을 실험합니다.</p></div>
        <div class="feature-card"><h3>Diagnostics</h3><p>ACL 적용 전후 lexical/vector 매치를 비교합니다.</p></div>
        <div class="feature-card"><h3>Providers</h3><p>fallback 이력과 최근 telemetry 창을 함께 추적합니다.</p></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Search Summary</h2><p>최근 검색 실행의 hit, fallback, provider 사용 현황을 요약합니다.</p></div></div>
      <div class="analytic-grid" id="search-analytics"></div>
      <div id="search-provider-bars"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Hit Cards</h2><p>상위 검색 hit를 카드형으로 훑어봅니다.</p></div></div>
      <div id="search-hit-cards"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Score Trend</h2><p>검색 hit score를 큰 차트로 시각화합니다.</p></div></div>
      <div id="search-score-chart"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Diagnostics Insight</h2><p>진단 응답을 카드형 인사이트로 재구성합니다.</p></div></div>
      <div id="search-diagnostic-cards"></div>
    </article>

    <article class="panel full">
      <div class="panel-header"><div><h2>Search Streams</h2><p>검색 응답과 진단 응답을 나란히 표시합니다.</p></div></div>
      <div class="grid">
        <div class="stack"><strong>Search</strong><pre id="output-search">{'{}'}</pre></div>
        <div class="stack"><strong>Diagnostics</strong><pre id="output-diagnostics">{'{}'}</pre></div>
      </div>
    </article>
  </section>
</AdminPage>

