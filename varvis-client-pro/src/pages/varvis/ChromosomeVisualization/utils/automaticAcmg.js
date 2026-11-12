// Short/cached jobs are observed promptly; long jobs retain the existing 5-second cadence.
export function literaturePollDelay(reads) { return [250, 500, 1000, 2000][reads] || 5000; }

// Per-tab recovery stores only opaque hashes and operation/task IDs; no clinical declaration.
export function automaticCheckpoint(storage, analysisId) {
    const key = `varvis-automatic-acmg-v1:${analysisId}`;
    return {
        read: fingerprint => JSON.parse(storage.getItem(key) || '{}')[fingerprint],
        write: (fingerprint, value) => {
            const entries = JSON.parse(storage.getItem(key) || '{}');
            entries[fingerprint] = value;
            if (Object.keys(entries).length > 200) throw new Error('待核对任务记录过多，请先核对现有任务。');
            storage.setItem(key, JSON.stringify(entries));
        },
        clear: () => storage.removeItem(key),
    };
}
const digest = async text => Array.from(new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))))
    .map(x => x.toString(16).padStart(2, '0')).join('');

const materialReasonLabels = {
    DENOVO_MODEL_TIMEOUT: '资料提取超时',
    DENOVO_MODEL_HTTP_503: '资料提取服务暂不可用',
    LITERATURE_SEARCH_CACHE_BUSY_OR_INTERRUPTED: '文献检索未完成',
    PARENT_PHENOTYPE_MISSING: '缺少亲本表型',
    PM3_MODEL_DISABLED_PENDING_READING: '资料提取未启用',
    PP1_DISCOVERY_SEARCH_INCOMPLETE: '文献检索未完成',
    PP1_MODEL_DISABLED_CACHE_MISSING: '暂无可用资料缓存',
    PP1_NO_CANDIDATES_IN_COMPLETED_SEARCH: '本轮检索未返回候选论文',
    PP1_TARGET_NOT_IN_PUBLIC_SOURCE: '已获取资料未核实目标变异',
};

// READY indicates that the worker returned; individual sources can still need review.
function materialTaskPresentation(task) {
    const result = task?.result || {};
    const papers = result.papers || [];
    const reasons = [task?.reasonCode, result.stopReason, result.readingLoop?.stopReason,
        result.readingLoop?.feedback?.stopReason, ...papers.map(paper => paper.stopReason)];
    const details = [...new Set(reasons.map(reason => materialReasonLabels[reason]).filter(Boolean))];
    const omitted = result.searchAliasCoverage?.omittedAliases;
    if (omitted > 0) details.push(`${omitted} 个检索名称未使用`);
    const pending = result.status === 'PARTIAL' || Boolean(result.discoveryState?.resumeAvailable)
        || result.readingLoop?.state === 'INCOMPLETE' || papers.some(paper => paper.status === 'PARTIAL');
    const attention = task?.status !== 'READY' || pending || details.length > 0;
    const status = {
        READY: attention ? '待核查' : '资料已返回',
        FAILED: '准备失败',
        CANCELLED: '已取消',
        BLOCKED: '准备受阻',
        QUEUED: '排队中',
        RUNNING: '准备中',
    }[task?.status] || '状态待核查';
    return { status, detail: details.join(' · '), attention };
}

export function materialTaskSummary(task) {
    const { status, detail } = materialTaskPresentation(task);
    return detail ? `${status} · ${detail}` : status;
}

/** Existing source workflows run before evaluation; no endpoint changes resource permission switches. */
export async function prepareAutomaticLiterature({ analysisId, topN, declaration, get, post, progress,
    checkpoint = null, candidateLabels = {}, onNotes = () => undefined, controlPoint = async () => undefined,
    onTask = async () => undefined, onAccepted = () => undefined, active = () => true,
    wait = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
    const root = `/variant-analyses/${encodeURIComponent(analysisId)}/acmg/literature`;
    const { selectedCriteria: _selection, operationId: _operation, ...materialDeclaration } = declaration;
    const notes = new Map();
    const record = (label, presentation) => {
        const note = { label, ...presentation };
        const key = JSON.stringify(note);
        notes.set(key, { ...note, count: (notes.get(key)?.count || 0) + 1 });
        onNotes([...notes.values()]);
    };
    const check = () => { if (!active()) throw new Error('页面任务已切换；停止后续提交，已启动任务保留。'); };
    const settled = new Map();
    const settle = async (base, request) => {
        await controlPoint();
        check();
        const body = JSON.stringify(request);
        const fingerprint = await digest(`${base}\n${body}`);
        if (settled.has(fingerprint)) return settled.get(fingerprint);
        const requestSha256 = await digest(body);
        const lane = base.endsWith('/pm3') ? 'PM3' : base.endsWith('/pp1') ? 'PP1' : 'PS2 / PM6';
        const label = [lane, candidateLabels[request.candidateId]].filter(Boolean).join(' · ');
        const recordBlocked = reason => record(label, {
            status: '准备受阻',
            detail: /PP1_PUBLIC_SEARCH_ALIAS_MISSING|没有可用的变异检索名称/.test(reason) ? '缺少可用的变异检索名称' : '',
            attention: true,
        });
        let saved = checkpoint?.read(fingerprint);
        let task;
        // This legacy rejection happened before a worker was created. Retry only
        // that obsolete preparation failure, never an uncertain/running task.
        if (base.endsWith('/pp1') && saved?.blocked?.includes('PP1_PUBLIC_SEARCH_ALIAS_UNAVAILABLE') && !saved.taskId)
            saved = undefined;
        if (saved?.blocked) { recordBlocked(saved.blocked); return { status: 'BLOCKED' }; }
        const recover = async () => {
            progress('正在核对提交回执；只读取，不重复启动资料任务');
            for (let attempt = 0; attempt < 12; attempt += 1) {
                check();
                const receipt = await get(`${root}/submissions/${saved.operationId}`);
                const lane = base.endsWith('/pp1') ? 'pp1' : base.endsWith('/pm3') ? 'pm3' : 'denovo';
                if (receipt.status !== 'NOT_FOUND' && (receipt.requestSha256 !== requestSha256 || receipt.lane !== lane))
                    throw new Error('提交回执与本次输入不一致；停止恢复。');
                if (receipt.status === 'ACKNOWLEDGED' && /^[a-f0-9]{64}$/.test(receipt.taskId || '')) {
                    saved = { ...saved, taskId: receipt.taskId }; checkpoint?.write(fingerprint, saved);
                    return get(`${base}/tasks/${receipt.taskId}`);
                }
                if (!['NOT_FOUND', 'STARTING'].includes(receipt.status)) throw new Error('提交回执状态异常。');
                if (attempt < 11) await wait(5000);
            }
            const uncertain = new Error('资料提交仍待核对；未重复提交或继续评价。稍后再次点击可继续读取同一回执。');
            uncertain.automaticSubmissionUncertain = true;
            throw uncertain;
        };
        if (saved) task = saved.taskId ? await get(`${base}/tasks/${saved.taskId}`) : await recover();
        else {
            saved = { operationId: globalThis.crypto.randomUUID() };
            // Write before sending. Storage failures must not leave an untrackable POST.
            checkpoint?.write(fingerprint, saved);
            try {
                task = await post(`${base}/tasks`, request, { 'X-Varvis-Submission-Id': saved.operationId });
            } catch (error) {
                if ([404, 409].includes(error.response?.status)) {
                    const reason = error.response?.data?.message || error.message;
                    const detail = String(reason).includes('PP1_PUBLIC_SEARCH_ALIAS_MISSING')
                        ? '当前基因的已有转录本注释中没有可用的变异检索名称；尚未检索，不能解释为没有相关论文。'
                        : reason;
                    const blocked = `资料准备不可用：${request.candidateId || ''} ${request.hypothesisId || ''} ${detail}`;
                    checkpoint?.write(fingerprint, { ...saved, blocked });
                    recordBlocked(reason);
                    return { status: 'BLOCKED' };
                }
                if (!checkpoint) throw error;
                task = await recover();
            }
        }
        if (!/^[a-f0-9]{64}$/.test(task?.taskId || '')) throw new Error('资料任务回执无效；请核对任务记录后继续。');
        if (saved.taskId && task.taskId !== saved.taskId) throw new Error('资料任务身份与提交回执不一致；停止恢复。');
        const expectedTaskId = task.taskId;
        await onTask({ base, taskId: task.taskId, status: task.status });
        const started = Date.now();
        const show = () => progress(`${label} · ${materialTaskSummary(task)} · 等待 ${Math.floor((Date.now() - started) / 1000)} 秒`);
        show();
        checkpoint?.write(fingerprint, { ...saved, taskId: task.taskId });
        for (let reads = 0; ['QUEUED', 'RUNNING'].includes(task.status); reads += 1) {
            if (reads >= 1084 || Date.now() - started >= 90 * 60 * 1000) throw new Error(`资料任务仍在运行：${task.taskId}。请在资料详情核对，勿重复提交。`);
            await wait(literaturePollDelay(reads)); check();
            task = await get(`${base}/tasks/${task.taskId}`);
            if (!task || task.taskId !== expectedTaskId) throw new Error('未找到身份一致的已启动资料任务；停止评价，避免采用不完整快照。');
            show();
            await onTask({ base, taskId: task.taskId, status: task.status });
        }
        if (!['READY', 'FAILED', 'CANCELLED', 'BLOCKED'].includes(task.status)) throw new Error('资料任务状态未知，待核查。');
        record(label, materialTaskPresentation(task));
        settled.set(fingerprint, task);
        await onTask(null);
        await controlPoint();
        return task;
    };
    const options = async base => {
        await controlPoint();
        check();
        try { return await get(`${base}/options`, { topN }); }
        catch (error) {
            // Missing/disabled modules have explicit unavailable outcomes in the final evaluator.
            if ([404, 409, 503].includes(error.response?.status)) {
                record('PS2 / PM6', { status: '资料入口暂不可用', attention: true });
                return null;
            }
            throw error;
        }
    };
    const selected = code => !declaration.selectedCriteria || declaration.selectedCriteria.includes(`ACMG_AMP_2015:${code}`);
    // Use the original recommendation bindings before de novo intake can refresh them.
    const pp1Attempts = declaration.hypotheses || [];
    if (selected('PP1') && pp1Attempts.length) {
        progress('核对本地 PP1 家系资料');
        await controlPoint();
        check();
        const plan = await post(`${root}/pp1/local-review`, materialDeclaration).catch(error => {
            if (error.response?.status === 404) return null;
            throw error;
        });
        const attemptKey = h => JSON.stringify([h.candidateId,h.hypothesisId]);
        const declared = new Set(pp1Attempts.map(e => attemptKey(e.hypothesis)));
        let targets = pp1Attempts.map(e => e.hypothesis);
        if (plan) {
            if (plan.schemaVersion !== 'pp1-local-review-plan-v1' || !/^[a-f0-9]{64}$/.test(plan.planId)
                || !Array.isArray(plan.attempts) || plan.attempts.some(h => !h || !declared.has(attemptKey(h)))
                || new Set(plan.attempts.map(attemptKey)).size !== plan.attempts.length)
                throw new Error('PP1 本地核查回执无效。');
            targets = plan.attempts;
            if (plan.reasonCode === 'PP1_LOCAL_REVIEW_UNAVAILABLE')
                record('PP1', { status: '逐条核查', detail: '本地批量核查暂不可用', attention: false });
        }
        for (const h of targets) {
            progress(['准备 PP1 家系资料', candidateLabels[h.candidateId]].filter(Boolean).join(' · '));
            await settle(`${root}/pp1`, { declaration: materialDeclaration, candidateId: h.candidateId, hypothesisId: h.hypothesisId,
                confirmPublicModel: true, ...(plan ? { batchPlanId: plan.planId } : {}) });
        }
    }
    if (selected('PS2') || selected('PM6')) {
        progress('核对本地 PS2 / PM6 资料');
        await controlPoint();
        check();
        const plan = await post(`${root}/local-review`, declaration);
        if (plan?.schemaVersion !== 'denovo-local-discovery-plan-v1' || !Array.isArray(plan.candidateIds)
            || plan.candidateIds.length > topN || new Set(plan.candidateIds).size !== plan.candidateIds.length
            || plan.candidateIds.some(id => typeof id !== 'string' || !id.trim()))
            throw new Error('PS2 / PM6 本地核查回执无效。');
        if (plan.candidateIds.length) {
            const deNovo = await options(root);
            if (deNovo?.enabled) {
                if (!/^[a-f0-9]{64}$/.test(deNovo.selectionSha256 || '')) throw new Error('PS2/PM6 检索身份缺失。');
                progress('补查缺少的 PS2 / PM6 文献资料');
                const task = await settle(root, { topN, selectionSha256: deNovo.selectionSha256, confirmPublicModel: true,
                    searchMode: 'LITVAR_ONLY', candidateIds: plan.candidateIds });
                if (task.status === 'READY' && deNovo.candidateAcceptanceEnabled) {
                    const accepted = new Map();
                    for (const paper of task.result?.papers || []) if (paper.candidateIntake) {
                        check();
                        if (!/^PMC[1-9][0-9]*$/.test(paper.pmcid || '')) throw new Error('文献接收缺少有效论文身份。');
                        const pin = JSON.stringify(paper);
                        if (accepted.has(paper.pmcid)) {
                            if (accepted.get(paper.pmcid) !== pin) throw new Error('同篇论文返回不同资料版本，请核查后继续；未合并冲突资料。');
                            continue;
                        }
                        progress(`PS2 / PM6：核查并接收 ${paper.pmcid} 已提取资料`);
                        const current = accepted.size ? await options(root) : deNovo;
                        const receipt = await post(`${root}/tasks/${task.taskId}/papers/${paper.pmcid}/accept`,
                            { topN, selectionSha256: current?.selectionSha256, confirmSourceIntake: true });
                        if (receipt.status !== 'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED' || receipt.scientificExecution !== false)
                            throw new Error('文献资料接收回执异常；未提交证据评价。');
                        accepted.set(paper.pmcid, pin);
                        onAccepted();
                    }
                }
            } else if (deNovo) record('PS2 / PM6', { status: '文献补查未启用', attention: true });
        }
    }
    check();
    checkpoint?.clear();
    return [...notes.values()];
}
