// Isolated acceptance page using the actual product component and service client.
const path = require('node:path');
const fs = require('node:fs');
const frontend = path.resolve(__dirname, '../../varvis-client-pro');
const esbuild = require(path.join(frontend, 'node_modules/@umijs/bundler-utils/compiled/esbuild'));
const [output, analysisId] = process.argv.slice(2);
if (!output || !/^[a-f0-9-]{36}$/.test(analysisId || '')) throw new Error('output-directory analysis-id required');
fs.mkdirSync(output, { recursive: true });
esbuild.buildSync({
  stdin: {
    contents: `import React from 'react'; import {createRoot} from 'react-dom/client';
      import Panel from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel';
      createRoot(document.getElementById('root')).render(React.createElement(Panel,{analysisId:${JSON.stringify(analysisId)}}));`,
    resolveDir: frontend,
    loader: 'jsx',
  },
  bundle: true, outfile: path.join(output, 'preview.js'), platform: 'browser',
  define: { 'process.env.NODE_ENV': '"production"' },
});
fs.writeFileSync(path.join(output, 'index.html'), '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BP7 同义分支 · 独立只读验收</title><body style="margin:16px;font-family:Arial,sans-serif"><div id="root"></div><script src="/preview.js"></script></body></html>');
