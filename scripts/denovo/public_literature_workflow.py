"""Explicit asynchronous workbench job: public discovery -> acquisition -> model review.

Only selected gene/HGVS search terms and public PMIDs leave the selection boundary.
Sample IDs, phenotypes, scores, credentials and paths never enter external requests.
"""
import argparse
import time
from pathlib import Path

from model_literature_contract import canonical, digest, parse
from model_transport import bounded, config as model_config, atomic
from discover_public_literature import run as discover
from acquire_public_literature import acquire
from paper_download_transport import DownloadTransport
from bundle_model_contract import require
from continue_literature_paper import process_paper
from literature_discovery_state import load_parent, snapshot, persist, paper_key
from literature_relevance import options as reading_options, screen, order, missing_topics

VERSION = 'denovo-public-literature-workflow-v1'


def safe_error(error):
    if isinstance(error,OSError) and getattr(error,'winerror',None)==206:
        return 'DENOVO_LOCAL_PATH_TOO_LONG'
    text = str(error)
    # PM3 workers share this boundary. Preserve bounded reason codes while
    # continuing to redact exception text, URLs, paths and credentials.
    if text.startswith('PM3_'):
        import re
        return text if re.fullmatch(r'PM3_[A-Z0-9_]{1,156}', text) else 'DENOVO_WORKFLOW_EXECUTION_FAILED'
    return text if text.startswith(('DENOVO_', 'PAPER_', 'LITERATURE_', 'DISCOVERY_', 'CHECKPOINT_')) and text.replace('_', '').isalnum() else 'DENOVO_WORKFLOW_EXECUTION_FAILED'


def run(request_path, output, config_path, remaining=540):
    c = parse(bounded(config_path, 16384))
    require(c.get('schemaVersion') == VERSION and c.get('enabled') is True, 'WORKFLOW_DISABLED')
    extraction_profile = c.get('extractionProfile', 'legacy-v1')
    require(extraction_profile in ('legacy-v1', 'core-material-v1', 'core-material-v2'), 'EXTRACTION_PROFILE_INVALID')
    root = Path(c['projectRoot']).resolve(strict=True)
    context_options = {}
    context_config = None
    require(type(c.get('sourceReview', False)) is bool, 'SOURCE_REVIEW_CONFIG_INVALID')
    context_options['source_review'] = c.get('sourceReview', False)
    need_reading=c.get('needDrivenReading',False)
    require(type(need_reading) is bool,'NEED_READING_CONFIG_INVALID')
    maximum_reading_calls=c.get('maximumReadingCalls',8)
    maximum_reading_chunks=c.get('maximumReadingChunks',8)
    require(type(maximum_reading_calls) is int and 2<=maximum_reading_calls<=32
            and type(maximum_reading_chunks) is int and 1<=maximum_reading_chunks<=64,'NEED_READING_LIMIT_INVALID')
    require(not need_reading or c.get('sourceReview') is True and extraction_profile in ('core-material-v1','core-material-v2')
            and c.get('coreContext') is not None,'NEED_READING_PREREQUISITES_REQUIRED')
    if c.get('coreContext') is not None:
        from core_context_review import load_config
        context_config, context_pin = load_config(root, c['coreContext'])
        context_options['core_context'] = {'root': root, 'config': context_config, 'sha256': context_pin}
    def local(name):
        path = (root / c[name]).resolve()
        require(path.is_relative_to(root / 'local'), 'CONFIG_PATH_OUTSIDE_LOCAL')
        return path
    library, cache, budget, model_path = [local(n) for n in ('library', 'searchCache', 'modelBudget', 'modelConfig')]
    priority_options = reading_options(c.get('readingPriority'))
    allow_model = c.get('allowModel') is True
    mc = model_config(model_path) if allow_model else parse(bounded(model_path, 16384))
    request = parse(bounded(request_path, 65536))
    require(set(request) - {'searchTargets', 'continuation', 'discoveryContinuation', 'searchMode'} == {'schemaVersion', 'genes', 'selectionSha256'} and request['schemaVersion'] == VERSION,
            'WORKFLOW_REQUEST_SCHEMA')
    if need_reading:
        require(isinstance(request.get('searchTargets'),list) and request['searchTargets'],'NEED_READING_SELECTION_REQUIRED')
        context_options['need_reading']={'targets':request['searchTargets'],'contextSpec':c['coreContext'],
                                        'maximumCalls':maximum_reading_calls,'maximumChunks':maximum_reading_chunks}
    search_mode = request.get('searchMode', 'LITVAR_ONLY')
    require(search_mode == 'LITVAR_ONLY' and not (
        request.get('continuation') and request.get('discoveryContinuation')), 'DISCOVERY_MODE_INVALID')
    import re
    require(isinstance(request['genes'], list) and 1 <= len(request['genes']) <= 50 and
            all(isinstance(g, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}', g) for g in request['genes']) and
            re.fullmatch('[a-f0-9]{64}', request['selectionSha256']), 'WORKFLOW_GENES_INVALID')
    output = Path(output); work = output.parent / 'pipeline'
    work.mkdir(exist_ok=False)
    deadline = time.monotonic() + remaining
    result = {'schemaVersion': VERSION, 'selectionSha256': request['selectionSha256'],
              'status': 'RUNNING', 'stage': 'DISCOVERY', 'scientificExecution': False,
              'businessPublishable': False, 'modelCalls': 0, 'modelAttempts': 0,
              'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0,
              'papers': [], 'issues': [], 'networkRequests': 0}
    def save():
        atomic(output, result)
    def time_left():
        require(time.monotonic() < deadline - 2, 'WORKFLOW_TIME_BUDGET')
    found, acquired, previous = None, None, None
    save()
    try:
        parent = request.get('continuation')
        if parent is not None:
            require(isinstance(parent, dict) and set(parent) == {'parentTaskId', 'parentResultSha256', 'pmcid', 'bundleSha256', 'plannedChunks', 'completedTargets'}, 'CONTINUATION_SCHEMA')
            require(all(re.fullmatch('[a-f0-9]{64}', parent.get(k, '')) for k in ('parentTaskId', 'parentResultSha256')), 'CONTINUATION_IDENTITY')
            result['continuationOf'] = {k: parent[k] for k in ('parentTaskId', 'parentResultSha256', 'pmcid', 'bundleSha256')}
            result['discovery'] = {'status': 'NOT_RUN_FIXED_PAPER_CONTINUATION'}
            acquired = {'papers': [{'pmcid': parent['pmcid'], 'status': 'REGISTERED', 'registration': {'bundleSha256': parent['bundleSha256']}}]}
            result['acquisition'] = [{'pmcid': parent['pmcid'], 'status': 'FIXED_BUNDLE_REUSE'}]
        else:
            previous = load_parent(request, root)
            if previous:
                result['discoveryContinuationOf'] = {k: request['discoveryContinuation'][k]
                                                     for k in ('parentTaskId', 'parentResultSha256', 'stateSha256')}
            targets = []
            precise = 'searchTargets' in request
            if precise:
                from workbench_literature_targets import build_targets
                targets = build_targets(request['searchTargets'], root, context_config, work)
                require({t['geneSymbol'] for t in request['searchTargets']} <= set(request['genes']), 'SELECTED_GENE_MISMATCH')
            else:
                for i, gene in enumerate(dict.fromkeys(request['genes'])):
                    raw = canonical({'schemaVersion': 'denovo-local-gene-signals-v1', 'geneSymbol': gene, 'geneAliases': []})
                    path = work / f'gene-{i}.json'; path.write_bytes(raw)
                    targets.append({'targetId': f'gene-{i}', 'sourceKind': 'ANNOTATION_SIGNALS',
                                    'source': {'path': path.name, 'sha256': digest(raw)}})
            discovery_input = work / 'discovery-input.json'
            discovery_input.write_bytes(canonical({'schemaVersion': 'denovo-literature-discovery-input-v1', 'targets': targets}))
            if previous and previous['pendingCandidates']:
                found = {**previous['summary'], 'candidates': previous['pendingCandidates'],
                         'queries': previous['checkpoint']['queries'], 'checkpoint': previous['checkpoint'],
                         'networkRequests': 0, 'resumeAvailable': not previous['discoveryComplete']}
            else:
                found = discover(discovery_input, work / 'discovery', cache, library,
                                 c.get('allowPublicNetwork') is True,
                                 max_pages=min(20, max(3, 3 * len(targets))) if precise else 2,
                                 precision_first=precise, litvar=precise,
                                 search_mode=search_mode, checkpoint=previous['checkpoint'] if previous else None)
                if previous:
                    attempted = set(previous['attemptedPaperKeys'])
                    found['candidates'] = [p for p in found['candidates'] if paper_key(p) not in attempted]
            result.update(stage='READING_PRIORITY', networkRequests=found['networkRequests'],
                          discovery=snapshot(found))
            save(); time_left()
            found['candidates'] = screen(found['candidates'], targets, work, ('PS2', 'PM6'),
                request.get('searchTargets', []), mc, model_path, budget, result, save, deadline,
                allow_model, priority_options)
            result['stage'] = 'ACQUISITION'; save()
            source = work / 'acquisition-input.json'
            atomic(source, {'schemaVersion': 'denovo-literature-discovery-v1', 'candidates': found['candidates']})
            with DownloadTransport(cache, work / 'acquisition-requests', c.get('allowPublicNetwork') is True) as transport:
                acquired = acquire(source, digest(source.read_bytes()), library, transport, work / 'acquisition',
                                   maximum=priority_options['maximumPrefetch'] if priority_options['enabled'] else 2)
            result['networkRequests'] += acquired['networkRequests']
            result['acquisition'] = [{'pmcid': p['pmcid'], 'status': p['status'], 'reasonCode': p.get('reasonCode'),
                                     'cacheReused': p.get('registration', {}).get('reused', False)}
                                     for p in acquired['papers']]
            result['discoveryState'] = persist(work, request, found, acquired, previous)
            save()
        result['stage'] = 'MODEL_REVIEW'; save()
        # Cache reading needs only the public configuration, not credentials or
        # an enabled model. Execution still validates the original enabled config.
        queue = [p for p in acquired['papers'] if p['status'] == 'REGISTERED']
        deep_limit = priority_options['maximumDeepRead'] if priority_options['enabled'] else 2
        missing = set()
        while queue and len(result['papers']) < deep_limit:
            queue = order(queue, result.get('readingPriority', {}), missing, 4-len(result['papers']) % 5)
            paper = queue.pop(0)
            time_left()
            pmcid, pin = paper['pmcid'], paper['registration']['bundleSha256']
            item = process_paper(library, pmcid, pin, mc, model_path, budget, work, result, save, deadline,
                                 allow_model, parent, extraction_profile, **context_options)
            missing.update(missing_topics(item))
            if result.get('readingPriority'):
                result['readingPriority']['remainingQueue'] = [paper_key(p) for p in order(queue, result['readingPriority'], missing)]
                result['readingPriority']['missingTopics'] = sorted(missing)
            if any(i.startswith(('DENOVO_', 'PAPER_', 'LITERATURE_')) for i in item['issues']):
                break  # Shared failures/budget stops do not silently move to another paper.
        result['status'] = 'REVIEW_READY' if result['papers'] and all(
            (p.get('completeTargetExtraction') or p.get('needReading',{}).get('status')=='MATERIAL_READY') and p.get('candidateIntake') and p.get('coreMaterial') and p.get('coreReview') and p.get('coreContext') and
            not any(i.startswith(('DENOVO_', 'PAPER_', 'LITERATURE_')) for i in p['issues'])
            for p in result['papers']) else 'PARTIAL'
        result['issues'].append('CANDIDATE_REVIEW_NOT_SCIENTIFIC_ADOPTION')
    except Exception as error:
        result['status'] = 'PARTIAL'
        result['issues'].append(safe_error(error))
    if found is not None and acquired is not None:
        processed = {p['pmcid'] for p in result['papers']}
        pending_model = {paper_key(p) for p in acquired['papers']
                         if p['status'] == 'REGISTERED' and p['pmcid'] not in processed}
        pending_model |= {p['pmcid'] for p in result['papers'] if set(p.get('issues', [])) & {
            'MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS', 'MODEL_DISABLED_NO_PAID_CALL',
            'SOURCE_REVIEW_BATCH_LIMIT', 'SOURCE_REVIEW_CACHE_MISSING'}}
        result['discoveryState'] = persist(work, request, found, acquired, previous, pending_model)
    result['stage'] = 'FINISHED'
    save()
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('request', 'output', 'config'):
        p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('--remaining-seconds', type=float, default=540)
    a = p.parse_args()
    value = run(a.request, a.output, a.config, min(540, a.remaining_seconds))
    print(canonical({k: value[k] for k in ('status', 'modelCalls', 'scientificExecution')}).decode())
