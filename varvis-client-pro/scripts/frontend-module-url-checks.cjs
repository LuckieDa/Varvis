const assert = require('node:assert/strict');
const fs = require('node:fs');
const frontendModuleUrl = require('./frontend-module-url.cjs');

const prefix = 'data:text/javascript;base64,';
const encode = source => prefix + Buffer.from(source).toString('base64');
const decode = url => Buffer.from(url.slice(prefix.length), 'base64').toString('utf8');

async function main() {
    const legacy = encode('export const preserved = 1;');
    assert.equal(frontendModuleUrl(legacy), legacy);
    assert.equal(frontendModuleUrl('file:///unknown.js'), 'file:///unknown.js');
    for (const name of ['unknown', 'acmgPp1FormalExtra', '../acmgPp1Formal']) {
        const unknown = encode(`export * from './${name}.js';`);
        assert.equal(frontendModuleUrl(unknown), unknown, 'Unknown references stay unresolved');
    }
    let reads = 0;
    const read = fs.readFileSync;
    fs.readFileSync = (file, ...args) => {
        if (/[\\/](acmgPp1Formal|acmgSequencePopulation|acmgDeNovoMethods|acmgPvs1Splice)\.js$/.test(String(file))) reads++;
        return read(file, ...args);
    };
    try {
        for (const extension of ['.js', '']) {
            const source = `export * as formal from './acmgPp1Formal${extension}'; export * as sequence from "./acmgSequencePopulation${extension}"; export * as deNovo from './acmgDeNovoMethods${extension}'; export * as pvs1 from './acmgPvs1Splice${extension}';`;
            const input = encode(source), result = frontendModuleUrl(input), modules = await import(result);
            assert.notEqual(result, input);
            assert.equal(frontendModuleUrl(input), result);
            assert.equal(decode(input), source, 'Original source remains unchanged');
            assert.equal(typeof modules.formal.validatePp1FormalCriterion, 'function');
            assert.ok(Object.keys(modules.sequence).length > 0);
            assert.equal(typeof modules.deNovo.validateDeNovoMethodsPair, 'function');
            assert.equal(typeof modules.pvs1.validatePvs1SpliceCriterion, 'function');
        }
        assert.equal(reads, 4, 'Each standalone helper is read once');
        const mixed = encode("export * from './acmgPp1Formal.js'; export * from './unknown.js';");
        assert.ok(decode(frontendModuleUrl(mixed)).includes("from './unknown.js'"));
    } finally {
        fs.readFileSync = read;
    }
    console.log('PASS frontend module loader: legacy and unknown URLs unchanged; all standalone helpers, quote/extension variants, cache and immutable input.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
