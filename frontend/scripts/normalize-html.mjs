import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const buildDir = path.resolve(__dirname, '../build');

const pageNameForFile = {
  'index.html': 'overview',
  'search.html': 'search',
  'text-ingest.html': 'text-ingest',
  'file-ingest.html': 'file-ingest',
  'documents.html': 'documents',
  'tenants.html': 'tenants',
  'provider-history.html': 'provider-history',
  'search-audit.html': 'search-audit',
  'job-history.html': 'job-history',
  'access-security.html': 'access-security',
  'config.html': 'config',
  'bulk-operations.html': 'bulk-operations'
};

function normalizeHtml(html, pageName) {
  const titleMatch = html.match(/<title>(.*?)<\/title>/s);
  const mainMatch = html
    .replace(/<!--[\s\S]*?-->/g, '')
    .match(/<main class="dashboard-shell">[\s\S]*<\/main>/);

  if (!titleMatch || !mainMatch) {
    throw new Error(`Failed to normalize ${pageName}: could not extract title or main content`);
  }

  const title = titleMatch[1].trim();
  const main = mainMatch[0];

  return `<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>${title}</title>
    <link rel="stylesheet" href="assets/app.css" />
  </head>
  <body data-page="${pageName}">
    ${main}

    <script>
      window.RAG_ADMIN_CONFIG = __RAG_ADMIN_CONFIG__;
    </script>
    <script src="assets/app.js"></script>
  </body>
</html>
`;
}

const files = await readdir(buildDir);

await Promise.all(
  files
    .filter((file) => file.endsWith('.html'))
    .map(async (file) => {
      const pageName = pageNameForFile[file];
      if (!pageName) {
        return;
      }
      const fullPath = path.join(buildDir, file);
      const original = await readFile(fullPath, 'utf8');
      const normalized = normalizeHtml(original, pageName);
      await writeFile(fullPath, normalized);
    })
);
