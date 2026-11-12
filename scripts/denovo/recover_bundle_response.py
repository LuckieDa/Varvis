"""Explicit offline validation of one received response, never a network retry.

Caller supplies a source validator; it must pass before any recovery is saved.
Failed ledger entries and reserved charges are immutable, including unknown bill.
"""
from pathlib import Path
from model_literature_contract import canonical, digest, parse
from model_transport import bounded, exclusive, atomic, require, public_config, units_cost
from bundle_model_transport import decode_response, candidate_version, cache_identity


def recover(c, state_dir, binding, request, expected_budget_sha256, validate_source, *, transport_kind):
    require(transport_kind in ('REAL_PROVIDER', 'SYNTHETIC_TEST'), 'RECOVERY_TRANSPORT_ORIGIN_REQUIRED')
    root = Path(state_dir); identity, key = cache_identity(c, request, binding)
    folder = root/'responses'/key
    with exclusive(root):
        prior_raw = bounded(root/'budget.json', 262144)
        require(digest(prior_raw) == expected_budget_sha256, 'RECOVERY_BUDGET_CHANGED')
        prior = parse(prior_raw)
        require(prior['configurationSha256'] == digest(canonical(public_config(c))), 'BUDGET_CONFIGURATION_CHANGED')
        failed = prior['calls'][-1]
        require(failed.get('key') == key and failed.get('status') == 'FAILED_RESERVED'
                and prior.get('stoppedReason') == 'RESPONSE_NOT_JSON'
                and failed.get('reasonCode') == 'DENOVO_MODEL_RESPONSE_NOT_JSON'
                and not prior.get('resumePermit') and not (folder/'receipt.json').exists(), 'RECOVERY_NOT_ELIGIBLE')
        require(bounded(folder/'request.json', c['maxRequestBytes']) == canonical(request), 'RECOVERY_REQUEST_CHANGED')
        raw = bounded(folder/'response.json', c['maxResponseBytes'])
        require(digest(raw) == failed.get('responseSha256'), 'RECOVERY_RESPONSE_CHANGED')
        candidate, usage = decode_response(raw, c, candidate_version(request))
        require(usage == failed.get('reportedUsage'), 'RECOVERY_USAGE_CHANGED')
        actual = units_cost(c, usage['prompt_tokens'], usage['completion_tokens'])
        require(actual <= failed['chargedOrReservedMicroCredits'], 'USAGE_EXCEEDS_RESERVATION')
        validation = validate_source(candidate)
        require(isinstance(validation, dict), 'RECOVERY_SOURCE_VALIDATION_REQUIRED')
        recovery = {'failedEntrySha256': digest(canonical(failed)), 'previousBudgetSha256': expected_budget_sha256,
                    'sourceValidationSha256': digest(canonical(validation)), 'implementationSha256': digest(Path(__file__).read_bytes()),
                    'method': 'RECHECK_EXACT_RECEIVED_JSON_FENCE_SCHEMA_AND_SOURCE', 'modelCallsThisRecovery': 0,
                    'networkCallsThisRecovery': 0, 'reservedChargeRetained': True}
        receipt = {'identity': identity, 'responseSha256': digest(raw), 'usage': usage, 'estimatedMicroCredits': actual,
                   'billing': 'RATE_ESTIMATE_PENDING_PLATFORM_RECONCILIATION', 'elapsedSeconds': failed['elapsedSeconds'],
                   'modelCalls': 1, 'networkCalls': 1, 'transportKind': transport_kind, 'receivedResponseRecovery': recovery}
        archive = root/'continuation-reviews'; archive.mkdir(exist_ok=True)
        backup = archive/(expected_budget_sha256+'-budget.json')
        if backup.exists(): require(backup.read_bytes() == prior_raw, 'RECOVERY_BACKUP_CHANGED')
        else: backup.write_bytes(prior_raw)
        atomic(folder/'received-response-validation.json', validation)
        atomic(folder/'receipt.json', receipt)
        prior.setdefault('receivedResponseRecoveries', []).append({'key': key, 'receiptSha256': digest(canonical(receipt)),
                                                                   **recovery})
        prior['stoppedReason'] = None
        atomic(root/'budget.json', prior)
        return {'status': 'RECEIVED_RESPONSE_RECHECKED', 'receiptSha256': digest(canonical(receipt)),
                'failedCallAndReservationRetained': True, 'modelCalls': 0, 'networkCalls': 0}
