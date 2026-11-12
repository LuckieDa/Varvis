"""One public PP1 paper: cache-first extraction/audit, then missing-driven reading.

The binding and adopted context are server inputs; no HTTP/model-provided ledger
or policy is accepted here. Artifacts remain private, public projections omit
their filesystem paths. Partial work survives a deadline, cancellation or error.
"""
from pathlib import Path
import re
import time

from bundle_model_contract import load
from literature_work_units import plans
from model_literature_contract import canonical, digest, parse
from model_transport import atomic, https_once
from pp1_literature_contract import initial_scope, validate_target
from pp1_reading_loop import run as continue_reading, material_coverage, recovery_state
from run_pp1_literature_model import prepare, run as model_run, replay

VERSION = 'pp1-paper-process-v1'
POSTPROCESSING_IO_STAGES = frozenset({'PEDIGREE_CHECKPOINT_CAPTURE', 'REVIEW_CACHE_REGISTRATION', 'PUBLIC_SOURCE_PROJECTION'})
SOURCE_IO_REASONS = frozenset({'PP1_SOURCE_FILE_UNAVAILABLE', 'PP1_SOURCE_IO_FAILED'})


def paper_recovery(stop, state=None):
    """Explicit continuation only; never restart a shared failed model ledger."""
    if stop in SOURCE_IO_REASONS and isinstance(state, dict):
        upstream = state.get('sourceExecutionRecovery', {})
        if isinstance(upstream, dict) and upstream.get('sharedBudgetReconciliationRequired') is True:
            return {'status': 'EXECUTION_REVIEW_REQUIRED', 'resumeAvailable': False,
                    'automaticRetryAllowed': False, 'reasonCode': stop, 'stage': state.get('stage'),
                    'sharedBudgetReconciliationRequired': True}
        checkpoint, available = state.get('checkpoint'), state.get('available')
        if isinstance(checkpoint, dict) and isinstance(available, dict):
            pins = checkpoint.get('acceptedArtifactSha256s')
            count = available.get('acceptedReviewArtifacts')
            if (state.get('stage') in POSTPROCESSING_IO_STAGES and isinstance(pins, list)
                    and 1 <= len(pins) <= 32 and type(count) is int and count == len(pins)
                    and all(isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin) for pin in pins)
                    and len(set(pins)) == len(pins)
                    and isinstance(checkpoint.get('pmcid'), str) and re.fullmatch('PMC[1-9][0-9]*', checkpoint['pmcid'])
                    and isinstance(checkpoint.get('bundleSha256'), str) and re.fullmatch('[a-f0-9]{64}', checkpoint['bundleSha256'])):
                return {'status': 'RESOURCE_REPAIR_REQUIRED', 'resumeAvailable': True,
                        'automaticRetryAllowed': False, 'resourceRepairRequired': True,
                        'reasonCode': stop, 'stage': state['stage']}
    if stop in {'PP1_MODEL_DISABLED_CACHE_MISSING', 'PP1_CALL_LIMIT', 'PP1_TIME_LIMIT', 'PP1_USER_CANCELLED'}:
        return {'status': 'EXECUTION_PENDING', 'resumeAvailable': True,
                'automaticRetryAllowed': False, 'reasonCode': stop, 'stage': 'SOURCE_EXTRACTION_OR_AUDIT'}
    return recovery_state(stop)


def public_reading_view(value):
    """Keep source facts/pins while excluding private continuation locations.

    The full original remains in reading/result.json and artifacts.json on the
    task's private disk. Rejection locations can also occur in round records,
    not only in the top-level rejected-artifact list.
    """
    if isinstance(value, dict):
        return {key: public_reading_view(item) for key, item in value.items()
                if key not in {'acceptedArtifacts', 'artifactPath', 'parentPath'}}
    if isinstance(value, list):
        return [public_reading_view(item) for item in value]
    return value


def public_spellings(public, aliases):
    """Recover printed whitespace/parenthesis forms, never infer an allele.

    The subsequent source qualification still verifies transcript and genomic
    identity. Matches retain the exact source text for receipt/cache binding.
    """
    found = []
    for alias in aliases:
        if re.search(r'(?<![\w.])' + re.escape(alias) + r'(?![\w>+=*-])', public):
            found.append(alias)
        value = re.sub(r'\s+', '', alias.split(':')[-1])
        variants = [value]
        if re.fullmatch(r'p\.[A-Za-z*]+[1-9][0-9]*(?:[A-Za-z*=]+)', value):
            variants.extend(['p(' + value[2:] + ')', 'p.(' + value[2:] + ')'])
        if not re.fullmatch(r'[cgp]\.[A-Za-z0-9_*+?>=().;\[\]-]+', value):
            continue
        for v in variants:
            pattern = r'(?<![\w.])' + r'[ \t]*'.join(re.escape(c) for c in v) + r'(?![\w>+=*-])'
            found.extend(m.group() for m in re.finditer(pattern, public))
    return list(dict.fromkeys(found))


def require(value, reason):
    if not value:
        raise ValueError(reason)


def initial_units(source, target, preflight):
    """Start with the source packets that mention a verified variant spelling.

    Keep their original rows, headers, notes and surrounding text together.
    Unselected packets stay available to the missing-driven reader.
    """
    units = source['units']
    pattern = re.compile(r'(?<![\w.])(?:' + '|'.join(map(re.escape, target['variantAliases']))
                         + r')(?![\w>+=*-])')
    matches = {u['unitId'] for u in units if u.get('kind') != 'REFERENCE' and pattern.search(u['text'])}
    ranked = [(len(matches.intersection(p['targetUnitIds'])), p) for p in plans(source)] if matches else []
    ranked = sorted((row for row in ranked if row[0]), key=lambda row: -row[0])
    if not ranked:
        ids = initial_scope(source, target)
        preflight(ids)
        return ids

    def packet_units(packet):
        return set(packet['targetUnitIds'] + packet['contextUnitIds'])

    focused = set().union(*(packet_units(packet) for _, packet in ranked))
    ids = [u['unitId'] for u in units if u['unitId'] in focused]
    try:
        preflight(ids)
        return ids
    except ValueError as error:
        if str(error) != 'PP1_INPUT_LIMIT_NOT_TRUNCATED':
            raise
    selected = set()
    for _, packet in ranked:
        proposed = selected | packet_units(packet)
        candidate = [u['unitId'] for u in units if u['unitId'] in proposed]
        try:
            preflight(candidate)
        except ValueError as error:
            if str(error) != 'PP1_INPUT_LIMIT_NOT_TRUNCATED':
                raise
            continue
        selected = proposed
    require(selected, 'PP1_INPUT_LIMIT_NOT_TRUNCATED')
    return [u['unitId'] for u in units if u['unitId'] in selected]


def process(library, pmcid, pin, target, binding, context, resolver, config, config_path,
            budget, output, *, allow_model=False, seconds=540, maximum_calls=8,
            maximum_rounds=8, transport=https_once, cancelled=lambda: False,
            accepted_artifacts=(), accepted_pedigree_reviews=()):
    validate_target(target)
    require(type(seconds) in (int, float) and 1 <= seconds <= 3600, 'PP1_TIME_BUDGET_INVALID')
    require(type(maximum_calls) is int and 0 <= maximum_calls <= 64, 'PP1_CALL_BUDGET_INVALID')
    require(type(maximum_rounds) is int and 0 <= maximum_rounds <= 24, 'PP1_ROUND_BUDGET_INVALID')
    require(binding.get('schemaVersion') == 'pp1-analysis-binding-v1', 'PP1_ANALYSIS_BINDING_REQUIRED')
    from pp1_published_contribution import checked_ledger
    selection, ledger = binding['selection'], checked_ledger(binding['ledger'], context)
    require(selection['geneSymbol'] == target['geneSymbol'], 'PP1_ANALYSIS_GENE_MISMATCH')
    require(ledger['selectionKey'] == selection['selectionKey'] and ledger['allele'] == selection['allele']
            and ledger['scope'] == {k: selection[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')},
            'PP1_ANALYSIS_LEDGER_MISMATCH')
    output = Path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    source = load(library, pmcid, pin, criterion='PP1')
    from pp1_reviewed_cache import manual_matches
    if source.get('reviewedPages'):
        bindings = {canonical(document['binding']['target']): document['binding']['target']
                    for _, document in manual_matches(library, target, selection, pmcid)
                    if document['binding']['bundleSha256'] == pin}
        require(len(bindings) == 1, 'PP1_SCANNED_PUBLICATION_MANUAL_REVIEW_REQUIRED')
        paper_target = next(iter(bindings.values()))
        allow_model = False
    else:
        # Only spellings actually present in public text may be sent to the model.
        public = '\n'.join(u['text'] for u in source['units'])
        aliases = public_spellings(public, target['variantAliases'])
        require(aliases and re.search(r'(?<![A-Za-z0-9])' + re.escape(target['geneSymbol'])
                                     + r'(?![A-Za-z0-9])', public), 'PP1_TARGET_NOT_IN_PUBLIC_SOURCE')
        paper_target = {'geneSymbol': target['geneSymbol'], 'variantAliases': aliases}
    output.mkdir(parents=True)
    deadline, calls = time.monotonic() + seconds, 0
    accepted = list(accepted_artifacts)
    verified_accepted, verified_pins = [], []
    qualified_observations = 0
    reading_execution_recovery = None
    pedigree_originals=[]
    stage = 'SOURCE_REVIEW_REPLAY'
    result = {'schemaVersion': VERSION, 'pmcid': pmcid, 'bundleSha256': pin, 'target': paper_target,
              'materialCoverage': material_coverage(source),
              'analysisBindingSha256': digest(canonical(binding)), 'status': 'RUNNING',
              'modelAttempts': 0, 'issues': [], 'scientificExecution': False, 'businessPublishable': False,
              'scopeStatement': '已获取公开资料的限定核查；未读、失败和语义未支持均不等于原文没有记载。'}

    def save():
        result['modelAttempts'] = calls
        manifest = {'acceptedArtifacts': [str(Path(p).resolve()) for p in accepted]}
        if pedigree_originals:manifest['pedigreeReviews']=pedigree_originals
        result['artifactManifestSha256'] = digest(canonical(manifest))
        atomic(output / 'artifacts.json', manifest)
        atomic(output / 'result.json', result)

    def reading_state():
        evaluation = result.get('evaluation', {})
        needs = evaluation.get('needs', [])
        # Retain the evaluator's exact paper/family/member requirements and
        # source comparisons.  Counts do not turn unknown facts into negatives.
        state = {'schemaVersion': 'pp1-paper-reading-state-v1', 'stage': stage,
                 'required': {'registeredSourceBundle': pin, 'sourceReview': 'REPLAYABLE_SOURCE_REVIEW',
                              'sourceReviewArtifactsAtLeast': 1,
                              'decisionInputs': 'CURRENT_SELECTED_CONTEXT_AND_QUALIFIED_OBSERVATIONS'},
                 'available': {'verifiedSourceBundle': pin, 'textUnits': len(source['units']),
                               'acceptedReviewArtifacts': len(verified_pins),
                               'modelExecutionEnabled': allow_model,
                               'remainingCallCapacity': max(0, maximum_calls-calls),
                               'qualifiedObservations': qualified_observations},
                 'missing': needs,
                 'checkpoint': {'pmcid': pmcid, 'bundleSha256': pin,
                                'acceptedArtifactSha256s': list(verified_pins)},
                 'wholePaperAbsenceClaim': False}
        if reading_execution_recovery is not None:
            state['sourceExecutionRecovery'] = reading_execution_recovery
        state['recovery'] = paper_recovery(result.get('stopReason'), state)
        # Before evaluation there are no member-specific biological needs yet.
        # Show the actual system-stage shortfall separately, rather than an
        # empty needs list suggesting all required source work has finished.
        state['stageGaps'] = []
        if len(verified_pins) < 1:
            state['stageGaps'].append({
                'kind': 'SYSTEM_STAGE', 'stage': 'SOURCE_REVIEW',
                'reasonCode': result.get('stopReason') or 'PP1_SOURCE_REVIEW_REQUIRED',
                'requirement': 'REPLAYABLE_SOURCE_REVIEW',
                'required': {'minimumReviewArtifacts': 1},
                'available': {'replayedReviewArtifacts': len(verified_pins), 'verifiedSourceBundle': pin},
                'difference': {'missingMinimumReviewArtifacts': 1-len(verified_pins)},
                'scope': {'pmcid': pmcid, 'bundleSha256': pin},
                'recovery': paper_recovery(result.get('stopReason'))})
        return state

    def obtain(ids, stage, parent=None):
        nonlocal calls
        require(not cancelled(), 'PP1_USER_CANCELLED')
        require(time.monotonic() < deadline, 'PP1_TIME_LIMIT')
        args = (library, pmcid, pin, paper_target, ids, config, config_path, budget)
        cached = model_run(*args, output / (stage + '-cache'), previous=parent, execute=False, transport=transport)
        if cached.get('status') != 'MODEL_CACHE_MISSING':
            return output / (stage + '-cache') / 'artifact.json'
        require(allow_model, 'PP1_MODEL_DISABLED_CACHE_MISSING')
        require(calls < maximum_calls, 'PP1_CALL_LIMIT')
        require(time.monotonic() + config['timeoutSeconds'] + 5 < deadline, 'PP1_TIME_LIMIT')
        calls += 1
        save()
        model_run(*args, output / stage, previous=parent, execute=True, transport=transport)
        return output / stage / 'artifact.json'

    save()
    try:
        from pp1_reviewed_cache import restore, register, target_key
        from literature_manual_review import VERSION as MANUAL_REVIEW
        # Reuse raw source reviews across request/prompt revisions. This never
        # reuses a prior score or a prior disease/ledger decision.
        reused = restore(library, budget, pmcid, pin, paper_target, output/'reviewed-cache')
        for index, (_, document) in enumerate(manual_matches(library,paper_target,selection,pmcid,pin)):
            # The manifest binds the bytes. Repeating the full library hash
            # here can exceed Windows path limits in nested task worktrees.
            path = output/'manual-reviews'/f'{index}.json'
            path.parent.mkdir(exist_ok=True)
            atomic(path,document)
            reused.append(path)
        by_pin = {digest(Path(p).read_bytes()):p for p in accepted + reused}
        accepted = list(by_pin.values())
        result['reviewedCacheArtifacts'] = len(reused)
        require(len(accepted) <= 32, 'PP1_ARTIFACT_LIMIT')
        original_targets=[]
        for path in accepted:
            from run_pp1_literature_model import verify
            document = parse(Path(path).read_bytes())
            original = document if document.get('schemaVersion') == MANUAL_REVIEW else verify(library,document,verify_derived=False)
            require(original['binding']['stage'] == 'SOURCE_REVIEW'
                    and target_key(original['binding']['target']) == target_key(paper_target)
                    and original['binding']['bundleSha256'] == pin
                    and original['binding']['pmcid'] == pmcid, 'PP1_CONTINUATION_SOURCE_MISMATCH')
            original_targets.append(original['binding']['target'])
            verified_accepted.append(path)
            verified_pins.append(digest(Path(path).read_bytes()))
        if original_targets:
            require(all(t==original_targets[0] for t in original_targets),'PP1_REVIEW_CACHE_IDENTITY_CHANGED')
            paper_target=original_targets[0]
            result['target']=paper_target
        if not accepted:
            ids = initial_units(source, paper_target,
                lambda scope: prepare(library, pmcid, pin, paper_target, scope, config))
            stage = 'INITIAL_EXTRACTION'
            extracted = obtain(ids, 'initial-extraction')
            stage = 'INITIAL_SOURCE_AUDIT'
            audited = obtain(ids, 'initial-audit', extracted)
            # Merely producing a response does not make it an accepted audit.
            checked = replay(library, audited)
            require(checked['checked'].get('status') != 'SOURCE_CHECK_REJECTED', 'PP1_SOURCE_AUDIT_REJECTED')
            accepted.append(audited)
            verified_pins.append(digest(audited.read_bytes()))
        require(not cancelled(), 'PP1_USER_CANCELLED')
        left = deadline - time.monotonic()
        require(left >= 1, 'PP1_TIME_LIMIT')
        stage = 'MISSING_DRIVEN_READING'
        reading = continue_reading(library, accepted, selection, context, ledger, resolver, config, config_path,
                budget, output / 'reading', maximum_rounds=maximum_rounds, maximum_calls=maximum_calls-calls,
                seconds=min(left, 3600), execute=allow_model, transport=transport, cancelled=cancelled,
                captured_reviews=[(figure, pair) for unused, figure, pair in accepted_pedigree_reviews])
        calls += reading['modelAttempts']
        reading_execution_recovery = reading.get('recovery')
        accepted = reading['acceptedArtifacts']
        verified_pins = [digest(Path(p).read_bytes()) for p in accepted]
        qualified_observations = len(reading['qualification'].get('observations', []))
        result.update(scientificExecution=True)
        result['evaluation'] = reading['evaluation']
        stage = 'PEDIGREE_CHECKPOINT_CAPTURE'
        from pp1_pedigree_delivery import freeze
        old_pairs = {digest(Path(pair).read_bytes()) for unused, unused_figure, pair in accepted_pedigree_reviews}
        remaining_qualification = {**reading['qualification'], 'memberCorrespondence': [row
            for row in reading['qualification'].get('memberCorrespondence', [])
            if row['sourceRef']['visualPairSha256'] not in old_pairs]}
        pedigree_originals = [entry for entry, unused_figure, unused_pair in accepted_pedigree_reviews]
        pedigree_originals += freeze(library, source, remaining_qualification, output)
        stage = 'REVIEW_CACHE_REGISTRATION'
        register(library, budget, [p for p in accepted if parse(Path(p).read_bytes()).get('schemaVersion') != MANUAL_REVIEW])
        result.update(status='EVALUATED', scientificExecution=True, stopReason=reading['stopReason'])
        # Internal continuation paths belong to the private manifest, not a
        # browser response or an exported patient-facing result.
        stage = 'PUBLIC_SOURCE_PROJECTION'
        result['reading'] = public_reading_view(reading)
    except (ValueError, OSError, KeyError, TypeError, TimeoutError) as error:
        if isinstance(error, FileNotFoundError):
            reason = 'PP1_SOURCE_FILE_UNAVAILABLE'
        elif isinstance(error, OSError):
            reason = 'PP1_SOURCE_IO_FAILED'
        else:
            code = str(error)
            reason = code if re.fullmatch('[A-Z][A-Z0-9_]{1,160}', code) else 'PP1_PAPER_EXECUTION_FAILED'
        result.update(status='PARTIAL', stopReason=reason)
        result['issues'].append(reason)
        if stage == 'SOURCE_REVIEW_REPLAY':
            accepted = verified_accepted
    result['readingState'] = reading_state()
    save()
    return result
