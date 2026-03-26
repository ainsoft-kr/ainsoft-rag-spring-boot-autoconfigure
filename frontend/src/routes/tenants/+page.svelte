<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
  import { onMount } from 'svelte';

  function openOps() {
    const modal = document.getElementById('tenant-ops-modal');
    if (modal) modal.hidden = false;
  }

  function closeOps() {
    const modal = document.getElementById('tenant-ops-modal');
    if (modal) modal.hidden = true;
  }

  onMount(() => {
    // Direct selection to be absolutely sure
    const tabs = document.querySelectorAll('.tenants-tabs .tab-button');
    const panels = document.querySelectorAll('.tenants-panels .panel-group');

    tabs.forEach((tab) => {
      tab.onclick = () => {
        const target = tab.getAttribute('data-tab');
        
        tabs.forEach(t => t.classList.toggle('active', t === tab));
        panels.forEach(p => p.hidden = p.getAttribute('data-tab-panel') !== target);
      };
    });

    // Handle close buttons for the modal
    document.querySelectorAll('[data-modal-close="tenant-ops-modal"]').forEach((node) => {
      node.onclick = closeOps;
    });
  });
</script>

<AdminPage
  title="Ainsoft RAG Admin Tenants"
  page="tenants"
  copy="Tenant board with snapshot, restore, optimize, and metadata rebuild controls."
>
  <div class="tab-bar tenants-tabs">
    <button class="tab-button active" data-tab="tenants" type="button">Tenants</button>
    <button class="tab-button" data-tab="insights" type="button">Insights</button>
    <button class="tab-button" data-tab="feed" type="button">Feed</button>
  </div>

  <section class="grid tenants-panels">
    <div class="panel-group full" data-tab-panel="tenants">
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Tenant Browser</h2>
            <p>tenant 목록과 snapshot 목록을 동시에 확인합니다.</p>
          </div>
          <div class="actions">
            <button id="btn-tenant-ops-trigger" on:click={openOps}>Index Operations</button>
            <button id="btn-tenants-refresh">Refresh Tenants</button>
          </div>
        </div>
        <div class="stack">
          <label>Current Tenant<input data-context="tenantId" value="tenant-admin" /></label>
          <div class="notice" id="tenants-notice"></div>
          <div id="tenants-table"></div>
          <div id="snapshot-table"></div>
        </div>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="insights" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Tenant Summary</h2>
            <p>tenant 규모, snapshot 수, 최근 업데이트 분포를 요약합니다.</p>
          </div>
        </div>
        <div class="analytic-grid" id="tenant-analytics"></div>
        <div id="tenant-bars"></div>
      </article>

      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Snapshot Timeline</h2>
            <p>snapshot 생성 시각을 타임라인 형태로 봅니다.</p>
          </div>
        </div>
        <div id="snapshot-timeline"></div>
      </article>

      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Tenant Spotlight</h2>
            <p>선택 tenant의 규모와 복구 포인트를 카드형으로 요약합니다.</p>
          </div>
        </div>
        <div id="tenant-detail-cards"></div>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="feed" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Operations Feed</h2>
            <p>tenant 목록, 상세, 작업 응답을 함께 표시합니다.</p>
          </div>
        </div>
        <div class="grid">
          <div class="stack">
            <strong>Tenants</strong>
            <pre id="output-tenants">{'{}'}</pre>
          </div>
          <div class="stack">
            <strong>Tenant Detail</strong>
            <pre id="output-tenant-detail">{'{}'}</pre>
          </div>
          <div class="stack">
            <strong>Operations</strong>
            <pre id="output-tenant-operations">{'{}'}</pre>
          </div>
        </div>
      </article>
    </div>
  </section>

  <div id="tenant-ops-modal" class="modal" hidden>
    <div class="modal-backdrop" data-modal-close="tenant-ops-modal"></div>
    <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="tenant-ops-title">
      <div class="modal-head">
        <div>
          <h2 id="tenant-ops-title">Index Operations</h2>
          <p>tenant 조회, 삭제, snapshot/restore, optimize, metadata rebuild를 실행합니다.</p>
        </div>
        <button type="button" class="secondary" data-modal-close="tenant-ops-modal">Close</button>
      </div>
      <div class="stack">
        <div class="dual">
          <label>Selected Tenant<input id="tenant-selected-id" /></label>
          <label>Snapshot Tag<input id="tenant-snapshot-tag" value="ops-snapshot" /></label>
        </div>
        <label>Restore Tag<input id="tenant-restore-tag" /></label>
        <div class="actions">
          <button class="secondary" id="btn-tenant-detail">Load Tenant</button>
          <button class="secondary" id="btn-tenant-delete">Delete Tenant</button>
          <button id="btn-snapshot">Snapshot</button>
          <button class="secondary" id="btn-restore">Restore</button>
          <button id="btn-optimize">Optimize</button>
          <button class="secondary" id="btn-rebuild-metadata">Rebuild Metadata</button>
        </div>
      </div>
    </div>
  </div>
</AdminPage>
