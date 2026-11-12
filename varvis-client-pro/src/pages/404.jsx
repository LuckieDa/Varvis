import React from 'react';
import { Box, Button, Paper, Typography } from '@mui/material';
import TravelExploreRoundedIcon from '@mui/icons-material/TravelExploreRounded';
import { useNavigate } from 'umi';

const NotFoundPage = () => {
    const navigate = useNavigate();

    return (
        <Box
            sx={{
                minHeight: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                p: { xs: 2, sm: 4 },
            }}
        >
            <Paper
                variant="outlined"
                sx={{ width: 'min(520px, 100%)', p: { xs: 3, sm: 5 }, textAlign: 'center' }}
            >
                <TravelExploreRoundedIcon color="primary" sx={{ fontSize: 44, mb: 1.5 }} />
                <Typography variant="h3" color="text.primary">
                    404
                </Typography>
                <Typography variant="h6" sx={{ mt: 1 }}>
                    Page not found
                </Typography>
                <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
                    The requested Varvis workspace is not available.
                </Typography>
                <Button variant="contained" onClick={() => navigate('/')}>
                    Return to genome overview
                </Button>
            </Paper>
        </Box>
    );
};

export default NotFoundPage;
