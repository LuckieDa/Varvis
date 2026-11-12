"""One bounded pass over a fixed paper; reuse exact receipts, never retry failures.

The parent checkpoint is supplied by the local task service, not a model or a
client path. It identifies completed targets; every one is replayed before any
new call. Each result is a cumulative replacement, never additional score.
"""
import time
from pathlib import Path

from model_literature_contract import canonical, digest, parse
from model_transport import bounded
from bundle_model_contract import load, requests, require
from bundle_model_transport import cache_identity
from run_bundle_model import run as extract, identity
from bundle_candidate_intake import pack
from qualify_bundle_candidates import qualify
from rejected_literature_target import isolate
from core_material_extraction import write_package
from core_material_review import review_package
from bundle_replay_compatibility import reviewed_profiles

MAX_TARGETS = 64  # Existing intake bound; report it instead of dropping results.


def safe_error(error):
    value = str(error)
    return value if value.startswith(('DENOVO_', 'PAPER_', 'LITERATURE_')) and value.replace('_', '').isalnum() else 'DENOVO_WORKFLOW_EXECUTION_FAILED'


def checkpoint(parent, pmcid, pin, count):
    if parent is None:
        return {}
    require(parent.get('pmcid') == pmcid and parent.get('bundleSha256') == pin and
            parent.get('plannedChunks') == count, 'CONTINUATION_SOURCE_OR_PLAN_CHANGED')
    completed = parent.get('completedTargets')
    require(isinstance(completed, list) and len(completed) <= MAX_TARGETS, 'CONTINUATION_CHECKPOINT_INVALID')
    result = {}
    for row in completed:
        index, sha = row.get('chunkIndex'), row.get('artifactSha256')
        require(type(index) is int and 0 <= index < count and index not in result and
                isinstance(sha, str) and len(sha) == 64 and all(c in '0123456789abcdef' for c in sha),
                'CONTINUATION_CHECKPOINT_INVALID')
        result[index] = sha
    return result


def process_paper(library, pmcid, pin, mc, model_path, budget, work, result, save, deadline,
                  allow_model=False, parent=None, extraction_profile='legacy-v1', core_context=None, source_review=False,
                  need_reading=None):
    """Mutates only this new result/work folder and the existing shared budget."""
    source = load(library, pmcid, pin)
    require(not source_review or extraction_profile in ('core-material-v1', 'core-material-v2'), 'CORE_EXTRACTION_PROFILE_REQUIRED')
    plans = requests(source, mc['model'], mc['maxOutputTokens'], mc['maxRequestBytes'], extraction_profile)
    completed = checkpoint(parent, pmcid, pin, len(plans))
    item = {'pmcid': pmcid, 'bundleSha256': pin, 'plannedChunks': len(plans),
            'supplementCoverage': source['coverage'], 'extractionProfile': extraction_profile,
            'chunks': [], 'unprocessedChunks': [], 'issues': []}
    result['papers'].append(item)
    processed, reasons, stopped = {}, {}, None
    work, budget = Path(work), Path(budget)

    def remember(index, cache_only):
        try:
            value = extract(library, pmcid, pin, mc, model_path, budget,
                            work / f'{pmcid}-chunk-{index}', chunk_index=index, cache_only=cache_only,
                            extraction_profile=extraction_profile, expected_artifact_sha256=completed.get(index))
        except ValueError as error:
            if index in completed:
                raise  # A formerly accepted parent cannot be silently dropped.
            rejected = isolate(source, mc, plans[index], budget, identity(pmcid, pin, index, len(plans), extraction_profile),
                               safe_error(error), work / f'{pmcid}-chunk-{index}-rejected.json')
            if rejected is None:
                raise
            item.setdefault('rejectedTargets', []).append(rejected)
            reasons[index] = rejected['reasonCode']
            return None
        require(value is not None, 'CONTINUATION_CACHE_MISSING')
        if index in completed:
            require(value['artifactSha256'] == completed[index], 'CONTINUATION_CACHE_CHANGED')
        processed[index] = value
        item['chunks'] = [processed[i] for i in sorted(processed)]
        return value

    try:
        # A missing old success must not turn into a paid replacement.
        for i in sorted(completed):
            require(time.monotonic() < deadline - 2, 'WORKFLOW_TIME_BUDGET')
            remember(i, True)
        # Recover compatible cache successes from other tasks before spending.
        for i, payload in enumerate(plans):
            if i in processed:
                continue
            require(time.monotonic() < deadline - 2, 'WORKFLOW_TIME_BUDGET')
            (work / f'{pmcid}-chunk-{i}-request.json').write_bytes(canonical(payload))
            if not parse(payload['messages'][1]['content'])['workWithinLimit']:
                reasons[i] = 'TARGET_WORK_LIMIT_NOT_TRUNCATED'
                continue
            if len(processed) >= MAX_TARGETS:
                reasons[i] = 'CUMULATIVE_INTAKE_TARGET_LIMIT'
                continue
            binding = identity(pmcid, pin, i, len(plans), extraction_profile)
            bindings = [binding] + [{**binding, 'implementation': p} for p in reviewed_profiles()]
            if any((budget / 'responses' / cache_identity(mc, payload, b)[1] / 'receipt.json').is_file() for b in bindings):
                remember(i, True)
        order=list(range(len(plans)))
        if need_reading:
            from denovo_workflow_reading import bootstrap_order
            order=bootstrap_order(plans,need_reading['targets'])
        for i in order:
            payload=plans[i]
            if i in processed or i in reasons:
                continue
            if need_reading and any(v['review']['cases'] for v in processed.values()):
                reasons[i]='NEED_READING_PLANNER_PENDING'
                continue
            if len(processed) >= MAX_TARGETS:
                reasons[i] = 'CUMULATIVE_INTAKE_TARGET_LIMIT'
                continue
            if not allow_model:
                reasons[i] = 'MODEL_DISABLED_NO_PAID_CALL'
                continue
            if result['modelAttempts'] - result.get('readingPriority', {}).get('modelAttempts', 0) >= 2:
                reasons[i] = 'MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS'
                continue
            require(deadline - time.monotonic() > mc['timeoutSeconds'] + 5, 'WORKFLOW_TIME_BUDGET')
            unused, key = cache_identity(mc, payload, identity(pmcid, pin, i, len(plans), extraction_profile))
            before = parse(bounded(budget / 'budget.json', 262144)) if (budget / 'budget.json').is_file() else {'calls': []}
            result['modelAttempts'] += 1
            save()
            try:
                value = remember(i, False)
                if value is not None:
                    result['estimatedMicroCreditsThisRun'] += value['estimatedMicroCreditsThisRun']
            finally:
                after = parse(bounded(budget / 'budget.json', 262144)) if (budget / 'budget.json').is_file() else before
                old_keys = {a['key'] for a in before['calls']}
                new = [a for a in after['calls'] if a['key'] == key and a['key'] not in old_keys]
                result['modelCalls'] += len(new)
                result['chargedOrReservedMicroCreditsThisRun'] += sum(a['chargedOrReservedMicroCredits'] for a in new)
                result['estimatedMicroCreditsThisRun'] += sum(a.get('usageRateEstimatedMicroCredits', 0) for a in new if a['status'] != 'SUCCESS')
                if i not in processed:
                    result['estimatedMicroCreditsThisRun'] += sum(a['chargedOrReservedMicroCredits'] for a in new if a['status'] == 'SUCCESS')
                result['modelChargeStatus'] = 'FAILED_OR_UNKNOWN_RESERVATION_RETAINED' if any(a['status'] != 'SUCCESS' for a in new) else 'USAGE_ESTIMATE_NOT_PLATFORM_BILL'
                save()
    except Exception as error:
        stopped = safe_error(error)
        result['issues'].append(stopped)
    finally:
        for i in range(len(plans)):
            if i not in processed:
                reasons.setdefault(i, stopped or 'TARGET_NOT_PROCESSED')
        item['unprocessedChunks'] = [{'chunkIndex': i, 'reasonCode': reason} for i, reason in sorted(reasons.items())]
        item['issues'] = sorted(set(reasons.values()) | ({stopped} if stopped else set()))
        item['completeModelInputProcessed'] = len(processed) == len(plans)
        item['completeTargetExtraction'] = item['completeModelInputProcessed'] and all(
            v['review']['extractionCoverageProposal'] == 'EXTRACTED_WITHIN_TARGET' for v in processed.values())
        try:
            if processed:
                reviews = [processed[i]['review'] for i in sorted(processed)]
                review_folders = None
                if source_review:
                    from core_workflow_review import review_targets
                    review_folders, merged, review_state = review_targets(library, pmcid, processed, mc, model_path,
                        budget, work, result, save, deadline, allow_model)
                    item['sourceReview'] = review_state
                    if review_state['pendingTargets']:
                        # Preserve all original facts for display; never publish a partially audited intake.
                        review_folders = None
                    else:
                        reviews = merged
                intake = pack(library, [work / f'{pmcid}-chunk-{i}' / 'artifact.json' for i in sorted(processed)], review_folders)
                path = work / f'{pmcid}-candidate-intake.json'; path.write_bytes(canonical(intake))
                item['candidateIntake'] = {'path': path.name, 'sha256': digest(canonical(intake)),
                    'status': 'OFFLINE_SOURCE_REPLAY_READY', 'businessPublishable': False,
                    'nextCheck': 'ALLELE_DISEASE_FAMILY_AND_EVENT_QUALIFICATION_REQUIRED'}
                if source_review and item['sourceReview']['pendingTargets']:
                    del item['candidateIntake']
                    item['issues'].append('SOURCE_REVIEW_REQUIRED_BEFORE_INTAKE')
                facts = qualify(source, reviews)
                path = work / f'{pmcid}-cumulative-cases.json'; path.write_bytes(canonical(facts))
                item['cumulativeCases'] = {'path': path.name, 'sha256': digest(canonical(facts)),
                    'uniqueCases': len(facts['cases']), 'proposals': sum(len(r['cases']) for r in reviews),
                    'status': 'SOURCE_CHECKS_ONLY_NOT_SCORED', 'clinicalAdoption': 'NONE',
                    'cases': [{'caseLabel': c['caseLabel'], 'issues': c['issues'],
                               'fieldLimitations': c.get('fieldLimitations', []),
                               'alleleIdentityIssues': c['alleleIdentity']['issues'],
                               'targetTesting': c['targetTesting']['status'], 'parentage': c['parentage']['status']}
                              for c in facts['cases']]}
                package_dir = work / f'{pmcid}-core-material'
                package = write_package(source, facts, reviews,
                                        package_dir, intake=intake)
                item['coreMaterial'] = {'path': package_dir.name + '/index.json',
                    'sha256': digest((package_dir / 'index.json').read_bytes()), 'uniqueCases': len(package['cases']),
                    'status': package['status'], 'businessPublishable': False, 'clinicalAdoption': 'NONE',
                    'nextCheck': 'SOURCE_REVIEW_AND_CONTEXT_REQUIRED'}
                review_dir = work / f'{pmcid}-core-review'
                reviewed = review_package(source, package_dir, item['coreMaterial']['sha256'], review_dir)
                item['coreReview'] = {'path': review_dir.name + '/index.json',
                    'sha256': digest((review_dir / 'index.json').read_bytes()), 'uniqueCases': len(reviewed['cases']),
                    'status': reviewed['status'], 'businessPublishable': False, 'clinicalAdoption': 'NONE',
                    'nextCheck': 'EXACT_IDENTITY_CONTEXT_AND_UNRESOLVED_SOURCE_CHECKS'}
                from core_context_review import context_package
                options = core_context or {}
                context_dir = work / f'{pmcid}-core-context'
                contextual = context_package(source, review_dir, item['coreReview']['sha256'], context_dir,
                    options.get('root', Path(__file__).resolve().parents[2]), options.get('config'), options.get('sha256'), complete_events=True)
                item['coreContext'] = {'path': context_dir.name + '/index.json',
                    'sha256': digest((context_dir / 'index.json').read_bytes()), 'uniqueCases': len(contextual['cases']),
                    'status': contextual['status'], 'businessPublishable': False, 'clinicalAdoption': 'NONE',
                    'identityConfigured': bool(options.get('config', {}).get('identity')),
                    'nextCheck': 'REMAINING_CONTEXT_EVENT_AND_ORIGINAL_SCIENTIFIC_INTAKE'}
                from core_evaluation_intake import project_package
                evaluation = project_package(context_dir, contextual)
                evaluation_path = work / f'{pmcid}-core-evaluation.json'
                evaluation_path.write_bytes(canonical(evaluation))
                item['coreEvaluation'] = {'path': evaluation_path.name, 'sha256': digest(canonical(evaluation)),
                    'readyCases': sum(c['status'] == 'READY_FOR_EVALUATION' for c in evaluation.values()),
                    'status': 'PREPARED_FOR_ORIGINAL_JAVA_INTAKE', 'businessPublishable': False, 'criterionDecision': 'NOT_RUN'}
                item['coreMaterials'] = [c['display'] for c in evaluation.values()]
        except Exception as error:
            stopped = safe_error(error); item['issues'].append(stopped); result['issues'].append(stopped)
        new_targets = len(set(processed) - set(completed))
        eligible = bool(reasons) and not stopped and len(processed) < MAX_TARGETS and any(
            v in ('MODEL_DISABLED_NO_PAID_CALL', 'MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS') for v in reasons.values())
        reason = 'UNPROCESSED_TARGETS_REMAIN' if eligible else (stopped or 'NO_SUPPORTED_PENDING_TARGETS')
        review_pending = item.get('sourceReview', {}).get('pendingTargets', [])
        if review_pending and not stopped:
            eligible = allow_model and all(r['reasonCode'] == 'SOURCE_REVIEW_BATCH_LIMIT' for r in review_pending)
            reason = 'SOURCE_REVIEW_PENDING' if eligible else 'SOURCE_REVIEW_CACHE_MISSING'
        if parent is not None and new_targets == 0 and eligible and not review_pending:
            eligible, reason = False, 'NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED'
        item['continuation'] = {'eligible': eligible, 'reasonCode': reason,
            'newTargets': new_targets, 'reusedTargets': sum(v['cacheHit'] for v in processed.values()),
            'completedTargets': len(processed), 'pendingTargets': len(reasons), 'maximumAccumulatedTargets': MAX_TARGETS}
        save()
    if need_reading and not stopped:
        from denovo_workflow_reading import advance
        advance(library,source,mc,model_path,budget,work,result,item,save,deadline,allow_model,
                core_context,need_reading)
    return item
