"""Literal self-contained case sentences, derived from original source units.

No pronoun resolution, inferred diagnosis, inherited phase or model certification.
Each accepted sentence names the case; incomplete and qualified claims remain
available in the original reviewed facts instead of being silently adopted.
"""
import re
from bundle_model_contract import cite
from copy import deepcopy

ALLELE=r'(?:NM_[0-9]+\.[1-9][0-9]*:)?c\.[1-9][0-9]*[ACGT]>[ACGT]'
GENE=r'[A-Za-z][A-Za-z0-9-]{0,39}'


def parse(text,label):
    prefix=r'(?:Patient|Case|Proband|Individual) '+re.escape(label)
    patterns=[
        (prefix+r' (?:had|carried) compound heterozygous (?:variants|mutations) ('+ALLELE+r') and ('+ALLELE+r') in ('+GENE+r')\.', 'pair'),
        (prefix+r' (?:was homozygous for|had a homozygous variant) ('+ALLELE+r') in ('+GENE+r')\.', 'homo'),
        (prefix+r' was diagnosed with ([^.\n]{1,180})\.', 'diagnosis'),
    ]
    # Consume the entire paragraph. Every sentence must name this exact case;
    # any unsupported qualifier/tail rejects the automatic interpretation.
    remaining=text.strip(); result={}
    while remaining:
        found=False
        for pattern, kind in patterns:
            m=re.match(pattern,remaining)
            if not m:continue
            if kind=='pair':data={'alleles':[m[1],m[2]],'gene':m[3],'zygosity':'compound heterozygous'}
            elif kind=='homo':data={'alleles':[m[1]],'gene':m[2],'zygosity':'homozygous'}
            else:
                if re.search(r'\b(?:not|no|suspected|possible|probable|or|and|without|excluded|family|history)\b',m[1],re.I):return None
                data={'diagnosis':m[1],'affectedStatus':'affected'}
            if any(k in result and result[k]!=v for k,v in data.items()):return None
            result.update(data);remaining=remaining[m.end():].strip();found=True;break
        if not found:return None
    return result or None


def native_facts(source,scope,label,target):
    from pm3_case_semantics import alias_key
    aliases={alias_key(v) for v in target['variantAliases']}
    result=[]
    for unit in source['main']['units']:
        own=scope.get(unit['unitId'],{})
        if own.get('method')!='EXPLICIT_CASE_PARAGRAPH':continue
        data=parse(unit['text'],label)
        if not data or 'alleles' not in data or data['gene']!=target['geneSymbol']:continue
        def target_match(a):return alias_key(a) in aliases or alias_key(a.split(':')[-1]) in aliases
        hits=[a for a in data['alleles'] if target_match(a)]
        if len(hits)!=1 or len(set(data['alleles']))!=len(data['alleles']):continue
        values=[('geneSymbol',data['gene']),('reportedZygosity',data['zygosity']),('targetVariant',hits[0])]
        values += [('partnerVariant',a) for a in data['alleles'] if a!=hits[0]]
        if 'diagnosis' in data:values += [('diagnosis',data['diagnosis']),('affectedStatus','affected')]
        for name,value in values:
            result.append({'field':name,'sourceValue':value,'subject':'PROBAND','issues':[],
                'producer':'SOURCE_NATIVE_EXPLICIT_CASE_SENTENCE','citation':cite(source,{'unitId':unit['unitId'],'quote':unit['text']}),
                'ownership':{**deepcopy(own),'paragraphText':unit['text'],'caseLabel':label},'status':'CASE_OWNED_SEMANTICS_PENDING'})
    return result


def accepts(fact,label):
    own=fact.get('ownership') or {}
    if own.get('method')!='EXPLICIT_CASE_PARAGRAPH' or fact.get('subject')!='PROBAND' or fact.get('issues'):return False
    text=own.get('paragraphText',''); data=parse(text,label)
    if not data or fact['citation']['quote'] not in text:return False
    field,value=fact['field'],fact['sourceValue']
    if field in ('targetVariant','partnerVariant'):return value in data.get('alleles',[])
    if field=='geneSymbol':return value==data.get('gene')
    if field=='reportedZygosity':return value==data.get('zygosity')
    if field=='diagnosis':return value==data.get('diagnosis')
    if field=='affectedStatus':return value==data.get('affectedStatus')
    return False
