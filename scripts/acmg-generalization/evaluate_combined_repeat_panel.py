"""Descriptive fixed-panel metrics. No specificity/clinical qualification inferred."""
import argparse
import json
from pathlib import Path
from decipher_repeat_method import require
from protein_repeat_method import save, sha


def positions(groups):
    return {p for group in groups for unit in group['units'] for p in range(unit['start'], unit['end'] + 1)}


def coverage(start, end, detected):
    expected = set(range(start, end + 1))
    return dict(start=start, end=end, residues=len(expected), hitResidues=len(expected & detected))


def evaluate(panel_raw, run, old_panel_raw, old_run):
    panel, old_panel = json.loads(panel_raw), json.loads(old_panel_raw)
    require(run['panelSha256'] == sha(panel_raw) and run['runtimeIntegrity'] == 'UNCHANGED', 'RUN_IDENTITY')
    require(panel['oldPanelSha256'] == sha(old_panel_raw) == old_run['panelSha256']
            and old_run['toolIntegrityAfter'] == 'UNCHANGED', 'REGRESSION_RUN_IDENTITY')
    cases = {c['id']: c for c in panel['cases']}
    results = {(r['id'], r['empirical']): r for r in run['results']}
    require(len(results) == len(run['results']) and set(results) == {(i, b) for i in cases for b in (False, True)},
            'RUN_RESULTS_INCOMPLETE_OR_DUPLICATE')
    old_results = {r['id']: r for r in old_run['results']}
    require(len(old_results) == len(old_run['results']) and set(old_results) == {c['id'] for c in old_panel['cases']},
            'OLD_RESULTS_INCOMPLETE')
    rows = []
    for identifier, case in cases.items():
        detections = {}; statuses = {}
        for empirical, label in [(False, 'DECIPHER_SEQUENCE'), (True, 'DECIPHER_EMPIRICAL')]:
            result = results[identifier, empirical]
            require(result['sequenceSha256'] == case['sequenceSha256'], 'METHOD_SEQUENCE_MISMATCH')
            statuses[label] = result['status']
            detections[label] = positions(result['facts']['groups']) if result['status'] == 'COMPLETED' else set()
        detections['DECIPHER_UNION'] = set.union(*detections.values())
        statuses['DECIPHER_UNION'] = 'COMPLETED' if all(s == 'COMPLETED' for s in statuses.values()) else 'PARTIAL'
        if identifier in old_results:
            result = old_results[identifier]
            require(result['sequenceSha256'] == case['sequenceSha256'], 'OLD_METHOD_SEQUENCE_MISMATCH')
            detections['TRUST'] = positions(result['facts']['groups']) if result['status'] == 'COMPLETED' else set()
            statuses['TRUST'] = result['status']
            detections['METHOD_UNION'] = detections['DECIPHER_UNION'] | detections['TRUST']
            statuses['METHOD_UNION'] = 'COMPLETED' if statuses['TRUST'] == statuses['DECIPHER_UNION'] == 'COMPLETED' else 'PARTIAL'
        for label, found in detections.items():
            row = dict(id=identifier, partition=case['partition'], caseClass=case['class'], method=label,
                       status=statuses[label], hitResidues=len(found))
            if 'expectedInterval' in case:
                start, end = case['expectedInterval']; row['constructed'] = coverage(start, end, found)
                row['outsideConstructedRegionResidues'] = len(found - set(range(start, end+1)))
            row['nativeUnits'] = [coverage(u['start'], u['end'], found) for u in case.get('knownRepeatUnits', [])]
            row['shortRuns'] = [coverage(u['start'], u['end'], found) for u in case.get('exactShortRunChallenges', [])]
            rows.append(row)
    summaries = []
    for partition, method in sorted({(r['partition'], r['method']) for r in rows}):
        subset = [r for r in rows if (r['partition'], r['method']) == (partition, method)]
        challenges = [r for r in subset if 'constructed' in r]
        units = [u for r in subset for u in r['nativeUnits']]
        shuffles = [r for r in subset if r['caseClass'] == 'COMPOSITION_MATCHED_SHUFFLE']
        summaries.append(dict(partition=partition, method=method, cases=len(subset),
            complete=sum(r['status'] == 'COMPLETED' for r in subset), challenges=len(challenges),
            challengeHits=sum(r['constructed']['hitResidues'] > 0 for r in challenges),
            challengeFull=sum(r['constructed']['hitResidues'] == r['constructed']['residues'] for r in challenges),
            challengeOverextension=sum(r['outsideConstructedRegionResidues'] > 0 for r in challenges),
            outsideConstructedResidues=sum(r['outsideConstructedRegionResidues'] for r in challenges),
            nativeUnits=len(units), nativeHit=sum(u['hitResidues'] > 0 for u in units),
            nativeFull=sum(u['hitResidues'] == u['residues'] for u in units),
            shuffles=len(shuffles), shuffleHits=sum(r['hitResidues'] > 0 for r in shuffles)))
    return dict(schemaVersion='combined-repeat-validation-evaluation-v1', panelSha256=sha(panel_raw),
                summaries=summaries, rows=rows, elapsedSeconds=run['elapsedSeconds'],
                nativeTruth='POSITIVE_ANNOTATED_REGIONS_ONLY', clinicalAdoption=False,
                warning='Failure counts remain visible; union is not an independent method. Native facts are not '
                        'added to method performance. Outside constructed-region hits are not proven clinical '
                        'false positives. Shuffles are not biological negative truth. No sensitivity/coverage population estimate.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ['panel', 'run', 'old-panel', 'old-run', 'output']:
        parser.add_argument('--'+key, required=True, type=Path)
    args = parser.parse_args()
    value = evaluate(args.panel.read_bytes(), json.loads(args.run.read_bytes()), args.old_panel.read_bytes(),
                     json.loads(args.old_run.read_bytes()))
    save(args.output, value)
    print(json.dumps(dict(elapsedSeconds=value['elapsedSeconds'], summaries=value['summaries']), indent=2))
