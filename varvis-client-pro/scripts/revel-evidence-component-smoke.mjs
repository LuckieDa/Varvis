// Component rendering with explicit fixture data; this is not browser or real-resource acceptance.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';

const [output] = process.argv.slice(2);
if (!output) throw new Error('Usage: node scripts/revel-evidence-component-smoke.mjs isolated-output-directory');
const project = path.resolve(fileURLToPath(new URL('../', import.meta.url)));
const outputDirectory = path.resolve(output);
if (outputDirectory === project || outputDirectory.startsWith(`${project}${path.sep}`))
    throw new Error('Use an isolated output directory outside the application workspace');
fs.mkdirSync(outputDirectory, { recursive: true });
const bundle = path.join(outputDirectory, 'revel-component-render.cjs');
buildSync({
    stdin: {
        contents: `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Matrix from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
export { default as Panel } from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export function render(candidate) {
 return renderToStaticMarkup(React.createElement(Matrix, { candidate,
  enabledCriteria: ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4', 'ACMG_AMP_2015:BP7'] }));
}`, resolveDir: project, loader: 'jsx',
    },
    bundle: true, platform: 'node', format: 'cjs', outfile: bundle,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
});
const { render } = createRequire(import.meta.url)(bundle);
const candidate = { attempts: [{
    transcriptInput: {
        predictionPath: 'HNF1A_REVEL', tool: 'REVEL',
        policyAuthority: {
            specificationId: 'GN017', authorityVersion: '3.1',
            sourceUrl: 'https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1',
        },
    },
    atomicEvidence: [{
        source: { sourceId: 'REVEL_HNF1A_2021', resourceVersion: '2021-05-03' },
        measurements: [{ metricId: 'revel.score', rawValue: '0.15' }],
    }],
    criterionEvaluations: ['PP3', 'BP4'].map((code) => ({
        criterionId: `ACMG_AMP_2015:${code}`,
        appliedStrength: code === 'BP4' ? 'SUPPORTING' : 'NONE',
        evaluationResult: { status: code === 'BP4' ? 'MET' : 'NOT_MET' },
        deterministicTrace: { parameters: [
            { name: 'threshold', value: code === 'PP3' ? '0.70' : '0.15' },
            { name: 'comparison', value: code === 'PP3' ? 'GE' : 'LE' },
        ] },
    })),
    bp7Disposition: { status: 'NOT_EVALUATED', applicability: 'NOT_APPLICABLE' },
}] };
const original = JSON.stringify(candidate);
const html = render(candidate);
assert.match(html, /REVEL 分数：0\.15/);
assert.match(html, /本次阈值：≥ 0\.70/);
assert.match(html, /本次阈值：≤ 0\.15/);
assert.match(html, /强度：SUPPORTING/);
assert.match(html, /GN017 \/ 3\.1/);
assert.match(html, /href="https:\/\/cspec\.genome\.network\/cspec\/ui\/svi\/doc\/GN017\?version=3\.1"/);
assert.match(html, /不能作为 BP7 剪接依赖/);
assert.doesNotMatch(html, /AlphaMissense 分数/);
assert.equal(JSON.stringify(candidate), original);
const missing = JSON.parse(original);
missing.attempts[0].atomicEvidence = [];
missing.attempts[0].criterionEvaluations = [];
missing.attempts[0].blockers = [{ reasonCode: 'REVEL_NO_RECORD' }];
const missingHtml = render(missing);
assert.match(missingHtml, /REVEL 分数：无可用分数/);
assert.match(missingHtml, /不能作为 BP4 满足依据/);
assert.match(missingHtml, /计算结果：未评价/);
fs.writeFileSync(path.join(outputDirectory, 'revel-component-fixture.html'), html);
fs.writeFileSync(path.join(outputDirectory, 'revel-missing-component-fixture.html'), missingHtml);
console.log('12/12 REVEL fixture component assertions passed; full panel bundle compiled; browser acceptance NOT covered.');
