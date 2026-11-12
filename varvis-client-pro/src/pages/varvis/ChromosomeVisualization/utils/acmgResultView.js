// Read projections are explicitly distinct from complete results. Never export a projection as a result.
export const isResultIndex = run => run?.viewKind === 'acmg-result-index-v1';

export function verifyBackgroundView(view, run, candidateId, backgroundIndex) {
    if (view?.viewKind !== 'acmg-result-background-v1' || view.completeExport !== false ||
        view.analysisId !== run.analysisId || view.runId !== run.runId || view.sourceViewSha256 !== run.sourceViewSha256 ||
        view.candidateId !== candidateId || view.backgroundIndex !== backgroundIndex ||
        view.candidates?.length !== 1 || view.candidates[0].candidateId !== candidateId ||
        JSON.stringify(view.enabledCriteria) !== JSON.stringify(run.enabledCriteria) ||
        JSON.stringify(view.executionProfile) !== JSON.stringify(run.executionProfile))
        throw new Error('读取的证据与所选运行、变异或背景不一致，请重新读取保存结果。');
    const expected = run.candidates.find(c => c.candidateId === candidateId)?.attempts?.[backgroundIndex];
    const actual = view.candidates[0].attempts?.[backgroundIndex];
    if (!expected || !actual || expected.hypothesisId !== actual.hypothesisId ||
        JSON.stringify(expected.context) !== JSON.stringify(actual.context))
        throw new Error('保存背景身份发生变化，请重新读取保存结果。');
    return view;
}

/** Small per-mounted-run cache. Concurrent requests share work; failures are retryable. */
export function createBackgroundReader(load, maxCharacters = 12 * 1024 * 1024) {
    const cache = new Map(), pending = new Map();
    let size = 0;
    return async (run, candidateId, backgroundIndex) => {
        const key = JSON.stringify([run.analysisId, run.runId, run.sourceViewSha256, candidateId, backgroundIndex]);
        if (cache.has(key)) return cache.get(key).value;
        if (pending.has(key)) return pending.get(key);
        const request = (async () => {
            const value = verifyBackgroundView(await load(run, candidateId, backgroundIndex), run, candidateId, backgroundIndex);
            const length = JSON.stringify(value).length;
            if (length <= maxCharacters) {
                while (size + length > maxCharacters && cache.size) {
                    const [old, entry] = cache.entries().next().value;
                    cache.delete(old); size -= entry.length;
                }
                cache.set(key, { value, length }); size += length;
            }
            return value;
        })();
        pending.set(key, request);
        try { return await request; } finally { pending.delete(key); }
    };
}

export function verifyCompleteExport(full, index) {
    if (full?.viewKind || full?.analysisId !== index.analysisId || full?.runId !== index.runId ||
        full?.status !== index.status || full?.requestSha256 !== index.requestSha256 ||
        JSON.stringify(full?.candidateIds) !== JSON.stringify(index.candidateIds) ||
        JSON.stringify(full?.enabledCriteria) !== JSON.stringify(index.enabledCriteria) ||
        JSON.stringify(full?.executionProfile) !== JSON.stringify(index.executionProfile))
        throw new Error('完整导出与当前保存运行不一致，未下载摘要替代结果。');
    return full;
}
