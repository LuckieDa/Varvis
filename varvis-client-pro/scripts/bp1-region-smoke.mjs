import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url);
const api=await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(source)).toString('base64')}`));
const facts={schemaVersion:'bp1-general-facts-v1',policyVersion:'1.1.0',scope:'PUBLISHED_SCOPE_PLUS_PROJECT_RESEARCH_RESTRICTION',
  clinicalValidationCompleted:false,experimentalNormalityEstablished:false,expertBp1SpliceThreshold:false,
  projectSplicePolicy:'PROJECT_RESEARCH_CONSERVATIVE_SPLICE_EXCLUSION_V1',sourceSpecification:'SYNTH@1.0',
  sourceTranscript:'NM_000001.1',transcript:'ENST000001.1',protein:'ENSP000001.1',proteinVariantKey:'A2V',maximumObservedDelta:'0.01',
  knowledgeDisposition:'EXECUTABLE',knowledgeStatus:'READY',functionalScreen:{status:'CHECKED',reasonCode:'BP1_LOADED_FUNCTION_SCREENS_CHECKED',
    catalogSha256:'a'.repeat(64),coverage:'LOADED_PUBLICATIONS_ONLY',absenceMeansNormal:false,clinicalAdoption:false,
    matches:[{screenId:'TEST_ONLY',sourceId:'SYNTHETIC',sourceUrl:'https://example.org/screen',sourceRawSha256:'b'.repeat(64),
      outcome:'NO_ADVERSE_SIGNAL_IN_THIS_ASSAY_NOT_NORMALITY_PROOF',reportedClass:'FUNCTIONAL',proteinVariantKey:'A2V',sourceRow:8,sourceSheet:'Synthetic'}]}};
const criterion={criterionId:'ACMG_AMP_2015:BP1',status:'MET',appliedStrength:'SUPPORTING',reasonCode:'BP1_PUBLISHED_SCOPE_AND_PROJECT_CHECKS_MET',details:{bp1Facts:facts}};
let count=0;const test=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const copy=()=>structuredClone(criterion),details=c=>api.publishedMissenseDetails(c),text=c=>JSON.stringify(details(c));
Object.assign(facts,{policyVersion:'1.2.0',scope:'PUBLISHED_REGION_AND_SPLICE_CONDITIONS',expertBp1SpliceThreshold:true,requiredDistanceBp:10000,
 projectSplicePolicy:'PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1',regionConditionCheck:{type:'PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1',status:'READY',
 reasonCode:'BP1_OUTSIDE_PUBLISHED_REGIONS',appliedStrength:'STRONG',distanceBp:10000,maximumRawDelta:'0.1',sourceTranscript:facts.sourceTranscript,
 experimentalNormalityEstablished:false,functionalCoverage:'LOADED_PUBLICATIONS_ONLY',proteinPosition:2,codingChange:'c.5C>T',
 excludedProteinRegions:[{label:'Synthetic expert region',start:4,end:6}],functionalMatches:[]}});
criterion.appliedStrength='STRONG';criterion.reasonCode='BP1_PUBLISHED_REGION_AND_SPLICE_CONDITIONS_MET';
test('source region and 10 kb published threshold shown with strong',()=>{assert.ok(text(criterion).includes('10 kb'));assert.ok(text(criterion).includes('强级'));assert.ok(!text(criterion).includes('0.1是项目额外'));});
for(const [name,change] of [
 ['inside',f=>f.regionConditionCheck.excludedProteinRegions[0].start=2],['overlap',f=>f.regionConditionCheck.excludedProteinRegions.push({label:'bad',start:6,end:8})],
 ['missing regions',f=>f.regionConditionCheck.excludedProteinRegions=[]],['position',f=>f.regionConditionCheck.proteinPosition=3],
 ['short window',f=>f.requiredDistanceBp=4999],['short fact',f=>f.regionConditionCheck.distanceBp=4999],['clinical',f=>f.clinicalValidationCompleted=true],
 ['not ready',f=>f.regionConditionCheck.status='REVIEW_REQUIRED'],['different transcript',f=>f.regionConditionCheck.sourceTranscript='NM_000001.2'],
 ['bad max',f=>f.maximumObservedDelta='0.10001'],['no function screen',f=>delete f.functionalScreen],
 ['matched adverse',f=>f.regionConditionCheck.functionalMatches.push({sourceRow:5,originalCells:[],codingChange:'c.5C>T',proteinVariantKey:'A2V',reviewRequired:true,spliceReviewRequired:false})],
 ['matched RNA',f=>f.regionConditionCheck.functionalMatches.push({sourceRow:5,originalCells:[],codingChange:'c.5C>T',proteinVariantKey:'A2V',reviewRequired:false,spliceReviewRequired:true})]
 ])test('refuses corrupt '+name,()=>{const c=copy();change(c.details.bp1Facts);assert.deepEqual(details(c),[]);});
test('strong cannot become supporting',()=>{const c=copy();c.appliedStrength='SUPPORTING';assert.deepEqual(details(c),[]);});
test('old policy cannot claim new strong',()=>{const c=copy();c.details.bp1Facts.policyVersion='1.1.0';assert.deepEqual(details(c),[]);});
test('precise blocked results preserve source conditions',()=>{const c=copy();c.status='NOT_EVALUATED';c.appliedStrength='NONE';c.reasonCode='BP1_INSIDE_PUBLISHED_EXCLUDED_REGION';c.details.bp1Facts.regionConditionCheck.status='NOT_APPLICABLE';assert.ok(details(c).length);});
test('damaged blocked region metadata cannot crash history',()=>{const c=copy();c.status='NOT_EVALUATED';c.appliedStrength='NONE';c.details.bp1Facts.regionConditionCheck.excludedProteinRegions={damaged:true};assert.doesNotThrow(()=>details(c));});
test('render helpers do not mutate complete export',()=>{const before=JSON.stringify(criterion);details(criterion);assert.equal(JSON.stringify(criterion),before);});
console.log(JSON.stringify({passed:count,scientificCalls:0}));
