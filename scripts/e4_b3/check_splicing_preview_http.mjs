// Real loopback HTTP and frontend request orchestration; explicitly not browser acceptance.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const [base, output] = process.argv.slice(2);
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base || '') || !output) throw Error('loopback-base output-json required');
const raw = fs.readFileSync(new URL('../../varvis-client-pro/src/pages/varvis/ChromosomeVisualization/utils/splicingExperimentCheck.js', import.meta.url), 'utf8');
const { initialExperimentCheck, experimentPrompt } = await import(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`);
const cases = await (await fetch(`${base}/preview/cases`)).json();
const reports = [];
for (const item of cases) {
  if (item.name === 'first-use') continue; // Leave a fresh isolated snapshot store for manual first-entry acceptance.
  const events = [];
  const headers = { 'X-BP7-Preview-Case': item.name };
  const runPath = `/variant-analyses/${item.analysisId}/acmg/runs/${item.runId}`;
  const request = async (path, method = 'get') => {
    const response = await fetch(base + path, { method, headers });
    events.push({ path, method, status: response.status });
    assert.equal(response.status, 200);
    return response.json();
  };
  const calculation = await request(runPath);
  const before = JSON.stringify(calculation);
  const first = await initialExperimentCheck((method, suffix = '') => request(runPath + '/splicing-checks' + suffix, method));
  assert.ok(first.snapshot); assert.equal(first.snapshot.runId, calculation.runId);
  const entryEvents = events.splice(0);
  const second = await initialExperimentCheck((method, suffix = '') => request(runPath + '/splicing-checks' + suffix, method));
  assert.deepEqual(second.snapshot, first.snapshot);
  assert.equal(events.filter(e => e.method === 'post').length, 0, 'Re-entry must only read saved versions');
  for (const version of first.versions.available) {
    const path = runPath + '/splicing-checks/' + version.checkId;
    assert.deepEqual(await request(path), await request(path + '/export'));
  }
  for (const version of first.versions.rows.filter(v => v.availability === 'UNAVAILABLE')) {
    const response = await fetch(base + runPath + '/splicing-checks/' + version.checkId, { headers });
    assert.equal(response.status, 503);
  }
  const refreshed = await request(runPath + '/splicing-checks', 'post');
  assert.equal(refreshed.runId, calculation.runId);
  assert.equal(JSON.stringify(await request(runPath)), before);
  if (item.name === 'history') {
    assert.equal(first.versions.available.length, 2); assert.equal(first.versions.unavailableCount, 1);
    assert.equal(entryEvents.some(e => e.method === 'post'), false, 'Corrupt history must not trigger auto creation');
  }
  if (item.name === 'lmna') {
    for (const check of first.snapshot.checks) {
      const pos = check.context.variant.inputAllele.position;
      assert.equal(check.status, pos === 156138613 ? 'OBSERVATION_MISMATCH_REVIEW_REQUIRED' : 'UNVERIFIED');
      assert.equal(check.calculation.evaluationResult.status, 'MET');
    }
  }
  if (item.name === 'cftr') {
    for (const check of first.snapshot.checks) {
      const candidate = calculation.candidates.find(c => c.candidateId === check.candidateId);
      const prompt = experimentPrompt(candidate, check.calculation, first.snapshot);
      assert.equal(prompt.severity, 'info'); assert.match(prompt.reason, /已匹配实验记录/);
      assert.deepEqual(prompt.records.map(r => r.geneSymbol), ['CFTR']);
      assert.equal(check.calculation.evaluationResult.status, 'NOT_MET');
    }
  }
  reports.push({ ...item, firstEntry: entryEvents, followup: events, versions: first.versions.rows,
    calculationSha256: crypto.createHash('sha256').update(before).digest('hex'), checkId: refreshed.checkId });
}
const health = await (await fetch(base + '/preview/health')).json();
assert.equal(health.forbiddenRuntimeCalls, 0);
fs.writeFileSync(output, JSON.stringify({ status: 'HTTP_AND_FRONTEND_REQUEST_FLOW_PASSED', browserCovered: false, health, cases: reports }, null, 2), { flag: 'wx' });
console.log(`${reports.length} real saved-result HTTP cases passed; browser not covered; runtime calls 0`);
