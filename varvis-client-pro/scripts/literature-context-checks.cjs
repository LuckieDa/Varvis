const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {buildSync}=require('esbuild'),project=path.resolve(__dirname,'..');
const [optionsPath,contextsPath,reportPath]=process.argv.slice(2);
const source=buildSync({stdin:{contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';`,resolveDir:project},bundle:true,platform:'node',format:'cjs',write:false,logLevel:'warning'}).outputFiles[0].text;
const compiled={exports:{}};new Function('require','module','exports',source)(require,compiled,compiled.exports);
const api=compiled.exports,options=JSON.parse(fs.readFileSync(optionsPath)),rows=JSON.parse(fs.readFileSync(contextsPath)).contexts;
const candidate=options.candidates[0],selection=rows.find(x=>x.hgncId===candidate.catalog.hgncId),results=[];
const check=(name,fn)=>{fn();results.push({name,status:'PASS'});};
let inputs=api.prepareSimplifiedInputs(options),key=candidate.candidateId;
inputs[key]={...inputs[key],literatureContext:{...selection,moi:'AD'},affectedStatus:'UNKNOWN',annotationId:'',stableRelationshipKey:''};
check('explicit hypothesis produces declaration without borrowing a transcript',()=>{
 const d=api.buildSimplifiedCaseDeclaration(options,candidate.recommendation.analysisId,inputs,null,'SYNTHETIC');
 assert.equal(d.dataClass,'SYNTHETIC');assert.ok(!d.retryOf);
 assert.equal(d.hypotheses[0].hypothesis.mondoId,selection.mondoId);
 assert.equal(d.recommendations[0].annotationId,null);assert.equal(d.recommendations[0].stableRelationshipKey,null);
 fs.writeFileSync(reportPath.replace(/\.json$/,'.declaration.json'),JSON.stringify(d,null,2));
});
check('missing MOI does not default',()=>assert.equal(api.declaredLiteratureContext(candidate,{...inputs[key],literatureContext:{...selection,moi:''}},options),null));
check('different HGNC and gene rejected independently',()=>{
 for(const patch of [{hgncId:'HGNC:999999'},{geneSymbol:'OTHER'}])assert.equal(api.declaredLiteratureContext(candidate,{...inputs[key],literatureContext:{...selection,moi:'AD',...patch}},options),null);
});
check('published inheritance restricts declared selection',()=>assert.equal(api.declaredLiteratureContext(candidate,{...inputs[key],literatureContext:{...selection,moi:'AR',publishedInheritance:['AD']}},options),null));
check('unchanged recommendation retains explicit choice',()=>assert.deepEqual(api.prepareSimplifiedInputs(options,inputs)[key].literatureContext,inputs[key].literatureContext));
check('changed recommendation clears stale choice',()=>{const changed=structuredClone(options);changed.candidates[0].recommendation.recommendationId='changed';assert.equal(api.prepareSimplifiedInputs(changed,inputs)[key].literatureContext,null);});
check('ordinary selector remains independently available',()=>assert.equal(api.declaredLiteratureContext(candidate,{...inputs[key],literatureContext:null},options),null));
fs.writeFileSync(reportPath,JSON.stringify({checks:results},null,2));console.log(JSON.stringify(results));
