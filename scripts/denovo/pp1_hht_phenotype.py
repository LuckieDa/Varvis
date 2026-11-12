"""Source-bound Curaçao phenotype eligibility for the published HHT PP1 rule."""
import re
from xml.etree import ElementTree as ET

METHOD = 'HHT_CURACAO_AFFECTED_ONLY'
INSTRUCTIONS = ('See HHT phenotype document in attachments for assignment of '
                'affected/unaffected status for purpose of inclusion in cosegregation study.')
_WORD = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
_SECTION = 'pp1/bs4 phenotype requirements:'
_BASE = (
    'epistaxis- spontaneous and recurrent',
    'telangiectases- multiple at characteristic sites (lips, oral cavity, fingers, nose)',
    'internal lesions- such as gastrointestinal telangiectasia; pulmonary, cerebral, hepatic, spinal avm',
    'family history- first degree relative with hht according to these criteria',
)
_RECOMMENDATION = (
    'assignment of affected/unaffected status to family members must consider that the most common manifestations '
    'of hht (nosebleeds and telangiectases) have significant phenocopy rates, age-related penetrance, and variable expression.',
    'recommendations for assignment of affected/unaffected status for purpose of inclusion in co-segregation study:',
)
_UNAFFECTED = ('unaffected: do not include for the purpose of segregation analysis. on clinical grounds an individual '
               'cannot at any age be assigned unaffected status with confidence (pmid: 35165143, pmid: 19553198).')

FEATURES = {
    'EPISTAXIS': r'(?:recurrent spontaneous|spontaneous recurrent) (?:epistaxis|nosebleeds)',
    'TELANGIECTASES': (r'multiple (?:mucocutaneous )?telangiectas(?:es|ias) (?:at|on|of|in) '
                       r'(?:the )?(?:lips?|tongue|oral cavity|oral mucosa|fingers?|nose|nasal mucosa)'),
    'VISCERAL_LESIONS': (r'(?:gastrointestinal telangiectas(?:es|ias)|(?:an? )?(?:pulmonary|cerebral|hepatic|spinal) '
                         r'(?:AVMs?|arteriovenous malformations?))'),
}


def compile_attachment(document_xml):
    """Adopt only the fully understood normative section of the verified attachment."""
    root = ET.fromstring(document_xml)
    lines = [' '.join(''.join(node.text or '' for node in p.iter(_WORD + 't')).lower().split())
             for p in root.iter(_WORD + 'p')]
    lines = [line for line in lines if line]
    if lines.count(_SECTION) != 1:
        return None
    index = lines.index(_SECTION)
    definition, section = tuple(lines[index-4:index]), lines[index+1:]
    if (definition != _BASE or len(section) != 4 or tuple(section[:2]) != _RECOMMENDATION
            or section[3] != _UNAFFECTED):
        return None
    affected = re.fullmatch(r'affected: ([1-4]) or more manifestations of hht '
                            r'\(first-degree relative with hht by curaçao criteria, counts as one\)\.', section[2])
    if affected and affected[1] == '3':
        return {'method': METHOD, 'minimumManifestations': int(affected[1]),
                'familyHistoryMaximum': 1, 'unaffectedExcluded': True}
    return None


def source_facts(member, unit, quote):
    """Recognize an explicit member assertion; paper diagnoses by genotype never qualify."""
    subject = rf'{member}\)? (?:has|had|reported|experienced|showed|presented with|was found to have) '
    statements = [s.strip().removesuffix('.') for s in re.split(r'(?<=[.!?])\s+', unit)]
    statements = [s for s in statements if s and s in quote and not s.endswith(('?', '!'))]
    for kind, feature in FEATURES.items():
        if any(re.fullmatch(rf'{subject}{feature}', s, re.I) for s in statements):
            yield 'hhtManifestation', kind
        if any(re.fullmatch(rf'{subject}(?:no|an absence of) {feature}', s, re.I) for s in statements):
            yield 'hhtManifestationAbsent', kind
    definite = (rf'{member}\)? (?:clinically )?(?:met|fulfilled|meets|fulfills|fulfils) (?:the )?'
                r'(?:(?:clinical )?cura[çc]ao criteria for definite (?:hht|hereditary h[ae]emorrhagic telangiectasia)'
                r'|(?:at least )?(?:3|three) (?:of the (?:4|four) )?cura[çc]ao criteria)')
    if any(re.fullmatch(definite, s, re.I) for s in statements):
        yield 'hhtCuracaoDefinite', True


def assess_members(rows, members, phenotype, source):
    """Qualify features by category and permit one anchored first-degree history item."""
    by = {member: {'present': {}, 'absent': set(), 'definite': [], 'relationships': [], 'unaffected': []}
          for member in members}
    for row in rows:
        member, kind, value = row['memberKey'], row['kind'], row['value']
        if member not in by:
            continue
        item = by[member]
        if kind == 'hhtManifestation' and value in FEATURES:
            item['present'].setdefault(value, []).append(row)
        if kind == 'hhtManifestationAbsent' and value in FEATURES:
            item['absent'].add(value)
        if kind == 'hhtCuracaoDefinite' and value is True:
            item['definite'].append(row)
        if kind == 'clinical' and value == 'UNAFFECTED':
            item['unaffected'].append(row)
        if kind in {'parentOf', 'childOf', 'fullSiblingOf'} and value in by and value != member:
            item['relationships'].append((value, row))
            by[value]['relationships'].append((member, row))
    minimum = phenotype['minimumManifestations']
    conflicts = {key for key, item in by.items() if item['absent'].intersection(item['present'])
                 or item['unaffected'] and (item['definite'] or len(item['present']) >= minimum)}
    # A relative whose own history is unobserved cannot seed another member's
    # family history. This prevents two people with two features each from
    # making one another affected merely because they are related.
    anchors = {key for key, item in by.items() if key not in conflicts and len(item['present']) >= minimum}
    for key, item in by.items():
        proof = [row for group in item['present'].values() for row in group]
        categories = sorted(item['present'])
        history = next(((relative, row) for relative, row in item['relationships'] if relative in anchors), None)
        if history:
            relative, relationship = history
            categories.append('FAMILY_HISTORY')
            proof += [relationship] + [row for group in by[relative]['present'].values() for row in group]
        affected = key not in conflicts and (len(categories) >= minimum or bool(item['definite']))
        if item['unaffected'] and affected:
            conflicts.add(key)
            affected = False
        proof += item['definite'] + item['unaffected']
        yield key, proof, {'basis': METHOD, 'status': 'CONFLICT' if key in conflicts else 'AFFECTED' if affected else 'UNRESOLVED',
                           'memberKey': key, 'memberLabel': members[key], 'minimumManifestations': minimum,
                           'verifiedManifestations': categories, 'reportedDefiniteClinicalCriteria': bool(item['definite']),
                           'ruleAttachmentSha256': source['fileSha256'],
                           'sourceRefs': [ref for row in proof for ref in row['sourceRefs']]}
