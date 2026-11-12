"""Assemble the ten-rule invocation identity from immutable audited predecessors."""
import copy
import json
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / 'varvis-server/src/main/resources/acmg'


def read(relative):
    return json.loads((RES / relative).read_bytes())


def build():
    main = read('acmg-business-execution-profile-joint-v6.json')
    bs1 = read('history/bs1-335c8ac/acmg-business-execution-profile-joint-v4.json')
    ps3 = read('history/ps3-ee7289c/acmg-business-execution-profile-joint-v1.json')
    result = copy.deepcopy(main)
    result.update(profileId='acmg-explicit-business-joint-v7', manifestVersion='1.6.0',
                  predecessorProfileId=main['profileId'])
    result['enabledCriteria'] += ['ACMG_AMP_2015:BS1', 'ACMG_AMP_2015:PS3']
    result['slices'] += [bs1['slices'][-1], ps3['slices'][-1]]
    assert result['slices'][-2]['criterionIds'] == ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1']
    assert result['slices'][-1]['criterionIds'] == ['ACMG_AMP_2015:PS3']
    assert len(result['enabledCriteria']) == len(set(result['enabledCriteria'])) == 10
    paths = {'/acmg/acmg-business-execution-profile-joint-v6.json',
             '/acmg/history/bs1-335c8ac/acmg-business-execution-profile-joint-v4.json',
             '/acmg/history/ps3-ee7289c/acmg-business-execution-profile-joint-v1.json'}
    result['historicalPredecessors'] = [p for p in read('history/execution-profile-history-v1.json')['profiles']
                                       if p['resourceClasspath'] in paths]
    assert len(result['historicalPredecessors']) == 3
    (RES / 'acmg-business-execution-profile-joint-v7.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    build()
