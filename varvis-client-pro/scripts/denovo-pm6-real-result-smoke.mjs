// Actual saved public-paper research result; no provider, model or business call.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildSync } from 'esbuild';

const [input, destination] = process.argv.slice(2);
if (!input || !destination || fs.existsSync(destination)) throw Error('Provide saved result and new output directory');
fs.mkdirSync(destination, { recursive: true });
const project = process.cwd(), req = createRequire(path.join(project, 'package.json'));
const source = fs.readFileSync(input), run = JSON.parse(source), before = JSON.stringify(run);
const output = path.join(destination, 'check.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export {localBatchScopeIssue,localBatchSavedRun,evidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`,
    resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', outfile: output,
    external: ['react', 'react-dom', 'react-dom/server'], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const Module = req('node:module'), mod = new Module(path.join(project, '__pm6_result_check.cjs'));
mod.filename = path.join(project, '__pm6_result_check.cjs'); mod.paths = Module._nodeModulePaths(project);
mod._compile(fs.readFileSync(output, 'utf8'), mod.filename);
const api = mod.exports, checks = [];
const check = (ok, name) => { assert.ok(ok, name); checks.push(name); };
check(api.localBatchScopeIssue(run) === null, 'current_saved_scope_accepted');
check(api.localBatchSavedRun(run), 'current_saved_run_accepted');
const html = api.render(run);
check(html.includes('aria-label="PM6 ·'), 'product_pm6_card_visible');
check(html.includes('实际强度：支持') && html.includes('0.5 分'), 'actual_supporting_half_point_visible');
check(html.includes('同一新发事件已经计入另一条证据'), 'ps2_same_event_not_double_counted');
check(html.includes('统一病例资料'), 'source_material_available');
check(!html.includes('当前规则范围尚未加载'), 'no_false_scope_failure');
check(html.includes('下载本次原始结果 JSON'), 'original_export_available');
for (const version of ['denovo-literature-evidence-1.2.1', 'denovo-literature-evidence-99.0.0']) {
    const copy = structuredClone(run), modules = copy.executionProfile.moduleBindings;
    modules.deNovo.literatureVersion = version;
    for (const key of ['pm2', 'prediction', 'reference']) modules[key].runtimeBinding.deNovo.literatureVersion = version;
    check((api.localBatchScopeIssue(copy) === null) === version.endsWith('1.2.1'), `version_boundary_${version}`);
}
const damaged = structuredClone(run); damaged.executionProfile.moduleBindings.deNovo.manifestSha256 = 'bad';
check(api.localBatchScopeIssue(damaged) !== null, 'damaged_binding_rejected');
check(JSON.stringify(run) === before, 'complete_saved_json_unchanged');
fs.writeFileSync(path.join(destination, 'render.html'), html);
fs.writeFileSync(path.join(destination, 'validation.json'), JSON.stringify({ checks, passed: checks.length,
    attribution: 'Product SavedResult SSR against actual saved research JSON; no browser/download/scientific calls' }, null, 2));
console.log(`${checks.length} checks PASS`);
