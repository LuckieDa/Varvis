// Isolated saved-result page. Product components/services are bundled unchanged.
const path = require('node:path');
const fs = require('node:fs');
const frontend = path.resolve(__dirname, '../../varvis-client-pro');
const esbuild = require(path.join(frontend, 'node_modules/@umijs/bundler-utils/compiled/esbuild'));
const [output] = process.argv.slice(2);
if (!output) throw new Error('isolated output directory required');
fs.mkdirSync(output, { recursive: true });
esbuild.build({
  stdin: {
    contents: `import React from 'react'; import {createRoot} from 'react-dom/client'; import axios from 'axios';
      import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel';
      import {fetchAcmgBusinessRun} from './src/pages/varvis/ChromosomeVisualization/services/variantAnalysis';
      async function start() {
        const cases=await (await fetch('/preview/cases')).json();
        const selected=cases.find(c=>c.name===new URLSearchParams(location.search).get('case'))||cases[0];
        axios.defaults.headers.common['X-BP7-Preview-Case']=selected.name;
        const run=await fetchAcmgBusinessRun(selected.analysisId,selected.runId);
        createRoot(document.getElementById('root')).render(<>
          <h2>BP7 独立验收 · {selected.label}</h2>
          <p>本分支代码、独立数据库中的真实保存结果。实验核验不重复运行模型。历史容错页包含明确标识的隔离测试快照。</p>
          <nav>{cases.map(c=><a key={c.name} href={'/?case='+c.name} style={{marginRight:20}}>{c.label}</a>)}</nav>
          <hr/><SavedResult key={selected.name+run.runId} run={run}/>
        </>);
      }
      start().catch(e=>{document.getElementById('root').textContent='读取失败：'+e.message;});`,
    resolveDir: frontend, loader: 'jsx',
  },
  plugins: [{ name: 'expose-existing-saved-component', setup(build) {
    build.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, args => ({
      contents: fs.readFileSync(args.path, 'utf8') + '\nexport { SavedResult };\n', loader: 'jsx', resolveDir: path.dirname(args.path),
    }));
  }}],
  bundle: true, outfile: path.join(output, 'preview.js'), platform: 'browser',
  define: { 'process.env.NODE_ENV': '"production"' },
}).then(() => {
  fs.writeFileSync(path.join(output, 'index.html'), '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BP7 实验核验 · 独立验收</title><body style="margin:20px;font-family:Arial,sans-serif"><div id="root">正在读取独立验收结果…</div><script src="/preview.js"></script></body></html>');
}).catch(error => { console.error(error); process.exitCode=1; });
