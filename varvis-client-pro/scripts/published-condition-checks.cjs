// Actual Java public products + explicit in-memory counterexamples. No backend/model/HTTP work.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const project = path.resolve(__dirname, '..');
const client = process.env.VARVIS_READONLY_CLIENT_ROOT || 'D:/LSD/Project/Code/varvis-client-pro';
const deps = createRequire(path.join(client, 'package.json'));
const { build: esbuild } = deps('esbuild');
async function main() {
const source = `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import * as input from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
import * as proof from './src/pages/varvis/ChromosomeVisualization/utils/acmgPublishedConditionProof.js';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export {input,proof};export const renderSaved=run=>renderToStaticMarkup(<SavedResult run={run} criteria={run.enabledCriteria}/>);`;
const apiNames = [...fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js'), 'utf8')
    .matchAll(/export\s+(?:async\s+)?(?:function|const)\s+([A-Za-z_$][\w$]*)/g)].map(m => m[1]);
const build = (await esbuild({ stdin: { contents: source, resolveDir: project, loader: 'jsx' }, bundle: true,
    platform: 'node', format: 'cjs', write: false, nodePaths: [path.join(client, 'node_modules')],
    loader: { '.less': 'empty', '.css': 'empty' }, logLevel: 'warning', define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'forbid-service-transport', setup(b) {
        b.onResolve({ filter: /services\/variantAnalysis$/ }, () => ({ path: 'no-http', namespace: 'no-http' }));
        b.onLoad({ filter: /.*/, namespace: 'no-http' }, () => ({ contents: apiNames.map(n => `export const ${n}=()=>{throw Error('UNEXPECTED_SERVICE_CALL');};`).join('\n') }));
    } }] })).outputFiles[0].text;
const compiled = { exports: {} };new Function('require', 'module', 'exports', build)(deps, compiled, compiled.exports);
const { input, proof, renderSaved } = compiled.exports;
const base = path.resolve(process.argv[2] || 'D:/LSD/Project/Code/local/development/handoffs/acmg-remaining-20261104/MAIN/pm1-peer-closure-round3');
const output = process.argv[3];
const proofOnly = process.argv.includes('--proof-only');
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const sha256 = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const clone = x => structuredClone(x);
const report = { tier: 'FRONTEND_DISPLAY_AND_CALLER_BINDING_ONLY', newBackendCalls: 0, clinicalQualification: false,
    proofOnly, notRun: proofOnly ? ['Complete cards and saved-panel paths explicitly omitted by --proof-only.'] : [], artifacts: [], actualRuns: [], cases: [] };
const artifact = p => { report.artifacts.push({ path: p, sha256: sha256(p) }); return read(p); };
const test = (name, action) => { try { action(); report.cases.push({ name, status: 'PASS' }); }
    catch (error) { console.error(`FAILED_CASE: ${name}`); throw error; } };
const pm1 = attempt => attempt.localBatch.criteria.find(c => c.criterionId === 'ACMG_AMP_2015:PM1');
const expected = (run, candidate, attempt) => ({ analysisId: run.analysisId, executionSourceSha256: run.executionSourceSha256,
    candidateId: candidate.candidateId, hypothesisId: attempt.hypothesisId, context: attempt.context,
    executionProfile: run.executionProfile, enabledCriteria: run.enabledCriteria });
const inspect = (criterion, e) => proof.inspectPublishedConditionCriterion(criterion, e, { identityHash: input.ps2IdentityHash });
const completeBase = path.resolve(base, '../pm1-complete-configuration-round3');
const normal = artifact(path.join(completeBase, 'actual-java-01/SINGLE/expected-public.json'));
for (const mode of ['SINGLE', 'RELATED', 'ALL_CONFIGURED']) {
    const run = artifact(path.join(completeBase, `actual-java-01/${mode}/expected-public.json`));
    for (const [i,candidate] of run.candidates.entries()) {
        const attempt = candidate.attempts[0], c = pm1(attempt), e = expected(run,candidate,attempt);
        test(`actual normal ${mode}/${i} preserves ${c.status}`, () => {
            assert.equal(inspect(c,e).valid,true,JSON.stringify(inspect(c,e)));
            if (proofOnly) return;
            assert.equal(input.localBatchScopeIssue(run),null);
            assert.equal(input.localBatchAttemptIssue(candidate,attempt,run.enabledCriteria,run.executionProfile,c.criterionId,run),null);
            const card = input.evidenceCards(candidate,run.enabledCriteria,undefined,undefined,run).find(v=>v.id===c.criterionId);
            assert.equal(card.entries[0].displayIssue,false);
            assert.equal(card.entries[0].evaluation.evaluationResult.status,c.status);
            assert.ok(input.publishedMissenseDetails(c,run.executionProfile,e).some(r=>r[0].includes('需要 / 已有')));
        });
    }
    if (!proofOnly) test(`actual ${mode} saved-panel completeness uses current root`,()=>assert.ok(!renderSaved(run).includes('保存的本地证据身份或部分结果不完整')));
}
for (const mode of ['SINGLE', 'RELATED', 'ALL_CONFIGURED']) {
    const run = artifact(path.join(base, `actual-business-06/${mode}/expected-public.json`));
    test(`original null-BA1 ${mode} ${mode === 'ALL_CONFIGURED' ? 'rejects selected BA1' : 'allows explicitly unselected resource'}`,()=>{
        assert.equal(run.executionProfile.populationExecutionProfile.profileVersion,'4.1.0');
        assert.equal(run.executionProfile.ba1RuntimeBinding,null);
        assert.equal(run.executionProfile.populationExecutionProfile.ba1RuntimeBinding,null);
        assert.equal(input.localBatchScopeIssue(run)===null,mode!=='ALL_CONFIGURED');
    });
    if (mode === 'SINGLE') for (const [name,change] of Object.entries({
        ambiguousSelection:r=>{delete r.executionProfile.criterionSelection;},
        oneSidedBa1:r=>{r.executionProfile.populationExecutionProfile.ba1RuntimeBinding={schemaVersion:'invented'};},
        invalidBs1:r=>{r.executionProfile.bs1RuntimeBinding.activationContract='INVENTED';r.executionProfile.populationExecutionProfile.bs1RuntimeBinding.activationContract='INVENTED';},
        invalidManifest:r=>{r.executionProfile.populationExecutionProfile.manifestSha256='bad';},
        forgedSelection:r=>{r.enabledCriteria=['ACMG_AMP_2015:BA1'];},
    })) test(`unselected BA1 does not bypass ${name}`,()=>{const bad=clone(run);change(bad);assert.notEqual(input.localBatchScopeIssue(bad),null);});
}
const candidate = normal.candidates[0], attempt = candidate.attempts[0], criterion = pm1(attempt), e = expected(normal,candidate,attempt);
for (const name of ['pm2-MET','pm2-NOT_MET','pm2-NOT_EVALUATED','pp3-MET-STRONG','pp3-MET-MODERATE','pp3-NOT_MET-NONE','pp3-NOT_EVALUATED-NONE']) {
    const folder=path.join(base,'actual-java-focused-06',name), full=read(path.join(folder,'actual-full.json'));
    const pub=read(path.join(folder,'actual-public.json')), w=full.results[0], c=pub.results[0].localBatch.criteria.find(x=>x.criterionId==='ACMG_AMP_2015:PM1');
    const ownExpected={...e,analysisId:full.analysisId,executionSourceSha256:full.sourceSnapshotSha256,
        candidateId:w.candidateId,hypothesisId:w.hypothesisId,context:w.context};
    test(`actual module-level ${name} ${c.status} (synthetic peer transport)`,()=>assert.equal(inspect(c,ownExpected).valid,true,JSON.stringify(inspect(c,ownExpected))));
}
test('new proof without independent caller does not upgrade', () => assert.equal(inspect(criterion,undefined).valid,false));
test('details without independent root do not upgrade', () => assert.deepEqual(input.publishedMissenseDetails(criterion),[]));
for (const [name,change] of Object.entries({
    source:x=>{x.executionSourceSha256='b'.repeat(64);}, candidate:x=>{x.candidateId+='-other';},
    hypothesis:x=>{x.hypothesisId+='-other';}, analysisMissing:x=>{delete x.analysisId;},
    context:x=>{x.context.disease.mondoId='MONDO:9999999';}, allele:x=>{x.context.variant.evidenceQueryAllele.alternate='G';},
    profile:x=>{delete x.executionProfile.moduleBindings.pm1;}, scope:x=>{x.enabledCriteria=['ACMG_AMP_2015:PM2'];},
    unknownMethod:x=>{x.executionProfile.moduleBindings.pm1.runtimeBinding.pm1ConditionalPeerExecution.methodVersion='1.99.0';},
})) test(`independent caller ${name} mismatch`,()=>{const bad=clone(e);change(bad);assert.equal(inspect(criterion,bad).valid,false);});
for(const [name,change] of Object.entries({
    finalStatus:c=>{c.status='NOT_MET';c.appliedStrength='NONE';},
    finalStrength:c=>{c.appliedStrength='STRONG';},
    proofSource:c=>{c.details.pm1Facts.currentEvaluationBinding.executionSourceSha256='b'.repeat(64);},
    clause:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].required.sourcePointer='/ld/CriteriaCode/9/entContent/strengthDescriptor/0';},
    clauseHash:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].required.sourceSha256='b'.repeat(64);},
    selectedClause:c=>{c.details.pm1Facts.sourceClausePointer='/ld/CriteriaCode/9';},
    sourceDisposition:c=>{c.details.pm1Facts.sourceDisposition='SOURCE_CONFLICT';},
    actualPeer:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].available.actualModulePointer='/modules/reference/results/0';},
    intermediatePointer:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].available.evaluationPointer='/scientificDecision';},
    peerStrength:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].available.appliedStrength='NONE';},
    checkOutcome:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].status='NOT_MET';},
    requiredRelation:c=>{c.details.pm1Facts.crossCriterionConditionCheck.checks[0].required.relation='EXCLUDE_IF_MET_AT_LEAST';},
    missingProof:c=>{delete c.details.pm1Facts.currentEvaluationBinding;},
    clinical:c=>{c.details.pm1Facts.clinicalValidationCompleted=true;},
}))test(`actual contract mutation ${name}`,()=>{const bad=clone(criterion);change(bad);assert.equal(inspect(bad,e).valid,false);});
test('self-rehashed metadata cannot replace independently expected context',()=>{
    const bad=clone(criterion), b=bad.details.pm1Facts.currentEvaluationBinding, fake=clone(e.context);
    fake.gene.hgncId='HGNC:999999';b.originalContextSha256=input.ps2IdentityHash(fake);
    bad.details.pm1Facts.crossCriterionConditionCheck.binding.originalContextSha256=b.originalContextSha256;
    assert.equal(inspect(bad,e).valid,false);
});
test('actual conditional NOT_MET keeps support rule rather than exclusion fiction',()=>{
    const c=pm1(normal.candidates[1].attempts[0]);assert.equal(c.details.pm1Facts.selectedRule.effect,'SUPPORT');
    assert.equal(inspect(c,expected(normal,normal.candidates[1],normal.candidates[1].attempts[0])).valid,true);
});
test('nonexclusive source metadata does not create a scored or required peer',()=>{
    const c=clone(criterion),f=c.details.pm1Facts;
    f.crossCriterionRelations.push({criterionId:'ACMG_AMP_2015:PP4',relation:'NOT_MUTUALLY_EXCLUSIVE',
        sourcePointer:'/entContent/notes',sourceSha256:'c'.repeat(64)});
    assert.equal(inspect(c,e).valid,true); // Contract mutation only; not a new published/scientific positive.
});
test('a different global condition descriptor is not forced to equal the winner descriptor',()=>{
    const c=clone(criterion);c.details.pm1Facts.selectedRule.sourcePointer=c.details.pm1Facts.sourceClausePointer+'/entContent/strengthDescriptor/9';
    c.details.pm1Facts.selectedRule.sourceSha256='c'.repeat(64);
    assert.equal(inspect(c,e).valid,true); // Backend, not this display check, establishes original source semantics.
});
if (!proofOnly) test('real saved-result component uses the independent root at its completeness call',()=>{
    const html=renderSaved(normal);assert.ok(!html.includes('保存的本地证据身份或部分结果不完整'));
    const bad=clone(normal);bad.executionSourceSha256='b'.repeat(64);
    assert.ok(renderSaved(bad).includes('保存的本地证据身份或部分结果不完整'));
});
test('actual historical no-proof PM1 stays under the original contract',()=>{
    const old=read('D:/LSD/Project/Code/local/development/handoffs/acmg-remaining-20261104/T01/phase-03/business-07/single/actual-product.json');
    const c=pm1(old.candidates[0].attempts[0]);assert.equal(c.details.pm1Facts.currentEvaluationBinding,undefined);
    assert.equal(input.localBatchAttemptIssue(old.candidates[0],old.candidates[0].attempts[0],old.enabledCriteria,old.executionProfile,c.criterionId),null);
    assert.ok(input.publishedMissenseDetails(c).length>0);
});
const bpBase = path.resolve(base,'../bp1-current-public-binding-round3');
const bpId = 'ACMG_AMP_2015:BP1';
const bp1 = attempt => attempt.localBatch.criteria.find(c=>c.criterionId===bpId);
let bpNormal;
for (const mode of ['MISSENSE_BP1','MISSENSE_BP1_AND_PP2','MISSENSE_ALL_CONFIGURED','NONSENSE_BP1','NONSENSE_BP1_AND_PP2','NONSENSE_ALL_CONFIGURED']) {
    const run=artifact(path.join(bpBase,`actual-java-02/${mode}/expected-public.json`));
    if(mode==='NONSENSE_BP1') bpNormal=run;
    for(const [i,candidate] of run.candidates.entries()) {
        const attempt=candidate.attempts[0],c=bp1(attempt),e=expected(run,candidate,attempt);
        test(`actual BP1 current ${mode}/${i} ${c.status}`,()=>{
            assert.equal(inspect(c,e).valid,true,JSON.stringify(inspect(c,e)));
            const rows=input.publishedMissenseDetails(c,run.executionProfile,e);
            assert.ok(rows.some(r=>r[0]==='BP1条款：需要的信息'));
            assert.ok(rows.some(r=>r[0]==='BP1条款：实际已有'));
            assert.ok(rows.some(r=>r[0]==='BP1条款：当前差异'));
            assert.ok(!rows.some(r=>r[0]==='BP1附加剪接核对'));
            if(proofOnly) return;
            if(mode.endsWith('ALL_CONFIGURED')) {
                assert.equal(run.executionProfile.ba1RuntimeBinding,null);
                assert.notEqual(input.localBatchScopeIssue(run),null);
                return; // Real old producer did not configure selected BA1; no UI-scope bypass.
            }
            assert.equal(input.localBatchScopeIssue(run),null);
            assert.equal(input.localBatchAttemptIssue(candidate,attempt,run.enabledCriteria,run.executionProfile,c.criterionId,run),null);
            const card=input.evidenceCards(candidate,run.enabledCriteria,undefined,undefined,run).find(v=>v.id===bpId);
            assert.equal(card.entries[0].displayIssue,false);
            assert.equal(card.entries[0].evaluation.evaluationResult.status,c.status);
            assert.ok(!card.entries[0].summary.includes('尚未提供可识别的具体原因'));
            if(mode.startsWith('MISSENSE')) assert.equal(card.entries[0].evaluation.criterionApplicability.status,'NOT_APPLICABLE');
        });
    }
    if(!proofOnly && !mode.endsWith('ALL_CONFIGURED')) test(`actual BP1 ${mode} complete saved-panel no generic pending check`,()=>assert.ok(!renderSaved(run).includes('保存的本地证据身份或部分结果不完整')));
}
const bpc=bpNormal.candidates[0],bpa=bpc.attempts[0],bpe=expected(bpNormal,bpc,bpa),b=bp1(bpa);
test('BP1 new proof without independent caller cannot upgrade',()=>assert.equal(inspect(b,undefined).valid,false));
for(const [name,change] of Object.entries({
    root:x=>{x.executionSourceSha256='b'.repeat(64);},candidate:x=>{x.candidateId+='-other';},
    attempt:x=>{x.hypothesisId+='-other';},context:x=>{x.context.disease.mondoId='MONDO:9999999';},
    selection:x=>{x.enabledCriteria=['ACMG_AMP_2015:PM2'];},profile:x=>{delete x.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution;},
}))test(`BP1 independent caller ${name}`,()=>{const bad=clone(bpe);change(bad);assert.equal(inspect(b,bad).valid,false);});
for(const [name,change] of Object.entries({
    missingBinding:c=>{delete c.details.bp1Facts.currentBinding;},
    source:c=>{c.details.bp1Facts.currentBinding.executionSourceSha256='b'.repeat(64);},
    wrongCandidate:c=>{c.details.bp1Facts.currentBinding.candidateId+='-other';},
    wrongAttempt:c=>{c.details.bp1Facts.currentBinding.hypothesisId+='-other';},
    module:c=>{c.details.bp1Facts.currentBinding.modulePointer='/modules/reference/results/0';},
    finalStatus:c=>{c.status='MET';c.appliedStrength='SUPPORTING';},
    finalReason:c=>{c.reasonCode='BP1_INVENTED';},
    clause:c=>{c.details.bp1Facts.truncatingConditionCheck.publishedSource.clausePointer='/ld/CriteriaCode/8';},
    sourcePin:c=>{c.details.bp1Facts.currentBinding.observedSourcePins.sourceRawSha256='b'.repeat(64);},
    knownCorrelation:c=>{c.details.bp1Facts.truncatingConditionCheck.lofDiseaseCorrelation.relation.hgncId='HGNC:999999';},
    clinical:c=>{c.details.bp1Facts.clinicalValidationCompleted=true;},
    selfRehashedContext:c=>{const fake=clone(bpe.context);fake.gene.hgncId='HGNC:999999';c.details.bp1Facts.currentBinding.originalContextSha256=input.ps2IdentityHash(fake);},
}))test(`BP1 actual contract mutation ${name}`,()=>{const bad=clone(b);change(bad);assert.equal(inspect(bad,bpe).valid,false);});
test('BP1 unknown no-LoF qualification never becomes NOT_MET or MET by declaration',()=>{
    const candidate=bpNormal.candidates[1],attempt=candidate.attempts[0],c=clone(bp1(attempt));
    c.status='NOT_MET';c.details.bp1Facts.currentBinding.status='NOT_MET';
    c.details.bp1Facts.truncatingConditionCheck.status='NOT_MET';
    assert.equal(inspect(c,expected(bpNormal,candidate,attempt)).valid,false);
});
test('actual legacy BP1 stays under original scientific-format contract without current binding',()=>{
    const old=artifact('D:/LSD/Project/Code/local/development/handoffs/bp1-completion-20261015/web-01/http-final/result.json');
    const candidate=old.candidates[0],attempt=candidate.attempts[0],c=bp1(attempt);
    assert.equal(c.details.bp1Facts.currentBinding,undefined);
    assert.equal(input.localBatchAttemptIssue(candidate,attempt,old.enabledCriteria,old.executionProfile,bpId),null);
    assert.ok(input.publishedMissenseDetails(c).length>0);
});
if(!proofOnly) {
    const t02base=path.resolve(base,'../../T02/continuation-02'),samples=artifact(path.join(t02base,'actual-public-samples.json'));
    for(const sample of samples.samples) test(`actual T02 ${sample.scenario} full current cards and saved-panel`,()=>{
        const file=path.join(t02base,...sample.publicFile.path.split(/[\\/]/));
        assert.equal(sha256(file),sample.publicFile.sha256);const run=artifact(file);
        assert.equal(input.localBatchScopeIssue(run),null);
        const summaries=[];
        for(const candidate of run.candidates) {
            for(const attempt of candidate.attempts) assert.equal(input.localBatchAttemptIssue(candidate,attempt,run.enabledCriteria,run.executionProfile,undefined,run),null);
            const cards=input.evidenceCards(candidate,run.enabledCriteria,undefined,undefined,run);
            assert.ok(cards.length>0);assert.ok(cards.every(card=>card.entries.every(entry=>!entry.displayIssue)));
            summaries.push({candidateId:candidate.candidateId,cards:cards.map(card=>({criterionId:card.id,
                entries:card.entries.map(entry=>({displayIssue:entry.displayIssue,status:entry.evaluation?.evaluationResult?.status,
                    strength:entry.evaluation?.appliedStrength,reasonCode:entry.evaluation?.evaluationResult?.reasonCode}))}))});
        }
        assert.ok(!renderSaved(run).includes('保存的本地证据身份或部分结果不完整'));
        report.actualRuns.push({scenario:sample.scenario,runId:run.runId,sourceSha256:run.executionSourceSha256,
            enabledCriteria:run.enabledCriteria,scopeIssue:null,genericPendingWarning:false,candidates:summaries});
    });
    if(process.env.VARVIS_CONDITIONAL_INTEGRATED_DIR) for(const suite of ['t01','t02-pp1','t02-pm3']) for(const mode of ['single','related','all']) {
        test(`current integrated ${suite}/${mode} complete cards and saved-panel`,()=>{
            const file=path.join(process.env.VARVIS_CONDITIONAL_INTEGRATED_DIR,suite,
                ...(suite==='t01'?[]:['another-jvm']),mode,'controller-full-export.json');
            const run=artifact(file),summaries=[];
            assert.equal(input.localBatchScopeIssue(run),null);
            for(const candidate of run.candidates) {
                for(const attempt of candidate.attempts) assert.equal(input.localBatchAttemptIssue(candidate,attempt,run.enabledCriteria,run.executionProfile,undefined,run),null,
                    `${suite}/${mode}/${candidate.candidateId}: ${JSON.stringify(attempt.localBatch.criteria.map(c=>({id:c.criterionId,status:c.status,reason:c.reasonCode})))}`);
                const cards=input.evidenceCards(candidate,run.enabledCriteria,undefined,undefined,run);
                assert.equal(cards.length,run.enabledCriteria.length);
                assert.ok(cards.every(card=>card.entries.every(entry=>!entry.displayIssue)),`${suite}/${mode}/${candidate.candidateId} display issues`);
                summaries.push({candidateId:candidate.candidateId,cards:cards.map(card=>({criterionId:card.id,
                    entries:card.entries.map(entry=>({displayIssue:entry.displayIssue,status:entry.evaluation?.evaluationResult?.status,
                        strength:entry.evaluation?.appliedStrength,reasonCode:entry.evaluation?.evaluationResult?.reasonCode}))}))});
            }
            assert.ok(!renderSaved(run).includes('保存的本地证据身份或部分结果不完整'));
            report.actualRuns.push({scenario:`integrated-${suite}-${mode}`,runId:run.runId,sourceSha256:run.executionSourceSha256,
                enabledCriteria:run.enabledCriteria,scopeIssue:null,genericPendingWarning:false,candidates:summaries});
        });
    }
}
report.status=proofOnly?'PASS_PROOF_ONLY_FULL_UI_PENDING':'PASS';report.tests=report.cases.length;
if(output){fs.mkdirSync(path.dirname(path.resolve(output)),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify({status:report.status,tests:report.tests,output:output||null}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
