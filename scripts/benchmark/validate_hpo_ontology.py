#!/usr/bin/env python3
"""Validate a fixed HPO OBO release and audit truth-masked review packets."""

import argparse
import hashlib
import json
import re
import sys
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path


VALIDATOR_VERSION = "hpo-ontology-validator-v1"
INCOMPLETE_SUFFIXES = (".part", ".crdownload", ".download", ".partial", ".tmp")
EXACT_SYNONYM = re.compile(r'^synonym:\s+"(?P<label>.*?)"\s+EXACT\b')


class ValidationError(Exception):
    def __init__(self, code, path, message):
        super().__init__(message)
        self.code = code
        self.path = path
        self.message = message

    def as_dict(self):
        return OrderedDict((
            ("code", self.code),
            ("path", self.path),
            ("message", self.message),
        ))


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalized_release(value):
    return value.rstrip("/").split("/")[-1]


def parse_obo(path):
    header = OrderedDict()
    terms = OrderedDict()
    current = None
    seen_term_section = False

    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ValidationError("OBO_UTF8_INVALID", "ontology", "Ontology is not readable UTF-8 text.") from error
    except OSError as error:
        raise ValidationError("OBO_READ_FAILED", "ontology", "Ontology could not be read.") from error

    def commit_term(term):
        if term is None:
            return
        term_id = term.get("id")
        if not term_id:
            raise ValidationError("TERM_ID_MISSING", "term", "A [Term] stanza is missing id.")
        if term_id in terms:
            raise ValidationError("DUPLICATE_TERM_ID", "term.id", "Duplicate OBO term ID: {0}".format(term_id))
        if not term.get("name"):
            raise ValidationError("TERM_NAME_MISSING", "term.name", "A [Term] stanza is missing name.")
        terms[term_id] = term

    for raw_line in lines:
        line = raw_line.strip()
        if line == "[Term]":
            commit_term(current)
            current = {"synonyms": [], "replacedBy": [], "consider": [], "obsolete": False}
            seen_term_section = True
            continue
        if line.startswith("[") and line.endswith("]"):
            commit_term(current)
            current = None
            continue
        if not line or line.startswith("!"):
            continue
        if current is None:
            if ":" in line:
                key, value = line.split(":", 1)
                if key in ("format-version", "data-version", "ontology"):
                    header[key] = value.strip()
            continue
        if line.startswith("id: "):
            current["id"] = line[4:].strip()
        elif line.startswith("name: "):
            current["name"] = line[6:].strip()
        elif line == "is_obsolete: true":
            current["obsolete"] = True
        elif line.startswith("replaced_by: "):
            current["replacedBy"].append(line[13:].strip())
        elif line.startswith("consider: "):
            current["consider"].append(line[10:].strip())
        else:
            synonym_match = EXACT_SYNONYM.match(line)
            if synonym_match:
                current["synonyms"].append(synonym_match.group("label"))

    commit_term(current)
    if not header.get("format-version"):
        raise ValidationError("FORMAT_VERSION_MISSING", "header.format-version", "OBO format-version is required.")
    if not header.get("data-version"):
        raise ValidationError("DATA_VERSION_MISSING", "header.data-version", "OBO data-version is required.")
    if not seen_term_section or not terms:
        raise ValidationError("TERM_SECTION_MISSING", "ontology", "OBO contains no [Term] stanzas.")
    return header, terms


def load_packet_terms(packet_directory, packet_prefix):
    if not re.fullmatch(r"[A-Z][A-Z0-9-]*-", packet_prefix or ""):
        raise ValidationError("PACKET_PREFIX_INVALID", "packetPrefix", "Packet prefix must be stable uppercase ASCII.")
    packet_paths = sorted(packet_directory.glob(packet_prefix + "*.packet.json"))
    if not packet_paths:
        raise ValidationError("PACKETS_MISSING", "packetDirectory", "No reviewer packet JSON files were found.")
    grouped = OrderedDict()
    for packet_path in packet_paths:
        try:
            packet = json.loads(packet_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise ValidationError("PACKET_READ_FAILED", packet_path.name, "Reviewer packet cannot be read.") from error
        if not isinstance(packet.get("terms"), list):
            raise ValidationError("PACKET_TERMS_MISSING", packet_path.name, "Reviewer packet has no terms list.")
        for term in packet["terms"]:
            term_id = term.get("hpoId")
            label = term.get("label")
            if not isinstance(term_id, str) or not isinstance(label, str):
                raise ValidationError("PACKET_TERM_INVALID", packet_path.name, "Reviewer packet term needs hpoId and label.")
            grouped.setdefault(term_id, OrderedDict((
                ("labels", set()),
                ("statuses", set()),
            )))
            grouped[term_id]["labels"].add(label)
            status = term.get("originalStatus")
            if isinstance(status, str):
                grouped[term_id]["statuses"].add(status)
    return packet_paths, grouped


def audit_terms(terms, requested):
    audited = []
    counts = {"currentLabelMatch": 0, "exactSynonymMatch": 0, "missing": 0, "obsolete": 0, "labelMismatch": 0}
    for term_id in sorted(requested):
        request = requested[term_id]
        labels = sorted(request["labels"])
        term = terms.get(term_id)
        item = OrderedDict((
            ("hpoId", term_id),
            ("sourceLabels", labels),
            ("sourceStatuses", sorted(request["statuses"])),
        ))
        if term is None:
            item["status"] = "MISSING"
            counts["missing"] += 1
        else:
            item["currentOfficialName"] = term["name"]
            item["exactSynonyms"] = sorted(term["synonyms"])
            item["isObsolete"] = term["obsolete"]
            item["replacedBy"] = sorted(term["replacedBy"])
            item["consider"] = sorted(term["consider"])
            if term["obsolete"]:
                item["status"] = "OBSOLETE"
                counts["obsolete"] += 1
            elif all(label == term["name"] or label in term["synonyms"] for label in labels):
                if all(label == term["name"] for label in labels):
                    item["status"] = "CURRENT_LABEL"
                    counts["currentLabelMatch"] += 1
                else:
                    item["status"] = "EXACT_SYNONYM"
                    counts["exactSynonymMatch"] += 1
            else:
                item["status"] = "LABEL_MISMATCH"
                counts["labelMismatch"] += 1
        audited.append(item)
    return audited, counts


def parameter_fingerprint(expected_version, packet_prefix, packet_names):
    parameters = OrderedDict((
        ("expectedReleaseVersion", expected_version),
        ("packetPrefix", packet_prefix),
        ("packetFileNames", packet_names),
        ("validatorVersion", VALIDATOR_VERSION),
    ))
    encoded = json.dumps(parameters, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def run(arguments):
    ontology_path = Path(arguments.ontology)
    if ontology_path.name.lower().endswith(INCOMPLETE_SUFFIXES):
        raise ValidationError("INCOMPLETE_INPUT_REJECTED", "ontology", "Incomplete ontology suffix is not accepted.")
    if not ontology_path.is_file() or ontology_path.stat().st_size == 0:
        raise ValidationError("ONTOLOGY_MISSING_OR_EMPTY", "ontology", "Ontology file is missing or empty.")

    header, terms = parse_obo(ontology_path)
    if header["format-version"] != "1.2":
        raise ValidationError("FORMAT_VERSION_UNSUPPORTED", "header.format-version", "Expected OBO format-version 1.2.")
    if normalized_release(header["data-version"]) != arguments.expected_version:
        raise ValidationError("DATA_VERSION_MISMATCH", "header.data-version", "Ontology data-version does not match requested release.")

    packet_paths, requested = load_packet_terms(Path(arguments.packet_directory), arguments.packet_prefix)
    audited_terms, counts = audit_terms(terms, requested)
    semantic_failed = any(counts[key] for key in ("missing", "obsolete", "labelMismatch"))
    sha256 = sha256_file(ontology_path)
    if arguments.official_sha256 and sha256 != arguments.official_sha256.lower():
        raise ValidationError("OFFICIAL_CHECKSUM_MISMATCH", "officialChecksumValue", "Local SHA-256 does not match the supplied official checksum.")
    if arguments.official_checksum_status == "OFFICIAL_RELEASE_ASSET_SHA256_MATCH" and not arguments.official_sha256:
        raise ValidationError("OFFICIAL_CHECKSUM_MISSING", "officialChecksumValue", "A matching official checksum value is required for this status.")
    packet_names = [path.name for path in packet_paths]
    fingerprint = parameter_fingerprint(arguments.expected_version, arguments.packet_prefix, packet_names)
    report = OrderedDict((
        ("schemaVersion", "hpo-ontology-semantic-audit-v1"),
        ("resourceId", arguments.resource_id),
        ("ontologyFileName", ontology_path.name),
        ("expectedReleaseVersion", arguments.expected_version),
        ("formatVersion", header["format-version"]),
        ("dataVersion", header["data-version"]),
        ("sha256", sha256),
        ("termCount", len(terms)),
        ("obsoleteTermCount", sum(1 for term in terms.values() if term["obsolete"])),
        ("packetFileNames", packet_names),
        ("uniqueHpoIdCount", len(requested)),
        ("matchCounts", counts),
        ("parameterFingerprint", fingerprint),
        ("validationStatus", "SEMANTIC_AUDIT_FAILED" if semantic_failed else "COMPLETED"),
        ("terms", audited_terms),
    ))
    special = next((item for item in audited_terms if item["hpoId"] == "HP:0009098"), None)
    if special is not None:
        report["semanticAuditNote"] = OrderedDict((
            ("hpoId", "HP:0009098"),
            ("sourceLabel", special["sourceLabels"][0]),
            ("currentOfficialName", special.get("currentOfficialName")),
            ("relationship", "EXACT_SYNONYM" if special["status"] == "EXACT_SYNONYM" else special["status"]),
            ("decisionImpact", "NONE"),
        ))
    if arguments.report_output:
        write_json(Path(arguments.report_output), report)

    if arguments.resource_manifest_output:
        manifest = OrderedDict((
            ("schemaVersion", "benchmark-hpo-ontology-resource-manifest-v1"),
            ("resourceId", arguments.resource_id),
            ("resourceType", "HPO_ONTOLOGY"),
            ("format", "OBO"),
            ("sourceUri", arguments.source_uri),
            ("releaseVersion", arguments.expected_version),
            ("dataVersion", header["data-version"]),
            ("fileName", ontology_path.name),
            ("fileSizeBytes", ontology_path.stat().st_size),
            ("sha256", sha256),
            ("officialChecksumStatus", arguments.official_checksum_status),
            ("officialChecksumAlgorithm", "SHA-256"),
            ("officialChecksumValue", arguments.official_sha256),
            ("downloadedAt", arguments.downloaded_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")),
            ("validationStatus", "VALID"),
            ("termCount", len(terms)),
            ("obsoleteTermCount", sum(1 for term in terms.values() if term["obsolete"])),
            ("validatorVersion", VALIDATOR_VERSION),
            ("parameterFingerprint", fingerprint),
        ))
        write_json(Path(arguments.resource_manifest_output), manifest)
    return report, semantic_failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ontology", required=True)
    parser.add_argument("--packet-directory", required=True)
    parser.add_argument("--packet-prefix", default="SMOKE-HPO-")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--resource-id", default="hpo-ontology-2026-06-23")
    parser.add_argument("--source-uri", default="https://purl.obolibrary.org/obo/hp/releases/2026-06-23/hp.obo")
    parser.add_argument("--official-checksum-status", default="SOURCE_CHECKSUM_NOT_PUBLISHED")
    parser.add_argument("--official-sha256", default=None)
    parser.add_argument("--downloaded-at")
    parser.add_argument("--report-output")
    parser.add_argument("--resource-manifest-output")
    parser.add_argument("--strict-semantic", action="store_true")
    arguments = parser.parse_args()
    try:
        report, semantic_failed = run(arguments)
    except ValidationError as error:
        print(json.dumps(OrderedDict((("validationStatus", "FAILED"), ("errors", [error.as_dict()]))), indent=2))
        return 2
    if not arguments.report_output:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    return 3 if arguments.strict_semantic and semantic_failed else 0


if __name__ == "__main__":
    sys.exit(main())
