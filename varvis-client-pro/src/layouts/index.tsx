import React from 'react';
import { Outlet } from 'umi';
import { Box, CssBaseline } from '@mui/material';

const AppLayout: React.FC = () => (
    <Box
        className="varvis-app-shell"
        sx={{
            height: '100dvh',
            minWidth: 0,
            bgcolor: 'background.default',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
        }}
    >
        <CssBaseline />
        <Box
            component="a"
            href="#varvis-main-content"
            onClick={(event) => {
                // Hash history owns the URL fragment; focus without changing the route.
                event.preventDefault();
                document.getElementById('varvis-main-content')?.focus();
            }}
            sx={{
                position: 'fixed',
                top: 8,
                left: 8,
                zIndex: 9999,
                px: 2,
                py: 1,
                color: 'common.white',
                bgcolor: 'primary.dark',
                borderRadius: 1,
                transform: 'translateY(-150%)',
                transition: 'transform 160ms ease',
                '&:focus': { transform: 'translateY(0)' },
            }}
        >
            Skip to main content
        </Box>
        <Box
            component="main"
            id="varvis-main-content"
            tabIndex={-1}
            sx={{
                flexGrow: 1,
                minWidth: 0,
                minHeight: 0,
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden',
            }}
        >
            <Outlet />
        </Box>
    </Box>
);

export default AppLayout;
