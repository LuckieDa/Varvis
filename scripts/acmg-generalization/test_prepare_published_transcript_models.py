"""Fixed synthetic controls for real-source preparation mechanics; no policy assertions."""
import copy
import gzip
import json
import tempfile
import unittest
from pathlib import Path

import prepare_published_transcript_models as b
from build_transcript_model import parse_refseq


def genbank(version="2", old=False):
    rna, start, end, exons = ("TTTTATGAAATAAGGAAA", 5, 13, [(1, 7), (8, 15)]) if old else ("CCATGAAATAAGG", 3, 11, [(1, 5), (6, 13)])
    return (f"VERSION     NM_000001.{version}\nCOMMENT     MANE Ensembl match :: ENST000001.4/ENSP000001.3\n"
            "FEATURES             Location/Qualifiers\n"
            '                     /gene="TEST"\n                     /db_xref="HGNC:123"\n'
            + "".join(f"     exon            {a}..{z}\n" for a, z in exons)
            + f"     CDS             {start}..{end}\n"
            + '                     /protein_id="NP_000001.1"\n                     /translation="MK"\n'
            + f"ORIGIN\n        1 {rna.lower()}\n//\n").encode()


class PublishedTranscriptModelTest(unittest.TestCase):
    def setUp(self):
        self.rna = genbank()
        self.old = genbank("1", True)
        self.model = dict(schemaVersion="native-coding-transcript-model-v1", modelId="GENCODE_TEST", modelVersion="1.0.0",
            transcript="ENST000001.4", protein="ENSP000001.3", assembly="GRCh38", contig="1", strand=1,
            hgncId="HGNC:123", geneSymbol="TEST", cdsSequence="ATGAAATAA", cdsLength=9, proteinLength=2,
            exons=[dict(ordinal=1, genomicStart=1, genomicEnd=5, cdsStart=-1, cdsEnd=3),
                   dict(ordinal=2, genomicStart=10, genomicEnd=17, cdsStart=4, cdsEnd=11)],
            sequenceVerification=dict(fullSplicedTranscriptSha256=b.sha(b"CCATGAAATAAGG"), cdsSha256=b.sha(b"ATGAAATAA"),
                queriedBases=13, method="PINNED_GTF_PLUS_QUALIFIED_REFERENCE", independentRnaVerification=False,
                referenceIdentity=dict(verificationStatus="VERIFIED", expectedSha256="a"*64, observedSha256="a"*64, indexSha256="b"*64)),
            sourceRecords=[dict(sourceId="GENCODE:ENST000001.4", sourceKind="GENCODE_GTF_LOCAL_SNAPSHOT", version="test",
                sha256="c"*64, annotationRowSha256="d"*64, rawGtfSha256="e"*64, indexManifestSha256="f"*64, url="https://example.org/gtf")])
        self.header = "#NCBI_GeneID\tEnsembl_Gene\tHGNC_ID\tsymbol\tname\tRefSeq_nuc\tRefSeq_prot\tEnsembl_nuc\tEnsembl_prot\tMANE_status\tGRCh38_chr\tchr_start\tchr_end\tchr_strand\n"
        self.row = "GeneID:1\tENSG000001.1\tHGNC:123\tTEST\tsynthetic\tNM_000001.2\tNP_000001.1\tENST000001.4\tENSP000001.3\tMANE Select\tNC_000001.11\t1\t17\t+\n"
        self.mane = gzip.compress((self.header+self.row).encode())

    def source(self, raw, version="2"):
        return dict(version="NM_000001."+version, sha256=b.sha(raw), url="https://example.org/refseq")

    def enrich(self):
        return b.enrich(json.dumps(self.model).encode(), self.rna, self.source(self.rna), self.mane, "test", "test-new")

    def test_complete_bridge_preserves_true_sources_and_original_model(self):
        model = self.enrich()
        self.assertEqual(model["transcriptBridge"]["refseqTranscript"], "NM_000001.2")
        self.assertEqual(model["sequenceVerification"]["rnaEquivalence"]["codingExonIntervals"], [[1,3],[4,9]])
        self.assertEqual(model["sourceRecords"][0], self.model["sourceRecords"][0])
        self.assertFalse(self.model["sequenceVerification"]["independentRnaVerification"])
        self.assertNotIn("reviewStatus", model)

    def test_coding_transfer_retains_different_rna_and_strict_parser_rejection(self):
        with self.assertRaisesRegex(ValueError, "REFSEQ_EXONS_INCOMPLETE"):
            parse_refseq(self.old)
        model = b.add_coding_transfer(self.enrich(), self.old, self.source(self.old,"1"), self.rna)
        transfer = model["codingTranscriptTransfers"][0]
        self.assertFalse(transfer["fullRnaEquivalent"])
        self.assertFalse(transfer["fullTranscriptEquivalenceClaimed"])
        self.assertFalse(transfer["sourceFullExonCoverage"])
        self.assertEqual(transfer["sourceTranscript"], "NM_000001.1")
        self.assertEqual(model["transcriptBridge"]["refseqTranscript"], "NM_000001.2")

    def test_independent_full_rna_cds_exon_gene_and_mane_mismatches_reject(self):
        original = copy.deepcopy(self.model)
        mutations = [lambda m:m.update(hgncId="HGNC:124"), lambda m:m.update(geneSymbol="OTHER"),
                     lambda m:m.update(protein="ENSP000001.4"), lambda m:m.update(cdsSequence="ATGAGATAA"),
                     lambda m:m["sequenceVerification"].update(fullSplicedTranscriptSha256="0"*64),
                     lambda m:m["exons"][0].update(cdsEnd=4), lambda m:m["exons"][0].update(genomicEnd=6),
                     lambda m:m["sequenceVerification"]["referenceIdentity"].update(verificationStatus="UNAVAILABLE"),
                     lambda m:m["sourceRecords"][0].update(sourceKind="ALIAS_ONLY"),
                     lambda m:m.update(transcript="ENST000001.5")]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.model=copy.deepcopy(original); mutation(self.model)
                with self.assertRaises(ValueError): self.enrich()

    def test_transfer_rejects_coding_gap_gene_protein_version_and_source_pin(self):
        cases = [self.old.replace(b'exon            8..15',b'exon            9..15'),
                 self.old.replace(b'HGNC:123',b'HGNC:124'), self.old.replace(b'NP_000001.1',b'NP_000001.2'),
                 self.old.replace(b'NM_000001.1',b'NM_000002.1'), self.old.replace(b'atgaaat',b'atgagat'),
                 self.old.replace(b'exon            1..7',b'exon            1..6')]
        for raw in cases:
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError): b.add_coding_transfer(self.enrich(),raw,self.source(raw,"1"),self.rna)
        with self.assertRaisesRegex(ValueError,"BINDING_MISMATCH"):
            b.add_coding_transfer(self.enrich(),self.old,self.source(b"wrong","1"),self.rna)
        with self.assertRaisesRegex(ValueError,"DISTINCT_VERSION"):
            b.add_coding_transfer(self.enrich(),self.rna,self.source(self.rna),self.rna)

    def test_row_pin_is_actual_raw_line_and_never_an_alias(self):
        result=self.enrich()
        self.assertEqual(result["sourceRecords"][2]["rowRawSha256"],b.sha(self.row.encode()))
        self.mane=gzip.compress((self.header+self.row.replace("NM_000001.2","NM_000001.3")).encode())
        with self.assertRaisesRegex(ValueError,"EXACT_MANE_ROW_REQUIRED"): self.enrich()

    def test_mane_genomic_identity_cannot_borrow_another_chromosome_version_or_strand(self):
        original = copy.deepcopy(self.model)
        for contig, strand, accession in [("2", 1, "NC_000001.11"), ("1", 1, "NC_000001.10"),
                                          ("1", 1, "NT_113891.3"), ("1", 0, "NC_000001.11"),
                                          ("1", -1, "NC_000001.11")]:
            with self.subTest(contig=contig, strand=strand, accession=accession):
                self.model = copy.deepcopy(original)
                self.model.update(contig=contig, strand=strand)
                self.mane = gzip.compress((self.header + self.row.replace("NC_000001.11", accession)).encode())
                with self.assertRaisesRegex(ValueError, "MANE_IDENTITY_OR_BOUNDS_MISMATCH"):
                    self.enrich()

    def test_batch_enforces_raw_pins_before_output_and_never_overwrites(self):
        with tempfile.TemporaryDirectory(prefix="published-model-test-") as temporary:
            root=Path(temporary)
            def file(name,raw):
                path=root/name; path.write_bytes(raw); return dict(file=str(path),sha256=b.sha(raw))
            r=file("rna.gb",self.rna)
            source=self.source(self.rna)|dict(destination=r["file"],status="CAPTURED_PENDING_SOURCE_REVIEW")
            plan=dict(schemaVersion="published-transcript-model-plan-v1",modelVersion="test",
                mane=file("mane.gz",self.mane)|dict(version="test"),sourceLedger=file("ledger.json",json.dumps(dict(entries=[source])).encode()),
                models=[dict(model=file("input.json",json.dumps(self.model).encode()),refseq=r)])
            output=root/"output"
            result=b.prepare(plan,output)
            self.assertEqual(len(result["entries"]),1)
            with self.assertRaisesRegex(ValueError,"OUTPUT_EXISTS"): b.prepare(plan,output)
            plan["models"][0]["refseq"]["sha256"]="0"*64
            with self.assertRaisesRegex(ValueError,"RAW_PIN_MISMATCH"): b.prepare(plan,root/"unwritten")
            self.assertFalse((root/"unwritten").exists())


if __name__ == "__main__":
    unittest.main()
