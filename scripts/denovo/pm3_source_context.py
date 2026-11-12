"""Source-native context from the exact table owning a reviewed PM3 case.

No query-supplied gene/transcript, abstract-wide inheritance, disease inference
or default transcript. Derived facts remain distinct from the model proposal.
"""
import re
from copy import deepcopy
from bundle_model_contract import cite


def caption_gene(text):
    direct=re.match(r'^([A-Za-z][A-Za-z0-9.-]{0,39})\s+(?:genotypes|variants|mutations)\b',text)
    if direct:return direct[1]
    relation=re.fullmatch(r'Correlation between ([A-Za-z][A-Za-z0-9.-]{0,39}) variants and '
                          r'[A-Za-z0-9 -]{1,100} in [1-9][0-9]* patients\.',text)
    return relation[1] if relation else None


def table_context_facts(source, scope):
    from pm3_case_semantics import shared_caption
    facts=[]
    for unit,owned in scope.items():
        if owned.get('method')!='SHARED_TABLE_CAPTION_SCOPE':continue
        text=owned['captionText']
        gene=caption_gene(text)
        if not gene:continue
        values=[('geneSymbol',gene)]
        values += [('transcript',v) for v in sorted(set(re.findall(r'NM_[0-9]+\.[1-9][0-9]*',text)))]
        for field,value in values:
            fact={'field':field,'sourceValue':value,'subject':'PROBAND','issues':[],
                  'citation':cite(source,{'unitId':unit,'quote':text}),
                  'ownership':deepcopy(owned),'producer':'SOURCE_NATIVE_TABLE_CONTEXT',
                  'status':'CASE_OWNED_SEMANTICS_PENDING'}
            if shared_caption(fact,field):facts.append(fact)
    return facts
