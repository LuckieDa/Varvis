"""Read-only identity/science checks for the scoped PVS1 business envelope.

Uses frozen schemas for its context, atomic evidence and evaluation components.
The criterion-only GN017 selection is deliberately not a full CSpec overlay bundle.
Usage: python scripts/pvs1/verify_hnf1a_business.py [backend-artifact-directory]
"""
import copy
import calendar
from datetime import datetime
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.evidence import acmg_evidence_v2_contract as e1
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

RES = ROOT / 'varvis-server/src/main/resources/acmg'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(directory=None):
    profile = read(RES / 'pvs1-hnf1a-business-profile-v1.json')
    model = read(RES / 'pvs1-hnf1a-transcript-model-v1.json')
    registry_path = RES / 'rule-registry-v2/acmg-rule-registry-pvs1-hnf1a-2026-09-09.json'
    registry = read(registry_path)
    _validate_schema(registry)
    old = read(RES / 'rule-registry-v2/acmg-rule-registry-e3-l1a-2026-09-05.json')
    assert registry['lineage'] == old['lineage']
    assert registry['lineage']['baseRegistrySha256'] == e1.canonical_sha256(read(e1.RULE_REGISTRY_PATH))
    assert {k for k in old if old[k] != registry[k]} == {'registryId', 'criteria'}
    for before, after in zip(old['criteria'], registry['criteria'], strict=True):
        assert before == after or (before['code'] == 'PVS1' and
                                  {k for k in before if before[k] != after[k]} == {'notes'})
    assert profile['registryBinding']['registrySha256'] == sha(registry_path)
    assert profile['transcriptModel']['canonicalSha256'] == e1.canonical_sha256(model)
    assert len(model['cdsSequence']) == model['cdsLength'] == 1896
    assert model['exons'][8]['cdsEnd'] == model['lastJunctionCdsPosition'] == 1768
    assert model['transcript'] == 'ENST00000257555.11' and model['refseqTranscript'] == 'NM_000545.8'
    result = {'profileAndRegistry': 'PASS', 'otherCriteriaUnchanged': True, 'pvs1Envelopes': 0}
    if directory is None:
        return result
    directory = Path(directory)
    saved = read(directory / 'first-post-response.json')
    original = copy.deepcopy(saved)
    assert saved == read(directory / 'saved-get-response.json') == read(directory / 'repeat-post-response.json')
    for event in ('saved-get-event.json', 'repeat-post-event.json'):
        record = read(directory / event)
        assert record['runId'] == saved['runId'] and all(n == 0 for n in record['callIncrement'].values())
    schema = e1.ContractSchemas()
    topn = {c['candidateId']: c for c in json.loads((directory / 'formal-topn.json').read_text(), parse_float=Decimal)}
    positions = set()
    for candidate in saved['candidates']:
        for attempt in candidate['attempts']:
            p = attempt.get('pvs1', {})
            if 'evaluation' not in p:
                continue
            context, atomic, evaluation = p['context'], p['evidence'], p['evaluation']
            for component, name in ((context, 'evidence-context-v2'), (atomic, 'atomic-evidence-v2'),
                                    (evaluation, 'criterion-evaluation-v2')):
                schema.validate(component, 'urn:varvis:schema:' + name)
            assert context['contextId'] == 'context-sha256-' + e1.canonical_sha256(e1.context_identity_projection(context))
            assert atomic['evidenceId'] == 'evidence-sha256-' + e1.canonical_sha256(e1.evidence_identity_projection(atomic))
            assert evaluation['evaluationId'] == 'evaluation-sha256-' + e1.canonical_sha256(e1.evaluation_identity_projection(evaluation))
            trace = evaluation['deterministicTrace']
            assert trace['traceSha256'] == e1.canonical_sha256(e1.deterministic_trace_projection(trace, include_hash=False))
            assert evaluation['contextId'] == atomic['contextId'] == context['contextId']
            assert evaluation['evidenceIds'] == trace['inputEvidenceIds'] == [atomic['evidenceId']]
            assert evaluation['criterionId'] == 'ACMG_AMP_2015:PVS1'
            assert evaluation['evaluationResult']['status'] == 'MET' and evaluation['appliedStrength'] == 'VERY_STRONG'
            assert p['profileSha256'] == e1.canonical_sha256(profile)
            assert p['policy'] == profile['policy'] and p['transcriptModelBinding'] == profile['transcriptModel']
            selection = copy.deepcopy(p['policySelection'])
            sid = selection.pop('selectionId')
            assert sid == 'cspec-selection-sha256-' + e1.canonical_sha256(selection)
            assert evaluation['ruleBinding']['cspecSelectionId'] == sid
            assert selection['authority'] == profile['policy']['authority']
            assert selection['contextId'] == context['contextId']
            inputs = p['resolvedInput']
            params = {v['name']: v['value'] for v in trace['parameters']}
            assert params['resolvedInputSha256'] == e1.canonical_sha256(inputs)
            assert inputs['storedAnnotationSha256'] == e1.canonical_sha256(p['storedAnnotation'])
            allele = context['variant']['evidenceQueryAllele']
            formal = topn[candidate['candidateId']]
            key = formal['observation']['key']
            assert allele['assembly'] == key['genomeAssembly'] and allele['contig'] == key['chromosome']
            assert all(allele[k] == key[k] for k in ('position', 'reference', 'alternate'))
            assert formal['candidateIdentity']['modeOfInheritance'] == 'AD'
            annotation = p['storedAnnotation']
            matches = [t for t in formal['transcriptAnnotations'] if t['hgvsCoding'] == annotation['hgvsCoding']]
            assert len(matches) == 1
            source_annotation = matches[0]
            assert {k:v for k,v in annotation.items() if k != 'timestamp'} == {k:v for k,v in source_annotation.items() if k != 'timestamp'}
            # The original API emits epoch decimals; PVS1 stores exact ISO time to
            # avoid binary floating point in scientific identities. Compare nanos.
            stamp = annotation['timestamp'].removesuffix('Z')
            whole, _, fraction = stamp.partition('.')
            seconds = Decimal(calendar.timegm(datetime.fromisoformat(whole).timetuple())) + Decimal('0.' + (fraction or '0'))
            assert seconds == source_annotation['timestamp']
            assert context['gene']['hgncId'] == 'HGNC:11621' and context['disease']['mondoId'] == 'MONDO:0015967'
            assert context['moi'] == 'AUTOSOMAL_DOMINANT'
            exon = next(e for e in model['exons'] if e['genomicStart'] <= allele['position'] <= e['genomicEnd'])
            c = exon['cdsStart'] + allele['position'] - exon['genomicStart']
            codon_start = (c - 1) // 3 * 3
            ref = model['cdsSequence'][codon_start:codon_start + 3]
            alt = list(ref)
            assert alt[(c - 1) % 3] == allele['reference']
            alt[(c - 1) % 3] = allele['alternate']
            assert inputs['refCodon'] == ref and inputs['altCodon'] == ''.join(alt) in ('TAA', 'TAG', 'TGA')
            assert inputs['cdsPosition'] == c and inputs['ptcEnd'] == codon_start + 3 < 1714
            assert 355 <= c and 2 <= exon['ordinal'] <= 9
            assert inputs['distanceToLastJunction'] == 1768 - inputs['ptcEnd']
            positions.add(allele['position'])
            result['pvs1Envelopes'] += 1
    assert positions == {120989017, 120993678}, positions
    assert saved == original
    result.update(distinctRealPositives=len(positions), javaNodesUnchanged=True, readbackRepeatIdentical=True)
    return result


if __name__ == '__main__':
    print(json.dumps(verify(sys.argv[1] if len(sys.argv) > 1 else None)))
