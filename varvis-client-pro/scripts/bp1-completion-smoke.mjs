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
test('new result displays the matched source and explicitly limited scope',()=>{assert.ok(text(criterion).includes('Synthetic 第8行'));assert.ok(text(criterion).includes('未覆盖不代表功能正常'));});
test('unmeasured outside coverage is not presented as a normal experiment',()=>{const c=copy();Object.assign(c.details.bp1Facts.functionalScreen.matches[0],{reportedClass:'',outcome:'OUTSIDE_REPORTED_SCREEN_RANGES'});assert.ok(text(c).includes('不在此研究筛查范围内'));});
for(const [name,mutate] of [
  ['missing screen',f=>delete f.functionalScreen],['unavailable',f=>f.functionalScreen.status='UNAVAILABLE'],
  ['missing source pin',f=>f.functionalScreen.catalogSha256=''],['normality claim',f=>f.functionalScreen.absenceMeansNormal=true],
  ['unreviewed adoption',f=>f.functionalScreen.clinicalAdoption=true],['wrong variant',f=>f.functionalScreen.matches[0].proteinVariantKey='A3V'],
  ['damaging result',f=>f.functionalScreen.matches[0].reportedClass='DAMAGING'],['intermediate result',f=>f.functionalScreen.matches[0].reportedClass='INTERMEDIATE'],
  ['missing row',f=>delete f.functionalScreen.matches[0].sourceRow],['unapproved knowledge',f=>f.knowledgeDisposition='SOURCE_CONFLICT'],
  ['future version',f=>f.policyVersion='2.0.0']])test('refuses positive '+name,()=>{const c=copy();mutate(c.details.bp1Facts);assert.deepEqual(details(c),[]);});
for(const [disposition,expected] of [['NOT_APPLICABLE','明确不适用'],['UNSUPPORTED_CONDITIONS','软件尚未支持'],['SOURCE_CONFLICT','冲突'],['UNRESOLVED','尚未匹配']])
 test('explains '+disposition,()=>{const c=copy();c.status='NOT_EVALUATED';c.appliedStrength='NONE';c.details.bp1Facts.knowledgeDisposition=disposition;
 c.details.bp1Facts.functionalScreen={status:'NOT_CHECKED',matches:[]};assert.ok(text(c).includes(expected));assert.equal(c.status,'NOT_EVALUATED');});
test('blocked damaged history never crashes the renderer',()=>{const c=copy();c.status='NOT_EVALUATED';c.details.bp1Facts.functionalScreen={matches:null};assert.doesNotThrow(()=>details(c));});
test('historical 1.0 remains readable without retrospective source claims',()=>{const c=copy();c.details.bp1Facts.policyVersion='1.0.0';delete c.details.bp1Facts.functionalScreen;delete c.details.bp1Facts.knowledgeDisposition;
 assert.ok(details(c).length);assert.ok(!text(c).includes('公开功能研究检查'));});
test('display and complete serialization preserve the saved object',()=>{const c=copy(),before=JSON.stringify(c);details(c);assert.equal(JSON.stringify(c),before);assert.deepEqual(JSON.parse(JSON.stringify(c)),criterion);});
console.log(JSON.stringify({passed:count,scientificCalls:0,businessPosts:0}));
