"""Bounded missing-fact loop over original extraction, review and core assessor.

No alternate scorer or business publication. The caller owns target/config and
initial artifacts. Every cumulative round replays original source audits; facts
are rebuilt from all accepted targets, never patched from model readiness flags.
"""
from pathlib import Path
import time

from model_literature_contract import canonical, digest, parse
from model_transport import bounded, https_once
from bundle_model_contract import load, requests, require
from bundle_model_transport import cache_identity
from run_bundle_model import run as extract, identity
from run_core_model_review import run as audit, replay as replay_audit, prepare as prepare_audit
from core_workflow_review import cached_review
from bundle_candidate_intake import pack
from qualify_bundle_candidates import qualify
from core_evaluation_intake import enrich
from denovo_reading_feedback import from_package
from denovo_reading_plan import plan

VERSION = 'denovo-need-reading-loop-v1'


def write(path, value):
    with Path(path).open('xb') as stream:
        stream.write(canonical(value))


def safe_error(error):
    code = str(error)
    return code if code.startswith('DENOVO_') and code.replace('_', '').isalnum() else 'DENOVO_READING_EXECUTION_FAILED'


def assess(library, source, accepted, output, context):
    """Rebuild, do not append scores. pack/replay retain other cases separately."""
    output.mkdir()
    ordered = [accepted[i] for i in sorted(accepted)]
    intake = pack(library, [p[0] for p in ordered], [p[1] for p in ordered])
    reviews = []
    for _, folder in ordered:
        reviews.extend(replay_audit(library, folder)['reviews'])
    facts = qualify(source, reviews)
    facts = enrich(source, facts, reviews, intake, output/'core', context)
    write(output/'candidate-intake.json', intake)
    write(output/'facts.json', facts)
    package = output/'core/context'
    feedback = from_package(package, digest((package/'index.json').read_bytes()))
    write(output/'feedback.json', feedback)
    return feedback, facts


def checkpoint_entry(index, artifact, folder, packet):
    original = parse(bounded(artifact, 8*1048576))
    review = parse(bounded(folder/'artifact.json', 8*1048576))['review']
    complete = (original['review']['extractionCoverageProposal'] == 'EXTRACTED_WITHIN_TARGET'
                and all(c['coverageProposal'] == 'REVIEWED_SUPPLIED_SCOPE' for c in review['cases']))
    return {'chunkIndex': index, 'requestSha256': digest(canonical(packet)),
            'artifactSha256': digest(artifact.read_bytes()),
            'reviewArtifactSha256': digest((folder/'artifact.json').read_bytes()),
            'status': 'REVIEWED' if complete else 'INCOMPLETE'}


def run(library, pmcid, bundle_pin, case_key, target, config, config_path, budget, core_context,
        output, seeds, *, allow_model=False, maximum_chunks=8, maximum_calls=16,
        timeout_seconds=120, transport=https_once):
    """Seeds are (extraction path, source-review folder) pairs, not editable facts.

    Fresh output only. Persistent/restart job control belongs to task4. Existing
    immutable model receipts can be reused across runs without new paid calls.
    """
    require(type(allow_model) is bool and type(maximum_chunks) is int and 1 <= maximum_chunks <= 64
            and type(maximum_calls) is int and 0 <= maximum_calls <= 128
            and type(timeout_seconds) is int and 1 <= timeout_seconds <= 3600, 'READING_LIMIT_INVALID')
    require(isinstance(seeds, list) and 1 <= len(seeds) <= 64, 'READING_SEED_REQUIRED')
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    budget = Path(budget); deadline = time.monotonic() + timeout_seconds
    source = load(library, pmcid, bundle_pin)
    profiles={parse(bounded(p,8*1048576))['binding'].get('extractionProfile') for p,_ in seeds}
    require(len(profiles)==1 and next(iter(profiles)) in ('core-material-v1','core-material-v2'), 'READING_SEED_PROFILE_CHANGED')
    profile=next(iter(profiles))
    packets = requests(source, config['model'], config['maxOutputTokens'], config['maxRequestBytes'], profile)
    accepted, entries = {}, {}
    result = {'schemaVersion': VERSION, 'pmcid': pmcid, 'bundleSha256': bundle_pin, 'caseKey': case_key,
              'target': target, 'rounds': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': 0,
              'chargedOrReservedMicroCreditsThisRun': 0, 'criterionDecision': 'NOT_RUN',
              'businessPublishable': False, 'absenceProven': False}
    result['implementation'] = {name:digest((Path(__file__).parent/name).read_bytes()) for name in (
        'denovo_reading_loop.py','denovo_reading_plan.py','denovo_reading_feedback.py')}

    def calls():
        return parse(bounded(budget/'budget.json',262144))['calls'] if (budget/'budget.json').is_file() else []

    def paid(function, payload, binding):
        require(allow_model, 'READING_MODEL_DISABLED')
        require(result['modelAttempts'] < maximum_calls, 'READING_CALL_LIMIT')
        require(time.monotonic() + config['timeoutSeconds'] + 5 < deadline, 'READING_TIME_LIMIT')
        key = cache_identity(config, payload, binding)[1]
        before = {c['key'] for c in calls()}
        result['modelAttempts'] += 1
        try:
            return function()
        finally:
            added = [c for c in calls() if c['key'] == key and key not in before]
            result['modelCalls'] += len(added)
            result['chargedOrReservedMicroCreditsThisRun'] += sum(c['chargedOrReservedMicroCredits'] for c in added)

    # Copy only required small artifacts, never mutate the caller's old archive.
    for number, (original, old_review) in enumerate(seeds):
        raw = bounded(original, 8*1048576); artifact = parse(raw); b = artifact['binding']
        require(b['pmcid'] == pmcid and b['bundleSha256'] == bundle_pin
                and b.get('extractionProfile') == profile and b['chunkCount'] == len(packets), 'READING_SEED_SOURCE_CHANGED')
        index = b['chunkIndex']
        require(type(index) is int and 0 <= index < len(packets)
                and artifact['requestSha256'] == digest(canonical(packets[index])), 'READING_SEED_REQUEST_CHANGED')
        folder = output/f'seed-{number}'; folder.mkdir()
        saved = folder/'extraction.json'; saved.write_bytes(raw)
        review_folder = folder/'review'; review_folder.mkdir()
        for name in ('artifact.json','request.json','original-extraction.json'):
            (review_folder/name).write_bytes(bounded(Path(old_review)/name, 8*1048576))
        # pack() below replays both artifacts and checks their exact linkage.
        if index in accepted:
            require(saved.read_bytes() == accepted[index][0].read_bytes()
                    and (review_folder/'artifact.json').read_bytes() == (accepted[index][1]/'artifact.json').read_bytes(),
                    'READING_CONFLICTING_SEED')
            continue
        accepted[index] = (saved,review_folder)
        entries[index] = checkpoint_entry(index,saved,review_folder,packets[index])
    feedback, facts = assess(library,source,accepted,output/'round-000',core_context)
    current_folder = output/'round-000'
    history = None
    try:
        for turn in range(maximum_chunks + 1):
            require(time.monotonic() < deadline, 'READING_TIME_LIMIT')
            reading = plan(source,feedback,case_key,target,config,history=history,cache_dir=budget,profile=profile)
            if history is None:
                history = {'schemaVersion':'denovo-reading-checkpoint-v1','scope':reading['scope'],'entries':list(entries.values())}
                reading = plan(source,feedback,case_key,target,config,history=history,cache_dir=budget,profile=profile)
            result['readingPlan'] = reading
            selected = next(c for c in feedback['cases'] if c['bindings']['caseKey'] == case_key)
            result['finalFeedback'] = selected
            result['coreEvaluation'] = next(c['coreEvaluation'] for c in facts['cases']
                                           if c['coreEvaluation']['caseKey'] == case_key)
            result['candidateIntake'] = {'path':str((current_folder/'candidate-intake.json').relative_to(output)),
                'sha256':digest((current_folder/'candidate-intake.json').read_bytes())}
            result['assessedFacts'] = {'path':str((current_folder/'facts.json').relative_to(output)),
                'sha256':digest((current_folder/'facts.json').read_bytes())}
            if selected['requirementsSatisfied']:
                result['stopReason'] = 'READY_FOR_ORIGINAL_EVALUATION'; break
            # Impossible dependencies do not trigger paid attempts to fill unrelated gaps.
            if selected['needs'] and all(n['route'] not in {'LITERATURE_READING','SOURCE_REVIEW','CONFLICT_REVIEW'} for n in selected['needs']):
                result['stopReason'] = 'EXTERNAL_OR_UNSUPPORTED_REQUIREMENT'; break
            if not reading['selectedChunks']:
                result['stopReason'] = reading['selectionState']; break
            if turn == maximum_chunks or len(accepted) >= 64:
                result['stopReason'] = 'READING_CHUNK_LIMIT'; break
            index = reading['selectedChunks'][0]
            step = {'chunkIndex':index, 'beforeEvaluationKey':selected['bindings']['evaluationKey'],
                    'beforeNeedIds':[n['needId'] for n in selected['needs']]}
            result['rounds'].append(step)
            extract_folder = output/f'chunk-{index}-extraction'
            audit_folder = output/f'chunk-{index}-review'
            try:
                value = extract(library,pmcid,bundle_pin,config,config_path,budget,extract_folder,
                                chunk_index=index,cache_only=True,extraction_profile=profile)
                if value is None:
                    value = paid(lambda: extract(library,pmcid,bundle_pin,config,config_path,budget,extract_folder,
                        chunk_index=index,extraction_profile=profile,transport=transport),packets[index],
                        identity(pmcid,bundle_pin,index,len(packets),profile))
                original = extract_folder/'artifact.json'
                checked = cached_review(library,original,config,config_path,budget,audit_folder)
                if checked is None:
                    _,_,payload,binding,_ = prepare_audit(library,original,config)
                    checked = paid(lambda: audit(library,original,config,config_path,budget,audit_folder,
                                                transport=transport),payload,binding)
                proposed = {**accepted,index:(original,audit_folder)}
                next_folder = output/f'round-{turn+1:03d}'
                next_feedback,next_facts = assess(library,source,proposed,next_folder,core_context)
                # No loss of the target case, even when new extraction finds only another case.
                require(any(c['bindings']['caseKey'] == case_key for c in next_feedback['cases']), 'READING_CASE_LOST')
                accepted = proposed; feedback,facts = next_feedback,next_facts; current_folder = next_folder
                entries[index] = checkpoint_entry(index,original,audit_folder,packets[index])
                after = next(c for c in feedback['cases'] if c['bindings']['caseKey'] == case_key)
                step.update(status=entries[index]['status'], artifactSha256=entries[index]['artifactSha256'],
                            reviewArtifactSha256=entries[index]['reviewArtifactSha256'],
                            extractionCacheHit=value['cacheHit'],reviewCacheHit=checked.get('cacheHit',False),
                            afterEvaluationKey=after['bindings']['evaluationKey'],
                            afterNeedIds=[n['needId'] for n in after['needs']],
                            requirementsSatisfied=after['requirementsSatisfied'])
            except (ValueError,OSError) as error:
                code = safe_error(error); step.update(status='FAILED',reasonCode=code); result['issues'].append(code)
                entries[index] = {'chunkIndex':index,'requestSha256':digest(canonical(packets[index])),
                    'artifactSha256':digest(canonical(step)), 'status':'FAILED'}
                # Shared transport/budget stops, disabled mode and limits stop the
                # run. Source-check rejection can leave other useful blocks available.
                if ('MODEL_' in code or 'LIMIT' in code or code == 'DENOVO_READING_EXECUTION_FAILED'):
                    result['stopReason'] = code
                    history['entries'] = list(entries.values())
                    result['readingPlan'] = plan(source,feedback,case_key,target,config,history=history,cache_dir=budget,profile=profile)
                    break
            history['entries'] = list(entries.values())
            write(output/f'progress-{turn+1:03d}.json',{'step':step,'checkpoint':history})
    except (ValueError,OSError) as error:
        result['stopReason'] = safe_error(error); result['issues'].append(result['stopReason'])
    result['checkpoint'] = history
    result['replayedArtifactCount'] = len(accepted)
    result['scopeStatement'] = '累计同源病例资料重新核验；资料就绪不是科学评分，未读/失败不证明全文无资料。'
    write(output/'result.json',result)
    return result
