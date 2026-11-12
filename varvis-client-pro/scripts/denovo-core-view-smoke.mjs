import frontendModuleUrl from './frontend-module-url.cjs';
// Fixed public artifacts plus explicit corruptions. No provider, model or science.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildSync } from 'esbuild';
const [task] = process.argv.slice(2), project = process.cwd(), req = createRequire(path.join(project, 'package.json'));
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync('src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js').toString('base64')}`));
const materials = JSON.parse(fs.readFileSync(path.join(task, 'fixed-materials.json'))), run = JSON.parse(fs.readFileSync(path.join(task, 'business/result.json')));
const before = JSON.stringify({ materials, run }), results = [];
const check = (ok, label) => { assert.ok(ok, label); results.push(label); };
check(materials.length === 9 && new Set(materials.map(m => m.caseKey.split('#')[0])).size === 3 && materials.every(api.validCoreMaterial), 'three_papers_nine_retained_cases_validate');
check(api.localBatchSavedRun(run), 'current_saved_result_identity_valid');
const bad = structuredClone(materials[0]); bad.criterionDecision = 'PS2_MET';
check(!api.validCoreMaterial(bad), 'precheck_cannot_claim_score');
for (const mutate of [m => m.fields.push(m.fields[0]), m => m.fields.splice(0, 3), m => m.documentSha256 = 'broken',
    m => m.fields.push(null), m => m.fields[0].citations.push(null), m => m.blockers.push(null),
    m => { m.fields[0].verification = 'CONFLICT'; m.fields[0].availability = 'NOT_EXTRACTED'; },
    m => { m.status = 'READY_FOR_EVALUATION'; m.blockers = [{ path: '/context/diseaseId', category: 'KNOWLEDGE_MISSING' }]; }]) {
    const copy = structuredClone(materials[0]); mutate(copy);
    // A conflict is valid display data; it must remain visible, never become a negative.
    check(copy.fields[0].verification === 'CONFLICT' ? api.validCoreMaterial(copy) : !api.validCoreMaterial(copy), 'shape_or_conflict_check_' + results.length);
}
const outfile = path.join(task, 'core-view.cjs');
buildSync({ stdin: { contents: `import React from 'react'; import {renderToStaticMarkup} from 'react-dom/server';
import View from './src/pages/varvis/ChromosomeVisualization/components/DeNovoCoreMaterialView.jsx';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=m=>renderToStaticMarkup(<View materials={m}/>);export const saved=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`,
    loader: 'jsx', resolveDir: project }, bundle: true, platform: 'node', format: 'cjs', outfile,
    external: ['react', 'react-dom', 'react-dom/server'], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const Module = req('node:module'), mod = new Module(path.join(project, '__core-view-check.cjs'));
mod.filename = path.join(project, '__core-view-check.cjs'); mod.paths = Module._nodeModulePaths(project); mod._compile(fs.readFileSync(outfile, 'utf8'), mod.filename);
const html = mod.exports.render(materials), saved = mod.exports.saved(run);
for (const term of ['统一病例资料', '尚未提取或核实，不代表原文没有', '缺少已配置且适用的疾病或规则知识', '父亲检测', '母亲检测', 'Clinodactyly', '原文定位与依据']) check(html.includes(term), 'actual_render_' + term);
check(saved.includes('统一病例资料') && saved.includes('0.5 分') && !saved.includes('保存结果待核查'), 'actual_saved_score_and_materials');
check(mod.exports.render(undefined).includes('不在读取时重新提取'), 'legacy_without_regeneration');
check(mod.exports.render([bad]).includes('不能作为可信资料展示'), 'invalid_view_refused');
const xss = structuredClone(materials[0]); xss.fields[0].value = '<script>window.pwned=true</script>';
check(!mod.exports.render([xss]).includes('<script>window.pwned'), 'untrusted_paper_text_escaped');
check(before === JSON.stringify({ materials, run }), 'full_json_not_mutated');
fs.writeFileSync(path.join(task, 'core-view-render.html'), html);
fs.writeFileSync(path.join(task, 'saved-result-render.html'), saved);
fs.writeFileSync(path.join(task, 'core-view-check.json'), JSON.stringify({ status: 'PASS', checks: results, modelCalls: 0, scientificExecutions: 0 }, null, 2));
console.log(`${results.length} core view checks passed`);
