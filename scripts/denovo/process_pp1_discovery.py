"""PP1 selected-variant LitVar discovery and bounded, resumable source reading.

Search relevance never supplies segregation facts. Each registered paper passes
the same extraction, source review and current-context evaluation as direct
paper work. A terminal source gap is retained separately from unread papers.
"""
from pathlib import Path
from decimal import Decimal, InvalidOperation
import re
import time

from model_literature_contract import canonical, digest, parse
from model_transport import atomic, bounded
from workbench_literature_targets import build_targets
from discover_public_literature import run as discover
from acquire_public_literature import acquire, MAX_PAPERS
from paper_download_transport import DownloadTransport
from literature_discovery_state import load_parent, persist, paper_key
from process_pp1_paper import process, require
from pp1_literature_contract import validate_target
from pp1_reading_loop import recovery_state
from literature_relevance import options as reading_options, screen, order, missing_topics
from pp1_reviewed_cache import discover as local_reviewed_papers

VERSION = 'pp1-discovery-process-v1'

# The same six unfinished system-transfer outcomes retained by the PM3 caller.
# These are not absence or bibliographic/source-identity rejections. The shared
# state writer still owns its schema, deduplication and bounded acquisition.
RETRYABLE_ACQUISITION = frozenset({'PAPER_DOWNLOAD_TIMEOUT', 'PAPER_DOWNLOAD_TRANSPORT_FAILURE',
    'PAPER_DOWNLOAD_HTTP_FAILURE', 'PAPER_DOWNLOAD_WORKER_FAILURE',
    'PAPER_DOWNLOAD_BODY_TRUNCATED', 'PAPER_DOWNLOAD_BODY_SIZE_MISMATCH'})


def acquisition_pending(acquired):
    return {paper_key(p) for p in acquired['papers'] if p.get('reasonCode') in RETRYABLE_ACQUISITION}


def reason(error):
    code = str(error)
    return code if re.fullmatch('[A-Z][A-Z0-9_]{1,160}',code) else 'PP1_DISCOVERY_EXECUTION_FAILED'


def local_stop(evaluation):
    if evaluation.get('status') == 'MET':
        return 'PP1_EVALUABLE_LOWER_BOUND_REACHED'
    required = [need for need in evaluation.get('needs',[]) if need['blockingEffect'] == 'BLOCKS_SCORING']
    if not required or any(need['route'] == 'LITERATURE' for need in required):
        return None
    if evaluation.get('context',{}).get('status') not in (None, 'QUALIFIED'):
        return 'PP1_RULE_CONTEXT_REQUIRES_REVIEW'
    try:
        sufficient = Decimal(evaluation.get('rawSegregationPoints','0')) >= 1
    except InvalidOperation:
        return None
    if sufficient and all(need['route'] == 'BUSINESS_CONTEXT' for need in required):
        return 'PP1_LOCAL_FACTS_READY_FOR_CURRENT_ALLOCATION'
    return None


def run(root, library, cache, budget, model, model_path, target, binding, context,
        resolver, output, *, search_context=None, allow_network=False, allow_model=False,
        seconds=540, maximum_calls=8, maximum_rounds=8, maximum_papers=2,
        continuation=None, prior_artifacts=None, cancelled=lambda: False, priority_options=None):
    validate_target(target)
    require(type(seconds) in (int, float) and 1 <= seconds <= 540, 'PP1_TIME_BUDGET_INVALID')
    require(type(maximum_papers) is int and 1 <= maximum_papers <= MAX_PAPERS, 'PP1_PAPER_BUDGET_INVALID')
    require(type(maximum_calls) is int and 0 <= maximum_calls <= 64, 'PP1_CALL_BUDGET_INVALID')
    output = Path(output); require(not output.exists(), 'NEW_OUTPUT_REQUIRED'); output.mkdir(parents=True)
    allele = binding['selection']['allele']
    projected = {'genomeAssembly':allele['assembly'], 'chromosome':allele['contig'],
                 **{k:allele[k] for k in ('position', 'reference', 'alternate')}}
    request = {'genes':[target['geneSymbol']], 'searchTargets':[{**target, 'allele':projected}],
               'searchMode':'LITVAR_ONLY'}
    if continuation is not None: request['discoveryContinuation'] = continuation
    previous = load_parent(request, root)
    result = {'schemaVersion':VERSION, 'status':'PARTIAL', 'target':target,
              'analysisBindingSha256':digest(canonical(binding)), 'businessPublishable':False,
              'scientificExecution':False, 'modelAttempts':0, 'networkRequests':0,
              'searchPolicy':'LITVAR_ONLY', 'papers':[], 'issues':[], 'wholeLiteratureAbsenceClaim':False}
    manifests, found, acquired, processed = [], None, None, set()
    deadline = time.monotonic() + seconds
    priority_options = reading_options(priority_options)
    priority_options['maximumCalls'] = min(priority_options['maximumCalls'], maximum_calls)

    def save():
        manifest = {'papers':manifests}
        atomic(output/'artifacts.json', manifest)
        result['artifactManifestSha256'] = digest((output/'artifacts.json').read_bytes())
        atomic(output/'result.json', result)

    def remaining():
        require(not cancelled(), 'PP1_USER_CANCELLED')
        left = deadline-time.monotonic(); require(left >= 1, 'PP1_TIME_LIMIT'); return left

    def local_result(items, stop):
        for item,folder in items:
            raw = bounded(folder/'artifacts.json',65536)
            require(digest(raw) == item['artifactManifestSha256'], 'PP1_ARTIFACT_MANIFEST_CHANGED')
            inventory = parse(raw)
            manifests.append({'pmcid':item['pmcid'],'bundleSha256':item['bundleSha256'],'target':item['target'],
                              'acceptedArtifacts':inventory['acceptedArtifacts'],
                              'pedigreeReviews':inventory.get('pedigreeReviews',[])})
        result.update(papers=[item for item, _ in items],status='EVALUATED',scientificExecution=True,stopReason=stop)
        save()
        return result

    save()
    try:
        remaining()
        attempted=set(previous['attemptedPaperKeys']) if previous else set()
        local=local_reviewed_papers(library,budget,target,binding['selection'])
        result['localCacheDiscovery']={k:v for k,v in local.items() if k!='candidates'}
        result['localCacheDiscovery']['papers']=[{'pmcid':p['pmcid'],'bundleSha256':p['localReviewedBundleSha256']} for p in local['candidates']]
        local_rows=[p for p in local['candidates'] if paper_key(p) not in attempted]
        local_checks = result['localCacheDiscovery']['checks'] = []
        evaluated_local = []
        for paper in local_rows[:maximum_papers]:
            pmcid, pin = paper['pmcid'], paper['localReviewedBundleSha256']
            folder = output/'local-first'/('paper-'+pmcid)
            item = process(library,pmcid,pin,target,binding,context,resolver,model,model_path,budget,folder,
                           allow_model=False,seconds=remaining(),maximum_calls=0,maximum_rounds=0,cancelled=cancelled)
            local_checks.append({'pmcid':pmcid,'stopReason':item.get('stopReason'),
                                 'evaluationStatus':item.get('evaluation',{}).get('status')})
            if item.get('status') == 'EVALUATED' and item.get('reading',{}).get('qualification',{}).get('identity'):
                evaluated_local.append((item,folder))
        if len(evaluated_local) == 1:
            stop = local_stop(evaluated_local[0][0]['evaluation'])
            if stop: return local_result(evaluated_local, stop)
        if len(evaluated_local) > 1:
            from pp1_published_contribution import checked_ledger
            from pp1_segregation import evaluate
            qualifications = [item['reading']['qualification'] for item, _ in evaluated_local]
            stop = local_stop(evaluate(qualifications,context,checked_ledger(binding['ledger'],context)))
            if stop: return local_result(evaluated_local, stop)
        targets = build_targets([{**target, 'allele':projected}], root, search_context, output)
        discovery_input = output/'discovery-input.json'
        atomic(discovery_input, {'schemaVersion':'denovo-literature-discovery-input-v1', 'targets':targets})
        if previous and previous['pendingCandidates']:
            found = {**previous['summary'], 'candidates':previous['pendingCandidates'],
                     'queries':previous['checkpoint']['queries'], 'checkpoint':previous['checkpoint'],
                     'networkRequests':0, 'resumeAvailable':not previous['discoveryComplete']}
        else:
            found = discover(discovery_input, output/'discovery', cache, library, online=allow_network,
                    max_pages=20, precision_first=True, litvar=True, search_mode='LITVAR_ONLY',
                    checkpoint=previous['checkpoint'] if previous else None)
            if previous:
                found['candidates'] = [p for p in found['candidates'] if paper_key(p) not in previous['attemptedPaperKeys']]
        merged={paper_key(p):p for p in local_rows}
        for p in found['candidates']:
            key=paper_key(p)
            if key in merged:
                local_row=merged[key];merged[key]={**p,'localReviewedBundleSha256':local_row['localReviewedBundleSha256'],
                    'origins':[*p.get('origins',[]),*local_row['origins']]}
            else:merged[key]=p
        found['candidates']=list(merged.values())
        require(len(found['candidates'])<=200,'PP1_COMBINED_CANDIDATE_LIMIT')
        result['discovery'] = found; result['networkRequests'] = found['networkRequests']; save(); remaining()
        found['candidates'] = screen(found['candidates'], targets, output, ('PP1',), request['searchTargets'],
            model, model_path, budget, result, save, deadline, allow_model, priority_options)
        source = output/'acquisition-input.json'
        atomic(source, {'schemaVersion':'denovo-literature-discovery-v1', 'candidates':found['candidates']})
        with DownloadTransport(cache, output/'acquisition-requests', allow_network) as transport:
            acquired = acquire(source, digest(source.read_bytes()), library, transport, output/'acquisition',
                               maximum=priority_options['maximumPrefetch'] if priority_options['enabled'] else maximum_papers, criteria=('PP1',))
        result['acquisition'] = acquired; result['networkRequests'] += acquired['networkRequests']; save()
        queue = [p for p in acquired['papers'] if p['status'] == 'REGISTERED']; missing = set()
        deep_limit = min(maximum_papers, priority_options['maximumDeepRead']) if priority_options['enabled'] else maximum_papers
        while queue and len(processed) < deep_limit:
            queue = order(queue, result.get('readingPriority', {}), missing, 4-len(processed)%5); paper = queue.pop(0)
            left = remaining(); pmcid = paper['pmcid']; pin = paper['registration']['bundleSha256']
            folder = output/('paper-'+pmcid)
            figures = []
            try:
                from bundle_model_contract import load
                from pp1_figure_acquisition import acquire_figures
                original = load(library, pmcid, pin, criterion='PP1')
                with DownloadTransport(cache, output/('figure-requests-'+pmcid), allow_network) as transport:
                    try:
                        figures = acquire_figures(library, original, transport, output/('figures-'+pmcid))
                    finally:
                        result['networkRequests'] += transport.requests
                        result.setdefault('figureRetrievals', []).extend(transport.events)
            except (ValueError, OSError, KeyError, TypeError) as error:
                figures.append({'status': 'UNAVAILABLE', 'reasonCode': reason(error)})
            left = remaining()
            try:
                item = process(library, pmcid, pin, target, binding, context, resolver, model, model_path,
                    budget, folder, allow_model=allow_model, seconds=left,
                    maximum_calls=maximum_calls-result['modelAttempts'], maximum_rounds=maximum_rounds,
                    accepted_artifacts=(prior_artifacts or {}).get((pmcid,pin), ()), cancelled=cancelled)
                raw = bounded(folder/'artifacts.json', 65536)
                require(digest(raw) == item['artifactManifestSha256'], 'PP1_ARTIFACT_MANIFEST_CHANGED')
                manifests.append({'pmcid':pmcid, 'bundleSha256':pin, 'target':item['target'],
                                  'acceptedArtifacts':parse(raw)['acceptedArtifacts'],
                                  'pedigreeReviews':parse(raw).get('pedigreeReviews',[])})
                result['modelAttempts'] += item['modelAttempts']
                result['scientificExecution'] |= item['scientificExecution']
            except (ValueError, OSError, KeyError, TypeError, TimeoutError) as error:
                item = {'schemaVersion':'pp1-paper-process-v1', 'pmcid':pmcid, 'bundleSha256':pin,
                        'target':target, 'status':'PARTIAL', 'stopReason':reason(error),
                        'modelAttempts':0, 'businessPublishable':False}
            item['figureRegistration'] = figures
            result['papers'].append(item); processed.add(pmcid); save()
            missing.update(missing_topics(item))
            if result.get('readingPriority'):
                result['readingPriority']['remainingQueue'] = [paper_key(p) for p in order(queue, result['readingPriority'], missing)]
                result['readingPriority']['missingTopics'] = sorted(missing)
            # Once a real lower-bound score is available, preserve the remaining
            # scope rather than spending just to seek a stronger score.
            if item.get('evaluation',{}).get('status') == 'MET':
                result['stopReason'] = 'PP1_EVALUABLE_LOWER_BOUND_REACHED'; break
            if item.get('stopReason') in {'PP1_CALL_LIMIT','PP1_TIME_LIMIT','PP1_USER_CANCELLED',
                                         'PP1_MODEL_DISABLED_CACHE_MISSING'}:
                result['stopReason'] = item['stopReason']; break
        if not found['candidates']:
            result['issues'].append('PP1_NO_CANDIDATES_IN_COMPLETED_SEARCH' if not found.get('resumeAvailable',True)
                                    else 'PP1_DISCOVERY_SEARCH_INCOMPLETE')
    except (ValueError, OSError, KeyError, TypeError, TimeoutError) as error:
        result['issues'].append(reason(error)); result['stopReason'] = reason(error)
    if found is not None and acquired is not None:
        # A partial paper may have reached a genuine external knowledge gap;
        # keep it visible, but do not keep re-extracting the same source on every
        # discovery continuation. Reading-budget stops retain its artifacts.
        resumable = {'PP1_CALL_LIMIT','PP1_TIME_LIMIT','PP1_USER_CANCELLED','PP1_MODEL_DISABLED_CACHE_MISSING',
                     'CALL_LIMIT','TIME_LIMIT','ROUND_LIMIT','USER_CANCELLED','MODEL_CACHE_MISSING',
                     'PP1_READING_EXECUTION_FAILED'}
        pending = {paper_key(p) for p in acquired['papers'] if p['status']=='REGISTERED' and p['pmcid'] not in processed}
        pending |= {p['pmcid'] for p in result['papers'] if p.get('stopReason') in resumable
                    or recovery_state(p.get('stopReason'))['resumeAvailable']}
        # Failed downloads have no registered source yet. Retain their exact
        # discovery candidates for one bounded attempt in a later explicit
        # continuation, without labelling them pending registered papers.
        result['discoveryState'] = persist(output, request, found, acquired, previous,
                                           pending | acquisition_pending(acquired))
        result['pendingRegisteredPapers'] = sorted(pending)
        result.setdefault('stopReason', 'PP1_SOURCE_BATCH_FINISHED')
        result['status'] = 'EVALUATED' if result['papers'] and all(p['status']=='EVALUATED' for p in result['papers']) else 'PARTIAL'
    save(); return result
