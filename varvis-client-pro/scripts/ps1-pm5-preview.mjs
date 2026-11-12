import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const hash = (filename) => createHash('sha256').update(fs.readFileSync(filename)).digest('hex');
const beneath = (root, relative) => {
  const absoluteRoot = path.resolve(root);
  const absolute = path.resolve(absoluteRoot, relative);
  if (!absolute.startsWith(`${absoluteRoot}${path.sep}`)) throw new Error('Path outside allowed root.');
  return absolute;
};

export function previewOptions(args) {
  const joint = args[0] === '--joint';
  const [instanceId, expectedBackendHead, expectedProfileId, suppliedBuild] = joint ? args.slice(1) : args;
  if (!instanceId || !expectedBackendHead || !expectedProfileId) {
    throw new Error('Usage: node scripts/ps1-pm5-preview.mjs [--joint] <backend-instance-id> <backend-full-head> <profile-id> [build-directory|joint-build-manifest]');
  }
  if (joint && (!suppliedBuild || !/^[0-9a-f]{40}$/.test(expectedBackendHead) ||
      expectedProfileId !== 'acmg-explicit-business-joint-v1')) {
    throw new Error('Joint preview requires an explicit manifest, full backend HEAD and joint profile.');
  }
  return { joint, instanceId, expectedBackendHead, expectedProfileId, suppliedBuild,
    frontendPort: joint ? 18228 : 8216, backendPort: joint ? 18227 : 18217 };
}

export function verifyManifest(manifest, dist, sourceRoot, joint) {
  if (joint && (manifest.mode !== 'JOINT' || manifest.frontendOrigin !== 'http://127.0.0.1:18228' ||
      manifest.distRelativePath !== 'varvis-client-pro/dist' ||
      manifest.provenance?.captureKind !== 'POST_BUILD_VERIFICATION' ||
      !/^[0-9a-f]{40}$/.test(manifest.sourceHead || '') || !manifest.branch ||
      !manifest.assets?.length || !manifest.sources?.length)) {
    throw new Error('Invalid joint frontend build manifest.');
  }
  for (const asset of manifest.assets) {
    if (hash(beneath(dist, asset.path)) !== asset.sha256) throw new Error(`Build asset changed: ${asset.path}`);
  }
  for (const source of manifest.sources) {
    if (hash(beneath(sourceRoot, source.path)) !== source.sha256) throw new Error(`Frontend source changed after build: ${source.path}`);
  }
  if (!joint && !/^feature\/ps1-pm5(?:-|$)/.test(manifest.branch)) throw new Error('Unexpected frontend branch.');
}

export function verifyBackendIdentity(identity, manifest, options) {
  if (identity.branch !== manifest.branch || identity.sourceHead !== options.expectedBackendHead ||
      identity.instanceId !== options.instanceId || identity.profileId !== options.expectedProfileId ||
      identity.port !== options.backendPort || identity.transport !== 'REAL_NETWORK_HTTP' ||
      identity.database !== 'PRIVATE_H2' || (options.joint && identity.mode !== 'JOINT')) {
    throw new Error('Backend identity mismatch. Refusing to proxy.');
  }
}

export function verifyJointSourceHistory(manifest, options, sourceRoot) {
  const git = (args) => execFileSync('git', args, { cwd: sourceRoot, stdio: 'pipe' });
  const sourcePaths = manifest.sources.map((source) => source.path);
  try {
    git(['merge-base', '--is-ancestor', manifest.sourceHead, 'HEAD']);
    git(['merge-base', '--is-ancestor', options.expectedBackendHead, 'HEAD']);
    git(['diff', '--quiet', options.expectedBackendHead, 'HEAD', '--', ...sourcePaths]);
    git(['diff', '--quiet', manifest.sourceHead, 'HEAD', '--', ...sourcePaths]);
    git(['diff', '--quiet', 'HEAD', '--', ...sourcePaths]);
  } catch {
    throw new Error('Joint frontend source history changed or the recorded source HEAD is not an ancestor.');
  }
}

async function main() {
  const options = previewOptions(process.argv.slice(2));
  const { joint, suppliedBuild, frontendPort, backendPort } = options;
  const work = path.join(repo, 'varvis-server/target/ps1-pm5-expansion/frontend');
  const build = joint ? path.dirname(fs.realpathSync(suppliedBuild)) :
    fs.realpathSync(suppliedBuild || fs.readFileSync(path.join(work, 'latest-build.txt'), 'utf8').trim());
  if (!joint && !build.startsWith(`${fs.realpathSync(work)}${path.sep}`)) throw new Error('Build must be inside this branch frontend output directory.');
  const manifestPath = joint ? suppliedBuild : path.join(build, 'build-manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const dist = fs.realpathSync(joint ? path.join(repo, 'varvis-client-pro/dist') : path.join(build, 'dist'));
  verifyManifest(manifest, dist, repo, joint);
  if (joint) verifyJointSourceHistory(manifest, options, repo);
  const verifiedAssets = new Set(manifest.assets.map((asset) => asset.path));
  const backend = `http://127.0.0.1:${backendPort}`;
  const identityResponse = await fetch(`${backend}/__varvis_ps1pm5_instance`, { signal: AbortSignal.timeout(10000) });
  if (!identityResponse.ok) throw new Error('Backend instance identity is unavailable.');
  const identity = await identityResponse.json();
  verifyBackendIdentity(identity, manifest, options);
  const summary = { frontend: { origin: manifest.frontendOrigin, buildId: manifest.buildId, sourceHead: manifest.sourceHead }, backend: identity };
  const mime = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
  const server = http.createServer((req, res) => {
    let pathname;
    try { pathname = decodeURIComponent(new URL(req.url, `http://127.0.0.1:${frontendPort}`).pathname); }
    catch { res.writeHead(400).end(); return; }
    if (pathname === (joint ? '/__varvis_joint_frontend' : '/__varvis_ps1pm5_frontend') && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(summary, null, 2));
      return;
    }
    if (pathname === '/variant-analyses' || pathname.startsWith('/variant-analyses/') || pathname.startsWith('/variantBasedMap/')) {
      const upstream = http.request(`${backend}${req.url}`, {
        method: req.method, headers: { ...req.headers, host: `127.0.0.1:${backendPort}` }, timeout: 15 * 60 * 1000,
      }, (response) => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      });
      upstream.on('timeout', () => upstream.destroy(new Error('Proxy timeout')));
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('Isolated backend unavailable.'); });
      req.on('aborted', () => upstream.destroy());
      req.pipe(upstream);
      return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    try {
      const filename = beneath(dist, pathname === '/' ? 'index.html' : `.${pathname}`);
      if (joint && !verifiedAssets.has(path.relative(dist, filename).split(path.sep).join('/'))) throw new Error('Not a verified build file');
      if (!fs.statSync(filename).isFile() || !fs.realpathSync(filename).startsWith(`${dist}${path.sep}`)) throw new Error('Not a build file');
      res.writeHead(200, { 'Content-Type': mime[path.extname(filename)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      if (req.method === 'HEAD') res.end(); else fs.createReadStream(filename).pipe(res);
    } catch { res.writeHead(404).end(); }
  });
  server.requestTimeout = 15 * 60 * 1000;
  server.on('error', (error) => { console.error(error.message); process.exitCode = 1; });
  server.listen(frontendPort, '127.0.0.1', () => {
    fs.writeFileSync(path.join(build, `preview-${Date.now()}.json`), JSON.stringify({ ...summary, processId: process.pid }, null, 2));
    console.log(JSON.stringify(summary, null, 2));
    console.log(`Open http://127.0.0.1:${frontendPort}/#/varvis/chromosome-visualization ; Ctrl+C stops only this preview.`);
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
