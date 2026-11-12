// Synthetic saved business result produced by Bs1KnowledgeBusinessTest; not a patient study.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
const [input, destination] = process.argv.slice(2);
if (!input || !destination) throw new Error('Supply synthetic saved result and new output directory');
const project = path.resolve(import.meta.dirname, '..'), output = path.resolve(destination);
fs.mkdirSync(output, { recursive: false });
const run = JSON.parse(fs.readFileSync(input, 'utf8')), original = JSON.stringify(run);
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const utils = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const expose = { name: 'saved-result', setup(b) {
    b.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) }));
} };
await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};
export {evidenceCards,reasonHelp} from ${JSON.stringify(utils)};
export const saved=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'checks.cjs'), plugins: [expose], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const api = createRequire(import.meta.url)(path.join(output, 'checks.cjs'));
const entries = run.candidates.flatMap((c) => api.evidenceCards(c, ['ACMG_AMP_2015:BS1'])[0].entries);
const model = entries.find((e) => e.raw?.preparation?.policy?.knowledgeDecision?.basis === 'MAX_CREDIBLE_AF');
assert.ok(model);
assert.equal(model.evaluation.evaluationResult.status, 'MET');
const details = Object.fromEntries(model.details);
assert.match(details['阈值来源'], /通用疾病模型.*非官方/);
assert.match(details['模型参数（比例）'], /患病率=0.001；外显率=0.5；基因最大贡献=0.2；等位基因最大贡献=0.1/);
assert.equal(details['参数适用人群'], 'SYNTHETIC_POPULATION');
assert.match(details['计算模型'], /sqrt/);
assert.match(details['阈值与比较'], /GT 0.002/);
assert.match(details['数据范围'], /exomes 4.1/);
let frequencyChecks = 0;
if (model.raw.preparation.policy.statistic.metricId === 'gnomad.exomes.eligible_ancestry_max_af') {
    assert.equal(details.FAF95, undefined);
    assert.equal(details['AF 所选祖源'], model.raw.qualityDecision.facts.ancestry);
    assert.match(details['精确比较依据'], /AC \/ AN/);
    assert.match(details['阈值与比较'], /AF GT/);
    assert.match(details['人群筛选'], /GT 2000/);
    assert.match(api.reasonHelp('BS1_AF_NO_ELIGIBLE_POPULATION'), /不表示.*零/);
    assert.match(api.reasonHelp('BS1_MAF_TARGET_ALLELE_INTERPRETATION_UNRESOLVED'), /1−AF/);
    const candidate = run.candidates.find((c) => c.attempts.some((a) => a.bs1?.preparation?.policy?.statistic?.metricId === 'gnomad.exomes.eligible_ancestry_max_af'));
    for (const fault of ['ANCESTRY', 'CONFIDENCE', 'VERSION', 'TERM']) {
        const damaged = structuredClone(candidate);
        const value = damaged.attempts.find((a) => a.bs1?.preparation?.policy?.statistic?.metricId === 'gnomad.exomes.eligible_ancestry_max_af').bs1;
        if (fault === 'ANCESTRY') value.qualityDecision.frequencySelection.selectedAncestry = 'other';
        if (fault === 'CONFIDENCE') value.preparation.policy.statistic.confidence = '95_PERCENT_LOWER_BOUND';
        if (fault === 'VERSION') delete value.qualityDecision.frequencySelection.version;
        if (fault === 'TERM') value.preparation.policy.statistic.sourceTerm = 'MAF';
        assert.ok(api.evidenceCards(damaged, ['ACMG_AMP_2015:BS1'])[0].entries.some((e) => e.evaluation.evaluationResult.reasonCode === 'BS1_SAVED_RESULT_ASSOCIATION_INVALID'));
    }
    frequencyChecks = 11;
}
assert.match(api.reasonHelp('BS1_DISEASE_PARAMETERS_MISSING'), /患病率|参数/);
assert.match(api.reasonHelp('BS1_RELATIONSHIP_CONFLICT'), /冲突/);
const html = api.saved(run);
assert.match(html, /已保存的证据结果/);
assert.equal(JSON.stringify(run), original);
assert.deepEqual(JSON.parse(JSON.stringify(run, null, 2)), run);
fs.writeFileSync(path.join(output, 'saved.html'), html);
await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import {SavedResult} from ${JSON.stringify(component)};
const run=${original};createRoot(document.getElementById('root')).render(<SavedResult run={run} criteria={run.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'page.js'), plugins: [expose], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
fs.writeFileSync(path.join(output, 'index.html'), '<!doctype html><meta charset="utf-8"><title>BS1 通用知识合成结果核验</title><body style="padding:24px;background:#fafafa"><p>合成测试材料的只读回放：不是患者结果，无业务提交及科学执行。</p><div id="root"></div><script src="page.js"></script>');
fs.writeFileSync(path.join(output, 'component-checks.json'), JSON.stringify({ assertions: 13 + frequencyChecks, result: 'PASS', dataClass: 'SYNTHETIC', completeInputUnchanged: true, browserRun: false, actualDownload: false }, null, 2));
console.log(`${13 + frequencyChecks} synthetic BS1 knowledge component assertions passed; source JSON unchanged.`);
