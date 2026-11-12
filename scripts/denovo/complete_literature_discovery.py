"""Drain discovery batches without a fixed batch count. No acquisition or model.

The caller supplies the authorized transport and an immutable per-batch recorder.
Daily workbench time limits are unchanged. Rate limits stop for later resumption;
transient failures get at most two further batches without forward progress.
"""
import json

from discover_public_literature import discover


def progress_key(checkpoint):
    queries = [{k: q.get(k) for k in (
        'queryId', 'executionComplete', 'cursor', 'seenResultIds',
        'unqueriedAliases', 'litvarVariantIds', 'referenceLinks')}
        for q in checkpoint.get('queries', [])]
    return json.dumps([queries, checkpoint.get('deferredCandidates', [])], sort_keys=True)


def complete(targets, transport_factory, record, checkpoint=None, search_mode='LITVAR_ONLY'):
    """Each factory call returns a fresh bounded batch sharing the same cache."""
    previous = progress_key(checkpoint or {})
    stalled = 0
    batch = 0
    while True:
        batch += 1
        with transport_factory(batch) as transport:
            result = discover(targets, transport, precision_first=True,
                              litvar=True, search_mode=search_mode,
                              checkpoint=checkpoint)
        checkpoint = result['checkpoint']
        current = progress_key(checkpoint)
        stalled = stalled + 1 if current == previous else 0
        previous = current
        failures = [e.get('reasonCode', '') for e in transport.events
                    if e.get('status') == 'UNAVAILABLE']
        reason = ('QUEUE_DRAINED' if not result['resumeAvailable'] else
                  'RATE_LIMIT_REQUIRES_RESUME' if any('RATE_LIMIT' in f for f in failures) else
                  'NO_PROGRESS_REQUIRES_REVIEW' if stalled >= 3 else None)
        record(batch, result, {'stopReason': reason, 'stalledBatches': stalled,
                               'requests': transport.requests, 'events': transport.events})
        if reason:
            return {'batches': batch, 'stopReason': reason,
                    'queueDrained': not result['resumeAvailable'],
                    'plannedQueriesComplete': all(t['plannedQueriesComplete'] for t in result['targets']),
                    'checkpoint': checkpoint}
