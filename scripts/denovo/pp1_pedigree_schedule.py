"""Missing-driven registered public pedigree review; no implicit network fetch."""
from pathlib import Path
import re,time
from model_literature_contract import canonical,digest
from pp1_pedigree import load_figure,run,retain_review


def next_figure(library,source,qualification,evaluation,attempted):
    if evaluation['status']=='MET':return None
    if not any(n['reasonCode'] in {'PP1_EXPERT_COUNT_RELATIONSHIP_UNRESOLVED',
            'PP1_MEIOSIS_ORIGIN_UNRESOLVED','PP1_MEIOSIS_INDEX_CONNECTION_UNRESOLVED',
            'PP1_MEMBER_EVENT_INPUT_INCOMPLETE','PP1_NO_QUALIFIED_INFORMATIVE_EVENT'} for n in evaluation['needs']):return None
    root=Path(library)/'papers'/source['pmcid']/'pedigree-figures'
    for figure in sorted(root.glob('*/source.json'))[:8]:
        try:record,_,_=load_figure(source,figure)
        except (ValueError,OSError,KeyError):continue
        for family in qualification.get('families',{}).values():
            label=family['sourceFamilyLabel']
            key=digest(canonical([record,label]))
            if key not in attempted:return {'key':key,'figure':figure,'family':label}
    return None


def review(item,source,target,config,config_path,budget,output,*,execute,deadline,
           remaining_calls,transport,cancelled,on_call):
    # Always try exact cached responses first; model-disabled never transmits.
    result=run(source,item['figure'],item['family'],target,config,config_path,budget,
               Path(output)/'cached',execute=False,transport=transport)
    if result['status']=='VISUAL_CACHE_MISSING':
        if not execute:return {'status':'PENDING','reasonCode':'PP1_PEDIGREE_MODEL_DISABLED_CACHE_MISSING'}
        if cancelled():return {'status':'PENDING','reasonCode':'USER_CANCELLED'}
        if remaining_calls<2:return {'status':'PENDING','reasonCode':'PP1_PEDIGREE_PAIR_CALL_CAPACITY'}
        if time.monotonic()+2*(config['timeoutSeconds']+5)>=deadline:
            return {'status':'PENDING','reasonCode':'PP1_PEDIGREE_PAIR_TIME_REQUIRED'}
        def observed(*args):
            if cancelled():raise ValueError('PP1_USER_CANCELLED')
            if time.monotonic()>=deadline:raise ValueError('PP1_TIME_LIMIT')
            on_call()
            return transport(*args)
        result=run(source,item['figure'],item['family'],target,config,config_path,budget,
                   Path(output)/'model',execute=True,transport=observed)
    graph,path=retain_review(source,item['figure'],result['pairPath'])
    return {'status':'VERIFIED','figureId':graph['figureId'],'familyLabel':graph['familyLabel'],
            'parentEdges':len(graph['parentEdges']),'pairSha256':digest(path.read_bytes()),
            'modelAttempts':result['modelAttempts'],'issues':graph['issues']}
