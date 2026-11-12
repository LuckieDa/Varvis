import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const drawerPath = path.join(
    projectRoot,
    'src/pages/varvis/ChromosomeVisualization/components/VariantAnalysisDrawer.jsx',
);
const servicePath = path.join(
    projectRoot,
    'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
);
const drawerSource = fs.readFileSync(drawerPath, 'utf8');
const serviceSource = fs.readFileSync(servicePath, 'utf8');

const candidateKey = (variant) => String(variant?.candidateId || variant?.variantId || '');
const canonicalKey = (variant) => String(variant?.canonicalVariantId || variant?.variantId || '');
const routeFor = (candidate, suffix = '') =>
    candidate?.candidateId
        ? `/candidates/${encodeURIComponent(candidate.candidateId)}${suffix}`
        : `/variants/${encodeURIComponent(canonicalKey(candidate))}${suffix}`;

const duplicateCanonicalRows = [
    {
        candidateId: 'candidate-0001',
        canonicalVariantId: 'GRCh38:1:101:A:G',
        variantId: 'GRCh38:1:101:A:G',
    },
    {
        candidateId: 'candidate-0002',
        canonicalVariantId: 'GRCh38:1:101:A:G',
        variantId: 'GRCh38:1:101:A:G',
    },
];
assert.equal(new Set(duplicateCanonicalRows.map(candidateKey)).size, 2);
assert.equal(new Set(duplicateCanonicalRows.map(canonicalKey)).size, 1);
assert.notEqual(candidateKey(duplicateCanonicalRows[0]), candidateKey(duplicateCanonicalRows[1]));
assert.equal(canonicalKey(duplicateCanonicalRows[0]), canonicalKey(duplicateCanonicalRows[1]));
assert.equal(routeFor(duplicateCanonicalRows[0]), '/candidates/candidate-0001');
assert.equal(
    routeFor(duplicateCanonicalRows[1], '/ranking-explanation'),
    '/candidates/candidate-0002/ranking-explanation',
);

const legacyRow = { variantId: 'GRCh37:2:202:C:T' };
assert.equal(candidateKey(legacyRow), legacyRow.variantId);
assert.equal(canonicalKey(legacyRow), legacyRow.variantId);
assert.equal(routeFor(legacyRow), '/variants/GRCh37%3A2%3A202%3AC%3AT');
assert.equal(
    routeFor(legacyRow, '/ranking-explanation'),
    '/variants/GRCh37%3A2%3A202%3AC%3AT/ranking-explanation',
);

const requireSource = (source, pattern, message) => {
    assert.match(source, pattern, message);
};

requireSource(
    drawerSource,
    /const candidateKey = \(variant\) => String\(variant\?\.candidateId \|\| variant\?\.variantId \|\| ''\);/,
    'Analysis Drawer must use candidateId with a legacy variantId fallback.',
);
requireSource(
    drawerSource,
    /const canonicalKey = \(variant\) =>\s*String\(variant\?\.canonicalVariantId \|\| variant\?\.variantId \|\| ''\);/,
    'Analysis Drawer must keep a separate canonical allele key.',
);
requireSource(
    drawerSource,
    /key=\{\s*rowCandidateId \|\|\s*`\$\{activeVariantPage\}-\$\{index\}`\s*\}/,
    'Candidate table React keys must use the analysis-local candidate key.',
);
requireSource(
    drawerSource,
    /selectedCandidateId === rowCandidateId/,
    'Candidate table selection must use the analysis-local candidate key.',
);
requireSource(
    drawerSource,
    /rowCandidateId === topLoadedCandidateId/,
    'Candidate table top-row highlighting must use the analysis-local candidate key.',
);
requireSource(
    drawerSource,
    /rankingExplanationCacheKey\(analysisId, candidateId\)/,
    'Ranking explanation cache entries must be candidate-scoped.',
);
requireSource(
    drawerSource,
    /fetchVariantAnalysisCandidateRankingExplanation\(\s*analysisId,\s*selectedVariant,/,
    'Ranking explanations must select the candidate-aware service route.',
);
requireSource(
    drawerSource,
    /schemaVersion === RANKING_EXPLANATION_SCHEMA_V2[\s\S]*payload\?\.candidateId[\s\S]*payload\?\.canonicalVariantId/,
    'V2 ranking explanations must validate both candidate and canonical identities.',
);
requireSource(
    drawerSource,
    /schemaVersion === RANKING_EXPLANATION_SCHEMA_V1[\s\S]*payload\?\.variantId/,
    'V1 ranking explanations must retain the legacy canonical identity check.',
);
requireSource(
    drawerSource,
    /const ANALYSIS_VCF_CALL_ALLOWED_KEYS = new Set\(\[[\s\S]*'candidateRelativeGenotype'[\s\S]*'phaseSetToken'[\s\S]*'invalidFields'/,
    'Analysis VCF call context must use an explicit frontend field allowlist.',
);
requireSource(
    drawerSource,
    /Object\.keys\(value\)\.some\(\(key\) => !ANALYSIS_VCF_CALL_ALLOWED_KEYS\.has\(key\)\)/,
    'Unexpected analysis VCF call fields must fail closed.',
);
requireSource(
    drawerSource,
    /schemaVersion === RANKING_EXPLANATION_SCHEMA_V2[\s\S]*sanitizeAnalysisVcfCallContext\(payload\?\.analysisVcfCallContext, expectedAssembly\)/,
    'Only candidate-aware V2 explanations may expose analysis VCF call context.',
);
requireSource(
    drawerSource,
    /const ANALYSIS_VCF_CALL_TARGET_ALLELE_CALLS = new Set\(\[[\s\S]*'HET_TARGET'[\s\S]*'PARTIAL_TARGET'[\s\S]*'UNSUPPORTED'/,
    'Analysis VCF target call values must use an explicit enum allowlist.',
);
requireSource(
    drawerSource,
    /\^\[0-9a-f\]\{24\}\$[\s\S]*value\.matchStatus === 'MATCHED'[\s\S]*inferAnalysisVcfTargetAlleleCall[\s\S]*analysisVcfPhasingIsConsistent/,
    'Analysis VCF call semantics and opaque phase tokens must fail closed.',
);
requireSource(
    drawerSource,
    /value\.matchStatus === 'NOT_FOUND'[\s\S]*matchingRecordCount !== expectedMatchCount[\s\S]*matchedDetails\.some/,
    'Unmatched analysis VCF contexts must reject contradictory matched details.',
);
requireSource(
    drawerSource,
    /expectedCanonicalVariantId\.match\(\/\^\(GRCh37\|GRCh38\)\[\|:\]\//,
    'Candidate assembly must be derived from the selected canonical identity.',
);
requireSource(
    drawerSource,
    /value\.assembly !== expectedAssembly/,
    'Analysis VCF call assembly must be bound to the selected canonical candidate.',
);
requireSource(
    drawerSource,
    /\(item\) => candidateKey\(item\) === currentCandidateId/,
    'Refresh re-selection must retain the exact candidate row.',
);
requireSource(
    drawerSource,
    /if \(!requestedCandidateId\) \{[\s\S]*identifies an allele but not a candidate row[\s\S]*return;/,
    'Allele-only visualization selection must require an explicit candidate-row choice.',
);
requireSource(
    drawerSource,
    /const searchText = requestedCandidateId;/,
    'Candidate-aware visualization search must use the globally unique candidate identity.',
);
requireSource(
    drawerSource,
    /variantExactKey\(variant\) \|\| canonicalKey\(variant\)/,
    'Visualization navigation must remain anchored to the canonical allele.',
);
requireSource(
    drawerSource,
    /variant\?\.exomiserCandidateFeatures\?\.geneSymbol \|\| variant\?\.candidateIdentity\?\.geneSymbol/,
    'Gene presentation must use structured Exomiser or candidate identity when transcript annotation is absent.',
);
requireSource(
    drawerSource,
    /data-testid="exomiser-candidate-context"[\s\S]*Native Exomiser fields; shown for provenance[\s\S]*not rescored/,
    'Structured Exomiser context must be visibly separated from ranking contributions.',
);
requireSource(
    drawerSource,
    /native gene\/MOI group rank[\s\S]*not a global variant rank/,
    'The UI must not present the native gene/MOI group rank as a global variant rank.',
);
requireSource(
    drawerSource,
    /Missing engine fields:[\s\S]*Invalid or conflicting engine fields:/,
    'The UI must distinguish missing fields from invalid or conflicting fields.',
);
requireSource(
    drawerSource,
    /data-testid="analysis-vcf-call-context"/,
    'The UI must show the candidate-relative call from the actual analysis VCF in a separate card.',
);
requireSource(
    drawerSource,
    /Actual analysis VCF call[\s\S]*Candidate-relative[\s\S]*ranking/,
    'The analysis VCF call card must identify the source and ranking boundary.',
);
requireSource(
    drawerSource,
    /Candidate-relative GT[\s\S]*Reference \/ target AD/,
    'The analysis VCF call card must show candidate-relative genotype and allele depths.',
);
requireSource(
    drawerSource,
    /MATCHED:[\s\S]*NOT_FOUND:[\s\S]*AMBIGUOUS:/,
    'The analysis VCF call card must distinguish matched, absent and ambiguous records.',
);
requireSource(
    drawerSource,
    /Missing call fields:[\s\S]*Invalid call fields:[\s\S]*were not used for[\s\S]*ranking/,
    'The call card must distinguish missing/invalid fields and state the ranking boundary.',
);

const callSanitizerSource = drawerSource.slice(
    drawerSource.indexOf('const sanitizeAnalysisVcfCallContext'),
    drawerSource.indexOf('const sanitizeRankingContributionList'),
);
assert.ok(callSanitizerSource.length > 0, 'Analysis VCF call sanitizer source must be present.');
assert.doesNotMatch(
    callSanitizerSource,
    /sampleId|rawGenotype|originalRecord|rawPhaseSet|file(?:Name|Path)|absolutePath/i,
    'Analysis VCF call sanitization must not consume sample, raw VCF/GT/PS or path fields.',
);
requireSource(
    serviceSource,
    /export const fetchVariantAnalysisCandidate = async/,
    'Candidate detail service must be present.',
);
requireSource(
    serviceSource,
    /\/candidates\/\$\{encodeURIComponent\([\s\S]*identity\.candidateId/,
    'Candidate-aware requests must use the candidates route.',
);
requireSource(
    serviceSource,
    /if \(!identity\.candidateId\) \{\s*return fetchVariantAnalysisVariant\(analysisId, identity\.variantId/,
    'Candidate detail must retain the legacy variant route fallback.',
);
requireSource(
    serviceSource,
    /if \(!identity\.candidateId\) \{\s*return fetchVariantAnalysisRankingExplanation\(analysisId, identity\.variantId/,
    'Candidate explanation must retain the legacy variant route fallback.',
);

console.log('Candidate identity contract smoke passed.');
