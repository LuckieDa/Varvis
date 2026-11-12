import React from 'react';
import { Alert, Button, Stack } from '@mui/material';
import { controlActions, terminalControl } from '../utils/acmgControl';

/** Live worker commands only. This view never starts or resumes source tasks. */
export default function AcmgRunControlActions({control, analysisId, runId, localPreparation=false, allowAcknowledge=true, busy=false, onAction}) {
    const localEnded=terminalControl(control?.state) && control?.state!=='INTERRUPTED' && !runId && !control?.runId && !Object.hasOwn(control || {},'availableActions');
    const view=localEnded ? {actions:[],issues:[]} : controlActions(control,{analysisId,runId,localPreparation,allowAcknowledge});
    return <Stack spacing={1} data-testid="acmg-live-control-actions">
        {view.issues.length>0 && <Alert severity="warning">运行控制尚未核实，请刷新状态。</Alert>}
        {view.actions.length>0 && <Stack direction="row" gap={1} useFlexGap flexWrap="wrap">
            {view.actions.map(action=><Button key={action.actionId} data-control-action={action.controlAction} disabled={busy}
                variant={action.controlAction==='RESUME'?'contained':'outlined'} color={action.controlAction==='STOP'?'error':'primary'}
                onClick={()=>onAction(action.controlAction)}>{action.label}</Button>)}
        </Stack>}
    </Stack>;
}
