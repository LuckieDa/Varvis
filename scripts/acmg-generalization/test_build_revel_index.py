"""Synthetic ZIP/BGZF/TBI only. Real source ZIPs and reference files are forbidden."""
import argparse
import gzip
import hashlib
import importlib.util
import inspect
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("revel_index", Path(__file__).with_name("build_revel_index.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class ProjectionTest(unittest.TestCase):
    def test_publisher_rows_keep_exact_raw_fields_and_data_ordinal(self):
        raw = b"1,101,201,G,A,A,T,0.7,ENST00001;ENST00002\n"
        key, reason, out = builder.source_row(raw, 1)
        self.assertEqual(("1", 201, 1), key)
        self.assertIsNone(reason)
        self.assertEqual(b"1\t201\t1\t" + raw, out)
        self.assertEqual(key, builder.index_row(out))

    def test_semantic_anomalies_are_retained_for_runtime_refusal(self):
        for raw in (b"1,NA,201,XX,,?,Z,NA,\n", b"1,0,201,T,T,A,A,2,ENST1.9\n", b"1,1,201,G,A,A,T,nan,FOREIGN\n"):
            with self.subTest(raw=raw):
                key, reason, out = builder.source_row(raw, 2)
                self.assertEqual(("1", 201, 2), key)
                self.assertIsNone(reason)
                self.assertTrue(out.endswith(raw))
                self.assertTrue(builder.annotation_flags(raw.decode().strip().split(",")))

    def test_missing_unsupported_and_malformed_tokens_never_become_coordinates(self):
        for chrom, pos, expected in (("1", ".", "PUBLISHER_GRCH38_MAPPING_MISSING"), ("1", "", "UNRECOGNIZED_OR_MALFORMED_GRCH38_POSITION"),
                                    ("1", "NA", "UNRECOGNIZED_OR_MALFORMED_GRCH38_POSITION"), ("1", "0", "UNRECOGNIZED_OR_MALFORMED_GRCH38_POSITION"),
                                    ("01", "5", "UNSUPPORTED_SOURCE_CONTIG_TOKEN"), ("chr1", "5", "UNSUPPORTED_SOURCE_CONTIG_TOKEN"),
                                    ("1", "536870912", "GRCH38_POSITION_OUTSIDE_TBI_RANGE")):
            raw = (chrom + ",1," + pos + ",A,T,A,T,0.5,ENST1\n").encode()
            key, reason, row = builder.source_row(raw, 8)
            self.assertIsNone(key)
            self.assertEqual(expected, reason)
            self.assertTrue(row.endswith(raw))

    def test_non_nine_field_or_multiline_or_non_ascii_source_refuses(self):
        for raw in (b"1,2\n", b"1,1,2,A,T,A,T,.1,ENST1\r\n", b"1,1,2,A,T,A,T,.1,ENST1", b"1,1,2,A,T,A,T,.1,ENST1\tX\n", b"1,1,2,A,T,A,T,.1,\xff\n"):
            with self.assertRaises((ValueError, UnicodeError)):
                builder.source_row(raw, 1)

    def test_disk_write_and_short_write_are_precharged(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();budget=builder.DiskBudget(root,builder.METADATA_RESERVE+5)
            with budget.create(root/'owned') as stream:
                builder.DiskBudget.write(budget,stream,b'12345')
                with self.assertRaisesRegex(ValueError,'DERIVED_DISK_BUDGET_EXCEEDED'):
                    budget.write(stream,b'6')
            self.assertEqual(b'12345',(root/'owned').read_bytes())
            self.assertEqual(5,budget.used)
            with budget.create(root/'short') as stream:
                class Short:
                    _revel_budget_path=stream._revel_budget_path
                    def write(self,data):
                        return stream.write(data[:2])
                budget.maximum+=5
                with self.assertRaisesRegex(ValueError,'OUTPUT_SHORT_WRITE'):
                    budget.write(Short(),b'12345')
            self.assertEqual(10,budget.used)
            self.assertEqual(b'12',(root/'short').read_bytes())


@unittest.skipUnless(os.environ.get("VARVIS_REVEL_BGZIP") and os.environ.get("VARVIS_REVEL_TABIX"), "Explicit existing bgzip/tabix paths required for synthetic integration")
class BuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="revel-synthetic-", dir=os.environ.get("VARVIS_REVEL_TEST_ROOT"))
        self.root = Path(self.temp.name).resolve()
        self.raw_rows = [b"2,1,99,A,T,A,T,.7,ENST2\n", b"1,1,20,G,A,A,T,.7,ENST1;ENST9\n",
                         b"1,1,20,G,A,A,T,.9,ENST1;ENST9\n", b"1,1,20,G,A,A,T,.7,ENST1;ENST9\n",
                         b"1,2,10,A,C,A,A,NA,\n", b"1,3,.,A,C,A,A,0.1,ENST1\n", b"01,3,1,A,C,A,A,0.1,ENST1\n",
                         b"X,4,536870911,XX,,?,Z,nan,FOREIGN\n", b"1,3,NA,A,C,A,A,0.1,ENST1\n"]

    def tearDown(self):
        self.temp.cleanup()

    def args(self, rows=None, header=builder.HEADER, extra=False):
        member = header + b"".join(self.raw_rows if rows is None else rows)
        source = self.root / ("source-%d.zip" % len(list(self.root.glob("source-*.zip"))))
        with zipfile.ZipFile(source, "x", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("revel_with_transcript_ids", member)
            if extra:
                archive.writestr("unreviewed", b"extra")
        return argparse.Namespace(source_zip=str(source), expected_zip_sha256=builder.file_sha(source), expected_zip_bytes=source.stat().st_size,
                                  member_name="revel_with_transcript_ids", expected_member_bytes=len(member), expected_member_crc32="%08x" % zlib.crc32(member),
                                  expected_member_sha256=hashlib.sha256(member).hexdigest(), output=str(self.root / (source.stem + "-output")),
                                  bgzip=os.environ["VARVIS_REVEL_BGZIP"], tabix=os.environ["VARVIS_REVEL_TABIX"],
                                  maximum_disk_bytes=100_000_000, maximum_data_rows=100000, chunk_bytes=1024, fan_in=2)

    def assert_failed(self, args, error=(ValueError, zipfile.BadZipFile)):
        with self.assertRaises(error):
            builder.build(args)
        out = Path(args.output)
        self.assertFalse((out / "manifest.json").exists())
        state = json.loads((out / "failure-manifest.json").read_bytes())
        self.assertFalse(state["publisherLookupReady"])
        self.assertTrue(state["failurePrefixMustNotBeUsed"])
        self.assertLessEqual(state["peakReservedLogicalDiskBytes"], args.maximum_disk_bytes)
        return state

    def test_complete_real_bgzf_tabix_retains_conflicts_duplicates_and_exclusions(self):
        args = self.args()
        before = Path(args.source_zip).read_bytes()
        state = builder.build(args)
        self.assertEqual("COMPLETE", state["status"])
        self.assertEqual((9, 6, 3), (state["sourceDataRows"], state["indexedRows"], state["excludedRows"]))
        self.assertFalse(state["nativeReferenceVerified"])
        self.assertFalse(state["defaultEnabled"])
        self.assertEqual(before, Path(args.source_zip).read_bytes())
        out = Path(args.output)
        with gzip.open(out / "revel-grch38.lookup.tsv.gz", "rb") as stream:
            lines = stream.readlines()
        self.assertEqual([5,2,3,4,1,8], [int(x.split(b"\t",3)[2]) for x in lines[1:]])
        self.assertEqual(self.raw_rows[1], lines[2].split(b"\t",3)[3])
        self.assertEqual(self.raw_rows[2], lines[3].split(b"\t",3)[3])
        self.assertEqual(self.raw_rows[3], lines[4].split(b"\t",3)[3])
        with gzip.open(out / "revel-unlocated-source-rows.tsv.gz", "rb") as stream:
            self.assertEqual(4, len(stream.readlines()))
        self.assertEqual("COMPLETE", json.loads((out / "cleanup.json").read_bytes())["status"])
        self.assertFalse(list(out.glob("sort-run-*")))

    def test_multi_pass_sort_has_complete_source_identity_and_bounded_cleanup(self):
        rows = [("1,%d,%d,A,G,A,T,0.5,ENST1\n" % (i,i)).encode() for i in range(300,0,-1)]
        args = self.args(rows)
        state = builder.build(args)
        self.assertGreater(len(state["sort"]["temporaryRuns"]), 2)
        self.assertEqual(300,state["validation"]["fullIndexedRowsReparsed"])
        self.assertLessEqual(state["peakReservedLogicalDiskBytes"],args.maximum_disk_bytes)
        self.assertFalse(list(Path(args.output).glob("sort-run-*")))

    def test_sparse_positions_cover_several_tabix_bins(self):
        rows = [("1,%d,%d,A,G,A,T,0.5,ENST1\n" % (i,i)).encode() for i in (1,16385,32769,262145,10000000)]
        state=builder.build(self.args(rows))
        self.assertEqual(5,state["validation"]["fullIndexedRowsReparsed"])

    def test_zip_member_and_header_mismatches_are_not_ready(self):
        for kind in ("zip_sha", "member_sha", "crc", "header", "extra"):
            args = self.args(header=b"WRONG\n" if kind=="header" else builder.HEADER, extra=kind=="extra")
            if kind=="zip_sha": args.expected_zip_sha256="0"*64
            if kind=="member_sha": args.expected_member_sha256="0"*64
            if kind=="crc": args.expected_member_crc32="00000000"
            self.assert_failed(args)

    def test_corrupt_member_with_matching_zip_sha_still_fails_crc(self):
        args = self.args()
        source = Path(args.source_zip)
        data = source.read_bytes()
        at = data.index(self.raw_rows[0])
        data = data[:at] + b"3" + data[at+1:]
        source.write_bytes(data)
        args.expected_zip_sha256=builder.file_sha(source)
        self.assert_failed(args)

    def test_rows_cap_disk_cap_and_index_failure_preserve_failed_prefixes(self):
        args = self.args(); args.maximum_data_rows=2
        self.assert_failed(args)
        args = self.args(); args.maximum_disk_bytes=2_000_000
        state=self.assert_failed(args)
        self.assertIn("INDEX_DISK_RESERVATION_UNAVAILABLE",state["error"])
        self.assertTrue(list(Path(args.output).glob("sort-run-*")))
        args = self.args()
        with patch.object(builder,"verify_tbi",side_effect=ValueError("synthetic index corruption")):
            self.assert_failed(args)
        self.assertTrue(list(Path(args.output).glob("sort-run-*")))

    def test_cancelled_projection_never_leaves_compressor_or_ready_manifest(self):
        args=self.args();created=[]
        original=builder.BgzipSink.__init__
        def capture(sink,*params):
            original(sink,*params);created.append(sink)
        with patch.object(builder.BgzipSink,'__init__',capture),patch.object(builder,'source_row',side_effect=KeyboardInterrupt('synthetic')):
            state=self.assert_failed(args,KeyboardInterrupt)
        self.assertEqual('CANCELLED',state['status'])
        self.assertTrue(created)
        self.assertTrue(all(s.process.poll() is not None and all(not t.is_alive() for t in s.threads) for s in created))

    def test_constructor_and_caller_cancellation_close_all_owned_resources(self):
        init_lines, init_start=inspect.getsourcelines(builder.BgzipSink.__init__)
        build_lines, build_start=inspect.getsourcelines(builder.build)
        seams=[(builder.BgzipSink.__init__,init_start+next(i for i,l in enumerate(init_lines) if 'self.output = budget.create' in l),'before_handles'),
               (builder.BgzipSink.__init__,init_start+next(i for i,l in enumerate(init_lines) if 'self.threads = [threading.Thread' in l),'after_popen'),
               (builder.BgzipSink.__init__,init_start+next(i for i,l in enumerate(init_lines) if l.strip()=='for thread in self.threads:'),'before_start'),
               (builder.BgzipSink.__init__,init_start+next(i for i,l in enumerate(init_lines) if l.strip()=='thread.start()'),'second_start'),
               (builder.build,build_start+next(i for i,l in enumerate(build_lines) if l.strip()=='excluded.write(EXCLUDED_HEADER)'),'caller_after_constructor')]
        for function,line,name in seams:
            with self.subTest(seam=name):
                args=self.args(); captured=[]
                def cancel(frame,event,_):
                    if event=='line' and frame.f_code is function.__code__ and frame.f_lineno==line:
                        sink=frame.f_locals['excluded'] if name=='caller_after_constructor' else frame.f_locals['self']
                        if name=='second_start' and frame.f_locals['thread'] is not sink.threads[1]:
                            return cancel
                        captured.append(sink)
                        raise KeyboardInterrupt('synthetic constructor ownership seam')
                    return cancel
                try:
                    sys.settrace(cancel)
                    state=self.assert_failed(args,KeyboardInterrupt)
                finally:
                    sys.settrace(None)
                self.assertEqual('CANCELLED',state['status']);self.assertEqual(1,len(captured))
                sink=captured[0]
                self.assertTrue(sink.process is None or sink.process.poll() is not None)
                self.assertTrue(all(not thread.is_alive() for thread in sink.threads))
                self.assertTrue(sink.output is None or sink.output.closed)
                self.assertTrue(sink.stderr is None or sink.stderr.closed)

    def test_manifest_write_failure_keeps_chunks_and_no_ready_marker(self):
        args=self.args();original=builder.DiskBudget.json_file
        def fail_manifest(budget,name,value):
            if name=='manifest.pending.json':raise OSError('synthetic final manifest write')
            return original(budget,name,value)
        with patch.object(builder.DiskBudget,'json_file',fail_manifest):
            self.assert_failed(args,OSError)
        self.assertTrue(list(Path(args.output).glob('sort-run-*')))

    def test_existing_directory_is_never_overwritten(self):
        args = self.args(); out=Path(args.output);out.mkdir()
        marker=out/"USER";marker.write_bytes(b"preserve")
        with self.assertRaisesRegex(ValueError,"NEW_OUTPUT_DIRECTORY_REQUIRED"):
            builder.build(args)
        self.assertEqual(b"preserve",marker.read_bytes())

    def test_absent_expected_member_sha_is_explicit_derived_hash(self):
        args=self.args();args.expected_member_sha256=None
        state=builder.build(args)
        self.assertEqual("COMPUTED_FROM_FULL_SHA_PINNED_ZIP",state["source"]["memberSha256Check"])
        self.assertRegex(state["source"]["memberSha256"],r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
