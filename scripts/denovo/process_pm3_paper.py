"""Bounded PM3 paper extraction/review sequence with resumable shared cache.

This stage produces observations, not a scientific verdict. A model or time
failure preserves completed artifacts and explicitly leaves chunks unfinished.
"""
from pathlib import Path
import time
from bundle_model_contract import load, requests as source_requests, require
from model_transport import atomic, https_once
from run_pm3_literature_model import run
from pm3_observations import collect
from literature_search_transport import filesystem_path

TEMPORARY_FAILURES = frozenset({'DENOVO_MODEL_TIMEOUT', 'DENOVO_MODEL_TRANSPORT_FAILED',
    'DENOVO_MODEL_RATE_LIMITED', 'DENOVO_MODEL_HTTP_500', 'DENOVO_MODEL_HTTP_502',
    'DENOVO_MODEL_HTTP_503', 'DENOVO_MODEL_HTTP_504'})


class _SchedulingDeadlineReached(Exception):
    """Only raised by the local barrier before entering the model worker."""


def process(library, pmcid, pin, target, config, config_path, budget, output,
            allow_model=False, maximum_chunks=32, seconds=540, transport=https_once,
            identity_resolver=None, selected_allele=None, stepwise=False, reading_feedback=None,
            manual_reviews=()):
    require(type(maximum_chunks) is int and 1 <= maximum_chunks <= 256, 'PM3_CHUNK_BUDGET')
    require(type(seconds) in (int,float) and 0 < seconds <= 3600, 'PM3_TIME_BUDGET')
    output = filesystem_path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    source = load(library, pmcid, pin, criterion='PM3')
    local_reader = source.get('publisherReader', False)
    if local_reader:
        require(bool(manual_reviews), 'PM3_PUBLISHER_MANUAL_REVIEW_REQUIRED')
        allow_model = False
    planned=[] if local_reader else source_requests(source, config['model'],config['maxOutputTokens'],config['maxRequestBytes'])
    count = len(planned)
    # Match collect's two artifacts per chunk limit before spending model calls.
    require(count <= 4096, 'PM3_SOURCE_CHUNK_LIMIT')
    from pm3_chunk_priority import order
    processing_order=order(planned,target)
    reading_plan=None
    if stepwise:
        from pm3_reading_plan import plan
        reading_plan=plan(planned,target,pmcid=pmcid,bundle_pin=pin,feedback=reading_feedback)
        preferred=reading_plan['orderedRelevantChunks']
        processing_order=preferred+[i for i in processing_order if i not in preferred]
    output.mkdir(parents=True,exist_ok=False)
    result = {'schemaVersion':'pm3-paper-process-v1','pmcid':pmcid,'bundleSha256':pin,
              'target':target,'chunkCount':count,'completedExtractionChunks':[],
              'completedReviewChunks':[],'status':'RUNNING','issues':[],
              'modelCalls':0,'businessPublishable':False,'scientificExecution':False}
    result['cacheReuse']={'extractionChunks':[],'reviewChunks':[],
                         'method':'EXACT_REQUEST_AND_SOURCE_BOUND_RECEIPT_REVALIDATION','savedScoreAdopted':False}
    checked_manual=[review for _,review in manual_reviews]
    if manual_reviews:
        result['manualReviews']=[receipt for receipt,_ in manual_reviews]
    result['chunkProcessingOrder']=processing_order
    result['schedulingPolicy']='TARGET_VARIANT_MENTIONS_FIRST_REMAINDER_RETAINED'
    artifacts = []; deadline = time.monotonic()+seconds; new_chunks=0
    cache_missing = False
    blocked_reviews=set()
    failed_stages=set()
    stage_checkpoints={}
    paid_work_stopped=False
    def checkpoint(index, stage, status, executed=None, reason=None):
        entry={'chunkIndex':index,'stage':stage.upper(),'status':status}
        if executed is not None:
            entry['modelCalls']=executed['modelCalls']
            if executed.get('artifactSha256'):entry['artifactSha256']=executed['artifactSha256']
        if reason is not None:entry['reasonCode']=reason
        stage_checkpoints[index,stage]=entry
    def save(final=False):
        result['pendingReviewChunks'] = [i for i in range(count) if i not in result['completedReviewChunks']]
        result['processingCheckpoint']={'schemaVersion':'pm3-paper-processing-checkpoint-v1',
            'pmcid':pmcid,'bundleSha256':pin,'target':target,
            'stages':[stage_checkpoints[k] for k in sorted(stage_checkpoints)],
            'newModelWorkStoppedAfterFailure':paid_work_stopped,
            'retryPolicy':'EXPLICIT_CONTINUATION_WITH_EXISTING_SHARED_LEDGER_GATES',
            'savedScoreAdopted':False}
        if reading_plan is not None:
            from pm3_reading_plan import coverage
            result['readingPlan']=coverage(reading_plan,result['completedReviewChunks'])
            result['readingPlan']['blockedReviewChunks']=sorted(blocked_reviews)
            result['readingPlan']['pendingRelevantChunks']=[i for i in result['readingPlan']['pendingRelevantChunks'] if i not in blocked_reviews]
        if artifacts:
            result['observations'] = collect(library,artifacts,checked_manual)
        else:
            from pm3_observations import native_projection
            result['observations'] = native_projection(source,target,count,checked_manual)
        if stepwise and not local_reader:
            # A cited fact can survive an incomplete model extraction, but that
            # does not make its entire target packet fully reviewed.
            result['observations']['allChunksProcessed']=len(result['completedReviewChunks'])==count
        if result['observations']['cases']:
            if final and identity_resolver is not None:
                from pm3_identity import resolve_case
                for case in result['observations']['cases']:
                    case['resolvedObservations']=resolve_case(case,identity_resolver,selected_allele)
            result['identitySupplyStatus']=('ATTEMPTED' if final else 'NOT_YET_RUN') if identity_resolver is not None else 'NOT_CONFIGURED'
        atomic(output/'result.json',result)
    save()
    try:
        # Incremental rounds first reconstruct every valid cache, then spend on
        # the next relevant unresolved chunk only. This prevents a newly filled
        # earlier chunk from dropping old later facts or double-counting them.
        passes=(False,True) if stepwise and allow_model else (False,) if stepwise else (allow_model,)
        if not allow_model and not (Path(budget)/'responses').is_dir():
            cache_missing = True
            for index in processing_order:
                checkpoint(index,'extraction','NOT_EXTRACTED',{'modelCalls':0},'MODEL_CACHE_MISSING')
            passes = ()
        seen=set()
        for spend in passes:
          for index in processing_order:
            # Revalidated cached chunks do not consume the new-work allowance.
            # Otherwise every continuation of a long paper stops at chunk 32.
            if not stepwise and new_chunks>=maximum_chunks and not paid_work_stopped:break
            if stepwise and spend and (new_chunks>=maximum_chunks or index not in reading_plan['orderedRelevantChunks']):continue
            new_work=False
            for stage in ('extraction','review'):
                if (index,stage) in seen or (index,stage) in failed_stages:continue
                if time.monotonic() >= deadline:
                    # This branch proves this stage never entered run(). Other
                    # exceptions, including the same code raised inside run(),
                    # do not prove that the shared ledger has no paid attempt.
                    checkpoint(index,stage,'NOT_EXTRACTED' if stage=='extraction' else 'NOT_REVIEWED',
                               {'modelCalls':0},'DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED')
                    raise _SchedulingDeadlineReached()
                destination = output/f'chunk-{index:04d}-{stage}'
                prior = output/f'chunk-{index:04d}-extraction/artifact.json' if stage=='review' else None
                try:
                    executed = run(library,pmcid,pin,target,config,config_path,budget,destination,
                        chunk_index=index,previous=prior,allow_model=spend and not paid_work_stopped,transport=transport,
                        _prepared=(source,planned),**({'allow_quote_repair':True} if stepwise else {}))
                except (ValueError,OSError,TimeoutError) as error:
                    # One failed packet must not hide unrelated successful cache
                    # artifacts. Stop new paid work, but keep replaying later
                    # packets through the same source/receipt validation gates.
                    from public_literature_workflow import safe_error
                    reason=safe_error(error);calls=getattr(error,'model_calls',0)
                    result['modelCalls']+=calls;new_work=new_work or calls>0
                    if reason not in result['issues']:result['issues'].append(reason)
                    failed_stages.add((index,stage));paid_work_stopped=True
                    checkpoint(index,stage,'TEMPORARY_FAILURE' if reason in TEMPORARY_FAILURES else 'EXECUTION_FAILED',
                               {'modelCalls':calls},reason)
                    result['callAccountingStatus']='CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT'
                    save();break
                result['modelCalls'] += executed['modelCalls']
                new_work=new_work or executed['modelCalls']>0
                if executed['status']=='MODEL_CACHE_MISSING':
                    # A missing earlier chunk cannot erase a completed later
                    # observation. No credentials or transport are used here.
                    cache_missing = True
                    checkpoint(index,stage,'NOT_EXTRACTED' if stage=='extraction' else 'NOT_REVIEWED',executed,'MODEL_CACHE_MISSING')
                    break
                if executed['modelCalls']==0:
                    result['cacheReuse']['extractionChunks' if stage=='extraction' else 'reviewChunks'].append(index)
                artifacts.append(destination/'artifact.json')
                seen.add((index,stage))
                if executed['status']=='SOURCE_CHECK_REJECTED':
                    checkpoint(index,stage,'SOURCE_REJECTED',executed,executed['reasonCode'])
                    result.setdefault('sourceRejectedProposals',[]).append({'chunkIndex':index,'stage':stage,'reasonCode':executed['reasonCode']})
                    if stage=='review':
                        blocked_reviews.add(index)
                        if 'PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED' not in result['issues']:
                            result['issues'].append('PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED')
                        if not stepwise:paid_work_stopped=True
                        # A rejected review cannot qualify the initial proposal,
                        # and must not erase valid receipts for unrelated packets.
                        save();break
                    # A source-invalid initial proposal is retained for the one
                    # explicit model review, never qualified or counted here.
                    save();continue
                if stepwise and stage=='review':
                    from model_literature_contract import parse
                    review=parse((destination/'artifact.json').read_bytes())['review']
                    if review.get('extractionCoverageProposal')!='EXTRACTED_WITHIN_TARGET':
                        checkpoint(index,stage,'EXTRACTION_INCOMPLETE',executed,'PM3_MODEL_TARGET_EXTRACTION_INCOMPLETE')
                        blocked_reviews.add(index)
                        if 'PM3_MODEL_TARGET_EXTRACTION_INCOMPLETE' not in result['issues']:
                            result['issues'].append('PM3_MODEL_TARGET_EXTRACTION_INCOMPLETE')
                        save();break
                result['completedExtractionChunks' if stage=='extraction' else 'completedReviewChunks'].append(index)
                checkpoint(index,stage,'SOURCE_CHECKED',executed)
                save()
            if new_work:new_chunks+=1
        result['status'] = 'SOURCE_REVIEWED_SEMANTICS_PENDING' if len(result['completedReviewChunks'])==count else 'PARTIAL'
        # save() last ran before the final review was appended; derive coverage
        # from completed work instead of that stale intermediate snapshot.
        if len(result['completedReviewChunks']) < count:
            result['issues'].append('MODEL_CACHE_MISSING' if cache_missing else 'PM3_CHUNK_BUDGET_REACHED')
        if stepwise:
            # Missing cache during the preliminary scan is expected when new
            # model work is enabled. Keep errors distinct from a round boundary.
            result['issues']=[i for i in result['issues'] if i not in ('MODEL_CACHE_MISSING','PM3_CHUNK_BUDGET_REACHED')]
            pending=[i for i in reading_plan['orderedRelevantChunks'] if i not in result['completedReviewChunks'] and i not in blocked_reviews]
            if pending:result['issues'].append('PM3_CHUNK_BUDGET_REACHED' if allow_model else 'MODEL_CACHE_MISSING')
    except _SchedulingDeadlineReached:
        result['status']='PARTIAL'
        result['issues'].append('DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED')
        # Preserve any earlier failed-attempt accounting. No new attempt was
        # made at this barrier, but this says nothing about earlier work.
    except (ValueError,OSError,TimeoutError) as error:
        # Public errors are codes only. Transport details/credentials never enter
        # user-visible reports. Cached transport receipts retain call accounting.
        from public_literature_workflow import safe_error
        result['modelCalls']+=getattr(error,'model_calls',0)
        result['status']='PARTIAL';result['issues'].append(safe_error(error))
        result['callAccountingStatus']='CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT'
    save(final=True)
    return result
