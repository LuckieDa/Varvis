// Real React rendering, synthetic source-bound reading advice. No HTTP/model calls.
const assert = require('node:assert/strict');
const { buildSync } = require('esbuild');
const path = require('node:path');
const project = path.resolve(__dirname, '..');
const source = buildSync({ stdin: { contents: `
import React from 'react'; import {renderToStaticMarkup} from 'react-dom/server';
import View from './src/pages/varvis/ChromosomeVisualization/components/LiteraturePriorityView.jsx';
import PP1 from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';
import PM3 from './src/pages/varvis/ChromosomeVisualization/components/Pm3LiteraturePanel.jsx';
export const render=result=>renderToStaticMarkup(<View result={result}/>);
export const pp1=()=>renderToStaticMarkup(<PP1 analysisId="synthetic" draftIdentity="fixed" prepareDeclaration={()=>({hypotheses:[]})}/>);
export const pm3=()=>renderToStaticMarkup(<PM3 analysisId="synthetic" topN={1}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', source)(require, compiled, compiled.exports);
const api = compiled.exports;
assert.equal(api.render({}), '');
assert.equal(api.render({ readingPriority: { schemaVersion: 'unsupported' } }), '');
const result = { readingPriority: { schemaVersion: 'literature-reading-advice-v1', status: 'PARTIAL', cacheHits: 2,
    limits: { maximumPrefetch: 20 }, orderedPaperKeys: ['PMC1'], items: [{ id: 'one', title: '<script>bad</script>',
        paperKey: 'PMC1', priority: 'UNRESOLVED', identityState: 'ALIAS_MATCH_ONLY', cacheHit: true,
        reason: '摘要不足，需读全文', citations: [{ field: 'abstract', quote: '<img src=x onerror=bad>' }] }] },
    acquisition: [{ pmcid: 'PMC1', status: 'REGISTERED' }], papers: [] };
const html = api.render(result);
for (const text of ['相关性待核实', '具体等位变异待核实', '原文已缓存，尚未深入读取',
    '尚无有效阅读建议', '模型仅依据标题和摘要', '复用阅读建议 2 项', 'https://pmc.ncbi.nlm.nih.gov/articles/PMC1/']) assert.ok(html.includes(text), text);
assert.ok(!html.includes('<script>bad') && !html.includes('<img src=x'));
assert.ok(!html.includes('<details open'));
assert.ok(api.pp1().includes('PP1') && api.pm3().includes('PM3'));
result.acquisition = { papers: result.acquisition }; result.papers = [{ pmcid: 'PMC1' }];
assert.ok(api.render(result).includes('已进入深入读取'));
console.log('13 React SSR checks PASS: legacy fallback, bounds, uncertainty, citations, escaping, cache/read separation and PM3/PP1 entry compatibility.');
