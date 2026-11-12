"""Ground explicitly numbered parental-WES cases in local journal originals."""
import re

from bundle_model_contract import cite


METHOD = 'PUBLISHER_EXPLICIT_PARENTAL_WES'
SNV = r'c\.[1-9][0-9]*(?:[+-][1-9][0-9]*)?[ACGT]>[ACGT]'
ALLELES = rf'(?P<first>{SNV})(?:（p\.[A-Za-z0-9*]+）)?(?:变异)?和(?P<second>{SNV})(?:（p\.[A-Za-z0-9*]+）)?变异'
GENE = r'(?P<gene>[A-Z][A-Z0-9-]{0,39})'
RESULT = re.compile(rf'^病例(?P<label>[1-9][0-9]*)\s*WES结果显示患儿{GENE}基因存在复合杂合变异，分别为'
                    rf'{ALLELES}（(?P<transcript>NM_[0-9]+\.[1-9][0-9]*)），'
                    r'前者来自(?P<from_first>父亲|母亲)，后者来自(?P<from_second>父亲|母亲)（(?P<figure>图[1-9][0-9]*)）。')
CAPTION = re.compile(rf'^病例(?P<label>[1-9][0-9]*)及其家系成员WES结果\s+病例(?P=label)\s*{GENE}基因存在'
                     rf'{ALLELES}，前者来自患儿(?P<from_first>父亲|母亲)，后者来自患儿(?P<from_second>父亲|母亲)。')
ASSAY = re.compile(r'(?:^|(?<=。))采集患儿及父母[^。；，]{1,70}，[^。；]{0,100}提取基因组DNA，进行WES检测。')
DIAGNOSIS = re.compile(r'本例患儿诊断为(?P<label>[A-Z][A-Z0-9-]{0,39}缺乏型[A-Z]{2,8})。$')
PARENTS = {'父亲': 'FATHER', '母亲': 'MOTHER'}


def quoted(source, unit, quote):
    return cite(source, {'unitId': unit['unitId'], 'quote': quote})


def matches(units, pattern):
    return [(u, m) for u in units if (m := pattern.search(u['text']))]


def ownership(source, label):
    if not source.get('publisherReader') or not re.fullmatch(r'[1-9][0-9]*', label):
        return {}
    case = re.compile(rf'^病例{label}(?:[，,]|\s*WES结果显示)')
    return {u['unitId']: {'method': 'EXPLICIT_CASE_PARAGRAPH', 'container': u['unitId'],
                         'anchorUnitId': u['unitId'], 'paragraphText': u['text'], 'caseLabel': label}
            for u in source['main']['units'] if u['kind'] == 'BODY_PARAGRAPH' and case.match(u['text'])}


def clinical_mrs(source, case, clinical, captions):
    reports = matches(clinical, re.compile(r'(?:^|(?<=。))头颅MRS示肌酸峰明显(?:减低|降低)（(?P<figure>图[1-9][0-9]*)(?P<panel>[A-Z])）。'))
    if len(reports) != 1:
        return None
    unit, report = reports[0]
    panel = re.compile(rf'{report["panel"]}为病例{case}[^。]{{1,90}}MRS图像，肌酸峰（Cr）明显(?:减低|降低)。')
    figure = matches([u for u in captions if u.get('sourceLabel') == report['figure'] and u.get('publishedFigure')], panel)
    if len(figure) != 1:
        return None
    caption, match = figure[0]
    return {'assay': 'BRAIN_MRS', 'observedResult': 'SIGNIFICANTLY_REDUCED_CREATINE_PEAK',
            'panel': report['panel'], 'caseLabel': case,
            'references': [quoted(source, unit, report[0]), quoted(source, caption, match[0])]}


def qualified(source, case, target):
    """Use a single reviewed case anchor. All phase and phenotype facts are re-read."""
    scope = ownership(source, case['caseLabel'])
    units = source['main']['units']
    case_units = [u for u in units if u['unitId'] in scope]
    results = matches(case_units, RESULT)
    if len(results) != 1:
        return None
    unit, result = results[0]
    label, gene = result['label'], result['gene']
    if gene != target['geneSymbol'] or not any(a['unitId'] == unit['unitId'] for a in case['anchors']):
        return None
    pair = {result['first'], result['second']}
    selected = pair & {a.split(':')[-1] for a in target['variantAliases']}
    if len(selected) != 1 or len(pair) != 2:
        return None
    target_variant, partner = next(iter(selected)), next(iter(pair - selected))
    clinical = [u for u in case_units if re.match(rf'^病例{label}[，,]', u['text'])]
    captions = [u for u in units if u['kind'] == 'CAPTION']
    diagnosis = DIAGNOSIS.search(unit['text'])
    diagnosis_terms = [diagnosis['label']] if diagnosis else []
    anchor = quoted(source, unit, result[0])
    method_refs = [anchor]
    matched_abstract = []
    if diagnosis:
        term = re.fullmatch(r'([A-Z][A-Z0-9-]{0,39})缺乏型([A-Z]{2,8})', diagnosis['label'])
        if term and term[1] == gene:
            abstracts = [u for u in units if u['kind'] == 'ABSTRACT']
            chinese = matches([u for u in abstracts if u['language'] == 'zh'],
                              re.compile(re.escape(diagnosis['label']) + r'患儿'))
            english_term = f'{term[1]} deficiency'
            english = matches([u for u in abstracts if u['language'] == 'en'],
                              re.compile(r'diagnosed with ' + re.escape(english_term + '-type ' + term[2]) + r'\b'))
            if chinese and len(english) == 1:
                diagnosis_terms.append(english_term)
                matched_abstract = [quoted(source, u, m[0]) for u, m in [chinese[0], english[0]]]
    assays = matches([u for u in units if u['kind'] == 'BODY_PARAGRAPH'], ASSAY)
    figures = matches([u for u in captions if u.get('sourceLabel') == result['figure']], CAPTION)
    phase, phase_basis, phase_proof = 'UNKNOWN', 'NOT_ESTABLISHED', None
    fields = ('label', 'gene', 'first', 'second', 'from_first', 'from_second')
    if len(figures) == 1 and any(figures[0][1][key] != result[key] for key in fields):
        phase, phase_basis = 'CONFLICT', 'CONTRADICTORY_SOURCE_PHASE'
    elif len(assays) == 1 and len(figures) == 1 and figures[0][0].get('publishedFigure') and result['from_first'] != result['from_second']:
        phase, phase_basis = 'TRANS', METHOD
        method_refs += [quoted(source, u, m[0]) for u, m in [assays[0], figures[0]]]
        phase_proof = {'method': METHOD, 'caseLabel': label, 'assay': 'PARENTAL_WES',
                       'maternalAllele': result['first'] if PARENTS[result['from_first']] == 'MOTHER' else result['second'],
                       'paternalAllele': result['first'] if PARENTS[result['from_first']] == 'FATHER' else result['second'],
                       'references': method_refs}
    facts = []
    for field, value in (('targetVariant', target_variant), ('partnerVariant', partner), ('geneSymbol', gene),
                         ('transcript', result['transcript'])):
        facts.append({'field': field, 'value': value, 'reportedValue': value, 'container': unit['unitId'],
                      'method': METHOD, 'citation': anchor, 'supportingCitations': method_refs[1:]})
    if diagnosis:
        facts.append({'field': 'diagnosis', 'value': diagnosis['label'], 'reportedValue': diagnosis['label'],
                      'container': unit['unitId'], 'method': METHOD, 'citation': quoted(source, unit, diagnosis[0]),
                      'supportingCitations': matched_abstract})
    duplicate_pair = any(other['label'] != label and other['gene'] == gene
                         and {other['first'], other['second']} == pair
                         for _, other in matches([u for u in units if u['kind'] == 'BODY_PARAGRAPH'], RESULT))
    return {'kind': 'REPORTED_TWO_ALLELES_PHASE_UNRESOLVED', 'geneSymbol': gene, 'targetRepresentation': target_variant,
            'partnerRepresentation': partner, 'reportedTranscripts': [result['transcript']],
            'reportedProteins': {'target': None, 'partner': None}, 'facts': facts,
            'reportedDiagnoses': diagnosis_terms, 'reportedAffectedStatus': 'AFFECTED' if diagnosis and len(clinical) == 1 else 'UNKNOWN',
            'phase': phase, 'phaseBasis': phase_basis, 'phaseAssayProof': phase_proof,
            'clinicalAssayProof': None if duplicate_pair else clinical_mrs(source, label, clinical, captions),
            'paperPmid': case['paperPmid'],
            'genomicIdentityStatus': 'NOT_RESOLVED', 'classificationStatus': 'NOT_ESTABLISHED',
            'rarityStatus': 'NOT_ESTABLISHED', 'businessPublishable': False}


def attach(source, case, target):
    observation = qualified(source, case, target)
    if observation is None:
        return
    semantics = case['semantics']
    semantics['observations'].append(observation)
    semantics['status'] = 'REPORTED_GENOTYPE_VERIFIED'
    origins = {row['proposal'] for row in case['originAssertions']} - {'UNCLEAR'}
    if origins == {'AUTHOR_COHORT'} and observation['reportedAffectedStatus'] == 'AFFECTED':
        case['primarySourceQualification'].update(qualification='VERIFIED',
            reasonCode='PM3_ORIGINAL_CASE_SOURCE_VERIFIED', method='EXPLICIT_NUMBERED_PUBLISHER_CASE')
