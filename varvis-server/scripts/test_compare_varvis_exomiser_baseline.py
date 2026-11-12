import json
import tempfile
import unittest
from pathlib import Path

import compare_varvis_exomiser_baseline as comparator


SCRIPT_DIR = Path(__file__).resolve().parent
VARVIS_FIXTURE = SCRIPT_DIR / "fixtures" / "varvis_ranking_synthetic.json"
EXOMISER_FIXTURE = SCRIPT_DIR / "fixtures" / "exomiser_baseline_synthetic.tsv"


def write_json(path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=True, sort_keys=True), encoding="utf-8")


def write_exomiser_tsv(path, rows):
    header = comparator.exomiser_validator.CANONICAL_COLUMNS
    with path.open("w", encoding="utf-8", newline="") as handle:
        handle.write("\t".join(header) + "\n")
        for row in rows:
            handle.write("\t".join(str(row.get(column, "")) for column in header) + "\n")


def exomiser_row(**overrides):
    row = {column: "" for column in comparator.exomiser_validator.CANONICAL_COLUMNS}
    row.update(
        {
            "case_id": "HG001",
            "hpo_terms": "HP:0001250;HP:0004322",
            "gene_symbol": "GENE1",
            "exomiser_gene_rank": "1",
            "exomiser_score": "0.9",
            "exomiser_gene_score": "0.8",
            "source": "EXOMISER_BASELINE",
            "source_version": "exomiser-15.1.0;data-2602",
            "genome_build": "GRCh38",
            "variant_match_status": "MATCHED",
            "evidence": "Synthetic test row only",
        }
    )
    row.update(overrides)
    return row


class CompareVarvisExomiserBaselineTest(unittest.TestCase):

    def test_synthetic_fixtures_are_readable(self):
        varvis = comparator.load_varvis_candidates(VARVIS_FIXTURE)
        exomiser = comparator.load_exomiser_rows(EXOMISER_FIXTURE)

        self.assertEqual(4, len(varvis))
        self.assertEqual(4, len(exomiser))
        self.assertEqual("KCNQ2", varvis[0]["gene"])
        self.assertEqual("FGFR3", exomiser[0]["gene"])

    def test_gene_overlap_calculation(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            top_k=2,
        )

        self.assertEqual(2, summary["top_k_gene_overlap_count"])
        self.assertEqual(["KCNQ2", "FGFR3"], summary["top_k_gene_overlap_genes"])

    def test_variant_matched_target(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            target_gene="KCNQ2",
            target_variant="20:62076081:G:A",
            top_k=2,
        )

        self.assertEqual("MATCHED", summary["target_variant"]["variant_match_status"])
        self.assertEqual(1, summary["target_variant"]["varvis_rank"])
        self.assertEqual(1, summary["target_variant"]["exomiser_variant_rank"])
        self.assertEqual("VARVIS_HIGH_EXOMISER_HIGH", summary["discordance_category"])

    def test_unmatched_variant_category(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            target_gene="PEX19",
            target_variant="1:16000000:C:T",
            top_k=2,
        )

        self.assertIsNone(summary["target_variant"]["varvis_rank"])
        self.assertEqual(2, summary["target_variant"]["exomiser_variant_rank"])
        self.assertEqual("UNMATCHED_VARIANT", summary["discordance_category"])

    def test_target_gene_rank_and_delta(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            target_gene="FGFR3",
            top_k=2,
        )

        self.assertEqual(2, summary["target_gene"]["varvis_rank"])
        self.assertEqual(1, summary["target_gene"]["exomiser_gene_rank"])
        self.assertEqual(1, summary["target_gene"]["rank_delta"])

    def test_discordance_category_for_varvis_high_exomiser_low(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            target_gene="KCNQ2",
            top_k=1,
        )

        self.assertEqual("VARVIS_HIGH_EXOMISER_LOW", summary["discordance_category"])

    def test_markdown_and_json_report_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_md = root / "comparison.md"
            output_json = root / "comparison.json"
            summary = comparator.compare(
                VARVIS_FIXTURE,
                EXOMISER_FIXTURE,
                "SYNTH-EXO-001",
                target_gene="KCNQ2",
                target_variant="20:62076081:G:A",
                top_k=2,
            )
            comparator.write_report(summary, output_md, output_json)
            md_text = output_md.read_text(encoding="utf-8")
            json_payload = json.loads(output_json.read_text(encoding="utf-8"))

        self.assertIn("# Varvis vs Exomiser Baseline Comparison", md_text)
        self.assertIn("## Safety Note", md_text)
        self.assertIn(comparator.SAFETY_NOTE, md_text)
        self.assertEqual("SYNTH-EXO-001", json_payload["case_id"])
        self.assertNotIn(str(root), md_text)
        self.assertNotIn(str(root), json.dumps(json_payload))

    def test_local_absolute_path_detection_in_varvis_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad_varvis.json"
            payload = {
                "caseId": "SYNTH-EXO-LOCAL-PATH",
                "candidates": [
                    {
                        "rank": 1,
                        "gene": "KCNQ2",
                        "evidence": r"D:\LSD\Project\Tool\exomiser\data",
                    }
                ],
            }
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "local absolute path"):
                comparator.load_varvis_candidates(path)

    def test_safety_note_exists_in_summary(self):
        summary = comparator.compare(
            VARVIS_FIXTURE,
            EXOMISER_FIXTURE,
            "SYNTH-EXO-001",
            target_gene="KCNQ2",
            top_k=2,
        )

        self.assertEqual(comparator.SAFETY_NOTE, summary["safety_note"])
        self.assertIn("not a diagnosis", summary["safety_note"])

    def test_no_target_real_schema_semantics_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            varvis_path = root / "varvis_ranking.json"
            exomiser_path = root / "exomiser_baseline.tsv"
            output_md = root / "comparison.md"
            output_json = root / "comparison.json"
            output_manifest = root / "comparison_manifest.json"
            write_json(
                varvis_path,
                {
                    "sampleId": "HG001",
                    "assembly": "GRCh38",
                    "hpoTerms": ["HP:0001250", "HP:0004322"],
                    "rankingApplied": False,
                    "phenotypeOverlapStatus": "NO_GENE_OVERLAP",
                    "officialExport": {
                        "candidateCount": 3,
                        "exportedCount": 2,
                        "complete": False,
                    },
                    "statistics": {
                        "rankingSourceCandidateCount": 3,
                        "comparisonCandidateCount": 2,
                        "unmappedCandidateCount": 1,
                        "uniqueGeneCount": 2,
                    },
                    "candidates": [
                        {
                            "rank": 1,
                            "gene": "GENE1",
                            "tier": "REVIEW",
                            "totalScore": 25.0,
                            "variant": {
                                "assembly": "GRCh38",
                                "chrom": "chr1",
                                "pos": 100,
                                "ref": "a",
                                "alt": "g",
                                "variantId": "v1",
                            },
                        },
                        {
                            "rank": 2,
                            "gene": "GENE2",
                            "tier": "UNRESOLVED",
                            "totalScore": 10.0,
                            "variant": {
                                "assembly": "GRCh38",
                                "chrom": "2",
                                "pos": 200,
                                "ref": "C",
                                "alt": "T",
                                "variantId": "v2",
                            },
                        },
                    ],
                    "unmappedCandidates": [
                        {
                            "rank": 3,
                            "geneMappingStatus": "MISSING_GENE_SYMBOL",
                            "variant": {
                                "assembly": "GRCh38",
                                "chrom": "3",
                                "pos": 300,
                                "ref": "G",
                                "alt": "A",
                                "variantId": "v3",
                            },
                        }
                    ],
                },
            )
            write_exomiser_tsv(
                exomiser_path,
                [
                    exomiser_row(
                        gene_symbol="GENE1",
                        chrom="1",
                        pos="100",
                        ref="A",
                        alt="G",
                        exomiser_gene_rank="1",
                        exomiser_variant_rank="",
                    ),
                    exomiser_row(
                        gene_symbol="GENE5",
                        chrom="3",
                        pos="300",
                        ref="G",
                        alt="A",
                        exomiser_gene_rank="2",
                        exomiser_variant_rank="",
                    ),
                    exomiser_row(
                        gene_symbol="GENE3",
                        chrom="4",
                        pos="400",
                        ref="C",
                        alt="T",
                        exomiser_gene_rank="3",
                        exomiser_variant_rank="",
                    ),
                    exomiser_row(
                        gene_symbol="GENE4",
                        exomiser_gene_rank="4",
                        variant_match_status="GENE_ONLY",
                    ),
                ],
            )

            summary = comparator.compare(
                varvis_path,
                exomiser_path,
                "HG001",
                expected_sample="HG001",
                expected_assembly="GRCh38",
                expected_hpo_terms="HP:0001250,HP:0004322",
            )
            comparator.write_report(
                summary,
                output_md,
                output_json,
                output_manifest,
                varvis_json=varvis_path,
                exomiser_tsv=exomiser_path,
            )
            json_payload = json.loads(output_json.read_text(encoding="utf-8"))
            manifest_payload = json.loads(output_manifest.read_text(encoding="utf-8"))
            md_text = output_md.read_text(encoding="utf-8")
            output_json_bytes = output_json.read_bytes()
            output_manifest_bytes = output_manifest.read_bytes()

        technical = json_payload["technicalComparison"]
        self.assertTrue(json_payload["noTargetMode"])
        self.assertIsNone(json_payload["target_gene"])
        self.assertIsNone(json_payload["target_variant"])
        self.assertEqual("NO_TARGET_TECHNICAL_COMPARISON", json_payload["discordance_category"])
        self.assertEqual(1, technical["geneOverlap"]["overlapCount"])
        self.assertEqual(5, technical["geneOverlap"]["unionCount"])
        self.assertAlmostEqual(0.2, technical["geneOverlap"]["jaccard"])
        self.assertEqual(2, technical["variantOverlap"]["exactMatchedVariantCount"])
        self.assertEqual(1, technical["variantOverlap"]["mappedGeneExactMatchedVariantCount"])
        self.assertEqual(1, technical["variantOverlap"]["varvisOnlyVariantCount"])
        self.assertEqual(1, technical["variantOverlap"]["exomiserOnlyVariantCount"])
        self.assertEqual(comparator.RANK_NOT_COMPUTABLE, technical["rankComparability"]["variantRankDeltaStatus"])
        self.assertEqual(2, technical["discordanceSummary"]["RANK_NOT_COMPARABLE"])
        self.assertEqual(1, technical["discordanceSummary"]["VARVIS_GENE_UNMAPPED"])
        self.assertEqual(0, technical["discordanceSummary"]["GENE_MATCH_VARIANT_DIFFERENT"])
        self.assertEqual("NO_GENE_OVERLAP", technical["phenotypeRankingStatus"]["phenotypeOverlapStatus"])
        self.assertIn("No target gene or target variant was supplied.", md_text)
        self.assertEqual("real_varvis_vs_exomiser_technical_comparison_completed", manifest_payload["status"])
        self.assertNotIn(directory, md_text)
        self.assertNotIn(directory, json.dumps(json_payload))
        self.assertNotIn(directory, json.dumps(manifest_payload))
        self.assertFalse(output_json_bytes.startswith(b"\xef\xbb\xbf"))
        self.assertFalse(output_manifest_bytes.startswith(b"\xef\xbb\xbf"))


if __name__ == "__main__":
    unittest.main()
