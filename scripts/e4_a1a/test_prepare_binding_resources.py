"""Offline parser checks; public catalog fixtures are not clinical validation."""

from __future__ import annotations

import copy
import csv
import gzip
import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts.e4_a1a import prepare_binding_resources as prepare


FIXTURES = Path(__file__).parent / "fixtures"


def encode_rows(rows: list[list[str]], delimiter: str = ",") -> bytes:
    stream = io.StringIO(newline="")
    csv.writer(stream, delimiter=delimiter, lineterminator="\n").writerows(rows)
    return stream.getvalue().encode("utf-8")


class BindingResourcesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clingen = (FIXTURES / "public-clingen.csv").read_bytes()
        self.mane = (FIXTURES / "public-mane.tsv").read_bytes()

    def test_public_records_retain_two_genes_and_two_actb_diseases(self) -> None:
        crosswalk, counts = prepare.parse_mane(self.mane)
        catalog, source_counts, date = prepare.parse_clingen(self.clingen)
        self.assertEqual("2026-09-05", date)
        self.assertEqual({"AARS1", "AARS2", "ACTB"}, {row["symbol"] for row in crosswalk})
        self.assertEqual({("MONDO:0017579", "AD"), ("MONDO:0100433", "AD")},
                         {(row["mondoId"], row["moi"]) for row in catalog if row["symbol"] == "ACTB"})
        self.assertEqual(1, counts["rejectedCount"])
        self.assertEqual({"HGNC_ID_MISSING": 1}, counts["rejectedReasonCounts"])
        self.assertEqual(0, source_counts["rejectedCount"])
        self.assertEqual("16", next(row["ncbiGeneId"] for row in crosswalk if row["symbol"] == "AARS1"))

    def test_mane_aggregates_same_identity_without_selecting_transcript(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.mane, "\t")]
        duplicate = rows[1].copy()
        duplicate[9] = "MANE Plus Clinical"
        duplicate[5] = "NM_synthetic.1"
        rows.append(duplicate)
        parsed, counts = prepare.parse_mane(encode_rows(rows, "\t"))
        self.assertEqual([2, len(rows)], parsed[0]["sourceRows"])
        self.assertEqual(1, counts["aggregatedDuplicateCount"])
        self.assertNotIn("transcriptId", parsed[0])

    def test_mane_preserves_conflicting_complete_identity(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.mane, "\t")]
        conflict = rows[1].copy()
        conflict[2] = "HGNC:999999"
        rows.append(conflict)
        parsed, _ = prepare.parse_mane(encode_rows(rows, "\t"))
        self.assertEqual(2, len([row for row in parsed if row["ncbiGeneId"] == "16"]))

    def test_missing_identity_cannot_hide_overlap(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.mane, "\t")]
        incomplete = rows[1].copy()
        incomplete[2] = ""
        rows.append(incomplete)
        with self.assertRaisesRegex(prepare.ResourceError, "INCOMPLETE_IDENTITY_OVERLAP"):
            prepare.parse_mane(encode_rows(rows, "\t"))

    def test_all_intake_classes_blank_and_duplicates_are_retained(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.clingen, ",")]
        source = rows[6]
        rows = rows[:6]
        for classification in sorted(prepare.CLASSIFICATIONS):
            row = source.copy()
            row[6] = classification
            rows.append(row)
        rows.append(rows[6].copy())
        parsed, counts, _ = prepare.parse_clingen(encode_rows(rows))
        self.assertEqual(9, len(parsed))
        self.assertEqual(0, counts["rejectedCount"])
        self.assertEqual(prepare.CLASSIFICATIONS, {row["classification"] for row in parsed})
        self.assertEqual(1, len({(row["hgncId"], row["mondoId"], row["moi"]) for row in parsed}))

    def test_physical_source_lines_survive_quoted_multiline_fields(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.clingen, ",")]
        rows[6][2] = "synthetic first line\nsecond line"
        parsed, _, _ = prepare.parse_clingen(encode_rows(rows))
        self.assertEqual(7, parsed[0]["sourceRow"])
        self.assertEqual(9, parsed[1]["sourceRow"])

    def test_schema_and_domain_drift_fail_the_snapshot(self) -> None:
        original = [row for _, row in prepare.rows_with_lines(self.clingen, ",")]
        for column, value, reason in (
            (1, "20", "HGNC"), (3, "OMIM:123456", "MONDO"),
            (4, "NEW_MODE", "MOI"), (6, "New classification", "CLASSIFICATION"),
            (7, "https://unofficial.invalid/record", "REPORT_LOCATOR"),
        ):
            rows = copy.deepcopy(original)
            rows[6][column] = value
            with self.subTest(reason=reason), self.assertRaisesRegex(prepare.ResourceError, reason):
                prepare.parse_clingen(encode_rows(rows))
        original[4][0], original[4][1] = original[4][1], original[4][0]
        with self.assertRaisesRegex(prepare.ResourceError, "HEADER_DRIFT"):
            prepare.parse_clingen(encode_rows(original))

    def test_official_legacy_report_namespace_is_retained(self) -> None:
        rows = [row for _, row in prepare.rows_with_lines(self.clingen, ",")]
        rows[6][7] = "https://search.clinicalgenome.org/kb/gene-validity/CGGCIEX:assertion_6238"
        parsed, _, _ = prepare.parse_clingen(encode_rows(rows))
        self.assertEqual(rows[6][7], parsed[0]["reportLocator"])

    def prepare_snapshot(self, root: Path) -> tuple[Path, dict]:
        snapshot = root / "binding"
        snapshot.mkdir()
        mane_archive = gzip.compress(self.mane, mtime=0)
        (snapshot / prepare.MANE_NAME).write_bytes(mane_archive)
        (snapshot / "Clingen-Gene-Disease-Summary-2026-09-05.csv").write_bytes(self.clingen)
        metadata = {"resources": {
            name: {"sourceUrl": url, "retrievedAt": "2026-09-05T08:00:00+00:00",
                   "rawSha256": prepare.sha256(raw),
                   "httpLastModified": "Thu, 04 Dec 2025 19:53:44 GMT"}
            for name, url, raw in (("mane", prepare.MANE_URL, mane_archive),
                                   ("clingen", prepare.CLINGEN_URL, self.clingen))}}
        return snapshot, metadata

    def test_offline_rederivation_verifies_hashes_counts_archive_and_relative_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot, metadata = self.prepare_snapshot(root)
            manifest = prepare.prepare(root, snapshot, metadata)
            self.assertEqual(prepare.INTAKE_POLICY, manifest["intakePolicy"])
            for name, resource in manifest["resources"].items():
                self.assertFalse(Path(resource["rawRelativePath"]).is_absolute())
                self.assertEqual(resource["sourceCount"], resource["acceptedCount"] + resource["rejectedCount"])
                header = prepare.MANE_HEADER if name == "mane" else prepare.CLINGEN_HEADER
                self.assertEqual(prepare.header_sha256(header), resource["headerSha256"])
            original = (snapshot / "manifest.json").read_bytes()
            (snapshot / "Clingen-Gene-Disease-Summary-2026-09-05.csv").unlink()
            prepare.prepare(root, snapshot, manifest)
            self.assertEqual(original, (snapshot / "manifest.json").read_bytes())
            changed = copy.deepcopy(manifest)
            changed["resources"]["mane"]["acceptedCount"] -= 1
            with self.assertRaisesRegex(prepare.ResourceError, "SOURCE_COUNT_MISMATCH"):
                prepare.verify(root, changed)
            catalog = snapshot / "clingen-catalog.json"
            catalog.write_bytes(catalog.read_bytes() + b" ")
            with self.assertRaisesRegex(prepare.ResourceError, "DERIVED_HASH_MISMATCH"):
                prepare.verify(root, manifest)

    def test_bad_raw_identity_or_header_produces_no_derived_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot, metadata = self.prepare_snapshot(root)
            metadata["resources"]["mane"]["sourceUrl"] = "https://unofficial.invalid/mane"
            with self.assertRaisesRegex(prepare.ResourceError, "SOURCE_URL_MISMATCH"):
                prepare.prepare(root, snapshot, metadata)
            self.assertFalse((snapshot / "manifest.json").exists())
            self.assertFalse((snapshot / "mane-crosswalk.json").exists())

    def test_manifest_identity_tampering_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot, metadata = self.prepare_snapshot(root)
            manifest = prepare.prepare(root, snapshot, metadata)
            for key, value, reason in (
                ("headerSha256", "0" * 64, "HEADER_IDENTITY"),
                ("header", ["WRONG"], "HEADER_IDENTITY"),
                ("parserVersion", "999", "PARSER_VERSION"),
                ("resourceVersion", "1.4", "RESOURCE_VERSION"),
                ("assembly", "GRCh37", "ASSEMBLY"),
                ("archiveBytes", 0, "ARCHIVE_BYTES"),
                ("fileDate", "2000-01-01", "FILE_DATE"),
            ):
                changed = copy.deepcopy(manifest)
                changed["resources"]["mane"][key] = value
                with self.subTest(key=key), self.assertRaisesRegex(prepare.ResourceError, reason):
                    prepare.verify(root, changed)
            changed = copy.deepcopy(manifest)
            changed["intakePolicy"]["readyClassifications"].append("Limited")
            with self.assertRaisesRegex(prepare.ResourceError, "INTAKE_POLICY"):
                prepare.verify(root, changed)

    def test_prepare_and_verify_accept_utc_timestamps_without_rewriting_them(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot, metadata = self.prepare_snapshot(root)
            for retrieved_at in ("2026-09-05T08:00:00Z", "2026-09-05T08:00:00+00:00",
                                 "2026-09-05T08:00:00.123456789Z"):
                for resource in metadata["resources"].values():
                    resource["retrievedAt"] = retrieved_at
                manifest = prepare.prepare(root, snapshot, metadata)
                prepare.verify(root, manifest)
                self.assertTrue(all(resource["retrievedAt"] == retrieved_at
                                    for resource in manifest["resources"].values()))

    def test_prepare_and_verify_reject_non_utc_or_invalid_retrieval_timestamps(self) -> None:
        invalid_values = (
            "2026-09-05", "2026-09-05T08:00:00", "2026-09-05T08:00:00+08:00",
            "2026-09-05T08:00:00-01:00", "2026-02-30T08:00:00Z",
            "2026-09-05T24:00:00Z", "2026-09-05 08:00:00Z", "not-a-dateZ",
            "0000-01-01T00:00:00Z", "2026-09-05T08:00:00.1234567890Z",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot, metadata = self.prepare_snapshot(root)
            manifest = prepare.prepare(root, snapshot, metadata)
            original_files = {path.name: path.read_bytes() for path in snapshot.iterdir()}
            for resource_name in ("mane", "clingen"):
                for retrieved_at in invalid_values:
                    with self.subTest(resource=resource_name, retrieved_at=retrieved_at):
                        changed_metadata = copy.deepcopy(metadata)
                        changed_metadata["resources"][resource_name]["retrievedAt"] = retrieved_at
                        with self.assertRaisesRegex(prepare.ResourceError, "RETRIEVAL_UTC_REQUIRED"):
                            prepare.prepare(root, snapshot, changed_metadata)
                        changed_manifest = copy.deepcopy(manifest)
                        changed_manifest["resources"][resource_name]["retrievedAt"] = retrieved_at
                        with self.assertRaisesRegex(prepare.ResourceError, "RETRIEVAL_UTC_REQUIRED"):
                            prepare.verify(root, changed_manifest)
            self.assertEqual(original_files, {path.name: path.read_bytes() for path in snapshot.iterdir()})

    def test_invalid_gzip_and_size_fail(self) -> None:
        with self.assertRaisesRegex(prepare.ResourceError, "GZIP_INVALID"):
            prepare.decompress(b"not a gzip file")
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "too-large"
            with path.open("wb") as stream:
                stream.truncate(prepare.LIMIT + 1)
            with self.assertRaisesRegex(prepare.ResourceError, "RESOURCE_LIMIT_EXCEEDED"):
                prepare.bounded_read(path)


if __name__ == "__main__":
    unittest.main()
