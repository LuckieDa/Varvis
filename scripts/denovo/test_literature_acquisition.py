"""Finite synthetic source/transport fixtures; no network or clinical truth claims."""
import io
import json
from pathlib import Path
import stat
import struct
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

from acquire_public_literature import acquire, source_bundle, verified_metadata
from discover_public_literature import paper
from literature_attachments import members, parse_attachment
from paper_download_transport import DownloadTransport, url_for, worker
from paper_library_registration import encoded, register
from prepare_literature import prepare, sha
from select_public_papers import select


def zipped(items):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', compression=zipfile.ZIP_STORED) as out:
        for name, raw in items:
            out.writestr(name, raw)
    return buf.getvalue()


def jats(supp=False, pmcid='PMC123'):
    return (f'<article xmlns:xlink="http://www.w3.org/1999/xlink"><front><article-meta>'
            f'<article-id pub-id-type="pmcid">{pmcid}</article-id><article-id pub-id-type="pmid">456</article-id>'
            '<article-id pub-id-type="doi">10.1234/test</article-id><title-group><article-title>Test source</article-title>'
            '</title-group></article-meta></front><body><p>A public source.</p>' +
            ('<supplementary-material xlink:href="table.csv"/>' if supp else '') + '</body></article>').encode()


def xlsx(extra=(), relation=None):
    ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    rel = relation or f'<Relationship Id="s1" Type="{office}/worksheet" Target="worksheets/sheet1.xml"/>'
    return zipped([
        ('xl/workbook.xml', f'<workbook xmlns="{ns}" xmlns:r="{office}"><workbookPr date1904="1"/>'
         '<sheets><sheet name="Cases" state="hidden" r:id="s1"/></sheets></workbook>'),
        ('xl/_rels/workbook.xml.rels', f'<Relationships>{rel}</Relationships>'),
        ('xl/worksheets/sheet1.xml', f'<worksheet xmlns="{ns}"><sheetData><row r="1" hidden="1">'
         '<c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>p.Arg1Gly</t></is></c>'
         '<c r="C1"><v>0</v></c><c r="D1"/><c r="E1" s="1"><v>45200</v></c>'
         '<c r="F1"><f>C1+1</f><v>1</v></c></row></sheetData><mergeCells><mergeCell ref="A1:A2"/></mergeCells></worksheet>'),
        ('xl/sharedStrings.xml', f'<sst xmlns="{ns}"><si><t>SEPT1</t></si></sst>'),
        ('xl/styles.xml', f'<styleSheet xmlns="{ns}"><numFmts><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/>'
         '</numFmts><cellXfs><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>'), *extra])


class AttachmentTests(unittest.TestCase):
    def test_csv_multiline_blank_zero_and_formula_are_text(self):
        doc = parse_attachment('table.csv', b'gene,empty,n,formula\n"A\nB",,0,=1+2\n')
        self.assertEqual([c['text'] for c in doc['rows'][1]['cells']], ['A\nB', '', '0', '=1+2'])
        self.assertEqual(doc['rows'][1]['row'], 2)

    def test_tsv_utf16(self):
        self.assertEqual(parse_attachment('table.tsv', '基因\t材料'.encode('utf-16'))['rows'][0]['cells'][1]['text'], '材料')

    def test_bad_encoding_explicit(self):
        self.assertEqual(parse_attachment('table.csv', b'\x80')['status'], 'ARCHIVED_NOT_PARSED')

    def test_pdf_never_claimed_parsed(self):
        self.assertEqual(parse_attachment('sup.pdf', b'%PDF-')['reasonCode'], 'PAPER_ATTACHMENT_FORMAT_UNSUPPORTED')

    def test_xlsx_preserves_types_layout_and_formula(self):
        result = parse_attachment('table.xlsx', xlsx())
        sheet = result['sheets'][0]
        self.assertEqual(sheet['state'], 'hidden')
        self.assertEqual(sheet['mergedRanges'], ['A1:A2'])
        cells = sheet['cells']
        self.assertEqual([c['text'] for c in cells], ['SEPT1', 'p.Arg1Gly', '0', None, '45200', '1'])
        self.assertEqual(cells[-1]['use'], 'FORMULA_NOT_EVALUATED')
        self.assertEqual(cells[-1]['formula'], 'C1+1')
        self.assertEqual(cells[4]['use'], 'RAW_TYPED_VALUE_NOT_INTERPRETED')
        self.assertEqual(result['styles']['numberFormats'][0]['formatCode'], 'yyyy-mm-dd')

    def test_external_sheet_refused(self):
        raw = xlsx(relation='<Relationship Id="s1" Type="x/worksheet" Target="https://bad" TargetMode="External"/>')
        self.assertEqual(parse_attachment('x.xlsx', raw)['reasonCode'], 'PAPER_ATTACHMENT_EXTERNAL_OR_UNSUPPORTED_SHEET')

    def test_missing_workbook(self):
        self.assertEqual(parse_attachment('x.xlsx', zipped([('a', b'b')]))['reasonCode'], 'PAPER_ATTACHMENT_XLSX_WORKBOOK_REQUIRED')

    def test_macro_refused(self):
        self.assertEqual(parse_attachment('x.xlsx', xlsx([('xl/vbaProject.bin', b'x')]))['reasonCode'], 'PAPER_ATTACHMENT_XLSX_MACRO_UNSUPPORTED')

    def test_traversal_and_windows_paths(self):
        for name in ('../a', '/a', 'a\\b', 'C:a'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'ZIP_PATH_INVALID'):
                raw = zipped([(name.replace('\\', '/'), b'x')])
                if '\\' in name: raw = raw.replace(b'a/b', b'a\\b')
                members(raw)

    def test_duplicate_casefold(self):
        with self.assertRaisesRegex(ValueError, 'ZIP_DUPLICATE_NAME'):
            members(zipped([('A.csv', b'x'), ('a.csv', b'y')]))

    def test_symlink(self):
        link = zipfile.ZipInfo('link')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(ValueError, 'ZIP_LINK_OR_ENCRYPTION'):
            members(zipped([(link, b'../outside')]))

    def test_crc(self):
        raw = zipped([('a.csv', b'UNIQUE_VALUE')]).replace(b'UNIQUE_VALUE', b'BROKEN_VALUE')
        with self.assertRaises(zipfile.BadZipFile):
            members(raw)

    def test_forged_directory_count(self):
        raw = bytearray(zipped([('a', b'a'), ('b', b'b')]))
        eocd = raw.rfind(b'PK\x05\x06')
        struct.pack_into('<HH', raw, eocd + 8, 1, 1)
        with self.assertRaisesRegex(ValueError, 'ZIP_DIRECTORY_INVALID'):
            members(bytes(raw))

    def test_limits(self):
        with patch('literature_attachments.MAX_MEMBER', 2), self.assertRaisesRegex(ValueError, 'ZIP_MEMBER_LIMIT'):
            members(zipped([('a', b'123')]))
        with patch('literature_attachments.MAX_CELLS', 1):
            self.assertEqual(parse_attachment('a.csv', b'one,two')['reasonCode'], 'PAPER_ATTACHMENT_CELL_LIMIT')

    def test_expansion_budget_shared_across_packages(self):
        budget = [7]
        self.assertEqual(members(zipped([('a', b'1234')]), budget), [('a', b'1234')])
        self.assertEqual(budget, [3])
        with self.assertRaisesRegex(ValueError, 'ZIP_EXPANDED_LIMIT'):
            members(zipped([('b', b'1234')]), budget)


class FileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        self.library = self.root / 'library'
        self.library.mkdir()

    def candidate(self, oa='Y', supplement='N'):
        row = {'source': 'MED', 'id': '456', 'pmcid': 'PMC123', 'doi': '10.1234/test',
               'title': 'Test source', 'isOpenAccess': oa, 'hasSuppl': supplement}
        raw = encoded({'hitCount': 1, 'resultList': {'result': [row]}})
        (self.cache / (sha(raw) + '.body')).write_bytes(raw)
        candidate = paper(row)
        candidate['origins'] = [{'kind': 'SEARCH_RESPONSE', 'sha256': sha(raw), 'queryId': 'q1'}]
        return candidate

    def plan(self, candidates):
        raw = encoded({'schemaVersion': 'denovo-literature-discovery-v1', 'candidates': candidates})
        path = self.root / 'discovery.json'
        path.write_bytes(raw)
        return path, sha(raw)

    def fake(self, source=None, supplement=None, failure=None):
        cache = self.cache
        class Fake:
            requests = 0
            transferred = 0
            events = []
            def get(inner, pmcid, kind):
                inner.requests += 1
                if failure: raise ValueError(failure)
                return source if kind == 'fullTextXML' else supplement
            def get_open_access(inner, pmcid, _candidate):
                return inner.get(pmcid, 'fullTextXML'), url_for(pmcid, 'fullTextXML')
        fake = Fake()
        fake.cache = cache
        return fake


class AcquisitionTests(FileTest):
    def test_pvs1_sources_reuse_without_registering_as_denovo_or_scientific_approval(self):
        path, pin = self.plan([self.candidate()])
        first = acquire(path, pin, self.library, self.fake(jats()), self.root/'pvs1-first', criteria=('PVS1',))
        self.assertEqual('REGISTERED', first['papers'][0]['status'])
        self.assertFalse(first['businessPublishable'])
        self.assertFalse((self.library/'evidence/PS2/index.json').exists())
        transport = self.fake(failure='UNEXPECTED_NETWORK')
        again = acquire(path, pin, self.library, transport, self.root/'pvs1-again', criteria=('PVS1',))
        self.assertEqual('REGISTERED', again['papers'][0]['status'])
        self.assertEqual(0, transport.requests)
        from bundle_model_contract import load
        loaded = load(self.library, 'PMC123', again['papers'][0]['registration']['bundleSha256'], criterion='PVS1')
        self.assertEqual('PMC123', loaded['pmcid'])

    def test_pp1_registration_and_local_reuse_through_actual_acquirer(self):
        path,pin=self.plan([self.candidate()])
        first=acquire(path,pin,self.library,self.fake(jats()),self.root/'pp1-first',criteria=('PP1',))
        self.assertEqual('REGISTERED',first['papers'][0]['status'])
        self.assertTrue((self.library/'evidence/PP1/index.json').exists())
        self.assertFalse((self.library/'evidence/PS2/index.json').exists())
        transport=self.fake(failure='UNEXPECTED_NETWORK')
        again=acquire(path,pin,self.library,transport,self.root/'pp1-again',criteria=('PP1',))
        self.assertEqual('REGISTERED',again['papers'][0]['status'])
        self.assertEqual(0,transport.requests)
        from bundle_model_contract import load
        loaded=load(self.library,'PMC123',again['papers'][0]['registration']['bundleSha256'],criterion='PP1')
        self.assertEqual('PMC123',loaded['pmcid'])

    def test_pm3_registration_and_local_reuse_do_not_require_denovo_index(self):
        path, pin = self.plan([self.candidate()])
        first = acquire(path,pin,self.library,self.fake(jats()),self.root/'pm3-first',criteria=('PM3',))
        self.assertEqual('REGISTERED',first['papers'][0]['status'])
        self.assertTrue((self.library/'evidence/PM3/index.json').exists())
        self.assertFalse((self.library/'evidence/PS2/index.json').exists())
        transport = self.fake(failure='UNEXPECTED_NETWORK')
        again = acquire(path,pin,self.library,transport,self.root/'pm3-again',criteria=('PM3',))
        self.assertEqual('REGISTERED',again['papers'][0]['status'])
        self.assertEqual(0,transport.requests)

    def test_register_fulltext_and_structured_supplement_idempotently(self):
        path, pin = self.plan([self.candidate(supplement='Y')])
        transport = self.fake(jats(True), zipped([('table.csv', b'a,b\n1,0'), ('info.pdf', b'%PDF-')]))
        first = acquire(path, pin, self.library, transport, self.root / 'first')
        item = first['papers'][0]
        self.assertEqual(item['status'], 'REGISTERED')
        self.assertEqual(item['supplementCoverage']['parsedAttachments'], 1)
        self.assertEqual(item['supplementCoverage']['unparsedAttachments'], 1)
        selected = select(self.library, ['PS2', 'PM6'], denovo=True)
        self.assertEqual(len(selected['documents']), 1)
        metadata = (self.library / 'papers/PMC123/metadata.json').read_bytes()
        indexes = [(self.library / f'evidence/{c}/index.json').read_bytes() for c in ('PS2', 'PM6')]
        again = acquire(path, pin, self.library, self.fake(None, transport.get('PMC123', 'supplementaryFiles')), self.root / 'again')
        self.assertEqual(again['papers'][0]['sourceKind'], 'REGISTERED_LOCAL_SOURCE')
        self.assertEqual(metadata, (self.library / 'papers/PMC123/metadata.json').read_bytes())
        self.assertEqual(indexes, [(self.library / f'evidence/{c}/index.json').read_bytes() for c in ('PS2', 'PM6')])

    def test_identity_mismatch_no_registration(self):
        path, pin = self.plan([self.candidate()])
        result = acquire(path, pin, self.library, self.fake(jats().replace(b'>456<', b'>789<')), self.root / 'out')
        self.assertIn('IDENTITY_MISMATCH', result['papers'][0]['reasonCode'])
        self.assertFalse((self.library / 'papers').exists())

    def test_non_oa_not_downloaded_even_if_candidate_edited(self):
        candidate = self.candidate(oa='N')
        candidate['openAccess'] = 'Y'
        path, pin = self.plan([candidate])
        transport = self.fake()
        result = acquire(path, pin, self.library, transport, self.root / 'out')
        self.assertEqual(result['papers'][0]['reasonCode'], 'PAPER_ACQUISITION_OPEN_ACCESS_NOT_CONFIRMED')
        self.assertEqual(transport.requests, 0)

    def test_bad_search_pin(self):
        candidate = self.candidate()
        (self.cache / (candidate['origins'][0]['sha256'] + '.body')).write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'SEARCH_PIN_MISMATCH'):
            verified_metadata(candidate, self.cache)

    def test_no_pmcid_and_retracted_skipped(self):
        a, b = self.candidate(), self.candidate()
        a['pmcid'] = None
        b['flags'] = ['RETRACTION_REQUIRES_REVIEW']
        path, pin = self.plan([a, b])
        fake = self.fake()
        result = acquire(path, pin, self.library, fake, self.root / 'out')
        self.assertEqual(fake.requests, 0)
        self.assertEqual([p['status'] for p in result['papers']], ['SKIPPED', 'SKIPPED'])

    def test_supplement_failure_keeps_valid_fulltext_and_gap(self):
        raw = jats(True)
        files, bundle = source_bundle(self.candidate(), raw, prepare(raw, 'PMC123', sha(raw)),
                                      self.fake(failure='PAPER_DOWNLOAD_NOT_AVAILABLE'), True)
        self.assertEqual(bundle['supplementCoverage']['reasonCode'], 'PAPER_DOWNLOAD_NOT_AVAILABLE')
        self.assertIn('PMC123.xml', files)

    def test_invalid_package_preserved_not_extracted(self):
        raw = jats(True)
        files, bundle = source_bundle(self.candidate(), raw, prepare(raw, 'PMC123', sha(raw)),
                                      self.fake(supplement=zipped([('../bad.csv', b'bad')])), True)
        self.assertEqual(bundle['supplementCoverage']['reasonCode'], 'PAPER_ATTACHMENT_ZIP_PATH_INVALID')
        self.assertEqual(len(bundle['attachments']), 0)
        self.assertNotIn('package', bundle)
        self.assertIn(bundle['unparsedPackage']['path'], files)

    def test_unmatched_file_does_not_invent_reference(self):
        raw = jats(True)
        unused, bundle = source_bundle(self.candidate(), raw, prepare(raw, 'PMC123', sha(raw)),
                                      self.fake(supplement=zipped([('other.csv', b'a,b')])), True)
        self.assertEqual(bundle['attachments'][0]['association'], 'OFFICIAL_PMCID_PACKAGE_ONLY')
        self.assertEqual(len(bundle['supplementCoverage']['unmatchedSourceUnits']), 1)

    def test_bibliography_pdf_not_mistaken_for_missing_supplement(self):
        raw = jats().replace(b'</article>', b'<back><ref-list><ref><ext-link xmlns:xlink="http://www.w3.org/1999/xlink" '
                            b'xlink:href="https://other.example/ref.pdf">Reference paper</ext-link></ref></ref-list></back></article>')
        transport = self.fake(failure='PAPER_DOWNLOAD_NOT_AVAILABLE')
        unused, bundle = source_bundle(self.candidate(), raw, prepare(raw, 'PMC123', sha(raw)), transport, False)
        self.assertEqual(transport.requests, 0)
        self.assertEqual(bundle['explicitLinks'], [])

    def test_discovery_pin_required(self):
        path, unused = self.plan([self.candidate()])
        with self.assertRaisesRegex(ValueError, 'DISCOVERY_PIN_MISMATCH'):
            acquire(path, '0' * 64, self.library, self.fake(), self.root / 'out')

    def test_new_version_does_not_replace(self):
        path, pin = self.plan([self.candidate()])
        acquire(path, pin, self.library, self.fake(jats()), self.root / 'out')
        raw = jats().replace(b'A public source.', b'Changed source.')
        before = (self.library / 'evidence/PS2/index.json').read_bytes()
        result = register(self.library, 'PMC123', {'PMC123.xml': raw}, {'main': {'sha256': sha(raw)}}, {'sha256': sha(raw)})
        self.assertEqual(result['status'], 'NEW_VERSION_PENDING')
        self.assertEqual(before, (self.library / 'evidence/PS2/index.json').read_bytes())
        self.assertEqual((self.library / 'papers/PMC123/PMC123.xml').read_bytes(), jats())

    def test_oversized_shared_json_bounded_before_full_allocation(self):
        with self.assertRaisesRegex(ValueError, 'JSON_SIZE_LIMIT'):
            encoded({'repeated': ['x' * 1000] * 100}, maximum=4096)

    def test_registration_second_index_failure_recoverable(self):
        path, pin = self.plan([self.candidate()])
        original = Path.replace
        def replacement(path, target):
            if str(target).replace('\\', '/').endswith('/PM6/index.json'):
                raise OSError('simulated interrupt')
            return original(path, target)
        with patch.object(Path, 'replace', replacement):
            first = acquire(path, pin, self.library, self.fake(jats()), self.root / 'first')
        self.assertEqual(first['papers'][0]['status'], 'UNAVAILABLE')
        self.assertTrue((self.library / 'evidence/PS2/index.json').exists())
        self.assertFalse((self.library / 'evidence/PM6/index.json').exists())
        self.assertTrue(list((self.library / '_provenance').glob('acquisition-*.json')))
        second = acquire(path, pin, self.library, self.fake(), self.root / 'second')
        self.assertEqual(second['papers'][0]['status'], 'REGISTERED')
        self.assertEqual(len(select(self.library, ['PS2', 'PM6'])['documents']), 1)

    def test_library_symlink_escape_rejected(self):
        # Resolve checking also applies to Windows junctions; no link creation needed.
        from paper_library_registration import inside
        with self.assertRaisesRegex(ValueError, 'PATH_OUTSIDE_LIBRARY'):
            inside(self.library, '../outside')


class TransportTests(FileTest):
    def transport(self, online=True):
        return DownloadTransport(self.cache, self.root / 'requests', online)

    def result(self, status='OK', body=b'abc', **fields):
        def run(command, stdout, stderr, **kwargs):
            if body is not None: Path(command[-2]).write_bytes(body)
            stdout.write(encoded({'status': status, 'bytes': len(body or b''), **fields}))
            stdout.flush()
            return subprocess.CompletedProcess(command, 0)
        return run

    def test_fixed_endpoint(self):
        with self.assertRaises(ValueError): url_for('../bad', 'fullTextXML')
        with self.assertRaises(ValueError): url_for('PMC123', 'https://evil')

    def test_network_off(self):
        with self.transport(False) as transport, self.assertRaisesRegex(ValueError, 'NETWORK_DISABLED'):
            transport.get('PMC123', 'fullTextXML')

    def test_success_cache_replay_no_network(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', self.result()):
            self.assertEqual(transport.get('PMC123', 'fullTextXML'), b'abc')
        with self.transport(False) as transport, patch('paper_download_transport.subprocess.run') as run:
            self.assertEqual(transport.get('PMC123', 'fullTextXML'), b'abc')
            run.assert_not_called()

    def test_cache_corruption_stops(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', self.result()):
            transport.get('PMC123', 'fullTextXML')
        (self.cache / (sha(b'abc') + '.body')).write_bytes(b'bad')
        with self.transport() as transport, self.assertRaisesRegex(ValueError, 'CACHE_INVALID'):
            transport.get('PMC123', 'fullTextXML')

    def test_rate_limit_shared_cooldown_and_no_retry(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', self.result(
                'UNAVAILABLE', None, reasonCode='PAPER_DOWNLOAD_RATE_LIMIT', cooldownSeconds=300)):
            with self.assertRaisesRegex(ValueError, 'RATE_LIMIT'): transport.get('PMC123', 'fullTextXML')
            with self.assertRaisesRegex(ValueError, 'BATCH_STOPPED'): transport.get('PMC456', 'fullTextXML')
            self.assertEqual(transport.requests, 1)
        self.assertGreater(json.loads((self.cache / 'schedule.json').read_bytes())['cooldownUntil'], time.time())

    def test_404_does_not_block_other_papers(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', self.result(
                'UNAVAILABLE', None, reasonCode='PAPER_DOWNLOAD_NOT_AVAILABLE')):
            with self.assertRaisesRegex(ValueError, 'NOT_AVAILABLE'): transport.get('PMC123', 'fullTextXML')
            self.assertIsNone(transport.stopped)

    def test_timeout_has_explicit_termination(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', side_effect=subprocess.TimeoutExpired('worker', 1)):
            with self.assertRaisesRegex(ValueError, 'TIMEOUT'): transport.get('PMC123', 'fullTextXML')
            self.assertTrue(transport.events[-1]['workerTerminatedAndWaited'])

    def test_official_fallback_verifies_latest_version_and_reuses_pinned_bytes_offline(self):
        from hashlib import md5
        from pmc_open_data import ARCHIVE

        listing = b'''<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
          <Name>pmc-oa-opendata</Name><Prefix>PMC123.</Prefix><IsTruncated>false</IsTruncated>
          <CommonPrefixes><Prefix>PMC123.2/</Prefix></CommonPrefixes>
          <CommonPrefixes><Prefix>PMC123.10/</Prefix></CommonPrefixes></ListBucketResult>'''
        body = jats()
        metadata = encoded({'pmcid': 'PMC123', 'version': 10, 'pmid': 456, 'doi': '10.1234/test',
            'is_pmc_openaccess': True, 'is_retracted': False,
            'xml_url': ARCHIVE + 'PMC123.10/PMC123.10.xml?md5=' + md5(body, usedforsecurity=False).hexdigest()})
        payloads = {'pmcArchiveList': listing, 'pmcArchiveMetadata': metadata, 'pmcArchiveXML': body}

        def run(command, stdout, stderr, **kwargs):
            kind = command[7]
            if kind == 'fullTextXML':
                raise subprocess.TimeoutExpired(command, 30)
            if kind != 'pmcArchiveList':
                self.assertEqual(command[-1], '10')
            raw = payloads[kind]
            Path(command[8]).write_bytes(raw)
            stdout.write(encoded({'status': 'OK', 'bytes': len(raw)}))
            stdout.flush()
            return subprocess.CompletedProcess(command, 0)

        candidate = {'pmid': '456', 'doi': '10.1234/test'}
        expected = body, url_for('PMC123', 'pmcArchiveXML', '10')
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', run):
            self.assertEqual(transport.get_open_access('PMC123', candidate), expected)
            self.assertEqual(transport.requests, 4)
        with self.transport(False) as transport, patch('paper_download_transport.subprocess.run') as network:
            self.assertEqual(transport.get_open_access('PMC123', candidate), expected)
            with self.assertRaisesRegex(ValueError, 'IDENTITY_MISMATCH'):
                transport.get_open_access('PMC123', {'pmid': '789'})
            network.assert_not_called()

    def test_official_fallback_does_not_bypass_restricted_response(self):
        with self.transport() as transport, patch('paper_download_transport.subprocess.run', self.result(
                'UNAVAILABLE', None, reasonCode='PAPER_DOWNLOAD_HTTP_FAILURE', httpStatus=403)):
            with self.assertRaisesRegex(ValueError, 'HTTP_FAILURE'):
                transport.get_open_access('PMC123', {})
            self.assertEqual(transport.requests, 1)

    def test_no_redirect_or_error_body_consumption(self):
        import urllib.error
        for code in (302, 404, 429):
            with self.subTest(code=code), patch('paper_download_transport.urllib.request.build_opener') as opener:
                opener.return_value.open.side_effect = urllib.error.HTTPError('https://official', code, '', {}, None)
                result = worker('PMC123', 'fullTextXML', self.root / 'body', '100')
                self.assertEqual(result['httpStatus'], code)
                self.assertFalse((self.root / 'body').exists())

    def test_request_time_and_byte_budget_before_worker(self):
        for field, value, expected in [('requests', 10, 'REQUEST_BUDGET'),
                                      ('transferred', 50 * 1024 * 1024, 'BYTE_BUDGET'),
                                      ('started', time.monotonic() - 181, 'TOTAL_TIMEOUT')]:
            with self.subTest(field=field), self.transport() as transport, patch('paper_download_transport.subprocess.run') as run:
                setattr(transport, field, value)
                with self.assertRaisesRegex(ValueError, expected): transport.get('PMC123', 'fullTextXML')
                run.assert_not_called()

    def test_worker_declared_overlimit_and_exact_body(self):
        class Response(io.BytesIO):
            status = 200
            headers = {'Content-Length': '4', 'Content-Type': 'application/xml'}
        with patch('paper_download_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = Response(b'abcd')
            result = worker('PMC123', 'fullTextXML', self.root / 'over', '3')
            self.assertEqual(result['reasonCode'], 'PAPER_DOWNLOAD_BYTE_BUDGET')
            self.assertFalse((self.root / 'over').exists())
            opener.return_value.open.return_value = Response(b'abcd')
            result = worker('PMC123', 'fullTextXML', self.root / 'exact', '4')
            self.assertEqual(result['status'], 'OK')
            self.assertEqual((self.root / 'exact').read_bytes(), b'abcd')


if __name__ == '__main__':
    unittest.main()
