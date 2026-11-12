import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { buildPm3ComponentChecks } from './pm3-business-component-checks.mjs';

// Isolated archive transport: no backend, database, scientific program or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const task = path.join(checkout, 'local/temp/PM3-ui-business-20260929/dev-01');
const handoff = path.join(checkout, 'local/development/handoffs/PM3-ui-business-20260929/dev-01');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every(x => x === '--self-check'));
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const read = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const sourceFiles = ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'scripts/pm3-business-browser-smoke.mjs', 'scripts/pm3-business-component-checks.mjs'];
write('source-before.json', sourceFiles.map(file => {
    const raw = fs.readFileSync(path.join(project, file)); fs.writeFileSync(path.join(output, path.basename(file)), raw);
    return { file, bytes: raw.length, sha256: sha(raw) };
}));
const require = createRequire(path.join(project, 'package.json'));
write('tools.json', { node: process.version, executable: process.execPath, realpath: fs.realpathSync(process.execPath), argv: process.argv,
    dependencies: ['esbuild', 'react', 'react-dom', 'eslint'].map(name => ({ name, path: fs.realpathSync(require.resolve(name)) })) });
const manifest = read(path.join(handoff, 'source-index.json'));
assert.equal(manifest.files.length, 46);
for (const f of manifest.files) {
    for (const file of [f.source, f.copy]) { const bytes = fs.readFileSync(path.toNamespacedPath(file)); assert.equal(bytes.length, f.bytes); assert.equal(sha(bytes), f.sha256); }
}
write('source-originals-verified.json', { count: 46, digest: 'RAW_BYTES_SHA256', originalsReadOnly: true, files: manifest.files });
const original = (group, role) => { const f = manifest.files.find(x => x.scene === group && x.role === role); assert.ok(f, group + '/' + role); return read(f.copy); };
const fixtures = Object.fromEntries(manifest.scenes.map(s => [s.id, {
    options: original(s.id, 'options'), saved: original(s.id, 'saved'), request: original(s.id, 'request'),
    failed: s.id === 'FAILED_RETRY_ALPHA' ? original(s.id, 'failed') : null,
}]));
const coreOnly = ['NORMAL_ALPHA','NORMAL_BETA'].map(group=>({group:group+'-core',result:original(group,'directCore')}));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helper = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const h = await import(frontendModuleUrl('data:text/javascript;base64,' + fs.readFileSync(helper).toString('base64')));
const checks = [];
const check = (name, fn) => { fn(); checks.push(name); write('contract-progress.json', checks); };
// Independent backend-rule mirror for mutations only; these are not backend materials.
const canonical = v => typeof v === 'string' ? v.normalize('NFC') : Array.isArray(v) ? v.map(canonical)
    : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k.normalize('NFC'), canonical(v[k])])) : v;
const digest = v => sha(JSON.stringify(canonical(v)));
const a = fixtures.NORMAL_ALPHA;
for (const [name, f] of Object.entries(fixtures)) {
    check(name + ' intact identity and exact request (no normalization)', () => {
        const before = JSON.stringify(f); assert.equal(h.pm3ScopeIssue(f.options, f.options.analysisId), null);
        assert.deepEqual(h.currentBusinessCriteria(f.options), ['ACMG_AMP_2015:PM3']);
        assert.deepEqual(h.buildPm3Declaration(f.options, f.options.analysisId, true, f.failed || undefined), f.request);
        assert.ok(h.pm3SavedRun(f.saved)); assert.equal(JSON.stringify(f), before);
    });
    check(name + ' explicit confirmation and legacy entry guards', () => {
        for (const confirmed of [false, undefined, 'true', 1]) assert.throws(() => h.buildPm3Declaration(f.options, f.options.analysisId, confirmed));
        assert.throws(() => h.buildSimplifiedCaseDeclaration(f.options, f.options.analysisId, {}));
        assert.throws(() => h.buildCaseDeclaration(f.options, f.options.analysisId, {}, {}));
        assert.throws(() => h.prepareSimplifiedInputs(f.options));
    });
}
const mutations = [
    ['profile', o => { o.executionProfile.profileVersion = '2.0.0'; }], ['declaration', o => { o.requiredDeclaration = 'ordinary-bp1-missense-v1'; }],
    ['scope', o => { o.acceptanceScope = 'UNKNOWN'; }], ['missing preparation', o => { delete o.pm3Preparation; }], ['missing profile', o => { delete o.executionProfile; }],
    ['mixed candidates', o => { o.candidates = []; }], ['mixed criteria', o => { o.enabledCriteria.push('ACMG_AMP_2015:BP1'); }],
    ['analysis', o => { o.analysisId = fixtures.UNKNOWN_ALPHA.options.analysisId; }], ['sample', o => { o.sampleId += 'x'; }],
    ['candidate', o => { o.candidateIds[0] += 'x'; }], ['pin', o => { o.executionProfile.pm3MaterialPins.bundleRawSha256 = '0'.repeat(64); }],
    ['binding', o => { o.pm3Preparation.materialBinding.familyId += 'x'; }], ['patient', o => { o.pm3Preparation.materialBinding.context.patientContext.sex = 'FEMALE'; }],
    ['case', o => { o.pm3Selection.caseId += 'x'; }], ['observation', o => { o.pm3Selection.observationId += 'x'; }], ['occurrence', o => { o.pm3Selection.occurrenceId += 'x'; }],
    ['token', o => { o.pm3Selection.preparationToken = fixtures.UNKNOWN_ALPHA.options.pm3Selection.preparationToken; }],
    ['tx injection', o => { o.pm3Selection.transcript = {}; }], ['result injection', o => { o.pm3Selection.result = 'MET'; }], ['preconfirmed', o => { o.pm3Selection.confirmed = true; }],
    ['float', o => { o.pm3Preparation.materialBinding.publishedInputIdentity.extra = 0.5; }], ['unsafe integer', o => { o.pm3Preparation.materialBinding.context.variant.inputAllele.position = 9007199254740992; }],
];
for (const [name, mutate] of mutations) check('reject ' + name, () => { const o = structuredClone(a.options); mutate(o); assert.ok(h.pm3ScopeIssue(o, a.options.analysisId)); assert.deepEqual(h.currentBusinessCriteria(o), []); assert.throws(() => h.buildPm3Declaration(o, a.options.analysisId, true)); });
const vectors = [
    [{ z: ['Cafe\u0301', { 'e\u0301': 'A\u030a' }, null, true, 7], a: 'Cafe\u0301' }, '{"a":"Café","z":["Café",{"é":"Å"},null,true,7]}'],
    [{ 'é': 1, z: 2, 'e\u0301x': 3 }, '{"éx":3,"z":2,"é":1}'], [{}, '{}'], [{ familyContextId: null }, '{"familyContextId":null}'],
];
for (const [input, bytes] of vectors) check('fixed canonical bytes ' + bytes, () => { assert.equal(h.pm3IdentityHash(input), sha(bytes)); });
check('NFD remains intact while matching existing NFC identity', () => {
    const o = structuredClone(a.options); o.pm3Preparation.materialBinding.context.disease.label = o.pm3Preparation.materialBinding.context.disease.label.normalize('NFD');
    const before = JSON.stringify(o); assert.equal(h.pm3ScopeIssue(o), null); assert.ok(h.buildPm3Declaration(o, o.analysisId, true)); assert.equal(JSON.stringify(o), before);
});
check('v3 full patient projection and absent transcript fixed original identity', () => {
    const c = a.options.pm3Preparation.materialBinding.context;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: c.patientContext };
    assert.equal(digest(projection), '80178c0ff26c5d47e8f1aa62b8314f7e3e157d9fc6402497ff7c689b9f4674a4');
    assert.equal(h.pm3IdentityHash(projection), digest(projection));
    for (const mutate of [v => { delete v.patientContext.affectedStatus; }, v => { v.patientContext.familyContextId = null; }, v => { delete v.patientContext.familyContextId; }, v => { v.transcript = null; }]) {
        const v = structuredClone(projection); mutate(v); assert.notEqual(h.pm3IdentityHash(v), digest(projection));
    }
    write('independent-identity-vectors.json', { fixedCanonicalBytes: vectors, originalV3Projection: projection, expected: c.contextId, provenance: 'FIXED_ORIGINAL_BACKEND_CONTEXT_AND_LITERAL_UTF8_VECTORS' });
});
check('standalone core and criterion name do not grant business capability', () => { assert.equal(h.pm3ScopeClaim({ resultMode: 'CORE_RESULT_ONLY', criterionId: 'ACMG_AMP_2015:PM3' }), false); assert.equal(h.pm3ScopeClaim({ enabledCriteria: ['ACMG_AMP_2015:PM3'] }), false); });
for (const status of ['FAILED', 'COMPLETED_WITH_BLOCKERS', 'RUNNING', 'COMPLETED']) check('retry state ' + status, () => {
    const r = { ...fixtures.FAILED_RETRY_ALPHA.failed, status };
    assert.equal(h.pm3RetryEligible(r, r.analysisId), ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(status));
    if (!h.pm3RetryEligible(r, r.analysisId)) assert.throws(() => h.buildPm3Declaration(fixtures.FAILED_RETRY_ALPHA.options, r.analysisId, true, r));
    assert.equal(h.pm3RetryEligible(r, a.options.analysisId), false);
});
for (const [name, mutate] of [
    ['artifact run', r => { r.bundles[0].runId += 'x'; }], ['candidate', r => { r.candidates[0].candidateId += 'x'; }],
    ['core', r => { r.candidates[0].attempts[0].pm3.pm3Points = 0; }], ['criterion', r => { r.candidates[0].attempts[0].criterionEvaluations = []; }],
    ['profile', r => { delete r.executionProfile; }], ['context', r => { r.bundles[0].preparation.materialBinding.context.patientContext.sex = 'FEMALE'; }],
    ['mixed attempt', r => { r.candidates[0].attempts[0].bp1 = {}; }], ['raw pin', r => { r.bundles[0].coreRawSha256 = 'bad'; }],
]) check('saved rejects ' + name, () => { const r = structuredClone(a.saved); mutate(r); assert.ok(h.pm3ScopeClaim(r)); assert.equal(h.pm3SavedRun(r), false); });

// Frontend-only mutations: outer token refresh isolates duplicate binding checks.
const retoken = o => { const p=o.pm3Preparation; p.preparationToken='PM3_IN_TRANS_CONTEXT@1.0.0:'+digest(Object.fromEntries(Object.entries(p).filter(([k])=>k!=='preparationToken'))); o.pm3Selection.preparationToken=p.preparationToken; };
for (const [label, mutate] of [
    ['patient copy', o=>{o.pm3Preparation.patientContext.sex='FEMALE';}],
    ['phase copy method', o=>{o.pm3Preparation.reviewedObservation.phase.method+=' altered';}],
    ['source copy raw', o=>{o.pm3Preparation.reviewedObservation.facts.sources[0].rawUtf8+=' ';}],
]) check('retokened duplicate mismatch '+label,()=>{const o=structuredClone(a.options);mutate(o);retoken(o);assert.ok(h.pm3ScopeIssue(o));});
const paths=[];const leaves=(v,p=[])=>{if(v&&typeof v==='object')Object.entries(v).forEach(([k,x])=>leaves(x,[...p,k]));else paths.push(p);};leaves(a.options.pm3Preparation.reviewedObservation);
check('every reviewed observation leaf remains bound',()=>{for(const keys of paths){const o=structuredClone(a.options);let v=o.pm3Preparation.reviewedObservation;for(const key of keys.slice(0,-1))v=v[key];v[keys.at(-1)]=String(v[keys.at(-1)])+' altered';assert.ok(h.pm3ScopeIssue(o),keys.join('.'));}write('observation-mutation-paths.json',{attribution:'FRONTEND_CONTRACT_MUTATION_ONLY',paths});});
check('PM3 mixed with PS2 rejected in both dispatches',()=>{const o=structuredClone(a.options);o.ps2Selection={};assert.ok(h.pm3ScopeIssue(o));assert.ok(h.ps2ScopeIssue(o));assert.throws(()=>h.validateInputOptions(o,o.analysisId));});
check('legacy derived builders reject PM3 contamination',()=>{for(const fn of [h.buildSavedCalibrationDeclaration,h.buildSavedObservationAssociationDeclaration,h.buildRevelApplicationDeclaration])assert.throws(()=>fn(a.options,a.options.analysisId,a.saved));});
check('known foreign retry rejected, slim same-analysis FAILED retained',()=>{const f=fixtures.FAILED_RETRY_ALPHA;assert.ok(h.pm3RetryEligible(f.failed,f.saved.analysisId));for(const scope of ['PS2_DENOVO_DEVELOPMENT','BP1_MISSENSE_DEVELOPMENT'])assert.equal(h.pm3RetryEligible({...f.failed,acceptanceScope:scope},f.saved.analysisId),false);assert.ok(h.pm3RetryEligible(fixtures.UNKNOWN_ALPHA.saved,fixtures.UNKNOWN_ALPHA.saved.analysisId));});
for(const [name,f] of Object.entries(fixtures))check(name+' complete export JSON including fractions and source raw strings',()=>{assert.deepEqual(JSON.parse(JSON.stringify(f.saved,null,2)),f.saved);});

check('malformed retry profile fails closed without throwing',()=>{const r=structuredClone(fixtures.UNKNOWN_ALPHA.saved);r.executionProfile.extra=0.25;assert.equal(h.pm3RetryEligible(r,r.analysisId),false);delete r.executionProfile;r.enabledCriteria=['ACMG_AMP_2015:BP1'];assert.equal(h.pm3RetryEligible(r,r.analysisId),false);assert.equal(h.pm3RetryEligible(undefined,undefined),false);});

check('third ranked witness is not a second requested target; pair is symmetric only',()=>{
    const o=a.options,p=o.pm3Preparation,b=p.materialBinding;
    assert.deepEqual(o.candidateIds,['exomiser-v1-00000001']);assert.equal(p.partnerCandidateId,'exomiser-v1-00000003');
    const alleles=[b.pairBinding.targetAlleleId,b.pairBinding.partnerAlleleId];assert.equal('pair-sha256-'+digest(alleles.slice().sort()),b.pairBinding.pairId);
    assert.equal(digest(alleles.slice().reverse().sort()),digest(alleles.slice().sort()));
    const changed=structuredClone(o);[changed.pm3Selection.targetAlleleId,changed.pm3Selection.partnerAlleleId]=alleles.slice().reverse();assert.ok(h.pm3ScopeIssue(changed));
});
check('all legacy declaration entry points reject PM3 scope and contamination',()=>{
    for(const name of ['buildCaseDeclaration','buildSimplifiedCaseDeclaration','prepareSimplifiedInputs','buildSavedCalibrationDeclaration','buildSavedObservationAssociationDeclaration','buildRevelApplicationDeclaration','buildPs2Declaration','buildPm6Declaration']) assert.throws(()=>h[name](a.options,a.options.analysisId,{},{}),name);
    for(const key of ['ps2Selection','pm6Selection']){const o=structuredClone(a.options);o[key]={};assert.ok(h.pm3ScopeIssue(o));assert.throws(()=>h.validateInputOptions(o,o.analysisId));}
});
check('UNKNOWN transport and exact explicit CWB retry preserve minimal NE',()=>{
    const f=fixtures.UNKNOWN_ALPHA, core=f.saved.candidates[0].attempts[0].pm3;
    assert.deepEqual(h.buildPm3Declaration(f.options,f.saved.analysisId,true,f.saved),original('UNKNOWN_ALPHA','retryRequest'));
    assert.equal(core.evaluationResult.reasonCode,'PM3_PHASE_DECLARATION_CONFLICT');
    assert.equal(f.options.pm3Preparation.reviewedObservation.phase.conclusion,'UNKNOWN');
    for(const k of ['pm3Points','evaluation','evaluationId','evidence','appliedStrength','deterministicTrace'])assert.equal(Object.hasOwn(core,k),false);
});
check('both allele identities fixed backend vectors; no mutation of source',()=>{
    const {target,partner}=a.options.pm3Preparation.reviewedObservation.facts.alleles;
    for(const v of [target,partner]){const projection={normalizedAllele:v.normalizedAllele,normalizer:Object.fromEntries(['method','methodVersion','referenceSequenceSha256','refValidation'].map(k=>[k,v.normalization[k]]))};assert.equal('allele-sha256-'+digest(projection),v.alleleId);assert.equal(h.pm3IdentityHash(projection),digest(projection));}
    const before=JSON.stringify(a);h.pm3ScopeIssue(a.options);h.buildPm3Declaration(a.options,a.saved.analysisId,true);h.pm3SavedRun(a.saved);assert.equal(JSON.stringify(a),before);
});
for(const part of ['facts','phase','partnerClassification','targetRarity','partnerRarity','assessment'])check('saved complete '+part+' cannot be substituted',()=>{const r=structuredClone(a.saved);r.bundles[0].core.sourceBundle.observations[0][part].extra='changed';assert.equal(h.pm3SavedRun(r),false);});

check('minimal NE rejects fabricated completion fields in both core copies',()=>{for(const key of ['pm3Points','evidence','evaluationId','appliedStrength','executionTrace','deterministicTrace']){const r=structuredClone(fixtures.UNKNOWN_ALPHA.saved);r.candidates[0].attempts[0].pm3[key]=key==='pm3Points'?0:'COMPLETED';r.bundles[0].core=structuredClone(r.candidates[0].attempts[0].pm3);assert.equal(h.pm3SavedRun(r),false,key);}});
// R1 mutations deliberately synchronize every core/evaluation copy. No backend fixture is edited.
const savedLinkageCases = [];
const linkedMutation = (name, scene, mutate) => {
    const run = structuredClone(fixtures[scene].saved), core = run.candidates[0].attempts[0].pm3;
    mutate(core);
    if (core.evaluation) core.criterionEvaluations = [structuredClone(core.evaluation)];
    else if (core.evaluationResult) core.criterionEvaluations = [{ criterionId: core.criterionId, evaluationResult: structuredClone(core.evaluationResult), criterionApplicability: structuredClone(core.criterionApplicability) }];
    run.bundles[0].core = structuredClone(core); run.candidates[0].attempts[0].criterionEvaluations = structuredClone(core.criterionEvaluations);
    savedLinkageCases.push({ name, run });
    check('R1 synchronized saved rejection: ' + name, () => { const before=JSON.stringify(run); assert.equal(h.pm3SavedRun(run),false); assert.equal(JSON.stringify(run),before); });
};
linkedMutation('foreign-completed-payload','NORMAL_ALPHA',core=>{for(const k of Object.keys(core))if(!['criterionId','resultMode','sourceOfTruth','classificationEnabled','adoptionSupported','sourceBundle'].includes(k))delete core[k];for(const [k,v] of Object.entries(fixtures.NORMAL_BETA.saved.candidates[0].attempts[0].pm3))if(!['criterionId','resultMode','sourceOfTruth','classificationEnabled','adoptionSupported','sourceBundle'].includes(k))core[k]=structuredClone(v);});
for(const key of ['evidence','evaluation','policySelection']) linkedMutation('missing-'+key,'NORMAL_ALPHA',core=>{delete core[key];});
for(const [name,mutate] of [
    ['evaluation-context', c=>{c.evaluation.contextId+='x';}], ['evidence-context',c=>{c.evidence.contextId+='x';}], ['selection-context',c=>{c.policySelection.contextId+='x';}],
    ['case',c=>{c.caseId+='x';}], ['occurrence',c=>{c.occurrenceId+='x';}], ['observation-count',c=>{c.observationCount=2;}],
    ['evidence-reference',c=>{c.evaluation.evidenceIds=['evidence-sha256-'+'0'.repeat(64)];}],
    ['registry-reference',c=>{c.evaluation.ruleBinding.ruleRegistrySha256='0'.repeat(64);}],
    ['selection-reference',c=>{c.evaluation.ruleBinding.cspecSelectionId+='x';}],
    ['policy-pin',c=>{c.policySelection.policySha256='0'.repeat(64);}], ['catalog-entry',c=>{c.policySelection.catalogEntryId+='x';}],
    ['scope',c=>{c.policySelection.scope.mondoId='MONDO:0000001';}],
    ['source-observation-pin',c=>{c.evidence.source.resourceSha256='0'.repeat(64);}],
    ['source-record',c=>{c.evidence.source.sourceRecords[0].locator.artifactSha256='0'.repeat(64);}],
    ['trace-evidence',c=>{c.evaluation.deterministicTrace.inputEvidenceIds=[];}],
    ['trace-pair',c=>{c.evaluation.deterministicTrace.parameters.find(x=>x.name==='pairId').value+='x';}],
    ['trace-assessment',c=>{c.evaluation.deterministicTrace.parameters.find(x=>x.name==='assessmentSha256').value='0'.repeat(64);}],
    ['source-of-truth',c=>{c.sourceOfTruth='OTHER';}], ['missing-evaluation-id',c=>{delete c.evaluation.evaluationId;}],
]) linkedMutation(name,'NORMAL_ALPHA',mutate);
for(const [name,mutate] of [
    ['ne-with-not-applicable',c=>{c.criterionApplicability.status='NOT_APPLICABLE';}], ['ne-with-applicable',c=>{c.criterionApplicability.status='APPLICABLE';}],
    ['ne-evaluation-result-injected-points',c=>{c.evaluationResult.pm3Points=0;}], ['ne-applicability-injected-strength',c=>{c.criterionApplicability.appliedStrength='NONE';}],
    ['ne-nested-trace',c=>{c.evaluationResult.trace={executionStatus:'COMPLETED'};}], ['ne-nested-id',c=>{c.criterionApplicability.evaluationId='invented';}],
    ['ne-empty-reason',c=>{c.evaluationResult.reasonCode='';c.criterionApplicability.reasonCode='';}],
    ['ne-missing-reason',c=>{delete c.evaluationResult.reasonCode;}], ['ne-reason-conflict',c=>{c.criterionApplicability.reasonCode='PM3_PART_PIN_MISMATCH';}],
]) linkedMutation(name,'UNKNOWN_ALPHA',mutate);
check('R1 other core refusal reason uses same minimal contract without scientific rerun',()=>{
    const run=structuredClone(fixtures.UNKNOWN_ALPHA.saved),core=run.candidates[0].attempts[0].pm3;
    core.evaluationResult.reasonCode='PM3_PART_PIN_MISMATCH';core.criterionApplicability.reasonCode='PM3_PART_PIN_MISMATCH';
    core.criterionEvaluations[0].evaluationResult=structuredClone(core.evaluationResult);core.criterionEvaluations[0].criterionApplicability=structuredClone(core.criterionApplicability);
    run.candidates[0].attempts[0].criterionEvaluations=structuredClone(core.criterionEvaluations);run.bundles[0].core=structuredClone(core);
    assert.ok(h.pm3SavedRun(run)); // FRONTEND_CONTRACT_MUTATION_ONLY, not a newly evaluated backend result.
});

write('contract-validation.json', { status: 'PASS', count: checks.length, checks, requestComparison: 'FULL_JSON_NO_EXCLUSIONS_NO_NORMALIZATION', scientificExecution: 0 });

await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};import Matrix from ${JSON.stringify(path.join(path.dirname(component), 'AcmgEvidenceMatrix.jsx'))};export const savedElement=run=>SavedResult({run,criteria:[]});export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);export const renderCore=pm3=>renderToStaticMarkup(<><p>CORE_RESULT_ONLY</p><Matrix candidate={{candidateId:'CORE_RESULT_ONLY',attempts:[{pm3}]}} enabledCriteria={['ACMG_AMP_2015:PM3']}/></>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
    plugins: [{ name: 'export-test-component', setup(api) { api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) })); } }] });
const renderer = require(path.join(output, 'render.cjs'));

// Invoke the actual SavedResult export handler under Node stubs: serialization only, no UI/download.
const rejectionRenders=[];
for(const {name,run} of savedLinkageCases){
    const html=renderer.render(run,[]); assert.ok(html.includes('保存的 PM3 身份不完整或相互冲突'));assert.ok(!html.includes('PM3 单家系双变异证据已保存'));assert.ok(!html.includes('实际强度：'));assert.ok(html.includes('下载本次原始结果 JSON'));
    const tree=renderer.savedElement(run);const find=v=>{if(!v)return null;if(Array.isArray(v))return v.map(find).find(Boolean);if(v.props?.children==='下载本次原始结果 JSON')return v;return find(v.props?.children);};const button=find(tree);assert.equal(typeof button?.props.onClick,'function');
    const previous={document:globalThis.document,create:URL.createObjectURL,revoke:URL.revokeObjectURL,timer:globalThis.setTimeout};let blob,filename,revoked=false;
    try{globalThis.document={createElement:tag=>{assert.equal(tag,'a');return{click(){filename=this.download;}};}};URL.createObjectURL=b=>{blob=b;return 'blob:SERIALIZATION_TEST_ONLY';};URL.revokeObjectURL=()=>{revoked=true;};globalThis.setTimeout=fn=>{fn();};button.props.onClick();assert.deepEqual(JSON.parse(await blob.text()),run);assert.equal(filename,'acmg-'+run.runId+'.json');assert.ok(revoked);}
    finally{if(previous.document===undefined)delete globalThis.document;else globalThis.document=previous.document;URL.createObjectURL=previous.create;URL.revokeObjectURL=previous.revoke;globalThis.setTimeout=previous.timer;}
    write('r1-'+name+'.json',run);fs.writeFileSync(path.join(output,'r1-'+name+'.html'),html);rejectionRenders.push({name,warning:true,trustedConclusion:false,fullJsonSerialization:true});
}
write('saved-linkage-validation.json',{status:'PASS',attribution:'FRONTEND_MUTATIONS_AND_ACTUAL_SAVEDRESULT_SSR_HANDLER_SERIALIZATION_NOT_DOWNLOAD',cases:rejectionRenders});

const renders = [];
for (const [name, core, html] of [...Object.entries(fixtures).map(([name, f]) => [name, f.saved.candidates[0].attempts[0].pm3, renderer.render(f.saved, ['ACMG_AMP_2015:BA1'])]),
    ...coreOnly.map(f => [f.group, f.result, renderer.renderCore(f.result)])]) {
    const card = h.pm3EvidenceEntry(core), e = card.evaluation;
    assert.ok(html.includes('PM3')); assert.ok(html.includes(e.evaluationResult.reasonCode)); assert.ok(html.includes(h.reasonHelp(e.evaluationResult.reasonCode)));
    assert.ok(html.includes(e.appliedStrength || '实际强度：未记录')); assert.equal(card.details.find(([k]) => k === '本次积分')[1], core.pm3Points ?? '未给出');
    assert.ok(!html.includes('需要 CSpec / 人工复核处理')); fs.writeFileSync(path.join(output, name + '.html'), html);
    renders.push({ name, status: e.evaluationResult.status, points: core.pm3Points ?? null, strength: e.appliedStrength ?? null });
}
const malformed = structuredClone(a.saved); malformed.bundles[0].coreRawSha256 = 'bad';
assert.ok(!renderer.render(malformed, ['ACMG_AMP_2015:PM3']).includes('实际强度：'));
write('render-validation.json', { status: 'PASS', actualReactServerComponents: true, renders, malformedDoesNotShowConclusion: true });
await buildPm3ComponentChecks({ project, output, fixtures, build });
const entry = `import React,{useState} from 'react';import {createRoot} from 'react-dom/client';import Panel from ${JSON.stringify(component)};
function Replay(){const [scene,setScene]=useState('NORMAL_BETA'),[status,setStatus]=useState('TEST_ONLY archive transport');const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([k, f]) => [k, f.saved.analysisId])))};
async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json()));}
return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PM3 · 真实普通页面</h1><p>单家系归档回放；无科学程序、后端或数据库执行。实际下载待独立接纳后由用户手动完成。</p>
<label>归档场景 <select value={scene} onChange={e=>setScene(e.target.value)}>{Object.keys(ids).map(k=><option key={k}>{k}</option>)}</select></label>{' '}
<button onClick={()=>control('control',{delayNext:true})}>延迟下一次提交响应</button>{' '}<button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
<button onClick={()=>control('control',{history:true})}>UNKNOWN 当前 options503</button>{' '}<button onClick={()=>control('control',{history:false})}>恢复 UNKNOWN options</button>{' '}
<button onClick={()=>control('state')}>读取请求计数</button><pre>{status}</pre></aside><Panel analysisId={ids[scene]}/></>};createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
write('bundle-inputs.json', bundle.metafile.inputs);
for (const suffix of ['OrdinaryAcmgEvaluationPanel.jsx', 'AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js']) assert.ok(Object.keys(bundle.metafile.inputs).some(x => x.replaceAll('\\', '/').endsWith(suffix)));
write('assets.json', ['entry.jsx', 'app.js', 'component-checks.js'].map(f => ({ file: f, bytes: fs.statSync(path.join(output, f)).size, rawSha256: sha(fs.readFileSync(path.join(output, f))) })));

const state = { history: false, readOnly: false, delayNext: false }, completed = new Set(), journal = [], pending = [];
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
        if (req.method === 'GET' && ['/', '/component-tests'].includes(url.pathname)) return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PM3 TEST_ONLY</title><div id="root"></div><script src="/' + (url.pathname === '/' ? 'app.js' : 'component-checks.js') + '"></script></html>', 'text/html');
        if (req.method === 'GET' && ['/app.js', '/component-checks.js'].includes(url.pathname)) return reply(200, fs.readFileSync(path.join(output, url.pathname.slice(1))), 'text/javascript');
        let body;
        if (req.method === 'POST') { assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0; for await (const chunk of req) { size += chunk.length; assert.ok(size < 2e6); chunks.push(chunk); } body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body; }
        if (url.pathname.startsWith('/__test/')) event.kind = 'CONTROL';
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') { for (const k of ['readOnly', 'history', 'delayNext']) if (typeof body[k] === 'boolean') state[k] = body[k]; return reply(200, snapshot()); }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach(p => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/component-results' && req.method === 'POST') { event.kind = 'COMPONENT_TEST_RECEIPT'; write('component-validation.json', body); return reply(200, { saved: true }); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname.startsWith('/__test/')) return reply(405, { message: 'TEST_ONLY_METHOD_OR_ROUTE' });
        const m = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const pair = m && Object.entries(fixtures).find(([, f]) => f.saved.analysisId === m[1]);
        if (!pair) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        const [name, f] = pair;
        if (req.method === 'GET' && m[2] === 'input-options') { event.kind = 'GET_OPTIONS';
            if (state.history && name === 'UNKNOWN_ALPHA') return reply(503, { message: 'TEST_ONLY_CURRENT_OPTIONS_UNAVAILABLE' });
            if (url.searchParams.get('topN') !== '1') return reply(400, { message: 'TEST_ONLY_TOP1_REQUIRED' }); return reply(200, f.options); }
        if (req.method === 'GET' && m[2] === 'runs') { event.kind = m[3] ? 'GET_RUN' : 'GET_RUNS';
            const rows = state.readOnly || completed.has(name) || !['NORMAL_BETA', 'UNKNOWN_ALPHA', 'FAILED_RETRY_ALPHA'].includes(name) ? [f.saved, ...(f.failed ? [f.failed] : [])] : f.failed ? [f.failed] : [];
            if (!m[3]) return reply(200, rows.map(r => ({ runId: r.runId, status: r.status, createdAt: r.createdAt })));
            const r = rows.find(r => r.runId === m[3]); return r ? reply(200, r) : reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' }); }
        if (req.method === 'POST' && m[2] === 'runs' && !m[3]) { event.kind = 'POST_RUN'; const id = journal.filter(e => e.kind === 'POST_RUN').length + 1;
            write('request-' + id + '-incoming.json', body); write('request-' + id + '-expected.json', f.request);
            assert.ok(!state.readOnly && ['NORMAL_BETA', 'UNKNOWN_ALPHA', 'FAILED_RETRY_ALPHA'].includes(name) && !(state.history && name === 'UNKNOWN_ALPHA'), 'NO_CURRENT_PERMISSION');
            const isCwbRetry = selfCheck && name === 'UNKNOWN_ALPHA' && body.retryOf === f.saved.runId;
            const expected = isCwbRetry ? original(name, 'retryRequest') : f.request; assert.deepEqual(body, expected); write('request-' + id + '-expected.json', expected); write('request-' + id + '-comparison.json', { status: 'PASS', fullJson: true, excludedFields: [], normalizedFields: [], expected: 'ARCHIVED_BACKEND_REQUEST', scene: name });
            completed.add(name); return reply(200, isCwbRetry ? original(name, 'retrySaved') : f.saved, undefined, state.delayNext); }
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
            if (!['NORMAL_BETA', 'UNKNOWN_ALPHA', 'FAILED_RETRY_ALPHA'].includes(name)) continue;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            if (f.failed) assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.failed.runId)).json(), f.failed);
            const response = await post(route + '/runs', h.buildPm3Declaration(f.options, f.options.analysisId, true, f.failed || undefined));
            assert.equal(response.status, 200); assert.deepEqual(await response.json(), f.saved);
            assert.equal((await post(route + '/runs', { ...f.request, extra: true })).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        const u=fixtures.UNKNOWN_ALPHA; const retryResponse=await post('/variant-analyses/'+u.saved.analysisId+'/acmg/runs',h.buildPm3Declaration(u.options,u.saved.analysisId,true,u.saved)); assert.equal(retryResponse.status,200); assert.deepEqual(await retryResponse.json(),original('UNKNOWN_ALPHA','retrySaved'));
        assert.equal((await post('/__test/control', { history: true })).status, 200);
        const unknownRoute = '/variant-analyses/' + fixtures.UNKNOWN_ALPHA.saved.analysisId + '/acmg';
        assert.equal((await fetch(origin + unknownRoute + '/input-options?topN=1')).status, 503);
        assert.equal((await post(unknownRoute + '/runs', fixtures.UNKNOWN_ALPHA.request)).status, 400);
        assert.equal((await post('/__test/control', { readOnly: true })).status, 200);
        for (const f of Object.values(fixtures)) { const route = '/variant-analyses/' + f.saved.analysisId + '/acmg'; assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved); assert.equal((await post(route + '/runs', f.request)).status, 400); }
        assert.equal((await post('/__test/release', {})).status, 200);
        write('http-validation.json', { status: 'PASS', ...snapshot(), allReadOnlyHistoriesMatchFullJson: true, explicitControls: true, realDownloads: 'NOT_RUN_USER_MANUAL_ONLY' });
        assert.equal((await post('/__test/stop', {})).status, 200);
    } catch (e) { write('http-failure.json', { message: e.message, stack: e.stack }); finish(); throw e; }
}
