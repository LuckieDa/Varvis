"""Synthetic sequence/coordinate controls, separate from public model/source acceptance."""
import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("native_builder", Path(__file__).with_name("build_transcript_model.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class TranscriptModelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="varvis-model-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.refseq = dict(transcript="NM_000001.1", protein="NP_000001.1", symbol="SYNTHETIC",
                           hgncId="HGNC:1", sequence="ATGAAATAA", cdsSequence="ATGAAATAA",
                           cdsStart=1, cdsEnd=9, proteinSequence="MK", exons=[(1, 3), (4, 9)],
                           maneMatches=[("ENST00000000001.1", "ENSP00000000001.1")])

    def inputs(self, strand=1):
        # Independently specified positive and negative genomic strings; introns are not transcribed.
        genome = "ATGCCCCAAATAA" if strand == 1 else "TTATTTGGGGCAT"
        fasta = self.root / "reference.fa"
        fasta.write_bytes(b">1\n" + b"\n".join(genome[i:i + 4].encode("ascii") for i in range(0, len(genome), 4)) + b"\n")
        Path(str(fasta) + ".fai").write_text("1\t13\t3\t4\t5\n", encoding="ascii")
        bounds = [(1, 3), (8, 13)] if strand == 1 else [(11, 13), (1, 6)]
        lookup = dict(assembly_name="GRCh38", seq_region_name="1", species="homo_sapiens",
                      object_type="Transcript", biotype="protein_coding", strand=strand,
                      id="ENST00000000001", version=1, length=9,
                      Translation=dict(id="ENSP00000000001", version=1, Parent="ENST00000000001", length=2, start=1, end=13),
                      Exon=[dict(start=lo, end=hi, assembly_name="GRCh38", seq_region_name="1", strand=strand) for lo, hi in bounds])
        return lookup, builder.IndexedFasta(fasta)

    def build(self, lookup, fasta, refseq=None):
        return builder.build_model(refseq or self.refseq, lookup, fasta, [], "ensembl", "synthetic-v1")

    def test_both_strands_preserve_exact_sequence_and_exon_order(self):
        for strand in (1, -1):
            with self.subTest(strand=strand):
                lookup, fasta = self.inputs(strand)
                model = self.build(lookup, fasta)
                self.assertEqual(model["cdsSequence"], "ATGAAATAA")
                self.assertEqual(model["lastJunctionCdsPosition"], 3)
                self.assertEqual(model["exons"][1]["cdsStart"], 4)
                self.assertEqual(model["sequenceVerification"]["queriedBases"], 9)

    def test_source_identity_comes_from_versioned_records_not_arbitrary_filenames(self):
        original = self.root / 'research-gene-old-name.txt'; lookup_file = self.root / 'download-42.data'
        original.write_bytes(b'synthetic-refseq-original'); lookup_file.write_bytes(b'synthetic-ensembl-original')
        lookup, unused_fasta = self.inputs()
        ledger = {'entries': [dict(destination=str(path), sha256=builder.sha(path.read_bytes()),
                                   version=version, url='https://example.invalid/synthetic',
                                   status='CAPTURED_PENDING_SOURCE_REVIEW') for path, version in
                               [(original, 'NM_000001.1'), (lookup_file, 'ENST00000000001.1')]]}
        records = builder.source_records(self.refseq, lookup, original, lookup_file, ledger)
        self.assertEqual('RefSeq:NM_000001.1', records[0]['sourceId'])
        self.assertEqual('NCBI_VERSIONED_REFSEQ_GENBANK', records[0]['sourceKind'])
        self.assertEqual('Ensembl:ENST00000000001.1', records[1]['sourceId'])
        ledger['entries'][0]['version'] = 'NM_000001.2'
        with self.assertRaisesRegex(ValueError, 'SOURCE_LEDGER_VERSION_MISMATCH'):
            builder.source_records(self.refseq, lookup, original, lookup_file, ledger)

    def test_wrong_native_sequence_and_versioned_mane_bridge_are_rejected(self):
        lookup, fasta = self.inputs()
        wrong = copy.deepcopy(self.refseq)
        wrong["sequence"] = "ATGCAATAA"
        with self.assertRaisesRegex(ValueError, "FULL_TRANSCRIPT_MISMATCH"):
            self.build(lookup, fasta, wrong)
        lookup, fasta = self.inputs()
        lookup["version"] = 2
        with self.assertRaisesRegex(ValueError, "VERSIONED_BRIDGE_MISMATCH"):
            self.build(lookup, fasta)

    def test_annotation_cds_boundary_and_exon_order_cannot_be_inferred(self):
        lookup, fasta = self.inputs()
        lookup["Translation"]["start"] = 2
        with self.assertRaisesRegex(ValueError, "CDS_BOUNDARIES_MISMATCH"):
            self.build(lookup, fasta)
        lookup, fasta = self.inputs(-1)
        lookup["Exon"].reverse()
        with self.assertRaisesRegex(ValueError, "EXON_LENGTH_MISMATCH|EXON_ORDER"):
            self.build(lookup, fasta)

    def test_fasta_query_is_bounded_and_does_not_read_introns(self):
        _, fasta = self.inputs()
        self.assertEqual(fasta.region("1", 1, 3), "ATG")
        self.assertEqual(fasta.bases_read, 3)
        with self.assertRaisesRegex(ValueError, "REGION_OR_INDEX_INVALID"):
            fasta.region("1", 1, 100)
        fasta.bases_read = builder.MAX_TRANSCRIPT_BASES
        with self.assertRaisesRegex(ValueError, "QUERY_BUDGET_EXCEEDED"):
            fasta.region("1", 1, 1)


if __name__ == "__main__":
    unittest.main()
