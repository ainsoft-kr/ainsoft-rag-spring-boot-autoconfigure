<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
</script>

<AdminPage
  title="Ainsoft RAG Admin Graph"
  page="graph"
  copy=""
>
  <section class="grid">
    <article class="panel full">
      <div class="panel-header">
        <div style="display: flex; align-items: center;">
          <h2>Graph Controls</h2>
          <span class="help-icon">?
            <div class="tooltip">
              <h3 style="color: var(--accent);">Graph Notes</h3>
              <p>이 뷰는 Lucene 인덱스를 대체하지 않고, 문서 관계와 ACL, source link를 보조적으로 보여줍니다.</p>
              <h3>Document</h3>
              <p>문서, chunk, source, principal, metadata 연결을 빠르게 펼칩니다.</p>
              <h3>Entity</h3>
              <p>normalized token 기반 엔티티 노드와 관계를 탐색합니다.</p>
              <h3>Path</h3>
              <p>document-to-document, entity-to-document 경로를 확인합니다.</p>
              <h3>FalkorDB Ready</h3>
              <p>현재 MVP는 in-memory projection이며, 저장소 구현은 FalkorDB adapter로 교체할 수 있습니다.</p>
            </div>
          </span>
        </div>
        <p>tenant, 문서, 엔티티, depth를 선택해서 subgraph를 조회합니다.</p>
      </div>
      <div class="stack">
        <div class="triple">
          <div class="dual" style="gap: 12px;">
            <label>Tenant ID<input id="graph-tenant-id" data-context="tenantId" value="tenant-admin" /></label>
            <label>Depth<input id="graph-depth" type="number" value="1" min="1" max="5" /></label>
          </div>
          <label>Document ID<input id="graph-doc-id" placeholder="doc-id" /></label>
          <label>Entity ID<input id="graph-entity-id" placeholder="entity-id" /></label>
        </div>
        <div class="triple">
          <label>Root Node ID<input id="graph-root-id" placeholder="document:tenant-admin|doc-1" /></label>
          <label>Relation Filter<input id="graph-relation-filter" placeholder="MENTIONS, ALLOWED_FOR" /></label>
          <div class="dual" style="gap: 8px;">
            <label>Path From<input id="graph-path-from" placeholder="from id" /></label>
            <label>Path To<input id="graph-path-to" placeholder="to id" /></label>
          </div>
        </div>
        <div class="triple">
          <div class="dual" style="gap: 8px;">
            <label>Layout
              <select id="graph-layout">
                <option value="cose" selected>Force Directed</option>
                <option value="circle">Circle</option>
                <option value="concentric">Concentric</option>
                <option value="grid">Grid</option>
              </select>
            </label>
            <label>Canvas Scale
              <select id="graph-scale">
                <option value="normal" selected>Normal</option>
                <option value="dense">Dense</option>
                <option value="compact">Compact</option>
              </select>
            </label>
          </div>
          <div class="dual" style="gap: 8px;">
            <label>Extractor Preset
              <select id="graph-extraction-preset">
                <option value="default" selected>Balanced Technical</option>
                <option value="technical">Technical Docs</option>
                <option value="korean">Korean Docs</option>
                <option value="policy">Policy / Regulation</option>
                <option value="website">Website / Crawl</option>
              </select>
            </label>
            <label>Graph Preset
              <select id="graph-preset">
                <option value="explore" selected>Explore</option>
                <option value="dense">Dense</option>
                <option value="path">Path Focus</option>
                <option value="entity">Entity Focus</option>
              </select>
            </label>
          </div>
          <div class="actions tight" style="justify-content: center; align-items: flex-end; padding-bottom: 4px;">
            <button type="button" class="secondary mini-chip graph-action" id="btn-graph-preset-explore">Explore</button>
            <button type="button" class="secondary mini-chip graph-action" id="btn-graph-preset-dense">Dense</button>
            <button type="button" class="secondary mini-chip graph-action" id="btn-graph-preset-path">Path</button>
            <button type="button" class="secondary mini-chip graph-action" id="btn-graph-preset-entity">Entity</button>
          </div>
        </div>
        <div class="actions">
          <button id="btn-graph-stats">Refresh Stats</button>
          <button class="secondary" id="btn-graph-document">Load Document Graph</button>
          <button class="secondary" id="btn-graph-entity">Load Entity Graph</button>
          <button id="btn-graph-subgraph">Load Subgraph</button>
          <button class="secondary" id="btn-graph-path">Load Path</button>
        </div>
        <div class="notice" id="graph-notice"></div>
      </div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Graph Summary</h2>
          <p>tenant 단위로 node, edge, document, entity, source, principal 개수를 요약합니다.</p>
        </div>
      </div>
      <div class="analytic-grid" id="graph-analytics"></div>
      <div id="graph-bars"></div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Graph Canvas</h2>
          <p>선택한 subgraph를 시각화할 공간입니다.</p>
        </div>
      </div>
      <div class="graph-canvas" id="graph-canvas"></div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Node Inspector</h2>
          <p>선택 노드의 속성과 인접 엣지를 확인합니다.</p>
        </div>
      </div>
      <div id="graph-node-cards"></div>
    </article>

    <article class="panel full">
      <div class="panel-header">
        <div>
          <h2>Graph Feeds</h2>
          <p>stats, document graph, entity graph, subgraph 응답을 그대로 봅니다.</p>
        </div>
      </div>
      <div class="grid">
        <div class="stack"><strong>Stats</strong><pre id="output-graph-stats">{'{}'}</pre></div>
        <div class="stack"><strong>Document Graph</strong><pre id="output-graph-document">{'{}'}</pre></div>
        <div class="stack"><strong>Entity Graph</strong><pre id="output-graph-entity">{'{}'}</pre></div>
        <div class="stack"><strong>Subgraph</strong><pre id="output-graph-subgraph">{'{}'}</pre></div>
        <div class="stack"><strong>Path</strong><pre id="output-graph-path">{'{}'}</pre></div>
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
