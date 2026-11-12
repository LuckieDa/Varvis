"""Offline transport boundaries; no official requests are issued by these tests."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error

from literature_search_transport import SearchTransport, worker, url_for, decode, MAX_BODY, sha
from test_discover_public_literature import response, row


class TransportTest(unittest.TestCase):
    def test_atomic_receipt_in_deep_task_folder(self):
        from literature_search_transport import atomic_json
        folder=self.root/('a'*64)/('b'*64)/'paper'/'acquisition'
        folder.mkdir(parents=True)
        target=folder/'acquisition.json'
        expected={'source':'public', 'evidence':['原文'], 'complete':True}
        atomic_json(target,expected)
        self.assertEqual(expected,json.loads(target.read_bytes()))
        atomic_json(target,{**expected,'complete':False})
        self.assertFalse(json.loads(target.read_bytes())['complete'])
        self.assertEqual(['acquisition.json'],[p.name for p in folder.iterdir()])

    def test_clinvar_exact_cache_and_429_shared_cooldown(self):
        from test_pm3_clinvar_supply import RAW
        from pm3_clinvar_supply import vcv_endpoint
        url,_=vcv_endpoint('VCV000000123')
        self.cache.mkdir()
        (self.cache/(sha(RAW)+'.body')).write_bytes(RAW)
        (self.cache/(sha(url.encode())+'.json')).write_text(json.dumps({
            'url':url,'sha256':sha(RAW),'retrievedEpoch':time.time(),'retrievedAt':'synthetic'}))
        with self.transport(False) as transport,patch('literature_search_transport.subprocess.run') as run:
            self.assertEqual(RAW,transport.get_clinvar('VCV000000123'));run.assert_not_called()
        def limited(args,stdout,**kw):
            stdout.write(b'{"status":"FAILED","reasonCode":"LITERATURE_SEARCH_RATE_LIMIT","cooldownSeconds":600}')
            stdout.flush();return subprocess.CompletedProcess(args,0)
        with self.transport() as transport,patch('literature_search_transport.subprocess.run',side_effect=limited):
            with self.assertRaisesRegex(ValueError,'RATE_LIMIT'):transport.get_clinvar('VCV000000124')
        with self.transport() as transport,patch('literature_search_transport.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError,'COOLDOWN'):transport.get_clinvar('VCV000000125')
            run.assert_not_called()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache, self.archive = self.root / 'cache', self.root / 'archive'

    def transport(self, online=True):
        return SearchTransport(self.cache, self.archive, online)

    def seed(self, raw, age=0):
        self.cache.mkdir(exist_ok=True)
        pin = sha(raw)
        (self.cache / (pin + '.body')).write_bytes(raw)
        (self.cache / (sha(url_for('EXAMPLE').encode()) + '.json')).write_text(json.dumps({
            'url': url_for('EXAMPLE'), 'sha256': pin, 'retrievedEpoch': time.time() - age, 'retrievedAt': 'synthetic'}))

    def test_fresh_positive_and_negative_cache_offline(self):
        for raw in [response(), response([row()])]:
            self.seed(raw)
            with self.transport(False) as transport, patch('literature_search_transport.subprocess.run') as run:
                self.assertEqual(raw, transport.get('EXAMPLE'))
                self.assertEqual(0, transport.requests)
                run.assert_not_called()

    def test_negative_expires_sooner_than_positive(self):
        self.seed(response(), 86401)
        with self.transport(False) as transport, self.assertRaisesRegex(ValueError, 'NETWORK_DISABLED'):
            transport.get('EXAMPLE')
        self.seed(response([row()]), 86401)
        with self.transport(False) as transport:
            self.assertEqual(response([row()]), transport.get('EXAMPLE'))

    def test_cache_corruption_does_not_fetch_silently(self):
        raw = response()
        self.seed(raw)
        (self.cache / (sha(raw) + '.body')).write_bytes(b'bad')
        with self.transport() as transport, patch('literature_search_transport.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'CACHE_INVALID'): transport.get('EXAMPLE')
            run.assert_not_called()

    def test_lock_blocks_concurrent_or_abandoned_writer(self):
        with self.transport():
            with self.assertRaisesRegex(ValueError, 'BUSY_OR_INTERRUPTED'):
                with self.transport(): pass
        self.assertFalse((self.cache / 'discovery.lock').exists())

    def test_offline_and_request_time_caps_never_spawn(self):
        for mode in ['offline', 'count', 'time']:
            with self.subTest(mode=mode), self.transport(mode != 'offline') as transport:
                if mode == 'count': transport.requests = 20
                if mode == 'time': transport.started -= 121
                with patch('literature_search_transport.subprocess.run') as run:
                    with self.assertRaisesRegex(ValueError, 'NETWORK_DISABLED|REQUEST_BUDGET|TOTAL_TIMEOUT'):
                        transport.get('EXAMPLE')
                    run.assert_not_called()

    def test_timeout_stops_later_requests(self):
        with self.transport() as transport, patch('literature_search_transport.subprocess.run',
            side_effect=subprocess.TimeoutExpired('synthetic', 20)) as run:
            for query in ['EXAMPLE', 'OTHER']:
                with self.assertRaisesRegex(ValueError, 'TIMEOUT'): transport.get(query)
            self.assertEqual(1, run.call_count)

    def test_429_stops_and_shares_cooldown_without_reading_body(self):
        def fake(args, stdout, **kw):
            stdout.write(b'{"status":"FAILED","reasonCode":"LITERATURE_SEARCH_RATE_LIMIT","cooldownSeconds":600}')
            stdout.flush()
            return subprocess.CompletedProcess(args, 0)
        with self.transport() as transport, patch('literature_search_transport.subprocess.run', side_effect=fake):
            with self.assertRaisesRegex(ValueError, 'RATE_LIMIT'): transport.get('EXAMPLE')
        with self.transport() as transport, patch('literature_search_transport.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'COOLDOWN'): transport.get('OTHER')
            run.assert_not_called()

    def test_worker_rejects_bad_json_before_success(self):
        class Response:
            status = 200
            headers = {}
            def __enter__(s): return s
            def __exit__(s, *a): pass
            def read(s, n):
                if getattr(s, 'sent', False): return b''
                s.sent = True
                return b'<html>Error page</html>'
        with patch('literature_search_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = Response()
            result = worker('EXAMPLE', '*', self.root / 'raw')
        self.assertEqual('LITERATURE_SEARCH_INVALID_RESPONSE', result['reasonCode'])

    def test_worker_accepts_cli_string_path(self):
        raw = response([row()])
        class Response:
            status = 200
            headers = {}
            def __enter__(s): return s
            def __exit__(s, *a): pass
            def read(s, n):
                if getattr(s, 'sent', False): return b''
                s.sent = True
                return raw
        with patch('literature_search_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = Response()
            result = worker('EXAMPLE', '*', str(self.root / 'raw'))
        self.assertEqual('OK', result['status'])
        self.assertEqual(raw, (self.root / 'raw').read_bytes())

    def test_unknown_worker_failure_stops_following_transfers(self):
        with self.transport() as transport, patch('literature_search_transport.subprocess.run',
                return_value=subprocess.CompletedProcess([], 1)) as run:
            for query in ['EXAMPLE', 'OTHER']:
                with self.assertRaisesRegex(ValueError, 'WORKER_FAILURE'): transport.get(query)
            self.assertEqual(1, run.call_count)

    def test_worker_never_writes_more_than_byte_limit(self):
        class Response:
            status = 200
            headers = {}
            def __enter__(s): return s
            def __exit__(s, *a): pass
            def read(s, n): return b'x' * n
        with patch('literature_search_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = Response()
            result = worker('EXAMPLE', '*', self.root / 'raw')
        self.assertEqual('LITERATURE_SEARCH_BODY_LIMIT', result['reasonCode'])
        self.assertEqual(MAX_BODY, (self.root / 'raw').stat().st_size)

    def test_http_429_retry_after_respected(self):
        error = urllib.error.HTTPError(url_for('EXAMPLE'), 429, 'rate', {'Retry-After': '900'}, None)
        with patch('literature_search_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.side_effect = error
            result = worker('EXAMPLE', '*', self.root / 'raw')
        self.assertEqual(900, result['cooldownSeconds'])
        self.assertFalse((self.root / 'raw').exists())

    def test_success_persists_raw_then_reuses_cache(self):
        raw = response([row()])
        def fake(args, stdout, **kw):
            Path(args[-1]).write_bytes(raw)
            stdout.write(b'{"status":"OK"}')
            stdout.flush()
            return subprocess.CompletedProcess(args, 0)
        with self.transport() as transport, patch('literature_search_transport.subprocess.run', side_effect=fake) as run:
            self.assertEqual(raw, transport.get('EXAMPLE'))
            self.assertEqual(raw, transport.get('EXAMPLE'))
            self.assertEqual(1, run.call_count)
            self.assertEqual('CACHE_HIT', transport.events[-1]['status'])

    def test_invalid_shape_and_cursor_and_duplicate_keys(self):
        for raw in [b'{}', b'{"hitCount":true,"resultList":{"result":[]}}',
                    b'{"hitCount":0,"hitCount":0,"resultList":{"result":[]}}',
                    response([row()], 0), b'{"hitCount":0,"resultList":{"result":[]},"nextCursorMark":7}']:
            with self.subTest(raw=raw), self.assertRaises(ValueError): decode(raw)
        with self.assertRaises(ValueError): url_for('a\nb')
        self.assertTrue(url_for('a&b').startswith('https://www.ebi.ac.uk/europepmc/webservices/rest/search?'))
        self.assertIn('a%26b', url_for('a&b'))


if __name__ == '__main__': unittest.main()
