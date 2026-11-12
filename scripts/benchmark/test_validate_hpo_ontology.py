#!/usr/bin/env python3
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "validate_hpo_ontology.py"
SPEC = importlib.util.spec_from_file_location("validate_hpo_ontology", MODULE_PATH)
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class HpoOntologyValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="hpo-ontology-test-"))
        self.ontology = self.temp / "hp.obo"
        shutil.copyfile(ROOT / "testdata" / "hpo_ontology" / "synthetic-hp.obo", self.ontology)
        self.packets = self.temp / "packets"
        self.packets.mkdir()
        packet = {
            "terms": [
                {"hpoId": "HP:0000001", "label": "Current term", "originalStatus": "OBSERVED"},
                {"hpoId": "HP:0009098", "label": "Chronic oral candidiasis", "originalStatus": "EXCLUDED"},
            ]
        }
        (self.packets / "SMOKE-HPO-001.packet.json").write_text(json.dumps(packet), encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.temp)

    def arguments(self, **overrides):
        values = {
            "ontology": str(self.ontology),
            "packet_directory": str(self.packets),
            "packet_prefix": "SMOKE-HPO-",
            "expected_version": "2026-06-23",
            "resource_id": "hpo-ontology-2026-06-23",
            "source_uri": "https://example.invalid/hp.obo",
            "official_checksum_status": "SOURCE_CHECKSUM_NOT_PUBLISHED",
            "official_sha256": None,
            "downloaded_at": "2026-07-16T00:00:00Z",
            "report_output": str(self.temp / "report.json"),
            "resource_manifest_output": str(self.temp / "manifest.json"),
            "strict_semantic": True,
        }
        values.update(overrides)
        return type("Arguments", (), values)()

    def test_valid_release_current_and_exact_synonym_round_trip(self):
        report, semantic_failed = VALIDATOR.run(self.arguments())
        self.assertFalse(semantic_failed)
        self.assertEqual("COMPLETED", report["validationStatus"])
        self.assertEqual(2, report["uniqueHpoIdCount"])
        self.assertEqual(1, report["matchCounts"]["currentLabelMatch"])
        self.assertEqual(1, report["matchCounts"]["exactSynonymMatch"])
        self.assertEqual("EXACT_SYNONYM", report["semanticAuditNote"]["relationship"])
        synonym_term = next(item for item in report["terms"] if item["hpoId"] == "HP:0009098")
        self.assertEqual("Recurrent oral thrush", synonym_term["currentOfficialName"])
        manifest = json.loads((self.temp / "manifest.json").read_text(encoding="utf-8"))
        self.assertNotIn(str(self.temp), json.dumps(manifest))
        first = (self.temp / "report.json").read_bytes()
        first_manifest = (self.temp / "manifest.json").read_bytes()
        VALIDATOR.run(self.arguments())
        self.assertEqual(first, (self.temp / "report.json").read_bytes())
        self.assertEqual(first_manifest, (self.temp / "manifest.json").read_bytes())

    def test_missing_and_obsolete_terms_are_semantic_failures(self):
        packet = json.loads((self.packets / "SMOKE-HPO-001.packet.json").read_text(encoding="utf-8"))
        packet["terms"].extend([
            {"hpoId": "HP:0000002", "label": "Obsolete term", "originalStatus": "OBSERVED"},
            {"hpoId": "HP:9999999", "label": "Missing term", "originalStatus": "OBSERVED"},
        ])
        (self.packets / "SMOKE-HPO-001.packet.json").write_text(json.dumps(packet), encoding="utf-8")
        report, semantic_failed = VALIDATOR.run(self.arguments())
        self.assertTrue(semantic_failed)
        self.assertEqual(1, report["matchCounts"]["obsolete"])
        self.assertEqual(1, report["matchCounts"]["missing"])
        obsolete_term = next(item for item in report["terms"] if item["hpoId"] == "HP:0000002")
        self.assertEqual(["HP:0000001"], obsolete_term["replacedBy"])
        packet["terms"].append({"hpoId": "HP:0000001", "label": "Incorrect label", "originalStatus": "OBSERVED"})
        (self.packets / "SMOKE-HPO-001.packet.json").write_text(json.dumps(packet), encoding="utf-8")
        report, semantic_failed = VALIDATOR.run(self.arguments())
        self.assertTrue(semantic_failed)
        self.assertEqual(1, report["matchCounts"]["labelMismatch"])

    def test_invalid_inputs_are_rejected(self):
        with self.assertRaises(VALIDATOR.ValidationError) as incomplete:
            VALIDATOR.run(self.arguments(ontology=str(self.temp / "hp.obo.part")))
        self.assertEqual("INCOMPLETE_INPUT_REJECTED", incomplete.exception.code)
        self.ontology.write_text("format-version: 1.2\ndata-version: hp/releases/2026-06-23\n", encoding="utf-8")
        with self.assertRaises(VALIDATOR.ValidationError) as malformed:
            VALIDATOR.run(self.arguments())
        self.assertEqual("TERM_SECTION_MISSING", malformed.exception.code)
        self.ontology.write_text((ROOT / "testdata" / "hpo_ontology" / "synthetic-hp.obo").read_text(encoding="utf-8") + "\n[Term]\nid: HP:0000001\nname: Duplicate\n", encoding="utf-8")
        with self.assertRaises(VALIDATOR.ValidationError) as duplicate:
            VALIDATOR.run(self.arguments())
        self.assertEqual("DUPLICATE_TERM_ID", duplicate.exception.code)
        shutil.copyfile(ROOT / "testdata" / "hpo_ontology" / "synthetic-hp.obo", self.ontology)
        with self.assertRaises(VALIDATOR.ValidationError) as wrong_version:
            VALIDATOR.run(self.arguments(expected_version="2025-01-01"))
        self.assertEqual("DATA_VERSION_MISMATCH", wrong_version.exception.code)
        with self.assertRaises(VALIDATOR.ValidationError) as checksum:
            VALIDATOR.run(self.arguments(official_checksum_status="OFFICIAL_RELEASE_ASSET_SHA256_MATCH", official_sha256="0" * 64))
        self.assertEqual("OFFICIAL_CHECKSUM_MISMATCH", checksum.exception.code)

    def test_accepts_explicit_pilot_packet_prefix_and_rejects_unsafe_prefix(self):
        (self.packets / "SMOKE-HPO-001.packet.json").rename(
            self.packets / "PILOT-HPO-001.packet.json")
        report, semantic_failed = VALIDATOR.run(self.arguments(packet_prefix="PILOT-HPO-"))
        self.assertFalse(semantic_failed)
        self.assertEqual(1, len(report["packetFileNames"]))
        with self.assertRaises(VALIDATOR.ValidationError) as invalid:
            VALIDATOR.run(self.arguments(packet_prefix="../PILOT-"))
        self.assertEqual("PACKET_PREFIX_INVALID", invalid.exception.code)


if __name__ == "__main__":
    unittest.main()
