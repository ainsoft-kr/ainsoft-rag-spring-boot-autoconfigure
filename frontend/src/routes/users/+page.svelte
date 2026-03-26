<script>
  import AdminPage from '$lib/admin/AdminPage.svelte';
  import { onMount } from 'svelte';

  function openEditor() {
    const modal = document.getElementById('users-editor-modal');
    if (modal) modal.hidden = false;
  }

  function closeEditor() {
    const modal = document.getElementById('users-editor-modal');
    if (modal) modal.hidden = true;
  }

  onMount(() => {
    // Tab logic
    const tabButtons = document.querySelectorAll('#users-page-tabs .tab-button');
    const tabPanels = document.querySelectorAll('#users-page-panels .panel-group');

    tabButtons.forEach((btn) => {
      btn.addEventListener('click', () => {
        const target = btn.getAttribute('data-tab');
        
        tabButtons.forEach(b => b.classList.toggle('active', b === btn));
        tabPanels.forEach(p => p.hidden = p.getAttribute('data-tab-panel') !== target);
      });
    });

    // Watch for mode changes from app.js to open editor modal
    const modeNode = document.getElementById('users-mode');
    if (modeNode) {
      const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
          if (mutation.type === 'childList') {
            const text = modeNode.textContent || '';
            if (text.startsWith('Editing')) {
              openEditor();
            }
          }
        });
      });
      observer.observe(modeNode, { childList: true });
    }

    // Handle close buttons for modals
    document.querySelectorAll('[data-modal-close="users-editor-modal"]').forEach((node) => {
      node.addEventListener('click', closeEditor);
    });
  });
</script>

<AdminPage
  title="Ainsoft RAG Admin Users"
  page="users"
  copy="Persisted account board with roles, password rotation, and lockout safeguards."
>
  <div class="tab-bar" id="users-page-tabs">
    <button class="tab-button active" data-tab="accounts" type="button">Accounts</button>
    <button class="tab-button" data-tab="detail" type="button">Detail</button>
    <button class="tab-button" data-tab="audit" type="button">Audit</button>
  </div>

  <section class="grid" id="users-page-panels">
    <div class="panel-group" data-tab-panel="accounts">
      <article class="panel">
        <div class="panel-header">
          <div>
            <h2>Accounts</h2>
            <p>Persisted users and role assignments.</p>
          </div>
          <div class="actions">
            <button id="btn-users-create-trigger" on:click={openEditor}>Create User</button>
            <button id="btn-users-refresh" type="button" class="secondary">Refresh Users</button>
          </div>
        </div>
        <div class="form-grid">
          <label class="field">
            Search
            <input id="users-search" autocomplete="off" spellcheck="false" placeholder="username, name, or role" />
          </label>
          <label class="field">
            Sort By
            <select id="users-sort-field">
              <option value="username">Username</option>
              <option value="displayName">Display Name</option>
              <option value="enabled">Enabled</option>
              <option value="roles">Roles</option>
            </select>
          </label>
          <label class="field">
            Page Size
            <select id="users-page-size">
              <option value="5">5</option>
              <option value="10" selected>10</option>
              <option value="25">25</option>
            </select>
          </label>
          <label class="field">
            Sort Direction
            <select id="users-sort-direction">
              <option value="asc" selected>Ascending</option>
              <option value="desc">Descending</option>
            </select>
          </label>
        </div>
        <div class="actions">
          <button id="btn-users-prev" type="button" class="secondary">Previous</button>
          <button id="btn-users-next" type="button" class="secondary">Next</button>
          <span id="users-page-info" class="helper">Page 1</span>
        </div>
        <div class="notice" id="users-notice"></div>
        <div id="users-table"></div>
      </article>

      <article class="panel">
        <div class="panel-header">
          <div>
            <h2>Account Summary</h2>
            <p>Enabled accounts, admin coverage, and password state.</p>
          </div>
        </div>
        <div class="analytic-grid" id="users-analytics"></div>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="detail" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Selected User</h2>
            <p>Inline user detail and latest activity snapshot.</p>
          </div>
        </div>
        <div class="actions">
          <button id="btn-users-export-audit" type="button" class="secondary">Export Audit CSV</button>
        </div>
        <div id="users-selected-panel"></div>
        <div class="stack">
          <div class="section-note">Role matrix for the current admin configuration.</div>
          <div id="users-selected-matrix"></div>
        </div>
        <div class="stack">
          <div class="section-note">Recent events for the selected user.</div>
          <div id="users-selected-timeline"></div>
        </div>
      </article>

      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Mutation Output</h2>
            <p>Latest user list or create/update/delete response.</p>
          </div>
        </div>
        <pre id="output-users">{'{}'}</pre>
        <pre id="output-users-action">{'{}'}</pre>
      </article>
    </div>

    <div class="panel-group full" data-tab-panel="audit" hidden>
      <article class="panel full">
        <div class="panel-header">
          <div>
            <h2>Change Audit</h2>
            <p>Create, update, reset password, and delete actions.</p>
          </div>
        </div>
        <div class="form-grid">
          <label class="field">
            Audit Filter
            <select id="users-audit-action">
              <option value="all" selected>All actions</option>
              <option value="CREATE">Create</option>
              <option value="UPDATE">Update</option>
              <option value="RESET_PASSWORD">Reset Password</option>
              <option value="DELETE">Delete</option>
            </select>
          </label>
          <label class="field">
            Audit Search
            <input id="users-audit-search" autocomplete="off" spellcheck="false" placeholder="actor, target, message" />
          </label>
          <label class="field">
            Audit From
            <input id="users-audit-from" type="date" />
          </label>
          <label class="field">
            Audit To
            <input id="users-audit-to" type="date" />
          </label>
          <label class="field">
            Audit Page Size
            <select id="users-audit-page-size">
              <option value="5">5</option>
              <option value="10" selected>10</option>
              <option value="25">25</option>
            </select>
          </label>
        </div>
        <div class="actions">
          <button id="btn-users-audit-preset-7d" type="button" class="secondary">Recent 7 Days</button>
          <button id="btn-users-audit-preset-30d" type="button" class="secondary">Recent 30 Days</button>
          <button id="btn-users-audit-preset-month" type="button" class="secondary">This Month</button>
        </div>
        <div class="actions">
          <button id="btn-users-audit-clear" type="button" class="secondary">Clear Filters</button>
          <button id="btn-users-audit-prev" type="button" class="secondary">Previous</button>
          <button id="btn-users-audit-next" type="button" class="secondary">Next</button>
          <span id="users-audit-page-info" class="helper">Page 1</span>
        </div>
        <div class="section-note" id="users-audit-summary">Filtered audit rows and timeline view.</div>
        <div id="users-audit-timeline"></div>
        <div id="users-audit-table"></div>
      </article>
    </div>
  </section>

  <div id="users-editor-modal" class="modal" hidden>
    <div class="modal-backdrop" data-modal-close="users-editor-modal"></div>
    <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="users-editor-title">
      <div class="modal-head">
        <div>
          <h2 id="users-editor-title">Account Editor</h2>
          <p>Create and update persisted admin users.</p>
        </div>
        <button type="button" class="secondary" data-modal-close="users-editor-modal">Close</button>
      </div>
      <div class="stack">
        <div class="helper">Mode: <strong id="users-mode" class="mono">Creating new user</strong></div>
        <div class="helper">Audit: <strong id="users-audit-count" class="mono">0</strong></div>
        <div class="form-grid">
          <label class="field">
            Username
            <input id="users-username" autocomplete="username" spellcheck="false" />
          </label>
          <label class="field">
            Display Name
            <input id="users-display-name" autocomplete="name" spellcheck="false" />
          </label>
          <label class="field">
            Password
            <input id="users-password" type="password" autocomplete="new-password" />
            <span class="helper" id="users-password-hint">Password is required for new users.</span>
          </label>
          <label class="field">
            Roles
            <textarea id="users-roles" rows="4">ADMIN</textarea>
            <span class="helper">One role per line or comma-separated. Use ADMIN, OPS, or AUDITOR.</span>
          </label>
          <label class="field inline">
            <input id="users-enabled" type="checkbox" checked />
            Enabled
          </label>
        </div>
        <div class="actions">
          <button id="btn-users-save">Create User</button>
          <button id="btn-users-clear" type="button" class="secondary">Clear Form</button>
        </div>
        <div class="notice" id="users-notice"></div>
      </div>
    </div>
  </div>

  <div id="users-reset-modal" class="modal" hidden>
    <div class="modal-backdrop" data-modal-close="users-reset-modal"></div>
    <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="users-reset-title">
      <div class="modal-head">
        <div>
          <h2 id="users-reset-title">Reset Password</h2>
          <p id="users-reset-target" class="helper">No user selected</p>
        </div>
        <button type="button" class="secondary" data-modal-close="users-reset-modal">Close</button>
      </div>
      <div class="stack">
        <label class="field">
          New Password
          <input id="users-reset-password" type="password" autocomplete="new-password" />
        </label>
        <div class="actions">
          <button id="btn-users-reset-submit" type="button">Reset Password</button>
          <button id="btn-users-reset-cancel" type="button" class="secondary">Cancel</button>
        </div>
      </div>
    </div>
  </div>

  <div id="users-audit-modal" class="modal" hidden>
    <div class="modal-backdrop" data-modal-close="users-audit-modal"></div>
    <div class="modal-card modal-wide" role="dialog" aria-modal="true" aria-labelledby="users-audit-title">
      <div class="modal-head">
        <div>
          <h2 id="users-audit-title">Audit Detail</h2>
          <p id="users-audit-subtitle" class="helper">Select an audit row to inspect the full event.</p>
        </div>
        <button type="button" class="secondary" data-modal-close="users-audit-modal">Close</button>
      </div>
      <div class="stack">
        <div id="users-audit-meta" class="helper"></div>
        <pre id="users-audit-detail">{'{}'}</pre>
      </div>
    </div>
  </div>
</AdminPage>
