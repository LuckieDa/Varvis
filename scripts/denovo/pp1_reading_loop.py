"""PP1 missing-driven reading using existing extraction, audit, cache and budget.

Research entry only. No daily switches, model credential changes, business POST,
silent retry or cross-paper family merge. Each accepted artifact is replayed.
"""
import argparse
from pathlib import Path
import re
import time

from bundle_model_contract import load
from literature_work_units import plans
from model_literature_contract import canonical, digest, parse
from model_transport import bounded, https_once
from pp1_literature_contract import require
from pp1_meiosis import family_relationships
from pp1_source_qualification import qualify
from pp1_segregation import evaluate
from run_pp1_literature_model import collect, run as model_run, verify, reassess
from pp1_literature_contract import claim_id
from literature_manual_review import VERSION as MANUAL_REVIEW, qualify_pp1

VERSION = 'pp1-reading-loop-v1'

# These are actual bounded-execution stops, not biological absence or source
# rejection.  Keep the emitted PP1_ prefix: require() adds it to READING_*.
RESUMABLE_READING_STOPS = frozenset({
    'CALL_LIMIT', 'TIME_LIMIT', 'ROUND_LIMIT', 'USER_CANCELLED',
    'PP1_READING_MODEL_DISABLED_CACHE_MISSING', 'PP1_READING_CALL_LIMIT',
    'PP1_READING_TIME_LIMIT', 'PP1_READING_CANCELLED',
    'PP1_PEDIGREE_MODEL_DISABLED_CACHE_MISSING', 'PP1_PEDIGREE_PAIR_CALL_CAPACITY',
    'PP1_PEDIGREE_PAIR_TIME_REQUIRED',
})


def recovery_state(stop, reading=None):
    """Classify execution checkpoints without changing model billing or facts.

    A provider failure may have incurred charges.  Retain the paper for an
    explicit, reviewed continuation, but never clear its shared ledger stop or
    automatically retry it from a new budget.  Integrity/semantic failures are
    deliberately excluded from resumable execution.
    """
    reading = reading or {}
    if stop in RESUMABLE_READING_STOPS:
        return {'status': 'EXECUTION_PENDING', 'resumeAvailable': True,
                'automaticRetryAllowed': False, 'reasonCode': stop,
                'stage': 'PEDIGREE_REVIEW' if 'PEDIGREE' in stop else 'SOURCE_READING'}
    if stop in {'DENOVO_MODEL_TIMEOUT', 'DENOVO_MODEL_TRANSPORT_FAILED',
                'DENOVO_MODEL_RATE_LIMITED', 'DENOVO_MODEL_TRANSPORT_OR_STORAGE_FAILED',
                'DENOVO_MODEL_PILOT_BUSY_OR_INTERRUPTED', 'PP1_READING_EXECUTION_FAILED'} \
            or re.fullmatch(r'DENOVO_MODEL_HTTP_(?:408|425|429|5\d\d)', stop or ''):
        return {'status': 'EXECUTION_REVIEW_REQUIRED', 'resumeAvailable': True,
                'automaticRetryAllowed': False, 'reasonCode': stop, 'stage': 'SOURCE_READING',
                'sharedBudgetReconciliationRequired': True}
    return {'status': 'CURRENT_SCOPE_FINISHED' if stop == 'QUALIFIED_EVIDENCE_REACHED' else 'SOURCE_OR_CONTEXT_UNRESOLVED',
            'resumeAvailable': False, 'automaticRetryAllowed': False,
            'reasonCode': stop, 'stage': 'SOURCE_READING'}


def material_coverage(source):
    """Expose acquisition/parser gaps separately from readable text progress."""
    coverage = source.get('coverage', {})
    unresolved = [{k: attachment[k] for k in ('originalName', 'sha256', 'status', 'reasonCode') if k in attachment}
                  for attachment in source.get('attachments', [])
                  if attachment.get('status') == 'ARCHIVED_NOT_PARSED']
    return {'textUnits': len(source['units']), 'acquisitionStatus': coverage.get('status', 'UNKNOWN'),
            'parsedAttachments': coverage.get('parsedAttachments', 0),
            'unparsedAttachments': coverage.get('unparsedAttachments', len(unresolved)),
            'unparsedFiles': unresolved, 'completePublicationProven': coverage.get('completePublicationProven') is True,
            'wholePaperAbsenceClaim': False}


def pending_review(library, artifacts, evaluation, rejected_parents=()):
    needed = {n['scope']['claimId'] for n in evaluation['needs']
              if n['reasonCode'] == 'PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW'}
    if not needed:
        return None
    for path in reversed(artifacts):
        raw = bounded(path, 16 * 1024 * 1024)
        if digest(raw) in rejected_parents: continue
        document = parse(raw)
        if document.get('schemaVersion') == MANUAL_REVIEW: continue
        artifact = verify(library, document, verify_derived=False)
        claims = [claim_id(f) for f in artifact['candidate'].get('additions', [])]
        if needed.intersection(claims):
            return {'parentPath': path, 'parentSha256': digest(raw),
                    'claimIds': sorted(needed.intersection(claims)), 'unitIds': artifact['binding']['unitIds']}
    return None


def qualify_artifacts(library, artifacts, selection, resolver, captured_reviews=(), *, captured_only=False):
    native_paths, manual = [], []
    for path in artifacts:
        document = parse(bounded(path,16*1024*1024))
        if document.get('schemaVersion') == MANUAL_REVIEW:
            manual.append(qualify_pp1(library,document,selection,resolver))
        else:
            native_paths.append(path)
    if not native_paths:
        index = next((i for i, (_, _, q) in enumerate(manual) if q['identity']), 0)
        source, inventory, qualification = manual.pop(index)
        return source, inventory, merge_manual(source, qualification, manual)
    inventory = collect(library, native_paths)
    identity = inventory['sourceIdentity']
    source = load(library, identity['pmcid'], identity['bundleSha256'], criterion='PP1')
    qualification = qualify(source, inventory, selection, resolver)
    from pp1_family_correspondence import augment
    if captured_only:
        qualification = augment(library,source,inventory,qualification,reviews=list(captured_reviews))
    elif captured_reviews:
        # Captured pairs remain independently verified by augment; combine with
        # newly completed native reviews instead of dropping either checkpoint.
        root = Path(library) / 'papers' / source['pmcid'] / 'pedigree-figures'
        native = [(figure, pair) for figure in sorted(root.glob('*/source.json'))[:8]
                  for pair in sorted((figure.parent / 'reviews').glob('*.json'))[:8]]
        unique = {}
        for figure, pair in [*captured_reviews, *native]:
            # The same frozen review can also remain in the paper library.
            # Paths are storage locations, not independent observations.
            unique.setdefault(digest(bounded(pair, 4 * 1024 * 1024)), (figure, pair))
        qualification = augment(library, source, inventory, qualification,
                                reviews=list(unique.values()))
    else:
        qualification = augment(library, source, inventory, qualification)
    if not qualification['identity']:
        index = next((i for i, (_, _, q) in enumerate(manual) if q['identity']), None)
        if index is not None:
            _, _, qualification = manual.pop(index)
    if manual or qualification.get('manualReview'):
        qualification = merge_manual(source, qualification, manual)
    return source, inventory, qualification


def merge_manual(source, qualification, reviews):
    for _, _, review in reviews:
        require(review['sourceIdentity'] == qualification['sourceIdentity'], 'MANUAL_REVIEW_SOURCE_MISMATCH')
        if not review['identity']:
            continue
        qualification['reviewedUnitIds'] = sorted(set(qualification['reviewedUnitIds'] + review['reviewedUnitIds']))
        for key, family in review['families'].items():
            qualification['families'].setdefault(key, family)['members'].update(family['members'])
        qualification['observations'].extend(review['observations'])
        qualification.setdefault('additionalManualReviews', []).append(review['manualReview'])
    if qualification['identity'] and qualification['target']['inheritance'] == 'AD':
        # Native qualification ran before the manually reviewed rosters were
        # available. Rebind pair declarations against the final named families.
        relationships = family_relationships(source, set(qualification['reviewedUnitIds']), qualification['families'])
        if relationships or 'familyRelationshipEvidence' in qualification:
            qualification['familyRelationshipEvidence'] = relationships
    return qualification


def assess(library, artifacts, selection, context, ledger, resolver, captured_reviews=()):
    source, inventory, qualification = qualify_artifacts(library,artifacts,selection,resolver,captured_reviews)
    return source, inventory, qualification, evaluate([qualification], context, ledger)


def plan(source, result, qualification, target, attempted=()):
    units = {u['unitId']: u for u in source['units']}
    visited = set(qualification['reviewedUnitIds'])
    needs = [n for n in result['needs'] if n['route'] == 'LITERATURE']
    labels = {n['scope']['memberLabel'] for n in needs if isinstance(n['scope'], dict) and 'memberLabel' in n['scope']}
    aliases = target['variantAliases']
    entries = []
    for chunk in plans(source):
        ids = list(dict.fromkeys(chunk['targetUnitIds'] + chunk['contextUnitIds']))
        key = digest(canonical(chunk['targetUnitIds']))
        text = ' '.join(units[i]['text'] for i in chunk['targetUnitIds'])
        relevance = 3 * sum(a in text for a in aliases) + 2 * sum(m in text for m in labels)
        relevance += bool(re.search(r'\b(family|pedigree|father|mother|sibling|segregat\w*|affected|homozygous|heterozygous|candidate)\b',text,re.I))
        entries.append({'chunkKey': key, 'targetUnitIds': chunk['targetUnitIds'], 'unitIds': ids,
            'relevance': relevance, 'status': 'REVIEWED' if set(chunk['targetUnitIds']) <= visited else
                'ATTEMPTED' if key in attempted else 'AVAILABLE'})
    available = sorted((e for e in entries if e['status'] == 'AVAILABLE' and e['relevance']),
                       key=lambda e: (-e['relevance'], entries.index(e)))
    return {'chunks': entries, 'selected': available[0] if available and needs else None,
            'remainingChunks': sum(e['status'] == 'AVAILABLE' for e in entries),
            'materialCoverage': material_coverage(source),
            'pedigreeReview': qualification.get('pedigreeReview', {
                'registeredFigures':0,'reviewedPairs':0,'issues':[]}),
            'selectionBasis': 'TARGET_AND_MEMBER_AND_REQUIREMENT_HINTS_NOT_PROOF_OF_RELEVANCE'}


def run(library, artifacts, selection, context, ledger, resolver, config, config_path,
        budget, output, *, maximum_rounds=8, maximum_calls=8, seconds=600,
        execute=False, transport=https_once, cancelled=lambda: False, captured_reviews=()):
    output = Path(output)
    require(not output.exists(), 'READING_NEW_OUTPUT_REQUIRED')
    require(0 <= maximum_rounds <= 24 and 0 <= maximum_calls <= 64 and 1 <= seconds <= 3600, 'READING_LIMIT_INVALID')
    output.mkdir(parents=True)
    deadline = time.monotonic() + seconds
    accepted, attempted, rounds = list(artifacts), set(), []
    calls = 0
    rejected = []
    rejected_parents = set()
    figure_attempts,figure_rounds=set(),[]
    def assess_current(paths):
        if captured_reviews:
            return assess(library, paths, selection, context, ledger, resolver, captured_reviews)
        return assess(library, paths, selection, context, ledger, resolver)
    source, inventory, qualification, evaluation = assess_current(accepted)
    target = inventory['target']
    binding = digest(canonical([inventory['sourceIdentity'], selection, context, ledger]))
    stop = None
    def write(name, value): (output / name).write_bytes(canonical(value))
    def obtain(ids, folder, parent=None):
        nonlocal calls
        args = (library, source['pmcid'], source['bundleSha256'], target, ids,
                config, config_path, budget, output / (folder + '-cache'))
        cached_result = model_run(*args, previous=parent, execute=False, transport=transport)
        if cached_result.get('status') != 'MODEL_CACHE_MISSING':
            return output / (folder + '-cache') / 'artifact.json', True
        require(execute, 'READING_MODEL_DISABLED_CACHE_MISSING')
        require(not cancelled(), 'READING_CANCELLED')
        require(calls < maximum_calls, 'READING_CALL_LIMIT')
        require(time.monotonic() + config['timeoutSeconds'] + 5 < deadline, 'READING_TIME_LIMIT')
        # Count attempted calls too; the transport's shared ledger owns billing,
        # global cooldown/stop and input/output reservations.
        calls += 1
        value = model_run(*args[:-1], output / folder, previous=parent, execute=True, transport=transport)
        require(value.get('status') != 'MODEL_CACHE_MISSING', 'READING_MODEL_NO_RESULT')
        path = output / folder / 'artifact.json'
        require(path.is_file(), 'READING_MODEL_ARTIFACT_MISSING')
        return path, False
    try:
        for index in range(maximum_rounds + 1):
            reading = plan(source, evaluation, qualification, target, attempted)
            reading['pedigreeScheduling']=figure_rounds
            pending = pending_review(library, accepted, evaluation, rejected_parents)
            reading['pendingSourceReview'] = {k: v for k, v in pending.items() if k != 'parentPath'} if pending else None
            write('progress.json', {'schemaVersion':VERSION,'binding':binding,'evaluation':evaluation,
                'qualification':qualification,'readingPlan':reading,'rounds':rounds,'modelAttempts':calls,
                'acceptedArtifacts':[str(Path(p).resolve()) for p in accepted], 'rejectedArtifacts':rejected})
            if cancelled(): stop = 'USER_CANCELLED'; break
            if evaluation['status'] == 'MET': stop = 'QUALIFIED_EVIDENCE_REACHED'; break
            global_external = [n for n in evaluation['needs'] if n['blockingEffect'] == 'BLOCKS_SCORING'
                               and n['route'] != 'LITERATURE' and not ((pending or rejected_parents) and n['reasonCode'] ==
                                   'PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW')]
            if global_external: stop = 'EXTERNAL_REQUIREMENT'; break
            if time.monotonic() >= deadline: stop = 'TIME_LIMIT'; break
            from pp1_pedigree_schedule import next_figure,review as review_figure
            item=next_figure(library,source,qualification,evaluation,figure_attempts)
            if item and index<maximum_rounds:
                figure_attempts.add(item['key'])
                def charged():
                    nonlocal calls
                    calls+=1
                try:
                    reviewed=review_figure(item,source,target,config,config_path,budget,output/f'figure-{index:03}',
                        execute=execute,deadline=deadline,remaining_calls=maximum_calls-calls,
                        transport=transport,cancelled=cancelled,on_call=charged)
                except (ValueError,OSError,KeyError) as error:
                    code=str(error)
                    reviewed={'status':'UNRESOLVED','reasonCode':code if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',code) else 'PP1_PEDIGREE_REVIEW_FAILED'}
                figure_rounds.append({'familyLabel':item['family'],'key':item['key'],**reviewed})
                if reviewed['status']=='VERIFIED':
                    source,inventory,qualification,evaluation=assess_current(accepted)
                    continue
            if not reading['selected'] and not pending:
                figure_pending = [r['reasonCode'] for r in figure_rounds
                                  if r.get('status') != 'VERIFIED' and r.get('reasonCode')
                                  and recovery_state(r['reasonCode'])['resumeAvailable']]
                stop = (figure_pending[0] if figure_pending else
                        'SOURCE_REVIEW_REJECTED_PENDING' if rejected else
                        'SOURCE_FORMAT_REVIEW_REQUIRED' if reading['materialCoverage']['unparsedAttachments'] else
                        'NO_UNREAD_RELEVANT_BLOCK' if reading['remainingChunks'] else 'AVAILABLE_SCOPE_REVIEWED')
                break
            if index == maximum_rounds or len(accepted) >= 32: stop = 'ROUND_LIMIT'; break
            selected = reading['selected']
            step = {'kind': 'REVIEW_ADDITIONS' if pending else 'READ_NEW_BLOCK',
                    'beforeNeeds':[n['needId'] for n in evaluation['needs']]}
            rounds.append(step)
            if pending:
                step.update(parentSha256=pending['parentSha256'], claimIds=pending['claimIds'])
                hit1 = None  # No extraction request, not an extraction cache hit.
                audited, hit2 = obtain(pending['unitIds'], f'{index:03}-addition-audit', pending['parentPath'])
            else:
                attempted.add(selected['chunkKey']); step['chunkKey'] = selected['chunkKey']
                extracted, hit1 = obtain(selected['unitIds'], f'{index:03}-extract')
                extracted_check = reassess(library, extracted)['checked']
                if extracted_check.get('status')=='SOURCE_CHECK_REJECTED':
                    rejection={'artifactPath':str(extracted.resolve()),'artifactSha256':digest(extracted.read_bytes()),
                               'reasonCode':extracted_check['reasonCode'],'chunkKey':selected['chunkKey']}
                    rejected.append(rejection);step.update(status='SOURCE_REJECTED_CONTINUE',**rejection)
                    continue
                audited, hit2 = obtain(selected['unitIds'], f'{index:03}-audit', extracted)
            # Recheck original response/request/receipt before handling a source
            # error. Never catch integrity errors or rewrite an invalid anchor.
            audited_check = reassess(library, audited)['checked']
            if audited_check.get('status')=='SOURCE_CHECK_REJECTED':
                rejection={'artifactPath':str(audited.resolve()),'artifactSha256':digest(audited.read_bytes()),
                           'reasonCode':audited_check['reasonCode']}
                if pending:
                    rejected_parents.add(pending['parentSha256'])
                    rejection['parentSha256']=pending['parentSha256']
                else:rejection['chunkKey']=selected['chunkKey']
                rejected.append(rejection)
                step.update(status='SOURCE_REJECTED_CONTINUE',extractionCacheHit=hit1,auditCacheHit=hit2,**rejection)
                continue
            proposed = accepted + [audited]
            _, new_inventory, new_qualification, new_evaluation = assess_current(proposed)
            accepted = proposed; inventory = new_inventory; qualification = new_qualification; evaluation = new_evaluation
            step.update(status='REPLAYED_AND_REQUALIFIED',extractionCacheHit=hit1,auditCacheHit=hit2,
                artifactSha256=digest(audited.read_bytes()),afterNeeds=[n['needId'] for n in evaluation['needs']],
                statusAfter=evaluation['status'],eventCount=len(evaluation['events']))
            # A no-progress block is not a reason to abandon other relevant
            # blocks; each target is tried at most once in this bounded run.
    except (ValueError, OSError) as error:
        code = str(error)
        stop = code if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',code) else 'PP1_READING_EXECUTION_FAILED'
        if rounds and 'status' not in rounds[-1]: rounds[-1].update(status='FAILED',reasonCode=stop)
    reading = plan(source,evaluation,qualification,target,attempted)
    reading['pedigreeScheduling']=figure_rounds
    reading['sourceReviewFailures'] = [{'reasonCode':x['reasonCode'],'artifactSha256':x['artifactSha256'],
        **({'chunkKey':x['chunkKey']} if 'chunkKey' in x else {})} for x in rejected]
    reading['wholePaperAbsenceClaim'] = False
    pending = pending_review(library, accepted, evaluation)
    reading['pendingSourceReview'] = {k: v for k, v in pending.items() if k != 'parentPath'} if pending else None
    result = {'schemaVersion':VERSION,'binding':binding,'stopReason':stop,'evaluation':evaluation,
        'qualification':qualification,'readingPlan':reading,
        'rounds':rounds,'modelAttempts':calls,'acceptedArtifacts':[str(Path(p).resolve()) for p in accepted],
        'rejectedArtifacts':rejected,'businessPublishable':False,'scopeStatement':
        '仅对已取得的公开资料续读；未读、获取失败、执行限制及语义不支持不等于论文没有记载。'}
    result['recovery'] = recovery_state(stop, reading)
    write('result.json',result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--spec',required=True); p.add_argument('--output',required=True); p.add_argument('--execute',action='store_true')
    args = p.parse_args(); spec = parse(bounded(args.spec, 1024*1024))
    from core_local_identity import LocalIdentity
    from pp1_context import select
    from model_transport import config as read_config
    context = select(spec['root'],spec.get('knowledge'),spec['selection'])
    resolver = LocalIdentity(spec['root'],spec['identityResources']) if spec.get('identityResources') else None
    result = run(spec['library'],spec['artifacts'],spec['selection'],context,spec.get('ledger'),resolver,
        read_config(spec['config']),spec['config'],spec['budget'],args.output,
        execute=args.execute,**spec.get('limits',{}))
    print(canonical({'status':result['evaluation']['status'],'stopReason':result['stopReason'],
                     'modelAttempts':result['modelAttempts']}).decode())


if __name__ == '__main__': main()
