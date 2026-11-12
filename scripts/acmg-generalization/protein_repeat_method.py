"""Bounded, offline TRUST method validation. No PM4/BP3 adoption or negative certificate.

Uses the unmodified author-distributed classes. A completed no-hit remains a
method observation, never proof of a non-repetitive or non-functional region.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

AA = 'ACDEFGHIKLMNPQRSTVWY'
PARAMETERS = ['-noseg', '-noforce', '-gapo', '8', '-gapx', '2', '-max', '3000']
ARCHIVE_SHA = 'd00e30233baa113f585b2f568836c58557bf7f02c6a770967c48a69a90cf64ee'
MATRIX_SHA = 'feb995c0f774da17f290856da8af4756b9178b5d27b3d02535ec71654599bc32'
STDOUT_LIMIT = 2_000_000
STDERR_LIMIT = 4_000_000
COMMENTS = {
    '# type of the repeat', '# profile_length',
    '# The list of repeats in the format:', '# START LENGTH [PVALUE [SCORE]]',
    '# The multiple alignment of repeats', '# lo-case letters: not a part of alignment',
    '# Profile pattern, "X": profile column, "-": a gap', '# no repeats found',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stamp():
    return datetime.now(timezone.utc).isoformat()


def save(path, value):
    # Exclusive creation is intentional: prior evidence must not be overwritten.
    with Path(path).open('xb') as stream:
        stream.write((json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf-8'))


def validate_sequence(identifier, sequence):
    if (not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,79}', identifier)
            or re.fullmatch(r'(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])', identifier)):
        raise ValueError('INVALID_SEQUENCE_ID')
    if not isinstance(sequence, str) or not 1 <= len(sequence) <= 3000 or set(sequence) - set(AA):
        raise ValueError('UNSUPPORTED_SEQUENCE_OR_LENGTH')


def parse_trust(raw, identifier, sequence):
    """Validate completion, unit coordinates and every aligned input residue."""
    validate_sequence(identifier, sequence)
    if len(raw) > STDOUT_LIMIT:
        raise ValueError('OUTPUT_TOO_LARGE')
    lines = [x.strip() for x in raw.decode('ascii').splitlines() if x.strip()]
    if (len(lines) < 3 or lines[0] != '>' + identifier
            or lines[-2:] != ['# end of the protein ' + identifier, '//']):
        raise ValueError('INCOMPLETE_OR_WRONG_PROTEIN')
    groups, group, pending, no_hit = [], None, None, False
    for line in lines[1:-2]:
        if pending is not None:
            if not re.fullmatch(r'[ACDEFGHIKLMNPQRSTVWYacdefghiklmnpqrstvwy-]+', line):
                raise ValueError('INVALID_ALIGNMENT')
            unit = group['units'][pending]
            if line.replace('-', '').upper() != sequence[unit['start'] - 1:unit['end']]:
                raise ValueError('ALIGNMENT_SEQUENCE_MISMATCH')
            unit['alignment'] = line
            pending = None
        elif line in COMMENTS or re.fullmatch(r'# [X-]+', line):
            no_hit |= line == '# no repeats found'
        elif re.fullmatch(r'REPEAT_TYPE [1-9][0-9]{0,5}', line):
            number = int(line.split()[1])
            if groups and number <= groups[-1]['type']:
                raise ValueError('DUPLICATE_REPEAT_TYPE')
            group = {'type': number, 'profileLength': None, 'units': []}
            groups.append(group)
        elif re.fullmatch(r'REPEAT_LENGTH [1-9][0-9]{0,5}', line):
            number = int(line.split()[1])
            if group is None or group['profileLength'] is not None or number > len(sequence):
                raise ValueError('INVALID_PROFILE_LENGTH')
            group['profileLength'] = number
        elif match := re.fullmatch(r'([1-9][0-9]{0,5}) ([1-9][0-9]{0,5})\s+# Repeat ([1-9][0-9]{0,5})', line):
            start, length, number = map(int, match.groups())
            if (group is None or group['profileLength'] is None
                    or number != len(group['units']) + 1 or start + length - 1 > len(sequence)
                    or any('alignment' in u for u in group['units'])):
                raise ValueError('INVALID_REPEAT_UNIT')
            group['units'].append({'number': number, 'start': start, 'end': start + length - 1})
        elif match := re.fullmatch(r'>Repeat ([1-9][0-9]{0,5})', line):
            number = int(match.group(1))
            if (group is None or number > len(group['units'])
                    or number != 1 + sum('alignment' in u for u in group['units'])):
                raise ValueError('INVALID_ALIGNMENT_NUMBER')
            pending = number - 1
        else:
            # Upstream invalid-amino-acid diagnostics and unknown future formats fail closed.
            raise ValueError('UNRECOGNIZED_TOOL_OUTPUT')
    if pending is not None or (groups and no_hit):
        raise ValueError('INCOMPLETE_OR_CONFLICTING_RESULT')
    for item in groups:
        if (item['profileLength'] is None or len(item['units']) < 2
                or any('alignment' not in u for u in item['units'])):
            raise ValueError('INCOMPLETE_REPEAT_GROUP')
    return {'algorithmState': 'REPEAT_CANDIDATE' if groups else 'NO_REPEAT_DETECTED',
            'coordinateSystem': 'ONE_BASED_CLOSED', 'groups': groups,
            'nonRepeatQualified': False, 'noKnownFunctionQualified': False,
            'clinicalAdoption': False, 'reviewStatus': 'UNREVIEWED'}


def validate_panel(panel, profile, profile_raw):
    required = {'schemaVersion': 'protein-repeat-method-profile-v1', 'method': 'TRUST',
                'upstreamReadmeVersion': '1.0', 'sourceArchiveSha256': ARCHIVE_SHA,
                'parameters': PARAMETERS, 'matrix': 'BLOSUM62', 'matrixSha256': MATRIX_SHA,
                'heapMiB': 512, 'perProteinSeconds': 30, 'totalSeconds': 300,
                'maximumProteins': 50, 'maximumProteinLength': 3000, 'maximumTotalResidues': 50000,
                'adoption': 'VALIDATION_ONLY_NO_NEGATIVE_QUALIFICATION'}
    if any(profile.get(k) != v for k, v in required.items()):
        raise ValueError('UNSUPPORTED_METHOD_PROFILE')
    if panel.get('profileSha256') != sha(profile_raw):
        raise ValueError('PROFILE_PIN_MISMATCH')
    cases = panel.get('cases')
    if not isinstance(cases, list) or not 1 <= len(cases) <= 50:
        raise ValueError('INVALID_PANEL_SIZE')
    seen = set()
    for row in cases:
        validate_sequence(row['id'], row['sequence'])
        if row['id'].lower() in seen or sha(row['sequence'].encode('ascii')) != row['sequenceSha256']:
            raise ValueError('DUPLICATE_ID_OR_SEQUENCE_PIN_MISMATCH')
        seen.add(row['id'].lower())
    if sum(len(row['sequence']) for row in cases) > 50000:
        raise ValueError('PANEL_RESIDUE_BUDGET_EXCEEDED')


def verify_tool(tool, manifest_path, expected_manifest_sha):
    raw = manifest_path.read_bytes()
    if sha(raw) != expected_manifest_sha:
        raise ValueError('TOOL_MANIFEST_PIN_MISMATCH')
    manifest = json.loads(raw)
    expected = {}
    for item in manifest:
        name = item['path'].replace('\\', '/')
        if name.endswith(('.class', '.java')) or name in ('trust.tgz', 'Align/BLOSUM62', 'Align/readme.txt'):
            path = (tool / name).resolve()
            if not path.is_relative_to(tool.resolve()) or name in expected:
                raise ValueError('UNSAFE_TOOL_MANIFEST_PATH')
            expected[name] = item['sha256']
            if sha(path.read_bytes()) != item['sha256']:
                raise ValueError('TOOL_FILE_PIN_MISMATCH: ' + name)
    required = {'trust.tgz': ARCHIVE_SHA, 'Align/BLOSUM62': MATRIX_SHA}
    if any(expected.get(k) != v for k, v in required.items()):
        raise ValueError('TOOL_ARCHIVE_OR_MATRIX_MISMATCH')
    main = 'Align/nl/vu/cs/align/SelfSimilarity.class'
    actual_classes = {p.relative_to(tool).as_posix() for p in (tool / 'Align').rglob('*.class')}
    if main not in expected or actual_classes != {k for k in expected if k.endswith('.class')}:
        raise ValueError('TOOL_CLASSPATH_MISMATCH')
    return {'manifestSha256': sha(raw), 'verifiedFiles': len(expected),
            'archiveSha256': ARCHIVE_SHA, 'matrixSha256': MATRIX_SHA}


def run_process(command, directory, timeout, stdout_limit=STDOUT_LIMIT, stderr_limit=STDERR_LIMIT):
    """Only launches one trusted JVM (no child launch in the pinned upstream code).

    File sizes are polled; this is a detection bound, not an OS hard disk quota.
    After exit, parser reads at most the limit+1 bytes. Kill/wait only our child.
    """
    started = time.monotonic()
    receipt = {'command': command, 'startedAt': stamp(), 'timeoutSeconds': timeout}
    save(directory / 'command.json', receipt)
    stdout, stderr = directory / 'stdout.txt', directory / 'stderr.txt'
    with stdout.open('xb') as out, stderr.open('xb') as err:
        process = subprocess.Popen(command, cwd=directory, stdout=out, stderr=err)
        reason = None
        try:
            while process.poll() is None:
                if stdout.stat().st_size > stdout_limit or stderr.stat().st_size > stderr_limit:
                    reason = 'OUTPUT_LIMIT'
                    break
                if time.monotonic() - started >= timeout:
                    reason = 'TIMEOUT'
                    break
                time.sleep(0.02)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()
    if stdout.stat().st_size > stdout_limit or stderr.stat().st_size > stderr_limit:
        reason = 'OUTPUT_LIMIT'
    receipt.update(pid=process.pid, reaped=True, exitCode=process.returncode,
                   elapsedSeconds=round(time.monotonic() - started, 6), finishedAt=stamp(),
                   status=reason or ('COMPLETED' if process.returncode == 0 else 'NONZERO_EXIT'),
                   stdoutBytes=stdout.stat().st_size, stderrBytes=stderr.stat().st_size)
    save(directory / 'process.json', receipt)
    return receipt


def run_panel(panel_path, profile_path, tool, manifest_path, manifest_sha, java, output):
    profile_raw, panel_raw = profile_path.read_bytes(), panel_path.read_bytes()
    profile, panel = json.loads(profile_raw), json.loads(panel_raw)
    validate_panel(panel, profile, profile_raw)
    qualification = verify_tool(tool, manifest_path, manifest_sha)
    java = java.resolve(strict=True)
    if java.name.lower() not in ('java', 'java.exe'):
        raise ValueError('JAVA_EXECUTABLE_REQUIRED')
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'plan.json', {'tool': qualification, 'profileSha256': sha(profile_raw),
                              'panelSha256': sha(panel_raw), 'startedAt': stamp(),
                              'javaPath': str(java), 'javaSha256': sha(java.read_bytes()),
                              'clinicalAdoption': False})
    started = time.monotonic()
    results = []
    for case in panel['cases']:
        directory = output / case['id']
        directory.mkdir()
        row = {'id': case['id'], 'sequenceSha256': case['sequenceSha256'],
               'profileSha256': sha(profile_raw), 'clinicalAdoption': False}
        remaining = profile['totalSeconds'] - (time.monotonic() - started)
        if remaining <= 0:
            row['status'] = 'BATCH_BUDGET_NOT_ATTEMPTED'
        else:
            fasta = directory / 'input.fasta'
            fasta.write_bytes(('>' + case['id'] + '\n' + case['sequence'] + '\n').encode('ascii'))
            command = [str(java), '-Xmx512m', '-Djava.awt.headless=true', '-cp', str(tool / 'Align'),
                       'nl.vu.cs.align.SelfSimilarity', '-fasta', str(fasta),
                       '-matrix', str(tool / 'Align/BLOSUM62'), '-o', str(directory), *PARAMETERS]
            try:
                process = run_process(command, directory, min(profile['perProteinSeconds'], remaining))
                row['process'] = process
                row['status'] = process['status']
                if process['status'] == 'COMPLETED':
                    with (directory / 'stdout.txt').open('rb') as stream:
                        row['facts'] = parse_trust(stream.read(STDOUT_LIMIT + 1), case['id'], case['sequence'])
            except (ValueError, OSError) as error:
                row.update(status='TOOL_OR_OUTPUT_REJECTED', reason=str(error))
        save(directory / 'result.json', row)
        results.append(row)
        print(case['id'], row['status'], row.get('facts', {}).get('algorithmState', ''), flush=True)
    try:
        verify_tool(tool, manifest_path, manifest_sha)
        integrity = 'UNCHANGED'
    except (ValueError, OSError) as error:
        integrity = 'REJECT_ALL_RESULTS: ' + str(error)
    summary = {'schemaVersion': 'protein-repeat-method-run-v1', 'toolIntegrityAfter': integrity,
               'finishedAt': stamp(), 'elapsedSeconds': round(time.monotonic() - started, 6),
               'profileSha256': sha(profile_raw), 'panelSha256': sha(panel_raw), 'results': results,
               'clinicalAdoption': False, 'nonRepeatQualified': False}
    save(output / 'results.json', summary)
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('panel', 'profile', 'tool', 'tool-manifest', 'java', 'output'):
        parser.add_argument('--' + flag, required=True, type=lambda s: Path(s).resolve())
    parser.add_argument('--tool-manifest-sha256', required=True)
    args = parser.parse_args()
    result = run_panel(args.panel, args.profile, args.tool, args.tool_manifest,
                       args.tool_manifest_sha256, args.java, args.output)
    raise SystemExit(0 if result['toolIntegrityAfter'] == 'UNCHANGED'
                     and all(r['status'] == 'COMPLETED' for r in result['results']) else 2)
