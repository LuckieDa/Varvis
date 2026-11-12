import React from 'react';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { savedSourceTaskLinks } from '../utils/acmgSourceTaskLinks';

export default function SavedSourceTaskLinks({ card, entry, run, onOpen, concise=false }) {
    const { links, notes, issues } = savedSourceTaskLinks({ card, entry, run });
    if (!links.length && (!notes.length || concise) && !issues.length) return null;
    return <Stack spacing={1} data-testid="saved-source-task-links">
        {issues.length > 0 && <Alert severity="warning">{concise?'取证任务的关联信息不完整，暂时无法打开。':'原任务标识或绑定无法完整核对，这是展示或任务定位问题，不表示患者资料不足。'}
            {!concise&&issues.map(code => <Typography key={code} variant="caption" display="block">{code}</Typography>)}
        </Alert>}
        {!concise&&notes.map(note => <Typography key={note} variant="caption" color="text.secondary">{note}</Typography>)}
        <Stack direction="row" gap={1} useFlexGap flexWrap="wrap">{links.map(link => <Button key={link.key} variant="outlined"
            data-source-task-kind={link.kind} onClick={() => onOpen(link)}>{link.label}</Button>)}</Stack>
    </Stack>;
}
