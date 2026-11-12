"""One optional source audit per cached extraction, sharing the job call budget."""
from pathlib import Path
import time
from model_literature_contract import parse, canonical, digest
from model_transport import bounded
from bundle_model_contract import require
from run_core_model_review import run


def cached_review(library, original, mc, model_path, budget, folder):
    """Use only exact, previously supported extraction envelopes, never new receipts.

An offline requalification field can change after a reader update even though
the paid extraction and review are identical. Try the explicitly supported
envelopes; each is fully replayed before a cached review can be reused.
"""
    from bundle_replay_compatibility import reviewed_profiles
    raw = original.read_bytes(); artifact = parse(raw)
    base = {k: v for k, v in artifact.items() if k != 'offlineRequalification'}
    variants = [artifact, base] + [{**base, 'offlineRequalification': {
        'implementation': p, 'originalReceiptSha256': digest(canonical(artifact['receipt'])),
        'modelCalls': 0, 'networkCalls': 0}} for p in reviewed_profiles()]
    seen = set()
    try:
        for candidate in variants:
            body = canonical(candidate); pin = digest(body)
            if pin in seen: continue
            seen.add(pin); original.write_bytes(body)
            value = run(library, original, mc, model_path, budget, folder, cache_only=True)
            if value is not None:
                return value
    except Exception:
        original.write_bytes(raw)
        raise
    original.write_bytes(raw)
    return None


def review_targets(library, pmcid, processed, mc, model_path, budget, work, result, save, deadline, allow_model):
    folders, merged, pending = [], [], []
    for i in sorted(processed):
        require(time.monotonic() < deadline - 2, 'WORKFLOW_TIME_BUDGET')
        original = Path(work) / f'{pmcid}-chunk-{i}' / 'artifact.json'
        folder = Path(work) / f'{pmcid}-source-review-{i}'
        value = cached_review(library, original, mc, model_path, budget, folder)
        if value is None and allow_model and result['modelAttempts'] - result.get('readingPriority', {}).get('modelAttempts', 0) < 2:
            require(deadline - time.monotonic() > mc['timeoutSeconds'] + 5, 'WORKFLOW_TIME_BUDGET')
            ledger = Path(budget) / 'budget.json'
            before = parse(bounded(ledger, 262144)) if ledger.exists() else {'calls': []}
            result['modelAttempts'] += 1; save()
            try:
                value = run(library, original, mc, model_path, budget, folder)
            finally:
                after = parse(bounded(ledger, 262144)) if ledger.exists() else before
                old = {r['key'] for r in before['calls']}
                # Shared budget can have other jobs: attribute only this review's key.
                from run_core_model_review import prepare
                from bundle_model_transport import cache_identity
                _, _, payload, binding, _ = prepare(library, original, mc)
                key = cache_identity(mc, payload, binding)[1]
                calls = [r for r in after['calls'] if r['key'] == key and r['key'] not in old]
                result['modelCalls'] += len(calls)
                result['chargedOrReservedMicroCreditsThisRun'] += sum(r['chargedOrReservedMicroCredits'] for r in calls)
                result['estimatedMicroCreditsThisRun'] += sum(r['chargedOrReservedMicroCredits'] if r['status'] == 'SUCCESS'
                    else r.get('usageRateEstimatedMicroCredits', 0) for r in calls)
                result['modelChargeStatus'] = 'FAILED_OR_UNKNOWN_RESERVATION_RETAINED' if any(
                    r['status'] != 'SUCCESS' for r in calls) else 'USAGE_ESTIMATE_NOT_PLATFORM_BILL'
                save()
        if value is None:
            pending.append({'chunkIndex': i, 'reasonCode': 'SOURCE_REVIEW_CACHE_MISSING' if not allow_model
                            else 'SOURCE_REVIEW_BATCH_LIMIT'})
        else:
            processed[i]['artifactSha256'] = digest(original.read_bytes())
            folders.append(folder)
            merged.extend(parse((folder / 'review-proposals.json').read_bytes())['reviews'])
    return folders, merged, {'status': 'REPLAYED_NOT_SCORED' if not pending else 'PENDING',
        'reviewedTargets': len(folders), 'pendingTargets': pending,
        'meaning': 'MODEL_VOTES_REQUIRE_PROGRAM_SOURCE_AND_CONTEXT_CHECKS'}
