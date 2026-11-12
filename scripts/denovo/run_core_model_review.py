"""Explicit opt-in source review; shared transport/budget/cache, offline replay.

The saved original and reviewer suggestions are immutable provenance. Only the
existing deterministic source reviewer may populate a VERIFIED source fact.
"""
import argparse
import base64
import re
from pathlib import Path

from model_literature_contract import canonical, digest, parse
from model_transport import bounded, credentials, public_config, https_once
from bundle_model_contract import load, requests, require
from bundle_model_transport import cached, invoke, decode_response, candidate_version, cache_identity
from run_bundle_model import replay as replay_extraction
from core_model_review_contract import request, check_review, implementation, replay_profiles, VERSION


def prepare(library, original_path, c):
    original_raw = bounded(original_path, 8 * 1024 * 1024)
    original = parse(original_raw)
    initial = replay_extraction(library, original_path)
    b = original['binding']
    require(b.get('extractionProfile') in ('core-material-v1', 'core-material-v2'), 'CORE_EXTRACTION_PROFILE_REQUIRED')
    source = load(library, b['pmcid'], b['bundleSha256'])
    old_c = original['configuration']
    old_request = requests(source, old_c['model'], old_c['maxOutputTokens'], old_c['maxRequestBytes'],
                           b['extractionProfile'])[b['chunkIndex']]
    candidate, unused = decode_response(base64.b64decode(original['responseBase64'], validate=True), old_c,
                                        candidate_version(old_request))
    payload = request(source, candidate, old_request, initial, c)
    binding = {'version': VERSION, 'originalArtifactSha256': digest(original_raw),
               'pmcid': b['pmcid'], 'bundleSha256': b['bundleSha256'],
               'implementation': implementation()}
    return source, candidate, payload, binding, original_raw


def materialize(source, checked, original_raw, output):
    from qualify_bundle_candidates import qualify
    from core_material_extraction import write_package
    from core_material_review import review_package
    reviews = checked['reviews']
    facts = qualify(source, reviews)
    raw = output / 'core-raw'
    write_package(source, facts, reviews, raw, intake={
        'schemaVersion': 'denovo-core-review-provenance-v1',
        'originalArtifactSha256': digest(original_raw), 'originalArtifact': parse(original_raw),
        'reviewProposals': checked, 'businessPublishable': False})
    final = review_package(source, raw, digest((raw / 'index.json').read_bytes()), output / 'core-reviewed')
    return {'rawIndexSha256': digest((raw / 'index.json').read_bytes()),
            'reviewedIndexSha256': digest((output / 'core-reviewed/index.json').read_bytes()),
            'cases': final['cases'], 'status': final['status'], 'criterionDecision': 'NOT_RUN'}


def run(library, original_path, c, config_path, state, output, cache_only=False, transport=https_once):
    output = Path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    source, candidate, payload, binding, original_raw = prepare(library, original_path, c)
    found = cached(c, payload, state, binding)
    if found is None:
        for previous in replay_profiles():
            historical = {**binding, 'implementation': previous}
            found = cached(c, payload, state, historical)
            if found is not None:
                binding = historical
                break
    if found is None and (Path(state)/'budget.json').is_file():
        # Reader improvements can change diagnostic hints or selected context
        # units without changing the paid extraction. Reuse only the actual
        # historical request, validated by the existing replay contract.
        from core_model_review_contract import historical_request
        ledger=parse(bounded(Path(state)/'budget.json',262144))
        keys={r['key'] for r in ledger['calls'] if r.get('status')=='SUCCESS'}
        keys.update(r['key'] for r in ledger.get('receivedResponseRecoveries',[]))
        require(len(keys)<=100,'REVIEW_CACHE_LEDGER_LIMIT')
        for key in sorted(keys):
            require(re.fullmatch('[a-f0-9]{64}',key) is not None,'REVIEW_CACHE_KEY_INVALID')
            folder=Path(state)/'responses'/key
            receipt_path=folder/'receipt.json'
            if not receipt_path.is_file():continue
            receipt=parse(bounded(receipt_path,65536));old=receipt.get('identity',{})
            prior=old.get('binding',{})
            if old.get('configuration')!=public_config(c) or (
                {k:v for k,v in prior.items() if k!='implementation'} !=
                {k:v for k,v in binding.items() if k!='implementation'}):continue
            if prior.get('implementation') not in [implementation(),*replay_profiles()]:continue
            saved=parse(bounded(folder/'request.json',c['maxRequestBytes']))
            try:historical_request(source,payload,saved)
            except ValueError:continue  # Different claims/prompt is not a reuse candidate.
            candidate_cache=cached(c,saved,state,prior)
            if candidate_cache is not None:
                payload,binding,found=saved,prior,candidate_cache
                break
    if found is None:
        if cache_only:
            return None
        found = invoke(c, credentials(c, config_path), payload, state, binding, transport)
    response, receipt, hit = found
    proposal, unused = decode_response(response, c, VERSION)
    # Preserve even a rejected reviewer response via the shared immutable cache.
    checked = check_review(source, candidate, payload, proposal)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'original-extraction.json').write_bytes(original_raw)
    artifact = {'schemaVersion': 'denovo-core-source-audit-artifact-v1', 'binding': binding,
                'configuration': public_config(c), 'receipt': receipt,
                'responseBase64': base64.b64encode(response).decode(), 'review': checked,
                'businessPublishable': False}
    (output / 'artifact.json').write_bytes(canonical(artifact))
    (output / 'request.json').write_bytes(canonical(payload))
    (output / 'review-proposals.json').write_bytes(canonical(checked))
    final = materialize(source, checked, original_raw, output)
    result = {'artifactSha256': digest(canonical(artifact)), 'modelCalls': 0 if hit else 1,
              'cacheHit': hit, 'estimatedMicroCreditsThisRun': 0 if hit else receipt['estimatedMicroCredits'],
              'material': final, 'businessPublishable': False}
    (output / 'result.json').write_bytes(canonical(result))
    return result


def replay(library, folder):
    folder = Path(folder)
    artifact = parse(bounded(folder / 'artifact.json', 8 * 1024 * 1024))
    require(artifact.get('schemaVersion') == 'denovo-core-source-audit-artifact-v1', 'REVIEW_ARTIFACT_SCHEMA')
    source, candidate, payload, binding, original = prepare(library, folder / 'original-extraction.json', artifact['configuration'])
    saved_binding = artifact['binding']
    require(saved_binding.get('implementation') in [binding['implementation'], *replay_profiles()]
            and {k: v for k, v in saved_binding.items() if k != 'implementation'} ==
                {k: v for k, v in binding.items() if k != 'implementation'}, 'REVIEW_IMPLEMENTATION_OR_SOURCE_CHANGED')
    binding = saved_binding  # Preserve the old receipt; exact full request below.
    saved_request = parse((folder / 'request.json').read_bytes())
    if saved_request != payload:
        from core_model_review_contract import historical_request
        payload = historical_request(source, payload, saved_request)
    expected, unused = cache_identity(artifact['configuration'], payload, binding)
    receipt = artifact['receipt']
    require(receipt['identity'] == expected and parse((folder / 'request.json').read_bytes()) == payload,
            'REVIEW_RECEIPT_BINDING')
    raw = base64.b64decode(artifact['responseBase64'], validate=True)
    require(digest(raw) == receipt['responseSha256'], 'REVIEW_RESPONSE_PIN')
    proposal, usage = decode_response(raw, artifact['configuration'], VERSION)
    require(usage == receipt['usage'] and receipt['modelCalls'] == receipt['networkCalls'] == 1 and
            receipt['transportKind'] in ('REAL_PROVIDER', 'SYNTHETIC_TEST'), 'REVIEW_RECEIPT_INVALID')
    checked = check_review(source, candidate, payload, proposal)
    require(checked == artifact['review'], 'REVIEW_RESULT_CHANGED')
    return checked


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', required=True)
    parser.add_argument('--artifact', required=True, help='Pinned prior core-material-v1 extraction')
    parser.add_argument('--config', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--cache-only', action='store_true')
    args = parser.parse_args()
    from model_transport import config
    c = parse(bounded(args.config, 65536)) if args.cache_only else config(args.config)
    print(canonical(run(args.library, args.artifact, c, args.config, args.state, args.output,
                        cache_only=args.cache_only)).decode('utf-8'))
