import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
let checked = 0, rejected = 0, automatic = 0;
for (const name of fs.readdirSync(process.argv[2]).filter(n => n.endsWith('.json'))) {
    const row = JSON.parse(fs.readFileSync(path.join(process.argv[2], name)));
    if (['NATIVE_TARGET_GAMT_C1_MODERATE.json', 'NATIVE_TARGET_GAMT_C2_MODERATE.json'].includes(name)) {
        const prep = row.details.pvs1Facts.automaticPreparation;
        const details = h.pvs1StartLossAutomaticDetails(prep);
        assert.ok(details.some(([label]) => label === '遗传方式出处'));
        assert.ok(details.some(([label]) => label === '专家转录本对应'));
        for (const mutate of [
            e => { e.expertAssertions.entries[0].inheritanceScopeJoin.resolvedMode = 'AUTOSOMAL_DOMINANT'; },
            e => { e.expertAssertions.entries[0].inheritanceScopeJoin.originalSourceModified = true; },
            e => { e.expertAssertions.entries[0].inheritanceScopeJoin.records[0].sourceConfidence = 'limited'; },
            e => { e.expertAssertions.transcriptBinding.verifiedModelSha256 = 'f'.repeat(64); },
            e => { e.expertAssertions.transcriptBinding.sourceTranscript.version = 0; },
            e => { e.expertAssertions.transcriptBinding.selectedTranscript.version = 999; },
        ]) {
            const bad = structuredClone(row); mutate(bad.details.pvs1Facts.expertAssessment);
            assert.deepEqual(h.pvs1SelectedDetails(bad), []); rejected++;
        }
    }
    if (row.schemaVersion === 'pvs1-start-loss-automatic-preparation-v1') {
        const before = JSON.stringify(row);
        const details = h.pvs1StartLossAutomaticDetails(row);
        assert.ok(details.some(([label]) => label === '自动上游检索'), name);
        assert.ok(details.some(([label]) => label === '自动准备仍缺的依据'), name);
        if (name === 'AUTO_NATIVE_MECHANISM.json') {
            assert.ok(details.find(([label]) => label === '起始丢失机制核查')?.[1].includes('有证据支持'));
            assert.ok(details.find(([label]) => label === '机制核查边界')?.[1].includes('不单独'));
            assert.equal(row.runtimeEligible, false);
        }
        if (name === 'AUTO_NATIVE_INTRON_SCOPE.json') {
            const scope = details.find(([label]) => label === '区间外内含子记录')?.[1];
            assert.ok(scope?.includes('7220051'));
            assert.ok(scope?.includes('不代表已排除剪接影响'));
            assert.equal(row.upstreamReview.negativeCoverageGranted, false);
            assert.equal(row.upstreamReview.sourceEnumerationComplete, false);
        }
        if (name === 'AUTO_NATIVE_INTRON_DELETION.json') {
            const scope = details.find(([label]) => label === '区间外内含子记录')?.[1];
            assert.ok(scope?.includes('135998099–135998103'));
            assert.ok(scope?.includes('不代表已排除剪接影响'));
            assert.equal(row.upstreamReview.negativeCoverageGranted, false);
            assert.equal(row.runtimeEligible, false);
        }
        if (name === 'AUTO_NATIVE_ORIGINAL_REPLAY.json') {
            assert.ok(details.find(([label]) => label === '来源分类重算')?.[1].includes('重现来源分类'));
            assert.ok(details.find(([label]) => label === '专家原件独立依据')?.[1].includes('跨来源冲突需另行核查'));
            assert.ok(details.find(([label]) => label === '专家原件独立依据')?.[1].includes('不表示已重新分析原始实验'));
            assert.ok(!details.find(([label]) => label === '原始判定的具体缺项')?.[1].includes('实际采用强度缺失'));
        }
        if (name === 'AUTO_NATIVE_CLINVAR_REFERENCE.json') {
            assert.equal(row.upstreamReview.status, 'WITNESS_VALIDATED');
            assert.ok(details.find(([label]) => label === 'ClinVar逐提交交叉核查')?.[1].includes('不重复计数'));
            assert.ok(details.find(([label]) => label === '已采用的上游参考')?.[1].includes('不单独授予PVS1'));
            assert.equal(row.runtimeEligible, false, 'reference existence does not establish target scoring eligibility');
        }
        if (name === 'AUTO_NATIVE_EVIDENCE_GRAPH.json') {
            const gaps = details.find(([label]) => label === '原始判定的具体缺项');
            assert.ok(gaps?.[1].includes('实际采用强度缺失'));
            assert.ok(gaps?.[1].includes('多条证据使用相同观察'));
            const malformed = structuredClone(row);
            malformed.upstreamReview.records[0].nativeAssessment.sourceEvidenceAudit.unresolvedReasons = [null, {}];
            assert.doesNotThrow(() => h.pvs1StartLossAutomaticDetails(malformed));
            const replayed = structuredClone(row);
            replayed.upstreamReview.records[0].nativeAssessment.originalRuleReplay = {
                status: 'ORIGINAL_CLASSIFICATION_REPLAYED', independenceEstablished: false,
                reasonCode: 'ORIGINAL_CLASSIFICATION_REPLAYED_INDEPENDENCE_NOT_ESTABLISHED'
            };
            const replayDetails = h.pvs1StartLossAutomaticDetails(replayed);
            assert.ok(replayDetails.find(([label]) => label === '来源分类重算')?.[1].includes('独立性仍待核查'));
        }
        for (const field of ['runtimeEligible', 'scientificEvaluationPerformed']) {
            const bad = structuredClone(row); bad[field] = true;
            assert.deepEqual(h.pvs1StartLossAutomaticDetails(bad), [], `${name}: preparation must not impersonate scoring`);
        }
        for (const section of ['transcriptReview', 'upstreamReview']) {
            const bad = structuredClone(row);
            bad[section] = { reasonCode: 'INVALID', [section === 'transcriptReview' ? 'findings' : 'records']: [null] };
            assert.deepEqual(h.pvs1StartLossAutomaticDetails(bad), [], `${name}: malformed source rows`);
        }
        assert.equal(JSON.stringify(row), before); automatic++; continue;
    }
    if (row.criterionId !== 'ACMG_AMP_2015:PVS1') continue;
    const before = JSON.stringify(row);
    const details = h.pvs1SelectedDetails(row);
    assert.ok(details.length > 0, name);
    if (row.details?.pvs1Facts?.methodVersion === '1.3.0') {
        assert.ok(details.some(([label]) => label === '专家原文与定位'));
        for (const mutate of [
            r => { r.status = 'MET'; },
            r => { r.appliedStrength = 'SUPPORTING'; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries = []; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].source.rawSha256 = ''; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].claims[0].sourceText = ''; },
            r => { r.details.pvs1Facts.expertAssessment.sequence.status = 'CONFLICT'; },
        ]) {
            const bad = structuredClone(row); mutate(bad);
            assert.deepEqual(h.pvs1SelectedDetails(bad), [], `${name}: contradictory expert exclusion`); rejected++;
        }
    }
    if (row.details?.pvs1Facts?.methodVersion === '1.4.0') {
        assert.ok(details.some(([label]) => label === '起始丢失自动资料准备'), 'published preparation remains visible');
        assert.ok(details.some(([label, value]) => label === '采用强度' && value === row.appliedStrength));
        assert.ok(details.some(([label]) => label === '核查来源'));
        assert.ok(details.some(([label]) => label === '专家原文与定位'));
        for (const mutate of [
            r => { r.appliedStrength = 'VERY_STRONG'; },
            r => { r.details.pvs1Facts.expertAssessment.publishedRuleCheck.verifiedModelSha256 = ''; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].source.rawSha256 = ''; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].startLossRule.strength = 'VERY_STRONG'; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].startLossRule.claims = []; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].policyDisposition = 'PROHIBITED'; },
            r => { r.details.pvs1Facts.expertAssessment.expertAssertions.entries[0].startLossRule.requiredRestartProteinPosition = 10; },
            r => { r.details.pvs1Facts.expertAssessment.sequence.referenceCodon = 'AAA'; },
            r => { r.reasonCode = 'DIFFERENT_DECISION'; },
        ]) {
            const bad = structuredClone(row); mutate(bad);
            assert.deepEqual(h.pvs1SelectedDetails(bad), [], `${name}: contradictory published rule result`); rejected++;
        }
    } else if (row.details?.pvs1Facts?.methodVersion === '1.5.0') {
        const mechanism = row.details.pvs1Facts.automaticAssessment.preparation.mechanismAssessment;
        const clinGenMechanism = ['CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_VALIDITY',
            'CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_G2P_ASSOCIATION'].includes(mechanism.basis);
        const findingsLabel = clinGenMechanism ? 'G2P路径逐条核查' : '机制来源逐条核查';
        const findingsText = details.find(([label]) => label === findingsLabel)?.[1];
        assert.ok(findingsText, `${name}: ${findingsLabel}`);
        assert.ok(Array.isArray(mechanism.findings) && mechanism.findings.length > 0);
        for (const finding of mechanism.findings)
            assert.ok(findingsText.includes(finding.recordId), `${name}: mechanism source record ${finding.recordId}`);
        if (clinGenMechanism) {
            const c = mechanism.clingenAssessment, b = c.binding;
            const mechanismText = details.find(([label]) => label === 'ClinGen疾病机制依据')?.[1];
            assert.ok(mechanismText?.includes(b.geneSymbol) && mechanismText.includes(b.hgncId) && mechanismText.includes(b.mondoId));
            const sourcesText = details.find(([label]) => label === 'ClinGen机制原件出处')?.[1];
            for (const source of b.sources)
                assert.ok(sourcesText?.includes(source.sourceUrl) && sourcesText.includes(source.sha256) &&
                    sourcesText.includes(`原记录第${source.sourceRow}行`), `${name}: independent ClinGen source retained`);
            assert.ok(details.find(([label]) => label === 'G2P原始记录保留')?.[1].includes('没有将inferred改写为evidence'));
            if (c.associationSource === 'G2P') {
                const associationText = details.find(([label]) => label === 'G2P疾病关联出处')?.[1];
                assert.ok(associationText?.includes(c.associationAnchor.binding.source.url) &&
                    associationText.includes(c.associationAnchor.binding.source.sha256));
                for (const record of c.associationAnchor.records)
                    assert.ok(associationText.includes(record.recordId) && associationText.includes(record.sourceRecordSha256),
                        `${name}: independent G2P association record retained`);
            }
        }
        assert.ok(details.some(([label, value]) => label === '采用强度' && value === row.appliedStrength));
        for (const mutate of [
            r => { r.appliedStrength = 'VERY_STRONG'; },
            r => { r.details.pvs1Facts.automaticAssessment.preparation.mechanismAssessment.status = 'UNRESOLVED'; },
            r => { r.details.pvs1Facts.automaticAssessment.preparation.transcriptReview.complete = false; },
            r => { r.details.pvs1Facts.automaticAssessment.preparation.upstreamReview.complete = false; },
            r => { r.details.pvs1Facts.automaticAssessment.preparation.expertAssertions.status = 'UNAVAILABLE'; },
            r => { r.details.pvs1Facts.automaticAssessment.verifiedModelSha256 = ''; },
            r => { r.details.pvs1Facts.automaticAssessment.sequence.referenceCodon = 'AAA'; }
        ]) {
            const bad = structuredClone(row); mutate(bad);
            assert.deepEqual(h.pvs1SelectedDetails(bad), [], `${name}: contradictory automatic result`); rejected++;
        }
    } else if (row.status === 'MET') {
        assert.ok(details.some(([label, value]) => label === '采用强度' && value === row.appliedStrength));
        assert.ok(details.some(([label]) => label === '核查来源'));
        for (const mutate of [
            r => { r.appliedStrength = 'VERY_STRONG'; },
            r => { r.details.pvs1Facts.startLossAssessment.upstreamSearchComplete = 'false'; },
            r => { r.details.pvs1Facts.startLossAssessment.runtimeMaterialSha256 = ''; },
            r => { r.details.pvs1Facts.startLossAssessment.sources = []; },
            r => { r.details.pvs1Facts.startLossAssessment.qualifyingReferenceCount = 99; },
            r => { delete r.details.pvs1Facts.startLossAssessment.materialEvidence; },
            r => { r.reasonCode = 'DIFFERENT_DECISION'; },
        ]) {
            const bad = structuredClone(row); mutate(bad);
            assert.deepEqual(h.pvs1SelectedDetails(bad), [], `${name}: contradictory scored result`); rejected++;
        }
        const absent = structuredClone(row); absent.details.pvs1Facts.startLossAssessment.methodVersion = '99';
        assert.deepEqual(h.pvs1SelectedDetails(absent), []); rejected++;
    }
    if (row.reasonCode.startsWith('PVS1_START_LOSS_RUNTIME_')) {
        const text = h.reasonHelp(row.reasonCode, 'PVS1_START_LOSS_V1');
        assert.ok(text && !text.includes('原因待核实') && !text.includes(row.reasonCode), name);
    }
    assert.equal(JSON.stringify(row), before); checked++;
}
// Previous sequence-only results remain viewable with their original meaning.
const old = { criterionId: 'ACMG_AMP_2015:PVS1', status: 'NOT_EVALUATED', appliedStrength: 'NONE', details: { pvs1Facts: {
    schemaVersion: 'pvs1-selected-facts-v1', methodVersion: '1.2.0', scope: 'REVIEWED_SELECTED_TRANSCRIPT', clinicalValidationCompleted: false,
    knowledgeStatus: 'NOT_CHECKED', knowledgeReason: 'PVS1_KNOWLEDGE_ASSESSMENT_UNAVAILABLE',
    doubleCountingConstraint: 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE',
    startLossAssessment: { schemaVersion: 'pvs1-start-loss-sequence-facts-v1', methodVersion: '1.0.0', status: 'AVAILABLE',
        clinicalScoringPerformed: false, selectedTranscript: 'NM_000001.1', strand: 1, cdsPosition: 1, referenceCodon: 'ATG', alternateCodon: 'GTG',
        candidateRestartCdsPosition: 10, transcriptReviewStatus: 'NOT_PREPARED', upstreamReferenceReviewStatus: 'NOT_PREPARED',
        reviewReasonCode: 'PVS1_START_LOSS_FUNCTIONAL_TRANSCRIPT_AND_REFERENCE_REVIEW_REQUIRED' }
} } };
assert.ok(h.pvs1SelectedDetails(old).length > 0);
console.log(`PASS: ${checked} Java start-loss projections, ${rejected} contradictory projections rejected, ${automatic} automatic preparations, legacy sequence-only history preserved.`);
