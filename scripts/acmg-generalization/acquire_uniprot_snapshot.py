"""Explicit, bounded acquisition of a fixed public protein list; never called by variant evaluation.

Native response bytes and release headers are preserved. A worker process enforces a total
per-record timeout (including the response body). No retries, redirect following or arbitrary URLs.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

VERSION = '1.0.0'
MAX_RECORD = 2_000_000
MAX_TOTAL = 32_000_000
MAX_COUNT = 50
PER_RECORD_SECONDS = 35
TOTAL_SECONDS = 240


def stamp():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=True, indent=2) + '\n').encode('ascii')


def require(ok, code):
    if not ok:
        raise ValueError(code)


def identifiers(values):
    require(isinstance(values, list) and 0 < len(values) <= MAX_COUNT, 'ACCESSION_COUNT_INVALID')
    require(all(isinstance(x, str) and re.fullmatch(r'[A-Z0-9]{6,10}', x) for x in values), 'ACCESSION_INVALID')
    require(len(set(values)) == len(values), 'ACCESSION_DUPLICATE')
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def worker(accession, directory):
    identifiers([accession])
    target = Path(directory)
    item = dict(accession=accession, url=f'https://rest.uniprot.org/uniprotkb/{accession}.json',
                startedAt=stamp(), success=False, transferredBytes=0)
    body_path = target / f'{accession}.body'
    try:
        request = urllib.request.Request(item['url'], headers={'Accept': 'application/json', 'Accept-Encoding': 'identity',
                                                               'User-Agent': 'Varvis-ProteinSnapshot/1.0'})
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=20) as response:
            item.update(status=response.status, headers=str(response.headers))
            with body_path.open('xb') as body:
                while True:
                    block = response.read(min(8192, MAX_RECORD + 1 - item['transferredBytes']))
                    if not block:
                        break
                    item['transferredBytes'] += len(block)
                    if item['transferredBytes'] > MAX_RECORD:
                        raise ValueError('RESPONSE_BYTE_LIMIT')
                    body.write(block)
            require(response.status == 200, 'HTTP_STATUS_NOT_200')
            item.update(success=True, bytes=body_path.stat().st_size, sha256=sha(body_path.read_bytes()))
    except urllib.error.HTTPError as error:
        item.update(status=error.code, headers=str(error.headers), error=f'HTTP_{error.code}')
    except (OSError, ValueError) as error:
        item['error'] = type(error).__name__ + ':' + str(error)
    finally:
        item['finishedAt'] = stamp()
        (target / f'{accession}.receipt.json').write_bytes(encode(item))


def run_worker(accession, directory, seconds):
    started = stamp()
    try:
        subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', accession, str(directory)],
                       check=True, timeout=seconds, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        return json.loads((directory / f'{accession}.receipt.json').read_bytes())
    except subprocess.TimeoutExpired:
        # subprocess.run kills and waits for this worker; it creates no descendant processes.
        return dict(accession=accession, url=f'https://rest.uniprot.org/uniprotkb/{accession}.json', success=False,
                    startedAt=started, finishedAt=stamp(), error='TOTAL_RECORD_TIMEOUT', workerReaped=True,
                    transferredBytes=(directory / f'{accession}.body').stat().st_size if (directory / f'{accession}.body').exists() else 0)
    except subprocess.CalledProcessError as error:
        return dict(accession=accession, url=f'https://rest.uniprot.org/uniprotkb/{accession}.json', success=False,
                    startedAt=started, finishedAt=stamp(), error='WORKER_FAILED', workerExit=error.returncode)


def bounded_file(path, limit):
    require(path.stat().st_size <= limit, 'LOCAL_FILE_SIZE_LIMIT')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, 'LOCAL_FILE_SIZE_LIMIT')
    return data


def seed_records(root, pin, requested):
    root = Path(root).resolve()
    manifest_bytes = bounded_file(root / 'manifest.json', 16_000_000)
    require(len(manifest_bytes) <= 16_000_000 and sha(manifest_bytes) == pin, 'SEED_MANIFEST_PIN_MISMATCH')
    manifest = json.loads(manifest_bytes)
    require(manifest.get('schemaVersion') == 'uniprot-protein-region-index-v1', 'SEED_SCHEMA_INVALID')
    receipt_bytes = bounded_file(root / 'acquisition.json', 16_000_000)
    require(sha(receipt_bytes) == manifest.get('acquisitionSha256'), 'SEED_RECEIPT_PIN_MISMATCH')
    receipts = json.loads(receipt_bytes)
    result = {}
    for entry in manifest['entries']:
        accession = identifiers([entry['accession']])[0]
        if accession not in requested:
            continue
        require(entry['file'] == accession + '.json', 'SEED_FILE_INVALID')
        path = (root / entry['file']).resolve()
        require(path.parent == root and path.stat().st_size <= MAX_RECORD, 'SEED_PATH_OR_SIZE_INVALID')
        data = bounded_file(path, MAX_RECORD)
        require(len(data) == entry['bytes'] and sha(data) == entry['sha256'], 'SEED_RECORD_PIN_MISMATCH')
        matches = [r for r in receipts if r.get('success') is True and r.get('accession') == accession
                   and r.get('sha256') == sha(data) and r.get('bytes') == len(data)]
        require(len(matches) == 1 and accession not in result, 'SEED_RECEIPT_BINDING_INVALID')
        result[accession] = (data, matches[0])
    return result


def acquire(values, directory, seed=None, seed_pin=None, runner=run_worker, clock=time.monotonic):
    requested = identifiers(values)
    require((seed is None) == (seed_pin is None), 'SEED_PIN_REQUIRED')
    reusable = seed_records(seed, seed_pin, requested) if seed is not None else {}
    root = Path(directory)
    root.mkdir(parents=False, exist_ok=False)
    attempts = root / 'attempts'
    attempts.mkdir()
    (root / 'request.json').write_bytes(encode(dict(version=VERSION, requested=requested, seedManifestSha256=seed_pin,
                                                  maximumRecordBytes=MAX_RECORD, maximumTotalBytes=MAX_TOTAL,
                                                  perRecordSeconds=PER_RECORD_SECONDS, totalSeconds=TOTAL_SECONDS)))
    report = dict(version=VERSION, startedAt=stamp(), requested=requested, reused=[], attempted=[], unattempted=[],
                  transferredBytes=0, reservedBodyByteBudget=0, status='PARTIAL', networkRetries=0)
    receipts, release = [], None
    deadline = clock() + TOTAL_SECONDS
    stop = None
    for accession in requested:
        if stop:
            report['unattempted'].append(dict(accession=accession, reason=stop))
            continue
        if accession in reusable:
            data, item = reusable[accession]
            report['reused'].append(accession)
        else:
            remaining = deadline - clock()
            if remaining <= 0 or report['reservedBodyByteBudget'] + MAX_RECORD + 1 > MAX_TOTAL:
                stop = 'BATCH_BUDGET_EXHAUSTED'
                report['unattempted'].append(dict(accession=accession, reason=stop))
                continue
            report['reservedBodyByteBudget'] += MAX_RECORD + 1
            item = runner(accession, attempts, min(PER_RECORD_SECONDS, remaining))
            report['attempted'].append(accession)
            report['transferredBytes'] += item.get('transferredBytes', 0)
            data = (attempts / f'{accession}.body').read_bytes() if item.get('success') is True else b''
        if item.get('success') is True:
            require(item.get('accession') == accession and len(data) == item.get('bytes') and len(data) <= MAX_RECORD
                    and sha(data) == item.get('sha256'), 'WORKER_RESPONSE_BINDING_INVALID')
            versions = re.findall(r'^X-UniProt-Release: ([0-9]{4}_[0-9]{2})\s*$', item.get('headers', ''), re.I | re.M)
            if len(versions) != 1 or release is not None and versions[0] != release:
                item = dict(item, success=False, error='RELEASE_MISSING_OR_CHANGED')
                stop = 'RELEASE_MISSING_OR_CHANGED'
            else:
                release = versions[0]
                (root / f'{accession}.json').write_bytes(data)
        if item.get('status') in (429, 503):
            stop = 'SERVICE_COOLDOWN_NO_MORE_REQUESTS'
        receipts.append(item)
        (root / 'acquisition.json').write_bytes(encode(receipts))
        (root / 'batch-report.json').write_bytes(encode(report))
    report.update(finishedAt=stamp(), release=release, successfulRecords=sum(r.get('success') is True for r in receipts))
    report['status'] = 'ACQUIRED' if report['successfulRecords'] == len(requested) else 'PARTIAL'
    (root / 'acquisition.json').write_bytes(encode(receipts))
    (root / 'batch-report.json').write_bytes(encode(report))
    return report


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--worker':
        worker(sys.argv[2], sys.argv[3]); return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accessions', required=True, help='JSON array file, frozen before running')
    parser.add_argument('--output', required=True, help='New Code-local resource snapshot directory')
    parser.add_argument('--seed')
    parser.add_argument('--seed-manifest-sha256')
    args = parser.parse_args()
    result = acquire(json.loads(Path(args.accessions).read_bytes()), args.output, args.seed, args.seed_manifest_sha256)
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result['status'] == 'ACQUIRED' else 2


if __name__ == '__main__':
    raise SystemExit(main())
