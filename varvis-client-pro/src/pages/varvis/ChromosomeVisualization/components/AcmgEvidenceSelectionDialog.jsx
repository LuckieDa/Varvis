import React, { useState } from 'react';
import { Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';
import { EVIDENCE_GROUPS, criterionId, orderedSelection, toggleGroup } from '../utils/acmgEvidenceSelection';

/** A new analysis scope; opening or changing this dialog never starts a request. */
export default function AcmgEvidenceSelectionDialog({ open, available = [], variantCount, backgroundCount, onClose, onStart }) {
    const [selected, setSelected] = useState(available);
    const chosen = orderedSelection(selected, available);
    const toggle = ids => setSelected(previous => toggleGroup(previous, ids, available));
    return <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth
        sx={{ zIndex: theme => theme.zIndex.modal + 100 }}
        PaperProps={{ sx: { borderRadius: 3, border: '1px solid #cfddf5', maxHeight: '90vh' } }}
        aria-labelledby="acmg-selection-title">
        <DialogTitle id="acmg-selection-title" sx={{ p: 3, background: 'linear-gradient(120deg,#eef4ff,#f8fcff)', borderBottom: '1px solid #dce6f6' }}>
            <Typography component="div" variant="h5" fontWeight={750}>选择本次分析证据</Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                <Chip size="small" label={`${variantCount} 个不同变异`} />
                <Chip size="small" label={`${backgroundCount} 个明确背景`} />
                <Chip size="small" color="primary" variant="outlined" label={`已选 ${chosen.length} / ${available.length} 条`} />
            </Stack>
        </DialogTitle>
        <DialogContent sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ py: 1 }}>
                <Stack direction="row"><Button onClick={() => setSelected(available)}>全选</Button><Button onClick={() => setSelected([])}>清空</Button></Stack>
            </Stack>
            {EVIDENCE_GROUPS.map(group => {
                const items = group.items.filter(([code]) => available.includes(criterionId(code)));
                const ids = items.map(([code]) => criterionId(code));
                if (!ids.length) return null;
                const count = ids.filter(id => chosen.includes(id)).length;
                return <Box key={group.title} sx={{ mb: 2.5 }}>
                    <Stack component="label" direction="row" alignItems="center" sx={{ cursor: 'pointer', mb: 1 }}>
                        <Checkbox size="small" checked={count === ids.length} indeterminate={count > 0 && count < ids.length}
                            onChange={() => toggle(ids)} inputProps={{ 'aria-label': `选择${group.title}` }} />
                        <Typography fontWeight={700}>{group.title}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>{count} / {ids.length}</Typography>
                    </Stack>
                    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3,minmax(0,1fr))' }, gap: 1 }}>
                        {items.map(([code, label]) => {
                            const id = criterionId(code), checked = chosen.includes(id);
                            return <Box key={id} component="label" sx={{ display: 'flex', alignItems: 'center', cursor: 'pointer', minHeight: 72,
                                p: 1, border: '1px solid', borderColor: checked ? '#86a8e5' : '#dce4f0', borderRadius: 2,
                                bgcolor: checked ? '#edf4ff' : '#fff', transition: 'background-color .15s', '&:hover': { bgcolor: '#e7f0ff' } }}>
                                <Checkbox checked={checked} onChange={() => toggle([id])} size="small" inputProps={{ 'aria-label': `${code} ${label}` }} />
                                <Box><Typography fontWeight={750} color={checked ? 'primary.main' : 'text.primary'}>{code}</Typography>
                                    <Typography variant="caption" color="text.secondary">{label}</Typography></Box>
                            </Box>;
                        })}
                    </Box>
                </Box>;
            })}
            {!chosen.length && <Typography variant="body2" color="error">请至少选择一条证据。</Typography>}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2, borderTop: '1px solid #dce6f6', bgcolor: '#f7faff' }}>
            <Button onClick={onClose}>返回修改</Button>
            <Button variant="contained" disabled={!chosen.length} onClick={() => onStart(chosen)}>开始分析（{chosen.length} 条）</Button>
        </DialogActions>
    </Dialog>;
}
