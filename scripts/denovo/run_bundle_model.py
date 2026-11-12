"""Bounded public source-bundle extraction and independently reproducible review."""
import base64
from pathlib import Path

from bundle_model_contract import load, requests, review, implementation, VERSION, require
from bundle_model_transport import invoke, decode_response, cached, candidate_version
from model_literature_contract import canonical, digest, parse
from model_transport import bounded, public_config, credentials, https_once
from bundle_replay_compatibility import compatible, reviewed_profiles


def profile_scope(extraction_profile, criterion, saved_implementation=None):
    if extraction_profile == 'pp2-relationship-material-v1':
        require(criterion == 'PP2', 'RELATIONSHIP_PROFILE_REQUIRES_PP2')
        if saved_implementation is not None:
            from re import fullmatch
            require(isinstance(saved_implementation, dict) and
                    isinstance(saved_implementation.get('pp2_relationship_material.py'), str) and
                    fullmatch(r'[a-f0-9]{64}', saved_implementation['pp2_relationship_material.py']),
                    'RELATIONSHIP_QUESTION_IMPLEMENTATION_REQUIRED')


def identity(pmcid, pin, index, count, extraction_profile='legacy-v1', *, criterion='PS2'):
    profile_scope(extraction_profile, criterion)
    value = {'version': VERSION, 'pmcid': pmcid, 'bundleSha256': pin, 'implementation': implementation(),
            'chunkIndex': index, 'chunkCount': count}
    if extraction_profile != 'legacy-v1':
        require(extraction_profile in ('core-material-v1', 'core-material-v2', 'pp2-relationship-material-v1'),
                'EXTRACTION_PROFILE_INVALID')
        value['extractionProfile'] = extraction_profile
    if criterion != 'PS2':
        # Purpose identifies the actual registered source view. It is neither a
        # scientific review nor permission to adopt any resulting evidence.
        value['criterion'] = criterion
    return value


def run(library, pmcid, pin, c, config_path, state, output, transport=https_once, chunk_index=0, cache_only=False,
        extraction_profile='legacy-v1', expected_artifact_sha256=None, *, criterion='PS2'):
    profile_scope(extraction_profile, criterion)
    source = load(library, pmcid, pin, criterion=criterion)
    plans = requests(source, c['model'], c['maxOutputTokens'], c['maxRequestBytes'], extraction_profile)
    require(type(chunk_index) is int and 0 <= chunk_index < len(plans), 'CHUNK_INVALID')
    payload = plans[chunk_index]
    content = parse(payload['messages'][1]['content'])
    require(content['workWithinLimit'], 'TARGET_WORK_LIMIT_NOT_TRUNCATED')
    binding = identity(pmcid, pin, chunk_index, len(plans), extraction_profile, criterion=criterion)
    found = cached(c,payload,state,binding)
    historical = False
    if found is None:
        for profile in reviewed_profiles():
            if extraction_profile == 'pp2-relationship-material-v1' and 'pp2_relationship_material.py' not in profile:
                continue
            previous = {**binding, 'implementation': profile}
            found = cached(c, payload, state, previous)
            if found is None:
                continue
            # Request/source identity is part of the cache key; preserve the old
            # receipt rather than issuing a new paid call after reader changes.
            binding = previous
            historical = True
            break
    if found is None:
        if cache_only:
            return None
        key = credentials(c, config_path)
        found = invoke(c, key, payload, state, binding, transport)
    raw, receipt, cache_hit = found
    candidate, unused = decode_response(raw, c, candidate_version(payload))
    result = review(source, candidate, {u['unitId'] for u in content['units']},
                    set(content['workUnit']['targetUnitIds']), actual_payload=payload)
    result['workUnit'] = content['workUnit']
    artifact = {'schemaVersion': 'denovo-bundle-model-artifact-v1', 'binding': binding,
                'configuration': public_config(c), 'requestSha256': digest(canonical(payload)),
                'responseBase64': base64.b64encode(raw).decode(), 'receipt': receipt,
                'review': result, 'businessPublishable': False}
    if historical:
        artifact['offlineRequalification'] = {
            'implementation': implementation(), 'originalReceiptSha256': digest(canonical(receipt)),
            'modelCalls': 0, 'networkCalls': 0}
    if expected_artifact_sha256 is not None and digest(canonical(artifact)) != expected_artifact_sha256:
        # Parent checkpoints pin the entire saved artifact, including its old
        # offline review identity. Reconstruct only explicitly supported formats
        # and accept only an exact checkpoint SHA, never rewrite the old receipt.
        require(cache_only and historical, 'CONTINUATION_CACHE_CHANGED')
        base = {k: v for k, v in artifact.items() if k != 'offlineRequalification'}
        variants = [base] + [{**base, 'offlineRequalification': {
            'implementation': p, 'originalReceiptSha256': digest(canonical(receipt)),
            'modelCalls': 0, 'networkCalls': 0}} for p in reviewed_profiles()]
        matches = [a for a in variants if digest(canonical(a)) == expected_artifact_sha256]
        require(len(matches) == 1, 'CONTINUATION_CACHE_CHANGED')
        artifact = matches[0]
    output = Path(output)
    output.mkdir(exist_ok=False)
    (output / 'artifact.json').write_bytes(canonical(artifact))
    (output / 'review.json').write_bytes(canonical(result))
    return {'pmcid': pmcid, 'bundleSha256': pin,
            'status': result['sourceFactStatus'] if extraction_profile == 'pp2-relationship-material-v1' else result['status'],
            'chunkIndex': chunk_index, 'chunkCount': len(plans),
            'artifactSha256': digest(canonical(artifact)), 'review': result,
            'modelCalls': 0 if cache_hit else 1, 'cacheHit': cache_hit,
            'estimatedMicroCreditsThisRun': 0 if cache_hit else receipt['estimatedMicroCredits']}


def replay(library, artifact_path, *, criterion=None):
    artifact = parse(bounded(artifact_path, 8 * 1024 * 1024))
    require(artifact.get('schemaVersion') == 'denovo-bundle-model-artifact-v1', 'ARTIFACT_SCHEMA')
    binding = artifact['binding']
    purpose = binding.get('criterion', 'PS2')
    profile_scope(binding.get('extractionProfile', 'legacy-v1'), purpose, binding.get('implementation'))
    require(criterion is None or criterion == purpose, 'ARTIFACT_CRITERION_MISMATCH')
    require(binding.get('version') == VERSION and compatible(binding.get('implementation'), implementation()),
            'ARTIFACT_IMPLEMENTATION_CHANGED')
    if 'offlineRequalification' in artifact:
        offline = artifact['offlineRequalification']
        require(set(offline) == {'implementation', 'originalReceiptSha256', 'modelCalls', 'networkCalls'}
                and compatible(offline['implementation'], implementation())
                and offline['originalReceiptSha256'] == digest(canonical(artifact['receipt']))
                and offline['modelCalls'] == offline['networkCalls'] == 0, 'REQUALIFICATION_BINDING_MISMATCH')
    source = load(library, binding['pmcid'], binding['bundleSha256'], criterion=purpose)
    c = artifact['configuration']
    plans = requests(source, c['model'], c['maxOutputTokens'], c['maxRequestBytes'], binding.get('extractionProfile', 'legacy-v1'))
    require(binding['chunkCount'] == len(plans) and 0 <= binding['chunkIndex'] < len(plans), 'CHUNK_INVALID')
    payload = plans[binding['chunkIndex']]
    receipt = artifact['receipt']
    from bundle_model_transport import VERSION as transport_version
    expected = {'transportVersion': transport_version, 'configuration': c,
                'requestSha256': digest(canonical(payload)), 'binding': binding}
    require(receipt['identity'] == expected and artifact['requestSha256'] == expected['requestSha256'],
            'ARTIFACT_BINDING_MISMATCH')
    raw = base64.b64decode(artifact['responseBase64'], validate=True)
    require(digest(raw) == receipt['responseSha256'], 'RESPONSE_PIN_MISMATCH')
    candidate, usage = decode_response(raw, c, candidate_version(payload))
    require(usage == receipt['usage'] and receipt['modelCalls'] == receipt['networkCalls'] == 1 and
            receipt['transportKind'] in ('REAL_PROVIDER', 'SYNTHETIC_TEST'), 'RECEIPT_INVALID')
    content = parse(payload['messages'][1]['content'])
    result = review(source, candidate, {u['unitId'] for u in content['units']},
                    set(content['workUnit']['targetUnitIds']), actual_payload=payload)
    result['workUnit'] = content['workUnit']
    require(result == artifact['review'], 'REVIEW_CHANGED')
    return result


def requalify_saved(library, request_path, response_path, receipt_path, trusted_receipt_sha256, output):
    """Recheck a frozen response locally; preserve its original implementation.

    Requires an externally pinned original receipt and exact reconstruction of
    the original request under the current source. No cache rewrite or provider
    transport is possible here, including for responses rejected before review.
    """
    original_receipt = bounded(receipt_path, 1048576)
    require(digest(original_receipt) == trusted_receipt_sha256, 'ORIGINAL_RECEIPT_PIN_MISMATCH')
    receipt = parse(original_receipt)
    binding = receipt['identity']['binding']
    profile_scope(binding.get('extractionProfile', 'legacy-v1'), binding.get('criterion', 'PS2'),
                  binding.get('implementation'))
    require(binding.get('version') == VERSION and compatible(binding.get('implementation'), implementation()),
            'ARTIFACT_IMPLEMENTATION_CHANGED')
    source = load(library, binding['pmcid'], binding['bundleSha256'], criterion=binding.get('criterion', 'PS2'))
    c = receipt['identity']['configuration']
    plans = requests(source, c['model'], c['maxOutputTokens'], c['maxRequestBytes'], binding.get('extractionProfile', 'legacy-v1'))
    require(binding['chunkCount'] == len(plans) and 0 <= binding['chunkIndex'] < len(plans), 'CHUNK_INVALID')
    payload = plans[binding['chunkIndex']]
    original_request = parse(bounded(request_path, 262144))
    require(canonical(original_request) == canonical(payload), 'ORIGINAL_REQUEST_CHANGED')
    require(digest(canonical(payload)) == receipt['identity']['requestSha256'], 'ARTIFACT_BINDING_MISMATCH')
    raw = bounded(response_path, c['maxResponseBytes'])
    require(digest(raw) == receipt['responseSha256'], 'RESPONSE_PIN_MISMATCH')
    candidate, usage = decode_response(raw, c, candidate_version(payload))
    require(usage == receipt['usage'], 'RECEIPT_INVALID')
    content = parse(payload['messages'][1]['content'])
    result = review(source, candidate, {u['unitId'] for u in content['units']},
                    set(content['workUnit']['targetUnitIds']), actual_payload=payload)
    result['workUnit'] = content['workUnit']
    artifact = {'schemaVersion': 'denovo-bundle-model-artifact-v1', 'binding': binding,
        'configuration': c, 'requestSha256': digest(canonical(payload)),
        'responseBase64': base64.b64encode(raw).decode(), 'receipt': receipt, 'review': result,
        'businessPublishable': False, 'offlineRequalification': {
            'implementation': implementation(), 'originalReceiptSha256': digest(canonical(receipt)),
            'modelCalls': 0, 'networkCalls': 0}}
    output = Path(output)
    output.mkdir(exist_ok=False)
    path = output / 'artifact.json'
    path.write_bytes(canonical(artifact))
    # The same public replay path must accept the complete newly built artifact.
    replay(library, path)
    (output / 'review.json').write_bytes(canonical(result))
    return result
