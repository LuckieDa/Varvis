import { validSourceTask } from './deNovoSourceTask';

const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const identity = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(value);
const pmc = value => typeof value === 'string' && /^PMC[0-9]+$/.test(value);
const PM3_SCOPE = 'GENE_AND_SELECTED_ALLELE_NOT_HYPOTHESIS_SPECIFIC';

/** Saved references locate tasks; they neither grant continuation nor change scientific results. */
export function savedSourceTaskLinks({ card, entry, run }) {
    const links = [], notes = [], issues = [];
    const id = card?.id;
    const criterion = entry?.savedCriterion?.criterionId === id ? entry.savedCriterion :
        entry?.pp1Criterion?.criterionId === id ? entry.pp1Criterion : null;
    const details = criterion?.details;
    const analysisId = identity(run?.analysisId) ? run.analysisId : null;
    if (id === 'ACMG_AMP_2015:PM3') {
        const refs = details?.pm3Facts?.sourceTaskReferences;
        const seen = new Set();
        for (const ref of Array.isArray(refs) ? refs : []) {
            if (ref?.kind !== 'PM3_LITERATURE' || !hash(ref.taskId) || !identity(ref.analysisId) ||
                !hash(ref.capturedResultSha256) || !hash(ref.selectionSha256) || ref.bindingScope !== PM3_SCOPE ||
                (ref.pmcid !== undefined && !pmc(ref.pmcid))) {
                issues.push('PM3_SOURCE_TASK_REFERENCE_INVALID'); continue;
            }
            const key = `${ref.analysisId}:${ref.taskId}`;
            if (seen.has(key)) {
                const previous = links.find(link => link.key === key);
                if (previous.reference.capturedResultSha256 !== ref.capturedResultSha256 || previous.reference.selectionSha256 !== ref.selectionSha256)
                    issues.push('PM3_SOURCE_TASK_REFERENCE_CONFLICT');
                continue;
            }
            seen.add(key);
            links.push({ key, kind: ref.kind, analysisId: ref.analysisId, taskId: ref.taskId,
                reference: { kind: ref.kind, analysisId: ref.analysisId, taskId: ref.taskId,
                    capturedResultSha256: ref.capturedResultSha256, selectionSha256: ref.selectionSha256,
                    bindingScope: ref.bindingScope, ...(ref.pmcid ? { pmcid: ref.pmcid } : {}) },
                label: `查看原 PM3 取证任务${ref.pmcid ? ` · ${ref.pmcid}` : ''}` });
        }
        if (issues.includes('PM3_SOURCE_TASK_REFERENCE_CONFLICT')) links.splice(0);
        if (!links.length) notes.push('本条保存结果没有可完整核对的 PM3 原任务标识；已有资料与判定保持，不能据此认定没有相关文献。');
        else notes.push('PM3 原任务对应基因、所选等位基因与候选范围，并非某个疾病背景专属；打开后只读取实际任务状态。');
    }
    if (id === 'ACMG_AMP_2015:PP1') {
        const coverage = details?.pp1Facts?.sourceCoverage;
        const seen = new Set();
        for (const source of Array.isArray(coverage) ? coverage : []) {
            if (source?.taskId === undefined) continue;
            if (!hash(source.taskId) || !analysisId || (source.pmcid !== undefined && !pmc(source.pmcid))) {
                issues.push('PP1_SOURCE_TASK_REFERENCE_INVALID'); continue;
            }
            if (seen.has(source.taskId)) continue;
            seen.add(source.taskId);
            links.push({ key: `${analysisId}:${source.taskId}`, kind: 'PP1_LITERATURE', analysisId, taskId: source.taskId,
                label: `查看原 PP1 取证任务${source.pmcid ? ` · ${source.pmcid}` : ''}` });
        }
        if (!links.length) notes.push('本条保存结果未记录可定位的 PP1 来源任务；不使用排除任务充当来源，也不把单篇资料摘要当作整个任务摘要。');
    }
    if (['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'].includes(id)) {
        const task = run?.localBatch?.modules?.deNovo?.sourceTask;
        if (analysisId && identity(run?.runId) && validSourceTask(task, { taskId: task?.taskId }))
            links.push({ key: `${analysisId}:${run.runId}:denovo`, kind: 'DENOVO_SOURCE_STATUS', analysisId, runId: run.runId,
                taskId: task.taskId, label: '查看本运行的新发资料任务状态' });
        else if (task) issues.push('DENOVO_SOURCE_TASK_BINDING_UNAVAILABLE');
        else if (analysisId && identity(run?.runId)) {
            links.push({ key: `${analysisId}:${run.runId}:denovo-lookup`, kind: 'DENOVO_SOURCE_STATUS', analysisId, runId: run.runId,
                label: '只读查询本运行的新发资料状态' });
            notes.push('此结果目录未附带新发资料任务摘要；按已保存运行查询实际绑定，读取失败单独说明，不推造任务编号。');
        }
        if (analysisId) {
            links.push({ key: `${analysisId}:public-list`, kind: 'PUBLIC_TASK_LIST', analysisId, label: '查看本分析已有公共文献任务' });
            notes.push('公共文献列表仅显示当前分析已有任务；不根据仓库任务编号、论文编号或原因推测来源归属，也不表示列表内任务已被本条采用。');
        }
    }
    return { links, notes, issues: [...new Set(issues)] };
}
