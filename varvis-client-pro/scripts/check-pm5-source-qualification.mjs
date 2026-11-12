import frontendModuleUrl from './frontend-module-url.cjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';

const [modulePath, savedPath, oldModulePath, oldSavedPath] = process.argv.slice(2);
const load = async (path) => import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(path).toString('base64')}`));
const current = await load(modulePath);
const run = JSON.parse(fs.readFileSync(savedPath, 'utf8'));
const original = JSON.stringify(run);
for (const candidate of run.candidates) {
    const card = current.evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0];
    const details = Object.fromEntries(card.entries[0].details);
    assert.match(details['专家资料来源与缺项'], /保留原专家分类.*自动核对.*未按新版重新分类/);
    assert.match(details['RNA 文献核验'], /自动检查.*无需逐配对人工签署.*不代表实验正常/);
    assert.match(details['剪接核验'], /目标.*参考/);
}
assert.equal(JSON.stringify(run), original);
const previous = await load(oldModulePath);
const oldRun = JSON.parse(fs.readFileSync(oldSavedPath, 'utf8'));
for (const candidate of oldRun.candidates) {
    assert.deepEqual(current.evidenceCards(candidate, ['ACMG_AMP_2015:PM5']),
        previous.evidenceCards(candidate, ['ACMG_AMP_2015:PM5']));
}
console.log(JSON.stringify({ status: 'PASS', newSavedCandidates: run.candidates.length,
    unchangedLegacyCards: oldRun.candidates.length, fullJsonUnchanged: true, browser: 'NOT_RUN' }));
