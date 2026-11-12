"""Source-scoped unnamed proband with an explicit current-study genotype.

No diagnosis from a gene, no ownership of review tables, and no cross-paper
independence. The source label locates one clinical report, not a patient ID.
"""
import re
from copy import deepcopy

METHOD = 'EXPLICIT_SINGLE_PROBAND_RESULTS'
CODING = re.compile(r'NM_[0-9]+\.[1-9][0-9]*:c\.[1-9][0-9]*[ACGT]>[ACGT]')
OTHER = re.compile(r'\b(?:another|second|third|other|additional) (?:patient|proband|case|child)\b|\b(?:two|three|multiple|[2-9]) (?:patients|probands|cases|children)\b', re.I)


def normalized(text):
    return text.translate(str.maketrans({'‐':'-','‑':'-','–':'-'}))


def genotype(unit):
    text=unit.get('text','')
    changes=set(CODING.findall(text))
    if (unit.get('kind')!='BODY_PARAGRAPH' or len(changes)!=1
            or not text.startswith('We identified ')
            or not re.search(r'\bhomozyg(?:ous|ote)\b',text.split('. ',1)[0])
            or not re.search(r'\bSanger sequencing subsequently confirmed this homozygous variant\b',text)
            or 'in this individual' not in text or OTHER.search(text)
            or re.search(r'\b(?:not|never|possible|suspected|previously reported)\b',text,re.I)):
        return None
    genes=set(re.findall(r'\bin (?:the )?([A-Z][A-Z0-9.-]{0,39}) gene\b',text))
    if len(genes)!=1:return None
    change=next(iter(changes));tx,nt=change.split(':')
    # Extra coding variants would make the singular genotype ambiguous.
    if set(re.findall(r'c\.[1-9][0-9]*[ACGT]>[ACGT]',text))!={nt}:return None
    return {'geneSymbol':next(iter(genes)),'transcript':tx,'targetVariant':nt,'reportedZygosity':'homozygous'}


def hints(units):
    return [{'caseLabel':'proband','unitId':u['unitId'],
             'identityKind':'SOURCE_SCOPED_UNNAMED_PROBAND_NOT_PATIENT_ID'}
            for u in units if genotype(u)]


def report(source):
    units=source['main']['units']
    clinical=[u for u in units if u.get('kind')=='BODY_PARAGRAPH'
        and any(s.casefold() in ('case presentation','case report','clinical report') for s in u.get('sections',[]))]
    groups={u['unitId'].rsplit('/p[',1)[0] for u in clinical}
    intro=re.compile(r'^The (?:reported )?(?:proband|patient) (?:is|was) (?:an? )?[0-9][A-Za-z0-9 .-]{0,90}\b(?:boy|girl|man|woman|male|female)\b')
    if (len(groups)!=1 or not 0<len(clinical)<=32 or not intro.match(normalized(clinical[0]['text']))
            or sum(bool(intro.match(normalized(u['text']))) for u in clinical)!=1
            or any(OTHER.search(u['text']) for u in clinical)):
        return None
    prefix=clinical[0]['unitId'].split('/body[1]/',1)
    if len(prefix)!=2:return None
    section=prefix[0]+'/body[1]/'+prefix[1].split('/',1)[0]+'/'
    current=[u for u in units if u.get('kind')=='BODY_PARAGRAPH' and u['unitId'].startswith(section)]
    if any(OTHER.search(u['text']) for u in current):return None
    candidates=[(u,genotype(u)) for u in current if genotype(u)]
    if len(candidates)!=1:return None
    unit,values=candidates[0]
    return {'anchor':unit,'clinical':clinical,'values':values,'section':section}


def ownership(source,label):
    if label!='proband':return {}
    found=report(source)
    if not found:return {}
    unit=found['anchor']
    return {unit['unitId']:{'method':METHOD,'container':found['section'],
        'anchorUnitId':unit['unitId'],'caseLabel':label,'paragraphText':unit['text'],
        'identityKind':'SOURCE_SCOPED_UNNAMED_PROBAND_NOT_PATIENT_ID',
        'values':deepcopy(found['values']),
        'clinicalSourceUnitIds':[u['unitId'] for u in found['clinical']]}}


def native_facts(source,label,target):
    """Literal genotype plus explicitly reported clinical compatibility.

    A same-paper disease abbreviation definition alone supplies no diagnosis.
    It must be linked to the current girl/boy's typical symptoms and an explicit
    diagnosis statement in the conclusion. Uncertain wording remains missing.
    """
    from bundle_model_contract import cite
    found=report(source);scope=ownership(source,label)
    if not found or not scope or found['values']['geneSymbol']!=target['geneSymbol']:return []
    if found['values']['targetVariant'] not in {v.split(':')[-1] for v in target['variantAliases']}:return []
    unit=found['anchor'];own=scope[unit['unitId']];facts=[]
    def add(field,value,anchor,support=()):
        facts.append({'field':field,'sourceValue':value,'subject':'PROBAND','issues':[],
            'producer':'SOURCE_NATIVE_SINGLE_PROBAND','status':'CASE_OWNED_SEMANTICS_PENDING',
            'citation':cite(source,{'unitId':anchor['unitId'],'quote':anchor['text']}),
            'supportingCitations':[cite(source,{'unitId':u['unitId'],'quote':u['text']}) for u in support],
            'ownership':{**deepcopy(own),'verifiedField':field,'verifiedValue':value}})
    for field,value in found['values'].items():add(field,value,unit,found['clinical'])
    conclusions=[u for u in source['main']['units'] if u.get('kind')=='BODY_PARAGRAPH'
        and any(s.casefold() in ('conclusion','conclusions') for s in u.get('sections',[]))]
    diagnoses=[]
    for u in conclusions:
        text=u['text']
        if (not text.startswith('In the present study,') or 'this case was diagnosed' not in text
                or OTHER.search(text) or re.search(r'\b(?:not|possible|suspected|probable)\b',text,re.I)):
            continue
        m=re.search(r'\b(?:girl|boy|patient|proband) manifesting typical ([A-Z][A-Z0-9-]{1,20}) symptoms\b',text)
        if not m or not re.search(r'\bin the '+re.escape(found['values']['geneSymbol'])+r' gene\b',text):continue
        # The expanded disease must be explicit, not an invented expansion.
        definitions=[]
        for d in source['main']['units']:
            if d.get('kind') not in ('ABSTRACT_PARAGRAPH','BODY_PARAGRAPH'):continue
            for x in re.finditer(r'(?:^|[.!?] )([A-Za-z][A-Za-z -]{2,100} (?:deficiency|syndrome|disease)) \('+re.escape(m[1])+r',?\s*(?:MIM|OMIM)?',d['text']):
                name=x[1].strip()
                if len(name.split())<=12:definitions.append((name,d))
        names={name.casefold() for name,d in definitions}
        if len(names)==1 and definitions:diagnoses.append((definitions[0][0],u,definitions[0][1]))
    if len(diagnoses)==1:
        name,conclusion,definition=diagnoses[0]
        add('diagnosis',name,conclusion,[definition,unit,*found['clinical']])
        add('affectedStatus','affected',conclusion,[definition,*found['clinical']])
    return facts


def accepts(fact,label):
    own=fact.get('ownership') or {}
    if own.get('method')!=METHOD or label!='proband' or fact.get('subject')!='PROBAND' or fact.get('issues'):return False
    field,value=fact.get('field'),fact.get('sourceValue')
    if fact.get('producer')=='SOURCE_NATIVE_SINGLE_PROBAND':
        return own.get('verifiedField')==field and own.get('verifiedValue')==value
    return (own.get('values',{}).get(field)==value
            and value in fact['citation']['quote'] and fact['citation']['quote'] in own.get('paragraphText',''))
