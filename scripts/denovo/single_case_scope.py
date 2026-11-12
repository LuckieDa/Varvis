"""One explicitly introduced clinical case, never a cohort inferred from a title.

`proband` is a source-scoped internal label, not a fabricated patient identifier.
Only the uniquely bounded Case report section owns its prose. Other sections,
literature tables, captions and unnamed neighbouring patients are not borrowed.
"""
import re

INTRO = re.compile(r'^(?:The (?:reported )?(?:patient|proband)|Our patient) (?:was|is) '
                   r'(?:a |an )?[0-9][A-Za-z0-9 .-]{0,70}\b(?:boy|girl|man|woman|male|female)\.')
OTHER = re.compile(r'(?i:\b(?:another|second|third|other|additional) (?:patient|proband|case|child)\b'
                   r'|\b(?:two|three|multiple|[2-9]) (?:patients|probands|cases|children)\b)'
                   r'|\b(?:Case|Patient|Individual|Proband) [A-Z0-9][A-Za-z0-9_.-]*\b')


def units(document, label):
    if label != 'proband':
        return []
    groups = {}
    for u in document['units']:
        if u['kind'] == 'BODY_PARAGRAPH' and u['sections'] and u['sections'][-1].lower() in {
                'case report', 'clinical report', 'case presentation'}:
            groups.setdefault(u['unitId'].rsplit('/p[', 1)[0], []).append(u)
    if len(groups) != 1:
        return []
    owned = next(iter(groups.values()))
    if (len(owned) > 32 or sum(len(u['text'].encode()) for u in owned) > 32768
            or sum(bool(INTRO.match(u['text'])) for u in owned) != 1
            or not INTRO.match(owned[0]['text'])
            or any(OTHER.search(u['text']) for u in owned)):
        return []
    return owned


def owns(document, unit, label):
    return any(u['unitId'] == unit['unitId'] for u in units(document, label))


def primary(document, unit, label):
    owned = units(document, label)
    from qualify_literature_cases import sentences
    direct_assay = any(re.match(r'^We performed (?:trio-based )?(?:whole-exome sequencing|whole-genome sequencing|Sanger sequencing)\b', sentence)
        and not re.search(r'\b(?:not|never|except|instead|another|other)\b', sentence, re.I)
        for _, _, sentence in sentences(unit['text']))
    return (owns(document, unit, label)
            and any(re.search(r'\b(?:referred to|presented to|treated at) our (?:hospital|clinic)\b', u['text']) for u in owned)
            and direct_assay)


def target_anchor(document, candidate):
    """Unique coding/protein pair across the whole owned single-case section."""
    from literature_allele_identity import one_field, coding, protein
    from qualify_literature_cases import cite
    try:
        gene = one_field(candidate, 'geneSymbol')
        change = re.sub(r'\s*>\s*', '>', one_field(candidate, 'variantText'))
        _, nt = coding(change); aa = protein(one_field(candidate, 'proteinChange'))
    except ValueError:
        return []
    owned = units(document, candidate['caseLabel']); all_changes, all_proteins, anchors = set(), set(), []
    for u in owned:
        changes = {re.sub(r'\s*>\s*', '>', c) for c in re.findall(
            r'\bc\.[0-9]+(?:_[0-9]+)?(?:[ACGT]\s*>\s*[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)', u['text'])}
        proteins = {protein(p) for p in re.findall(r'\bp\.([A-Za-z*]+[0-9]+[A-Za-z*]+)', u['text'])}
        all_changes.update(changes); all_proteins.update(proteins)
        if nt in changes and aa in proteins and re.search(r'(?<![\w-])' + re.escape(gene) + r'(?![\w-])', u['text']):
            anchors.append(cite(document, u))
    return anchors if all_changes == {nt} and all_proteins == {aa} and len(anchors) == 1 else []
