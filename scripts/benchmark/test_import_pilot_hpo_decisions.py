import json
import tempfile
import unittest
from pathlib import Path

import import_pilot_hpo_decisions as importer


class PilotDecisionImporterTest(unittest.TestCase):
    def test_bounded_repair_and_normalization_preserve_decision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "PILOT-HPO-001.decision.json"
            source.write_text(self.raw_decision(), encoding="utf-8")
            raw = importer.load_bounded_broken_json(source)
            normalized = importer.normalize(raw, self.packet())
            self.assertEqual("NOT_RECORDED", normalized["priorTruthExposure"])
            self.assertTrue(normalized["aiAssistance"]["humanFinalConfirmation"])
            self.assertEqual("2026-06-23", normalized["aiAssistance"]["sourceHpoOntologyVersion"])
            self.assertEqual("KEEP_OBSERVED", normalized["termDecisions"][0]["decision"])
            self.assertEqual("READY_IDENTICAL_TO_G1", normalized["reviewCompletionStatus"])

    def test_unknown_json_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "broken.json"
            source.write_text('{\n"humanApprovalStatement": "broken,\n"still": ???\n}', encoding="utf-8")
            with self.assertRaisesRegex(importer.ImportErrorDetail, "PACKAGE_JSON_INVALID_AFTER_BOUNDED_REPAIR"):
                importer.load_bounded_broken_json(source)

    def test_identity_or_term_order_change_is_rejected(self):
        raw = json.loads(self.raw_decision().replace(
            '    "humanApprovalStatement": "broken,\n', ""))
        raw["termDecisions"][0]["hpoId"] = "HP:9999999"
        with self.assertRaisesRegex(importer.ImportErrorDetail, "TERM_ID_OR_ORDER_MISMATCH"):
            importer.normalize(raw, self.packet())

    def packet(self):
        return {
            "reviewProtocolVersion": importer.REVIEW_PROTOCOL,
            "reviewId": "PILOT-HPO-001",
            "packetFingerprint": "a" * 64,
            "packetContentChecksum": "b" * 64,
            "hpoOntologyVersion": "2026-06-23",
            "sourceContext": "PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE",
            "terms": [{"hpoId": "HP:0000001", "label": "Term", "originalStatus": "OBSERVED"}],
        }

    def raw_decision(self):
        return '''{
  "schemaVersion": "truth-masked-hpo-review-decision-v1",
  "reviewProtocolVersion": "truth-masked-development-review-v1",
  "reviewId": "PILOT-HPO-001",
  "packetFingerprint": "%s",
  "packetContentChecksum": "%s",
  "reviewMethod": "TRUTH_MASKED_DEVELOPMENT_REVIEW",
  "reviewerRole": "HUMAN_PROJECT_REVIEWER",
  "priorTruthExposure": "NOT_ATTESTED",
  "activeTruthReviewReported": false,
  "eligibleForStrictBlindedEvaluation": false,
  "aiAssistance": {
    "used": true,
    "provider": "OpenAI Codex",
    "humanApprovalRecorded": true,
    "humanApprovalStatement": "broken,
    "scope": "HPO_IDENTIFIER_AND_LABEL_SEMANTIC_MAPPING"
  },
  "semanticAuditNotes": [],
  "reviewCompletionStatus": "HUMAN_REVIEW_COMPLETED",
  "reviewDate": "2026-07-17",
  "termDecisions": [{"hpoId":"HP:0000001","originalStatus":"OBSERVED","decision":"KEEP_OBSERVED","mappingErrorReason":null}],
  "additions": []
}''' % ("a" * 64, "b" * 64)


if __name__ == "__main__":
    unittest.main()
