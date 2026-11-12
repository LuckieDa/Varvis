"""LitVar-only discovery and PM3-only acquisition/extraction for one selected allele.

Retrieval relevance never proves case identity. Pending papers/chunks are retained,
not reported as absent evidence. Shared transports and model ledger remain in use.
"""
from pathlib import Path
import time
from model_literature_contract import canonical,digest
from model_transport import atomic
from bundle_model_contract import require
from pm3_literature_contract import validate_target
from workbench_literature_targets import build_targets
from discover_public_literature import run as discover
from acquire_public_literature import acquire
from paper_download_transport import DownloadTransport
from process_pm3_paper import process
from public_literature_workflow import safe_error
from literature_discovery_state import persist,paper_key,load_parent
from literature_relevance import options as reading_options, screen, order, missing_topics
from pm3_local_papers import discover as local_papers

# These outcomes concern an unfinished system transfer, not a biological absence
# or a source-identity rejection. Keep the exact candidate in the existing
# continuation state; each bounded round still attempts acquisition only once.
RETRYABLE_ACQUISITION = frozenset({'PAPER_DOWNLOAD_TIMEOUT', 'PAPER_DOWNLOAD_TRANSPORT_FAILURE',
    'PAPER_DOWNLOAD_HTTP_FAILURE', 'PAPER_DOWNLOAD_WORKER_FAILURE',
    'PAPER_DOWNLOAD_BODY_TRUNCATED', 'PAPER_DOWNLOAD_BODY_SIZE_MISMATCH'})


def acquisition_pending(acquired):
    return {paper_key(p) for p in acquired['papers'] if p.get('reasonCode') in RETRYABLE_ACQUISITION}


def run(root,library,cache,budget,model,model_path,target,allele,output,
        allow_network=False,allow_model=False,context=None,identity=None,seconds=540,continuation=None,maximum_chunks=32,
        stepwise=False,reading_feedback=None,priority_options=None,local_only=False,reviewed=None):
    validate_target(target)
    require(type(seconds) in (int,float) and 0<seconds<=540,'PM3_TIME_BUDGET')
    require(type(maximum_chunks) is int and 1<=maximum_chunks<=256,'PM3_CHUNK_BUDGET')
    output=Path(output);require(not output.exists(),'NEW_OUTPUT_REQUIRED');output.mkdir(parents=True)
    result={'schemaVersion':'pm3-discovery-process-v1','status':'RUNNING','target':target,
            'scientificExecution':False,'businessPublishable':False,'modelCalls':0,'networkRequests':0,
            'papers':[],'issues':[],'searchPolicy':'LITVAR_ONLY'}
    projected={'genomeAssembly':allele['assembly'],'chromosome':allele['contig'],
               **{k:allele[k] for k in ('position','reference','alternate')}}
    state_request={'genes':[target['geneSymbol']],'searchTargets':[{**target,'allele':projected}],
                   'searchMode':'LITVAR_ONLY'}
    if local_only:
        return process_local(library,target,allele,output,result,model,model_path,budget,
                             identity,seconds,maximum_chunks,reviewed or {})
    if continuation is not None:state_request['discoveryContinuation']=continuation
    previous=load_parent(state_request,root)
    found=acquired=None
    deadline=time.monotonic()+seconds
    priority_options=reading_options(priority_options)
    def save():atomic(output/'result.json',result)
    def remaining():
        left=deadline-time.monotonic();require(left>2,'PM3_TIME_BUDGET_EXHAUSTED');return left
    save()
    try:
        targets=build_targets([{**target,'allele':projected}],root,context,output)
        discovery_input=output/'discovery-input.json'
        atomic(discovery_input,{'schemaVersion':'denovo-literature-discovery-input-v1','targets':targets})
        if previous and previous['pendingCandidates']:
            found={**previous['summary'],'candidates':previous['pendingCandidates'],
                   'queries':previous['checkpoint']['queries'],'checkpoint':previous['checkpoint'],
                   'networkRequests':0,'resumeAvailable':not previous['discoveryComplete']}
        else:
            found=discover(discovery_input,output/'discovery',cache,library,online=allow_network,
                           max_pages=20,precision_first=True,litvar=True,search_mode='LITVAR_ONLY',
                           checkpoint=previous['checkpoint'] if previous else None)
            if previous:
                found['candidates']=[p for p in found['candidates'] if paper_key(p) not in previous['attemptedPaperKeys']]
        local=local_papers(library,target)
        result['localCacheDiscovery']={k:v for k,v in local.items() if k!='candidates'}
        result['localCacheDiscovery']['papers']=[{'pmcid':p['pmcid'],
            'bundleSha256':p['localReviewedBundleSha256']} for p in local['candidates']]
        attempted=set(previous['attemptedPaperKeys']) if previous else set()
        merged={paper_key(p):p for p in local['candidates'] if paper_key(p) not in attempted}
        for candidate in found['candidates']:
            key=paper_key(candidate)
            if key in merged:
                cached=merged[key]
                merged[key]={**candidate,'localReviewedBundleSha256':cached['localReviewedBundleSha256'],
                             'origins':[*candidate.get('origins',[]),*cached['origins']]}
            else: merged[key]=candidate
        require(len(merged)<=200,'PM3_COMBINED_CANDIDATE_LIMIT')
        found['candidates']=list(merged.values())
        result['discovery']=found;result['networkRequests']=found['networkRequests'];save();remaining()
        found['candidates']=screen(found['candidates'],targets,output,('PM3',),state_request['searchTargets'],
            model,model_path,budget,result,save,deadline,allow_model,priority_options)
        source=output/'acquisition-input.json'
        atomic(source,{'schemaVersion':'denovo-literature-discovery-v1','candidates':found['candidates']})
        with DownloadTransport(cache,output/'acquisition-requests',allow_network) as transport:
            acquired=acquire(source,digest(source.read_bytes()),library,transport,output/'acquisition',
                maximum=priority_options['maximumPrefetch'] if priority_options['enabled'] else 2,criteria=('PM3',))
        result['acquisition']=acquired;result['networkRequests']+=acquired['networkRequests']
        result['acquisitionRecovery']=[{'pmcid':p.get('pmcid'),'stage':'ACQUISITION',
            'status':'SYSTEM_TRANSFER_INCOMPLETE','reasonCode':p['reasonCode'],
            'retryPolicy':'BOUND_CONTINUATION_WITH_EXISTING_TRANSPORT_BUDGETS',
            'sourceAbsenceProven':False}
            for p in acquired['papers'] if p.get('reasonCode') in RETRYABLE_ACQUISITION]
        result['discoveryState']=persist(output,state_request,found,acquired,previous,
                {paper_key(p) for p in acquired['papers'] if p['status']=='REGISTERED'} | acquisition_pending(acquired))
        save()
        processed=set()
        queue=[p for p in acquired['papers'] if p['status']=='REGISTERED'];missing=set()
        while queue and len(processed)<(priority_options['maximumDeepRead'] if priority_options['enabled'] else 2):
            queue=order(queue,result.get('readingPriority',{}),missing,4-len(processed)%5);paper=queue.pop(0)
            left=remaining();pmcid=paper['pmcid']
            item=process(library,pmcid,paper['registration']['bundleSha256'],target,model,model_path,budget,
                         output/('paper-'+pmcid),allow_model=allow_model,seconds=left,
                         identity_resolver=identity,selected_allele=allele,maximum_chunks=maximum_chunks,
                         manual_reviews=(reviewed or {}).get(pmcid,{}).get(paper['registration']['bundleSha256'],()),
                         **({'stepwise':True,'reading_feedback':next((p for p in (reading_feedback or [])
                             if p.get('pmcid')==pmcid),None)} if stepwise else {}))
            result['papers'].append(item);result['modelCalls']+=item['modelCalls'];processed.add(pmcid);save()
            missing.update(missing_topics(item))
            if result.get('readingPriority'):
                result['readingPriority']['remainingQueue']=[paper_key(p) for p in order(queue,result['readingPriority'],missing)]
                result['readingPriority']['missingTopics']=sorted(missing)
            if item['status']=='PARTIAL' and not stepwise:break
        result['pendingRegisteredPapers']=[p['pmcid'] for p in acquired['papers'] if p['status']=='REGISTERED' and p['pmcid'] not in processed]
        attempted={p['pmcid'] for p in acquired['papers'] if p.get('reasonCode')!='PAPER_ACQUISITION_PAPER_BUDGET'}
        result['pendingDiscoveryCandidates']=[p for p in found['candidates'] if p.get('pmcid') not in attempted]
        result['status']='SOURCE_REVIEWED_SEMANTICS_PENDING' if result['papers'] and not result['pendingRegisteredPapers'] and not result['pendingDiscoveryCandidates'] and not found.get('resumeAvailable',True) and all(p['status']=='REGISTERED' for p in acquired['papers']) and all(p['status']=='SOURCE_REVIEWED_SEMANTICS_PENDING' for p in result['papers']) else 'PARTIAL'
        if not found['candidates']:result['issues'].append('PM3_NO_CANDIDATES_IN_COMPLETED_SEARCH' if not found.get('resumeAvailable',True) and all(t.get('status')=='NO_HITS_IN_EXECUTED_QUERIES' for t in found.get('targets',[])) else 'PM3_DISCOVERY_NO_CANDIDATES_SEARCH_INCOMPLETE')
    except (ValueError,OSError,KeyError,TypeError,TimeoutError) as error:
        result['status']='PARTIAL';result['issues'].append(safe_error(error))
    if found is not None and acquired is not None:
        complete={p['pmcid'] for p in result['papers'] if p['status']=='SOURCE_REVIEWED_SEMANTICS_PENDING'
                  or stepwise and p.get('readingPlan') is not None
                  and not p['readingPlan']['pendingRelevantChunks'] and not p['issues']}
        pending={paper_key(p) for p in acquired['papers'] if p['status']=='REGISTERED' and p['pmcid'] not in complete}
        result['discoveryState']=persist(output,state_request,found,acquired,previous,pending | acquisition_pending(acquired))
    save();return result


def process_local(library,target,allele,output,result,model,model_path,budget,identity,seconds,maximum_chunks,reviewed):
    """Recheck registered source bundles and receipts without opening a transport."""
    local=local_papers(library,target)
    pins={row['pmcid']:row['localReviewedBundleSha256'] for row in local['candidates']}
    issues=list(local['issues'])
    for pmcid, bundles in reviewed.items():
        if len(bundles)!=1 or (pmcid in pins and pins[pmcid] not in bundles):
            issues.append({'pmcid':pmcid,'reasonCode':'PM3_LOCAL_BUNDLE_AMBIGUOUS'})
            pins.pop(pmcid,None)
        else:
            pins[pmcid]=next(iter(bundles))
    result['localCacheDiscovery']={'issues':issues,'complete':not issues}
    result['localFirstPass']=True
    result['status']='PARTIAL'
    deadline=time.monotonic()+seconds
    ordered=sorted(pins,key=lambda pmcid:(pmcid not in reviewed,pmcid))
    for pmcid in ordered[:20]:
        remaining=deadline-time.monotonic()
        if remaining<=2:break
        pin=pins[pmcid]
        try:
            item=process(library,pmcid,pin,target,model,model_path,budget,output/('paper-'+pmcid),
                         allow_model=False,seconds=remaining,identity_resolver=identity,selected_allele=allele,
                         maximum_chunks=maximum_chunks,stepwise=True,
                         manual_reviews=reviewed.get(pmcid,{}).get(pin,()))
            result['papers'].append(item)
        except (ValueError,OSError,KeyError,TypeError) as error:
            issues.append({'pmcid':pmcid,'reasonCode':safe_error(error)})
    if len(ordered)>20 or deadline-time.monotonic()<=2:
        issues.append({'reasonCode':'PM3_LOCAL_READING_LIMIT'})
    result['localCacheDiscovery']['complete']=not issues
    if issues:result['issues'].append('PM3_LOCAL_SOURCES_INCOMPLETE')
    atomic(output/'result.json',result)
    return result
