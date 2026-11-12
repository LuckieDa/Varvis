"""Resolve PM3 source-verified transcript representations using local resources.

No requirement for a reported protein change or for the partner to occur in the
current patient's ranking. No transcript default, protein-only equality or remote
request. Unsupported HGVS forms remain explicit; they never query an old key.
"""
from copy import deepcopy
import re
from core_local_identity import reverse_complement
from pm3_observations import collect
from model_literature_contract import canonical, digest

INTRONIC_SNV = re.compile(r'c\.([1-9][0-9]{0,8})([+-])([1-9][0-9]{0,8})([ACGT])>([ACGT])')


def representation(value, transcripts):
    if ':' in value:
        tx, change = value.split(':',1)
        if transcripts and set(transcripts) != {tx}: raise ValueError('PM3_TRANSCRIPT_CONFLICT')
    else:
        if len(set(transcripts)) != 1: raise ValueError('PM3_EXACT_TRANSCRIPT_MISSING_OR_AMBIGUOUS')
        tx, change = transcripts[0], value
    if not re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*',tx): raise ValueError('PM3_EXACT_TRANSCRIPT_REQUIRED')
    return tx, change


def resolve_intronic_snv(resolver, gene, tx, match, reported_protein):
    cds_position, sign, offset, ref, alt = match.groups()
    if reported_protein is not None or ref == alt:
        raise ValueError('PM3_INTRONIC_SNV_REPRESENTATION_CONFLICT')
    resolver.load()
    rows = [row for row in resolver.mane if row['RefSeq_nuc'] == tx and row['symbol'] == gene]
    if len(rows) != 1:
        raise ValueError('PM3_INTRONIC_EXACT_MANE_MAPPING_REQUIRED')
    row = rows[0]
    annotation, pin = resolver.annotation(row['Ensembl_nuc'])
    strand = annotation['strand']
    if (annotation['hgncId'] != row['HGNC_ID'] or annotation['geneSymbol'] != gene
            or annotation['protein'] != row['Ensembl_prot'] or type(strand) is not int
            or row['chr_strand'] not in ('+', '-') or strand != (1 if row['chr_strand'] == '+' else -1)):
        raise ValueError('PM3_INTRONIC_TRANSCRIPT_MAPPING_CONFLICT')

    exons = sorted(annotation['exons'], key=lambda exon: exon['cdsStart'])
    anchor = int(cds_position)
    upstream = sign == '+'
    boundaries = [(left, right) for left, right in zip(exons, exons[1:])
                  if left['cdsEnd'] + 1 == right['cdsStart']
                  and anchor == (left['cdsEnd'] if upstream else right['cdsStart'])]
    if len(boundaries) != 1:
        raise ValueError('PM3_INTRONIC_BOUNDARY_UNRESOLVED')
    left, right = boundaries[0]
    left_edge = left['genomicEnd' if strand == 1 else 'genomicStart']
    right_edge = right['genomicStart' if strand == 1 else 'genomicEnd']
    intron_length = (right_edge - left_edge) * strand - 1
    distance = int(offset)
    if distance > (intron_length + int(upstream)) // 2:
        raise ValueError('PM3_INTRONIC_OFFSET_OUT_OF_RANGE')
    position = left_edge + strand * distance if upstream else right_edge - strand * distance
    genomic_ref, genomic_alt = (ref, alt) if strand == 1 else (reverse_complement(ref), reverse_complement(alt))
    if resolver.base(annotation['contig'], position) != genomic_ref:
        raise ValueError('PM3_INTRONIC_REFERENCE_MISMATCH')
    resolver.reference.recheck()
    return {'status': 'LOCAL_IDENTITY_VERIFIED', 'geneSymbol': gene, 'hgncId': annotation['hgncId'],
            'transcript': tx, 'coding': match.group(), 'maneMapping': row, 'gencodeRecord': annotation,
            'gencodeRecordSha256': pin, 'referenceIdentity': resolver.reference.identity,
            'referenceReads': list(resolver.reference.observations),
            'allele': {'assembly': 'GRCh38', 'contig': annotation['contig'].removeprefix('chr'),
                       'position': position, 'reference': genomic_ref, 'alternate': genomic_alt},
            'scope': 'GRCH38_EXACT_TRANSCRIPT_INTRONIC_SNV'}


def resolve_case(case, resolver, selected_allele=None):
    result=[]
    for observation in case['semantics']['observations']:
        row={'reportedObservation':deepcopy(observation),'caseLabel':case['caseLabel'],
             'identities':{},'issues':[],'businessPublishable':False,
             'selectedTargetBinding':'NOT_REQUESTED' if selected_allele is None else 'PENDING'}
        for role,key in [('target','targetRepresentation'),('partner','partnerRepresentation')]:
            if observation[key] is None: continue
            try:
                aa=observation.get('reportedProteins',{}).get(role)
                from pm3_source_aliases import matching_coding
                source_coding = matching_coding(case, observation['geneSymbol'], observation[key], aa) \
                    if ':' not in observation[key] and not observation['reportedTranscripts'] and observation[key].startswith('c.') else None
                if observation[key].startswith('p.'):
                    from pm3_source_aliases import matching
                    from literature_allele_identity import protein
                    tx,change,proof=matching(case,observation['geneSymbol'],observation[key],observation['reportedTranscripts'])
                    if tx is None:
                        # Both DNA and protein must already be linked in the paper.
                        # A database citation alone never resolves protein-only data.
                        resolved=resolver.resolve_paired_alias(observation['geneSymbol'],change,protein(observation[key]),case.get('paperPmid'))
                        checks=[]
                        for match in resolved['alias']['matches']:
                            for pair in match['pairedHgvs']:
                                checked=resolver.resolve_coding(observation['geneSymbol'],change,pair['transcript'],protein(observation[key]))
                                if checked['allele']!=resolved['allele'] or checked['hgncId']!=resolved['hgncId']:
                                    raise ValueError('PM3_PROTEIN_RESOURCE_IDENTITY_CONFLICT')
                                checks.append(checked)
                        if not checks:raise ValueError('PM3_PROTEIN_TRANSCRIPT_RESOURCE_NOT_FOUND')
                        if len({c['transcript'] for c in checks})!=1:
                            raise ValueError('PM3_PROTEIN_TRANSCRIPT_MAPPING_MISSING_OR_AMBIGUOUS')
                        alias=resolved['alias']
                        resolved=checks[0]
                        resolved['alias']=alias
                        resolved['sourceTranscriptLink']={'method':'SAME_PAPER_DNA_PROTEIN_PAIRED_VCV_AND_SEQUENCE',
                            'transcript':resolved['transcript'],'clinvarMatches':alias['matches']}
                    else:
                        resolved=resolver.resolve_coding(observation['geneSymbol'],change,tx,protein(observation[key]))
                    resolved['sourceProteinCrosswalk']=proof
                elif source_coding:
                    tx, source_protein, proof = source_coding
                    resolved = resolver.resolve_coding(observation['geneSymbol'], observation[key], tx, source_protein)
                    resolved['sourceProteinCrosswalk'] = proof
                    resolved['sourceTranscriptLink'] = {'method':'EXPLICIT_SAME_PAPER_CODING_PROTEIN_TABLE', 'transcript':tx}
                elif ':' not in observation[key] and not observation['reportedTranscripts'] and aa:
                    resolved=resolver.resolve_paired_alias(observation['geneSymbol'],observation[key],aa,case.get('paperPmid'))
                else:
                    tx, change = representation(observation[key],observation['reportedTranscripts'])
                    intronic = INTRONIC_SNV.fullmatch(change)
                    if intronic:
                        resolved = resolve_intronic_snv(resolver, observation['geneSymbol'], tx, intronic, aa)
                    elif re.fullmatch(r'c\.[1-9][0-9]*(?:_[1-9][0-9]*)?del', change):
                        from refseq_identity import resolve as resolve_refseq
                        pins = [pin for pin in resolver.config.get('refseqMappings', [])
                                if pin['transcript'] == tx and pin['coding'] == change]
                        if len(pins) != 1:
                            raise ValueError('PM3_EXACT_CODING_DELETION_MAPPING_UNAVAILABLE')
                        resolved = resolve_refseq(resolver, observation['geneSymbol'], change, aa, tx, pins[0])
                    else:
                        resolved=resolver.resolve_coding(observation['geneSymbol'],change,tx,aa) if aa else resolver.resolve_coding(observation['geneSymbol'],change,tx)
                if role=='target' and selected_allele is not None and resolved['allele']!=selected_allele:
                    raise ValueError('PM3_SELECTED_TARGET_IDENTITY_MISMATCH')
                row['identities'][role]=resolved
                if role=='target' and selected_allele is not None: row['selectedTargetBinding']='MATCHED'
            except (ValueError,OSError) as error:
                code=str(error)
                if not re.fullmatch(r'[A-Z][A-Z0-9_]{1,150}',code): code='PM3_LOCAL_IDENTITY_IO_FAILURE'
                issue={'role':role,'reasonCode':code}
                if observation[key].startswith('p.') and code in (
                        'PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_MISSING',
                        'PM3_PROTEIN_SOURCE_CODING_LINK_UNRESOLVED',
                        'PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_AMBIGUOUS',
                        'PM3_PROTEIN_SOURCE_TABLE_UNRESOLVED'):
                    from pm3_protein_candidates import candidates
                    issue['candidateSearch']=candidates(resolver,observation['geneSymbol'],observation[key],
                                                        observation['reportedTranscripts'])
                row['issues'].append(issue)
        if len(row['identities'])==(1 if observation['partnerRepresentation'] is None else 2):
            alleles={role:value['allele'] for role,value in row['identities'].items()}
            if 'partner' in alleles and alleles['target']==alleles['partner']:
                row['issues'].append({'role':'pair','reasonCode':'PM3_SAME_ALLELE_IS_NOT_DISTINCT_PARTNER'})
            else:
                row['alleleIds']={role:'allele-sha256-'+digest(canonical(value)) for role,value in alleles.items()}
        row['status']='LOCAL_IDENTITIES_VERIFIED' if not row['issues'] else 'IDENTITY_INCOMPLETE'
        result.append(row)
    return result


def resolve(library, artifacts, resolver, selected_allele=None):
    result=collect(library,artifacts)
    for case in result['cases']:
        case['resolvedObservations']=resolve_case(case,resolver,selected_allele)
    result['identityScope']='SOURCE_VERIFIED_EXACT_TRANSCRIPT_CODING_OR_INTRONIC_SNV_OR_EXPLICIT_PAPER_CROSSWALK_LOCAL_GRCH38'
    return result
