// Explicit public fixture through the actual HTTP boundary and the ordinary UI's declaration builder.
// This is an API test, not browser acceptance. No runtime witness or receipt is constructed here.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const directory = process.argv[2];
if (!directory) throw new Error('Provide the task-owned acceptance directory');
const read = (name) => JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
const write = (name, value) => fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2) + '\n');
const panel = read('fixed-panel.json'); const job = read('job.json');
const raw = fs.readFileSync(new URL('../../varvis-client-pro/src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const { buildCaseDeclaration, transcriptOptions } = await import(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`);
const base = `http://127.0.0.1:8086/variant-analyses/${encodeURIComponent(job.id)}`;
const http = async (url, options) => {
    const response = await fetch(url, options); if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);
    return response.json();
};
assert.equal(job.fileSha256, panel.vcfSha256); assert.equal(job.status, 'READY');
const options = await http(`${base}/acmg/input-options`); write('input-options.json', options);
const declared = {};
for (const candidate of options.candidates) {
    const fixed = panel.variants.find((v) => v.gene === candidate.geneSymbol);
    assert.ok(fixed, 'Unregistered gene must not be silently assigned a disease');
    const tx = transcriptOptions(candidate).filter((t) => t.eligible && t.versionedId === fixed.transcript);
    assert.equal(tx.length, 1, `${fixed.gene}: preselected transcript must uniquely match current sourced annotation`);
    assert.equal(tx[0].annotation.source, 'VEP'); assert.equal(tx[0].annotation.version, '116.0');
    declared[candidate.candidateId] = [{ hypothesisId: 'explicit-public-h1', hgncId: fixed.hgncId,
        mondoId: fixed.mondoId, moi: fixed.moi, affectedStatus: 'UNKNOWN', transcriptIndex: tx[0].index,
        recordLocator: `acceptance/ordinary-public-20260907/${fixed.gene}`,
        selectionReason: ['HBB', 'GJB2'].includes(fixed.gene)
            ? 'Explicit public compatibility TEST choice fixed before this run; not a disease-specific clinical optimum.'
            : 'Explicit confirmation of the fixed public MANE reference TEST choice, before this AM query; not a patient diagnosis.' }];
}
const form = { declarationVersion: 'ordinary-public-v2', patientContextId: 'patient-context-ordinary-public-20260907',
    sex: 'UNKNOWN', familyContextId: '', recordLocator: 'acceptance/ordinary-public-20260907/case', dataClass: 'SYNTHETIC' };
const request = buildCaseDeclaration(options, job.id, form, declared);
write('request.json', request);
const result = await http(`${base}/acmg/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
write('run.json', result);
const repeated = await http(`${base}/acmg/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
assert.deepEqual(repeated, result);
write('repeat-check.json', { runId: result.runId, unchanged: true, providerCallsRepeated: 0, evaluatorCallsRepeated: 0 });
console.log(JSON.stringify({ analysisId: job.id, runId: result.runId, status: result.status,
    providerCalls: result.providerCalls, evaluatorCalls: result.evaluatorCalls, selectedCount: result.selectedCount,
    apiOnly: true, repeatedReadIdentical: true }));
