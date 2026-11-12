import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// Local component compilation/rendering only. Browser confirmation remains a separate check.
const project = fileURLToPath(new URL('../', import.meta.url));
const artifacts = path.resolve(process.argv[2]);
const output = path.join(artifacts, 'render');
fs.mkdirSync(output, { recursive: true });
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
await build({
    stdin: { contents: `import React from 'react'; import {renderToStaticMarkup} from 'react-dom/server';
        import {TestSavedResult} from ${JSON.stringify(component)};
        export function render(run) {return renderToStaticMarkup(<TestSavedResult run={run} criteria={['ACMG_AMP_2015:PP3']} />);}`,
        resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'),
    loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'test-only-export', setup(api) {
        api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({
            contents: `${fs.readFileSync(component, 'utf8')}\nexport { SavedResult as TestSavedResult };`,
            loader: 'jsx', resolveDir: path.dirname(component),
        }));
    } }],
});
const { render } = createRequire(import.meta.url)(path.join(output, 'render.cjs'));
for (const name of ['source', 'application']) {
    const run = JSON.parse(fs.readFileSync(path.join(artifacts, `synthetic-${name}.json`), 'utf8'));
    const html = render(run);
    assert.ok(html.includes(run.runId)); assert.ok(html.includes('MaxEntScan'));
    assert.ok(html.includes('0.9') && html.includes('0.5'));
    assert.ok(html.includes(name === 'application' ? 'SUPPORTING' : 'NOT_APPLIED'));
    assert.ok(!html.includes('HNF1A'));
    assert.ok(html.includes('HGNC:101') && html.includes('AUTOSOMAL_RECESSIVE'));
    fs.writeFileSync(path.join(output, `${name}.html`), `<!doctype html><meta charset="utf-8"><title>TEST_ONLY REVEL component</title>${html}`);
}
const historical = render({ runId: 'TEST_ONLY_AM_HISTORY', status: 'COMPLETED', dataClass: 'SYNTHETIC',
    executionProfile: { profileId: 'acmg-pp3-bp4-development-v1' }, candidates: [], enabledCriteria: ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'] });
assert.ok(historical.includes('TEST_ONLY_AM_HISTORY')); assert.ok(!historical.includes('REVEL 蛋白分支'));
console.log('Actual saved result component compiled and rendered from isolated-H2 GET exports. No browser interaction claimed.');
