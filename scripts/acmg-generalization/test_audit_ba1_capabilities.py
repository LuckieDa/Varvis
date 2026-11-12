import copy
from decimal import Decimal
import json
from pathlib import Path
import tempfile
import unittest

import audit_ba1_capabilities as audit
import import_cspec_catalog as compiler
from test_import_cspec_catalog import FakeClient, specimen
from cspec_ba1_semantics import compile_frequency


class CapabilityAuditTest(unittest.TestCase):
    def test_new_report_bytes_are_utf8_lf_and_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'report.json'
            audit.write_report(path, {'label': '\u6761\u4ef6', 'conditions': []})
            raw = path.read_bytes()
            self.assertNotIn(b'\r', raw)
            self.assertEqual('{\n  "conditions": [],\n  "label": "\u6761\u4ef6"\n}\n'.encode('utf-8'), raw)
            with self.assertRaises(FileExistsError):
                audit.write_report(path, {})

    def test_finite_grammar_keeps_unsupported_operators_and_extra_conditions_blocked(self):
        source = {'url': 'https://example.test/synthetic', 'sha256': 'b' * 64}
        text = 'gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.'
        self.assertEqual(compile_frequency(text, source), compile_frequency(text.replace(' ', '  \n '), source))
        for operator in ('<', '<=', '=', '!='):
            self.assertIsNone(compile_frequency(text.replace('>=', operator), source))
        for extra in (' Only in adults.', ' Except variant X.', ' Use alternative ancestry.'):
            self.assertIsNone(compile_frequency(text + extra, source))

    def test_frozen_input_determinism_counts_and_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'capture'
            entities = [specimen(1), specimen(2)]
            entities[1]['ld']['RuleSet'][0]['entContent']['genes'][0].pop('preferredTranscript')
            entities[1]['ld']['CriteriaCode'][0]['entContent']['instructionsToUse'] = 'Unknown restriction'
            manifest = compiler.capture_batch(FakeClient(root, entities))
            manifest_path = root / 'capture-manifest.json'
            manifest_path.write_bytes(compiler.canonical(manifest))
            sha = compiler.file_sha(manifest_path)
            snapshot = compiler.compile_snapshot(root, sha)
            catalog_path = root / 'candidate.json'
            catalog_path.write_bytes(compiler.canonical(snapshot))
            before = {p.name: compiler.file_sha(p) for p in root.iterdir() if p.is_file()}
            first = audit.audit(catalog_path, manifest_path, 'CANDIDATE_PENDING_REVIEW')
            self.assertEqual(first, audit.audit(catalog_path, manifest_path, 'CANDIDATE_PENDING_REVIEW'))
            self.assertEqual(before, {p.name: compiler.file_sha(p) for p in root.iterdir() if p.is_file()})
            self.assertEqual(2, first['entryCount'])
            self.assertEqual(1, first['entriesWithBlockers'])
            self.assertEqual(2, first['reasonOccurrenceCount'])
            self.assertEqual('REVIEW_PUBLICATION_PENDING', first['rows'][0]['capabilityStatus'])
            self.assertFalse(any(r['capabilityStatus'] == 'ACTIVE_SUPPORTED' for r in first['rows']))
            self.assertEqual(2, audit.compare(first, copy.deepcopy(first))['matchedCount'])
            with self.assertRaisesRegex(compiler.Invalid, 'PUBLICATION_REVIEW_STATUS_MISMATCH'):
                audit.audit(catalog_path, manifest_path, 'ACTIVE')

    def test_actual_python_compiled_java_fixture_and_decimal_expectations(self):
        path = Path(__file__).resolve().parents[2] / 'varvis-server/src/test/resources/acmg/ba1-python-compiled-transfer.json'
        fixture = json.loads(path.read_bytes())
        self.assertEqual(compiler.file_sha(Path(compiler.__file__)), fixture['compilerSha256'])
        baseline = [r for r in fixture['rows'] if not r['ba1']['conditions']]
        self.assertEqual(20, len(baseline))
        self.assertEqual(180, sum(len(r['cases']) for r in baseline))
        self.assertEqual(2, len([r for r in fixture['rows'] if r['ba1']['conditions']]))
        for row in fixture['rows']:
            scope = compiler.normalize_scopes(row['rawEntity'])[0]
            self.assertEqual(scope, row['scope'])
            compiled = compiler.compile_ba1(row['rawEntity'], row['ba1']['sourceRefs'][0], scope, row.get('semanticReview'))
            self.assertEqual(compiled, row['ba1'])
            for case in row['cases']:
                value, threshold = Decimal(case['faf']), Decimal(compiled['value'])
                count, minimum = Decimal(case['an']), Decimal(compiled['minimumAn'])
                qualified = count > minimum if compiled['minimumAnComparator'] == 'GREATER_THAN' else count >= minimum
                met = value > threshold if compiled['comparator'] == 'GREATER_THAN' else value >= threshold
                self.assertEqual('NOT_EVALUATED' if not qualified else 'MET' if met else 'NOT_MET', case['expected'])


if __name__ == '__main__':
    unittest.main()
