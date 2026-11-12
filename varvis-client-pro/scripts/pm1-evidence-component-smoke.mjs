// Actual component rendering of engineering fixtures, not browser acceptance.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';

const [destination, envelopes] = process.argv.slice(2);
if (!destination || !envelopes) throw new Error('Supply isolated output and Java fixture envelopes');
const project = path.resolve(fileURLToPath(new URL('../', import.meta.url)));
const output = path.resolve(destination);
if (output.startsWith(project + path.sep)) throw new Error('Output must be outside the source tree');
fs.mkdirSync(output, { recursive: true });
const bundle = path.join(output, 'pm1-component.cjs');
buildSync({ stdin: { contents: `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Matrix from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
export { default as Panel } from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render = candidate => renderToStaticMarkup(React.createElement(Matrix, { candidate, enabledCriteria: ['ACMG_AMP_2015:PM1'] }));
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', outfile: bundle,
define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render } = createRequire(import.meta.url)(bundle);
const rows = JSON.parse(fs.readFileSync(envelopes, 'utf8'));
for (const [index, pm1] of rows.entries()) {
    const original = JSON.stringify(pm1);
    const html = render({ attempts: [{ pm1 }] });
    assert.match(html, /GN017 v3.1/);
    assert.match(html, /蛋白位置/);
    assert.match(html, /href="https:\/\/cspec.genome.network/);
    assert.match(html, new RegExp(pm1.evaluation.appliedStrength));
    assert.equal(JSON.stringify(pm1), original);
    fs.writeFileSync(path.join(output, `pm1-fixture-${index}.html`), html);
}
const missing = render({ attempts: [{ pm1: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PM1_PROTEIN_CODON_MISMATCH' } } }] });
assert.match(missing, /未评价/);
assert.match(missing, /不一致/);
console.log('17/17 PM1 fixture component assertions passed; browser acceptance pending.');
