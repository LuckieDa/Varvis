import assert from 'node:assert/strict';
import fs from 'node:fs';
import frontendModuleUrl from './frontend-module-url.cjs';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const sha = 'a'.repeat(64), dosageUrl = 'https://search.clinicalgenome.org/kb/gene-dosage/download';
const validityUrl = 'https://search.clinicalgenome.org/kb/gene-validity/download';
const diseaseUrl = 'https://ftp.clinicalgenome.org/ClinGen_gene_curation_list_GRCh38.tsv';
const basis = 'CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_VALIDITY';
const associationBasis = 'CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_G2P_ASSOCIATION';
const reason = 'PVS1_START_LOSS_CLINGEN_DISEASE_HAPLOINSUFFICIENCY';
// Fictional joined records exercise the display contract, not scientific adoption.
function mechanism() {
    const t = { 'Gene Symbol': 'GENE1', 'Gene ID': '100', cytoBand: '1p1', 'Genomic Location': 'chr1:100-1000',
        'Haploinsufficiency Score': '3', 'Haploinsufficiency Description': 'Sufficient evidence for dosage pathogenicity',
        'Triplosensitivity Score': '0', 'Triplosensitivity Description': 'No evidence available',
        'Date Last Evaluated': '2025-01-01', 'Haploinsufficiency Disease ID': 'MONDO:0000001', 'Triplosensitivity Disease ID': '' };
    for (let i = 1; i <= 6; i++) {
        t[`Haploinsufficiency PMID${i}`] = i === 1 ? '12345' : '';
        t[`Triplosensitivity PMID${i}`] = '';
    }
    const row = (url, record) => ({ sourceUrl: url, sha256: sha, sourceRow: 100, capturedDate: '2026-11-03', fileCreatedDate: '2026-11-02', sourceRecord: record });
    const binding = { sourceDataClass: 'PUBLIC_REFERENCE', geneSymbol: 'GENE1', hgncId: 'HGNC:100', mondoId: 'MONDO:0000001', moi: 'AUTOSOMAL_DOMINANT', sources: [
        row(dosageUrl, { 'GENE SYMBOL': 'GENE1', 'HGNC ID': 'HGNC:100', HAPLOINSUFFICIENCY: 'Sufficient Evidence for Haploinsufficiency',
            TRIPLOSENSITIVITY: 'No Evidence for Triplosensitivity', 'ONLINE REPORT': 'https://search.clinicalgenome.org/kb/gene-dosage/HGNC:100', DATE: '2025-01-01T12:00:00+00:00' }),
        row(validityUrl, { 'GENE SYMBOL': 'GENE1', 'GENE ID (HGNC)': 'HGNC:100', 'DISEASE LABEL': 'Specific disease', 'DISEASE ID (MONDO)': 'MONDO:0000001',
            MOI: 'AD', SOP: 'SOP8', CLASSIFICATION: 'Definitive', 'ONLINE REPORT': 'https://search.clinicalgenome.org/kb/gene-validity/CGGV:assertion_12345678-abcd-ef01-abcd-123456789012',
            'CLASSIFICATION DATE': '2025-01-02T12:00:00.000Z', GCEP: 'Example GCEP' }), row(diseaseUrl, t)
    ] };
    const original = { schemaVersion: 'pvs1-start-loss-mechanism-v1', methodVersion: '1.1.0', contextSha256: sha, runtimeEligible: false, status: 'UNRESOLVED',
        reasonCode: 'PVS1_START_LOSS_MECHANISM_RECORDS_UNRESOLVED', findings: [{ recordId: 'G2P_SYNTHETIC', status: 'UNRESOLVED',
            reasonCode: 'PVS1_START_LOSS_MECHANISM_INFERRED_ONLY', sourceSupport: 'inferred', sourceConfidence: 'definitive' }] };
    return { ...original, primaryExperimentsReevaluated: false, transcriptRelevanceEstablished: false, basis, status: 'CURATED_MECHANISM_SUPPORTED', reasonCode: reason,
        binding: structuredClone(binding), g2pAssessment: original, clingenAssessment: {
            schemaVersion: 'pvs1-start-loss-clingen-mechanism-v1', methodVersion: '1.0.0', contextSha256: sha, basis, status: 'CURATED_MECHANISM_SUPPORTED', reasonCode: reason,
            runtimeEligible: false, primaryExperimentsReevaluated: false, transcriptRelevanceEstablished: false, hiScore: 3, validityClassification: 'Definitive',
            diseaseLabel: 'Specific disease', evaluationDate: '2025-01-01', geneId: '100', publications: ['12345'], binding
        } };
}
function criterion(m) {
    const reasonCode = 'PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE';
    return { criterionId: 'ACMG_AMP_2015:PVS1', status: 'MET', appliedStrength: 'SUPPORTING', reasonCode, details: { pvs1Facts: {
        schemaVersion: 'pvs1-selected-facts-v1', methodVersion: '1.5.0', scope: 'AUTOMATIC_GENERIC_START_LOSS', clinicalValidationCompleted: false,
        automaticAssessment: { schemaVersion: 'pvs1-start-loss-automatic-facts-v1', methodVersion: '1.0.0', contextSha256: sha, verifiedModelSha256: 'b'.repeat(64),
            rule: 'CLINGEN_SVI_START_LOSS_2018_ASSESSED_SOURCE_SET', ruleSource: 'https://pubmed.ncbi.nlm.nih.gov/30192042/',
            doubleCountingConstraint: 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE', decisionOutcome: 'MET', decisionStrength: 'SUPPORTING', reasonCode,
            sequence: { status: 'AVAILABLE', selectedTranscript: 'NM_000001.1', strand: 1, cdsPosition: 1, referenceCodon: 'ATG', alternateCodon: 'GTG', candidateRestartCdsPosition: 4 },
            preparation: { schemaVersion: 'pvs1-start-loss-automatic-preparation-v1', methodVersion: '1.2.0', status: 'GENERIC_FACTS_QUALIFIED', contextSha256: sha,
                runtimeEligible: false, scientificEvaluationPerformed: false, issues: [], mechanismAssessment: m,
                expertAssertions: { status: 'NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT', entries: [] },
                transcriptReview: { status: 'NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET', complete: true, sourceEnumerationComplete: true,
                    selectedTranscriptPresent: true, qualificationBasis: 'COMPLETE_STRUCTURAL_SOURCE_SET_NO_DIFFERENT_START', unresolvedCount: 0 },
                upstreamReview: { status: 'NO_QUALIFYING_REFERENCE_WITHIN_SCOPE', complete: true, negativeCoverageGranted: true, coverage: { complete: true }, reasonCode, records: [] }
            } }
    } } };
}
function associationMechanism() {
    const m = mechanism(), c = m.clingenAssessment;
    m.basis = associationBasis; m.methodVersion = '1.2.0'; c.basis = associationBasis; c.methodVersion = '1.1.0';
    c.associationSource = 'G2P'; c.validityClassification = 'NOT_USED_G2P_ASSOCIATION_ANCHOR';
    for (const binding of [m.binding, c.binding]) binding.sources = binding.sources.filter(s => s.sourceUrl !== validityUrl);
    const binding = { sourceDataClass: 'PUBLIC_REFERENCE', sourceCandidatesRawSha256: sha, adoptionRawSha256: 'b'.repeat(64),
        source: { url: 'https://www.ebi.ac.uk/gene2phenotype/api/panel/all/download/', version: 'TEST_ONLY', sha256: 'c'.repeat(64) } };
    const record = { recordId: 'G2P00001:1', sourceRow: 1, sourceRecordSha256: sha, status: 'UNREVIEWED', runtimeEligible: false,
        sourceRecord: { 'g2p id': 'G2P00001', 'hgnc id': '100', 'gene symbol': 'GENE1', 'disease MONDO': 'MONDO:0000001', 'disease name': 'Specific disease',
            'allelic requirement': 'monoallelic_autosomal', confidence: 'definitive', comments: '', review: '', 'cross cutting modifier': '',
            'date of last review': '2025-01-01 12:00:00+00:00', 'molecular mechanism support': 'inferred' } };
    c.associationAnchor = { schemaVersion: 'pvs1-start-loss-association-v1', methodVersion: '1.0.0', status: 'CURATED_ASSOCIATION_SUPPORTED',
        basis: 'PINNED_G2P_EXACT_DISEASE_ASSOCIATION', reasonCode: 'PVS1_START_LOSS_PINNED_G2P_ASSOCIATION', contextSha256: sha,
        runtimeEligible: false, mechanismEstablished: false, transcriptRelevanceEstablished: false, binding, records: [record], sourceRecordSetSha256: sha };
    m.g2pAssessment.binding = structuredClone(binding);
    m.g2pAssessment.findings[0].recordId = record.recordId; m.g2pAssessment.findings[0].sourceRecordSha256 = sha;
    c.clinGenValidityCheck = { sourceUrl: validityUrl, sha256: sha, capturedDate: '2026-11-03', fileCreatedDate: '2026-11-02',
        status: 'NO_EXACT_DISEASE_ASSOCIATION', sourceEnumerationComplete: true, records: [] };
    return m;
}
const original = mechanism(), before = JSON.stringify(original);
assert.equal(h.pvs1StartLossMechanismValid(original, sha), true);
assert.equal(JSON.stringify(original), before, 'validation must not mutate source or original G2P result');
const displayed = h.pvs1SelectedDetails(criterion(original));
assert.ok(displayed.some(([label, value]) => label === 'ClinGen疾病机制依据' && value.includes('MONDO:0000001')));
assert.ok(displayed.some(([label, value]) => label === 'ClinGen机制原件出处' && value.includes(diseaseUrl) && value.includes(sha)));
assert.ok(displayed.some(([label, value]) => label === 'G2P原始记录保留' && value.includes('没有将inferred改写为evidence')));
assert.ok(displayed.some(([label, value]) => label === '采用强度' && value === 'SUPPORTING'));
const joined = associationMechanism(), joinedBefore = JSON.stringify(joined);
assert.equal(h.pvs1StartLossMechanismValid(joined, sha), true);
const joinedDetails = h.pvs1SelectedDetails(criterion(joined));
assert.ok(joinedDetails.some(([label, value]) => label === 'G2P疾病关联出处' && value.includes('G2P00001:1')));
assert.ok(joinedDetails.some(([label, value]) => label === 'ClinGen关联资料核查' && value.includes('没有同一疾病记录')));
assert.equal(joined.clingenAssessment.associationAnchor.records[0].sourceRecord['molecular mechanism support'], 'inferred');
assert.equal(JSON.stringify(joined), joinedBefore);
const weakerClinGen = associationMechanism(), weakScan = weakerClinGen.clingenAssessment.clinGenValidityCheck;
const weakRecord = structuredClone(original.clingenAssessment.binding.sources.find(s => s.sourceUrl === validityUrl));
weakRecord.sourceRecord.CLASSIFICATION = 'Moderate';
const otherModeRecord = structuredClone(weakRecord); otherModeRecord.sourceRow++;
otherModeRecord.sourceRecord.MOI = 'AR'; otherModeRecord.sourceRecord.CLASSIFICATION = 'Refuted';
weakScan.status = 'INSUFFICIENT_ASSOCIATION_STRENGTH'; weakScan.records = [weakRecord, otherModeRecord];
assert.equal(h.pvs1StartLossMechanismValid(weakerClinGen, sha), true, 'distinct inheritance is retained; same-AD strong G2P association supplies its own anchor');
const native = criterion(joined), facts = native.details.pvs1Facts, a = facts.automaticAssessment, t = a.preparation.transcriptReview;
facts.methodVersion = '1.6.0'; a.methodVersion = '1.1.0';
const evidence = { schemaVersion: 'pvs1-start-loss-source-qualified-transcripts-v1', contextSha256: sha,
    status: 'NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET', complete: true,
    sourceBindings: [{ role: 'SYNTHETIC_TEST_SOURCE', sourceSha256: sha, sourceLocator: '/test', url: 'https://example.invalid/test' }] };
a.sourceQualifiedTranscriptAssessment = { method: 'INTERNAL_SOURCE_REPLAY', contextSha256: sha, functionalAlternativeFound: false, completeNegative: true, evidence };
t.sourceQualifiedAssessment = structuredClone(evidence); t.qualificationBasis = 'SOURCE_QUALIFIED_FUNCTIONAL_TRANSCRIPT_REVIEW';
assert.ok(h.pvs1SelectedDetails(native).length > 0, 'same typed mechanism is recognized by method1.6 source-qualified results');
let rejected = 0;
const bothRows = (m, url, mutate) => {
    for (const binding of [m.binding, m.clingenAssessment.binding]) mutate(binding.sources.find(s => s.sourceUrl === url));
};
for (const mutate of [
    m => { m.methodVersion = '2.0.0'; }, m => { m.methodVersion = '1.invalid'; },
    m => { m.contextSha256 = 'b'.repeat(64); }, m => { m.clingenAssessment.contextSha256 = 'b'.repeat(64); },
    m => { m.clingenAssessment.hiScore = 2; }, m => { m.clingenAssessment.validityClassification = 'Moderate'; },
    m => { m.clingenAssessment.binding.moi = 'AUTOSOMAL_RECESSIVE'; },
    m => bothRows(m, diseaseUrl, s => { s.sourceRecord['Haploinsufficiency Disease ID'] = 'MONDO:0000002'; }),
    m => bothRows(m, validityUrl, s => { s.sourceRecord.MOI = 'AR'; }),
    m => bothRows(m, dosageUrl, s => { s.sourceRecord['HGNC ID'] = 'HGNC:999'; }),
    m => bothRows(m, diseaseUrl, s => { s.sourceRecord['Haploinsufficiency Score'] = '2'; }),
    m => bothRows(m, validityUrl, s => { s.sourceRecord.CLASSIFICATION = 'Limited'; }),
    m => bothRows(m, dosageUrl, s => { s.sourceRecord['ONLINE REPORT'] = 'https://example.com/HGNC:100'; }),
    m => bothRows(m, dosageUrl, s => { s.sha256 = 'invalid'; }),
    m => bothRows(m, dosageUrl, s => { s.sourceUrl = 'constructor'; s.sourceRecord = { invalid: 'row' }; }),
    m => bothRows(m, dosageUrl, s => { s.sourceRow = 10001; }),
    m => bothRows(m, diseaseUrl, s => { s.fileCreatedDate = '2024-12-31'; }),
    m => bothRows(m, dosageUrl, s => { s.capturedDate = '2026-02-30'; }),
    m => bothRows(m, dosageUrl, s => { s.sourceRecord.DATE = '2025-01-01'; }),
    m => bothRows(m, diseaseUrl, s => { delete s.sourceRecord['Gene ID']; s.sourceRecord.geneId = '100'; }),
    m => { m.binding.sources.pop(); m.clingenAssessment.binding.sources.pop(); },
    m => { m.binding.sources[0] = structuredClone(m.binding.sources[1]); m.clingenAssessment.binding.sources[0] = structuredClone(m.clingenAssessment.binding.sources[1]); },
    m => { m.binding.sources[0].sha256 = 'b'.repeat(64); },
    m => { m.clingenAssessment.publications = []; }, m => { m.runtimeEligible = true; },
    m => { m.g2pAssessment.status = 'CONFLICT'; },
    m => { m.g2pAssessment.findings[0].reasonCode = 'PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED'; }
]) {
    const bad = mechanism(); mutate(bad);
    assert.equal(h.pvs1StartLossMechanismValid(bad, sha), false, `negative contract ${rejected}`);
    assert.deepEqual(h.pvs1SelectedDetails(criterion(bad)), [], `invalid mechanism cannot authorize selected PVS1 ${rejected}`); rejected++;
}
for (const mutate of [
    m => { delete m.clingenAssessment.associationAnchor; },
    m => { m.clingenAssessment.associationAnchor.contextSha256 = 'b'.repeat(64); },
    m => { m.clingenAssessment.associationAnchor.mechanismEstablished = true; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecord.confidence = 'limited'; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecord.comments = 'Only a restricted subset'; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecord['allelic requirement'] = 'biallelic_autosomal'; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecord['disease MONDO'] = 'MONDO:0000002'; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecord['hgnc id'] = '999'; },
    m => { m.clingenAssessment.associationAnchor.records[0].sourceRecordSha256 = 'b'.repeat(64); },
    m => { m.clingenAssessment.associationAnchor.binding.source.url = 'https://example.invalid/G2P'; },
    m => { m.clingenAssessment.associationAnchor.binding.sourceCandidatesRawSha256 = 'b'.repeat(64); },
    m => { m.clingenAssessment.clinGenValidityCheck.sourceEnumerationComplete = false; },
    m => { delete m.clingenAssessment.clinGenValidityCheck; },
    m => { m.clingenAssessment.clinGenValidityCheck.sha256 = 'invalid'; },
    m => {
        const row = structuredClone(original.clingenAssessment.binding.sources.find(s => s.sourceUrl === validityUrl));
        row.sourceRecord.CLASSIFICATION = 'Refuted'; m.clingenAssessment.clinGenValidityCheck.status = 'INSUFFICIENT_ASSOCIATION_STRENGTH';
        m.clingenAssessment.clinGenValidityCheck.records = [row];
    }
]) {
    const bad = associationMechanism(); mutate(bad);
    assert.equal(h.pvs1StartLossMechanismValid(bad, sha), false, `negative association contract ${rejected}`);
    assert.deepEqual(h.pvs1SelectedDetails(criterion(bad)), []); rejected++;
}
const g2p = { schemaVersion: 'pvs1-start-loss-mechanism-v1', methodVersion: '1.0.0', status: 'CURATED_MECHANISM_SUPPORTED', contextSha256: sha, runtimeEligible: false,
    basis: 'CURATED_G2P_DISEASE_MECHANISM_AND_NULL_VARIANT_SPECTRUM', findings: [{ status: 'CURATED_MECHANISM_SUPPORTED', sourceSupport: 'evidence', sourceConfidence: 'definitive' }] };
for (const version of ['1.0.0', '1.1.0', '1.37.9']) {
    g2p.methodVersion = version;
    assert.equal(h.pvs1StartLossMechanismValid(g2p, sha), true, `compatible G2P release ${version}`);
    assert.ok(h.pvs1SelectedDetails(criterion(g2p)).length > 0, `compatible saved G2P release ${version}`);
}
g2p.findings[0].sourceSupport = 'inferred';
assert.equal(h.pvs1StartLossMechanismValid(g2p, sha), false, 'inferred G2P alone is still insufficient');
console.log(`PVS1 ClinGen mechanism display checks passed: 3 qualified routes, legacy/additive versions and method1.5/1.6, ${rejected} rejected contracts.`);
// Optional real-source artifacts are produced by the opt-in native Java tests.
// Their expected criterion strength is independent of this projection function.
if (process.argv[2]) {
    for (const name of ['NATIVE_TARGET_FOXL2_C3_SUPPORTING.json', 'NATIVE_TARGET_MECP2_FUNCTIONAL_EXCLUSION.json']) {
        const row = JSON.parse(fs.readFileSync(`${process.argv[2]}/${name}`)), before = JSON.stringify(row);
        const details = h.pvs1SelectedDetails(row); assert.ok(details.length > 0, name);
        assert.equal(JSON.stringify(row), before, 'native projection must be immutable');
        if (name.includes('FOXL2')) {
            assert.equal(row.status, 'MET'); assert.equal(row.appliedStrength, 'SUPPORTING');
            assert.ok(details.some(([key, value]) => key === 'G2P疾病关联出处' && value.includes('G2P00393:381')));
            assert.ok(details.some(([key, value]) => key === 'ClinGen疾病机制依据' && value.includes('MONDO:0007201')));
            assert.equal(row.details.pvs1Facts.automaticAssessment.preparation.mechanismAssessment.clingenAssessment.associationAnchor.records[0]
                .sourceRecord['molecular mechanism support'], 'inferred');
        } else {
            assert.equal(row.status, 'NOT_APPLICABLE'); assert.equal(row.appliedStrength, 'NONE');
            assert.ok(details.some(([key]) => key === '功能转录本原件出处'));
        }
        console.log(`Native PVS1 projection passed: ${name}; ${row.status}/${row.appliedStrength}; ${details.length} detail rows; immutable.`);
    }
}
