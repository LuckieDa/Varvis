"""Anonymous source-row locators are not invented patient/family identities.

Recognises only two explicit protein/coding SNV pairs below an Allele 1/2
header on the same native-text PDF page. No inference of phase, diagnosis,
transcript, original case origin or independence is made here.
"""
import re

PAIR = r'(?P<{p}>p\.(?:[A-Z][a-z]{{2}}|[A-Z*])[1-9][0-9]*(?:[A-Z][a-z]{{2}}|[A-Z*]))(?P<{m}>[‡†§¶|]*)\s+(?P<{c}>c\.[1-9][0-9]*[ACGT]>[ACGT])'
ROW = re.compile('^'+PAIR.format(p='p1',m='m1',c='c1')+r'\s+'+PAIR.format(p='p2',m='m2',c='c2')+r'(?:\s+(?P<tail>.*))?$')
HEADER = re.compile(r'^Allele\s+1\s+Allele\s+2(?:\s|$)',re.I)


def rows(units):
    result=[]
    for unit in units:
        if not unit.get('pdfTextOnly'):continue
        match=ROW.fullmatch(unit['text'])
        if not match:continue
        # Free prose or a negative observation is not a tabulated genotype.
        tail=match['tail'] or ''
        if tail and not re.fullmatch(r'[0-9 .%+−\-]*(?:(?:Yes|No|positive|negative|NA|[0-9.%+−\-]+)\s*)+',tail):continue
        headers=[v for v in units if v.get('pdfTextOnly') and v.get('page')==unit.get('page')
                 and v.get('archiveMemberName')==unit.get('archiveMemberName')
                 and v['sourceSha256']==unit['sourceSha256'] and HEADER.match(v['text'])
                 and v.get('bbox',[0,0,0,0])[3] <= unit.get('bbox',[0,0,0,0])[1]]
        if len(headers)!=1:continue
        line=re.fullmatch(r'/page\[([1-9][0-9]*)\]/line\[([1-9][0-9]*)\]',unit.get('sourcePath',''))
        if not line:continue
        label=f"SRC-{unit['sourceSha256'][:12]}-P{line[1]}-L{line[2]}"
        if unit.get('archiveMemberName'):
            from model_literature_contract import digest
            label=f"SRC-{unit['sourceSha256'][:12]}-{digest(unit['archiveMemberName'].encode())[:12]}-P{line[1]}-L{line[2]}"
        result.append({'caseLabel':label,'identityKind':'ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID',
            'unitId':unit['unitId'],'quote':unit['text'],'headerUnitId':headers[0]['unitId'],
            'headerQuote':headers[0]['text'],
            'alleles':[{'coding':match['c1'],'protein':match['p1'],'sourceMarkers':match['m1']},
                       {'coding':match['c2'],'protein':match['p2'],'sourceMarkers':match['m2']}],
            'phase':'NOT_ESTABLISHED','caseIndependence':'NOT_ESTABLISHED'})
    return result


def ownership(source,label):
    found=[r for r in rows(source['units']) if r['caseLabel']==label]
    if len(found)!=1:return {}
    row=found[0]
    return {row['unitId']:{'method':'ANONYMOUS_SOURCE_GENOTYPE_ROW','container':row['unitId'],
            'anchorUnitId':row['unitId'],'cellText':row['quote'],'caseLabel':label,
            'identityKind':row['identityKind'],'row':row}}


def accepts(fact):
    own=fact.get('ownership') or {};row=own.get('row',{})
    return (own.get('method')=='ANONYMOUS_SOURCE_GENOTYPE_ROW' and fact.get('subject')=='PROBAND'
            and not fact.get('issues') and fact.get('field') in ('targetVariant','partnerVariant')
            and fact['sourceValue'] in {a[k] for a in row.get('alleles',[]) for k in ('coding','protein')}
            and fact['sourceValue'] in fact['citation']['quote']
            and fact['citation']['quote'] in row.get('quote',''))
