"""Build a publisher-coordinate REVEL 1.3 lookup, never native REF/CDS evidence.

Standard-library bounded external sort; existing bgzip/tabix only. A new output
directory is mandatory. No network, runtime registration, score selection, or
automatic retry. SourceLine is the one-based DATA row ordinal (header excluded).
"""
import argparse
import collections
import contextlib
import datetime
import decimal
import gzip
import hashlib
import heapq
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import threading
import zipfile
import zlib

VERSION = "1.0.1"
HEADER = b"chr,hg19_pos,grch38_pos,ref,alt,aaref,aaalt,REVEL,Ensembl_transcriptid\n"
FIELD_NAMES = HEADER.decode("ascii").strip().split(",")
INDEX_HEADER = b"#chrom\tposition\tsourceLine\trawCsv\n"
EXCLUDED_HEADER = b"#reason\tsourceLine\trawCsv\n"
CONTIGS = frozenset([str(n) for n in range(1, 23)] + ["X", "Y", "MT"])
MAX_POSITION = (1 << 29) - 1
MAX_LINE = 16385
MAX_DISK = 20_000_000_000
METADATA_RESERVE = 1_048_576
INDEX_RESERVE = 67_108_864
MODULUS = 1 << 256


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"


def file_sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def process_options():
    return {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}


class DiskBudget:
    """Logical file bytes, checked before every owned write, plus reserved TBI bound."""
    def __init__(self, root, maximum):
        self.root, self.maximum = root, maximum
        self.used = self.peak = self.reserved = 0
        self.files = {}
        self.lock = threading.Lock()

    def confined(self, path):
        resolved = path.resolve()
        require(self.root in resolved.parents, "OUTPUT_PATH_ESCAPES_NEW_ROOT")
        return resolved

    def create(self, path):
        path = self.confined(path)
        stream = path.open("xb")
        try:
            stream._revel_budget_path = path
            with self.lock:
                self.files[path] = 0
        except BaseException:
            stream.close()
            raise
        return stream

    def write(self, stream, data, metadata=False):
        path = stream._revel_budget_path
        with self.lock:
            require(path in self.files, "UNREGISTERED_OUTPUT_WRITE")
            margin = 0 if metadata else METADATA_RESERVE
            require(self.used + self.reserved + len(data) <= self.maximum - margin, "DERIVED_DISK_BUDGET_EXCEEDED")
            # Charge the whole attempted write first: a short/failed OS write
            # may have consumed a prefix, so its unknown remainder stays reserved.
            self.files[path] += len(data)
            self.used += len(data)
            self.peak = max(self.peak, self.used + self.reserved)
            count = stream.write(data)
            require(count == len(data), "OUTPUT_SHORT_WRITE")

    def reserve_index(self):
        with self.lock:
            require(self.used + INDEX_RESERVE <= self.maximum - METADATA_RESERVE, "INDEX_DISK_RESERVATION_UNAVAILABLE")
            self.reserved += INDEX_RESERVE
            self.peak = max(self.peak, self.used + self.reserved)

    def settle_index(self, path):
        path = self.confined(path)
        size = path.stat().st_size if path.exists() else 0
        require(size <= INDEX_RESERVE, "INDEX_EXCEEDS_PROVEN_POINT_TBI_BOUND")
        with self.lock:
            self.reserved -= INDEX_RESERVE
            self.files[path] = size
            self.used += size
            self.peak = max(self.peak, self.used + self.reserved)

    def remove_owned(self, path):
        path = self.confined(path)
        require(path in self.files and path.is_file() and path.stat().st_size == self.files[path], "TEMPORARY_FILE_IDENTITY_CHANGED")
        path.unlink()
        self.used -= self.files.pop(path)

    def rename_owned(self, old, new):
        old, new = self.confined(old), self.confined(new)
        require(old in self.files and not new.exists(), "OUTPUT_RENAME_IDENTITY_INVALID")
        old.rename(new)
        self.files[new] = self.files.pop(old)

    def json_file(self, name, value):
        with self.create(self.root / name) as stream:
            self.write(stream, canonical(value), metadata=True)


class BgzipSink:
    """No child writes to disk: bounded readers account stdout/stderr themselves."""
    def __init__(self, executable, path, budget, owners):
        self.budget, self.path, self.error = budget, path, None
        self.output = self.stderr = self.process = None
        self.threads = list()
        try:
            # Register before acquiring handles or starting a process. The build
            # can cancel even after this constructor returns but before assignment.
            owners.append(self)
            self.output = budget.create(path)
            self.stderr = budget.create(path.with_name(path.name + ".stderr.log"))
            self.process = subprocess.Popen([str(executable), "-c", "-l", "6", "-@", "1"],
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                            **process_options())
            self.threads = [threading.Thread(target=self.drain, args=(self.process.stdout, self.output, None)),
                            threading.Thread(target=self.drain, args=(self.process.stderr, self.stderr, 1048576))]
            for thread in self.threads:
                thread.start()
        except BaseException:
            self.abort()
            raise

    def drain(self, source, target, limit):
        count = 0
        try:
            for block in iter(lambda: source.read(65536), b""):
                count += len(block)
                require(limit is None or count <= limit, "TOOL_STDERR_LIMIT")
                self.budget.write(target, block)
        except BaseException as error:
            if self.error is None:
                self.error = error
            self.process.kill()
        finally:
            source.close()
            target.close()

    def write(self, data):
        if self.error:
            raise self.error
        try:
            require(self.process.stdin.write(data) == len(data), "BGZIP_INPUT_SHORT_WRITE")
        except BrokenPipeError:
            if self.error:
                raise self.error
            raise ValueError("BGZIP_PIPE_FAILED")

    def close(self):
        with contextlib.suppress(BrokenPipeError):
            self.process.stdin.close()
        code = self.process.wait(timeout=60)
        for thread in self.threads:
            thread.join(timeout=60)
        require(all(not thread.is_alive() for thread in self.threads), "BGZIP_READER_NOT_FINISHED")
        if self.error:
            raise self.error
        require(code == 0, "BGZIP_FAILED")

    def abort(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.kill()
            with contextlib.suppress(Exception):
                self.process.stdin.close()
            self.process.wait(timeout=10)
        for thread in self.threads:
            if thread.ident is not None:
                thread.join(timeout=10)
        require(all(not thread.is_alive() for thread in self.threads), "BGZIP_ABORT_READER_NOT_FINISHED")
        handles = [self.output, self.stderr]
        if self.process is not None:
            handles += [self.process.stdout, self.process.stderr]
        for handle in handles:
            if handle is not None:
                with contextlib.suppress(Exception):
                    handle.close()


def tool_call(args, limit=65536, timeout=60):
    """Only metadata/integrity tools with bounded output; caller never supplies URLs."""
    result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, **process_options())
    require(len(result.stdout) + len(result.stderr) <= limit, "TOOL_RECEIPT_TOO_LARGE")
    require(result.returncode == 0, "TOOL_FAILED_" + Path(args[0]).stem.upper())
    return result.stdout


def source_row(raw, ordinal):
    require(type(ordinal) is int and ordinal > 0, "SOURCE_DATA_ORDINAL_INVALID")
    require(raw.endswith(b"\n") and len(raw) <= MAX_LINE and b"\r" not in raw and b"\t" not in raw and b"\x00" not in raw,
            "SOURCE_LINE_BOUND_OR_LF_FORMAT_INVALID")
    text = raw[:-1].decode("ascii", errors="strict")
    cells = text.split(",")
    require(len(cells) == 9, "SOURCE_NINE_FIELDS_REQUIRED")
    reason = None
    if cells[2] == ".":
        reason = "PUBLISHER_GRCH38_MAPPING_MISSING"
    elif not re.fullmatch(r"[1-9][0-9]{0,8}", cells[2]):
        reason = "UNRECOGNIZED_OR_MALFORMED_GRCH38_POSITION"
    elif int(cells[2]) > MAX_POSITION:
        reason = "GRCH38_POSITION_OUTSIDE_TBI_RANGE"
    elif cells[0] not in CONTIGS:
        reason = "UNSUPPORTED_SOURCE_CONTIG_TOKEN"
    if reason:
        return None, reason, reason.encode() + b"\t" + str(ordinal).encode() + b"\t" + raw
    # Do not filter on score, REF/ALT, amino acids, transcript identity, or hg19.
    projected = cells[0].encode() + b"\t" + cells[2].encode() + b"\t" + str(ordinal).encode() + b"\t" + raw
    return (cells[0], int(cells[2]), ordinal), None, projected


def index_row(line):
    parts = line.split(b"\t", 3)
    require(len(parts) == 4, "INDEX_FOUR_FIELDS_REQUIRED")
    require(re.fullmatch(rb"[1-9][0-9]*", parts[2]) is not None, "INDEX_SOURCE_ORDINAL_INVALID")
    key, reason, rebuilt = source_row(parts[3], int(parts[2]))
    require(reason is None and rebuilt == line, "INDEX_RAW_COORDINATE_MISMATCH")
    return key


def annotation_flags(cells):
    """Descriptive raw-format counters only. Never suppress an indexable row."""
    flags = []
    if not re.fullmatch(r"[1-9][0-9]{0,8}", cells[1]):
        flags.append("HG19_POSITION_FORMAT_UNRECOGNIZED")
    if any(len(x) != 1 or x not in "ACGT" for x in cells[3:5]) or cells[3] == cells[4]:
        flags.append("PUBLISHED_DNA_FIELDS_NOT_DISTINCT_SINGLE_ACGT")
    if any(len(x) != 1 or x not in "ACDEFGHIKLMNPQRSTVWY" for x in cells[5:7]) or cells[5] == cells[6]:
        flags.append("PUBLISHED_AMINO_ACID_FIELDS_NOT_DISTINCT_STANDARD_RESIDUES")
    try:
        value = decimal.Decimal(cells[7])
        if not value.is_finite() or not 0 <= value <= 1:
            flags.append("RAW_SCORE_NOT_FINITE_UNIT_INTERVAL")
    except decimal.InvalidOperation:
        flags.append("RAW_SCORE_UNPARSEABLE_OR_MISSING")
    if not re.fullmatch(r"ENST[0-9]+(?:;ENST[0-9]+)*", cells[8]):
        flags.append("UNVERSIONED_TRANSCRIPT_TOKEN_FORMAT_UNRECOGNIZED")
    return flags


class ExternalSort:
    def __init__(self, root, budget, chunk_bytes, fan_in):
        self.root, self.budget = root, budget
        self.chunk_bytes, self.fan_in = chunk_bytes, fan_in
        self.buffer, self.buffer_bytes, self.runs, self.all_runs = [], 0, [], []

    def add(self, key, row):
        self.buffer.append((key, row))
        self.buffer_bytes += len(row)
        if self.buffer_bytes >= self.chunk_bytes or len(self.buffer) >= 500000:
            self.flush()

    def new_run(self, rows):
        path = self.root / ("sort-run-%06d.tsv" % len(self.all_runs))
        digest = hashlib.sha256()
        count = 0
        pending = bytearray()
        with self.budget.create(path) as stream:
            for row in rows:
                pending.extend(row)
                if len(pending) >= 65536:
                    self.budget.write(stream, pending)
                    pending.clear()
                digest.update(row)
                count += 1
            if pending:
                self.budget.write(stream, pending)
        self.all_runs.append({"file": path.name, "bytes": path.stat().st_size, "rows": count, "sha256": digest.hexdigest()})
        return path

    def flush(self):
        if self.buffer:
            self.buffer.sort(key=lambda item: item[0])
            self.runs.append(self.new_run(row for _, row in self.buffer))
            self.buffer, self.buffer_bytes = [], 0

    @staticmethod
    def merged(paths):
        with contextlib.ExitStack() as stack:
            readers = [stack.enter_context(p.open("rb")) for p in paths]
            yield from heapq.merge(*readers, key=index_row)

    def finish(self):
        self.flush()
        while len(self.runs) > self.fan_in:
            merged = []
            for start in range(0, len(self.runs), self.fan_in):
                merged.append(self.new_run(self.merged(self.runs[start:start+self.fan_in])))
            self.runs = merged
        return self.merged(self.runs)


def verify_tbi(path, contigs, data_bytes):
    """Parse the complete bounded TBI, including all bins and linear offsets."""
    with gzip.open(path, "rb") as stream:
        data = stream.read(INDEX_RESERVE + 1)
    require(len(data) <= INDEX_RESERVE and data[:4] == b"TBI\x01", "TBI_FORMAT_OR_SIZE_INVALID")
    offset = 4
    def take(fmt):
        nonlocal offset
        size = struct.calcsize(fmt)
        require(offset + size <= len(data), "TBI_TRUNCATED")
        value = struct.unpack_from(fmt, data, offset)
        offset += size
        return value
    nref, preset, seq, begin, end, comment, skip, names_len = take("<8i")
    require((nref, preset, seq, begin, end, comment, skip) == (len(contigs), 0, 1, 2, 2, 35, 0), "TBI_COLUMN_BINDING_INVALID")
    require(0 <= names_len <= 128 and offset + names_len <= len(data), "TBI_NAMES_INVALID")
    names = data[offset:offset+names_len].decode("ascii").rstrip("\x00").split("\x00") if names_len else []
    offset += names_len
    require(names == contigs, "TBI_CONTIG_IDENTITY_MISMATCH")
    for _ in range(nref):
        nbin, = take("<i")
        require(0 <= nbin <= 32769, "TBI_BIN_COUNT_INVALID")
        seen = set()
        for _ in range(nbin):
            bin_id, chunks = take("<Ii")
            require(bin_id not in seen, "TBI_BIN_DUPLICATE")
            seen.add(bin_id)
            require((4681 <= bin_id <= 37448 and chunks == 1) or (bin_id == 37450 and chunks == 2), "TBI_POINT_BIN_INVALID")
            for _ in range(chunks):
                lo, hi = take("<QQ")
                if bin_id != 37450:
                    require(0 <= lo <= hi <= (data_bytes << 16), "TBI_VIRTUAL_OFFSET_INVALID")
        intervals, = take("<i")
        require(0 <= intervals <= 32768, "TBI_LINEAR_COUNT_INVALID")
        for _ in range(intervals):
            value, = take("<Q")
            require(value <= data_bytes << 16, "TBI_LINEAR_OFFSET_INVALID")
    require(len(data)-offset in (0, 8), "TBI_TRAILING_DATA")
    if len(data)-offset == 8:
        require(take("<Q")[0] == 0, "TBI_UNLOCATED_RECORDS")


def query_check(tabix, data, region, expected):
    process = subprocess.Popen([str(tabix), "-D", str(data), region], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **process_options())
    count = size = 0
    digest = hashlib.sha256()
    newline_modes = set()
    try:
        for line in iter(lambda: process.stdout.readline(MAX_LINE + 128), b""):
            # The installed native Windows tabix uses text stdout (CRLF).
            # This is transport-only normalization; BGZF raw rows were already
            # completely verified as LF, with no CR in publisher content.
            if line.endswith(b"\r\n"):
                newline_modes.add("CRLF_STDOUT")
                line = line[:-2] + b"\n"
            else:
                newline_modes.add("LF_STDOUT")
            count += 1
            size += len(line)
            require(size <= expected["bytes"] and count <= expected["rows"], "TABIX_QUERY_EXTRA_DATA")
            digest.update(line)
        require(process.wait(timeout=60) == 0, "TABIX_QUERY_FAILED")
        require((count, size, digest.hexdigest()) == (expected["rows"], expected["bytes"], expected["sha256"]), "TABIX_QUERY_IDENTITY_MISMATCH")
        return sorted(newline_modes)
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)


def build(args):
    require(0 < args.maximum_disk_bytes <= MAX_DISK and args.maximum_disk_bytes > METADATA_RESERVE, "DISK_LIMIT_INVALID")
    require(1024 <= args.chunk_bytes <= 134217728 and 2 <= args.fan_in <= 64, "SORT_LIMIT_INVALID")
    require(args.expected_zip_bytes > 0 and args.expected_member_bytes > len(HEADER) and args.maximum_data_rows > 0, "SOURCE_LIMIT_INVALID")
    require(re.fullmatch("[0-9a-f]{64}", args.expected_zip_sha256) is not None, "ZIP_SHA_REQUIRED")
    require(re.fullmatch("[0-9a-f]{8}", args.expected_member_crc32) is not None, "MEMBER_CRC_REQUIRED")
    require(args.expected_member_sha256 is None or re.fullmatch("[0-9a-f]{64}", args.expected_member_sha256), "MEMBER_SHA_INVALID")
    source = Path(args.source_zip).resolve(strict=True)
    require(source.is_file(), "SOURCE_ZIP_NOT_FILE")
    output = Path(args.output).resolve()
    require(output.parent.is_dir() and not output.exists(), "NEW_OUTPUT_DIRECTORY_REQUIRED")
    bgzip, tabix = (Path(x).resolve(strict=True) for x in (args.bgzip, args.tabix))
    require(bgzip.is_file() and tabix.is_file(), "EXISTING_TOOLS_REQUIRED")
    output.mkdir()
    budget = DiskBudget(output, args.maximum_disk_bytes)
    sinks = []
    state = {"schemaVersion": "revel-publisher-lookup-build-v1", "toolVersion": VERSION, "status": "BUILDING", "startedAt": now(),
             "publisherLookupReady": False, "nativeReferenceVerified": False, "nativeCdsVerified": False,
             "classificationEnabled": False, "defaultEnabled": False, "providerCalls": 0, "networkCalls": 0,
             "sourceLineConvention": "One-based data-row ordinal; header excluded. rawCsv excludes its original single LF; output adds one LF.",
             "sourceMappingMethod": "REVEL_publisher_liftOver", "sourceOriginalAssembly": "GRCh37", "sourcePublishedAssembly": "GRCh38",
             "sourceTranscriptVersionStatus": "UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM", "maximumDiskBytes": args.maximum_disk_bytes,
             "producerSha256": file_sha(Path(__file__).resolve()),
             "source": {"zipFile": str(source), "expectedZipBytes": args.expected_zip_bytes, "expectedZipSha256": args.expected_zip_sha256,
                        "member": args.member_name, "expectedMemberBytes": args.expected_member_bytes, "expectedMemberCrc32": args.expected_member_crc32,
                        "expectedMemberSha256": args.expected_member_sha256}, "tools": {}, "sourceDataRows": 0, "indexedRows": 0, "excludedRows": 0}
    try:
        for label, path in (("bgzip", bgzip), ("tabix", tabix)):
            require(path.stat().st_size <= 20000000, "TOOL_SIZE_UNEXPECTED")
            state["tools"][label] = {"path": str(path), "sha256": file_sha(path), "version": tool_call([path, "--version"]).decode("utf-8").strip()}
        stamp = source.stat()
        require(stamp.st_size == args.expected_zip_bytes, "ZIP_SIZE_MISMATCH")
        require(file_sha(source) == args.expected_zip_sha256, "ZIP_SHA_MISMATCH")
        sorter = ExternalSort(output, budget, args.chunk_bytes, args.fan_in)
        excluded_path = output / "revel-unlocated-source-rows.tsv.gz"
        excluded = BgzipSink(bgzip, excluded_path, budget, sinks)
        excluded.write(EXCLUDED_HEADER)
        excluded_digest = hashlib.sha256(EXCLUDED_HEADER)
        projected_sum = 0
        member_hash = hashlib.sha256()
        member_crc = member_bytes = 0
        reasons, annotations, raw_flags = collections.Counter(), collections.Counter(), collections.Counter()
        with zipfile.ZipFile(source, "r") as archive:
            entries = archive.infolist()
            require(len(entries) == 1 and entries[0].filename == args.member_name, "EXACT_SINGLE_ZIP_MEMBER_REQUIRED")
            info = entries[0]
            require(not info.is_dir() and not info.flag_bits & 1 and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "ZIP_MEMBER_FORMAT_UNSUPPORTED")
            require(info.file_size == args.expected_member_bytes and "%08x" % info.CRC == args.expected_member_crc32, "ZIP_MEMBER_IDENTITY_MISMATCH")
            with archive.open(info, "r") as stream:
                header = stream.readline(MAX_LINE + 1)
                require(header == HEADER, "SOURCE_HEADER_MISMATCH")
                member_hash.update(header); member_crc = zlib.crc32(header); member_bytes += len(header)
                while True:
                    raw = stream.readline(MAX_LINE + 1)
                    if not raw:
                        break
                    member_hash.update(raw); member_crc = zlib.crc32(raw, member_crc); member_bytes += len(raw)
                    require(member_bytes <= args.expected_member_bytes, "MEMBER_BYTE_LIMIT_EXCEEDED")
                    state["sourceDataRows"] += 1
                    require(state["sourceDataRows"] <= args.maximum_data_rows, "SOURCE_ROW_LIMIT_EXCEEDED")
                    key, reason, row = source_row(raw, state["sourceDataRows"])
                    cells = raw[:-1].decode("ascii").split(",")
                    for field, value in zip(FIELD_NAMES, cells):
                        if value in ("", ".", "NA", "nan", "NaN"):
                            annotations[field + ":" + repr(value)] += 1
                    raw_flags.update(annotation_flags(cells))
                    if reason:
                        reasons[reason] += 1; state["excludedRows"] += 1
                        excluded.write(row); excluded_digest.update(row)
                    else:
                        state["indexedRows"] += 1
                        projected_sum = (projected_sum + int.from_bytes(hashlib.sha256(row).digest(), "big")) % MODULUS
                        sorter.add(key, row)
        excluded.close()
        require(member_bytes == args.expected_member_bytes and "%08x" % member_crc == args.expected_member_crc32, "COMPLETE_MEMBER_SIZE_OR_CRC_MISMATCH")
        require(args.expected_member_sha256 is None or member_hash.hexdigest() == args.expected_member_sha256, "MEMBER_SHA_MISMATCH")
        current = source.stat()
        require((stamp.st_size, stamp.st_mtime_ns, stamp.st_ino) == (current.st_size, current.st_mtime_ns, current.st_ino), "SOURCE_ZIP_CHANGED_DURING_BUILD")
        state["source"].update({"verifiedZipSha256": args.expected_zip_sha256, "memberSha256": member_hash.hexdigest(), "memberCrc32": "%08x" % member_crc,
                               "memberBytes": member_bytes, "memberSha256Check": "MATCHED_EXPECTED" if args.expected_member_sha256 else "COMPUTED_FROM_FULL_SHA_PINNED_ZIP"})
        state["excludedReasons"], state["rawMissingCellCounts"] = dict(reasons), dict(annotations)
        state["rawAnnotationFlagCounts"] = dict(raw_flags)
        state["rawAnnotationFlagsDoNotFilterRowsOrEstablishScientificValidity"] = True
        require(state["sourceDataRows"] == state["indexedRows"] + state["excludedRows"], "SOURCE_ROW_ACCOUNTING_MISMATCH")
        require(state["indexedRows"] > 0, "NO_INDEXABLE_PUBLISHER_ROWS")
        data_path = output / "revel-grch38.lookup.tsv.gz"
        compressed = BgzipSink(bgzip, data_path, budget, sinks)
        compressed.write(INDEX_HEADER)
        ordered_digest = hashlib.sha256(INDEX_HEADER)
        pending = bytearray()
        for row in sorter.finish():
            pending.extend(row)
            if len(pending) >= 65536:
                compressed.write(pending); pending.clear()
            ordered_digest.update(row)
        if pending:
            compressed.write(pending)
        compressed.close()
        tool_call([bgzip, "-t", data_path], timeout=3600); tool_call([bgzip, "-t", excluded_path], timeout=3600)
        # Whole output re-read, not a first-N sample. Multiset sum + exact count
        # binds all projected original rows; strict key ordering retains duplicates
        # through their distinct source ordinals.
        digest, count, checksum, previous = hashlib.sha256(), 0, 0, None
        boundaries, current_position, coordinate_digest, coordinate_rows, coordinate_bytes = {}, None, None, 0, 0
        def finish_coordinate():
            if current_position is not None:
                chrom, pos = current_position
                item = {"position": pos, "rows": coordinate_rows, "bytes": coordinate_bytes, "sha256": coordinate_digest.hexdigest()}
                if chrom not in boundaries:
                    boundaries[chrom] = {"first": item, "last": item}
                else:
                    boundaries[chrom]["last"] = item
        with gzip.open(data_path, "rb") as stream:
            header = stream.readline(MAX_LINE + 128); require(header == INDEX_HEADER, "INDEX_HEADER_INVALID"); digest.update(header)
            for row in iter(lambda: stream.readline(MAX_LINE + 128), b""):
                key = index_row(row)
                require(previous is None or previous < key, "INDEX_ORDER_OR_ORDINAL_DUPLICATE")
                require(key[2] <= state["sourceDataRows"], "INDEX_ORDINAL_OUTSIDE_SOURCE")
                previous = key; count += 1; digest.update(row)
                checksum = (checksum + int.from_bytes(hashlib.sha256(row).digest(), "big")) % MODULUS
                position = key[:2]
                if position != current_position:
                    finish_coordinate(); current_position = position
                    coordinate_digest, coordinate_rows, coordinate_bytes = hashlib.sha256(), 0, 0
                coordinate_digest.update(row); coordinate_rows += 1; coordinate_bytes += len(row)
            finish_coordinate()
        require(count == state["indexedRows"] and checksum == projected_sum and digest.hexdigest() == ordered_digest.hexdigest(), "FULL_INDEX_PROJECTION_IDENTITY_MISMATCH")
        excluded_check = hashlib.sha256(); excluded_count = 0
        with gzip.open(excluded_path, "rb") as stream:
            header = stream.readline(MAX_LINE + 128); require(header == EXCLUDED_HEADER, "EXCLUDED_HEADER_INVALID"); excluded_check.update(header)
            for row in iter(lambda: stream.readline(MAX_LINE + 128), b""):
                cells = row.split(b"\t", 2); require(len(cells) == 3, "EXCLUDED_ROW_INVALID")
                ordinal = int(cells[1]); key, reason, rebuilt = source_row(cells[2], ordinal)
                require(key is None and reason and rebuilt == row and 1 <= ordinal <= state["sourceDataRows"], "EXCLUDED_SOURCE_IDENTITY_MISMATCH")
                excluded_check.update(row); excluded_count += 1
        require(excluded_check.hexdigest() == excluded_digest.hexdigest() and excluded_count == state["excludedRows"], "COMPLETE_EXCLUDED_IDENTITY_MISMATCH")
        state["validation"] = {"fullBgzfIntegrityTest": True, "fullIndexedRowsReparsed": count, "fullExcludedRowsReparsed": excluded_count,
                               "indexedUncompressedSha256": ordered_digest.hexdigest(), "excludedUncompressedSha256": excluded_digest.hexdigest(),
                               "projectedRowMultisetSumSha256": "%064x" % projected_sum, "referenceQueries": 0, "criterionEvaluations": 0}
        # Native primary-contig points (<2^29) yield at most 25*32768 leaf bins,
        # one chunk per monotonic bin, plus linear indexes: <32MiB plain TBI.
        # Reserve 64MiB including BGZF overhead before allowing tabix's file write.
        tbi_path = data_path.with_name(data_path.name + ".tbi")
        budget.reserve_index()
        try:
            tool_call([tabix, "-s", "1", "-b", "2", "-e", "2", "-c", "#", data_path], timeout=3600)
        finally:
            budget.settle_index(tbi_path)
        verify_tbi(tbi_path, list(boundaries), data_path.stat().st_size)
        require(tool_call([tabix, "-l", data_path]).decode("ascii").splitlines() == list(boundaries), "TABIX_CONTIG_QUERY_MISMATCH")
        query_count, stdout_modes = 0, set()
        for chrom, limits in boundaries.items():
            selected = {value["position"]: value for value in limits.values()}
            for position, expected in selected.items():
                stdout_modes.update(query_check(tabix, data_path, "%s:%d-%d" % (chrom, position, position), expected)); query_count += 1
        state["validation"].update({"completeTbiParsed": True, "tabixBoundaryQueries": query_count, "contigs": list(boundaries), "tabixStdoutNewlineModes": sorted(stdout_modes)})
        state["artifacts"] = [{"file": p.name, "bytes": p.stat().st_size, "sha256": file_sha(p)} for p in (data_path, tbi_path, excluded_path)]
        state["sort"] = {"chunkBytes": args.chunk_bytes, "maximumBufferedRecords": 500000, "fanIn": args.fan_in, "temporaryRuns": sorter.all_runs,
                         "cleanupBoundary": "Remove only these owned temporary files after COMPLETE manifest and all data/index checks."}
        final_stamp = source.stat()
        require((stamp.st_size, stamp.st_mtime_ns, stamp.st_ino) == (final_stamp.st_size, final_stamp.st_mtime_ns, final_stamp.st_ino), "SOURCE_ZIP_CHANGED_DURING_BUILD")
        state.update({"status": "COMPLETE", "publisherLookupReady": True, "finishedAt": now(), "peakReservedLogicalDiskBytes": budget.peak,
                      "logicalDiskBytesBeforeCleanup": budget.used, "runtimeActivation": "NOT_REGISTERED_NATIVE_REF_CDS_TRANSCRIPT_AND_CONFLICT_CHECKS_STILL_REQUIRED"})
        budget.json_file("manifest.pending.json", state)
        pending_manifest = output / "manifest.pending.json"
        require(pending_manifest.read_bytes() == canonical(state), "COMPLETE_MANIFEST_RECHECK_FAILED")
        manifest_path = output / "manifest.json"
        budget.rename_owned(pending_manifest, manifest_path)
        cleanup = {"schemaVersion": "revel-index-cleanup-v1", "completeManifestSha256": file_sha(manifest_path), "removedFiles": [], "retainedDueToError": []}
        for entry in sorter.all_runs:
            path = output / entry["file"]
            try:
                require(file_sha(path) == entry["sha256"], "TEMPORARY_HASH_CHANGED")
                budget.remove_owned(path); cleanup["removedFiles"].append(entry["file"])
            except (OSError, ValueError) as error:
                cleanup["retainedDueToError"].append({"file": entry["file"], "reason": str(error)})
        cleanup.update({"status": "COMPLETE" if not cleanup["retainedDueToError"] else "PARTIAL", "logicalDiskBytesAfterCleanup": budget.used})
        budget.json_file("cleanup.json", cleanup)
        return state
    except BaseException as error:
        for sink in sinks:
            with contextlib.suppress(Exception):
                sink.abort()
        if not (output / "manifest.json").exists():
            state.update({"status": "CANCELLED" if isinstance(error, (KeyboardInterrupt, SystemExit)) else "FAILED", "publisherLookupReady": False,
                          "error": type(error).__name__ + ": " + str(error), "finishedAt": now(), "peakReservedLogicalDiskBytes": budget.peak,
                          "logicalDiskBytesRetained": budget.used, "failurePrefixMustNotBeUsed": True})
            with contextlib.suppress(Exception):
                budget.json_file("failure-manifest.json", state)
        raise


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("source-zip", "expected-zip-sha256", "member-name", "expected-member-crc32", "output", "bgzip", "tabix"):
        p.add_argument("--" + name, required=True)
    for name in ("expected-zip-bytes", "expected-member-bytes"):
        p.add_argument("--" + name, required=True, type=int)
    p.add_argument("--expected-member-sha256")
    p.add_argument("--maximum-disk-bytes", type=int, default=MAX_DISK)
    p.add_argument("--maximum-data-rows", type=int, default=500000000)
    p.add_argument("--chunk-bytes", type=int, default=33554432)
    p.add_argument("--fan-in", type=int, default=64)
    return p


if __name__ == "__main__":
    result = build(parser().parse_args())
    print(json.dumps({k: result[k] for k in ("status", "publisherLookupReady", "sourceDataRows", "indexedRows", "excludedRows", "validation", "artifacts")}, indent=2))
