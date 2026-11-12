import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import acquire_uniprot_snapshot as a


class AcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.calls = []

    def runner(self, accession, directory, timeout):
        self.calls.append(accession)
        data = b'{"synthetic":"TEST_ONLY"}'
        (directory / (accession + '.body')).write_bytes(data)
        return dict(accession=accession, success=True, status=200, headers='X-UniProt-Release: 2026_03\r\n',
                    url=f'https://rest.uniprot.org/uniprotkb/{accession}.json', startedAt=a.stamp(), finishedAt=a.stamp(),
                    bytes=len(data), transferredBytes=len(data), sha256=a.sha(data))

    def test_fixed_set_and_native_bytes(self):
        output = self.root / 'new'
        result = a.acquire(['P12345', 'P12346'], output, runner=self.runner)
        self.assertEqual('ACQUIRED', result['status'])
        self.assertEqual(['P12345', 'P12346'], self.calls)
        self.assertEqual(2, len(json.loads((output / 'acquisition.json').read_bytes())))
        self.assertEqual(2 * (a.MAX_RECORD + 1), result['reservedBodyByteBudget'])

    def test_no_overwrite_or_retry(self):
        output = self.root / 'new'
        a.acquire(['P12345'], output, runner=self.runner)
        with self.assertRaises(FileExistsError):
            a.acquire(['P12345'], output, runner=self.runner)
        self.assertEqual(['P12345'], self.calls)

    def test_invalid_requests_before_any_directory_or_network(self):
        for values in [[], ['../ABC'], ['P12345', 'P12345'], ['x'], [5], ['P12345'] * 51, {'a': 1}]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                a.acquire(values, self.root / 'never', runner=self.runner)
        self.assertEqual([], self.calls)
        self.assertFalse((self.root / 'never').exists())

    def test_cooldown_stops_next_request(self):
        for status in [429, 503]:
            def fail(accession, directory, seconds):
                self.calls.append(accession)
                return dict(accession=accession, success=False, status=status, error='TEST_ONLY')
            result = a.acquire(['P12345', 'P12346'], self.root / str(status), runner=fail)
            self.assertEqual(['P12345'], result['attempted'])
            self.assertEqual('P12346', result['unattempted'][0]['accession'])
            self.assertEqual('PARTIAL', result['status'])

    def test_mixed_release_preserves_native_attempt_and_stops(self):
        def run(accession, directory, seconds):
            item = self.runner(accession, directory, seconds)
            if accession == 'P12346':
                item['headers'] = 'X-UniProt-Release: 2025_01\n'
            return item
        output = self.root / 'mix'
        result = a.acquire(['P12345', 'P12346', 'P12347'], output, runner=run)
        self.assertEqual(1, result['successfulRecords'])
        self.assertFalse((output / 'P12346.json').exists())
        self.assertTrue((output / 'attempts/P12346.body').exists())
        self.assertEqual(1, len(result['unattempted']))

    def test_seed_reused_only_with_pins(self):
        first = self.root / 'first'
        a.acquire(['P12345'], first, runner=self.runner)
        data = (first / 'P12345.json').read_bytes()
        manifest = dict(schemaVersion='uniprot-protein-region-index-v1', acquisitionSha256=a.sha((first / 'acquisition.json').read_bytes()),
                        entries=[dict(accession='P12345', file='P12345.json', bytes=len(data), sha256=a.sha(data))])
        raw = a.encode(manifest)
        (first / 'manifest.json').write_bytes(raw)
        result = a.acquire(['P12345', 'P12346'], self.root / 'second', first, a.sha(raw), runner=self.runner)
        self.assertEqual(['P12345'], result['reused'])
        self.assertEqual(['P12345', 'P12346'], self.calls)
        (first / 'P12345.json').write_bytes(b'different')
        with self.assertRaises(ValueError):
            a.acquire(['P12345'], self.root / 'third', first, a.sha(raw), runner=self.runner)

    def test_total_byte_budget_reserves_failed_transfers_too(self):
        def run(accession, directory, seconds):
            return dict(accession=accession, success=False, transferredBytes=0, error='TEST_ONLY')
        with patch.object(a, 'MAX_TOTAL', a.MAX_RECORD + 1):
            result = a.acquire(['P12345', 'P12346'], self.root / 'budget', runner=run)
        self.assertEqual(['P12345'], result['attempted'])
        self.assertEqual(1, len(result['unattempted']))

    def test_total_time_budget_before_next_attempt(self):
        ticks = iter([0, 0, 999])
        result = a.acquire(['P12345', 'P12346'], self.root / 'time', runner=self.runner, clock=lambda: next(ticks))
        self.assertEqual(['P12345'], result['attempted'])
        self.assertEqual(1, len(result['unattempted']))

    def test_worker_timeout_exposes_reaping_and_preserves_partial(self):
        (self.root / 'P12345.body').write_bytes(b'partial')
        with patch.object(a.subprocess, 'run', side_effect=subprocess.TimeoutExpired('TEST_ONLY', 1)):
            result = a.run_worker('P12345', self.root, 1)
        self.assertFalse(result['success'])
        self.assertEqual('TOTAL_RECORD_TIMEOUT', result['error'])
        self.assertTrue(result['workerReaped'])
        self.assertEqual(7, result['transferredBytes'])

    def test_redirect_not_followed(self):
        self.assertIsNone(a.NoRedirect().redirect_request(None, None, 302, None, None, 'https://untrusted.example/'))


if __name__ == '__main__':
    unittest.main()
