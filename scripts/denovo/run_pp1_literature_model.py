"""Public PP1 extraction/audit artifacts, exact replay and shared paid budget.

Explicit execution only. This is a Stage-2 research entry, not a workbench job.
No legacy PP1 inputs, scientific scores, database writes or implicit retries.
"""
import argparse
import base64
from copy import deepcopy
from pathlib import Path

from bundle_model_contract import load, implementation
from bundle_model_transport import cached, invoke, decode_response, cache_identity
from model_transport import bounded, credentials, public_config, https_once
from model_literature_contract import canonical, digest, parse
from pp1_literature_contract import (VERSION, AUDIT_VERSION, DICTIONARY, PROMPT_VERSION, EXPLICIT_AUDIT_PROMPT_VERSION, require,
                                     request, source_check, audit_check)

MAX_REVIEW_DEPTH = 8


def review_input(parent):
    """A fresh review of additions, derived from raw responses, not old facts.

    This is a request view with its real parent retained, never a manufactured
    extraction response or receipt. Previously reviewed claims remain in the
    parent chain; they need not be paid for and reviewed again.
    """
    if parent['binding']['stage'] == 'EXTRACTION':
        return parent['candidate']
    require(parent['candidate']['additions'], 'NO_PENDING_ADDITIONS')
    previous = review_input(parent['parent'])
    families = deepcopy(previous['families'])
    known = {f['familyLabel']: f for f in families}
    for family in parent['candidate']['additionalFamilies']:
        if family['familyLabel'] not in known:
            families.append(deepcopy(family)); known[family['familyLabel']] = families[-1]
        else:
            row = known[family['familyLabel']]
            members = {m['memberLabel'] for m in row['members']}
            row['members'].extend(deepcopy(m) for m in family['members'] if m['memberLabel'] not in members)
    return {'schemaVersion': VERSION, 'families': families,
            'facts': deepcopy(parent['candidate']['additions']),
            'coverage': parent['candidate']['coverage'], 'groupChecks': parent['candidate']['groupChecks']}


def implementation_pins():
    names = ('pp1_literature_contract.py', 'run_pp1_literature_model.py',
             'bundle_model_transport.py', 'literature_work_units.py')
    return {**implementation(), **{n: digest(Path(__file__).with_name(n).read_bytes()) for n in names},
            'pp1-field-dictionary': digest(DICTIONARY.read_bytes())}


def prepare(library, pmcid, pin, target, unit_ids, config, previous=None):
    source = load(library, pmcid, pin, criterion='PP1')
    # Only raw candidate/source/receipt are inputs to the fresh audit. A parent's
    # old derived result is never adopted; it may predate a reviewed parser fix.
    original = verify(library, parse(bounded(previous, 16 * 1024 * 1024)), verify_derived=False) if previous is not None else None
    if original:
        b = original['binding']
        require(b['stage'] in {'EXTRACTION', 'SOURCE_REVIEW'} and b['pmcid'] == pmcid and b['bundleSha256'] == pin
                and b['target'] == target and set(b['unitIds']) == set(unit_ids), 'REVIEW_PARENT_MISMATCH')
        depth, node = 0, original
        while node is not None:
            depth += 1; node = node.get('parent')
        require(depth <= MAX_REVIEW_DEPTH, 'REVIEW_DEPTH_LIMIT')
    prompt_version = PROMPT_VERSION
    prior = review_input(original) if original else None
    try:
        payload = request(source, target, unit_ids, config, prior)
    except ValueError as error:
        if original is None or str(error) != 'PP1_INPUT_LIMIT_NOT_TRUNCATED':
            raise
        prompt_version = EXPLICIT_AUDIT_PROMPT_VERSION
        payload = request(source, target, unit_ids, config, prior, prompt_version)
    actual = [u['unitId'] for u in parse(payload['messages'][1]['content'])['units']]
    binding = {'version': AUDIT_VERSION if original else VERSION, 'stage': 'SOURCE_REVIEW' if original else 'EXTRACTION',
        'promptVersion': prompt_version,
        'pmcid': pmcid, 'bundleSha256': pin, 'target': target, 'unitIds': actual,
        'parentSha256': digest(canonical(original)) if original else None, 'implementation': implementation_pins()}
    if original and original['binding']['stage'] == 'SOURCE_REVIEW':
        binding['reviewMode'] = 'ADDITIONS_ONLY'
    return source, original, payload, binding


def check(source, candidate, binding, parent):
    try:
        if parent:
            return audit_check(source, review_input(parent), candidate, binding['unitIds'], binding['target'])
        return source_check(source, candidate, binding['unitIds'])
    except ValueError as error:
        return {'status': 'SOURCE_CHECK_REJECTED', 'reasonCode': str(error),
                'businessPublishable': False, 'scientificExecution': False, 'facts': []}


def run(library, pmcid, pin, target, unit_ids, config, config_path, budget, output,
        previous=None, execute=False, transport=https_once):
    output = Path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    source, parent, payload, binding = prepare(library, pmcid, pin, target, unit_ids, config, previous)
    output.mkdir(parents=True, exist_ok=False)
    # No credentials, model or network for preflight. Same request is reconstructed
    # from authoritative bytes again at execution and replay.
    (output / 'preflight.json').write_bytes(canonical({'schemaVersion': 'denovo-model-preflight-v1',
        'executeRequested': False, 'configuration': public_config(config), 'binding': binding,
        'requests': [{'requestSha256': digest(canonical(payload)), 'bytes': len(canonical(payload))}]}))
    (output / 'request.json').write_bytes(canonical(payload))
    found = cached(config, payload, budget, binding)
    if found is None:
        from bundle_replay_compatibility import reviewed_profiles
        profiles = reviewed_profiles()
        require(len(profiles) <= 256, 'REPLAY_PROFILE_LIMIT')
        for profile in reversed(profiles):
            if not all(k in profile for k in ('pp1_literature_contract.py', 'run_pp1_literature_model.py')):
                continue
            historical = {**binding, 'implementation': profile}
            variants = [historical, {k: v for k, v in historical.items() if k != 'promptVersion'}]
            for candidate_binding in variants:
                reused = cached(config, payload, budget, candidate_binding)
                if reused is not None:
                    found, binding = reused, candidate_binding
                    break
            if found is not None: break
    if found is None and not execute:
        return {'status': 'MODEL_CACHE_MISSING', 'modelCalls': 0, 'scientificExecution': False}
    try:
        if found is None:
            found = invoke(config, credentials(config, config_path), payload, budget, binding, transport)
    except ValueError as error:
        # The shared ledger retains attempted/unknown charges and the raw response.
        # Never open a new ledger, erase a stop or retry from here.
        (output / 'failure.json').write_bytes(canonical({'status': 'MODEL_EXECUTION_FAILED',
            'reasonCode': str(error), 'sharedRequestKey': cache_identity(config, payload, binding)[1]}))
        raise
    raw, receipt, hit = found
    candidate, unused = decode_response(raw, config, binding['version'])
    checked = check(source, candidate, binding, parent)
    artifact = {'schemaVersion': 'pp1-literature-artifact-v1', 'binding': binding,
        'configuration': public_config(config), 'request': payload, 'parent': parent,
        'responseBase64': base64.b64encode(raw).decode(), 'receipt': receipt,
        'derivationImplementation': implementation_pins(),
        'candidate': candidate, 'checked': checked, 'businessPublishable': False}
    (output / 'artifact.json').write_bytes(canonical(artifact))
    (output / 'facts.json').write_bytes(canonical(checked))
    return {'status': checked.get('status', 'SOURCE_FACTS_REVIEWED' if parent else 'EXTRACTED_AWAITING_REVIEW'),
        'modelCalls': 0 if hit else 1, 'cacheHit': hit, 'factCount': len(checked.get('facts', [])),
        'readingNeeds': len(checked.get('readingNeeds', [])), 'scientificExecution': False}


def replay(library, path):
    artifact = parse(bounded(path, 16 * 1024 * 1024))
    return verify(library, artifact)


def verify(library, artifact, depth=0, verify_derived=True):
    require(depth <= MAX_REVIEW_DEPTH and artifact.get('schemaVersion') == 'pp1-literature-artifact-v1', 'ARTIFACT_SCHEMA_OR_DEPTH')
    b, c = artifact['binding'], artifact['configuration']
    from bundle_replay_compatibility import compatible
    require(compatible(b['implementation'], implementation_pins()), 'IMPLEMENTATION_CHANGED')
    if 'derivationImplementation' in artifact:
        require(compatible(artifact['derivationImplementation'], implementation_pins()), 'DERIVATION_IMPLEMENTATION_CHANGED')
    source = load(library, b['pmcid'], b['bundleSha256'], criterion='PP1')
    parent = artifact.get('parent')
    if parent is not None:
        verify(library, parent, depth + 1, verify_derived=False)
        pb = parent['binding']
        normal = pb['stage'] == 'EXTRACTION' and 'reviewMode' not in b
        followup = pb['stage'] == 'SOURCE_REVIEW' and b.get('reviewMode') == 'ADDITIONS_ONLY'
        require(b['stage'] == 'SOURCE_REVIEW' and b['version'] == AUDIT_VERSION and (normal or followup)
                and all(pb[k] == b[k] for k in ('pmcid', 'bundleSha256', 'target', 'unitIds'))
                and digest(canonical(parent)) == b['parentSha256'], 'PARENT_CHANGED')
    else:
        require(b['stage'] == 'EXTRACTION' and b['version'] == VERSION and b['parentSha256'] is None
                and 'reviewMode' not in b, 'PARENT_REQUIRED')
    payload = request(source, b['target'], b['unitIds'], c, review_input(parent) if parent else None,
                      b.get('promptVersion', 'pp1-review-v1'))
    require(payload == artifact['request'], 'REQUEST_CHANGED')
    receipt = artifact['receipt']
    require(receipt['identity'] == cache_identity(c, payload, b)[0], 'RECEIPT_BINDING_CHANGED')
    raw = base64.b64decode(artifact['responseBase64'], validate=True)
    require(digest(raw) == receipt['responseSha256'], 'RESPONSE_CHANGED')
    candidate, usage = decode_response(raw, c, b['version'])
    require(candidate == artifact['candidate'] and usage == receipt['usage']
            and receipt['modelCalls'] == receipt['networkCalls'] == 1
            and receipt['transportKind'] in {'REAL_PROVIDER', 'SYNTHETIC_TEST'}, 'RECEIPT_CHANGED')
    require(artifact['businessPublishable'] is False, 'PUBLICATION_CHANGED')
    if verify_derived:
        require(check(source, candidate, b, parent) == artifact['checked'], 'FACTS_CHANGED')
    return artifact


def reassess(library, path):
    """Fresh offline derivation from verified original response, never overwrite it.

    Old derived fields are not trusted or used. Unknown implementation profiles,
    changed raw response/request/source/parent/receipt still fail closed.
    """
    raw = bounded(path, 16 * 1024 * 1024)
    artifact = verify(library, parse(raw), verify_derived=False)
    b = artifact['binding']
    source = load(library, b['pmcid'], b['bundleSha256'], criterion='PP1')
    ancestors = []
    parent = artifact.get('parent')
    while parent and parent['binding']['stage'] == 'SOURCE_REVIEW':
        ancestors.append({'originalArtifactSha256': digest(canonical(parent)),
                          'checked': check(source, parent['candidate'], parent['binding'], parent['parent'])})
        parent = parent.get('parent')
    return {'schemaVersion': 'pp1-offline-source-reassessment-v1', 'originalArtifactSha256': digest(raw),
            'storedDerivedResultUsed': False, 'implementation': implementation_pins(),
            'checked': check(source, artifact['candidate'], b, artifact['parent']),
            'priorReviews': ancestors,
            'modelCalls': 0, 'networkCalls': 0}


def collect(library, paths):
    """Merge only revalidated artifacts for one exact source/target, no scoring.

    Repeated work units and quotes cannot create extra observations. Disagreeing
    audits remain visible instead of choosing whichever supports a conclusion.
    """
    from copy import deepcopy
    from pp1_literature_contract import assemble
    require(0 < len(paths) <= 32, 'COLLECTION_LIMIT')
    outer = [reassess(library, p) for p in paths]
    # A saved follow-up carries all actual raw ancestors. Loading that one
    # artifact must not discard earlier facts or count ancestors twice.
    values = list({v['originalArtifactSha256']: v for o in outer
                   for v in [*reversed(o.get('priorReviews', [])), o]}.values())
    checked = [v['checked'] for v in values]
    require(all(c.get('schemaVersion') == 'pp1-source-fact-inventory-v1' for c in checked), 'REVIEWED_INVENTORY_REQUIRED')
    identity, target = checked[0]['sourceIdentity'], checked[0]['target']
    require(all(c['sourceIdentity'] == identity and c['target'] == target for c in checked), 'COLLECTION_IDENTITY_MISMATCH')
    families, facts, scopes = {}, {}, set()
    for v, c in zip(values, checked):
        scopes.update(c['inputScope']['unitIds'])
        for label, roster in c['families'].items():
            if label in families:
                require(families[label]['canonicalLabel'] == roster['canonicalLabel'], 'FAMILY_IDENTITY_CHANGED')
                families[label]['members'].update(roster['members'])
            else: families[label] = deepcopy(roster)
        for f in c['facts']:
            row = facts.setdefault(f['claimId'], deepcopy(f))
            row.setdefault('artifactOrigins', []).append(v['originalArtifactSha256'])
            row['issues'] = sorted(set(row['issues'] + f['issues']))
            if f.get('verification') == 'REJECTED':
                row['verification'] = 'REJECTED'
            # An addition has no audit yet, not a negative audit. Later source
            # review may qualify it regardless of artifact order. Keep every
            # actual verdict so a third report cannot erase disagreement.
            audits = row.get('allSourceAudits', []) + [row.get('sourceAudit'), f.get('sourceAudit')]
            unique = {canonical(a): deepcopy(a) for a in audits if a is not None}
            row['allSourceAudits'] = [unique[k] for k in sorted(unique)]
            if unique:
                row['sourceAudit'] = row['allSourceAudits'][0]
                verdicts = {a['verdict'] for a in row['allSourceAudits']}
                if len(verdicts) > 1:
                    row['issues'] = sorted(set(row['issues'] + ['AUDIT_DISAGREEMENT']))
                row['sourceSupport'] = 'SOURCE_CHECKED_MODEL_SUPPORTED' if verdicts == {'SUPPORTED'} \
                    and not row['issues'] and row.get('verification') != 'REJECTED' else 'UNRESOLVED_OR_CHALLENGED'
    source = load(library, identity['pmcid'], identity['bundleSha256'], criterion='PP1')
    ids = [u['unitId'] for u in source['units'] if u['unitId'] in scopes]
    result = {'families': families, 'facts': list(facts.values()), 'coverage': 'CONTEXT_INSUFFICIENT',
              'groupChecks': [{'group': 'F%02d' % n, 'outcome': 'UNRESOLVED'} for n in range(1, 11)],
              'businessPublishable': False, 'scientificExecution': False}
    result.update(assemble(source, result, target, ids))
    result['artifactPins'] = [v['originalArtifactSha256'] for v in values]
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('library', 'pmcid', 'bundle', 'target', 'units', 'config', 'budget', 'output'):
        p.add_argument('--' + name, required=True)
    p.add_argument('--previous'); p.add_argument('--execute', action='store_true')
    args = p.parse_args()
    from model_transport import config
    print(canonical(run(args.library, args.pmcid, args.bundle, parse(bounded(args.target, 16384)),
        parse(bounded(args.units, 262144)), config(args.config), args.config, args.budget, args.output,
        args.previous, args.execute)).decode())


if __name__ == '__main__':
    main()
