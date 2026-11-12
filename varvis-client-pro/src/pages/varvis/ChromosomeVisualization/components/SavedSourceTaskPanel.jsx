import React from 'react';
import { Alert, Button, Stack, Typography } from '@mui/material';
import Pm3LiteraturePanel from './Pm3LiteraturePanel';
import Pp1LiteraturePanel from './Pp1LiteraturePanel';
import PublicLiteraturePanel from './PublicLiteraturePanel';
import DeNovoSourceTaskNotice from './DeNovoSourceTaskNotice';

/** Mount only after an explicit detail action; existing panels own live GET and confirmed POST. */
export default function SavedSourceTaskPanel({ link, run, sourceTaskContext, onReturn }) {
    const topN = run?.requestedN;
    const current = sourceTaskContext?.analysisId === link?.analysisId ? sourceTaskContext : null;
    let panel;
    if (link?.kind === 'PM3_LITERATURE') panel = <Pm3LiteraturePanel analysisId={link.analysisId} topN={topN}
        initialTaskId={link.taskId} initialTaskReference={link.reference} onReturn={onReturn} />;
    else if (link?.kind === 'PP1_LITERATURE') panel = <Pp1LiteraturePanel analysisId={link.analysisId}
        initialTaskId={link.taskId} draftIdentity={current?.draftIdentity} prepareDeclaration={current?.prepareDeclaration}
        candidates={current?.candidates || []} onReturn={onReturn} />;
    else if (link?.kind === 'PUBLIC_TASK_LIST') panel = <PublicLiteraturePanel analysisId={link.analysisId}
        topN={topN} readOnlyEntry onReturn={onReturn} />;
    else if (link?.kind === 'DENOVO_SOURCE_STATUS' && run?.analysisId === link.analysisId && run?.runId === link.runId &&
        ((!link.taskId && !run?.localBatch?.modules?.deNovo?.sourceTask) || run?.localBatch?.modules?.deNovo?.sourceTask?.taskId === link.taskId))
        panel = <DeNovoSourceTaskNotice run={run} readOnlyEntry onReturn={onReturn} />;
    else panel = <Alert severity="warning">来源任务类型或当前保存范围不相容，未读取或执行任务。<Button onClick={onReturn}>返回证据详情</Button></Alert>;
    return <Stack spacing={2} data-testid="saved-source-task-view">
        <Typography fontWeight={700}>原取证任务 · 实时只读核对</Typography>
        {panel}
    </Stack>;
}
