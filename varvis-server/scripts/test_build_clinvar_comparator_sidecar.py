import gzip
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

import build_clinvar_comparator_sidecar as builder


VARIANT_HEADER = [
    "#AlleleID",
    "Type",
    "Name",
    "GeneID",
    "GeneSymbol",
    "HGNC_ID",
    "ClinicalSignificance",
    "ClinSigSimple",
    "LastEvaluated",
    "RS# (dbSNP)",
    "nsv/esv (dbVar)",
    "RCVaccession",
    "PhenotypeIDS",
    "PhenotypeList",
    "Origin",
    "OriginSimple",
    "Assembly",
    "ChromosomeAccession",
    "Chromosome",
    "Start",
    "Stop",
    "ReferenceAllele",
    "AlternateAllele",
    "Cytogenetic",
    "ReviewStatus",
    "NumberSubmitters",
    "Guidelines",
    "TestedInGTR",
    "OtherIDs",
    "SubmitterCategories",
    "VariationID",
    "PositionVCF",
    "ReferenceAlleleVCF",
    "AlternateAlleleVCF",
]

SUBMISSION_HEADER = [
    "#VariationID",
    "ClinicalSignificance",
    "DateLastEvaluated",
    "Description",
    "SubmittedPhenotypeInfo",
    "ReportedPhenotypeInfo",
    "ReviewStatus",
    "CollectionMethod",
    "OriginCounts",
    "Submitter",
    "SCV",
    "SubmittedGeneSymbol",
    "ExplanationOfInterpretation",
]


def variant_row(
    variation_id,
    assembly="GRCh38",
    variant_type="single nucleotide variant",
    chromosome="7",
    position="140753336",
    reference="A",
    alternate="T",
):
    values = {name.lstrip("#"): "-" for name in VARIANT_HEADER}
    values.update(
        {
            "AlleleID": "29000",
            "Type": variant_type,
            "GeneSymbol": "BRAF",
            "ClinicalSignificance": "Conflicting classifications of pathogenicity",
            "LastEvaluated": "2026-02-01",
            "RCVaccession": "RCV000013293.13|RCV000022727.11",
            "PhenotypeIDS": "MONDO:0015280|MONDO:0021060",
            "PhenotypeList": "Cardio-facio-cutaneous syndrome|RASopathy",
            "Assembly": assembly,
            "Chromosome": chromosome,
            "ReviewStatus": "criteria provided, conflicting classifications",
            "VariationID": str(variation_id),
            "PositionVCF": position,
            "ReferenceAlleleVCF": reference,
            "AlternateAlleleVCF": alternate,
        }
    )
    return [values[name.lstrip("#")] for name in VARIANT_HEADER]


def submission_row(
    variation_id,
    scv,
    classification,
    submitted_condition,
    reported_condition,
    date="2026-01-01",
    review="criteria provided, single submitter",
    gene="BRAF",
):
    values = {name.lstrip("#"): "-" for name in SUBMISSION_HEADER}
    values.update(
        {
            "VariationID": str(variation_id),
            "ClinicalSignificance": classification,
            "DateLastEvaluated": date,
            "SubmittedPhenotypeInfo": submitted_condition,
            "ReportedPhenotypeInfo": reported_condition,
            "ReviewStatus": review,
            "SCV": scv,
            "SubmittedGeneSymbol": gene,
        }
    )
    return [values[name.lstrip("#")] for name in SUBMISSION_HEADER]


def write_gzip_tsv(path, header, rows, comments=()):
    with gzip.open(path, "wt", encoding="utf-8", newline="") as handle:
        for comment in comments:
            handle.write(comment + "\n")
        handle.write("\t".join(header) + "\n")
        for row in rows:
            handle.write("\t".join(row) + "\n")


def find_tool(environment_name, executable):
    configured = os.environ.get(environment_name)
    if configured and Path(configured).is_file():
        return configured
    return shutil.which(executable)


class BuildClinvarComparatorSidecarTest(unittest.TestCase):

    def test_builds_tabix_sidecar_without_flattening_submission_conditions(self):
        bgzip = find_tool("BGZIP", "bgzip")
        tabix = find_tool("TABIX", "tabix")
        if not bgzip or not tabix:
            self.skipTest("BGZIP and TABIX are required for the sidecar integration test")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            variant_path = root / "variant_summary_2026-03.txt.gz"
            submission_path = root / "submission_summary_2026-03.txt.gz"
            output = root / "clinvar-comparator.tsv.gz"
            manifest_path = root / "clinvar-comparator.manifest.json"
            write_gzip_tsv(
                variant_path,
                VARIANT_HEADER,
                [
                    variant_row(13961),
                    variant_row(13962, alternate="C"),
                    variant_row(13962, alternate="G"),
                    variant_row(13963, assembly="GRCh37"),
                    variant_row(13964, variant_type="Deletion", reference="AT", alternate="A"),
                    variant_row(13965, chromosome="1", position="925775", reference="G", alternate="T"),
                ],
            )
            write_gzip_tsv(
                submission_path,
                SUBMISSION_HEADER,
                [
                    submission_row(
                        13961,
                        "SCV000000001.2",
                        "Pathogenic",
                        "MONDO:0015280:Cardio-facio-cutaneous syndrome",
                        "MedGen:C1275081:Cardio-facio-cutaneous syndrome",
                    ),
                    submission_row(
                        13961,
                        "SCV000000002.1",
                        "Uncertain significance",
                        "MONDO:0021060:RASopathy",
                        "MedGen:C5555857:RASopathy",
                        date="-",
                        review="-",
                        gene="-",
                    ),
                    submission_row(99999, "SCV999999999.1", "Benign", "none", "none"),
                ],
                comments=("##Overview", "##Explanation"),
            )
            args = builder.parse_args(
                [
                    "--variant-summary",
                    str(variant_path),
                    "--submission-summary",
                    str(submission_path),
                    "--variant-summary-url",
                    "https://example.test/variant_summary_2026-03.txt.gz",
                    "--variant-summary-published-at",
                    "2026-03-05T05:05:02Z",
                    "--submission-summary-url",
                    "https://example.test/submission_summary_2026-03.txt.gz",
                    "--submission-summary-published-at",
                    "2026-03-05T05:05:41Z",
                    "--output",
                    str(output),
                    "--manifest-output",
                    str(manifest_path),
                    "--source-release",
                    "ClinVar 2026-03 monthly TSV",
                    "--resource-id",
                    "CLINVAR_COMPARATOR_2026_03_GRCH38",
                    "--base-clinvar-resource-id",
                    "CLINVAR_2026_03_21_GRCH38",
                    "--base-clinvar-resource-version",
                    "ClinVar 2026-03-21 GRCh38",
                    "--base-clinvar-identity-fingerprint",
                    "2" * 64,
                    "--bgzip",
                    bgzip,
                    "--tabix",
                    tabix,
                ]
            )

            manifest = builder.build(args)

            self.assertTrue(output.is_file())
            self.assertTrue(Path(str(output) + ".tbi").is_file())
            queried = subprocess.run(
                [tabix, str(output), "VID:13961-13961"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            fields = queried.split("\t")
            self.assertEqual("13961", fields[3])
            self.assertEqual("7", fields[5])
            self.assertEqual("140753336", fields[6])
            self.assertEqual("CONFLICTING", fields[10])
            variant_records = json.loads(fields[11])
            self.assertEqual(
                "Cardio-facio-cutaneous syndrome|RASopathy",
                variant_records[0]["condition"]["namesRaw"],
            )
            self.assertEqual(
                "MONDO:0015280|MONDO:0021060",
                variant_records[0]["condition"]["identifiersRaw"],
            )
            submissions = json.loads(fields[12])
            self.assertEqual(2, len(submissions))
            self.assertEqual("SCV000000001.2", submissions[0]["scv"])
            self.assertEqual(
                "MONDO:0015280:Cardio-facio-cutaneous syndrome",
                submissions[0]["condition"]["submittedRaw"],
            )
            self.assertEqual(
                "submission_summary_2026-03.txt.gz",
                submissions[0]["sourceLocator"]["logicalName"],
            )
            self.assertEqual(4, submissions[0]["sourceLocator"]["line"])
            self.assertEqual("SCV000000002.1", submissions[1]["scv"])
            self.assertEqual(
                "MONDO:0021060:RASopathy",
                submissions[1]["condition"]["submittedRaw"],
            )
            self.assertIsNone(submissions[1]["dateLastEvaluated"])
            self.assertIsNone(submissions[1]["reviewStatus"])
            self.assertIsNone(submissions[1]["geneSymbol"])

            with gzip.open(output, "rt", encoding="utf-8") as handle:
                output_lines = [line for line in handle if not line.startswith("#")]
            self.assertEqual(2, len(output_lines))
            self.assertEqual(2, manifest["counts"]["eligibleVariationIds"])
            self.assertEqual(1, manifest["counts"]["ambiguousVariationIdsExcluded"])
            self.assertEqual(2, manifest["counts"]["matchedSubmissionRows"])
            self.assertEqual(2, manifest["counts"]["outputRows"])

            serialized_manifest = manifest_path.read_text(encoding="utf-8")
            self.assertNotIn(str(root), serialized_manifest)
            self.assertEqual(False, manifest["clinicalValidation"])
            self.assertEqual(False, manifest["patientDataIncluded"])
            self.assertEqual(
                "not used as submitted assertion content",
                manifest["derivation"]["vcfClnsigscvUsage"],
            )

    def test_rejects_missing_required_source_column(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "variant_summary.txt.gz"
            write_gzip_tsv(path, ["#VariationID", "Assembly"], [["1", "GRCh38"]])

            with self.assertRaisesRegex(ValueError, "missing required columns"):
                builder.require_columns(path, builder.VARIANT_REQUIRED_COLUMNS)

    def test_staged_build_is_resumable_and_does_not_duplicate_rows(self):
        bgzip = find_tool("BGZIP", "bgzip")
        tabix = find_tool("TABIX", "tabix")
        if not bgzip or not tabix:
            self.skipTest("BGZIP and TABIX are required for the staged integration test")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            variant_path = root / "variant_summary_2026-03.txt.gz"
            submission_path = root / "submission_summary_2026-03.txt.gz"
            output = root / "clinvar-comparator.tsv.gz"
            manifest_path = root / "clinvar-comparator.manifest.json"
            work_database = root / "work.sqlite"
            write_gzip_tsv(
                variant_path,
                VARIANT_HEADER,
                [
                    variant_row(13961),
                    variant_row(13962, alternate="C"),
                    variant_row(13962, alternate="G"),
                    variant_row(13965, chromosome="1", position="925775", reference="G", alternate="T"),
                ],
            )
            write_gzip_tsv(
                submission_path,
                SUBMISSION_HEADER,
                [
                    submission_row(13961, "SCV000000001.2", "Pathogenic", "condition one", "condition one"),
                    submission_row(13961, "SCV000000002.1", "Uncertain significance", "condition two", "condition two"),
                    submission_row(99999, "SCV999999999.1", "Benign", "other", "other"),
                ],
                comments=("##Overview",),
            )
            common = [
                "--variant-summary", str(variant_path),
                "--submission-summary", str(submission_path),
                "--variant-summary-url", "https://example.test/variant_summary_2026-03.txt.gz",
                "--variant-summary-published-at", "2026-03-05T05:05:02Z",
                "--submission-summary-url", "https://example.test/submission_summary_2026-03.txt.gz",
                "--submission-summary-published-at", "2026-03-05T05:05:41Z",
                "--output", str(output),
                "--manifest-output", str(manifest_path),
                "--source-release", "ClinVar 2026-03 monthly TSV",
                "--resource-id", "CLINVAR_COMPARATOR_2026_03_GRCH38",
                "--base-clinvar-resource-id", "CLINVAR_2026_03_21_GRCH38",
                "--base-clinvar-resource-version", "ClinVar 2026-03-21 GRCh38",
                "--base-clinvar-identity-fingerprint", "2" * 64,
                "--bgzip", bgzip,
                "--tabix", tabix,
                "--work-database", str(work_database),
            ]

            def run_stage(stage):
                return builder.build(builder.parse_args(common + ["--stage", stage]))

            run_stage("initialize")
            connection = sqlite3.connect(work_database)
            try:
                self.assertEqual("wal", connection.execute("PRAGMA journal_mode").fetchone()[0])
            finally:
                connection.close()
            with self.assertRaisesRegex(ValueError, "requires completed stage load-variants"):
                run_stage("prepare-eligible")

            original_variant_bytes = variant_path.read_bytes()
            with gzip.open(variant_path, "at", encoding="utf-8") as handle:
                handle.write("changed\n")
            with self.assertRaisesRegex(ValueError, "Source identity mismatch"):
                run_stage("load-variants")
            variant_path.write_bytes(original_variant_bytes)

            for stage in (
                "load-variants",
                "prepare-eligible",
                "load-submissions",
                "bundle-variants",
                "bundle-submissions",
                "emit",
            ):
                run_stage(stage)
                result = run_stage(stage)

            self.assertEqual(2, result["counts"]["outputRows"])
            self.assertEqual(2, result["counts"]["eligibleVariationIds"])
            self.assertEqual(1, result["counts"]["ambiguousVariationIdsExcluded"])
            self.assertEqual(2, result["counts"]["matchedSubmissionRows"])
            connection = sqlite3.connect(work_database)
            try:
                self.assertEqual(4, connection.execute("SELECT COUNT(*) FROM variant_rows").fetchone()[0])
                self.assertEqual(2, connection.execute("SELECT COUNT(*) FROM submission_rows").fetchone()[0])
                self.assertEqual(7, connection.execute("SELECT COUNT(*) FROM completed_stages").fetchone()[0])
            finally:
                connection.close()
            queried = subprocess.run(
                [tabix, str(output), "VID:13961-13961"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            self.assertEqual("13961", queried.split("\t")[3])


if __name__ == "__main__":
    unittest.main()
