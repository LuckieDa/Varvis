"""Source-audited prose results, without a disease policy or inferred zygosity."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from acquire_public_literature import source_bundle
from bundle_model_contract import load
from model_literature_contract import digest
from paper_library_registration import register
from pp1_literature_contract import VERSION, AUDIT_VERSION, audit_check, claim_id
from pp1_source_qualification import qualify
from prepare_literature import prepare
from test_literature_acquisition import jats
from test_pp1_reading import Resolver
from test_pp1_segregation import selection


class NativeResults(unittest.TestCase):
    def test_short_deletion_requires_paired_native_cell_and_unambiguous_assembly(self):
        from unittest.mock import Mock
        from pp1_source_qualification import deletion_identity
        prefix = '/article[1]/table-wrap[1]'
        cell = prefix + '/table[1]/tbody[1]/tr[1]/td[1]'
        caption = prefix + '/caption[1]'
        source_sha = 'f' * 64
        source = {'main': {'sourceSha256': source_sha}, 'units': [
            {'unitId': caption, 'text': 'Genomic coordinates are reported on GRCh38.', 'sourceSha256': source_sha},
            {'unitId': cell, 'text': 'g.101, c.5_6del', 'sourceSha256': source_sha}]}
        resolver = Mock()
        resolver.resolve_coding_deletion.return_value = {'status': 'LOCAL_IDENTITY_VERIFIED'}
        selected = {**selection(), 'allele': {**selection()['allele'], 'assembly': 'GRCh38'}}
        result = deletion_identity(source, {cell}, selected, 'c.5_6del', None, resolver)
        resolver.resolve_coding_deletion.assert_called_once_with('GENE1', 'c.5_6del', None, 101)
        self.assertEqual([cell, caption], [ref['unitId'] for ref in result['sourceRefs']])
        for bad_caption in ('Genomic coordinates GRCh37.', 'Genomic coordinates GRCh37 and GRCh38.'):
            source['units'][0]['text'] = bad_caption
            with self.assertRaisesRegex(ValueError, 'PAIR_MISSING_OR_AMBIGUOUS'):
                deletion_identity(source, {cell}, selected, 'c.5_6del', None, resolver)

    def test_snv_without_source_transcript_requires_matching_native_grch38_pair(self):
        from unittest.mock import Mock
        from core_local_identity import LocalIdentity
        selected = selection()
        position = selected['allele']['position']
        prefix = '/article[1]/table-wrap[1]'
        cell = prefix + '/table[1]/tbody[1]/tr[1]/td[1]'
        caption = prefix + '/caption[1]'
        pin = 'f' * 64
        source = {'pmcid': 'PMC123', 'bundleSha256': pin, 'main': {'sourceSha256': pin}, 'units': [
            {'unitId': caption, 'text': 'Genomic coordinates on GRCh38.', 'sourceSha256': pin},
            {'unitId': cell, 'text': f'g.{position}, c.1A>G', 'sourceSha256': pin}]}
        inventory = {'sourceIdentity': {'pmcid': 'PMC123', 'bundleSha256': pin}, 'target': {'geneSymbol': 'GENE1',
                     'variantAliases': ['c.1A>G']}, 'inputScope': {'unitIds': [cell, caption]},
                     'families': {}, 'facts': []}
        resolver = LocalIdentity(Path.cwd(), {})
        resolver.load = Mock()
        resolver.mane = [{'symbol': 'GENE1', 'RefSeq_nuc': 'NM_000001.1'}]
        resolver.resolve_coding = Mock(side_effect=lambda *args: {'allele': selected['allele']})
        bound = qualify(source, inventory, selected, resolver)
        self.assertIsNone(bound['identityIssue'])
        self.assertEqual([cell, caption], [r['unitId'] for r in bound['identity']['sourceRefs']])
        resolver.resolve_coding.assert_called_once_with('GENE1', 'c.1A>G', 'NM_000001.1')
        for row, reason in (
            (f'g.{position + 1}, c.1A>G', 'CODING_SNV_GENOMIC_POSITION_MISMATCH'),
            (f'g.{position}, c.1A>G; c.2A>T', 'SOURCE_SNV_GRCH38_PAIR_MISSING_OR_AMBIGUOUS'),
            ('c.1A>G', 'SOURCE_SNV_GRCH38_PAIR_MISSING_OR_AMBIGUOUS'),
        ):
            source['units'][1]['text'] = row
            with self.subTest(row=row):
                result = qualify(source, inventory, selected, resolver)
                self.assertIsNone(result['identity'])
                self.assertTrue(result['identityIssue'].endswith(reason), result['identityIssue'])

    def qualify(self, prose, member, quote=None, audit_verdict='SUPPORTED', field='F06.3', display_family=False,
                coding='c.1A>G', additional_facts=(), selected=None):
        # Synthetic public-source fixture. Labels/allele do not name real people.
        text = 'Family F1: GENE1 NM_000001.1:' + coding + '. ' + prose
        raw = jats(False).replace(b'<p>A public source.</p>',
                                 ('<p>' + text.replace('>', '&gt;') + '</p>').encode())
        with tempfile.TemporaryDirectory() as tmp:
            library = Path(tmp)
            files, bundle = source_bundle({'pmcid': 'PMC123'}, raw,
                                          prepare(raw, 'PMC123', digest(raw)), None, False)
            pin = register(library, 'PMC123', files, bundle,
                           {'sha256': digest(raw), 'acquisitionUrl':
                            'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},
                           criteria=('PP1',))['bundleSha256']
            source = load(library, 'PMC123', pin, criterion='PP1')
            unit = next(u for u in source['units'] if 'Family F1:' in u['text'])
            labels = [label for label in ('I:1', 'II:2', 'III:3') if label in text]
            fact = {'fieldId': field, 'familyLabel': 'F1', 'memberLabel': member,
                    'sourceValue': quote or prose, 'relatedMembers': [], 'relationKind': 'NONE',
                    'citations': [{'unitId': unit['unitId'], 'quote': quote or prose}]}
            if field == 'F07.1':
                fact.update(relatedMembers=[m for m in labels if m != member], relationKind='UNCLEAR')
            facts = [fact] + [
                {'fieldId': key, 'familyLabel': 'F1', 'memberLabel': label, 'sourceValue': literal,
                 'relatedMembers': related, 'relationKind': 'PARENT_OF' if related else 'NONE',
                 'citations': [{'unitId': unit['unitId'], 'quote': literal}]}
                for key, label, literal, related in additional_facts
            ]
            candidate = {'schemaVersion': VERSION, 'families': [{'familyLabel': 'F1',
                         'anchor': {'unitId': unit['unitId'], 'quote': 'Family F1:'},
                         'members': [{'memberLabel': m, 'anchor':
                                      {'unitId': unit['unitId'], 'quote': m}} for m in labels]}],
                         'facts': facts, 'coverage': 'REVIEWED_SUPPLIED_SCOPE',
                         'groupChecks': [{'group': f'F{n:02}', 'outcome': 'UNRESOLVED'} for n in range(1, 11)]}
            if display_family:
                candidate['families'][0]['familyLabel']='model display label'
                candidate['families'][0]['anchor']={'unitId':unit['unitId'],'quote':prose}
                fact['familyLabel']='model display label'
            audit = {'schemaVersion': AUDIT_VERSION, 'audits': [{'claimId': claim_id(row),
                     'verdict': audit_verdict, 'reason': 'Synthetic source-support proposal.',
                     'evidence': deepcopy(row['citations'])} for row in facts], 'additions': [], 'additionalFamilies': [],
                     'coverage': candidate['coverage'], 'groupChecks': candidate['groupChecks']}
            inventory = audit_check(source, candidate, audit, [unit['unitId']],
                                    {'geneSymbol': 'GENE1', 'variantAliases': [coding]})
            return qualify(source, inventory, selected or selection(), Resolver())

    def test_allele_origin_binds_named_members_and_full_native_statement(self):
        statement = 'III:3 inherited the variant NM_000001.1:c.1A>G from II:2'
        q = self.qualify(statement + '.', 'III:3')
        members = next(iter(q['families'].values()))['members']
        origin, = [o for o in q['observations'] if o['kind'] == 'alleleOriginParent']
        self.assertEqual((members['III:3'], members['II:2']), (origin['memberKey'], origin['value']))
        self.assertTrue(any(r['quote'] == statement + '.' for r in origin['sourceRefs']))
        self.assertFalse([o for o in q['observations'] if o['kind'] == 'genotype'])
        for text, quote in [
            ('Whether ' + statement + ' remains uncertain.', statement),
            (statement + ' according to an unverified hypothesis.', statement),
            (statement + '?', statement),
            (statement.replace('NM_000001.1', 'NM_000002.1') + '.', None),
            (statement.replace('c.1A>G', 'c.1A>T') + '.', None),
        ]:
            with self.subTest(text=text):
                out = self.qualify(text, 'III:3', quote=quote)
                self.assertFalse([o for o in out['observations'] if o['kind'] == 'alleleOriginParent'])

    def test_published_obligate_count_from_qualified_original_text(self):
        from test_pp1_specification_context import chosen, result
        origin = 'II:2 inherited c.1A>G from I:1.'
        facts = [
            ('F04.2', 'II:2', 'The proband II:2 was ascertained.', []),
            ('F05.2', 'I:1', 'I:1 was diagnosed with TEST disease.', []),
            ('F05.2', 'II:2', 'II:2 was diagnosed with TEST disease.', []),
            ('F05.2', 'III:3', 'III:3 was diagnosed with TEST disease.', []),
            ('F06.4', 'II:2', 'II:2 was heterozygous for c.1A>G.', []),
            ('F06.4', 'III:3', 'III:3 was heterozygous for c.1A>G.', []),
            ('F07.1', 'I:1', 'I:1 is the father of II:2.', ['II:2']),
            ('F07.1', 'II:2', 'II:2 is the father of III:3.', ['III:3']),
        ]
        prose = ' '.join([origin, *(row[2] for row in facts)])
        qualified = self.qualify(prose, 'II:2', quote=origin, additional_facts=facts, selected=chosen())
        scored = result(qualified)
        self.assertEqual(('MET', 'SUPPORTING', 1), (scored['status'], scored['strength'], scored['awardedPP1Points']))
        event, = scored['events']
        self.assertEqual(['I:1'], event['obligateCarrierLabels'])
        self.assertTrue(any(ref['quote'] == origin for ref in event['sourceRefs']))
        self.assertTrue(all(ref['bundleSha256'] == qualified['sourceIdentity']['bundleSha256'] for ref in event['sourceRefs']))
        self.assertFalse(any(row['memberLabel'] == 'I:1' and row['kind'] == 'genotype' for row in qualified['observations']))

    def test_informative_meioses_from_qualified_original_text(self):
        from test_pp1_meiosis import evaluate
        from test_pp1_specification_context import chosen
        origin = 'II:2 inherited c.1A>G from I:1.'
        second_origin = 'III:3 inherited c.1A>G from II:2.'
        facts = [
            ('F04.2', 'I:1', 'The proband I:1 was ascertained.', []),
            ('F06.3', 'III:3', second_origin, []),
            ('F07.1', 'I:1', 'I:1 is the father of II:2.', ['II:2']),
            ('F07.1', 'II:2', 'II:2 is the father of III:3.', ['III:3']),
        ]
        for member in ('I:1', 'II:2', 'III:3'):
            facts.extend([('F05.2', member, f'{member} was diagnosed with TEST disease.', []),
                          ('F06.4', member, f'{member} was heterozygous for c.1A>G.', [])])
        prose = ' '.join([origin, *(row[2] for row in facts)])
        q = self.qualify(prose, 'II:2', quote=origin, additional_facts=facts, selected=chosen())
        scored = evaluate(q)
        self.assertEqual(('MET', 'SUPPORTING', 1), (scored['status'], scored['strength'], scored['awardedPP1Points']))
        self.assertEqual({('I:1','II:2'),('II:2','III:3')},
                         {(event['parentLabel'], event['memberLabel']) for event in scored['events']})
        for event in scored['events']:
            self.assertTrue(all(ref['bundleSha256'] == q['sourceIdentity']['bundleSha256'] for ref in event['sourceRefs']))
        incomplete = self.qualify(prose.replace(second_origin, ''), 'II:2', quote=origin,
                                  additional_facts=[row for row in facts if row[2] != second_origin], selected=chosen())
        checked = evaluate(incomplete)
        self.assertEqual(('NOT_EVALUATED', 1, 0), (checked['status'], checked['meiosisAssessment']['informativeMeioses'],
                                                  checked['awardedPP1Points']))
        self.assertIn('PP1_MEIOSIS_ORIGIN_UNRESOLVED', [need['reasonCode'] for need in checked['needs']])

    def test_coding_snv_display_spaces_resolve_without_changing_source(self):
        for coding in ('c.1A > G', 'c.1A> G', 'c.1A >G'):
            with self.subTest(coding=coding):
                q = self.qualify('I:1 was examined.', 'I:1', coding=coding)
                self.assertIsNotNone(q['identity'])
                self.assertIsNone(q['identityIssue'])
                self.assertTrue(any(coding in r['quote'] for e in q['identityEvidence']
                                    for r in e['sourceRefs']))
                self.assertFalse(q['observations'])
        for coding in ('c.1A > T', 'c.1 A>G', 'c.1A >> G'):
            with self.subTest(coding=coding):
                self.assertIsNone(self.qualify('I:1 was examined.', 'I:1', coding=coding)['identity'])

    def carriers(self, result):
        return [(r['memberLabel'], r['value']) for r in result['observations'] if r['kind'] == 'genotype']

    def test_display_family_requires_complete_explicit_native_kinship_component(self):
        prose='The patient (I:1) carried c.1A>G. His son (II:2) and grandson (III:3) had the same variant.'
        q=self.qualify(prose,'I:1',display_family=True)
        self.assertEqual([('I:1','CARRIER')],self.carriers(q))
        self.assertTrue(any(r.get('meaning')=='EXPLICIT_KINSHIP_FAMILY_MEMBERSHIP_ONLY'
                            for r in q['observations'][0]['sourceRefs']))
        self.assertFalse(any(o['kind'] in ('proband','clinical','parentOf','childOf') for o in q['observations']))
        for invalid in (
            'The patient (I:1) carried c.1A>G. II:2 and III:3 had the same variant.',
            'The patient (I:1) carried c.1A>G. His son (II:2) had the same variant. Unrelated III:3 was examined.',
            'The patient (I:1) carried c.1A>G. His possible son (II:2) and grandson (III:3) had the same variant.'):
            with self.subTest(invalid=invalid):
                self.assertFalse(self.carriers(self.qualify(invalid,'I:1',display_family=True)))

    def test_explicit_detection_does_not_require_one_special_verb(self):
        for verb in ('was revealed to have a variant of', 'was found to carry the variant',
                     'was shown to carry', 'carried', 'had'):
            with self.subTest(verb=verb):
                q = self.qualify(f'The patient (I:1) {verb} c.1A>G.', 'I:1')
                self.assertEqual([('I:1', 'CARRIER')], self.carriers(q))
                self.assertTrue(all(ref['pmcid'] == 'PMC123' for ref in q['observations'][0]['sourceRefs']))

    def test_joint_named_subjects_have_same_result_without_inventing_relationships(self):
        prose = 'His son (II:2) and grandson (III:3) had the same variant.'
        for member in ('II:2', 'III:3'):
            with self.subTest(member=member):
                q = self.qualify(prose, member)
                self.assertEqual([(member, 'CARRIER')], self.carriers(q))
                self.assertFalse(any(o['kind'] in {'parentOf', 'childOf'} for o in q['observations']))
                self.assertTrue(any(ref.get('meaning') == 'SINGLE_VARIANT_IN_SAME_UNIT_NOT_PROTEIN_ONLY'
                                    for ref in q['observations'][0]['sourceRefs']))

    def test_plain_subject_list_and_parenthesized_proband(self):
        for prose, member in [('I:1 and II:2 carried c.1A>G.', 'I:1'),
                              ('I:1, II:2, and III:3 have the same variant.', 'II:2'),
                              ('The proband (I:1) has the same variant.', 'I:1')]:
            with self.subTest(prose=prose):
                self.assertEqual([(member, 'CARRIER')], self.carriers(self.qualify(prose, member)))

    def test_other_member_and_performed_test_are_not_positive_results(self):
        for prose, member in [('I:1 was examined. His son (II:2) had the same variant.', 'I:1'),
                              ('I:1 underwent sequencing for c.1A>G.', 'I:1'),
                              ('I:1 and II:2 were tested for the same variant.', 'II:2')]:
            with self.subTest(prose=prose):
                self.assertFalse(self.carriers(self.qualify(prose, member)))

    def test_negative_and_uncertain_detection_not_promoted(self):
        for prose in ('I:1 was not found to carry c.1A>G.',
                      'I:1 may have the same variant.',
                      'It is possible that I:1 carried c.1A>G.',
                      'It is not true that I:1 had the same variant.',
                      'I:1 did not have the same variant.'):
            with self.subTest(prose=prose):
                self.assertFalse(self.carriers(self.qualify(prose, 'I:1')))

    def test_model_quote_cannot_omit_outer_negation(self):
        quote = 'I:1 was revealed to have a variant of c.1A>G.'
        self.assertFalse(self.carriers(self.qualify('It is not true that ' + quote, 'I:1', quote)))

    def test_same_variant_with_second_change_or_unresolved_variant_not_accepted(self):
        for extra in ('Another variant c.2A>G was found.', 'Another variant c.2_3del was found.',
                      'A different variant was found.', 'The second variant was investigated.'):
            with self.subTest(extra=extra):
                q = self.qualify(extra + ' His son (I:1) had the same variant.', 'I:1')
                self.assertFalse(self.carriers(q))
                self.assertTrue(q['unresolved'])

    def test_unsupported_or_wrong_subject_audit_not_bypassed(self):
        for verdict in ('AMBIGUOUS', 'WRONG_SUBJECT_OR_FIELD', 'CONTRADICTED'):
            with self.subTest(verdict=verdict):
                q = self.qualify('I:1 was revealed to have a variant of c.1A>G.', 'I:1', audit_verdict=verdict)
                self.assertFalse(self.carriers(q))

    def test_two_results_do_not_lend_positive_to_negative_subject(self):
        prose = 'I:1 carried c.1A>G, but II:2 did not have the same variant.'
        self.assertEqual([('I:1', 'CARRIER')], self.carriers(self.qualify(prose, 'I:1')))
        self.assertFalse(self.carriers(self.qualify(prose, 'II:2')))

    def test_local_kinship_preserves_grandchild_without_inventing_parent(self):
        prose = ('The patient (I:1) carried c.1A>G. '
                 'His son (II:2) and grandson (III:3) had the same variant.')
        for member, kind in [('II:2', 'childOf'), ('III:3', 'grandchildOf')]:
            q = self.qualify(prose, member, field='F07.1')
            relations = [o for o in q['observations'] if o['kind'] in {'childOf','grandchildOf'}]
            self.assertEqual([kind], [o['kind'] for o in relations])
            self.assertEqual(q['families'][relations[0]['familyKey']]['members']['I:1'], relations[0]['value'])
            self.assertTrue(any(r.get('meaning') == 'UNIQUE_PRECEDING_NAMED_SUBJECT'
                                for r in relations[0]['sourceRefs']))

    def test_sibling_is_not_automatically_full_sibling(self):
        q = self.qualify('The patient (I:1) carried c.1A>G. Her brother (II:2) was examined.',
                         'II:2', field='F07.1')
        self.assertEqual(['siblingOf'], [o['kind'] for o in q['observations']])

    def test_possessive_relation_needs_unique_adjacent_source_subject(self):
        for prose in ('I:1 and II:2 were examined. His son (III:3) was examined.',
                      'The patient (I:1) was examined. Testing was performed. His son (II:2) was examined.',
                      'The patient (I:1) was examined. His son (II:2) may be adopted.',
                      'The patient (I:1) was examined. His half brother (II:2) was examined.'):
            with self.subTest(prose=prose):
                q = self.qualify(prose, 'III:3' if 'III:3' in prose else 'II:2', field='F07.1')
                self.assertFalse(q['observations'])

    def test_relation_cannot_bypass_challenged_audit_or_wrong_subject(self):
        prose = 'The patient (I:1) carried c.1A>G. His son (II:2) was examined.'
        for member, verdict in [('I:1','SUPPORTED'),('II:2','AMBIGUOUS'),('II:2','CONTRADICTED')]:
            self.assertFalse(self.qualify(prose, member, audit_verdict=verdict, field='F07.1')['observations'])

    def test_explicit_parenthesized_proband_and_index_case(self):
        for role in ('proband', 'index patient', 'index case'):
            q = self.qualify(f'The {role} (I:1) was examined.', 'I:1', field='F04.2')
            self.assertEqual([('proband', True)], [(o['kind'],o['value']) for o in q['observations']])
        for prose in ('The patient (I:1) was examined.',
                      'It is not true that the proband (I:1) was examined.',
                      'The possible proband (I:1) was examined.'):
            self.assertFalse(self.qualify(prose, 'I:1', field='F04.2')['observations'])

    def test_named_criteria_statement_is_preserved_without_disease_inference(self):
        prose='The son (I:1) fulfilled the revised Example criteria with a reported feature.'
        q=self.qualify(prose,'I:1',field='F05.3')
        self.assertEqual([('reportedDiagnosticCriteria','revised Example criteria')],
                         [(o['kind'],o['value']) for o in q['observations']])
        for invalid,member in [('I:1 did not fulfill the revised Example criteria.','I:1'),
                               ('I:1 met no revised Example criteria.','I:1'),
                               ('It is possible that I:1 met the revised Example criteria.','I:1'),
                               ('I:1 was examined. II:2 fulfilled the revised Example criteria.','I:1')]:
            with self.subTest(invalid=invalid):
                self.assertFalse(self.qualify(invalid,member,field='F05.3')['observations'])

    def test_ascertainment_role_does_not_require_literal_proband_word(self):
        for prose in ('I:1 was the index case.', 'I:1 was the proband.',
                      'The family was ascertained through individual I:1.',
                      'The pedigree was ascertained through the patient (I:1).'):
            q=self.qualify(prose,'I:1',field='F04.2')
            self.assertEqual([('proband',True)],[(o['kind'],o['value']) for o in q['observations']])
        for prose in ('I:1 was referred for sequencing.', 'I:1 was the first tested patient.',
                      'The family was possibly ascertained through I:1.',
                      'It is not true that the family was ascertained through I:1.',
                      'I:1 was examined. The family was ascertained through II:2.'):
            self.assertFalse(self.qualify(prose,'I:1',field='F04.2')['observations'])
        self.assertFalse(self.qualify('The family was ascertained through I:1.','I:1',
                                     field='F04.2',audit_verdict='AMBIGUOUS')['observations'])


if __name__ == '__main__':
    unittest.main()
