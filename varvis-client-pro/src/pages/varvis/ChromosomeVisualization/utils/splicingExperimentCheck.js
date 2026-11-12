export const checkLabels = {
    UNVERIFIED: '未核验',
    OBSERVATION_MISMATCH_REVIEW_REQUIRED: '与已收录实验观察不一致，待复核',
    UNAVAILABLE: '核验资料不可用',
};

export function savedCheckVersions(rows) {
    if (!Array.isArray(rows)) throw new Error('INVALID_CHECK_LIST');
    const ids = new Set();
    for (const row of rows) {
        if (
            !/^[a-f0-9]{64}$/.test(row?.checkId || '') ||
            ids.has(row.checkId) ||
            ![undefined, 'AVAILABLE', 'UNAVAILABLE'].includes(row.availability)
        )
            throw new Error('INVALID_CHECK_LIST');
        ids.add(row.checkId);
    }
    const available = rows.filter((row) => row.availability !== 'UNAVAILABLE');
    return {
        rows,
        available,
        unavailableCount: rows.length - available.length,
        mayCreate: rows.length === 0,
    };
}

export async function initialExperimentCheck(request) {
    let versions = savedCheckVersions(await request('get'));
    const latest = versions.available[versions.available.length - 1];
    const snapshot = latest
        ? await request('get', `/${encodeURIComponent(latest.checkId)}`)
        : versions.mayCreate
          ? await request('post')
          : null;
    if (versions.mayCreate) versions = savedCheckVersions(await request('get'));
    return { snapshot, versions };
}

export function compatibleCheck(snapshot, run) {
    return (
        snapshot?.schemaVersion === 'splicing-experiment-check-v1' &&
        snapshot.runId === run?.runId &&
        snapshot.analysisId === run?.analysisId &&
        typeof snapshot.checkId === 'string' &&
        /^[a-f0-9]{64}$/.test(snapshot.checkId) &&
        Array.isArray(snapshot.checks)
    );
}

export function experimentPrompt(candidate, evaluation, snapshot, unavailable) {
    if (
        !['ACMG_AMP_2015:BP4', 'ACMG_AMP_2015:BP7'].includes(evaluation?.criterionId) ||
        evaluation?.criterionApplicability?.status !== 'APPLICABLE'
    )
        return null;
    const attempt = candidate?.attempts?.find(
        (a) =>
            a.context?.contextId === evaluation.contextId &&
            a.criterionEvaluations?.some((e) => e.evaluationId === evaluation.evaluationId),
    );
    if (!attempt?.transcriptInput?.predictionPath?.startsWith('SPLICEAI_')) return null;
    const matched = snapshot?.checks?.find(
        (c) =>
            c.candidateId === candidate.candidateId &&
            c.contextId === evaluation.contextId &&
            c.evaluationId === evaluation.evaluationId &&
            c.criterionId === evaluation.criterionId,
    );
    const state =
        unavailable || (matched && !checkLabels[matched.status])
            ? 'UNAVAILABLE'
            : matched?.status || 'UNVERIFIED';
    const conflict = state === 'OBSERVATION_MISMATCH_REVIEW_REQUIRED';
    const uncertain = [
        'TRANSCRIPT_OR_GENE_APPLICABILITY_UNCERTAIN',
        'INPUT_APPLICABILITY_UNCERTAIN',
    ].includes(matched?.reasonCode);
    const records = (snapshot?.material?.catalog?.records || []).filter((r) =>
        matched?.recordIds?.includes(r.recordId),
    );
    return {
        label: checkLabels[state] || checkLabels.UNAVAILABLE,
        severity: conflict ? 'warning' : state === 'UNAVAILABLE' ? 'error' : 'info',
        reason: conflict
            ? '计算支持无剪接影响，但已收录实验观察到剪接改变；需结合实验体系复核，不能据此推定疾病致病性。'
            : state === 'UNAVAILABLE'
              ? '资料读取或校验失败，不能视为无冲突；原计算结果仍可读取。'
              : uncertain
                ? '已有相关位点记录，但转录本或分子适用条件尚未确认。'
                : matched?.reasonCode === 'NO_COMPARABLE_BENIGN_SUPPORT'
                  ? '已匹配实验记录；当前没有可比较的无剪接影响支持，不构成实验正常证明。'
                  : '有限资料集尚未覆盖此上下文，不代表实验正常；可继续分析。',
        records,
    };
}

export const safeSourceUrl = (value) => {
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && !url.username && !url.password ? url.href : undefined;
    } catch {
        return undefined;
    }
};
