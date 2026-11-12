"""Offline, pinned DECIPHER comparison; outputs repeat candidates, never ACMG adoption."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import time
from protein_repeat_method import AA, STDOUT_LIMIT, run_process, save, sha, stamp

VERSION = '1.0.1'


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def validate_sequence(identifier, sequence):
    require(isinstance(identifier, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,79}', identifier)
            and not re.fullmatch(r'(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])', identifier), 'INVALID_PROTEIN_ID')
    require(isinstance(sequence, str) and 1 <= len(sequence) <= 6000
            and not set(sequence) - set(AA), 'UNSUPPORTED_PROTEIN_SEQUENCE')


def parse_output(raw, identifier, sequence, empirical):
    validate_sequence(identifier, sequence)
    require(type(empirical) is bool and len(raw) <= STDOUT_LIMIT, 'OUTPUT_LIMIT_OR_MODE_INVALID')
    lines = raw.decode('ascii').splitlines()
    mode = str(empirical).upper()
    require(len(lines) >= 2 and lines[0] == f'BEGIN\t{identifier}\t3.8.1\t{mode}', 'WRONG_METHOD_OR_PROTEIN')
    end = lines[-1].split('\t')
    require(len(end) == 3 and end[:2] == ['END', identifier] and end[2].isdigit(), 'INCOMPLETE_OUTPUT')
    groups = []
    for line in lines[1:-1]:
        parts = line.split('\t')
        require(parts[0] in ('GROUP', 'UNIT'), 'UNEXPECTED_OUTPUT')
        if parts[0] == 'GROUP':
            require(len(parts) == 5 and all(re.fullmatch(r'[1-9][0-9]{0,5}', v) for v in parts[1:4]), 'INVALID_GROUP')
            number, start, stop = map(int, parts[1:4])
            score = float(parts[4])
            require(number == len(groups) + 1 and 1 <= start <= stop <= len(sequence)
                    and math.isfinite(score) and score >= 8, 'GROUP_BOUNDS_OR_SCORE')
            groups.append(dict(number=number, start=start, end=stop, score=score, units=[]))
        else:
            require(len(parts) == 6 and groups
                    and all(re.fullmatch(r'[1-9][0-9]{0,5}', v) for v in parts[1:5]), 'INVALID_UNIT')
            group_id, number, start, stop = map(int, parts[1:5])
            group = groups[-1]
            require(group_id == group['number'] and number == len(group['units']) + 1
                    and group['start'] <= start <= stop <= group['end'], 'UNIT_BOUNDS_OR_NUMBER')
            require(parts[5] == sequence[start-1:stop], 'UNIT_SEQUENCE_MISMATCH')
            require(not group['units'] or start > group['units'][-1]['end'], 'OVERLAPPING_UNITS_WITHIN_GROUP')
            group['units'].append(dict(number=number, start=start, end=stop))
    require(int(end[2]) == len(groups), 'GROUP_COUNT_MISMATCH')
    require(all(len(g['units']) >= 2 and g['units'][0]['start'] == g['start']
                and g['units'][-1]['end'] == g['end'] for g in groups), 'INCOMPLETE_GROUP')
    return dict(schemaVersion='decipher-repeat-observation-v1', methodVersion=VERSION,
                method='DECIPHER::DetectRepeats', upstreamVersion='3.8.1', empirical=empirical,
                sequenceSha256=sha(sequence.encode()), coordinateSystem='ONE_BASED_CLOSED', groups=groups,
                state='REPEAT_CANDIDATES' if groups else 'NO_REPEAT_DETECTED',
                clinicalAdoption=False, nonRepeatQualified=False, noKnownFunctionQualified=False)


def verify_runtime(directory, manifest_path, pin):
    raw = manifest_path.read_bytes()
    require(sha(raw) == pin, 'RUNTIME_MANIFEST_PIN_CHANGED')
    manifest = json.loads(raw)
    seen = set()
    for item in manifest['files']:
        rel = item['path']
        path = (directory / rel).resolve()
        require(path.is_relative_to(directory.resolve()) and rel not in seen, 'RUNTIME_PATH_INVALID')
        seen.add(rel)
        require(sha(path.read_bytes()) == item['sha256'], 'RUNTIME_FILE_CHANGED: ' + rel)
    actual = {p.relative_to(directory).as_posix() for folder in ['R-4.6.1', 'library']
              for p in (directory / folder).rglob('*') if p.is_file()}
    require(actual == seen, 'RUNTIME_INVENTORY_CHANGED')
    return pin


def run(panel_path, profile_path, runtime, manifest, pin, output):
    panel_raw, profile_raw = panel_path.read_bytes(), profile_path.read_bytes()
    panel, profile = json.loads(panel_raw), json.loads(profile_raw)
    expected = dict(method='DECIPHER::DetectRepeats', version='3.8.1', R='4.6.1', type='tandem',
                    minScore=8, allScores=True, maxCopies=1000, maxPeriod=1000, maxFailures=3, maxShifts=5,
                    correctBackground=True, processors=1, empiricalModes=[False, True],
                    maximumSequenceLength=6000, maximumSequences=60, perCallSeconds=30, totalSeconds=600,
                    adoption='METHOD_FACTS_ONLY_NO_CLINICAL_OR_NEGATIVE_QUALIFICATION')
    require(all(profile.get(k) == v for k, v in expected.items()), 'UNSUPPORTED_PROFILE')
    cases = panel['cases']
    require(0 < len(cases) <= 60 and sum(len(c['sequence']) for c in cases) <= 60000, 'PANEL_BUDGET')
    require(len({r['id'].lower() for r in cases}) == len(cases), 'DUPLICATE_PROTEIN_ID')
    for case in cases:
        validate_sequence(case['id'], case['sequence'])
        require(sha(case['sequence'].encode()) == case['sequenceSha256'], 'SEQUENCE_PIN_MISMATCH')
    verify_runtime(runtime, manifest, pin)
    output.mkdir(parents=True, exist_ok=False)
    worker = Path(__file__).with_name('decipher_repeat_worker.R').resolve()
    # The top-level Windows launcher spawns cmd and another Rscript. Invoke the
    # actual interpreter directly so our timeout can kill/wait the computation.
    rscript = runtime / 'R-4.6.1/bin/x64/Rscript.exe'
    worker_sha = sha(worker.read_bytes())
    save(output / 'plan.json', dict(panelSha256=sha(panel_raw), profileSha256=sha(profile_raw),
         runtimeManifestSha256=pin, workerSha256=worker_sha, startedAt=stamp(), clinicalAdoption=False))
    started = time.monotonic()
    results = []
    for case in cases:
        for empirical in profile['empiricalModes']:
            directory = output / (case['id'] + ('-empirical' if empirical else '-sequence'))
            directory.mkdir()
            result = dict(id=case['id'], empirical=empirical, sequenceSha256=case['sequenceSha256'])
            remaining = profile['totalSeconds'] - (time.monotonic() - started)
            if remaining <= 0:
                result['status'] = 'BATCH_BUDGET_NOT_ATTEMPTED'
            else:
                fasta = directory / 'input.fa'
                fasta.write_bytes(('>' + case['id'] + '\n' + case['sequence'] + '\n').encode())
                command = [str(rscript), '--vanilla', str(worker), str(runtime / 'library'),
                           str(fasta), case['id'], str(empirical).upper()]
                try:
                    receipt = run_process(command, directory, min(profile['perCallSeconds'], remaining))
                    result.update(status=receipt['status'], process=receipt)
                    if receipt['status'] == 'COMPLETED':
                        with (directory / 'stdout.txt').open('rb') as stream:
                            result['facts'] = parse_output(stream.read(STDOUT_LIMIT + 1), case['id'], case['sequence'], empirical)
                except (ValueError, OSError) as error:
                    result.update(status='REJECTED', reason=str(error))
            save(directory / 'result.json', result)
            results.append(result)
            print(case['id'], empirical, result['status'], len(result.get('facts', {}).get('groups', [])), flush=True)
    integrity = 'UNCHANGED'
    try:
        verify_runtime(runtime, manifest, pin)
        require(sha(worker.read_bytes()) == worker_sha, 'WORKER_CHANGED')
    except (ValueError, OSError) as error:
        integrity = 'REJECT_ALL_RESULTS: ' + str(error)
    result = dict(schemaVersion='decipher-validation-run-v1', panelSha256=sha(panel_raw), profileSha256=sha(profile_raw),
                  runtimeManifestSha256=pin, workerSha256=worker_sha, runtimeIntegrity=integrity,
                  elapsedSeconds=round(time.monotonic()-started, 6), results=results, clinicalAdoption=False)
    save(output / 'results.json', result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ['panel', 'profile', 'runtime', 'manifest', 'output']:
        parser.add_argument('--'+arg, required=True, type=lambda p: Path(p).resolve())
    parser.add_argument('--manifest-sha256', required=True)
    args = parser.parse_args()
    # Only this CLI process environment changes. --vanilla and explicit library
    # avoid user profiles; the caller supplies Code-local TEMP and R_USER.
    os.environ.update(LANG='C', LC_ALL='C', LANGUAGE='en')
    result = run(args.panel, args.profile, args.runtime, args.manifest, args.manifest_sha256, args.output)
    raise SystemExit(0 if result['runtimeIntegrity'] == 'UNCHANGED'
                     and all(r['status'] == 'COMPLETED' for r in result['results']) else 2)
