import frontendModuleUrl from './frontend-module-url.cjs';
// Regression for PM4 detail text: an evaluated result must not become an input failure.
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const { reasonHelp } = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const checks = [
    ['successful stop loss remains evaluated', () => {
        const text = reasonHelp('PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET');
        assert.match(text, /序列.*推导/);
        assert.match(text, /满足/);
        assert.doesNotMatch(text, /未评价|尚未通过核验/);
    }],
    ['missing tail is a missing-data explanation', () => {
        assert.match(reasonHelp('PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE'), /缺少.*3′/);
    }],
    ['completed search without a stop states its finite scope', () => {
        assert.match(reasonHelp('PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT'), /完整转录本.*未找到/);
    }],
    ['technical window is not a biological absence', () => {
        const text = reasonHelp('PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW');
        assert.match(text, /技术.*范围|技术.*窗口/);
        assert.match(text, /未评价/);
    }],
    ['invalid tail is not absent data', () => {
        assert.match(reasonHelp('PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_INVALID'), /序列.*不合法|序列.*无效/);
    }],
    ['unsupported stop loss names its own scope', () => {
        assert.match(reasonHelp('PM4_STOP_LOSS_SIMPLE_SNV_REQUIRED'), /单核苷酸|SNV/);
    }],
    ['in-frame positive stays positive', () => {
        assert.doesNotMatch(reasonHelp('PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET'), /未评价/);
    }],
    ['known specialization still requires clause selection', () => {
        assert.match(reasonHelp('PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION'), /专门规范/);
    }],
];
let failures = 0;
for (const [name, check] of checks) {
    try { check(); console.log(`PASS ${name}`); }
    catch (error) { failures++; console.error(`FAIL ${name}: ${error.message}`); }
}
console.log(JSON.stringify({ tests: checks.length, passed: checks.length - failures, failures }));
process.exitCode = failures ? 1 : 0;
