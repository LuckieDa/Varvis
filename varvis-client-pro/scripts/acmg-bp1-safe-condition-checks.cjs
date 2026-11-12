// Saved public products and explicit format controls; no source issuance or analysis.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {createHash}=require('node:crypto'),{Module}=require('node:module'),{buildSync}=require('esbuild');
const project=path.resolve(__dirname,'..'),contractFile=process.argv[2],output=process.argv[3],currentFiles=process.argv.slice(4);
if(!contractFile||!output)throw Error('Supply frozen T05 consumer contract, result path, then central bound public products');
const bundle=buildSync({stdin:{contents:`export {inspectPublishedConditionCriterion} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPublishedConditionProof';
export {ps2IdentityHash,localBatchScopeIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails';`,resolveDir:project},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent'});
const mod=new Module(path.join(project,'scripts/bp1-safe-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(project,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.id);
const api=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const clone=v=>JSON.parse(JSON.stringify(v)),sha=b=>createHash('sha256').update(b).digest('hex'),read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const contract=read(contractFile),checks=[],pins=[],id='ACMG_AMP_2015:BP1';let template,pureTemplate;
const legacyCapFile=path.resolve(path.dirname(contractFile),'../closure-03-noLof/actual-java/business-01/MISSENSE_BP1/expected-private.json');
const legacyCapBytes=fs.readFileSync(legacyCapFile);
assert.equal(sha(legacyCapBytes),'7a53974f5904ab8e23e0dbeb60a49c9f94bbf30cb55093962674c4282497cdac');
const legacyCap=read(legacyCapFile).localBatch.modules.bp1.policy.truncatingConditionExecution;
assert.equal(legacyCap.canonicalSha256,'798e14a968877833a426b024523a5642bfa04ad8c8d0caffe19fa6132f67ee78');
assert.equal(api.ps2IdentityHash(Object.fromEntries(Object.entries(legacyCap).filter(([k])=>k!=='canonicalSha256'))),legacyCap.canonicalSha256);
pins.push({kind:'FROZEN_11_CAPABILITY_FORMAT_ONLY_NOT_CURRENT_AUTHORITY',file:legacyCapFile,sha256:sha(legacyCapBytes)});
function check(name,fn){fn();checks.push({name,status:'PASS'});}
function expected(run,candidate,attempt){return {analysisId:run.analysisId,executionSourceSha256:run.executionSourceSha256,
    candidateId:candidate.candidateId,hypothesisId:attempt.hypothesisId,context:attempt.context,executionProfile:run.executionProfile,enabledCriteria:run.enabledCriteria};}
const inspect=(c,e)=>api.inspectPublishedConditionCriterion(c,e,{identityHash:api.ps2IdentityHash});
const criterion=a=>a.localBatch?.criteria?.find(c=>c.criterionId===id);
function each(run,fn){for(const candidate of run.candidates)for(const [index,attempt]of candidate.attempts.entries()){const c=criterion(attempt);if(c)fn({run,candidate,index,attempt,c,e:expected(run,candidate,attempt)});}}
function card(x){return api.backgroundEvidenceCards(x.candidate,x.run.enabledCriteria,x.run,x.index).find(c=>c.id===id);}
for(const sample of contract.publicSamples){
    const file=sample.public.path,bytes=fs.readFileSync(file),run=JSON.parse(bytes);assert.equal(sha(bytes),sample.public.rawSha256);pins.push({kind:'T05_unbound_source_sample',file,sha256:sha(bytes)});
    each(run,x=>check('unbound source product cannot acquire current qualification '+sample.scenario+'/'+x.candidate.candidateId,()=>assert.equal(inspect(x.c,x.e).valid,false)));
}
for(const file of currentFiles){
    const bytes=fs.readFileSync(file),run=JSON.parse(bytes),before=JSON.stringify(run);pins.push({kind:'CENTRAL_BOUND_NORMAL_PRODUCT',file,sha256:sha(bytes)});
    each(run,x=>check('central current result '+path.basename(path.dirname(file))+'/'+x.candidate.candidateId,()=>{
        const verified=inspect(x.c,x.e);assert.equal(verified.valid,true,JSON.stringify(verified));assert.equal(api.localBatchScopeIssue(run),null);
        const cards=api.backgroundEvidenceCards(x.candidate,x.run.enabledCriteria,x.run,x.index);
        assert.equal(cards.length,x.run.enabledCriteria.length);
        assert.deepEqual(cards.map(c=>c.id).sort(),[...x.run.enabledCriteria].sort());
        for(const other of cards)for(const row of other.entries)assert.equal(Boolean(row.displayIssue),false,other.id+': '+row.summary);
        const c=card(x);assert.ok(c&&c.entries.length===1);const entry=c.entries[0],p=api.resultPresentation(entry),v=api.buildEvidenceDetailView(c,entry,x.attempt.context);
        assert.equal(entry.displayIssue,false);assert.equal(entry.evaluation.evaluationResult.status,x.c.status);assert.equal(entry.savedCriterion,x.c);
        const html=renderToStaticMarkup(React.createElement(api.Details,{card:c,entry,context:x.attempt.context}));
        assert.ok(!html.includes('noEstablishedLofQualification'));assert.ok(!html.includes('"sourceBinding":'));
        if(contract.reasonCodes[x.c.reasonCode]==='SOFTWARE_UNSUPPORTED'){
            assert.equal(p.outcome.kind,'UNSUPPORTED');assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(verified.view.conditionStatus,'SOFTWARE_UNSUPPORTED');
            assert.ok(html.includes('软件暂未支持'));assert.ok(!html.includes('缺少信息'));assert.ok(!html.includes('判定资料不足'));
            assert.ok(v.comparisons.some(r=>r.gap===''&&r.conclusion));assert.equal(v.missing,'');
            const supply=x.c.details.bp1Facts.truncatingConditionCheck.noEstablishedLofQualification;
            if(supply.sourceTransport?.status==='CHECKED')assert.ok(v.fields.some(r=>r.label==='规范原件读取'&&r.value==='已完成'));
            if(supply.reviewCoverage?.sameGeneOtherDiseasesChecked===false)assert.ok(v.fields.some(r=>r.label==='同基因其他疾病核查'&&r.value==='尚未完成'));
            for(const source of supply.sourceReviews||[])if(source.sourceBinding?.sourceUrl)assert.ok(v.sources.some(r=>r.url===source.sourceBinding.sourceUrl));
            template=x;
        }else if(verified.view?.conditionStatus==='NOT_APPLICABLE'){
            assert.equal(p.mainKind,'NOT_APPLICABLE');assert.equal(x.c.appliedStrength,'NONE');pureTemplate=x;
        }else if(x.c.status==='NOT_MET'){assert.equal(p.mainKind,'NOT_MET');assert.ok(v.fields.some(r=>r.label==='已建立关联的疾病'&&r.value));assert.ok(!v.needs);}
        assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);
    }));
}
if(template){
    const structuredResult=()=>{
        const c=clone(template.c),f=c.details.bp1Facts,b=f.currentBinding,q=f.truncatingConditionCheck;
        const x=template.e.context,evaluation=b.evaluationContextSha256;
        c.status=b.status=q.status='NOT_MET';
        c.reasonCode=b.reasonCode=q.reasonCode='BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION';
        q.available.geneLofDiseaseCorrelation='CONTRAINDICATION_ESTABLISHED';
        q.available.sourceCondition='NO_ESTABLISHED_LOF_DISEASE_CORRELATION_CONTRADICTED';
        q.missing=[];delete q.noEstablishedLofQualification;
        const digest='a'.repeat(64);
        const proof={methodVersion:'1.0.0',status:'VERIFIED_ESTABLISHED_LOF',contextId:b.evaluationContextId,contextSha256:evaluation,
            geneSymbol:x.gene.symbol,diseaseLabel:x.disease.label,entryId:'SYNTHETIC_FORMAT_CONTROL',
            modelSha256:b.observedSourcePins.modelCanonicalSha256,policySha256:digest,bundleSha256:digest,
            adoptionRawSha256:digest,sourceCandidatesRawSha256:digest,mechanismSourceSetSha256:digest,
            scope:clone(q.publishedSource.scope),mechanismSources:[{sourceId:'synthetic',sha256:digest,url:'https://example.test/structured-lof'}]};
        q.structuredLof=proof;b.observedSourcePins.structuredLofSha256=api.ps2IdentityHash(proof);
        return c;
    };
    check('synthetic format control: exact structured mechanism supports NOT_MET only',()=>{
        const c=structuredResult(),result=inspect(c,template.e);
        assert.equal(result.valid,true,JSON.stringify(result));
        assert.equal(c.appliedStrength,'NONE');
        const proof=c.details.bp1Facts.truncatingConditionCheck.structuredLof;
        proof.scope.mondoId='MONDO:9999999';
        c.details.bp1Facts.currentBinding.observedSourcePins.structuredLofSha256=api.ps2IdentityHash(proof);
        assert.equal(inspect(c,template.e).valid,false);
    });
    for(const [name,change]of [
        ['root source',e=>{e.executionSourceSha256='f'.repeat(64);}],
        ['candidate',e=>{e.candidateId+='-other';}],
        ['context',e=>{e.context.disease.mondoId='MONDO:9999999';}],
        ['module capability',e=>{delete e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution;}],
    ])check('independent caller mismatch '+name,()=>{const e=clone(template.e);change(e);assert.equal(inspect(template.c,e).valid,false);});
    for(const [name,change]of [
        ['template cannot become MET',c=>{c.status='MET';c.appliedStrength='SUPPORTING';c.details.bp1Facts.currentBinding.status='MET';c.details.bp1Facts.currentBinding.appliedStrength='SUPPORTING';}],
        ['absence qualification cannot become true',c=>{c.details.bp1Facts.truncatingConditionCheck.noEstablishedLofQualification.absenceQualificationGranted=true;}],
        ['other model source',c=>{c.details.bp1Facts.truncatingConditionCheck.noEstablishedLofQualification.useBinding.modelCanonicalSha256='f'.repeat(64);}],
        ['other module pointer',c=>{c.details.bp1Facts.currentBinding.modulePointer='/modules/pp2/results/0';}],
    ])check('reject malformed safe output '+name,()=>{const c=clone(template.c);change(c);assert.equal(inspect(c,template.e).valid,false);});
    for(const method of ['1.1.0','9.0.0'])check('unsupported capability '+method+' never upgrades a template',()=>{
        const e=clone(template.e),cap=e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution;
        cap.methodVersion=method;cap.canonicalSha256=api.ps2IdentityHash(Object.fromEntries(Object.entries(cap).filter(([k])=>k!=='canonicalSha256')));
        assert.equal(inspect(template.c,e).valid,false);
    });
    check('saved interpretation date is provenance, never a new daily expiry gate',()=>{
        const c=clone(template.c);c.details.bp1Facts.truncatingConditionCheck.noEstablishedLofQualification.evaluatedDate='2000-01-01';
        assert.equal(inspect(c,template.e).valid,true);
    });
    check('synthetic frozen 1.1 assessment remains unsupported even with exact original capability',()=>{
        const e=clone(template.e);e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution=clone(legacyCap);
        assert.equal(inspect(template.c,e).valid,false);
    });
    for(const code of Object.keys(contract.reasonCodes))check('synthetic format control: safe unsupported reason '+code,()=>{
        const c=clone(template.c),q=c.details.bp1Facts.truncatingConditionCheck;c.reasonCode=code;c.details.bp1Facts.currentBinding.reasonCode=code;
        q.reasonCode=code;q.noEstablishedLofQualification.reasonCode=code;q.missing.find(v=>v.conditionId==='GENE_LOF_DISEASE_CORRELATION').availableReasonCode=code;
        const result=inspect(c,template.e);assert.equal(result.valid,true,JSON.stringify(result));assert.equal(result.view.conditionStatus,'SOFTWARE_UNSUPPORTED');assert.equal(c.appliedStrength,'NONE');
    });
}
if(pureTemplate){
    check('synthetic frozen 1.1 pure-missense contract retains nonapplicability, never a truncation grant',()=>{
        const e=clone(pureTemplate.e);e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution=clone(legacyCap);
        const verified=inspect(pureTemplate.c,e);assert.equal(verified.valid,true,JSON.stringify(verified));assert.equal(verified.view.conditionStatus,'NOT_APPLICABLE');
        assert.equal(pureTemplate.c.status,'NOT_EVALUATED');assert.equal(pureTemplate.c.appliedStrength,'NONE');
    });
    check('synthetic frozen 1.1 ordinary and regional branches remain delegated to their existing scientific checks',()=>{
        const e=clone(pureTemplate.e);e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution=clone(legacyCap);
        for(const scope of ['PUBLISHED_SCOPE_PLUS_PROJECT_RESEARCH_RESTRICTION','PUBLISHED_REGION_AND_SPLICE_CONDITIONS']){
            const c=clone(pureTemplate.c);c.details.bp1Facts.scope=scope;delete c.details.bp1Facts.truncatingConditionCheck;
            const result=inspect(c,e);assert.equal(result.valid,true,JSON.stringify(result));assert.equal(result.handlesDecision,false);
            assert.equal(c.status,'NOT_EVALUATED');assert.equal(c.appliedStrength,'NONE');
        }
    });
    check('synthetic legacy capability rehash cannot change the frozen 1.1 grammar',()=>{
        const e=clone(pureTemplate.e),cap=clone(legacyCap);cap.noLofSourceGrammar='INVENTED';
        cap.canonicalSha256=api.ps2IdentityHash(Object.fromEntries(Object.entries(cap).filter(([k])=>k!=='canonicalSha256')));
        e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution=cap;assert.equal(inspect(pureTemplate.c,e).valid,false);
    });
    check('synthetic frozen 1.1 pure-missense may not acquire MET by changing the outer receipt',()=>{
        const e=clone(pureTemplate.e),c=clone(pureTemplate.c);e.executionProfile.moduleBindings.bp1.runtimeBinding.bp1TruncatingConditionExecution=clone(legacyCap);
        c.status='MET';c.appliedStrength='SUPPORTING';c.details.bp1Facts.currentBinding.status='MET';c.details.bp1Facts.currentBinding.appliedStrength='SUPPORTING';
        assert.equal(inspect(c,e).valid,false);
    });
}
const status=currentFiles.length?'PASS_CURRENT_BOUND_PRODUCTS':'SOURCE_AND_FORMAT_REGRESSION_ONLY_CENTRAL_PRODUCTS_PENDING';
if(currentFiles.length){assert.ok(template,'No actual central software-unsupported output supplied');assert.ok(pureTemplate,'No actual central pure-missense nonapplicable output supplied');}
fs.writeFileSync(output,JSON.stringify({status,checks:checks.length,results:checks,pins,centralFiles:currentFiles,clinicalQualification:false,
    authorityIssued:false,providerModelNetworkCalls:0,browser:'NOT_RUN'},null,2));
console.log(checks.length+' BP1 safe-consumer checks: '+status);
