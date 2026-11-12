// Official Table 5 / ClinGen Appendix III examples and actual CSpec rule sets.
// These are combination-engine tests, not clinical validation of the criteria inputs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname,'..');
const bundle = buildSync({stdin:{contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgClassification';
export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgEvidenceScore';`,resolveDir:project,loader:'js'},
    bundle:true,platform:'node',format:'cjs',write:false,loader:{'.js':'jsx'}});
const loaded=new Module(path.join(project,'scripts','classification-checks-bundle.cjs'));
loaded.filename=loaded.id; loaded.paths=[path.join(project,'node_modules')]; loaded._compile(bundle.outputFiles[0].text,loaded.filename);
const api=loaded.exports;
const catalog=JSON.parse(fs.readFileSync(path.join(project,'src/pages/varvis/ChromosomeVisualization/utils/acmgClassificationCatalog.json')));
let count=0;
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const item=(code,strength)=>({code,strength,standalone:code==='BA1',points:null});
const score=items=>({status:items.some(i=>i.standalone)?'STANDALONE':'SCORED',total:items.some(i=>i.standalone)?null:0,items,pendingCount:0});
const classify=(items,rule)=>api.classifyAdoptedEvidence(score(items),rule);
const pvs=()=>item('PVS1','VERY_STRONG'), ps=()=>item('PS3','STRONG'), pm=(code='PM1')=>item(code,'MODERATE');
const pp=(code='PP1')=>item(code,'SUPPORTING'), bs=(code='BS1')=>item(code,'STRONG'), bp=(code='BP2')=>item(code,'SUPPORTING');
const context=(gene='GENE',hgncId='HGNC:999999',disease='MONDO:9999999',moi='AUTOSOMAL_DOMINANT')=>({
    scope:{analysisDomain:'GERMLINE',diseaseModel:'MENDELIAN',variantScope:'SMALL_SEQUENCE_VARIANT'},
    gene:{symbol:gene,hgncId},disease:{mondoId:disease},moi,
});
const fromScope=scope=>context(scope.gene,scope.hgncId,scope.disease,scope.inheritance[0]);

for (const [name,items,expected] of [
    ['VS + S',[pvs(),ps()],'P'], ['VS + 2M',[pvs(),pm(),pm('PM3')],'P'],
    ['VS + M + supporting',[pvs(),pm(),pp()],'P'], ['VS + 2supporting',[pvs(),pp(),pp('PP4')],'P'],
    ['2S',[ps(),item('PS2','STRONG')],'P'], ['S + 3M',[ps(),pm(),pm('PM3'),pm('PM5')],'P'],
    ['S + 2M + 2supporting',[ps(),pm(),pm('PM3'),pp(),pp('PP4')],'P'],
    ['S + M + 4supporting',[ps(),pm(),pp(),pp('PP2'),pp('PP3'),pp('PP4')],'P'],
    ['VS + M',[pvs(),pm()],'LP'], ['VS + supporting (ClinGen)',[pvs(),pp('PM2')],'LP'],
    ['S + M',[ps(),pm()],'LP'], ['S + 2M',[ps(),pm(),pm('PM3')],'LP'],
    ['S + 2supporting',[ps(),pp(),pp('PP4')],'LP'], ['3M',[pm(),pm('PM3'),pm('PM5')],'LP'],
    ['2M + 2supporting',[pm(),pm('PM3'),pp(),pp('PP4')],'LP'],
    ['M + 4supporting',[pm(),pp(),pp('PP2'),pp('PP3'),pp('PP4')],'LP'],
    ['standalone BA1',[item('BA1','STAND_ALONE')],'B'], ['2 benign strong',[bs(),bs('BS3')],'B'],
    ['benign strong + supporting',[bs(),bp()],'LB'], ['2 benign supporting',[bp(),bp('BP7')],'LB'],
    ['single VS',[pvs()],'VUS'], ['single benign strong',[bs()],'VUS'], ['single benign supporting',[bp()],'VUS'],
    ['actual downgraded PVS1',[item('PVS1','MODERATE'),pp()],'VUS'],
    ['opposing adopted evidence',[ps(),pm(),bs()],'VUS'],
]) check('generic published combination: '+name,()=>assert.equal(classify(items).code,expected));

check('generic conflict is explicit, not a subtraction leading to P or B',()=>{
    const result=classify([pvs(),ps(),bs()]);assert.equal(result.code,'VUS');assert.equal(result.conflict,true);
});
check('stale numeric totals never override actual evidence combinations',()=>{
    assert.equal(api.classifyAdoptedEvidence({...score([pm()]),total:100}).code,'VUS');
    assert.equal(api.classifyAdoptedEvidence({...score([pm()]),total:NaN}).code,null);
});
check('no evidence, source conflicts and obsolete reputable-source codes do not classify',()=>{
    for (const value of [null,score([]),{...score([pvs(),ps()]),status:'REVIEW'},score([pp('PP5')]),score([bp('BP6')])])
        assert.equal(api.classifyAdoptedEvidence(value).code,null);
});
check('unresolved evidence retains a provisional classification',()=>{
    const result=api.classifyAdoptedEvidence({...score([ps(),pm()]),pendingCount:1});assert.equal(result.code,'LP');assert.equal(result.provisional,true);
});
check('known source inventory is pinned, reproducible and contains published nuclear rules',()=>{
    assert.equal(catalog.source.documents,123);assert.equal(catalog.specifications.length,126);
    assert.match(catalog.sha256,/^[0-9a-f]{64}$/);assert.equal(catalog.source.scope,'PINNED_RELEASED_CSPEC_INVENTORY');
});
check('exact official PTEN disease/inheritance selects its expert panel, not the generic score bands',()=>{
    const rule=api.selectClassificationRules(context('PTEN','HGNC:9588','MONDO:0017623'));
    assert.equal(rule.type,'VCEP');assert.equal(rule.specificationId,'GN003');assert.equal(rule.version,'3.2');
});
check('same gene with a different explicit disease never borrows the specialist rule',()=>{
    assert.equal(api.selectClassificationRules(context('PTEN','HGNC:9588')).type,'GENERAL');
});
check('missing identity or non-germline contexts cannot silently choose a rule',()=>{
    const c=context();delete c.disease.mondoId;assert.equal(api.selectClassificationRules(c).status,'REVIEW');
    const somatic=context();somatic.scope.analysisDomain='SOMATIC';assert.equal(api.selectClassificationRules(somatic).status,'REVIEW');
    assert.equal(api.selectClassificationRules(context('WRONG','HGNC:9588','MONDO:0017623')).status,'REVIEW');
});
check('a selected expert rule must not silently fall back when its combination grammar is unavailable',()=>{
    const base=catalog.specifications.find(s=>s.specificationId==='GN003');
    const bad={...catalog,specifications:[{...base,rules:{}}]};
    const rule=api.selectClassificationRules(context('PTEN','HGNC:9588','MONDO:0017623'),bad);
    assert.equal(rule.status,'REVIEW');assert.equal(classify([pvs(),ps()],rule).code,null);
});
check('two simultaneously applicable specifications are a selection conflict, not a guessed newest version',()=>{
    const base=catalog.specifications.find(s=>s.specificationId==='GN003');
    const rule=api.selectClassificationRules(context('PTEN','HGNC:9588','MONDO:0017623'),{...catalog,specifications:[base,{...base,id:'other',version:'99'}]});
    assert.equal(rule.status,'REVIEW');assert.equal(classify([pvs(),ps()],rule).code,null);
});
check('expert scope never Cartesian-expands unrelated gene/disease pairs',()=>{
    const base=catalog.specifications.find(s=>s.specificationId==='GN003');
    const fake={...base,scopes:[{gene:'A',hgncId:'HGNC:1',disease:'MONDO:0000001',inheritance:['AUTOSOMAL_DOMINANT']},
        {gene:'B',hgncId:'HGNC:2',disease:'MONDO:0000002',inheritance:['AUTOSOMAL_RECESSIVE']}]};
    assert.equal(api.selectClassificationRules(context('A','HGNC:1','MONDO:0000002'),{...catalog,specifications:[fake]}).type,'GENERAL');
});
check('real RASopathy BRAF rules permit single BS1 as LB where generic combinations require more',()=>{
    const spec=catalog.specifications.find(s=>s.scopes.some(c=>c.gene==='BRAF'));
    const rule=api.selectClassificationRules(fromScope(spec.scopes.find(c=>c.gene==='BRAF')));
    assert.equal(rule.type,'VCEP');assert.equal(classify([bs()],rule).code,'LB');assert.equal(classify([bs()]).code,'VUS');
});
check('real published point-model rules use their own ranges and permitted strength assignments',()=>{
    const spec=catalog.specifications.find(s=>s.specificationId==='GN008');
    const rule=api.selectClassificationRules(fromScope(spec.scopes[0]));
    assert.equal(rule.type,'VCEP');assert.equal(classify([bs()],rule).code,'LB');
});
check('specialist rules refuse unpermitted applied strength instead of substituting a default',()=>{
    const spec=catalog.specifications.find(s=>s.scopes.some(c=>c.gene==='BRAF'));
    const rule=api.selectClassificationRules(fromScope(spec.scopes.find(c=>c.gene==='BRAF')));
    const result=classify([item('PP3','VERY_STRONG')],rule);assert.equal(result.code,null);assert.match(result.reason,/PP3/);
});
check('a reviewed PM3 row must match its applicable RuleSet and a strength in the released combinations',()=>{
    const spec=catalog.specifications.find(s=>s.specificationId==='GN120');
    const rule=api.selectClassificationRules(fromScope(spec.scopes[0]));
    const base=item('PM3','SUPPORTING'), reviewed={...base,reviewedPm3RuleId:rule.id};
    assert.equal(classify([reviewed],rule).code,'VUS');
    for(const input of [base,{...reviewed,reviewedPm3RuleId:rule.id.replace('/0','/1')}])
        assert.equal(classify([input],rule).code,null);
    const withoutSupporting={...rule,rules:{mainRules:rule.rules.mainRules.map(r=>({...r,conditions:r.conditions.map(c=>
        ({...c,applicableCriteriaCodes:c.applicableCriteriaCodes.map(code=>code==='PM3_Supporting'?'PP2':code)}))}))}};
    const result=classify([reviewed],withoutSupporting);assert.equal(result.code,null);assert.match(result.reason,/PM3/);
});
check('official source clauses remain unmodified by repeated evaluation',()=>{
    const before=JSON.stringify(catalog),c=context('PTEN','HGNC:9588','MONDO:0017623');
    const rule=api.selectClassificationRules(c,catalog);classify([ps(),pm()],rule);
    assert.equal(JSON.stringify(catalog),before);
});
check('normal saved-background scoring carries the selected rule through compact history summaries',()=>{
    const attempt={context:context('PTEN','HGNC:9588','MONDO:0017623')};
    const value=api.scoreBackground({candidateId:'c',attempts:[attempt]},[],{},0);
    assert.equal(value.classificationRule.specificationId,'GN003');
    const compact={...value,items:value.items.map(({code,strength,points,standalone})=>({code,strength,points,standalone}))};
    assert.equal(api.classifyEvidenceScore(compact).rule.id,value.classificationRule.id);
});
check('supported new document versions are data, not hard-coded compatibility failures',()=>{
    const base=catalog.specifications.find(s=>s.specificationId==='GN003');
    const rule=api.selectClassificationRules(context('PTEN','HGNC:9588','MONDO:0017623'),{...catalog,specifications:[{...base,id:'GN003@next',version:'99.0'}]});
    assert.equal(rule.status,'SELECTED');assert.equal(rule.version,'99.0');
});
check('a malformed source condition is reported, never converted into a generic result',()=>{
    const spec=catalog.specifications.find(s=>s.scopes.some(c=>c.gene==='BRAF'));
    const rule={...api.selectClassificationRules(fromScope(spec.scopes.find(c=>c.gene==='BRAF'))),
        rules:{mainRules:[{rule:'bad',inference:'Likely Benign',conditions:[{partitionPath:'Benign.Strong',applicableCriteriaCodes:['BS1'],condition:'EXECUTE()'}]}]}};
    assert.equal(classify([bs()],rule).code,null);
});
check('published point mappings are used and gaps in ranges do not fabricate VUS',()=>{
    const spec=catalog.specifications.find(s=>s.specificationId==='GN008');
    const original=api.selectClassificationRules(fromScope(spec.scopes[0]));
    const criteria=original.criteria.map(c=>c.code==='BS1'?{...c,points:{...c.points,STRONG:-8}}:c);
    assert.equal(classify([bs()],{...original,criteria}).code,'B');
    const noRange={...original,rules:{pointRules:{items:[{inference:'Pathogenic',rule:'only',conditions:[{condition:'>=10'}]}]}}};
    assert.equal(classify([bs()],noRange).code,null);
});
const [snapshotPath,sourceDirectory,pm3RunPath]=process.argv.slice(2);
if (snapshotPath && sourceDirectory) check('entire public rule catalog reproduces from the original pinned bytes',()=>{
    const {compile}=require('./build-acmg-classification-catalog.cjs');
    const bytes=fs.readFileSync(snapshotPath);
    assert.deepEqual(compile(bytes,sourceDirectory,catalog.source.snapshotSha256),catalog);
    assert.throws(()=>compile(bytes,sourceDirectory,'0'.repeat(64)));
});
if (pm3RunPath) check('normal saved PM3 control uses the same released combination without recomputing its score',()=>{
    const run=JSON.parse(fs.readFileSync(pm3RunPath,'utf8'));
    const candidate=run.candidates.find(c=>c.attempts.some(a=>a.context?.gene?.symbol==='RPE65'));
    const ar=candidate.attempts.findIndex(a=>a.context?.moi==='AUTOSOMAL_RECESSIVE');
    const ad=candidate.attempts.findIndex(a=>a.context?.moi==='AUTOSOMAL_DOMINANT');
    const result=api.scoreBackground(candidate,run.enabledCriteria,run,ar);
    assert.equal(result.total,1);assert.equal(result.items[0].reviewedPm3RuleId,'GN120@1.0/RuleSet/0');
    assert.equal(api.classifyEvidenceScore(result).code,'VUS');
    assert.equal(api.scoreBackground(candidate,run.enabledCriteria,run,ad).items.length,0);
});
console.log(`${count} classification checks passed; no patient analysis, network or model calls.`);
