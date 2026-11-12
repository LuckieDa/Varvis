"""Offline source-only intake check; never adopts scientific conclusions."""
import argparse
import base64
from pathlib import Path

from bundle_candidate_intake import replay_intake, MAX_BYTES, require
from model_literature_contract import parse, canonical, digest
from model_transport import bounded


def check(path, work):
    raw = bounded(path, MAX_BYTES); intake = parse(raw)
    candidates = [base64.b64decode(v['base64'], validate=True) for v in intake['files'].values()
                  if v['sha256'] == intake['sourceSha256']]
    require(bool(candidates) and all(v == candidates[0] for v in candidates), 'SOURCE_MISSING_OR_AMBIGUOUS')
    source = candidates[0]
    facts = replay_intake(source, intake['pmcid'], intake['sourceSha256'], intake, work_root=work)
    return {'schemaVersion': 'denovo-source-intake-check-v1', 'status': 'SOURCE_REPLAYED_NOT_SCORED',
            'intakeSha256': digest(raw), 'pmcid': intake['pmcid'], 'sourceSha256': intake['sourceSha256'],
            'bundleSha256': intake['bundleSha256'], 'cases': len(facts['cases']),
            'transportKind': facts['modelProvenance']['receipt']['transportKind'],
            'modelCalls': 0, 'networkCalls': 0, 'clinicalAdoption': 'NONE'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--intake', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = check(args.intake, args.output.parent)
    with args.output.open('xb') as stream:stream.write(canonical(result))
