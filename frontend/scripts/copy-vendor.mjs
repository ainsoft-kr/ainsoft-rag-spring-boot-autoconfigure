import { mkdirSync, copyFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const frontendDir = resolve(scriptDir, '..');
const source = resolve(frontendDir, 'node_modules/cytoscape/dist/cytoscape.min.js');
const targetDir = resolve(frontendDir, 'static/assets/vendor');
const target = resolve(targetDir, 'cytoscape.min.js');

mkdirSync(targetDir, { recursive: true });
copyFileSync(source, target);
