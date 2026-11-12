import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(scriptDirectory, '..');
const drawerPath = path.join(
    clientRoot,
    'src',
    'pages',
    'varvis',
    'ChromosomeVisualization',
    'components',
    'VariantAnalysisDrawer.jsx',
);
const servicePath = path.join(
    clientRoot,
    'src',
    'pages',
    'varvis',
    'ChromosomeVisualization',
    'services',
    'variantAnalysis.js',
);

const drawer = fs.readFileSync(drawerPath, 'utf8');
const service = fs.readFileSync(servicePath, 'utf8');

const forbiddenDrawerText = [
    'HPO_SUGGESTION_RULES',
    'hpoSuggestionCandidates',
    'Clinical phenotype text for HPO suggestions',
    '缺氧缺血性脑病',
    "const HPO_SUGGESTION_SOURCE = 'AI_RECOMMENDATION'",
];

for (const forbidden of forbiddenDrawerText) {
    if (drawer.includes(forbidden)) {
        throw new Error(`Misleading narrative-to-HPO path returned: ${forbidden}`);
    }
}

const requiredDrawerText = [
    "const MANUAL_HPO_SOURCE = 'MANUAL_CONFIRMED'",
    'Narrative-to-HPO suggestions are not configured.',
    'no AI or keyword inference is',
    'Legacy unvalidated local rule match',
    'hpoProvenanceForTerms(parsedHpoTerms)',
];

for (const required of requiredDrawerText) {
    if (!drawer.includes(required)) {
        throw new Error(`HPO input safety contract is missing: ${required}`);
    }
}

const requiredSubmissionFields = [
    "formData.append('hpoTerms'",
    "formData.append('excludedHpoTerms'",
    "formData.append('hpoProvenance'",
];

for (const required of requiredSubmissionFields) {
    if (!service.includes(required)) {
        throw new Error(`HPO multipart submission contract is missing: ${required}`);
    }
}

console.log(
    'PASSED HPO input integrity smoke: manual provenance retained; fake suggestions absent.',
);
