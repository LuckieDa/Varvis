"""Use the Perl test's synthetic export; no real variants, VEP run or resource query."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


HERE = Path(__file__).resolve().parent
exporter = module("candidate_converter", HERE / "convert_export.py")
builder = module("existing_transcript_builder", HERE.parent / "build_transcript_model.py")
parser = argparse.ArgumentParser()
parser.add_argument("--fixture-dir", required=True)
ARGS = parser.parse_args()
FIXTURES = Path(ARGS.fixture_dir)
REQUEST_BYTES = (FIXTURES / "synthetic-request.json").read_bytes()
EXPORT_BYTES = (FIXTURES / "synthetic-export.jsonl").read_bytes()
REQUEST = exporter.strict_json(REQUEST_BYTES)
RECORDS = [exporter.strict_json(line) for line in EXPORT_BYTES.splitlines()]
REQUEST_SHA = exporter.sha(REQUEST_BYTES)
EXPORTER_SHA = exporter.sha((HERE / "VarvisTranscriptModelExport.pm").read_bytes())
TARGET = "ENST00000000001.2"


class ExportConversionTest(unittest.TestCase):
    def records(self):
        return copy.deepcopy(RECORDS)

    def validate(self, records, request=None):
        return exporter.validate_export(request or REQUEST, REQUEST_SHA, records, EXPORTER_SHA)

    def reject(self, records, reason):
        with self.assertRaisesRegex(ValueError, reason):
            self.validate(records)

    def reseal(self, records):
        records[1]["objectSha256"] = exporter.sha(exporter.canonical(records[1]["body"]))
        records[-1]["transcripts"][0]["sha256"] = records[1]["objectSha256"]

    def test_actual_perl_output_canonical_hash_and_native_coordinates(self):
        body = self.validate(self.records())[TARGET]
        self.assertEqual(body["lookup"]["Translation"]["start"], 201)
        self.assertEqual(body["lookup"]["Translation"]["end"], 303)
        self.assertEqual(body["codingBoundaries"]["cdnaStart"], 4)
        self.assertEqual(body["objectSequenceChecks"]["cdsSha256"], exporter.sha(b"ATGAAATAA"))

    def test_pinned_export_and_new_destination(self):
        with tempfile.TemporaryDirectory(dir=FIXTURES) as directory:
            destination = Path(directory) / "candidates"
            result = exporter.convert(FIXTURES / "synthetic-request.json", REQUEST_SHA,
                                      FIXTURES / "synthetic-export.jsonl", exporter.sha(EXPORT_BYTES), EXPORTER_SHA, destination)
            self.assertFalse(result["defaultEnabled"])
            self.assertEqual(result["sourceKind"], exporter.SOURCE_KIND)
            entry = result["entries"][0]
            self.assertEqual(exporter.sha((destination / entry["file"]).read_bytes()), entry["sha256"])
            with self.assertRaises(FileExistsError):
                exporter.convert(FIXTURES / "synthetic-request.json", REQUEST_SHA,
                                 FIXTURES / "synthetic-export.jsonl", exporter.sha(EXPORT_BYTES), EXPORTER_SHA, destination)

    def test_tampered_bytes_create_no_destination(self):
        with tempfile.TemporaryDirectory(dir=FIXTURES) as directory:
            destination = Path(directory) / "candidate"
            with self.assertRaisesRegex(ValueError, "SOURCE_SHA_MISMATCH"):
                exporter.convert(FIXTURES / "synthetic-request.json", REQUEST_SHA,
                                 FIXTURES / "synthetic-export.jsonl", "0" * 64, EXPORTER_SHA, destination)
            self.assertFalse(destination.exists())

    def test_wrong_exporter_source_not_accepted_by_rebinding_object_hash(self):
        records = self.records()
        records[0]["exporterSha256"] = "0" * 64
        self.reject(records, "EXPORT_HEADER_IDENTITY_MISMATCH")

    def test_missing_footer(self):
        self.reject(self.records()[:-1], "EXPORT_RECORD_COUNT_INVALID")

    def test_conflict_never_salvaged(self):
        records = self.records()
        records.insert(2, dict(recordType="CONFLICT"))
        self.reject(records, "EXPORT_CONFLICT_OR_UNEXPECTED_RECORD")

    def test_duplicate_same_object_is_not_a_second_unique_record(self):
        records = self.records()
        records.insert(2, copy.deepcopy(records[1]))
        self.reject(records, "EXPORT_TARGET_DUPLICATE_OR_UNREQUESTED")

    def test_not_observed_and_rejected_are_not_sources(self):
        for status in ("NOT_OBSERVED", "REJECTED", "CONFLICT"):
            with self.subTest(status=status):
                records = self.records()
                records[-1]["transcripts"][0]["status"] = status
                self.reject(records, "EXPORT_REQUEST_NOT_UNIQUELY_OBSERVED")

    def test_version_alias_and_resealed_hash_do_not_rewrite_identity(self):
        for field, value in (("id", "NM_000001"), ("version", 3)):
            with self.subTest(field=field):
                records = self.records()
                records[1]["body"]["lookup"][field] = value
                self.reseal(records)
                self.reject(records, "TRANSCRIPT_IDENTITY_INVALID")

    def test_resealed_native_coordinate_contradiction_rejected(self):
        records = self.records()
        records[1]["body"]["codingBoundaries"]["genomicBiologicalStart"] = 202
        self.reseal(records)
        self.reject(records, "CDS_BOUNDARY_MAPPING_MISMATCH")

    def test_resealed_protein_length_contradiction_rejected(self):
        records = self.records()
        records[1]["body"]["lookup"]["Translation"]["length"] = 3
        self.reseal(records)
        self.reject(records, "CDS_TRANSLATION_BOUNDARY_MISMATCH")

    def test_resealed_exon_gap_and_unknown_condition_rejected(self):
        records = self.records()
        records[1]["body"]["lookup"]["Exon"][0]["cdnaEnd"] = 2
        self.reseal(records)
        self.reject(records, "EXON_CDNA_OR_VERSION_INVALID")
        records = self.records()
        records[1]["body"]["additionalRequirements"] = {"operator": "unimplemented"}
        self.reseal(records)
        self.reject(records, "UNSUPPORTED_OR_MISSING_FIELD")

    def test_same_fasta_cannot_be_labeled_independent_rna(self):
        records = self.records()
        records[1]["body"]["objectSequenceChecks"]["independence"] = "INDEPENDENT_RNA"
        self.reseal(records)
        self.reject(records, "SEQUENCE_PROVENANCE_INVALID")

    def test_json_duplicate_float_and_boolean_coordinate_rejected(self):
        for content, reason in ((b'{"a":1,"a":2}', "DUPLICATE_JSON_KEY"), (b'{"a":1.0}', "NON_INTEGER_JSON_NUMBER")):
            with self.subTest(content=content), self.assertRaisesRegex(ValueError, reason):
                exporter.strict_json(content)
        records = self.records()
        records[1]["body"]["lookup"]["Exon"][0]["ordinal"] = True
        self.reseal(records)
        self.reject(records, "EXON_IDENTITY_INVALID")

    def test_current_builder_with_independent_synthetic_rna_and_tiny_fasta(self):
        body = self.validate(self.records())[TARGET]
        # Independently declared RNA source fixture: not read back from the FASTA.
        refseq = builder.parse_refseq(b'''VERSION     NM_000001.2
COMMENT     MANE Ensembl match :: ENST00000000001.2/ENSP00000000001.3
     exon            1..3
     exon            4..9
     exon            10..15
     CDS             4..12
                     /gene="SYNTHETIC"
                     /db_xref="HGNC:999999"
                     /protein_id="NP_000001.3"
                     /translation="MK"
ORIGIN
        1 cccatgaaataaggg
//
''')
        self.assertEqual(body["objectSequenceChecks"]["cdsSha256"], exporter.sha(refseq["cdsSequence"].encode("ascii")))
        self.assertEqual(body["objectSequenceChecks"]["peptideSha256"], exporter.sha(refseq["proteinSequence"].encode("ascii")))
        with tempfile.TemporaryDirectory(dir=FIXTURES) as directory:
            fasta_path = Path(directory) / "synthetic.fa"
            genome = bytearray(b"N" * 400)
            for start, sequence in ((101, b"CCC"), (201, b"ATGAAA"), (301, b"TAAGGG")):
                genome[start - 1:start - 1 + len(sequence)] = sequence
            fasta_path.write_bytes(b">10\n" + genome + b"\n")
            Path(str(fasta_path) + ".fai").write_bytes(b"10\t400\t4\t400\t401\n")
            model = builder.build_model(refseq, body["lookup"], builder.IndexedFasta(fasta_path),
                                        [dict(sourceKind="SYNTHETIC_INDEPENDENT_RNA_AND_NATIVE_DNA")], "ensembl", "synthetic-v1")
            self.assertEqual(model["cdsSequence"], "ATGAAATAA")
            self.assertEqual(model["sequenceVerification"]["queriedBases"], 15)
            self.assertEqual(model["exons"][0]["cdsEnd"], 0)
            bad_bridge = copy.deepcopy(refseq)
            bad_bridge["maneMatches"] = [("ENST00000000001.3", "ENSP00000000001.3")]
            with self.assertRaisesRegex(ValueError, "REFSEQ_MANE_VERSIONED_BRIDGE_MISMATCH"):
                builder.build_model(bad_bridge, body["lookup"], builder.IndexedFasta(fasta_path), [], "ensembl", "test")
            genome[100] = ord("A")
            fasta_path.write_bytes(b">10\n" + genome + b"\n")
            with self.assertRaisesRegex(ValueError, "GENOMIC_REFSEQ_FULL_TRANSCRIPT_MISMATCH"):
                builder.build_model(refseq, body["lookup"], builder.IndexedFasta(fasta_path), [], "ensembl", "test")


if __name__ == "__main__":
    unittest.main(argv=[__file__], verbosity=2)
