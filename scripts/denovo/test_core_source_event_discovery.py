"""Normal registered-source replay with synthetic transport; no live model calls."""
import base64
from copy import deepcopy
import gzip
import shutil
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from xml.sax.saxutils import escape as xml_escape

from model_literature_contract import canonical, digest, parse
from prepare_literature import prepare
from acquire_public_literature import source_bundle
from paper_library_registration import register
from bundle_model_contract import load, requests
from bundle_candidate_intake import pack, replay_intake
from run_bundle_model import run
from test_bundle_model import configuration, response
from test_bundle_qualification import article, candidate, PMCID
from test_core_evaluation_intake import config_for
from test_core_special_event import event
from test_literature_acquisition import zipped
from core_source_event_discovery import HEADERS, leaf_values, discover, acquired_json, METHOD, bind_record
from core_material_contract import validate, context_binding, digest as object_digest
from project_core_material import blank

TARGET = {'assembly': 'GRCh38', 'contig': '1', 'position': 123, 'reference': 'A', 'alternate': 'G'}


def record(value=None):
    value = deepcopy(event() if value is None else value); value.pop('schemaVersion')
    return {'caseLabel': 'A', 'geneSymbol': 'EXAMPLE', 'allele': TARGET, 'specialEvent': value}


def table(value=None, partial=False):
    value = event() if value is None else value
    reverse = {}
    for name, path in HEADERS.items():
        reverse.setdefault(path, name)
    rows = [('Gene symbol', 'EXAMPLE'), ('Genomic allele', canonical(TARGET).decode())]
    for path, part in leaf_values(value):
        if path == '/schemaVersion' or path == '/trio/personId':
            continue
        if partial and path not in {'/kind', '/familyId', '/eventId', '/personId', '/sex'}:
            continue
        if path not in reverse:
            raise ValueError('TEST_TABLE_PATH_NOT_SUPPORTED:' + path)
        rows.append((reverse[path], part if type(part) is str else canonical(part).decode()))
    body = ''.join('<tr><td>' + xml_escape(key) + '</td><td>' + xml_escape(value) + '</td></tr>' for key, value in rows)
    return ('<table-wrap><table><tr><th>Observation</th><th>Case A</th></tr>' + body + '</table></table-wrap>').encode()


def options(root):
    result = config_for(root)
    path = root / result['path']; config = parse(path.read_bytes())
    policy_path = root / config['contexts'][0]['policy']['path']; policy = parse(policy_path.read_bytes())
    policy['scope']['diseaseId'] = 'MONDO:0000001'
    policy['scope']['inheritance'] = 'AR'; policy['specialEvents'] = {'mode': 'SVI_1_1', 'sourceIds': ['paper']}
    policy_path.write_bytes(canonical(policy)); config['contexts'][0]['scope'] = policy['scope']
    config['contexts'][0]['policy']['sha256'] = digest(policy_path.read_bytes())
    path.write_bytes(canonical(config)); result['sha256'] = digest(path.read_bytes())
    return result


def registered_intake(root, raw, attachment=None):
    library = root / 'library'; library.mkdir()
    transport = Mock()
    transport.get.return_value = zipped([('events.json', attachment)]) if attachment is not None else b''
    files, bundle = source_bundle({'pmcid': PMCID}, raw, prepare(raw, PMCID, digest(raw)), transport, attachment is not None)
    receipt = register(library, PMCID, files, bundle, {'sha256': digest(raw), 'acquisitionUrl':
        'https://www.ebi.ac.uk/europepmc/webservices/rest/' + PMCID + '/fullTextXML'})
    source = load(library, PMCID, receipt['bundleSha256'])
    config = configuration(); config_path = root / 'config.json'; config_path.write_bytes(canonical(config))
    (root / 'key.txt').write_text('synthetic-not-secret')
    proposed = candidate(source); target = proposed['cases'][0]['fields'][0]['citation']['unitId']
    index = next(i for i, packet in enumerate(requests(source)) if target in parse(packet['messages'][1]['content'])['workUnit']['targetUnitIds'])
    run(library, PMCID, source['bundleSha256'], config, config_path, root / 'budget', root / 'model',
        lambda *args: response(proposed), chunk_index=index)
    artifact_path = root / 'model/artifact.json'; artifact = parse(artifact_path.read_bytes())
    artifact['receipt']['transportKind'] = 'SYNTHETIC_TEST'; artifact_path.write_bytes(canonical(artifact))
    for receipt_path in (root / 'budget/responses').glob('*/receipt.json'):
        item = parse(receipt_path.read_bytes()); item['transportKind'] = 'SYNTHETIC_TEST'; receipt_path.write_bytes(canonical(item))
    return source, pack(library, [artifact_path])


class DiscoveryTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[2] / 'local/temp'; temporary_root.mkdir(parents=True, exist_ok=True)
        # Keep hashed source-bundle filenames within Windows limits in nested worktrees.
        self.temp = tempfile.TemporaryDirectory(prefix='sd-', dir=temporary_root)
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)

    def run_normal(self, attachment=None, jats_table=None):
        raw = article()
        if jats_table is not None:
            extra_rows = jats_table.split(b'</tr>', 1)[1].split(b'</table>', 1)[0]
            raw = raw.replace(b'</table>', extra_rows + b'</table>', 1)
        source, intake = registered_intake(self.root, raw, attachment)
        config = options(self.root)
        facts = replay_intake(raw, PMCID, digest(raw), intake, work_root=self.root, core_context=config)
        return facts['cases'][0]['coreEvaluation'], facts, source, intake

    def test_normal_reader_discovers_registered_json_without_per_case_configuration(self):
        raw = canonical({'cases': [record()]})
        core, facts, _, _ = self.run_normal(attachment=raw)
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('CONSTITUTIONAL', core['specialEvent']['kind'])
        proof = core['fieldProofs']['/extensions/specialEvent']
        self.assertEqual(METHOD, proof['check']['method'])
        self.assertTrue(any(c['locator'] == '/cases/0/specialEvent/familyId' for c in proof['fact']['citations']))
        saved = parse(gzip.decompress(base64.b64decode(facts['coreAuditArchive']['payload'])))
        self.assertEqual(raw, base64.b64decode(saved['originalsBySha256'][digest(raw)]))
        self.assertEqual('NOT_RUN', core['criterionDecision'])

    def test_normal_reader_discovers_jats_cells_with_raw_unit_proofs(self):
        core, _, source, _ = self.run_normal(jats_table=table())
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual(event(), core['specialEvent'])
        self.assertEqual(digest(source['raw']), core['specialEventSource']['eventIdentityProof']['authoritySha256'])
        citations = core['fieldProofs']['/extensions/specialEvent']['fact']['citations']
        units = {u['unitId']: u['text'] for u in source['main']['units']}
        for citation in citations:
            if citation['kind'] == 'JATS_UNIT':
                self.assertEqual(units[citation['locator']], citation['quote'])

    def test_partial_table_keeps_locators_without_filling_missing_genotype_or_parentage(self):
        core, *_ = self.run_normal(jats_table=table(partial=True))
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('REQUIRED_FACTS_MISSING' in str(item) for item in core['blockers']))
        self.assertIsNone(core['values']['/extensions/specialEvent'])
        located = [r for r in core['sourceDiscovery']['records'] if r['isEventCandidate']]
        self.assertIn('/genotype', located[0]['missingPaths'])
        self.assertTrue(any(f['path'] == '/sex' for f in located[0]['fields']))

    def test_wrong_variant_record_is_not_adopted(self):
        row = record(); row['allele'] = {**TARGET, 'position': 999}
        core, *_ = self.run_normal(attachment=canonical({'cases': [row]}))
        self.assertNotIn('specialEvent', core)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])

    def test_two_matching_records_do_not_select_favourable_parent_result(self):
        first = record(); second = deepcopy(first); second['specialEvent']['trio']['motherTarget'] = 'PRESENT'
        core, *_ = self.run_normal(attachment=canonical({'cases': [first, second]}))
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('MULTIPLE_EVENT_RECORDS_AMBIGUOUS' in str(item) for item in core['blockers']))

    def test_model_style_qualification_flags_are_not_event_fields(self):
        row = record(); row['specialEvent']['qualified'] = True
        core, *_ = self.run_normal(attachment=canonical({'cases': [row]}))
        self.assertNotIn('specialEvent', core)
        self.assertEqual('SOFTWARE_UNSUPPORTED', core['status'])

    def test_conflicting_alias_headers_do_not_overwrite_reported_sex(self):
        raw_table = table().replace(b'</table>', b'<tr><td>event person sex</td><td>FEMALE</td></tr></table>')
        core, *_ = self.run_normal(jats_table=raw_table)
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('SOURCE_FIELD_CONFLICT' in str(item) for item in core['blockers']))

    def test_unsupported_literal_kind_does_not_fall_back_to_ordinary_scoring(self):
        row = record(); row['specialEvent'] = {'kind': 'a model says qualified'}
        core, *_ = self.run_normal(attachment=canonical({'cases': [row]}))
        self.assertNotIn('specialEvent', core)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])

    def test_optional_observation_scope_with_missing_fields_is_not_filled(self):
        row = record(); row['specialEvent']['trio']['observationScopes'] = {'subject': {}}
        core, *_ = self.run_normal(attachment=canonical({'cases': [row]}))
        self.assertNotIn('specialEvent', core)
        missing = [p for r in core['sourceDiscovery']['records'] for p in r['missingPaths']]
        self.assertIn('/trio/observationScopes/subject/specimenId', missing)
        self.assertIn('/trio/observationScopes/subject/tissue', missing)

    def test_resigned_source_fact_is_reparsed_from_original_json(self):
        _, facts, *_ = self.run_normal(attachment=canonical({'cases': [record()]}))
        saved = parse(gzip.decompress(base64.b64decode(facts['coreAuditArchive']['payload'])))
        restored = self.root / 'restored'; restored.mkdir()
        document = ledger = None
        for name, pin in saved['files'].items():
            raw = base64.b64decode(saved['originalsBySha256'][pin]); destination = restored / name
            self.assertTrue(destination.resolve().is_relative_to(restored.resolve()))
            destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(raw)
            if name.endswith('.json'):
                value = parse(raw)
                if type(value) is dict and value.get('schemaVersion') == 'denovo-core-material-v1':
                    document = value
                if type(value) is dict and value.get('schemaVersion') == 'denovo-core-review-ledger-v1':
                    ledger = value
        self.assertIsNotNone(document); self.assertIsNotNone(ledger)
        fact = document['extensions']['specialEvent']; fact['value']['trio']['motherTarget'] = 'PRESENT'
        fact['rawValue'] = canonical(fact['value']).decode()
        ledger['checks']['/extensions/specialEvent']['factSha256'] = object_digest(fact)
        ledger['contextBinding'] = context_binding(document)
        with self.assertRaisesRegex(ValueError, 'REPLAY_FACT_MISMATCH'):
            validate(document, restored, ledger, object_digest(ledger))

    def test_boolean_position_cannot_match_integer_allele_identity(self):
        doc = blank(PMCID + '#A')
        doc['groups']['gene']['symbol'].update(value='EXAMPLE', availability='REPORTED', verification='VERIFIED')
        doc['groups']['variant']['resolvedAllele'].update(value={**TARGET, 'position': 1}, availability='REPORTED', verification='VERIFIED')
        row = record(); row['allele'] = {**TARGET, 'position': True}
        self.assertEqual('ALLELE_BINDING_MISMATCH', bind_record(row, doc))

    def test_undeclared_extra_json_file_is_not_a_registered_attachment(self):
        source, intake = registered_intake(self.root, article())
        raw = canonical({'cases': [record()]})
        intake['files']['papers/' + PMCID + '/unregistered.json'] = {'sha256': digest(raw), 'base64': base64.b64encode(raw).decode()}
        origins, _ = acquired_json(source, {'original-intake.json': canonical(intake)})
        self.assertEqual([], origins)

    def test_flat_record_is_parsed_from_explicit_fields(self):
        row = {'caseLabel': 'A', 'geneSymbol': 'EXAMPLE', 'allele': TARGET}
        for name, path in HEADERS.items():
            try:
                from core_material_contract import pointer
                value = pointer(event(), path)
            except ValueError:
                continue
            row[name] = value
        core, *_ = self.run_normal(attachment=canonical({'observations': [row]}))
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual(event(), core['specialEvent'])


def write_java_fixture(output, kind='JATS', reported_partner_status='UNRESOLVED'):
    """Actual normal intake replay, with synthetic identity and no case config."""
    if kind not in {'JATS', 'JSON'}:
        raise ValueError('FIXTURE_KIND_UNSUPPORTED')
    if reported_partner_status not in {'UNRESOLVED', 'NONE_IN_CHECKED_SCOPE'}:
        raise ValueError('FIXTURE_REPORTED_PARTNER_STATUS_UNSUPPORTED')
    fixture = DiscoveryTests(); fixture.setUp()
    try:
        reported = event(); reported['partner']['status'] = reported_partner_status
        if kind == 'JATS':
            core, facts, source, intake = fixture.run_normal(jats_table=table(reported))
        else:
            core, facts, source, intake = fixture.run_normal(attachment=canonical({'cases': [record(reported)]}))
        output = Path(output)
        if output.exists():
            raise ValueError('NEW_FIXTURE_DIRECTORY_REQUIRED')
        output.mkdir(parents=True)
        shutil.copytree(fixture.root / 'local', output / 'local')
        config_path = output / 'local/core-config.json'; config = parse(config_path.read_bytes())
        policy = parse((output / config['contexts'][0]['policy']['path']).read_bytes())
        (output / 'synthetic-literature.xml').write_bytes(source['raw'])
        (output / 'bundle-intake.json').write_bytes(canonical(intake))
        (output / 'facts.json').write_bytes(canonical(facts))
        (output / 'model-artifacts.json').write_bytes(canonical({'schemaVersion': 'denovo-bundle-intake-index-v1',
            'artifacts': [{'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'path': 'bundle-intake.json',
                           'sha256': digest(canonical(intake))}]}))
        support = output / 'scripts/acmg-generalization'; support.mkdir(parents=True)
        for name in ('normalize_public_panel.py', 'build_transcript_model.py'):
            (support / name).write_bytes((Path(__file__).resolve().parents[1] / 'acmg-generalization' / name).read_bytes())
        result = {'core': core, 'policy': policy, 'policySources': {'paper': 'fixed knowledge'},
            'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'bundleSha256': source['bundleSha256'],
            'coreContext': {'root': str(output.resolve()), 'path': 'local/core-config.json', 'sha256': digest(config_path.read_bytes())},
            'sourceClass': 'SYNTHETIC_NORMAL_REGISTERED_' + kind, 'criterionDecision': 'NOT_RUN'}
        path = output / 'java-discovery-fixture.json'; path.write_bytes(canonical(result))
        return path
    finally:
        fixture.doCleanups()


def audit_real_jats(path, pmcid, expected_sha256, case_label):
    """Explicit, offline public-original audit; no local cache traversal."""
    raw = Path(path).read_bytes()
    if digest(raw) != expected_sha256:
        raise ValueError('REAL_ORIGINAL_PIN_MISMATCH')
    prepared = prepare(raw, pmcid, expected_sha256)
    source = {'raw': raw, 'pmcid': pmcid, 'main': prepared, 'attachments': []}
    selected, report = discover(source, blank(pmcid + '#' + case_label))
    units = {u['unitId']: u['text'] for u in prepared['units']}
    fields = [field for record in report['records'] for field in record['fields']]
    if not fields:
        raise ValueError('REAL_STRUCTURED_FIELDS_NOT_LOCATED')
    for field in fields:
        if field['kind'] != 'JATS_UNIT' or units.get(field['locator']) != field['quote']:
            raise ValueError('REAL_STRUCTURED_FIELD_REPLAY_FAILED')
    if selected is not None:
        raise ValueError('REAL_PARTIAL_SOURCE_MUST_NOT_BECOME_QUALIFIED_EVENT')
    return {'pmcid': pmcid, 'sourceSha256': expected_sha256, 'caseLabel': case_label,
            'fieldLocations': fields, 'criterionDecision': 'NOT_RUN', 'eventStatus': 'INCOMPLETE_SOURCE_OBSERVATIONS',
            'report': report}


if __name__ == '__main__':
    unittest.main()
