"""Synthetic JATS -> audited raw artifacts -> normal formal snapshot replay.

Both policy adoption and allele resolver are explicitly SYNTHETIC. The existing
model transport contract receives deterministic local JSON responses; no model,
network, patient record, or adopted scientific policy is used. Qualification and
evaluation are the real implementations, never patched or supplied typed facts.
"""
from copy import deepcopy
from html import escape
import unittest

from acquire_public_literature import source_bundle
from bundle_model_contract import load
from evaluate_pp1_snapshot import calculate
from model_literature_contract import canonical, digest, parse
from paper_library_registration import register
from pp1_literature_contract import AUDIT_VERSION, VERSION, claim_id
from prepare_literature import prepare
from run_pp1_literature_model import collect, run as extract
from test_bundle_model import configuration, response
from test_literature_acquisition import jats
import test_pp1_formal_context as formal_context
import test_pp1_reading as reading_fixture
from test_pp1_segregation import ledger, selection


class FormalSnapshot(unittest.TestCase):
    def setUp(self):
        self.policy = formal_context.FormalContext()
        self.policy.setUp()
        self.addCleanup(self.policy.doCleanups)
        self.root = self.policy.root
        self.binding = {'schemaVersion': 'pp1-analysis-binding-v1',
                        'selection': selection(), 'ledger': ledger()}
        self.transports = 0

    def build(self, *, relatives=None, ascertainment=True, founder_genotype=True,
              ascertainment_text=None, extra_paragraphs=(), clinical_texts=None, clinical_claims=()):
        """Create source and proposals together, then execute both audit stages."""
        if relatives is None:
            relatives = [('II-2', 1, True), ('II-3', 0, False)]
        members = ['I-1', 'I-2', 'II-1'] + [row[0] for row in relatives]
        claims = []
        clinical_texts = clinical_texts or {}

        def add(field, member, quote, *, related=None, kind='NONE'):
            claims.append({'fieldId': field, 'familyLabel': 'F1',
                           'memberLabel': member, 'sourceValue': quote,
                           'relatedMembers': related or [], 'relationKind': kind})

        add('F02.5', 'FAMILY', 'Only one candidate variant was identified in family F1.')
        add('F06.5', 'FAMILY',
            'Candidate assessment in family F1 used SYNTHETIC complete candidate assessment.')
        if ascertainment:
            add('F04.1', 'FAMILY',
                ascertainment_text or 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.')
        add('F04.2', 'II-1', 'The proband II-1 was ascertained.')

        def genotype(member, dosage):
            quote = (f'No c.1A>G was detected in {member}.' if dosage == 0 else
                     f'{member} is {"heterozygous" if dosage == 1 else "homozygous"} for c.1A>G.')
            add('F06.4', member, quote)

        genotype('I-1', 1)
        if founder_genotype:
            genotype('I-2', 0)
        genotype('II-1', 1)
        add('F05.2', 'II-1', clinical_texts.get('II-1', 'II-1 is diagnosed with TEST disease.'))
        for member, dosage, affected in relatives:
            genotype(member, dosage)
            add('F05.2', member, clinical_texts.get(member, f'{member} is diagnosed with TEST disease.' if affected
                else f'{member} is clinically unaffected.'))
        for field, member, quote in clinical_claims:
            add(field, member, quote)
        for child in ['II-1'] + [row[0] for row in relatives]:
            for parent, role in [('I-1', 'father'), ('I-2', 'mother')]:
                add('F07.1', parent, f'{parent} is the {role} of {child}.',
                    related=[child], kind='PARENT_OF')

        text = 'SYNTHETIC fixture. Family F1: GENE1 NM_000001.1:c.1A>G. '
        text += ' '.join(f'Member {member}.' for member in members)
        paragraphs = [text] + [row['sourceValue'] for row in claims] + list(extra_paragraphs)
        raw = jats(False).replace(b'<p>A public source.</p>',
                                  ''.join('<p>' + escape(text) + '</p>'
                                          for text in paragraphs).encode())
        self.library = self.root / 'library'
        self.library.mkdir()
        files, bundle = source_bundle({'pmcid': 'PMC123'}, raw,
                                     prepare(raw, 'PMC123', digest(raw)), None, False)
        self.pin = register(self.library, 'PMC123', files, bundle,
                            {'sha256': digest(raw),
                             'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},
                            criteria=('PP1',))['bundleSha256']
        self.source = load(self.library, 'PMC123', self.pin, criterion='PP1')
        seed = next(unit for unit in self.source['units'] if 'SYNTHETIC fixture.' in unit['text'])
        for row in claims:
            unit = next(unit for unit in self.source['units']
                        if unit['text'] == row['sourceValue'])
            row['citations'] = [{'unitId': unit['unitId'], 'quote': row['sourceValue']}]
        proposal = {
            'schemaVersion': VERSION,
            'families': [{'familyLabel': 'F1',
                          'anchor': {'unitId': seed['unitId'], 'quote': 'Family F1:'},
                          'members': [{'memberLabel': member,
                                       'anchor': {'unitId': seed['unitId'], 'quote': f'Member {member}.'}}
                                      for member in members]}],
            'facts': claims, 'coverage': 'REVIEWED_SUPPLIED_SCOPE',
            'groupChecks': [{'group': f'F{number:02}', 'outcome': 'UNRESOLVED'}
                            for number in range(1, 11)]}

        def transport(config, key, body):
            self.transports += 1
            request = parse(parse(body)['messages'][1]['content'])
            if 'original' not in request:
                return response(deepcopy(proposal))
            original = request['original']
            return response({'schemaVersion': AUDIT_VERSION,
                             'audits': [{'claimId': claim_id(row), 'verdict': 'SUPPORTED',
                                         'reason': 'Exact SYNTHETIC source fixture.',
                                         'evidence': row['citations']} for row in original['facts']],
                             'additions': [], 'additionalFamilies': [],
                             'coverage': original['coverage'], 'groupChecks': original['groupChecks']})

        config = configuration()
        config_path = self.root / 'config.json'
        config_path.write_bytes(canonical(config))
        (self.root / 'key.txt').write_bytes(b'SYNTHETIC_TEST_KEY_NOT_A_CREDENTIAL')
        self.target = {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']}
        args = (self.library, 'PMC123', self.pin, self.target,
                [unit['unitId'] for unit in self.source['units']],
                config, config_path, self.root / 'budget')
        extract(*args, self.root / 'extraction', execute=True, transport=transport)
        reviewed = extract(*args, self.root / 'review',
                           previous=self.root / 'extraction/artifact.json',
                           execute=True, transport=transport)
        self.assertEqual('SOURCE_FACTS_REVIEWED', reviewed['status'])
        self.artifact = self.root / 'review/artifact.json'
        inventory = collect(self.library, [self.artifact])
        self.assertEqual(len(claims), len(inventory['facts']))
        self.assertTrue(all(row['sourceAudit']['verdict'] == 'SUPPORTED'
                            for row in inventory['facts']))
        self.doc = {'taskId': 'a' * 64,
                    'request': {'pmcid': 'PMC123', 'bundleSha256': self.pin,
                                'analysisBinding': deepcopy(self.binding)},
                    'result': {'target': self.target, 'status': 'SYNTHETIC_SOURCE_AUDITED'},
                    'artifacts': [{'path': str(self.artifact.relative_to(self.root)),
                                   'sha256': digest(self.artifact.read_bytes())}]}

    def evaluate(self, *, documents=None, binding=None, context=None):
        return calculate(self.root, self.library, self.root, documents or [self.doc],
                         binding or self.binding, context or self.policy.selected(),
                         reading_fixture.Resolver())

    def test_formal_positive_replays_source_and_audit_before_calculation(self):
        self.build()
        result = self.evaluate()
        evaluated = result['evaluation']
        self.assertEqual(('MET', 'SUPPORTING', 1),
                         (evaluated['status'], evaluated['strength'], evaluated['awardedPP1Points']))
        # With f0=1/4, f1=3/4, each additional concordant sibling gives 3/2;
        # the typed founders and selected proband are conditioned on: (3/2)^2.
        self.assertEqual({'numerator': '9', 'denominator': '4'}, evaluated['bayesFactor'])
        self.assertEqual('pp1-segregation-evaluation-v2', evaluated['schemaVersion'])
        self.assertEqual([], evaluated['events'])
        self.assertEqual([], evaluated['needs'])
        self.assertEqual((0, 0, 1),
                         (result['modelCalls'], result['networkRequests'], result['evaluatorCalls']))
        self.assertEqual(2, self.transports)  # Deterministic local fake responses only.
        qualified = result['qualifications'][0]
        self.assertEqual('SYNTHETIC_TEST_IDENTITY_NOT_REAL_REFERENCE', qualified['identity']['method'])
        self.assertTrue(qualified['observations'])
        self.assertFalse(qualified['unresolved'])
        self.assertTrue(any(row['kind'] == 'formalAscertainment' for row in qualified['observations']))
        assessment = evaluated['familyAssessments'][0]
        self.assertTrue(assessment['factIds'] and assessment['sourceRefs'])
        self.assertTrue(all(ref['pmcid'] == 'PMC123' and ref['bundleSha256'] == self.pin
                            for ref in assessment['sourceRefs']))
        self.assertEqual('SYNTHETIC_TEST', evaluated['context']['dataClass'])
        self.assertFalse(result['clinicalApproval'])

    def test_negative_source_observation_remains_signed_not_auto_bs4(self):
        self.build(relatives=[('II-2', 0, True)])
        result = self.evaluate()['evaluation']
        self.assertEqual(('NOT_MET', 'OPPOSES', 0),
                         (result['status'], result['direction'], result['awardedPP1Points']))
        self.assertEqual({'numerator': '1', 'denominator': '2'}, result['bayesFactor'])
        self.assertEqual([], result['needs'])
        self.assertFalse(result['calibration']['negativeEvidenceAutomaticallyAssignedToBS4'])

    def test_changed_original_source_cannot_reuse_old_qualification(self):
        self.build()
        source_path = self.library / 'papers/PMC123/PMC123.xml'
        self.assertTrue(source_path.is_file())
        source_path.write_bytes(source_path.read_bytes().replace(b'clinically unaffected', b'clinically affected'))
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'):
            self.evaluate()

    def test_repeated_document_artifact_and_saved_score_do_not_add_families(self):
        self.build()
        self.doc['result']['evaluation'] = {'status': 'MET', 'awardedPP1Points': 99,
                                          'bayesFactor': {'numerator': '999', 'denominator': '1'}}
        self.doc['artifacts'] *= 2
        repeated = deepcopy(self.doc)
        repeated['taskId'] = 'b' * 64
        result = self.evaluate(documents=[self.doc, repeated])
        evaluated = result['evaluation']
        self.assertEqual({'numerator': '9', 'denominator': '4'}, evaluated['bayesFactor'])
        self.assertEqual(1, evaluated['awardedPP1Points'])
        self.assertEqual(1, len(evaluated['familyAssessments']))
        self.assertEqual(1, len(result['qualifications']))
        self.assertEqual(2, self.transports)

    def test_current_pp4_capacity_and_fact_ledger_recompute_from_original(self):
        self.build()
        first = self.evaluate()['evaluation']
        for pp4, awarded in [(4, 1), (5, 0)]:
            current = deepcopy(self.binding)
            current['ledger']['pp4Points'] = pp4
            with self.subTest(pp4=pp4):
                result = self.evaluate(binding=current)['evaluation']
                self.assertEqual(awarded, result['awardedPP1Points'])
                self.assertEqual({'numerator': '9', 'denominator': '4'}, result['bayesFactor'])
                self.assertEqual(str(5 - pp4), result['remainingLocusCapacity'])
        for field, key in [('eventIds', 'familyContributionId'), ('exclusiveFactIds', 'factIds')]:
            current = deepcopy(self.binding)
            assessment = first['familyAssessments'][0]
            current['ledger'][field] = ([assessment[key]] if key == 'familyContributionId'
                                        else [assessment[key][0]])
            with self.subTest(field=field):
                result = self.evaluate(binding=current)['evaluation']
                self.assertEqual(('NOT_MET', 0), (result['status'], result['awardedPP1Points']))
                self.assertTrue(result['familyAssessments'][0]['alreadyAllocated'])

    def test_missing_policy_parameter_preserves_source_observations(self):
        self.build()
        del self.policy.row['formalModel']['phenotypeModel']['probabilities']['2']
        selected = self.policy.selected()
        self.assertNotEqual('QUALIFIED', selected['status'])
        result = self.evaluate(context=selected)
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual(0, result['evaluation']['awardedPP1Points'])
        self.assertTrue(result['qualifications'][0]['observations'])
        need = next(row for row in result['evaluation']['needs']
                    if row['reasonCode'] == 'PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED')
        self.assertTrue(need['expected'] and need['sourceRefs'])

    def test_no_matching_documents_keeps_formal_method_and_parameter_needs(self):
        self.build()
        del self.policy.row['formalModel']['phenotypeModel']['probabilities']['2']
        selected = self.policy.selected()
        result = calculate(self.root, self.library, self.root, [], self.binding, selected, reading_fixture.Resolver())
        self.assertEqual('pp1-segregation-evaluation-v2', result['evaluation']['schemaVersion'])
        self.assertEqual(0, result['evaluatorCalls'])
        reasons = [n['reasonCode'] for n in result['evaluation']['needs']]
        self.assertIn('PP1_NO_REPLAYABLE_SELECTED_SOURCE', reasons)
        self.assertIn('PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED', reasons)

    def test_missing_source_ascertainment_never_uses_proband_role_as_substitute(self):
        self.build(ascertainment=False)
        result = self.evaluate()
        self.assertTrue(any(row['kind'] == 'proband'
                            for row in result['qualifications'][0]['observations']))
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertTrue(any(row['reasonCode'] == 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED'
                            for row in result['evaluation']['needs']))

    def test_missing_founder_genotype_is_a_source_gap_not_zero_dosage(self):
        self.build(founder_genotype=False)
        result = self.evaluate()
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        need = next(row for row in result['evaluation']['needs']
                    if row['reasonCode'] == 'PP1_FORMAL_FOUNDER_GENOTYPE_REQUIRED')
        self.assertEqual(1, len(need['actual']['untypedFounderKeys']))
        self.assertTrue(need['knownFacts'] and need['sourceLocators'])


if __name__ == '__main__':
    unittest.main()
