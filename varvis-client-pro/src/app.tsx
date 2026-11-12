import React from 'react';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

const theme = createTheme({
    palette: {
        mode: 'light',
        primary: {
            main: '#1d5fae',
            dark: '#164a8a',
            light: '#eaf2fc',
        },
        secondary: {
            main: '#147d70',
        },
        success: {
            main: '#16815f',
        },
        warning: {
            main: '#b86a0b',
        },
        error: {
            main: '#c43d4b',
        },
        info: {
            main: '#2b74b8',
        },
        background: {
            default: '#edf2f7',
            paper: '#ffffff',
        },
        text: {
            primary: '#17263d',
            secondary: '#607089',
        },
        divider: '#dbe4ee',
    },
    shape: {
        borderRadius: 8,
    },
    typography: {
        fontFamily: [
            '-apple-system',
            'BlinkMacSystemFont',
            '"Segoe UI"',
            'Roboto',
            '"Helvetica Neue"',
            'Arial',
            'sans-serif',
        ].join(','),
        fontSize: 14,
        h3: { fontWeight: 750, letterSpacing: 0 },
        h4: { fontWeight: 750, letterSpacing: 0 },
        h5: { fontWeight: 750, letterSpacing: 0 },
        h6: { fontWeight: 700, letterSpacing: 0 },
        subtitle1: { fontWeight: 700, letterSpacing: 0 },
        subtitle2: { fontWeight: 700, letterSpacing: 0 },
        button: { fontWeight: 700, letterSpacing: 0, textTransform: 'none' },
    },
    components: {
        MuiCssBaseline: {
            styleOverrides: {
                'html, body, #root': {
                    width: '100%',
                    minWidth: 0,
                    height: '100%',
                },
                body: {
                    margin: 0,
                    overflow: 'hidden',
                    textRendering: 'optimizeLegibility',
                    WebkitFontSmoothing: 'antialiased',
                },
                '*, *::before, *::after': {
                    boxSizing: 'border-box',
                },
                '@media (prefers-reduced-motion: reduce)': {
                    '*, *::before, *::after': {
                        animationDuration: '0.01ms !important',
                        animationIterationCount: '1 !important',
                        scrollBehavior: 'auto !important',
                        transitionDuration: '0.01ms !important',
                    },
                },
            },
        },
        MuiButton: {
            styleOverrides: {
                root: {
                    minHeight: 36,
                    borderRadius: 6,
                    boxShadow: 'none',
                    letterSpacing: 0,
                    textTransform: 'none',
                },
            },
        },
        MuiIconButton: {
            styleOverrides: {
                root: {
                    borderRadius: 6,
                },
            },
        },
        MuiPaper: {
            styleOverrides: {
                rounded: {
                    borderRadius: 8,
                },
            },
        },
        MuiChip: {
            styleOverrides: {
                root: {
                    minWidth: 0,
                    height: 26,
                    borderRadius: 6,
                    fontWeight: 650,
                },
                label: {
                    minWidth: 0,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                },
            },
        },
        MuiOutlinedInput: {
            styleOverrides: {
                root: {
                    borderRadius: 6,
                    backgroundColor: '#ffffff',
                },
            },
        },
        MuiTooltip: {
            defaultProps: {
                arrow: true,
                enterDelay: 450,
            },
            styleOverrides: {
                tooltip: {
                    maxWidth: 360,
                    fontSize: 12,
                    lineHeight: 1.45,
                },
            },
        },
    },
});

export function rootContainer(container: React.ReactNode) {
    return (
        <ThemeProvider theme={theme}>
            <CssBaseline />
            {container}
        </ThemeProvider>
    );
}
