import json
import tempfile
import unittest
from pathlib import Path
from build_gencode_transcript_index import annotation, build


def fixture(negative=False):
    identity = 'gene_id "ENSG000001.1"; transcript_id "ENST000001.4"; gene_name "TEST"; hgnc_id "HGNC:1"; transcript_type "protein_coding"; protein_id "ENSP000001.2"; '
    strand = "-" if negative else "+"
    # Two UTR-containing exons; CDS crosses a junction; stop is absent from CDS.
    values = [("transcript", 96, 115, ".", 0), ("exon", 96, 102, ".", 2 if negative else 1),
              ("exon", 110, 115, ".", 1 if negative else 2)]
    values += ([("CDS", 110, 113, "0", 1), ("CDS", 101, 102, "2", 2), ("start_codon", 111, 113, "0", 1), ("stop_codon", 98, 100, "0", 2)]
               if negative else [("CDS", 98, 102, "0", 1), ("CDS", 110, 110, "1", 2), ("start_codon", 98, 100, "0", 1), ("stop_codon", 111, 113, "0", 2)])
    return "".join(f'chr1\tHAVANA\t{kind}\t{lo}\t{hi}\t.\t{strand}\t{phase}\t{identity}'
                   + (f'exon_number {exon}; ' if exon else '') + '\n' for kind, lo, hi, phase, exon in values).encode()


class GencodeIndexTest(unittest.TestCase):
    def test_both_strands_keep_utr_offsets_and_stop_codon(self):
        for negative in (False, True):
            result = annotation(fixture(negative))
            self.assertEqual(9, result["cdsLength"])
            self.assertEqual(13, result["transcriptLength"])
            self.assertEqual(-1, result["exons"][0]["cdsStart"])
            self.assertEqual(-1 if negative else 1, result["strand"])

    def test_malformed_phase_missing_stop_incomplete_and_overlap_refused(self):
        cases = [fixture().replace(b'\t1\tgene_id', b'\t0\tgene_id'),
                 b'\n'.join(x for x in fixture().split(b'\n') if b'\tstop_codon\t' not in x),
                 fixture().replace(b'protein_id "ENSP000001.2";', b'protein_id "ENSP000001.2"; tag "cds_end_NF";'),
                 fixture().replace(b'\texon\t110\t115', b'\texon\t102\t115')]
        for raw in cases:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                annotation(raw)

    def test_ambiguous_identity_and_protein_version_refused(self):
        for raw in (fixture().replace(b'ENSP000001.2', b'ENSP000001'),
                    fixture().replace(b'hgnc_id "HGNC:1";', b'hgnc_id "HGNC:1"; hgnc_id "HGNC:2";', 1),
                    fixture().replace(b'\t+\t1\t', b'\t-\t1\t')):
            with self.assertRaises(ValueError):
                annotation(raw)

    def test_streamed_index_is_pinned_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source.gtf"
            source.write_bytes(b'##description: evidence-based annotation of the human genome (GRCh38), version 38 (Ensembl 104)\n' + fixture())
            result = build(source, root / "out", 38)
            self.assertEqual(1, result["counts"]["indexed"])
            self.assertFalse(result["independentRnaVerification"])
            index = (root / "out/index.tsv").read_text().split("\t")
            self.assertEqual("ENST000001.4", index[0])
            self.assertEqual((root / "out/annotations.jsonl").stat().st_size, int(index[2]))
            with self.assertRaises(FileExistsError):
                build(source, root / "out", 38)

    def test_wrong_release_or_repeated_transcript_does_not_publish_manifest(self):
        for duplicate in (False, True):
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder); source = root / "source.gtf"
                gene = b'chr1\tHAVANA\tgene\t96\t115\t.\t+\t.\tgene_id "ENSG000001.1";\n'
                source.write_bytes(b'##description: (GRCh38), version 38 (Ensembl 104)\n' + fixture() + (gene + fixture() if duplicate else b''))
                with self.assertRaises(ValueError):
                    build(source, root / "out", 38 if duplicate else 39)
                self.assertFalse((root / "out/manifest.json").exists())

    def test_inventory_keeps_excluded_models_and_source_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source = root / "source.gtf"
            noncoding = fixture().replace(b'ENST000001.4', b'ENST000002.1').replace(b'protein_coding', b'lncRNA')
            incomplete = fixture().replace(b'ENST000001.4', b'ENST000003.1').replace(
                b'protein_id "ENSP000001.2";', b'protein_id "ENSP000001.2"; tag "cds_start_NF";')
            source.write_bytes(b'##description: (GRCh38), version 38 (Ensembl 104)\n' + fixture() + noncoding + incomplete)
            manifest = build(source, root / "out", 38)
            self.assertEqual(3, manifest['transcripts'])
            rows = [json.loads(raw) for raw in (root / 'out/inventory.jsonl').read_bytes().splitlines()]
            self.assertEqual(['AVAILABLE', 'UNRESOLVED', 'UNRESOLVED'], [r['structureStatus'] for r in rows])
            self.assertEqual('lncRNA', rows[1]['transcriptType'])
            self.assertEqual('GTF_INCOMPLETE_OR_READTHROUGH', rows[2]['reason'])
            self.assertTrue(all(r['hgncId'] == 'HGNC:1' and r['geneSymbol'] == 'TEST' for r in rows))
            index = (root / 'out/inventory-index.tsv').read_text().splitlines()
            self.assertEqual(3, len(index))
            for line, row in zip(index, rows):
                tx, hgnc, symbol, offset, length, sha = line.split('\t')
                with (root / 'out/inventory.jsonl').open('rb') as stream:
                    stream.seek(int(offset)); raw = stream.read(int(length))
                self.assertEqual(row, json.loads(raw))
                self.assertEqual((row['transcript'], 'HGNC:1', 'TEST'), (tx, hgnc, symbol))


if __name__ == "__main__":
    unittest.main()
