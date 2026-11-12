// These views explain processing records. They never establish source qualification,
// an ACMG result, or permission to execute work from a saved result.
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const count = (value) => Number.isSafeInteger(value) && value >= 0 ? value : null;
const code = (value) => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,191}$/.test(value) ? value : null;
const pin = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const pmcid = (value) => typeof value === 'string' && /^PMC[1-9][0-9]{0,28}$/.test(value);

const stageText = {
    ACQUISITION: '获取原件', SOURCE_REVIEW: '核对原文事实', EXTRACTION: '提取资料', REVIEW: '复核提取内容',
    PEDIGREE_CHECKPOINT_CAPTURE: '保存家系图核查记录', REVIEW_CACHE_REGISTRATION: '登记已核资料',
    PUBLIC_SOURCE_PROJECTION: '整理可展示的来源资料',
};
const stateText = {
    SOURCE_CHECKED: '已有资料检查记录', NOT_EXTRACTED: '尚未提取', NOT_REVIEWED: '尚未复核',
    TEMPORARY_FAILURE: '暂时执行失败', EXECUTION_FAILED: '执行失败', SOURCE_REJECTED: '来源检查未通过',
    EXTRACTION_INCOMPLETE: '提取尚未完成', SYSTEM_TRANSFER_INCOMPLETE: '原件传输尚未完成',
};
export const literatureStageText = (value) => stageText[code(value)] || (code(value) ? `处理环节（${value}）` : '处理环节未记录');
export const literatureProcessingText = (value) => stateText[code(value)] || (code(value) ? `处理状态（${value}）` : '处理状态未记录');
export const literatureReasonCode = code;
const diagnosticReasons = {
    PAPER_DOWNLOAD_TIMEOUT: '原件下载超时，尚未完整取得资料',
    PAPER_DOWNLOAD_TRANSPORT_FAILURE: '下载连接中断，尚未完整取得原件',
    PAPER_DOWNLOAD_HTTP_FAILURE: '原件服务请求失败，尚未完成下载',
    PAPER_DOWNLOAD_WORKER_FAILURE: '原件获取任务失败，已取得部分仍保留',
    PAPER_DOWNLOAD_BODY_TRUNCATED: '下载内容不完整，需要核对原件传输',
    PAPER_DOWNLOAD_BODY_SIZE_MISMATCH: '下载大小与原件记录不一致，尚不能作为完整原件读取',
    DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED: '本轮处理时间不足，该阶段尚未完成，已完成资料保留',
    SOURCE_REVIEW_PENDING: '原文资料尚待复核',
};
export const literatureDiagnosticReason = (value, explain) => code(value)
    ? diagnosticReasons[value] || (explain ? explain(value) : `原因：${value}`) : '本记录尚未提供具体原因';

// A current task response is the authority for continuing. Historical progress,
// a schema label, a source hash or zero modelCalls never supplies that authority.
export function currentLiteratureContinuationIssue(task, criterion) {
    if (task?.status !== 'READY') return '资料任务尚未结束；当前只能只读刷新进度或查看失败原因。';
    if (!object(task.result)) return '当前任务尚未完整读回处理结果，暂不能继续。';
    const visited = new Set();
    const requiresReconciliation = (result, depth = 0) => {
        if (!object(result)) return false;
        if (depth > 8 || visited.size >= 512 || visited.has(result)) return true;
        visited.add(result);
        if (result.callAccountingStatus === 'CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT'
            || result.readingLoop?.failedAttemptAccounting === 'CHECK_SHARED_LEDGER'
            || [result.modelChargeStatus, result.reading?.recovery?.modelChargeStatus,
                result.readingState?.recovery?.modelChargeStatus, result.readingState?.sourceExecutionRecovery?.modelChargeStatus]
                .includes('FAILED_OR_UNKNOWN_RESERVATION_RETAINED')
            || result.readingState?.recovery?.sharedBudgetReconciliationRequired === true
            || result.readingState?.sourceExecutionRecovery?.sharedBudgetReconciliationRequired === true
            || result.reading?.recovery?.sharedBudgetReconciliationRequired === true) return true;
        if (criterion === 'PP1' && Array.isArray(result.issues)
            && result.issues.some((issue) => typeof issue === 'string' && issue.startsWith('DENOVO_MODEL_'))) return true;
        return Array.isArray(result.papers) && result.papers.some((paper) => requiresReconciliation(paper, depth + 1));
    };
    if (task.sharedBudgetReconciliationRequired === true || requiresReconciliation(task.result))
        return '先前调用的执行与费用记录尚待核对，当前不能再次调用；已取得的资料继续保留。';
    const discovery = criterion === 'PP1' && task.result.schemaVersion === 'pp1-discovery-process-v1';
    const blockedReason = discovery ? task.discoveryContinuationBlockedReason : task.processingContinuationBlockedReason;
    const permission = discovery ? task.canContinueDiscovery : task.canContinueProcessing;
    if (blockedReason !== undefined && blockedReason !== null && blockedReason !== '') {
        const reason = code(blockedReason);
        if (reason?.includes('RECONCILIATION')) return '先前调用的执行与费用记录尚待核对，服务端当前不允许再次调用。';
        if (reason?.includes('CONTINUATION_LIMIT')) return '已达到当前资料任务的续处理次数上限；现有资料仍可查看。';
        if (reason?.includes('CONFIGURATION') || reason?.includes('DEPENDENCY'))
            return '当前任务依赖已发生变化，服务端尚未允许继续；请先核对资料与配置。';
        return `服务端暂不允许继续${reason ? `（${reason}）` : '，阻断原因尚待核对'}。`;
    }
    if (permission !== true)
        return permission === false ? '服务端当前没有允许继续处理的工作；已有资料与停止原因仍可查看。'
            : '尚未取得当前服务端的继续许可；旧记录和阅读进度仅供查看，不会据此启动新任务。';
    const attempt = count(discovery ? task.discoveryAttempt : task.processingAttempt);
    const maximum = count(discovery ? task.maximumDiscoveryAttempts : task.maximumProcessingAttempts);
    if (attempt === null || maximum === null || attempt < 1 || maximum < 1)
        return '当前任务的续处理次数与上限尚未完整读回，暂不能继续。';
    if (attempt >= maximum) return '已达到当前资料任务的续处理次数上限；现有资料仍可查看。';
    return null;
}

export function pp1ReadingDiagnostic(readingState, expected = {}) {
    if (!object(readingState)) return null;
    if (readingState.projection?.status === 'NOT_RECORDED') return null;
    if (readingState.projection?.status === 'WITHHELD') return { withheld: true, reasonCode: code(readingState.projection.reasonCode), kind: 'PP1' };
    const checkpoint = readingState.checkpoint;
    if (!object(checkpoint) || !pmcid(checkpoint.pmcid) || !pin(checkpoint.bundleSha256)
        || (expected.pmcid && checkpoint.pmcid !== expected.pmcid)
        || (expected.bundleSha256 && checkpoint.bundleSha256 !== expected.bundleSha256))
        return { invalidBinding: true, kind: 'PP1' };
    const required = readingState.required || {}, available = readingState.available || {};
    if ((pin(required.registeredSourceBundle) && required.registeredSourceBundle !== checkpoint.bundleSha256)
        || (pin(available.verifiedSourceBundle) && available.verifiedSourceBundle !== checkpoint.bundleSha256))
        return { invalidBinding: true, kind: 'PP1' };
    const stageGaps = Array.isArray(readingState.stageGaps) ? readingState.stageGaps : [];
    if (stageGaps.some((gap) => (gap?.scope?.pmcid && gap.scope.pmcid !== checkpoint.pmcid)
        || (gap?.scope?.bundleSha256 && gap.scope.bundleSha256 !== checkpoint.bundleSha256)))
        return { invalidBinding: true, kind: 'PP1' };
    const minimum = count(required.sourceReviewArtifactsAtLeast), reviewed = count(available.acceptedReviewArtifacts);
    const sourceRequired = pin(required.registeredSourceBundle), sourceAvailable = pin(available.verifiedSourceBundle);
    const missing = Array.isArray(readingState.missing) ? readingState.missing : [];
    const displayedGaps = stageGaps.slice(0, 64).filter((gap) => gap?.kind === 'SYSTEM_STAGE');
    return {
        kind: 'PP1', stage: code(readingState.stage),
        rows: [
            { label: '资料原件', required: sourceRequired ? '本次登记的原件' : '未记录',
                available: sourceAvailable ? '已记录原件摘要' : '未记录可用原件摘要',
                difference: sourceRequired && sourceAvailable ? (required.registeredSourceBundle === available.verifiedSourceBundle
                    ? '两项原件摘要一致；资料资格仍按判定核查' : '所需与已有原件摘要不一致') : '原件关联信息尚未完整记录' },
            { label: '原文复核', required: minimum === null ? '未记录所需复核数量' : `至少 ${minimum} 份可重放复核`,
                available: reviewed === null ? '未记录复核数量' : `已记录 ${reviewed} 份复核`,
                difference: minimum === null || reviewed === null ? '无法计算数量差异'
                    : reviewed < minimum ? `至少还需 ${minimum - reviewed} 份复核` : '已达到本环节数量要求；不代表证据已满足' },
        ],
        textUnits: count(available.textUnits), qualifiedObservations: count(available.qualifiedObservations),
        stageGaps: displayedGaps.map((gap) => ({ stage: code(gap.stage), reasonCode: code(gap.reasonCode),
                required: count(gap.required?.minimumReviewArtifacts), available: count(gap.available?.replayedReviewArtifacts) })),
        stageGapTotal: stageGaps.length, omittedStageGaps: stageGaps.length - displayedGaps.length,
        missing: missing.slice(0, 128).map((need) => ({ reasonCode: code(need?.reasonCode),
            knownFactCount: count(need?.knownFactCount), sourceLocatorCount: count(need?.sourceLocatorCount) })),
        missingDetails: readingState.projection?.status === 'AVAILABLE'
            ? missing.slice(0, 128).map(publicMissingNeedDiagnostic).filter(Boolean) : undefined,
        missingTotal: object(readingState.projection) ? count(readingState.projection.missingTotal) : missing.length,
        missingOmitted: object(readingState.projection) ? count(readingState.projection.missingOmitted) : Math.max(0, missing.length - 128),
        missingInvalid: count(readingState.projection?.missingInvalid),
        missingCountsUnknown: object(readingState.projection) && ['missingTotal', 'missingOmitted', 'missingInvalid']
            .some((field) => count(readingState.projection[field]) === null),
    };
}

export function pm3CheckpointDiagnostic(checkpoint, expected = {}) {
    if (!object(checkpoint)) return null;
    if (checkpoint.projection?.status === 'NOT_RECORDED') return null;
    if ((expected.pmcid && checkpoint.pmcid && checkpoint.pmcid !== expected.pmcid)
        || (expected.bundleSha256 && checkpoint.bundleSha256 && checkpoint.bundleSha256 !== expected.bundleSha256))
        return { invalidBinding: true, kind: 'PM3' };
    if (checkpoint.projection?.status === 'WITHHELD') return { withheld: true, reasonCode: code(checkpoint.projection.reasonCode), kind: 'PM3',
        withheldStageTotal: count(checkpoint.projection.stageTotal), withheldStageOmitted: count(checkpoint.projection.stageOmitted),
        withheldStagePending: count(checkpoint.projection.stagePending), withheldSourceRejected: count(checkpoint.projection.sourceRejectedStages) };
    if (!pmcid(checkpoint.pmcid) || !pin(checkpoint.bundleSha256)
        || (expected.pmcid && checkpoint.pmcid !== expected.pmcid)
        || (expected.bundleSha256 && checkpoint.bundleSha256 !== expected.bundleSha256))
        return { invalidBinding: true, kind: 'PM3' };
    const original = Array.isArray(checkpoint.stages) ? checkpoint.stages : [];
    return { kind: 'PM3',
        stages: original.slice(0, 512).filter((stage) => count(stage?.chunkIndex) !== null && stage.chunkIndex <= 4095
            && ['EXTRACTION', 'REVIEW'].includes(stage.stage)).map((stage) => ({ chunkIndex: stage.chunkIndex,
            stage: stage.stage, status: code(stage.status), reasonCode: code(stage.reasonCode) })),
        stageTotal: object(checkpoint.projection) ? count(checkpoint.projection.stageTotal) : Array.isArray(checkpoint.stages) ? original.length : null,
        omittedStages: object(checkpoint.projection) ? count(checkpoint.projection.stageOmitted) === null ? null
            : checkpoint.projection.stageOmitted + Math.max(0, original.length - 512) : Math.max(0, original.length - 512),
        stageCountsUnknown: !Array.isArray(checkpoint.stages) || (object(checkpoint.projection) && ['stageTotal', 'stageOmitted', 'stagePending', 'sourceRejectedStages']
            .some((field) => count(checkpoint.projection[field]) === null)),
        pendingStages: count(checkpoint.projection?.stagePending), sourceRejectedStages: count(checkpoint.projection?.sourceRejectedStages),
        stoppedAfterFailure: checkpoint.newModelWorkStoppedAfterFailure === true,
    };
}

export function literatureAcquisitionDiagnostic(recovery, projection) {
    if (projection?.status === 'WITHHELD') return { withheld: true, reasonCode: code(projection.reasonCode) };
    if (projection?.status === 'NOT_RECORDED' || !Array.isArray(recovery)
        || (!recovery.length && !(count(projection?.total) > 0))) return null;
    return { items: recovery.slice(0, 200).filter((item) => item?.stage === 'ACQUISITION'
        && item.status === 'SYSTEM_TRANSFER_INCOMPLETE' && item.sourceAbsenceProven === false
        && (item.pmcid === null || pmcid(item.pmcid))).map((item) => ({
        pmcid: pmcid(item.pmcid) ? item.pmcid : null, reasonCode: code(item.reasonCode),
    })), total: object(projection) ? count(projection.total) : recovery.length,
        omitted: object(projection) ? count(projection.omitted) === null ? null
            : projection.omitted + Math.max(0, recovery.length - 200) : Math.max(0, recovery.length - 200),
        countsUnknown: object(projection) && (count(projection.total) === null || count(projection.omitted) === null) };
}

// The normal saved result already carries the scientific/source binding. These
// checks keep display rows joined to that capsule; they confer no qualification.
export function savedPp1ReadingDiagnostic(row, facts) {
    if (!object(row?.readingState?.projection)) return null;
    if (row.readingState.projection.status === 'NOT_RECORDED') return null;
    if (![row.taskId, row.bundleSha256, row.capturedResultSha256, row.analysisBindingSha256, row.selectionSha256].every(pin)
        || row.bindingPinKind !== 'CAPTURED_PAPER_RESULT_CANONICAL_SHA256'
        || (facts?.evaluation?.target?.geneSymbol && row.target?.geneSymbol !== facts.evaluation.target.geneSymbol))
        return { invalidBinding: true, kind: 'PP1' };
    return pp1ReadingDiagnostic(row.readingState, row);
}
function pm3SavedTaskMatches(row, facts) {
    const references = Array.isArray(facts?.sourceTaskReferences) ? facts.sourceTaskReferences : [];
    if (references.length && !references.some((reference) => reference.taskId === row.taskId
        && reference.capturedResultSha256 === row.capturedResultSha256 && reference.selectionSha256 === row.selectionSha256)) return false;
    return true;
}
function pm3SavedRowJoined(row, facts, allowSizeWithholding = false) {
    return [row?.taskId, row?.capturedResultSha256, row?.selectionSha256, row?.sourceSnapshotSha256].every(pin)
        && (row.projection?.status === 'AVAILABLE' || (allowSizeWithholding && row.projection?.status === 'WITHHELD'
            && row.projection.reasonCode === 'PM3_STAGE_PUBLIC_SIZE_LIMIT')) && row.projection.bindingPinKind === 'WORKER_RESULT_RAW_SHA256'
        && (!pin(facts?.sourceSnapshotSha256) || row.sourceSnapshotSha256 === facts.sourceSnapshotSha256)
        && pm3SavedTaskMatches(row, facts);
}
export function savedPm3CheckpointDiagnostic(row, facts) {
    if (!object(row?.processingCheckpoint?.projection)) return null;
    if (row.processingCheckpoint.projection.status === 'NOT_RECORDED') return null;
    if (row.projection?.status === 'WITHHELD' && row.projection.reasonCode !== 'PM3_STAGE_PUBLIC_SIZE_LIMIT')
        return { withheld: true, reasonCode: code(row.projection.reasonCode), kind: 'PM3' };
    if (!pm3SavedRowJoined(row, facts, true))
        return { invalidBinding: true, kind: 'PM3' };
    return pm3CheckpointDiagnostic(row.processingCheckpoint, row);
}
export function savedPm3AcquisitionDiagnostic(row, facts) {
    if (!object(row?.acquisitionProjection)) return null;
    if (row.projection?.status === 'WITHHELD') return { withheld: true, reasonCode: code(row.projection.reasonCode) };
    if (!pm3SavedRowJoined(row, facts)) return { withheld: true };
    return literatureAcquisitionDiagnostic(row.acquisitionRecovery, row.acquisitionProjection);
}
export function savedPm3TaskProblem(row, facts) {
    if (!pm3SavedRowJoined(row, facts)) return null;
    const stop = code(row.readingLoop?.stopReason || row.readingLoop?.feedback?.stopReason);
    if (row.reconciliationRequired === true) return { kind: 'RECONCILIATION', stop };
    if (['PM3_WORKER_FAILED', 'PM3_WORKER_TIMEOUT', 'PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED'].includes(stop))
        return { kind: 'EXECUTION_FAILURE', stop };
    if (row.readingLoop?.state === 'CONFLICT_REQUIRES_REVIEW' || row.readingLoop?.feedback?.sourceConflict === true)
        return { kind: 'SOURCE_CONFLICT', stop };
    if (row.readingLoop?.state === 'DEPENDENCY_UNRESOLVED') return { kind: 'DEPENDENCY', stop };
    return null;
}

const shortPublicText = (value, maximum = 160) => typeof value === 'string' && value.trim() && value.length <= maximum
    && !Array.from(value).some((character) => character.codePointAt(0) < 32 || character.codePointAt(0) === 127)
    && !/(?:[A-Za-z]:[\\/]|https?:\/\/|file:\/\/|(?:^|\s)\/(?:[^\s]+\/))/i.test(value)
    ? value : null;
const typedValueText = (value) => {
    if (typeof value === 'boolean') return value ? '是' : '否';
    if (typeof value === 'number') return Number.isFinite(value) ? String(value) : null;
    if (typeof value === 'string') return shortPublicText(value);
    if (Array.isArray(value) && value.length <= 16) {
        if (!value.length) return '空列表（未登记条目）';
        const parts = value.map((item) => typeof item === 'object' ? null : typedValueText(item));
        return parts.every((item) => item !== null) ? parts.join('、') : null;
    }
    return null;
};
const factLabels = {
    clinical: '临床状态记录', genotype: '目标变异基因型记录', diagnosis: '原文疾病表述', sex: '性别记录',
    proband: '先证者角色记录', age: '年龄记录', examinationAge: '检查年龄记录', consanguinity: '近亲婚配记录',
    parentOf: '亲代关系记录', childOf: '子代关系记录', fullSiblingOf: '同胞关系记录',
    reportedDiagnosticCriteria: '原文诊断标准名称', reportedVariant: '原文变异写法', candidateScope: '候选排查范围记录',
    candidateMethod: '候选排查方法记录', formalAscertainment: '取样机制记录', biallelic: '双等位构型记录',
    phase: '相位记录', phaseAnchor: '相位锚定成员标识', dosage: '记录的等位基因剂量', transcript: '转录本记录',
    reportedTranscript: '原文转录本名称', targetVariant: '目标变异写法', partnerVariant: '伙伴变异写法', variant: '变异写法',
    method: '方法记录', geneDiseaseValidity: '基因与疾病关联级别', expertPolicyStatus: '专家规范状态',
    penetrance: '外显率条件', phenocopy: '拟表型条件', locusModel: '位点模型',
};
const factEnums = {
    AFFECTED: '报告为受累；仍需对应本次疾病定义', UNAFFECTED: '报告为未受累；仍需核对观察年龄和疾病定义',
    CARRIER: '携带目标变异，未说明具体合子类型', NONCARRIER: '未携带目标变异', HETEROZYGOUS: '杂合',
    HOMOZYGOUS: '纯合', HEMIZYGOUS: '半合子', MALE: '男性', FEMALE: '女性', UNKNOWN: '尚未核定',
    SINGLE_REPORTED_CANDIDATE: '原文仅报告一个候选；排查范围资格仍须核对',
    PHENOTYPE_BEFORE_GENOTYPING: '记录为先依据表型识别、后检测基因', IN_TRANS: '记录为反式',
};
function publicKnownFactText(fact) {
    if (!object(fact)) return null;
    if (factLabels[fact.kind]) {
        let value = typedValueText(fact.value);
        if (['reportedVariant', 'targetVariant', 'partnerVariant', 'variant'].includes(fact.kind)) {
            const raw = shortPublicText(fact.value, 300);
            value = raw && /^(?:(?:N[CMRPG]_[0-9]+\.[0-9]+|ENS[TP][0-9]+\.[0-9]+):)?[cgmnp]\.[0-9A-Za-z_?*()[\]+=>-]{1,180}$/.test(raw.replace(/ /g, ''))
                ? raw : '已记录相关变异描述；具体原句见出处，身份仍待核对';
        } else if (fact.kind === 'biallelic') value = object(fact.value) && factEnums[fact.value.phase]
            ? factEnums[fact.value.phase] : '已登记构型资料；具体含义仍须核对';
        else if (fact.kind === 'formalAscertainment' && object(fact.value)) value = '已登记取样机制材料；具体资格见判定及出处';
        else if (typeof fact.value === 'string' && factEnums[fact.value]) value = factEnums[fact.value];
        if (value !== null) return `${factLabels[fact.kind]}：${value}`;
    }
    // Existing formal clinical records carry these typed fields rather than
    // kind/value. Do not copy sourceValue, quotes, arbitrary objects or extras.
    const parts = [];
    if (['AFFECTED', 'UNAFFECTED'].includes(fact.status)) parts.push(`临床状态：${factEnums[fact.status]}`);
    if (count(fact.attainedAge) !== null && fact.attainedAge <= 999) parts.push(`记录的观察年龄：${fact.attainedAge} 岁`);
    if (shortPublicText(fact.diseaseTerm, 120)) parts.push(`原文疾病表述：${fact.diseaseTerm}`);
    return parts.length ? parts.join('；') : null;
}

// Rich missing rows use the actual bounded public projection, separately from
// legacy evaluation.needs. Its captured values remain explanation, not adoption.
function publicMissingNeedDiagnostic(need) {
    if (!object(need)) return null;
    const available = (projection) => ['AVAILABLE', 'PARTIAL'].includes(projection?.status);
    const facts = available(need.knownFactsProjection) && Array.isArray(need.knownFacts) ? need.knownFacts.filter((fact) => {
        if (fact?.interpretation !== 'CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION') return false;
        switch (fact.valueType) {
            case 'ENUM': return typeof fact.value === 'string' && code(fact.value) !== null;
            case 'BOOLEAN': return typeof fact.value === 'boolean';
            case 'INTEGER': return count(fact.value) !== null;
            case 'OPAQUE_MEMBER_ID': return pin(fact.value);
            case 'SOURCE_ACCESSION': return typeof fact.value === 'string' && /^(?:N[CMRPG]_[0-9]+|ENS[TP][0-9]+)\.[1-9][0-9]*$/.test(fact.value);
            case 'HGVS_LITERAL': return typeof fact.value === 'string' && shortPublicText(fact.value, 300)
                && /^(?:(?:N[CMRPG]_[0-9]+\.[0-9]+|ENS[TP][0-9]+\.[0-9]+):)?[cgmnp]\.[0-9A-Za-z_?*()[\]+=>-]{1,180}$/.test(fact.value.replace(/ /g, ''));
            default: return false;
        }
    }).slice(0, 8) : [];
    const inputs = available(need.requiredInputsProjection) && Array.isArray(need.requiredInputs) ? need.requiredInputs.slice(0, 8) : [];
    const programRequirement = need.requirementProjection?.status === 'AVAILABLE'
        && ['PROGRAM_REQUIREMENTS_CATALOG', 'PROGRAM_ROUTE_REQUIREMENTS_CATALOG'].includes(need.requirementProjection.source);
    const view = evaluationNeedDiagnostic({ reasonCode: need.reasonCode, blockingEffect: need.blockingEffect,
        scope: { missingInputs: inputs }, knownFacts: facts, requirement: programRequirement ? need.requirement : undefined });
    view.scopes = [];
    if (available(need.scopeProjection)) {
        if (need.scopeProjection.kind === 'OPAQUE_ID' && pin(need.scope)) view.scopes.push(`范围标识 ${need.scope}`);
        else if (need.scopeProjection.kind === 'OPAQUE_ID_LIST' && Array.isArray(need.scope))
            view.scopes.push(...need.scope.slice(0, 32).filter(pin).map((id) => `范围标识 ${id}`));
        else if (need.scopeProjection.kind === 'OBJECT' && object(need.scope)) {
            if (pmcid(need.scope.paper?.pmcid)) view.scopes.push(`论文 ${need.scope.paper.pmcid}`);
            for (const [field, label] of Object.entries({ familyKey: '家系标识', memberKey: '成员标识', claimId: '陈述标识',
                eventId: '事件标识', probandKey: '先证者标识' })) if (pin(need.scope[field])) view.scopes.push(`${label} ${need.scope[field]}`);
            for (const [field, label] of Object.entries({ priorEventIds: '既有事件标识', priorExclusiveFactIds: '既有排他事实标识' }))
                if (Array.isArray(need.scope[field])) view.scopes.push(...need.scope[field].slice(0, 32).filter(pin).map((id) => `${label} ${id}`));
        }
    }
    view.scopeUnrecorded = !view.scopes.length;
    view.undisplayedFactRecords = count(need.knownFactsProjection?.total) !== null
        ? Math.max(0, need.knownFactsProjection.total - view.facts.length) : null;
    view.locators = available(need.sourceLocatorProjection) && Array.isArray(need.sourceLocators) ? need.sourceLocators.slice(0, 8)
        .filter((locator) => pin(locator?.sourceSha256) && pmcid(locator.pmcid) && pin(locator.bundleSha256)
            && (locator.kind === 'SOURCE_PIN_ONLY' || (locator.bindingStatus === 'MATCHED_REGISTERED_SOURCE_UNIT'
                && typeof locator.unitId === 'string' && locator.unitId.length <= 1024
                && ((locator.kind === 'JATS_UNIT' && /^\/article(?:\[[1-9][0-9]*\])?(?:\/[A-Za-z][A-Za-z0-9_.-]*(?:\[[1-9][0-9]*\])?)*$/.test(locator.unitId))
                    || (locator.kind === 'ATTACHMENT_UNIT' && /^attachment:[a-f0-9]{64}:(?:member:[a-f0-9]{64}:[a-f0-9]{64}:)?[A-Za-z0-9_:/.[\]-]+$/.test(locator.unitId))))))
        .map((locator) => ({ pmcid: locator.pmcid, unitId: locator.kind === 'SOURCE_PIN_ONLY' ? undefined : locator.unitId,
            sourceSha256: locator.sourceSha256 })) : [];
    view.detailProjections = ['scopeProjection', 'requiredInputsProjection', 'knownFactsProjection', 'sourceLocatorProjection']
        .map((field) => ({ label: { scopeProjection: '对象范围', requiredInputsProjection: '所需条件', knownFactsProjection: '已有事实', sourceLocatorProjection: '来源定位' }[field],
            total: count(need[field]?.total), displayed: count(need[field]?.displayed), withheld: count(need[field]?.withheld), omitted: count(need[field]?.omitted) }))
        .filter((item) => item.withheld > 0 || item.omitted > 0);
    return view;
}

// Call only on evaluation.needs already published by the normal bound result.
// Raw readingState.missing has a different, deliberately smaller display view.
export function evaluationNeedDiagnostic(need) {
    if (!object(need)) return null;
    const scopes = [];
    const labels = { pmcid: '论文', caseLabel: '病例', familyLabel: '家系', memberLabel: '成员',
        familyKey: '家系标识', memberKey: '成员标识', observationId: '观察标识', sourceObservationId: '来源观察标识' };
    if (object(need.scope)) for (const [field, label] of Object.entries(labels)) {
        const value = shortPublicText(need.scope[field], 160);
        if (value) scopes.push(`${label} ${value}`);
    }
    const rawFacts = Array.isArray(need.knownFacts) ? need.knownFacts : [];
    const facts = rawFacts.slice(0, 32).map(publicKnownFactText).filter(Boolean);
    const actual = typedValueText(need.actual);
    if (actual !== null) facts.push(`当前输入：${actual}`);
    if (object(need.actual)) for (const [field, label] of Object.entries({ statuses: '记录的临床状态', attainedAges: '记录的年龄',
        diseaseTerms: '记录的疾病表述', issues: '当前核查问题', status: '当前状态', attainedAge: '当前观察年龄' })) {
        const text = typedValueText(need.actual[field]); if (text !== null) facts.push(`${label}：${text}`);
    }
    const missingInputs = Array.isArray(need.scope?.missingInputs) ? need.scope.missingInputs : [];
    const missingNames = missingInputs.map((field) => ({ CLINICAL_STATUS: '符合本次疾病定义的临床状态依据',
        TARGET_GENOTYPE: '目标变异的实际基因型依据', INFORMATIVE_RELATIONSHIP: '信息性亲缘关系依据' })[field]).filter(Boolean);
    const differences = {
        PP1_LOCAL_IDENTITY_PROVIDER_UNAVAILABLE: '原文已记载相关变异，但相容的本地身份解析尚不可用，当前不能完成身份核对。',
        PP1_PP4_LEDGER_UNKNOWN: '尚未取得同次、同变异和疾病背景的可信 PP4 贡献记录；未知不能当作零贡献。',
        PP1_QUALIFIED_CONTEXT_UNAVAILABLE: '当前方法所需的合格背景尚未完整提供；已有论文陈述不能替代该前提。',
    };
    return { scopes, reasonCode: code(need.reasonCode), required: shortPublicText(need.requirement, 600),
        expected: typedValueText(need.expected), facts, factRecordCount: rawFacts.length,
        undisplayedFactRecords: rawFacts.length - rawFacts.slice(0, 32).filter((fact) => publicKnownFactText(fact) !== null).length,
        difference: missingNames.length ? `尚缺：${missingNames.join('、')}。` : differences[need.reasonCode] || null,
        blockingEffect: code(need.blockingEffect) };
}
