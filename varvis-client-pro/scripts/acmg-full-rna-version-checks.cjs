// Saved paired providers and isolated wire-method checks; no authority, scoring or scientific execution.
const fs=require('fs'),path=require('path'),assert=require('node:assert/strict'),crypto=require('crypto'),{Module}=require('module'),{buildSync}=require('esbuild');
const project=path.resolve(__dirname,'..'),oldDir=process.argv[2],currentDir=process.argv[3],output=process.argv[4];
if(!oldDir||!currentDir||!output)throw Error('Supply legacy1.9 samples, current1.10.1 samples and private output');
const built=buildSync({stdin:{resolveDir:project,contents:`export {fullRnaPvs1MethodValid,fullRnaCriterionClaim} from './src/pages/varvis/ChromosomeVisualization/utils/acmgFullRna';`},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,logLevel:'silent'});
const mod=new Module(path.join(project,'scripts/rna-version-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(project,'node_modules')];mod._compile(built.outputFiles[0].text,mod.id);
const api=mod.exports,read=p=>JSON.parse(fs.readFileSync(p,'utf8')),clone=v=>JSON.parse(JSON.stringify(v)),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),checks=[],pins=[];
const test=(name,fn)=>{fn();checks.push({name,status:'PASS'});};
for(const [label,dir,version]of [['actual legacy1.9',oldDir,'1.9.0'],['actual current1.10.1',currentDir,'1.10.1']])test(label+' uses its original saved cap, module and trace',()=>{
    let count=0;for(const name of ['NOT_COUNTED','CONFLICT']){
        const pubFile=path.join(dir,name+'-public-batch.json'),privateFile=path.join(dir,name+'-private-batch.json'),pub=read(pubFile),raw=read(privateFile),cap=pub.identity.fullRnaExecution;
        pins.push({pubFile,publicSha256:sha(fs.readFileSync(pubFile)),privateFile,privateSha256:sha(fs.readFileSync(privateFile)),layer:'ACTUAL_PAIRED_PROVIDER_FIXTURE_NOT_NORMAL_SERVICE'});
        const c=pub.results[0].localBatch.criteria.find(r=>r.criterionId==='ACMG_AMP_2015:PVS1'),f=c.details.pvs1Facts,e=raw.modules.pvs1.results[0].evaluation;
        assert.equal(cap.pvs1Version,version);assert.equal(e.deterministicTrace.evaluatorVersion,version);assert.equal(api.fullRnaPvs1MethodValid(f,cap,c.details.fullRnaUse.claim,e),true);
        if(version==='1.9.0')assert.equal(Object.hasOwn(f,'evaluationState'),false);else{assert.equal(f.evaluationState,'EXECUTED');assert.equal(f.evaluationMethodVersion,version);}
        count++;
    }assert.equal(count,2);
});
const claim={originalStatus:'NOT_EVALUATED',originalStrength:'NONE',application:'NOT_ADOPTED'},rawNe={evaluationResult:{status:'NOT_EVALUATED'}};
const versions=[['1.9.0','1.0.0'],['1.10.0','1.1.0'],['1.10.1','1.1.1']];
const facts=(version,module)=>({methodVersion:version,fullRnaAssessment:{schemaVersion:'pvs1-full-rna-assessment-v1',methodVersion:module,status:'NOT_EVALUATED'}});
test('wire only: all exact known legacy tuples omit optional execution metadata without claiming execution',()=>{
    for(const [version,module]of versions)assert.equal(api.fullRnaPvs1MethodValid(facts(version,module),{pvs1Version:version},claim),true);
});
test('wire only: an executed tuple binds the configured version, assessment and any retained original trace',()=>{
    for(const [version,module]of versions){const f={...facts(version,module),evaluationState:'EXECUTED',evaluationMethodVersion:version},cap={pvs1Version:version},raw={deterministicTrace:{evaluatorId:'transcript-truncating-nmd-pvs1',evaluatorVersion:version}};
        assert.equal(api.fullRnaPvs1MethodValid(f,cap,claim,raw),true);for(const change of [x=>{x.evaluationMethodVersion=null;},x=>{x.fullRnaAssessment.methodVersion='9.0.0';},x=>{delete x.fullRnaAssessment;}]){const x=clone(f);change(x);assert.equal(api.fullRnaPvs1MethodValid(x,cap,claim,raw),false);}
        raw.deterministicTrace.evaluatorVersion='9.0.0';assert.equal(api.fullRnaPvs1MethodValid(f,cap,claim,raw),false);
    }
});
test('wire only: unexecuted source or policy rejection keeps its target version and a null execution version',()=>{
    for(const [version]of versions){const f={methodVersion:version,evaluationState:'NOT_EXECUTED',evaluationMethodVersion:null},cap={pvs1Version:version};
        assert.equal(api.fullRnaPvs1MethodValid(f,cap,claim,rawNe),true);
        for(const change of [x=>{x.evaluationMethodVersion=version;},x=>{x.fullRnaAssessment=facts(version,versions.find(r=>r[0]===version)[1]).fullRnaAssessment;},x=>{delete x.evaluationState;},x=>{x.methodVersion='NOT_EXECUTED';}]){const x=clone(f);change(x);assert.equal(api.fullRnaPvs1MethodValid(x,cap,claim,rawNe),false);}
        assert.equal(api.fullRnaPvs1MethodValid(f,cap,{...claim,originalStatus:'MET',originalStrength:'SUPPORTING',application:'ADOPTED'},rawNe),false);
        assert.equal(api.fullRnaPvs1MethodValid(f,cap,claim,{...rawNe,deterministicTrace:{evaluatorVersion:version}}),false);
    }
});
test('wire only: unknown future versions and crossed assessment/configuration tuples remain rejected',()=>{
    for(const [f,cap]of [[facts('1.11.0','1.2.0'),{pvs1Version:'1.11.0'}],[facts('1.10.0','1.0.0'),{pvs1Version:'1.10.0'}],[facts('1.9.0','1.0.0'),{pvs1Version:'1.10.1'}]])assert.equal(api.fullRnaPvs1MethodValid(f,cap,claim),false);
});
test('ordinary DNA A1 and splice methods are not claimed as full RNA by a version update',()=>{
    for(const methodVersion of ['1.5.2','1.7.0','1.8.0'])assert.equal(api.fullRnaCriterionClaim({criterionId:'ACMG_AMP_2015:PVS1',details:{pvs1Facts:{schemaVersion:'pvs1-selected-facts-v1',methodVersion,method:'SAVED_DNA_BRANCH'}}}),false);
});
const result={status:'PASS_REAL_19_1101_AND_KNOWN_WIRE_METHOD_CONTRACTS',checks:checks.length,results:checks,pins,
    realSavedExecutions:['1.9.0/1.0.0','1.10.1/1.1.1'],wireOnlyNotRealBusiness:['1.10.0/1.1.0','NOT_EXECUTED version/null handling'],
    pending:['Completed-initiation rescue/public NOT_APPLICABLE detail contract','Normal Service complete run'],authorityIssued:false,scientificOrModelCalls:0,browser:'NOT_RUN'};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(`${checks.length} full RNA method checks: ${result.status}`);
