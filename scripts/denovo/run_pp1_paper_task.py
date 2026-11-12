"""Private server worker for a bound PP1 paper; no HTTP policy/score input.

The Java queue owns request construction, command lifetime, durable result and
configuration pins. The worker independently checks bytes and local paths, uses
the shared model budget, and resolves the configured adopted knowledge itself.
"""
import argparse
from pathlib import Path
import re

from model_literature_contract import parse, digest, canonical
from model_transport import bounded, config as model_config, atomic
from pp1_context import select
from process_pp1_paper import process, require, public_spellings, paper_recovery


def run(request_path, request_pin, config_path, output):
    raw = bounded(request_path, 262144)
    require(digest(raw) == request_pin, 'PP1_REQUEST_BYTES_CHANGED')
    request = parse(raw)
    discovery = request.get('schemaVersion') == 'pp1-public-discovery-task-v1'
    expected = {'schemaVersion', 'selectionSha256', 'target', 'analysisBinding'}
    if 'searchAliasCoverage' in request:
        expected.add('searchAliasCoverage')
    expected |= ({'continuation'} if 'continuation' in request else set()) if discovery else {'pmcid','bundleSha256'}
    if not discovery and 'processingContinuation' in request:
        expected.add('processingContinuation')
    require(set(request) == expected and (discovery or request['schemaVersion'] == 'pp1-public-paper-task-v1'),
            'PP1_REQUEST_SCHEMA')
    if 'searchAliasCoverage' in request:
        coverage = request['searchAliasCoverage']
        selected = request.get('target', {}).get('variantAliases', [])
        require(isinstance(coverage, dict) and coverage.get('policy') == 'PP1_MANE_CANONICAL_FIRST_MAX20_V1'
                and coverage.get('retrievalOnly') is True and isinstance(coverage.get('deferredAliases'), list),
                'PP1_SEARCH_ALIAS_COVERAGE_INVALID')
        deferred = coverage['deferredAliases']
        require(0 < len(selected) <= 20 and coverage.get('selectedAliases') == len(selected)
                and coverage.get('omittedAliases') == len(deferred)
                and coverage.get('availableAliases') == len(selected) + len(deferred)
                and all(isinstance(a, str) and 0 < len(a) <= 200 and not any(ord(c) < 32 for c in a)
                        for a in selected + deferred)
                and len(set(selected + deferred)) == len(selected) + len(deferred),
                'PP1_SEARCH_ALIAS_COVERAGE_INVALID')
    require(isinstance(request['selectionSha256'],str) and re.fullmatch('[a-f0-9]{64}',request['selectionSha256'])
            and (discovery or isinstance(request['pmcid'], str) and re.fullmatch('PMC[1-9][0-9]*', request['pmcid'])
            and isinstance(request['bundleSha256'],str) and re.fullmatch('[a-f0-9]{64}',request['bundleSha256'])),
            'PP1_REQUEST_IDENTITY_INVALID')
    configuration_raw = bounded(config_path, 16384)
    configuration = parse(configuration_raw)
    require(configuration.get('schemaVersion') == 'denovo-public-literature-workflow-v1'
            and configuration.get('enabled') is True, 'PP1_WORKFLOW_DISABLED')
    root = Path(configuration['projectRoot']).resolve(strict=True)

    def local(field):
        name = configuration.get(field)
        require(isinstance(name, str) and bool(name.strip()), 'PP1_LOCAL_PATH_CONFIGURATION_REQUIRED')
        path = (root / name).resolve()
        require(not Path(name).is_absolute() and path.is_relative_to((root/'local').resolve()), 'PP1_PATH_OUTSIDE_LOCAL')
        return path

    out = Path(output).resolve()
    require(out.is_relative_to(local('work')) and not out.exists(), 'PP1_OUTPUT_OUTSIDE_WORK_OR_EXISTS')
    binding = request['analysisBinding']
    # Select only the exact gene/disease/inheritance context from server-owned
    # files. No embedded caller policy or source author's PP1/PP4 is adopted.
    context = select(root, configuration.get('pp1Knowledge'), binding['selection'],
                     published_inheritance=configuration.get('publishedInheritanceSources', []))
    resolver, shared = None, None
    if configuration.get('coreContext'):
        from core_context_review import load_config
        from core_local_identity import LocalIdentity
        shared, _ = load_config(root, configuration['coreContext'])
        resolver = LocalIdentity(root, shared['identity'])
    model_path = local('modelConfig')
    model_raw = bounded(model_path, 16384)
    allow_model = configuration.get('allowModel') is True
    model = model_config(model_path) if allow_model else parse(model_raw)
    options = dict(allow_model=allow_model, seconds=configuration.get('pp1MaximumSeconds',540),
                   maximum_calls=configuration.get('pp1MaximumCalls',8),
                   maximum_rounds=configuration.get('pp1MaximumReadingRounds',8))
    if discovery:
        from process_pp1_discovery import run as discover
        continuation, prior = None, {}
        parent = request.get('continuation')
        if parent is not None:
            require(isinstance(parent,dict) and set(parent)=={'parentTaskId','parentResultSha256','stateSha256',
                    'statePath','manifestPath','manifestSha256'}, 'PP1_CONTINUATION_SCHEMA')
            path = (root/parent['manifestPath']).resolve(strict=True)
            require(path.is_relative_to(local('work')) and path.name=='artifacts.json', 'PP1_CONTINUATION_PATH')
            manifest = bounded(path,65536)
            require(digest(manifest)==parent['manifestSha256'], 'PP1_CONTINUATION_MANIFEST_CHANGED')
            for item in parse(manifest)['papers']:
                paths = [Path(p).resolve(strict=True) for p in item['acceptedArtifacts']]
                require(all(p.is_relative_to(local('work')) for p in paths),'PP1_ARTIFACT_OUTSIDE_WORK')
                prior[(item['pmcid'],item['bundleSha256'])] = paths
            continuation = {k:parent[k] for k in ('parentTaskId','parentResultSha256','stateSha256','statePath')}
        result = discover(root,local('library'),local('searchCache'),local('modelBudget'),model,model_path,
                          request['target'],binding,context,resolver,out,search_context=shared,
                          allow_network=configuration.get('allowPublicNetwork') is True,
                          maximum_papers=configuration.get('pp1MaximumPapers',5),continuation=continuation,
                          priority_options=configuration.get('readingPriority'),
                          prior_artifacts=prior,**options)
    else:
        accepted, pedigree_reviews = [], []
        parent = request.get('processingContinuation')
        if parent is not None:
            require(isinstance(parent, dict) and set(parent) == {'parentTaskId', 'parentResultSha256'}
                    and all(isinstance(parent.get(k), str) and re.fullmatch('[a-f0-9]{64}', parent[k])
                            for k in ('parentTaskId', 'parentResultSha256')), 'PP1_PROCESSING_CONTINUATION_SCHEMA')
            work = local('work').resolve(strict=True)
            previous = (work / 'pp1-artifacts' / parent['parentTaskId'] / 'paper').resolve(strict=True)
            require(previous.is_relative_to(work) and previous != out, 'PP1_PROCESSING_CONTINUATION_PATH')
            parent_raw = bounded(previous / 'result.json', 16 * 1024 * 1024)
            require(digest(parent_raw) == parent['parentResultSha256'], 'PP1_PROCESSING_CONTINUATION_RESULT_CHANGED')
            prior = parse(parent_raw)
            require(prior.get('schemaVersion') == 'pp1-paper-process-v1'
                    and prior.get('pmcid') == request['pmcid'] and prior.get('bundleSha256') == request['bundleSha256']
                    and prior.get('selectionSha256') == request['selectionSha256']
                    and prior.get('analysisBindingSha256') == digest(canonical(binding))
                    and prior.get('workflowConfigSha256') == digest(configuration_raw),
                    'PP1_PROCESSING_CONTINUATION_IDENTITY')
            pending = paper_recovery(prior.get('stopReason'), prior.get('readingState'))
            require(pending['resumeAvailable'] and pending['status'] in {'EXECUTION_PENDING', 'RESOURCE_REPAIR_REQUIRED'},
                    'PP1_PROCESSING_CONTINUATION_NOT_RESUMABLE')
            from bundle_model_contract import load
            source = load(local('library'), request['pmcid'], request['bundleSha256'], criterion='PP1')
            from pp1_reviewed_cache import target_key
            paper_target = {'geneSymbol': request['target']['geneSymbol'], 'variantAliases': public_spellings(
                '\n'.join(u['text'] for u in source['units']), request['target']['variantAliases'])}
            require(paper_target['variantAliases'] and target_key(prior.get('target', {})) == target_key(paper_target),
                    'PP1_PROCESSING_CONTINUATION_TARGET')
            manifest_raw = bounded(previous / 'artifacts.json', 65536)
            require(digest(manifest_raw) == prior.get('artifactManifestSha256'),
                    'PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED')
            manifest = parse(manifest_raw)
            require(isinstance(manifest, dict) and set(manifest) <= {'acceptedArtifacts', 'pedigreeReviews'}
                    and isinstance(manifest.get('acceptedArtifacts'), list) and len(manifest['acceptedArtifacts']) <= 32,
                    'PP1_PROCESSING_CONTINUATION_MANIFEST_SCHEMA')
            from run_pp1_literature_model import verify
            from literature_manual_review import VERSION as MANUAL_REVIEW, read as read_manual
            accepted_pins = []
            for name in manifest['acceptedArtifacts']:
                require(isinstance(name, str), 'PP1_PROCESSING_CONTINUATION_ARTIFACT_PATH')
                path = Path(name).resolve(strict=True)
                require(path.is_relative_to(work), 'PP1_PROCESSING_CONTINUATION_ARTIFACT_PATH')
                artifact_raw = bounded(path, 16 * 1024 * 1024)
                document = parse(artifact_raw)
                if document.get('schemaVersion') == MANUAL_REVIEW:
                    read_manual(local('library'), document)
                    original = document
                else:
                    original = verify(local('library'), document, verify_derived=False)
                b = original['binding']
                require(b['stage'] == 'SOURCE_REVIEW' and b['pmcid'] == request['pmcid']
                        and b['bundleSha256'] == request['bundleSha256'] and target_key(b['target']) == target_key(paper_target),
                        'PP1_PROCESSING_CONTINUATION_ARTIFACT_IDENTITY')
                accepted.append(path)
                accepted_pins.append(digest(artifact_raw))
            if pending['status'] == 'RESOURCE_REPAIR_REQUIRED':
                checkpoint = prior['readingState']['checkpoint']
                require(checkpoint['pmcid'] == request['pmcid'] and checkpoint['bundleSha256'] == request['bundleSha256']
                        and len(accepted_pins) == len(set(accepted_pins))
                        and set(accepted_pins) == set(checkpoint['acceptedArtifactSha256s']),
                        'PP1_PROCESSING_CONTINUATION_CHECKPOINT_CHANGED')
            from pp1_pedigree_delivery import replay_paths
            from pp1_pedigree import verify_pair
            entries = manifest.get('pedigreeReviews', [])
            pairs = replay_paths(root, work, entries)
            for entry, (figure, pair) in zip(entries, pairs):
                unused, checked = verify_pair(source, figure, pair)
                require(target_key(checked['target']) == target_key(paper_target), 'PP1_PROCESSING_CONTINUATION_PEDIGREE_TARGET')
                pedigree_reviews.append((entry, figure, pair))
        result = process(local('library'), request['pmcid'], request['bundleSha256'], request['target'],
                     binding, context, resolver, model, model_path, local('modelBudget'), out,
                     accepted_artifacts=accepted, accepted_pedigree_reviews=pedigree_reviews, **options)
    require(configuration_raw == bounded(config_path, 16384) and model_raw == bounded(model_path, 16384),
            'PP1_CONFIGURATION_CHANGED_DURING_TASK')
    result.update(requestSha256=request_pin, selectionSha256=request['selectionSha256'],
                  workflowConfigSha256=digest(configuration_raw))
    if 'searchAliasCoverage' in request:
        result['searchAliasCoverage'] = request['searchAliasCoverage']
    atomic(out/'result.json', result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--request', required=True)
    p.add_argument('--request-sha256', required=True)
    p.add_argument('--config', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    result = run(args.request, args.request_sha256, args.config, args.output)
    print(result['status'])


if __name__ == '__main__': main()
