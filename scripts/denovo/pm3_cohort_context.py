"""Conservative source-native cohort -> explicit table -> unique case binding.

No disease synonym inference, assay assignment, phase or genomic identity.
Related prose is retained even when this narrow automatic grammar cannot bind it.
"""
import re
import xml.etree.ElementTree as ET
from copy import deepcopy
from bundle_model_contract import cite

ENROLLED = re.compile(r'^Patients(?: at least [0-9]+ years of age,)?(?: with|,)? diagnosed(?: with)? (?P<diagnosis>[^.;]{1,180}) were enrolled in (?:a|the) [^.]+\.', re.I)
UNCERTAIN = re.compile(r'\b(?:not|no|without|suspected|possible|probable|or|and|controls?|unaffected|mixed|subgroups?|cohorts?)\b', re.I)
MIXED = re.compile(r'\b(?:healthy controls?|unaffected controls?|control subjects|control group|mixed cohort|another cohort|second cohort)\b', re.I)


def cohort_context(source, scope):
    paragraphs = [u for u in source['main']['units'] if u['kind'] == 'BODY_PARAGRAPH']
    related = [u for u in paragraphs if re.search(r'\b(?:diagnos\w*|enrolled|parental studies|genotyped|sequencing)\b', u['text'], re.I)]
    result = {'schemaVersion':'pm3-cohort-context-v1', 'status':'UNRESOLVED', 'facts':[],
              'reasonCode':'PM3_COHORT_CASE_LINK_NOT_ESTABLISHED', 'bindings':[],
              'relatedStatements':[{'citation':cite(source, {'unitId':u['unitId'],'quote':u['text']}),
                  'use':'RELATED_SOURCE_NOT_CASE_ADOPTED'} for u in related]}
    candidates = [(u, ENROLLED.match(u['text'])) for u in paragraphs]
    candidates = [(u,m) for u,m in candidates if m]
    if len(candidates) != 1 or any(MIXED.search(u['text']) for u in paragraphs):
        return result
    enrollment, match = candidates[0]; diagnosis = match['diagnosis'].strip()
    if (UNCERTAIN.search(diagnosis) or ',' in diagnosis or
            re.search(r'\b(?:prior|previous|another|published|reported|suspected|controls?|case-control)\b',match[0],re.I)):
        return result
    try: tree = ET.fromstring(source['raw'])
    except (KeyError,ET.ParseError): return result
    for table in source['main']['tables']:
        container = 'main:'+table['tableId']
        rows = [v for v in scope.values() if v.get('method')=='EXPLICIT_UNIQUE_CASE_ROW' and v['container']==container]
        if not rows or len({v['anchorUnitId'] for v in rows}) != 1: continue
        native = [t for t in tree.iter('table-wrap') if t.get('id')==table.get('xmlId') and t.get('id')]
        if len(native)!=1: continue
        label = native[0].find('label')
        if label is None: continue
        label = ' '.join(''.join(label.itertext()).split())
        if not re.fullmatch(r'Table [1-9][0-9]*',label): continue
        same_labels=[t for t in tree.iter('table-wrap') if t.find('label') is not None and
                     ' '.join(''.join(t.find('label').itertext()).split())==label]
        if len(same_labels)!=1: continue
        links = [u for u in paragraphs if re.match(re.escape(label)+r' (?:presents|shows|lists) [^.]*\bof all (?:enrolled )?patients(?:[,.])',u['text'],re.I)
                 and not re.search(r'\b(?:not|no|except|excluding|subset|controls?|unaffected)\b',u['text'].split('.')[0],re.I)]
        if len(links)!=1: continue
        anchor = rows[0]['anchorUnitId']
        unit = next((u for u in source['units'] if u['unitId']==anchor),None)
        if unit is None: continue
        citations = [cite(source,{'unitId':enrollment['unitId'],'quote':match[0]}),
                     cite(source,{'unitId':links[0]['unitId'],'quote':links[0]['text']}),
                     cite(source,{'unitId':anchor,'quote':unit['text']})]
        binding = {'container':container,'tableLabel':label,'tableXmlId':table['xmlId'],
                   'diagnosis':diagnosis,'citations':citations,'method':'EXPLICIT_ENROLMENT_ALL_PATIENTS_TABLE_CASE'}
        result['bindings'].append(binding)
        for field,value in [('diagnosis',diagnosis),('affectedStatus','affected')]:
            result['facts'].append({'field':field,'sourceValue':value,'subject':'PROBAND','issues':[],
                'producer':'SOURCE_NATIVE_COHORT_CONTEXT','status':'CASE_OWNED_SEMANTICS_PENDING',
                'citation':deepcopy(citations[0]),'supportingCitations':deepcopy(citations[1:]),
                'ownership':{'method':'EXPLICIT_COHORT_TABLE_CASE','container':container,
                    'anchorUnitId':anchor,'diagnosis':diagnosis,'binding':deepcopy(binding)}})
    if result['facts']:
        result.update(status='SOURCE_COHORT_BOUND',reasonCode='PM3_COHORT_CASE_SOURCE_LINK_VERIFIED')
        adopted={c['unitId'] for b in result['bindings'] for c in b['citations']}
        result['relatedStatements']=[r for r in result['relatedStatements'] if r['citation']['unitId'] not in adopted]
    return result


def cohort_fact(fact):
    own=fact.get('ownership') or {}
    return (own.get('method')=='EXPLICIT_COHORT_TABLE_CASE' and
            fact.get('producer')=='SOURCE_NATIVE_COHORT_CONTEXT' and not fact.get('issues') and
            fact.get('subject')=='PROBAND' and len(own.get('binding',{}).get('citations',[]))==3 and
            ((fact['field']=='diagnosis' and fact['sourceValue']==own.get('diagnosis')) or
             (fact['field']=='affectedStatus' and fact['sourceValue']=='affected')))
