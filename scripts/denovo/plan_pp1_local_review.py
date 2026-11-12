"""Determine which exact PP1 attempts need a source worker without public requests."""
import argparse
from pathlib import Path

from model_literature_contract import canonical, digest, parse
from model_transport import bounded
from pp1_literature_contract import require, validate_target
from pp1_reviewed_cache import discover


def plan(request, library, budget, search_cache, allow_network):
    rows = request['attempts']
    require(request.get('schemaVersion') == 'pp1-local-review-input-v1' and isinstance(rows, list)
            and len(rows) <= 400, 'PP1_LOCAL_REVIEW_INPUT_INVALID')
    can_search = allow_network or any(p.is_file() for p in search_cache.glob('*.json'))
    results = []
    for row in rows:
        validate_target(row['target'])
        reviewed = discover(library, budget, row['target'], row['selection'])
        matched = bool(reviewed['candidates'])
        results.append({'candidateId': row['candidateId'], 'hypothesisId': row['hypothesisId'],
                        'localMatch': matched, 'needsWorker': can_search or matched or not reviewed['complete']})
    return results


def main():
    args = argparse.ArgumentParser(description=__doc__)
    for name in ('config', 'request', 'output'):
        args.add_argument('--' + name, type=Path, required=True)
    paths = args.parse_args()
    raw_config = bounded(paths.config, 16384)
    raw_request = bounded(paths.request, 1024*1024)
    configuration = parse(raw_config)
    require(configuration.get('schemaVersion') == 'denovo-public-literature-workflow-v1'
            and configuration.get('enabled') is True, 'PP1_WORKFLOW_DISABLED')
    root = Path(configuration['projectRoot']).resolve(strict=True)

    def local(key):
        configured = Path(configuration[key])
        path = (root/configured).resolve()
        require(not configured.is_absolute() and path.is_relative_to((root/'local').resolve()), 'PP1_PATH_OUTSIDE_LOCAL')
        return path

    work = local('work')
    require(paths.request.resolve(strict=True).is_relative_to(work) and paths.output.resolve().is_relative_to(work),
            'PP1_OUTPUT_OUTSIDE_WORK_OR_EXISTS')
    result = plan(parse(raw_request), local('library'), local('modelBudget'), local('searchCache'),
                  configuration.get('allowPublicNetwork') is True)
    paths.output.write_bytes(canonical({'requestSha256': digest(raw_request), 'workflowConfigSha256': digest(raw_config), 'rows': result}))


if __name__ == '__main__':
    main()
