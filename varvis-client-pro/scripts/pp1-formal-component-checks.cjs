// Actual React SSR with captured Python results. No service, page or scientific execution.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const [fixtureDirectory, realResultPath, dependencyProject, discoveryReceipt] = process.argv.slice(2);
if (!fixtureDirectory || !realResultPath) throw Error('Supply Python normal fixture directory and archived real NOT_EVALUATED result');
const project = path.resolve(__dirname, '..');
const dependencies = dependencyProject ? createRequire(path.join(path.resolve(dependencyProject), 'package.json')) : require;
const { buildSync } = dependencies('esbuild');
const built = buildSync({ stdin: { contents: `
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {Pp1SavedFacts,Pp1PaperProgress} from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';
import {EvidenceEntryDetails} from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
import Workbench from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
import DeNovoCoreMaterialView from './src/pages/varvis/ChromosomeVisualization/components/DeNovoCoreMaterialView.jsx';
export const saved=(value,criterion)=>renderToStaticMarkup(<Pp1SavedFacts value={value} criterion={criterion}/>);
export const task=evaluation=>renderToStaticMarkup(<Pp1PaperProgress paper={{pmcid:'PMC123',evaluation}}/>);
export const shared=(value,criterion)=>renderToStaticMarkup(<EvidenceEntryDetails card={{id:'ACMG_AMP_2015:PP1',entries:[{}]}} entry={{reasons:[],details:[],pp1LiteratureFacts:value,pp1Criterion:criterion}} run={{localBatch:{}}}/>);
export const diagnostics=rows=>renderToStaticMarkup(<EvidenceEntryDetails card={{id:'ACMG_AMP_2015:PM4',entries:[{}]}} entry={{reasons:[],details:[],executionDiagnostics:rows}} run={{localBatch:{}}}/>);
export const emptyWorkbench=()=>renderToStaticMarkup(<Workbench run={{runId:'SYNTHETIC_EMPTY',candidates:[]}} criteria={[]}/>);
export const core=materials=>renderToStaticMarkup(<DeNovoCoreMaterialView materials={materials}/>);
export const comparisons=()=>renderToStaticMarkup(<EvidenceEntryDetails card={{id:'ACMG_AMP_2015:PS2',entries:[{}]}} entry={{reasons:[],details:[['事件 1 已核来源差异','母亲：普通来源 检测到目标等位基因；特殊事件来源 未检测到目标等位基因。<script>untrusted</script> 保留冲突，不采用本事件。']]}} run={{localBatch:{}}}/>);
export const transportIssues=()=>renderToStaticMarkup(<EvidenceEntryDetails card={{id:'ACMG_AMP_2015:PS2',entries:[{}]}} entry={{reasons:[],details:[],presentationIssues:['DENOVO_FACT_COMPARISON_CONTRADICTION']}} run={{localBatch:{}}}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: dependencyProject ? [path.join(path.resolve(dependencyProject), 'node_modules')] : [],
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', built)(dependencies, compiled, compiled.exports);
const { saved, task, shared, diagnostics, emptyWorkbench, core, transportIssues, comparisons } = compiled.exports;
const plain = html => html.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, '').replace(/<[^>]*>/g, '');
function wrap(value) {
    const facts = { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false, evaluation: value.evaluation,
        qualifications: value.qualifications, sourceCoverage: value.sourceCoverage, excludedTasks: value.excludedTasks };
    const e = facts.evaluation;
    return { criterionId: 'ACMG_AMP_2015:PP1', status: e.status, appliedStrength: e.strength || 'NONE', reason: 'Captured Python normal result',
        reasonCode: e.status === 'MET' ? 'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET' : e.status === 'NOT_MET' ? 'PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD' : 'PP1_LITERATURE_REQUIREMENTS_UNRESOLVED', details: { pp1Facts: facts } };
}
let count = 0;
const check = (name, run) => { run(); count++; console.log(`PASS ${name}`); };
const rows = {};
for (const name of ['positive', 'negative', 'zero']) {
    const file = path.join(fixtureDirectory, `${name}.json`), bytes = fs.readFileSync(file), original = JSON.parse(bytes), before = JSON.stringify(original);
    const criterion = wrap(original), facts = criterion.details.pp1Facts;
    rows[name] = criterion;
    check(`${name}: captured Python formal fields render without old transmission sums`, () => {
        const text = plain(saved(facts, criterion));
        assert.ok(text.includes('PP1 条件家系似然'));
        assert.ok(text.includes('最终贡献'));
        assert.ok(text.includes(`${original.evaluation.bayesFactor.numerator}/${original.evaluation.bayesFactor.denominator}`));
        assert.ok(text.includes('四和 genotypeAnchor') && text.includes('已条件化成员') && text.includes('同次观察 ID'));
        assert.ok(!text.includes('共分离原始贡献'));
        assert.equal(JSON.stringify(original), before);
        assert.deepEqual(fs.readFileSync(file), bytes);
    });
}
check('positive strength, negative direction and BF=0 logarithm stay distinct', () => {
    assert.ok(plain(saved(rows.positive.details.pp1Facts, rows.positive)).includes('满足 · 支持'));
    assert.ok(plain(saved(rows.negative.details.pp1Facts, rows.negative)).includes('反向'));
    assert.ok(plain(saved(rows.zero.details.pp1Facts, rows.zero)).includes('−∞（BF=0）'));
});
const realBytes = fs.readFileSync(realResultPath), realOriginal = JSON.parse(realBytes), real = wrap(realOriginal), realBefore = JSON.stringify(realOriginal);
check('real-source unresolved parameters preserve known facts and precise clinical gaps', () => {
    const text = plain(saved(real.details.pp1Facts, real));
    assert.ok(text.includes('尚未评价') && !text.includes('最终贡献'));
    assert.ok(text.includes('PP1_FORMAL_MODEL_REQUIRED') && text.includes('已经有什么') && text.includes('目前情况与差异'));
    assert.ok(text.includes('不是原件确缺声明') && text.includes('22 y.o., LVNC, heart failure'));
    assert.ok(text.includes('SHA-256') && text.includes('/article[1]/body[1]'));
    assert.equal(JSON.stringify(realOriginal), realBefore);
    assert.deepEqual(fs.readFileSync(realResultPath), realBytes);
});
check('absent, mismatched and tampered full criteria cannot display awards', () => {
    const facts = rows.positive.details.pp1Facts;
    for (const criterion of [undefined, { ...rows.positive, appliedStrength: 'STRONG' }, rows.negative]) {
        const text = plain(saved(facts, criterion));
        assert.ok(text.includes('需要展示核查'));
        assert.ok(!text.includes('最终贡献') && !text.includes('满足 · 支持'));
    }
});
check('shared matrix/drawer detail slot forwards the full criterion', () => {
    assert.ok(plain(shared(rows.positive.details.pp1Facts, rows.positive)).includes('最终贡献 1 分'));
});
check('task-only formal pre-evaluation never falls through legacy point presentation', () => {
    const text = plain(task(rows.positive.details.pp1Facts.evaluation));
    assert.ok(text.includes('预评价'));
    assert.ok(!text.includes('最终贡献') && !text.includes('共分离原始贡献') && !text.includes('PP1 支持级'));
});
check('legacy count method stays readable without a new full criterion', () => {
    const text = plain(saved({ schemaVersion: 'pp1-selected-facts-v1', evaluation: { schemaVersion: 'pp1-segregation-evaluation-v1', status: 'MET', strength: 'SUPPORTING', awardedPP1Points: 1, rawSegregationPoints: '1', remainingLocusCapacity: '5', events: [] } }));
    assert.ok(text.includes('PP1 支持级（1 分）') && text.includes('共分离原始贡献 1 分'));
});
check('synthetic failure and long untrusted text are visible, bounded and escaped', () => {
    const criterion = structuredClone(real), facts = criterion.details.pp1Facts;
    facts.evaluation.needs.push({ reasonCode: 'PP1_SYNTHETIC_SOURCE_FAILED', requirement: '<script>untrusted</script>' + 'x'.repeat(7000), blockingEffect: 'BLOCKS_SCORING' });
    const html = saved(facts, criterion), text = plain(html);
    assert.ok(html.includes('MuiAlert-standardError'));
    assert.ok(html.includes('&lt;script&gt;untrusted&lt;/script&gt;') && !html.includes('<script>untrusted'));
    assert.ok(text.includes('仅展示前 6000 字符') && text.includes('完整字段保留在原始结果'));
});
check('selection support/restriction conflicts expose both source sides before collapsed technical fields', () => {
    const criterion = structuredClone(real), q = criterion.details.pp1Facts.qualifications[0];
    const review = Object.values(q.ascertainmentReview.families)[0];
    review.conflicts = [{ reasonCode: 'PP1_FORMAL_ASCERTAINMENT_SUPPORT_RESTRICTION_CONFLICT',
        supportSourceRefs: [{ quote: 'SYNTHETIC phenotype-first assertion', unitId: 'support', ...q.sourceIdentity }],
        restrictionSourceRefs: [{ quote: 'SYNTHETIC genotype-filtered report', unitId: 'restriction', ...q.sourceIdentity }] }];
    const text = plain(saved(criterion.details.pp1Facts, criterion));
    assert.ok(text.includes('取样支持与限制冲突'));
    assert.ok(text.includes('SYNTHETIC phenotype-first assertion') && text.includes('SYNTHETIC genotype-filtered report'));
    assert.ok(text.indexOf('取样支持与限制冲突') < text.indexOf('BF、校准、PP4'));
});
check('retained failed execution is prominent and escaped in the shared evidence slot', () => {
    const html = diagnostics([['来源执行', '失败；完整 JSON 不代表执行成功'], ['执行失败原因', 'ROOT_EXITED_WITH_ACTIVE_DESCENDANTS <script>untrusted</script>'], ['本次回放用途', '诊断回放，未接纳来源']]);
    assert.ok(html.includes('role="alert"'));
    assert.ok(plain(html).includes('来源执行与回放用途（保存声明）'));
    assert.ok(plain(html).includes('ROOT_EXITED_WITH_ACTIVE_DESCENDANTS'));
    assert.ok(!html.includes('<script>untrusted</script>'));
});
check('empty saved run has an explicit empty-state message', () => {
    assert.ok(plain(emptyWorkbench()).includes('本次没有已保存的变异结果'));
});
check('source field comparison contradictions are prominent in the shared detail slot', () => {
    const html=transportIssues(); assert.ok(html.includes('role="alert"'));
    assert.ok(plain(html).includes('来源字段对比与事件采用状态互相矛盾'));
});
check('excluded event source conflicts show both sides prominently and escaped exactly once', () => {
    const html=comparisons(),text=plain(html);
    assert.ok(html.includes('MuiAlert-standardWarning') && text.includes('来源资料存在差异，本事件未采用'));
    assert.ok(text.includes('普通来源 检测到目标等位基因') && text.includes('特殊事件来源 未检测到目标等位基因'));
    assert.equal(text.split('事件 1 已核来源差异').length-1,1);
    assert.ok(html.includes('&lt;script&gt;untrusted&lt;/script&gt;') && !html.includes('<script>untrusted</script>'));
});
if(discoveryReceipt) {
    const bytes=fs.readFileSync(discoveryReceipt), document=JSON.parse(bytes), discovery=document.report || document;
    const material={schemaVersion:'denovo-core-display-v1',dataClass:'SYNTHETIC',status:'INSUFFICIENT_DATA',criterionDecision:'NOT_RUN',
        caseKey:'PMC4572487#SYNTHETIC_DISPLAY_SCAFFOLD',documentSha256:'a'.repeat(64),ledgerSha256:'b'.repeat(64),coverageLimitations:[],blockers:[],
        fields:['paper','case','gene','variant','proband','mother','father','testing','parentage','phenotype','family','overlap'].map(g=>({
            path:`/groups/${g}/identity`,value:null,availability:'NOT_EXTRACTED',verification:'UNVERIFIED',issues:[],citations:[]})),sourceDiscovery:discovery};
    check('actual prior Python locator receipt displays source scope and specific missing fields without granting evidence',()=>{
        const text=plain(core([material]));assert.ok(text.includes('原件自动定位与尚未采用的资料'));
        assert.ok(text.includes('NOT_RUN') && text.includes('/genotype') && text.includes('KCNC3'));
        assert.deepEqual(fs.readFileSync(discoveryReceipt),bytes);
    });
    check('source discovery cannot change its own no-decision boundary into MET',()=>{
        const bad=structuredClone(material);bad.sourceDiscovery.criterionDecision='MET';
        assert.ok(plain(core([bad])).includes('不能作为可信资料展示'));
    });
}
console.log(`${count} actual React SSR checks passed. Three inputs are synthetic materials through normal Python evaluation; the real-source case remains NOT_EVALUATED. No Java public workflow, page or actual download acceptance is claimed.`);
