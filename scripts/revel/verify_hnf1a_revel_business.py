"""Read-only HNF1A REVEL verification using frozen schemas and independent science.

No Java evaluator, network query, clinical classification, or model inference is
performed. Source records are checked against the acquired CSV; thresholds and
codons are recomputed independently of the production implementation.
"""
import argparse
import copy
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.evidence import acmg_evidence_v2_contract as e1
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

RES = ROOT / 'varvis-server/src/main/resources/acmg'
PROFILE = RES / 'hnf1a-revel-business-profile-v1.json'
MODEL = RES / 'pvs1-hnf1a-transcript-model-v1.json'
TX = 'ENST00000257555.11'
SOURCE_ID = 'REVEL_HNF1A_2021'
GN017_JSON_SHA256 = '6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1'
# Independently checked against the official GN017 JSON and HTML captured for
# this task. The frozen validator explicitly permits new observed alias sets.
GN017_ALIASES = {('GN017', '3.1.0'): {
    (version, 'CSpec:GN017@' + version, '467903868', '_ka7YRPu---',
     'Zenodo:21421568/GN017_3.1.0.json', GN017_JSON_SHA256)
    for version in ('3.1', '3.1.0')
}}
FIELDS = ['chr', 'hg19_pos', 'grch38_pos', 'ref', 'alt', 'aaref', 'aaalt', 'REVEL', 'Ensembl_transcriptid']
CODE = dict(zip(map(''.join, itertools.product('TCAG', repeat=3)),
                'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG', strict=True))
AA = dict(zip('ACDEFGHIKLMNPQRSTVWY',
              ('Ala', 'Cys', 'Asp', 'Glu', 'Phe', 'Gly', 'His', 'Ile', 'Lys', 'Leu',
               'Met', 'Asn', 'Pro', 'Gln', 'Arg', 'Ser', 'Thr', 'Val', 'Trp', 'Tyr'), strict=True))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def equal(actual, expected, label):
    require(actual == expected, f'{label}: expected {expected!r}, observed {actual!r}')


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def confined(base, value):
    require(isinstance(value, str) and value and not Path(value).is_absolute(), 'relative resource path required')
    base = Path(base).resolve()
    result = (base / value).resolve()
    require(result != base and result.is_relative_to(base), 'resource path escapes manifest directory')
    return result


def decimal_score(value):
    require(not isinstance(value, bool), 'invalid REVEL score')
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError('invalid REVEL score') from None
    require(result.is_finite() and Decimal(0) <= result <= Decimal(1), 'invalid REVEL score')
    return result


def expected_results(score):
    if score is None:
        return {'PP3': 'NOT_EVALUATED', 'BP4': 'NOT_EVALUATED'}
    value = decimal_score(score)
    return {'PP3': 'MET' if value >= Decimal('0.70') else 'NOT_MET',
            'BP4': 'MET' if value <= Decimal('0.15') else 'NOT_MET'}


def unique_values(rows, name):
    result = {row[name]: row for row in rows}
    equal(len(result), len(rows), 'duplicate ' + name)
    return result


def derive_protein(allele, model, *, internal_only=True):
    """Map genomic allele to pinned CDS and translate one codon independently."""
    equal((model['assembly'], model['strand'], model['transcript']), ('GRCh38', 1, TX), 'model scope')
    equal((allele['assembly'], allele['contig']), ('GRCh38', '12'), 'input assembly and contig')
    reference, alternate = allele['reference'], allele['alternate']
    require(reference in 'ACGT' and alternate in 'ACGT' and len(reference) == len(alternate) == 1
            and reference != alternate, 'missense SNV allele required')
    exons = [exon for exon in model['exons'] if exon['genomicStart'] <= allele['position'] <= exon['genomicEnd']]
    equal(len(exons), 1, 'unique genomic CDS exon')
    exon = exons[0]
    cds_position = exon['cdsStart'] + allele['position'] - exon['genomicStart']
    require(1 <= cds_position <= len(model['cdsSequence']), 'outside pinned CDS')
    if internal_only:
        require(4 <= cds_position <= len(model['cdsSequence']) - 3, 'outside enabled internal CDS')
    equal(model['cdsSequence'][cds_position - 1], reference, 'CDS reference base')
    start = (cds_position - 1) // 3 * 3
    original = model['cdsSequence'][start:start + 3]
    changed = list(original)
    changed[(cds_position - 1) % 3] = alternate
    changed = ''.join(changed)
    ref_aa, alt_aa = CODE[original], CODE[changed]
    require(ref_aa != alt_aa and '*' not in (ref_aa, alt_aa), 'not an internal missense substitution')
    protein_position = start // 3 + 1
    return {'cdsPosition': cds_position, 'proteinPosition': protein_position,
            'refCodon': original, 'altCodon': changed, 'refAminoAcid': ref_aa, 'altAminoAcid': alt_aa,
            'hgvsc': f'{TX}:c.{cds_position}{reference}>{alternate}',
            'hgvsp': f"{model['protein']}:p.{AA[ref_aa]}{protein_position}{AA[alt_aa]}",
            'exon': f"{exon['ordinal']}/{len(model['exons'])}"}


def verify_profile():
    profile, model = read(PROFILE), read(MODEL)
    equal(profile['profileId'], 'HNF1A_GN017_3_1_REVEL', 'profile ID')
    equal(profile['activation']['automaticTrigger'], False, 'explicit activation')
    equal(profile['activation']['classificationEnabled'], False, 'classification disabled')
    equal(profile['context'], {'hgncId': 'HGNC:11621', 'geneSymbol': 'HNF1A', 'mondoId': 'MONDO:0015967',
                              'moi': 'AUTOSOMAL_DOMINANT', 'assembly': 'GRCh38', 'transcript': TX,
                              'refseqTranscript': 'NM_000545.8', 'variantType': 'SNV', 'consequence': 'missense_variant'}, 'bounded context')
    equal(profile['policy']['criteria'], [
        {'criterionId': 'ACMG_AMP_2015:PP3', 'comparison': 'GE', 'threshold': '0.70', 'strength': 'SUPPORTING'},
        {'criterionId': 'ACMG_AMP_2015:BP4', 'comparison': 'LE', 'threshold': '0.15', 'strength': 'SUPPORTING'}], 'independent GN017 thresholds')
    equal(profile['policy']['bp7DependencyEligible'], False, 'no BP7 dependency')
    equal(profile['policy']['evidenceMechanism'], 'PROTEIN_MISSENSE_PREDICTION', 'protein prediction mechanism')
    equal(profile['policy']['missingResourceFallback'], 'NOT_EVALUATED_NO_ALPHAMISSENSE_FALLBACK', 'missing resource policy')
    authority = profile['policy']['authority']
    equal((authority['specificationId'], authority['authorityVersion'], authority['sourceUrl']),
          ('GN017', '3.1', 'https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1'), 'authority version')
    equal(e1.canonical_sha256(model), profile['transcriptModel']['canonicalSha256'], 'pinned CDS identity')
    registry_path = confined(RES.parent, profile['registryBinding']['registryClasspathResource'].lstrip('/'))
    equal(file_sha(registry_path), profile['registryBinding']['registrySha256'], 'rule registry bytes')
    registry = read(registry_path)
    equal(registry['registryId'], profile['registryBinding']['registryId'], 'rule registry identity')
    binding = profile['overlayBinding']
    authority_path = confined(RES.parent, binding['authorityCatalogClasspathResource'].lstrip('/'))
    overlay_path = confined(RES.parent, binding['registryClasspathResource'].lstrip('/'))
    equal(file_sha(authority_path), binding['authorityCatalogSha256'], 'authority catalog bytes')
    equal(file_sha(overlay_path), binding['registrySha256'], 'CSpec overlay registry bytes')
    catalog, overlays = read(authority_path), read(overlay_path)
    schemas = e1.ContractSchemas()
    e1.validate_authority_catalog(catalog, schemas)
    _validate_schema(registry)
    e1.validate_cspec_registry(overlays, catalog, registry, schemas,
                               authority_path=authority_path, rule_registry_path=registry_path,
                               successor_alias_identities=GN017_ALIASES)
    selected = [row for row in overlays['overlays'] if row['overlayId'] == binding['overlayId']]
    equal(len(selected), 1, 'unique registered HNF1A overlay')
    equal(selected[0]['snapshotSha256'], binding['overlaySha256'], 'selected official CSpec snapshot identity')
    if 'overlayCanonicalSha256' in binding:
        equal(e1.canonical_sha256(selected[0]), binding['overlayCanonicalSha256'], 'overlay object canonical identity')
    equal(selected[0]['selector'], {'hgncId': 'HGNC:11621', 'mondoId': 'MONDO:0015967',
                                  'moi': 'AUTOSOMAL_DOMINANT',
                                  'transcript': {'accession': 'ENST00000257555', 'version': 11}}, 'overlay exact scope')
    specifications = unique_values(selected[0]['criterionSpecifications'], 'criterionId')
    equal(set(specifications), {row['criterionId'] for row in profile['policy']['criteria']}, 'overlay criterion scope')
    for rule in profile['policy']['criteria']:
        spec = specifications[rule['criterionId']]
        parameters = {row['name']: row['value'] for row in spec['parameters']}
        equal(spec['strength'], 'SUPPORTING', 'overlay strength')
        for name in ('threshold', 'comparison'):
            equal(parameters[name], rule[name], 'overlay ' + name)
        equal(parameters['tool'], 'REVEL', 'overlay tool')
    return profile, model


def java_row(row):
    return {'hg19Position': int(row['hg19_pos']),
            'grch38Position': 0 if row['grch38_pos'] in ('', '.', 'NA', 'nan', 'NaN') else int(row['grch38_pos']),
            'reference': row['ref'], 'alternate': row['alt'], 'refAa': row['aaref'], 'altAa': row['aaalt'],
            'score': row['REVEL'], 'transcripts': row['Ensembl_transcriptid'], 'sourceLine': int(row['sourceLine'])}


def verify_resource(path, model=None):
    """Hash bounded source artifacts and prove each subset row's source-line identity."""
    model = model or read(MODEL)
    path = Path(path)
    manifest = read(path)
    equal((manifest['schemaVersion'], manifest['resourceVersion'], manifest['derivationVersion']),
          ('revel-hnf1a-resource-v1', '1.3', 'hnf1a-revel-cds-map-v1'), 'resource version')
    equal(manifest['transcriptModelSha256'], e1.canonical_sha256(model), 'resource CDS canonical identity')
    if 'transcriptModelByteSha256' in manifest:
        equal(manifest['transcriptModelByteSha256'], file_sha(MODEL), 'resource CDS file bytes')
    scope, source = manifest['scope'], manifest['source']
    equal((scope['hgncId'], scope['transcript'], scope['refseqTranscript'], scope['assembly'], scope['contig']),
          ('HGNC:11621', TX, 'NM_000545.8', 'GRCh38', '12'), 'resource scope')
    equal((source['originalAssembly'], source['publishedAssembly'], source['mappingMethod']),
          ('GRCh37', 'GRCh38', 'REVEL_publisher_liftOver'), 'publisher coordinate lineage')
    equal(source['transcriptVersionProvided'], False, 'unversioned source transcript')
    equal(source['proteinPositionProvided'], False, 'absent source protein position')
    data_path, source_path = confined(path.parent, manifest['dataFile']), confined(path.parent, source['file'])
    equal(file_sha(data_path), manifest['dataSha256'], 'derived data bytes')
    equal(file_sha(source_path), source['sha256'], 'original CSV bytes')
    with data_path.open(encoding='utf-8', newline='') as stream:
        reader = csv.DictReader(stream, delimiter='\t')
        equal(reader.fieldnames, FIELDS + ['sourceLine'], 'derived fields')
        raw_rows = list(reader)
    require(raw_rows, 'resource is empty')
    rows_by_line = {int(row['sourceLine']): row for row in raw_rows}
    equal(len(rows_by_line), len(raw_rows), 'unique source line IDs')
    observed = set()
    with source_path.open(encoding='utf-8', newline='') as stream:
        reader = csv.DictReader(stream)
        equal(reader.fieldnames, FIELDS, 'source fields')
        for line, row in enumerate(reader, 2):
            if 'ENST00000257555' not in row['Ensembl_transcriptid'].split(';'):
                continue
            require(line in rows_by_line, 'selected transcript source row omitted')
            equal({key: rows_by_line[line][key] for key in FIELDS}, row, 'unaltered source row')
            observed.add(line)
    equal(observed, set(rows_by_line), 'complete source selected-transcript subset')
    records = [java_row(row) for row in raw_rows]
    for raw, row in zip(raw_rows, records, strict=True):
        equal(raw['chr'], '12', 'source contig')
        if row['grch38Position'] == 0:
            continue
        derived = derive_protein({'assembly': 'GRCh38', 'contig': '12', 'position': row['grch38Position'],
                                  'reference': row['reference'], 'alternate': row['alternate']}, model, internal_only=False)
        equal((row['refAa'], row['altAa']), (derived['refAminoAcid'], derived['altAminoAcid']), 'source codon amino acids')
        decimal_score(row['score'])
        require(row['hg19Position'] > 0 and scope['minPosition'] <= row['grch38Position'] <= scope['maxPosition'], 'published mapping scope')
    if 'selectedTranscriptValidation' in manifest:
        validation = manifest['selectedTranscriptValidation']
        validation_path = confined(path.parent, validation['file'])
        equal(file_sha(validation_path), validation['sha256'], 'source validation artifact bytes')
        equal(validation['selectedRows'], len(records), 'source validation row count')
    return {'manifest': manifest, 'identity': e1.canonical_sha256(manifest), 'records': records,
            'recordCount': len(records), 'missingMappings': any(row['grch38Position'] == 0 for row in records)}


def source_score(rows, derived):
    """Use all exact records; conflicting scores never select a favorable value."""
    if not rows:
        return None
    scores, original_positions = set(), set()
    for row in rows:
        require('ENST00000257555' in re.split('[;&]', row['transcripts']), 'source transcript mismatch')
        equal((row['refAa'], row['altAa']), (derived['refAminoAcid'], derived['altAminoAcid']), 'source protein identity')
        require(row['hg19Position'] > 0, 'published original coordinate missing')
        original_positions.add(row['hg19Position'])
        scores.add(decimal_score(row['score']))
    equal(len(original_positions), 1, 'source coordinate conflict')
    equal(len(scores), 1, 'source score conflict')
    return scores.pop()


def verify_identity(node, kind, identity, projection, schemas):
    schemas.validate(node, 'urn:varvis:schema:' + kind)
    prefix = {'contextId': 'context', 'selectionId': 'cspec-selection',
              'evidenceId': 'evidence', 'evaluationId': 'evaluation'}[identity]
    equal(node[identity], prefix + '-sha256-' + e1.canonical_sha256(projection(node)), identity)


def verify_attempt(attempt, resource, profile, model, schemas):
    before = copy.deepcopy(attempt)
    context, inputs, selection = attempt['context'], attempt['transcriptInput'], attempt['selection']
    verify_identity(context, 'evidence-context-v2', 'contextId', e1.context_identity_projection, schemas)
    equal(context['variant']['alleleId'], 'allele-sha256-' + e1.canonical_sha256(e1.allele_identity_projection(context)), 'allele ID')
    verify_identity(selection, 'cspec-selection-v1', 'selectionId', e1.cspec_selection_identity_projection, schemas)
    equal(selection['contextId'], context['contextId'], 'selection context')
    equal(inputs['profileSha256'], e1.canonical_sha256(profile), 'input profile')
    equal(inputs['policyAuthority'], profile['policy']['authority'], 'input authority')
    equal(inputs['tool'], 'REVEL', 'actual tool')
    equal(inputs['bp7DependencyEligible'], False, 'protein score cannot authorize BP7')
    equal(inputs['modelInferenceCalls'], 0, 'precomputed resource lookup')
    if not attempt.get('criterionEvaluations'):
        require(attempt.get('blockers'), 'empty evaluation requires explicit blockers')
        require(not attempt.get('atomicEvidence') and 'score' not in inputs, 'blocked preflight must not fabricate a query or score')
        require(selection['outcome'] in ('ZERO_MATCH', 'SINGLE_SELECTABLE_MATCH'), 'bounded preflight selection outcome')
        if selection['outcome'] == 'ZERO_MATCH':
            require('selectedOverlayId' not in selection and not selection['candidateOverlayIds'], 'unsupported context has no selected overlay')
        equal(attempt, before, 'Java blocked attempt preserved')
        return {'status': 'NOT_EVALUATED', 'reasonCodes': [b['reasonCode'] for b in attempt['blockers']], 'contextId': context['contextId']}
    equal(selection['outcome'], 'SINGLE_SELECTABLE_MATCH', 'specialist selection')
    overlay = profile['overlayBinding']
    for selected_name, binding_name in [('overlayRegistryId', 'registryId'), ('overlayRegistrySha256', 'registrySha256'),
                                       ('selectedOverlayId', 'overlayId'), ('selectedOverlaySha256', 'overlaySha256')]:
        equal(selection[selected_name], overlay[binding_name], 'specialist selection ' + selected_name)
    equal(selection['candidateOverlayIds'], [overlay['overlayId']], 'single exact specialist match')
    equal((context['gene']['hgncId'], context['disease']['mondoId'], context['moi']),
          ('HGNC:11621', 'MONDO:0015967', 'AUTOSOMAL_DOMINANT'), 'confirmed disease context')
    equal(f"{context['transcript']['accession']}.{context['transcript']['version']}", TX, 'confirmed transcript')
    equal(context['variant']['assemblyLineage']['mappingStatus'], 'NATIVE', 'native input lineage')
    equal(context['variant']['normalizedAllele'], context['variant']['evidenceQueryAllele'], 'no input projection')
    equal(inputs['evidenceMechanism'], 'PROTEIN_MISSENSE_PREDICTION', 'evidence mechanism')
    equal(inputs['resourceIdentitySha256'], resource['identity'], 'exact resource identity')
    equal(inputs['providerInfo']['manifest'], resource['manifest'], 'saved manifest content')
    equal(inputs['providerInfo']['queryType'], 'PRECOMPUTED_SCORE_LOOKUP', 'lookup type')
    evidence = unique_values(attempt.get('atomicEvidence', []), 'evidenceId')
    for atomic in evidence.values():
        verify_identity(atomic, 'atomic-evidence-v2', 'evidenceId', e1.evidence_identity_projection, schemas)
        equal(atomic['contextId'], context['contextId'], 'atomic context')
        equal(atomic['deduplicationKey'], 'dedup-sha256-' + e1.canonical_sha256(e1.evidence_deduplication_projection(atomic)), 'deduplication key')
        require(not atomic['source']['sourceId'].startswith('ALPHAMISSENSE_'), 'specialist path must not query AM')
    predictions = [item for item in evidence.values() if item['source']['sourceId'] == SOURCE_ID]
    evaluations = unique_values(attempt.get('criterionEvaluations', []), 'criterionId')
    for item in evaluations.values():
        verify_identity(item, 'criterion-evaluation-v2', 'evaluationId', e1.evaluation_identity_projection, schemas)
    equal(set(evaluations), {'ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'}, 'one pair of prediction rules')
    allele = context['variant']['evidenceQueryAllele']
    all_rows = [row for row in resource['records'] if (row['grch38Position'], row['reference'], row['alternate']) ==
                (allele['position'], allele['reference'], allele['alternate'])]
    score = None
    if predictions:
        equal(len(predictions), 1, 'one REVEL atomic')
        prediction = predictions[0]
        equal(inputs.get('sourceRecords', []), all_rows, 'exact returned source records')
        equal(prediction['source']['resourceSha256'], resource['identity'], 'atomic resource identity')
        equal(prediction['source']['retrievedAt'], resource['manifest']['source']['retrievedAt'], 'actual source acquisition time')
        locators = prediction['source']['sourceLocators']
        require(locators, 'REVEL source artifact locator required')
        require(any(locator.get('locatorValue') == resource['manifest']['source']['url'] and
                    locator.get('artifactSha256') == resource['manifest']['source']['sha256'] for locator in locators),
                'REVEL original CSV locator/hash binding')
        equal(prediction['provenance']['sourcePayloadSha256'], e1.canonical_sha256(all_rows), 'source record payload identity')
        equal(prediction['queryOutcome']['recordCount'], len(all_rows), 'actual record count')
        equal(prediction['queryOutcome']['status'], 'HIT' if all_rows else 'NO_RECORD', 'query state')
        equal(prediction['resourceExecution']['status'], 'SUCCEEDED', 'actual bounded source query')
        equal(prediction['resourceAvailability']['status'], 'AVAILABLE', 'verified source availability')
        equal(prediction['queryOutcome']['queryFingerprint'], e1.canonical_sha256(allele), 'query identity')
        ids = [record['recordId'] for record in prediction['source']['sourceRecords']]
        equal(sorted(ids), sorted(f"REVEL:1.3:chr12:line:{row['sourceLine']}" for row in all_rows), 'source record locators')
        for record in prediction['source']['sourceRecords']:
            equal(record['locator']['artifactSha256'], resource['manifest']['source']['sha256'], 'source record CSV hash')
        derived = derive_protein(allele, model)
        for key, value in derived.items():
            if key != 'exon':
                equal(inputs[key], value, 'independent ' + key)
        equal(inputs['transcriptModelSha256'], e1.canonical_sha256(model), 'resolved CDS identity')
        equal(inputs['sourceTranscriptVersionStatus'], 'UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM', 'no invented source transcript version')
        annotations = [atomic for atomic in evidence.values() if atomic['source']['sourceId'] == 'VEP_116_GRCH38']
        equal(len(annotations), 1, 'one stored VEP annotation')
        vep = annotations[0]
        vep_resource = next(row for row in profile['resources'] if row['resourceId'] == 'VEP_116_GRCH38')
        equal(vep['source']['resourceSha256'], vep_resource['identityFingerprintSha256'], 'VEP resource binding')
        annotation_metrics = unique_values(vep['measurements'], 'metricId')
        for name, value in {'vep.selectedTranscriptId': TX, 'hgvsc': derived['hgvsc'], 'hgvsp': derived['hgvsp'],
                            'proteinIdentity': model['protein'], 'proteinPosition': str(derived['proteinPosition']),
                            'aminoAcids': derived['refAminoAcid'] + '/' + derived['altAminoAcid']}.items():
            equal(annotation_metrics[name]['rawValue'], value, 'stored VEP ' + name)
        score = source_score(all_rows, derived)
        measurements = unique_values(prediction['measurements'], 'metricId')
        if score is not None:
            equal(decimal_score(inputs['score']), score, 'raw source score')
            equal(decimal_score(measurements['revel.score']['rawValue']), score, 'atomic score')
            equal(decimal_score(measurements['revel.score']['normalizedValue']), score, 'normalized score')
            equal(measurements['revel.score']['unit'], 'dimensionless', 'score unit')
            equal(inputs['sourceOriginalPosition'], all_rows[0]['hg19Position'], 'original coordinate')
            equal(inputs['sourceOriginalAssembly'], 'GRCh37', 'original source assembly')
            equal(inputs['sourcePublishedAssembly'], 'GRCh38', 'published source assembly')
            equal(inputs['sourceMappingMethod'], 'REVEL_publisher_liftOver', 'source coordinate provenance')
        else:
            require('score' not in inputs and 'revel.score' not in measurements, 'missing data cannot fabricate score zero')
    expected = expected_results(score)
    results = {}
    for code, status in expected.items():
        evaluation = evaluations['ACMG_AMP_2015:' + code]
        equal(evaluation['contextId'], context['contextId'], 'evaluation context')
        equal(evaluation['evaluationResult']['status'], status, 'independent ' + code + ' status')
        equal(evaluation['appliedStrength'], 'SUPPORTING' if status == 'MET' else 'NONE', code + ' strength')
        equal(evaluation['ruleBinding']['cspecSelectionId'], selection['selectionId'], 'bound CSpec selection')
        equal(evaluation['ruleBinding']['ruleRegistryId'], profile['registryBinding']['registryId'], 'bound registry')
        equal(evaluation['ruleBinding']['ruleRegistrySha256'], profile['registryBinding']['registrySha256'], 'bound registry bytes')
        trace = evaluation['deterministicTrace']
        parameters = {name: item['value'] for name, item in unique_values(trace['parameters'], 'name').items()}
        expected_parameters = {'profileSha256': inputs['profileSha256'], 'resourceIdentitySha256': resource['identity'],
                               'resolvedInputSha256': e1.canonical_sha256(inputs), 'tool': 'REVEL',
                               'evidenceMechanism': 'PROTEIN_MISSENSE_PREDICTION', 'bp7DependencyEligible': 'false',
                               'threshold': '0.70' if code == 'PP3' else '0.15', 'comparison': 'GE' if code == 'PP3' else 'LE'}
        for name, value in expected_parameters.items():
            equal(parameters[name], value, 'trace ' + name)
        equal(sorted(evaluation['evidenceIds']), sorted(evidence), 'complete scientific evidence inputs')
        equal(sorted(trace['inputEvidenceIds']), sorted(evidence), 'trace evidence IDs')
        if score is not None:
            equal(decimal_score(parameters['revel.score']), score, 'trace score')
            equal(trace['executionStatus'], 'COMPLETED', 'threshold evaluation execution')
            equal(trace['traceSha256'], e1.canonical_sha256(e1.deterministic_trace_projection(trace, include_hash=False)), 'trace ID')
        else:
            equal(trace['executionStatus'], 'NOT_RUN', 'missing input does not execute scientific threshold')
            require(evaluation['evaluationResult'].get('reasonCode'), 'missing input reason required')
        results[code] = status
    disposition = attempt.get('bp7Disposition') or {}
    require(disposition.get('status') != 'MET', 'REVEL BP4 cannot make BP7 MET')
    equal(attempt, before, 'Java attempt preserved')
    return {'allele': allele, 'score': str(score) if score is not None else None,
            'results': results, 'sourceRecordCount': len(all_rows), 'contextId': context['contextId']}


def verify_responses(business, responses):
    original = Path(business).read_bytes()
    run_id = json.loads(original)['runId']
    for name, path in responses.items():
        equal(Path(path).read_bytes(), original, name + ' full response bytes')
        equal(read(path)['runId'], run_id, name + ' run ID')
    return {'comparedResponses': list(responses), 'completeBytesIdentical': bool(responses), 'runId': run_id}


def verify_counter_pairs(pairs):
    results = []
    for before_path, after_path in pairs:
        before, after = read(before_path), read(after_path)
        require(before and after, 'nonempty raw counters required')
        equal(set(before), set(after), 'counter field identity')
        require(all(isinstance(value, int) and not isinstance(value, bool) and value >= 0
                    for item in (before, after) for value in item.values()),
                'raw counter values must be nonnegative integers')
        equal(after, before, 'zero added calls')
        results.append({'beforeSha256': file_sha(before_path), 'afterSha256': file_sha(after_path),
                        'counterCount': len(before), 'allIncrementsZero': True})
    return results


def verify_formal(saved, formal_path=None, options_path=None):
    if formal_path is None:
        require(options_path is None, 'input options require formal candidates')
        return {'covered': False}
    rows = read(formal_path)
    require(isinstance(rows, list), 'formal Top-N artifact must be an original candidate array')
    formal = unique_values(rows, 'candidateId')
    if options_path is not None:
        options = read(options_path)
        equal(options['analysisId'], saved['analysisId'], 'input options analysis')
        equal([row['candidateId'] for row in options['candidates']], saved['candidateIds'], 'formal selection order')
    count = 0
    for candidate in saved['candidates']:
        require(candidate['candidateId'] in formal, 'saved candidate absent from formal Top-N')
        source = formal[candidate['candidateId']]
        for attempt in candidate.get('attempts', []):
            inputs = attempt.get('transcriptInput') or {}
            if inputs.get('predictionPath') != 'HNF1A_REVEL':
                continue
            allele = attempt['context']['variant']['evidenceQueryAllele']
            key = source['observation']['key']
            equal((key['genomeAssembly'], key['chromosome']), (allele['assembly'], allele['contig']), 'formal allele assembly')
            for name in ('position', 'reference', 'alternate'):
                equal(key[name], allele[name], 'formal allele ' + name)
            if 'hgvsc' in inputs:
                annotations = [row for row in source['transcriptAnnotations'] if row.get('hgvsCoding') == inputs['hgvsc']]
                equal(len(annotations), 1, 'unique original VEP annotation')
                annotation = annotations[0]
                for source_name, input_name in [('hgvsProtein', 'hgvsp'), ('source', 'source'),
                                                 ('version', 'annotationVersion'), ('consequence', 'consequence')]:
                    equal(annotation[source_name], inputs[input_name], 'original VEP ' + source_name)
                if 'proteinPosition' in inputs:
                    equal(annotation['proteinPosition'], str(inputs['proteinPosition']), 'original VEP protein position')
                    equal(annotation['aminoAcids'], inputs['refAminoAcid'] + '/' + inputs['altAminoAcid'], 'original VEP amino acids')
            count += 1
    return {'covered': True, 'revelAttemptsBoundToFormalCandidates': count, 'formalSha256': file_sha(formal_path),
            'inputOptionsSha256': file_sha(options_path) if options_path else None}


def verify_instances(before_path=None, after_path=None):
    if before_path is None and after_path is None:
        return {'covered': False}
    require(before_path is not None and after_path is not None, 'both JVM instance receipts required')
    before, after = read(before_path), read(after_path)
    for value in (before, after):
        require(isinstance(value.get('processId'), int) and value['processId'] > 0, 'real process ID required')
    require(before['processId'] != after['processId'], 'different JVM processes required')
    for name in ('instanceId', 'sourceHead', 'configurationSha256', 'executionProfile'):
        require(before.get(name), 'instance receipt missing ' + name)
        equal(after.get(name), before[name], 'rebuilt JVM ' + name)
    return {'covered': True, 'beforeProcessId': before['processId'], 'afterProcessId': after['processId'],
            'beforeSha256': file_sha(before_path), 'afterSha256': file_sha(after_path)}


def validate_scientific_bundle(bundle, profile, schemas):
    binding = bundle['authorityBinding']
    rule_path = confined(RES / 'rule-registry-v2', binding['ruleRegistryId'] + '.json')
    overlay_path = confined(RES / 'cspec-overlay-registry-v1', binding['cspecOverlayRegistryId'] + '.json')
    authority_path = confined(RES, profile['overlayBinding']['authorityCatalogClasspathResource'].removeprefix('/acmg/'))
    equal(e1.rehash_bundle(copy.deepcopy(bundle)), bundle, 'original Java bundle identities')
    e1.validate_bundle(bundle, read(authority_path), read(rule_path), read(overlay_path), schemas,
                       authority_path=authority_path, rule_registry_path=rule_path, cspec_registry_path=overlay_path)


def verify_fixture_bundles(path):
    """Full frozen contract validation of explicitly synthetic Java output."""
    profile, _ = verify_profile()
    initial_sha = file_sha(path)
    rows = read(path)
    require(isinstance(rows, list) and rows, 'nonempty fixture bundle array required')
    schemas = e1.ContractSchemas()
    verified = []
    for row in rows:
        bundle = row['bundle']
        equal(bundle['dataClass'], 'SYNTHETIC', 'fixture-only entry data class')
        equal(bundle['context']['dataClass'], 'SYNTHETIC', 'fixture-only context data class')
        validate_scientific_bundle(bundle, profile, schemas)
        evidence = [item for item in bundle['atomicEvidence'] if item['source']['sourceId'] == SOURCE_ID]
        equal(len(evidence), 1, 'one REVEL fixture atomic')
        measurements = unique_values(evidence[0]['measurements'], 'metricId')
        score = decimal_score(measurements['revel.score']['rawValue'])
        evaluations = unique_values(bundle['criterionEvaluations'], 'criterionId')
        equal(set(evaluations), {'ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'}, 'no BP7 execution from REVEL')
        expected = expected_results(score)
        for code, status in expected.items():
            evaluation = evaluations['ACMG_AMP_2015:' + code]
            equal(evaluation['evaluationResult']['status'], status, 'independent fixture threshold ' + code)
            equal(evaluation['appliedStrength'], 'SUPPORTING' if status == 'MET' else 'NONE', 'fixture strength ' + code)
        verified.append({'candidateId': row['candidateId'], 'bundleId': bundle['bundleId'],
                         'score': str(score), 'results': expected})
    equal(file_sha(path), initial_sha, 'original fixture bytes unchanged')
    return {'status': 'PASS', 'dataClass': 'SYNTHETIC', 'engineeringFixtureOnly': True,
            'profileSha256': e1.canonical_sha256(profile), 'inputSha256': initial_sha,
            'fixtureBundles': len(verified), 'details': verified,
            'realResourceAcceptanceCovered': False, 'actualHttpVerified': False,
            'jvmRestartVerified': False, 'browserAcceptanceCovered': False}


def verify(business, resource_manifest, responses=None, counter_pairs=None,
           formal_path=None, options_path=None, instance_before=None, instance_after=None):
    profile, model = verify_profile()
    resource = verify_resource(resource_manifest, model)
    saved = read(business)
    original = copy.deepcopy(saved)
    require(saved.get('runId') and saved.get('analysisId'), 'saved business identity required')
    equal(saved['candidateIds'], [row['candidateId'] for row in saved['candidates']], 'saved formal candidate order')
    equal(len(set(saved['candidateIds'])), len(saved['candidateIds']), 'unique candidate identities')
    execution = saved['executionProfile']
    equal(execution['automaticTrigger'], False, 'automatic trigger remains disabled')
    equal(execution['classificationEnabled'], False, 'classification remains disabled')
    execution_identity = {key: value for key, value in execution.items() if key != 'manifestSha256'}
    equal(execution['manifestSha256'], e1.canonical_sha256(execution_identity), 'execution manifest identity')
    schemas = e1.ContractSchemas()
    verified = []
    original_attempts = {}
    for candidate in saved['candidates']:
        for attempt in candidate.get('attempts', []):
            if (attempt.get('transcriptInput') or {}).get('predictionPath') == 'HNF1A_REVEL':
                result = verify_attempt(attempt, resource, profile, model, schemas)
                result['candidateId'] = candidate['candidateId']
                verified.append(result)
                attempt_key = (candidate['candidateId'], result['contextId'])
                require(attempt_key not in original_attempts, 'duplicate REVEL context within one candidate')
                original_attempts[attempt_key] = attempt
    require(verified, 'no HNF1A REVEL attempt to verify')
    bundle_count = 0
    bundled_contexts = set()
    for item in saved.get('bundles', []):
        bundle = item['bundle']
        if not any(atomic['source']['sourceId'] == SOURCE_ID for atomic in bundle['atomicEvidence']):
            continue
        context_id = bundle['context']['contextId']
        attempt_key = (item['candidateId'], context_id)
        require(attempt_key in original_attempts, 'bundle is not bound to a saved REVEL attempt')
        require(attempt_key not in bundled_contexts, 'duplicate scientific bundle for one candidate context')
        bundled_contexts.add(attempt_key)
        attempt = original_attempts[attempt_key]
        for bundle_key, attempt_key in [('context', 'context'), ('cspecSelection', 'selection'),
                                        ('atomicEvidence', 'atomicEvidence'), ('criterionEvaluations', 'criterionEvaluations')]:
            equal(bundle[bundle_key], attempt[attempt_key], 'original attempt/bundle ' + bundle_key)
        validate_scientific_bundle(bundle, profile, schemas)
        bundle_count += 1
    evaluated_contexts = {(row['candidateId'], row['contextId']) for row in verified
                          if any(status != 'NOT_EVALUATED' for status in row.get('results', {}).values())}
    equal(bundled_contexts, evaluated_contexts, 'all evaluated REVEL contexts have scientific bundles')
    equal(saved, original, 'all saved fields preserved')
    instances = verify_instances(instance_before, instance_after)
    if instances['covered']:
        require({'saved-get', 'repeat-post', 'rebuilt-saved-get', 'rebuilt-repeat-post'} <= set(responses or {}),
                'JVM reuse requires all four before/after response records')
        require(len(counter_pairs or []) >= 2, 'JVM reuse requires before and after restart raw counter intervals')
    return {'status': 'PASS', 'profileSha256': e1.canonical_sha256(profile), 'resourceIdentitySha256': resource['identity'],
            'verifiedResourceRows': resource['recordCount'], 'revelAttempts': verified, 'scientificBundles': bundle_count,
            'savedSha256': file_sha(business), 'javaNodesUnchanged': True, 'modelInferencePerformed': False,
            'responses': verify_responses(business, responses or {}), 'counterPairs': verify_counter_pairs(counter_pairs or []),
            'formalCandidates': verify_formal(saved, formal_path, options_path), 'instances': instances,
            'jvmRestartVerified': instances['covered'], 'browserAcceptanceCovered': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--business', type=Path)
    source.add_argument('--fixture-bundles', type=Path)
    parser.add_argument('--resource-manifest', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    for label in ('saved-get', 'repeat-post', 'rebuilt-saved-get', 'rebuilt-repeat-post'):
        parser.add_argument('--' + label, type=Path)
    parser.add_argument('--counter-pair', action='append', nargs=2, default=[], metavar=('BEFORE', 'AFTER'))
    parser.add_argument('--formal-topn', type=Path)
    parser.add_argument('--input-options', type=Path)
    parser.add_argument('--instance-before', type=Path)
    parser.add_argument('--instance-after', type=Path)
    args = parser.parse_args()
    responses = {label: getattr(args, label.replace('-', '_')) for label in
                 ('saved-get', 'repeat-post', 'rebuilt-saved-get', 'rebuilt-repeat-post')
                 if getattr(args, label.replace('-', '_')) is not None}
    if args.fixture_bundles is not None:
        report = verify_fixture_bundles(args.fixture_bundles)
    else:
        require(args.resource_manifest is not None, 'business verification requires resource manifest')
        report = verify(args.business, args.resource_manifest, responses, args.counter_pair,
                        args.formal_topn, args.input_options, args.instance_before, args.instance_after)
    input_paths = [args.business, args.fixture_bundles, args.resource_manifest, *responses.values(), args.formal_topn, args.input_options,
                   args.instance_before, args.instance_after, *(Path(path) for pair in args.counter_pair for path in pair)]
    require(args.output.resolve() not in {path.resolve() for path in input_paths if path is not None},
            'verification output cannot overwrite input evidence')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
