import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.parse

import import_cspec_catalog as catalog
from cspec_ba1_semantics import ancillary_version_text, compile_frequency, load_review, RECIPROCAL_FAMILY, related_ba1_conditions


QC_INSTRUCTION = (
    'Recommend using as supporting level of evidence (PM2_Supporting) per ClinGen guidance. '
    'Per guidance from ClinGen/SVI, PM2_Supporting + PVS1 is sufficient evidence of a variant being likely pathogenic. '
    'We recommend investigating the genotype metrics in gnomAD for variants that have been flagged for having failed one or more quality parameters, '
    'as it is possible that some of these filtered variants are actually real. The number of filtered alleles can be counted to determine whether '
    'PM2_Supporting would be met even if they were genuine calls. If the filtered calls are sufficient in number to not meet PM2_Supporting, '
    'then we would not use it. Because it is also possible that these calls are false positives, we would not use filtered variants to support BA1 or BS1.')


def encoded(data):
    return catalog.canonical({'data': data, 'status': {'code': 200, 'name': 'OK'},
                              'metadata': {'rendered': {'when': '2026-09-14T00:00:00Z', 'by': catalog.BASE + '/srvc'}}})


def specimen(number, text='gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.'):
    symbol = 'SYNTHETIC' + str(number)
    return {'entId': 'GN' + str(number), 'ldhId': str(10000 + number), 'rev': 'synthetic-rev-' + str(number),
            'modified': '2026-09-14T00:00:00Z', 'entContent': {'version': '1.0', 'namespace': 'GN' + str(number),
                'title': 'SYNTHETIC ONLY', 'states': [{'name': 'Released', 'current': True}], 'notes': ''},
            'ld': {'Gene': [{'entContent': {'HGNC': {'symbol': symbol, 'hgnc_id': 'HGNC:' + str(number),
                                                   'ensembl_gene_id': 'ENSG' + str(number), 'entrez_id': str(number)}}}],
                   'RuleSet': [{'ldhId': 'rs' + str(number), 'entContent': {'genes': [{'gene': symbol,
                       'preferredTranscript': 'NM_' + str(number) + '.1',
                       'diseases': [{'preferredMondoId': 'MONDO:%07d' % number,
                                     'preferredModeOfInheritance': 'Autosomal recessive inheritance'}]}], 'references': []}}],
                   'CriteriaCode': [{'ldhId': 'ba1' + str(number), 'entContent': {'label': 'BA1', 'gene': [symbol],
                       'applicability': 'Applicable', 'additionalComments': '', 'originalACMGSummary': 'Template is never an executable condition.',
                       'strengthDescriptor': [{'strength': 'Stand Alone', 'applicability': 'Applicable',
                                               'instructionsToUse': '', 'text': text},
                                              {'strength': 'Strong', 'applicability': 'Not applicable', 'text': ''}]}}]}}


class FakeClient:
    def __init__(self, directory, entities, transform=None):
        self.directory, self.entities, self.transform = directory, entities, transform
        self.requests = []
        directory.mkdir()

    def get(self, suffix, name):
        if suffix == '/srvc':
            data = {'version': 'synthetic', 'entTypes': {catalog.TYPE: {'entCount': len(self.entities)}}}
        elif suffix == '/' + catalog.TYPE:
            data = {'entType': catalog.TYPE, 'entCount': len(self.entities)}
        else:
            query = urllib.parse.parse_qs(urllib.parse.urlparse(suffix).query)
            if 'ids' in query:
                ids = query['ids'][0].split(',')
                data = [copy.deepcopy(r) for r in self.entities if r['entId'] in ids]
            else:
                page, count = int(query['pg'][0]), int(query['pgSize'][0])
                data = [catalog.metadata_key(r) for r in self.entities[(page - 1) * count:page * count]]
        if self.transform:
            data = self.transform(name, data)
        raw = encoded(data)
        filename = 'raw-' + name + '.json'
        (self.directory / filename).write_bytes(raw)
        ref = {'rawRef': filename, 'rawSha256': hashlib.sha256(raw).hexdigest(), 'url': catalog.BASE + suffix,
               'sizeBytes': len(raw)}
        self.requests.append(ref)
        return raw, ref


class CspecImportTest(unittest.TestCase):
    def test_complete_known_quality_condition_is_preserved_only_in_threshold_contract(self):
        source = {'url': 'https://example.test/synthetic', 'sha256': 'b' * 64}
        review = {'reviewId': 'SYNTHETIC-ONLY', 'reviewSha256': 'a' * 64, 'sources': []}
        for disposition, text in (
                ('THRESHOLD', 'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.'),
                ('THRESHOLD', 'gnomAD Grpmax FAF \u2265 1:10,000 (\u2265 0.01% or 0.0001)'),
                ('GENERAL', 'No change from the ACMG/AMP guidelines.'),
                ('DISABLED', 'BA1 is not applicable.')):
            for instruction in (QC_INSTRUCTION, QC_INSTRUCTION.replace('would not use filtered', 'may use filtered'),
                                QC_INSTRUCTION + ' Except BA1 for adults.', QC_INSTRUCTION.split('Because')[1]):
                with self.subTest(text=text, instruction=instruction):
                    entity = specimen(222, text)
                    content = entity['ld']['CriteriaCode'][0]['entContent']
                    if disposition == 'DISABLED':
                        content['applicability'] = content['strengthDescriptor'][0]['applicability'] = 'Not Applicable'
                    entity['ld']['CriteriaCode'].append({'entContent': {'label': 'PM2', 'instructionsToUse': instruction}})
                    result = catalog.compile_ba1(entity, source, semantic_review=review)
                    if disposition == 'THRESHOLD' and instruction == QC_INSTRUCTION:
                        self.assertEqual('THRESHOLD', result['disposition'])
                        self.assertEqual(['GNOMAD_FILTERED_VARIANTS_EXCLUDED'], result['conditions'])
                    else:
                        self.assertEqual('UNSUPPORTED', result['disposition'])
                        self.assertEqual('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', result['reasonCode'])

    def test_cross_criterion_unknown_gate_is_shared_by_all_dispositions(self):
        source = {'url': 'https://example.test/synthetic', 'sha256': 'b' * 64}
        review = {'reviewId': 'SYNTHETIC-ONLY', 'reviewSha256': 'a' * 64, 'sources': []}
        for disposition, text in (
                ('THRESHOLD', 'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.'),
                ('THRESHOLD', 'gnomAD Grpmax FAF \u2265 1:10,000 (\u2265 0.01% or 0.0001)'),
                ('GENERAL', 'No change from the ACMG/AMP guidelines.'),
                ('DISABLED', 'BA1 is not applicable.')):
            entity = specimen(222, text)
            content = entity['ld']['CriteriaCode'][0]['entContent']
            if disposition == 'DISABLED':
                content['applicability'] = content['strengthDescriptor'][0]['applicability'] = 'Not Applicable'
            baseline = catalog.compile_ba1(entity, source, semantic_review=review)
            self.assertEqual(disposition, baseline['disposition'])
            self.assertEqual([], baseline['conditions'])
            for label in ('PM2', 'BS1', 'PS4'):
                for location in ('additionalComments', 'instructionsToUse', 'descriptor.text', 'descriptor.instructionsToUse'):
                    with self.subTest(text=text, label=label, location=location):
                        altered = copy.deepcopy(entity)
                        extra = {'label': label}
                        target = extra
                        if location.startswith('descriptor.'):
                            target = {}
                            extra['strengthDescriptor'] = [target]
                        target[location.split('.')[-1]] = 'Do not use BA1 in adults.'
                        altered['ld']['CriteriaCode'].append({'entContent': extra})
                        self.assertIsNone(related_ba1_conditions(altered))
                        result = catalog.compile_ba1(altered, source, semantic_review=review)
                        self.assertEqual('UNSUPPORTED', result['disposition'])
                        self.assertEqual('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', result['reasonCode'])
                        altered['entContent']['notes'] = 'Uninterpreted global restriction'
                        self.assertEqual('BA1_GLOBAL_NOTES_OR_ATTACHMENTS_UNSUPPORTED',
                                         catalog.compile_ba1(altered, source, semantic_review=review)['reasonCode'])

    def test_reciprocal_grpmax_faf_preserves_three_equivalent_numbers_and_review_binding(self):
        text = 'gnomAD Grpmax FAF ≥ 1:10,000 (≥ 0.01% or 0.0001)'
        source = {'url': 'https://example.test/public-replay', 'sha256': 'b' * 64}
        review = {'reviewId': 'SYNTHETIC-ONLY', 'reviewSha256': 'a' * 64, 'sources': [source]}
        result = compile_frequency(text, source, review)
        self.assertEqual(RECIPROCAL_FAMILY, result['semanticFamily'])
        self.assertEqual('0.0001', result['value'])
        self.assertEqual('10000', result['calculationProvenance']['reciprocalDenominator'])
        self.assertEqual('FAF', result['originalMetric'])
        self.assertIsNone(compile_frequency(text, source))
        for invalid in [text.replace('10,000', '1,000'), text.replace('0.01%', '0.1%'), text.replace('(≥', '(>'),
                        text.replace('FAF', 'MAF'), text.replace('10,000', '10,00'), text + ' Only in affected adults.']:
            with self.subTest(invalid=invalid): self.assertIsNone(compile_frequency(invalid, source, review))
        altered = compile_frequency(text.replace('10,000', '20,000').replace('0.01%', '0.005%').replace('0.0001', '0.00005'), source, review)
        self.assertEqual('0.00005', altered['value'])  # Finite grammar, no gene/threshold-specific dispatch.

    def test_unknown_cross_criterion_ba1_instruction_blocks_reciprocal_family(self):
        entity = specimen(222, 'gnomAD Grpmax FAF ≥ 1:10,000 (≥ 0.01% or 0.0001)')
        review = {'reviewId': 'SYNTHETIC-ONLY', 'reviewSha256': 'a' * 64, 'sources': []}
        source = {'url': 'https://example.test/fixture', 'sha256': 'b' * 64}
        self.assertEqual('THRESHOLD', catalog.compile_ba1(entity, source, semantic_review=review)['disposition'])
        entity['ld']['CriteriaCode'].append({'entContent': {'label': 'PM2', 'instructionsToUse': 'Do not use BA1 in adults.'}})
        self.assertIsNone(related_ba1_conditions(entity))
        self.assertEqual('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', catalog.compile_ba1(entity, source, semantic_review=review)['reasonCode'])
        entity['entContent']['releaseNotes'] = 'Updated BA1 for prevalence in adults.'
        self.assertFalse(ancillary_version_text('releaseNotes', entity['entContent']['releaseNotes']))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def captured(self, entities=None, transform=None):
        client = FakeClient(self.root / 'capture', entities or [specimen(101), specimen(102), specimen(103)], transform)
        with patch('builtins.print'):
            manifest = catalog.capture_batch(client, page_size=2, batch_size=2)
        return client.directory, manifest

    def snapshot(self, directory):
        return catalog.compile_snapshot(directory, catalog.file_sha(directory / 'capture-manifest.json'))

    def test_complete_pagination_and_atomically_sealed_snapshot(self):
        directory, manifest = self.captured()
        self.assertEqual([2, 1], [p['returnedCount'] for p in manifest['pages']])
        result = self.snapshot(directory)
        self.assertEqual((3, 3), (result['metadata']['selectedReleasedCount'], result['metadata']['entryCount']))
        self.assertEqual('COMPLETE', result['metadata']['status'])
        for entry in result['entries']:
            self.assertEqual('THRESHOLD', entry['ba1']['disposition'])
            self.assertEqual('GREATER_THAN', entry['ba1']['minimumAnComparator'])
            self.assertEqual('2000', entry['ba1']['minimumAn'])
        expected = result.pop('sha256')
        self.assertEqual(expected, catalog.digest(result))

    def test_same_algorithm_imports_new_gene_without_a_hand_authored_policy(self):
        directory, _ = self.captured([specimen(123456)])
        entry = self.snapshot(directory)['entries'][0]
        self.assertEqual(['HGNC:123456'], entry['scope']['hgncIds'])
        self.assertEqual('THRESHOLD', entry['ba1']['disposition'])

    def test_duplicate_page_entity_cannot_publish(self):
        def transform(name, data):
            if name == 'before-page-2': data[0] = catalog.metadata_key(specimen(101))
            return data
        with self.assertRaisesRegex(catalog.Invalid, 'PAGINATION_DUPLICATE'):
            self.captured(transform=transform)
        self.assertEqual('FAILED', json.loads((self.root / 'capture/capture-manifest.json').read_text())['status'])
        self.assertFalse((self.root / 'capture/snapshot.json').exists())

    def test_truncated_page_cannot_publish(self):
        with self.assertRaisesRegex(catalog.Invalid, 'PAGINATION_COUNT'):
            self.captured(transform=lambda name, data: [] if name == 'before-page-1' else data)

    def test_excess_record_beyond_declared_tail_is_not_ignored(self):
        with self.assertRaisesRegex(catalog.Invalid, 'PAGINATION_COUNT'):
            self.captured(transform=lambda name, data: data + [catalog.metadata_key(specimen(104))] if name == 'before-page-2' else data)

    def test_revision_change_in_content_fails_capture(self):
        def transform(name, data):
            if name.startswith('contents-'): data[0]['rev'] = 'changed'
            return data
        with self.assertRaisesRegex(catalog.Invalid, 'CONTENT_RELEASE_REVISION_CHANGED'):
            self.captured(transform=transform)

    def test_final_metadata_change_fails_capture(self):
        def transform(name, data):
            if name == 'after-page-1': data[0]['entContent']['version'] = '2.0'
            return data
        with self.assertRaisesRegex(catalog.Invalid, 'ENUMERATION_CHANGED'):
            self.captured(transform=transform)

    def test_cancelled_capture_keeps_raw_and_never_complete(self):
        def transform(name, data):
            if name == 'contents-0': raise KeyboardInterrupt()
            return data
        with self.assertRaises(KeyboardInterrupt): self.captured(transform=transform)
        manifest = json.loads((self.root / 'capture/capture-manifest.json').read_text())
        self.assertEqual('CANCELLED', manifest['status'])
        self.assertTrue((self.root / 'capture/raw-before-page-1.json').exists())
        with self.assertRaisesRegex(catalog.Invalid, 'CAPTURE_NOT_COMPLETE'):
            self.snapshot(self.root / 'capture')

    def test_legacy_and_never_released_filter_are_audited(self):
        records = [specimen(i) for i in range(101, 105)]
        records[1]['entContent']['legacyReplaced'] = True
        records[2]['entContent']['legacyFullySuperseded'] = False
        records[3]['entContent']['states'] = [{'name': 'Pilot Rules In Prep', 'current': True}]
        directory, manifest = self.captured(records)
        self.assertEqual(3, len(manifest['omitted']))
        self.assertEqual(1, len(self.snapshot(directory)['entries']))

    def test_historical_released_but_current_draft_is_never_executed(self):
        record = specimen(101)
        record['entContent']['states'] = [{'name': 'Released', 'current': False}, {'name': 'Pilot Rules In Prep', 'current': True}]
        directory, _ = self.captured([record])
        with self.assertRaisesRegex(catalog.Invalid, 'RELEASED_ARCHIVE_SET_INCOMPLETE'):
            self.snapshot(directory)

    def test_rebound_manifest_cannot_hide_raw_count_or_selection_tamper(self):
        directory, _ = self.captured()
        path = directory / 'capture-manifest.json'
        manifest = json.loads(path.read_text())
        manifest['contents'].pop()
        path.write_bytes(catalog.canonical(manifest))
        with self.assertRaisesRegex(catalog.Invalid, 'RELEASED_CONTENT_SET_MISMATCH'):
            self.snapshot(directory)

    def test_raw_hash_and_manifest_pin_are_required(self):
        directory, manifest = self.captured()
        with self.assertRaisesRegex(catalog.Invalid, 'MANIFEST_HASH'):
            catalog.compile_snapshot(directory, '0' * 64)
        path = directory / manifest['contents'][0]['rawRef']
        path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaisesRegex(catalog.Invalid, 'RAW_CONTENT_HASH'):
            self.snapshot(directory)

    def test_duplicate_json_key_is_rejected(self):
        with self.assertRaisesRegex(catalog.Invalid, 'DUPLICATE_JSON_KEY'):
            catalog.strict_json(b'{"status":200,"status":200}')

    def test_gene_disease_moi_are_not_cartesian_product(self):
        record = specimen(101)
        other = specimen(102)
        record['ld']['Gene'] += other['ld']['Gene']
        record['ld']['RuleSet'][0]['entContent']['genes'] += other['ld']['RuleSet'][0]['entContent']['genes']
        result = catalog.normalize_scopes(record)
        self.assertEqual(2, len(result))
        self.assertEqual([(['HGNC:101'], ['MONDO:0000101']), (['HGNC:102'], ['MONDO:0000102'])],
                         [(x['hgncIds'], x['mondoIds']) for x in result])

    def test_missing_transcript_does_not_mean_unrestricted(self):
        record = specimen(101)
        gene = record['ld']['RuleSet'][0]['entContent']['genes'][0]
        del gene['preferredTranscript']
        self.assertEqual('UNRESOLVED', catalog.normalize_scopes(record)[0]['transcriptScope'])
        gene['preferredTranscript'] = ''
        self.assertEqual('UNRESTRICTED', catalog.normalize_scopes(record)[0]['transcriptScope'])

    def test_unknown_moi_remains_unresolved(self):
        record = specimen(101)
        record['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance'] = 'complex'
        self.assertEqual('UNRESOLVED', catalog.normalize_scopes(record)[0]['status'])

    def test_hidden_not_applicable_templates_do_not_disable_active_ba1(self):
        self.assertEqual('THRESHOLD', catalog.compile_ba1(specimen(101), {})['disposition'])

    def test_exact_default_point_metadata_does_not_disable_or_rewrite_ba1(self):
        for disposition, text in (('THRESHOLD', 'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.'),
                                  ('GENERAL', 'No change from the ACMG/AMP guidelines.'),
                                  ('DISABLED', 'BA1 is not applicable.')):
            record = specimen(101, text)
            clause = record['ld']['CriteriaCode'][0]['entContent']
            if disposition == 'DISABLED':
                clause['applicability'] = clause['strengthDescriptor'][0]['applicability'] = 'Not Applicable'
            clause['strengthDescriptor'][0]['defaultPoint'] = 'Not Applicable'
            original = copy.deepcopy(record)
            with self.subTest(disposition=disposition):
                result = catalog.compile_ba1(record, {})
                self.assertEqual(disposition, result['disposition'])
                self.assertEqual('Not Applicable', result['clauses'][0]['entContent']['strengthDescriptor'][0]['defaultPoint'])
                self.assertEqual(original, record)
                self.assertEqual(original['ld']['CriteriaCode'], result['clauses'])

    def test_other_default_point_values_remain_unknown_operational_fields(self):
        for value in (None, False, True, 0, 1, -1, 0.5, '', '0', '1', 'Not applicable',
                      'Not Applicable ', ' Not Applicable', 'Applicable', [], {},
                      ['Not Applicable'], {'value': 'Not Applicable'}):
            record = specimen(101)
            record['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['defaultPoint'] = value
            original = copy.deepcopy(record)
            with self.subTest(value=value):
                result = catalog.compile_ba1(record, {})
                self.assertEqual('UNSUPPORTED', result['disposition'])
                self.assertEqual('BA1_UNKNOWN_OPERATIONAL_FIELD', result['reasonCode'])
                self.assertEqual(original, record)

    def test_default_point_sentinel_is_not_a_criterion_or_global_field(self):
        for where, reason in (('criterion', 'BA1_UNKNOWN_OPERATIONAL_FIELD'),
                              ('root', 'BA1_UNKNOWN_GLOBAL_OPERATIONAL_FIELD'),
                              ('ruleset', 'BA1_UNKNOWN_GLOBAL_OPERATIONAL_FIELD')):
            record = specimen(101)
            node = (record['entContent'] if where == 'root' else
                    record['ld']['RuleSet'][0]['entContent'] if where == 'ruleset' else
                    record['ld']['CriteriaCode'][0]['entContent'])
            node['defaultPoint'] = 'Not Applicable'
            with self.subTest(where=where):
                result = catalog.compile_ba1(record, {})
                self.assertEqual('UNSUPPORTED', result['disposition'])
                self.assertEqual(reason, result['reasonCode'])

    def test_default_point_metadata_never_bypasses_other_instruction_gates(self):
        cases = [('criterion_instruction', 'BA1_ADDITIONAL_INSTRUCTIONS_UNSUPPORTED'),
                 ('descriptor_instruction', 'BA1_ADDITIONAL_INSTRUCTIONS_UNSUPPORTED'),
                 ('unknown_descriptor', 'BA1_UNKNOWN_OPERATIONAL_FIELD'),
                 ('global_note', 'BA1_GLOBAL_NOTES_OR_ATTACHMENTS_UNSUPPORTED'),
                 ('global_publication_text', 'BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED'),
                 ('global_attachment', 'BA1_GLOBAL_NOTES_OR_ATTACHMENTS_UNSUPPORTED'),
                 ('unknown_cross_criterion', 'BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED'),
                 ('unknown_frequency', 'BA1_ACTIVE_SEMANTICS_UNSUPPORTED')]
        for fault, reason in cases:
            record = specimen(101)
            clause = record['ld']['CriteriaCode'][0]['entContent']
            chosen = clause['strengthDescriptor'][0]
            chosen['defaultPoint'] = 'Not Applicable'
            if fault == 'criterion_instruction': clause['instructionsToUse'] = 'Except variants in adults.'
            elif fault == 'descriptor_instruction': chosen['instructionsToUse'] = 'Require another evidence code.'
            elif fault == 'unknown_descriptor': chosen['upperBound'] = '0.02'
            elif fault == 'global_note': record['entContent']['notes'] = 'BA1 excludes selected variants.'
            elif fault == 'global_publication_text': record['entContent']['description'] = 'Use the point system in the attachment.'
            elif fault == 'global_attachment': record['ld']['File'] = [{'ldhIri': 'https://example.test/synthetic-attachment'}]
            elif fault == 'unknown_cross_criterion':
                record['ld']['CriteriaCode'].append({'entContent': {'label': 'PM2', 'instructionsToUse': 'Do not use BA1 in adults.'}})
            elif fault == 'unknown_frequency': chosen['text'] = 'MAF >= 0.007 with >2000 alleles.'
            with self.subTest(fault=fault):
                result = catalog.compile_ba1(record, {})
                self.assertEqual('UNSUPPORTED', result['disposition'])
                self.assertEqual(reason, result['reasonCode'])

    def test_explicit_active_nonapplicability_is_distinct_from_unsupported(self):
        record = specimen(101)
        content = record['ld']['CriteriaCode'][0]['entContent']
        content['applicability'] = content['strengthDescriptor'][0]['applicability'] = 'Not Applicable for this VCEP'
        content['strengthDescriptor'][0]['text'] = ''
        self.assertEqual('DISABLED', catalog.compile_ba1(record, {})['disposition'])

    def test_legacy_disposition_is_explicit_not_inferred_from_missing_top_field(self):
        record = specimen(101)
        content = record['ld']['CriteriaCode'][0]['entContent']
        del content['applicability']
        self.assertEqual('UNSUPPORTED', catalog.compile_ba1(record, {})['disposition'])
        chosen = content['strengthDescriptor'][0]
        chosen['applicability'] = 'Applicable with VCEP specification'
        self.assertEqual('THRESHOLD', catalog.compile_ba1(record, {})['disposition'])
        chosen['applicability'] = 'Not applicable'
        self.assertEqual('UNSUPPORTED', catalog.compile_ba1(record, {})['disposition'])
        chosen['text'] = 'BA1 is not applicable.'
        self.assertEqual('DISABLED', catalog.compile_ba1(record, {})['disposition'])
        chosen['extraCondition'] = 'except selected variants'
        self.assertEqual('UNSUPPORTED', catalog.compile_ba1(record, {})['disposition'])

    def test_empty_original_summary_and_unrecognized_no_change_never_general(self):
        for text in ('', ' ', 'No change.', 'Allele frequency above 5%.'):
            with self.subTest(text=text):
                self.assertEqual('UNSUPPORTED', catalog.compile_ba1(specimen(101, text), {})['disposition'])

    def test_explicit_complete_general_inheritance(self):
        self.assertEqual('GENERAL', catalog.compile_ba1(specimen(101, 'No change from the ACMG/AMP guidelines.'), {})['disposition'])

    def test_no_maf_to_faf_migration_or_ignoring_remainder(self):
        for text in ('MAF >= 0.007 with >2000 alleles.',
                     'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000. Except founder variants.',
                     'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000. See [1](#ref).'):
            with self.subTest(text=text): self.assertEqual('UNSUPPORTED', catalog.compile_ba1(specimen(101, text), {})['disposition'])

    def test_lower_source_an_is_unsupported_instead_of_silently_rewritten(self):
        template = 'gnomAD joint GroupMax FAF95 >= 0.007; same population AN >= %s.'
        self.assertIsNone(compile_frequency(template % '1999', {}))
        self.assertEqual('2000', compile_frequency(template % '2000', {})['minimumAn'])
        result = compile_frequency(template.replace('AN >=', 'AN >') % '2000', {})
        self.assertEqual(('2000', 'GREATER_THAN'), (result['minimumAn'], result['minimumAnComparator']))

    def test_unknown_operational_field_instruction_and_global_note_are_not_dropped(self):
        for where, key, value in [('clause', 'instructionsToUse', 'Check BS1'), ('clause', 'footnote', 'Except X'),
                                  ('descriptor', 'upperBound', '0.02'), ('root', 'notes', 'BA1 excludes Y')]:
            record = specimen(101)
            node = record['entContent'] if where == 'root' else record['ld']['CriteriaCode'][0]['entContent']
            if where == 'descriptor': node = node['strengthDescriptor'][0]
            node[key] = value
            with self.subTest(key=key): self.assertEqual('UNSUPPORTED', catalog.compile_ba1(record, {})['disposition'])

    def test_approved_hgnc_only_current_symbols_and_external_ids(self):
        raw = b'hgnc_id\tsymbol\tstatus\tensembl_gene_id\tentrez_id\talias_symbol\nHGNC:1\tCURRENT\tApproved\tENSG001\t123\tOLD|OTHER\nHGNC:2\tWITHDRAWN\tEntry Withdrawn\t\t\t\n'
        result, excluded = catalog.derive_gene_mappings(raw)
        self.assertEqual(1, excluded)
        self.assertEqual([{'hgncId': 'HGNC:1', 'geneSymbols': ['CURRENT'], 'ensemblGeneIds': ['ENSG001'], 'entrezGeneIds': ['123']}], result)

    def test_hgnc_duplicate_namespace_and_truncation_are_rejected(self):
        header = 'hgnc_id\tsymbol\tstatus\tensembl_gene_id\tentrez_id\n'
        for body in ('HGNC:1\tA\tApproved\tENSG1\t1\nHGNC:1\tB\tApproved\tENSG2\t2\n',
                     '37\tA\tApproved\tENSG1\t1\n', 'HGNC:1\tA\tApproved\tENSG1\n'):
            with self.subTest(body=body), self.assertRaises(catalog.Invalid):
                catalog.derive_gene_mappings((header + body).encode())

    def test_archive_versions_unique_exact_spec_and_order(self):
        prefix = catalog.BASE + '/' + catalog.TYPE + '/id/GN101/version/'
        self.assertEqual(['1.10.0', '1.2.0'], catalog.archive_versions(encoded([{'@id': prefix + '1.2.0'}, {'@id': prefix + '1.10.0'}]), 'GN101'))
        for items in ([{'@id': prefix + '1.0.0'}, {'@id': prefix + '1.0.0'}],
                      [{'@id': prefix.replace('GN101', 'GN102') + '1.0.0'}], [{'@id': prefix + '../1.0.0'}]):
            with self.subTest(items=items), self.assertRaises(catalog.Invalid): catalog.archive_versions(encoded(items), 'GN101')

    def test_complete_calculated_family_preserves_fraction_percent_an_and_provenance(self):
        text = ('* Variants with a highest population minor allele frequency (MAF) ≥0.006 (0.6%) in any continental population with >2000 alleles in gnomAD will meet BA1.\n'
                '  * Calculated using the Prevalence of 1:60,000, Allelic Contribution of 0.5, Genetic Contribution of 1, and Penetrance of 0.75 to allow for mild EXAMPLE that may develop in adulthood.')
        review = {'reviewId': 'SYNTHETIC-ONLY', 'reviewSha256': 'a' * 64, 'sources': [{'url': 'https://example.org/review', 'sha256': 'b' * 64}]}
        source = {'url': 'https://example.org/raw', 'sha256': 'c' * 64}
        result = compile_frequency(text, source, review)
        self.assertIsNotNone(result)
        self.assertEqual(('MAF', '0.006', 'GREATER_THAN', '2000'), (result['originalMetric'], result['value'], result['minimumAnComparator'], result['minimumAn']))
        self.assertEqual('EXPLICIT_CALCULATED_FREQUENCY_MIGRATION', result['migration']['basis'])
        self.assertFalse(result['calculationProvenance']['cutoffRecomputed'])
        self.assertIsNone(compile_frequency(text, source))
        for mutation in (text.replace('(0.6%)', '(6%)'), text + ' Exclude founder variants.',
                         text.replace('EXAMPLE', 'EXAMPLE except specific variants'), text.replace('1:60,000', '1:6,00'),
                         text.replace('Penetrance of 0.75', 'Penetrance of 0')):
            with self.subTest(mutation=mutation): self.assertIsNone(compile_frequency(mutation, source, review))

    def test_explicit_faf_alignment_still_requires_bound_review(self):
        text = 'Allele frequency is ≥0.001 based on the filtering allele frequency (FAF) in gnomAD in the subpopulation with the highest frequency (popmax).'
        self.assertIsNone(compile_frequency(text, {}))
        result = compile_frequency(text, {}, {'reviewId': 'synthetic', 'reviewSha256': 'a' * 64, 'sources': []})
        self.assertEqual('FAF', result['originalMetric'])
        self.assertEqual('gnomad.joint.faf95max', result['metric'])
        self.assertEqual('EXPLICIT_FAF_DEFINITION_ALIGNMENT', result['migration']['basis'])
        self.assertIsNone(compile_frequency(text + ' Prevalence must be at most 1/300.', {}, {'reviewId': 'synthetic', 'reviewSha256': 'a' * 64, 'sources': []}))

    def test_additional_criterion_disease_scope_is_preserved_as_unresolved(self):
        record = specimen(101)
        record['ld']['CriteriaCode'][0]['entContent']['disease'] = ['MONDO:0000101', 'MONDO:0000999']
        directory, _ = self.captured([record])
        scope = self.snapshot(directory)['entries'][0]['scope']
        self.assertEqual('UNRESOLVED', scope['status'])
        self.assertIn('MONDO:0000999', scope['criterionDiseaseIds'])

    def test_source_url_names_the_actual_raw_batch_response(self):
        directory, _ = self.captured()
        source = self.snapshot(directory)['entries'][0]['ba1']['sourceRefs'][0]
        self.assertIn('ids=', source['url'])
        self.assertEqual('/data/0', source['jsonPointer'])

    def test_old_moi_labels_are_exact_synonyms_and_unknown_labels_remain_unresolved(self):
        record = specimen(101)
        disease = record['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        for raw, expected in [('Autosomal Dominant', 'AUTOSOMAL_DOMINANT'),
                              ('Autosomal Recessive', 'AUTOSOMAL_RECESSIVE'),
                              (' Autosomal dominant inheritance', 'AUTOSOMAL_DOMINANT')]:
            disease['preferredModeOfInheritance'] = raw
            self.assertEqual([expected], catalog.normalize_scopes(record)[0]['moi'])
        for raw in ('Other', 'Undetermined mode of inheritance', 'Semidominant inheritance'):
            disease['preferredModeOfInheritance'] = raw
            self.assertEqual('UNRESOLVED', catalog.normalize_scopes(record)[0]['status'])

    def test_mitochondrial_scope_requires_both_source_nodes_and_never_fabricates_hgnc(self):
        record = specimen(101)
        record['ld']['Gene'] = []
        rs = record['ld']['RuleSet'][0]['entContent']
        rs['genes'] = []
        rs['geneType'] = 'mitochondrial'
        source = {'url': 'https://example.org/raw', 'sha256': 'a' * 64, 'jsonPointer': '/data/0'}
        self.assertNotIn('sequenceScope', catalog.normalize_scopes(record, source)[0])
        record['ld']['CriteriaCode'][0]['entContent']['geneType'] = 'mitochondrial'
        scope = catalog.normalize_scopes(record, source)[0]
        self.assertEqual(('UNRESOLVED', [], ['MT']), (scope['status'], scope['hgncIds'], scope['contigs']))
        self.assertEqual(2, len(scope['sequenceScopeSourceRefs']))
        self.assertTrue(all(x['sha256'] == source['sha256'] for x in scope['sequenceScopeSourceRefs']))
        rs['geneType'] = 'nuclear'
        self.assertNotIn('sequenceScope', catalog.normalize_scopes(record, source)[0])

    def test_complete_publication_templates_do_not_ignore_new_ba1_or_unlabelled_requirements(self):
        description = ('SYNTHETIC Specifications version 2.0. The VCEP reviewed all the codes and updated wording. '
                       'SYNTHETIC Specifications version 2.0.1. The VCEP updated the wording for PP3 and BP4 to only include reference to the Splice AI tool. '
                       '6/11/26 the VCEP added that PVS1 + PM2 (supporting) = Likely Pathogenic')
        self.assertTrue(ancillary_version_text('description', description))
        for mutation in (description + ' Requires expert review.', description.replace('PP3 and BP4', 'BA1 and BP4'),
                         description.replace('Splice AI tool', 'new source and population threshold')):
            self.assertFalse(ancillary_version_text('description', mutation))
        for key, value in [('description', description + ' Except variant X.'),
                           ('releaseNotes', 'Unlabelled population exclusions apply.'), ('additionalRequirements', ['Review X'])]:
            record = specimen(101)
            record['entContent'][key] = value
            self.assertEqual('UNSUPPORTED', catalog.compile_ba1(record, {})['disposition'])

    def test_released_archive_must_match_exact_original_release_event_and_specification(self):
        record = specimen(101)
        timestamp = '2025-01-01T00:00:00Z'
        record['entContent']['states'] = [{'name': 'Released', 'current': False, 'event': {'timeStamp': timestamp}},
                                         {'name': 'Pilot Rules In Prep', 'current': True}]
        directory, manifest = self.captured([record])
        archive = specimen(101)
        archive['entContent']['version'] = '0.9'
        archive['entContent']['states'][0]['event'] = {'timeStamp': timestamp}
        version_url = catalog.BASE + '/' + catalog.TYPE + '/id/GN101/version/0.9.1'
        def write_ref(name, data, url):
            path = directory / name
            path.write_bytes(encoded(data))
            return {'rawRef': name, 'rawSha256': catalog.file_sha(path), 'url': url}
        versions = write_ref('versions.json', [{'@id': version_url}], version_url.rsplit('/', 1)[0])
        def build(value):
            ref = write_ref('archive.json', value, version_url)
            manifest['releasedArchives'] = [dict(ref, specificationId='GN101', archiveVersion='0.9.1', versionsSource=versions)]
            catalog.save_json(directory / 'capture-manifest.json', manifest)
            return self.snapshot(directory)
        self.assertEqual('0.9', build(archive)['entries'][0]['version'])
        for changed in ('timestamp', 'specification', 'draft'):
            bad = copy.deepcopy(archive)
            if changed == 'timestamp': bad['entContent']['states'][0]['event']['timeStamp'] = '2024-01-01T00:00:00Z'
            elif changed == 'specification': bad['entId'] = 'GN102'
            else: bad['entContent']['states'][0]['current'] = False
            with self.subTest(changed=changed), self.assertRaisesRegex(catalog.Invalid, 'ARCHIVE_RELEASE_BINDING'):
                build(bad)

    def test_review_pin_prevents_self_reported_migration_or_changed_guidance(self):
        path = self.root / 'review.json'
        path.write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'REVIEW_HASH'):
            load_review(path, '0' * 64)
        with self.assertRaisesRegex(ValueError, 'FAMILY_UNSUPPORTED'):
            load_review(path, catalog.file_sha(path))

    def publication_fixture(self):
        directory, _ = self.captured([specimen(101)])
        candidate = self.snapshot(directory)
        candidate_path = directory / 'candidate.json'
        catalog.save_json(candidate_path, candidate)
        metadata = candidate['metadata']
        review = {'schemaVersion': 'a01-ba1-cspec-batch-review-v1', 'status': 'REVIEWED',
                  'reviewId': 'SYNTHETIC-REVIEW-ONLY', 'reviewScope': 'FINITE_BA1_COMPILER_AND_COMPLETE_RELEASED_CATALOG',
                  'candidate': {'sha256': catalog.file_sha(candidate_path), 'compilerVersion': metadata['compilerVersion'],
                                'sourceCaptureSha256': metadata['captureManifestSha256'],
                                'compilerSourceSha256': metadata['compilerSourceSha256'],
                                'semanticCompilerSourceSha256': metadata['semanticCompilerSourceSha256']},
                  'reviewer': {'role': 'INDEPENDENT_SOURCE_AND_SEMANTICS_REVIEW', 'id': 'SYNTHETIC-REVIEWER'},
                  'reviewedAt': '2026-09-14T00:00:00Z', 'sourcesIntegrityReceipt': {'sha256': 'a' * 64},
                  'metadata': {'boundary': 'SYNTHETIC ONLY; NOT A REAL INDEPENDENT REVIEW'}}
        review_path = directory / 'review.json'
        catalog.save_json(review_path, review)
        return candidate_path, review_path, review, self.root / 'new-runtime-snapshot.json'

    def test_publish_changes_only_batch_review_and_seal_preserving_candidate(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        original_bytes = candidate_path.read_bytes()
        result = catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
        self.assertEqual(original_bytes, candidate_path.read_bytes())
        self.assertEqual(result, catalog.strict_json(output.read_bytes()))
        original = catalog.strict_json(original_bytes)
        seal = result.pop('sha256')
        self.assertEqual(seal, catalog.digest(result))
        self.assertEqual('REVIEWED', result['metadata']['batchReview']['status'])
        self.assertEqual(catalog.file_sha(review_path), result['metadata']['batchReview']['reviewReceiptSha256'])
        result['metadata']['batchReview'] = original['metadata']['batchReview']
        del original['sha256']
        self.assertEqual(original, result)
        self.assertEqual([], list(self.root.glob('.*.publication-*')))

    def test_publish_requires_external_candidate_and_review_pins(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        for candidate_pin, review_pin in [('0' * 64, catalog.file_sha(review_path)), (review['candidate']['sha256'], '0' * 64)]:
            with self.subTest(candidate_pin=candidate_pin), self.assertRaisesRegex(catalog.Invalid, 'HASH_OR_SIZE'):
                catalog.publish_snapshot(candidate_path, candidate_pin, review_path, review_pin, output)
            self.assertFalse(output.exists())

    def test_publish_rejects_wrong_review_candidate_compiler_capture_and_source_even_when_receipt_rehashed(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        for key in ('sha256', 'compilerVersion', 'sourceCaptureSha256', 'compilerSourceSha256', 'semanticCompilerSourceSha256'):
            bad = copy.deepcopy(review)
            bad['candidate'][key] = '9.9.9' if key == 'compilerVersion' else '0' * 64
            review_path.write_bytes(catalog.canonical(bad))
            with self.subTest(key=key), self.assertRaisesRegex(catalog.Invalid, 'CANDIDATE_BINDING'):
                catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
            self.assertFalse(output.exists())

    def test_publish_rejects_nonreviewed_wrong_scope_or_hidden_conditions(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        for key, value in [('status', 'PENDING'), ('reviewScope', 'ALL_RULES_CLINICAL_APPROVED'),
                           ('additionalRequirements', ['manual approval required']), ('reviewedAt', '2026-09-14')]:
            bad = copy.deepcopy(review)
            bad[key] = value
            review_path.write_bytes(catalog.canonical(bad))
            with self.subTest(key=key), self.assertRaises(catalog.Invalid):
                catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
            self.assertFalse(output.exists())

    def test_publish_rejects_existing_snapshot_without_touching_either_input(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        output.write_bytes(b'OLD RUNTIME SNAPSHOT')
        before = (candidate_path.read_bytes(), review_path.read_bytes(), output.read_bytes())
        with self.assertRaisesRegex(catalog.Invalid, 'ALREADY_EXISTS'):
            catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
        self.assertEqual(before, (candidate_path.read_bytes(), review_path.read_bytes(), output.read_bytes()))

    def test_atomic_publication_never_overwrites_concurrently_created_target(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        actual_link = catalog.os.link
        def competing_publish(staging, target):
            target.write_bytes(b'CONCURRENT OLD SNAPSHOT')
            return actual_link(staging, target)
        with patch.object(catalog.os, 'link', side_effect=competing_publish), self.assertRaises(FileExistsError):
            catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
        self.assertEqual(b'CONCURRENT OLD SNAPSHOT', output.read_bytes())
        evidence = list(self.root.glob('.*.publication-*'))
        self.assertEqual(1, len(evidence))
        self.assertEqual('REVIEWED', catalog.strict_json(evidence[0].read_bytes())['metadata']['batchReview']['status'])

    def test_publication_write_failure_keeps_partial_staging_without_runtime_output(self):
        candidate_path, review_path, review, output = self.publication_fixture()
        def fail_save(path, value):
            path.with_name(path.name + '.writing').write_bytes(b'{incomplete')
            raise OSError('SYNTHETIC_WRITE_FAILURE')
        with patch.object(catalog, 'save_json', side_effect=fail_save), self.assertRaisesRegex(OSError, 'SYNTHETIC_WRITE_FAILURE'):
            catalog.publish_snapshot(candidate_path, review['candidate']['sha256'], review_path, catalog.file_sha(review_path), output)
        self.assertFalse(output.exists())
        self.assertEqual(1, len(list(self.root.glob('.*.publication-*.writing'))))


if __name__ == '__main__':
    unittest.main()
