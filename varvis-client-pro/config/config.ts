import { defineConfig } from 'umi';
import routes from './routes';
import proxy from './proxy';

const { REACT_APP_ENV } = process.env;
const API_BASE = process.env.VARVIS_API_BASE || 'http://localhost:8086';

export default defineConfig({
    history: {
        type: 'hash',
    },
    jsMinifierOptions: { target: ['chrome80', 'es2020'] },
    hash: true,
    routes,
    npmClient: 'npm',
    title: 'Variation Visualization',
    proxy: proxy[REACT_APP_ENV || 'dev'],
    define: {
        'process.env.VARVIS_API_BASE': API_BASE,
    },
    mfsu: {
        esbuild: true,
    },
    exportStatic: {},
    ignoreMomentLocale: true,
});
