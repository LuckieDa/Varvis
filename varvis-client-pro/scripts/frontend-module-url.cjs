const fs = require('node:fs');
const path = require('node:path');

const prefix = 'data:text/javascript;base64,';
const modules = new Set(['acmgPp1Formal', 'acmgSequencePopulation', 'acmgDeNovoMethods', 'acmgPvs1Splice', 'acmgProteinLengthProof',
    'acmgPublishedConditionProof', 'acmgPp2HcmProof', 'acmgBa1Conditional', 'acmgFullRna', 'acmgPm3Conditions']);
const rewrittenUrls = new Map();
const helperUrls = new Map();

/** Resolve the standalone display helpers in existing source-only tests. */
module.exports = function frontendModuleUrl(url) {
    if (typeof url !== 'string' || !url.startsWith(prefix)) return url;
    if (rewrittenUrls.has(url)) return rewrittenUrls.get(url);
    const source = Buffer.from(url.slice(prefix.length), 'base64').toString('utf8');
    const rewritten = source.replace(/(\bfrom\s*)(['"])\.\/(\w+)(?:\.js)?\2/g,
        (original, from, quote, name) => {
            if (!modules.has(name)) return original;
            if (!helperUrls.has(name)) {
                const file = path.join(__dirname, '../src/pages/varvis/ChromosomeVisualization/utils', `${name}.js`);
                helperUrls.set(name, frontendModuleUrl(prefix + fs.readFileSync(file).toString('base64')));
            }
            return from + JSON.stringify(helperUrls.get(name));
        });
    const result = rewritten === source ? url : prefix + Buffer.from(rewritten).toString('base64');
    rewrittenUrls.set(url, result);
    return result;
};
