export const controlLabels = {
    RUNNING: '正在执行', PAUSE_REQUESTED: '暂停中：等待当前步骤到达安全检查点',
    PAUSED: '已暂停；仅在点击继续后恢复', STOP_REQUESTED: '停止中：当前调用退出后不再启动下一步',
    CANCELLED: '本次分析已停止', INTERRUPTED: '原执行进程已退出，分析已中断',
    RECOVERY_REQUIRED: '旧记录仍标为运行中，但无法确认执行者；请先核对，不自动继续',
};
export const activeControl = state => ['RUNNING','PAUSE_REQUESTED','PAUSED','STOP_REQUESTED'].includes(state);
export const terminalControl = state => ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED','INTERRUPTED'].includes(state);
// Live controls are a separate, transient contract. They do not authorize
// scientific execution, a new source task, or resume in another worker/JVM.
const controlObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const controlText = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 256;
const controlDefinitions = {
    PAUSE_RUN: { controlAction: 'PAUSE', label: '暂停分析', requiresConfirmation: false },
    RESUME_RUN: { controlAction: 'RESUME', label: '继续分析', requiresConfirmation: false },
    STOP_RUN: { controlAction: 'STOP', label: '停止本次分析', requiresConfirmation: true },
    ACKNOWLEDGE_INTERRUPTED: { controlAction: 'ACKNOWLEDGE_INTERRUPTED', label: '确认旧运行已中断', requiresConfirmation: true },
};
const controlTerminal = state => ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'].includes(state);
const controlStates = new Set(['RUNNING','PAUSE_REQUESTED','PAUSED','STOP_REQUESTED','RECOVERY_REQUIRED','INTERRUPTED',
    'COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED']);
const actionKeys = ['actionId','controlAction','label','available','requiresConfirmation','createsNewRun','createsNewSourceTask',
    'overwritesSavedScientificResult','target'];
const owns = (value,key) => controlObject(value) && Object.hasOwn(value,key);

/** Prefer server actions. Missing availableActions alone invokes legacy fallback.
 * localPreparation is only the UI-owned preparation/awaiting-run-id queue; no
 * backend command is sent until its real run target and GET capabilities exist.
 */
export function controlActions(control, { analysisId, runId, allowAcknowledge = true, localPreparation = false } = {}) {
    const source = owns(control,'availableActions') ? 'SERVER' : 'LEGACY';
    const rejected = issue => ({ source, actions: [], issues: [issue] });
    if (!controlObject(control) || !controlStates.has(control.state)) return rejected('ACMG_CONTROL_RESPONSE_INVALID');
    const targeted = controlText(analysisId) && controlText(runId);
    if (!targeted && (!(source === 'LEGACY' && localPreparation === true) || owns(control,'analysisId') || owns(control,'runId')))
        return rejected('ACMG_CONTROL_TARGET_REQUIRED');
    if (targeted && (owns(control,'analysisId') && control.analysisId !== analysisId || owns(control,'runId') && control.runId !== runId))
        return rejected('ACMG_CONTROL_TARGET_MISMATCH');
    for (const field of ['activeWorkerAvailable','sameWorkerResumable'])
        if (owns(control,field) && typeof control[field] !== 'boolean') return rejected('ACMG_CONTROL_CAPABILITIES_INVALID');
    if (owns(control,'savedScientificResultChanged') && control.savedScientificResultChanged !== false)
        return rejected('ACMG_CONTROL_CAPABILITIES_INVALID');
    const liveUnavailable = control.activeWorkerAvailable === false;
    const resumeUnavailable = control.sameWorkerResumable === false;
    const permitted = command => {
        if (controlTerminal(control.state) || control.state === 'STOP_REQUESTED') return false;
        if (command === 'ACKNOWLEDGE_INTERRUPTED') return targeted && allowAcknowledge === true &&
            ['RECOVERY_REQUIRED','INTERRUPTED'].includes(control.state) && control.activeWorkerAvailable !== true && control.sameWorkerResumable !== true;
        if (liveUnavailable) return false;
        if (command === 'PAUSE') return control.state === 'RUNNING';
        if (command === 'RESUME') return !resumeUnavailable && ['PAUSED','PAUSE_REQUESTED'].includes(control.state);
        return command === 'STOP' && ['RUNNING','PAUSED','PAUSE_REQUESTED'].includes(control.state);
    };
    if (source === 'SERVER') {
        if (!targeted || control.analysisId !== analysisId || control.runId !== runId)
            return rejected('ACMG_CONTROL_TARGET_MISMATCH');
        if (!Array.isArray(control.availableActions) || control.availableActions.length > 4)
            return rejected('ACMG_CONTROL_ACTIONS_INVALID');
        const seen = new Set(), actions = [];
        for (const item of control.availableActions) {
            if (!controlObject(item) || Object.keys(item).some(key => !actionKeys.includes(key)) ||
                !actionKeys.every(key => Object.hasOwn(item,key)) || !controlObject(item.target) ||
                Object.keys(item.target).some(key => !['analysisId','runId'].includes(key)) || typeof item.available !== 'boolean' || !controlText(item.label))
                return rejected('ACMG_CONTROL_ACTIONS_INVALID');
            const definition = typeof item.actionId === 'string' && Object.hasOwn(controlDefinitions,item.actionId) ? controlDefinitions[item.actionId] : undefined;
            if (!definition || definition.controlAction !== item.controlAction || seen.has(item.actionId))
                return rejected('ACMG_CONTROL_ACTION_NOT_SUPPORTED');
            seen.add(item.actionId);
            if (item.target.analysisId !== analysisId || item.target.runId !== runId)
                return rejected('ACMG_CONTROL_ACTION_TARGET_MISMATCH');
            if (item.createsNewRun !== false || item.createsNewSourceTask !== false || item.overwritesSavedScientificResult !== false)
                return rejected('ACMG_CONTROL_ACTION_EFFECT_INVALID');
            if (item.requiresConfirmation !== definition.requiresConfirmation)
                return rejected('ACMG_CONTROL_ACTION_CONFIRMATION_INVALID');
            if (!item.available) continue;
            // A present capability list is authoritative, but contradictory
            // worker/state/target declarations cannot enable a known command.
            if (item.controlAction === 'ACKNOWLEDGE_INTERRUPTED' && allowAcknowledge !== true) continue;
            if (!permitted(item.controlAction)) return rejected('ACMG_CONTROL_ACTION_STATE_OR_WORKER_INVALID');
            actions.push({ actionId: item.actionId, ...definition, target: { analysisId, runId }, available: true,
                createsNewRun: false, createsNewSourceTask: false, overwritesSavedScientificResult: false });
        }
        return { source, actions, issues: [] };
    }
    const actions = Object.entries(controlDefinitions).filter(([,definition]) => permitted(definition.controlAction))
        .map(([actionId,definition]) => ({ actionId, ...definition, ...(targeted ? { target: { analysisId, runId } } : {}),
            available: true, createsNewRun: false, createsNewSourceTask: false, overwritesSavedScientificResult: false }));
    return { source, actions, issues: [] };
}
export const controlActionAvailable = (control, command, options) =>
    controlActions(control, options).actions.some(item => item.controlAction === command);

// Current live GET/POST capabilities remain separate from the saved scientific
// result. An older owner's receipt must not restore buttons after completion/ACK.
export function controlAfterHistory(previous, run) {
    const matching = controlObject(previous) && previous.analysisId === run?.analysisId && previous.runId === run?.runId;
    // INTERRUPTED can still offer ACK while worker ownership is unresolved.
    // It cannot override a saved completed/failed/cancelled result.
    if (matching && terminalControl(previous.state) &&
        !(previous.state === 'INTERRUPTED' && controlTerminal(run?.status))) return previous;
    // Persisted completion must supersede an older live worker snapshot too.
    // The opposite order still preserves a fresh terminal response over stale history.
    if (terminalControl(run?.status)) return {state:run.status};
    if (matching && owns(previous,'availableActions')) return previous;
    const saved=controlObject(run?.executionControl) ? {...run.executionControl} : {state:run?.status};
    for (const key of ['availableActions','activeWorkerAvailable','sameWorkerResumable','savedScientificResultChanged']) delete saved[key];
    return saved;
}

// A timed-out command may already have reached the server. Read its state once;
// never resend a control command or mistake a completed save for a paused run.
export async function commandWithReconciliation(action, command, read) {
    try { return await command(); }
    catch (error) {
        let value;
        try { value = await read(); } catch (_) { throw error; }
        const acknowledged = {
            PAUSE: ['PAUSE_REQUESTED','PAUSED'], RESUME: ['RUNNING'],
            STOP: ['STOP_REQUESTED','CANCELLED'], ACKNOWLEDGE_INTERRUPTED: ['INTERRUPTED'],
        }[action] || [];
        if (terminalControl(value?.state) || acknowledged.includes(value?.state)) return value;
        throw error;
    }
}
export function createPreparationControl(onState = () => undefined) {
    let state = 'RUNNING', wake;
    return {
        state: () => state,
        command(action) {
            if (action === 'PAUSE' && state === 'RUNNING') state = 'PAUSE_REQUESTED';
            else if (action === 'RESUME' && ['PAUSE_REQUESTED','PAUSED'].includes(state)) state = 'RUNNING';
            else if (action === 'STOP') state = 'CANCELLED';
            else throw new Error('当前步骤不能执行该控制操作。');
            onState(state); if (wake) wake();
        },
        async checkpoint() {
            while (['PAUSE_REQUESTED','PAUSED'].includes(state)) {
                state = 'PAUSED'; onState(state);
                await new Promise(resolve => { wake = resolve; }); wake = null;
            }
            if (state === 'CANCELLED') throw new Error('ACMG_USER_STOPPED');
        },
    };
}
