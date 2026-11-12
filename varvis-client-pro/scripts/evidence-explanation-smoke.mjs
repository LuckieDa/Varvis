import frontendModuleUrl from './frontend-module-url.cjs';
// Optional archived downloads are read-only; no provider, service or science execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url)).toString('base64')}`));
const results = [];
const check = (name, run) => {
    try { run(); results.push({ name, passed: true }); }
    catch (e) { results.push({ name, passed: false, error: e.message }); }
};
let component;
check('shared de novo events are explicit, not unresolved or scored twice', () => {
    for (const selected of ['PS2', 'PM6']) {
        const item={evaluationResult:{status:'NOT_EVALUATED',reasonCode:`DENOVO_SHARED_EVENT_ASSIGNED_TO_${selected}`}};
        const before=JSON.stringify(item);
        assert.equal(api.evidenceOutcome(item).kind,'NOT_COUNTED');
        assert.equal(JSON.stringify(item),before);
    }
});
if (process.argv[4]) {
    const output = path.resolve(process.argv[4]), project = fileURLToPath(new URL('../', import.meta.url));
    if (output.startsWith(project)) throw Error('Use isolated component output outside the frontend module');
    fs.mkdirSync(output, { recursive: true });
    const { buildSync } = await import('esbuild');
    const bundle = path.join(output, 'saved-component.cjs');
    buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
        import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
        export const render=run=>renderToStaticMarkup(<SavedResult run={run} criteria={run.enabledCriteria}/>);
        export const download=run=>{let click;const walk=e=>{if(!React.isValidElement(e))return;
            if(e.props.children==='下载本次原始结果 JSON')click=e.props.onClick;React.Children.forEach(e.props.children,walk);};
            walk(SavedResult({run,criteria:run.enabledCriteria}));if(!click)throw Error('Missing download handler');click();};`,
        resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', outfile: bundle,
        define: { 'process.env.NODE_ENV': '"production"' }, loader: { '.less': 'empty' }, logLevel: 'warning' });
    component = createRequire(import.meta.url)(bundle);
}
const cases = [
    ['PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED', undefined, /软件.*范围|当前.*支持范围/],
    ['VARIANT_FILTER_NOT_PASS', undefined, /质量.*PASS|FILTER.*PASS/],
    ['CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED', undefined, /纯.*错义|错义.*后果/],
    ['PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE', undefined, /后果.*范围/],
    ['CLAUSE_SEARCH_SCOPE_NOT_COVERED', 'PM5', /PM5.*政策|政策.*PM5/],
    ['PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING', 'PVS1_NMD_V6', /知识采用材料/],
    ['BP3_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION', 'BP3_INFRAME_V7', /BP3.*条款/],
    ['BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED', 'BP3_INFRAME_V7', /未配置.*BP3.*知识材料/],
    ['PP2_GENERIC_AUTHORITY_DEFINITION_ONLY', undefined, /定义.*依据|定义.*条件/],
    ['BINDING_NOT_READY', undefined, /绑定.*就绪/],
    ['SELECTED_ATTEMPT_AMBIGUOUS_OR_MISSING', 'PVS1_NMD_V6', /唯一.*执行|执行.*唯一/],
];
for (const [code, scope, expected] of cases) check(code, () => assert.match(api.reasonHelp(code, scope), expected));
check('BP3 shared sequence reason names BP3 scope', () => {
    const text = api.reasonHelp('PM4_COMPLEX_ALLELE_UNSUPPORTED', 'BP3_INFRAME_V7');
    assert.match(text, /BP3/); assert.doesNotMatch(text, /PM4|终止丢失/);
});
check('unknown is not presumed missing data', () => {
    assert.match(api.reasonHelp('NEW_UNKNOWN_CAUSE'), /维护者|尚未.*解释/);
    assert.doesNotMatch(api.reasonHelp('NEW_UNKNOWN_CAUSE'), /没有完整证据|材料不足/);
});
check('known rule inapplicability is not missing data', () => {
    assert.match(api.reasonHelp('PM5_SPLICE_COMPARISON_NOT_APPLICABLE'), /不适用/);
});
check('no record differs from execution failure', () => {
    assert.match(api.reasonHelp('BS1_NO_RECORD'), /未找到/);
    assert.match(api.reasonHelp('NATIVE_SPLICE_PROCESS_FAILED'), /程序运行失败/);
});
check('known positive is not turned into an input failure', () => {
    assert.match(api.reasonHelp('PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET'), /满足/);
    assert.doesNotMatch(api.reasonHelp('PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET'), /未评价/);
});

const manifest = process.argv[2];
const snapshots = [];
if (manifest) {
    const inputs = JSON.parse(fs.readFileSync(manifest)).inputs;
    for (const input of inputs) {
        const bytes = fs.readFileSync(input.file);
        assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), input.sha256);
        const run = JSON.parse(bytes), before = JSON.stringify(run), entries = [];
        for (const candidate of run.candidates) {
            const cards = api.evidenceCards(candidate, run.executionProfile.enabledCriteria, undefined, undefined, run);
            for (const card of cards) for (const entry of card.entries) entries.push({ code: card.code, entry });
        }
        check(`${input.label}: original object and serialization retained`, () => assert.equal(JSON.stringify(run), before));
        check(`${input.label}: saved identity still valid`, () => assert.ok(entries.every(({ entry }) => !entry.displayIssue)));
        for (const [code, , expected] of cases) {
            const matching = entries.filter(({ entry }) => entry.evaluation?.evaluationResult?.reasonCode === code);
            if (matching.length) check(`${input.label}: ${code} summary`, () => {
                for (const { entry } of matching) {
                    const match = code === 'CLAUSE_SEARCH_SCOPE_NOT_COVERED' ? /政策|目录/ : expected;
                    assert.match(api.evidenceSummary(entry), match);
                    const detail = entry.details?.find(([key]) => key === '本次原因');
                    if (detail) assert.equal(detail[1], api.evidenceSummary(entry));
                }
            });
        }
        check(`${input.label}: no generic material-shortage summary`, () => {
            for (const { entry } of entries) assert.doesNotMatch(api.evidenceSummary(entry), /^现有材料或资格不足/);
        });
        const local = run.candidates.find(c => c.attempts?.some(a => a.localBatch));
        if (local) check(`${input.label}: unknown future reason stays explicit, input unmodified`, () => {
            const copy = structuredClone(run), candidate = copy.candidates.find(c => c.candidateId === local.candidateId);
            const attempt = candidate.attempts.find(a => a.localBatch), row = attempt.localBatch.criteria.find(c => c.status === 'NOT_EVALUATED');
            if (!row) return;
            row.reasonCode = 'NEW_UNKNOWN_TRANSCRIPT_RESOURCE_TIMEOUT';
            row.reason = '现有材料或资格不足，保留未评价状态。（NEW_UNKNOWN_TRANSCRIPT_RESOURCE_TIMEOUT）';
            const original = JSON.stringify(copy);
            const card = api.evidenceCards(candidate, copy.executionProfile.enabledCriteria, undefined, undefined, copy).find(c => c.id === row.criterionId);
            const entry = card.entries.find(e => e.evaluation?.evaluationResult?.reasonCode === row.reasonCode);
            assert.ok(entry);
            assert.match(api.evidenceSummary(entry), /维护者|尚未.*解释/);
            assert.doesNotMatch(api.evidenceSummary(entry), /资料不足|资源未就绪|核查所选带版本转录本/);
            assert.equal(JSON.stringify(copy), original);
        });
        snapshots.push({ label: input.label, entries: entries.length, evaluations: entries.map(({ code, entry }) => ({ code, evaluation: entry.evaluation, rawSha256: crypto.createHash('sha256').update(JSON.stringify(entry.raw) ?? 'undefined').digest('hex') })) });
        if (component) {
            const html = component.render(run);
            fs.writeFileSync(path.join(process.argv[4], `${input.label}.html`), html);
            check(`${input.label}: actual React renders each selectable variant with exact summaries`, () => {
                const escape = t => t.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#x27;');
                // The workbench mounts one selected variant, rather than every expanded result at once.
                for (const candidate of run.candidates) {
                    const selectedHtml = component.render({...run, candidates:[candidate]});
                    const selectedCards = api.evidenceCards(candidate, run.executionProfile.enabledCriteria, undefined, undefined, run);
                    for (const card of selectedCards) for (const entry of card.entries)
                        assert.ok(selectedHtml.includes(escape(api.evidenceSummary(entry))), api.evidenceSummary(entry));
                }
            });
            let blob, clicks = 0;
            const create = URL.createObjectURL, revoke = URL.revokeObjectURL, document = globalThis.document;
            try {
                URL.createObjectURL = value => { blob = value; return 'blob:isolated-export'; };
                URL.revokeObjectURL = () => {};
                globalThis.document = { createElement: () => ({ click: () => { clicks++; } }) };
                component.download(run);
                const exported = await blob.text();
                check(`${input.label}: actual export handler keeps complete original JSON`, () => {
                    assert.equal(clicks, 1); assert.equal(exported, JSON.stringify(JSON.parse(bytes), null, 2));
                });
            } finally { URL.createObjectURL = create; URL.revokeObjectURL = revoke; globalThis.document = document; }
            check(`${input.label}: React and export leave source object unchanged`, () => assert.equal(JSON.stringify(run), before));
        }
    }
}
const report = { tests: results.length, passed: results.filter(r => r.passed).length, failures: results.filter(r => !r.passed), results, snapshots };
if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(report, null, 2));
console.log(JSON.stringify({ tests: report.tests, passed: report.passed, failures: report.failures }, null, 2));
process.exitCode = report.failures.length ? 1 : 0;
