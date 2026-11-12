import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// Dedicated TEST_ONLY BP1 replay. No backend, database, scientific execution or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every((arg) => arg === '--self-check'));
const task = path.join(checkout, 'local/temp/BP1-ui-business-20260928');
const handoff = path.join(checkout, 'local/development/handoffs/BP1-ui-business-20260928');
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-check-' : 'browser-'));
// Capture the actual inputs even if a contract fails before building the browser asset.
for (const p of ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',
    'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
    'scripts/bp1-business-browser-smoke.mjs']) fs.copyFileSync(path.join(project,p),path.join(output,path.basename(p)));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(fs.readFileSync(path.join(handoff, 'source-index.json')));
assert.equal(manifest.files.length, 96);
for (const record of manifest.files) {
    const original = fs.readFileSync(path.toNamespacedPath(record.source));
    const copy = fs.readFileSync(record.copy);
    assert.equal(original.length, record.bytes); assert.equal(sha(original), record.sha256);
    assert.equal(sha(copy), record.sha256); assert.equal(record.copySha256, record.sha256);
}
write('original-sha256.json', manifest);
const recordFor = (group, filename) => manifest.files.find(f => f.group === group && f.recommendedRelativeDestination.endsWith('/'+filename));
const readOriginal = (group, filename) => JSON.parse(fs.readFileSync(recordFor(group, filename).copy));
const fixtures = Object.fromEntries(manifest.businessGroups.map(scenario => {
 const f=Object.fromEntries(['input-options','request','submit','saved-store','get-after-store-service-rebuild','reuse'].map(key=>[key,readOriginal(scenario.group,key+'.json')]));
 f.saved=f['get-after-store-service-rebuild']; f.savedSource='ARCHIVED_GET_AFTER_STORE_SERVICE_REBUILD'; f.readOnly=false;
 for(const n of ['submit','saved-store','reuse']) assert.deepEqual(f[n],f.saved);
 const binding=f['input-options'].executionProfile.reviewedRuleBinding;
 assert.equal(sha(fs.readFileSync(recordFor(scenario.group,'configured-bundle-'+scenario.activeBundleOrdinal+'-raw.json').copy)),binding.bundleRawSha256);
 return [scenario.group,f];
}));
const normals=fixtures;
const core=Object.fromEntries(manifest.coreGroups.map(e=>[e.group,readOriginal(e.group,'result.json')]));
for (const f of Object.values(fixtures)) assert.equal(f.saved.dataClass, 'SYNTHETIC');
write('source-before.json', ['src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
    'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'scripts/acmg-input-contract-smoke.mjs', 'scripts/bp1-business-browser-smoke.mjs'].map(p => ({ path: p, sha256: sha(fs.readFileSync(path.join(project, p))) })));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helperPath = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const helpers = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(helperPath).toString('base64')}`));
const contractChecks = [];
const check = (name, action) => { action(); contractChecks.push(name); };
// Independent mirror of RuntimeEvidenceV2Adapter.canonicalNode for these bounded
// identity JSON fixtures: sort raw names, then NFC keys/strings; preserve arrays.
// This is a frontend contract oracle, not a Java execution or backend original.
const canonical = v => typeof v === 'string' ? v.normalize('NFC')
    : Array.isArray(v) ? v.map(canonical) : v && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map(k => [k.normalize('NFC'),canonical(v[k])])) : v;
const digest = v => sha(JSON.stringify(canonical(v)));
const resignRecommendation = r => { r.recommendationId = 'recommendation-'+digest(Object.fromEntries(Object.entries(r).filter(([k])=>!['recommendationId','generatedAt'].includes(k)))); };
// Private function is exported only from an in-memory test module, not a production API.
const hashTest = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(helperPath,'utf8')+'\nexport {bp3IdentityHash};').toString('base64')}`));
const unicodeExpectedId = 'recommendation-ee38dad03248eed0a6a647cf1afcb32e8b9176f723541f0550d81debaee90455';
for (const [form,label] of [['NFC','Caf\u00e9 TEST_ONLY'],['NFD','Cafe\u0301 TEST_ONLY']]) check(form+' valid canonical recommendation passes scope and both declarations without mutation',()=>{
    const o=structuredClone(normals.NORMAL['input-options']),c=o.candidates[0],r=c.recommendation;
    r.relationships[0].diseaseLabel=label;resignRecommendation(r);
    assert.equal(r.recommendationId,unicodeExpectedId); // Fixed independently reproduced backend projection.
    const before=JSON.stringify(o),projection=Object.fromEntries(Object.entries(r).filter(([k])=>!['recommendationId','generatedAt'].includes(k)));
    const issue=helpers.bp1ScopeIssue(o),criteria=helpers.currentBusinessCriteria(o);
    const inputs=helpers.prepareSimplifiedInputs(o),rel=r.relationships[0];
    const h={hypothesisId:r.hypothesisId,hgncId:rel.hgncId,mondoId:rel.mondoId,moi:rel.moi,
        affectedStatus:'UNKNOWN',transcriptIndex:'0',selectionPolicy:r.annotations[0].ruleContextSelectionPolicy,
        selectionReason:'TEST_ONLY explicit background',recordLocator:'test-only/advanced'};
    const result={form,label,fixtureKind:'FRONTEND_CONTRACT_MUTATION_ONLY_NOT_BACKEND_ORIGINAL_OR_SCIENTIFIC_MATERIAL',
        expectedId:unicodeExpectedId,canonicalBytes:JSON.stringify(canonical(projection)),actualId:'recommendation-'+hashTest.bp3IdentityHash(projection),issue,criteria};
    for(const [mode,buildRequest] of [
        ['simplified',()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs,undefined,'SYNTHETIC')],
        ['advanced',()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration),{[c.candidateId]:[h]})],
    ]) {try {result[mode]={request:buildRequest()};} catch(error) {result[mode]={error:error.message};}}
    result.inputUnchanged=JSON.stringify(o)===before;
    write('unicode-'+form+'-input-options.json',o);write('unicode-'+form+'-result.json',result);
    assert.equal(issue,null,form+' valid canonical scope must pass');
    assert.equal(result.actualId,unicodeExpectedId);assert.deepEqual(criteria,['ACMG_AMP_2015:BP1']);
    assert.equal(result.simplified.request.hypotheses.length,2);assert.equal(result.advanced.request.hypotheses.length,1);
    for(const request of [result.simplified.request,result.advanced.request]) {
        assert.equal(request.declarationVersion,'ordinary-bp1-missense-v1');
        for(const key of ['hypothesisId','hgncId','mondoId','moi']) assert.equal(request.hypotheses[0].hypothesis[key],normals.NORMAL.request.hypotheses[0].hypothesis[key]);
        for(const key of ['accession','version','selectionPolicy','annotationSource','annotationSourceVersion'])
            assert.equal(request.hypotheses[0].transcript[key],normals.NORMAL.request.hypotheses[0].transcript[key]);
    }
    assert.equal(result.inputUnchanged,true);assert.equal(r.relationships[0].diseaseLabel,label);
});
check('recursive NFC values and keys preserve raw key sort and array order',()=>{
    const vectors=[
        {input:{z:['Cafe\u0301',{ 'e\u0301':'A\u030a' },null,true,7],a:'Cafe\u0301'},bytes:'{"a":"Café","z":["Café",{"é":"Å"},null,true,7]}'},
        {input:{'é':'last','z':'middle','e\u0301':'first'},bytes:'{"é":"last","z":"middle"}'},
        {input:{'é':1,'z':2,'e\u0301x':3},bytes:'{"éx":3,"z":2,"é":1}'},
        {input:['Cafe\u0301','Caf\u00e9'],bytes:'["Café","Café"]'},
    ];
    const results=vectors.map(({input,bytes})=>{
        const before=JSON.stringify(input),expected=sha(Buffer.from(bytes,'utf8')),actual=hashTest.bp3IdentityHash(input);
        assert.equal(JSON.stringify(canonical(input)),bytes);assert.equal(actual,expected);assert.equal(JSON.stringify(input),before);
        return {input,canonicalBytes:bytes,expected,actual,inputUnchanged:true};
    });
    assert.notEqual(hashTest.bp3IdentityHash(['Cafe\u0301','other']),hashTest.bp3IdentityHash(['other','Caf\u00e9']));
    write('unicode-vectors.json',{status:'PASS',oracle:'FIXED_CANONICAL_UTF8_BYTES_AND_NODE_CRYPTO',results});
});
check('NFC equivalence never admits changed content, digest, candidate, material token or raw pin',()=>{
    for(const [name,mutate] of [
        ['real text change',r=>{r.relationships[0].diseaseLabel+=' changed';}],
        ['damaged digest',r=>{r.recommendationId='recommendation-'+'0'.repeat(64);}],
        ['cross candidate',r=>{r.candidateId=normals.NORMAL['input-options'].candidateIds[1];}],
        ['cross material token',r=>{r.annotations[0].ruleContextSelectionPolicy=fixtures.MATERIAL['input-options'].candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy;}],
        ['published raw pin',r=>{r.publishedCandidateRawSha256='1'.repeat(64);}],
    ]) {
        const o=structuredClone(normals.NORMAL['input-options']),r=o.candidates[0].recommendation;
        r.relationships[0].diseaseLabel='Cafe\u0301 TEST_ONLY';r.recommendationId=unicodeExpectedId;mutate(r);
        assert.ok(helpers.bp1ScopeIssue(o),name);assert.deepEqual(helpers.currentBusinessCriteria(o),[]);
        assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,helpers.prepareSimplifiedInputs(o)),/BP1/,name);
        assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration),{}),/BP1/,name);
    }
});
check('identity hashing matches independent Node SHA including Unicode and block boundaries',()=>{
    for(const value of ['', 'abc', '审核😀', ...[55,56,63,64,65,127,128,1024].map(n=>'x'.repeat(n)), {z:null,a:[true,7,'来源']}])
        assert.equal(hashTest.bp3IdentityHash(value),digest(value));
});
const mutations = [
    ['profileId', o => { o.executionProfile.profileId = 'legacy'; }],
    ['profileVersion', o => { o.executionProfile.profileVersion = '9'; }],
    ['declaration', o => { o.requiredDeclaration = 'ordinary-ui-v3'; }],
    ['profile declaration', o => { o.executionProfile.requiredDeclaration = 'ordinary-ui-v3'; }],
    ['extra criterion', o => { o.enabledCriteria.push('ACMG_AMP_2015:PS3'); }],
    ['profile criterion', o => { o.executionProfile.enabledCriteria = ['ACMG_AMP_2015:PM1']; }],
    ...['formalEnabled','automaticTrigger','classificationEnabled','adoptionSupported','manifestSha256'].map(k =>
        [k, o => { o.executionProfile[k] = true; }]),
    ...['schemaVersion','status','bundleId','bundleVersion','bundleSha256','bundleRawSha256','catalogSha256'].map(k =>
        [k, o => { o.executionProfile.reviewedRuleBinding[k] = ''; }]),
    ['algorithm version', o => { o.executionProfile.reviewedRuleBinding.algorithms['reviewed-missense-spectrum-bp1'] = '1.0.3'; }],
    ['extra algorithm', o => { o.executionProfile.reviewedRuleBinding.algorithms.MissenseComparisonRuntime = '1.0.0'; }],
    ...['automaticTrigger','classificationEnabled','adoptionSupported'].map(k =>
        ['bundle '+k, o => { o.executionProfile.reviewedRuleBinding[k] = true; }]),
    ...['schemaVersion','version','mode','reviewedRuleBinding'].map(k =>
        ['preparation '+k, o => { o.executionProfile.ruleContextPreparationBinding[k] = ''; }]),
    ...['automaticTrigger','classificationEnabled','criterionPolicySelected','predictionCompatibilityClaimed'].map(k =>
        ['preparation '+k, o => { o.executionProfile.ruleContextPreparationBinding[k] = true; }]),
    ...['candidateId','analysisId','requiredDeclaration','policyHash','executionSourceSha256','recommendationId','publishedCandidateRawSha256'].map(k =>
        ['recommendation '+k, o => { o.candidates[0].recommendation[k] = ''; }]),
    ['candidate preparation', o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '5.0.0'; }],
    ...['annotationId','annotationSourceSha256','versionedTranscriptId','annotationSource','annotationSourceVersion','ruleContextSelectionPolicy'].map(k =>
        ['annotation '+k, o => { o.candidates[0].recommendation.annotations[0][k] = ''; }]),
    ['legacy identity bypass', o => { Object.assign(o.candidates[0].recommendation.annotations[0], {ruleContextReady:false,contextSelectable:false,identityCompatible:true,predictionPath:'HNF1A_REVEL'}); }],
    ['cross candidate annotation', o => { o.candidates[0].recommendation.annotations = o.candidates[1].recommendation.annotations; }],
    ['mixed raw and canonical hash', o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding.bundleRawSha256 = o.executionProfile.reviewedRuleBinding.bundleSha256; }],
];
for (const [name, mutate] of mutations) check('reject '+name, () => {
    const o = structuredClone(normals.NORMAL['input-options']); mutate(o);
    assert.ok(helpers.bp1ScopeClaim(o)); assert.ok(helpers.bp1ScopeIssue(o));
    assert.deepEqual(helpers.currentBusinessCriteria(o), []);
    assert.throws(() => helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,helpers.prepareSimplifiedInputs(o)), /BP1/);
    assert.throws(() => helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration),{}), /BP1/);
});
for (const [name,f] of Object.entries(normals)) check(name+' exact server tokens and confirmation identity', () => {
    const o=f['input-options'], before=JSON.stringify(o), inputs=helpers.prepareSimplifiedInputs(o);
    assert.deepEqual(helpers.currentBusinessCriteria(o),['ACMG_AMP_2015:BP1']);
    const request=helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs,undefined,'SYNTHETIC');
    assert.equal(request.declarationVersion,'ordinary-bp1-missense-v1');
    request.hypotheses.forEach((h,i)=>assert.equal(h.transcript.selectionPolicy,f.request.hypotheses[i].transcript.selectionPolicy));
    const stale=structuredClone(inputs);stale[o.candidateIds[0]].recommendationId='stale';
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,stale),/确认/);
    stale[o.candidateIds[0]]=structuredClone(inputs[o.candidateIds[1]]);
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,stale),/确认/);
    for(const id of o.candidateIds) inputs[id].annotationId='';
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs),/尚无/);
    assert.equal(JSON.stringify(o),before);
});
check('old BP1 identities are not v9',()=>{
    for(const value of [{enabledCriteria:['ACMG_AMP_2015:BP1']},{executionProfile:{reviewedRuleBinding:{schemaVersion:'reviewed-rule-bundle-binding-v1'}}}]) {
        assert.equal(helpers.bp1ScopeClaim(value),false);assert.equal(helpers.bp1SavedRun(value),false);
    }
});
check('partial or malformed BP1 claims never fall back to legacy ordinary mode',()=>{
    for(const value of [
        {executionProfile:{profileId:'acmg-bp1-unsupported'}},
        {requiredDeclaration:'ordinary-bp1-unsupported'},
        {candidates:[{recommendation:{requiredDeclaration:'ordinary-bp1-missense-v1'}}]},
        {candidates:[{recommendation:{annotations:[{ruleContextSelectionPolicy:'REVIEWED_RULE_CONTEXT@9.0.0:broken'}]}}]},
    ]) {assert.equal(helpers.bp1ScopeClaim(value),true);assert.ok(helpers.bp1ScopeIssue(value));assert.deepEqual(helpers.currentBusinessCriteria(value),[]);}
});
check('v9 advanced declaration cannot bypass scope',()=>{
    const o=normals.NORMAL['input-options'];
    assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC','ordinary-ui-v3'),{}),/BP1/);
    const c=o.candidates[0], r=c.recommendation, relationship=r.relationships[0];
    const hypothesis={hypothesisId:r.hypothesisId,hgncId:relationship.hgncId,mondoId:relationship.mondoId,moi:relationship.moi,
        affectedStatus:'UNKNOWN',transcriptIndex:'0',selectionPolicy:r.annotations[0].ruleContextSelectionPolicy,
        selectionReason:'TEST_ONLY explicit background',recordLocator:'test-only/advanced'};
    const form=helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration);
    assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,form,{}),/BP1 至少/);
    assert.equal(helpers.buildCaseDeclaration(o,o.analysisId,form,{[c.candidateId]:[hypothesis]}).hypotheses.length,1);
    for(const [key,value] of [['hypothesisId','other'],['hgncId','HGNC:999999'],['mondoId','MONDO:0999999'],['moi','AR'],
        ['selectionPolicy',fixtures.MATERIAL['input-options'].candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy]])
        assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,form,{[c.candidateId]:[{...hypothesis,[key]:value}]}),/BP1/,key);
});
check('success and explicit negatives have distinct BP1 semantics',()=>{
 assert.doesNotMatch(helpers.reasonHelp('BP1_REVIEWED_TRUNCATING_MECHANISM_AND_VARIANT_APPLICABILITY'),/待审核|需要.*复核/);
 for(const name of ['negative-mechanism','negative-exception']) assert.match(helpers.reasonHelp(core[name].evaluation.evaluationResult.reasonCode),/NOT_MET/);
});
check('published full candidate identity is consumed and invalidates stale input',()=>{
 const o=structuredClone(normals.NORMAL['input-options']),inputs=helpers.prepareSimplifiedInputs(o);
 inputs[o.candidateIds[0]].publishedCandidateRawSha256='0'.repeat(64);
 assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs),/BP1/);
 const changed=structuredClone(o);changed.candidates[0].recommendation.publishedCandidateRawSha256='1'.repeat(64);
 assert.ok(helpers.bp1ScopeIssue(changed));
});
check('coherent recommendation cannot borrow another material token or annotation source',()=>{
 for(const mutate of [
    r=>{r.annotations[0].ruleContextSelectionPolicy=fixtures.MATERIAL['input-options'].candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy;},
    r=>{r.annotations[0].annotationSource='OTHER_VEP';},
    r=>{r.annotations[0].annotationSourceVersion='115.0';},
    r=>{r.annotations[0].versionedTranscriptId='ENST000001.2';},
 ]) {
    const o=structuredClone(normals.NORMAL['input-options']),r=o.candidates[0].recommendation;
    mutate(r);r.resourceCompatibleTranscript=structuredClone(r.annotations[0]);resignRecommendation(r);
    assert.ok(helpers.bp1ScopeIssue(o));
 }
});
check('coherent profile still rejects stale preparation token and swapped raw/canonical identity',()=>{
 const o=structuredClone(normals.NORMAL['input-options']),p=o.executionProfile;
 const b=p.reviewedRuleBinding;[b.bundleSha256,b.bundleRawSha256]=[b.bundleRawSha256,b.bundleSha256];
 p.ruleContextPreparationBinding.reviewedRuleBinding=structuredClone(b);
 p.manifestSha256=digest(Object.fromEntries(Object.entries(p).filter(([k])=>k!=='manifestSha256')));
 for(const c of o.candidates) {const r=c.recommendation;r.ruleContextPreparationBinding=structuredClone(p.ruleContextPreparationBinding);
 r.policyHash=digest(Object.fromEntries(['policyId','policyVersion','patientSpecific','scoreIndependent','compatibility','requiredDeclaration','ruleContextPreparationBinding'].map(k=>[k,r[k]])));resignRecommendation(r);}
 assert.ok(helpers.bp1ScopeIssue(o));
});
check('advanced source/version cannot borrow another annotation with the same transcript ID',()=>{
 const o=structuredClone(normals.NORMAL['input-options']),c=o.candidates[0],r=c.recommendation,rel=r.relationships[0];
 c.transcriptAnnotations.push({...c.transcriptAnnotations[0],source:'OTHER_VEP'});
 const h={hypothesisId:r.hypothesisId,hgncId:rel.hgncId,mondoId:rel.mondoId,moi:rel.moi,affectedStatus:'UNKNOWN',
 transcriptIndex:'1',selectionPolicy:r.annotations[0].ruleContextSelectionPolicy,selectionReason:'explicit',recordLocator:'test-only/advanced'};
    assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration),{[c.candidateId]:[h]}),/BP1/);
});
check('v1 to v8 tokens and declarations cannot authorize BP1',()=>{
    for(let version=1;version<=8;version++) {
        const o=structuredClone(normals.NORMAL['input-options']),r=o.candidates[0].recommendation;
        r.annotations[0].ruleContextSelectionPolicy=`REVIEWED_RULE_CONTEXT@${version}.0.0:${'a'.repeat(64)}`;
        r.resourceCompatibleTranscript=structuredClone(r.annotations[0]);resignRecommendation(r);
        assert.ok(helpers.bp1ScopeIssue(o));assert.deepEqual(helpers.currentBusinessCriteria(o),[]);
    }
    for(const declaration of ['ordinary-pm1-region-v1','ordinary-pvs1-nmd-v1','ordinary-bp3-inframe-v1']) {
        const o=normals.NORMAL['input-options'];
        assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',declaration),{}),/BP1/);
    }
});
check('draft identity changes for task TopN data class relationship transcript recommendation and raw pin',()=>{
    const o=normals.NORMAL['input-options'],inputs=helpers.prepareSimplifiedInputs(o);
    const baseline=helpers.declarationDraftIdentity(o.analysisId,2,'SYNTHETIC',inputs);
    assert.notEqual(helpers.declarationDraftIdentity('other',2,'SYNTHETIC',inputs),baseline);
    assert.notEqual(helpers.declarationDraftIdentity(o.analysisId,1,'SYNTHETIC',inputs),baseline);
    assert.notEqual(helpers.declarationDraftIdentity(o.analysisId,2,'PRIVACY_BOUNDED_RUNTIME',inputs),baseline);
    for(const key of ['stableRelationshipKey','annotationId','recommendationId','publishedCandidateRawSha256']) {
        const changed=structuredClone(inputs);changed[o.candidateIds[0]][key]='changed';
        assert.notEqual(helpers.declarationDraftIdentity(o.analysisId,2,'SYNTHETIC',changed),baseline,key);
    }
    const old=structuredClone(inputs);old[o.candidateIds[0]].annotationId='stale';
    old[o.candidateIds[0]].publishedCandidateRawSha256='0'.repeat(64);
    assert.equal(helpers.prepareSimplifiedInputs(o,old)[o.candidateIds[0]].annotationId,o.candidates[0].recommendation.recommendedAnnotationId);
});
check('missing preparation and mixed saved v9 scope refuse without borrowing current permission',()=>{
    for(const mutate of [r=>{delete r.executionProfile;},r=>{r.declarationVersion='ordinary-bp3-inframe-v1';},
        r=>{r.executionProfile.ruleContextPreparationBinding.version='7.0.0';},r=>{r.enabledCriteria.push('ACMG_AMP_2015:BP3');}]) {
        const run=structuredClone(fixtures.NORMAL.saved);mutate(run);assert.ok(helpers.bp1ScopeClaim(run));assert.equal(helpers.bp1SavedRun(run),false);
    }
    for(const value of [
        {executionProfile:{ruleContextPreparationBinding:{schemaVersion:'reviewed-rule-context-preparation-bp1-v9'}}},
        {candidates:[{recommendation:{ruleContextPreparationBinding:{schemaVersion:'reviewed-rule-context-preparation-bp1-v9'}}}]},
    ]) {assert.ok(helpers.bp1ScopeClaim(value));assert.ok(helpers.bp1ScopeIssue(value));assert.deepEqual(helpers.currentBusinessCriteria(value),[]);}
});
check('policy version remains exact even with coherently recomputed identity',()=>{
 const o=structuredClone(fixtures.NORMAL['input-options']),r=o.candidates[0].recommendation;
 r.policyVersion='1.0.0-pp2-missense-development';
 r.policyHash=digest(Object.fromEntries(['policyId','policyVersion','patientSpecific','scoreIndependent','compatibility','requiredDeclaration','ruleContextPreparationBinding'].map(k=>[k,r[k]])));
 resignRecommendation(r);assert.ok(helpers.bp1ScopeIssue(o));assert.deepEqual(helpers.currentBusinessCriteria(o),[]);
});
check('full saved JSON export preserves every field independently of card projection',()=>{
 const componentSource=fs.readFileSync(component,'utf8');
 assert.ok(componentSource.includes('JSON.stringify(run, null, 2)'));
 assert.ok(componentSource.includes('acmg-${run.runId}.json'));
 for(const f of Object.values(fixtures)) assert.deepEqual(JSON.parse(JSON.stringify(f.saved,null,2)),f['get-after-store-service-rebuild']);
});
write('contract-validation.json',{status:'PASS',checks:contractChecks.length,names:contractChecks,mutatedFixtures:'FRONTEND_CONTRACT_ONLY_NOT_BACKEND_ORIGINALS'});
// Expected scientific/context identities come from Java's archived request, never this builder.
function validateRequest(body, f) {
    const archived = f.request;
    assert.deepEqual(Object.keys(body).sort(), ['dataClass', 'declarationVersion', 'hypotheses', 'patient', 'recommendations', 'sampleId', 'topN'].sort());
    for (const key of ['topN', 'declarationVersion', 'sampleId', 'dataClass']) assert.deepEqual(body[key], archived[key], key);
    assert.deepEqual(body.recommendations, archived.recommendations, 'server recommendation references');
    assert.equal(body.hypotheses.length, archived.hypotheses.length);
    assert.deepEqual(Object.keys(body.patient).sort(), ['familyContextId', 'patientContextId', 'role', 'sex', 'source']);
    const source = (s, locator) => assert.deepEqual(s, { source: 'EXPLICIT_USER_DECLARATION', version: '1.0.0', recordLocator: locator });
    // Allowed differences: UI privacy identifier/source, absent familyContextId -> null,
    // Exact BP1 confirmation prose. UNKNOWN is identical in both originals and UI.
    assert.equal(Object.prototype.hasOwnProperty.call(archived.patient, 'familyContextId'), false);
    assert.equal(body.patient.role, 'PROBAND'); assert.equal(body.patient.sex, 'UNKNOWN'); assert.equal(body.patient.familyContextId, null);
    assert.equal(body.patient.patientContextId, `patient-context-${f['input-options'].analysisId}`);
    source(body.patient.source, `analysis/${f['input-options'].analysisId}/acmg/ordinary-bp1-missense-v1`);
    body.hypotheses.forEach((actual, i) => {
        const expected = archived.hypotheses[i], rec = archived.recommendations[i];
        for (const key of ['candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi']) assert.equal(actual.hypothesis[key], expected.hypothesis[key], key);
        for (const key of ['accession', 'version', 'selectionPolicy', 'annotationSource', 'annotationSourceVersion'])
            assert.equal(actual.transcript[key], expected.transcript[key], key);
        assert.equal(expected.affectedStatus, 'UNKNOWN'); assert.equal(actual.affectedStatus, expected.affectedStatus);
        const locator = `recommendations/${rec.recommendationId}/selection`;
        source(actual.hypothesis.provenance, locator); source(actual.transcript.source, locator);
        assert.equal(actual.transcript.selectionReason,
            `User confirmed disease relationship, MOI and exact versioned transcript ${expected.transcript.accession}.${expected.transcript.version} for limited BP1 missense evaluation. Sequence, reviewed truncating mechanism, exact variant exceptions and splicing applicability remain verified by the server; no scientific adoption or final classification is authorized.`);
        assert.deepEqual(Object.keys(actual).sort(), ['affectedStatus', 'hypothesis', 'transcript']);
        assert.deepEqual(Object.keys(actual.hypothesis).sort(), Object.keys(expected.hypothesis).sort());
        assert.deepEqual(Object.keys(actual.transcript).sort(), Object.keys(expected.transcript).sort());
    });
    const expectedUi = structuredClone(archived);
    expectedUi.patient.patientContextId=body.patient.patientContextId;
    expectedUi.patient.source=body.patient.source;
    expectedUi.patient.familyContextId=null;
    expectedUi.hypotheses.forEach((h,i)=>{
        h.hypothesis.provenance=body.hypotheses[i].hypothesis.provenance;
        h.transcript.source=body.hypotheses[i].transcript.source;
        h.transcript.selectionReason=body.hypotheses[i].transcript.selectionReason;
    });
    assert.deepEqual(body,expectedUi,'full request equality after only individually asserted differences');
}

const entry = `import React,{useState} from 'react'; import {createRoot} from 'react-dom/client';
import Panel from ${JSON.stringify(component)};
function Replay(){const [scenario,setScenario]=useState('NORMAL'); const [history,setHistory]=useState(false);
 const [status,setStatus]=useState('TEST_ONLY: 本回放只有 Top-2，请先把组件 Top-20 改为 2，再读取正式候选。');
 const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([key, f]) => [key, f.saved.analysisId])))};
 async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json(),null,2));}
 return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY BP1 · 真实组件合成 HTTP</h1>
 <p>无 Java、数据库或科学执行，仅原 runId 的保存回执回放。仅支持 Top-2，不扩造候选。</p>
 <p>NORMAL 与 MATERIAL 各使用同组 options/request 和重建 GET；故障开关只将 MATERIAL 当前 options 设为503并禁止提交。</p>
 <label>归档场景 <select value={scenario} onChange={e=>setScenario(e.target.value)}><option>NORMAL</option><option>MATERIAL</option></select></label>{' '}
 <button onClick={()=>control('control',{delayNext:true})}>延迟下一次 POST（最多120秒）</button>{' '}
 <button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
 <button onClick={()=>control('state')}>读取请求计数</button>{' '}
 <button onClick={async()=>{await control('control',{history:!history});setHistory(!history);}}>切换 MATERIAL options503（之后读取正式候选）</button>
 <p>下载后可通过此控件核验真实下载文件（保存文件路径请另行记录）：</p><input aria-label="核验实际下载JSON" type="file" accept=".json" onChange={async e=>{const f=e.target.files?.[0];if(f){const bytes=await f.arrayBuffer();const rawSha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join('');await control('compare-download',{fileName:f.name,rawSha256,value:JSON.parse(new TextDecoder().decode(bytes))});}}}/>
 <pre style={{whiteSpace:'pre-wrap'}}>{status}</pre></aside><Panel analysisId={ids[scenario]}/></>;}
createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true,
    platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' },
    define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
const imports = Object.keys(bundle.metafile.inputs).map((s) => s.replaceAll('\\', '/'));
for (const suffix of ['components/OrdinaryAcmgEvaluationPanel.jsx', 'components/AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js'])
    assert.ok(imports.some((s) => s.endsWith(suffix)));
write('bundle-inputs.json', imports); write('asset-sha256.json', ['entry.jsx','app.js'].map(p=>({path:p,bytes:fs.statSync(path.join(output,p)).size,sha256:sha(fs.readFileSync(path.join(output,p)))}))); for(const p of ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js','src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx','src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx','scripts/bp1-business-browser-smoke.mjs']) fs.copyFileSync(path.join(project,p),path.join(output,path.basename(p)));

// Two intact saved runs and twelve core-only objects use separate actual components.
await build({stdin:{contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server'; import {SavedResult} from ${JSON.stringify(component)}; import Matrix from ${JSON.stringify(path.join(path.dirname(component),'AcmgEvidenceMatrix.jsx'))}; export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>); export const renderCore=bp1=>renderToStaticMarkup(<><p>CORE_RESULT_ONLY</p><Matrix candidate={{candidateId:'CORE_RESULT_ONLY',attempts:[{bp1}]}} enabledCriteria={['ACMG_AMP_2015:BP1']}/></>);`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',outfile:path.join(output,'render.cjs'),loader:{'.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning',plugins:[{name:'test-only-export',setup(api){api.onLoad({filter:/OrdinaryAcmgEvaluationPanel\.jsx$/},()=>({contents:fs.readFileSync(component,'utf8')+'\nexport {SavedResult};',loader:'jsx',resolveDir:path.dirname(component)}));}}]});
const {render,renderCore}=createRequire(import.meta.url)(path.join(output,'render.cjs'));
const rendered=[];
function inspect(name,bp1,html) {
 const e=bp1.evaluation||bp1, card=helpers.evidenceCards({attempts:[{bp1}]},['ACMG_AMP_2015:BP1'])[0].entries[0];
 assert.deepEqual(card.evaluation,e);assert.equal(card.raw,bp1);assert.ok(html.includes(e.evaluationResult.reasonCode));
 assert.ok(html.includes(helpers.reasonHelp(e.evaluationResult.reasonCode,'BP1_MISSENSE_V9')));
 assert.ok(!html.includes('undefined'));assert.ok(!html.includes('需要 CSpec / 人工复核处理'));assert.ok(html.includes('本次评价结果'));assert.ok(!html.includes('净核苷酸'));
 if(!e.appliedStrength) {assert.equal(card.evaluation.appliedStrength,undefined);assert.ok(html.includes('实际强度：未记录'));assert.ok(card.details.some(([k,t])=>k==='保存执行步骤'&&t==='未记录/未进入该步骤'));}
 else assert.ok(html.includes(e.appliedStrength));
 for(const k of ['hgvsc','hgvsp','refCodon','altCodon','refAminoAcid','altAminoAcid','proteinVariantKey','storedAnnotationSha256','nativeModelSha256']) if(bp1.resolvedInput?.[k]!=null) assert.ok(card.details.some(([,t])=>t.includes(String(bp1.resolvedInput[k]))),name+' '+k);
 if(bp1.mechanismMaterial?.reviewedVariantApplicability?.identity) assert.ok(card.details.some(([k,t])=>k==='受审变异适用身份'&&t===JSON.stringify(bp1.mechanismMaterial.reviewedVariantApplicability.identity)));
 assert.equal(card.evaluation.criterionApplicability,e.criterionApplicability);
 for(const [c,label] of [[bp1.mechanismMaterial?.truncatingMechanism,'截短主导致病机制'],[bp1.mechanismMaterial?.reviewedVariantApplicability?.exceptions,'当前变异例外检索'],[bp1.mechanismMaterial?.reviewedVariantApplicability?.splicing,'当前变异剪接适用']]) {
  assert.ok(card.details.some(([k])=>k===label+'：保存的材料声明'));
  if(c) {assert.ok(card.details.some(([k,t])=>k===label+'：保存的材料声明'&&t.includes(c.conclusion)));
   for(const key of ['reviewer','evaluatedAt','basis','contentSha256']) if(c.review?.[key]) assert.ok(card.details.some(([,t])=>t.includes(String(c.review[key]))),name+' '+label+' '+key);}
 }
 return e.evaluationResult;
}
for(const [name,f] of Object.entries(fixtures)) {
 const run=f.saved,before=JSON.stringify(run),html=render(run),other=render(run,['ACMG_AMP_2015:BA1']);
 assert.ok(helpers.bp1SavedRun(run),name+' saved identity');
 for(const markup of [html,other]) {assert.ok(markup.includes('BP1'));assert.ok(markup.includes('只读'));assert.ok(!markup.includes('>BA1<'));}
 const outcomes=run.candidates.map(c=>inspect(name,c.attempts[0].bp1,html));
 assert.equal(JSON.stringify(run),before);fs.writeFileSync(path.join(output,name+'.html'),'<!doctype html><meta charset="utf-8">'+html);rendered.push({name,kind:'ACTUAL_SAVED_RUN',outcomes});
}
for(const [name,bp1] of Object.entries(core)) {
 const before=JSON.stringify(bp1),html=renderCore(bp1);assert.equal(bp1.runId,undefined);
 const outcome=inspect(name,bp1,html);assert.equal(JSON.stringify(bp1),before);
 fs.writeFileSync(path.join(output,'core-'+name+'.html'),'<!doctype html><meta charset="utf-8">'+html);rendered.push({name,kind:'CORE_RESULT_ONLY_NOT_SAVED_RUN',outcome});
}
for(const mutation of [run=>{run.declarationVersion='ordinary-bp3-inframe-v1';},run=>{run.executionProfile.manifestSha256='0'.repeat(64);}]) {
 const run=structuredClone(fixtures.NORMAL.saved);mutation(run);
 const html=render(run,['ACMG_AMP_2015:BP1']);assert.ok(html.includes('执行身份不受支持'));assert.ok(!html.includes('实际强度：'));
}
for(const mutation of [run=>{delete run.executionProfile;delete run.declarationVersion;},
 run=>{run.candidates[0].attempts[0].bp1.bundleIdentity=structuredClone(fixtures.MATERIAL.saved.executionProfile.reviewedRuleBinding);}]) {
 const run=structuredClone(fixtures.NORMAL.saved);mutation(run);
 assert.ok(helpers.bp1ScopeClaim(run),'partial saved v9 binding is a claim, not permission');
 assert.equal(helpers.bp1SavedRun(run),false,'missing or mixed saved identity');
 const html=render(run,['ACMG_AMP_2015:BP1']);assert.ok(html.includes('执行身份不受支持'));assert.ok(!html.includes('实际强度：'));
}
write('render-validation.json',{status:'PASS',actualComponents:true,scenarios:rendered,currentNonBp1History:true,immutableInput:true,browserInteraction:'NOT_RUN'});

const state = { history: false, delayNext: false, readOnly: false }, journal = [], pending = [];
let origin;
const snapshot = () => ({ testOnly: true, ...state, postCount: journal.filter((e) => e.kind === 'POST_RUN').length,
    acceptedPostCount: journal.filter((e) => e.kind === 'POST_RUN' && e.status === 200).length, pending: pending.length,
    scientificExecution: 'NOT_RUN_ARCHIVE_REPLAY_ONLY', output });
function finish() { pending.splice(0).forEach((p) => p.send()); write('summary.json', snapshot()); server.close(); server.closeAllConnections(); }
const server = http.createServer(async (req, res) => {
    const event = { executionClass: selfCheck ? 'HTTP_SELF_CHECK' : 'BROWSER', at: new Date().toISOString(), method: req.method, url: req.url, kind: 'OTHER' };
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'");
    const reply = (status, value, type = 'application/json', delay = false) => {
        event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'requests.jsonl'), JSON.stringify(event) + '\n');
        const send = () => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': `${type}; charset=utf-8` }); res.end(type === 'application/json' ? JSON.stringify(value) : value); } };
        if (delay) { state.delayNext = false; const p = { send: () => { clearTimeout(p.timer); const i = pending.indexOf(p); if (i >= 0) pending.splice(i, 1); send(); } }; p.timer = setTimeout(p.send, 120000); pending.push(p); }
        else send();
    };
    try {
        if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site')
            return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        const url = new URL(req.url, origin); if(url.pathname.startsWith('/__test/')) event.kind = url.pathname.includes('compare-download') ? 'FILE_COMPARE' : 'CONTROL';
        if (req.method === 'GET' && url.pathname === '/') return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>BP1 TEST_ONLY</title><div id="root"></div><script src="/app.js"></script></html>', 'text/html');
        if (req.method === 'GET' && url.pathname === '/app.js') return reply(200, fs.readFileSync(path.join(output, 'app.js')), 'text/javascript');
        let body;
        if (req.method === 'POST') {
            assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0;
            for await (const chunk of req) { size += chunk.length; assert.ok(size <= 1024 * 1024); chunks.push(chunk); }
            body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body;
        }
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') {
            if (typeof body.readOnly === 'boolean') state.readOnly = body.readOnly;
            if (typeof body.history === 'boolean') state.history = body.history;
            if (typeof body.delayNext === 'boolean') state.delayNext = body.delayNext;
            return reply(200, snapshot());
        }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach((p) => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname === '/__test/compare-download' && req.method === 'POST') {
            const f = Object.values(fixtures).find((v) => v.saved.runId === body.value?.runId); assert.ok(f);
            assert.match(body.rawSha256, /^[a-f0-9]{64}$/); assert.equal(body.fileName, `acmg-${f.saved.runId}.json`);
            assert.deepEqual(body.value, f.saved); write(`download-comparison-${f.saved.runId}.json`, { status: 'PASS', runId: f.saved.runId,
                fileName: body.fileName, rawSha256: body.rawSha256, comparedAgainst: f.savedSource, input: selfCheck ? 'SELF_CHECK_INTERFACE_ONLY_NOT_DOWNLOAD' : 'BROWSER_FILE_PICKER_ACTUAL_DOWNLOAD' });
            return reply(200, { status: 'PASS', runId: f.saved.runId });
        }
        const match = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const f = match && Object.values(fixtures).find((v) => v.saved.analysisId === decodeURIComponent(match[1]));
        if (!f) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        if (req.method === 'GET' && match[2] === 'input-options') {
            event.kind = 'GET_OPTIONS';
            if ((state.history && f === fixtures.MATERIAL) || f.readOnly) return reply(503, { message: 'TEST_ONLY_CURRENT_BP1_UNAVAILABLE_OR_READ_ONLY_ARCHIVE' });
            if (url.searchParams.get('topN') !== '2') return reply(400, { message: 'TEST_ONLY_TOP2_REQUIRED：请把正式 Top-N 改为 2 后读取' });
            return reply(200, f['input-options']);
        }
        if (req.method === 'GET' && match[2] === 'runs') {
            event.kind = match[3] ? 'GET_RUN' : 'GET_RUNS'; event.savedSource = f.savedSource; const run = f.saved;
            if (match[3] && match[3] !== run.runId) return reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' });
            return reply(200, match[3] ? run : [{ runId: run.runId, status: run.status, createdAt: run.createdAt }]);
        }
        if (req.method === 'POST' && match[2] === 'runs' && !match[3]) {
            event.kind = 'POST_RUN'; const id = journal.filter(e => e.kind === 'POST_RUN').length + 1; write('request-'+id+'-incoming.json', body); if(f.request) write('request-'+id+'-expected.json', f.request); assert.ok(!(state.history && f === fixtures.MATERIAL), 'Current BP1 scope unavailable');
            assert.ok(!state.readOnly && !f.readOnly && f.request, 'NO_CURRENT_PERMISSION'); validateRequest(body, f); write('request-'+id+'-comparison.json',{status:'PASS',expected:'ARCHIVED_BACKEND_REQUEST',allowlistedDifferences:['patient.patientContextId','patient.source','patient.familyContextId: KEY_ABSENT -> explicit null (strictly asserted)','hypotheses[*].hypothesis.provenance','hypotheses[*].transcript.source','hypotheses[*].transcript.selectionReason = exact BP1 confirmation prose'],allOtherFieldsCompared:true});
            return reply(200, f.saved, undefined, state.delayNext);
        }
        return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
    } catch (error) { return reply(400, { message: `TEST_ONLY_CONTRACT_REJECTED: ${error.message}` }); }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve)); origin = `http://127.0.0.1:${server.address().port}`;
write('server.json', { origin, output, pid: process.pid, topN: 2, testOnly: true, browserStatus: 'NOT_RUN', startedAt:new Date().toISOString() });
console.log(JSON.stringify({ origin, output, pid: process.pid }));
if (!selfCheck) { process.once('SIGINT', finish); process.once('SIGTERM', finish); }
else {
    try {
        assert.equal((await fetch(origin)).status, 200);
        for (const f of Object.values(fixtures)) {
            if (f.readOnly) {
                const route = `/variant-analyses/${f.saved.analysisId}/acmg`;
                assert.equal((await fetch(origin + route + '/input-options?topN=2')).status, 503);
                assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
                assert.equal((await fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 400);
                continue;
            }
            const o = f['input-options'], route = `/variant-analyses/${o.analysisId}/acmg`;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/input-options?topN=2')).json(), o);
            const inputs = helpers.prepareSimplifiedInputs(o);
            f.request.recommendations.forEach((r) => Object.assign(inputs[r.candidateId], { annotationId: r.annotationId, stableRelationshipKey: r.stableRelationshipKey }));
            const actual = helpers.buildSimplifiedCaseDeclaration(o, o.analysisId, inputs, undefined, 'SYNTHETIC');
            const post = (b) => fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
            for (let i = 0; i < 1; i++) { const r = await post(actual); assert.equal(r.status, 200, await r.clone().text()); assert.deepEqual(await r.json(), f.saved); }
            const extra = {...actual, extraScienceAuthority:true}; assert.equal((await post(extra)).status,400);
            const wrong = structuredClone(actual); wrong.hypotheses[0].transcript.selectionPolicy += 'wrong'; assert.equal((await post(wrong)).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        await fetch(origin+'/__test/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({history:true})});
        const materialRoute='/variant-analyses/'+fixtures.MATERIAL.saved.analysisId+'/acmg';
        assert.equal((await fetch(origin+materialRoute+'/input-options?topN=2')).status,503);
        assert.equal((await fetch(origin+materialRoute+'/runs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(helpers.buildSimplifiedCaseDeclaration(fixtures.MATERIAL['input-options'],fixtures.MATERIAL.saved.analysisId,helpers.prepareSimplifiedInputs(fixtures.MATERIAL['input-options']),undefined,'SYNTHETIC'))})).status,400);
        assert.deepEqual(await (await fetch(origin+materialRoute+'/runs/'+fixtures.MATERIAL.saved.runId)).json(),fixtures.MATERIAL.saved);
        const controlResponse=await fetch(origin+'/__test/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({readOnly:true,history:true})});
        assert.equal(controlResponse.status,200);assert.equal((await controlResponse.json()).readOnly,true);
        for(const f of Object.values(fixtures)) {
            const route=origin+'/variant-analyses/'+f.saved.analysisId+'/acmg';
            assert.deepEqual(await (await fetch(route+'/runs/'+f.saved.runId)).json(),f.saved);
            assert.equal((await fetch(route+'/runs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(f.request)})).status,400);
        }
        assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        const wrongHostStatus = await new Promise((resolve, reject) => {
            const request = http.get(origin, { headers: { Host: 'invalid.example' } }, response => {
                response.resume(); resolve(response.statusCode);
            });
            request.on('error', reject);
        });
        assert.equal(wrongHostStatus, 403);
        for (const f of Object.values(fixtures)) {
            const body = { fileName: 'acmg-' + f.saved.runId + '.json', rawSha256: sha(JSON.stringify(f.saved)), value: f.saved };
            const compare = b => fetch(origin + '/__test/compare-download', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});
            assert.equal((await compare(body)).status,200);
            assert.equal((await compare({...body,value:{...f.saved,status:'FABRICATED'}})).status,400);
        }
        write('transport-validation.json', { status: 'PASS', ...snapshot(), browserInteraction: 'NOT_RUN', download: 'NOT_RUN' });
    } finally { finish(); }
}
