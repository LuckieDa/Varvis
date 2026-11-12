"""GET-only closeout checks; this never creates a run or changes source files."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

DATASETS = {
    'bs1': ('13bce19e-eab3-41d3-8218-5a38c0ae7aa3', 'bb85990e-4060-44ec-a9a1-27ce691c4041',
            'e0e02f69-c69d-4f13-ab3e-bca6b603e9b3', 'c475569d8091310bf695df3e5d19696e36e175646a803e91fdff5ef1e36fd251', 12),
    'pvs1': ('ee6bc47b-6af6-4c40-bbc6-bd6157ddf192', 'b927c195-670a-4008-859a-b7952b6e7379',
             '70ca0619-7acf-43ab-a07b-86e152e89cfe', '77092d5a5e46f50cedc279d0ae53ce09050b0ac9e12cacad0c88c527178bcbf8', 8),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--session', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--recorded', action='store_true', help='Validate a previous GET capture without sending requests')
    args = parser.parse_args()
    config = json.loads(Path(args.session).read_text(encoding='utf-8-sig'))
    output = Path(args.output)
    if not args.recorded:
        output.mkdir(parents=True, exist_ok=False)
    dataset = config['dataset']
    analysis, run_id, old_id, expected_sha, count = DATASETS[dataset]
    base = f"http://127.0.0.1:{config['pagePort']}"
    records = json.loads((output / 'requests.json').read_text()) if args.recorded else []

    def get(path, name, optional=False):
        if args.recorded:
            record = next(row for row in records if row['file'] == name and row['path'] == path)
            data = (output / name).read_bytes()
            assert len(data) == record['bytes'] and hashlib.sha256(data).hexdigest() == record['sha256']
            assert optional or record['status'] == 200
            return data
        try:
            with urlopen(Request(base + path, headers={'Accept': '*/*'}), timeout=120) as response:
                status, data = response.status, response.read()
        except HTTPError as error:
            status, data = error.code, error.read()
        digest = hashlib.sha256(data).hexdigest()
        records.append({'path': path, 'status': status, 'bytes': len(data), 'sha256': digest, 'file': name})
        (output / name).write_bytes(data)
        (output / 'requests.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        if not optional:
            assert status == 200, (path, status)
        return data

    def document(path, name):
        return json.loads(get(path, name))

    before = document('/__varvis_ps1pm5_calls', 'calls-before.json')
    identity = document('/__varvis_ps1pm5_instance', 'identity.json')
    assert identity['readOnlyPage'] is True
    assert identity['sourceHead'] == config['sourceHead']
    html = get('/', 'page.html').decode('utf-8')
    for i, asset in enumerate(re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"', html)):
        get(asset, f'asset-{i}.body')
    # The optional chromosome knowledge base is separate from the saved analysis.
    get('/variantBasedMap/chromosomes', 'chromosomes.json', optional=True)
    api = f'/variant-analyses/{analysis}'
    job = document(api, 'job.json')
    assert job['id'] == analysis and job['status'] == 'READY'
    document('/variant-analyses/providers', 'providers.json')
    document('/variant-analyses/preflight?genomeAssembly=GRCh38', 'preflight.json')
    document(api + '/events', 'events.json')
    variants = document(api + '/variants?page=1&pageSize=20&view=CANDIDATES', 'variants.json')
    assert len(variants['variants']) == count
    for i, candidate in enumerate(variants['variants']):
        key = candidate.get('candidateId') or candidate.get('id')
        if key:
            document(api + '/candidates/' + key, f'candidate-{i}.json')
    runs = document(api + '/acmg/runs', 'runs.json')
    assert {run_id, old_id}.issubset({row['runId'] for row in runs})
    run_path = api + '/acmg/runs/' + run_id
    raw = get(run_path, 'saved-run.json')
    assert hashlib.sha256(raw).hexdigest() == expected_sha
    value = json.loads(raw)
    assert value['runId'] == run_id
    old = document(api + '/acmg/runs/' + old_id, 'historical-run.json')
    assert old['runId'] == old_id
    get(api + '/exports/json?limit=20&view=CANDIDATES', 'analysis-export.json')
    get(api + '/exports/csv?limit=20&view=CANDIDATES', 'analysis-export.csv')
    # The existing run download is a browser Blob of JSON.stringify(run, null, 2).
    document(api + '/acmg/runs/' + run_id + '/splicing-checks', 'saved-splicing-checks.json')
    assert get(run_path, 'saved-run-repeat.json') == raw
    after_reads = document('/__varvis_ps1pm5_calls', 'calls-after-reads.json')
    assert after_reads == before, (before, after_reads)
    document(api + '/acmg/input-options?topN=20', 'input-options.json')
    after_options = document('/__varvis_ps1pm5_calls', 'calls-after-options.json')
    delta = {key: after_options[key] - after_reads[key] for key in after_reads}
    assert all(value == 0 for key, value in delta.items() if key not in {'amIdentityLookup', 'coordinatorFactory'}), delta
    result = {'dataset': dataset, 'status': 'READ_ROUTES_VERIFIED_UI_PENDING',
              'analysisId': analysis, 'runId': run_id, 'responseSha256': expected_sha,
              'readCallDelta': {key: after_reads[key] - before[key] for key in before},
              'inputOptionsCallDelta': delta, 'requests': records}
    result['validationMode'] = 'RECORDED_HTTP_REVALIDATION' if args.recorded else 'FRESH_HTTP'
    (output / 'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({key: result[key] for key in ['dataset', 'status', 'runId', 'readCallDelta', 'inputOptionsCallDelta']}))


if __name__ == '__main__':
    main()
