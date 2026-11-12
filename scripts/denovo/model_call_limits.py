"""Explicit cumulative-call extensions; never reset money, failures or caches.

The original configuration remains the cache identity. A source-bound ledger
extension changes only its cumulative call ceiling, not the task/request limits.
"""
from pathlib import Path
import time
from model_literature_contract import canonical,digest,parse


def effective_call_limit(config, state, root):
    from model_transport import bounded,require,public_config
    if config.get('cumulativeLimitPolicy') == 'TRACK_ONLY':
        require(config.get('maxCalls') is None and config.get('budgetRmb') is None
                and not state.get('callLimitExtensions'), 'TRACK_ONLY_LIMITS_MUST_BE_NULL')
        return float('inf')
    limit=config['maxCalls'];history=state.get('callLimitExtensions',[])
    require(type(history) is list and len(history)<=20,'CALL_EXTENSION_HISTORY_INVALID')
    for index,entry in enumerate(history):
        require(type(entry.get('newLimit')) is int and entry.get('previousLimit')==limit
                and limit<entry['newLimit']<=1000 and entry['newLimit']-limit<=100
                and entry.get('authorization')=='USER_AUTHORIZED_CALL_LIMIT_EXTENSION'
                and entry.get('configurationSha256')==digest(canonical(public_config(config))), 'CALL_EXTENSION_INVALID')
        pin=entry.get('previousBudgetSha256','')
        import re
        require(re.fullmatch('[a-f0-9]{64}',pin) is not None,'CALL_EXTENSION_PIN_INVALID')
        raw=bounded(Path(root)/'call-limit-extensions'/(pin+'.json'),262144)
        require(digest(raw)==pin,'CALL_EXTENSION_ARCHIVE_CHANGED')
        prior=parse(raw);calls=prior.get('calls',[])
        require(prior.get('configurationSha256')==entry['configurationSha256']
                and prior.get('callLimitExtensions',[])==history[:index]
                and state.get('calls',[])[:len(calls)]==calls
                and digest(canonical(calls))==entry['retainedCallsSha256']
                and sum(c['chargedOrReservedMicroCredits'] for c in calls)==entry['retainedMicroCredits'],
                'CALL_EXTENSION_HISTORY_CHANGED')
        limit=entry['newLimit']
    return limit


def extend(config, root, expected_sha, new_limit, *, authorization, reason):
    from model_transport import bounded,require,public_config,exclusive,atomic
    require(authorization=='USER_AUTHORIZED_CALL_LIMIT_EXTENSION' and isinstance(reason,str)
            and 20<=len(reason)<=2000,'CALL_EXTENSION_AUTHORIZATION_REQUIRED')
    root=Path(root)
    with exclusive(root):
        path=root/'budget.json';raw=bounded(path,262144);state=parse(raw)
        require(digest(raw)==expected_sha,'BUDGET_CHANGED_SINCE_REVIEW')
        require(state['configurationSha256']==digest(canonical(public_config(config))),'BUDGET_CONFIGURATION_CHANGED')
        limit=effective_call_limit(config,state,root)
        require(type(new_limit) is int and limit<new_limit<=1000 and new_limit-limit<=100,'CALL_EXTENSION_LIMIT_INVALID')
        calls=state['calls']
        require(all(c.get('status') in {'SUCCESS','FAILED_RESERVED'} and type(c.get('chargedOrReservedMicroCredits')) is int
                    and c['chargedOrReservedMicroCredits']>=0 for c in calls),'UNSETTLED_CALL_REQUIRES_RECONCILIATION')
        require(len(state.get('callLimitExtensions',[]))<20,'CALL_EXTENSION_HISTORY_INVALID')
        entry={'previousBudgetSha256':expected_sha,'configurationSha256':state['configurationSha256'],
               'previousLimit':limit,'newLimit':new_limit,'authorization':authorization,'reason':reason,
               'retainedCallsSha256':digest(canonical(calls)),
               'retainedMicroCredits':sum(c['chargedOrReservedMicroCredits'] for c in calls),'createdAt':time.time()}
        archive=root/'call-limit-extensions';archive.mkdir(exist_ok=True)
        backup=archive/(expected_sha+'.json')
        require(not backup.exists() or backup.read_bytes()==raw,'CALL_EXTENSION_ARCHIVE_CHANGED')
        backup.write_bytes(raw)
        state.setdefault('callLimitExtensions',[]).append(entry)
        effective_call_limit(config,state,root)
        atomic(path,state)
        return {'status':'CALL_LIMIT_EXTENDED','previousLimit':limit,'newLimit':new_limit,
                'retainedCalls':len(calls),'retainedMicroCredits':entry['retainedMicroCredits'],
                'stoppedReason':state.get('stoppedReason'),'moneyBudgetChanged':False,'networkCalls':0}
