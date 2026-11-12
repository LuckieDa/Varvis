"""Evaluate frozen TRUST results; annotations absent from a source are not negatives."""
import argparse
import json
from pathlib import Path
from protein_repeat_method import save, sha


def covered_residues(start, end, units):
    # Independent interval-union computation; overlapping hits must not inflate coverage.
    positions = set()
    for unit in units:
        positions.update(range(max(start, unit['start']), min(end, unit['end']) + 1))
    return len(positions)


def evaluate(panel_raw, run):
    panel = json.loads(panel_raw)
    if run['panelSha256'] != sha(panel_raw) or run['toolIntegrityAfter'] != 'UNCHANGED':
        raise ValueError('UNQUALIFIED_RUN_IDENTITY')
    cases = panel['cases']
    by_id = {row['id']: row for row in run['results']}
    if len(by_id) != len(run['results']) or set(by_id) != {r['id'] for r in cases}:
        raise ValueError('RUN_CASES_DIFFER_FROM_FROZEN_PANEL')
    rows = []
    for case in cases:
        result = by_id[case['id']]
        if result['sequenceSha256'] != case['sequenceSha256']:
            raise ValueError('RUN_SEQUENCE_IDENTITY_MISMATCH')
        row = {'id': case['id'], 'class': case['class'], 'length': len(case['sequence']),
               'status': result['status'], 'gene': case.get('gene'),
               'elapsedSeconds': result.get('process', {}).get('elapsedSeconds')}
        if case['class'] == 'CONSTRUCTED_REPEAT_CHALLENGE' and 'expectedInterval' not in case:
            raise ValueError('CONSTRUCTED_CHALLENGE_EXPECTATION_MISSING')
        if result['status'] == 'COMPLETED':
            facts = result['facts']
            if facts['clinicalAdoption'] or facts['nonRepeatQualified'] or facts['noKnownFunctionQualified']:
                raise ValueError('UNAUTHORIZED_QUALIFICATION')
            units = [unit for group in facts['groups'] for unit in group['units']]
            row.update(algorithmState=facts['algorithmState'], groups=len(facts['groups']), units=len(units),
                       hitResidues=covered_residues(1, row['length'], units))
            if 'expectedInterval' in case:
                start, end = case['expectedInterval']
                row['constructedInterval'] = [start, end]
                row['constructedResidues'] = end - start + 1
                row['constructedHitResidues'] = covered_residues(start, end, units)
            if 'knownRepeatUnits' in case:
                row['nativeUnits'] = [dict(start=u['start'], end=u['end'], sourceLocator=u['sourceLocator'],
                    residues=u['end'] - u['start'] + 1, hitResidues=covered_residues(u['start'], u['end'], units))
                    for u in case['knownRepeatUnits']]
                row['nativeNegativeTruth'] = 'NOT_AVAILABLE'
        rows.append(row)
    completed = [r for r in rows if r['status'] == 'COMPLETED']
    challenges = [r for r in rows if r['class'] == 'CONSTRUCTED_REPEAT_CHALLENGE']
    native_units = [u for r in completed for u in r.get('nativeUnits', [])]
    decoys = [r for r in completed if r['class'] == 'COMPOSITION_MATCHED_SHUFFLE']
    summary = {
        'schemaVersion': 'protein-repeat-method-evaluation-v1', 'panelSha256': sha(panel_raw),
        'cases': len(rows), 'completed': len(completed), 'failedOrNotAttempted': len(rows) - len(completed),
        'elapsedSeconds': run['elapsedSeconds'],
        'constructedChallenges': len(challenges),
        'constructedWithAnyOverlap': sum(r.get('constructedHitResidues', 0) > 0 for r in challenges),
        'constructedFullyCovered': sum(r.get('constructedHitResidues') == r.get('constructedResidues')
                                      for r in challenges if r['status'] == 'COMPLETED'),
        'nativeAnnotatedUnitsInCompletedProteins': len(native_units),
        'nativeAnnotatedUnitsAnyOverlap': sum(u['hitResidues'] > 0 for u in native_units),
        'nativeAnnotatedUnitsFullyCovered': sum(u['hitResidues'] == u['residues'] for u in native_units),
        'nativeAnnotatedResidues': sum(u['residues'] for u in native_units),
        'nativeAnnotatedHitResidues': sum(u['hitResidues'] for u in native_units),
        'shuffleControlsCompleted': len(decoys), 'shuffleControlsWithHits': sum(r['units'] > 0 for r in decoys),
        'rows': rows, 'nonRepeatQualified': False, 'clinicalAdoption': False,
        'interpretation': 'Descriptive fixed panel only; no prevalence, clinical specificity, '
                          'population coverage or absence-of-repeat conclusion. Failure is not no-hit.'}
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--panel', required=True, type=Path)
    parser.add_argument('--run', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = evaluate(args.panel.read_bytes(), json.loads(args.run.read_bytes()))
    save(args.output, result)
    print(json.dumps({k: v for k, v in result.items() if k != 'rows'}, indent=2))
