"""Replay actual pinned rule originals with explicitly synthetic observations.

CLI is a direct Java policy test fixture, not clinical evidence or a source
acquisition task. Rule collections remain byte-identical; no gene whitelist is
added to a production policy and no patient's data is read.
"""
from copy import deepcopy
import json
from pathlib import Path
import sys

from model_literature_contract import canonical, digest
from pm3_specification_context import SpecificationContext, scopes


def build(root, seed):
    catalog = root/'local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json'
    config = {'catalog': {'path': catalog.relative_to(root).as_posix(), 'sha256': digest(catalog.read_bytes())},
              'captureDirectory': 'local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture'}
    supply = SpecificationContext(root, config)
    result = {'sourceClass': 'ACTUAL_RULE_ORIGINALS_SYNTHETIC_CASE_METADATA', 'clinicalAdoption': False,
              'catalogRawSha256': digest(catalog.read_bytes()), 'examples': []}
    for key, family in [('GN006@2.0', 'SVI_EXPLICIT_DELEGATION'), ('GN025@2.0', 'SVI_PHASE_METHOD'),
                        ('GN026@2.0', 'SVI_PHASE_AND_APPROVED_PARTNER')]:
        spec = next(s for s in supply.specifications if s['specificationKey'] == key)
        association = scopes(spec['original'])[0]
        row = deepcopy(seed); target = row['identities']['target']
        target.update(geneSymbol=association['geneSymbol'], hgncId=association['scope']['hgncId'],
                      transcript=association['rawGeneAssociation'].get('preferredTranscript'))
        row['diseaseContext'].update(qualification='VERIFIED', inheritance='AR', diseaseId=association['scope']['mondoId'])
        row['reportedObservation']['geneSymbol'] = association['geneSymbol']
        observations = {'cases': [{'resolvedObservations': [row]}]}
        supply.attach(observations)
        result['examples'].append({'specificationKey': key, 'expectedFamily': family, 'observations': observations,
                                   'syntheticIdentity': True, 'scientificCaseAdoption': 'NOT_RUN'})
    return result


if __name__ == '__main__':
    root, seed, output = map(Path, sys.argv[1:4])
    result = build(root.resolve(strict=True), json.loads(seed.read_text(encoding='utf-8')))
    output.write_bytes(canonical(result))
    print(json.dumps({'examples': len(result['examples']), 'catalogRawSha256': result['catalogRawSha256'],
                      'ruleLevel': 'ACTUAL_PINNED_ORIGINALS', 'caseLevel': 'SYNTHETIC_NOT_CLINICAL'}))
