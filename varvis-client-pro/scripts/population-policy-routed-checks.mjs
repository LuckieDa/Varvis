// Actual captured normal Java/public business JSON from committed 469147aa.
// Inputs use synthetic policies/minireferences; no Java, provider or new source execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgSequencePopulation.js',import.meta.url),'utf8');
const h=await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const native=process.env.VARVIS_NATIVE_ROOT;
if(!native)throw Error('Set VARVIS_NATIVE_ROOT to reuse existing captured artifacts read-only');
const directory=path.join(native,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a10/round3/public-samples-02');
const hash=b=>createHash('sha256').update(b).digest('hex');
const canonical=v=>typeof v==='string'?v.normalize('NFC'):Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k.normalize('NFC'),canonical(v[k])])):v;
const identity=v=>hash(JSON.stringify(canonical(v)));
let count=0,actual=0,completed=0,blocked=0;const captures=[];
const check=(name,run)=>{run();count++;console.log('PASS '+name);};
for(const name of fs.readdirSync(directory).sort()){
    const scenario=path.join(directory,name),manifestFile=path.join(scenario,'manifest.json');
    const manifest=JSON.parse(fs.readFileSync(manifestFile));
    for(const filename of fs.readdirSync(scenario).filter(f=>/^\d+-public\.json$/.test(f)).sort()){
        const file=path.join(scenario,filename),bytes=fs.readFileSync(file),document=JSON.parse(bytes),before=JSON.stringify(document);
        check(`actual Java public ${name}/${filename} contract and immutable source`,()=>{
            assert.equal(h.populationRuntimeBindingValid(document.executionProfile.bs1RuntimeBinding),true,'actual runtime binding');
            assert.equal(manifest.dataClass,'SYNTHETIC');assert.equal(manifest.scope,'ACTUAL_ISOLATED_BUSINESS_RESPONSE_NOT_USER_DOWNLOAD');
            const record=manifest.records.find(r=>r.prefix===filename.split('public.json')[0]);assert.equal(record.responseSha256,hash(bytes));
            for(const candidate of document.candidates)for(const attempt of candidate.attempts){
                const b=attempt.bs1;if(!b)continue;actual++;captures.push({file,document,candidate,attempt,b});
                assert.equal(h.populationStrataClaim(b),true);
                const status=b.evaluation?.evaluationResult?.status;
                if(['MET','NOT_MET'].includes(status)){completed++;assert.equal(h.populationSourceRoutingValid(b),true,'routed source must agree');assert.equal(h.populationStrataResultValid(b,b.context,document.executionProfile.bs1RuntimeBinding),true,`${name}/${filename} ${b.dataScope?.dataset} ${status}`);}
                else{blocked++;assert.equal(status,'NOT_EVALUATED');assert.equal(h.populationStrataResultValid(b,b.context),false,'no positive/negative claim may be fabricated');}
                const details=h.populationStrataDetails(b),text=JSON.stringify(details);
                assert.ok(text.includes(b.sourceRouting.dataset));assert.ok(text.includes('不能改称患者性别或患者倍性'));
                assert.ok(details.some(([label])=>label==='事实复用与独立评价'));
                if(b.sourceRouting.dataset==='JOINT')assert.ok(text.includes('不本地累加EXOMES和GENOMES'));
            }
            assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(file),bytes);
        });
        console.log(`FROZEN ${name}/${filename} ${hash(bytes)} ${manifest.scenario}`);
    }
}
assert.equal(captures.length,17);assert.equal(completed,13);assert.equal(blocked,4);
const joint=captures.find(v=>v.b.dataScope?.dataset==='JOINT'&&v.b.evaluation.evaluationResult.status==='MET').b;
const exomes=captures.find(v=>v.b.dataScope?.dataset==='EXOMES'&&v.b.evaluation.evaluationResult.status==='MET').b;
const mutations=[
    ['dataset relabel',v=>v.dataScope.dataset='EXOMES'],
    ['AF relabel to FAF',v=>v.preparation.policy.statistic.metricId='gnomad.joint.sex_ancestry_max_faf95'],
    ['cross dataset measurement',v=>v.evidence.measurements.push({metricId:'gnomad.exomes.faf95max',rawValue:'1'})],
    ['public evidence profile conflict',v=>v.evidence.measurements.find(m=>m.metricId==='gnomad.inputProfileSha256').rawValue='f'.repeat(64)],
    ['public query status conflict',v=>v.evidence.queryOutcome.status='NO_RECORD'],
    ['preparation policy digest',v=>v.preparation.policySha256='f'.repeat(64)],
    ['profile substitution',v=>v.observation.profileSha256='bce4c90293c0bd5028d5d240a9bdabea9bf4118e27adf2af245f61d98c8e7a1d'],
    ['source dataset substitution',v=>v.sourceRouting.effectiveSource.dataset='EXOMES'],
    ['source configuration mismatch',v=>v.sourceRouting.effectiveSource.configurationSha256='f'.repeat(64)],
    ['selected policy mismatch',v=>v.sourceRouting.policySha256='f'.repeat(64)],
    ['resource mismatch',v=>v.sourceRouting.resourceContract.identityFingerprintSha256='f'.repeat(64)],
    ['no route metadata',v=>delete v.sourceRouting],
    ['source disabled',v=>v.sourceRouting.effectiveSource.populationInputEnabled=false],
    ['source execution failed',v=>v.observation.carrier.resourceExecution.status='FAILED'],
    ['joint quality inner blocked',v=>v.qualityDecision.sourceQualityDecision.status='BLOCKED'],
    ['joint source author unbound',v=>v.qualityDecision.sourceQualityDecision.filterCondition.sourceContract.sourceAuthor='different'],
    ['joint disagreement',v=>v.qualityDecision.sourceQualityDecision.discordance.signal=true],
    ['different selected karyotype',v=>v.qualityDecision.frequencySelection.selectedKaryotype='XX'],
    ['same-source counts conflict',v=>v.qualityDecision.facts.AC='999'],
    ['new strength mismatch',v=>v.evaluation.appliedStrength='SUPPORTING'],
    ['same allele wrong background',v=>v.context.disease.mondoId='MONDO:0999999'],
];
for(const [name,mutate]of mutations)check('synthetic mutation rejects '+name,()=>{
    const b=structuredClone(joint);mutate(b);assert.equal(h.populationStrataResultValid(b,b.context),false);assert.doesNotThrow(()=>h.populationStrataDetails(b));
});
check('EXOMES routed facts cannot acquire a JOINT label or metric',()=>{
    for(const mutate of [v=>v.sourceRouting.dataset='JOINT',v=>v.preparation.policy.statistic.metricId='gnomad.joint.sex_ancestry_max_af',v=>v.sourceRouting.profileSha256='626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3']){
        const b=structuredClone(exomes);mutate(b);assert.equal(h.populationStrataResultValid(b,b.context),false);
    }
});
check('resealed selected numerator/denominator still must match immutable native raw fields',()=>{
    const b=structuredClone(joint);b.qualityDecision.facts.AC='22';b.evaluation.deterministicTrace.parameters.find(p=>p.name==='qualitySha256').value=identity(b.qualityDecision);
    const trace=b.evaluation.deterministicTrace;trace.traceSha256=identity(Object.fromEntries(Object.entries(trace).filter(([key])=>key!=='traceSha256')));
    assert.equal(h.populationStrataResultValid(b,b.context),false);
});
check('ordinary AF and FAF95 remain separate concepts in details',()=>{
    const text=JSON.stringify(h.populationStrataDetails(joint));assert.ok(text.includes('普通 AF 路径不借用 FAF95'));
    assert.ok(text.includes('精确目标 AC / AN'));
});
check('runtime identity must retain activation, pins, source order and canonical binding',()=>{
    const b=captures[0].document.executionProfile.bs1RuntimeBinding;
    for(const mutate of [v=>v.formalEnabled=false,v=>v.activationContract='LOCAL_EXOMES_BUSINESS_V1',v=>v.observationVersion='UNKNOWN',
        v=>v.sourceRouting.componentSummation=true,v=>v.sourceRouting.sources[0].profileSha256=v.sourceRouting.sources[1].profileSha256,
        v=>v.sourceRouting.sources.reverse(),v=>v.knowledgeIdentity.jointSexHeaderSha256='f'.repeat(64),v=>v.jointSexFilterSourceSha256='f'.repeat(64)]){
        const v=structuredClone(b);mutate(v);v.sourceRouting.bindingSha256=identity(Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>key!=='bindingSha256')));
        assert.equal(h.populationRuntimeBindingValid(v),false);
    }
});
check('same policy source configuration from a different run cannot bind even after self-resealing',()=>{
    const capture=captures.find(v=>v.b===joint),binding=structuredClone(capture.document.executionProfile.bs1RuntimeBinding);
    const v=structuredClone(joint);v.sourceRouting.effectiveSource.configurationSha256='f'.repeat(64);
    v.sourceRouting.executionRouteId=identity(Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>key!=='executionRouteId')));
    v.sourceRouting.executionRouteId='population-execution-route-sha256-'+v.sourceRouting.executionRouteId;
    assert.equal(h.populationStrataResultValid(v,v.context),true,'self-consistent route alone cannot identify current runtime');
    assert.equal(h.populationStrataResultValid(v,v.context,binding),false,'current saved profile must match consumed configuration');
});
check('self-resealed resource contract cannot retain a fingerprint for changed source URI',()=>{
    const v=structuredClone(joint);
    v.sourceRouting.resourceContract.identity.dataUriTemplate='https://example.invalid/changed';
    v.observation.resourceContract=structuredClone(v.sourceRouting.resourceContract);
    const routePlan=Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>!['routeId','effectiveSource','executionRouteId'].includes(key)));
    v.sourceRouting.routeId='population-route-sha256-'+identity(routePlan);
    v.sourceRouting.executionRouteId='population-execution-route-sha256-'+identity(Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>key!=='executionRouteId')));
    assert.equal(h.populationSourceRoutingValid(v),false);
});
check('JOINT FAF95 independent synthetic negative control never substitutes ordinary AF',()=>{
    // A copied SYNTHETIC arithmetic fixture only, not newly approved knowledge.
    const v=structuredClone(joint),p=v.preparation.policy,r=p.knowledgeRecord,q=v.qualityDecision,trace=v.evaluation.deterministicTrace;
    for(const statistic of [p.statistic,r.expertPolicy.statistic])Object.assign(statistic,{metricId:'gnomad.joint.sex_ancestry_max_faf95',confidence:'95_PERCENT_LOWER_BOUND',sourceTerm:'FAF95',interpretation:'TARGET_ALT_FAF95'});
    for(const tiers of [p.tiers,r.expertPolicy.tiers])for(const tier of tiers)for(const c of tier.conditions)if(c.fact==='AF')c.fact='FAF95';
    q.frequencySelection.statistic=structuredClone(p.statistic);q.frequencySelection.comparisonBasis='SOURCE_STRATUM_FAF95';q.facts.FAF95='0.0015';
    r.qualification.payloadSha256=identity(Object.fromEntries(Object.entries(r).filter(([key])=>!['review','qualification'].includes(key))));
    r.qualification.priorReview.contentSha256=r.qualification.payloadSha256;r.review.contentSha256=identity(Object.fromEntries(Object.entries(r).filter(([key])=>key!=='review')));
    const policySha=identity(p);v.preparation.policySha256=policySha;v.sourceRouting.metricId=p.statistic.metricId;v.sourceRouting.policySha256=policySha;
    const routePlan=Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>!['routeId','effectiveSource','executionRouteId'].includes(key)));
    v.sourceRouting.routeId='population-route-sha256-'+identity(routePlan);
    v.sourceRouting.executionRouteId='population-execution-route-sha256-'+identity(Object.fromEntries(Object.entries(v.sourceRouting).filter(([key])=>key!=='executionRouteId')));
    for(const parameter of trace.parameters){if(parameter.name==='policySha256')parameter.value=policySha;if(parameter.name==='qualitySha256')parameter.value=identity(q);if(parameter.name.includes('.AF.'))parameter.name=parameter.name.replace('.AF.','.FAF95.');}
    for(const step of trace.steps)step.outcome='FALSE';v.evaluation.evaluationResult.status='NOT_MET';v.evaluation.appliedStrength='NONE';
    trace.traceSha256=identity(Object.fromEntries(Object.entries(trace).filter(([key])=>key!=='traceSha256')));
    assert.equal(h.populationStrataResultValid(v,v.context),true,'raw FAF95 0.0015 is below independent threshold 0.002');
    v.evaluation.evaluationResult.status='MET';v.evaluation.appliedStrength='STRONG';assert.equal(h.populationStrataResultValid(v,v.context),false);
});
console.log(`${count} population policy-selected checks passed; ${actual} actual captured rows (${completed} evaluated, ${blocked} explicit NOT_EVALUATED). Captures use SYNTHETIC materials; mutations are not new Java/source executions.`);
export {captures};
