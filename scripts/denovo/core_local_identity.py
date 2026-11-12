"""Local, pinned identity supply for reviewed literature. No scoring or network.

Reuses the accepted-reference reader and the legacy ClinVar alias reader. A MANE
row maps an explicitly reported RefSeq version; it never supplies a missing one.
"""
import csv
import gzip
import io
from pathlib import Path
import re
import sys

from model_literature_contract import canonical, digest, parse
from core_material_contract import bounded
from literature_allele_identity import coding, protein, read_record, resolve

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'acmg-generalization'))
from normalize_public_panel import BoundedReference
from build_transcript_model import CODONS, reverse_complement

VERSION = 'denovo-core-local-identity-1.0.0'


def require(ok, code):
    if not ok:
        raise ValueError('DENOVO_IDENTITY_' + code)


def local_file(root, name):
    require(isinstance(name, str) and not Path(name).is_absolute(), 'RELATIVE_PATH_REQUIRED')
    path = (root / name).resolve(strict=True)
    require(path.is_relative_to(root / 'local') and path.is_file(), 'LOCAL_FILE_REQUIRED')
    return path


def pinned(root, spec, maximum=16 * 1024 * 1024):
    path = local_file(root, spec['path'])
    raw = bounded(path, maximum)
    require(digest(raw) == spec['sha256'], 'RESOURCE_PIN_MISMATCH')
    return raw


def identifiers(doc):
    g, v = doc['groups']['gene']['symbol'], doc['groups']['variant']['representations']
    require(all(f['verification'] == 'VERIFIED' and f['availability'] == 'REPORTED' for f in (g, v)),
            'REVIEWED_IDENTIFIERS_REQUIRED')
    variants, transcripts, proteins = set(), set(), set()
    for value in v['value']:
        value = value.strip()
        if re.fullmatch(r'NM_[0-9]+\.[0-9]+', value):
            transcripts.add(value)
        elif re.fullmatch(r'(?:NM_[0-9]+\.[0-9]+:)?(?:c\.)?[0-9]+[ACGT]\s*>\s*[ACGT]', value):
            # Normalize display spacing only in this verified-identifier view.
            # The raw source and old source/response replay stay byte-identical.
            tx, change = coding(re.sub(r'(?<=[ACGT])\s*>\s*(?=[ACGT]$)', '>', value))
            variants.add(change)
            if tx:
                transcripts.add(tx)
        elif re.fullmatch(r'(?:p\.)?\(?[A-Za-z*]+[0-9]+[A-Za-z*]+\)?', value):
            proteins.add(protein(value))
        else:
            # Never silently ignore a second, unsupported HGVS representation.
            raise ValueError('DENOVO_IDENTITY_REPRESENTATION_UNSUPPORTED')
    require(len(variants) == len(proteins) == 1 and len(transcripts) <= 1, 'IDENTIFIER_AMBIGUOUS_OR_MISSING')
    return g['value'], next(iter(variants)), next(iter(proteins)), next(iter(transcripts), None)


class LocalIdentity:
    def __init__(self, root, config):
        self.root, self.config = Path(root).resolve(strict=True), config
        self.loaded = False
        self.reference = None

    def load(self):
        if self.loaded:
            return
        c = self.config
        self.context_raw = pinned(self.root, c['contextManifest'])
        self.context_manifest = parse(self.context_raw)
        mane_raw = pinned(self.root, c['mane'])
        require(digest(mane_raw) == self.context_manifest['resources']['mane']['rawSha256'], 'MANE_MANIFEST_MISMATCH')
        with gzip.GzipFile(fileobj=io.BytesIO(mane_raw)) as stream:
            text = stream.read(16 * 1024 * 1024 + 1)
        require(len(text) <= 16 * 1024 * 1024, 'MANE_EXPANSION_LIMIT')
        self.mane = list(csv.DictReader(io.StringIO(text.decode('utf-8-sig')), delimiter='\t'))
        self.gencode_raw = pinned(self.root, c['gencodeManifest'])
        self.gencode = parse(self.gencode_raw)
        require(self.gencode['schemaVersion'] == 'gencode-transcript-index-v1' and self.gencode['assembly'] == 'GRCh38', 'GENCODE_SCHEMA')
        index = pinned(self.root, c['gencodeIndex'])
        require(digest(index) == self.gencode['index']['sha256'], 'GENCODE_INDEX_MANIFEST_MISMATCH')
        self.index = {}
        for line in index.decode('utf-8').splitlines():
            tx, offset, size, pin = line.split('\t')
            require(tx not in self.index and offset.isdigit() and size.isdigit() and 0 < int(size) <= 1048576
                    and re.fullmatch('[a-f0-9]{64}', pin), 'GENCODE_INDEX_INVALID')
            self.index[tx] = (int(offset), int(size), pin)
        self.data_path = local_file(self.root, c['gencodeData'])
        require(self.data_path.stat().st_size == self.gencode['data']['bytes'], 'GENCODE_DATA_SIZE_MISMATCH')
        self.data_stamp = self.stamp(self.data_path)
        self.loaded = True

    @staticmethod
    def stamp(path):
        s = path.stat()
        return (s.st_size, s.st_mtime_ns, s.st_dev, s.st_ino)

    def reference_reader(self):
        if self.reference is None:
            c = self.config['reference']; raw = pinned(self.root, c['receipt'], 1048576); envelope = parse(raw)
            resource_root = (self.root / c['resourceRoot']).resolve(strict=True)
            require(resource_root.is_relative_to(self.root / 'local'), 'REFERENCE_ROOT_OUTSIDE_LOCAL')
            self.reference = BoundedReference(resource_root, local_file(self.root, c['receipt']['path']),
                c['receipt']['sha256'], envelope['source'], envelope['version'], 'reference', c['sha256'],
                local_file(self.root, c['aliases']['path']), c['aliases']['sha256'], {},
                {'cumulativeLeftExtensions': [0], 'initialRightPaddingBases': 0,
                 'maximumWindowBases': 1, 'maximumWindowReads': 512, 'maximumTotalFastaBases': 512})
        return self.reference

    def base(self, contig, position):
        reader = self.reference_reader()
        key = str(contig) + ':' + str(position)
        reader.windows[key] = {'contig': str(contig), 'start': position, 'end': position}
        sequence, _ = reader.read(key, 0, position)
        require(sequence in 'ACGT' and len(sequence) == 1, 'REFERENCE_BASE_UNRESOLVED')
        return sequence

    def annotation(self, tx):
        require(tx in self.index, 'EXACT_GENCODE_TRANSCRIPT_UNAVAILABLE')
        offset, size, pin = self.index[tx]
        require(self.stamp(self.data_path) == self.data_stamp, 'GENCODE_CHANGED')
        require(offset + size <= self.data_stamp[0], 'GENCODE_RECORD_BOUNDS')
        with self.data_path.open('rb') as stream:
            stream.seek(offset); raw = stream.read(size)
        require(len(raw) == size and digest(raw) == pin and self.stamp(self.data_path) == self.data_stamp, 'GENCODE_RECORD_PIN_MISMATCH')
        item = parse(raw)
        # rawGtfSha256 hashes this transcript's GTF block, not the entire GTF.
        # The pinned index already binds the complete annotation record bytes.
        require(item['transcript'] == tx and item['assembly'] == 'GRCh38'
                and re.fullmatch('[a-f0-9]{64}', item['rawGtfSha256']), 'GENCODE_RECORD_IDENTITY_MISMATCH')
        return item, pin

    def resolve(self, doc, pmid):
        gene, change, aa, tx = identifiers(doc)
        match = re.fullmatch(r'c\.([0-9]+)([ACGT])>([ACGT])', change)
        require(match and match[2] != match[3], 'CODING_SNV_REQUIRED')
        if tx is None:
            return self.resolve_paired_alias(gene,change,aa,pmid)
        return self.resolve_coding(gene, change, tx, aa)

    def resolve_paired_alias(self,gene,change,aa,pmid):
        """Reuse the paired coding/protein + exact paper ClinVar mapping.

        Caller must establish both representations in the same reported allele.
        Protein alone, MANE preference and unrelated PMID records are not routes.
        """
        match=re.fullmatch(r'c\.([0-9]+)([ACGT])>([ACGT])',change)
        require(match and match[2]!=match[3], 'CODING_SNV_REQUIRED')
        fields = [{'field': k, 'findingState': 'REPORTED', 'sourceValue': v} for k, v in
                  [('geneSymbol', gene), ('variantText', change), ('proteinChange', aa)]]
        records = [read_record(pinned(self.root, s, 4 * 1024 * 1024), s['sha256'], s['accession'])
                   for s in self.config.get('clinvar', [])]
        alias = resolve({'fields': fields}, pmid, records)
        require(alias['status'] == 'ALIAS_RESOLVED_REFERENCE_CHECK_PENDING', 'EXACT_TRANSCRIPT_UNAVAILABLE')
        allele = alias['allele']
        require(len(allele['reference']) == len(allele['alternate']) == 1, 'ALIAS_SNV_REQUIRED')
        require(self.base(allele['contig'], allele['position']) == allele['reference'], 'ALIAS_REFERENCE_MISMATCH')
        return {'status': 'LOCAL_IDENTITY_VERIFIED', 'allele': allele, 'hgncId': alias['hgncId'],
                'geneSymbol': gene, 'alias': alias, 'referenceIdentity': self.reference.identity,
                'referenceReads': list(self.reference.observations),
                'scope': 'EXISTING_PUBMED_PAIRED_CLINVAR_ALIAS_AND_CURRENT_REF_NOT_NEW_TRANSCRIPT_INFERENCE'}

    def resolve_coding_deletion(self, gene, change, tx, genomic_start):
        """Map a short, contiguous coding deletion using a paired GRCh38 position."""
        match = re.fullmatch(r'c\.([1-9][0-9]*)(?:_([1-9][0-9]*))?del', change)
        require(match and type(genomic_start) is int and genomic_start > 1, 'CODING_DELETION_PAIR_REQUIRED')
        first, last = int(match[1]), int(match[2] or match[1])
        require(0 <= last - first < 50, 'CODING_DELETION_LENGTH_UNSUPPORTED')
        self.load()
        rows = [row for row in self.mane if row['symbol'] == gene and (tx is None or row['RefSeq_nuc'] == tx)]
        require(len(rows) == 1, 'CODING_DELETION_TRANSCRIPT_NOT_UNIQUE')
        row = rows[0]
        a, record_pin = self.annotation(row['Ensembl_nuc'])
        require(a['hgncId'] == row['HGNC_ID'] and a['geneSymbol'] == gene and a['protein'] == row['Ensembl_prot']
                and row['chr_strand'] in ('+', '-') and type(a['strand']) is int
                and a['strand'] == (1 if row['chr_strand'] == '+' else -1), 'TRANSCRIPT_MAPPING_CONFLICT')
        exons = [exon for exon in a['exons'] if exon['cdsStart'] <= first <= last <= exon['cdsEnd']]
        require(len(exons) == 1 and last <= a['cdsLength'], 'CODING_DELETION_NOT_WITHIN_ONE_EXON')
        exon = exons[0]
        start = exon['genomicStart'] + first - exon['cdsStart'] if a['strand'] == 1 else (
            exon['genomicEnd'] - last + exon['cdsStart'])
        end = start + last - first
        require(exon['genomicStart'] <= start <= end <= exon['genomicEnd'] and start == genomic_start,
                'CODING_DELETION_REPORTED_POSITION_MISMATCH')
        contig = a['contig']
        deleted = ''.join(self.base(contig, p) for p in range(start, end + 1))
        for _ in range(100):
            anchor = self.base(contig, start - 1)
            if anchor != deleted[-1]:
                break
            start -= 1
            require(start > 1, 'CODING_DELETION_ANCHOR_UNAVAILABLE')
            deleted = anchor + deleted[:-1]
        else:
            raise ValueError('DENOVO_IDENTITY_CODING_DELETION_NORMALIZATION_LIMIT')
        self.reference.recheck()
        return {'status': 'LOCAL_IDENTITY_VERIFIED', 'geneSymbol': gene, 'transcript': row['RefSeq_nuc'],
                'transcriptBasis': 'REPORTED_EXACT_TRANSCRIPT' if tx else 'UNIQUE_MANE_AND_REPORTED_GRCH38_PAIR',
                'coding': change, 'allele': {'assembly': 'GRCh38', 'contig': contig.removeprefix('chr'),
                    'position': start - 1, 'reference': anchor + deleted, 'alternate': anchor},
                'maneMapping': row, 'gencodeRecordSha256': record_pin, 'referenceIdentity': self.reference.identity,
                'scope': 'EXACT_PAIRED_CODING_DELETION_AND_CURRENT_REFERENCE'}

    def resolve_coding_snv_at_position(self, gene, change, genomic_position):
        """Confirm a published coding/genomic pair using the pinned mapping."""
        self.load()
        transcripts = {row['RefSeq_nuc'] for row in self.mane if row['symbol'] == gene}
        require(len(transcripts) == 1, 'CODING_SNV_TRANSCRIPT_NOT_UNIQUE')
        result = self.resolve_coding(gene, change, next(iter(transcripts)))
        require(result['allele']['position'] == genomic_position, 'CODING_SNV_GENOMIC_POSITION_MISMATCH')
        result['scope'] = 'GRCH38_PUBLISHED_CODING_GENOMIC_PAIR_AND_PINNED_TRANSCRIPT_REFERENCE'
        return result

    def resolve_coding(self, gene, change, tx, expected_protein=None):
        """Exact transcript/coding identity need not have a reported protein alias.

        Missing transcript is not replaced by MANE preference. The existing
        codon/reference checks derive the consequence; an explicitly supplied
        protein is still checked for contradiction.
        """
        require(isinstance(gene, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}', gene)
                and isinstance(tx, str) and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', tx), 'EXACT_TRANSCRIPT_REQUIRED')
        match = re.fullmatch(r'c\.([0-9]+)([ACGT])>([ACGT])', change)
        require(match and match[2] != match[3], 'CODING_SNV_REQUIRED')
        aa = expected_protein
        self.load()
        rows = [r for r in self.mane if r['RefSeq_nuc'] == tx and r['symbol'] == gene]
        if not rows:
            cached = [s for s in self.config.get('refseqMappings', []) if s['transcript'] == tx and s['coding'] == change]
            require(len(cached) <= 1, 'REFSEQ_CACHE_AMBIGUOUS')
            if cached and aa is not None:
                from refseq_identity import resolve as resolve_refseq
                return resolve_refseq(self, gene, change, aa, tx, cached[0])
        require(bool(rows), 'EXACT_MANE_MAPPING_UNAVAILABLE')
        require(len(rows) == 1, 'EXACT_MANE_MAPPING_AMBIGUOUS')
        row = rows[0]; a, record_pin = self.annotation(row['Ensembl_nuc'])
        require(a['hgncId'] == row['HGNC_ID'] and a['geneSymbol'] == gene and a['protein'] == row['Ensembl_prot']
                and row['chr_strand'] in ('+', '-') and type(a['strand']) is int
                and a['strand'] == (1 if row['chr_strand'] == '+' else -1), 'TRANSCRIPT_MAPPING_CONFLICT')
        position = int(match[1]); require(1 <= position <= a['cdsLength'], 'CODING_POSITION_OUTSIDE_CDS')

        def genomic(cpos):
            exons = [e for e in a['exons'] if e['cdsStart'] <= cpos <= e['cdsEnd']]
            require(len(exons) == 1, 'CODING_POSITION_MAPPING_AMBIGUOUS')
            e = exons[0]
            p = e['genomicStart'] + cpos - e['cdsStart'] if a['strand'] == 1 else e['genomicEnd'] - cpos + e['cdsStart']
            require(e['genomicStart'] <= p <= e['genomicEnd'], 'EXON_MAPPING_INVALID')
            return p

        codon_start = (position - 1) // 3 * 3 + 1
        require(codon_start + 2 <= a['cdsLength'], 'COMPLETE_CODON_REQUIRED')
        bases = [self.base(a['contig'], genomic(p)) for p in range(codon_start, codon_start + 3)]
        if a['strand'] == -1:
            bases = [reverse_complement(b) for b in bases]
        require(bases[(position - 1) % 3] == match[2], 'CODING_REFERENCE_MISMATCH')
        wt = ''.join(bases); bases[(position - 1) % 3] = match[3]; alt_codon = ''.join(bases)
        expected = CODONS[wt] + str((position - 1) // 3 + 1) + CODONS[alt_codon]
        require(aa is None or expected == aa, 'PROTEIN_CONSEQUENCE_MISMATCH')
        allele = {'assembly': 'GRCh38', 'contig': a['contig'].removeprefix('chr'), 'position': genomic(position),
                  'reference': match[2] if a['strand'] == 1 else reverse_complement(match[2]),
                  'alternate': match[3] if a['strand'] == 1 else reverse_complement(match[3])}
        self.reference.recheck()
        return {'status': 'LOCAL_IDENTITY_VERIFIED', 'allele': allele, 'hgncId': a['hgncId'], 'geneSymbol': gene,
                'transcript': tx, 'coding': change, 'proteinChange': expected, 'maneMapping': row,
                'gencodeRecord': a, 'gencodeRecordSha256': record_pin, 'codon': wt, 'alternateCodon': alt_codon,
                'referenceIdentity': self.reference.identity, 'referenceReads': list(self.reference.observations),
                'alias': None, 'resourcePins': {k: v for k, v in self.config.items() if k != 'clinvar'},
                'scope': 'GRCh38_EXACT_TRANSCRIPT_CODING_SNV_PUBLIC_RESEARCH_NOT_CLINICAL_APPROVAL'}
