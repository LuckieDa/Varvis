"""Source-scoped PM3 occurrence proof; not worldwide independence or scoring.

Reconstruct ownership and prior-report signals from pinned source units. Model
agreement and different labels alone cannot establish independent probands.
"""
import re
from copy import deepcopy
from literature_case_structure import paragraph_owned, prior_case
from qualify_literature_cases import sentences
from model_literature_contract import canonical, digest

FAMILY_HEADING=re.compile(r'family(?:\s+(?:id|identifier|number|no\.?))?',re.I)
UNRELATED=re.compile(r'(?:Patients|Probands|Cases) (?P<labels>[1-9][0-9]*(?:(?:, ?| and )[1-9][0-9]*)+) (?:were|are) unrelated\.')


def attach(source, observations):
    from pm3_observations import ownership
    cases=observations.get('cases',[])
    if not cases:return observations
    owned={c['caseLabel']:ownership(source,c['caseLabel']) for c in cases}
    labels={c['caseLabel'] for c in cases if owned[c['caseLabel']]}
    groups=[]
    for unit in source['main']['units']:
        if (unit['kind']!='BODY_PARAGRAPH' or prior_case(unit['text'])
                or not any(s.casefold() in ('methods','patients','subjects','participants','results',
                    'case reports','clinical report','clinical reports') for s in unit.get('sections',[]))):continue
        for start,end,text in sentences(unit['text']):
            match=UNRELATED.fullmatch(text)
            if not match:continue
            members=re.split(r', ?| and ',match['labels'])
            if len(set(members))!=len(members) or not set(members)<=labels:continue
            groups.append({'caseLabels':members,'citation':{'unitId':unit['unitId'],
                'sourceSha256':source['main']['sourceSha256'],'quote':text,'start':start,'end':end},
                'method':'EXPLICIT_NUMBERED_UNRELATED_PROBANDS'})
    for case in cases:
        label=case['caseLabel'];scope=owned[label]
        prior=[]
        for unit in source['main']['units']:
            if unit['kind']=='BODY_PARAGRAPH' and paragraph_owned(source['main'],unit,label) and prior_case(unit['text']):
                prior.append({'unitId':unit['unitId'],'sourceSha256':source['main']['sourceSha256'],'quote':unit['text']})
        families=[]
        relation_claims=[]
        for fact in case['facts']:
            binding=scope.get(fact['citation']['unitId'],{})
            if fact['field'] in ('previousReport','relatedCases') and binding:
                relation_claims.append(deepcopy(fact))
            if (fact['field']=='familyIdentity' and fact.get('subject') in ('PROBAND','FAMILY')
                    and not fact.get('issues') and binding.get('method')=='EXPLICIT_UNIQUE_CASE_ROW'
                    and FAMILY_HEADING.fullmatch((binding.get('columnHeading') or '').strip())
                    and binding.get('cellText','').strip()==fact['sourceValue'].strip()==fact['citation']['quote'].strip()
                    and re.fullmatch(r'(?:Family\s+)?[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',fact['sourceValue'].strip())
                    and fact['sourceValue'].strip().casefold() not in ('unknown','na','none','missing','unreported')):
                families.append({'value':re.sub(r'^Family\s+','',fact['sourceValue'].strip()),'citation':deepcopy(fact['citation'])})
        family_values={f['value'] for f in families}
        related=[g for g in groups if label in g['caseLabels']]
        # Retain relation proposals for later cross-source reconciliation. A
        # "previous report" proposal is not accepted as a duplicate by itself.
        unresolved_relations=[f for f in relation_claims if not (f['field']=='relatedCases' and any(
            f['citation']['unitId']==g['citation']['unitId'] and f['citation']['quote']==g['citation']['quote'] for g in related))]
        primary=case.get('primarySourceQualification',{}).get('qualification')
        reason=('PM3_CASE_ORIGIN_CONFLICT' if primary=='CONFLICT' else
                'PM3_ORIGINAL_CASE_SOURCE_UNRESOLVED' if primary!='VERIFIED' else
                'PM3_CASE_SOURCE_OWNERSHIP_UNRESOLVED' if not scope else
                'PM3_FAMILY_IDENTITY_CONFLICT' if len(family_values)>1 else
                'PM3_PRIOR_OR_RELATED_CASE_RECONCILIATION_REQUIRED' if prior or unresolved_relations else None)
        status='CONFLICT' if reason and reason.endswith('CONFLICT') else 'UNKNOWN' if reason else 'VERIFIED'
        proof={'schemaVersion':'pm3-source-occurrence-v1','qualification':status,
            'reasonCode':reason or 'PM3_PRIMARY_CASE_VERIFIED_WITHIN_SOURCE',
            'scope':'CURRENT_PINNED_PAPER_ONLY','crossPaperIndependence':'NOT_ESTABLISHED',
            'sourceCaseId':'pm3-case-'+digest(canonical({'pmcid':source['pmcid'],'caseLabel':label})),
            'pmcid':source['pmcid'],'caseLabel':label,'sourceSha256':source['main']['sourceSha256'],
            'familyIdentity':next(iter(family_values)) if len(family_values)==1 else None,
            'familyCitations':families,'unrelatedGroups':related,'priorReportCitations':prior,
            'relationClaims':relation_claims,'unresolvedRelationClaims':unresolved_relations,
            'checkedOwnershipUnits':sorted(scope)}
        case['occurrenceQualification']=proof
    return observations
