"""Synthetic offline requests: ordering, fallback, privacy, exact source linkage."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from discover_public_literature import discover, targets_from
from test_discover_public_literature import Fake, response, row, target
from test_literature_source_assertions import RECORD, VCV
from literature_allele_identity import read_record
from model_literature_contract import canonical, digest
from workbench_literature_targets import build_targets


class SignalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); (self.root / 'local').mkdir()
        (self.root / 'local/record.xml').write_bytes(RECORD)
        self.context = {'identity': {'clinvar': [{'path': 'local/record.xml', 'sha256': digest(RECORD), 'accession': VCV}]}}
        record = read_record(RECORD, digest(RECORD), VCV)
        key = record['allele']
        self.selected = [{'geneSymbol': record['geneSymbol'], 'variantAliases': ['c.123A>G', 'p.Thr41Ala'],
                          'allele': {'genomeAssembly': key['assembly'], 'chromosome': key['contig'],
                                     'position': key['position'], 'reference': key['reference'], 'alternate': key['alternate']}}]

    def build(self):
        targets = build_targets(self.selected, self.root, self.context, self.root)
        request = self.root / 'request.json'
        request.write_bytes(canonical({'schemaVersion': 'denovo-literature-discovery-input-v1', 'targets': targets}))
        return targets_from(request)

    def test_formal_allele_links_pinned_clinvar_citations_and_hgvs(self):
        actual = self.build()[0]
        self.assertEqual(['999999997'], actual['citationPmids'])
        self.assertIn('NM_999999997.1:c.123A>G', actual['variantAliases'])
        self.assertIn('p.T41A', actual['variantAliases'])
        self.assertEqual('SELECTED_VARIANT_TERMS', actual['externalScope'])

    def test_other_allele_or_assembly_never_borrows_pmid(self):
        for field, value in [('position', 42), ('genomeAssembly', 'GRCh37'), ('alternate', 'T')]:
            before = deepcopy(self.selected)
            self.selected[0]['allele'][field] = value
            self.assertEqual([], self.build()[0]['citationPmids'])
            self.selected = before

    def test_changed_source_is_error_not_no_record(self):
        (self.root / 'local/record.xml').write_bytes(RECORD + b' ')
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'):
            self.build()

    def test_no_clinvar_keeps_variant_search(self):
        self.context = None
        actual = self.build()[0]
        self.assertTrue(actual['variantAliases'])
        self.assertEqual([], actual['citationPmids'])
        self.assertIn('CLINVAR_RECORDS_NOT_CONFIGURED', actual['sourceIssues'])

    def test_patient_fields_and_query_operators_are_rejected(self):
        self.selected[0]['patientName'] = 'PRIVATE'
        with self.assertRaisesRegex(ValueError, 'SCHEMA'): self.build()
        del self.selected[0]['patientName']
        self.selected[0]['variantAliases'] = ['x" OR ALL:*']
        with self.assertRaisesRegex(ValueError, 'SIGNAL_INVALID'): self.build()

    def test_workbench_request_runs_actual_discovery_and_no_model_without_candidates(self):
        from public_literature_workflow import run, VERSION
        config = {'schemaVersion': VERSION, 'enabled': True, 'projectRoot': str(self.root),
                  'library': 'local/library', 'searchCache': 'local/cache',
                  'modelBudget': 'local/budget', 'modelConfig': 'local/model.json',
                  'allowPublicNetwork': False, 'allowModel': False}
        (self.root / 'local/model.json').write_bytes(b'{}')
        config_path = self.root / 'config.json'; config_path.write_bytes(canonical(config))
        request = self.root / 'request.json'
        request.write_bytes(canonical({'schemaVersion': VERSION, 'genes': ['EXAMPLE'],
                                      'selectionSha256': '1' * 64, 'searchTargets': self.selected}))
        transport = Fake(response())
        with patch('discover_public_literature.SearchTransport') as search, \
             patch('public_literature_workflow.DownloadTransport') as download, \
             patch('public_literature_workflow.acquire', return_value={'networkRequests': 0, 'papers': []}), \
             patch('public_literature_workflow.process_paper') as extract:
            search.return_value.__enter__.return_value = transport
            result = run(request, self.root / 'result.json', config_path)
            self.assertFalse(search.call_args.args[2])
            self.assertFalse(download.call_args.args[2])
            extract.assert_not_called()
        self.assertEqual(['LITVAR_VARIANT'], [q['tier'] for q in result['discovery']['queries']])
        self.assertTrue(result['discovery']['litvarEnabled'])
        self.assertIsNone(result['discovery']['targets'][0]['fallbackReason'])
        self.assertEqual('LITVAR_ONLY', result['discovery']['searchPolicy'])
        self.assertEqual(0, result['modelCalls'])
        self.assertFalse(result['scientificExecution'])
        self.assertEqual([], result['papers'])


if __name__ == '__main__':
    unittest.main()
