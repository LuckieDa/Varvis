import { evidenceCards, evidenceOutcome, reasonExplanation, ps2IdentityHash } from './acmgInput';
import { formatVariantCoordinate } from './variantCoordinate';
import { validatedBa1ConditionalEntry } from './acmgBa1Conditional';
import { fullRnaEntryView, fullRnaCriterionClaim, fullRnaPending, fullRnaRescueReviewConflict } from './acmgFullRna';

export function savedVariantLabel(candidate, index = 0) {
    const contexts = (candidate.attempts || []).flatMap(a => [a.context, a.ba1Context, a.bs1Context]).filter(Boolean);
    const c = contexts.find(savedContextAllele) || contexts[0];
    const allele = savedContextAllele(c);
    return { gene: c?.gene?.symbol || c?.gene?.hgncId || `变异 ${candidate.rank ?? index + 1}`, variant: allele,
        allele: allele ? [allele.assembly,formatVariantCoordinate(allele,candidate.candidateId)].filter(Boolean).join(' · ') : candidate.candidateId };
}

export const savedContextAllele = context => context?.variant?.normalizedAllele || context?.variant?.inputAllele || context?.variant?.evidenceQueryAllele;

export function savedBackgroundBinding(candidate, attempt) {
    const binding = attempt?.binding;
    return candidate?.candidateId && attempt?.hypothesisId && binding?.candidateId === candidate.candidateId &&
        binding.hypothesisId === attempt.hypothesisId ? binding : null;
}

export function savedBackgrounds(candidate) {
    return (candidate.attempts?.length ? candidate.attempts : [{}]).map((attempt, index) => {
        const c = attempt.context || attempt.bs1Context || attempt.ba1Context;
        const binding = c ? null : savedBackgroundBinding(candidate, attempt);
        const disease = c?.disease?.mondoId || binding?.mondoId;
        return { index, hypothesisId: attempt.hypothesisId || attempt.localBatch?.hypothesisId,
            label: c?.disease?.label || binding?.diseaseLabel || disease || '背景尚未完整确定',
            identity: [disease, c?.moi || binding?.normalizedMoi].filter(Boolean).join(' · '), attempt };
    });
}

/** Preserve the original candidate and positional source references; never clone/filter its attempts. */
export function backgroundEvidenceCards(candidate, criteria, run, index) {
    const backgrounds = savedBackgrounds(candidate);
    const background = backgrounds[index];
    if (!background) return [];
    const attempt = background.attempt;
    const ba1Context = Object.hasOwn(attempt, 'ba1Context') ? attempt.ba1Context : attempt.context;
    const ba1View = ba1Context?.contextId || (attempt.ba1ResultSelection?.effectiveResultPath === '/ba1' ? 'candidate' : '__missing_context__');
    const cards = evidenceCards(candidate, criteria, ba1View, String(index), run);
    const requested = [...new Set((Array.isArray(criteria) ? criteria : []).filter(id => typeof id === 'string' && id))];
    const unavailable = id => ({ id, code: id.split(':').pop(), entries: [{ displayIssue: true, reasons: [],
        details: [['展示核查范围', '当前请求的证据与背景'], ['当前记录', '未取得唯一、可核对的保存结果或运行范围；未采用任何科学结论。']],
        summary: '当前背景缺少身份及范围可核对的保存结果；请核查运行配置、方法身份或结果记录，不将展示问题解释为病例资料不足。' }] });
    // A rejected profile/missing saved card still occupies its requested row;
    // otherwise a broken record would misleadingly look like an empty scope.
    return requested.map(id => {
        const matching = Array.isArray(cards) ? cards.filter(card => card.id === id) : [];
        if (matching.length !== 1) return unavailable(id);
        const card = matching[0];
        let entries;
        if (card.views) entries = card.selectedView === ba1View ? card.entries : [];
        else if (card.backgrounds) entries = card.selectedBackground === String(index) ? card.entries : [];
        else if (card.entries.length === backgrounds.length) entries = [card.entries[index]];
        else entries = card.entries.filter(e => background.hypothesisId && e.hypothesisId === background.hypothesisId);
        entries = entries?.filter(e => !e.hypothesisId || e.hypothesisId === background.hypothesisId);
        // An absent association is visible; another background's score is never used as a fallback.
        if (!entries?.length) entries = [{ displayIssue: true, reasons: [], details: [],
            summary: '当前背景没有身份一致的保存结果；请核对保存记录，不能采用其他背景的结论。' }];
        return { ...card, entries };
    });
}

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const adoptedStrengths = new Set(['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG', 'STAND_ALONE', 'STANDALONE']);
const result = entry => object(entry?.evaluation) ? entry.evaluation : undefined;
const storedStatus = entry => result(entry)?.evaluationResult?.status;
const storedReason = entry => result(entry)?.evaluationResult?.reasonCode;
const knownInapplicability = evaluation => evaluation?.evaluationResult?.status === 'NOT_APPLICABLE' ||
    evaluation?.criterionApplicability?.status === 'NOT_APPLICABLE' ||
    evaluation?.evaluationResult?.reasonCode === 'BA1_EXCEPTION_HIT' || reasonExplanation(evaluation)?.category === 'NOT_APPLICABLE';
// Frozen reviewSummary semantics. Absence on historical local projections is
// not a newly invented approval requirement; a present summary must be coherent.
function savedReview(evaluation) {
    if (!object(evaluation) || !Object.hasOwn(evaluation, 'review')) return 'ABSENT';
    const r = evaluation.review;
    if (!object(r) || !Array.isArray(r.reviewRecordIds) || r.reviewRecordIds.length > 256 ||
        !r.reviewRecordIds.every(id => typeof id === 'string' && /^review-sha256-[a-f0-9]{64}$/.test(id)) ||
        new Set(r.reviewRecordIds).size !== r.reviewRecordIds.length) return 'INVALID';
    if (r.status === 'NOT_REQUIRED') return r.decision === 'NONE' && r.reviewRecordIds.length === 0 ? 'NOT_REQUIRED' : 'INVALID';
    if (['REQUIRED', 'IN_PROGRESS'].includes(r.status)) return r.decision === 'NONE' && r.reviewRecordIds.length > 0 ? 'PENDING' : 'INVALID';
    if (r.status === 'COMPLETED' && ['ACCEPTED', 'REJECTED', 'OVERRIDDEN'].includes(r.decision) && r.reviewRecordIds.length > 0) return r.decision;
    return 'INVALID';
}
const reviewPending = entry => result(entry)?.reviewRequired === true || result(entry)?.adoptionStatus === 'REVIEW' || entry?.adoptionStatus === 'REVIEW' ||
    savedReview(result(entry)) === 'PENDING';

function boundLocalBa1Selection(evaluation, raw) {
    const binding = evaluation.ruleBinding;
    const selection = raw?.selection || raw?.generalization?.selection;
    if (!object(selection) || selection.status !== 'SELECTED' ||
        evaluation.deterministicTrace?.algorithmId !== 'GNOMAD_LOCAL_EXOMES_BA1_V1' ||
        binding?.dataPolicyId !== 'BA1_LOCAL_EXOMES_V1' || binding.dataPolicyVersion !== '1.0.0' ||
        evaluation.dataScope?.policyId !== binding.dataPolicyId || evaluation.dataScope.version !== binding.dataPolicyVersion ||
        evaluation.dataScope.dataset !== 'EXOMES' || evaluation.dataScope.release !== '4.1' ||
        raw?.evaluation?.evaluationId !== evaluation.evaluationId) return false;
    const { selectionId, ...identity } = selection;
    return selection.scope === evaluation.scope && binding.ba1SelectionId === selectionId &&
        selectionId === 'ba1-local-selection-sha256-' + ps2IdentityHash(identity);
}

function boundJointBa1Selection(evaluation, entry) {
    const raw = entry?.raw, selection = raw?.selection;
    const { evidence, runtime } = entry?.ba1JointSource || {};
    const trace = evaluation.deterministicTrace, binding = evaluation.ruleBinding;
    const clause = selection?.policy?.clause;
    if (selection?.status !== 'SELECTED' || selection.scope !== 'DISEASE_SPECIFIC' ||
        selection.scope !== evaluation.scope || raw.contextId !== selection.contextId ||
        clause?.disposition !== 'THRESHOLD' || clause.requiredDataset !== 'JOINT' || clause.metric !== 'gnomad.joint.faf95max' ||
        raw.evaluation?.evaluationId !== evaluation.evaluationId ||
        trace?.algorithmId !== 'GNOMAD_JOINT_BA1_V1' || trace.algorithmVersion !== '2.1.0-catalog-ba1-native-qc' ||
        evidence?.source?.sourceId !== 'GNOMAD_JOINT_V4_1_GRCH38_SITES' ||
        evidence.queryOutcome?.status !== 'HIT' || evidence.queryOutcome.recordCount !== 1 ||
        runtime?.catalog?.status !== 'READY' || runtime.catalog.catalogSha256 !== selection.catalogSha256) return false;
    const { selectionId, ...identity } = selection;
    const traceValue = (name) => {
        const matches = trace.parameters?.filter(parameter => parameter.name === name);
        return matches?.length === 1 ? matches[0].value : undefined;
    };
    return binding?.ba1SelectionId === selectionId && traceValue('ba1.selectionId') === selectionId &&
        selectionId === 'ba1-selection-sha256-' + ps2IdentityHash(identity) &&
        traceValue('ba1.catalogSha256') === selection.catalogSha256 &&
        traceValue('profile.sha256') === runtime.populationInputProfileSha256 &&
        /^[a-f0-9]{64}$/.test(runtime.populationInputProfileSha256 || '') &&
        evaluation.evidenceIds?.length === 1 && evaluation.evidenceIds[0] === 'population-evidence-sha256-' + ps2IdentityHash(evidence);
}

// Successful atomic-v2 evaluations legitimately omit reasonCode. Bind their
// completed trace and frozen identities instead of inventing a reason string.
// This is a display consistency check, not a substitute for source/rule gates.
function atomicSuccessWithoutReason(evaluation,entry) {
    try {
        const nativeBa1=evaluation?.schemaVersion==='ba1-allele-evaluation-v1'&&evaluation.criterionId==='ACMG_AMP_2015:BA1'&&
            (validatedBa1ConditionalEntry(entry,ps2IdentityHash)||boundLocalBa1Selection(evaluation,entry.raw)||
                boundJointBa1Selection(evaluation,entry))&&
            /^allele-sha256-[a-f0-9]{64}$/.test(evaluation.alleleId||'');
        if ((!nativeBa1&&evaluation?.schemaVersion !== 'criterion-evaluation-v2') || evaluation.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
            !['MET', 'NOT_MET'].includes(evaluation.evaluationResult?.status) || Object.hasOwn(evaluation.evaluationResult, 'reasonCode') ||
            !(nativeBa1 ? /^ba1-allele-evaluation-sha256-[a-f0-9]{64}$/ : /^evaluation-sha256-[a-f0-9]{64}$/).test(evaluation.evaluationId || '') || !nativeBa1&&!/^context-sha256-[a-f0-9]{64}$/.test(evaluation.contextId || '') ||
            evaluation.criterionApplicability?.status !== 'APPLICABLE' || !['NOT_REQUIRED', 'ACCEPTED'].includes(savedReview(evaluation))) return false;
        const ids = evaluation.evidenceIds, trace = evaluation.deterministicTrace;
        if (!Array.isArray(ids) || !ids.length || ids.length > 256 || !ids.every(id => (nativeBa1 ? /^population-evidence-sha256-[a-f0-9]{64}$/ : /^evidence-sha256-[a-f0-9]{64}$/).test(id)) ||
            new Set(ids).size !== ids.length || !object(trace) || trace.executionStatus !== 'COMPLETED' ||
            !Array.isArray(trace.inputEvidenceIds) || trace.inputEvidenceIds.length !== ids.length ||
            new Set(trace.inputEvidenceIds).size !== ids.length || !trace.inputEvidenceIds.every(id => ids.includes(id)) ||
            !['evaluatorId', 'evaluatorVersion', 'algorithmId', 'algorithmVersion'].every(key => typeof trace[key] === 'string' && trace[key]) ||
            !Array.isArray(trace.steps) || !trace.steps.length || !/^[a-f0-9]{64}$/.test(trace.traceSha256 || '')) return false;
        const { traceSha256, ...traceIdentity } = trace;
        const evaluationIdentity = Object.fromEntries(Object.entries(evaluation).filter(([key]) => !['evaluationId', 'conflict', 'review'].includes(key)));
        return traceSha256 === ps2IdentityHash(traceIdentity) && evaluation.evaluationId === (nativeBa1 ? 'ba1-allele-evaluation-sha256-' : 'evaluation-sha256-') + ps2IdentityHash(evaluationIdentity);
    } catch { return false; }
}

export function resultOutcome(entry) {
    if (entry?.displayIssue) return entry.displayOutcome || {
        kind: 'UNRESOLVED', label: '保存结果待核查', next: '核对原始记录及背景身份。', reasonCode: storedReason(entry),
    };
    const rna=fullRnaEntryView(entry);
    if(fullRnaCriterionClaim(entry?.savedCriterion)&&!rna)return {kind:'UNRESOLVED',label:'保存结果待核查',next:'核对本次 RNA 来源、判定与采用记录。',reasonCode:storedReason(entry)};
    if(rna?.application==='NOT_COUNTED')return {kind:'NOT_COUNTED',label:'相同后果不重复计分',next:`相同 RNA 后果已计入 ${rna.assignedTo}。`,reasonCode:storedReason(entry)};
    if(rna?.application==='CONFLICT')return {kind:'CONFLICT',label:'相反方向证据冲突',next:'核对同一 RNA 后果的致病与良性依据；冲突解决前不采用。',reasonCode:storedReason(entry)};
    if(fullRnaRescueReviewConflict(rna))return {kind:'CONFLICT',label:'功能救援审核冲突',next:'核对两份记录采用的 RNA 产物、实验及疾病范围。',reasonCode:storedReason(entry)};
    if (reviewPending(entry)) return { kind: 'REVIEW', label: '需复核 · 未采用',
        next: '先完成本条证据的复核；内部计算满足不代替采用结论。', reasonCode: storedReason(entry) };
    const evaluation = result(entry), review = savedReview(evaluation), outcome = evidenceOutcome(evaluation);
    if (review === 'INVALID') return {kind: 'UNRESOLVED', label: '复核字段待核查',
        next: '保存的复核状态、决定或记录身份不完整；当前不能据内部评价采用证据。', reasonCode: storedReason(entry)};
    if (['REJECTED', 'OVERRIDDEN'].includes(review)) return {kind: 'REVIEW', label: review === 'REJECTED' ? '复核已拒绝 · 未采用' : '复核已覆盖 · 需核采用结果',
        next: '保留原内部计算；核对已保存复核记录及最终采用结果，不自动采用原强度。', reasonCode: storedReason(entry)};
    if (knownInapplicability(evaluation) && storedStatus(entry) !== 'MET') return { kind: 'NOT_APPLICABLE', label: '不适用',
        next: '已保存状态、适用性或明确规则说明当前范围不适用。', reasonCode: storedReason(entry) };
    if(rna?.application==='NOT_ADOPTED'&&rna.claim.originalStatus==='NOT_EVALUATED'){
        if(rna.preparation.reasonCode==='FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED')
            return {kind:'UNSUPPORTED',label:'软件暂未支持',next:fullRnaPending(rna).suggestion,reasonCode:storedReason(entry)};
        if(rna.preparation.reasonCode==='FULL_RNA_SOURCE_CHANGED_AFTER_REQUEST_IDENTITY')
            return {kind:'EXECUTION_FAILED',label:'执行或核查失败',next:fullRnaPending(rna).suggestion,reasonCode:storedReason(entry)};
    }
    // A familiar-looking suffix on an unknown reason is not a rule decision.
    if (outcome.kind === 'NOT_APPLICABLE' && !knownInapplicability(evaluation)) return { kind: 'UNRESOLVED', label: '原因待核实',
        next: '当前记录没有明确适用性或已接通的规则说明；保留原始原因代码，不推造不适用结论。', reasonCode: storedReason(entry) };
    return outcome;
}

// Four mutually exclusive conclusions; adoption/progress are separate dimensions.
export const mainResultKinds = [
    ['ADOPTED_MET', '已采用满足', '#246b55', '#edf8f2', '#b6ddc9'],
    ['NOT_MET', '已评价未满足', '#4b607b', '#f1f5fa', '#cfdaea'],
    ['NOT_APPLICABLE', '不适用', '#526477', '#f2f5f8', '#d4dfe8'],
    ['NO_CONCLUSION', '尚无可采用结论', '#75518b', '#f6effb', '#dac7e7'],
].map(([kind, label, color, background, border]) => ({kind, label, color, background, border}));

export function resultPresentation(entry) {
    const evaluation = result(entry), status = storedStatus(entry), reasonCode = storedReason(entry), strength = evaluation?.appliedStrength;
    let outcome = resultOutcome(entry), mainKind = 'NO_CONCLUSION';
    let adoption = {kind: 'NOT_ADOPTED', label: '未采用'}, progress = {kind: 'BLOCKED', label: outcome.label};
    const adoptionStatus = entry?.adoptionStatus ?? evaluation?.adoptionStatus;
    if (entry?.displayIssue) {
        adoption = {kind: 'DISPLAY_UNVERIFIED', label: '展示核验未通过'};
        progress = {kind: 'DISPLAY_REVIEW', label: outcome.kind === 'UNSUPPORTED' ? '展示方法未接通' : outcome.kind === 'UNAVAILABLE' ? '公开采用证明暂不可用' : '保存记录待核查'};
    } else if (outcome.kind === 'REVIEW') {
        const review = savedReview(evaluation), rejected = ['REJECTED', 'OVERRIDDEN'].includes(review);
        adoption = {kind: rejected ? review : 'REVIEW', label: rejected ? outcome.label : '待复核 · 未采用'};
        progress = {kind: rejected ? 'REVIEW_COMPLETED' : 'AWAITING_REVIEW', label: rejected ? '复核结果已记录' : '等待复核'};
    } else if (!['MET', 'NOT_MET', 'NOT_APPLICABLE', 'NOT_EVALUATED'].includes(status) ||
        typeof evaluation?.criterionId !== 'string' || !evaluation.criterionId ||
        (typeof reasonCode !== 'string' || !reasonCode) && !atomicSuccessWithoutReason(evaluation,entry) ||
        savedReview(evaluation) === 'INVALID' ||
        Object.hasOwn(evaluation, 'reviewRequired') && typeof evaluation.reviewRequired !== 'boolean' ||
        entry?.adoptionStatus !== undefined && evaluation?.adoptionStatus !== undefined && entry.adoptionStatus !== evaluation.adoptionStatus) {
        outcome = {kind: 'UNRESOLVED', label: '保存结果字段待核查', next: '当前记录未保存有效状态、证据身份或原因代码；不据此判为满足、未满足或不适用。', reasonCode};
        adoption = {kind: 'UNVERIFIED', label: '结果字段待核查'};
        progress = {kind: 'DISPLAY_REVIEW', label: '保存字段不完整'};
    } else if (status === 'MET' && (knownInapplicability(evaluation) || !adoptedStrengths.has(strength))) {
        outcome = {kind: 'UNRESOLVED', label: '保存结论与采用字段待核查', next: '保存的满足状态与适用性或实际强度不一致，不能据此采用。', reasonCode};
        adoption = {kind: 'UNVERIFIED', label: '采用字段待核查'};
        progress = {kind: 'DISPLAY_REVIEW', label: '保存结果不一致'};
    } else if (outcome.kind === 'NOT_COUNTED') {
        const deNovoOwner = ({DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2: 'PS2', DENOVO_SHARED_EVENT_ASSIGNED_TO_PM6: 'PM6'})[reasonCode];
        const targetCriterion = fullRnaEntryView(entry)?.assignedTo || deNovoOwner;
        adoption = deNovoOwner ? {kind: 'NOT_COUNTED', label: `归属 ${deNovoOwner}`, targetCriterion, next: `该共同事件归属 ${deNovoOwner}。`}
            : {kind: 'NOT_COUNTED', label: targetCriterion ? `已计入 ${targetCriterion}，本项不重复采用` : '本项不重复采用', targetCriterion,
                next: targetCriterion ? `查看同背景 ${targetCriterion} 的已保存结果；本项不重复计分，不表示资料不足。` : '核对已保存的事件分派，不推断资料不足。'};
        progress = {kind: 'EVENT_ASSIGNED', label: fullRnaEntryView(entry) ? '相同后果已分派' : '共同事件已分派'};
    } else if (status === 'MET' && ['NOT_APPLIED', 'NOT_ADOPTED'].includes(adoptionStatus)) {
        adoption = {kind: adoptionStatus, label: '内部满足 · 尚未采用'};
        progress = {kind: 'EVALUATED', label: '规则评价已完成'};
    } else if (status === 'MET' && adoptionStatus !== undefined && adoptionStatus !== 'ADOPTED') {
        adoption = {kind: 'UNVERIFIED', label: '采用状态待核查'};
        progress = {kind: 'DISPLAY_REVIEW', label: '未知采用状态'};
    } else if (status === 'MET' && outcome.kind === 'MET') {
        mainKind = 'ADOPTED_MET'; adoption = {kind: 'ADOPTED', label: '已采用'};
        progress = {kind: 'EVALUATED', label: '规则评价已完成'};
    } else if (status === 'NOT_MET' && outcome.kind === 'NOT_MET' && (!strength || strength === 'NONE')) {
        mainKind = 'NOT_MET'; progress = {kind: 'EVALUATED', label: '规则评价已完成'};
    } else if (outcome.kind === 'NOT_APPLICABLE' && knownInapplicability(evaluation)) {
        mainKind = 'NOT_APPLICABLE'; adoption = {kind: 'NOT_APPLICABLE', label: '无需采用'};
        progress = {kind: 'RULE_EXCLUDED', label: '规则范围已排除'};
    } else if (outcome.kind === 'INCOMPLETE') progress = {kind: 'INCOMPLETE', label: '取证或核查尚未完成'};
    else if (outcome.kind === 'EXECUTION_FAILED') progress = {kind: 'FAILED', label: '执行或核查失败'};
    else if (!status || outcome.kind === 'UNRESOLVED') progress = {kind: 'UNRESOLVED', label: '原因或执行阶段待核实'};
    const mainLabel = mainResultKinds.find(item => item.kind === mainKind).label;
    return {mainKind, mainLabel, outcome, adoption, progress, status, strength, reasonCode,
        diagnostics: mainKind === 'NO_CONCLUSION' ? [{kind: outcome.kind, label: outcome.label, reasonCode}] : []};
}

/** Counts only supplied rows; callers pass backgroundEvidenceCards for one selected background. */
export function presentationCounts(rows) {
    const counts = new Map(mainResultKinds.map(item => [item.kind, 0]));
    for (const row of Array.isArray(rows) ? rows : []) {
        const item = resultPresentation(row?.entry || row);
        counts.set(item.mainKind, counts.get(item.mainKind) + 1);
    }
    return mainResultKinds.map(item => ({...item, count: counts.get(item.kind)}));
}

// Display-only taxonomy; scoring and the saved reason codes remain unchanged.
export const resultStatuses = [
    ['MET', '已满足', '#246b55', '#edf8f2', '#b6ddc9'],
    ['NOT_MET', '已评价未满足', '#4b607b', '#f1f5fa', '#cfdaea'],
    ['NOT_COUNTED', '共同事件不重复计分', '#466b76', '#eef7f8', '#c8dfe3'],
    ['NOT_APPLICABLE', '不适用', '#526477', '#f2f5f8', '#d4dfe8'],
    ['MISSING', '判定资料不足', '#8a5716', '#fff7e7', '#ebd3a0'],
    ['QUALITY_REJECTED', '资料质量未通过', '#94622a', '#fff5e9', '#e8cba8'],
    ['POLICY_NOT_READY', '适用规范尚未接通', '#615b8b', '#f3f1fb', '#d5d0e9'],
    ['NO_RECORD', '未取得匹配记录', '#77602c', '#fbf7ec', '#e4d9b9'],
    ['INCOMPLETE', '取证尚未完成', '#816219', '#fff8df', '#e5d69c'],
    ['REVIEW', '需复核 · 未采用', '#75518b', '#f6effb', '#dac7e7'],
    ['UNRESOLVED', '原因待核实', '#735b82', '#f6f1fa', '#dcd0e6'],
    ['DISABLED', '所需能力未启用', '#3a6897', '#edf5fe', '#c4d8ee'],
    ['CONFIGURATION', '资源配置未就绪', '#376b80', '#edf7fa', '#c5dde7'],
    ['UNAVAILABLE', '所需资料暂不可用', '#7c6236', '#faf5ea', '#e5d6b9'],
    ['CONFLICT', '资料冲突或不相容', '#99512d', '#fff1e9', '#f0ccba'],
    ['UNSUPPORTED', '软件暂未支持', '#615b8b', '#f3f1fb', '#d5d0e9'],
    ['EXECUTION_FAILED', '执行或核查失败', '#a13c45', '#fff0f1', '#ecc3c8'],
].map(([kind, label, color, background, border]) => ({ kind, label, color, background, border }));

export function resultStyle(kind) {
    return resultStatuses.find(item => item.kind === kind) || resultStatuses.find(item => item.kind === 'UNRESOLVED');
}

// Reading priority only. Stable ties preserve the saved criterion/entry order and source indices.
// Both benign and pathogenic MET evidence are equally visible; no clinical score is inferred here.
export function sortResultRows(rows) {
    const order = ['MET', 'NOT_MET', 'NOT_COUNTED', 'CONFLICT', 'REVIEW', 'EXECUTION_FAILED', 'UNRESOLVED',
        'MISSING', 'QUALITY_REJECTED', 'INCOMPLETE', 'NO_RECORD', 'POLICY_NOT_READY',
        'CONFIGURATION', 'UNAVAILABLE', 'DISABLED', 'UNSUPPORTED', 'NOT_APPLICABLE'];
    const priority = row => {
        if (!row.entry) return order.indexOf(resultStyle(row.outcome?.kind).kind);
        const p = resultPresentation(row.entry);
        return p.mainKind === 'ADOPTED_MET' ? 0 : p.mainKind === 'NOT_MET' ? 1 :
            p.mainKind === 'NOT_APPLICABLE' ? order.length : 2 + Math.max(0,order.indexOf(resultStyle(p.outcome.kind).kind));
    };
    return rows.map((row, position) => ({row, position}))
        .sort((a,b) => priority(a.row) - priority(b.row) || a.position-b.position)
        .map(item => item.row);
}

export function resultStatusCounts(rows) {
    const counts = new Map();
    rows.forEach(row => { const kind = resultStyle(row.outcome.kind).kind; counts.set(kind, (counts.get(kind) || 0) + 1); });
    return resultStatuses.map(item => ({ ...item, count: counts.get(item.kind) || 0 }));
}
