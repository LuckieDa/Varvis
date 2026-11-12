import frontendModuleUrl from './frontend-module-url.cjs';
// Saved public facts, real React rendering and lossless export; no remote calls.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildSync } from 'esbuild';
const [task] = process.argv.slice(2), project = process.cwd(), req = createRequire(path.join(project, 'package.json'));
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync('src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js').toString('base64')}`));
const materials = JSON.parse(fs.readFileSync(path.join(task, 'fixed-materials.json')));
const before = JSON.stringify(materials), checks = [], check = (v, name) => { assert.ok(v, name); checks.push(name); };
check(materials.every(api.validCoreMaterial), 'three_real_materials_valid');
const mecom = materials.find(m => m.caseKey === 'PMC10330190#3');
check(mecom.explanation.items.length === 1 && mecom.blockers.length === 2, 'same_family_gap_grouped');
const age = mecom.fields.find(f => f.path === '/groups/phenotype/age');
check(age.sourceEvidence.some(c => c.role === 'PAPER_ATTACHMENT' && c.locationLabel.includes('tS1.docx')), 'docx_original_location');
for (const [name, mutation] of [
    ['wrong_paper', m => m.explanation.paper.paperId = 'PMC1'],
    ['invented_checked_scope', m => m.explanation.items[0].checkedScope[0].scopeOnly = false],
    ['unsafe_link', m => m.explanation.items[0].checkedScope[0].url = 'javascript:alert(1)'],
    ['unverified_claim_as_fact', m => m.explanation.items[0].observedFieldPaths.push('/groups/family/compatibility')],
    ['hidden_gap', m => m.explanation.items = []],
    ['damaged_blocker_rejected_without_crash', m => m.blockers[0] = null],
    ['duplicate_item', m => m.explanation.items.push(m.explanation.items[0])],
    ['forged_precheck_score', m => m.criterionDecision = 'PM6_MET'],
]) { const copy = structuredClone(mecom); mutation(copy); check(!api.validCoreMaterial(copy), name); }
const legacy = structuredClone(mecom); delete legacy.explanation; legacy.fields.forEach(f => delete f.sourceEvidence);
check(api.validCoreMaterial(legacy), 'legacy_history_remains_readable');
const unsafeLegacy = structuredClone(legacy); unsafeLegacy.fields[0].sourceEvidence = [{ url: 'javascript:alert(1)' }];
check(!api.validCoreMaterial(unsafeLegacy), 'legacy_optional_citation_cannot_bypass_validation');
const output = path.join(task, 'explanation-view.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import View from './src/pages/varvis/ChromosomeVisualization/components/DeNovoCoreMaterialView.jsx';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=m=>renderToStaticMarkup(<View materials={m}/>);export const saved=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`,
    resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', outfile: output,
    external: ['react', 'react-dom', 'react-dom/server'], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const Module = req('node:module'), mod = new Module(path.join(project, '__explanation_check.cjs'));
mod.filename = path.join(project, '__explanation_check.cjs'); mod.paths = Module._nodeModulePaths(project); mod._compile(fs.readFileSync(output, 'utf8'), mod.filename);
const html = mod.exports.render(materials);
for (const phrase of ['判定需要', '对判定的影响', '如何继续', '不能据此推定双亲', '原文依据与位置', 'tS1.docx', '程序尚不支持', '队列相关病史未明确归属']) check(html.includes(phrase), 'render_' + phrase);
check(mod.exports.render([legacy]).includes('不重新提取或补写历史结论'), 'legacy_no_regeneration');
const xss = structuredClone(mecom); xss.explanation.paper.title = '<script>bad()</script>'; xss.explanation.items[0].explanation = '<img src=x onerror=bad()>';
check(!mod.exports.render([xss]).includes('<img src=x'), 'source_content_escaped');
const runPath = path.join(task, 'business-final/result.json');
if (fs.existsSync(runPath)) {
    const run = JSON.parse(fs.readFileSync(runPath)), bytes = JSON.stringify(run), saved = mod.exports.saved(run);
    check(api.localBatchSavedRun(run), 'actual_saved_result_contract');
    check(!saved.includes('保存结果待核查') && saved.includes('0.5 分'), 'actual_score_preserved');
    check(saved.includes('同一新发事件已经计入另一条证据'), 'actual_pm6_no_double_count_explanation');
    check(saved.includes('事实、判定要求与缺项说明'), 'saved_view_explanation');
    check(bytes === JSON.stringify(run), 'full_saved_json_unmodified');
    fs.writeFileSync(path.join(task, 'saved-view.html'), saved);
}
check(before === JSON.stringify(materials), 'full_materials_json_unmodified');
fs.writeFileSync(path.join(task, 'explanation-view.html'), html);
fs.writeFileSync(path.join(task, 'explanation-view-check.json'), JSON.stringify({ status: 'PASS', checks, modelCalls: 0 }, null, 2));
console.log(`${checks.length} explanation checks passed`);
