import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_varvis_exomiser_baseline as comparator
import convert_varvis_export_to_comparison_input as converter


def write_json(path, payload):
    path.write_text(
        json.dumps(payload, ensure_ascii=True, sort_keys=True),
        encoding="utf-8",
    )


def variant_item(variant_id="GRCh38:1:100:A:G", gene="GENE1", rank=None):
    chromosome, position, reference, alternate = variant_id.split(":")[1:]
    item = {
        "variantId": variant_id,
        "observation": {
            "key": {
                "genomeAssembly": "GRCh38",
                "chromosome": chromosome,
                "position": int(position),
                "reference": reference,
                "alternate": alternate,
            },
            "sampleId": "HG001",
            "genotype": "0/1",
            "filterStatus": "PASS",
            "variantType": "SNV",
        },
        "tier": "UNRESOLVED",
        "totalScore": 12.5,
        "scoreBreakdown": {"population_frequency": 10.0},
        "positiveReasons": ["Synthetic low-frequency fixture reason."],
        "warnings": ["Synthetic fixture only; not clinical evidence."],
        "transcriptAnnotations": [],
    }
    if gene:
        item["transcriptAnnotations"].append(
            {
                "geneSymbol": gene,
                "transcriptAccession": "ENST000001",
                "transcriptSource": "protein_coding",
                "impact": "MODERATE",
                "canonical": True,
                "maneSelect": True,
            }
        )
    if rank is not None:
        item["rank"] = rank
    return item


def audit_payload():
    return {
        "input": {
            "sampleId": "HG001",
            "assembly": "GRCh38",
            "hpoTerms": ["HP:0001250", "HP:0004322"],
            "noPatientData": True,
        },
        "phenotypeOverlapStatus": "NO_GENE_OVERLAP",
        "rootCauseClassification": "LEGITIMATE_ZERO_OVERLAP",
        "candidateGeneAudit": {
            "candidateUniqueGenes": 1,
            "allAnnotatedUniqueGenes": 1,
            "hardGateEligibleUniqueGenes": 1,
            "missingGeneCandidateVariants": 1,
        },
        "phenotypePriorAudit": {
            "returnedPriorGenes": 50,
            "providerSourceTotalGenes": 17918,
            "maxGenesConfig": 50,
            "maxGenesActsAsProviderAndRankingLimit": True,
        },
        "overlap": {
            "candidateVsTop50Count": 0,
            "candidateVsTop50Genes": [],
            "eligibleCandidateVsTop50Count": 0,
            "candidateVsFullProviderCount": 0,
            "candidateVsFullProviderGenes": [],
        },
    }


class ConvertVarvisExportToComparisonInputTest(unittest.TestCase):

    def run_converter(self, directory, input_payload, extra_args=None):
        root = Path(directory)
        input_path = root / "varvis_input.json"
        audit_path = root / "audit.json"
        output_path = root / "varvis_ranking.json"
        manifest_path = root / "varvis_ranking_manifest.json"
        write_json(input_path, input_payload)
        write_json(audit_path, audit_payload())
        argv = [
            "--input-json",
            str(input_path),
            "--output-json",
            str(output_path),
            "--manifest",
            str(manifest_path),
            "--sample-id",
            "HG001",
            "--assembly",
            "GRCh38",
            "--phenotype-audit",
            str(audit_path),
            "--phenotype-overlap-status",
            "NO_GENE_OVERLAP",
        ]
        if extra_args:
            argv.extend(extra_args)
        self.assertEqual(0, converter.main(argv))
        return output_path, manifest_path

    def test_variants_page_conversion_preserves_no_overlap_and_unmapped_candidates(self):
        payload = {
            "analysisId": "analysis-1",
            "view": "CANDIDATES",
            "page": 1,
            "pageSize": 1000,
            "total": 2,
            "candidateTotal": 2,
            "allTotal": 2,
            "frequencyFilteredTotal": 0,
            "variants": [
                variant_item("GRCh38:1:100:A:G", "MAPPED1"),
                variant_item("GRCh38:1:200:C:T", None),
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            output_path, manifest_path = self.run_converter(directory, payload)
            output = json.loads(output_path.read_text(encoding="utf-8"))
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

            loaded = comparator.load_varvis_candidates(output_path)

        self.assertFalse(output["rankingApplied"])
        self.assertEqual("NO_GENE_OVERLAP", output["phenotypeOverlapStatus"])
        self.assertEqual(2, output["statistics"]["rankingSourceCandidateCount"])
        self.assertEqual(1, output["statistics"]["comparisonCandidateCount"])
        self.assertEqual(1, output["statistics"]["unmappedCandidateCount"])
        self.assertEqual("MAPPED1", output["candidates"][0]["gene"])
        self.assertEqual("MISSING_GENE_SYMBOL", output["unmappedCandidates"][0]["geneMappingStatus"])
        self.assertEqual("NO_GENE_OVERLAP", output["candidates"][0]["phenotypeSupport"]["phenotypeOverlapStatus"])
        self.assertEqual(1, len(loaded))
        self.assertEqual("MAPPED1", loaded[0]["gene"])
        self.assertEqual("real_varvis_ranking_ready_with_no_phenotype_overlap", manifest["status"])

    def test_official_export_conversion_reads_top_candidates_and_cap_metadata(self):
        export_payload = {
            "summary": {
                "candidateCount": 2,
                "exportedCount": 1,
                "filteredTotalForRequest": 2,
                "exportView": "CANDIDATES",
                "transcriptAnnotationStatus": "COMPLETED",
                "phenotypeSummary": {"rankingApplied": False},
            },
            "topCandidates": [variant_item("GRCh38:2:300:G:A", "GENE2", rank=1)],
            "generatedAt": "2026-06-26T00:00:00Z",
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            official_path = root / "official_export.json"
            write_json(official_path, export_payload)
            output_path, _manifest_path = self.run_converter(
                directory,
                export_payload,
                extra_args=["--official-export-json", str(official_path)],
            )
            output = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual("official_export", output["source"]["inputKind"])
        self.assertEqual(1, output["statistics"]["comparisonCandidateCount"])
        self.assertEqual(2, output["officialExport"]["candidateCount"])
        self.assertEqual(1, output["officialExport"]["exportedCount"])
        self.assertFalse(output["officialExport"]["complete"])

    def test_gene_selection_prefers_mane_canonical_transcript_annotation(self):
        item = variant_item("GRCh38:3:400:T:C", None)
        item["transcriptAnnotations"] = [
            {
                "geneSymbol": "GENE_LOW",
                "transcriptSource": "lncRNA",
                "impact": "MODIFIER",
            },
            {
                "geneSymbol": "GENE_BEST",
                "transcriptSource": "protein_coding",
                "impact": "MODERATE",
                "canonical": True,
                "maneSelect": True,
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            output_path, _manifest_path = self.run_converter(
                directory,
                {"variants": [item]},
            )
            output = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual("GENE_BEST", output["candidates"][0]["gene"])
        self.assertEqual(["GENE_LOW", "GENE_BEST"], output["candidates"][0]["allGeneSymbols"])

    def test_output_is_deterministic_utf8_without_bom_and_no_local_paths(self):
        payload = {"variants": [variant_item("GRCh38:4:500:A:C", "GENE4")]}
        with tempfile.TemporaryDirectory() as directory:
            output_path, manifest_path = self.run_converter(directory, payload)
            first = output_path.read_bytes()
            manifest_first = manifest_path.read_bytes()
            output_path_2, manifest_path_2 = self.run_converter(directory, payload)
            second = output_path_2.read_bytes()
            manifest_second = manifest_path_2.read_bytes()
            text = second.decode("utf-8")
            manifest_text = manifest_second.decode("utf-8")

        self.assertEqual(first, second)
        self.assertEqual(manifest_first, manifest_second)
        self.assertFalse(second.startswith(b"\xef\xbb\xbf"))
        self.assertFalse(manifest_second.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(directory, text)
        self.assertNotIn(directory, manifest_text)
        self.assertIn('"rankingApplied": false', text)
        self.assertIn('"phenotypeOverlapStatus": "NO_GENE_OVERLAP"', text)


if __name__ == "__main__":
    unittest.main()
