"""Read explicit same-allele segregation in a source-owned JATS case report."""
import re
from xml.etree import ElementTree

from bundle_model_contract import cite
from literature_allele_identity import protein
from pm3_single_case import normalized, OTHER
from prepare_literature import text


METHOD = 'JATS_PROBAND_SEGREGATION_AND_CLONING_CIS'
LABEL = r'[IVX]{1,5}-[1-9][0-9]*'
ALLELE = r'[1-9][0-9]*(?:_[1-9][0-9]*)?(?:[ACGT]>[ACGT]|del)'
PROBAND = re.compile(rf'^An? [0-9]+-year-old (?:girl|boy|woman|man)\b[^.()]{{0,120}}'
                     rf'\((?P<label>{LABEL}), Figure (?P<figure>[1-9][0-9]*)\)')
CONTEXT = re.compile(r'^(?P<disease>[A-Za-z][A-Za-z -]{2,100}) \((?P<alias>[A-Z][A-Z0-9-]*); MIM#[0-9]{6}\)'
                     r' is an autosomal recessive disorder caused by mutations in (?P<gene>[A-Z][A-Z0-9-]*) '
                     r'\([^()]{1,140}; GenBank: (?P<tx>NM_[0-9]+\.[1-9][0-9]*)\)')
GENOTYPE = re.compile(rf'\brevealed two heterozygous variants: (?:the already identified )?'
                      rf'(?P<a>c\.{ALLELE}) \((?P<pa>p\.[^();]+)(?:;[^()]{{0,100}})?\) and '
                      rf'(?P<b>c\.{ALLELE}) \((?P<pb>p\.[^();]+)(?:;[^()]{{0,100}})?\)\.')
SEGREGATION = re.compile(r'^(?:However, )?[Tt]he segregation analysis revealed that the two (?:mutations|variants) '
                         r'were in cis, (?P<parent>maternally|paternally) inherited as a complex allele\. '
                         r'The data were confirmed by cloning assay \(Figure (?P<figure>[1-9][0-9]*)\) and allel(?:e|ic)-specific PCR\b')
BRACKET = re.compile(rf'c\.\[({ALLELE});\s*({ALLELE})\]', re.I)


def anchor(source, label):
    if source.get('publisherReader') or not re.fullmatch(LABEL, label):
        return None
    results = [unit for unit in source['main']['units'] if unit['kind'] == 'BODY_PARAGRAPH'
               and unit['sections'] and unit['sections'][-1].casefold() == 'results']
    introductions = [(unit, match) for unit in results if (match := PROBAND.match(normalized(unit['text'])))]
    if len(introductions) != 1 or introductions[0][1]['label'] != label:
        return None
    unit, match = introductions[0]
    prefix = unit['unitId'].rsplit('/', 1)[0] + '/'
    group = [row for row in results if row['unitId'].rsplit('/', 1)[0] + '/' == prefix]
    if group[0] != unit or any(OTHER.search(row['text']) for row in group):
        return None
    return unit, match['figure'], group


def ownership(source, label):
    found = anchor(source, label)
    if found is None:
        return {}
    first, _, group = found
    return {unit['unitId']: {'method': 'EXPLICIT_CASE_PARAGRAPH', 'container': first['unitId'],
                            'anchorUnitId': first['unitId'], 'paragraphText': unit['text'], 'caseLabel': label}
            for unit in group}


def matches_figure_link(source, unit, number):
    links = [link['rid'] for link in source['main']['links'] if link['refType'] == 'fig'
             and link['sourceUnitId'].startswith(unit['unitId'] + '/')
             and link['label'] in {number, 'Figure ' + number}]
    return links[0] if len(links) == 1 and re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,79}', links[0] or '') else None


def attach(source, case, target):
    """A same-section pair, direct molecular phase and an agreeing figure are required."""
    found = anchor(source, case['caseLabel'])
    if found is None:
        return
    first, figure, group = found
    if not any(item['unitId'] == first['unitId'] for item in case['anchors']):
        return
    origins = {item['proposal'] for item in case['originAssertions']} - {'UNCLEAR'}
    if origins != {'AUTHOR_COHORT'}:
        return
    units = source['main']['units']
    definitions = [(unit, match) for unit in units if unit['kind'] == 'BODY_PARAGRAPH'
                   and unit['sections'] and unit['sections'][-1].casefold() == 'introduction'
                   and (match := CONTEXT.match(unit['text'])) and match['gene'] == target['geneSymbol']]
    genotypes = [(unit, match) for unit in group if (match := GENOTYPE.search(unit['text']))]
    if len(definitions) != 1 or len(genotypes) != 1:
        return
    context, definition = definitions[0]
    genotype, pair = genotypes[0]
    diagnosis = re.search(r'\b[Tt]he ' + re.escape(definition['alias']) + r' diagnosis was supported by positive [A-Z][A-Z0-9]*\b',
                          genotype['text'][:pair.start()])
    selected = {pair['a'], pair['b']} & {value.split(':')[-1] for value in target['variantAliases']}
    if not diagnosis or pair['a'] == pair['b'] or len(selected) != 1:
        return
    phase_rows = [(unit, match) for unit in group if (match := SEGREGATION.match(normalized(unit['text'])))]
    if len(phase_rows) != 1:
        return
    phase_unit, segregation = phase_rows[0]
    if group.index(phase_unit) != group.index(genotype) + 1 or segregation['figure'] != figure:
        return
    figure_id = matches_figure_link(source, first, figure)
    if not figure_id or matches_figure_link(source, phase_unit, figure) != figure_id:
        return
    # The main document has already passed the bounded JATS reader. Tie the
    # visible caption to the two original figure cross-references by XML ID.
    figures = ElementTree.fromstring(source['raw']).findall(f".//fig[@id='{figure_id}']")
    if len(figures) != 1 or figures[0].find('caption') is None:
        return
    caption_text = text(figures[0].find('caption'))
    parent = 'mother' if segregation['parent'] == 'maternally' else 'father'
    prefix = first['unitId'].rsplit('/', 1)[0] + '/'
    captions = [(unit, match) for unit in units if unit['kind'] == 'CAPTION'
                and unit['unitId'].startswith(prefix + 'fig[')
                and unit['text'] == caption_text
                and (match := BRACKET.search(unit['text']))
                and re.search(r'\bthe proband and (?:her|his|the) ' + parent + r'\b', unit['text'])]
    if len(captions) != 1:
        return
    caption, caption_pair = captions[0]
    alleles = {pair['a'], pair['b']}
    if {'c.' + value.casefold() for value in caption_pair.groups()} != {value.casefold() for value in alleles}:
        return

    def reference(unit, quote):
        return cite(source, {'unitId': unit['unitId'], 'quote': quote})

    pair_ref = reference(genotype, pair[0])
    phase_ref = reference(phase_unit, phase_unit['text'][:segregation.end()])
    figure_ref = reference(caption, caption['text'])
    figure_ref.update(sourceObjectId=figure_id, sourceLabel='Figure ' + figure)
    target_variant = next(iter(selected))
    partner_variant = next(iter(alleles - selected))
    proteins = {pair[key]: protein(pair['p' + key].replace('[', '').replace(']', '')) for key in ('a', 'b')}
    contradiction = any(re.search(r'\bthe two (?:variants|mutations) (?:are|were) in trans\b', row['text'], re.I)
                        for row in group)
    phase = 'CONFLICT' if contradiction else 'CIS'
    phase_basis = 'CONTRADICTORY_SOURCE_PHASE' if contradiction else METHOD
    facts = [{'field': field, 'value': value, 'reportedValue': value, 'container': first['unitId'],
              'method': METHOD, 'citation': citation, 'supportingCitations': [phase_ref, figure_ref]}
             for field, value, citation in [
                 ('targetVariant', target_variant, pair_ref), ('partnerVariant', partner_variant, pair_ref),
                 ('geneSymbol', definition['gene'], reference(context, definition[0])),
                 ('transcript', definition['tx'], reference(context, definition[0])),
                 ('diagnosis', definition['disease'], reference(genotype, diagnosis[0]))]]
    case['semantics']['observations'].append({
        'kind': 'REPORTED_TWO_ALLELES_PHASE_UNRESOLVED', 'geneSymbol': definition['gene'],
        'targetRepresentation': target_variant, 'partnerRepresentation': partner_variant,
        'reportedTranscripts': [definition['tx']], 'reportedProteins': {
            'target': proteins[target_variant], 'partner': proteins[partner_variant]},
        'facts': facts, 'reportedDiagnoses': [definition['disease']], 'reportedAffectedStatus': 'AFFECTED',
        'phase': phase, 'phaseBasis': phase_basis,
        'phaseAssayProof': {'method': METHOD, 'caseLabel': case['caseLabel'], 'assay': 'SEGREGATION_CLONING_ALLELE_SPECIFIC_PCR',
                            'parent': parent.upper(), 'alleles': sorted(alleles),
                            'references': [pair_ref, phase_ref, figure_ref]},
        'paperPmid': case['paperPmid'], 'genomicIdentityStatus': 'NOT_RESOLVED',
        'classificationStatus': 'NOT_ESTABLISHED', 'rarityStatus': 'NOT_ESTABLISHED', 'businessPublishable': False})
    case['semantics']['status'] = 'REPORTED_GENOTYPE_VERIFIED'
    case['primarySourceQualification'].update(qualification='VERIFIED',
        reasonCode='PM3_ORIGINAL_CASE_SOURCE_VERIFIED', method=METHOD)
