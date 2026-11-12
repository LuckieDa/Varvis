import React from 'react';
import { Alert, Chip, Paper, Stack, Typography } from '@mui/material';

const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isSafeInteger(value) && value >= 0 && value <= 2147483647;
const assemblies = new Set(['GRCh37', 'GRCh38']);
const fieldCodes = new Set(['GT', 'DP', 'GQ', 'AD', 'AB', 'PS', 'AD:REF', 'AD:TARGET', 'PS:UNPHASED']);
const positiveCalls = new Set(['HET_TARGET', 'HOM_TARGET', 'HEMI_TARGET', 'TARGET_WITH_OTHER_ALT']);
const negativeCalls = new Set(['HOM_REF', 'HEMI_REF', 'OTHER_ALT_ONLY']);
const limitations = ['ORIGINAL_SOURCE_ASSOCIATION_REQUIRES_CALLER_VERIFICATION',
    'REPORTED_ALT_IS_NOT_CLINICAL_OR_SCIENTIFIC_ADOPTION', 'INDEPENDENT_ALLELE_EVALUATION_IS_SEPARATE'];
const optional = (value, valid) => value === null || valid(value);
const codes = value => Array.isArray(value) && value.length <= fieldCodes.size &&
    value.every(code => fieldCodes.has(code)) && new Set(value).size === value.length;

function coherentFacts(summary) {
    const { source, quality, reasonCode, targetAltReported: reported, genotypeComplete: complete, targetAlleleCall: call } = summary;
    const is = (r, c) => reported === r && complete === c;
    if (source.schemaVersion === null) return reasonCode === 'ANALYSIS_VCF_CALL_MISSING' && call === null && is(null, null);
    if (!source.originalAnalysisAlleleKeySha256 || !source.originalAnalysisAssembly)
        return reasonCode === 'ORIGINAL_ANALYSIS_ALLELE_MISSING_OR_INCOMPLETE' && is(null, null);
    if (source.assembly !== source.originalAnalysisAssembly)
        return reasonCode === 'ANALYSIS_ALLELE_ASSEMBLY_MISMATCH' && is(null, null);
    if (source.matchStatus !== 'MATCHED') return reasonCode === `ANALYSIS_VCF_${source.matchStatus}` && call === null && is(null, null);
    if (call === null) return reasonCode === 'SOURCE_GENOTYPE_FACTS_MISSING' && is(null, null);
    if (call === 'NO_CALL') return reasonCode === 'SOURCE_NO_CALL' && is(null, false);
    if (call === 'UNSUPPORTED') return reasonCode === 'SOURCE_GENOTYPE_UNSUPPORTED' && is(null, false);
    if (quality.invalidFields.includes('GT') || quality.missingFields.includes('GT') && call !== 'PARTIAL_TARGET')
        return reasonCode === 'SOURCE_GENOTYPE_FLAGS_INCOMPATIBLE' && is(null, null);
    if (positiveCalls.has(call)) return reasonCode === 'SOURCE_REPORTS_TARGET_ALT' && is(true, true);
    if (negativeCalls.has(call)) return reasonCode === 'SOURCE_DOES_NOT_REPORT_TARGET_ALT' && is(false, true);
    return call === 'PARTIAL_TARGET' && reasonCode === 'SOURCE_REPORTS_TARGET_ALT_WITH_INCOMPLETE_GENOTYPE' && is(true, false);
}

/** Checks saved transport consistency only. A snapshot digest is not source authentication.
 * No raw genotype is parsed and no source/candidate association is reconstructed here.
 */
export function inspectSampleCallSummary(candidate) {
    const keys = ['sampleCallSummary', 'sampleCallAssociationStatus', 'sampleCallSourceSnapshotSha256'];
    if (!keys.some(key => Object.hasOwn(candidate || {}, key))) {
        const blockers = [candidate?.blockers, ...(Array.isArray(candidate?.attempts) ? candidate.attempts : []).map(attempt => attempt?.blockers)]
            .flatMap(value => Array.isArray(value) ? value : []).map(value => typeof value === 'string' ? value : value?.reasonCode);
        const issue = blockers.some(code => ['ANALYSIS_VCF_CALL_SOURCE_CONFLICT', 'EXECUTION_SAMPLE_FILTER_BINDING_CONFLICT', 'EXECUTION_SNAPSHOT_ALLELE_CONFLICT'].includes(code))
            ? 'SOURCE_CONFLICT' : blockers.includes('ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED') ? 'ASSOCIATION_REQUIRED'
                : blockers.some(code => ['EXECUTION_SOURCE_UNAVAILABLE', 'EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE'].includes(code)) ? 'SOURCE_UNAVAILABLE' : undefined;
        return { state: 'ABSENT', ...(issue ? { issue } : {}) };
    }
    const summary = candidate?.sampleCallSummary, source = summary?.source, quality = summary?.quality;
    const unavailable = { state: 'UNVERIFIED', issue: 'FIELDS_INVALID' };
    if (!hash(candidate.sampleCallSourceSnapshotSha256) ||
        !object(summary) || summary.schemaVersion !== 'candidate-allele-call-summary-v1' || summary.scope !== 'ANALYSIS_VCF_SOURCE_REPORTED_FACTS_ONLY' ||
        !object(source) || !object(quality) || !['targetAltReported', 'genotypeComplete'].every(key => Object.hasOwn(summary, key) &&
            [true, false, null].includes(summary[key])) ||
        !optional(source.schemaVersion, value => value === 'analysis-vcf-call-v1') ||
        !optional(source.matchStatus, value => ['MATCHED', 'NOT_FOUND', 'AMBIGUOUS'].includes(value)) ||
        !optional(source.assembly, value => assemblies.has(value)) || !optional(source.originalAnalysisAssembly, value => assemblies.has(value)) ||
        !optional(source.originalAnalysisAlleleKeySha256, hash) || !optional(source.targetAlleleIndex, value => integer(value) && value > 0) ||
        !optional(source.matchingRecordCount, integer) || !optional(source.matchedRecordOrdinal, value => integer(value) && value > 0) ||
        !optional(source.artifactRole, value => value === 'EXOMISER_ANALYSIS_VCF') ||
        !optional(source.provenance, value => value === 'ANALYSIS_VCF_EXACT_ALLELE') ||
        !optional(summary.targetAlleleCall, value => positiveCalls.has(value) || negativeCalls.has(value) || ['PARTIAL_TARGET', 'NO_CALL', 'UNSUPPORTED'].includes(value)) ||
        !optional(summary.candidateRelativeGenotype, value => typeof value === 'string' && value.length > 0 && value.length <= 128) ||
        !optional(quality.filterEvaluation, value => ['PASS', 'FILTERED', 'UNASSESSED'].includes(value)) ||
        !['depth', 'genotypeQuality', 'referenceDepth', 'targetAlleleDepth'].every(key => optional(quality[key], integer)) ||
        !optional(quality.alleleBalance, value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1) ||
        !codes(quality.missingFields) || !codes(quality.invalidFields) || !Array.isArray(summary.usageLimitations) ||
        !limitations.every(value => summary.usageLimitations.includes(value))) return unavailable;
    if (source.schemaVersion === null) {
        if (['matchStatus', 'assembly', 'targetAlleleIndex', 'matchingRecordCount', 'matchedRecordOrdinal', 'artifactRole', 'provenance'].some(key => source[key] !== null)) return unavailable;
    } else if (source.artifactRole !== 'EXOMISER_ANALYSIS_VCF' || source.provenance !== 'ANALYSIS_VCF_EXACT_ALLELE' || !assemblies.has(source.assembly) || !source.matchStatus) return unavailable;
    if (source.matchStatus === 'MATCHED' && (source.matchingRecordCount !== 1 || !source.targetAlleleIndex || !source.matchedRecordOrdinal)) return unavailable;
    if (source.matchStatus === 'NOT_FOUND' && source.matchingRecordCount !== 0 || source.matchStatus === 'AMBIGUOUS' && !(source.matchingRecordCount >= 2)) return unavailable;
    if (['NOT_FOUND', 'AMBIGUOUS'].includes(source.matchStatus) &&
        (source.targetAlleleIndex !== null || source.matchedRecordOrdinal !== null || summary.targetAlleleCall !== null || summary.candidateRelativeGenotype !== null)) return unavailable;
    if (source.schemaVersion === null || ['NOT_FOUND', 'AMBIGUOUS'].includes(source.matchStatus)) {
        if (summary.targetAlleleCall !== null || summary.candidateRelativeGenotype !== null || quality.filterEvaluation !== null ||
            ['depth', 'genotypeQuality', 'referenceDepth', 'targetAlleleDepth', 'alleleBalance'].some(key => quality[key] !== null) ||
            quality.missingFields.length || quality.invalidFields.length) return unavailable;
    }
    if (source.matchStatus === 'MATCHED' && summary.targetAlleleCall !== null && summary.candidateRelativeGenotype === null) return unavailable;
    if (!coherentFacts(summary)) return { state: 'UNVERIFIED', issue: 'FACT_CONFLICT' };
    const associated = candidate.sampleCallAssociationStatus === 'VERIFIED_ORIGINAL_ANALYSIS_SOURCE';
    if (source.matchStatus !== 'MATCHED') {
        if (candidate.sampleCallAssociationStatus !== undefined && candidate.sampleCallAssociationStatus !== null)
            return { state: 'UNVERIFIED', issue: 'ASSOCIATION_INVALID' };
        return { state: 'UNKNOWN', sourceState: source.matchStatus || 'CALL_MISSING', targetAltReported: null, genotypeComplete: null };
    }
    if (!associated || source.schemaVersion !== 'analysis-vcf-call-v1' || !hash(source.originalAnalysisAlleleKeySha256) ||
        !assemblies.has(source.originalAnalysisAssembly) || source.assembly !== source.originalAnalysisAssembly)
        return { state: 'UNVERIFIED', issue: 'ASSOCIATION_INVALID' };
    const numeric = [['depth', 'DP 深度'], ['genotypeQuality', 'GQ'], ['referenceDepth', '参考等位深度'], ['targetAlleleDepth', '目标 ALT 深度'], ['alleleBalance', '目标 ALT 比例']]
        .filter(([key]) => quality[key] !== null).map(([key, label]) => ({ label, value: quality[key] }));
    return { state: 'READY', targetAltReported: summary.targetAltReported, genotypeComplete: summary.genotypeComplete,
        filterEvaluation: quality.filterEvaluation, numeric, missingFields: quality.missingFields, invalidFields: quality.invalidFields };
}

export default function SampleCallSummary({ candidate }) {
    const view = inspectSampleCallSummary(candidate);
    if (view.state === 'ABSENT') {
        const sourceNotice = { SOURCE_CONFLICT: '原保存记录报告当前候选的来源关联不一致；请核对该候选的原始记录。',
            ASSOCIATION_REQUIRED: '当前候选与其原始记录的关联尚未核实。',
            SOURCE_UNAVAILABLE: '当前原始分析来源不可用或尚未通过核查。' }[view.issue];
        return sourceNotice ? <Alert severity="warning" data-testid="sample-call-absent">{sourceNotice}</Alert> : null;
    }
    if (view.state === 'UNKNOWN') return <Alert severity="info" data-testid="sample-call-unknown">
        {{ NOT_FOUND: '原始分析中未匹配到当前候选的记录。', AMBIGUOUS: '原始分析中存在多个匹配记录，尚未建立当前候选的唯一记录关联。',
            CALL_MISSING: '当前记录未保存可用的原始样本调用信息。' }[view.sourceState]}
        当前原始记录是否报告目标 ALT 尚无法确定，基因型完整性及质量待核实。
    </Alert>;
    if (view.state !== 'READY') return <Alert severity="warning" data-testid="sample-call-unverified">
        {view.issue === 'FACT_CONFLICT' ? '样本报告摘要中的目标 ALT、完整性或来源状态相互不一致。' : view.issue === 'ASSOCIATION_INVALID'
            ? '样本报告摘要尚未具有与其原始匹配记录一致的来源关联标记。' : '样本报告摘要的来源关联或保存字段尚不能核对。'}
    </Alert>;
    const report = view.targetAltReported === true ? '原始分析 VCF 记录报告目标 ALT' : view.targetAltReported === false ? '原始记录未报告目标 ALT' : '当前原始记录是否报告目标 ALT 尚无法确定';
    const genotype = view.genotypeComplete === true ? '基因型记录完整' : view.genotypeComplete === false ? '基因型记录不完整' : '基因型完整性尚无法确定';
    const filter = { PASS: '原始 FILTER 通过', FILTERED: '原始 FILTER 标记为过滤', UNASSESSED: '原始 FILTER 未评估' }[view.filterEvaluation] || '未保存原始 FILTER 评价';
    return <Paper variant="outlined" data-testid="sample-call-summary" sx={{ p: 1.5, my: 1, borderColor: '#d4e0ec', background: '#f8fbfe' }}>
        <Stack spacing={0.75}>
            <Typography variant="subtitle2">样本记录事实 · 目标 ALT</Typography>
            <Typography variant="body2" fontWeight={600}>{report}</Typography>
            <Stack direction="row" useFlexGap flexWrap="wrap" gap={0.75}>
                <Chip size="small" variant="outlined" label={genotype} />
                <Chip size="small" variant="outlined" label={filter} />
            </Stack>
            {view.numeric.length > 0 && <Typography variant="body2">{view.numeric.map(item => `${item.label}：${item.value}`).join('；')}</Typography>}
            {view.missingFields.length > 0 && <Typography variant="body2">原始字段缺失：{view.missingFields.join('、')}。</Typography>}
            {view.invalidFields.length > 0 && <Typography variant="body2" color="warning.main">原始字段无效：{view.invalidFields.join('、')}。</Typography>}
        </Stack>
    </Paper>;
}
