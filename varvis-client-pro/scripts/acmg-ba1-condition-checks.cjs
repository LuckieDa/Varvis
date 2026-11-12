// Saved normal-business outputs with synthetic records/policy, not GN027 scientific adoption.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{Module}=require('node:module');
const {build}=require('esbuild');const project=path.resolve(__dirname,'..'),handoff=process.argv[2],currentProducts=process.argv[4],nativeBoundsProducts=process.argv[6];
if(!handoff)throw Error('Supply T03 private handoff root');
(async()=>{
const bundle=await build({stdin:{contents:`export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {workbenchEvidenceCopy} from './src/pages/varvis/ChromosomeVisualization/utils/acmgWorkbenchCopy';
export {localBatchScopeIssue,ps2IdentityHash} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {populationRuntimeBindingValid,populationStrataResultValid} from './src/pages/varvis/ChromosomeVisualization/utils/acmgSequencePopulation';
export {ba1SavedCardsForCheck} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {ba1ScopeAuditForCheck} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails';
`,resolveDir:project},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent',
plugins:[{name:'expose-existing-ba1-consumer-only-for-contract-check',setup(build){build.onLoad({filter:/[\\/]utils[\\/]acmgInput\.js$/},args=>({
    contents:fs.readFileSync(args.path,'utf8').replace('if (![LOCAL_BATCH_PROFILE, UNIFIED_PROFILE, ELEVEN_PROFILE, FOURTEEN_PROFILE].includes(p?.profileId)',
        `ba1ScopeAuditForCheck={profileId:p?.profileId,versionValid,bp7Valid,populationValid,extensionValid,publishedValid,pm4,
        populationVersion:population?.profileVersion,populationBindingValid:populationRuntimeBindingValid(p.bs1RuntimeBinding),
        samePopulationBindings:Object.fromEntries(['ba1RuntimeBinding','bs1RuntimeBinding','populationDataScope'].map(k=>[k,JSON.stringify(p[k])===JSON.stringify(population?.[k])])),
        expectedCriteria:batchCriteriaFor(value),criteria:p.enabledCriteria,context:p.contextPreparation,declaration:p.requiredDeclaration};
        if (![LOCAL_BATCH_PROFILE, UNIFIED_PROFILE, ELEVEN_PROFILE, FOURTEEN_PROFILE].includes(p?.profileId)`)
        +'\nexport {ordinaryEvidenceCards as ba1SavedCardsForCheck}; export let ba1ScopeAuditForCheck;',loader:'jsx'}));}}]});
const mod=new Module(path.join(project,'scripts/ba1-conditions-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(project,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.id);
const api=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const clone=v=>JSON.parse(JSON.stringify(v));let checks=0;
function check(name,fn){fn();checks++;console.log('PASS '+name);}
function current(run){
    for(const candidate of run.candidates)for(let i=0;i<candidate.attempts.length;i++){
        const attempt=candidate.attempts[i],result=candidate.ba1?.contextResults?.find(r=>r.contextId===attempt.ba1Context?.contextId);
        if(result?.selection?.status==='CONDITIONAL_SELECTED'){
            const card=api.ba1SavedCardsForCheck(candidate,['ACMG_AMP_2015:BA1'],attempt.ba1Context.contextId,String(i),run).find(c=>c.code==='BA1');
            assert.ok(card&&card.entries.length===1);return {candidate,attempt,result,card,entry:card.entries[0]};
        }
    }
    throw Error('No saved conditional background');
}
const runs=new Map();
for(const filename of fs.readdirSync(path.join(handoff,'public-contracts/examples')).filter(f=>f.startsWith('ba1-native-')&&f.endsWith('.json'))){
    const example=JSON.parse(fs.readFileSync(path.join(handoff,'public-contracts/examples',filename)));
    const file=path.join(handoff,example.sourceFile),bytes=fs.readFileSync(file),run=JSON.parse(bytes.toString('utf8'));
    runs.set(filename,run);
    check('actual saved conditional output retains final state: '+filename,()=>{
        const before=JSON.stringify(run),x=current(run),p=api.resultPresentation(x.entry),expected=x.result.evaluation.evaluationResult.status;
        assert.equal(x.entry.displayIssue,false,JSON.stringify({scope:api.localBatchScopeIssue(run),summary:x.entry.summary,reason:x.entry.evaluation}));
        assert.equal(x.entry.evaluation.evaluationResult.status,expected);
        assert.equal(p.mainKind,expected==='MET'?'ADOPTED_MET':expected==='NOT_MET'?'NOT_MET':'NO_CONCLUSION',JSON.stringify(p));
        const view=api.buildEvidenceDetailView(x.card,x.entry),html=renderToStaticMarkup(React.createElement(api.Details,{card:x.card,entry:x.entry,candidate:x.candidate}));
        assert.ok(view.sections.some(s=>s.title==='频率条件'));assert.ok(view.sections.some(s=>s.title==='人数条件'));
        assert.ok(html.includes('纯合或半合子人数'));assert.ok(!html.includes('ruleConditionEvaluation'));
        if(expected==='MET')assert.equal(api.workbenchEvidenceCopy(x.card,x.entry).summary,'本次频率或人数条件满足已采用的独立良性规则。');
        else assert.ok(!api.workbenchEvidenceCopy(x.card,x.entry).summary.includes('独立良性规则。'));
        assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);
    });
}
const positive=runs.get('ba1-native-count-met.json');
check('mismatched runtime, selection, source and individual qualification cannot masquerade as adopted conditions',()=>{
    for(const mutate of [
        r=>{r.executionProfile.ba1RuntimeBinding.conditionalConsumerVersion='not-the-saved-engine';},
        r=>{current(r).result.evaluation.ruleBinding.ba1SelectionId='different-selection';},
        r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.binding.source.resourceSha256='f'.repeat(64);},
        r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.status='UNRESOLVED';},
        r=>{current(r).result.evaluation.ruleConditionEvaluation.individualCountRequirement.value='1';},
        r=>{current(r).candidate.ba1.evidence.measurements[0].rawValue='unbound-carrier';},
    ]){
        const r=clone(positive);mutate(r);const x=current(r);assert.equal(x.entry.displayIssue,true);assert.notEqual(api.resultPresentation(x.entry).mainKind,'ADOPTED_MET');
    }
});
check('lower-bound below threshold remains UNKNOWN while a qualified positive lower bound or independent frequency can satisfy OR',()=>{
    const low=current(runs.get('ba1-native-below-count-threshold.json')).entry;
    assert.equal(low.ba1ConditionView.countTruth,'UNKNOWN');assert.equal(api.resultPresentation(low).mainKind,'NO_CONCLUSION');
    const homo=current(positive).entry;assert.equal(homo.ba1ConditionView.frequencyTruth,'FALSE');assert.equal(homo.ba1ConditionView.countTruth,'TRUE');
    const faf=current(runs.get('ba1-native-faf-met.json')).entry;assert.equal(faf.ba1ConditionView.frequencyTruth,'TRUE');assert.equal(faf.ba1ConditionView.countTruth,'UNKNOWN');
    assert.equal(api.resultPresentation(homo).mainKind,'ADOPTED_MET');assert.equal(api.resultPresentation(faf).mainKind,'ADOPTED_MET');
});
check('synthetic historical-shape control: old UNKNOWN diagnostics do not require new native-source fields',()=>{
    const r=clone(runs.get('ba1-native-faf-met.json')),x=current(r),c=x.result.evaluation.ruleConditionEvaluation;
    c.countSourceQualification={status:'UNRESOLVED'};delete c.observedHomozygoteStatistic;delete c.frequencyReadings;delete c.frequencyRequirement;delete c.individualCountRequirement;
    delete r.executionProfile.ba1RuntimeBinding.nativeIndividualCounts;
    // Re-seal this explicitly constructed shape control; no backend material or authority is issued.
    const e=x.result.evaluation;e.evaluationId='ba1-allele-evaluation-sha256-'+api.ps2IdentityHash(Object.fromEntries(Object.entries(e).filter(([k])=>!['evaluationId','conflict','review'].includes(k))));
    const result=current(r).entry;assert.equal(result.displayIssue,false);assert.equal(result.evaluation,x.result.evaluation);
    assert.equal(api.resultPresentation(result).mainKind,'ADOPTED_MET');
});
check('a valid native BA1 success omits a reason, but altered trace, allele or copied view cannot authorize adoption',()=>{
    const original=current(positive).entry;assert.ok(!Object.hasOwn(original.evaluation.evaluationResult,'reasonCode'));
    assert.equal(api.resultPresentation(original).mainKind,'ADOPTED_MET');
    for(const mutate of [
        e=>{e.evaluation.deterministicTrace.steps[0].outcome='FALSE';},
        e=>{e.evaluation.alleleId='allele-sha256-'+'f'.repeat(64);},
    ]){const e=clone(original);mutate(e);assert.equal(api.resultPresentation(e).mainKind,'NO_CONCLUSION');}
    const copied=clone(original);assert.equal(api.resultPresentation(copied).mainKind,'NO_CONCLUSION');
});
check('blocked publication or tampered result association never borrows a conditional success',()=>{
    const r=clone(positive),x=current(r);x.result.selection.status='BLOCKED';
    const card=api.ba1SavedCardsForCheck(x.candidate,['ACMG_AMP_2015:BA1'],x.attempt.ba1Context.contextId,'0',r).find(c=>c.code==='BA1');assert.notEqual(api.resultPresentation(card.entries[0]).mainKind,'ADOPTED_MET');
    const broken=clone(positive),a=current(broken);a.attempt.ba1ResultSelection.effectiveResultPath='/ba1';
    const b=api.ba1SavedCardsForCheck(a.candidate,['ACMG_AMP_2015:BA1'],a.attempt.ba1Context.contextId,'0',broken).find(c=>c.code==='BA1');assert.ok(!b.entries[0].ba1ConditionView);assert.notEqual(api.resultPresentation(b.entries[0]).mainKind,'ADOPTED_MET');
    // Constructed final-refusal control: source qualification / a TRUE arm is not adoption.
    const refused=clone(positive),original=current(refused).result.evaluation;
    original.evaluationResult={status:'NOT_EVALUATED',reasonCode:'BA1_CONDITIONAL_REQUIRED_FACTS_UNRESOLVED'};original.appliedStrength='NONE';
    original.deterministicTrace.executionStatus='NOT_RUN';delete original.deterministicTrace.traceSha256;
    original.evaluationId='ba1-allele-evaluation-sha256-'+api.ps2IdentityHash(Object.fromEntries(Object.entries(original).filter(([k])=>!['evaluationId','conflict','review'].includes(k))));
    const no=current(refused);assert.equal(no.entry.ba1ConditionView.countTruth,'TRUE');assert.equal(api.resultPresentation(no.entry).mainKind,'NO_CONCLUSION');
    assert.ok(!api.workbenchEvidenceCopy(no.card,no.entry).summary.includes('独立良性规则'));
});
const legacyFile=process.argv[3];if(legacyFile&&legacyFile!=='-')check('legacy scalar histories keep their old evaluations and no conditional material is invented',()=>{
    const bytes=fs.readFileSync(legacyFile),run=JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,'')),before=JSON.stringify(run);let rows=0;
    for(const candidate of run.candidates)for(let i=0;i<candidate.attempts.length;i++){
        const card=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,i).find(c=>c.code==='BA1');if(!card)continue;
        for(const entry of card.entries){assert.ok(!entry.ba1ConditionView);rows++;}
    }
    assert.ok(rows>0);assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(legacyFile),bytes);
});
if(currentProducts)for(const name of fs.readdirSync(currentProducts).filter(n=>!n.endsWith('fresh-jvm')&&fs.existsSync(path.join(currentProducts,n,'public.json')))){
    check('current complete public workbench route: '+name,()=>{
        const file=path.join(currentProducts,name,'public.json'),bytes=fs.readFileSync(file),r=JSON.parse(bytes),x=current(r);
        const issue=api.localBatchScopeIssue(r);assert.equal(issue,null,JSON.stringify({issue,audit:api.ba1ScopeAuditForCheck}));
        const cards=api.backgroundEvidenceCards(x.candidate,r.enabledCriteria,r,x.candidate.attempts.indexOf(x.attempt));
        const card=cards.find(c=>c.code==='BA1');assert.ok(card);assert.equal(card.entries.length,1);
        assert.equal(api.resultPresentation(card.entries[0]).mainKind,'ADOPTED_MET',JSON.stringify(card.entries[0]));
        assert.ok(card.entries[0].ba1ConditionView);assert.deepEqual(fs.readFileSync(file),bytes);
        if(r.enabledCriteria.includes('ACMG_AMP_2015:BS1')){
            const bs1=cards.find(c=>c.code==='BS1');assert.ok(bs1);const e=bs1.entries[0];
            assert.equal(e.displayIssue,undefined,JSON.stringify(e));assert.equal(api.populationStrataResultValid(x.attempt.bs1,x.attempt.bs1.context,r.executionProfile.bs1RuntimeBinding),true);
            assert.equal(api.resultPresentation(e).mainKind,'ADOPTED_MET',JSON.stringify(api.resultPresentation(e)));
            const view=api.buildEvidenceDetailView(bs1,e),html=renderToStaticMarkup(React.createElement(api.Details,{card:bs1,entry:e}));
            assert.ok(view.sections.some(s=>s.title==='人群 nfe · XY'&&s.rows.some(row=>row.value.includes('已排除'))));
            assert.ok(html.includes('AC &gt; 1'));assert.ok(html.includes('本次采用的最高合格频率'));
        }
    });
}
if(currentProducts){
    const r=JSON.parse(fs.readFileSync(path.join(currentProducts,'conditional-native-faf-met-none-pair/public.json'))),b=r.executionProfile.bs1RuntimeBinding,raw=r.candidates[0].attempts[0].bs1;
    check('saved v2 runtime requires known and matching method sets without replacing pinned source contracts',()=>{
        assert.equal(api.populationRuntimeBindingValid(b),true);
        const mixed=clone(b),versions=['bs1-joint-sex-stratified-frequency-v1','bs1-joint-sex-stratified-frequency-v2'];
        for(const value of [mixed,mixed.knowledgeIdentity]){value.jointSexChromosomeStatisticVersion='MIXED';value.jointSexChromosomeStatisticVersions=versions;
            value.sexChromosomeStatisticVersion='MIXED';value.sexChromosomeStatisticVersions=versions;}
        mixed.jointSexStatisticVersion='MIXED';assert.equal(api.populationRuntimeBindingValid(mixed),true);
        for(const mutate of [
            v=>{v.jointSexStatisticVersion='bs1-joint-sex-stratified-frequency-v3';},
            v=>{v.knowledgeIdentity.jointSexChromosomeStatisticVersion='bs1-joint-sex-stratified-frequency-v1';},
            v=>{v.jointSexChromosomeStatisticVersions=['bs1-joint-sex-stratified-frequency-v1'];},
            v=>{v.knowledgeIdentity.sexChromosomeStatisticVersions=[];},
            v=>{v.jointSexHeaderSha256='f'.repeat(64);},
        ]){const value=clone(b);mutate(value);assert.equal(api.populationRuntimeBindingValid(value),false);}
    });
    check('v2 result binds eligible AC, exact method and per-population exclusion to the saved policy',()=>{
        assert.equal(api.populationStrataResultValid(raw,raw.context,b),true);
        for(const mutate of [
            v=>{v.qualityDecision.adapterVersion='bs1-joint-sex-stratified-frequency-v1';},
            v=>{v.qualityDecision.frequencySelection.version='bs1-joint-sex-stratified-frequency-v3';},
            v=>{v.qualityDecision.frequencySelection.populations.find(p=>p.status==='EXCLUDED').reasonCode='BS1_SEX_AN_INSUFFICIENT';},
            v=>{delete v.preparation.policy.statistic.eligibleAc;},
        ]){const value=clone(raw);mutate(value);assert.equal(api.populationStrataResultValid(value,value.context,b),false);}
    });
    check('PM3 requirements separate pure homozygous observations; expert BP1 truncation uses its saved branch',()=>{
        const make=(code,facts)=>({savedCriterion:{criterionId:'ACMG_AMP_2015:'+code,status:'NOT_EVALUATED',details:{[code==='BP1'?'bp1Facts':'pm3Facts']:facts}},evaluation:{criterionId:'ACMG_AMP_2015:'+code,evaluationResult:{status:'NOT_EVALUATED',reasonCode:'LOCAL_BATCH_CRITERION_NOT_EVALUATED'},appliedStrength:'NONE'}});
        const pm3=api.buildEvidenceDetailView({code:'PM3',id:'ACMG_AMP_2015:PM3'},make('PM3',{}));assert.ok(pm3.requirement.includes('纯合观察按适用规范单独核查'));
        const bp1=api.buildEvidenceDetailView({code:'BP1',id:'ACMG_AMP_2015:BP1'},make('BP1',{truncatingConditionCheck:{status:'CONDITION_EVALUATED'}}));assert.ok(bp1.requirement.includes('专家规范核查截短变异条件'));
    });
}
const v1File=process.argv[5];if(v1File&&v1File!=='-')check('actual saved v1 population identity and results retain their original method',()=>{
    const bytes=fs.readFileSync(v1File),r=JSON.parse(bytes),b=r.executionProfile.bs1RuntimeBinding;assert.equal(api.populationRuntimeBindingValid(b),true);
    let n=0;for(const c of r.candidates)for(const a of c.attempts){const v=a.bs1;if(!['MET','NOT_MET'].includes(v?.evaluation?.evaluationResult?.status))continue;
        assert.equal(api.populationStrataResultValid(v,v.context,b),true);n++;}
    assert.ok(n>0);assert.deepEqual(fs.readFileSync(v1File),bytes);
});
if(nativeBoundsProducts){
    const nativeRuns=new Map();
    for(const name of fs.readdirSync(nativeBoundsProducts).filter(n=>n.startsWith('conditional-native-')&&!n.endsWith('fresh-jvm')&&fs.existsSync(path.join(nativeBoundsProducts,n,'public.json')))){
        const file=path.join(nativeBoundsProducts,name,'public.json'),bytes=fs.readFileSync(file),r=JSON.parse(bytes);
        if(r.executionProfile?.ba1RuntimeBinding?.nativeIndividualCounts?.version!=='2.0.0')continue;
        nativeRuns.set(name,r);
        check('native2 normal complete workbench keeps backend decision and bounded facts: '+name,()=>{
            const before=JSON.stringify(r),x=current(r),cards=api.backgroundEvidenceCards(x.candidate,r.enabledCriteria,r,x.candidate.attempts.indexOf(x.attempt));
            const card=cards.find(c=>c.code==='BA1'),entry=card?.entries[0],expected=x.result.evaluation.evaluationResult.status;
            assert.ok(entry&&!entry.displayIssue,JSON.stringify(entry));assert.equal(entry.evaluation,x.result.evaluation);
            assert.equal(api.resultPresentation(entry).mainKind,expected==='MET'?'ADOPTED_MET':expected==='NOT_MET'?'NOT_MET':'NO_CONCLUSION');
            const view=api.buildEvidenceDetailView(card,entry),rows=view.sections.find(s=>s.title==='人数条件')?.rows;
            assert.ok(rows);const bounds=entry.ba1ConditionView.countSourceQualification?.additionalNativeBounds;
            if(bounds?.hemizygoteLowerBound?.status==='QUALIFIED_LOWER_BOUND')assert.ok(rows.some(row=>row.label==='已核实半合子人数下限'&&row.value===`≥ ${bounds.hemizygoteLowerBound.value} 人（XY，非 PAR）`));
            if(bounds?.personUpperBound?.status==='QUALIFIED_UPPER_BOUND')assert.ok(rows.some(row=>row.label==='人数上界'&&row.value===`≤ ${bounds.personUpperBound.value} 人`));
            if(name.includes('raw_unknown_bound')){assert.equal(entry.ba1ConditionView.countTruth,'UNKNOWN');assert.equal(api.resultPresentation(entry).mainKind,'NO_CONCLUSION');}
            if(name.includes('below_ten'))assert.equal(entry.ba1ConditionView.countTruth,'UNKNOWN');
            if(name.includes('raw_bound-')){assert.equal(entry.ba1ConditionView.countTruth,'FALSE');assert.equal(api.resultPresentation(entry).mainKind,'NOT_MET');}
            assert.equal(JSON.stringify(r),before);assert.deepEqual(fs.readFileSync(file),bytes);
        });
    }
    assert.ok(nativeRuns.size>0,'Require actual saved native2 normal outputs, not fabricated positive fixtures');
    const source=[...nativeRuns.values()].find(r=>current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification?.status==='QUALIFIED_BOUNDS');
    assert.ok(source,'Require normal saved lower and upper bound material');
    const reseal=r=>{
        const e=current(r).result.evaluation,m=e.ruleConditionEvaluation.countSourceQualification,t=e.deterministicTrace;
        m.materialId='ba1-native-count-material-sha256-'+api.ps2IdentityHash(Object.fromEntries(Object.entries(m).filter(([k])=>k!=='materialId')));
        const p=t.parameters.find(p=>p.name==='ba1.condition.nativeCountMaterialId');p.value=m.materialId;
        if(Object.hasOwn(t,'traceSha256'))t.traceSha256=api.ps2IdentityHash(Object.fromEntries(Object.entries(t).filter(([k])=>k!=='traceSha256')));
        e.evaluationId='ba1-allele-evaluation-sha256-'+api.ps2IdentityHash(Object.fromEntries(Object.entries(e).filter(([k])=>!['evaluationId','conflict','review'].includes(k))));
    };
    for(const [name,mutate]of [
        ['future scope with internally matching identity',r=>{const m=current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification,n=r.executionProfile.ba1RuntimeBinding.nativeIndividualCounts;
            n.qualificationScope='FUTURE_UNREVIEWED_COUNT_SCOPE';m.qualificationScope=n.qualificationScope;m.sourceContract=clone(n);m.binding.supplyIdentitySha256=api.ps2IdentityHash(n);}],
        ['future version with internally matching identity',r=>{const m=current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification,n=r.executionProfile.ba1RuntimeBinding.nativeIndividualCounts;
            n.version='2.1.0';m.version=n.version;m.sourceContract=clone(n);m.binding.supplyIdentitySha256=api.ps2IdentityHash(n);}],
        ['changed original source pins even when JSON resealed',r=>{const m=current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification,n=r.executionProfile.ba1RuntimeBinding.nativeIndividualCounts;
            n.sources[0].sha256='f'.repeat(64);m.sourceContract=clone(n);m.binding.supplyIdentitySha256=api.ps2IdentityHash(n);}],
        ['raw source mismatch',r=>{current(r).candidate.ba1.rawPopulationRecord+=';unbound_test_field=1';}],
        ['wrong raw hash',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.binding.rawRecordSha256='f'.repeat(64);}],
        ['wrong region source pin',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.binding.region.sourceCatalogSha256='f'.repeat(64);}],
        ['PAR label cannot qualify NON_PAR hemi',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.binding.region.region='PAR';}],
        ['upper bound cannot claim exact people',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.additionalNativeBounds.personUpperBound.exactPersonCount=true;}],
        ['partial count cannot claim complete coverage',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.countScopeCompleteness='COMPLETE';}],
        ['altered upper value without matching raw statistic',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.additionalNativeBounds.personUpperBound.value='1';}],
        ['upper bound in allele units',r=>{current(r).result.evaluation.ruleConditionEvaluation.countSourceQualification.additionalNativeBounds.personUpperBound.countUnit='alleles';}],
        ['homozygote display in wrong metric units',r=>{current(r).result.evaluation.ruleConditionEvaluation.observedHomozygoteStatistic.unit='alleles';}],
    ])check('native2 rejects '+name,()=>{
        const r=clone(source);mutate(r);reseal(r);const x=current(r);
        assert.equal(x.entry.displayIssue,true);assert.notEqual(api.resultPresentation(x.entry).mainKind,'ADOPTED_MET');
    });
}
console.log(checks+' T03 condition/public workbench checks passed. Older frozen products are checked at the BA1 consumer layer; current supplied products use the full saved-workbench entry. This does not claim GN027 adoption or browser acceptance.');
})().catch(error=>{console.error(error);process.exitCode=1;});
