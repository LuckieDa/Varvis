import { backgroundEvidenceCards, resultPresentation, savedBackgrounds } from './acmgResultsWorkbench';
import { classifyAdoptedEvidence, pm1Pp3Cap, selectClassificationRules } from './acmgClassification';
import { reviewedPm3CombinationRule } from './acmgPm3Conditions';
export { classificationLevels } from './acmgClassification';

// Read-only suggestions over adopted evidence in one saved disease background.
export const SCORE_METHOD = {
    id: 'tavtigian-2020-adopted-evidence-v1',
    label: 'Tavtigian 2020',
    url: 'https://doi.org/10.1002/humu.24088',
};
const weights = { SUPPORTING: 1, MODERATE: 2, STRONG: 4, VERY_STRONG: 8 };
const pathogenic = new Set(['PVS1', 'PS1', 'PS2', 'PS3', 'PS4', 'PM1', 'PM2', 'PM3', 'PM4', 'PM5', 'PM6', 'PP1', 'PP2', 'PP3', 'PP4']);
const benign = new Set(['BS1', 'BS2', 'BS3', 'BS4', 'BP1', 'BP2', 'BP3', 'BP4', 'BP5', 'BP7']);
// These pairs require a combination review if both survived source adoption.
// Do not arbitrarily choose a winner or silently subtract conflicting evidence.
const relatedPairs = [
    ['PS1', 'PM5'], ['PS2', 'PM6'], ['BS1', 'PM2'], ['PP3', 'BP4'],
    ['PP2', 'BP1'], ['PM4', 'BP3'], ['PVS1', 'PM4'], ['PVS1', 'PP3'], ['PVS1', 'BP7'],
];
export const signedScore = value => value > 0 ? `+${value}` : String(value);

export function scoreEvidenceCards(cards, context) {
    const items = [], issues = [], seen = new Map();
    let pendingCount = 0, excludedCount = 0;
    for (const card of cards || []) for (const entry of card.entries || []) {
        const presentation = resultPresentation(entry);
        if (presentation.mainKind !== 'ADOPTED_MET') {
            if (presentation.mainKind === 'NO_CONCLUSION' && presentation.outcome.kind !== 'NOT_COUNTED') pendingCount++;
            else excludedCount++;
            continue;
        }
        const evaluation = entry.evaluation, code = card.code, strength = evaluation.appliedStrength;
        const standalone = code === 'BA1' && ['STAND_ALONE', 'STANDALONE'].includes(strength);
        const direction = pathogenic.has(code) ? 1 : benign.has(code) ? -1 : 0;
        if (evaluation.criterionId !== card.id || card.id !== `ACMG_AMP_2015:${code}` ||
            (!standalone && (!direction || !Object.hasOwn(weights, strength)))) {
            issues.push(`${code} 的证据身份或强度尚不能换算积分。`);
            continue;
        }
        const previous = seen.get(code);
        if (previous) {
            if (JSON.stringify(previous.evaluation) !== JSON.stringify(evaluation)) issues.push(`${code} 存在多份不同采用记录。`);
            else excludedCount++;
            continue;
        }
        const reviewedPm3RuleId = code === 'PM3' ? reviewedPm3CombinationRule(entry) : undefined;
        const item = { code, strength, points: standalone ? null : direction * weights[strength], standalone, reviewedPm3RuleId,
            evaluation, entry, card };
        seen.set(code, item); items.push(item);
    }
    for (const [a, b] of relatedPairs) if (seen.has(a) && seen.has(b)) {
        issues.push(`${a} 与 ${b} 的共同来源、相互依赖或相反结论需要核查。`);
    }
    for (let i = 0; i < items.length; i++) for (let j = i + 1; j < items.length; j++) {
        const ids = items[i].evaluation.evidenceIds;
        if (Array.isArray(ids) && ids.some(id => items[j].evaluation.evidenceIds?.includes(id))) {
            issues.push(`${items[i].code} 与 ${items[j].code} 引用了相同证据记录。`);
        }
    }
    const standalone = items.some(item => item.standalone);
    if (standalone && items.some(item => item.points > 0)) issues.push('BA1 与致病方向证据同时满足，需要核查冲突。');
    const classificationRule = selectClassificationRules(context);
    const pm1Pp3 = classificationRule.type === 'GENERAL' && classificationRule.status === 'SELECTED' ? pm1Pp3Cap(items) : null;
    const adjustment = pm1Pp3 ? pm1Pp3.rawPoints - pm1Pp3.points : 0;
    const positive = items.reduce((sum, item) => sum + Math.max(0, item.points || 0), 0) - adjustment;
    const negative = items.reduce((sum, item) => sum + Math.min(0, item.points || 0), 0);
    const status = issues.length ? 'REVIEW' : standalone ? 'STANDALONE' : items.length ? 'SCORED' : 'NO_EVIDENCE';
    return { status, total: status === 'SCORED' ? positive + negative : null,
        positive, negative, items, issues: [...new Set(issues)], pendingCount, excludedCount, method: SCORE_METHOD.id,
        classificationRule, pm1Pp3 };
}

export function scoreBackground(candidate, criteria, run, index) {
    const attempt = savedBackgrounds(candidate)[index]?.attempt;
    return { ...scoreEvidenceCards(backgroundEvidenceCards(candidate, criteria, run, index),
        attempt?.context || attempt?.bs1Context || attempt?.ba1Context),
        candidateId: candidate.candidateId, backgroundIndex: index };
}

// A variant's representative score is the maximum in one background, never a sum.
// An incomplete background set cannot be presented as a fully ranked variant.
export function representativeScore(candidate, summaries) {
    if (!summaries || summaries.length !== savedBackgrounds(candidate).length || Array.from(summaries).some((item, index) =>
        item?.candidateId !== candidate.candidateId || item.backgroundIndex !== index)) return null;
    const sorted = [...summaries].sort((a, b) => scorePriority(a) - scorePriority(b) ||
        (b.total ?? 0) - (a.total ?? 0) || a.backgroundIndex - b.backgroundIndex);
    return { ...sorted[0], backgrounds: summaries };
}

function scorePriority(score) {
    return ({ SCORED: 0, STANDALONE: 1, NO_EVIDENCE: 2, REVIEW: 3 })[score?.status] ?? 4;
}

export function sortScoredCandidates(candidates, scores, mode = 'SCORE') {
    return candidates.map((candidate, index) => ({ candidate, index, score: scores[index] }))
        .sort((a, b) => (mode === 'SCORE' ? scorePriority(a.score) - scorePriority(b.score) ||
            (b.score?.total ?? 0) - (a.score?.total ?? 0) : 0) ||
            (a.candidate.rank ?? a.index + 1) - (b.candidate.rank ?? b.index + 1) || a.index - b.index);
}

export function scoreLabel(score) {
    if (!score) return '积分待汇总';
    return score.status === 'SCORED' ? `${signedScore(score.total)} 分` :
        ({ STANDALONE: 'BA1 独立良性', NO_EVIDENCE: '暂无计分证据', REVIEW: '组合待核查' })[score.status] || '积分待汇总';
}

// A suggestion from currently adopted evidence, not a patient diagnosis.
// Missing evidence never becomes a benign observation or a fabricated zero.
export function classifyEvidenceScore(score) {
    return classifyAdoptedEvidence(score, score?.classificationRule);
}

// Bounded read-only projection for indexed history. Keep only compact scoring
// summaries; do not retain all full background payloads or invoke providers.
export async function readScoreSummaries(run, criteria, readBackground, onProgress, isCurrent = () => true) {
    const tasks = run.candidates.flatMap((candidate, candidateIndex) => savedBackgrounds(candidate)
        .map(background => ({ candidate, candidateIndex, backgroundIndex: background.index })));
    const summaries = run.candidates.map(() => []);
    let cursor = 0, done = 0;
    const failures = [];
    const worker = async () => {
        while (isCurrent() && cursor < tasks.length) {
            const task = tasks[cursor++];
            try {
                const view = await readBackground(run, task.candidate.candidateId, task.backgroundIndex);
                if (!isCurrent()) return;
                const value = scoreBackground(view.candidates[0], criteria, view, task.backgroundIndex);
                summaries[task.candidateIndex][task.backgroundIndex] = {
                    ...value, items: value.items.map(({ code, strength, points, standalone, reviewedPm3RuleId }) =>
                        ({ code, strength, points, standalone, reviewedPm3RuleId })),
                };
            } catch (error) {
                if (!isCurrent()) return;
                failures.push({ candidateId: task.candidate.candidateId, backgroundIndex: task.backgroundIndex,
                    message: error?.message || '读取失败' });
            }
            done++;
            onProgress?.({ done, total: tasks.length });
        }
    };
    await Promise.all([worker(), worker()]);
    return { scores: run.candidates.map((candidate, index) => representativeScore(candidate, summaries[index])), failures };
}
