import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// Read-only saved results (complete export or background projection). No API calls.
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const java = fs.readFileSync(new URL('../../varvis-server/src/main/java/org/nbscn/varvis/variantanalysis/provider/GeneralPp2Runtime.java', import.meta.url), 'utf8');
const currentVersion = java.match(/public static final String VERSION = "([^"]+)"/)[1];
function versionedProfile(profile, version) {
    const copy = structuredClone(profile);
    function visit(value) {
        if (!value || typeof value !== 'object') return;
        if (Object.hasOwn(value, 'pp2GeneralVersion')) value.pp2GeneralVersion = version;
        Object.values(value).forEach(visit);
    }
    visit(copy);
    return copy;
}
assert.ok(process.argv.length > 2, 'Supply saved result JSON files');
let backgrounds = 0, criteria = 0, mutations = 0;
for (const file of process.argv.slice(2)) {
    const bytes = fs.readFileSync(file);
    const digest = data => createHash('sha256').update(data).digest('hex');
    const original = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
    assert.equal(api.localBatchScopeIssue(original), null, 'saved execution scope');
    for (const candidate of original.candidates) {
        for (const attempt of candidate.attempts || []) {
            if (!attempt.localBatch) continue; // Other background slots in a lazy projection.
            const check = (value, profile = original.executionProfile) => api.localBatchAttemptIssue(
                candidate, value, original.enabledCriteria, profile);
            assert.equal(check(attempt), null, `${file}: ${attempt.hypothesisId}`);
            backgrounds++;
            criteria += attempt.localBatch.criteria.length;
            const pp2 = attempt.localBatch.criteria.find(row => row.criterionId === 'ACMG_AMP_2015:PP2');
            if (!pp2?.details?.pp2Facts) continue;
            const mutate = edit => {
                const value = structuredClone(attempt);
                edit(value, value.localBatch.criteria.find(row => row.criterionId === pp2.criterionId));
                return value;
            };
            for (const version of new Set(['1.0.0', '1.1.0', '1.2.0', '1.42.7', currentVersion])) {
                // Same facts-v1 contract: optional transcript-preparation metadata
                // does not replace or mutate the saved scientific result.
                const value = mutate((_, row) => { row.details.pp2Facts.policyVersion = version; });
                if (version === '1.0.0') delete value.localBatch.criteria.find(row => row.criterionId === pp2.criterionId).details.pp2Facts.transcriptPreparation;
                const profile = versionedProfile(original.executionProfile, version);
                assert.equal(api.localBatchScopeIssue({ ...original, executionProfile: profile }), null, `compatible scope ${version}`);
                assert.equal(check(value, profile), null, `compatible saved PP2 ${version}`);
            }
            assert.ok(check(attempt, versionedProfile(original.executionProfile, '1.99.0')), 'facts must match the exact saved runtime');
            for (const edit of [
                (_, row) => { row.details.pp2Facts.policyVersion = '9.9.9'; },
                (_, row) => { row.details.pp2Facts.schemaVersion = 'unknown-facts'; },
                (_, row) => { row.details.pp2Facts.clinicalValidationCompleted = true; },
                (_, row) => { row.appliedStrength = row.status === 'MET' ? 'NONE' : 'STRONG'; },
                (_, row) => { row.status = 'UNKNOWN_STATUS'; },
                value => { value.localBatch.candidateId = 'another-candidate'; },
                value => { value.localBatch.contextId = 'another-context'; },
                value => { value.localBatch.criteria = value.localBatch.criteria.filter(row => row.criterionId !== pp2.criterionId); },
            ]) {
                assert.ok(check(mutate(edit)), 'incompatible or damaged result must stay rejected');
                mutations++;
            }
            const incompatiblePp2 = mutate((_, row) => { row.details.pp2Facts.schemaVersion = 'unknown-facts'; });
            for (const row of attempt.localBatch.criteria) {
                const issue = api.localBatchAttemptIssue(candidate, incompatiblePp2, original.enabledCriteria, original.executionProfile, row.criterionId);
                assert.equal(Boolean(issue), row.criterionId === pp2.criterionId, 'one bad evidence row must not mask unrelated valid rows');
            }
            const wrongIdentity = mutate(value => { value.localBatch.candidateId = 'another-candidate'; });
            for (const row of attempt.localBatch.criteria) assert.ok(api.localBatchAttemptIssue(
                candidate, wrongIdentity, original.enabledCriteria, original.executionProfile, row.criterionId), 'shared identity failure must block every row');
            assert.equal(check(attempt), null, 'validation must not mutate original result');
        }
    }
    assert.equal(digest(fs.readFileSync(file)), digest(bytes), 'saved source bytes unchanged');
}
console.log(JSON.stringify({ backgrounds, localCriteria: criteria, rejectedMutations: mutations, status: 'PASS' }));
