import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { buildPp4ComponentChecks } from './pp4-business-component-checks.mjs';

// Isolated archive transport: no backend, database, scientific program or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const task = path.join(checkout, 'local/temp/PP4-ui-business-20261003/dev-01');
const handoff = path.join(checkout, 'local/development/handoffs/PP4-ui-business-20261003/dev-01');
const selfCheck = process.argv.includes('--self-check');
const readOnly = process.argv.includes('--read-only');
assert.ok(process.argv.slice(2).every(x => ['--self-check','--read-only'].includes(x)));
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const read = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const sourceFiles = ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'src/pages/varvis/ChromosomeVisualization/components/Pp4PhenotypeSummary.jsx', 'scripts/pp4-business-browser-smoke.mjs', 'scripts/pp4-business-component-checks.mjs'];
write('source-before.json', sourceFiles.map(file => {
    const raw = fs.readFileSync(path.join(project, file)); fs.writeFileSync(path.join(output, path.basename(file)), raw);
    return { file, bytes: raw.length, sha256: sha(raw) };
}));
const require = createRequire(path.join(project, 'package.json'));
write('tools.json', { node: process.version, executable: process.execPath, realpath: fs.realpathSync(process.execPath), argv: process.argv,
    dependencies: ['esbuild', 'react', 'react-dom', 'eslint'].map(name => ({ name, path: fs.realpathSync(require.resolve(name)) })) });
const manifest = read(path.join(handoff, 'source-index.json'));
assert.equal(manifest.files.length, 120);
for (const f of manifest.files) {
    for (const file of [f.source, f.copy]) { const bytes = fs.readFileSync(path.toNamespacedPath(file)); assert.equal(bytes.length, f.bytes); assert.equal(sha(bytes), f.sha256); }
}
write('source-originals-verified.json', { count: 120, digest: 'RAW_BYTES_SHA256', originalsReadOnly: true, files: manifest.files });
const original = (group, role) => { const sourcePath = manifest.scenes.find(x => x.id === group)?.roles[role]; const f = manifest.files.find(x => x.path === sourcePath); assert.ok(f, group + '/' + role); return read(f.copy); };
const fixtures = Object.fromEntries(manifest.scenes.filter(s => s.roles.saved).map(s => [s.id, {
    options: original(s.id, 'options'), saved: original(s.id, 'saved'), request: original(s.id, 'request'),
    failed: s.id === 'FAILED_RETRY' ? { ...original(s.id, 'failed'), analysisId: original(s.id, 'options').analysisId } : s.id === 'CWB_CHANGED_PINS' ? original(s.id, 'oldSaved') : null,
}]));
write('transport-adaptations.json', { failedReceipt: { sourceRole: 'FAILED_RETRY.failed', addedAnalysisIdFrom: 'FAILED_RETRY.options.analysisId', preservedOriginalFields: true, attribution: 'TEST_ONLY_RECEIPT_TO_ROUTED_HISTORY_NOT_NEW_BACKEND_GET' }, mapping: 'case-family-observation-v1: exactly one PROBAND; case/occurrence from facts root; no fabricated parents or events.' });
const coreOnly = ['NORMAL_ALPHA','NE_POSITIVE_ONLY'].map(group=>({group:group+'-core',result:structuredClone(fixtures[group].saved.candidates[0].attempts[0].pp4)}));
const ftPublished=original('FT_REJECTION','publishedSource');
const ftAnalysisId=ftPublished.analysisId; assert.ok(ftAnalysisId);
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helper = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
fs.copyFileSync(helper, path.join(output, 'helper-under-test.mjs'));
const h = await import(pathToFileURL(path.join(output, 'helper-under-test.mjs')).href);
const checks = [];
const check = (name, fn) => { fn(); checks.push(name); write('contract-progress.json', checks); };
// Independent backend-rule mirror for mutations only; these are not backend materials.
const canonical = v => typeof v === 'string' ? v.normalize('NFC') : Array.isArray(v) ? v.map(canonical)
    : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k.normalize('NFC'), canonical(v[k])])) : v;
const digest = v => sha(JSON.stringify(canonical(v)));
const a = fixtures.NORMAL_ALPHA;
for (const [name,f] of Object.entries(fixtures)) {
 check(name+' exact options/request and saved identity',()=>{const before=JSON.stringify(f);assert.equal(h.pp4ScopeIssue(f.options),null);assert.deepEqual(h.currentBusinessCriteria(f.options),['ACMG_AMP_2015:PP4']);assert.deepEqual(h.buildPp4Declaration(f.options,f.options.analysisId,true,f.failed||undefined),f.request);assert.ok(h.pp4SavedRun(f.saved));assert.equal(JSON.stringify(f),before);});
 check(name+' frozen saved/get full equality',()=>{if(manifest.scenes.find(s=>s.id===name).roles.get)assert.deepEqual(f.saved,original(name,'get'));});
 check(name+' explicit confirmation and old builders reject PP4',()=>{for(const v of [false,undefined,'true',1])assert.throws(()=>h.buildPp4Declaration(f.options,f.options.analysisId,v));for(const fn of [h.buildCaseDeclaration,h.buildSimplifiedCaseDeclaration,h.buildPs2Declaration,h.buildPm6Declaration,h.buildPm3Declaration,h.buildBp2Declaration,h.buildPp1Declaration,h.buildBs4Declaration])assert.throws(()=>fn(f.options,f.options.analysisId,true));assert.throws(()=>h.prepareSimplifiedInputs(f.options));});
}
const mutations=[
 ['profile',o=>{o.executionProfile.profileVersion='2';}],['clinical switch',o=>{delete o.executionProfile.clinicalValidationCompleted;}],['declaration',o=>{o.requiredDeclaration='ordinary-bp2-phase-v1';}],['scope',o=>{o.acceptanceScope='OTHER';}],['missing preparation',o=>{delete o.pp4Preparation;}],['missing profile',o=>{delete o.executionProfile;}],['mixed candidates',o=>{o.candidates=[];}],['mixed criteria',o=>{o.enabledCriteria.push('ACMG_AMP_2015:BA1');}],['analysis',o=>{o.analysisId+='x';}],['sample',o=>{o.sampleId+='x';}],['candidate',o=>{o.candidateIds[0]+='x';}],['raw pin',o=>{o.executionProfile.pp4MaterialPins.bundleRawSha256='0'.repeat(64);}],['family',o=>{o.pp4Preparation.materialBinding.familyBinding.familyContextId+='x';}],['patient',o=>{o.pp4Preparation.materialBinding.context.patientContext.affectedStatus='UNKNOWN';}],['member',o=>{o.pp4Preparation.reviewedObservation.facts.members[0].sampleId+='x';}],['token',o=>{o.pp4Selection.preparationToken=fixtures.HALF_2_5.options.pp4Selection.preparationToken;}],['case',o=>{o.pp4Selection.caseId+='x';}],['preconfirmed',o=>{o.pp4Selection.confirmed=true;}],['score injection',o=>{o.pp4Selection.points=1;}],['float',o=>{o.pp4Preparation.materialBinding.extra=0.5;}],['unsafe integer',o=>{o.pp4Preparation.materialBinding.context.variant.inputAllele.position=9007199254740992;}],['null family',o=>{o.pp4Preparation.patientContext.familyContextId=null;}]
];
for(const [name,mutate] of mutations)check('reject options '+name,()=>{const o=structuredClone(a.options);mutate(o);assert.ok(h.pp4ScopeIssue(o,a.options.analysisId));assert.deepEqual(h.currentBusinessCriteria(o),[]);assert.throws(()=>h.buildPp4Declaration(o,a.options.analysisId,true));});
for(const [input,bytes] of [[{z:['Cafe\u0301',{'e\u0301':'A\u030a'},null,true,7],a:'Cafe\u0301'},'{"a":"Café","z":["Café",{"é":"Å"},null,true,7]}'],[{},'{}'],[{familyContextId:null},'{"familyContextId":null}']])check('fixed independent canonical '+bytes,()=>assert.equal(h.pp4IdentityHash(input),sha(bytes)));
check('canonical rejects floating and unsafe integers',()=>{for(const v of [0.25,Number.MAX_SAFE_INTEGER+1])assert.throws(()=>h.pp4IdentityHash({v}));});
check('full v3 patient projection fixed original identity',()=>{const c=a.options.pp4Preparation.materialBinding.context;const p={domain:'varvis-evidence-context-v3',schemaVersion:c.schemaVersion,dataClass:c.dataClass,scope:c.scope,alleleId:c.variant.alleleId,hgncId:c.gene.hgncId,mondoId:c.disease.mondoId,moi:c.moi,patientContext:c.patientContext};assert.equal('context-sha256-'+digest(p),c.contextId);for(const mutate of [p=>{delete p.patientContext.affectedStatus;},p=>{delete p.patientContext.familyContextId;},p=>{p.patientContext.familyContextId=null;},p=>{p.transcript=null;}]){const q=structuredClone(p);mutate(q);assert.notEqual(digest(q),digest(p));}});
check('retry gates and exact CWB archived request',()=>{const n=fixtures.NE_POSITIVE_ONLY;assert.ok(h.pp4RetryEligible(n.saved,n.saved.analysisId));assert.deepEqual(h.buildPp4Declaration(n.options,n.options.analysisId,true,n.saved),{...n.request,retryOf:n.saved.runId});for(const r of [{...n.saved,status:'RUNNING'},{...n.saved,status:'COMPLETED'},{...n.saved,analysisId:'other'},{...fixtures.FAILED_RETRY.failed,acceptanceScope:'BP2_PHASE_DEVELOPMENT'}])assert.equal(h.pp4RetryEligible(r,n.saved.analysisId),false);});
const savedLinkageCases=[];
const sync=r=>{const attempt=r.candidates[0].attempts[0],c=attempt.pp4;if(c.evaluation)c.criterionEvaluations=[structuredClone(c.evaluation)];else if(c.evaluationResult) c.criterionEvaluations=[{criterionId:c.criterionId,evaluationResult:structuredClone(c.evaluationResult),criterionApplicability:structuredClone(c.criterionApplicability)}];r.bundles[0].core=structuredClone(c);attempt.criterionEvaluations=structuredClone(c.criterionEvaluations);};
const bad=(name,scene,mutate)=>check('saved reject '+name,()=>{const r=structuredClone(fixtures[scene].saved);mutate(r.candidates[0].attempts[0].pp4,r);sync(r);assert.equal(h.pp4SavedRun(r),false);savedLinkageCases.push({name,run:r});});
for(const key of ['policySelection','evidence','evaluation'])bad('missing-'+key,'HALF_2_5',c=>{delete c[key];});
for(const key of ['caseId','occurrenceId','familyId','evidenceGroupId'])bad('wrong-'+key,'HALF_2_5',c=>{c[key]+='x';});
bad('cross-background-payload','NORMAL_ALPHA',(c,r)=>{const beta=structuredClone(fixtures.HALF_2_5.saved.candidates[0].attempts[0].pp4);beta.sourceBundle=c.sourceBundle;r.candidates[0].attempts[0].pp4=beta;});
bad('evidence-context','HALF_2_5',c=>{c.evidence.contextId+='x';});bad('evaluation-context','HALF_2_5',c=>{c.evaluation.contextId+='x';});
bad('evidence-reference','HALF_2_5',c=>{c.evaluation.evidenceIds[0]+='x';});bad('trace-input','HALF_2_5',c=>{c.evaluation.deterministicTrace.inputEvidenceIds[0]+='x';});bad('trace-parameter','HALF_2_5',c=>{c.evaluation.deterministicTrace.parameters[0].value+='x';});bad('source-locator','HALF_2_5',c=>{c.evidence.source.sourceLocators[0].locatorValue+='x';});
for(const key of ['rawPoints','appliedPoints','rawHalfPoints','appliedHalfPoints','pointsEncoding','capReason','algorithmVersion'])bad('report-'+key,'HALF_2_5',c=>{c[key]='CORRUPT';});
bad('diagnostic-yield-source-reference','HALF_2_5',c=>{c.diagnosticYield.numerator+=1;});
bad('half-point-integer-truncation','HALF_2_5',c=>{c.rawPoints='2';c.appliedPoints='2';});
bad('half-point-numeric-instead-of-string','HALF_2_5',c=>{c.rawPoints=2.5;c.appliedPoints=2.5;});
bad('cap-applied-seven-instead-of-five','CAP_7_TO_5',c=>{c.appliedPoints='7';});
bad('r1-half-raw-integer-mismatch','HALF_2_5',c=>{c.rawHalfPoints=6;});
bad('r1-half-applied-integer-mismatch','HALF_2_5',c=>{c.appliedHalfPoints=4;});
bad('r1-half-false-cap-reason','HALF_2_5',c=>{c.capReason='SINGLE_PP4_CAP_5';});
bad('r1-capped-false-no-cap-reason','CAP_7_TO_5',c=>{c.capReason='NOT_CAPPED';});
for(const key of ['rawPoints','appliedPoints','diagnosticYield','rawHalfPoints','evidence','evaluationId','appliedStrength'])bad('NE-extra-'+key,'NE_POSITIVE_ONLY',c=>{c[key]=0;});
bad('NE-nested-points','NE_POSITIVE_ONLY',c=>{c.evaluationResult.rawPoints=0;});bad('NE-NOT_APPLICABLE','NE_POSITIVE_ONLY',c=>{c.criterionApplicability.status='NOT_APPLICABLE';});bad('NE-reason-conflict','NE_POSITIVE_ONLY',c=>{c.criterionApplicability.reasonCode='OTHER';});
check('original points preserve half and cap reports without frontend scoring',()=>{for(const [name,raw,applied,strength] of [['NORMAL_ALPHA','2','2','MODERATE'],['HALF_2_5','2.5','2.5','MODERATE'],['CAP_7_TO_5','7','5','STRONG']]){const c=fixtures[name].saved.candidates[0].attempts[0].pp4;assert.equal(c.rawPoints,raw);assert.equal(c.appliedPoints,applied);assert.equal(c.evaluation.appliedStrength,strength);}});
check('optional selector NE receipt allows core PP4-prefixed reason',()=>{const r=structuredClone(fixtures.NE_POSITIVE_ONLY.saved),c=r.candidates[0].attempts[0].pp4,cat=c.sourceBundle.catalog;const selection={schemaVersion:'acmg-specification-selection-v1',contextId:c.sourceBundle.context.contextId,criterionIds:['ACMG_AMP_2015:PP4'],...Object.fromEntries(['catalogId','catalogVersion','searchedAt','searchScope'].map(k=>[k,cat[k]])),catalogSha256:c.sourceBundle.catalogSha256,outcome:'SELECTION_INPUT_INCOMPLETE',reasonCode:'CLAUSE_SEARCH_SCOPE_NOT_COVERED'};selection.selectionId='cspec-selection-sha256-'+digest(selection);c.policySelection=selection;c.evaluationResult.reasonCode='PP4_'+selection.reasonCode;c.criterionApplicability.reasonCode=c.evaluationResult.reasonCode;sync(r);assert.ok(h.pp4SavedRun(r));write('optional-selector-NE.json',r);selection.contextId+='x';sync(r);assert.equal(h.pp4SavedRun(r),false);});

// Independent mirror of the read-only Java identity projection, not scientific scoring.
const without = (value, keys) => Object.fromEntries(Object.entries(value).filter(([key]) => !keys.includes(key)));
const sorted = values => [...values].sort((a, b) => { const x = JSON.stringify(canonical(a)), y = JSON.stringify(canonical(b)); return x < y ? -1 : x > y ? 1 : 0; });
function atomicProjection(e) {
    const p = without(e, ['evidenceId', 'deduplicationKey', 'provenance', 'conflict', 'review']);
    p.source = without(e.source, ['retrievedAt']);
    p.source.sourceLocators = sorted(e.source.sourceLocators.map(v => without(v, ['retrievedAt'])));
    p.source.sourceRecords = sorted(e.source.sourceRecords.map(v => ({ recordId: v.recordId, locator: without(v.locator, ['retrievedAt']) })));
    p.resourceExecution = without(e.resourceExecution, ['attemptedAt', 'completedAt']);
    p.measurements = sorted(e.measurements); p.derivedFromEvidenceIds = sorted(e.derivedFromEvidenceIds);
    p.provenance = { producerId: 'varvis-runtime-v2-adapter', producerVersion: '2.0.0-e2', sourcePayloadSha256: e.provenance.sourcePayloadSha256 };
    return p;
}
check('original content identities match independent Java projection', () => {
    for (const f of Object.values(fixtures)) { const c=f.saved.candidates[0].attempts[0].pp4; if(!c.evaluation) continue;
        assert.equal(c.evaluation.evaluationId,'evaluation-sha256-'+digest(without(c.evaluation,['evaluationId','conflict','review'])));
        assert.equal(c.evidence.evidenceId,'evidence-sha256-'+digest(atomicProjection(c.evidence)));
        assert.equal(c.evaluation.deterministicTrace.traceSha256,digest(without(c.evaluation.deterministicTrace,['traceSha256'])));
        assert.equal(c.policySelection.selectionId,'cspec-selection-sha256-'+digest(without(c.policySelection,['selectionId'])));
    }
});
bad('r1-evaluation-strength','NORMAL_ALPHA',c=>{c.evaluation.appliedStrength='STRONG';});
bad('r1-evidence-execution','HALF_2_5',c=>{c.evidence.resourceExecution.status='FAILED';c.evidence.resourceAvailability.status='UNAVAILABLE';});
bad('r1-trace-digest','HALF_2_5',c=>{c.evaluation.deterministicTrace.traceSha256='0'.repeat(64);});
bad('r1-selection-digest','HALF_2_5',c=>{c.policySelection.selectionId='cspec-selection-sha256-'+'0'.repeat(64);c.evaluation.ruleBinding.cspecSelectionId=c.policySelection.selectionId;});
const reidentify = c => {
    const e=c.evidence,v=c.evaluation,t=v.deterministicTrace;
    e.evidenceId='evidence-sha256-'+digest(atomicProjection(e));e.provenance.identityProjectionSha256=e.evidenceId.slice(16);
    v.evidenceIds=[e.evidenceId];t.inputEvidenceIds=[e.evidenceId];t.steps[0].inputEvidenceIds=[e.evidenceId];
    t.traceSha256=digest(without(t,['traceSha256']));v.evaluationId='evaluation-sha256-'+digest(without(v,['evaluationId','conflict','review']));
};
bad('r1-reidentified-failed-evidence-still-not-MET','HALF_2_5',c=>{c.evidence.resourceExecution.status='FAILED';c.evidence.resourceAvailability.status='UNAVAILABLE';reidentify(c);});
check('projection exclusions preserve identity without modifying source or input',()=>{
    const r=structuredClone(a.saved),c=r.candidates[0].attempts[0].pp4;
    c.evidence.resourceExecution.attemptedAt='2026-10-02T00:00:00Z';c.evidence.resourceExecution.completedAt='2026-10-02T00:00:01Z';
    c.evidence.deduplicationKey='TEST_ONLY_EXCLUDED';c.evidence.provenance.producerVersion='TEST_ONLY_EXCLUDED';
    c.evidence.review.note='TEST_ONLY_EXCLUDED';c.evidence.conflict.note='TEST_ONLY_EXCLUDED';c.evaluation.review.note='TEST_ONLY_EXCLUDED';c.evaluation.conflict.note='TEST_ONLY_EXCLUDED';
    sync(r);const before=JSON.stringify(r);assert.ok(h.pp4SavedRun(r));assert.equal(JSON.stringify(r),before);
});
check('atomic array canonical sorting and NFC bounded transport vector',()=>{
    const r=structuredClone(a.saved),c=r.candidates[0].attempts[0].pp4;
    c.evidence.measurements=[{metricId:'TEST_ONLY_Z',rawValue:'Café'},{metricId:'TEST_ONLY_A',rawValue:'Å'}];
    reidentify(c);sync(r);assert.ok(h.pp4SavedRun(r));const identity=c.evidence.evidenceId;
    c.evidence.measurements.reverse();c.evidence.measurements.forEach(v=>{v.rawValue=v.rawValue.normalize('NFD');});sync(r);
    const before=JSON.stringify(r);assert.equal('evidence-sha256-'+digest(atomicProjection(c.evidence)),identity);assert.ok(h.pp4SavedRun(r));assert.equal(JSON.stringify(r),before);
});
check('old PP4 boundary object and ledger mention are not business markers',()=>{for(const v of [{pp4:{status:'NOT_EVALUATED',reasonCode:'PP4_OUTSIDE_THIS_CORE'}},{usedEvidence:[{criterionId:'ACMG_AMP_2015:PP4'}]}])assert.equal(h.pp4ScopeClaim(v),false);});
check('explicit mixed PP4 markers reject old scope builders',()=>{for(const mark of [{pp4Selection:{}},{pp4Preparation:{}},{executionProfile:{profileId:'acmg-pp4-phenotype-development-v1'}},{token:'PP4_PHENOTYPE_CONTEXT@1.0.0:x'}])for(const fn of [h.buildCaseDeclaration,h.buildSimplifiedCaseDeclaration,h.buildPs2Declaration,h.buildPm6Declaration,h.buildPm3Declaration,h.buildBp2Declaration,h.buildPp1Declaration,h.buildBs4Declaration])assert.throws(()=>fn(mark,'test',true));});
write('contract-validation.json', { status: 'PASS', count: checks.length, checks, requestComparison: 'FULL_JSON_NO_EXCLUSIONS_NO_NORMALIZATION', scientificExecution: 0 });

await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};import Matrix from ${JSON.stringify(path.join(path.dirname(component), 'AcmgEvidenceMatrix.jsx'))};export const savedElement=run=>SavedResult({run,criteria:[]});export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);export const renderCore=pp4=>renderToStaticMarkup(<><p>CORE_RESULT_ONLY</p><Matrix candidate={{candidateId:'CORE_RESULT_ONLY',attempts:[{pp4}]}} enabledCriteria={['ACMG_AMP_2015:PP4']}/></>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
    plugins: [{ name: 'export-test-component', setup(api) { api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) })); } }] });
const renderer = require(path.join(output, 'render.cjs'));

// Invoke the actual SavedResult export handler under Node stubs: serialization only, no UI/download.
const rejectionRenders=[];
for(const {name,run} of savedLinkageCases){
    const html=renderer.render(run,[]); assert.ok(html.includes('保存的 PP4 身份不完整或相互冲突'));assert.ok(!html.includes('PP4 致病方向证据已保存'));assert.ok(!html.includes('实际强度：'));assert.ok(html.includes('下载本次原始结果 JSON'));
    const tree=renderer.savedElement(run);const find=v=>{if(!v)return null;if(Array.isArray(v))return v.map(find).find(Boolean);if(v.props?.children==='下载本次原始结果 JSON')return v;return find(v.props?.children);};const button=find(tree);assert.equal(typeof button?.props.onClick,'function');
    const previous={document:globalThis.document,create:URL.createObjectURL,revoke:URL.revokeObjectURL,timer:globalThis.setTimeout};let blob,filename,revoked=false;
    try{globalThis.document={createElement:tag=>{assert.equal(tag,'a');return{click(){filename=this.download;}};}};URL.createObjectURL=b=>{blob=b;return 'blob:SERIALIZATION_TEST_ONLY';};URL.revokeObjectURL=()=>{revoked=true;};globalThis.setTimeout=fn=>{fn();};button.props.onClick();assert.deepEqual(JSON.parse(await blob.text()),run);assert.equal(filename,'acmg-'+run.runId+'.json');assert.ok(revoked);}
    finally{if(previous.document===undefined)delete globalThis.document;else globalThis.document=previous.document;URL.createObjectURL=previous.create;URL.revokeObjectURL=previous.revoke;globalThis.setTimeout=previous.timer;}
    write('r1-'+name+'.json',run);fs.writeFileSync(path.join(output,'r1-'+name+'.html'),html);rejectionRenders.push({name,warning:true,trustedConclusion:false,fullJsonSerialization:true});
}
write('saved-linkage-validation.json',{status:'PASS',attribution:'FRONTEND_MUTATIONS_AND_ACTUAL_SAVEDRESULT_SSR_HANDLER_SERIALIZATION_NOT_DOWNLOAD',cases:rejectionRenders});

const renders = [];
for (const [name, core, html] of [...Object.entries(fixtures).map(([name, f]) => [name, f.saved.candidates[0].attempts[0].pp4, renderer.render(f.saved, ['ACMG_AMP_2015:BA1'])]),
    ...coreOnly.map(f => [f.group, f.result, renderer.renderCore(f.result)])]) {
    const card = h.pp4EvidenceEntry(core), e = card.evaluation;
    assert.ok(html.includes('PP4')); assert.ok(html.includes(e.evaluationResult.reasonCode)); assert.ok(html.includes(h.reasonHelp(e.evaluationResult.reasonCode)));
    assert.ok(html.includes(e.appliedStrength || '实际强度：未记录'));
    assert.ok(!html.includes('需要 CSpec / 人工复核处理')); fs.writeFileSync(path.join(output, name + '.html'), html);
    renders.push({ name, status: e.evaluationResult.status, rawPoints: core.rawPoints ?? null, strength: e.appliedStrength ?? null });
}
const malformed = structuredClone(a.saved); malformed.bundles[0].coreRawSha256 = 'bad';
assert.ok(!renderer.render(malformed, ['ACMG_AMP_2015:PP4']).includes('实际强度：'));
write('render-validation.json', { status: 'PASS', actualReactServerComponents: true, renders, malformedDoesNotShowConclusion: true });
for(const [name,f] of Object.entries(fixtures)){const tree=renderer.savedElement(f.saved);const find=v=>!v?null:Array.isArray(v)?v.map(find).find(Boolean):v.props?.children==='下载本次原始结果 JSON'?v:find(v.props?.children);let blob;const old={d:globalThis.document,c:URL.createObjectURL,r:URL.revokeObjectURL,t:globalThis.setTimeout};try{globalThis.document={createElement:()=>({click(){return undefined;}})};URL.createObjectURL=v=>{blob=v;return 'blob:SERIALIZATION_ONLY';};URL.revokeObjectURL=()=>undefined;globalThis.setTimeout=f=>f();find(tree).props.onClick();assert.deepEqual(JSON.parse(await blob.text()),f.saved);write(name+'-serialization.json',{fullJson:true,actualDownload:false});}finally{globalThis.document=old.d;URL.createObjectURL=old.c;URL.revokeObjectURL=old.r;globalThis.setTimeout=old.t;}}
await buildPp4ComponentChecks({ project, output, fixtures, build });
const entry = `import React,{useState} from 'react';import {createRoot} from 'react-dom/client';import Panel from ${JSON.stringify(component)};
function Replay(){const [scene,setScene]=useState(new URLSearchParams(location.search).get('scene') || 'HALF_2_5'),[status,setStatus]=useState('TEST_ONLY archive transport');const ids=${JSON.stringify({...Object.fromEntries(Object.entries(fixtures).map(([k, f]) => [k, f.saved.analysisId])),FT_REJECTION:ftAnalysisId})};
async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json()));}
return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PP4 · 真实普通页面</h1><p>单家系归档回放；无科学程序、后端或数据库执行。实际下载待独立接纳后由用户手动完成。</p>
<label>归档场景 <select value={scene} onChange={e=>setScene(e.target.value)}>{Object.keys(ids).map(k=><option key={k}>{k}</option>)}</select></label>{' '}
<button onClick={()=>control('control',{delayNext:true})}>延迟下一次提交响应</button>{' '}<button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
<button onClick={()=>control('control',{history:true})}>NE_POSITIVE_ONLY 当前 options409</button>{' '}<button onClick={()=>control('control',{history:false})}>恢复 NE_POSITIVE_ONLY options</button>{' '}
<button onClick={()=>control('control',{ftFailure:true})}>当前 FT 来源失败</button>{' '}<button onClick={()=>control('control',{ftFailure:false})}>恢复 FT 来源</button>{' '}<button onClick={()=>control('state')}>读取请求计数</button><pre>{status}</pre></aside><Panel analysisId={ids[scene]}/></>};createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
write('bundle-inputs.json', bundle.metafile.inputs);
for (const suffix of ['OrdinaryAcmgEvaluationPanel.jsx', 'AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js']) assert.ok(Object.keys(bundle.metafile.inputs).some(x => x.replaceAll('\\', '/').endsWith(suffix)));
write('assets.json', ['entry.jsx', 'app.js', 'component-checks.js'].map(f => ({ file: f, bytes: fs.statSync(path.join(output, f)).size, rawSha256: sha(fs.readFileSync(path.join(output, f))) })));

const state = { history: readOnly, readOnly, ftFailure: false, delayNext: false }, completed = new Set(), journal = [], pending = [];
let origin;
const snapshot = () => ({ ...state, postCount: journal.filter(e => e.kind === 'POST_RUN').length, acceptedPosts: journal.filter(e => e.kind === 'POST_RUN' && e.status === 200).length,
    pending: pending.length, scientificExecution: 0, output });
function finish() { pending.splice(0).forEach(p => p.send()); write('summary.json', snapshot()); server.close(); server.closeAllConnections(); }
const server = http.createServer(async (req, res) => {
    const event = { at: new Date().toISOString(), executionClass: selfCheck ? 'HTTP_SELF_CHECK' : 'BROWSER_REPLAY', method: req.method, url: req.url, kind: 'OTHER' };
    const reply = (status, value, type = 'application/json', delay = false) => {
        event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'requests.jsonl'), JSON.stringify(event) + '\n');
        const send = () => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': type + '; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(type === 'application/json' ? JSON.stringify(value) : value); } };
        if (delay) { state.delayNext = false; const p = { send: () => { clearTimeout(p.timer); const i = pending.indexOf(p); if (i >= 0) pending.splice(i, 1); send(); } }; p.timer = setTimeout(p.send, 120000); pending.push(p); } else send();
    };
    try {
        if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site') return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        const url = new URL(req.url, origin);
        res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
        if (req.method === 'GET' && ['/', '/component-tests'].includes(url.pathname)) return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PP4 TEST_ONLY</title><div id="root"></div><script src="/' + (url.pathname === '/' ? 'app.js' : 'component-checks.js') + '"></script></html>', 'text/html');
        if (req.method === 'GET' && ['/app.js', '/component-checks.js'].includes(url.pathname)) return reply(200, fs.readFileSync(path.join(output, url.pathname.slice(1))), 'text/javascript');
        let body;
        if (req.method === 'POST') { assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0; for await (const chunk of req) { size += chunk.length; assert.ok(size < 2e6); chunks.push(chunk); } body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body; }
        if (url.pathname.startsWith('/__test/')) event.kind = 'CONTROL';
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') { for (const k of ['readOnly', 'history', 'ftFailure', 'delayNext']) if (typeof body[k] === 'boolean') state[k] = k === 'readOnly' ? readOnly || body[k] : body[k]; return reply(200, snapshot()); }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach(p => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/component-results' && req.method === 'POST') { event.kind = 'COMPONENT_TEST_RECEIPT'; write('component-validation.json', body); return reply(200, { saved: true }); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname.startsWith('/__test/')) return reply(405, { message: 'TEST_ONLY_METHOD_OR_ROUTE' });
        const m = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        if(m && m[1]===ftAnalysisId){event.kind=m[2]==='input-options'?'GET_OPTIONS':req.method==='POST'?'POST_RUN':'GET_RUNS';if(req.method==='GET' && m[2]==='runs' && !m[3])return reply(200,[]);return reply(409,{message:'PP4_PUBLISHED_SAMPLE_FILTER_FAILED',testOnlyTransport:true,source: 'FT_REJECTION archived service refusal'});}
        const pair = m && Object.entries(fixtures).find(([, f]) => f.saved.analysisId === m[1]);
        if (!pair) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        const [name, f] = pair;
        if (req.method === 'GET' && m[2] === 'input-options') { event.kind = 'GET_OPTIONS';
            if (state.ftFailure) return reply(409, { message: 'PP4_PUBLISHED_SAMPLE_FILTER_FAILED', testOnlyTransport: true });
            if (state.history && name === 'NE_POSITIVE_ONLY') return reply(409, { message: 'PP4_MATERIAL_UNAVAILABLE', testOnlyTransport: true });
            if (url.searchParams.get('topN') !== '1') return reply(400, { message: 'TEST_ONLY_TOP1_REQUIRED' }); return reply(200, f.options); }
        if (req.method === 'GET' && m[2] === 'runs') { event.kind = m[3] ? 'GET_RUN' : 'GET_RUNS';
            const rows = state.readOnly || completed.has(name) || !['HALF_2_5', 'NE_POSITIVE_ONLY', 'FAILED_RETRY', ...(selfCheck ? ['CWB_CHANGED_PINS'] : [])].includes(name) ? [f.saved, ...(f.failed ? [f.failed] : [])] : f.failed ? [f.failed] : [];
            if (!m[3]) return reply(200, rows.map(r => ({ runId: r.runId, status: r.status, createdAt: r.createdAt })));
            const r = rows.find(r => r.runId === m[3]); return r ? reply(200, r) : reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' }); }
        if (req.method === 'POST' && m[2] === 'runs' && !m[3]) { event.kind = 'POST_RUN'; const id = journal.filter(e => e.kind === 'POST_RUN').length + 1;
            write('request-' + id + '-incoming.json', body); write('request-' + id + '-expected.json', f.request);
            assert.ok(!state.readOnly && !state.ftFailure && ['HALF_2_5', 'NE_POSITIVE_ONLY', 'FAILED_RETRY', ...(selfCheck ? ['CWB_CHANGED_PINS'] : [])].includes(name) && !(state.history && name === 'NE_POSITIVE_ONLY'), 'NO_CURRENT_PERMISSION');
            const expected = f.request; assert.deepEqual(body, expected); write('request-' + id + '-expected.json', expected); write('request-' + id + '-comparison.json', { status: 'PASS', fullJson: true, excludedFields: [], normalizedFields: [], expected: 'ARCHIVED_BACKEND_REQUEST', scene: name });
            completed.add(name); return reply(200, f.saved, undefined, state.delayNext); }
        return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
    } catch (e) { return reply(400, { message: 'TEST_ONLY_CONTRACT_REJECTED: ' + e.message }); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); origin = 'http://127.0.0.1:' + server.address().port;
write('server.json', { origin, output, pid: process.pid, testOnly: true, browserStatus: 'NOT_RUN', startedAt: new Date().toISOString() });
console.log(JSON.stringify({ origin, output, pid: process.pid }));
if (!selfCheck) { process.once('SIGINT', finish); process.once('SIGTERM', finish); }
else {
    try {
        const post = (route, body) => fetch(origin + route, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        assert.equal((await fetch(origin)).status, 200); assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        assert.equal((await fetch(origin + '/__test/control')).status, 405);
        for (const [name, f] of Object.entries(fixtures)) {
            const route = '/variant-analyses/' + f.saved.analysisId + '/acmg';
            assert.equal((await fetch(origin + route + '/input-options?topN=1')).status, 200);
            if (!['HALF_2_5', 'NE_POSITIVE_ONLY', 'FAILED_RETRY'].includes(name)) continue;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            if (f.failed) assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.failed.runId)).json(), f.failed);
            const response = await post(route + '/runs', h.buildPp4Declaration(f.options, f.options.analysisId, true, f.failed || undefined));
            assert.equal(response.status, 200); assert.deepEqual(await response.json(), f.saved);
            assert.equal((await post(route + '/runs', { ...f.request, extra: true })).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        const u=fixtures.CWB_CHANGED_PINS; const retryResponse=await post('/variant-analyses/'+u.saved.analysisId+'/acmg/runs',h.buildPp4Declaration(u.options,u.saved.analysisId,true,u.failed)); assert.equal(retryResponse.status,200); assert.deepEqual(await retryResponse.json(),u.saved);
        assert.equal((await post('/__test/control', { history: true })).status, 200);
        const unknownRoute = '/variant-analyses/' + fixtures.NE_POSITIVE_ONLY.saved.analysisId + '/acmg';
        assert.equal((await fetch(origin + unknownRoute + '/input-options?topN=1')).status, 409);
        assert.equal((await post(unknownRoute + '/runs', fixtures.NE_POSITIVE_ONLY.request)).status, 400);
        assert.equal((await post('/__test/control', { readOnly: true })).status, 200);
        for (const f of Object.values(fixtures)) { const route = '/variant-analyses/' + f.saved.analysisId + '/acmg'; assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved); assert.equal((await post(route + '/runs', f.request)).status, 400); }
        assert.equal((await post('/__test/release', {})).status, 200);
        write('http-validation.json', { status: 'PASS', ...snapshot(), allReadOnlyHistoriesMatchFullJson: true, explicitControls: true, realDownloads: 'NOT_RUN_USER_MANUAL_ONLY' });
        assert.equal((await post('/__test/stop', {})).status, 200);
    } catch (e) { write('http-failure.json', { message: e.message, stack: e.stack }); finish(); throw e; }
}
