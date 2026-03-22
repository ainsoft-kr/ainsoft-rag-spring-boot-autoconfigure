<script>
  export let title;
  export let page;
  export let copy = '';

  $: bootstrapHtml =
    `<script>document.body.dataset.page = ${JSON.stringify(page)}; window.RAG_ADMIN_CONFIG = __RAG_ADMIN_CONFIG__;</scr` +
    `ipt><script src="assets/app.js"></scr` +
    'ipt>';
</script>

<svelte:head>
  <title>{title}</title>
  <link rel="stylesheet" href="assets/app.css" />
</svelte:head>

<main class="dashboard-shell">
  <aside class="sidebar">
    <section class="brand">
      <span class="brand-mark">RA</span>
      <div>
        <div class="brand-title">RAG Admin</div>
        {#if copy}
          <p class="brand-copy">{copy}</p>
        {/if}
      </div>
    </section>
    <nav class="nav-menu"></nav>
  </aside>

  <section class="content-area">
    <slot />
  </section>
</main>

{@html bootstrapHtml}
