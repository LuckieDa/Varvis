// TEST_ONLY issuance. Independent canonical mirror, no production evaluator or historical family fixture.
// Usage: Code-local node this-file <NEW output directory>. Never overwrites existing materials.
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const root = path.resolve(__dirname, '../..');
const output = process.argv[2];
if (!output || fs.existsSync(output)) throw Error('A new explicit output directory is required');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const canonical = value => {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw Error('integer-only');
  if (typeof value === 'string') return value.normalize('NFC');
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key.normalize('NFC'), canonical(value[key])]));
  return value;
};
const hash = value => sha(JSON.stringify(canonical(value)));
const copy = value => JSON.parse(JSON.stringify(value));
const DATE = '2026-09-29T00:00:00Z';
const registryPath = '/acmg/rule-registry-v2/acmg-rule-registry-pm3-in-trans-core-2026-09-29.json';
const registryBytes = fs.readFileSync(root + '/varvis-server/src/main/resources' + registryPath);
const registry = JSON.parse(registryBytes);
const authority = JSON.parse(fs.readFileSync(root + '/varvis-server/src/main/resources/acmg/' + registry.authorityCatalogId + '.json'));
const entry = authority.sources.find(s => s.authoritySourceId === 'authority-clingen-svi-pm3-v1.0');
const scientific = { sourceId: entry.authoritySourceId, version: entry.documentVersion, url: entry.sourceUrl, sha256: entry.snapshotSha256 };
function variant(contig, position, reference, alternate, tag) {
  const allele = { assembly: 'GRCh38', contig, position, reference, alternate };
  const normalization = { status: 'UNCHANGED', method: 'TEST_ONLY_native_identity', methodVersion: '1.0', referenceSequenceSha256: sha('TEST_ONLY reference ' + tag), refValidation: 'MATCH' };
  const { status, ...normalizer } = normalization;
  return { alleleId: 'allele-sha256-' + hash({ normalizedAllele: allele, normalizer }), variantType: 'SNV', inputAllele: copy(allele), normalizedAllele: copy(allele), evidenceQueryAllele: copy(allele), normalization, assemblyLineage: { mappingStatus: 'NATIVE' } };
}
function issue(spec) {
  const { tag, contig, loci, bases, hgncId, mondoId, symbol, classification, targetParent, depths, af, limit } = spec;
  const partnerParent = targetParent === 'FATHER' ? 'MOTHER' : 'FATHER';
  const variants = { target: variant(contig, loci[0], ...bases[0], tag), partner: variant(contig, loci[1], ...bases[1], tag) };
  const patientContextId = 'patient-context-test-pm3-' + tag, familyContextId = 'family-context-test-pm3-' + tag;
  const context = { schemaVersion: 'evidence-context-v3', contextId: '', canonicalizationProfile: 'varvis-json-c14n-v1', dataClass: 'SYNTHETIC', scope: { analysisDomain: 'GERMLINE', diseaseModel: 'MENDELIAN', variantScope: 'SMALL_SEQUENCE_VARIANT' }, variant: copy(variants.target), gene: { hgncId, symbol }, disease: { mondoId, label: 'TEST_ONLY fictional recessive disorder ' + tag }, moi: 'AUTOSOMAL_RECESSIVE', patientContext: { patientContextId, familyContextId, role: 'PROBAND', affectedStatus: 'AFFECTED', sex: 'UNKNOWN' }, contextCompleteness: { status: 'COMPLETE', missingFields: [] } };
  context.contextId = 'context-sha256-' + hash({ domain: 'varvis-evidence-context-v3', schemaVersion: context.schemaVersion, dataClass: context.dataClass, scope: context.scope, alleleId: variants.target.alleleId, hgncId, mondoId, moi: context.moi, patientContext: context.patientContext });
  const binding = { observationId: 'TEST_ONLY_pm3_' + tag + '_observation', occurrenceId: 'TEST_ONLY_pm3_' + tag + '_occurrence', caseId: 'TEST_ONLY_pm3_' + tag + '_case', contextId: context.contextId, targetAlleleId: variants.target.alleleId, partnerAlleleId: variants.partner.alleleId, pairId: 'pair-sha256-' + hash([variants.target.alleleId, variants.partner.alleleId].sort()), hgncId, mondoId, moi: context.moi, patientContextId, familyContextId };
  const sourceId = part => 'TEST_ONLY_pm3_' + tag + '_' + part;
  const review = part => ({ status: 'REVIEWED', reviewer: 'TEST_ONLY fictional ' + part + ' reviewer ' + tag, date: DATE, basis: 'TEST_ONLY explicit structured observations checked for this exact family and pair; not professional approval', sourceIds: [sourceId(part)] });
  const roles = ['PROBAND', 'FATHER', 'MOTHER'];
  const members = roles.map(role => ({ memberId: role === 'PROBAND' ? patientContextId : 'TEST_ONLY_pm3_' + tag + '_' + role.toLowerCase(), sampleId: 'TEST_ONLY_pm3_' + tag + '_sample_' + role, role, affectedStatus: role === 'PROBAND' ? 'AFFECTED' : 'UNKNOWN' }));
  const records = [], detections = [];
  roles.forEach((role, ri) => ['target', 'partner'].forEach((alleleRole, ai) => {
    const het = role === 'PROBAND' || role === (alleleRole === 'target' ? targetParent : partnerParent), dp = depths[ri] + ai * 2;
    const gt = het ? '0/1' : '0/0', ad = het ? `${dp / 2},${dp / 2}` : `${dp},0`;
    const { contig: chromosome, ...rest } = variants[alleleRole].normalizedAllele;
    records.push({ variantKey: { ...rest, chromosome }, role, sampleId: members[ri].sampleId, geneSymbol: symbol, recordFilter: 'PASS', sampleFilter: 'PASS', rawSampleValue: `${gt}:${dp}:60:${ad}:PASS`, observation: { role, genotype: gt, call: het ? 'HET_ALT' : 'HOM_REF', depth: dp, genotypeQuality: 60, alleleDepth: ad, phased: false } });
    detections.push({ binding: copy(binding), role, alleleRole, memberId: members[ri].memberId, sampleId: members[ri].sampleId, targetAltIndex: 1, ploidy: 2, sampleFormat: 'GT:DP:GQ:AD:FT', method: 'SEQUENCE_ASSAY', coverage: 'COVERED', capability: 'TARGET_DETECTION', quality: 'VALID', basis: 'TEST_ONLY raw call and detection report concordant; no universal DP/GQ cutoff', limitations: 'TEST_ONLY no real instrument, excludes mosaic and structural variants', sourceIds: [sourceId('facts')], review: review('facts') });
  }));
  const facts = { schemaVersion: 'case-family-observation-v2', observationId: binding.observationId, observationVersion: 'TEST_ONLY_PM3_1', occurrenceId: binding.occurrenceId, caseId: binding.caseId, familyId: familyContextId, binding: copy(binding), alleles: variants, members, records, detections, parentage: ['FATHER', 'MOTHER'].map(role => { const parent = members.find(m => m.role === role); return { binding: copy(binding), parentRole: role, parentMemberId: parent.memberId, probandMemberId: patientContextId, parentSampleId: parent.sampleId, probandSampleId: members[0].sampleId, testStatus: 'COMPLETED', conclusion: 'CONFIRMED', method: 'INDEPENDENT_RELATIONSHIP_TEST', result: 'RELATIONSHIP_SUPPORTED', limitations: 'TEST_ONLY synthetic confirmed relationship, not inferred from target/partner GT', sourceIds: [sourceId('facts')], review: review('facts') }; }), clinical: { binding: copy(binding), probandMemberId: patientContextId, diseaseApplicability: 'APPLICABLE', affectedStatus: 'AFFECTED', mosaicism: 'REVIEWED_NONMOSAIC', observations: ['TEST_ONLY affected AR phenotype ' + tag], review: review('facts') }, sources: [] };
  const phase = { schemaVersion: 'pm3-phase-v1', binding: copy(binding), conclusion: 'CONFIRMED_TRANS', method: 'TRIO_SEGREGATION', targetParent, partnerParent, recordRefs: detections.map(d => ({ role: d.role, alleleRole: d.alleleRole, sampleId: d.sampleId })), review: review('phase'), sources: [] };
  const partnerClassification = { schemaVersion: 'pm3-partner-classification-v1', binding: copy(binding), alleleId: binding.partnerAlleleId, classification, assertion: { classification, conflict: 'NONE', status: 'REVIEWED', sourceIds: [sourceId('partnerClassification')] }, independence: { conclusion: 'INDEPENDENT', targetAlleleId: binding.targetAlleleId, pairId: binding.pairId, caseId: binding.caseId, targetContribution: 'REVIEWED_EXCLUDED', pairContribution: 'REVIEWED_EXCLUDED', caseContribution: 'REVIEWED_EXCLUDED', pm3Contribution: 'REVIEWED_EXCLUDED', dependencies: [{ kind: 'INDEPENDENT_FUNCTIONAL_STUDY', identity: 'TEST_ONLY unrelated study ' + tag }], review: review('partnerClassification') }, review: review('partnerClassification'), sources: [] };
  const scope = { hgncId, mondoId, moi: context.moi, assembly: 'GRCh38', transcriptMode: 'TRANSCRIPT_NOT_REQUIRED', variantType: 'NATIVE_NONPROJECTED_SNV' };
  const rarity = role => ({ schemaVersion: 'pm3-pm2-rarity-v1', binding: copy(binding), alleleRole: role, alleleId: binding[role + 'AlleleId'], conclusion: 'SATISFIED', resourceVersion: 'TEST_ONLY population panel ' + tag, availability: 'AVAILABLE', queryOutcome: 'HIT', coverage: 'COVERED', quality: 'VALID', applicability: 'APPLICABLE', observedAf: af[role], frequencyPolicy: { policyId: 'TEST_ONLY_PM2_' + tag + '_' + role, policyVersion: '1.0', criterionId: 'ACMG_AMP_2015:PM2', scope: copy(scope), comparison: 'LESS_THAN_OR_EQUAL', maximumAf: limit, review: review(role + 'Rarity') }, review: review(role + 'Rarity'), sources: [] });
  const observation = { facts, phase, partnerClassification, targetRarity: rarity('target'), partnerRarity: rarity('partner') };
  const partPins = {};
  Object.keys(observation).forEach(part => { const document = observation[part], { sources, ...projection } = document, rawUtf8 = JSON.stringify(projection); document.sources = [{ sourceId: sourceId(part), recordId: sourceId(part) + '_record', version: 'TEST_ONLY_1', locator: 'TEST_ONLY/' + tag + '/' + part, acquiredAt: DATE, rawUtf8, rawSha256: sha(rawUtf8), canonicalSha256: hash(projection) }]; partPins[part] = hash(document); });
  observation.assessment = { schemaVersion: 'pm3-assessment-v1', assessmentId: 'TEST_ONLY_pm3_' + tag + '_assessment', assessmentVersion: '1.0', partPins: copy(partPins), review: { ...review('facts'), sourceIds: Object.keys(partPins).map(sourceId) } };
  observation.assessment.contentSha256 = hash(observation.assessment);
  const policy = { schemaVersion: 'pm3-reviewed-in-trans-policy-v1', policyId: 'TEST_ONLY_pm3_' + tag + '_policy', policyVersion: '1.0', mode: 'SINGLE_CONFIRMED_TRANS_PAIR', criterionIds: ['ACMG_AMP_2015:PM3'], scope, review: { status: 'REVIEWED_IMPLEMENTED', reviewId: 'TEST_ONLY_pm3_' + tag + '_policy_review' }, partPins, assessmentSha256: hash(observation.assessment), authoritySourceIds: [scientific.sourceId], scientificSources: [scientific] };
  const catalog = { schemaVersion: 'acmg-specification-catalog-v1', catalogId: 'TEST_ONLY_pm3_' + tag + '_catalog', catalogVersion: '1.0', searchScope: 'TEST_ONLY exact fictional AR background, reviewed complete search; not a real VCEP claim', searchedAt: DATE, review: { status: 'REVIEWED', reviewId: 'TEST_ONLY_catalog_review_' + tag }, sources: [scientific], entries: [{ entryId: 'TEST_ONLY_pm3_' + tag + '_entry', criterionId: 'ACMG_AMP_2015:PM3', scope: copy(scope), searchStatus: 'COMPLETE', review: { status: 'REVIEWED', reviewId: 'TEST_ONLY_entry_review_' + tag }, sourceIds: [scientific.sourceId], disposition: 'NO_APPLICABLE_SPECIALIZATION', baseRuleRelationship: 'TEST_ONLY complete exact-scope search permits general PM3 single-case branch', generalPrerequisites: { status: 'COMPLETE', sourceIds: [scientific.sourceId] }, policyBinding: { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: hash(policy) } }] };
  const bundle = { schemaVersion: 'pm3-in-trans-core-bundle-v1', context, observations: [observation], policy, catalog, catalogSha256: hash(catalog), registryBinding: { registryId: registry.registryId, registrySha256: sha(registryBytes), registryClasspathResource: registryPath } };
  const bytes = JSON.stringify(bundle, null, 2) + '\n';
  fs.writeFileSync(output + '/' + tag + '-bundle.json', bytes);
  fs.writeFileSync(output + '/' + tag + '-pins.json', JSON.stringify({ rawSha256: sha(bytes), canonicalSha256: hash(bundle) }, null, 2) + '\n');
  // Scientific expectation is explicit and independent; this script never evaluates PM3.
  fs.writeFileSync(output + '/' + tag + '-expected.json', JSON.stringify({ status: 'MET', points: 1, strength: 'MODERATE', targetParent, partnerParent, classification, caseId: binding.caseId, pairId: binding.pairId, targetAlleleId: binding.targetAlleleId, partnerAlleleId: binding.partnerAlleleId, contextId: context.contextId }, null, 2) + '\n');
}
fs.mkdirSync(output, { recursive: true });
issue({ tag: 'alpha', contig: '3', loci: [301037, 304991], bases: [['C', 'T'], ['G', 'A']], hgncId: 'HGNC:930031', mondoId: 'MONDO:0930031', symbol: 'TEST_ONLY_PM3_CEDAR', classification: 'PATHOGENIC', targetParent: 'FATHER', depths: [44, 52, 48], af: { target: '0.00001', partner: '0.00002' }, limit: '0.0001' });
issue({ tag: 'beta', contig: '16', loci: [1602481, 1610059], bases: [['T', 'G'], ['A', 'C']], hgncId: 'HGNC:930162', mondoId: 'MONDO:0930162', symbol: 'TEST_ONLY_PM3_WILLOW', classification: 'LIKELY_PATHOGENIC', targetParent: 'MOTHER', depths: [68, 74, 62], af: { target: '0.000004', partner: '0.000008' }, limit: '0.00002' });
fs.writeFileSync(output + '/issuance.json', JSON.stringify({ dataClass: 'TEST_ONLY', date: DATE, scriptRawSha256: sha(fs.readFileSync(__filename)), registryRawSha256: sha(registryBytes), identities: 'Independent Node mirror of existing integer/NFC identity projections; no evaluator used', origins: 'Two newly designed six-record families, opposite transmissions, distinct alleles/backgrounds/subjects/sources; not relabeled de novo fixtures', expected: 'Hand-specified one-point MODERATE for both P and LP; no real clinical approval' }, null, 2) + '\n');
