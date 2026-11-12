"""One bounded, diagnosed quote-format repair; retains all charged reservations.

No quote is truncated or accepted locally. Only the model can submit a new
proposal, which must pass the same schema and source checks as any other result.
Unknown transport charges, wrong models and other schema errors are not cleared.
"""
from copy import deepcopy
from pathlib import Path
import time
from model_literature_contract import canonical, digest, parse
from model_transport import bounded, require, exclusive, atomic, public_config, response_usage, units_cost

VERSION='pm3-quote-length-repair-v2'
INSTRUCTION=(' Formatting correction: every citation.quote, including caseCitation, originCitations and '
             'sourceAssessments, must contain at most 600 characters. Select a shorter EXACT CONTIGUOUS '
             'passage that supports the statement. Do not copy an entire long paragraph; do not paraphrase '
             'or insert ellipses into quotes. Keep sourceValue an exact substring. If no adequate quote '
             'can be provided under this bound, omit the unsupported claim and report incomplete coverage. '
             'This instruction supplies no clinical facts or score.')


def request(original, version=VERSION):
    value=deepcopy(original)
    value['messages'][0]['content']+=INSTRUCTION
    if version==VERSION:
        import re
        content=parse(value['messages'][1]['content']);excerpts=[]
        for unit in content.get('units',[]):
            text=unit.get('text','')
            if len(text)<=600:continue
            # Mechanical source offsets only; keep the complete original unit.
            start=0
            while start<len(text):
                end=min(len(text),start+480)
                if end<len(text):
                    endings=list(re.finditer(r'[.!?]\s+',text[start:end]))
                    if endings:end=start+endings[-1].end()
                excerpts.append({'unitId':unit['unitId'],'start':start,'end':end,'quote':text[start:end]})
                start=end
        content['boundedCitationExcerpts']=excerpts
        value['messages'][1]['content']=canonical(content).decode()
        value['messages'][0]['content']+=' For caseCitation especially, choose ONE boundedCitationExcerpts quote or a shorter exact passage. Never join excerpts. These are source text, not accepted clinical facts.'
    else:require(version=='pm3-quote-length-repair-v1','PM3_REPAIR_VERSION')
    return value


def quote_overflows(candidate, schema):
    issues=[]
    def walk(value,node,path):
        kind=node['type']
        if kind=='object':
            require(type(value) is dict and set(value)==set(node['required']),'PM3_REPAIR_NOT_ONLY_QUOTE_LENGTH')
            for key,child in node['properties'].items():walk(value[key],child,path+'.'+key)
        elif kind=='array':
            require(type(value) is list and len(value)<=node['maxItems'],'PM3_REPAIR_NOT_ONLY_QUOTE_LENGTH')
            for i,item in enumerate(value):walk(item,node['items'],path+f'[{i}]')
        else:
            require(type(value) is str and value.strip() and ('enum' not in node or value in node['enum']),
                    'PM3_REPAIR_NOT_ONLY_QUOTE_LENGTH')
            if len(value)>node['maxLength']:
                require(path.endswith('.quote') and node['maxLength']==600,'PM3_REPAIR_NOT_ONLY_QUOTE_LENGTH')
                issues.append({'path':path,'actualCharacters':len(value),'maximumCharacters':600})
    walk(candidate,schema,'$')
    require(bool(issues),'PM3_REPAIR_QUOTE_OVERFLOW_REQUIRED')
    return issues


def authorize(c,state_dir,original,repaired):
    require(repaired==request(original),'PM3_REPAIR_REQUEST_CHANGED')
    require(original['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum']
            ==['pm3-literature-candidates-v1'],'PM3_REPAIR_PROFILE_REQUIRED')
    root=Path(state_dir)
    with exclusive(root):
        path=root/'budget.json';before=bounded(path,262144);state=parse(before)
        require(state.get('configurationSha256')==digest(canonical(public_config(c))),'BUDGET_CONFIGURATION_CHANGED')
        calls=state.get('calls',[])
        require(calls and all(x.get('status') in ('SUCCESS','FAILED_RESERVED') for x in calls),
                'UNSETTLED_CALL_REQUIRES_RECONCILIATION')
        last=calls[-1]
        import re
        require(isinstance(last.get('key'),str) and re.fullmatch(r'[a-f0-9]{64}',last['key']), 'BUDGET_STATE_INVALID')
        require(state.get('stoppedReason')=='RESPONSE_SCHEMA_INVALID' and last.get('status')=='FAILED_RESERVED'
                and last.get('reasonCode')=='DENOVO_MODEL_RESPONSE_SCHEMA_INVALID' and not state.get('resumePermit'),
                'PM3_REPAIR_SCHEMA_FAILURE_REQUIRED')
        folder=root/'responses'/last['key']
        prior=bounded(folder/'request.json',c['maxRequestBytes'])
        require(prior in (canonical(original),canonical(request(original,'pm3-quote-length-repair-v1'))),'PM3_REPAIR_EXACT_REQUEST_REQUIRED')
        original_pin=digest(canonical(original))
        require(not any(x.get('originalRequestSha256')==original_pin and x.get('schemaVersion')==VERSION for x in state.get('pm3QuoteRepairs',[])),
                'PM3_REPAIR_ALREADY_ATTEMPTED')
        raw=bounded(folder/'response.json',c['maxResponseBytes']);response=parse(raw)
        require(last.get('responseSha256')==digest(raw),'PM3_REPAIR_RESPONSE_CHANGED')
        usage=response_usage(response,c)
        require(units_cost(c,usage['prompt_tokens'],usage['completion_tokens'])<=last['chargedOrReservedMicroCredits'],
                'USAGE_EXCEEDS_RESERVATION')
        choices=response.get('choices',[])
        require(len(choices)==1 and choices[0].get('finish_reason')=='stop'
                and not choices[0]['message'].get('refusal') and not choices[0]['message'].get('tool_calls'),
                'PM3_REPAIR_COMPLETE_RESPONSE_REQUIRED')
        content=choices[0]['message']['content']
        fence=re.fullmatch(r'\s*```json\s*\r?\n([\s\S]*?)\r?\n```\s*',content)
        candidate=parse(fence[1] if fence else content)
        from pm3_literature_contract import SCHEMA
        diagnosis=quote_overflows(candidate,SCHEMA)
        record={'schemaVersion':VERSION,'authorization':'PM3_NEED_DRIVEN_TASK_ONE_FORMAT_REPAIR',
                'previousBudgetSha256':digest(before),'failedCallKey':last['key'],
                'originalRequestSha256':original_pin,'nextRequestSha256':digest(canonical(repaired)),
                'responseSha256':digest(raw),'diagnosis':diagnosis,'at':time.time(),
                'retainedReservationMicroCredits':last['chargedOrReservedMicroCredits'],
                'platformBillReconciled':False}
        record_raw=canonical(record);pin=digest(record_raw)
        archive=root/'continuation-reviews';archive.mkdir(exist_ok=True)
        for name,data in ((digest(before)+'-budget.json',before),(pin+'-pm3-quote-repair.json',record_raw)):
            dest=archive/name
            if dest.exists():require(dest.read_bytes()==data,'PM3_REPAIR_ARCHIVE_CHANGED')
            else:dest.write_bytes(data)
        state.setdefault('pm3QuoteRepairs',[]).append(record)
        state['stoppedReason']=None
        state['resumePermit']={'reviewSha256':pin,'requestSha256s':[record['nextRequestSha256']]}
        atomic(path,state)
        return record
