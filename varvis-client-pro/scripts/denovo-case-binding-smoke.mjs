import frontendModuleUrl from './frontend-module-url.cjs';
// Public/synthetic saved preparation only. No model, network, or scientific execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const [fixture, output] = process.argv.slice(2);
if (!fixture || !output || fs.existsSync(output)) throw Error('Supply preparation JSON and new output directory');
fs.mkdirSync(output, {recursive:true});
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url)).toString('base64')}`));
const options = JSON.parse(fs.readFileSync(fixture)), original = JSON.stringify(options), inputs = api.prepareSimplifiedInputs(options);
const binding = {analysisId:options.analysisId, materialSha256:options.executionProfile.moduleBindings.deNovo.manifestSha256,
    patientContextId:'patient-context-synthetic01', familyContextId:'family-context-synthetic01'};
const build = (b = null, o = options) => api.buildSimplifiedCaseDeclaration(o, options.analysisId, inputs, undefined, 'SYNTHETIC', b);
const results = [];
function test(name, fn) { try { fn(); results.push({name,status:'PASS'}); } catch(e) {results.push({name,status:'FAIL',error:e.message});} console.log(results.at(-1).status, name); }
test('unbound retains ordinary generated identity and no family', () => {const v=build(); assert.equal(v.patient.patientContextId,`patient-context-${options.analysisId}`); assert.equal(v.patient.familyContextId,null);});
test('explicit exact binding reaches existing declaration without inventing facts', () => {const v=build(binding); assert.equal(v.patient.patientContextId,binding.patientContextId); assert.equal(v.patient.familyContextId,binding.familyContextId); assert.equal(v.patient.sex,'UNKNOWN'); assert.equal(v.patient.source.recordLocator,'de-novo/current-case-binding'); assert.deepEqual(v.hypotheses,build().hypotheses); assert.deepEqual(v.recommendations,build().recommendations); fs.writeFileSync(path.join(output,'bound-declaration.json'),JSON.stringify(v,null,2));});
for (const [name, edit] of [
    ['different analysis', b=>b.analysisId='other-analysis'], ['different material',b=>b.materialSha256='0'.repeat(64)],
    ['patient absent',b=>delete b.patientContextId], ['family absent',b=>delete b.familyContextId],
    ['patient free text',b=>b.patientContextId='a patient name'], ['family path',b=>b.familyContextId='C:/case'],
    ['patient trailing whitespace',b=>b.patientContextId+=' '], ['invented confirmation',b=>b.parentageConfirmed=true],
]) test(`reject ${name}`,()=>{const b=structuredClone(binding);edit(b);assert.throws(()=>build(b));});
test('legacy scope cannot take this binding',()=>{const o=structuredClone(options);o.executionProfile.profileId='acmg-local-computational-batch1-v1';assert.throws(()=>build(binding,o));});
test('binding change affects declaration identity and leaves inputs untouched',()=>{const b={...binding,patientContextId:'patient-context-synthetic02'};assert.notDeepEqual(build(b).patient,build(binding).patient);assert.equal(JSON.stringify(options),original);});
fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify({results,groups:results.length,modelCalls:0,scientificExecutions:0},null,2));
if(results.some(x=>x.status==='FAIL'))process.exitCode=1;
