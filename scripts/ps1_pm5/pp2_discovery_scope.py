"""Replay a declared LitVar discovery scope from exact saved HTTP bytes only.

COMPLETE describes retrieval of the declared targets, aliases and metadata. It
never establishes article/allele identity, clinical causality, PP2 eligibility,
or an absence of support. No network, acquisition, model, or curation runs here.
"""
from collections.abc import Mapping
from copy import deepcopy
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from discover_public_literature import (discover, implementation_pin, make_plan,
                                      targets_from)
from literature_search_transport import MAX_BODY, MAX_REQUESTS, endpoint
from prepare_literature import sha
from select_public_papers import bounded

VERSION = 'pp2-discovery-scope-replay-v1'
MAX_BATCHES = 256
MAX_RESPONSES = 8192
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_CANDIDATES = 8192
MAX_REPLAY_OPERATIONS = 32768


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode('utf-8')


def require(value, reason):
    if not value:
        raise ValueError('PP2_DISCOVERY_' + reason)


def failure(error):
    # Do not copy local paths or arbitrary exception text into the ledger.
    code = str(error)
    return code if code and len(code) <= 180 and all(
        c.isupper() or c.isdigit() or c == '_' for c in code) else 'PP2_DISCOVERY_REPLAY_FAILED'


class FrozenTransport:
    """The existing endpoint builder/decoder, with no network fallback."""

    def __init__(self, responses):
        self.responses = dict(responses)
        # Existing discovery counts *network* requests, not cached reads. All
        # frozen responses are local; a >20-alias plan must remain replayable.
        self.requests = 0
        self.replay_operations = 0
        self.events = []

    def get(self, query, cursor='*'):
        return self._get('europepmc', query, cursor)

    def get_litvar(self, operation, value):
        return self._get('litvar', value, operation)

    def _get(self, source, query, cursor):
        url, decoder = endpoint(source, query, cursor)
        event = {'source': source, 'url': url, 'query': query, 'cursor': cursor,
                 'operation': cursor if source == 'litvar' else 'search'}
        self.events.append(event)
        try:
            if self.replay_operations >= MAX_REPLAY_OPERATIONS:
                raise ValueError('LITERATURE_SEARCH_FROZEN_REPLAY_OPERATION_LIMIT')
            self.replay_operations += 1
            if url not in self.responses:
                raise ValueError('LITERATURE_SEARCH_FROZEN_RESPONSE_MISSING')
            raw = self.responses[url]
            event.update(sha256=sha(raw), bytes=len(raw))
            decoder(raw)
            event['status'] = 'REPLAYED'
            return raw
        except (ValueError, OSError, TypeError, KeyError) as error:
            event.update(status='UNAVAILABLE', reasonCode=failure(error))
            raise ValueError(event['reasonCode']) from error


def _responses(responses):
    require(isinstance(responses, Mapping) and len(responses) <= MAX_RESPONSES,
            'RESPONSE_SET_INVALID')
    result, total = {}, 0
    for url, raw in responses.items():
        require(isinstance(url, str) and 0 < len(url) <= 16384
                and not any(ord(c) < 32 for c in url), 'RESPONSE_URL_INVALID')
        require(isinstance(raw, bytes) and len(raw) <= MAX_BODY, 'RESPONSE_BYTES_INVALID')
        require(url not in result or result[url] == raw, 'RESPONSE_URL_CONFLICT')
        result[url] = raw
        require(len(result) <= MAX_RESPONSES, 'RESPONSE_SET_INVALID')
        total += len(raw)
        require(total <= MAX_TOTAL_BYTES, 'RESPONSE_SET_BYTE_LIMIT')
    return result


def _progress(checkpoint):
    return sha(canonical({'queries': [{k: q.get(k) for k in (
        'queryId', 'executionComplete', 'cursor', 'seenResultIds',
        'unqueriedAliases', 'litvarVariantIds', 'referenceLinks')}
        for q in checkpoint.get('queries', [])],
        'deferredCandidates': checkpoint.get('deferredCandidates', [])}))


def _candidate(candidate):
    # Metadata is a list of material to review, never an adopted case. Original
    # response bytes remain in the caller's shared archive, not per-target blobs.
    return {key: deepcopy(candidate.get(key)) for key in (
        'source', 'sourceId', 'pmid', 'pmcid', 'doi', 'title', 'flags',
        'qualification', 'matches', 'origins')}


def replay(discovery_input_path, responses, *, max_batches=MAX_BATCHES):
    """Return an operational ledger; never return a scientific SourceReview.

    ``responses`` maps an exact URL to its immutable original bytes. Callers
    building that mapping from multiple events must reject conflicting bytes
    for the same URL before calling; a dict cannot recover overwritten entries.
    All batches start at the real plan and use only internally derived
    checkpoints. The caller cannot inject a completed checkpoint or query flag.
    """
    result = {'schemaVersion': VERSION, 'status': 'INCOMPLETE',
              'scopeMeaning': 'DECLARED_TARGET_ALIAS_METADATA_RETRIEVAL_ONLY',
              'completeLiteratureSearch': False, 'scientificReviewComplete': False,
              'scientificAdoption': False, 'businessPublishable': False,
              'networkRequests': 0, 'fullTextDownloads': 0, 'modelCalls': 0,
              'targetSourcePins': [], 'targets': [], 'queryReplay': [],
              'usedResponses': [], 'candidates': [], 'batches': [],
              'remaining': [], 'errors': []}
    try:
        require(type(max_batches) is int and 1 <= max_batches <= MAX_BATCHES,
                'BATCH_BUDGET_INVALID')
        method = implementation_pin()
        method['pp2_discovery_scope.py'] = sha(Path(__file__).read_bytes())
        result['methodPins'] = method
        result['methodSha256'] = sha(canonical(method))
        frozen = _responses(responses)
        response_pins = [{'url': url, 'sha256': sha(raw), 'bytes': len(raw)}
                         for url, raw in sorted(frozen.items())]
        result['responseSetSha256'] = sha(canonical(response_pins))
        result['responsePins'] = response_pins
        path = Path(discovery_input_path).resolve(strict=True)
        result['inputSha256'] = sha(bounded(path, 1024 * 1024))
        targets = targets_from(path)
        require(sha(bounded(path, 1024 * 1024)) == result['inputSha256'],
                'INPUT_CHANGED_DURING_REPLAY')
        plans = make_plan(targets)
        require(bool(targets) and bool(plans), 'EMPTY_PLAN')
        # Exact identity used by the existing checkpoint implementation.
        result['targetSha256'] = sha(json.dumps(targets, sort_keys=True,
                                              separators=(',', ':')).encode())
        result['plannedQueryIds'] = [q['queryId'] for q in plans]
        result['targetSourcePins'] = [{k: deepcopy(t[k]) for k in (
            'targetId', 'sourceKind', 'sourceSha256', 'geneSymbol',
            'variantAliases', 'externalScope', 'sourceIssues')} for t in targets]
        result['limits'] = {'maxBatches': max_batches,
                            'maxMetadataPagesPerBatch': MAX_REQUESTS,
                            'maxReplayOperationsPerBatch': MAX_REPLAY_OPERATIONS,
                            'maxResponseBytes': MAX_BODY,
                            'maxTotalResponseBytes': MAX_TOTAL_BYTES,
                            'maxCandidates': MAX_CANDIDATES}
        checkpoint, previous, seen_candidates, used = None, None, {}, {}
        final = None
        for batch in range(1, max_batches + 1):
            transport = FrozenTransport(frozen)
            final = discover(targets, transport, precision_first=True, litvar=True,
                             checkpoint=checkpoint)
            checkpoint = final['checkpoint']
            current = _progress(checkpoint)
            result['batches'].append({'batch': batch,
                'resumeAvailable': final['resumeAvailable'],
                'replayOperations': transport.replay_operations,
                'metadataPagesRead': sum(q['pagesRead'] for q in final['queries'])
                    - sum(q['pagesRead'] for q in result['queryReplay']),
                'events': transport.events,
                'checkpointSha256': sha(canonical(checkpoint))})
            for event in transport.events:
                # A malformed original was still observed and must remain
                # archivable; its failed status prevents completion.
                if 'sha256' in event:
                    used[(event['url'], event['sha256'])] = deepcopy(event)
            for candidate in final['candidates']:
                item = _candidate(candidate)
                seen_candidates.setdefault(sha(canonical(item)), item)
            if len(seen_candidates) > MAX_CANDIDATES:
                result['errors'].append('PP2_DISCOVERY_CANDIDATE_LEDGER_LIMIT')
                break
            if not final['resumeAvailable']:
                break
            # Frozen bytes cannot improve a stationary checkpoint. Budget
            # exhaustion with forward progress is resumed in a fresh batch.
            if current == previous:
                result['errors'].append('PP2_DISCOVERY_REPLAY_NO_PROGRESS')
                break
            previous = current
            result['queryReplay'] = deepcopy(final['queries'])
        result['usedResponses'] = sorted(used.values(), key=lambda e: e['url'])
        result['candidates'] = list(seen_candidates.values())[:MAX_CANDIDATES]
        result['candidateCount'] = len(result['candidates'])
        result['unqualifiedCandidateCount'] = len(result['candidates'])
        result['queryReplay'] = deepcopy(final['queries'])
        result['checkpoint'] = deepcopy(checkpoint)
        result['targets'] = deepcopy(final['targets'])
        for target, summary in zip(targets, result['targets']):
            queries = [q for q in final['queries'] if target['targetId'] in q['targetIds']]
            summary['targetSourceSha256'] = target['sourceSha256']
            summary['plannedQueryIds'] = [q['queryId'] for q in plans if target['targetId'] in q['targetIds']]
            summary['queryIds'] = [q['queryId'] for q in queries]
            summary['finishedQueryIds'] = [q['queryId'] for q in queries if _complete(q)]
            if not queries:
                result['remaining'].append({'targetId': target['targetId'],
                                             'reasonCode': 'PP2_DISCOVERY_TARGET_WITHOUT_PLAN'})
            for issue in target.get('sourceIssues', []):
                result['remaining'].append({'targetId': target['targetId'],
                    'reasonCode': 'PP2_DISCOVERY_TARGET_SOURCE_ISSUE', 'sourceIssue': issue})
        for query in final['queries']:
            if not _complete(query):
                result['remaining'].append({'queryId': query['queryId'],
                    'targetIds': query['targetIds'], 'status': query['status'],
                    'reasonCode': 'PP2_DISCOVERY_QUERY_INCOMPLETE',
                    'issues': deepcopy(query['issues']),
                    'unqueriedAliases': deepcopy(query.get('unqueriedAliases', [])),
                    'referencesWithoutMetadata': deepcopy(query.get('referencesWithoutMetadata', []))})
        if checkpoint.get('deferredCandidates'):
            result['remaining'].append({'reasonCode': 'PP2_DISCOVERY_DEFERRED_CANDIDATES',
                                        'count': len(checkpoint['deferredCandidates'])})
        if final['resumeAvailable'] and len(result['batches']) == max_batches:
            result['errors'].append('PP2_DISCOVERY_REPLAY_BATCH_LIMIT')
        if (not result['errors'] and not result['remaining']
                and not final['resumeAvailable']
                and all(t['plannedQueriesComplete'] for t in final['targets'])):
            result['status'] = 'COMPLETE'
        result['unusedResponseUrls'] = sorted(set(frozen) - {e['url'] for e in used.values()})
    except (ValueError, TypeError, KeyError, OSError, RecursionError) as error:
        result['errors'].append(failure(error))
    result['ledgerSha256'] = sha(canonical(result))
    return result


def _complete(query):
    return (query.get('status') == 'COMPLETE' and query.get('executionComplete') is True
            and not query.get('issues') and not query.get('omittedTerms')
            and not query.get('omittedReferences') and not query.get('unqueriedAliases')
            and not query.get('referencesWithoutMetadata')
            and (query.get('tier') != 'LITVAR_VARIANT'
                 or query.get('resolutionComplete') is True))
