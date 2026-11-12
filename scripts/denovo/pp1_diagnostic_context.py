"""Bind reported fulfillment of named criteria to an exact published disease.

This reads a finite, affirmative disease-specific PP4 declaration from the same
released specification as PP1. It neither diagnoses from measurements nor
awards PP4. Unknown conditions, multiple disease scopes and mere mentions do
not produce a binding. Names and source pointers come from the source, never a
gene, variant or patient whitelist.
"""
from copy import deepcopy
import re

from model_literature_contract import canonical, digest


def normalized(value):
    return ' '.join(value.split()).casefold() if isinstance(value, str) else ''


def published_bindings(specification, associations, selection):
    scope = {k: selection[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}
    result = {'bindings': [], 'unresolved': []}
    nodes = [(i, n) for i, n in enumerate(specification['original'].get('ld', {}).get('CriteriaCode', []))
             if n.get('entContent', {}).get('label') == 'PP4'
             and selection['geneSymbol'] in n['entContent'].get('gene', [])]
    if not nodes:
        return result
    # An omitted criterion-level disease is unambiguous only if the selected
    # gene has exactly one disease in this released specification.
    disease_ids = {a['scope']['mondoId'] for a in associations if a['geneSymbol'] == selection['geneSymbol']}
    for index, node in nodes:
        clause = node['entContent']; pointer = f'/ld/CriteriaCode/{index}/entContent'
        reason = None
        allowed = {'_uniqueProp','additionalComments','baseStrength','defaultStrength','evidenceCategory',
                   'gene','geneType','instructionsToUse','label','ns','originalACMGSummary','sepioID',
                   'strengthDescriptor','disease','applicability','status','specificationType'}
        if (set(clause) - allowed or len(nodes) != 1
                or clause.get('disease', None) not in (None, [selection['diseaseId']])
                or (clause.get('disease') is None and disease_ids != {selection['diseaseId']})):
            reason = 'PP1_DIAGNOSTIC_SCOPE_OR_STRUCTURE_UNRESOLVED'
        elif (clause.get('geneType') != 'nuclear' or clause.get('baseStrength') != 'Pathogenic'
              or clause.get('defaultStrength') != 'Pathogenic Supporting'
              or clause.get('applicability') not in (None, 'Applicable', 'Applicable with VCEP specification')
              or clause.get('status') not in (None, 'approved')
              or normalized(clause.get('additionalComments')) or normalized(clause.get('instructionsToUse'))):
            reason = 'PP1_DIAGNOSTIC_CONDITIONS_UNIMPLEMENTED'
        descriptors = clause.get('strengthDescriptor', [])
        active = [(j, d) for j, d in enumerate(descriptors)
                  if d.get('applicability') != 'Not Applicable for this VCEP']
        if len(active) != 1:
            reason = reason or 'PP1_DIAGNOSTIC_DECLARATION_UNSUPPORTED'
        match = None
        if reason is None:
            j, descriptor = active[0]
            allowed_descriptor = {'applicability','id','instructionsToUse','specificationType',
                                  'strength','strengthSepioID','text','type','status'}
            # Full-match the whole assertion, including its only permitted
            # family-use sentence; do not strip unknown extra requirements.
            text = ' '.join((descriptor.get('text') or '').split())
            match = re.fullmatch(r'\* Use if patient (?:fulfils|fulfills|meets) ([A-Za-z][A-Za-z0-9 ()-]{0,100} criteria)\.'
                                 r'(?: \* Can be used if any of the family members have a highly specific phenotype\.)?', text)
            if (set(descriptor) - allowed_descriptor or not match
                    or descriptor.get('strength') != 'Supporting'
                    or descriptor.get('applicability') != 'Applicable with VCEP specification'
                    or descriptor.get('specificationType') != 'Disease-specific'
                    or descriptor.get('status') not in (None, 'approved')
                    or normalized(descriptor.get('instructionsToUse'))
                    or any(normalized(d.get('text')) or normalized(d.get('instructionsToUse'))
                           for d in descriptors if d not in [descriptor])):
                reason = 'PP1_DIAGNOSTIC_DECLARATION_UNSUPPORTED'
            elif re.search(r'\b(or|and|not|possible|suspected|partial|some|screening)\b', match[1], re.I):
                reason = 'PP1_DIAGNOSTIC_DECLARATION_UNSUPPORTED'
        if reason:
            result['unresolved'].append({'reasonCode': reason, 'sourcePointer': pointer})
            continue
        result['bindings'].append({'criteriaName': match[1], 'scope': scope,
            'meaning': 'REPORTED_FULFILLMENT_OF_PUBLISHED_DISEASE_CRITERIA',
            'application': 'REPORTED_PHYSICIAN_ASSESSMENT_ONLY_NO_NEW_DIAGNOSIS_OR_PP4',
            'source': deepcopy(specification['source']),
            'specificationKey': specification['specificationKey'],
            'clauseSha256': digest(canonical(node)),
            'jsonPointer': pointer + f'/strengthDescriptor/{j}/text', 'sourceText': descriptor['text']})
    return result
