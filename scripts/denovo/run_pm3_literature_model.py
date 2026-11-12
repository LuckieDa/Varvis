"""Source-bound PM3 extraction and second-pass proposals using the shared budget.
No scientific adoption or scoring. Missing cache never implicitly spends.
"""
import base64
from pathlib import Path
from bundle_model_contract import load, requests as source_requests, implementation, require
from pm3_literature_contract import requests, review, VERSION
from bundle_model_transport import cached, invoke, decode_response, cache_identity
from model_transport import credentials, public_config, https_once
from model_literature_contract import canonical, digest, parse


class ModelAttemptFailure(ValueError):
    def __init__(self,reason,attempted):
        super().__init__(reason);self.model_calls=attempted


def checked_proposal(source,candidate,content,isolate=False):
    try:
        from pm3_literature_contract import review_isolated
        return (review_isolated if isolate else review)(source,candidate,{u['unitId'] for u in content['units']},set(content['workUnit']['targetUnitIds']))
    except ValueError as error:
        # Retain the exact proposal for a bounded correcting review. No rejected
        # citation or assertion becomes a qualified fact through this envelope.
        code=str(error)
        import re
        if not re.fullmatch(r'[A-Z][A-Z0-9_]{1,160}',code):code='PM3_SOURCE_VALIDATION_FAILED'
        return {'status':'SOURCE_CHECK_REJECTED','reasonCode':code,'cases':[],
                'businessPublishable':False,'scientificExecution':False}


def reusable_cache(config, payload, budget, binding):
    """Keep the actual old binding; a compatible replay is not a new receipt.

    Each lookup still requires the exact reconstructed request, public model
    configuration, source/target/chunk and parent. Only explicitly reviewed
    implementation profiles are eligible. No cache-directory search or key read.
    """
    found = cached(config, payload, budget, binding)
    if found is not None: return found, binding
    from bundle_replay_compatibility import reviewed_profiles
    profiles = reviewed_profiles()
    require(len(profiles) <= 256, 'PM3_REPLAY_PROFILE_LIMIT')
    for profile in reversed(profiles):
        if profile == binding['implementation'] or not all(k in profile for k in
                ('pm3_literature_contract.py','run_pm3_literature_model.py')):
            continue
        historical = {**binding, 'implementation':profile}
        found = cached(config, payload, budget, historical)
        if found is not None: return found, historical
        # Extraction has no prior-proposal feedback. An identical legacy
        # request may reuse its receipt, but retains its OLD validation mode.
        if binding['stage']=='EXTRACTION' and historical.get('sourceFeedbackVersion'):
            legacy={k:v for k,v in historical.items() if k!='sourceFeedbackVersion'}
            found=cached(config,payload,budget,legacy)
            if found is not None:return found,legacy
    return None, binding


def run(library, pmcid, pin, target, config, config_path, budget, output,
        chunk_index=0, previous=None, allow_model=False, transport=https_once, _prepared=None,
        allow_quote_repair=False):
    output = Path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    source, base = _prepared if _prepared is not None else (
        load(library, pmcid, pin, criterion='PM3'), None)
    require(source['pmcid'] == pmcid and source['bundleSha256'] == pin, 'PM3_PREPARED_SOURCE_MISMATCH')
    if base is None:
        base = source_requests(source, config['model'], config['maxOutputTokens'], config['maxRequestBytes'])
    require(type(chunk_index) is int and 0 <= chunk_index < len(base), 'CHUNK_INVALID')
    original = None
    if previous is not None:
        original = replay(library, previous)
        require(original['binding']['target'] == target and original['binding']['bundleSha256'] == pin
                and original['binding']['pmcid'] == pmcid and original['binding']['chunkIndex'] == chunk_index
                and original['binding']['stage'] == 'EXTRACTION', 'PM3_REVIEW_PARENT_MISMATCH')
    payload = requests([base[chunk_index]], target, config['maxRequestBytes'],
                       original['candidate'] if original else None,single_cases=True,source=source)[0]
    content = parse(payload['messages'][1]['content'])
    require(content['workWithinLimit'], 'TARGET_WORK_LIMIT_NOT_TRUNCATED')
    binding = {'version':VERSION, 'sourceFeedbackVersion':'pm3-source-feedback-v1', 'pmcid':pmcid, 'bundleSha256':pin, 'target':target,
               'chunkIndex':chunk_index, 'chunkCount':len(base),
               'stage':'SOURCE_REVIEW' if original else 'EXTRACTION',
               'parentSha256':digest(Path(previous).read_bytes()) if original else None,
               'implementation': {**implementation(), **{name:digest(Path(__file__).with_name(name).read_bytes())
                   for name in ('pm3_literature_contract.py','run_pm3_literature_model.py','pm3_pdf_observations.py','pm3_single_case.py','pm3_quote_repair.py')}}}
    found, binding = reusable_cache(config,payload,budget,binding)
    from pm3_quote_repair import request as repair_request, authorize as authorize_repair, VERSION as repair_version
    repaired_payload=repair_request(payload)
    repaired_binding={**binding,'formatRepair':repair_version}
    attempted_calls=0
    if found is None:
        repaired_found,repaired_binding=reusable_cache(config,repaired_payload,budget,repaired_binding)
        if repaired_found is not None:
            found=repaired_found;payload=repaired_payload;binding=repaired_binding
    if found is None:
        if not allow_model: return {'status':'MODEL_CACHE_MISSING', 'modelCalls':0, 'scientificExecution':False}
        _,attempt_key=cache_identity(config,payload,binding)
        _,repair_key=cache_identity(config,repaired_payload,repaired_binding)
        ledger=Path(budget)/'budget.json'
        def own_entries():
            if not ledger.exists():return []
            from model_transport import bounded
            state=parse(bounded(ledger,262144))
            return [item for item in state.get('calls',[]) if item.get('key') in (attempt_key,repair_key)]
        before=own_entries()
        try:
            key=credentials(config,config_path)
            # A reviewed interrupted formatting attempt may authorize exactly
            # the repaired body. Do not first submit the obsolete original body.
            if allow_quote_repair and ledger.exists():
                from model_transport import bounded
                permit=parse(bounded(ledger,262144)).get('resumePermit') or {}
                if digest(canonical(repaired_payload)) in permit.get('requestSha256s',[]) and digest(canonical(payload)) not in permit.get('requestSha256s',[]):
                    payload=repaired_payload;binding=repaired_binding
            try:
                found = invoke(config,key,payload,budget,binding,transport)
            except ValueError as original_error:
                if not allow_quote_repair:raise
                try:authorize_repair(config,budget,payload,repaired_payload)
                except ValueError:raise original_error from None
                payload=repaired_payload;binding=repaired_binding
                found=invoke(config,key,payload,budget,binding,transport)
            attempted_calls=len(own_entries())-len(before)
        except ValueError as error:
            # A timed-out or malformed response may still be charged. Count this
            # exact request's newly reserved attempt, not other concurrent jobs.
            attempted=max(0,len(own_entries())-len(before))
            raise ModelAttemptFailure(str(error),attempted) from None
    raw,receipt,hit = found
    candidate,_ = decode_response(raw,config,VERSION)
    checked = checked_proposal(source,candidate,content,isolate=bool(binding.get('sourceFeedbackVersion')))
    artifact = {'schemaVersion':'pm3-literature-artifact-v1','binding':binding,'request':payload,
                'configuration':public_config(config),'responseBase64':base64.b64encode(raw).decode(),
                'receipt':receipt,'originalExtraction':original,'candidate':candidate,'review':checked,'businessPublishable':False}
    output.mkdir(parents=True,exist_ok=False)
    (output/'artifact.json').write_bytes(canonical(artifact))
    return {'status':'SOURCE_CHECK_REJECTED' if checked['status']=='SOURCE_CHECK_REJECTED' else 'SOURCE_CHECKED_SEMANTICS_UNVERIFIED',
            'reasonCode':checked.get('reasonCode'),'modelCalls':0 if hit else attempted_calls,
            'artifactSha256':digest(canonical(artifact)), 'chunkCount':len(base),'scientificExecution':False}


def replay(library, path):
    from model_transport import bounded
    artifact = parse(bounded(path,8*1024*1024))
    require(artifact.get('schemaVersion') == 'pm3-literature-artifact-v1','PM3_ARTIFACT_SCHEMA')
    return verify(library, artifact)


def verify(library, artifact, depth=0):
    require(depth <= 1, 'PM3_PARENT_DEPTH')
    b=artifact['binding']; source=load(library,b['pmcid'],b['bundleSha256'],criterion='PM3')
    require(b['version']==VERSION,'PM3_ARTIFACT_VERSION')
    expected_impl={**implementation(), **{name:digest(Path(__file__).with_name(name).read_bytes())
        for name in ('pm3_literature_contract.py','run_pm3_literature_model.py','pm3_pdf_observations.py','pm3_single_case.py','pm3_quote_repair.py')}}
    from bundle_replay_compatibility import compatible
    require(all(name in b['implementation'] for name in ('pm3_literature_contract.py','run_pm3_literature_model.py'))
            and compatible(b['implementation'],expected_impl),'ARTIFACT_IMPLEMENTATION_CHANGED')
    c=artifact['configuration']; base=source_requests(source,c['model'],c['maxOutputTokens'],c['maxRequestBytes'])
    require(type(b['chunkIndex']) is int and 0 <= b['chunkIndex'] < len(base) and b['chunkCount']==len(base),'CHUNK_INVALID')
    prior=artifact.get('originalExtraction')
    if b['stage']=='SOURCE_REVIEW':
        require(prior is not None and prior['binding']['stage']=='EXTRACTION','PM3_REVIEW_PARENT_REQUIRED')
        verify(library,prior,depth+1)
        require(digest(canonical(prior))==b['parentSha256'] and all(prior['binding'][k]==b[k]
            for k in ('target','pmcid','bundleSha256','chunkIndex')),'PM3_PARENT_CHANGED')
    else:
        require(b['stage']=='EXTRACTION' and prior is None and b['parentSha256'] is None,'PM3_STAGE_INVALID')
    require(b.get('sourceFeedbackVersion') in (None,'pm3-source-feedback-v1'),'PM3_SOURCE_FEEDBACK_VERSION')
    payload=requests([base[b['chunkIndex']]],b['target'],c['maxRequestBytes'],prior['candidate'] if prior else None,
                     single_cases='pm3_single_case.py' in b['implementation'],
                     source=source if b.get('sourceFeedbackVersion') else None)[0]
    if 'formatRepair' in b:
        require(b['formatRepair'] in ('pm3-quote-length-repair-v1','pm3-quote-length-repair-v2'),'PM3_FORMAT_REPAIR_VERSION')
        from pm3_quote_repair import request as repair_request
        payload=repair_request(payload,b['formatRepair'])
    require(payload==artifact['request'],'PM3_REQUEST_CHANGED')
    from bundle_model_transport import VERSION as transport_version
    receipt=artifact['receipt']
    require(receipt['identity']=={'transportVersion':transport_version,'configuration':c,
        'requestSha256':digest(canonical(payload)),'binding':b},'ARTIFACT_BINDING_MISMATCH')
    raw=base64.b64decode(artifact['responseBase64'],validate=True)
    require(digest(raw)==receipt['responseSha256'],'RESPONSE_PIN_MISMATCH')
    candidate,usage=decode_response(raw,artifact['configuration'],VERSION)
    require(usage==receipt['usage'] and receipt['modelCalls']==receipt['networkCalls']==1
            and receipt['transportKind'] in ('REAL_PROVIDER','SYNTHETIC_TEST'),'RECEIPT_INVALID')
    require(candidate==artifact['candidate'],'PM3_CANDIDATE_CHANGED')
    content=parse(artifact['request']['messages'][1]['content'])
    checked=checked_proposal(source,candidate,content,isolate=bool(b.get('sourceFeedbackVersion')))
    require(checked==artifact['review'],'PM3_REVIEW_CHANGED')
    return artifact
