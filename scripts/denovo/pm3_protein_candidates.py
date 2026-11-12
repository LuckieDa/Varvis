"""Bounded local protein-to-coding candidate supply, never proof of a paper allele.

Search exact reported transcripts or the pinned MANE gene rows. Enumerate all
codon substitutions, including multinucleotide alternatives, before reporting
SNVs verified by the existing identity reader. MANE is a search scope, not an
assumed transcript used by the publication. No ranking, network or scoring.
"""
from copy import deepcopy
import re
from core_local_identity import CODONS, reverse_complement
from literature_allele_identity import protein

VERSION = 'pm3-protein-candidates-v1'


def require(ok, code):
    if not ok: raise ValueError('PM3_PROTEIN_' + code)


def candidates(resolver, gene, value, transcripts):
    result = {'schemaVersion': VERSION, 'geneSymbol': gene, 'reportedProtein': value,
              'status': 'INCOMPLETE', 'candidates': [], 'issues': [],
              'searchScope': 'REPORTED_EXACT_TRANSCRIPTS' if transcripts else 'LOCAL_MANE_GENE_TRANSCRIPTS_ONLY',
              'scopeComplete': False, 'paperAlleleEstablished': False, 'businessPublishable': False}
    try:
        require(isinstance(gene, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}', gene), 'GENE_INVALID')
        require(isinstance(value, str), 'SUBSTITUTION_REQUIRED')
        aa = protein(value)
        match = re.fullmatch(r'([ACDEFGHIKLMNPQRSTVWY])([1-9][0-9]{0,6})([ACDEFGHIKLMNPQRSTVWY])', aa)
        require(match and match[1] != match[3], 'SUBSTITUTION_REQUIRED')
        require(isinstance(transcripts, list) and len(transcripts) <= 8 and
                all(isinstance(t, str) and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', t) for t in transcripts),
                'TRANSCRIPT_SCOPE_INVALID')
        resolver.load()
        rows = [r for r in resolver.mane if r['symbol'] == gene and (not transcripts or r['RefSeq_nuc'] in transcripts)]
        txs = sorted(set(transcripts) if transcripts else {r['RefSeq_nuc'] for r in rows})
        require(bool(txs), 'TRANSCRIPT_RESOURCE_NOT_FOUND')
        require(len(txs) <= 8, 'TRANSCRIPT_SEARCH_LIMIT')
        result['searchedTranscripts'] = txs
        result['resourcePins'] = {k: resolver.config[k]['sha256'] for k in
                                  ('contextManifest','mane','gencodeManifest','gencodeIndex')}
        for tx in txs:
            tx_rows = [r for r in rows if r['RefSeq_nuc'] == tx]
            require(len(tx_rows) == 1, 'TRANSCRIPT_MAPPING_MISSING_OR_AMBIGUOUS')
            row = tx_rows[0]; annotation, pin = resolver.annotation(row['Ensembl_nuc'])
            require(annotation['geneSymbol'] == gene and annotation['hgncId'] == row['HGNC_ID']
                    and annotation['protein'] == row['Ensembl_prot'] and row['chr_strand'] in ('+','-')
                    and type(annotation['strand']) is int
                    and annotation['strand'] == (1 if row['chr_strand'] == '+' else -1), 'TRANSCRIPT_MAPPING_CONFLICT')
            start = (int(match[2])-1)*3+1
            require(1 <= start and start+2 <= annotation['cdsLength'], 'POSITION_OUTSIDE_CDS')
            coords=[]; bases=[]
            for cpos in range(start,start+3):
                exons=[e for e in annotation['exons'] if e['cdsStart'] <= cpos <= e['cdsEnd']]
                require(len(exons)==1, 'EXON_MAPPING_AMBIGUOUS')
                exon=exons[0]
                pos=(exon['genomicStart']+cpos-exon['cdsStart'] if annotation['strand']==1
                     else exon['genomicEnd']-cpos+exon['cdsStart'])
                require(exon['genomicStart'] <= pos <= exon['genomicEnd'], 'EXON_MAPPING_INVALID')
                base=resolver.base(annotation['contig'],pos)
                coords.append(pos);bases.append(base if annotation['strand']==1 else reverse_complement(base))
            codon=''.join(bases)
            require(CODONS.get(codon)==match[1], 'REFERENCE_AMINO_ACID_MISMATCH')
            for alt_codon, amino in sorted(CODONS.items()):
                if amino != match[3]: continue
                changes=[(i,ref,alt_codon[i]) for i,ref in enumerate(codon) if ref != alt_codon[i]]
                entry={'transcript':tx,'codon':codon,'alternateCodon':alt_codon,'proteinChange':aa,
                       'codingChanges':[f'c.{start+i}{ref}>{alt}' for i,ref,alt in changes],
                       'substitutionType':'SNV' if len(changes)==1 else 'MULTINUCLEOTIDE',
                       'gencodeRecordSha256':pin,'codonGenomicPositions':coords,
                       'referenceSequenceChecked':True,'paperAlleleEstablished':False}
                if len(changes)==1:
                    identity=resolver.resolve_coding(gene,entry['codingChanges'][0],tx,aa)
                    entry.update(allele=identity['allele'],hgncId=identity['hgncId'],
                                 status='LOCAL_SEQUENCE_VERIFIED_CANDIDATE')
                else:
                    entry['status']='MULTINUCLEOTIDE_ALTERNATIVE_NOT_RESOLVED'
                result['candidates'].append(entry)
        resolver.reference.recheck()
        result['referenceIdentity']={k:deepcopy(resolver.reference.identity[k]) for k in
                                     ('assembly','acceptedReceiptSha256','indexSha256','explicitContigAliasSha256')}
        result['scopeComplete']=True
        result['status']='CANDIDATES_ONLY'
        result['issues'].append('PM3_PROTEIN_CANDIDATES_NEED_SOURCE_DNA_LINK')
        if not transcripts: result['issues'].append('PM3_PROTEIN_PAPER_TRANSCRIPT_NOT_ESTABLISHED')
        if any(c['substitutionType']=='MULTINUCLEOTIDE' for c in result['candidates']):
            result['issues'].append('PM3_PROTEIN_MULTINUCLEOTIDE_ALTERNATIVES_EXIST')
    except (ValueError,OSError,KeyError,TypeError) as error:
        code=str(error)
        result['issues'].append(code if re.fullmatch(r'[A-Z][A-Z0-9_]{1,150}',code) else 'PM3_PROTEIN_RESOURCE_READ_FAILED')
    return result
