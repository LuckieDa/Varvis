"""Durable batches for the existing discovery/acquisition workflow, no scoring."""
from pathlib import Path
import re

from model_literature_contract import canonical, digest, parse
from model_transport import bounded, atomic
from bundle_model_contract import require

VERSION = 'denovo-discovery-batch-v1'
MAX_STATE_BYTES = 64 * 1024 * 1024
DEFERRED_ACQUISITION = {'PAPER_ACQUISITION_PAPER_BUDGET', 'PAPER_DOWNLOAD_REQUEST_BUDGET',
    'PAPER_DOWNLOAD_BYTE_BUDGET', 'PAPER_DOWNLOAD_TOTAL_TIMEOUT', 'PAPER_DOWNLOAD_BATCH_STOPPED',
    'PAPER_DOWNLOAD_RATE_LIMIT', 'PAPER_DOWNLOAD_RATE_LIMIT_COOLDOWN', 'PAPER_DOWNLOAD_NETWORK_DISABLED'}


def selection_pin(request):
    return digest(canonical({'genes': request['genes'], 'targets': request.get('searchTargets')}))


def load_parent(request, root):
    parent = request.get('discoveryContinuation')
    if parent is None: return None
    require(isinstance(parent, dict) and set(parent) == {'parentTaskId', 'parentResultSha256', 'stateSha256', 'statePath'},
            'DISCOVERY_CONTINUATION_SCHEMA')
    require(all(isinstance(parent.get(k), str) and re.fullmatch('[a-f0-9]{64}', parent[k])
                for k in ('parentTaskId', 'parentResultSha256', 'stateSha256')), 'DISCOVERY_CONTINUATION_PIN')
    path = (root / parent['statePath']).resolve(strict=True)
    require(path.is_relative_to(root / 'local') and path.name == 'discovery-state.json', 'DISCOVERY_CONTINUATION_PATH')
    raw = bounded(path, MAX_STATE_BYTES)
    require(digest(raw) == parent['stateSha256'], 'DISCOVERY_CONTINUATION_PIN_MISMATCH')
    state = parse(raw)
    require(state.get('schemaVersion') == VERSION and state.get('selectionPin') == selection_pin(request)
            and state.get('searchMode') == request.get('searchMode', 'LITVAR_ONLY'), 'DISCOVERY_CONTINUATION_IDENTITY')
    require(isinstance(state.get('pendingCandidates'), list) and len(state['pendingCandidates']) <= 200
            and isinstance(state.get('attemptedPaperKeys'), list) and len(state['attemptedPaperKeys']) <= 100000,
            'DISCOVERY_CONTINUATION_LIMIT')
    from discover_public_literature import implementation_pin
    require(state.get('checkpoint', {}).get('implementation') == implementation_pin(),
            'DISCOVERY_CONTINUATION_IMPLEMENTATION_CHANGED')
    return state


def paper_key(candidate):
    return candidate.get('pmcid') or 'MED:' + (candidate.get('pmid') or candidate.get('sourceId', ''))


def snapshot(found):
    queries = found.get('queries', [])
    fields = ('queryId', 'tier', 'status', 'targetIds', 'pagesRead', 'hitCount', 'issues', 'fallbackDecisions',
              'linkedReferenceCount', 'litvarVariantId', 'litvarVariantIds', 'unqueriedAliases',
              'variantCandidates', 'referencesWithoutMetadata', 'resolutionComplete')
    return {'status': found['status'], 'candidates': len(found['candidates']), 'targets': found['targets'],
            'searchPolicy': found.get('searchPolicy'), 'searchMode': found.get('searchMode', 'LITVAR_ONLY'),
            'litvarEnabled': found.get('litvarEnabled', False),
            'queries': [{k: q.get(k) for k in fields} for q in queries[:100]],
            'additionalQueryRecords': max(0, len(queries)-100),
            'pendingQueries': sum(not q.get('executionComplete') and q['status'] not in {
                'SKIPPED_PRECISE_HIT', 'BLOCKED_PRECISE_INCOMPLETE'} for q in queries),
            'articles': [{k: c.get(k) for k in ('pmid', 'pmcid', 'doi', 'title', 'publicationYear', 'flags', 'matches')}
                         for c in found['candidates']],
            'linkedIdentifierCount': sum(q.get('linkedReferenceCount', 0) for q in queries),
            'completeLiteratureSearch': False}


def persist(work, request, found, acquired, previous=None, pending_model=()):
    previous = previous or {}
    outcomes = {paper_key(p): p for p in acquired['papers']}
    pending, attempted = [], set(previous.get('attemptedPaperKeys', []))
    for candidate in found['candidates']:
        key = paper_key(candidate)
        outcome = outcomes.get(key)
        if key in pending_model or not outcome or outcome.get('reasonCode') in DEFERRED_ACQUISITION:
            pending.append(candidate)
            attempted.discard(key)
        else:
            attempted.add(key)
    state = {'schemaVersion': VERSION, 'selectionPin': selection_pin(request),
             'searchMode': request.get('searchMode', 'LITVAR_ONLY'), 'checkpoint': found['checkpoint'],
             'discoveryComplete': not found['resumeAvailable'], 'pendingCandidates': pending,
             'attemptedPaperKeys': sorted(attempted), 'summary': snapshot(found)}
    raw = canonical(state)
    require(len(raw) <= MAX_STATE_BYTES, 'DISCOVERY_STATE_SIZE_LIMIT')
    path = Path(work) / 'discovery-state.json'
    atomic(path, state)
    # atomic() uses a display encoding; bind the exact persisted bytes.
    return {'path': path.name, 'sha256': digest(path.read_bytes()),
            'resumeAvailable': bool(pending) or not state['discoveryComplete'],
            'pendingPapers': len(pending), 'attemptedPapers': len(attempted),
            'pendingQueries': state['summary']['pendingQueries'], 'searchMode': state['searchMode']}
