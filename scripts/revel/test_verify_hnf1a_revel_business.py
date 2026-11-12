"""Focused verifier tests with explicit synthetic records, not real acceptance."""
import copy
import csv
from decimal import Decimal
import json
from pathlib import Path
import tempfile
import unittest

from scripts.revel.verify_hnf1a_revel_business import (
    FIELDS, MODEL, PROFILE, TX, confined, decimal_score, derive_protein, e1, expected_results,
    file_sha, java_row, read, source_score, unique_values, verify_counter_pairs,
    verify_formal, verify_identity, verify_instances, verify_profile, verify_resource, verify_responses,
)


class IndependentRevelVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = read(MODEL)

    def allele(self):
        return {'assembly': 'GRCh38', 'contig': '12', 'position': 120989017,
                'reference': 'C', 'alternate': 'G'}

    def source(self, score='0.15'):
        # Engineering fixture: the hg19 coordinate and score are intentionally synthetic.
        return dict(zip(FIELDS + ['sourceLine'],
                        ['12', '121416901', '120989017', 'C', 'G', 'R', 'G', score,
                         'ENST00000257555', '2'], strict=True))

    def resource(self, root, rows=None):
        rows = rows or [self.source()]
        with (root / 'source.csv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS, extrasaction='ignore', lineterminator='\n')
            writer.writeheader()
            writer.writerows(rows)
        with (root / 'subset.tsv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS + ['sourceLine'], delimiter='\t', lineterminator='\n')
            writer.writeheader()
            writer.writerows(rows)
        manifest = {
            'schemaVersion': 'revel-hnf1a-resource-v1', 'resourceVersion': '1.3',
            'derivationVersion': 'hnf1a-revel-cds-map-v1', 'transcriptModelSha256': e1.canonical_sha256(self.model),
            'dataFile': 'subset.tsv', 'dataSha256': file_sha(root / 'subset.tsv'),
            'scope': {'hgncId': 'HGNC:11621', 'transcript': TX, 'refseqTranscript': 'NM_000545.8',
                      'assembly': 'GRCh38', 'contig': '12', 'minPosition': 120989017, 'maxPosition': 120989017},
            'source': {'file': 'source.csv', 'sha256': file_sha(root / 'source.csv'),
                       'originalAssembly': 'GRCh37', 'publishedAssembly': 'GRCh38',
                       'mappingMethod': 'REVEL_publisher_liftOver', 'transcriptVersionProvided': False,
                       'proteinPositionProvided': False},
        }
        path = root / 'manifest.json'
        path.write_text(json.dumps(manifest), encoding='utf-8')
        return path, manifest

    def test_inclusive_thresholds_and_neighbours(self):
        expected = {
            '0': ('NOT_MET', 'MET'), '0.149999': ('NOT_MET', 'MET'), '0.15': ('NOT_MET', 'MET'),
            '0.150001': ('NOT_MET', 'NOT_MET'), '0.699999': ('NOT_MET', 'NOT_MET'),
            '0.70': ('MET', 'NOT_MET'), '0.700001': ('MET', 'NOT_MET'), '1': ('MET', 'NOT_MET'),
        }
        for score, states in expected.items():
            with self.subTest(score=score):
                self.assertEqual(tuple(expected_results(score).values()), states)
        self.assertEqual(expected_results(None), {'PP3': 'NOT_EVALUATED', 'BP4': 'NOT_EVALUATED'})

    def test_invalid_and_nonfinite_scores_cannot_be_benign(self):
        for score in ('', '.', 'NA', 'NaN', 'Infinity', '-0.01', '1.01', None, True):
            with self.subTest(score=score), self.assertRaisesRegex(ValueError, 'invalid REVEL score'):
                decimal_score(score)
        self.assertEqual(decimal_score('0.1500'), Decimal('0.15'))

    def test_reference_cds_and_hgvs_are_independent(self):
        before = copy.deepcopy(self.allele())
        derived = derive_protein(before, self.model)
        self.assertEqual((derived['cdsPosition'], derived['proteinPosition']), (511, 171))
        self.assertEqual((derived['refCodon'], derived['altCodon']), ('CGA', 'GGA'))
        self.assertEqual((derived['refAminoAcid'], derived['altAminoAcid']), ('R', 'G'))
        self.assertEqual(derived['hgvsp'], 'ENSP00000257555.5:p.Arg171Gly')
        self.assertEqual(derived['hgvsc'], TX + ':c.511C>G')
        self.assertEqual(before, self.allele())

    def test_wrong_ref_or_assembly_and_stop_are_rejected(self):
        for field, value, message in [('reference', 'A', 'CDS reference'), ('assembly', 'GRCh37', 'assembly'),
                                      ('alternate', 'T', 'not an internal missense')]:
            allele = self.allele()
            allele[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                derive_protein(allele, self.model)

    def test_resource_can_contain_start_codon_while_execution_stays_internal(self):
        allele = dict(self.allele(), position=120978769, reference='A', alternate='G')
        with self.assertRaisesRegex(ValueError, 'outside enabled internal CDS'):
            derive_protein(allele, self.model)
        source = derive_protein(allele, self.model, internal_only=False)
        self.assertEqual((source['proteinPosition'], source['refAminoAcid'], source['altAminoAcid']), (1, 'M', 'V'))

    def test_exact_records_preserve_strings_and_original_coordinate(self):
        source = self.source('0.1500')
        row = java_row(source)
        self.assertEqual(row['score'], '0.1500')
        self.assertEqual((row['hg19Position'], row['grch38Position']), (121416901, 120989017))
        self.assertEqual(source_score([row], derive_protein(self.allele(), self.model)), Decimal('0.15'))

    def test_conflicting_score_or_mapping_cannot_select_favourable_record(self):
        row = java_row(self.source())
        derived = derive_protein(self.allele(), self.model)
        for field, value, message in [('score', '0.99', 'score conflict'),
                                      ('hg19Position', 121416902, 'coordinate conflict')]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                source_score([row, {**row, field: value}], derived)

    def test_transcript_or_amino_acid_mismatch_is_rejected(self):
        row = java_row(self.source())
        derived = derive_protein(self.allele(), self.model)
        for field, value, message in [('transcripts', 'ENST00000257555.10', 'source transcript mismatch'),
                                      ('altAa', 'V', 'source protein identity')]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                source_score([{**row, field: value}], derived)

    def test_missing_records_remain_no_score(self):
        self.assertIsNone(source_score([], derive_protein(self.allele(), self.model)))
        self.assertEqual(expected_results(None)['BP4'], 'NOT_EVALUATED')

    def test_resource_checks_source_rows_hashes_and_sequence(self):
        with tempfile.TemporaryDirectory() as directory:
            path, manifest = self.resource(Path(directory))
            result = verify_resource(path, self.model)
            self.assertEqual(result['recordCount'], 1)
            self.assertEqual(result['identity'], e1.canonical_sha256(manifest))
            self.assertEqual(result['records'][0]['score'], '0.15')

    def test_changed_derived_file_fails_before_scientific_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, _ = self.resource(root)
            with (root / 'subset.tsv').open('a', encoding='utf-8') as stream:
                stream.write('\n')
            with self.assertRaisesRegex(ValueError, 'derived data bytes'):
                verify_resource(path, self.model)

    def test_rehashed_subset_still_cannot_change_original_source_score(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, manifest = self.resource(root)
            text = (root / 'subset.tsv').read_text().replace('0.15', '0.99')
            (root / 'subset.tsv').write_text(text, encoding='utf-8')
            manifest['dataSha256'] = file_sha(root / 'subset.tsv')
            path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'unaltered source row'):
                verify_resource(path, self.model)

    def test_source_unversioned_identity_cannot_be_relabelled_native(self):
        with tempfile.TemporaryDirectory() as directory:
            path, manifest = self.resource(Path(directory))
            manifest['source']['originalAssembly'] = 'GRCh38'
            path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'publisher coordinate lineage'):
                verify_resource(path, self.model)

    def test_relative_paths_and_unique_fields_are_strict(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'escapes manifest directory'):
                confined(directory, '../outside.tsv')
        with self.assertRaisesRegex(ValueError, 'duplicate name'):
            unique_values([{'name': 'threshold'}, {'name': 'threshold'}], 'name')

    def test_reuse_requires_identical_bytes_and_all_counter_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = b'{"runId":"fixture-run","unchangedOtherRules":[1,2]}'
            for name in ('first.json', 'saved.json'):
                (root / name).write_bytes(raw)
            self.assertTrue(verify_responses(root / 'first.json', {'saved-get': root / 'saved.json'})['completeBytesIdentical'])
            (root / 'saved.json').write_bytes(raw + b'\n')
            with self.assertRaisesRegex(ValueError, 'full response bytes'):
                verify_responses(root / 'first.json', {'saved-get': root / 'saved.json'})
            for name in ('before.json', 'after.json'):
                (root / name).write_text('{"provider":3,"evaluator":6}', encoding='utf-8')
            self.assertEqual(verify_counter_pairs([(root / 'before.json', root / 'after.json')])[0]['counterCount'], 2)
            (root / 'after.json').write_text('{"provider":4,"evaluator":6}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'zero added calls'):
                verify_counter_pairs([(root / 'before.json', root / 'after.json')])

    def test_registered_profile_preserves_exact_specialist_policy(self):
        profile, model = verify_profile()
        self.assertFalse(profile['policy']['bp7DependencyEligible'])
        self.assertEqual(model['transcript'], TX)

    def test_formal_candidate_identity_and_source_annotation_cannot_be_injected(self):
        allele = self.allele()
        derived = derive_protein(allele, self.model)
        inputs = {'predictionPath': 'HNF1A_REVEL', 'hgvsc': derived['hgvsc'], 'hgvsp': derived['hgvsp'],
                  'source': 'VEP', 'annotationVersion': '116.0', 'consequence': 'missense_variant',
                  'proteinPosition': 171, 'refAminoAcid': 'R', 'altAminoAcid': 'G'}
        saved = {'candidateIds': ['fixture'], 'candidates': [{'candidateId': 'fixture', 'attempts': [{
            'context': {'variant': {'evidenceQueryAllele': allele}}, 'transcriptInput': inputs}]}]}
        formal = [{'candidateId': 'fixture', 'observation': {'key': {'genomeAssembly': 'GRCh38',
                   'chromosome': '12', 'position': allele['position'], 'reference': 'C', 'alternate': 'G'}},
                   'transcriptAnnotations': [{'hgvsCoding': inputs['hgvsc'], 'hgvsProtein': inputs['hgvsp'],
                       'source': 'VEP', 'version': '116.0', 'consequence': 'missense_variant',
                       'proteinPosition': '171', 'aminoAcids': 'R/G'}]}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'formal.json'
            path.write_text(json.dumps(formal), encoding='utf-8')
            self.assertEqual(verify_formal(saved, path)['revelAttemptsBoundToFormalCandidates'], 1)
            formal[0]['transcriptAnnotations'][0]['hgvsProtein'] = 'ENSP00000257555.5:p.Arg171Val'
            path.write_text(json.dumps(formal), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'original VEP hgvsProtein'):
                verify_formal(saved, path)

    def test_service_object_reconstruction_is_not_a_jvm_restart(self):
        receipt = {'processId': 1000, 'instanceId': 'fixture-instance', 'sourceHead': 'fixture-head',
                   'configurationSha256': 'fixture-config', 'executionProfile': {'profileId': 'fixture-profile'}}
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / 'before.json', Path(directory) / 'after.json'
            before.write_text(json.dumps(receipt), encoding='utf-8')
            after.write_text(json.dumps(receipt), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'different JVM processes'):
                verify_instances(before, after)
            after.write_text(json.dumps({**receipt, 'processId': 1001}), encoding='utf-8')
            self.assertTrue(verify_instances(before, after)['covered'])
            after.write_text(json.dumps({**receipt, 'processId': 1001, 'sourceHead': 'other-head'}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'rebuilt JVM sourceHead'):
                verify_instances(before, after)

    def test_selection_uses_frozen_cspec_identity_prefix(self):
        binding = read(PROFILE)['overlayBinding']
        selection = {
            'schemaVersion': 'cspec-selection-v1', 'canonicalizationProfile': 'varvis-json-c14n-v1',
            'contextId': 'context-sha256-' + 'a' * 64,
            'overlayRegistryId': binding['registryId'], 'overlayRegistrySha256': binding['registrySha256'],
            'policyId': 'CSPEC_EXACT_CONTEXT_SELECTION_V1', 'outcome': 'SINGLE_SELECTABLE_MATCH',
            'selectedOverlayId': binding['overlayId'], 'selectedOverlaySha256': binding['overlaySha256'],
            'candidateOverlayIds': [binding['overlayId']],
            'conflict': {'status': 'NONE', 'conflictIds': []},
            'review': {'status': 'NOT_REQUIRED', 'decision': 'NONE', 'reviewRecordIds': []},
        }
        selection['selectionId'] = 'cspec-selection-sha256-' + e1.canonical_sha256(e1.cspec_selection_identity_projection(selection))
        before = copy.deepcopy(selection)
        verify_identity(selection, 'cspec-selection-v1', 'selectionId', e1.cspec_selection_identity_projection, e1.ContractSchemas())
        self.assertEqual(selection, before)
        selection['selectedOverlaySha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'selectionId'):
            verify_identity(selection, 'cspec-selection-v1', 'selectionId', e1.cspec_selection_identity_projection, e1.ContractSchemas())


if __name__ == '__main__':
    unittest.main()
