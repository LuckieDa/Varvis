"""Synthetic controls for candidate intake; never a real model/clinical claim."""
import base64
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_literature_source_assertions import ARTICLE, RECORD, PMCID, VCV, sha
from test_bundle_model import configuration, response
from prepare_literature import prepare
from extract_literature_cases import extract
from acquire_public_literature import source_bundle
from paper_library_registration import register
from bundle_model_contract import load, requests, review, RESPONSE_VERSION
from model_literature_contract import canonical, digest, parse
from run_bundle_model import run
from bundle_candidate_intake import pack, replay_intake
from qualify_bundle_candidates import qualify


def article(raw=ARTICLE):
    return raw.replace(b'<body>', b'<body><sec><title>Methods</title><p>Patients were recruited for this study.</p></sec>').replace(b'The EXAMPLE variant', b'Case A was studied. The EXAMPLE variant')


def registered(root, raw):
    library = root / 'library'; library.mkdir()
    files, bundle = source_bundle({'pmcid': PMCID}, raw, prepare(raw, PMCID, sha(raw)), None, False)
    registration = register(library, PMCID, files, bundle, {'sha256': sha(raw), 'acquisitionUrl':
        'https://www.ebi.ac.uk/europepmc/webservices/rest/' + PMCID + '/fullTextXML'})
    return library, load(library, PMCID, registration['bundleSha256'])


def candidate(source):
    d = source['main']; native = extract(d)['cases'][0]
    own = next(u for u in d['units'] if u['kind'] == 'BODY_PARAGRAPH' and 'c.123A>G' in u['text'])
    method = next(u for u in d['units'] if u['text'] == 'Patients were recruited for this study.')
    quote = lambda unit: {'unitId': unit['unitId'], 'quote': unit['text']}
    return {'schemaVersion': RESPONSE_VERSION, 'coverage': 'EXTRACTED_WITHIN_TARGET',
        'sourceAssessments': [{'materialKind': 'INDIVIDUAL_CASE_MATERIAL', 'citation': quote(own)}],
        'cases': [{'caseLabel': 'A', 'caseOrigin': 'AUTHOR_COHORT',
            'caseCitation': {'unitId': own['unitId'], 'quote': 'Case A was studied.'},
            'originCitations': [quote(method)], 'fields': [
                {'field': field, 'sourceValue': value, 'subject': 'PROBAND', 'citation': quote(own)}
                for field, value in [('geneSymbol', 'EXAMPLE'), ('variantText', 'c.123A>G'), ('proteinChange', 'p.T41A')]]}]}


def synthetic_intake(root, raw):
    if b'SYNTHETIC only' not in raw:
        raise ValueError('SYNTHETIC_FIXTURE_REQUIRED')
    library, source = registered(root, raw)
    c = configuration(); config = root / 'config.json'; config.write_bytes(canonical(c))
    (root / 'key.txt').write_text('synthetic-not-secret')
    proposed = candidate(source)
    target = proposed['cases'][0]['fields'][0]['citation']['unitId']
    index = next(i for i, p in enumerate(requests(source)) if target in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
    run(library, PMCID, source['bundleSha256'], c, config, root / 'budget', root / 'model',
        lambda *a: response(proposed), chunk_index=index)
    path = root / 'model/artifact.json'; value = parse(path.read_bytes())
    value['receipt']['transportKind'] = 'SYNTHETIC_TEST'; path.write_bytes(canonical(value))
    for receipt_path in (root / 'budget/responses').glob('*/receipt.json'):
        receipt = parse(receipt_path.read_bytes()); receipt['transportKind'] = 'SYNTHETIC_TEST'
        receipt_path.write_bytes(canonical(receipt))
    return source, pack(library, [path])


def write_synthetic_fixture(root):
    """Called by Java integration tests only; creates no external connections."""
    root = Path(root); path = root / 'synthetic-literature.xml'
    raw = path.read_bytes()
    source, intake = synthetic_intake(root, raw)
    body = canonical(intake); (root / 'bundle-intake.json').write_bytes(body)
    (root / 'model-artifacts.json').write_bytes(canonical({'schemaVersion': 'denovo-bundle-intake-index-v1',
        'artifacts': [{'pmcid': PMCID, 'sourceSha256': sha(raw), 'path': 'bundle-intake.json', 'sha256': digest(body)}]}))


class BundleQualificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.raw = article()
        self.library, self.source = registered(self.root, self.raw)
        self.proposed = candidate(self.source)

    def checked(self, proposed=None, source=None):
        source = source or self.source
        value = review(source, proposed or self.proposed)
        value['workUnit'] = {'targetUnitIds': [value['cases'][0]['fields'][0]['citation']['unitId']] if value['cases'] else []}
        return value

    def facts(self, reviews=None, records=None):
        return qualify(self.source, reviews if reviews is not None else [self.checked()],
                       [(RECORD, sha(RECORD), VCV)] if records is None else records)

    def test_partial_target_recovers_native_phenotype_and_full_case(self):
        facts = self.facts(); case = facts['cases'][0]
        self.assertEqual([], case['issues'])
        self.assertEqual('PUBLISHED_TRIO_SANGER_DENOVO', case['targetTesting']['status'])
        self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', case['parentage']['status'])
        self.assertTrue(any(f['field'] == 'reportedPhenotype' and f['sourceValue'] == 'Synthetic trait' for f in case['fields']))
        self.assertEqual('PARTIAL', facts['extractionCoverage']['status'])

    def test_same_case_across_targets_deduplicates_but_preserves_proposals(self):
        one = self.checked(); other = deepcopy(one)
        other['cases'][0]['fields'] = other['cases'][0]['fields'][:1]
        facts = self.facts([one, other, one])
        self.assertEqual(1, len(facts['cases']))
        self.assertEqual([], facts['cases'][0]['issues'])
        self.assertEqual(3, len(facts['cases'][0]['candidateProvenance']))
        self.assertEqual(1, sum(f['field'] == 'variantText' for f in facts['cases'][0]['fields']))

    def test_family_subject_rechecked_from_explicit_case_source(self):
        sentence = 'He is the second child of healthy non-consanguineous parents, with no family history of neurological disorders.'
        raw = self.raw.replace(b'</p></sec></sec>', ('</p><p>' + sentence + '</p></sec></sec>').encode())
        with tempfile.TemporaryDirectory() as td:
            unused, source = registered(Path(td), raw)
            proposed = candidate(source)
            unit = next(u for u in source['main']['units'] if u['text'] == sentence)
            proposed['cases'][0]['fields'].append({'field': 'reportedFamilyHistory',
                'subject': 'FAMILY', 'sourceValue': 'no family history of neurological disorders',
                'citation': {'unitId': unit['unitId'], 'quote': sentence}})
            facts = qualify(source, [self.checked(proposed, source)], [(RECORD, sha(RECORD), VCV)])
            case = facts['cases'][0]
            self.assertEqual([], case['issues'])
            self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', case['parentage']['status'])
            self.assertEqual('REPORTED_NONE', case['familyHistory'])

    def test_uninterpreted_additional_phenotype_does_not_erase_supported_facts(self):
        sentence = 'He was noted to have early motor delay.'
        raw = self.raw.replace(b'</p></sec></sec>', ('</p><p>' + sentence + '</p></sec></sec>').encode())
        with tempfile.TemporaryDirectory() as td:
            unused, source = registered(Path(td), raw)
            proposed = candidate(source)
            unit = next(u for u in source['main']['units'] if u['text'] == sentence)
            proposed['cases'][0]['fields'].append({'field': 'reportedPhenotype',
                'subject': 'PROBAND', 'sourceValue': 'early motor delay',
                'citation': {'unitId': unit['unitId'], 'quote': sentence}})
            case = qualify(source, [self.checked(proposed, source)], [(RECORD, sha(RECORD), VCV)])['cases'][0]
            self.assertEqual([], case['issues'])
            self.assertEqual('PUBLISHED_TRIO_SANGER_DENOVO', case['targetTesting']['status'])
            self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', case['parentage']['status'])
            self.assertEqual('NOT_ADOPTED', case['fieldLimitations'][0]['disposition'])
            self.assertFalse(any(f['sourceValue'] == 'early motor delay' for f in case['fields']))
            self.assertTrue(any(f['sourceValue'] == 'Synthetic trait' for f in case['fields']))

    def test_explicit_negative_against_native_phenotype_still_blocks(self):
        sentence = 'The proband had no Synthetic trait.'
        raw = self.raw.replace(b'</p></sec></sec>', ('</p><p>' + sentence + '</p></sec></sec>').encode())
        with tempfile.TemporaryDirectory() as td:
            unused, source = registered(Path(td), raw)
            proposed = candidate(source)
            unit = next(u for u in source['main']['units'] if u['text'] == sentence)
            proposed['cases'][0]['fields'].append({'field': 'reportedPhenotypeNegation',
                'subject': 'PROBAND', 'sourceValue': 'no Synthetic trait',
                'citation': {'unitId': unit['unitId'], 'quote': sentence}})
            case = qualify(source, [self.checked(proposed, source)], [(RECORD, sha(RECORD), VCV)])['cases'][0]
            self.assertTrue(any('CONFLICT' in issue for issue in case['issues']))

    def test_family_role_does_not_approve_unknown_or_conflicting_family_history(self):
        for sentence in ('There was a family history of neurological disorders.',
                         'He is the second child of healthy non-consanguineous parents, with no family history of neurological disorders.'):
            raw = self.raw.replace(b'<td>None</td>', b'<td>Affected relatives</td>')
            raw = raw.replace(b'</p></sec></sec>', ('</p><p>' + sentence + '</p></sec></sec>').encode())
            with tempfile.TemporaryDirectory() as td:
                unused, source = registered(Path(td), raw)
                proposed = candidate(source)
                unit = next(u for u in source['main']['units'] if u['text'] == sentence)
                proposed['cases'][0]['fields'].append({'field': 'reportedFamilyHistory',
                    'subject': 'FAMILY', 'sourceValue': sentence,
                    'citation': {'unitId': unit['unitId'], 'quote': sentence}})
                case = qualify(source, [self.checked(proposed, source)], [(RECORD, sha(RECORD), VCV)])['cases'][0]
                self.assertTrue(case['issues'])
                self.assertEqual('UNKNOWN', case['targetTesting']['status'])

    def test_phenotype_conflict_does_not_depend_on_proposal_order(self):
        positive = 'The proband had Other trait.'
        negative = 'The proband had no Other trait.'
        raw = self.raw.replace(b'</p></sec></sec>',
                               ('</p><p>' + positive + '</p><p>' + negative + '</p></sec></sec>').encode())
        with tempfile.TemporaryDirectory() as td:
            unused, source = registered(Path(td), raw)
            proposed = candidate(source)
            additions = []
            for name, value, sentence in (
                    ('reportedPhenotype', 'Other trait', positive),
                    ('reportedPhenotypeNegation', 'no Other trait', negative)):
                unit = next(u for u in source['main']['units'] if u['text'] == sentence)
                additions.append({'field': name, 'subject': 'PROBAND', 'sourceValue': value,
                                  'citation': {'unitId': unit['unitId'], 'quote': sentence}})
            for ordered in (additions, list(reversed(additions))):
                with self.subTest(first=ordered[0]['field']):
                    value = deepcopy(proposed)
                    value['cases'][0]['fields'].extend(ordered)
                    case = qualify(source, [self.checked(value, source)], [(RECORD, sha(RECORD), VCV)])['cases'][0]
                    self.assertIn('LITERATURE_FIELD_CONFLICT:BUNDLE_CONFLICTING_REPORTEDPHENOTYPE',
                                  case['issues'])

    def test_nonproband_and_unknown_subject_are_not_adopted(self):
        for subject in ('FATHER', 'MOTHER', 'UNKNOWN'):
            row = self.checked(); row['cases'][0]['fields'][0]['subject'] = subject
            with self.subTest(subject=subject):
                self.assertIn('LITERATURE_FIELD_CONFLICT:BUNDLE_SUBJECT_NOT_VERIFIED', self.facts([row])['cases'][0]['issues'])

    def test_cited_unclear_and_previously_reported_origins_remain_blocked(self):
        for origin in ('CITED_ONLY', 'UNCLEAR', 'PREVIOUSLY_REPORTED_REEXAMINED'):
            value = deepcopy(self.proposed); value['cases'][0]['caseOrigin'] = origin
            with self.subTest(origin=origin):
                self.assertTrue(any('ORIGIN_NOT_PRIMARY' in i for i in self.facts([self.checked(value)])['cases'][0]['issues']))

    def test_origin_requires_original_methods_not_model_label(self):
        row = self.checked(); row['cases'][0]['originCitations'] = [row['cases'][0]['fields'][0]['citation']]
        self.assertTrue(any('PRIMARY_COHORT_NOT_ESTABLISHED' in i for i in self.facts([row])['cases'][0]['issues']))

    def test_conflicting_values_never_choose_favourable_one(self):
        row = self.checked(); changed = deepcopy(row)
        changed['cases'][0]['fields'][1]['sourceValue'] = 'c.124A>G'
        self.assertTrue(any('CITATION_OR_VALUE_INVALID' in i for i in self.facts([row, changed])['cases'][0]['issues']))

    def test_attachment_only_and_aggregate_no_score(self):
        row = self.checked(); row['cases'][0]['fields'][0]['citation']['unitId'] = 'attachment:' + 'a' * 64 + ':0:A2'
        self.assertTrue(any('ATTACHMENT_CASE_ATTRIBUTION' in i for i in self.facts([row])['cases'][0]['issues']))
        empty = self.checked(); empty['cases'] = []
        result = self.facts([empty]); self.assertEqual([], result['cases'])
        self.assertIn('NOT_ABSENCE', result['issues'][0])

    def test_missing_allele_and_wrong_transcript_have_specific_gap(self):
        self.assertTrue(self.facts(records=[])['cases'][0]['alleleIdentity']['issues'])
        record = RECORD.replace(b'123A&gt;G', b'124A&gt;G')
        self.assertTrue(self.facts(records=[(record, sha(record), VCV)])['cases'][0]['alleleIdentity']['issues'])

    def test_full_source_hidden_inheritance_and_prior_publication_block(self):
        for concern in ('The variant was inherited from the mother.', 'This case was previously reported elsewhere.'):
            raw = self.raw.replace(b'</p></sec></sec>', (concern + '</p></sec></sec>').encode())
            with tempfile.TemporaryDirectory() as td:
                library, source = registered(Path(td), raw)
                proposed = candidate(source); r = self.checked(proposed, source)
                facts = qualify(source, [r], [(RECORD, sha(RECORD), VCV)])
                self.assertTrue(facts['cases'][0]['issues'], concern)

    def test_proband_phenotype_cannot_borrow_parent_or_negation(self):
        for clause in ('The mother had Other trait.', 'The proband had no Other trait.'):
            raw = self.raw.replace(b'</p></sec></sec>', (clause + '</p></sec></sec>').encode())
            with tempfile.TemporaryDirectory() as td:
                library, source = registered(Path(td), raw)
                proposed = candidate(source); f = deepcopy(proposed['cases'][0]['fields'][0])
                f.update(field='reportedPhenotype', sourceValue='Other trait')
                proposed['cases'][0]['fields'].append(f)
                r = self.checked(proposed, source)
                facts = qualify(source, [r], [(RECORD, sha(RECORD), VCV)])
                self.assertTrue(any('PHENOTYPE_SUBJECT_OR_NEGATION' in i for i in facts['cases'][0]['issues']))

    def test_intake_replays_and_detects_mutations_offline(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td); source, intake = synthetic_intake(root, self.raw)
            facts = replay_intake(self.raw, PMCID, sha(self.raw), intake, [(RECORD, sha(RECORD), VCV)], root)
            self.assertEqual([], facts['cases'][0]['issues'])
            self.assertEqual('SYNTHETIC_TEST', facts['modelProvenance']['receipt']['transportKind'])
            self.assertEqual(0, facts['modelCalls'])
            legacy = deepcopy(intake)
            index = parse(base64.b64decode(legacy['files']['evidence/PS2/index.json']['base64']))
            self.assertEqual('PS2', index.pop('criterion'))
            old_index = canonical(index)
            legacy['files']['evidence/PS2/index.json'] = {'sha256':sha(old_index), 'base64':base64.b64encode(old_index).decode()}
            frozen = canonical(legacy)
            self.assertEqual(facts['cases'], replay_intake(self.raw, PMCID, sha(self.raw), legacy, [(RECORD, sha(RECORD), VCV)], root)['cases'])
            self.assertEqual(frozen, canonical(legacy))
            wrong_index = canonical({'criterion':'PM3', **index})
            legacy['files']['evidence/PS2/index.json'] = {'sha256':sha(wrong_index), 'base64':base64.b64encode(wrong_index).decode()}
            with self.assertRaisesRegex(ValueError, 'INDEX_CRITERION_MISMATCH'):
                replay_intake(self.raw, PMCID, sha(self.raw), legacy, [], root)
            for change in ('source', 'response', 'review', 'path', 'identity'):
                bad = deepcopy(intake)
                if change == 'source': next(iter(bad['files'].values()))['sha256'] = 'f' * 64
                if change == 'response': bad['artifacts'][0]['responseBase64'] = base64.b64encode(b'{}').decode()
                if change == 'review': bad['artifacts'][0]['review']['cases'] = []
                if change == 'path': bad['files']['../escaped'] = next(iter(bad['files'].values()))
                if change == 'identity': bad['artifacts'][0]['binding']['pmcid'] = 'PMC1'
                with self.subTest(change=change), self.assertRaises(ValueError):
                    replay_intake(self.raw, PMCID, sha(self.raw), bad, [], root)
            duplicate = deepcopy(intake); duplicate['artifacts'] *= 2
            self.assertEqual(1, len(replay_intake(self.raw, PMCID, sha(self.raw), duplicate, [], root)['cases']))

    def test_workbench_pipeline_automatically_prepares_intake_from_cache(self):
        from public_literature_workflow import run as workflow, VERSION as protocol
        with tempfile.TemporaryDirectory() as td:
            project = Path(td); root = project / 'local/fixture'; root.mkdir(parents=True)
            source, unused = synthetic_intake(root, self.raw)
            mc = parse((root / 'config.json').read_bytes()); mc['enabled'] = False
            (root / 'config.json').write_bytes(canonical(mc)); (root / 'key.txt').unlink()
            cfg = {'schemaVersion': protocol, 'enabled': True, 'projectRoot': str(project),
                   'library': 'local/fixture/library', 'searchCache': 'local/search',
                   'modelBudget': 'local/fixture/budget', 'modelConfig': 'local/fixture/config.json',
                   'allowModel': False, 'allowPublicNetwork': False}
            config = project / 'workflow.json'; config.write_bytes(canonical(cfg))
            request = project / 'request.json'; request.write_bytes(canonical({
                'schemaVersion': protocol, 'genes': ['EXAMPLE'], 'selectionSha256': 'a' * 64}))
            def discovery_stub(unused, destination, *args, **kwargs):
                destination.mkdir(); value = {'networkRequests': 0, 'status': 'PARTIAL', 'candidates': [], 'targets': [],
                                             'checkpoint': {}, 'resumeAvailable': False}
                (destination / 'discovery.json').write_bytes(canonical(value))
                return value
            with patch('public_literature_workflow.discover', side_effect=discovery_stub), \
                 patch('public_literature_workflow.acquire', return_value={'networkRequests': 0, 'papers': [
                    {'pmcid': PMCID, 'status': 'REGISTERED', 'registration': {'bundleSha256': source['bundleSha256']}}]}):
                result = workflow(request, project / 'result.json', config)
            self.assertEqual(0, result['modelCalls'])
            self.assertEqual(0, result['networkRequests'])
            self.assertFalse(result['scientificExecution'])
            self.assertEqual('PARTIAL', result['status'])
            self.assertTrue(result['papers'], result)
            entry = result['papers'][0]['candidateIntake']
            raw = (project / 'pipeline' / entry['path']).read_bytes()
            self.assertEqual(entry['sha256'], digest(raw))
            facts = replay_intake(self.raw, PMCID, sha(self.raw), parse(raw), [(RECORD, sha(RECORD), VCV)], root)
            self.assertEqual([], facts['cases'][0]['issues'])


if __name__ == '__main__':
    unittest.main()
