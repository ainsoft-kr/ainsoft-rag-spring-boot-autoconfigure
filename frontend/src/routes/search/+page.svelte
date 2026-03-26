<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Search"
  page="search"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div style="display: flex; align-items: center;">
          <h2>Search Controls</h2>
          <span class="help-icon">?
            <div class="tooltip">
              <h3 style="color: var(--accent);">Desk Notes</h3>
              <p>여기서 실행한 검색은 Search Audit 페이지에서 역할, 쿼리, 결과 수 기준으로 다시 조회할 수 있습니다.</p>
              <h3>Query</h3>
              <p>질의와 metadata filter를 함께 보내 hybrid retrieval 조건을 실험합니다.</p>
              <h3>Diagnostics</h3>
              <p>ACL 적용 전후 lexical/vector 매치를 비교합니다.</p>
              <h3>Thresholds</h3>
              <p>finalConfidence와 top hit score가 낮으면 결과를 아예 숨깁니다. Exact term mode는 원형 비교와 AND/OR 결합을 지원합니다.</p>
              <h3>Providers</h3>
              <p>fallback 이력과 최근 telemetry 창을 함께 추적합니다.</p>
            </div>
          </span>
        </div>
        <p>ACL principal 기준 검색과 진단을 실행합니다.</p>
      </div>
      <div class="stack">
        <div class="triple">
          <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
          <label>Recent Provider Window (ms)<input data-context="recentProviderWindowMillis" type="number" value="60000" /></label>
          <label>Query<input id="search-query" value="hybrid retrieval" /></label>
        </div>
        <div class="triple">
          <label>Principals<input id="search-principals" value="group:admin" /></label>
          <label>Top K<input id="search-topk" type="number" value="5" /></label>
          <label>Metadata Filter (`key=value`)<textarea id="search-filter" placeholder="surface=admin" style="min-height: 42px;"></textarea></label>
        </div>
        <div class="triple">
          <div class="dual" style="gap: 12px;">
            <label>Final Confidence<input id="search-final-confidence" type="number" step="0.01" value="0.45" /></label>
            <label>Top Hit Score<input id="search-top-hit-score" type="number" step="0.01" value="0.03" /></label>
          </div>
          <div class="stack">
            <label>Compare As
              <select id="search-exact-compare">
                <option value="literal">Literal</option>
                <option value="nori" selected>Nori stem/lemma</option>
              </select>
            </label>
          </div>
          <div class="stack">
            <label>Combine Tokens
              <select id="search-exact-combine">
                <option value="and" selected>AND</option>
                <option value="or">OR</option>
              </select>
            </label>
          </div>
        </div>
        <div class="actions">
          <button class="secondary" type="button" data-threshold-preset="strict">Strict</button>
          <button class="secondary" type="button" data-threshold-preset="balanced">Balanced</button>
          <button class="secondary" type="button" data-threshold-preset="permissive">Permissive</button>
          <label class="toggle-row">
            <input id="search-exact-match" type="checkbox" />
            <span>Exact term match</span>
          </label>
          <button id="btn-search">Run Search</button>
          <button class="secondary" id="btn-diagnose">Run Diagnostics</button>
        </div>
        <div class="status-chip" id="search-threshold-summary">finalConfidence 0.45+, topHitScore 0.03+, exact match off, compare nori, combine and</div>
        <div class="notice" id="search-notice"></div>
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
      <div class="panel-header">
        <div><h2>Exact Match Debug</h2><p>exact term mode에서 어떤 토큰이 비교되고 어떤 hit가 통과했는지 확인합니다.</p></div>
        <div class="actions">
          <button class="secondary" type="button" id="btn-copy-exact-query">Copy Query Tokens</button>
          <button class="secondary" type="button" id="btn-copy-exact-hits">Copy Hit Tokens</button>
          <button class="secondary" type="button" id="btn-download-exact-json">Download JSON</button>
          <button class="secondary" type="button" id="btn-download-exact-csv">Download CSV</button>
        </div>
      </div>
      <div id="search-exact-debug-cards"></div>
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
