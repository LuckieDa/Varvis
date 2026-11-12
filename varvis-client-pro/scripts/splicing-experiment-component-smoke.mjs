// Product-component rendering, not browser acceptance. Arguments are public saved test artifacts only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';
const [savedFile, checkFile, output] = process.argv.slice(2);
if (!savedFile || !checkFile || !output) throw new Error('Usage: node scripts/splicing-experiment-component-smoke.mjs saved.json check.json isolated-output-directory');
const project = fileURLToPath(new URL('../', import.meta.url));
fs.mkdirSync(output, { recursive: true });
const bundle = path.resolve(output, 'component-render.cjs');
buildSync({
    stdin: { contents: `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Matrix from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
export { default as Panel } from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export function render(run, check, unavailable = false) {
 return renderToStaticMarkup(React.createElement('main', {}, ...run.candidates.map(candidate =>
  React.createElement(Matrix, { key: candidate.candidateId, candidate, enabledCriteria: run.enabledCriteria, experimentCheck: check, experimentUnavailable: unavailable }))));
}`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: bundle,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
});
const { render } = createRequire(import.meta.url)(bundle);
const run = JSON.parse(fs.readFileSync(savedFile, 'utf8'));
const check = JSON.parse(fs.readFileSync(checkFile, 'utf8'));
const original = JSON.stringify(run);
const html = render(run, check);
assert.match(html, /与已收录实验观察不一致，待复核/);
assert.match(html, /0\.09/);
assert.match(html, /不代表实验正常/);
assert.match(html, /12714972/);
assert.match(html, /https:\/\/www\.nature\.com\/articles\/nature01629/);
assert.match(render(run, check, true), /核验资料不可用/);
assert.equal(JSON.stringify(run), original);
fs.writeFileSync(path.join(output, 'saved-component.html'), html);
console.log('7/7 product rendering assertions passed; full panel bundle compiled; browser acceptance NOT covered.');
