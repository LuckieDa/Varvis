import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// Dedicated TEST_ONLY PVS1 replay. No backend, database, scientific execution or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every((arg) => arg === '--self-check'));
const task = path.join(checkout, 'local/temp/PVS1-ui-business-20260927');
const handoff = path.join(checkout, 'local/development/handoffs/PVS1-ui-business-20260927');
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-check-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(fs.readFileSync(path.join(handoff, 'source-index.json')));
assert.equal(manifest.files.length, 24);
for (const record of manifest.files) {
    const original = fs.readFileSync(path.toNamespacedPath(record.absolutePath));
    const copy = fs.readFileSync(record.copy);
    assert.equal(original.length, record.bytes); assert.equal(sha(original), record.sha256);
    assert.equal(sha(copy), record.sha256); assert.equal(record.copySha256, record.sha256);
}
write('original-sha256.json', manifest);
const read = relative => JSON.parse(fs.readFileSync(path.join(handoff, 'originals', relative)));
const normals = Object.fromEntries(['NONSENSE','FRAMESHIFT'].map(name => {
 const f = Object.fromEntries(['input-options','request','submit','get-after-store-service-rebuild','reuse','observed-counts'].map(n => [n,read(name+'/'+n+'.json')]));
 f.saved=f['get-after-store-service-rebuild']; f.savedSource='ARCHIVED_GET_AFTER_STORE_SERVICE_REBUILD';
 assert.equal(f['input-options'].requestedN,2); assert.equal(f.request.topN,2); assert.deepEqual(f.submit,f.saved);assert.deepEqual(f.reuse,f.saved);return [name,f];
}));
const negatives=Object.fromEntries(manifest.files.filter(e=>e.role.startsWith('negative')).map(e=>[e.scenario,JSON.parse(fs.readFileSync(e.copy))]));
const material={saved:negatives.MODEL_SHA,savedSource:'ARCHIVED_SUBMIT_SERVED_AS_TEST_GET_READ_ONLY'};
const fixtures={...normals,MATERIAL:material};
for (const f of Object.values(fixtures)) assert.equal(f.saved.dataClass, 'SYNTHETIC');
write('source-before.json', ['src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
    'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'scripts/acmg-input-contract-smoke.mjs', 'scripts/pvs1-business-browser-smoke.mjs'].map(p => ({ path: p, sha256: sha(fs.readFileSync(path.join(project, p))) })));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helperPath = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const helpers = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(helperPath).toString('base64')}`));
const contractChecks = [];
const check = (name, action) => { action(); contractChecks.push(name); };
const mutations = [
    ['profileId', o => { o.executionProfile.profileId = 'legacy'; }],
    ['profileVersion', o => { o.executionProfile.profileVersion = '9'; }],
    ['declaration', o => { o.requiredDeclaration = 'ordinary-ui-v3'; }],
    ['profile declaration', o => { o.executionProfile.requiredDeclaration = 'ordinary-ui-v3'; }],
    ['extra criterion', o => { o.enabledCriteria.push('ACMG_AMP_2015:PS3'); }],
    ['profile criterion', o => { o.executionProfile.enabledCriteria = ['ACMG_AMP_2015:PM1']; }],
    ...['formalEnabled','automaticTrigger','classificationEnabled','adoptionSupported','manifestSha256'].map(k =>
        [k, o => { o.executionProfile[k] = true; }]),
    ...['schemaVersion','status','bundleId','bundleVersion','bundleSha256','bundleRawSha256','catalogSha256'].map(k =>
        [k, o => { o.executionProfile.reviewedRuleBinding[k] = ''; }]),
    ['algorithm version', o => { o.executionProfile.reviewedRuleBinding.algorithms['transcript-truncating-nmd-pvs1'] = '1.0.3'; }],
    ['extra algorithm', o => { o.executionProfile.reviewedRuleBinding.algorithms.MissenseComparisonRuntime = '1.0.0'; }],
    ...['automaticTrigger','classificationEnabled','adoptionSupported'].map(k =>
        ['bundle '+k, o => { o.executionProfile.reviewedRuleBinding[k] = true; }]),
    ...['schemaVersion','version','mode','reviewedRuleBinding'].map(k =>
        ['preparation '+k, o => { o.executionProfile.ruleContextPreparationBinding[k] = ''; }]),
    ...['automaticTrigger','classificationEnabled','criterionPolicySelected','predictionCompatibilityClaimed'].map(k =>
        ['preparation '+k, o => { o.executionProfile.ruleContextPreparationBinding[k] = true; }]),
    ...['candidateId','analysisId','requiredDeclaration','policyHash','executionSourceSha256','recommendationId'].map(k =>
        ['recommendation '+k, o => { o.candidates[0].recommendation[k] = ''; }]),
    ['candidate preparation', o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '5.0.0'; }],
    ...['annotationId','annotationSourceSha256','versionedTranscriptId','annotationSource','annotationSourceVersion','ruleContextSelectionPolicy'].map(k =>
        ['annotation '+k, o => { o.candidates[0].recommendation.annotations[0][k] = ''; }]),
    ['legacy identity bypass', o => { Object.assign(o.candidates[0].recommendation.annotations[0], {ruleContextReady:false,contextSelectable:false,identityCompatible:true,predictionPath:'HNF1A_REVEL'}); }],
    ['cross candidate annotation', o => { o.candidates[0].recommendation.annotations = o.candidates[1].recommendation.annotations; }],
    ['mixed raw and canonical hash', o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding.bundleRawSha256 = o.executionProfile.reviewedRuleBinding.bundleSha256; }],
];
for (const [name, mutate] of mutations) check('reject '+name, () => {
    const o = structuredClone(normals.NONSENSE['input-options']); mutate(o);
    assert.ok(helpers.pvs1NmdScopeClaim(o)); assert.ok(helpers.pvs1NmdScopeIssue(o));
    assert.deepEqual(helpers.currentBusinessCriteria(o), []);
    assert.throws(() => helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,helpers.prepareSimplifiedInputs(o)), /PVS1/);
    assert.throws(() => helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration),{}), /PVS1/);
});
for (const [name,f] of Object.entries(normals)) check(name+' exact server tokens and confirmation identity', () => {
    const o=f['input-options'], before=JSON.stringify(o), inputs=helpers.prepareSimplifiedInputs(o);
    assert.deepEqual(helpers.currentBusinessCriteria(o),['ACMG_AMP_2015:PVS1']);
    const request=helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs,undefined,'SYNTHETIC');
    assert.equal(request.declarationVersion,'ordinary-pvs1-nmd-v1');
    request.hypotheses.forEach((h,i)=>assert.equal(h.transcript.selectionPolicy,f.request.hypotheses[i].transcript.selectionPolicy));
    const stale=structuredClone(inputs);stale[o.candidateIds[0]].recommendationId='stale';
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,stale),/确认/);
    stale[o.candidateIds[0]]=structuredClone(inputs[o.candidateIds[1]]);
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,stale),/确认/);
    for(const id of o.candidateIds) inputs[id].annotationId='';
    assert.throws(()=>helpers.buildSimplifiedCaseDeclaration(o,o.analysisId,inputs),/尚无/);
    assert.equal(JSON.stringify(o),before);
});
check('old PVS1 identities are not v6',()=>{
    for(const value of [{enabledCriteria:['ACMG_AMP_2015:PVS1']},{executionProfile:{reviewedRuleBinding:{schemaVersion:'reviewed-rule-bundle-binding-v1'}}}]) {
        assert.equal(helpers.pvs1NmdScopeClaim(value),false);assert.equal(helpers.pvs1NmdSavedRun(value),false);
    }
});
check('v6 advanced declaration cannot bypass scope',()=>{
    const o=normals.NONSENSE['input-options'];
    assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,helpers.automaticCaseForm(o.analysisId,'SYNTHETIC','ordinary-ui-v3'),{}),/PVS1/);
    const c=o.candidates[0], r=c.recommendation, relationship=r.relationships[0];
    const hypothesis={hypothesisId:r.hypothesisId,hgncId:relationship.hgncId,mondoId:relationship.mondoId,moi:relationship.moi,
        affectedStatus:'UNKNOWN',transcriptIndex:'0',selectionPolicy:r.annotations[0].ruleContextSelectionPolicy,
        selectionReason:'TEST_ONLY explicit background',recordLocator:'test-only/advanced'};
    const form=helpers.automaticCaseForm(o.analysisId,'SYNTHETIC',o.requiredDeclaration);
    assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,form,{}),/PVS1 至少/);
    assert.equal(helpers.buildCaseDeclaration(o,o.analysisId,form,{[c.candidateId]:[hypothesis]}).hypotheses.length,1);
    for(const [key,value] of [['hypothesisId','other'],['hgncId','HGNC:999999'],['mondoId','MONDO:0999999'],['moi','AR'],
        ['selectionPolicy',normals.FRAMESHIFT['input-options'].candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy]])
        assert.throws(()=>helpers.buildCaseDeclaration(o,o.analysisId,form,{[c.candidateId]:[{...hypothesis,[key]:value}]}),/PVS1/,key);
});
check('successful trace codes are not pending review',()=>{
    for(const code of ['REVIEWED_CLAUSE_POLICY_AND_MODEL_BOUND','REVIEWED_NMD_SCOPE_SUPPORTED']) assert.doesNotMatch(helpers.reasonHelp(code),/需要.*复核/);
    assert.match(helpers.reasonHelp('PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED'),/HNF1A/);
    assert.doesNotMatch(helpers.reasonHelp('PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED','PVS1_NMD_V6'),/HNF1A/);
});
check('inserted-base mapping and partial resolved facts',()=>{
    const c=structuredClone(normals.FRAMESHIFT.saved.candidates[0]);
    Object.assign(c.attempts[0].pvs1.resolvedInput,{ptcReferenceMapping:'CONTAINS_INSERTED_BASES',ptcReferenceMiddle:null});
    const entry=helpers.evidenceCards(c,['ACMG_AMP_2015:PVS1'])[0].entries[0];
    assert.match(entry.details.find(([k])=>k==='PTC 参考映射')[1],/含插入碱基/);
    assert.match(entry.details.find(([k])=>k==='PTC 参考对应（起/中/末）')[1],/未记录/);
});
write('contract-validation.json',{status:'PASS',checks:contractChecks.length,names:contractChecks,mutatedFixtures:'FRONTEND_CONTRACT_ONLY_NOT_BACKEND_ORIGINALS'});
// Expected scientific/context identities come from Java's archived request, never this builder.
function validateRequest(body, f) {
    const archived = f.request;
    assert.deepEqual(Object.keys(body).sort(), ['dataClass', 'declarationVersion', 'hypotheses', 'patient', 'recommendations', 'sampleId', 'topN'].sort());
    for (const key of ['topN', 'declarationVersion', 'sampleId', 'dataClass']) assert.deepEqual(body[key], archived[key], key);
    assert.deepEqual(body.recommendations, archived.recommendations, 'server recommendation references');
    assert.equal(body.hypotheses.length, archived.hypotheses.length);
    assert.deepEqual(Object.keys(body.patient).sort(), ['familyContextId', 'patientContextId', 'role', 'sex', 'source']);
    const source = (s, locator) => assert.deepEqual(s, { source: 'EXPLICIT_USER_DECLARATION', version: '1.0.0', recordLocator: locator });
    // Allowed differences: UI privacy identifier and source labels, UNKNOWN affected status, PVS1 confirmation prose.
    assert.equal(body.patient.role, 'PROBAND'); assert.equal(body.patient.sex, 'UNKNOWN'); assert.equal(body.patient.familyContextId, null);
    assert.equal(body.patient.patientContextId, `patient-context-${f['input-options'].analysisId}`);
    source(body.patient.source, `analysis/${f['input-options'].analysisId}/acmg/ordinary-pvs1-nmd-v1`);
    body.hypotheses.forEach((actual, i) => {
        const expected = archived.hypotheses[i], rec = archived.recommendations[i];
        for (const key of ['candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi']) assert.equal(actual.hypothesis[key], expected.hypothesis[key], key);
        for (const key of ['accession', 'version', 'selectionPolicy', 'annotationSource', 'annotationSourceVersion'])
            assert.equal(actual.transcript[key], expected.transcript[key], key);
        assert.equal(actual.affectedStatus, 'UNKNOWN');
        const locator = `recommendations/${rec.recommendationId}/selection`;
        source(actual.hypothesis.provenance, locator); source(actual.transcript.source, locator);
        assert.equal(actual.transcript.selectionReason,
            `User confirmed disease relationship, MOI and exact versioned transcript ${expected.transcript.accession}.${expected.transcript.version} for limited PVS1 NMD evaluation. Sequence, LoF mechanism, exon scope and policy remain verified by the server; no scientific adoption or final classification is authorized.`);
        assert.deepEqual(Object.keys(actual).sort(), ['affectedStatus', 'hypothesis', 'transcript']);
        assert.deepEqual(Object.keys(actual.hypothesis).sort(), Object.keys(expected.hypothesis).sort());
        assert.deepEqual(Object.keys(actual.transcript).sort(), Object.keys(expected.transcript).sort());
    });
}

const entry = `import React,{useState} from 'react'; import {createRoot} from 'react-dom/client';
import Panel from ${JSON.stringify(component)};
function Replay(){const [scenario,setScenario]=useState('NONSENSE'); const [history,setHistory]=useState(false);
 const [status,setStatus]=useState('TEST_ONLY: 本回放只有 Top-2，请先把组件 Top-20 改为 2，再读取正式候选。');
 const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([key, f]) => [key, f.saved.analysisId])))};
 async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json(),null,2));}
 return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PVS1 · 真实组件合成 HTTP</h1>
 <p>无 Java、数据库或科学执行，仅原 runId 的保存回执回放。仅支持 Top-2，不扩造候选。</p>
 <p>NONSENSE 和 FRAMESHIFT 有各自匹配 options/request；其余场景只有归档 submit，作为只读测试 GET，不允许提交。</p>
 <label>归档场景 <select value={scenario} onChange={e=>setScenario(e.target.value)}><option>NONSENSE</option><option>FRAMESHIFT</option><option>MATERIAL</option></select></label>{' '}
 <button onClick={()=>control('control',{delayNext:true})}>延迟下一次 POST（最多120秒）</button>{' '}
 <button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
 <button onClick={()=>control('state')}>读取请求计数</button>{' '}
 <button onClick={async()=>{await control('control',{history:!history});setHistory(!history);}}>切换当前 options 可用性（之后读取正式候选）</button>
 <p>下载后可通过此控件核验真实下载文件（保存文件路径请另行记录）：</p><input aria-label="核验实际下载JSON" type="file" accept=".json" onChange={async e=>{const f=e.target.files?.[0];if(f){const bytes=await f.arrayBuffer();const rawSha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join('');await control('compare-download',{fileName:f.name,rawSha256,value:JSON.parse(new TextDecoder().decode(bytes))});}}}/>
 <pre style={{whiteSpace:'pre-wrap'}}>{status}</pre></aside><Panel analysisId={ids[scenario]}/></>;}
createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true,
    platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' },
    define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
const imports = Object.keys(bundle.metafile.inputs).map((s) => s.replaceAll('\\', '/'));
for (const suffix of ['components/OrdinaryAcmgEvaluationPanel.jsx', 'components/AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js'])
    assert.ok(imports.some((s) => s.endsWith(suffix)));
write('bundle-inputs.json', imports); write('asset-sha256.json', ['entry.jsx','app.js'].map(p=>({path:p,bytes:fs.statSync(path.join(output,p)).size,sha256:sha(fs.readFileSync(path.join(output,p)))}))); for(const p of ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js','src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx','src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx','scripts/pvs1-business-browser-smoke.mjs']) fs.copyFileSync(path.join(project,p),path.join(output,path.basename(p)));

// Render the actual SavedResult; no manufactured backend envelopes.
await build({stdin:{contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server'; import {SavedResult} from ${JSON.stringify(component)}; export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',outfile:path.join(output,'render.cjs'),loader:{'.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning',plugins:[{name:'test-only-export',setup(api){api.onLoad({filter:/OrdinaryAcmgEvaluationPanel\.jsx$/},()=>({contents:fs.readFileSync(component,'utf8')+'\nexport {SavedResult};',loader:'jsx',resolveDir:path.dirname(component)}));}}]});
const {render}=createRequire(import.meta.url)(path.join(output,'render.cjs'));
const cases={...Object.fromEntries(Object.entries(normals).map(([k,f])=>[k,f.saved])),...negatives};
const rendered=[];
for(const [name,run] of Object.entries(cases)) {
 const before=JSON.stringify(run),html=render(run),other=render(run,['ACMG_AMP_2015:BA1']);
 assert.ok(helpers.pvs1NmdSavedRun(run),name+' saved identity');
 for(const markup of [html,other]) {assert.ok(markup.includes('PVS1'));assert.ok(markup.includes('只读'));assert.ok(!markup.includes('HNF1A'));assert.ok(!markup.includes('undefined'));assert.ok(!markup.includes('需要 CSpec / 人工复核处理'));assert.ok(!markup.includes('>BA1<'));}
 const outcomes=[];
 for(const candidate of run.candidates) {
 const v=candidate.attempts[0].pvs1,e=v.evaluation||v,card=helpers.evidenceCards(candidate,['ACMG_AMP_2015:PVS1'])[0].entries[0];
 assert.deepEqual(card.evaluation,e); assert.equal(card.raw,v);outcomes.push(e.evaluationResult);
 if(e.evaluationResult.reasonCode) {assert.ok(html.includes(e.evaluationResult.reasonCode));assert.ok(html.includes(helpers.reasonHelp(e.evaluationResult.reasonCode,'PVS1_NMD_V6')));}
 else {assert.equal(e.evaluationResult.status,'MET');assert.deepEqual(card.reasons,[]);assert.ok(html.includes('VERY_STRONG'));}
 for(const k of ['ptcStart','ptcEnd','lastJunctionCdsPosition','distanceToLastJunction','mutantLastJunctionCdsPosition','referenceLastJunctionCdsPosition','netLengthChange','ptcReferenceMiddle']) if(v.resolvedInput?.[k]!=null) assert.ok(card.details.some(([,text])=>String(text).includes(String(v.resolvedInput[k]))),name+' '+k);
 if(!e.appliedStrength) assert.equal(card.evaluation.appliedStrength,undefined);
 if(!v.resolvedInput?.sequenceDerivationStatus) assert.ok(card.details.some(([k,t])=>k==='序列核对状态'&&t==='未记录/未进入该步骤'));
 }
 assert.equal(JSON.stringify(run),before);fs.writeFileSync(path.join(output,name+'.html'),'<!doctype html><meta charset="utf-8">'+html); rendered.push({name,outcomes});
}
write('render-validation.json',{status:'PASS',actualComponents:true,savedScenarios:rendered,currentNonPvs1History:true,immutableInput:true,browserInteraction:'NOT_RUN'});

const state = { history: false, delayNext: false }, journal = [], pending = [];
let origin;
const snapshot = () => ({ testOnly: true, ...state, postCount: journal.filter((e) => e.kind === 'POST_RUN').length,
    acceptedPostCount: journal.filter((e) => e.kind === 'POST_RUN' && e.status === 200).length, pending: pending.length,
    scientificExecution: 'NOT_RUN_ARCHIVE_REPLAY_ONLY', output });
function finish() { pending.splice(0).forEach((p) => p.send()); write('summary.json', snapshot()); server.close(); server.closeAllConnections(); }
const server = http.createServer(async (req, res) => {
    const event = { executionClass: selfCheck ? 'HTTP_SELF_CHECK' : 'BROWSER', at: new Date().toISOString(), method: req.method, url: req.url, kind: 'OTHER' };
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'");
    const reply = (status, value, type = 'application/json', delay = false) => {
        event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'requests.jsonl'), JSON.stringify(event) + '\n');
        const send = () => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': `${type}; charset=utf-8` }); res.end(type === 'application/json' ? JSON.stringify(value) : value); } };
        if (delay) { state.delayNext = false; const p = { send: () => { clearTimeout(p.timer); const i = pending.indexOf(p); if (i >= 0) pending.splice(i, 1); send(); } }; p.timer = setTimeout(p.send, 120000); pending.push(p); }
        else send();
    };
    try {
        if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site')
            return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        const url = new URL(req.url, origin); if(url.pathname.startsWith('/__test/')) event.kind = url.pathname.includes('compare-download') ? 'FILE_COMPARE' : 'CONTROL';
        if (req.method === 'GET' && url.pathname === '/') return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PVS1 TEST_ONLY</title><div id="root"></div><script src="/app.js"></script></html>', 'text/html');
        if (req.method === 'GET' && url.pathname === '/app.js') return reply(200, fs.readFileSync(path.join(output, 'app.js')), 'text/javascript');
        let body;
        if (req.method === 'POST') {
            assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0;
            for await (const chunk of req) { size += chunk.length; assert.ok(size <= 1024 * 1024); chunks.push(chunk); }
            body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body;
        }
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') {
            if (typeof body.history === 'boolean') state.history = body.history;
            if (typeof body.delayNext === 'boolean') state.delayNext = body.delayNext;
            return reply(200, snapshot());
        }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach((p) => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname === '/__test/compare-download' && req.method === 'POST') {
            const f = Object.values(fixtures).find((v) => v.saved.runId === body.value?.runId); assert.ok(f);
            assert.match(body.rawSha256, /^[a-f0-9]{64}$/); assert.equal(body.fileName, `acmg-${f.saved.runId}.json`);
            assert.deepEqual(body.value, f.saved); write(`download-comparison-${f.saved.runId}.json`, { status: 'PASS', runId: f.saved.runId,
                fileName: body.fileName, rawSha256: body.rawSha256, comparedAgainst: f.savedSource, input: selfCheck ? 'SELF_CHECK_INTERFACE_ONLY_NOT_DOWNLOAD' : 'BROWSER_FILE_PICKER_ACTUAL_DOWNLOAD' });
            return reply(200, { status: 'PASS', runId: f.saved.runId });
        }
        const match = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const f = match && Object.values(fixtures).find((v) => v.saved.analysisId === decodeURIComponent(match[1]));
        if (!f) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        if (req.method === 'GET' && match[2] === 'input-options') {
            event.kind = 'GET_OPTIONS';
            if (state.history || !f.request) return reply(503, { message: 'TEST_ONLY_CURRENT_PVS1_UNAVAILABLE_OR_READ_ONLY_ARCHIVE' });
            if (url.searchParams.get('topN') !== '2') return reply(400, { message: 'TEST_ONLY_TOP2_REQUIRED：请把正式 Top-N 改为 2 后读取' });
            return reply(200, f['input-options']);
        }
        if (req.method === 'GET' && match[2] === 'runs') {
            event.kind = match[3] ? 'GET_RUN' : 'GET_RUNS'; event.savedSource = f.savedSource; const run = f.saved;
            if (match[3] && match[3] !== run.runId) return reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' });
            return reply(200, match[3] ? run : [{ runId: run.runId, status: run.status, createdAt: run.createdAt }]);
        }
        if (req.method === 'POST' && match[2] === 'runs' && !match[3]) {
            event.kind = 'POST_RUN'; const id = journal.filter(e => e.kind === 'POST_RUN').length + 1; write('request-'+id+'-incoming.json', body); if(f.request) write('request-'+id+'-expected.json', f.request); assert.equal(state.history, false, 'Current PVS1 scope unavailable');
            assert.ok(f.request, 'READ_ONLY_ARCHIVE_HAS_NO_MATCHING_OPTIONS_OR_REQUEST'); validateRequest(body, f); write('request-'+id+'-comparison.json',{status:'PASS',expected:'ARCHIVED_BACKEND_REQUEST',allowlistedDifferences:['patient.patientContextId','patient.source','hypotheses[*].affectedStatus = UNKNOWN','hypotheses[*].hypothesis.provenance','hypotheses[*].transcript.source','hypotheses[*].transcript.selectionReason = exact PVS1 confirmation prose'],allOtherFieldsCompared:true});
            return reply(200, f.saved, undefined, state.delayNext);
        }
        return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
    } catch (error) { return reply(400, { message: `TEST_ONLY_CONTRACT_REJECTED: ${error.message}` }); }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve)); origin = `http://127.0.0.1:${server.address().port}`;
write('server.json', { origin, output, pid: process.pid, topN: 2, testOnly: true, browserStatus: 'NOT_RUN', startedAt:new Date().toISOString() });
console.log(JSON.stringify({ origin, output, pid: process.pid }));
if (!selfCheck) { process.once('SIGINT', finish); process.once('SIGTERM', finish); }
else {
    try {
        assert.equal((await fetch(origin)).status, 200);
        for (const f of Object.values(fixtures)) {
            if (!f.request) {
                const route = `/variant-analyses/${f.saved.analysisId}/acmg`;
                assert.equal((await fetch(origin + route + '/input-options?topN=2')).status, 503);
                assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
                assert.equal((await fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 400);
                continue;
            }
            const o = f['input-options'], route = `/variant-analyses/${o.analysisId}/acmg`;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/input-options?topN=2')).json(), o);
            const inputs = helpers.prepareSimplifiedInputs(o);
            f.request.recommendations.forEach((r) => Object.assign(inputs[r.candidateId], { annotationId: r.annotationId, stableRelationshipKey: r.stableRelationshipKey }));
            const actual = helpers.buildSimplifiedCaseDeclaration(o, o.analysisId, inputs, undefined, 'SYNTHETIC');
            const post = (b) => fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
            for (let i = 0; i < 1; i++) { const r = await post(actual); assert.equal(r.status, 200, await r.clone().text()); assert.deepEqual(await r.json(), f.saved); }
            const extra = {...actual, extraScienceAuthority:true}; assert.equal((await post(extra)).status,400);
            const wrong = structuredClone(actual); wrong.hypotheses[0].transcript.selectionPolicy += 'wrong'; assert.equal((await post(wrong)).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        const wrongHostStatus = await new Promise((resolve, reject) => {
            const request = http.get(origin, { headers: { Host: 'invalid.example' } }, response => {
                response.resume(); resolve(response.statusCode);
            });
            request.on('error', reject);
        });
        assert.equal(wrongHostStatus, 403);
        for (const f of Object.values(fixtures)) {
            const body = { fileName: 'acmg-' + f.saved.runId + '.json', rawSha256: sha(JSON.stringify(f.saved)), value: f.saved };
            const compare = b => fetch(origin + '/__test/compare-download', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});
            assert.equal((await compare(body)).status,200);
            assert.equal((await compare({...body,value:{...f.saved,status:'FABRICATED'}})).status,400);
        }
        write('transport-validation.json', { status: 'PASS', ...snapshot(), browserInteraction: 'NOT_RUN', download: 'NOT_RUN' });
    } finally { finish(); }
}
