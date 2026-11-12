import io
import struct
import unittest
import warnings
import zipfile
import zlib
from calibration_zip_member import TARGETS, MAX_MEMBER, MissingRange, SingleResponseView, directory, extract, extract_local_records, suffix


def archive(extra=(), payload=b"author fixture only", zip64=False, method=zipfile.ZIP_STORED):
    out = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(out, "w", method) as z:
            for name, data in [*( (n, payload) for n in sorted(TARGETS)), *extra]:
                z.writestr(name, data)
    body = out.getvalue()
    if zip64:
        e = struct.unpack("<4s4H2LH", body[-22:])
        offset = len(body) - 22
        footer = struct.pack("<4sQ2H2L4Q", b"PK\x06\x06", 44, 45, 45, 0, 0, e[3], e[4], e[5], e[6])
        locator = struct.pack("<4sLQL", b"PK\x06\x07", 0, offset, 1)
        body = body[:-22] + footer + locator + struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 65535, 65535, 0xffffffff, 0xffffffff, 0)
    return body


class Response(io.BytesIO):
    url = "https://example.test/archive.zip"

    def __init__(self, body, status=206, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = headers or {"Content-Range": "bytes 0-%d/%d" % (len(body)-1, len(body)), "Content-Length": str(len(body))}
        self.reads = 0

    def read(self, size=-1):
        self.reads += 1
        return super().read(size)


class CalibrationZipTest(unittest.TestCase):
    def test_single_response_without_etag_and_zip64(self):
        for z64 in (False, True):
            body = archive(zip64=z64)
            response = Response(body)
            start, total, data, proof = suffix(response.url, 262144, lambda *a, **k: response)
            self.assertIsNone(proof["etag"])
            self.assertFalse(proof["crossResponseCombined"])
            self.assertEqual(set(extract(start, total, data)), TARGETS)

    def test_missing_member_interval_not_filled_or_fetched(self):
        body = archive(); central = body.index(b"PK\x01\x02")
        z, _ = directory(central, len(body), body[central:]); z.close()
        with self.assertRaises(MissingRange): extract(central, len(body), body[central:])

    def test_missing_directory_rejected(self):
        body = archive()
        with self.assertRaises(MissingRange): directory(len(body)-22, len(body), body[-22:])

    def test_crc_mismatch_rejected(self):
        body = archive().replace(b"author fixture only", b"Xuthor fixture only", 1)
        with self.assertRaisesRegex(ValueError, "CRC mismatch"): extract(0, len(body), body)

    def test_duplicate_rejected(self):
        body = archive([(sorted(TARGETS)[0], b"different")])
        with self.assertRaisesRegex(ValueError, "duplicate"): extract(0, len(body), body)

    def test_size_and_unsafe_paths_rejected(self):
        body = archive(payload=b"a" * (MAX_MEMBER+1))
        with self.assertRaisesRegex(ValueError, "size limit"): extract(0, len(body), body)
        body = archive([("../escape", b"x")])
        with self.assertRaisesRegex(ValueError, "unsafe"): extract(0, len(body), body)

    def test_ignored_range_and_oversized_response_not_read(self):
        for response in (Response(b"x", 200), Response(b"x", headers={"Content-Range": "bytes 0-9999999/10000000", "Content-Length": "10000000"})):
            with self.assertRaises(ValueError): suffix(response.url, 262144, lambda *a, **k: response)
            self.assertEqual(response.reads, 0)

    def test_non_identity_and_truncated_body_rejected(self):
        response = Response(b"x", headers={"Content-Encoding": "gzip"})
        with self.assertRaises(ValueError): suffix(response.url, 262144, lambda *a, **k: response)
        self.assertEqual(response.reads, 0)
        response = Response(b"x", headers={"Content-Range": "bytes 0-1/2", "Content-Length": "2"})
        with self.assertRaisesRegex(ValueError, "length mismatch"): suffix(response.url, 262144, lambda *a, **k: response)

    def test_no_unvalidated_multiple_response_interface(self):
        with self.assertRaises(ValueError): SingleResponseView(0, 2, [b"a", b"b"])

    def test_deflate_and_falsified_inflated_size(self):
        body = archive(method=zipfile.ZIP_DEFLATED)
        self.assertEqual(len(extract(0, len(body), body)), 2)
        body = bytearray(archive(payload=b"A" * (MAX_MEMBER+1), method=zipfile.ZIP_DEFLATED))
        # An attacker may declare a tiny output and its CRC in both headers.
        # ZipExtFile truncates to that declaration; our explicit inflater must reject it.
        with zipfile.ZipFile(io.BytesIO(body)) as z:
            for info in z.infolist():
                struct.pack_into("<L", body, info.header_offset+14, zlib.crc32(b"A"))
                struct.pack_into("<L", body, info.header_offset+22, 1)
        position = body.index(b"PK\x01\x02")
        while body[position:position+4] == b"PK\x01\x02":
            struct.pack_into("<L", body, position+16, zlib.crc32(b"A"))
            struct.pack_into("<L", body, position+24, 1)
            name, extra, comment = struct.unpack_from("<HHH", body, position+28)
            position += 46+name+extra+comment
        with self.assertRaisesRegex(ValueError, "inflated size mismatch"):
            extract(0, len(body), bytes(body))


class LocalRecordTest(unittest.TestCase):
    def test_prefix_does_not_require_archive_tail(self):
        body = archive(method=zipfile.ZIP_DEFLATED)
        prefix = body[:body.index(b"PK\x01\x02")]
        records = extract_local_records(prefix)
        self.assertEqual(set(records), TARGETS)
        for record in records.values():
            self.assertTrue(record["recordCrcVerified"])
            self.assertFalse(record["centralDirectoryBound"])
            self.assertEqual(record["data"], b"author fixture only")

    def test_descriptor_and_truncation(self):
        class Stream(io.BytesIO):
            def seek(self, *args): raise io.UnsupportedOperation()
        out = Stream()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for name in sorted(TARGETS): z.writestr(name, b"small descriptor fixture")
        body = out.getvalue(); end = body.index(b"PK\x01\x02")
        self.assertEqual(set(extract_local_records(body[:end])), TARGETS)
        with self.assertRaises(MissingRange): extract_local_records(body[:end-1])
        broken = bytearray(body); descriptor = body.index(b"PK\x07\x08")
        broken[descriptor+8] ^= 1
        with self.assertRaisesRegex(ValueError, "descriptor/header"): extract_local_records(bytes(broken))

    def test_local_crc_failure_and_incomplete_member(self):
        body = archive()
        with self.assertRaisesRegex(ValueError, "CRC"): extract_local_records(body.replace(b"author fixture only", b"Xuthor fixture only", 1))
        with self.assertRaises(MissingRange): extract_local_records(body[:50])

    def test_do_not_search_embedded_signature_or_export_nonwhitelist(self):
        body = archive()
        with self.assertRaisesRegex(ValueError, "sequential"): extract_local_records(b"garbage" + body)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as z:
            z.writestr("prefix/readme", b"PK\x03\x04 harmless payload")
            for name in sorted(TARGETS): z.writestr(name, b"small")
        self.assertEqual(set(extract_local_records(out.getvalue())), TARGETS)

    def test_false_size_and_oversized_inflation_rejected(self):
        body = bytearray(archive(payload=b"A"*(MAX_MEMBER+1), method=zipfile.ZIP_DEFLATED))
        struct.pack_into("<L", body, 22, 1)
        with self.assertRaisesRegex(ValueError, "limit|mismatch|oversized"): extract_local_records(bytes(body))


if __name__ == "__main__":
    unittest.main()
