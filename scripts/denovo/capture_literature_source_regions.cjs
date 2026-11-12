// Production asset renderer: screenshots of generated local JATS full text.
// This does not drive the user's browser or serve as browser UI acceptance.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawn } = require('node:child_process');
const { once } = require('node:events');

async function capture(browserPath, jobPath) {
  const jobs = JSON.parse(fs.readFileSync(jobPath, 'utf8')).jobs;
  const tempRoot = path.resolve(__dirname, '../../local/temp/literature-source-renderer');
  fs.mkdirSync(tempRoot, { recursive: true });
  const profile = fs.mkdtempSync(path.join(tempRoot, 'profile-'));
  const child = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--disable-background-networking', '--disable-extensions',
    '--remote-debugging-pipe', '--user-data-dir=' + profile, 'about:blank'],
  { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] });
  let nextId = 0, buffer = '', errors = '';
  const pending = new Map();
  child.stderr.on('data', chunk => { errors = (errors + chunk.toString()).slice(-4000); });
  child.on('error', error => { for (const item of pending.values()) item.reject(error); });
  child.on('exit', code => { for (const item of pending.values()) item.reject(new Error('Renderer exited ' + code + ': ' + errors)); });
  child.stdio[4].on('data', chunk => {
    buffer += chunk.toString();
    let end;
    while ((end = buffer.indexOf('\0')) !== -1) {
      const message = JSON.parse(buffer.slice(0, end)); buffer = buffer.slice(end + 1);
      const item = pending.get(message.id);
      if (!item) continue;
      clearTimeout(item.timeout); pending.delete(message.id);
      if (message.error) item.reject(new Error(JSON.stringify(message.error)));
      else item.resolve(message.result);
    }
  });
  function command(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      const id = ++nextId;
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error('Renderer timeout: ' + method)); }, 20000);
      pending.set(id, { resolve, reject, timeout });
      child.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
    });
  }
  try {
    await command('Browser.getVersion');
    const { targetId } = await command('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await command('Target.attachToTarget', { targetId, flatten: true });
    const page = (method, params) => command(method, params, sessionId);
    await page('Page.enable');
    await page('Emulation.setDeviceMetricsOverride', { width: 1184, height: 900, deviceScaleFactor: 2, mobile: false });
    let currentPath;
    for (const job of jobs) {
      if (job.htmlPath !== currentPath) {
        const url = pathToFileURL(job.htmlPath).href;
        const navigation = await page('Page.navigate', { url });
        if (navigation.errorText) throw new Error(navigation.errorText);
        const ready = await page('Runtime.evaluate', { expression: `(async () => {
          const until = Date.now() + 15000;
          while (location.href !== ${JSON.stringify(url)} || document.readyState !== 'complete') {
            if (Date.now() > until) throw new Error('Source document did not load');
            await new Promise(resolve => setTimeout(resolve, 50));
          }
          await document.fonts.ready;
          return true;
        })()`, awaitPromise: true, returnByValue: true });
        if (ready.exceptionDetails) throw new Error(JSON.stringify(ready.exceptionDetails));
        currentPath = job.htmlPath;
      }
      const result = await page('Runtime.evaluate', { expression: `(() => {
        const element = document.getElementById(${JSON.stringify(job.elementId)});
        if (!element || Array.from(element.querySelectorAll('img')).some(image => !image.complete || !image.naturalWidth)) return null;
        const box = element.getBoundingClientRect();
        return { x: Math.max(0, Math.floor(box.x + scrollX) - 12),
          y: Math.max(0, Math.floor(box.y + scrollY) - 12),
          width: Math.ceil(box.width) + 24, height: Math.ceil(box.height) + 24, scale: 1 };
      })()`, returnByValue: true });
      if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
      const clip = result.result.value;
      if (!clip || clip.width <= 24 || clip.height <= 24 || clip.height > 16000 || clip.width > 5000) {
        console.log(JSON.stringify({ path: job.outputPath, annotationKey: job.annotationKey,
          annotationSkipped: true, reason: '无法定位可截图的原文区域' }));
        continue;
      }
      if (job.annotation) {
        const prepared = await page('Runtime.evaluate', { expression: `(() => {
          const root = document.getElementById(${JSON.stringify(job.elementId)});
          if (!root) throw new Error('Source region not found for annotation');
          document.querySelectorAll('.source-focus-cell').forEach(node => node.classList.remove('source-focus-cell'));
          CSS.highlights.delete('source-reference');
          const ranges = [];
          for (const citation of ${JSON.stringify(job.annotation.citations)}) {
            const target = document.getElementById(citation.targetElementId);
            if (!target || !root.contains(target)) return { matched: false };
            if (citation.sourceKind === 'td' || citation.sourceKind === 'th') {
              if (!['TD', 'TH'].includes(target.tagName)) return { matched: false };
              target.classList.add('source-focus-cell');
              continue;
            }
            const requested = citation.quote.replace(/\\s+/g, ' ').trim();
            if (!requested) return { matched: false };
            const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
            const positions = []; let normalized = '', current;
            while ((current = walker.nextNode())) {
              for (let offset = 0; offset < current.data.length; offset++) {
                const character = current.data[offset];
                if (/\\s/.test(character)) {
                  if (!normalized || normalized.endsWith(' ')) {
                    const last = positions[positions.length - 1];
                    if (last && last.node === current) last.end = offset + 1;
                    continue;
                  }
                  normalized += ' ';
                  positions.push({ node: current, start: offset, end: offset + 1 });
                } else {
                  normalized += character;
                  positions.push({ node: current, start: offset, end: offset + 1 });
                }
              }
            }
            const index = normalized.indexOf(requested);
            if (index < 0 || normalized.indexOf(requested, index + requested.length) >= 0) return { matched: false };
            if (!CSS.highlights || typeof Highlight !== 'function') return { matched: false };
            const range = document.createRange();
            range.setStart(positions[index].node, positions[index].start);
            const last = positions[index + requested.length - 1];
            range.setEnd(last.node, last.end);
            ranges.push(range);
          }
          if (ranges.length) CSS.highlights.set('source-reference', new Highlight(...ranges));
          return { matched: true };
        })()`, returnByValue: true });
        if (prepared.exceptionDetails) throw new Error('Could not prepare the original-text annotation: ' + JSON.stringify(prepared.exceptionDetails));
        if (!prepared.result.value.matched) {
          console.log(JSON.stringify({ path: job.outputPath, annotationKey: job.annotationKey,
            annotationSkipped: true, reason: '引用文字在目标原文区域中没有唯一匹配' }));
          continue;
        }
      }
      const shot = await page('Page.captureScreenshot', { format: 'png', clip, captureBeyondViewport: true, fromSurface: true });
      const raw = Buffer.from(shot.data, 'base64');
      if (!raw.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) throw new Error('Invalid screenshot');
      fs.writeFileSync(job.outputPath, raw);
      console.log(JSON.stringify({ path: job.outputPath, width: clip.width, height: clip.height,
        ...(job.annotationKey ? { annotationKey: job.annotationKey } : {}) }));
    }
  } finally {
    try { await command('Browser.close'); } catch (_) { child.kill(); }
    if (child.exitCode === null) {
      await Promise.race([once(child, 'exit'), new Promise(resolve => setTimeout(resolve, 3000))]);
    }
    if (child.exitCode === null) child.kill();
    for (const item of pending.values()) clearTimeout(item.timeout);
    // Only this invocation's known temporary profile is eligible for cleanup.
    if (path.dirname(profile) === tempRoot && path.basename(profile).startsWith('profile-')) {
      fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 150 });
    }
  }
}

if (require.main === module) capture(process.argv[2], process.argv[3]).catch(error => { console.error(error.message); process.exitCode = 1; });
