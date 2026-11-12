// Read-only scoring/sorting checks using the actual result-presentation adapter.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname, '..');
const bundle = buildSync({ stdin: { contents: `
export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgEvidenceScore';
export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgWorkbenchCopy';
export * from './src/pages/varvis/ChromosomeVisualization/utils/variantCoordinate';
export {validCoreMaterial} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {savedBackgrounds,savedVariantLabel} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {default as Summary} from './src/pages/varvis/ChromosomeVisualization/components/AcmgScoreSummary.jsx';
export {default as Workbench} from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
export {default as Decision} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';
export {default as Sample, inspectSampleCallSummary} from './src/pages/varvis/ChromosomeVisualization/components/SampleCallSummary.jsx';
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs',
    packages: 'external', write: false, loader: { '.js': 'jsx', '.less': 'empty' }, logLevel: 'silent' });
const loaded = new Module(path.join(project, 'scripts', 'score-checks-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(project, 'node_modules')];
loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const api = loaded.exports, React = require('react'), { renderToStaticMarkup } = require('react-dom/server');
let checks = 0;
const check = async (name, fn) => { await fn(); checks++; console.log('PASS ' + name); };
const entry = (code, strength, extra = {}) => ({ evaluation: { criterionId: 'ACMG_AMP_2015:' + code,
    appliedStrength: strength, evaluationResult: { status: 'MET', reasonCode: 'LOCAL_BATCH_CRITERION_MET' }, ...extra } });
const card = (code, strength, extra) => ({ id: 'ACMG_AMP_2015:' + code, code, entries: [entry(code, strength, extra)] });
const score = (...cards) => api.scoreEvidenceCards(cards);
const freeze = value => { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); } return value; };

(async () => {
    for (const [strength, points] of Object.entries({ SUPPORTING: 1, MODERATE: 2, STRONG: 4, VERY_STRONG: 8 })) {
        await check('actual adopted pathogenic/benign strength ' + strength, () => {
            assert.equal(score(card('PM3', strength)).total, points);
            assert.equal(score(card('BS1', strength)).total, -points);
        });
    }
    await check('PVS1 downgraded strength and PM3 strength do not use criterion default or case points', () => {
        const c = card('PM3', 'SUPPORTING'); c.entries[0].raw = { points: 999, score: 999 };
        assert.equal(score(card('PVS1', 'SUPPORTING'), c).total, 2);
    });
    await check('independent accepted contributions sum; original data is frozen and preserved', () => {
        const cards = freeze([card('PS3', 'STRONG'), card('PM1', 'MODERATE'), card('BP2', 'SUPPORTING')]);
        const before = JSON.stringify(cards), value = api.scoreEvidenceCards(cards);
        assert.equal(value.total, 5); assert.equal(value.positive, 6); assert.equal(value.negative, -1);
        assert.equal(JSON.stringify(cards), before);
    });
    await check('general PM1 plus PP3 is capped for ranking and classification without changing individual strengths', () => {
        const pair=freeze([card('PM1','MODERATE'),card('PP3','STRONG')]);
        const capped=score(...pair);
        assert.equal(capped.total,4);
        assert.deepEqual(capped.pm1Pp3,{rawPoints:6,points:4});
        assert.deepEqual(capped.items.map(i=>[i.strength,i.points]),[['MODERATE',2],['STRONG',4]]);
        assert.equal(api.classifyEvidenceScore(capped).code,'VUS');
        assert.match(renderToStaticMarkup(React.createElement(api.Summary,{score:capped})),/PM1 与 PP3 合计 \+4 分/);
        const withIndependent=score(...pair,card('PS2','SUPPORTING'),card('PM2','SUPPORTING'));
        assert.equal(withIndependent.total,6);
        assert.equal(api.classifyEvidenceScore(withIndependent).code,'LP');
        const atLimit=score(card('PM1','MODERATE'),card('PP3','MODERATE'));
        assert.equal(atLimit.total,4);
        assert.equal(atLimit.pm1Pp3,null);
        assert.equal(score(card('PM1','MODERATE')).total,2);
        assert.equal(score(card('PP3','STRONG')).total,4);
    });
    await check('a selected expert-group classification retains its own combination rules', () => {
        const context={scope:{analysisDomain:'GERMLINE',diseaseModel:'MENDELIAN',variantScope:'SMALL_SEQUENCE_VARIANT'},
            gene:{symbol:'MYH7',hgncId:'HGNC:7577'},disease:{mondoId:'MONDO:0005021'},moi:'AUTOSOMAL_DOMINANT'};
        const value=api.scoreEvidenceCards([card('PM1','MODERATE'),card('PP3','STRONG')],context);
        assert.equal(value.classificationRule.type,'VCEP');
        assert.equal(value.pm1Pp3,null);
    });
    for (const extra of [{reviewRequired:true}, {adoptionStatus:'NOT_APPLIED'}, {adoptionStatus:'NOT_ADOPTED'},
        {review:{status:'COMPLETED',decision:'REJECTED',reviewRecordIds:['review-sha256-'+'a'.repeat(64)]}},
        {appliedStrength:'NONE'}, {appliedStrength:'FUTURE'}]) {
        await check('unadopted or unverified evidence earns no score: ' + JSON.stringify(extra), () => {
            const value = score(card('PM1', 'MODERATE', extra)); assert.equal(value.total, null); assert.equal(value.items.length, 0);
        });
    }
    await check('display identity rejection, missing and nonapplicable results never become points', () => {
        for (const status of ['NOT_MET','NOT_APPLICABLE','NOT_EVALUATED']) assert.equal(score(card('BS1','NONE',{
            evaluationResult:{status,reasonCode:'LOCAL_BATCH_CRITERION_NOT_MET'}})).total,null);
        const c = card('PM1','MODERATE'); c.entries[0].displayIssue = true;
        assert.equal(score(c).total,null);
    });
    await check('BA1 stays standalone, and opposing adopted evidence requires review', () => {
        for (const strength of ['STAND_ALONE','STANDALONE']) {
            assert.equal(score(card('BA1',strength)).status,'STANDALONE');
            assert.equal(score(card('BA1',strength)).total,null);
        }
        assert.equal(score(card('BA1','STAND_ALONE'),card('PM1','MODERATE')).status,'REVIEW');
        assert.equal(score(card('BA1','STRONG')).status,'REVIEW');
    });
    await check('zero after actual positive and negative evidence is distinct from no score', () => {
        assert.equal(score(card('PM1','MODERATE'),card('BP2','MODERATE')).total,0);
        assert.equal(api.scoreLabel(score()),'暂无计分证据');
        assert.equal(api.scoreLabel(score(card('PM1','MODERATE'),card('BP2','MODERATE'))),'0 分');
    });
    await check('identical duplicate criterion counts once; divergent adopted records are held', () => {
        const c = card('PM1','MODERATE'); assert.equal(score(c,c).total,2);
        assert.equal(score(c,card('PM1','STRONG')).status,'REVIEW');
    });
    for (const [a,b] of [['PS1','PM5'],['PS2','PM6'],['BS1','PM2'],['PP3','BP4'],['PVS1','PP3']]) {
        await check('related/opposing combination is not silently added ' + a + '/' + b, () => assert.equal(score(card(a,'SUPPORTING'),card(b,'SUPPORTING')).status,'REVIEW'));
    }
    await check('same atomic evidence across criteria is not double-counted', () => {
        assert.equal(score(card('PS3','STRONG',{evidenceIds:['same']}),card('PM1','MODERATE',{evidenceIds:['same']})).total,null);
    });
    await check('criterion identity and unknown codes cannot invent score', () => {
        const c = card('PM1','MODERATE'); c.entries[0].evaluation.criterionId = 'ACMG_AMP_2015:PM2';
        assert.equal(score(c).total,null); assert.equal(score(card('NEW','STRONG')).total,null);
    });
    await check('representative selects maximum in one background, preserves others, never sums', () => {
        const c = {candidateId:'one',attempts:[{},{}]};
        const values = [2,8].map((total,backgroundIndex)=>({status:'SCORED',total,candidateId:'one',backgroundIndex}));
        const best = api.representativeScore(c,values);
        assert.equal(best.total,8); assert.equal(best.backgroundIndex,1); assert.equal(best.backgrounds.length,2);
        assert.equal(api.representativeScore(c,[values[0]]),null);
        const sparse = []; sparse[1] = values[1]; assert.equal(api.representativeScore(c,sparse),null);
        assert.equal(api.representativeScore(c,[{...values[0],candidateId:'wrong'},values[1]]),null);
    });
    await check('descending score including negatives, stable original-rank ties, input identity preserved', () => {
        const candidates = freeze([{rank:4},{rank:1},{rank:3},{rank:2},{rank:5}]);
        const scores = [8,2,-4,2,null].map(total=>({total,status:total===null?'NO_EVIDENCE':'SCORED'}));
        assert.deepEqual(api.sortScoredCandidates(candidates,scores).map(row=>row.index),[0,1,3,2,4]);
        assert.deepEqual(api.sortScoredCandidates(candidates,scores,'ORIGINAL').map(row=>row.index),[1,3,2,0,4]);
        assert.ok(api.sortScoredCandidates(candidates,scores).every(row=>row.candidate===candidates[row.index]));
    });
    await check('source-controlled PM6 shared event is excluded instead of becoming a missing field', () => {
        const c = card('PM6','NONE',{evaluationResult:{status:'NOT_EVALUATED',reasonCode:'DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2'}});
        const value = score(card('PS2','STRONG'),c); assert.equal(value.total,4); assert.equal(value.pendingCount,0);
    });
    await check('background summary acquisition is bounded to two reads and keeps original scope', async () => {
        const run = {candidates:Array.from({length:3},(_,i)=>({candidateId:'c'+i,attempts:[{},{}]}))};
        let active=0,peak=0,calls=0,last;
        const result = await api.readScoreSummaries(run,[],async (_,candidateId,backgroundIndex)=>{
            calls++; active++; peak=Math.max(peak,active); await new Promise(resolve=>setTimeout(resolve,2)); active--;
            return {...run,candidates:[run.candidates.find(c=>c.candidateId===candidateId)],backgroundIndex};
        },progress=>{last=progress;});
        assert.equal(calls,6); assert.equal(peak,2); assert.deepEqual(last,{done:6,total:6});
        assert.equal(result.failures.length,0); assert.equal(result.scores.length,3);
    });
    await check('failed background remains unranked and cancellation starts no further reads', async () => {
        const run = {candidates:[{candidateId:'c',attempts:[{},{},{}]}]};
        const failed = await api.readScoreSummaries(run,[],async()=>{throw Error('failed');});
        assert.equal(failed.failures.length,3); assert.equal(failed.scores[0],null);
        let calls=0,current=true;
        await api.readScoreSummaries(run,[],async()=>{calls++;current=false;return {candidates:[]};},null,()=>current);
        assert.equal(calls,1);
    });
    for (const [points, expected, cards] of [
        [-7,'LB',[card('BS1','STRONG'),card('BP2','MODERATE'),card('BP7','SUPPORTING')]],
        [-6,'VUS',[card('BS1','STRONG'),card('BP2','MODERATE')]],
        [-1,'VUS',[card('BP2','SUPPORTING')]],
        [0,'VUS',[card('PM1','MODERATE'),card('BP2','MODERATE')]],
        [5,'VUS',[card('PS3','STRONG'),card('PP1','SUPPORTING')]],
        [6,'LP',[card('PS3','STRONG'),card('PM1','MODERATE')]],
        [9,'LP',[card('PVS1','VERY_STRONG'),card('PP1','SUPPORTING')]],
        [10,'LP',[card('PVS1','VERY_STRONG'),card('PM1','MODERATE')]],
    ]) await check(`score ${points} remains sortable while the evidence combination suggests ${expected}`,()=>{
        const value=score(...cards); assert.equal(value.total,points);
        assert.equal(api.classifyEvidenceScore(value).code,expected);
    });
    await check('standalone BA1 suggests benign without an invented numeric score',()=>{
        const value=score(card('BA1','STAND_ALONE'));
        assert.equal(value.total,null); assert.equal(api.classifyEvidenceScore(value).code,'B');
    });
    await check('missing or conflicting adoption cannot create VUS, benign or a score classification',()=>{
        for(const value of [null,score(),score(card('PS1','STRONG'),card('PM5','MODERATE')),
            {status:'SCORED',total:NaN,items:[{}]},{status:'SCORED',total:0,items:[]}]) {
            assert.equal(api.classifyEvidenceScore(value).code,null);
        }
        assert.equal(api.classifyEvidenceScore(score(card('BA1','STAND_ALONE'),card('PM1','MODERATE'))).label,'分类待核查');
    });
    await check('unresolved evidence leaves the current suggestion explicitly provisional',()=>{
        const missing=card('PM3','NONE',{evaluationResult:{status:'NOT_EVALUATED',reasonCode:'PM3_PHASE_UNKNOWN'}});
        const value=score(card('PS3','STRONG'),card('PM1','MODERATE'),missing);
        assert.equal(api.classifyEvidenceScore(value).code,'LP'); assert.equal(api.classifyEvidenceScore(value).provisional,true);
        const html=renderToStaticMarkup(React.createElement(api.Summary,{score:value}));
        assert.ok(html.includes('证据待补全')); assert.ok(!html.includes('患者确诊'));
    });
    await check('score summary retains the suggestion and five-level scale without score details', () => {
        const html = renderToStaticMarkup(React.createElement(api.Summary,{score:score(card('PM1','MODERATE'))}));
        assert.ok(html.includes('+2 分') && html.includes('data-classification="VUS"'));
        assert.ok(!html.includes('计分明细') && !html.includes('计分方法与出处') && !html.includes('acmg-score-title'));
        for (const level of ['良性','可能良性','意义未明','可能致病','致病']) assert.ok(html.includes(level));
        assert.equal((html.match(/aria-current="step"/g)||[]).length,1);
        const pending=renderToStaticMarkup(React.createElement(api.Summary,{score:score()}));
        assert.ok(pending.includes('待分类')); assert.ok(!pending.includes('aria-current="step"'));
    });
    await check('absent sample summary is hidden but real source conflicts remain visible', () => {
        assert.equal(renderToStaticMarkup(React.createElement(api.Sample,{candidate:{}})),'');
        assert.ok(renderToStaticMarkup(React.createElement(api.Sample,{candidate:{blockers:['ANALYSIS_VCF_CALL_SOURCE_CONFLICT']}})).includes('来源关联不一致'));
        assert.equal(api.inspectSampleCallSummary({}).state,'ABSENT');
    });
    await check('compact adopted PM2 keeps the local exome scope and preserves the complete original',()=>{
        const e=freeze({...entry('PM2','SUPPORTING',{evaluationResult:{status:'MET',reasonCode:'PM2_PROJECT_LOCAL_ABSENCE_MET'}}),
            summary:'本地 EXOMES 未收录该精确 SNV；未证明祖源匹配或全库缺失。'});
        const before=JSON.stringify(e), copy=api.workbenchEvidenceCopy({code:'PM2'},e);
        assert.equal(copy.label,'已采用'); assert.ok(copy.summary.includes('本次外显子组数据'));
        assert.ok(copy.summary.includes('覆盖')); assert.ok(!copy.summary.includes('全库未检出'));
        assert.equal(JSON.stringify(e),before);
    });
    await check('technical PP3 code becomes concise evidence text only after adoption',()=>{
        const e=entry('PP3','STRONG',{evaluationResult:{status:'MET',reasonCode:'PRESELECTED_MECHANISM_SUPPORTS_PP3'}});
        assert.equal(api.workbenchEvidenceCopy({code:'PP3'},e).summary,'计算预测支持有害影响。');
        const pending=api.workbenchEvidenceCopy({code:'PP3'},{...e,evaluation:{...e.evaluation,reviewRequired:true}});
        assert.equal(pending.label,'待复核'); assert.ok(!pending.summary.includes('支持有害影响'));
    });
    await check('missing, conflict and execution failure keep their concrete causes and distinct states',()=>{
        for(const [reasonCode,label,summary] of [
            ['PHASE_INFORMATION_REQUIRED','资料不足','尚未确认两个变异是否位于反式。'],
            ['SOURCE_DATA_CONFLICT','资料冲突','两份报告的父亲检测结果不一致。'],
            ['SOURCE_READ_FAILED','执行失败','补充材料下载超时。'],
        ]) {
            const e={...entry('PM3','NONE',{evaluationResult:{status:'NOT_EVALUATED',reasonCode}}),summary};
            assert.deepEqual(api.workbenchEvidenceCopy({code:'PM3'},e),{label,summary});
        }
    });
    await check('unknown technical codes and boilerplate do not create scientific findings',()=>{
        const e={...entry('PS3','STRONG'),summary:'满足本次规则条件；实际强度和依据以保存结果为准。'};
        assert.equal(api.workbenchEvidenceCopy({code:'PS3'},e).summary,'');
        const missing=entry('PM3','NONE',{evaluationResult:{status:'NOT_EVALUATED',reasonCode:'UNRECOGNIZED_REASON_REQUIRED'}});
        assert.ok(!api.workbenchEvidenceCopy({code:'PM3'},missing).summary.includes('UNRECOGNIZED_REASON_REQUIRED'));
    });
    await check('variant identity follows the exact selected background without inventing transcript or HGVS',()=>{
        const c=freeze({candidateId:'c',attempts:[{context:{gene:{symbol:'GENEA'},transcript:{accession:'NM_001',version:2,
            annotation:{hgvsc:'c.1A>G',hgvsp:'p.Met1Val'}},variant:{normalizedAllele:{assembly:'GRCh38',contig:'1',position:5,reference:'A',alternate:'G'}},moi:'AUTOSOMAL_DOMINANT'}},
            {context:{gene:{symbol:'GENEB'}}}]});
        const first=api.workbenchVariantIdentity(c,0),second=api.workbenchVariantIdentity(c,1);
        assert.equal(first.transcript,'NM_001.2');assert.equal(first.hgvs,'c.1A>G · p.Met1Val');assert.equal(first.moi,'AD');
        assert.equal(first.coordinate,'chr1-5 A>G');
        assert.equal(second.gene,'GENEB'); assert.equal(second.transcript,'');assert.equal(second.hgvs,'');assert.equal(second.coordinate,'c');
    });
    await check('variant labels share chr-position ref>alt without rewriting source identities',()=>{
        const a=freeze({contig:'19',position:50323670,reference:'G',alternate:'A'}),before=JSON.stringify(a);
        assert.equal(api.formatVariantCoordinate(a),'chr19-50323670 G>A');
        assert.equal(api.formatVariantCoordinate({...a,contig:'chr19'}),'chr19-50323670 G>A');
        assert.equal(api.formatVariantCoordinate({...a,contig:'CHRx'}),'chrX-50323670 G>A');
        assert.equal(api.formatVariantCoordinate({...a,contig:undefined,chromosome:'19'}),'chr19-50323670 G>A');
        assert.equal(api.formatVariantCoordinate({contig:'19',position:null},'unavailable'),'unavailable');
        const c={candidateId:'internal:unchanged',attempts:[{context:{variant:{normalizedAllele:a}}}]};
        assert.ok(api.savedVariantLabel(c).allele.includes('chr19-50323670 G>A'));
        assert.equal(api.workbenchVariantIdentity(c).coordinate,'chr19-50323670 G>A');
        assert.equal(c.candidateId,'internal:unchanged');assert.equal(JSON.stringify(a),before);
    });
    await check('unbound background displays its own disease and the same candidate coordinate',()=>{
        const c=freeze({candidateId:'c',attempts:[{hypothesisId:'h1',context:null,binding:{candidateId:'c',hypothesisId:'h1',
            symbol:'GENE',mondoId:'MONDO:0000001',normalizedMoi:'AUTOSOMAL_DOMINANT'}},
            {context:{variant:{inputAllele:{assembly:'GRCh38',contig:'1',position:5,reference:'A',alternate:'G'}},
                disease:{mondoId:'MONDO:0000002'},transcript:{accession:'NM_001',version:1}}}]});
        assert.equal(api.savedBackgrounds(c)[0].label,'MONDO:0000001');
        const identity=api.workbenchVariantIdentity(c);
        assert.equal(identity.coordinate,'chr1-5 A>G'); assert.equal(identity.transcript,''); assert.equal(identity.moi,'AD');
        assert.equal(identity.diseaseId,'MONDO:0000001');
    });
    await check('variant header survives indexed loading without showing a stale classification',()=>{
        const html=renderToStaticMarkup(React.createElement(api.Summary,{score:null,identity:{gene:'GENE',coordinate:'1:5 A>G',assembly:'GRCh38'}}));
        assert.ok(html.includes('acmg-variant-identity') && html.includes('结果读取中'));
        assert.ok(html.includes('data-classification="LOADING"')); assert.ok(!html.includes('计分明细'));
    });
    await check('concise details omit generic legacy copy but retain actual validation issues',()=>{
        const e=entry('PM2','SUPPORTING');
        assert.ok(renderToStaticMarkup(React.createElement(api.Decision,{entry:e})).includes('原保存状态、强度及原因'));
        assert.equal(renderToStaticMarkup(React.createElement(api.Decision,{entry:e,concise:true})),'');
        const issue={...e,displayIssue:true,summary:'保存结果与当前背景不一致。'};
        assert.notEqual(renderToStaticMarkup(React.createElement(api.Decision,{entry:issue,concise:true})),'');
    });
    await check('verified follow-up citations are scoped to event grouping and share the same source pin', () => {
        const paper = (paperId, hash) => ({ paperId, sourceSha256: hash.repeat(64), role: 'PAPER_PRIMARY', kind: 'SOURCE_UNIT',
            url: `https://pmc.ncbi.nlm.nih.gov/articles/${paperId}/`, quote: 'Source text', locator: 'Table 1',
            title: 'Synthetic source', locationLabel: 'Table 1', linkScope: 'ARTICLE_LANDING_PAGE', scopeOnly: false });
        const primary = paper('PMC999999999','a'), followUp = paper('PMC999999998','b');
        const field = (path, value, sourceEvidence = [primary]) => ({ path, value, sourceEvidence, citations: [],
            origin: 'REVIEWED_ASSESSMENT', subject: 'PROBAND', verification: 'VERIFIED', availability: 'REPORTED', issues: [] });
        const material = { schemaVersion: 'denovo-core-display-v1', caseKey: 'PMC999999999#Case A',
            dataClass: 'PUBLIC_PAPER_RESEARCH', status: 'READY_FOR_EVALUATION', criterionDecision: 'NOT_RUN',
            documentSha256: 'c'.repeat(64), ledgerSha256: 'd'.repeat(64), blockers: [], coverageLimitations: [],
            fields: ['paper','case','gene','variant','proband','mother','father','testing','parentage','phenotype','family','overlap']
                .map(group => field(`/groups/${group}/field`, 'value')) };
        assert.ok(api.validCoreMaterial(material));
        material.fields.push(field('/groups/overlap/followUpReports', ['PMC999999998 · Pt 1'], [primary, followUp]));
        material.fields.push(field('/groups/overlap/eventGrouping', 'VERIFIED_FOLLOW_UP_SAME_EVENT', [primary, followUp]));
        assert.ok(api.validCoreMaterial(material));
        const foreignFact = structuredClone(material);
        foreignFact.fields.find(f => f.path === '/groups/mother/field').sourceEvidence = [followUp];
        assert.equal(api.validCoreMaterial(foreignFact), false);
        const wrongPin = structuredClone(material);
        wrongPin.fields.at(-1).sourceEvidence = [primary, paper(followUp.paperId, 'e')];
        assert.equal(api.validCoreMaterial(wrongPin), false);
        const missingGroup = {...material, fields: material.fields.slice(0, -1)};
        assert.equal(api.validCoreMaterial(missingGroup), false);
    });
    const fixture = process.argv[2];
    if (fixture) await check('captured saved run renders actual integrated workbench, scores and rows without modifying history', () => {
        const bytes = fs.readFileSync(fixture), run = freeze(JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,'')));
        const before = JSON.stringify(run), html = renderToStaticMarkup(React.createElement(api.Workbench,{run,criteria:run.enabledCriteria}));
        assert.ok(html.includes('ACMG得分排序') && html.includes('acmg-score-summary') && html.includes('evidence-result-row'));
        assert.match(html,/<button[^>]*aria-pressed="true"[^>]*>ACMG得分排序<\/button>/);
        assert.ok(html.includes('data-classification='));
        const scores=run.candidates.map(c=>api.representativeScore(c,api.savedBackgrounds(c).map(b=>api.scoreBackground(c,run.enabledCriteria,run,b.index))));
        const leader=api.sortScoredCandidates(run.candidates,scores)[0], label=api.savedVariantLabel(leader.candidate);
        const plain=html.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g,'').replace(/<[^>]+>/g,'');
        assert.ok(plain.includes('#1 · '+label.gene),'first ranked candidate follows score');
        const section=html.slice(html.indexOf('aria-label="当前背景证据"'));
        assert.ok(section.includes(label.gene),'initial details follow first ranked candidate');
        const expected=api.classifyEvidenceScore(leader.score).code || 'PENDING';
        assert.ok(section.includes(`data-classification="${expected}"`),'initial classification follows selected background');
        assert.ok(html.includes('acmg-variant-identity') && html.includes('acmg-variant-coordinate'));
        for(const text of ['PM2_PROJECT_LOCAL_ABSENCE_MET','PRESELECTED_MECHANISM_SUPPORTS_PP3','实际强度和依据以保存结果为准','规则评价已完成']) assert.ok(!html.includes(text));
        for(const text of ['四类主结论 · 仅统计','未审核、未采用及读取问题不计作','按采用结论与具体原因排序；','本记录未保存样本报告摘要。']) assert.ok(!html.includes(text));
        assert.equal(JSON.stringify(run),before); assert.deepEqual(fs.readFileSync(fixture),bytes);
    });
    console.log(`${checks} score and component checks passed. No business/model/network execution or browser acceptance.`);
})().catch(error=>{console.error(error);process.exitCode=1;});
