<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
  import { onMount } from 'svelte';

  function openDetail() {
    const modal = document.getElementById('docs-detail-modal');
    if (modal) modal.hidden = false;
  }

  function closeDetail() {
    const modal = document.getElementById('docs-detail-modal');
    if (modal) modal.hidden = true;
  }

  onMount(() => {
    // Tab logic - Use direct onclick for better reliability in this environment
    const tabs = document.querySelectorAll('.docs-tabs .tab-button');
    const panels = document.querySelectorAll('.docs-panels .panel-group');

    tabs.forEach((tab) => {
      tab.onclick = () => {
        const target = tab.getAttribute('data-tab');
        
        tabs.forEach(t => t.classList.toggle('active', t === tab));
        panels.forEach(p => p.hidden = p.getAttribute('data-tab-panel') !== target);
      };
    });

    // Handle close buttons for the modal
    document.querySelectorAll('[data-modal-close="docs-detail-modal"]').forEach((node) => {
      node.onclick = closeDetail;
    });

    // Add a listener to the table container to open modal on table actions
    const tableContainer = document.getElementById('documents-table');
    if (tableContainer) {
      tableContainer.addEventListener('click', (e) => {
        if (e.target.closest('button')) {
          setTimeout(openDetail, 50);
        }
      });
    }
  });
</script>

<AdminPage
  title="Ainsoft RAG Admin Documents"
  page="documents"
  copy=""
>
  <div class="tab-bar docs-tabs">
    <button class="tab-button active" data-tab="browse" type="button">Browse</button>
    <button class="tab-button" data-tab="insights" type="button">Insights</button>
    <button class="tab-button" data-tab="feeds" type="button">Feeds</button>
  </div>

  <section class="grid docs-panels">
    <div class="panel-group full" data-tab-panel="browse">
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Browse Filters</h2>
            <p>tenant 범위에서 문서를 검색하고 목록을 새로고침합니다.</p>
          </div>
          <div class="actions">
            <button id="btn-docs-detail-trigger" on:click={openDetail}>Detail Workspace</button>
            <button id="btn-documents-refresh">Refresh Documents</button>
          </div>
        </div>
        <div class="stack">
          <div class="triple">
            <label>Tenant ID<input data-context="tenantId" value="tenant-admin" /></label>
            <label>Query<input id="docs-query" placeholder="doc id or metadata" /></label>
            <label>Limit<input id="docs-limit" type="number" value="100" /></label>
          </div>
          <div class="notice" id="documents-notice"></div>
          <div id="documents-table"></div>
        </div>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="insights" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Document Summary</h2>
            <p>문서 수, 청크 수, content kind, source 분포를 요약합니다.</p>
          </div>
        </div>
        <div class="analytic-grid" id="document-analytics"></div>
        <div id="document-bars"></div>
      </article>

      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Chunk Timeline</h2>
            <p>선택한 문서의 chunk/page/offset 흐름을 타임라인으로 확인합니다.</p>
          </div>
        </div>
        <div id="document-timeline"></div>
      </article>

      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Document Spotlight</h2>
            <p>선택 문서의 ACL, metadata, source, chunk 구성을 카드형으로 요약합니다.</p>
          </div>
        </div>
        <div id="document-detail-cards"></div>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="feeds" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Document Feeds</h2>
            <p>목록, 상세, 액션 응답, source preview를 나란히 봅니다.</p>
          </div>
        </div>
        <div class="grid">
          <div class="stack">
            <strong>Documents</strong>
            <pre id="output-documents">{'{}'}</pre>
          </div>
          <div class="stack">
            <strong>Document Detail</strong>
            <pre id="output-document-detail">{'{}'}</pre>
          </div>
          <div class="stack">
            <strong>Action Response</strong>
            <pre id="output-document-action">{'{}'}</pre>
          </div>
          <div class="stack">
            <strong>Source Preview</strong>
            <pre id="output-source-preview">{'{}'}</pre>
          </div>
        </div>
      </article>
    </div>
  </section>

  <div id="docs-detail-modal" class="modal" hidden>
    <div class="modal-backdrop" data-modal-close="docs-detail-modal"></div>
    <div class="modal-card modal-wide" role="dialog" aria-modal="true" aria-labelledby="docs-detail-title">
      <div class="modal-head">
        <div>
          <h2 id="docs-detail-title">Detail Workspace</h2>
          <p>선택 문서의 상세 정보와 운영 액션을 한 카드에서 처리합니다.</p>
        </div>
        <button type="button" class="secondary" data-modal-close="docs-detail-modal">Close</button>
      </div>
      <div class="stack">
        <div class="dual">
          <label>Selected Tenant<input id="docs-selected-tenant" /></label>
          <label>Selected Doc ID<input id="docs-selected-id" /></label>
        </div>
        <div class="dual">
          <label>Source URI<input id="docs-selected-source" /></label>
          <label>Charset<input id="docs-selected-charset" value="UTF-8" /></label>
        </div>
        <div class="dual">
          <label>ACL<textarea id="docs-selected-acl"></textarea></label>
          <label>Metadata (`key=value`)<textarea id="docs-selected-metadata"></textarea></label>
        </div>
        <label>Reindex Text Override<textarea id="docs-selected-text" placeholder="비워두면 sourceUri 또는 기존 원문을 사용합니다."></textarea></label>
        <div class="triple">
          <label>Preview Chunk<select id="docs-selected-chunk"></select></label>
          <label>Preview Context Chars<input id="docs-preview-context" type="number" value="140" /></label>
          <div class="actions">
            <button class="secondary" id="btn-document-detail">Load Detail</button>
            <button class="secondary" id="btn-document-preview">Source Preview</button>
            <button id="btn-document-reindex">Reindex</button>
            <button class="secondary" id="btn-document-delete">Delete</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</AdminPage>
