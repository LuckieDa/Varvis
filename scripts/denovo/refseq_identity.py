"""Replay pinned NCBI HGVS/SPDI and exact RefSeq records; no network or scoring.

Simple coding SNVs and short in-frame coding deletions on GRCh38 primary
chromosomes are supported. An exact versioned transcript is retained.
"""
import re
from urllib.parse import quote
from model_literature_contract import parse, digest

API = 'https://api.ncbi.nlm.nih.gov/variation/v0/'
# RefSeq primary chromosome accession versions for GRCh38 (not hg19 aliases).
CHROMOSOMES = {f'NC_{i:06d}.{v}': str(i) for i, v in enumerate(
    [11, 12, 12, 12, 10, 12, 14, 11, 12, 11, 10, 12, 11, 9, 10, 10, 11, 10, 10, 11, 9, 11], 1)}
CHROMOSOMES.update({'NC_000023.11': 'X', 'NC_000024.10': 'Y'})
DELETION = re.compile(r'c\.([1-9][0-9]*)(?:_([1-9][0-9]*))?del')


def deletion_on_reference(resolver, spdi, length):
    from core_local_identity import require
    contig = CHROMOSOMES[spdi['seq_id']]
    deleted, inserted = spdi['deleted_sequence'], spdi['inserted_sequence']
    require(type(spdi['position']) is int and spdi['position'] > 0
            and re.fullmatch(r'[ACGT]{1,100}', deleted)
            and re.fullmatch(r'[ACGT]{0,100}', inserted)
            and len(deleted) - len(inserted) == length, 'REFSEQ_GENOMIC_ALLELES_CONFLICT')
    start = spdi['position'] + 1
    require(''.join(resolver.base(contig, start + index) for index in range(len(deleted))) == deleted,
            'REFSEQ_GENOMIC_REFERENCE_MISMATCH')
    # Contextual SPDI can cover the entire repeat. Find the leftmost equivalent
    # pure deletion, then verify the VCF anchor against the same native reference.
    offsets = [i for i in range(len(inserted) + 1) if deleted[:i] + deleted[i + length:] == inserted]
    require(bool(offsets), 'REFSEQ_GENOMIC_ALLELES_CONFLICT')
    start += offsets[0]
    deleted = deleted[offsets[0]:offsets[0] + length]
    for _ in range(100):
        require(start > 1, 'REFSEQ_DELETION_ANCHOR_UNAVAILABLE')
        anchor = resolver.base(contig, start - 1)
        if anchor != deleted[-1]:
            return {'assembly': 'GRCh38', 'contig': contig, 'position': start - 1,
                    'reference': anchor + deleted, 'alternate': anchor}
        start -= 1
        deleted = anchor + deleted[:-1]
    raise ValueError('REFSEQ_DELETION_NORMALIZATION_LIMIT')


def resolve(resolver, gene, change, protein, transcript, spec):
    from core_local_identity import pinned, require, CODONS, reverse_complement
    manifest = parse(pinned(resolver.root, spec['manifest']))
    deletion = DELETION.fullmatch(change)
    schema = 'denovo-refseq-deletion-cache-v1' if deletion else 'denovo-refseq-snv-cache-v1'
    require(manifest.get('schemaVersion') == schema
            and manifest.get('transcript') == transcript and manifest.get('coding') == change,
            'REFSEQ_CACHE_IDENTITY_MISMATCH')
    originals = {}

    def read(name, url):
        source = manifest['sources'][name]
        require(source['url'] == url and source['finalUrl'] == url and source['httpStatus'] == 200,
                'REFSEQ_OFFICIAL_SOURCE_MISMATCH')
        raw = pinned(resolver.root, source, 4 * 1024 * 1024)
        originals[name] = {'sha256': digest(raw), 'url': url, 'raw': raw.decode('utf-8')}
        return raw

    hgvs = transcript + ':' + change
    contextual = parse(read('contextual', API + 'hgvs/' + quote(hgvs, safe='') + '/contextuals'))['data']
    require(contextual.get('input_hgvs_validity') == 'valid' and len(contextual['spdis']) == 1,
            'REFSEQ_HGVS_AMBIGUOUS_OR_INVALID')
    item = contextual['spdis'][0]
    match = re.fullmatch(r'c\.([1-9][0-9]*)([ACGT])>([ACGT])', change)
    require(item['seq_id'] == transcript and type(item['position']) is int and item['position'] >= 0
            and (deletion or match and item['deleted_sequence'] == match[2] and item['inserted_sequence'] == match[3]),
            'REFSEQ_CONTEXTUAL_MISMATCH')
    gb = read('refseq', 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id='
              + transcript + '&rettype=gb&retmode=text').decode('utf-8')
    require(re.findall(r'^VERSION\s+(\S+)', gb, re.M) == [transcript]
            and gb.count('\nORIGIN') == 1 and gb.count('\n//') == 1, 'REFSEQ_VERSION_MISMATCH')
    cds = re.findall(r'^     CDS\s+(\d+)\.\.(\d+)\s*\n((?:                     .*\n)+)', gb, re.M)
    require(len(cds) == 1 and len(re.findall(r'^     CDS\s', gb, re.M)) == 1, 'REFSEQ_CDS_UNSUPPORTED')
    start, end, qualifiers = cds[0]; start, end = int(start), int(end)
    require(re.findall(r'/gene="([^"]+)"', qualifiers) == [gene]
            and re.findall(r'/codon_start=(\d+)', qualifiers) == ['1']
            and '/transl_except=' not in qualifiers and '/exception=' not in qualifiers, 'REFSEQ_CDS_CONFLICT')
    hgnc = re.findall(r'/db_xref="HGNC:(HGNC:[0-9]+)"', qualifiers)
    require(len(hgnc) == 1, 'REFSEQ_HGNC_UNRESOLVED')
    sequence = re.sub(r'[\s0-9]', '', gb.split('\nORIGIN', 1)[1].split('//')[0]).upper()
    require(re.fullmatch('[ACGT]+', sequence) and 1 <= start <= end <= len(sequence),
            'REFSEQ_CDS_POSITION_MISMATCH')
    location = item['position']
    consequences = {}
    if deletion:
        first, last = int(deletion[1]), int(deletion[2] or deletion[1])
        length = last - first + 1
        require(1 <= first <= last <= end - start + 1 and 0 < length <= 48 and length % 3 == 0,
                'REFSEQ_CODING_DELETION_SCOPE')
        removed, added = item['deleted_sequence'], item['inserted_sequence']
        require(len(removed) - len(added) == length and sequence[location:location + len(removed)] == removed
                and sequence[:location] + added + sequence[location + len(removed):]
                    == sequence[:start + first - 2] + sequence[start + last - 1:], 'REFSEQ_CONTEXTUAL_MISMATCH')
        if protein:
            residue = re.fullmatch(r'([A-Z])([1-9][0-9]*)del', protein)
            require(residue and length == 3, 'REFSEQ_PROTEIN_DELETION_UNSUPPORTED')
            cds_seq = sequence[start - 1:end]
            altered = cds_seq[:first - 1] + cds_seq[last:]
            reference_aa = ''.join(CODONS[cds_seq[i:i+3]] for i in range(0, len(cds_seq), 3))
            alternate_aa = ''.join(CODONS[altered[i:i+3]] for i in range(0, len(altered), 3))
            pos = int(residue[2]) - 1
            require(reference_aa[pos:pos + 1] == residue[1]
                    and reference_aa[:pos] + reference_aa[pos+1:] == alternate_aa, 'REFSEQ_PROTEIN_CONSEQUENCE_MISMATCH')
    else:
        require(1 <= int(match[1]) <= end - start + 1 and location == start + int(match[1]) - 2,
                'REFSEQ_CDS_POSITION_MISMATCH')
        codon_start = start - 1 + (int(match[1]) - 1) // 3 * 3
        wt = sequence[codon_start:codon_start + 3]; offset = (int(match[1]) - 1) % 3
        require(len(wt) == 3 and wt[offset] == match[2], 'REFSEQ_CODING_REFERENCE_MISMATCH')
        alt = wt[:offset] + match[3] + wt[offset + 1:]
        aa_position = (int(match[1]) - 1) // 3 + 1
        require(CODONS[wt] + str(aa_position) + CODONS[alt] == protein, 'REFSEQ_PROTEIN_CONSEQUENCE_MISMATCH')
        translation = re.findall(r'/translation="([^"]+)"', qualifiers)
        require(len(translation) == 1 and re.sub(r'\s', '', translation[0])[aa_position - 1:aa_position] == CODONS[wt],
                'REFSEQ_TRANSLATION_MISMATCH')
        consequences.update(codon=wt, alternateCodon=alt)
    spdi = ':'.join(str(item[k]) for k in ('seq_id', 'position', 'deleted_sequence', 'inserted_sequence'))
    equivalents = parse(read('equivalents', API + 'spdi/' + quote(spdi, safe='') + '/all_equivalent_contextual'))['data']['spdis']
    require(type(equivalents) is list and len(equivalents) <= 2048 and item in equivalents,
            'REFSEQ_EQUIVALENCE_INPUT_MISSING')
    genomic = [r for r in equivalents if r['seq_id'] in CHROMOSOMES]
    require(len(genomic) == 1, 'REFSEQ_GRCH38_MAPPING_AMBIGUOUS_OR_MISSING')
    g = genomic[0]
    if deletion:
        allele = deletion_on_reference(resolver, g, length)
    else:
        require(type(g['position']) is int and g['position'] >= 0 and
                (g['deleted_sequence'], g['inserted_sequence']) in
                [(match[2], match[3]), (reverse_complement(match[2]), reverse_complement(match[3]))],
                'REFSEQ_GENOMIC_ALLELES_CONFLICT')
        allele = {'assembly': 'GRCh38', 'contig': CHROMOSOMES[g['seq_id']], 'position': g['position'] + 1,
                  'reference': g['deleted_sequence'], 'alternate': g['inserted_sequence']}
        require(resolver.base(allele['contig'], allele['position']) == allele['reference'], 'REFSEQ_GENOMIC_REFERENCE_MISMATCH')
    resolver.reference.recheck()
    return {'status': 'LOCAL_IDENTITY_VERIFIED', 'allele': allele, 'hgncId': hgnc[0], 'geneSymbol': gene,
            'transcript': transcript, 'coding': change, 'proteinChange': protein, **consequences,
            'mappingMethod': 'NCBI_EXACT_REFSEQ_HGVS_SPDI_EQUIVALENCE_AND_LOCAL_REF', 'genomicAccession': g['seq_id'],
            'referenceIdentity': resolver.reference.identity, 'referenceReads': list(resolver.reference.observations),
            'resourcePins': spec, 'mappingSources': originals,
            'scope': 'GRCh38_PRIMARY_CODING_DELETION_PUBLIC_RESEARCH_NOT_CLINICAL_APPROVAL' if deletion else
                     'GRCh38_PRIMARY_CODING_SNV_PUBLIC_RESEARCH_NOT_CLINICAL_APPROVAL'}
