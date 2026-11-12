"""Replay native start-codon SNVs against exact transcript structure and reference.

Technical facts only: a downstream in-frame ATG is a candidate, never proof of
translation rescue. No NMD prerequisites and no PVS1 scoring.
"""
import re
from assemble_materials import content_hash
from build_transcript_model import CODONS, reverse_complement


def require(ok, reason):
    if not ok:
        raise ValueError('START_LOSS_' + reason)


def positive(value):
    return type(value) is int and value > 0


def validate_model(model, context):
    bg, allele = context['background'], context['allele']
    require(all(model[k] == value for k, value in {
        'assembly': allele['assembly'], 'contig': allele['contig'],
        'hgncId': bg['hgncId'], 'transcript': bg['transcript']}.items()), 'MODEL_IDENTITY_MISMATCH')
    require(model['assembly'] == 'GRCh38' and re.fullmatch(r'[1-9]|1[0-9]|2[0-2]|X|Y', model['contig']),
            'ASSEMBLY_OR_CONTIG_UNSUPPORTED')
    require(type(model['strand']) is int and model['strand'] in {-1, 1}, 'STRAND_INVALID')
    length, exons = model['cdsLength'], model['exons']
    require(positive(length) and 6 <= length <= 1_000_000 and length % 3 == 0
            and isinstance(exons, list) and 1 <= len(exons) <= 1000, 'MODEL_SIZE_INVALID')
    previous, cursor = None, 1
    for ordinal, exon in enumerate(exons, 1):
        a, b, c, d = (exon[k] for k in ('genomicStart', 'genomicEnd', 'cdsStart', 'cdsEnd'))
        require(type(exon['ordinal']) is int and exon['ordinal'] == ordinal
                and positive(a) and positive(b) and a <= b
                and type(c) is int and type(d) is int and d-c == b-a, 'EXON_MAPPING_INVALID')
        if previous:
            require(c == previous['cdsEnd'] + 1 and (a > previous['genomicEnd'] if model['strand'] == 1
                                                    else b < previous['genomicStart']), 'EXON_ORDER_OR_OVERLAP_INVALID')
        low, high = max(1, c), min(length, d)
        if low <= high:
            require(low == cursor, 'CDS_MAPPING_INCOMPLETE')
            cursor = high + 1
        previous = exon
    require(cursor == length + 1, 'CDS_MAPPING_INCOMPLETE')


def genomic_position(model, cds_position):
    require(positive(cds_position) and cds_position <= model['cdsLength'], 'CDS_POSITION_INVALID')
    matches = [e for e in model['exons'] if e['cdsStart'] <= cds_position <= e['cdsEnd']]
    require(len(matches) == 1, 'CDS_MAPPING_AMBIGUOUS')
    exon = matches[0]
    return (exon['genomicStart'] + cds_position-exon['cdsStart'] if model['strand'] == 1
            else exon['genomicEnd'] - cds_position+exon['cdsStart'])


def cds_position(model, genomic):
    matches = [e for e in model['exons'] if e['genomicStart'] <= genomic <= e['genomicEnd']]
    require(len(matches) <= 1, 'GENOMIC_MAPPING_AMBIGUOUS')
    if not matches:
        return None
    exon = matches[0]
    value = exon['cdsStart'] + (genomic-exon['genomicStart'] if model['strand'] == 1 else exon['genomicEnd']-genomic)
    return value if 1 <= value <= model['cdsLength'] else None


def coding_intervals(model, low=1, high=None):
    high = model['cdsLength'] if high is None else high
    require(positive(low) and positive(high) and low <= high <= model['cdsLength'], 'CDS_INTERVAL_INVALID')
    result = []
    for exon in model['exons']:
        a, b = max(low, exon['cdsStart']), min(high, exon['cdsEnd'])
        if a <= b:
            start, end = sorted((genomic_position(model, a), genomic_position(model, b)))
            result.append({'contig': model['contig'], 'start': start, 'end': end,
                           'cdsStart': a, 'cdsEnd': b})
    return result


def reconstruct(model, context, read_region):
    validate_model(model, context)
    parts, reads = [], []
    for interval in coding_intervals(model):
        raw = read_region(interval['contig'], interval['start'], interval['end'])
        require(isinstance(raw, str) and len(raw) == interval['end'] - interval['start'] + 1
                and re.fullmatch('[ACGT]+', raw), 'REFERENCE_SEQUENCE_INCOMPLETE')
        parts.append(raw if model['strand'] == 1 else reverse_complement(raw))
        reads.append(dict(interval, sequenceSha256=content_hash(raw)))
    cds = ''.join(parts)
    require(len(cds) == model['cdsLength'], 'CDS_LENGTH_MISMATCH')
    require(cds.startswith('ATG'), 'REFERENCE_START_NOT_ATG')
    require(CODONS[cds[-3:]] == '*' and all(CODONS[cds[i:i+3]] != '*' for i in range(0, len(cds)-3, 3)),
            'REFERENCE_TRANSLATION_INVALID')
    require('cdsSequence' not in model or model['cdsSequence'] == cds, 'MODEL_REFERENCE_SEQUENCE_MISMATCH')
    return cds, reads


def resolve(context, model, read_region, annotation=None):
    out = {'schemaVersion': 'pvs1-start-loss-sequence-preparation-v1', 'status': 'UNRESOLVED',
           'scientificEvaluationPerformed': False, 'contextSha256': content_hash(context),
           'modelSha256': content_hash(model), 'issues': []}
    try:
        allele = context['allele']
        require(positive(allele['position']), 'POSITION_INVALID')
        require(all(isinstance(allele[k], str) and re.fullmatch('[ACGT]', allele[k]) for k in ('reference', 'alternate'))
                and allele['reference'] != allele['alternate'], 'SNV_REQUIRED')
        cds, reads = reconstruct(model, context, read_region)
        pos = cds_position(model, allele['position'])
        out.update(referenceCds=cds, referenceReads=reads,
                   startGenomicPositions=[genomic_position(model, i) for i in (1, 2, 3)])
        if pos not in {1, 2, 3}:
            return dict(out, status='NOT_APPLICABLE', issues=['START_LOSS_TARGET_OUTSIDE_START_CODON'])
        ref, alt = allele['reference'], allele['alternate']
        if model['strand'] == -1:
            ref, alt = reverse_complement(ref), reverse_complement(alt)
        require(cds[pos-1] == ref, 'REFERENCE_ALLELE_MISMATCH')
        if annotation is not None:
            expected = f"{model['transcript']}:c.{pos}{ref}>{alt}"
            require(annotation['transcript'] == model['transcript'] and annotation['hgvsc'] == expected,
                    'ANNOTATION_IDENTITY_CONFLICT')
            terms = set(re.split('[&,|]', annotation['consequence']))
            require('start_lost' in terms and not terms.intersection({'stop_gained', 'frameshift_variant', 'synonymous_variant'}),
                    'ANNOTATION_CONSEQUENCE_CONFLICT')
        mutant = cds[:pos-1] + alt + cds[pos:]
        next_start = next((i+1 for i in range(3, len(cds)-3, 3) if mutant[i:i+3] == 'ATG'), None)
        out.update(status='AVAILABLE', strand=model['strand'], cdsPosition=pos, referenceCodon='ATG',
                   alternateCodon=mutant[:3], mutantCds=mutant, candidateRestartCdsPosition=next_start,
                   candidateRestartProteinPosition=((next_start-1)//3+1) if next_start else None,
                   annotationAgreement='AGREES' if annotation is not None else 'NOT_PROVIDED',
                   restartInterpretation='POTENTIAL_IN_FRAME_ATG_NOT_OBSERVED_TRANSLATION',
                   upstreamIntervals=coding_intervals(model, 1, next_start-1) if next_start else [])
        if annotation is None:
            out['issues'].append('START_LOSS_ANNOTATION_NOT_PROVIDED')
    except (ValueError, KeyError, TypeError, IndexError, OSError) as error:
        code = str(error)
        out['issues'] = [code if re.fullmatch('START_LOSS_[A-Z_]+', code) else 'START_LOSS_INPUT_INVALID']
        out['status'] = 'UNSUPPORTED' if out['issues'][0] in {
            'START_LOSS_SNV_REQUIRED', 'START_LOSS_ASSEMBLY_OR_CONTIG_UNSUPPORTED', 'START_LOSS_REFERENCE_START_NOT_ATG'} else 'CONFLICT'
        if isinstance(error, OSError):
            out['status'] = 'UNRESOLVED'
            out['issues'] = ['START_LOSS_REFERENCE_SOURCE_UNAVAILABLE']
    return out
