"""Fetch one official REVEL 1.3 CSV member using bounded public HTTP ranges.

This never downloads the complete genome package or installs software. A full
ZIP checksum is recorded as publisher metadata, not claimed as verified when
only a member has been retrieved. Every range, raw receipt, member CRC and CSV
hash is retained. HTTP errors stop the command; retry is an explicit new run.
"""

import argparse
import hashlib
import json
import struct
import time
import urllib.request
import zlib
from datetime import datetime, timezone
from pathlib import Path


URL = "https://zenodo.org/api/records/7072866/files/revel-v1.3_segments_chrom_12.zip/content"
ARCHIVE_BYTES = 35934024
ARCHIVE_MD5 = "ae4f4fd6269e2c046184bc6db43d294c"
MEMBER = "revel-v1.3_segments_chrom_12/revel_grch38_chrom_12_112191609_123463733.csv"
MAX_MEMBER_COMPRESSED_BYTES = 8_000_000
MAX_MEMBER_EXPANDED_BYTES = 50_000_000


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def fetch_range(destination, start, end, label):
    if not 0 <= start <= end < ARCHIVE_BYTES or end - start + 1 > 524288:
        raise ValueError("Range exceeds fixed archive or per-request byte bound")
    request = urllib.request.Request(URL, headers={
        "Range": f"bytes={start}-{end}", "User-Agent": "Varvis-resource-audit/1.0",
    })
    receipt = {"url": URL, "requestedRange": [start, end],
               "retrievedAt": datetime.now(timezone.utc).isoformat()}
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            receipt.update(status=response.status, headers=dict(response.headers))
            expected_range = f"bytes {start}-{end}/{ARCHIVE_BYTES}"
            if response.status != 206 or response.headers.get("Content-Range") != expected_range:
                raise ValueError("Server did not honor exact byte range; body not read")
            content_length = response.headers.get("Content-Length")
            if content_length is not None and int(content_length) != end - start + 1:
                raise ValueError("Range Content-Length differs from the requested byte count")
            started = time.monotonic()
            blocks, received = [], 0
            while received <= end - start:
                block = response.read(min(65536, end - start + 1 - received))
                if not block:
                    break
                blocks.append(block)
                received += len(block)
                if time.monotonic() - started > 120:
                    raise TimeoutError("Range transfer exceeded 120 seconds")
            data = b"".join(blocks)
            receipt.update(sizeBytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            (destination / (label + ".bin")).write_bytes(data)
            if len(data) != end - start + 1:
                raise ValueError("Truncated range response")
            if response.read(1):
                raise ValueError("Excess bytes after requested range")
            return data
    except Exception as error:
        receipt.update(failureType=type(error).__name__, failure=str(error))
        raise
    finally:
        write_json(destination / (label + ".json"), receipt)


def member_descriptor(tail, tail_start):
    end = tail.rfind(b"PK\x05\x06")
    if end < 0 or len(tail) - end < 22:
        raise ValueError("ZIP end-of-central-directory not found")
    values = struct.unpack("<4s4H2LH", tail[end:end + 22])
    _, disk, directory_disk, disk_count, total_count, size, offset, comment = values
    if disk or directory_disk or disk_count != total_count or comment or end + 22 != len(tail):
        raise ValueError("Unsupported multi-disk or extended ZIP")
    if offset < tail_start or offset + size != tail_start + end:
        raise ValueError("Central directory outside retained tail")
    cursor, entries = offset - tail_start, []
    for _ in range(total_count):
        header = struct.unpack("<4s6H3L5H2L", tail[cursor:cursor + 46])
        if header[0] != b"PK\x01\x02":
            raise ValueError("Invalid central-directory signature")
        name = tail[cursor + 46:cursor + 46 + header[10]].decode("utf-8")
        if name == MEMBER:
            entries.append({"memberName": name, "flags": header[3], "method": header[4],
                            "crc32": header[7], "compressedSize": header[8],
                            "expandedSize": header[9], "localHeaderOffset": header[16]})
        cursor += 46 + header[10] + header[11] + header[12]
    if cursor != end or len(entries) != 1:
        raise ValueError("Missing, ambiguous or incomplete member directory")
    entry = entries[0]
    if entry["flags"] or entry["method"] != 8 or not 0 < entry["compressedSize"] <= MAX_MEMBER_COMPRESSED_BYTES:
        raise ValueError("Unsupported or oversized compressed member")
    if not 0 < entry["expandedSize"] <= MAX_MEMBER_EXPANDED_BYTES:
        raise ValueError("Oversized expanded member")
    return entry


def fetch(destination):
    destination.mkdir(parents=True, exist_ok=False)
    tail_start = ARCHIVE_BYTES - 65536
    tail = fetch_range(destination, tail_start, ARCHIVE_BYTES - 1, "zenodo-chr12-tail")
    entry = member_descriptor(tail, tail_start)
    offset = entry["localHeaderOffset"]
    header = fetch_range(destination, offset, offset + 255, "zenodo-member-header")
    values = struct.unpack("<4s5H3L2H", header[:30])
    if values[0] != b"PK\x03\x04" or values[2] != 0 or values[3] != 8:
        raise ValueError("Invalid local ZIP member header")
    if (values[6], values[7], values[8]) != (entry["crc32"], entry["compressedSize"], entry["expandedSize"]):
        raise ValueError("ZIP local/central member identity conflict")
    if header[30:30 + values[9]].decode("utf-8") != MEMBER:
        raise ValueError("Local ZIP member name mismatch")
    begin = offset + 30 + values[9] + values[10]
    last = begin + entry["compressedSize"] - 1
    parts, names = [], []
    for index, start in enumerate(range(begin, last + 1, 524288)):
        name = f"zenodo-member-part-{index}"
        parts.append(fetch_range(destination, start, min(start + 524287, last), name))
        names.append(name)
    compressed = b"".join(parts)
    decoder = zlib.decompressobj(-15)
    content = decoder.decompress(compressed, MAX_MEMBER_EXPANDED_BYTES + 1)
    if len(content) > MAX_MEMBER_EXPANDED_BYTES or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Invalid or oversized deflate payload")
    if len(content) != entry["expandedSize"] or zlib.crc32(content) != entry["crc32"]:
        raise ValueError("ZIP member CRC or size mismatch")
    (destination / "segment.csv").write_bytes(content)
    (destination / "compressed-member.bin").write_bytes(compressed)
    receipt = {
        "url": URL, "memberName": MEMBER, "csvSha256": hashlib.sha256(content).hexdigest(),
        "csvSizeBytes": len(content), "compressedMemberSha256": hashlib.sha256(compressed).hexdigest(),
        "compressedMemberSizeBytes": len(compressed), "memberCrc32": format(entry["crc32"], "08x"),
        "zipCrcVerified": True, "rangeParts": names,
        "archiveMd5RecordedNotVerified": ARCHIVE_MD5,
        "retrievedAt": datetime.now(timezone.utc).isoformat(),
    }
    write_json(destination / "segment-download-receipt.json", receipt)
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", required=True, type=Path)
    print(json.dumps(fetch(parser.parse_args().destination), indent=2))
