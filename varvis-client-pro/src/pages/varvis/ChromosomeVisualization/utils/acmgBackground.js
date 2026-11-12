import {
    prepareSimplifiedInputs,
    relationshipOptions,
    annotationSelectable,
    contextTranscriptRecommendation,
    candidateInputStatus,
    buildSimplifiedCaseDeclaration,
} from './acmgInput';

export const MOI_LABELS = {
    AD: '常染色体显性',
    AR: '常染色体隐性',
    XLD: 'X 连锁显性',
    XLR: 'X 连锁隐性',
    MT: '线粒体遗传',
};
export const alleleText = (c) =>
    `${c.allele?.chromosome || '?'}:${c.allele?.position || '?'} ${c.allele?.reference || '?'}>${c.allele?.alternate || '?'}`;

export function backgroundSourceLinks(choice) {
    const links = new Map();
    const add = (url, label) => {
        if (typeof url === 'string' && url.startsWith('https://'))
            links.set(`${url}\n${label}`, { url, label });
    };
    for (const source of choice?.sources || []) {
        add(source.sourceUrl, source.specificationKey || '规范来源');
        if (source.kind === 'EXACT_PUBLISHED_EXPERT_CONTEXT') {
            for (const match of source.matches || []) {
                add(match.source?.sourceUrl,
                    `遗传方式来源：${MOI_LABELS[match.mode] || match.mode} · ${match.source?.panel || '专家库'}`);
            }
        }
    }
    return [...links.values()];
}

// Union hypotheses by gene/disease/inheritance, not by which source promises a score.
// An ordinary relationship retains its compatible transcript path when both sources agree.
export function backgroundChoices(candidate, contexts = []) {
    const choices = new Map();
    const unresolved = new Set(contexts.filter(c => c.hgncId === candidate.catalog?.hgncId &&
        c.geneSymbol === candidate.geneSymbol && c.inheritanceResolution?.status === 'CONFLICT')
        .map(c => c.mondoId));
    for (const r of relationshipOptions(candidate).filter(
        (x) => x.selectable && x.stableRelationshipKey && !unresolved.has(x.mondoId),
    )) {
        const key =
            r.hgncId && r.mondoId && r.moi
                ? `${r.hgncId}/${r.mondoId}/${r.moi}`
                : r.stableRelationshipKey;
        if (!choices.has(key))
            choices.set(key, {
                id: `relationship:${r.stableRelationshipKey}`,
                relationship: r,
                label: r.diseaseLabel || r.mondoId,
                mondoId: r.mondoId,
                moi: r.moi,
                sources: [],
            });
    }
    for (const c of contexts.filter(
        (x) =>
            x.contextId &&
            x.mondoId &&
            x.hgncId &&
            x.geneSymbol === candidate.geneSymbol &&
            x.hgncId === candidate.catalog?.hgncId,
    )) {
        for (const moi of !unresolved.has(c.mondoId) && c.publishedInheritance?.length ? c.publishedInheritance : ['']) {
            const key = `${c.hgncId}/${c.mondoId}/${moi}`;
            const existing = choices.get(key);
            if (existing) existing.sources.push(...(c.sources || []));
            else
                choices.set(key, {
                    id: `literature:${c.contextId}:${moi}`,
                    literature: { ...c, moi },
                    label: c.diseaseLabels?.join(' / ') || c.mondoId,
                    mondoId: c.mondoId,
                    moi,
                    inheritanceResolution: c.inheritanceResolution,
                    sources: [...(c.sources || [])],
                });
        }
    }
    // Same explicit gene/disease identity can share sources. Missing inheritance is
    // not a new disease and never overwrites the inheritance supplied by another source.
    for (const [key, value] of choices) {
        if (value.moi || !value.literature || unresolved.has(value.mondoId)) continue;
        const known = [...choices.values()].filter((x) => x.mondoId === value.mondoId && x.moi);
        if (!known.length) continue;
        for (const target of known) {
            target.sources.push(...value.sources);
            target.inheritanceUnspecifiedSources = value.sources;
        }
        choices.delete(key);
    }
    return [...choices.values()].sort((a, b) => a.id.localeCompare(b.id));
}

export function chooseBackground(candidate, input, choice) {
    const relationship = choice?.relationship;
    const recommended = contextTranscriptRecommendation(candidate, relationship);
    return {
        ...input,
        backgroundReviewed: true,
        stableRelationshipKey: relationship?.stableRelationshipKey || '',
        literatureContext: choice?.literature || null,
        annotationId:
            recommended && annotationSelectable(recommended, relationship)
                ? recommended.annotationId
                : '',
        // A different disease is not evidence of that patient's affected status.
        affectedStatus: 'UNKNOWN',
    };
}

export function prepareGuidedInputs(options, contexts, previous = {}) {
    const base = prepareSimplifiedInputs(options);
    return Object.fromEntries(
        options.candidates.map((candidate) => {
            const old = previous[candidate.candidateId];
            const entries = backgroundChoices(candidate, contexts).map((choice) => {
                const before =
                    old?.backgroundMode === 'AUTO' &&
                    old.recommendationId === candidate.recommendation?.recommendationId
                        ? old.backgroundInputs?.find((x) => x.choiceId === choice.id)
                        : null;
                const input = chooseBackground(candidate, base[candidate.candidateId], choice);
                // Patient facts remain scoped to this exact disease/MOI, never copied across backgrounds.
                if (before && ['UNKNOWN', 'AFFECTED', 'UNAFFECTED'].includes(before.affectedStatus))
                    input.affectedStatus = before.affectedStatus;
                return { ...input, choiceId: choice.id };
            });
            return [
                candidate.candidateId,
                {
                    ...base[candidate.candidateId],
                    backgroundMode: 'AUTO',
                    stableRelationshipKey: '',
                    annotationId: '',
                    literatureContext: null,
                    affectedStatus: 'UNKNOWN',
                    backgroundInputs: entries,
                },
            ];
        }),
    );
}

/** Intake changes the de novo source pin and recommendation receipts. Preserve a
 * confirmation only if the actual candidates, annotations and backgrounds agree. */
export function rebindGuidedInputsAfterIntake(previous, next, inputs) {
    const stableProfile = ({manifestSha256, moduleBindings, ...profile} = {}) => {
        const {deNovo, ...bindings} = moduleBindings || {};
        if (!deNovo) return {...profile, moduleBindings:bindings};
        const {literatureSourceResultSha256, literatureQualification, modelValidation, modelArtifactReplay, ...method} = deNovo;
        const {candidateSnapshotSha256, ...artifactPolicy} = modelArtifactReplay || {};
        return {...profile, moduleBindings:{...bindings, deNovo:{...method, modelArtifactReplay:artifactPolicy}}};
    };
    const project = ({options, literature}) => ({
        scope: ['analysisId','requestedN','selectedCount','inputAssembly','analysisAssembly','sampleId',
            'requiredDeclaration','candidateIds','enabledCriteria','commonBackground'].map(key => options[key]),
        criteriaProfile: stableProfile(options.executionProfile),
        candidates: options.candidates.map(candidate => {
            const {recommendationId,generatedAt,policyHash,...content}=candidate.recommendation||{};
            return {...candidate,recommendation:content};
        }),
        contexts: literature?.contexts,
    });
    const canonical = value => {
        if (Array.isArray(value)) return value.map(canonical);
        if (value && typeof value === 'object') return Object.fromEntries(
            Object.keys(value).sort().map(key=>[key,canonical(value[key])]));
        return value;
    };
    if (JSON.stringify(canonical(project(previous))) !== JSON.stringify(canonical(project(next))))
        throw new Error('取证后候选或疾病背景发生变化，请重新准备并确认。');
    const rebound = Object.fromEntries(next.options.candidates.map(candidate => [candidate.candidateId,
        {...inputs[candidate.candidateId],recommendationId:candidate.recommendation?.recommendationId}]));
    return prepareGuidedInputs(next.options,next.literature?.contexts||[],rebound);
}

// Reuse the original per-context declaration builder and its qualification checks.
// The final request contains one recommendation per allele, with separately bound contexts.
export function buildGuidedDeclaration(
    options,
    analysisId,
    inputs,
    contexts,
    dataClass,
    caseBinding,
) {
    const rows = backgroundRows(options, contexts, inputs);
    const count = rows.reduce((n, r) => n + r.backgrounds.filter((b) => b.choice.moi).length, 0);
    if (count > 200)
        throw new Error('本次背景超过200个，请减少分析变异数量；系统不会静默丢弃背景。');
    const rounds = Math.max(1, ...rows.map((r) => r.backgrounds.length));
    let result;
    const references = new Map();
    const hypotheses = [];
    for (let round = 0; round < rounds; round++) {
        const roundInputs = Object.fromEntries(
            rows.map((r) => [
                r.candidate.candidateId,
                r.backgrounds[round]?.choice.moi
                    ? r.backgrounds[round].input
                    : chooseBackground(r.candidate, r.input, null),
            ]),
        );
        const d = buildSimplifiedCaseDeclaration(
            options,
            analysisId,
            roundInputs,
            undefined,
            dataClass,
            caseBinding,
        );
        if (!result) result = { ...d, hypotheses: [], recommendations: [] };
        for (const r of rows) {
            const id = r.candidate.candidateId;
            const reference = d.recommendations.find((x) => x.candidateId === id);
            if (!references.has(id))
                references.set(id, {
                    ...reference,
                    stableRelationshipKey: null,
                    annotationId: null,
                    backgrounds: [],
                });
            if (!r.backgrounds[round]?.choice.moi) continue;
            const found = d.hypotheses.filter((x) => x.hypothesis.candidateId === id);
            for (const h of found) {
                const key =
                    id +
                    '-background-' +
                    h.hypothesis.hgncId.replace('HGNC:', '') +
                    '-' +
                    h.hypothesis.mondoId.replace('MONDO:', '') +
                    '-' +
                    h.hypothesis.moi;
                hypotheses.push({ ...h, hypothesis: { ...h.hypothesis, hypothesisId: key } });
                references
                    .get(id)
                    .backgrounds.push({
                        hypothesisId: key,
                        stableRelationshipKey: reference.stableRelationshipKey,
                        annotationId: reference.annotationId,
                    });
            }
        }
    }
    result.hypotheses = hypotheses;
    result.recommendations = [...references.values()];
    return result;
}

export function selectedBackground(choices, input) {
    return choices.find((x) =>
        input?.literatureContext
            ? x.literature?.contextId === input.literatureContext.contextId &&
              (!x.moi || x.moi === input.literatureContext.moi)
            : Boolean(input?.stableRelationshipKey) &&
              x.relationship?.stableRelationshipKey === input.stableRelationshipKey,
    );
}

export function backgroundRows(options, contexts, inputs) {
    return (options?.candidates || []).map((candidate) => {
        const input = inputs[candidate.candidateId] || {};
        const choices = backgroundChoices(candidate, contexts);
        const backgrounds = choices.map((choice) => {
            const saved = input.backgroundInputs?.find((x) => x.choiceId === choice.id);
            const prepared = saved || chooseBackground(candidate, input, choice);
            return {
                choice,
                input: prepared,
                state: candidateInputStatus(candidate, prepared, options),
            };
        });
        const issues = [];
        if (!candidate.recommendation?.executionSourceSha256)
            issues.push('正式来源尚未就绪，需系统核查');
        if (!choices.length)
            issues.push('疾病背景尚未确定，由系统探索相关背景；当前本地资料尚未提供可采用关系。');
        if (backgrounds.some((b) => !b.choice.moi))
            issues.push('部分背景缺少有依据的遗传方式，保留待核实，不猜填。');
        if (backgrounds.some((b) => b.choice.inheritanceResolution?.status === 'CONFLICT'))
            issues.push('同一基因—疾病的遗传方式来源存在冲突，系统不会自动选用其中一种；其他疾病背景仍分别处理。');
        if (backgrounds.some((b) => b.choice.inheritanceResolution?.status === 'UNMAPPED'))
            issues.push('来源提供了遗传方式，但其术语尚未完成映射；这与来源未记载不同。');
        if (backgrounds.some((b) => !b.state.transcript))
            issues.push('部分背景尚未绑定兼容转录本；不等于没有注释，相关规则会分别核查。');
        return {
            candidate,
            input,
            choices,
            backgrounds,
            issues,
            status: choices.length > 1 ? 'MULTIPLE' : choices.length ? 'MATCHED' : 'UNKNOWN',
        };
    });
}
