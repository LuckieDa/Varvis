"""Synthetic registered-original replay; no claim of real clinical qualification."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from core_special_event import leaves
from core_material_contract import assess, validate, digest as object_digest, context_binding
from core_material_schema import SPECIAL_EVENT
from core_material_contract import validate_shape
from core_material_extraction import write_package
from core_material_review import review_package
from core_context_review import context_package
from core_context_policy import read_policy
from core_evaluation_intake import project_package, archive_package
from test_core_context_review import LocalFixture, policy
from test_bundle_qualification import article, registered, candidate, PMCID
from bundle_model_contract import review
from qualify_bundle_candidates import qualify

ALLELE = {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}


def event():
    return {'schemaVersion': 'denovo-special-event-v1', 'kind': 'CONSTITUTIONAL',
        'familyId': 'F1', 'eventId': 'E1', 'personId': 'A', 'probandId': 'A', 'sex': 'MALE',
        'affectedStatus': 'AFFECTED', 'ploidy': 'DIPLOID', 'genotype': 'HETEROZYGOUS',
        'parentalAvailability': 'AVAILABLE', 'otherAffectedMales': 'UNKNOWN',
        'trio': {'personId': 'A', 'motherId': 'M', 'fatherId': 'F', 'subjectTarget': 'PRESENT',
            'motherTarget': 'ABSENT', 'fatherTarget': 'ABSENT', 'maternity': 'CONFIRMED', 'paternity': 'CONFIRMED',
            'maternityMethod': 'SNP_PANEL', 'paternityMethod': 'SNP_PANEL', 'method': 'SANGER',
            'targetCoverage': 'EXPLICIT_TARGET', 'detectionCapability': 'ADEQUATE', 'quality': 'VALID'},
        'partner': {'status': 'UNRESOLVED', 'allele': None, 'phase': 'UNKNOWN', 'sourceRefs': []},
        'siblings': [], 'eventOrigin': 'UNKNOWN', 'tissues': []}


class SpecialEventTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[2] / 'local' / 'temp'
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='special-event-', dir=temporary_root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.f = LocalFixture(self.root)
        self.p = policy(); self.p['scope']['inheritance'] = 'AR'
        self.p['specialEvents'] = {'mode': 'SVI_1_1', 'sourceIds': ['paper']}
        self.p['literatureTerms'][0]['aliases'] = ['Synthetic trait']
        self.envelope = {'schemaVersion': 'denovo-special-event-source-v1',
            'sourceClass': 'ORIGINAL_JSON_ATTACHMENT', 'sourceDescription': 'Synthetic original attachment fixture',
            'caseKey': PMCID + '#A', 'allele': ALLELE, 'scope': deepcopy(self.p['scope']),
            'specialEvent': event(), 'sourceReferences': []}

    def build(self, derived=False, change=None, registered_source=True, tamper_pin=False, source_raw=None, partner_sources=None, split_identity_sources=False, source_pmcid=None):
        raw = article() if source_raw is None else source_raw
        if source_pmcid is None:
            _, self.source = registered(self.root, raw)
        else:
            # A second synthetic paper is really registered and replayed; no
            # post-replay edits to case keys or source proofs are permitted.
            from prepare_literature import prepare
            from acquire_public_literature import source_bundle
            from paper_library_registration import register
            from bundle_model_contract import load
            raw = raw.replace(PMCID[3:].encode(), source_pmcid[3:].encode())
            library = self.root / 'library'; library.mkdir()
            files, bundle = source_bundle({'pmcid': source_pmcid}, raw, prepare(raw, source_pmcid, digest(raw)), None, False)
            registration = register(library, source_pmcid, files, bundle, {'sha256': digest(raw),
                'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/' + source_pmcid + '/fullTextXML'})
            self.source = load(library, source_pmcid, registration['bundleSha256'])
        envelope = deepcopy(self.envelope); envelope['caseKey'] = self.source['pmcid'] + '#A'; parents = []
        if derived:
            envelope['sourceClass'] = 'REGISTERED_STRUCTURED_REPORT'
            original = canonical({'reported': envelope['specialEvent']})
            parent = self.f.put('parent-original.json', original); parents.append(parent)
            envelope['sourceReferences'] = [{'eventPath': p, 'sourceSha256': parent['sha256'], 'recordLocator': '/reported' + p}
                                            for p, _ in leaves(envelope['specialEvent'])]
            if split_identity_sources:
                second = self.f.put('event-original.json', canonical({'reported': envelope['specialEvent'], 'recordKind': 'separate event registry'}))
                parents.append(second)
                next(ref for ref in envelope['sourceReferences'] if ref['eventPath'] == '/eventId')['sourceSha256'] = second['sha256']
                if registered_source:
                    self.source['attachments'].append({'sha256': second['sha256'], 'originalName': 'synthetic-event.json',
                                                       'association': 'SYNTHETIC_TEST', 'status': 'ARCHIVED_NOT_PARSED'})

            if registered_source:
                self.source['attachments'].append({'sha256': parent['sha256'], 'originalName': 'synthetic-parent.json',
                                                   'association': 'SYNTHETIC_TEST', 'status': 'ARCHIVED_NOT_PARSED'})
        if change:
            change(envelope)
        pin = self.f.put('special.json', canonical(envelope))
        if not derived and registered_source:
            self.source['attachments'].append({'sha256': pin['sha256'], 'originalName': 'synthetic-original.json',
                                               'association': 'SYNTHETIC_TEST', 'status': 'ARCHIVED_NOT_PARSED'})
        spec = {'caseKey': envelope['caseKey'], **pin, **({'parents': parents} if parents else {})}
        if partner_sources is not None:
            spec['partnerSources'] = partner_sources
        config = {'identity': {'synthetic': True}, 'contexts': [{'scope': self.p['scope'],
            'policy': self.f.put('policy.json', canonical(self.p)),
            'sources': {'paper': self.f.put('knowledge.txt', b'fixed knowledge')}}], 'specialEventSources': [spec]}
        if tamper_pin:
            (self.root / pin['path']).write_bytes(canonical({**envelope, 'sourceDescription': 'Changed original'}))
        checked = review(self.source, candidate(self.source))
        checked['workUnit'] = {'targetUnitIds': [checked['cases'][0]['caseCitation']['unitId']]}
        native = qualify(self.source, [checked])
        write_package(self.source, native, [checked], self.root / 'input', data_class='SYNTHETIC')
        review_package(self.source, self.root / 'input', digest((self.root / 'input/index.json').read_bytes()), self.root / 'review')
        identity = {'status': 'LOCAL_IDENTITY_VERIFIED', 'hgncId': 'HGNC:1', 'geneSymbol': 'EXAMPLE', 'allele': ALLELE}
        with patch('core_context_review.LocalIdentity.resolve', return_value=identity):
            index = context_package(self.source, self.root / 'review', digest((self.root / 'review/index.json').read_bytes()),
                self.root / 'output', self.root, config, complete_events=True)
        row = index['cases'][0]; directory = self.root / 'output'
        doc = parse((directory / row['path']).read_bytes()); ledger = parse((directory / row['ledgerPath']).read_bytes())
        core = project_package(directory, index)[doc['caseKey']]
        return core, doc, ledger, index, spec

    def test_original_typed_route_ready_and_does_not_score(self):
        core, doc, _, index, _ = self.build()
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('denovo-core-evaluation-intake-1.1.0', core['schemaVersion'])
        self.assertEqual(event(), core['specialEvent'])
        self.assertEqual('NOT_RUN', core['criterionDecision'])
        self.assertEqual('E1', core['values']['/groups/overlap/eventId'])
        proof = core['fieldProofs']['/extensions/specialEvent']
        self.assertEqual(proof['fact'], parse(proof['factCanonicalUtf8'].encode()))
        self.assertEqual(proof['check']['factSha256'], digest(proof['factCanonicalUtf8'].encode()))
        self.assertEqual('PAPER_ATTACHMENT', doc['sources']['special-event-original']['role'])
        self.assertEqual('NOT_RUN', core['specialEventSource']['scientificQualification'])
        identity = core['specialEventSource']['eventIdentityProof']
        self.assertEqual(doc['sources']['special-event-original']['sha256'], identity['authoritySha256'])
        self.assertEqual('/specialEvent/familyId', identity['familyLocator'])
        self.assertEqual('/specialEvent/eventId', identity['eventLocator'])
        self.assertIn('core_special_event.py', __import__('core_evaluation_intake').FILES)
        self.assertIn('payload', archive_package(self.root / 'output', index))

    def test_derived_report_requires_and_retains_original_leaf_references(self):
        core, doc, ledger, _, _ = self.build(derived=True)
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('DERIVED_FACTS', doc['sources']['special-event-original']['role'])
        self.assertEqual('PAPER_ATTACHMENT', doc['sources']['special-event-parent-0']['role'])
        self.assertTrue(core['specialEventSource']['parentSourceSha256s'])
        identity = core['specialEventSource']['eventIdentityProof']
        self.assertEqual(doc['sources']['special-event-parent-0']['sha256'], identity['authoritySha256'])
        self.assertEqual('/reported/familyId', identity['familyLocator'])
        self.assertEqual('/reported/eventId', identity['eventLocator'])
        self.assertGreater(len(ledger['checks']['/extensions/specialEvent']['basis']), 30)

    def test_equal_local_labels_in_two_originals_do_not_share_event_authority(self):
        first, *_ = self.build()
        second_fixture = SpecialEventTests(); second_fixture.setUp()
        self.addCleanup(second_fixture.doCleanups)
        second_fixture.envelope['sourceDescription'] = 'A separate original with colliding local labels'
        second, *_ = second_fixture.build()
        self.assertEqual(first['specialEvent']['familyId'], second['specialEvent']['familyId'])
        self.assertEqual(first['specialEvent']['eventId'], second['specialEvent']['eventId'])
        self.assertNotEqual(first['specialEventSource']['eventIdentityProof']['authoritySha256'],
                            second['specialEventSource']['eventIdentityProof']['authoritySha256'])

    def test_identity_fields_from_two_parents_have_no_shared_authority(self):
        core, doc, *_ = self.build(derived=True, split_identity_sources=True)
        identity = core['specialEventSource']['eventIdentityProof']
        self.assertIsNone(identity['authoritySha256'])
        self.assertNotEqual(identity['familySourceSha256'], identity['eventSourceSha256'])
        self.assertEqual(doc['sources']['special-event-parent-0']['sha256'], identity['familySourceSha256'])
        self.assertEqual(doc['sources']['special-event-parent-1']['sha256'], identity['eventSourceSha256'])
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])

    def test_resigned_identity_metadata_cannot_replace_original_namespace(self):
        _, _, _, index, _ = self.build()
        row = index['cases'][0]; path = self.root / 'output' / row['reportPath']
        report = parse(path.read_bytes())
        report['specialEventSource']['eventIdentityProof']['authoritySha256'] = '0' * 64
        path.write_bytes(canonical(report)); row['reportSha256'] = digest(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'IDENTITY_PROOF_REPLAY_MISMATCH'):
            project_package(self.root / 'output', index)

    def test_configured_unregistered_json_is_not_a_source_fact(self):
        core, doc, *_ = self.build(registered_source=False)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])
        self.assertNotIn('specialEvent', core)
        self.assertEqual('UNVERIFIED', doc['extensions']['specialEvent']['verification'])
        self.assertTrue(any('ORIGINAL_NOT_REGISTERED' in str(b) for b in core['blockers']))

    def test_derived_field_renamed_or_value_changed_cannot_self_verify(self):
        core, *_ = self.build(derived=True, change=lambda e: e['specialEvent']['trio'].update(maternity='EXCLUDED'))
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('FIELD_SOURCE_MISMATCH' in str(b) for b in core['blockers']))

    def test_derived_missing_leaf_cannot_be_called_verified(self):
        core, *_ = self.build(derived=True, change=lambda e: e['sourceReferences'].pop())
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('FIELD_REFERENCES_INCOMPLETE' in str(b) for b in core['blockers']))

    def test_same_event_key_retained_for_siblings(self):
        core, *_ = self.build(change=lambda e: e['specialEvent'].update(kind='SIBLING_RECURRENCE', siblings=['A', 'B'], eventOrigin='SHARED_EVENT'))
        self.assertEqual('E1', core['values']['/groups/overlap/eventId'])
        self.assertEqual('SIBLING_RECURRENCE', core['values']['/conditions/specialFamilyModel'])
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])

    def test_maternal_event_preserves_actual_person_trio(self):
        def change(e):
            e['specialEvent'].update(kind='MATERNAL_ORIGIN', personId='M', affectedStatus='UNAFFECTED')
            e['specialEvent']['trio'].update(personId='M', motherId='MM', fatherId='MF')
        core, *_ = self.build(change=change)
        self.assertEqual('M', core['specialEvent']['trio']['personId'])
        self.assertEqual('UNAFFECTED', core['specialEvent']['affectedStatus'])
        self.assertNotIn('probandSex', core['specialEvent'])
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])

    def test_explicit_proband_sex_stays_distinct_from_maternal_event_person(self):
        def change(e):
            e['specialEvent'].update(kind='MATERNAL_ORIGIN', personId='M', sex='FEMALE', probandSex='MALE', affectedStatus='UNAFFECTED')
            e['specialEvent']['trio'].update(personId='M', motherId='MM', fatherId='MF')
        core, *_ = self.build(change=change)
        self.assertEqual('FEMALE', core['specialEvent']['sex'])
        self.assertEqual('MALE', core['specialEvent']['probandSex'])
        citations = core['fieldProofs']['/extensions/specialEvent']['fact']['citations']
        self.assertTrue(any(c['locator'] == '/specialEvent/probandSex' and c['quote'] == 'MALE' for c in citations))

    def test_unknown_affected_is_not_filled(self):
        core, *_ = self.build(change=lambda e: e['specialEvent'].update(affectedStatus='UNKNOWN'))
        self.assertEqual('UNKNOWN', core['specialEvent']['affectedStatus'])
        self.assertNotIn('score', core)

    def test_resigned_fact_cannot_change_the_pinned_original(self):
        _, doc, ledger, *_ = self.build()
        f = doc['extensions']['specialEvent']; f['value']['trio']['maternity'] = 'EXCLUDED'
        ledger['checks']['/extensions/specialEvent']['factSha256'] = object_digest(f)
        ledger['contextBinding'] = context_binding(doc)
        with self.assertRaisesRegex(ValueError, 'REPLAY_VALUE_OR_PROOF_MISMATCH'):
            validate(doc, self.root / 'output', ledger, object_digest(ledger))

    def test_special_fact_and_sources_participate_in_verified_key(self):
        core, doc, ledger, *_ = self.build()
        self.assertIn('/extensions/specialEvent', core['fieldProofs'])
        old = doc['extensions'].pop('specialEvent'); ledger['checks'].pop('/extensions/specialEvent')
        ledger['contextBinding'] = context_binding(doc)
        untyped = assess(doc, self.root / 'output', ledger, object_digest(ledger))
        self.assertNotEqual(core['verifiedFactsKey'], untyped['verifiedFactsKey'])
        self.assertEqual('SOFTWARE_UNSUPPORTED', untyped['status'])

    def test_float_tissue_observation_preserved_without_inventing_parent_data(self):
        self.p['scope']['inheritance'] = 'AD'
        self.p['expertPolicyStatus'] = 'APPLICABLE_IMPLEMENTED'
        self.p['specialEvents']['mode'] = 'TISSUE_DIFFERENTIAL_PS2'
        self.envelope['scope'] = deepcopy(self.p['scope'])
        def change(e):
            special = e['specialEvent']; special.update(kind='POSTZYGOTIC', parentalAvailability='UNAVAILABLE')
            special['trio'].update(motherTarget='UNKNOWN', fatherTarget='UNKNOWN', maternity='INCONCLUSIVE', paternity='INCONCLUSIVE')
            special['tissues'] = [{'personId': 'A', 'tissueId': 'skin', 'affected': True,
                'altReads': 31, 'totalReads': 100, 'limit': 0.0125, 'method': 'TARGETED_SEQUENCING', 'quality': 'HIGH_CONFIDENCE'}]
        core, *_ = self.build(change=change)
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual(0.0125, core['specialEvent']['tissues'][0]['limit'])
        self.assertEqual('UNKNOWN', core['specialEvent']['trio']['motherTarget'])
        proof = core['fieldProofs']['/extensions/specialEvent']
        self.assertEqual(proof['check']['factSha256'], digest(proof['factCanonicalUtf8'].encode()))

    def test_xlinked_route_keeps_observed_ploidy_and_unknown_region_for_java(self):
        self.p['scope']['inheritance'] = 'X_LINKED'; self.envelope['scope'] = deepcopy(self.p['scope'])
        core, *_ = self.build(change=lambda e: e['specialEvent'].update(ploidy='UNKNOWN', genotype='UNKNOWN'))
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('UNKNOWN', core['specialEvent']['ploidy'])
        self.assertNotIn('region', core['specialEvent'])

    def test_changed_raw_pin_cannot_reuse_prepared_fact(self):
        core, *_ = self.build(tamper_pin=True)
        self.assertNotIn('specialEvent', core)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])

    def test_wrong_case_or_background_cannot_borrow_event(self):
        core, *_ = self.build(change=lambda e: e['scope'].update(diseaseId='MONDO:OTHER'))
        self.assertNotIn('specialEvent', core)
        self.assertTrue(any('BACKGROUND_MISMATCH' in str(b) for b in core['blockers']))

    def test_constitutional_typed_event_cannot_fill_unknown_family_context(self):
        raw = article().replace(b'<td>Family history</td><td>None</td>', b'<td>Family history</td><td>Unknown</td>')
        core, *_ = self.build(source_raw=raw)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])
        self.assertIsNone(core['values']['/groups/family/compatibility'])

    def test_typed_event_does_not_erase_unresolved_prior_publication(self):
        raw = article().replace(b'Case A was studied.', b'Case A was studied. This patient was previously reported elsewhere.')
        core, *_ = self.build(source_raw=raw)
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])
        self.assertIsNone(core['values']['/groups/overlap/eventId'])
        self.assertTrue(any('PRIOR_EVENT_RELATION_UNRESOLVED' in str(b) for b in core['blockers']))

    def test_schema_rejects_bool_as_reads_and_unknown_fields(self):
        for field, value in [('altReads', True), ('limit', 1.1), ('limit', True)]:
            e = event(); e['tissues'] = [{'personId': 'A', 'tissueId': 'blood', 'affected': False,
                'altReads': 3, 'totalReads': 100, 'limit': 0.01, 'method': 'TARGETED_SEQUENCING', 'quality': 'HIGH_CONFIDENCE'}]
            e['tissues'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate_shape(e, SPECIAL_EVENT)
        e = event(); e['qualified'] = True
        with self.assertRaisesRegex(ValueError, 'SCHEMA_UNKNOWN_FIELD'):
            validate_shape(e, SPECIAL_EVENT)

    def test_policy_non_ad_requires_sourced_special_mode(self):
        self.p.pop('specialEvents')
        spec = {'policy': self.f.put('policy.json', canonical(self.p)), 'sources': {'paper': self.f.put('knowledge.txt', b'fixed knowledge')}}
        with self.assertRaisesRegex(ValueError, 'POLICY_SCOPE_UNSUPPORTED'):
            read_policy(self.root, spec, 'SYNTHETIC')

    def test_optional_observation_scope_is_replayed_as_original_facts(self):
        self.envelope['specialEvent']['trio']['observationScopes'] = {
            'subject': {'specimenId': 'sample-A', 'tissue': 'brain', 'timepoint': 'T1'}}
        core, _, _, _, _ = self.build(derived=True)
        self.assertEqual(core['specialEvent']['trio']['observationScopes']['subject'],
                         self.envelope['specialEvent']['trio']['observationScopes']['subject'])
        proof = core['fieldProofs']['/extensions/specialEvent']
        self.assertTrue(any('/trio/observationScopes/subject/timepoint' in c['locator'] for c in proof['fact']['citations']))

    def test_observation_scope_does_not_accept_a_qualification_claim(self):
        raw = event(); raw['trio']['observationScopes'] = {
            'subject': {'specimenId': 'sample-A', 'tissue': 'brain', 'timepoint': 'T1', 'qualified': True}}
        with self.assertRaisesRegex(ValueError, 'SCHEMA_UNKNOWN_FIELD'):
            validate_shape(raw, SPECIAL_EVENT)

    def test_policy_special_modes_require_implemented_expert(self):
        self.p['specialEvents']['mode'] = 'SVI_1_1_SECOND_ALLELE_REQUIRED'
        spec = {'policy': self.f.put('policy.json', canonical(self.p)), 'sources': {'paper': self.f.put('knowledge.txt', b'fixed knowledge')}}
        with self.assertRaisesRegex(ValueError, 'POLICY_SPECIAL_EXPERT_REQUIRED'):
            read_policy(self.root, spec, 'SYNTHETIC')


if __name__ == '__main__':
    unittest.main()
