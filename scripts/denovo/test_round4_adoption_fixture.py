"""Normal-reader controls with invented knowledge and families; no clinical adoption.

All scenario facts exist in the registered original before replay.  No result,
review receipt, scientific status or source check is changed after replay.
"""
import shutil
from pathlib import Path
import tempfile
import unittest
from xml.sax.saxutils import escape

from bundle_candidate_intake import replay_intake
from core_material_contract import pointer
from core_source_event_discovery import HEADERS
from model_literature_contract import canonical, digest, parse
from test_bundle_qualification import article, PMCID
from test_core_evaluation_intake import config_for
from test_core_source_event_discovery import record, registered_intake
from test_core_special_event import event


SCENARIOS = {
    'TISSUE_BOTH', 'TISSUE_C1', 'TISSUE_C2', 'COMBINED_ASSUMED',
    'COMBINED_EXCLUDED', 'TISSUE_CROSS_TRIO', 'TISSUE_QUALITY',
    'TISSUE_ORDINARY_PARENT_PRESENT', 'TISSUE_DUPLICATE_RECORD', 'TISSUE_C2_NO_DIFFERENTIAL', 'GENERAL_ASSUMED',
}
KNOWLEDGE = ('SYNTHETIC ONLY. Invented reusable test policy; no clinical or gene claim. '
             'Synthetic trait is consistent. Tissue differential uses C1 and C2. '
             'Combined PS2 includes qualified assumed parentage at SVI weight.').encode()


def reported(scenario):
    if scenario not in SCENARIOS:
        raise ValueError('ROUND4_SCENARIO_UNSUPPORTED')
    value = event()
    if scenario.startswith('COMBINED') or scenario == 'GENERAL_ASSUMED':
        for key in ('maternity', 'paternity'):
            value['trio'][key] = 'ASSUMED_NOT_TESTED'
            value['trio'][key + 'Method'] = 'NOT_TESTED'
        if scenario == 'COMBINED_EXCLUDED':
            value['trio']['paternity'] = 'EXCLUDED'
            value['trio']['paternityMethod'] = 'SNP_PANEL'
    else:
        value['kind'] = 'POSTZYGOTIC'
        if scenario != 'TISSUE_C1':
            value['tissues'] = [
                {'personId': 'A', 'tissueId': 'SYNTHETIC-lesion', 'affected': True,
                 'altReads': 20, 'totalReads': 100, 'limit': 0.01,
                 'method': 'TARGETED_SEQUENCING', 'quality': 'HIGH_CONFIDENCE'},
                {'personId': 'A', 'tissueId': 'SYNTHETIC-comparison', 'affected': False,
                 'altReads': 0, 'totalReads': 100, 'limit': 0.01,
                 'method': 'TARGETED_SEQUENCING', 'quality': 'HIGH_CONFIDENCE'}]
        if scenario.startswith('TISSUE_C2'):
            value['parentalAvailability'] = 'UNAVAILABLE'
            value['trio']['motherTarget'] = value['trio']['fatherTarget'] = 'UNKNOWN'
            for key in ('maternity', 'paternity'):
                value['trio'][key] = 'ASSUMED_NOT_REPORTED'
                value['trio'][key + 'Method'] = 'NOT_REPORTED'
            if scenario == 'TISSUE_C2_NO_DIFFERENTIAL':
                value['tissues'][1]['altReads'] = 20
        if scenario == 'TISSUE_CROSS_TRIO':
            value['trio']['personId'] = 'SYNTHETIC-different-person'
        if scenario == 'TISSUE_QUALITY':
            value['trio']['quality'] = 'FAILED'
    return value


def source_table(value):
    """An explicit case-column table, with tissues a source JSON-valued cell."""
    rows = [('Gene symbol', 'EXAMPLE'), ('Genomic allele', canonical(record()['allele']).decode())]
    seen = set()
    for header, path in HEADERS.items():
        if path in seen:
            continue
        seen.add(path)
        try:
            part = pointer(value, path)
        except ValueError:
            continue
        rows.append((header, part if type(part) is str else canonical(part).decode()))
    body = ''.join('<tr><td>' + escape(k) + '</td><td>' + escape(v) + '</td></tr>' for k, v in rows)
    return ('<table-wrap><table><tr><th>Observation</th><th>Case A</th></tr>' + body + '</table></table-wrap>').encode()


def context_options(root, scenario):
    result = config_for(root)
    path = root / result['path']; config = parse(path.read_bytes())
    policy_path = root / config['contexts'][0]['policy']['path']
    policy = parse(policy_path.read_bytes())
    policy['policyId'] = 'SYNTHETIC-ROUND4-' + ('GENERAL' if scenario == 'GENERAL_ASSUMED' else 'COMBINED' if scenario.startswith('COMBINED') else 'TISSUE')
    policy['scope']['diseaseId'] = 'MONDO:0000001'
    policy['scope']['inheritance'] = 'AD'
    policy['expertPolicyStatus'] = 'GENERAL_ALLOWED' if scenario == 'GENERAL_ASSUMED' else 'APPLICABLE_IMPLEMENTED'
    policy['specialEvents'] = {'mode': 'SVI_1_1' if scenario == 'GENERAL_ASSUMED' else 'SVI_1_1_COMBINED_PS2' if scenario.startswith('COMBINED')
                             else 'TISSUE_DIFFERENTIAL_PS2', 'sourceIds': ['paper']}
    # The independent synthetic expectation is 1 assumed consistent point, not
    # a high-heterogeneity half point.  This is source policy, never a result edit.
    policy['conditions'][0]['category'] = 'CONSISTENT'
    policy['sources'][0].update(sha256=digest(KNOWLEDGE), locator='synthetic knowledge paragraph 1',
                                quote='Synthetic trait is consistent.')
    (root / config['contexts'][0]['sources']['paper']['path']).write_bytes(KNOWLEDGE)
    config['contexts'][0]['sources']['paper']['sha256'] = digest(KNOWLEDGE)
    policy_path.write_bytes(canonical(policy))
    config['contexts'][0]['scope'] = policy['scope']
    config['contexts'][0]['policy']['sha256'] = digest(policy_path.read_bytes())
    path.write_bytes(canonical(config)); result['sha256'] = digest(path.read_bytes())
    return result, policy


def build(root, kind, scenario):
    if kind not in {'JSON', 'JATS'}:
        raise ValueError('ROUND4_KIND_UNSUPPORTED')
    value = reported(scenario); raw = article()
    if scenario.startswith('COMBINED') or scenario == 'GENERAL_ASSUMED':
        raw = raw.replace(b'SNP analysis confirmed both maternity and paternity.', b'No parentage testing was performed.')
    if scenario.startswith('TISSUE_C2'):
        raw = raw.replace(b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.',
                          b'Sanger sequencing confirmed the heterozygous variant in the proband.')
        raw = raw.replace(b'<fig><caption><p>(A) Other panel. (B) Sanger sequencing of the patient and parents to show that the heterozygous mutation is de novo.</p></caption></fig>', b'')
        raw = raw.replace(b'SNP analysis confirmed both maternity and paternity.', b'Parental samples were unavailable.')
    if scenario == 'TISSUE_ORDINARY_PARENT_PRESENT':
        raw = raw.replace(b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.',
                          b'Sanger sequencing confirmed the heterozygous variant in the proband. The variant was inherited from the mother.')
    attachment = None
    if kind == 'JSON':
        rows = [record(value)]
        if scenario == 'TISSUE_DUPLICATE_RECORD':
            rows.append(record(value))
        attachment = canonical({'cases': rows})
    else:
        table = source_table(value)
        extra_rows = table.split(b'</tr>', 1)[1].split(b'</table>', 1)[0]
        raw = raw.replace(b'</table>', extra_rows + b'</table>', 1)
    source, intake = registered_intake(root, raw, attachment)
    options, policy = context_options(root, scenario)
    facts = replay_intake(raw, PMCID, digest(raw), intake, work_root=root, core_context=options)
    return facts['cases'][0]['coreEvaluation'], facts, source, intake, options, policy


def write_java_fixture(output, kind='JSON', scenario='TISSUE_BOTH'):
    """Write genuine parser artifacts; all original observations are synthetic."""
    output = Path(output)
    if output.exists():
        raise ValueError('NEW_FIXTURE_DIRECTORY_REQUIRED')
    temp_root = Path(__file__).resolve().parents[2] / 'local/temp'; temp_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='r4-', dir=temp_root) as directory:
        root = Path(directory)
        core, facts, source, intake, options, policy = build(root, kind, scenario)
        output.mkdir(parents=True)
        shutil.copytree(root / 'local', output / 'local')
        (output / 'synthetic-literature.xml').write_bytes(source['raw'])
        (output / 'bundle-intake.json').write_bytes(canonical(intake))
        (output / 'facts.json').write_bytes(canonical(facts))
        (output / 'model-artifacts.json').write_bytes(canonical({'schemaVersion': 'denovo-bundle-intake-index-v1',
            'artifacts': [{'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'path': 'bundle-intake.json',
                           'sha256': digest(canonical(intake))}]}))
        support = output / 'scripts/acmg-generalization'; support.mkdir(parents=True)
        for name in ('normalize_public_panel.py', 'build_transcript_model.py'):
            (support / name).write_bytes((Path(__file__).resolve().parents[1] / 'acmg-generalization' / name).read_bytes())
        result = {'core': core, 'policy': policy, 'policySources': {'paper': KNOWLEDGE.decode()},
            'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'bundleSha256': source['bundleSha256'],
            'coreContext': {'root': str(output.resolve()), 'path': options['path'], 'sha256': options['sha256']},
            'sourceClass': 'SYNTHETIC_NORMAL_REGISTERED_' + kind, 'criterionDecision': 'NOT_RUN', 'scenario': scenario}
        path = output / 'java-round4-fixture.json'; path.write_bytes(canonical(result)); return path


class Round4FixtureTests(unittest.TestCase):
    def test_positive_sources_replay_without_an_evaluation(self):
        temporary = Path(__file__).resolve().parents[2] / 'local/temp'; temporary.mkdir(parents=True, exist_ok=True)
        for kind, scenario in [('JSON', 'TISSUE_BOTH'), ('JATS', 'TISSUE_C1'), ('JSON', 'TISSUE_C2'), ('JATS', 'COMBINED_ASSUMED')]:
            with self.subTest(kind=kind, scenario=scenario), tempfile.TemporaryDirectory(prefix='r4-', dir=temporary) as directory:
                core, _, _, _, _, policy = build(Path(directory), kind, scenario)
                self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
                self.assertEqual('NOT_RUN', core['criterionDecision'])
                self.assertEqual(reported(scenario), core['specialEvent'])
                self.assertEqual('APPLICABLE_IMPLEMENTED', policy['expertPolicyStatus'])

    def test_unavailable_source_does_not_supply_ordinary_parents(self):
        temporary = Path(__file__).resolve().parents[2] / 'local/temp'; temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='r4-', dir=temporary) as directory:
            core, *_ = build(Path(directory), 'JSON', 'TISSUE_C2')
            for path in ('/groups/mother/targetResult', '/groups/father/targetResult'):
                self.assertIn(core['values'][path], (None, 'UNKNOWN'))
            self.assertNotEqual('CONFIRMED', core['values']['/groups/parentage/maternity'])


if __name__ == '__main__':
    unittest.main()
