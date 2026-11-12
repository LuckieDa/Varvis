import React from 'react';
import { Drawer, Box, Stack, Typography, Chip, IconButton, Tooltip } from '@mui/material';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';

const DiseaseDetailDrawer = ({ disease, onClose }) => {
    const moi = normalizeToArray(disease?.moi);
    const onset = normalizeToArray(disease?.onset);
    const genes = Array.isArray(disease?.genes) ? disease.genes : [];

    return (
        <Drawer
            anchor="right"
            open={!!disease}
            onClose={onClose}
            ModalProps={{ keepMounted: true }}
            sx={{
                zIndex: (theme) => (theme?.zIndex?.tooltip || 1500) + 800,
                '& .MuiDrawer-paper': {
                    zIndex: (theme) => (theme?.zIndex?.tooltip || 1500) + 800,
                    width: 'min(520px, 100vw)',
                    maxWidth: '100vw',
                    overflow: 'hidden',
                },
            }}
        >
            <Box className="cv-disease-drawer-content">
                <Stack gap={2}>
                    <Stack
                        className="cv-disease-drawer-header"
                        direction="row"
                        justifyContent="space-between"
                        alignItems="flex-start"
                        gap={2}
                    >
                        <Typography variant="h6" sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                            {disease?.name || 'Disease detail'}
                            {disease?.orpha ? ` · Orpha ${disease.orpha}` : ''}
                        </Typography>
                        <Tooltip title="Close disease detail">
                            <IconButton
                                size="small"
                                aria-label="Close disease detail"
                                onClick={onClose}
                            >
                                <CloseRoundedIcon fontSize="small" />
                            </IconButton>
                        </Tooltip>
                    </Stack>
                    {disease?.definition && (
                        <Typography variant="body2" color="text.secondary">
                            {disease.definition}
                        </Typography>
                    )}
                    {moi.length > 0 && (
                        <Typography variant="body2">Inheritance: {moi.join(', ')}</Typography>
                    )}
                    {onset.length > 0 && (
                        <Typography variant="body2">Onset: {onset.join(', ')}</Typography>
                    )}
                    {disease?.locus && (
                        <Typography variant="body2">Locus: {disease.locus}</Typography>
                    )}
                    {genes.length > 0 && (
                        <Box>
                            <Typography variant="subtitle2">Associated genes</Typography>
                            <Box className="cv-pill-wrap cv-disease-gene-list">
                                {genes.map((g) => (
                                    <Chip
                                        key={g}
                                        label={g}
                                        size="small"
                                        component="a"
                                        href={`https://www.genenames.org/tools/search/#!/all?query=${encodeURIComponent(g)}`}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        clickable
                                    />
                                ))}
                            </Box>
                        </Box>
                    )}
                </Stack>
            </Box>
        </Drawer>
    );
};

function normalizeToArray(value) {
    if (!value) return [];
    if (Array.isArray(value)) return value;
    return [value];
}

export default DiseaseDetailDrawer;
