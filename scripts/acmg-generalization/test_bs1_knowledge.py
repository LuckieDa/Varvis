import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import bs1_knowledge as knowledge


class KnowledgeTest(unittest.TestCase):
    def test_gencc_preserves_assertions_and_new_submission_identity(self):
        raw = b'sgc_id,version_number,gene_curie,disease_curie,moi_curie,classification_title\nSGC-1,2,HGNC:1,MONDO:0000001,HP:0000006,Definitive\n'
        row = list(knowledge.gencc(raw))[0]
        self.assertEqual('AUTOSOMAL_DOMINANT', row['moi'])
        self.assertEqual('2', row['original']['version_number'])
        with self.assertRaisesRegex(ValueError, 'DUPLICATE'):
            list(knowledge.gencc(raw + raw.splitlines()[1] + b'\n'))
        with self.assertRaisesRegex(ValueError, 'HEADER'):
            list(knowledge.gencc(raw.replace(b'sgc_id,version_number', b'uuid,version_number')))
        with self.assertRaisesRegex(ValueError, 'INCOMPLETE_ROW'):
            list(knowledge.gencc(raw + b'SGC-2,1,HGNC:2\n'))

    def test_orpha_does_not_turn_incidence_or_cases_into_prevalence(self):
        raw = b'<JDBOR><DisorderList><Disorder><OrphaCode>1</OrphaCode><Name>X</Name><PrevalenceList><Prevalence><PrevalenceType><Name>Annual incidence</Name></PrevalenceType><ValMoy>3</ValMoy></Prevalence></PrevalenceList></Disorder></DisorderList></JDBOR>'
        rows = list(knowledge.orphanet(raw))
        self.assertEqual('Annual incidence', rows[0]['type'])
        self.assertEqual('3', rows[0]['mean'])
        self.assertNotIn('fraction', rows[0])
        with self.assertRaisesRegex(ValueError, 'ENTITIES'):
            list(knowledge.orphanet(b'<!DOCTYPE foo>' + raw))

    def setup_manifest(self, directory):
        rows = [{'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'moi': 'AD', 'classification': 'Definitive'},
                {'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'moi': 'AD', 'classification': 'Refuted'},
                {'hgncId': 'HGNC:2', 'mondoId': 'MONDO:0000002', 'moi': 'AR', 'classification': 'Strong'},
                {'hgncId': 'HGNC:3', 'mondoId': 'OMIM:123456', 'moi': 'AD', 'classification': 'Strong'},
                {'hgncId': 'HGNC:4', 'mondoId': 'MONDO:0000004', 'moi': 'XL', 'classification': 'Strong'}]
        raw = json.dumps(rows).encode()
        (directory / 'source.json').write_bytes(raw)
        manifest = {'schemaVersion': 'bs1-knowledge-input-v1', 'version': 'SYNTHETIC', 'sources': [
            {'sourceId': 'synthetic', 'kind': 'CLINGEN_JSON', 'path': 'source.json', 'url': 'https://example.org/synthetic',
             'version': '1', 'sha256': hashlib.sha256(raw).hexdigest()}]}
        knowledge.write(directory / 'manifest.json', manifest)
        return directory / 'manifest.json'

    def test_conflicts_identity_and_missing_parameters_are_separate(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); manifest = self.setup_manifest(directory)
            report = knowledge.compile_manifest(manifest, directory / 'out')
            b = knowledge.read_json((directory / 'out/bundle.json').read_bytes())
            self.assertEqual(3, report['exactBackgrounds'])
            self.assertEqual(1, report['eligibleRelationshipBackgrounds'])
            self.assertEqual(0, report['automaticScientificApprovals'])
            self.assertEqual(['BS1_RELATIONSHIP_CONFLICT', 'BS1_DISEASE_PARAMETERS_MISSING', 'BS1_MODEL_MOI_UNSUPPORTED'],
                             [r['reasonCode'] for r in b['backgrounds']])
            self.assertEqual([], b['records'])
            with self.assertRaises(FileExistsError):
                knowledge.compile_manifest(manifest, directory / 'out')

    def test_wrong_raw_pin_and_duplicate_json_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); manifest = self.setup_manifest(directory)
            with (directory / 'source.json').open('ab') as output:
                output.write(b' ')
            with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'):
                knowledge.compile_manifest(manifest, directory / 'out')
            self.assertFalse((directory / 'out').exists())
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_JSON_KEY'):
            knowledge.read_json(b'{"value":1,"value":2}')

    def test_cspec_only_released_sources_produce_non_executable_barriers(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            entity = {'entId': 'GN-TEST', 'entContent': {'version': '1', 'states': [{'name': 'Released', 'current': True}]},
                      'ld': {'Gene': [{'entContent': {'HGNC': {'symbol': 'X', 'hgnc_id': 'HGNC:1'}}}],
                             'CriteriaCode': [{'entContent': {'label': 'BS1', 'gene': ['X'], 'disease': ['MONDO:0000001']}}]}}
            raw = json.dumps({'status': {'code': 200}, 'data': [entity]}).encode()
            (directory / 'raw.json').write_bytes(raw)
            knowledge.write(directory / 'manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': '1', 'sources': [
                {'sourceId': 'cspec', 'kind': 'CSPEC_RAW', 'path': 'raw.json', 'url': 'https://example.org/synthetic', 'version': '1', 'sha256': knowledge.sha(raw)}]})
            report = knowledge.compile_manifest(directory / 'manifest.json', directory / 'out')
            self.assertEqual(1, report['expertScopeBarriers']); self.assertEqual(0, report['curatedRecordCandidates'])


if __name__ == '__main__':
    unittest.main()
