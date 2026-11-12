// Saved-result layout checks. Uses caller-provided public fixtures; no business/model requests.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildSync } from 'esbuild';

const [task] = process.argv.slice(2), project = process.cwd();
const req = createRequire(path.join(project, 'package.json'));
const run = JSON.parse(fs.readFileSync(path.join(task, 'business-final/result.json')));
const materials = JSON.parse(fs.readFileSync(path.join(task, 'fixed-materials.json')));
const before = JSON.stringify(run), checks = [];
const check = (value, name) => { assert.ok(value, name); checks.push(name); };
const output = path.join(task, 'hierarchy-test.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
import {evidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export * from './src/pages/varvis/ChromosomeVisualization/utils/evidencePresentation';
export const cards=r=>evidenceCards(r.candidates[0],r.enabledCriteria,undefined,undefined,r);
export const tree=r=>SavedResult({run:r,criteria:r.enabledCriteria});
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: output, external: ['react', 'react-dom', 'react-dom/server'],
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const Module = req('node:module'), mod = new Module(path.join(project, '__hierarchy_check.cjs'));
mod.filename = path.join(project, '__hierarchy_check.cjs'); mod.paths = Module._nodeModulePaths(project);
mod._compile(fs.readFileSync(output, 'utf8'), mod.filename);
const api = mod.exports, cards = api.cards(run), html = api.render(run);
check(html.indexOf('aria-label="PS2 ·') < html.indexOf('aria-label="PM2 ·'), 'valid_met_result_first');
check(html.includes('实际强度：支持') && html.includes('0.5 分'), 'strength_and_score_preserved');
check(!html.includes('实际强度：无') && !html.includes('实际强度：未记录'), 'empty_strength_omitted');
check(html.includes('同一新发事件已经计入另一条证据'), 'pm6_critical_reason_retained');
check((html.match(/统一病例资料 · /g) || []).length === 1, 'identical_ps2_pm6_material_shown_once');
check(!/<details[^>]*\sopen(?:[\s=>])/.test(html), 'secondary_materials_initially_collapsed');
check(html.includes('保存的变异：') && html.includes('19:50323670'), 'saved_variant_visible');
check(api.sharedDeNovoMaterials(cards).length === 1, 'validated_exact_shared_material');
const split = structuredClone(cards), pm6 = split.find(c => c.code === 'PM6');
pm6.entries[0].coreMaterials[0].ledgerSha256 = 'a'.repeat(64);
check(api.sharedDeNovoMaterials(split).length === 0, 'different_ledger_never_merged');
const invalid = structuredClone(cards); invalid.find(c => c.code === 'PM6').entries[0].displayIssue = true;
check(api.sharedDeNovoMaterials(invalid).length === 0, 'damaged_entry_never_shared');
const duplicate = [cards.find(c => c.code === 'PS2'), cards.find(c => c.code === 'PS2')];
check(api.sharedDeNovoMaterials(duplicate).length === 0, 'same_criterion_not_cross_shared');
check([false, 0, '0', { x: 0 }].every(api.hasDisplayValue), 'zero_false_facts_retained');
check([null, undefined, '', ' ', [], {}].every(v => !api.hasDisplayValue(v)), 'empty_placeholders_omitted');
const bad = structuredClone(run); bad.candidates[0].attempts[0].localBatch = null;
const badHtml = api.render(bad);
check(badHtml.includes('待核查') || badHtml.includes('不完整'), 'damaged_saved_result_warning_preserved');

// Exercise the actual existing export handler without triggering a browser download.
const find = node => {
    if (!node || typeof node !== 'object') return null;
    if (node.props?.children === '下载本次原始结果 JSON') return node;
    return (Array.isArray(node.props?.children) ? node.props.children.flat(Infinity) : [node.props?.children]).map(find).find(Boolean);
};
let blob, clicked = false;
const oldDocument = globalThis.document, oldCreate = URL.createObjectURL, oldRevoke = URL.revokeObjectURL;
try {
    globalThis.document = { createElement: () => ({ click: () => { clicked = true; } }) };
    URL.createObjectURL = b => { blob = b; return 'blob:test'; }; URL.revokeObjectURL = () => {};
    find(api.tree(run)).props.onClick();
    check(clicked && await blob.text() === JSON.stringify(run, null, 2), 'actual_export_handler_full_json_unchanged');
} finally { globalThis.document = oldDocument; URL.createObjectURL = oldCreate; URL.revokeObjectURL = oldRevoke; }
check(before === JSON.stringify(run), 'saved_objects_unchanged');
fs.writeFileSync(path.join(task, 'hierarchy-check.json'), JSON.stringify({ checks, status: 'PASS', modelCalls: 0, businessCalls: 0 }, null, 2));
console.log(`${checks.length} result hierarchy checks passed`);

// A read-only preview built from production components, with the same saved JSON.
const dist = path.join(task, 'preview'); fs.mkdirSync(dist, { recursive: true });
fs.writeFileSync(path.join(dist, 'result.json'), JSON.stringify(run));
fs.writeFileSync(path.join(dist, 'materials.json'), JSON.stringify(materials));
buildSync({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
import View from './src/pages/varvis/ChromosomeVisualization/components/DeNovoCoreMaterialView.jsx';
Promise.all(['result.json','materials.json'].map(p=>fetch('./'+p).then(r=>r.json()))).then(([run,materials])=>
createRoot(document.getElementById('root')).render(<main style={{maxWidth:1100,margin:'20px auto',padding:'0 16px',fontFamily:'Arial,sans-serif'}}>
<p style={{color:'#526071'}}>保存结果预览 · 公开论文科研验证</p><SavedResult run={run} criteria={run.enabledCriteria}/>
<details style={{marginTop:24}}><summary>其他论文的资料预检示例（未计入上方结果）</summary><View materials={materials.filter(m=>m.status!=='READY_FOR_EVALUATION')}/></details>
</main>)).catch(()=>document.getElementById('root').textContent='保存结果读取失败');`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'browser', outfile: path.join(dist, 'app.js'), minify: true,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
fs.writeFileSync(path.join(dist, 'index.html'), '<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>证据结果预览</title><div id="root">读取已保存结果…</div><script src="./app.js"></script></html>');
