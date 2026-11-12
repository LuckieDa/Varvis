// The public escape witness is checked as a committed backend-qualified
// projection, not as a re-extraction or clinical approval of private originals.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=process.env.VARVIS_NATIVE_ROOT;
if(!root)throw Error('Set VARVIS_NATIVE_ROOT to existing read-only resources.');
const base=path.join(root,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a2-a4');
const bytes=fs.readFileSync(path.join(base,'round4/public-contract.json')),contract=JSON.parse(bytes);
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgPvs1Splice.js',import.meta.url));
const api=await import('data:text/javascript;base64,'+source.toString('base64'));
// Test-only export invokes the actual private binding operator, not a stub.
const positionsApi=await import('data:text/javascript;base64,'+Buffer.from(source.toString('utf8')+'\nexport {checkPositionProof};').toString('base64'));
const canonical=v=>typeof v==='string'?v.normalize('NFC'):Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(key=>[key.normalize('NFC'),canonical(v[key])])):v;
const identityHash=v=>createHash('sha256').update(JSON.stringify(canonical(v))).digest('hex');
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
let count=0,actualRows=0;const captures=[];
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
check('stable contract points to a committed descendant and exact additive schema',()=>{
    execFileSync('git',['cat-file','-e',contract.sourceHead+'^{commit}']);
    execFileSync('git',['merge-base','--is-ancestor','0f41be882cc9c2a7eac1639beed456dbe8198817',contract.sourceHead]);
    assert.equal(contract.proofSchemaVersion,'pvs1-escape-adoption-proof-v1');assert.equal(contract.proofMethodVersion,'1.0.0');
});
const inspect=(criterion,context)=>api.inspectPvs1SpliceCriterion(criterion,context,{identityHash});
for(const example of contract.actualJavaExamples){
    const expected={UNCAPPED:'STRONG',PARENT_CAP:'MODERATE',LOCAL_CAP:'SUPPORTING',BOTH_CAPS:'SUPPORTING'}[example.scenario];assert.ok(expected);
    for(const [kind,ref]of Object.entries({...example.files,criterionSamples:example.criterionSamples})){
        const file=path.join(base,ref.path),originalBytes=fs.readFileSync(file),saved=JSON.parse(originalBytes),before=JSON.stringify(saved);
        check(`actual ${example.scenario}/${kind} pinned Java bytes and independent adopted cap`,()=>{
            assert.equal(hash(originalBytes),ref.sha256);
            if(kind==='all-local-batch')for(const candidate of saved.candidates)for(const attempt of candidate.attempts){
                const criterion=attempt.localBatch.criteria.find(row=>row.criterionId==='ACMG_AMP_2015:PVS1'),proof=criterion.details.pvs1Facts.escapeAdoptionProof;
                assert.deepEqual(inspect(criterion,attempt.context),{valid:true,issues:[]});
                assert.equal(criterion.status,'MET');assert.equal(criterion.appliedStrength,expected);assert.equal(proof.strengthDerivation.sourceRuleStrength,'STRONG');
                assert.equal(proof.proteinEffect.referenceProteinLength,199);assert.equal(proof.proteinEffect.alteredProteinLength,16);
                assert.equal(proof.ruleCheck.affectedResiduesInRegion,186);assert.ok(api.pvs1SpliceDetails(criterion).some(([label])=>label==='逃逸公开采用证明'));
                captures.push({criterion:structuredClone(criterion),context:structuredClone(attempt.context)});actualRows++;
            }
            if(kind==='pvs1-only')for(const candidate of saved.candidates)for(const attempt of candidate.attempts){
                // This captured direct method preserves the full raw result;
                // it is not the public bounded-proof product path.
                assert.equal(attempt.pvs1.evaluation.evaluationResult.status,'MET');assert.equal(attempt.pvs1.evaluation.appliedStrength,expected);
                assert.equal(attempt.pvs1.resolvedInput.nmdPrediction,'NMD_NOT_PREDICTED');
            }
            if(kind==='criterionSamples')for(const criterion of saved){assert.equal(criterion.status,'MET');assert.equal(criterion.appliedStrength,expected);}
            assert.equal(JSON.stringify(saved),before);assert.deepEqual(fs.readFileSync(file),originalBytes);
        });
        console.log(`FROZEN ${example.scenario}/${kind} ${ref.sha256}`);
    }
}
assert.equal(actualRows,8);
const clone=()=>structuredClone(captures[0]);
const mutations=[
    ['wrong proof schema',p=>p.schemaVersion='UNKNOWN'],['wrong proof major',p=>p.methodVersion='2.0.0'],
    ['new approval invented',p=>p.scientificApprovalCreated=true],['rescoring invented',p=>p.rescoringPerformed=true],
    ['wrong disease',p=>p.scope.mondoId='MONDO:0999999'],['wrong transcript',p=>p.scope.transcript='NM_999999.1'],
    ['wrong model',p=>p.bindings.transcriptModelSha256='f'.repeat(64)],['wrong allele',p=>p.bindings.alleleSha256='f'.repeat(64)],
    ['wrong evaluation',p=>p.bindings.evaluationSha256='f'.repeat(64)],['invalid raw provenance digest',p=>p.bindings.selectedRuleSha256='wrong'],
    ['changed match arithmetic',p=>p.ruleCheck.affectedResiduesInRegion++],['rule changed but selected row not changed',p=>p.selectedRule.start++],
    ['duplicate selected rules',p=>p.ruleChecks.push(structuredClone(p.ruleChecks[0]))],['changed protein denominator',p=>p.proteinEffect.referenceProteinLength++],
    ['altered length confused with reference tail',p=>p.proteinEffect.alteredProteinLength=p.proteinEffect.referenceResiduesAffected],
    ['wrong final cap',p=>p.strengthDerivation.adoptedStrength='VERY_STRONG'],['wrong local cap',p=>p.strengthDerivation.localMaximumStrength='MODERATE'],
    ['missing source',p=>p.sources=[]],['unbound rule source',p=>p.selectedRule.sourceIds=['absent']],['conflicting original source',p=>p.sources[0].sha256='f'.repeat(64)],
    ['unsafe source URL',p=>p.sources[0].url='https://user:password@example.invalid/?secret=1#raw'],
    ['wrong exon inventory',p=>p.affectedReferenceExonOrdinals.pop()],['unrelated exon',p=>p.exonChecks[0].ordinal=200],
    ['negative exon relevance',p=>p.exonChecks[0].biologicalRelevance='ABSENT'],['tolerated exon',p=>p.exonChecks[0].populationLof='FREQUENT'],
    ['fabricated NMD PASS',p=>p.positionConditions.nmd.status='CHECKED'],['positive collection truncated',p=>p.ruleChecks=Array(33).fill(p.ruleChecks[0])],
];
for(const [name,mutate]of mutations)check('synthetic mutation rejects '+name,()=>{
    const v=clone();mutate(v.criterion.details.pvs1Facts.escapeAdoptionProof);const before=JSON.stringify(v);
    assert.equal(inspect(v.criterion,v.context).valid,false);assert.equal(JSON.stringify(v),before);
});
check('exact fraction arithmetic uses separate tail and shortening metrics without floats',()=>{
    const v=clone(),p=v.criterion.details.pvs1Facts.escapeAdoptionProof;
    for(const metric of ['REFERENCE_TAIL_ALTERED_OR_REMOVED','NET_PROTEIN_SHORTENING']){
        const n=metric==='REFERENCE_TAIL_ALTERED_OR_REMOVED'?195:183;
        p.selectedRule={ruleId:'SYNTHETIC_FRACTION',predicate:'PROTEIN_LOSS_FRACTION',metric,comparison:'GT',numerator:1,denominator:10,strength:'STRONG',sourceIds:p.selectedRule.sourceIds};
        p.ruleCheck={status:'MATCH',actualNumerator:n,actualDenominator:199,leftProduct:n*10,rightProduct:199};p.ruleChecks=[{rule:structuredClone(p.selectedRule),...p.ruleCheck}];
        assert.equal(inspect(v.criterion,v.context).valid,true);p.ruleCheck.leftProduct++;assert.equal(inspect(v.criterion,v.context).valid,false);
    }
});
check('published positional bounds bind actual PTC coordinates, while NMD conditions remain unused',()=>{
    const v=clone(),p=v.criterion.details.pvs1Facts.escapeAdoptionProof,ids=p.review.sourceIds;
    p.positionConditions.escape={status:'CHECKED',conditions:[{conditionId:'SYNTHETIC_POSITION',coordinate:'ALTERED_PTC_SPAN',minimumInclusive:40,maximumExclusive:60,sourceIds:ids,status:'PASS',checkedPositions:[49,51]}]};
    p.positionConditions.nmd={status:'NOT_USED_BY_ESCAPE_METHOD',conditions:[{conditionId:'SYNTHETIC_UNUSED',coordinate:'ALTERED_PTC_SPAN',minimumInclusive:200,sourceIds:ids}]};
    assert.equal(inspect(v.criterion,v.context).valid,true);p.positionConditions.escape.conditions[0].checkedPositions=[50,51];assert.equal(inspect(v.criterion,v.context).valid,false);
});
check('large exact fraction operands remain integer comparisons and unsafe serialized products are rejected',()=>{
    const v=clone(),p=v.criterion.details.pvs1Facts.escapeAdoptionProof;
    p.selectedRule={ruleId:'SYNTHETIC_LARGE_FRACTION',predicate:'PROTEIN_LOSS_FRACTION',metric:'REFERENCE_TAIL_ALTERED_OR_REMOVED',comparison:'GT',numerator:1,denominator:2147483647,strength:'STRONG',sourceIds:p.selectedRule.sourceIds};
    p.ruleCheck={status:'MATCH',actualNumerator:195,actualDenominator:199,leftProduct:195*2147483647,rightProduct:199};p.ruleChecks=[{rule:p.selectedRule,...p.ruleCheck}];
    assert.equal(inspect(v.criterion,v.context).valid,true);p.ruleCheck.leftProduct=Number.MAX_SAFE_INTEGER+1;assert.equal(inspect(v.criterion,v.context).valid,false);
});
check('critical rule may retain UNKNOWN population while fraction route cannot invent tolerance clearance',()=>{
    const v=clone(),p=v.criterion.details.pvs1Facts.escapeAdoptionProof;p.exonChecks[0].populationLof='UNKNOWN';
    assert.equal(inspect(v.criterion,v.context).valid,true);
    p.selectedRule={ruleId:'SYNTHETIC_FRACTION',predicate:'PROTEIN_LOSS_FRACTION',metric:'REFERENCE_TAIL_ALTERED_OR_REMOVED',comparison:'GT',numerator:1,denominator:10,strength:'STRONG',sourceIds:p.selectedRule.sourceIds};
    p.ruleCheck={status:'MATCH',actualNumerator:195,actualDenominator:199,leftProduct:1950,rightProduct:199};p.ruleChecks=[{rule:p.selectedRule,...p.ruleCheck}];
    assert.equal(inspect(v.criterion,v.context).valid,false);
});
check('absent and UNAVAILABLE proof preserve original MET but cannot create a positive display',()=>{
    const v=clone(),f=v.criterion.details.pvs1Facts;delete f.escapeAdoptionProof;
    assert.deepEqual(inspect(v.criterion,v.context),{valid:false,issues:['PVS1_SPLICE_ESCAPE_PUBLIC_RULE_PROOF_REQUIRED']});
    f.escapeAdoptionProof={schemaVersion:'pvs1-escape-adoption-proof-v1',methodVersion:'1.0.0',scientificApprovalCreated:false,rescoringPerformed:false,status:'UNAVAILABLE',reasonCode:'PVS1_ESCAPE_PUBLIC_PROOF_SOURCE_BINDING_MISMATCH'};
    assert.deepEqual(inspect(v.criterion,v.context),{valid:false,issues:['PVS1_ESCAPE_PUBLIC_PROOF_UNAVAILABLE']});assert.equal(v.criterion.status,'MET');
    assert.ok(api.pvs1SpliceDetails(v.criterion).some(([,value])=>String(value).includes('公开采用证明未通过')));
});
check('NOT_ADOPTED cannot be swapped onto a positive escape; negative nonadoption stays distinct',()=>{
    const v=clone(),f=v.criterion.details.pvs1Facts;f.escapeAdoptionProof={schemaVersion:'pvs1-escape-adoption-proof-v1',methodVersion:'1.0.0',scientificApprovalCreated:false,rescoringPerformed:false,status:'NOT_ADOPTED',reasonCode:'PVS1_ESCAPE_PUBLIC_PROOF_NO_ADOPTED_ESCAPE'};
    assert.equal(inspect(v.criterion,v.context).valid,false);v.criterion.status='NOT_MET';v.criterion.appliedStrength='NONE';
    assert.deepEqual(api.inspectPvs1EscapeAdoptionProof(v.criterion,v.context,{identityHash}),{valid:true,issues:[]});
    f.escapeAdoptionProof.ruleChecks=[{status:'MATCH'}];assert.equal(api.inspectPvs1EscapeAdoptionProof(v.criterion,v.context,{identityHash}).valid,false);
});
check('raw rule digest keeps its original scope instead of hashing the deliberately partial projection',()=>{
    const v=clone(),p=v.criterion.details.pvs1Facts.escapeAdoptionProof;
    assert.notEqual(identityHash(p.selectedRule),p.bindings.selectedRuleSha256);
    assert.equal(inspect(v.criterion,v.context).valid,true);
    assert.ok(api.pvs1SpliceDetails(v.criterion).some(([label,value])=>label==='逃逸采用证明核验范围'&&value.includes('不声称完成私有原件重放')));
});
// Independent positional vectors copied from the committed producer's
// Pvs1NmdConditions.positions contract, not from the frontend's output.
const positionCondition=positions=>({status:'CHECKED',conditions:[{conditionId:'SYNTHETIC_REFERENCE_EVENT',coordinate:'REFERENCE_EVENT_SPAN',
    sourceIds:['SYNTHETIC_SOURCE'],minimumInclusive:1,maximumExclusive:1000,status:'PASS',checkedPositions:positions}]});
const codingPositionFacts={method:'SOURCE_BOUND_CODING_EDITS',variantMechanism:'FRAMESHIFT'};
const codingEdit=(id,start,end,status)=>({id,origin:'CODING_ALLELE',referenceEditStart0:start,referenceEditEnd0:end,status});
const bindPositions=(value,f,c)=>positionsApi.checkPositionProof(value,'escape',new Set(['SYNTHETIC_SOURCE']),f,c);
check('coding reference-event PASS must bind normalized and duplicate HGVS deletion spans',()=>{
    const c={editsTruncated:false,edits:[codingEdit('allele:SYNTHETIC',12,15,'APPLIED'),codingEdit('hgvs:SYNTHETIC',12,15,'DUPLICATE_SEQUENCE_EDIT')]};
    assert.doesNotThrow(()=>bindPositions(positionCondition([13,15,13,15]),codingPositionFacts,c));
    const wrong=positionCondition([250,260]);wrong.conditions[0].minimumInclusive=200;wrong.conditions[0].maximumExclusive=300;
    assert.throws(()=>bindPositions(wrong,codingPositionFacts,c),/PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID/);
});
check('coding insertion reference-event PASS preserves both genomic and HGVS flanks',()=>{
    const c={editsTruncated:false,edits:[codingEdit('allele:SYNTHETIC',12,12,'APPLIED'),codingEdit('hgvs:SYNTHETIC',15,15,'DUPLICATE_SEQUENCE_EDIT')]};
    assert.doesNotThrow(()=>bindPositions(positionCondition([12,13,15,16]),codingPositionFacts,c));
    for(const positions of [[13,13,16,16],[15,16,15,16],[12,13]])assert.throws(()=>bindPositions(positionCondition(positions),codingPositionFacts,c),/PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID/);
});
check('missing or truncated coding/HGVS public boundary witness cannot authorize a reference-event PASS',()=>{
    const c={editsTruncated:false,edits:[codingEdit('allele:SYNTHETIC',12,15,'APPLIED')]};
    assert.throws(()=>bindPositions(positionCondition([13,15,13,15]),codingPositionFacts,c),/PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED/);
    c.edits.push(codingEdit('hgvs:SYNTHETIC',12,15,'DUPLICATE_SEQUENCE_EDIT'));c.editsTruncated=true;
    assert.throws(()=>bindPositions(positionCondition([13,15,13,15]),codingPositionFacts,c),/PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED/);
});
check('splice reference-event scope binds the RNA deletion, not unrelated retained coding edits',()=>{
    const f={method:'SOURCE_BOUND_SPLICE_CONSEQUENCE'},c={editsTruncated:false,edits:[codingEdit('allele:SYNTHETIC',70,71,'APPLIED'),
        {id:'splice:SYNTHETIC',origin:'SPLICE_PROJECTION',referenceEditStart0:12,referenceEditEnd0:25,status:'APPLIED'}]};
    assert.doesNotThrow(()=>bindPositions(positionCondition([13,25]),f,c));
    assert.throws(()=>bindPositions(positionCondition([13,71]),f,c),/PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID/);
});
assert.deepEqual(fs.readFileSync(path.join(base,'round4/public-contract.json')),bytes);
console.log(`${count} escape public-proof checks passed: eight actual captured Java public rows, direct raw cap cross-checks, synthetic mutations; no new Java/clinical/page execution.`);
