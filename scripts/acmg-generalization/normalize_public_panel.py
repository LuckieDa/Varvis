"""Bounded public-identity REF verification using the production bcftools norm engine.

Only masked source projections are inputs. No ClinVar classifications, inference,
resource download, whole-FASTA rehash, rule evaluation or panel selection.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import threading

VERSION = "1.0.1"
RAW_HASH = re.compile(r"[0-9a-f]{64}")
BASES = re.compile(r"[ACGT]+")
PRIMARY = re.compile(r"(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def sha(content):
    return hashlib.sha256(content).hexdigest()


def number(value, minimum=0, maximum=2147483647):
    return type(value) is int and minimum <= value <= maximum


def digest(value):
    return type(value) is str and RAW_HASH.fullmatch(value) is not None


def fields(value, required, optional=""):
    require(type(value) is dict and set(required.split()) <= set(value)
            and set(value) <= set((required + " " + optional).split()), "UNKNOWN_OR_MISSING_FIELD")


def strict_json(content):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError("NONFINITE_JSON_NUMBER")
    return json.loads(content, object_pairs_hook=pairs, parse_constant=invalid_constant)


def bounded_bytes(path, maximum):
    with Path(path).open("rb") as stream:
        content = stream.read(maximum + 1)
    require(len(content) <= maximum, "FILE_SIZE_LIMIT")
    return content


def pinned_bytes(path, expected_sha, maximum):
    require(digest(expected_sha), "EXPECTED_SOURCE_SHA_REQUIRED")
    content = bounded_bytes(path, maximum)
    require(sha(content) == expected_sha, "SOURCE_SHA_MISMATCH")
    return content


def envelope(position, ref, alt):
    if not number(position, 1):
        return "SOURCE_POSITION_INVALID"
    if type(ref) is not str or type(alt) is not str:
        return "SOURCE_ALLELE_TYPE_INVALID"
    if "," in alt:
        return "MULTIALLELIC_SOURCE_REVIEW_REQUIRED"
    if not 1 <= len(ref) <= 50 or not 1 <= len(alt) <= 50 or not BASES.fullmatch(ref) or not BASES.fullmatch(alt) or ref == alt:
        return "ALLELE_ENVELOPE_EXCLUDED"
    if not (len(ref) == len(alt) == 1 or len(ref) == 1 and alt.startswith(ref) or len(alt) == 1 and ref.startswith(alt)):
        return "COMPLEX_REPLACEMENT_EXCLUDED"
    return None


class BoundedReference:
    """Apply an accepted full-checksum receipt; read only scheduled query windows."""
    def __init__(self, resource_root, receipt_path, receipt_sha, source_id, source_version, locator,
                 expected_reference_sha, alias_path, alias_sha, windows, policy):
        self.root = Path(resource_root).resolve(strict=True)
        require(not str(self.root).startswith(("\\\\", "//")), "NETWORK_REFERENCE_FORBIDDEN")
        receipt_bytes = pinned_bytes(receipt_path, receipt_sha, 1_048_576)
        envelope = strict_json(receipt_bytes)
        require(envelope.get("source") == source_id and envelope.get("version") == source_version
                and type(envelope.get("records")) is dict and locator in envelope["records"], "REFERENCE_RECEIPT_SOURCE_MISMATCH")
        record = envelope["records"][locator]
        receipt = record.get("actualChecksumReceipt", {})
        require(record.get("kind") == "REFERENCE_IDENTITY" and record.get("assembly") == "GRCh38"
                and record.get("resourceDataClass") == "PUBLIC" and record.get("verificationStatus") == "VERIFIED"
                and record.get("verificationMethod") == "SHA-256"
                and record.get("observedSha256") == record.get("expectedSha256") == expected_reference_sha,
                "REFERENCE_IDENTITY_RECORD_INVALID")
        require(digest(expected_reference_sha) and receipt.get("observedSha256") == receipt.get("expectedSha256") == expected_reference_sha
                and receipt.get("assembly") == "GRCh38" and receipt.get("hashStatus") == "VERIFIED"
                and receipt.get("hashAlgorithm") == "SHA-256" and receipt.get("hashScope") == "RAW_BYTES"
                and receipt.get("unchangedDuringHash") is True and type(receipt.get("resourceId")) is str
                and receipt["resourceId"] and type(receipt.get("resourceVersion")) is str and receipt["resourceVersion"],
                "REFERENCE_FULL_CHECKSUM_RECEIPT_INVALID")
        for key in ("startedAt", "completedAt"):
            require(type(receipt.get(key)) is str and receipt[key].endswith("Z"), "REFERENCE_CHECKSUM_TIME_INVALID")
        started, completed = (datetime.datetime.fromisoformat(receipt[key].replace("Z", "+00:00")) for key in ("startedAt", "completedAt"))
        require(started <= completed <= datetime.datetime.now(datetime.timezone.utc), "REFERENCE_CHECKSUM_TIME_INVALID")
        logical = receipt.get("logicalName")
        require(type(logical) is str and logical and not logical.startswith(("/", "\\")) and ":" not in logical
                and ".." not in logical.replace("\\", "/").split("/")
                and record.get("referenceArtifactLocator") == logical, "REFERENCE_LOGICAL_PATH_INVALID")
        self.fasta = (self.root / logical).resolve(strict=True)
        require(self.root in self.fasta.parents and self.fasta.is_file(), "REFERENCE_RESOURCE_BOUNDARY_INVALID")
        self.before = self.stamp()
        require(number(receipt.get("byteLength"), 1, 2**63 - 1) and number(receipt.get("mtimeNs"), 1, 2**63 - 1)
                and (receipt["byteLength"], receipt["mtimeNs"]) == self.before[:2], "REFERENCE_RECEIPT_NOT_CURRENTLY_APPLICABLE")
        self.index_path = Path(str(self.fasta) + ".fai")
        self.index_sha = receipt.get("indexSha256")
        index_bytes = pinned_bytes(self.index_path, self.index_sha, 4_194_304)
        self.index = {}
        for line in index_bytes.decode("ascii").splitlines():
            parts = line.split("\t")
            require(len(parts) == 5 and re.fullmatch(r"[A-Za-z0-9_.-]+", parts[0]) and parts[0] not in self.index
                    and all(re.fullmatch(r"[0-9]+", value) for value in parts[1:]), "FAI_FORMAT_INVALID")
            size, offset, bases, width = map(int, parts[1:])
            require(0 < size <= 2147483647 and 0 < offset < self.before[0] and 0 < bases <= size
                    and bases + 1 <= width <= bases + 2
                    and offset + (size - 1) // bases * width + (size - 1) % bases < self.before[0], "FAI_LAYOUT_INVALID")
            self.index[parts[0]] = (size, offset, bases, width)
        self.alias_path, self.alias_sha = Path(alias_path), alias_sha
        aliases = pinned_bytes(alias_path, alias_sha, 1_048_576)
        self.aliases = {}
        for line in aliases.decode("utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split()
            require(len(parts) == 2 and parts[0] not in self.aliases, "EXPLICIT_CONTIG_ALIAS_INVALID")
            self.aliases[parts[0]] = parts[1]
        self.windows, self.policy = windows, policy
        self.reads, self.charged_bases, self.returned_bases, self.cache, self.observations = 0, 0, 0, {}, []
        self.identity = dict(assembly="GRCh38", fullReferenceHashComputed=False,
                             method="ACCEPTED_FULL_CHECKSUM_RECEIPT_CURRENT_METADATA_AND_INDEX",
                             acceptedReceiptSha256=receipt_sha, sourceId=source_id, sourceVersion=source_version, recordLocator=locator,
                             actualChecksumReceipt=receipt, referenceLogicalPath=logical, indexSha256=self.index_sha,
                             explicitContigAliasSha256=alias_sha,
                             boundary="Previously completed whole-file checksum; current metadata/index applicability, not a new full-file hash")

    def stamp(self):
        value = self.fasta.stat()
        return value.st_size, value.st_mtime_ns, value.st_dev, value.st_ino

    def recheck(self):
        require(self.stamp() == self.before, "REFERENCE_CHANGED_DURING_PREPARATION")
        pinned_bytes(self.index_path, self.index_sha, 4_194_304)
        pinned_bytes(self.alias_path, self.alias_sha, 1_048_576)

    def contig(self, requested):
        name = requested if requested in self.index else self.aliases.get(requested)
        require(name in self.index, "REFERENCE_CONTIG_OR_EXPLICIT_ALIAS_MISSING")
        return name

    def read(self, window_id, extension, needed_right):
        require(window_id in self.windows and extension in self.policy["cumulativeLeftExtensions"], "UNAUTHORIZED_WINDOW_QUERY")
        window = self.windows[window_id]
        require(number(needed_right) and needed_right == window["end"] + self.policy["initialRightPaddingBases"],
                "UNAUTHORIZED_RIGHT_REF_COMPLETION")
        reference_contig = self.contig(window["contig"])
        size, offset, bases, width = self.index[reference_contig]
        start, end = max(1, window["start"] - extension), min(size, needed_right)
        require(start <= end and end - start + 1 <= self.policy["maximumWindowBases"], "REFERENCE_QUERY_WINDOW_INVALID")
        self.recheck()
        key = window_id, start, end
        if key in self.cache:
            return self.cache[key]
        count = end - start + 1
        require(self.reads < self.policy["maximumWindowReads"]
                and self.charged_bases + count <= self.policy["maximumTotalFastaBases"], "REFERENCE_QUERY_BUDGET_EXHAUSTED")
        self.reads += 1
        self.charged_bases += count  # Reserve before any sequence read; do not refund failures.
        position, pieces = start - 1, []
        with self.fasta.open("rb") as stream:
            while position < end:
                take = min(bases - position % bases, end - position)
                stream.seek(offset + position // bases * width + position % bases)
                piece = stream.read(take)
                self.returned_bases += len(piece)
                require(len(piece) == take, "REFERENCE_WINDOW_TRUNCATED")
                pieces.append(piece)
                position += take
        raw = b"".join(pieces)
        sequence = raw.decode("ascii").upper()
        require(re.fullmatch(r"[ACGTN]+", sequence) is not None, "REFERENCE_SEQUENCE_ALPHABET_INVALID")
        self.recheck()
        locator = dict(windowId=window_id, contig=window["contig"], referenceContig=reference_contig, start=start, end=end,
                       coordinateConvention="ONE_BASED_INCLUSIVE", cumulativeLeftExtension=extension,
                       rightRefCompletionBases=max(0, end - window["end"]), naturalChromosomeEndClipped=end < needed_right,
                       rawQueriedBasesSha256=sha(raw), normalizedCaseBasesSha256=sha(sequence.encode("ascii")),
                       softMaskCaseNormalized=raw != sequence.encode("ascii"), hashScope="QUERIED_BASES_ONLY_NOT_WHOLE_FASTA")
        self.observations.append(locator)
        self.cache[key] = sequence, locator
        return self.cache[key]


class BcftoolsWindowEngine:
    """Run the unchanged production norm options against explicit local slices."""
    def __init__(self, executable, expected_sha, version, htslib_version, workspace, artifacts=None):
        self.executable = Path(executable).resolve(strict=True)
        self.expected_sha = expected_sha
        pinned_bytes(self.executable, expected_sha, 50_000_000)
        self.workspace = Path(workspace)
        self.artifacts = artifacts or Artifacts(self.workspace, 268435456, 1048576)
        self.flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        output = self._run(["--version"], self.workspace, 30)
        lines = output.decode("utf-8").splitlines()
        require(lines[:2] == ["bcftools " + version, "Using htslib " + htslib_version], "BCFTOOLS_VERSION_MISMATCH")
        self.identity = dict(engine="bcftools norm", version=version, htslibVersion=htslib_version,
                             executableSha256=expected_sha, normalizationOptions=["norm", "-f", "<window-fasta>",
                             "-c", "e", "-m", "-any", "--old-rec-tag", "VARVIS_ORIGINAL_VARIANT"],
                             scope="EXPLICIT_NATIVE_WINDOW_COORDINATE_BRIDGE_NO_NEW_ASSEMBLY")
        self.calls = 0

    def _run(self, arguments, cwd, timeout):
        result = subprocess.run([str(self.executable)] + arguments, cwd=cwd, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
                                check=False, creationflags=self.flags)
        require(len(result.stdout) + len(result.stderr) <= 1_048_576, "BCFTOOLS_LOG_LIMIT")
        if result.returncode != 0:
            raise ValueError("BCFTOOLS_EXECUTION_FAILED:" + result.stderr.decode("utf-8", errors="replace")[:300])
        return result.stdout

    def normalize(self, windows, round_number):
        """Each window has id, sequence, and records of id/localPosition/ref/alt."""
        require(number(round_number, 0, 2) and 1 <= len(windows) <= 129, "ENGINE_BATCH_BOUND_INVALID")
        directory = self.workspace / ("round-" + str(round_number))
        directory.mkdir()
        fasta, vcf, output = (directory / name for name in ("windows.fa", "input.vcf", "normalized.vcf"))
        expected, offset, fai = {}, 0, []
        with self.artifacts.open(fasta) as sequence_file, self.artifacts.open(vcf, text=True) as variant_file:
            variant_file.write("##fileformat=VCFv4.2\n")
            seen_windows = set()
            for window in windows:
                identifier, sequence = window["id"], window["sequence"]
                require(re.fullmatch(r"W[0-9]{1,4}", identifier) and identifier not in seen_windows
                        and re.fullmatch(r"[ACGTN]+", sequence) and 1 <= len(sequence) <= 35049, "ENGINE_WINDOW_INVALID")
                seen_windows.add(identifier)
                header = (">" + identifier + "\n").encode("ascii")
                sequence_file.write(header + sequence.encode("ascii") + b"\n")
                offset += len(header)
                fai.append("%s\t%d\t%d\t%d\t%d\n" % (identifier, len(sequence), offset, len(sequence), len(sequence) + 1))
                offset += len(sequence) + 1
                variant_file.write("##contig=<ID=%s,length=%d>\n" % (identifier, len(sequence)))
            variant_file.write("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n")
            for window in windows:
                for record in sorted(window["records"], key=lambda value: (value["localPosition"], value["id"])):
                    identifier, position = record["id"], record["localPosition"]
                    require(re.fullmatch(r"R[0-9]{1,6}", identifier) and identifier not in expected
                            and envelope(position, record["ref"], record["alt"]) is None, "ENGINE_INPUT_IDENTITY_INVALID")
                    require(window["sequence"][position - 1:position - 1 + len(record["ref"])] == record["ref"], "ENGINE_INPUT_REF_MISMATCH")
                    expected[identifier] = (window, record)
                    variant_file.write("%s\t%d\t%s\t%s\t%s\t.\t.\t.\n" %
                                       (window["id"], position, identifier, record["ref"], record["alt"]))
            require(0 < len(expected) <= 150000, "ENGINE_RECORD_BOUND_INVALID")
        with self.artifacts.open(Path(str(fasta) + ".fai"), text=True) as index:
            index.writelines(fai)
        arguments = ["norm", "-f", str(fasta), "-c", "e", "-m", "-any", "--old-rec-tag", "VARVIS_ORIGINAL_VARIANT",
                     str(vcf), "-Ov", "--no-version"]
        self.calls += 1
        pinned_bytes(self.executable, self.expected_sha, 50_000_000)
        self.artifacts.process([str(self.executable)] + arguments, directory, output, directory / "stderr.txt", self.flags)
        pinned_bytes(self.executable, self.expected_sha, 50_000_000)
        content = bounded_bytes(output, 67_108_864)
        results = {}
        for line_number, line in enumerate(content.decode("ascii").splitlines(), 1):
            if line.startswith("#"):
                continue
            columns = line.split("\t")
            require(len(columns) == 8 and columns[2] in expected and columns[2] not in results, "ENGINE_OUTPUT_IDENTITY_INVALID")
            window, original = expected[columns[2]]
            require(columns[0] == window["id"] and re.fullmatch(r"[1-9][0-9]*", columns[1])
                    and "," not in columns[4], "ENGINE_SPLIT_OR_COORDINATE_INVALID")
            position, ref, alt = int(columns[1]), columns[3], columns[4]
            require(1 <= position <= len(window["sequence"]) and position + len(ref) - 1 <= len(window["sequence"])
                    and 1 <= len(ref) <= 50 and 1 <= len(alt) <= 50 and ref != alt
                    and re.fullmatch(r"[ACGTN]+", ref) and re.fullmatch(r"[ACGTN]+", alt), "ENGINE_ALLELE_ENVELOPE_CHANGED")
            sequence = window["sequence"]
            require(sequence[position - 1:position - 1 + len(ref)] == ref, "ENGINE_NORMALIZED_REF_MISMATCH")
            before = sequence[:original["localPosition"] - 1] + original["alt"] + sequence[original["localPosition"] - 1 + len(original["ref"]):]
            after = sequence[:position - 1] + alt + sequence[position - 1 + len(ref):]
            require(before == after, "ENGINE_HAPLOTYPE_IDENTITY_MISMATCH")
            results[columns[2]] = dict(localPosition=position, ref=ref, alt=alt, originalRecordTag=columns[7],
                                      outputLocator=dict(round=round_number, file="round-%d/normalized.vcf" % round_number,
                                                         lineNumber=line_number, lineSha256=sha(line.encode("ascii"))))
        self.last_command = dict(arguments=[self.executable.name] + [Path(arg).name if arg in (str(fasta), str(vcf)) else arg for arg in arguments],
                                 executableSha256=self.identity["executableSha256"], exitCode=0,
                                 outputFile="round-%d/normalized.vcf" % round_number,
                                 outputSha256=sha(content), outputBytes=len(content))
        require(set(results) == set(expected), "ENGINE_RECORD_COUNT_CHANGED")
        return results


def canonical_key(contig, position, ref, alt):
    return "GRCh38|%s|%d|%s|%s" % (contig, position, ref, alt)


def normalize_candidates(candidates, windows, reference, engine, policy):
    """Keep every original representation; unfinished window edges have no key."""
    results, pending = {}, {}
    for item in candidates:
        require(item["id"] not in results and item["windowId"] in windows, "CANDIDATE_ID_OR_WINDOW_INVALID")
        result = dict(original=item["original"], source=item["source"], memberships=item["memberships"],
                      status="NOT_EVALUATED", referenceStatus="NOT_CHECKED", canonicalAllele=None, canonicalKey=None,
                      attempts=[], membershipStatusPreserved=True)
        results[item["id"]] = result
        reason = envelope(item["position"], item["ref"], item["alt"])
        if item["original"]["contig"] != windows[item["windowId"]]["contig"]:
            result.update(status="INCOMPLETE", reasonCode="SOURCE_CONTIG_QUERY_MISMATCH")
            continue
        if reason:
            result.update(status="EXCLUDED", reasonCode=reason)
        else:
            pending.setdefault(item["windowId"], []).append(item)
    for round_number, extension in enumerate(policy["cumulativeLeftExtensions"]):
        if not pending:
            break
        batches, submitted, next_pending = [], {}, {}
        for window_id, items in pending.items():
            window = windows[window_id]
            admissible = []
            for item in items:
                if item["position"] + len(item["ref"]) - 1 < window["start"] or item["position"] > window["end"]:
                    results[item["id"]].update(status="INCOMPLETE", reasonCode="SOURCE_ALLELE_OUTSIDE_FIXED_QUERY_WINDOW")
                else:
                    admissible.append(item)
            if not admissible:
                continue
            right = window["end"] + policy["initialRightPaddingBases"]
            try:
                sequence, locator = reference.read(window_id, extension, right)
            except ValueError as error:
                # A hard budget gap never turns the last attempted representation
                # into a canonical allele; source identity failures abort globally.
                if str(error) not in ("REFERENCE_QUERY_BUDGET_EXHAUSTED", "REFERENCE_QUERY_WINDOW_INVALID", "REFERENCE_CONTIG_OR_EXPLICIT_ALIAS_MISSING"):
                    raise
                for item in admissible:
                    results[item["id"]].update(status="INCOMPLETE", reasonCode=str(error))
                continue
            records = []
            for item in admissible:
                result = results[item["id"]]
                result["attempts"].append(dict(window=locator, round=round_number))
                local = item["position"] - locator["start"] + 1
                if local < 1:
                    result.update(status="INCOMPLETE", reasonCode="SOURCE_REF_REQUIRES_AUTHORIZED_LEFT_CONTEXT")
                    next_pending.setdefault(window_id, []).append(item)
                    continue
                if local + len(item["ref"]) - 1 > len(sequence):
                    result.update(status="EXCLUDED", referenceStatus="OUT_OF_RANGE", reasonCode="REFERENCE_ALLELE_EXCEEDS_NATIVE_CONTIG")
                    continue
                observed = sequence[local - 1:local - 1 + len(item["ref"])]
                result["observedReference"] = observed
                if "N" in observed:
                    result.update(status="INCOMPLETE", referenceStatus="AMBIGUOUS", reasonCode="AMBIGUOUS_NATIVE_REFERENCE")
                    continue
                if observed != item["ref"]:
                    result.update(status="EXCLUDED", referenceStatus="MISMATCH", reasonCode="NATIVE_REF_MISMATCH")
                    continue
                result["referenceStatus"] = "MATCH"
                records.append(dict(id=item["id"], localPosition=local, ref=item["ref"], alt=item["alt"]))
                submitted[item["id"]] = item, sequence, locator
            if records:
                batches.append(dict(id=window_id, sequence=sequence, records=records))
        if batches:
            normalized = engine.normalize(batches, round_number)
            for identifier, value in normalized.items():
                item, sequence, locator = submitted[identifier]
                result = results[identifier]
                result["attempts"][-1]["normalizedOutputLocator"] = value["outputLocator"]
                result["attempts"][-1]["commandReceipt"] = engine.last_command
                result["attempts"][-1]["normalizedLocalPosition"] = value["localPosition"]
                position = locator["start"] + value["localPosition"] - 1
                local_original = item["position"] - locator["start"]
                relevant_end = max(local_original + len(item["ref"]), value["localPosition"] - 1 + len(value["ref"]))
                if "N" in value["ref"] + value["alt"] or "N" in sequence[value["localPosition"] - 1:relevant_end]:
                    result.update(status="INCOMPLETE", reasonCode="AMBIGUOUS_NORMALIZATION_CONTEXT")
                    continue
                require(envelope(position, value["ref"], value["alt"]) is None, "NORMALIZED_ENVELOPE_CHANGED")
                snv = len(item["ref"]) == len(item["alt"]) == 1
                if snv:
                    require((position, value["ref"], value["alt"]) == (item["position"], item["ref"], item["alt"]), "SNV_IDENTITY_CHANGED")
                if not snv and value["localPosition"] == 1 and locator["start"] > 1:
                    result.update(status="INCOMPLETE", reasonCode="GLOBAL_LEFTMOST_NOT_PROVEN_WITHIN_AUTHORIZED_WINDOW")
                    next_pending.setdefault(item["windowId"], []).append(item)
                    continue
                allele = dict(assembly="GRCh38", contig=windows[item["windowId"]]["contig"], position=position,
                              reference=value["ref"], alternate=value["alt"])
                result.update(status="CANONICAL", canonicalAllele=allele,
                              canonicalKey=canonical_key(allele["contig"], position, value["ref"], value["alt"]),
                              leftShiftBases=item["position"] - position,
                              leftmostProof="SNV_POSITION_INVARIANT" if snv else "NATURAL_CHROMOSOME_START" if position == 1 else "BCFTOOLS_STOPPED_INSIDE_AUTHORIZED_NATIVE_WINDOW",
                              localHaplotypeEqualityVerified=True)
                result.pop("reasonCode", None)
        pending = next_pending
    reference.recheck()
    return [results[item["id"]] for item in candidates]


class Artifacts:
    """Charge all persisted bytes, including CLI slices/logs and partial failures."""
    def __init__(self, root, maximum, reserve):
        require(number(maximum, 1, 268435456) and number(reserve, 1, min(maximum, 1048576)), "OUTPUT_BUDGET_INVALID")
        self.root, self.maximum, self.reserve = Path(root), maximum, reserve
        self.used, self.paths, self.lock = 0, [], threading.Lock()

    @contextlib.contextmanager
    def open(self, path, text=False, manifest=False):
        owner = self
        path = Path(path)
        require(self.root.resolve() in path.resolve().parents and not path.exists(), "NEW_ARTIFACT_PATH_REQUIRED")
        with path.open("xb") as stream:
            self.paths.append(path)

            class Writer:
                def write(self, value):
                    data = value.encode("ascii") if text else value
                    with owner.lock:
                        limit = owner.maximum if manifest else owner.maximum - owner.reserve
                        require(len(data) <= limit - owner.used, "OUTPUT_ARTIFACT_BUDGET_EXHAUSTED")
                        owner.used += len(data)  # Reserve before write; failed writes are not refunded.
                        require(stream.write(data) == len(data), "OUTPUT_ARTIFACT_SHORT_WRITE")
                def writelines(self, values):
                    for value in values:
                        self.write(value)
            yield Writer()

    def json(self, name, value, manifest=False):
        content = canonical(value) + b"\n"
        require(not manifest or len(content) <= self.reserve, "MANIFEST_RESERVE_EXCEEDED")
        with self.open(self.root / name, manifest=manifest) as stream:
            stream.write(content)

    def process(self, command, directory, output, stderr, flags):
        # Pipes are drained concurrently into the same hard byte-accounted writer.
        # On any cap/timeout/write error terminate only this owned child, retaining
        # partial files. Never let -o write beyond the budget outside this writer.
        failures = []
        with self.open(output) as out, self.open(stderr) as err:
            process, threads = None, []
            try:
                process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=flags)
                def drain(pipe, writer, maximum):
                    total = 0
                    try:
                        while True:
                            data = pipe.read(65536)
                            if not data:
                                break
                            require(total + len(data) <= maximum, "BCFTOOLS_STREAM_LIMIT")
                            writer.write(data)
                            total += len(data)
                    except BaseException as error:
                        failures.append(error)
                        with contextlib.suppress(OSError):
                            process.kill()
                    finally:
                        pipe.close()
                threads = [threading.Thread(target=drain, args=(process.stdout, out, 67108864)),
                           threading.Thread(target=drain, args=(process.stderr, err, 1048576))]
                for thread in threads:
                    thread.start()
                process.wait(timeout=120)
            finally:
                if process is not None:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=10)
                for thread in threads:
                    if thread.ident is not None:
                        thread.join(timeout=10)
                require(all(not thread.is_alive() for thread in threads), "BCFTOOLS_READER_NOT_FINISHED")
                if process is not None:
                    process.stdout.close()
                    process.stderr.close()
            if failures:
                raise failures[0]
            require(process.returncode == 0, "BCFTOOLS_EXECUTION_FAILED")

    def identities(self):
        result = []
        for path in self.paths:
            digest_value = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(65536), b""):
                    digest_value.update(chunk)
            result.append(dict(file=path.relative_to(self.root).as_posix(), bytes=path.stat().st_size, sha256=digest_value.hexdigest()))
        return result


def validate_plan(plan, query):
    fields(plan, "schemaVersion planId version status reviewer scope bindings reference normalizer queryPlanSha256 windowPolicy alleleEnvelope execution budgets normalizationSemantics outputContract prospectiveTestsRequired executionOwnership normalizationOrRuleOutputsSeenToSetBudgets finalSelectedAlleles ruleEvaluations clinicalAcceptance", "supersedes")
    require(plan["schemaVersion"] == "public-panel-reference-normalization-plan-v1"
            and plan["status"] == "PROSPECTIVELY_FIXED_BEFORE_REFERENCE_READS"
            and plan["normalizationOrRuleOutputsSeenToSetBudgets"] is False and plan["clinicalAcceptance"] is False
            and type(plan["finalSelectedAlleles"]) is int and plan["finalSelectedAlleles"] == 0
            and type(plan["ruleEvaluations"]) is int and plan["ruleEvaluations"] == 0, "PLAN_EXECUTION_BOUNDARY_INVALID")
    bindings = plan["bindings"]
    fields(bindings, "frameSha256 queryPlanSha256 poolManifestSha256 maskedProjectionSha256 queryOccurrencesSha256 provisionalPrerankManifestSha256")
    require(all(digest(value) for value in bindings.values()) and plan["queryPlanSha256"] == bindings["queryPlanSha256"], "PLAN_BINDING_INVALID")
    fields(plan["reference"], "assembly resourceVersion logicalFastaPath fastaBytes fastaMtimeNs logicalFaiPath faiBytes faiSha256 wholeFastaSha256 checksumReceipt identityBoundary")
    receipt = plan["reference"]["checksumReceipt"]
    fields(receipt, "logicalPath fileSha256 locator resourceId hashScope hashStatus completedAt boundary")
    require(plan["reference"]["assembly"] == "GRCh38" and digest(plan["reference"]["wholeFastaSha256"])
            and digest(plan["reference"]["faiSha256"]) and digest(receipt["fileSha256"])
            and receipt["hashScope"] == "RAW_BYTES" and receipt["hashStatus"] == "VERIFIED", "PLAN_REFERENCE_FULL_RECEIPT_REQUIRED")
    normalizer = plan["normalizer"]
    fields(normalizer, "name expectedVersion logicalExecutablePath executableBytes executableSha256 reuse requiredRefCheck inputEnvelope commandBoundary")
    fields(plan["execution"], "engine version htslibVersion sourceRead panelSelection criterionEvaluation versionBoundary")
    require(normalizer["name"] == "bcftools" and normalizer["requiredRefCheck"] == "e"
            and digest(normalizer["executableSha256"]) and plan["execution"]["engine"] == "bcftools norm"
            and plan["execution"]["version"] == normalizer["expectedVersion"]
            and plan["execution"]["sourceRead"] == "MASKED_PROJECTION_AND_QUERY_OCCURRENCES_ONLY"
            and plan["execution"]["panelSelection"] is False and plan["execution"]["criterionEvaluation"] is False, "PLAN_NORMALIZER_UNSUPPORTED")
    require(plan["alleleEnvelope"] == dict(assembly="GRCh38", maximumAlleleLength=50,
            allow=["SNV", "SIMPLE_ANCHORED_INSERTION", "SIMPLE_ANCHORED_DELETION"], splitMultiallelic=False), "PLAN_ALLELE_ENVELOPE_UNSUPPORTED")
    policy = plan["windowPolicy"]
    fields(policy, "initialWindowSource source originalQueryWindowCount originalTotalBases originalMaximumWindowBases initialRightPaddingBases cumulativeLeftExtensions maximumLeftExtensionBases maximumRightRefCompletionBases maximumWindowBases maximumAttemptsPerWindow maximumWindowReads maximumTotalFastaBases clipOnlyToActualContigBounds grouping extensionTrigger noFurtherExtension budgetRationale")
    fixed = dict(initialWindowSource="QUERY_PLAN_V2_WINDOWS", originalMaximumWindowBases=25000,
                 initialRightPaddingBases=49, cumulativeLeftExtensions=[0, 1000, 10000], maximumLeftExtensionBases=10000,
                 maximumRightRefCompletionBases=49, maximumWindowBases=35049, maximumAttemptsPerWindow=3,
                 maximumWindowReads=387, maximumTotalFastaBases=12582912, clipOnlyToActualContigBounds=True)
    require(all(type(policy[key]) is type(value) and policy[key] == value for key, value in fixed.items()), "PLAN_WINDOW_OPERATOR_UNSUPPORTED")
    require(all(type(value) is int for value in policy["cumulativeLeftExtensions"]), "PLAN_WINDOW_OPERATOR_UNSUPPORTED")
    budgets = plan["budgets"]
    fields(budgets, "maximumInputArtifactBytes maximumOutputArtifactBytes manifestReserveBytes maximumInputRowsPerArtifact maximumSingleInputLineBytes rowAccounting byteAccounting reserveRationale")
    require(all(type(budgets[key]) is int and budgets[key] == value for key, value in dict(
        maximumInputArtifactBytes=268435456, maximumOutputArtifactBytes=268435456, manifestReserveBytes=1048576,
        maximumInputRowsPerArtifact=150000, maximumSingleInputLineBytes=1048576).items()), "PLAN_BUDGET_UNSUPPORTED")
    fields(plan["normalizationSemantics"], "originalRef coordinateTransfer leftmostProof canonicalKey equivalence geneIdentity aliases finalRanking")
    require(plan["normalizationSemantics"]["canonicalKey"] == "GRCh38|canonicalContig|leftNormalizedPosition1|REF|ALT", "CANONICAL_KEY_CONTRACT_UNSUPPORTED")
    fields(plan["outputContract"], "manifestSchemaVersion recordSchemaVersion requiredRecordFields requiredAttemptFields requiredManifestFields canonicalNullForUnresolvedOrRejectedRecord deterministicSourceRejection incompleteHandling noImplicitSelection")
    require(plan["outputContract"]["manifestSchemaVersion"] == "public-panel-normalization-manifest-v1"
            and plan["outputContract"]["recordSchemaVersion"] == "public-source-allele-normalization-v1"
            and plan["outputContract"]["canonicalNullForUnresolvedOrRejectedRecord"] is True, "OUTPUT_CONTRACT_UNSUPPORTED")
    for key, names in dict(requiredRecordFields="sourceAlleleKey sourceRawLineSha256s queryLocators geneMembershipStatuses normalizationStatus reasonCode referenceStatus canonicalAllele attemptIds",
                           requiredAttemptFields="attemptId originalWindowId extensionIndex assembly contig windowStart windowEnd absoluteToLocalOffset referenceSequenceSha256 referenceBasesRead commandReceipt outcome",
                           requiredManifestFields="planSha256 inputIdentities referenceIdentity normalizerIdentity status allSourceRecordsAccountedFor unresolvedRecords referenceBasesRead windowReads artifactIdentities finalPanelSelectedAlleles ruleEvaluations").items():
        require(type(plan["outputContract"][key]) is list and sorted(plan["outputContract"][key]) == sorted(names.split()), "OUTPUT_REQUIREMENT_UNSUPPORTED")
    require(query.get("schemaVersion") == "prospective-local-public-allele-query-plan-v2"
            and query.get("frameBinding", {}).get("fileSha256") == bindings["frameSha256"], "QUERY_PLAN_IDENTITY_INVALID")
    windows, lookup, total = {}, {}, 0
    genes = query.get("geneQueries")
    require(type(genes) is list and 1 <= len(genes) <= 20, "QUERY_GENE_BOUND_INVALID")
    seen_genes = set()
    for gene in genes:
        symbol, interval = gene["geneSymbol"], gene["sourceGeneInterval"]
        require(type(symbol) is str and symbol not in seen_genes and interval.get("assembly") == "GRCh38"
                and PRIMARY.fullmatch(interval.get("chromosome", "")), "QUERY_GENE_IDENTITY_INVALID")
        seen_genes.add(symbol)
        previous = None
        for ordinal, window in enumerate(gene["queryWindows"], 1):
            fields(window, "start end tabixRegion")
            start, end, contig = window["start"], window["end"], interval["chromosome"]
            require(number(start, 1) and number(end, start) and end - start + 1 <= 25000
                    and (previous is None or previous + 1 == start)
                    and window["tabixRegion"] == "%s:%d-%d" % (contig, start, end), "QUERY_WINDOW_INVALID")
            identifier = "W%d" % (len(windows) + 1)
            windows[identifier] = dict(contig=contig, start=start, end=end, geneSymbol=symbol,
                                       queryRegion=window["tabixRegion"], windowOrdinal=ordinal)
            lookup[symbol, window["tabixRegion"]] = identifier
            total += end - start + 1
            previous = end
    require(1 <= len(windows) <= 129 and len(windows) == policy["originalQueryWindowCount"] == query["totals"]["queryWindows"]
            and total == policy["originalTotalBases"] == query["totals"]["queriedBasesIncludingPadding"]
            and total <= 2968558, "QUERY_WINDOW_TOTAL_MISMATCH")
    return windows, lookup


def input_rows(content, budgets):
    require(content.endswith(b"\n") or not content, "INPUT_FINAL_LINE_INCOMPLETE")
    lines = content.splitlines()
    require(len(lines) <= budgets["maximumInputRowsPerArtifact"], "INPUT_ROW_LIMIT")
    for ordinal, line in enumerate(lines, 1):
        require(0 < len(line) + 1 <= budgets["maximumSingleInputLineBytes"], "INPUT_LINE_LIMIT")
        yield ordinal, strict_json(line)


def load_candidates(plan, query, pool_path, projection_path, occurrences_path, windows, lookup):
    bindings, budgets = plan["bindings"], plan["budgets"]
    manifest_bytes = pinned_bytes(pool_path, bindings["poolManifestSha256"], 1048576)
    manifest = strict_json(manifest_bytes)
    require(manifest.get("schemaVersion") == "local-public-allele-pool-manifest-v1"
            and manifest.get("status") == "COMPLETE" and manifest.get("samplingAllowed") is True
            and manifest.get("sourceIdentityVerified") is True and manifest.get("classificationFieldsExposedToSampler") is False
            and manifest.get("referenceNormalizationPerformed") is False
            and manifest.get("planSha256") == bindings["queryPlanSha256"]
            and manifest.get("sourceRawLineHashConvention") == "SHA256_ORIGINAL_UTF8_BYTES_EXCLUDING_LINE_TERMINATOR"
            and manifest.get("sourceSnapshotIdentity") == query["sources"][0]
            and manifest.get("frameBinding") == query["frameBinding"]
            and manifest.get("plannedTotals") == query["totals"], "MASKED_POOL_INCOMPLETE_OR_IDENTITY_INVALID")
    for key in ("networkCalls", "providerCalls", "criterionEvaluations", "selectedAlleles"):
        require(type(manifest.get(key)) is int and manifest[key] == 0, "POOL_EXECUTION_BOUNDARY_INVALID")
    snapshot = manifest.get("sourceSnapshotIdentitySha256")
    require(digest(snapshot), "SOURCE_SNAPSHOT_IDENTITY_REQUIRED")
    require(len(manifest["genes"]) == len(query["geneQueries"]), "POOL_GENE_ACCOUNTING_INVALID")
    for state, gene in zip(manifest["genes"], query["geneQueries"]):
        require(state.get("geneSymbol") == gene["geneSymbol"] and state.get("status") == "COMPLETE"
                and len(state.get("windows", [])) == len(gene["queryWindows"])
                and len(state.get("partitions", [])) == len(gene["queryPartitions"]), "POOL_PEER_INCOMPLETE")
        for observed, expected in zip(state["windows"], gene["queryWindows"]):
            require(observed.get("status") == "COMPLETE" and observed.get("queryRegion") == expected["tabixRegion"], "POOL_WINDOW_INCOMPLETE")
        for observed, expected in zip(state["partitions"], gene["queryPartitions"]):
            require(observed.get("status") == "COMPLETE" and all(observed.get(key) == value for key, value in expected.items()), "POOL_PARTITION_INCOMPLETE")
    artifact_entries = {}
    for entry in manifest.get("artifacts", []):
        fields(entry, "file bytes sha256")
        require(entry["file"] not in artifact_entries and number(entry["bytes"], 0, budgets["maximumInputArtifactBytes"])
                and digest(entry["sha256"]), "POOL_ARTIFACT_IDENTITY_INVALID")
        artifact_entries[entry["file"]] = entry
    contents, total = {}, len(manifest_bytes)
    for name, path, binding in (("sampling-projection.jsonl", projection_path, "maskedProjectionSha256"),
                               ("query-occurrences.jsonl", occurrences_path, "queryOccurrencesSha256")):
        entry = artifact_entries.get(name, {})
        require(entry.get("sha256") == bindings[binding] and number(entry.get("bytes"), 0)
                and entry["bytes"] <= budgets["maximumInputArtifactBytes"] - total, "INPUT_CUMULATIVE_BUDGET_OR_IDENTITY_INVALID")
        content = pinned_bytes(path, bindings[binding], entry["bytes"])
        require(len(content) == entry["bytes"], "MASKED_ARTIFACT_SIZE_MISMATCH")
        contents[name] = content
        total += len(content)
    candidates, by_hash = [], {}
    for ordinal, row in input_rows(contents["sampling-projection.jsonl"], budgets):
        fields(row, "CHROM POS ID REF ALT FILTER sourceRawLineSha256 sourceSnapshotIdentity", "GENEINFO MC CLNHGVS ALLELEID RS AF_ESP AF_EXAC AF_TGP")
        require(number(row["POS"], 1) and all(type(value) is str for key, value in row.items() if key != "POS")
                and digest(row["sourceRawLineSha256"]) and row["sourceRawLineSha256"] not in by_hash
                and row["sourceSnapshotIdentity"] == snapshot, "PROJECTION_SOURCE_IDENTITY_INVALID")
        original = dict(assembly="GRCh38", contig=row["CHROM"], position=row["POS"], reference=row["REF"], alternate=row["ALT"])
        item = dict(id="R%d" % ordinal, position=row["POS"], ref=row["REF"], alt=row["ALT"], original=original,
                    source=dict(maskedProjection=row, projectionLineNumber=ordinal,
                                sourceAlleleKey=canonical_key(row["CHROM"], row["POS"], row["REF"], row["ALT"])), memberships=[])
        candidates.append(item)
        by_hash[row["sourceRawLineSha256"]] = item
    seen, occurrence_count, counts = set(), 0, {}
    for ordinal, row in input_rows(contents["query-occurrences.jsonl"], budgets):
        fields(row, "sourceGeneInfoPairs geneMembershipStatus alleleEnvelopeStatus membershipStatus samplingEligibility sourceRawLineSha256 queryGeneSymbol queryRegion queryReturnOrdinal")
        key = row["queryGeneSymbol"], row["queryRegion"]
        require(key in lookup and row["sourceRawLineSha256"] in by_hash and number(row["queryReturnOrdinal"], 1), "OCCURRENCE_SOURCE_OR_QUERY_UNKNOWN")
        occurrence_key = key + (row["queryReturnOrdinal"],)
        require(occurrence_key not in seen, "DUPLICATE_QUERY_RETURN_LOCATOR")
        seen.add(occurrence_key)
        counts[key] = counts.get(key, 0) + 1
        require(row["queryReturnOrdinal"] == counts[key], "QUERY_RETURN_SEQUENCE_INCOMPLETE")
        require(row["geneMembershipStatus"] in ("GENEINFO_MISSING_OR_MALFORMED", "QUERY_GENE_NOT_EXACTLY_MATCHED",
                "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED", "EXACT_SINGLE_GENE_MATCH")
                and type(row["sourceGeneInfoPairs"]) is list, "GENE_MEMBERSHIP_STATUS_INVALID")
        for pair in row["sourceGeneInfoPairs"]:
            fields(pair, "symbol ncbiGeneId")
            require(type(pair["symbol"]) is str and type(pair["ncbiGeneId"]) is str, "GENE_MEMBERSHIP_PAIR_INVALID")
        item = by_hash[row["sourceRawLineSha256"]]
        item["memberships"].append(dict(row, occurrenceLineNumber=ordinal))
        identifier = lookup[key]
        if "windowId" not in item or int(identifier[1:]) < int(item["windowId"][1:]):
            item["windowId"] = identifier
        occurrence_count += 1
    require(len(candidates) == manifest.get("uniqueRawRows") and occurrence_count == manifest.get("retainedRowObservations")
            and all(item["memberships"] for item in candidates), "SOURCE_ROW_ACCOUNTING_INCOMPLETE")
    for gene in manifest["genes"]:
        for window in gene["windows"]:
            # Extractor v2 leaves retainedObservations absent for a completed,
            # empty tabix window. Its joined observation count must still be zero.
            require(number(window.get("retainedObservations", 0), 0)
                    and counts.get((gene["geneSymbol"], window["queryRegion"]), 0) == window.get("retainedObservations", 0),
                    "WINDOW_SOURCE_ROW_ACCOUNTING_INCOMPLETE")
    identities = dict(poolManifestSha256=bindings["poolManifestSha256"], maskedProjection=artifact_entries["sampling-projection.jsonl"],
                      queryOccurrences=artifact_entries["query-occurrences.jsonl"], cumulativeBytes=total,
                      uniqueRawRows=len(candidates), retainedRowObservations=occurrence_count,
                      sourceSnapshotIdentity=manifest["sourceSnapshotIdentity"], sourceSnapshotIdentitySha256=snapshot,
                      sourceRawLineHashConvention=manifest["sourceRawLineHashConvention"],
                      sourceSnapshotHashIsWholeDataHash=False, classificationFieldsRead=False)
    return candidates, identities


def output_records(candidates, results):
    attempts, records, groups = {}, [], {}
    for item, result in zip(candidates, results):
        attempt_ids = []
        for detail in result["attempts"]:
            loc = detail["window"]
            identifier = "%s-E%d" % (item["windowId"], detail["round"])
            attempt_ids.append(identifier)
            if identifier not in attempts:
                attempts[identifier] = dict(attemptId=identifier, originalWindowId=item["windowId"], extensionIndex=detail["round"],
                    assembly="GRCh38", contig=loc["contig"], windowStart=loc["start"], windowEnd=loc["end"],
                    absoluteToLocalOffset=loc["start"] - 1, referenceSequenceSha256=loc["normalizedCaseBasesSha256"],
                    referenceBasesRead=loc["end"] - loc["start"] + 1, referenceLocator=loc,
                    commandReceipt=None, outcome="REFERENCE_CHECK_ONLY")
            if "commandReceipt" in detail:
                attempts[identifier].update(commandReceipt=detail["commandReceipt"], outcome="NORMALIZER_COMPLETED")
        row = dict(schemaVersion="public-source-allele-normalization-v1", recordId=item["id"],
            sourceAlleleKey=item["source"]["sourceAlleleKey"], sourceRawLineSha256s=[item["source"]["maskedProjection"]["sourceRawLineSha256"]],
            queryLocators=[dict(queryGeneSymbol=m["queryGeneSymbol"], queryRegion=m["queryRegion"], queryReturnOrdinal=m["queryReturnOrdinal"],
                                occurrenceLineNumber=m["occurrenceLineNumber"]) for m in item["memberships"]],
            geneMembershipStatuses=[m["geneMembershipStatus"] for m in item["memberships"]],
            normalizationStatus=result["status"], reasonCode=result.get("reasonCode"), referenceStatus=result["referenceStatus"],
            canonicalAllele=result["canonicalAllele"], canonicalKey=result["canonicalKey"], attemptIds=attempt_ids,
            source=item["source"], memberships=item["memberships"], original=result["original"],
            observedReference=result.get("observedReference"), leftmostProof=result.get("leftmostProof"),
            localHaplotypeEqualityVerified=result.get("localHaplotypeEqualityVerified", False),
            normalizedOutputLocators=[a["normalizedOutputLocator"] for a in result["attempts"] if "normalizedOutputLocator" in a])
        records.append(row)
        if row["canonicalKey"]:
            group = groups.setdefault(row["canonicalKey"], dict(canonicalKey=row["canonicalKey"], canonicalAllele=row["canonicalAllele"],
                sourceRecordIds=[], sourceAlleleKeys=[], sourceRawLineSha256s=[], geneMembershipStatuses=[],
                transcriptAttribution="UNCHANGED_PENDING_INDEPENDENT_ATTRIBUTION", selected=False))
            group["sourceRecordIds"].append(row["recordId"])
            for field in ("sourceRawLineSha256s", "geneMembershipStatuses"):
                group[field] = sorted(set(group[field] + row[field]))
            group["sourceAlleleKeys"] = sorted(set(group["sourceAlleleKeys"] + [row["sourceAlleleKey"]]))
    return records, list(attempts.values()), list(groups.values())


def bind_reference_plan(reference, definition):
    actual, checksum = reference.identity["actualChecksumReceipt"], definition["checksumReceipt"]
    pairs = [(definition["logicalFastaPath"], actual["logicalName"]),
             (definition["logicalFaiPath"], actual["logicalName"] + ".fai"),
             (definition["resourceVersion"], actual["resourceVersion"]),
             (definition["fastaBytes"], actual["byteLength"]), (definition["fastaMtimeNs"], actual["mtimeNs"]),
             (definition["wholeFastaSha256"], actual["observedSha256"]),
             (definition["faiBytes"], reference.index_path.stat().st_size), (definition["faiSha256"], reference.index_sha),
             (checksum["fileSha256"], reference.identity["acceptedReceiptSha256"]),
             (checksum["locator"], "records." + reference.identity["recordLocator"] + ".actualChecksumReceipt"),
             (checksum["resourceId"], actual["resourceId"]), (checksum["completedAt"], actual["completedAt"])]
    require(all(type(a) is type(b) and a == b for a, b in pairs), "PLAN_REFERENCE_RECEIPT_BINDING_MISMATCH")


def execute(options):
    plan = strict_json(pinned_bytes(options.plan, options.plan_sha256, 1048576))
    query = strict_json(pinned_bytes(options.query_plan, plan["bindings"]["queryPlanSha256"], 1048576))
    windows, lookup = validate_plan(plan, query)
    output = Path(options.output).absolute()
    task_root = Path(options.task_output_root).resolve(strict=True)
    require(not str(task_root).startswith(("\\\\", "//")) and not output.exists()
            and output.parent.resolve(strict=True) == task_root, "NEW_TASK_PRIVATE_OUTPUT_DIRECTORY_REQUIRED")
    output = task_root / output.name
    output.mkdir()
    store = Artifacts(output, plan["budgets"]["maximumOutputArtifactBytes"], plan["budgets"]["manifestReserveBytes"])
    store.json("RUNNING.json", dict(status="RUNNING", boundary="Only final COMPLETE manifest accounts for every source row; no panel selection is performed"))
    report = dict(schemaVersion="public-panel-normalization-manifest-v1", toolVersion=VERSION, planSha256=options.plan_sha256,
        queryPlanSha256=plan["bindings"]["queryPlanSha256"], status="RUNNING", allSourceRecordsAccountedFor=False,
        inputIdentities=None, referenceIdentity=None, normalizerIdentity=None, unresolvedRecords=None,
        referenceBasesRead=0, windowReads=0, artifactIdentities=[], finalPanelSelectedAlleles=0, ruleEvaluations=0,
        networkCalls=0, clinicalAcceptance=False, samplingAllowed=False,
        finalRankingPerformed=False, boundary="Native source normalization only; gene/clinical transcript attribution and final panel selection remain separate",
        startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    reference, engine, candidates = None, None, None
    try:
        candidates, report["inputIdentities"] = load_candidates(plan, query, options.pool_manifest, options.projection,
                                                               options.occurrences, windows, lookup)
        reference = BoundedReference(options.resource_root, options.reference_receipt,
            plan["reference"]["checksumReceipt"]["fileSha256"], options.reference_source_id, options.reference_source_version,
            options.reference_record_locator, plan["reference"]["wholeFastaSha256"], options.aliases, options.aliases_sha256,
            windows, plan["windowPolicy"])
        bind_reference_plan(reference, plan["reference"])
        report["referenceIdentity"] = reference.identity
        require(Path(options.bcftools).stat().st_size == plan["normalizer"]["executableBytes"], "BCFTOOLS_SIZE_MISMATCH")
        engine = BcftoolsWindowEngine(options.bcftools, plan["normalizer"]["executableSha256"], plan["execution"]["version"],
                                     plan["execution"]["htslibVersion"], output, store)
        report["normalizerIdentity"] = engine.identity
        results = normalize_candidates(candidates, windows, reference, engine, plan["windowPolicy"])
        records, attempts, groups = output_records(candidates, results)
        for name, rows in (("source-normalization.jsonl", records), ("window-attempts.jsonl", attempts), ("canonical-aliases.jsonl", groups)):
            with store.open(output / name) as stream:
                for row in rows:
                    stream.write(canonical(row) + b"\n")
        reference.recheck()
        unresolved = sum(row["normalizationStatus"] not in ("CANONICAL", "EXCLUDED") for row in records)
        report.update(status="INCOMPLETE" if unresolved else "COMPLETE", allSourceRecordsAccountedFor=True,
                      unresolvedRecords=unresolved, sourceRecords=len(records), canonicalSourceRecords=sum(r["canonicalKey"] is not None for r in records),
                      excludedSourceRecords=sum(r["normalizationStatus"] == "EXCLUDED" for r in records),
                      uniqueCanonicalAlleles=len(groups), sourceAliasesAreNotAdditionalAlleles=True)
    except (Exception, KeyboardInterrupt) as error:
        report.update(status="CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAILED",
                      reasonCode=str(error) or type(error).__name__, allSourceRecordsAccountedFor=False)
    finally:
        if reference:
            report.update(referenceBasesRead=reference.charged_bases, actualReturnedReferenceBases=reference.returned_bases,
                          windowReads=reference.reads)
        report["normalizerCalls"] = engine.calls if engine else 0
        report["artifactIdentities"] = store.identities()
        report["artifactBytesBeforeManifest"] = sum(value["bytes"] for value in report["artifactIdentities"])
        report["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        store.json("manifest.json", report, manifest=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("plan", "plan-sha256", "query-plan", "pool-manifest", "projection", "occurrences", "resource-root",
                 "reference-receipt", "reference-source-id", "reference-source-version", "reference-record-locator",
                 "aliases", "aliases-sha256", "bcftools", "task-output-root", "output"):
        parser.add_argument("--" + name, required=True)
    try:
        report = execute(parser.parse_args())
        print(json.dumps({key: report.get(key) for key in ("status", "reasonCode", "sourceRecords", "unresolvedRecords", "referenceBasesRead", "windowReads")}, ensure_ascii=False))
        return 0 if report["status"] == "COMPLETE" else 2
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps(dict(status="PREFLIGHT_FAILED", reasonCode=str(error) or type(error).__name__)))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
