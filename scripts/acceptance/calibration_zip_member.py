"""Bounded, single-response ZIP member inspection; no archive code is executed.

Only bytes present in one HTTP response are addressable. An ETag is not needed
for this contract; combining responses is deliberately unsupported.
"""
import hashlib
import io
import re
import struct
import urllib.request
import zipfile
import zlib

TARGETS = frozenset(("results/README",
    "results/thresholds/discounted_comparator_tools_w3pts_bootstrap10k_thrs.txt"))
MAX_RESPONSE = 2 * 1024 * 1024
MAX_MEMBER = 64 * 1024


def extract_local_records(prefix):
    """Independent streaming contract; no CEN binding or archive uniqueness claim.

    Starts at byte zero and stops after both complete whitelisted local records.
    Supports stored/deflated records and unambiguous 32-bit descriptors only.
    """
    position, inflated_total = 0, 0
    seen, output = set(), {}
    while set(output) != TARGETS:
        start = position
        if len(prefix) - position < 30:
            raise MissingRange("local header truncated")
        h = struct.unpack_from("<4s5H3L2H", prefix, position)
        signature, version, flags, method = h[:4]
        crc, compressed_size, size, name_size, extra_size = h[6:]
        if signature != b"PK\x03\x04":
            raise ValueError("sequential local header required")
        if flags & ~(2048 | 8 | 6) or method not in (0, 8) or flags & 6 and method != 8:
            raise ValueError("unsupported flags/compression or encryption")
        if version > 20 or 0xffffffff in (compressed_size, size):
            raise ValueError("ZIP64 local streaming contract unsupported")
        position += 30
        if position + name_size + extra_size > len(prefix):
            raise MissingRange("local name/extra truncated")
        name = prefix[position:position+name_size].decode("utf-8" if flags & 2048 else "cp437")
        if not name or "\0" in name or "\\" in name or name.startswith("/") or ":" in name or ".." in name.split("/") or name in seen:
            raise ValueError("unsafe/duplicate local name")
        seen.add(name)
        position += name_size + extra_size
        if size > MAX_MEMBER or compressed_size > MAX_MEMBER:
            raise ValueError("local member size limit")
        if flags & 8:
            if method != 8:
                raise ValueError("stored descriptor boundary ambiguous")
            inflater = zlib.decompressobj(-15)
            data = inflater.decompress(prefix[position:], MAX_MEMBER+1)
            if len(data) > MAX_MEMBER or inflater.unconsumed_tail:
                raise ValueError("local inflated size limit")
            if not inflater.eof:
                raise MissingRange("deflate stream truncated")
            actual_compressed = len(prefix)-position-len(inflater.unused_data)
            position += actual_compressed
            if prefix[position:position+4] == b"PK\x07\x08":
                position += 4
            if position + 12 > len(prefix):
                raise MissingRange("descriptor truncated")
            dc, ds, du = struct.unpack_from("<3L", prefix, position)
            position += 12
            if ds != actual_compressed or du != len(data) or crc not in (0, dc) or compressed_size not in (0, ds) or size not in (0, du):
                raise ValueError("descriptor/header mismatch")
            crc, compressed_size, size = dc, ds, du
        else:
            if position + compressed_size > len(prefix):
                raise MissingRange("compressed local record truncated")
            compressed = prefix[position:position+compressed_size]
            position += compressed_size
            if method == 8:
                inflater = zlib.decompressobj(-15)
                data = inflater.decompress(compressed, MAX_MEMBER+1)
                if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
                    raise ValueError("local deflate incomplete/oversized/trailing")
            else:
                data = compressed
        inflated_total += len(data)
        if len(data) != size or size > MAX_MEMBER or inflated_total > 2*MAX_MEMBER:
            raise ValueError("local inflated size limit/mismatch")
        if zlib.crc32(data) & 0xffffffff != crc:
            raise ValueError("local CRC mismatch")
        if name in TARGETS:
            output[name] = {"data": data, "offset": start, "endOffset": position,
                "status": "LOCAL_RECORD_TEXT_OBTAINED", "recordCrcVerified": True,
                "recordSha256": hashlib.sha256(data).hexdigest(),
                "centralDirectoryBound": False, "wholeArchiveChecksumVerified": False}
    return output


class MissingRange(ValueError):
    pass


class SingleResponseView(io.RawIOBase):
    def __init__(self, start, total, body):
        if not isinstance(body, bytes) or start < 0 or start + len(body) != total:
            raise ValueError("one complete suffix response required; no cross-response assembly")
        self.start, self.total, self.body, self.position = start, total, body, 0

    def seekable(self):
        return True

    def seek(self, offset, whence=0):
        self.position = offset + (0 if whence == 0 else self.position if whence == 1 else self.total)
        if not 0 <= self.position <= self.total:
            raise MissingRange("seek outside object")
        return self.position

    def tell(self):
        return self.position

    def read(self, size=-1):
        end = self.total if size < 0 else min(self.total, self.position + size)
        if self.position < self.start:
            raise MissingRange("missing interval %d:%d" % (self.position, end))
        value = self.body[self.position - self.start:end - self.start]
        self.position = end
        return value


def directory(start, total, body):
    view = SingleResponseView(start, total, body)
    eocd = body.rfind(b"PK\x05\x06")
    if eocd < 0 or eocd + 22 > len(body):
        raise ValueError("EOCD missing")
    end = struct.unpack_from("<4s4H2LH", body, eocd)
    if eocd + 22 + end[-1] != len(body) or end[1] or end[2] or end[3] != end[4]:
        raise ValueError("EOCD incomplete or multidisk")
    if eocd >= 20 and body[eocd-20:eocd-16] == b"PK\x06\x07":
        disk, offset, disks = struct.unpack_from("<LQL", body, eocd-16)
        if disk or disks != 1:
            raise ValueError("ZIP64 multidisk")
        view.seek(offset)
        z64 = view.read(56)
        z = struct.unpack("<4sQ2H2L4Q", z64)
        if z[0] != b"PK\x06\x06" or z[1] != 44 or z[4] or z[5] or z[6] != z[7]:
            raise ValueError("unsupported ZIP64 footer")
        if offset + 56 != start + eocd - 20:
            raise ValueError("ZIP64 footer offsets inconsistent")
        count, cdsize, cdoffset = z[7:10]
        footer = offset
    else:
        count, cdsize, cdoffset, footer = end[4], end[5], end[6], start + eocd
    if cdoffset < start:
        raise MissingRange("central directory missing; suffix needs %d bytes" % (total - cdoffset))
    if cdoffset + cdsize != footer:
        raise ValueError("central directory boundary mismatch")
    archive = zipfile.ZipFile(view)
    entries = archive.infolist()
    if len(entries) != count or archive.start_dir != cdoffset:
        raise ValueError("central directory count/offset mismatch")
    names = set()
    for info in entries:
        if info.orig_filename != info.filename or "\\" in info.filename or info.filename.startswith("/") or ":" in info.filename or ".." in info.filename.split("/"):
            raise ValueError("unsafe member path")
        if info.filename in names or info.volume != 0:
            raise ValueError("duplicate member or multidisk")
        names.add(info.filename)
    if not TARGETS <= names:
        raise ValueError("whitelisted member absent")
    return archive, {name: archive.getinfo(name) for name in sorted(TARGETS)}


def extract(start, total, body):
    archive, targets = directory(start, total, body)
    output = {}
    with archive:
        for name, info in targets.items():
            if info.flag_bits & (1 | 64) or info.compress_type not in (0, 8):
                raise ValueError("encrypted or unsupported compression")
            if info.file_size > MAX_MEMBER or info.compress_size > MAX_MEMBER:
                raise ValueError("member size limit")
            view = archive.fp
            view.seek(info.header_offset)
            header = view.read(30)
            if len(header) != 30:
                raise MissingRange("local header incomplete")
            local = struct.unpack("<4s5H3L2H", header)
            if local[0] != b"PK\x03\x04" or local[2] != info.flag_bits or local[3] != info.compress_type:
                raise ValueError("local header identity mismatch")
            data_end = info.header_offset + 30 + local[-2] + local[-1] + info.compress_size
            if data_end > archive.start_dir:
                raise ValueError("member overlaps directory")
            filename = view.read(local[-2]).decode("utf-8" if info.flag_bits & 2048 else "cp437")
            extra = view.read(local[-1])
            if filename != name or len(extra) != local[-1]:
                raise ValueError("local member name/extensions mismatch")
            if not info.flag_bits & 8 and (local[6] != info.CRC
                    or local[7] not in (info.compress_size, 0xffffffff)
                    or local[8] not in (info.file_size, 0xffffffff)):
                raise ValueError("local member CRC/sizes mismatch")
            if local[7] == 0xffffffff or local[8] == 0xffffffff:
                position, sizes = 0, None
                while position + 4 <= len(extra):
                    tag, length = struct.unpack_from("<HH", extra, position)
                    position += 4
                    if position + length > len(extra):
                        raise ValueError("local extra field truncated")
                    if tag == 1:
                        if sizes is not None or length < 16:
                            raise ValueError("local ZIP64 size field invalid")
                        sizes = struct.unpack_from("<QQ", extra, position)
                    position += length
                if sizes != (info.file_size, info.compress_size):
                    raise ValueError("local ZIP64 sizes mismatch")
            compressed = view.read(info.compress_size)
            if len(compressed) != info.compress_size:
                raise MissingRange("compressed member incomplete")
            if info.compress_type == 8:
                inflater = zlib.decompressobj(-15)
                data = inflater.decompress(compressed, MAX_MEMBER + 1)
                if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
                    raise ValueError("deflate stream incomplete, oversized or trailing data")
            else:
                data = compressed
            if len(data) != info.file_size or len(data) > MAX_MEMBER:
                raise ValueError("inflated size mismatch")
            if zlib.crc32(data) & 0xffffffff != info.CRC:
                raise ValueError("member CRC mismatch")
            output[name] = data
    return output


def suffix(url, length, opener=urllib.request.urlopen):
    if not url.startswith("https://") or not 0 < length <= MAX_RESPONSE:
        raise ValueError("HTTPS and bounded suffix required")
    request = urllib.request.Request(url, headers={"Range": "bytes=-%d" % length,
        "Accept-Encoding": "identity", "User-Agent": "Varvis-single-response-audit/1"})
    with opener(request, timeout=30) as response:
        if response.status != 206 or not response.url.startswith("https://"):
            raise ValueError("range ignored or insecure redirect; body not read")
        if response.headers.get("Content-Encoding", "identity").lower() != "identity":
            raise ValueError("encoded response; body not read")
        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
        if not match:
            raise ValueError("invalid Content-Range; body not read")
        start, end, total = map(int, match.groups())
        expected = min(length, total)
        if start != total - expected or end != total - 1:
            raise ValueError("unexpected suffix; body not read")
        if int(response.headers.get("Content-Length", "-1")) != expected:
            raise ValueError("Content-Length mismatch; body not read")
        body = response.read(expected)
        if len(body) != expected:
            raise ValueError("response body length mismatch")
        proof = {"url": url, "finalUrl": response.url, "status": 206,
            "contentRange": response.headers["Content-Range"], "bytes": len(body),
            "bodySha256": hashlib.sha256(body).hexdigest(), "etag": response.headers.get("ETag"),
            "contract": "SINGLE_RESPONSE_COMPLETE_DIRECTORY_AND_MEMBER_V1",
            "crossResponseCombined": False, "fullZipChecksumVerified": False}
        return start, total, body, proof
