"""Capture bounded local HTTP results, or compare readback after a service restart."""
import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen


def get(path):
    with urlopen('http://127.0.0.1:8086/variant-analyses/' + path, timeout=60) as response:
        raw = response.read(16 * 1024 * 1024 + 1)
    if len(raw) > 16 * 1024 * 1024:
        raise ValueError('DEMO_RESPONSE_SIZE_LIMIT')
    return raw, json.loads(raw)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-root', type=Path, required=True)
    parser.add_argument('--analysis-id', required=True)
    parser.add_argument('--verify-readback', action='store_true')
    args = parser.parse_args()
    import re
    if not re.fullmatch('[a-f0-9-]{36}', args.analysis_id):
        raise ValueError('ANALYSIS_ID_INVALID')
    output = args.runtime_root / 'acceptance'
    output.mkdir(exist_ok=True)
    job_raw, job = get(args.analysis_id)
    ranking_raw, ranking = get(args.analysis_id + '/variants?page=1&pageSize=20&view=CANDIDATES')
    _, runs = get(args.analysis_id + '/acmg/runs')
    if not runs or runs[0]['status'] != 'COMPLETED':
        raise ValueError('COMPLETED_DEMO_RUN_REQUIRED')
    run_raw, run = get(args.analysis_id + '/acmg/runs/' + runs[0]['runId'])
    artifacts = [('job.json', job_raw, job), ('ranking.json', ranking_raw, ranking), ('run.json', run_raw, run)]
    if args.verify_readback:
        for name, _, value in artifacts:
            if json.loads((output / name).read_text(encoding='utf-8')) != value:
                raise ValueError('RESTART_READBACK_CHANGED_' + name)
        archive = json.loads((output / 'private-audit.json').read_text(encoding='utf-8'))
        request_raw = archive['persistedRequestRaw'].encode()
        request = Request('http://127.0.0.1:8086/variant-analyses/' + args.analysis_id + '/acmg/runs',
                          data=request_raw, headers={'Content-Type': 'application/json'}, method='POST')
        with urlopen(request, timeout=60) as response:
            repeated = json.load(response)
        if repeated != run or len(get(args.analysis_id + '/acmg/runs')[1]) != len(runs):
            raise ValueError('REPEATED_REQUEST_CHANGED_RUN')
        result = {'status': 'PASS', 'runId': run['runId'], 'serviceRestartReadbackUnchanged': True,
                  'repeatedSubmissionUnchanged': True, 'newRunCreated': False}
        (output / 'readback-check.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    else:
        for name, raw, _ in artifacts:
            (output / name).write_bytes(raw)
        result = {'status': 'CAPTURED', 'runId': run['runId'], 'providerCalls': run['providerCalls'],
                  'evaluatorCalls': run['evaluatorCalls']}
    print(json.dumps(result))
