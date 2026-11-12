"""Read a single SNV pair and its reported phase from a case-owned supplement row."""
import re
from pathlib import PurePosixPath

from bundle_model_contract import cite
from literature_allele_identity import protein
from pm3_case_semantics import alias_key


SNV = r'[1-9][0-9]*[ACGT]>[ACGT]'
PROTEIN = r'[A-Z][a-z]{2}[1-9][0-9]*[A-Z][a-z]{2}'
DIPLOTYPE = re.compile(
    r'(?P<gene>[A-Z][A-Z0-9-]{0,39});\s*chr(?:[1-9]|1[0-9]|2[0-2]|X|Y):\[' + SNV + r'\]\(;\)\[' + SNV + r'\];\s*'
    r'(?P<tx>NM_[0-9]+\.[1-9][0-9]*):c\.\[(?P<a>' + SNV + r')\]\(;\)\[(?P<b>' + SNV + r')\];\s*'
    r'NP_[0-9]+\.[1-9][0-9]*:p\.\[\((?P<pa>' + PROTEIN + r')\)\]\(;\)\[\((?P<pb>' + PROTEIN + r')\)\]',
    re.I)
HETEROZYGOUS = re.compile(r'two heterozygous variants(?: \(in both proband and affected (?:sister|brother|sibling)\))?', re.I)
CLASS = r'(?:likely )?pathogenic'
DIPLOTYPE_RESULT = re.compile(
    rf'(?:two {CLASS} variants|2 {CLASS} variants|1 {CLASS} and 1 {CLASS} variants?)'
    r'(?:, (?P<bare>phase unknown)| \((?P<parenthetic>phase unknown)\))?\.?', re.I)
COHORT = re.compile(
    r'^In this study, [1-9][0-9]* consecutive unrelated probands with [a-z][a-z -]{1,100} '
    r'tested in our laboratory from [0-9]{4} to [0-9]{4}, were included\.', re.I)
METHOD = 'EXPLICIT_LINKED_SUPPLEMENT_CASE_DIPLOTYPE'


def attach(source, case, target):
    if case['semantics']['observations']:
        return
    from pm3_observations import ownership
    scope = ownership(source, case['caseLabel'])
    units = {u['unitId']: u for u in source['units']}
    aliases = {alias_key(a.split(':')[-1]) for a in target['variantAliases']}
    candidates = []
    for uid, owned in scope.items():
        variant = units[uid]
        if variant['kind'] != 'SUPPLEMENT_CELL' or owned.get('columnHeading', '').strip().casefold() != 'variant':
            continue
        pair = DIPLOTYPE.fullmatch(' '.join(variant['text'].split()))
        if not pair or pair['gene'] != target['geneSymbol'] or pair['a'] == pair['b']:
            continue
        alleles = {'c.' + pair[k]: protein('p.' + pair['p' + k]) for k in ('a', 'b')}
        if not all(alleles.values()):
            continue
        chosen = [a for a, p in alleles.items() if a in aliases or 'protein:' + p in aliases]
        if len(chosen) != 1:
            continue
        cells = {}
        for key, owner in scope.items():
            if owner.get('anchorUnitId') == owned['anchorUnitId']:
                cells.setdefault(owner['columnHeading'].strip().casefold(), []).append(units[key])
        required = ('zygosity', 'result', 'phenotype classification')
        if any(len(cells.get(k, [])) != 1 for k in required):
            continue
        zygosity, result, phenotype = (cells[k][0] for k in required)
        result_text = ' '.join(result['text'].split())
        reported_result = DIPLOTYPE_RESULT.fullmatch(result_text)
        if (not HETEROZYGOUS.fullmatch(' '.join(zygosity['text'].split())) or (result_text and not reported_result)
                or not re.fullmatch(r'[A-Za-z][A-Za-z -]{1,100}', phenotype['text'].strip())):
            continue
        anchor = units[owned['anchorUnitId']]
        if not any(r['unitId'] == anchor['unitId'] for r in case['anchors']):
            continue
        links = [link for link in source['main']['links'] if link['kind'] == 'media'
                 and PurePosixPath(link.get('href') or '').name == variant.get('originalName')]
        if len(links) == 1:
            phase_text = (reported_result['bare'] or reported_result['parenthetic']) if reported_result else None
            candidates.append((owned, cells, variant, anchor, pair, alleles, chosen[0], phase_text))
    if len(candidates) != 1:
        return
    owned, cells, variant, anchor, pair, alleles, selected, phase_text = candidates[0]
    partner = next(a for a in alleles if a != selected)

    def ref(unit, value=None):
        return cite(source, {'unitId': unit['unitId'], 'quote': value or unit['text'].strip()})

    def fact(name, value, unit, quote=None, method=METHOD):
        return {'field': name, 'value': value, 'reportedValue': value,
                'container': owned['container'], 'method': method, 'citation': ref(unit, quote)}

    phenotype = cells['phenotype classification'][0]
    facts = [fact('targetVariant', selected, variant),
             fact('partnerVariant', partner, variant),
             fact('transcript', pair['tx'], variant, pair['tx']),
             fact('geneSymbol', pair['gene'], variant, pair['gene']),
             fact('reportedZygosity', 'heterozygous', cells['zygosity'][0]),
             fact('diagnosis', phenotype['text'].strip(), phenotype)]
    if phase_text:
        phase_fact = fact('phaseText', phase_text, cells['result'][0], phase_text, 'EXPLICIT_CASE_TYPED_TABLE_PHASE')
        phase_fact['supportingCitations'] = [ref(anchor), ref(cells['zygosity'][0])]
        facts.append(phase_fact)
    for name, field in [('omim disease association', 'reportedDiseaseAssociation'), ('fferg', 'reportedClinicalAssay'),
                        ('rod erg', 'reportedClinicalAssay'), ('scotopic erg', 'reportedClinicalAssay'),
                        ('congenital nyctalopia', 'reportedSymptom'), ('congenital night blindness', 'reportedSymptom'),
                        ('nyctalopia', 'reportedSymptom'), ('night blindness', 'reportedSymptom'),
                        ('case category', 'reportedMolecularDiagnosisCategory')]:
        if len(cells.get(name, [])) == 1 and cells[name][0]['text'].strip():
            unit = cells[name][0]
            facts.append({**fact(field, unit['text'].strip(), unit), 'columnHeading': name})
    cohorts = [(u, m[0]) for u in source['main']['units'] if u['kind'] == 'BODY_PARAGRAPH'
               and any(s.casefold() == 'results' for s in u['sections']) and (m := COHORT.match(u['text']))]
    origins = {row['proposal'] for row in case['originAssertions']} - {'UNCLEAR'}
    verified = len(cohorts) == 1 and origins == {'AUTHOR_COHORT'}
    if verified:
        unit, quote = cohorts[0]
        facts.append(fact('affectedStatus', 'AFFECTED', unit, quote))
        case['primarySourceQualification'].update(qualification='VERIFIED',
            reasonCode='PM3_ORIGINAL_CASE_SOURCE_VERIFIED', method=METHOD)
    case['semantics']['observations'].append({
        'kind': 'REPORTED_TWO_ALLELES_PHASE_UNRESOLVED', 'geneSymbol': pair['gene'],
        'targetRepresentation': selected, 'partnerRepresentation': partner,
        'reportedTranscripts': [pair['tx']], 'reportedProteins': {'target': alleles[selected], 'partner': alleles[partner]},
        'facts': facts, 'reportedDiagnoses': [phenotype['text'].strip()],
        'reportedAffectedStatus': 'AFFECTED' if verified else 'UNKNOWN',
        'phase': 'UNKNOWN', 'phaseBasis': 'EXPLICIT_SOURCE_PHASE_UNKNOWN' if phase_text else 'NOT_REPORTED',
        'paperPmid': case['paperPmid'],
        'genomicIdentityStatus': 'NOT_RESOLVED', 'classificationStatus': 'NOT_ESTABLISHED',
        'rarityStatus': 'NOT_ESTABLISHED', 'businessPublishable': False})
    case['semantics']['status'] = 'REPORTED_GENOTYPE_VERIFIED'
