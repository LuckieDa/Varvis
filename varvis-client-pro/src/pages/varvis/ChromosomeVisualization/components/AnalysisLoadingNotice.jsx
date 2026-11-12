import React, { useEffect, useState } from 'react';
import { Box, LinearProgress, Typography } from '@mui/material';

// Elapsed time is observation only, never a completion percentage or an ETA.
export default function AnalysisLoadingNotice({ startedAt, title, detail, children }) {
    const [now, setNow] = useState(Date.now);
    useEffect(() => {
        setNow(Date.now());
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, [startedAt]);
    const seconds = Math.max(0, Math.floor((now - startedAt) / 1000));
    const elapsed = seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
    return (
        <Box sx={{ my: 1.5, p: 2, bgcolor: '#f4f8ff', border: '1px solid #dce5f3', borderRadius: 1.5 }} aria-busy="true">
            <Typography role="status" fontWeight={600} variant="body2">{title}</Typography>
            <LinearProgress sx={{ my: 1.25 }} aria-label={title} />
            {detail && <Typography variant="body2" color="text.secondary">{detail}</Typography>}
            {children}
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
                已等待 {elapsed}
            </Typography>
        </Box>
    );
}
