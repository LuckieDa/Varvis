import copy
import unittest
from prepare_bp3_published_knowledge import derive, prepare
from prepare_published_criterion_knowledge import criterion_scopes
import test_prepare_published_criterion_knowledge as source_fixture


def clause():
    return {'label': 'BP3', 'gene': ['TEST'], 'disease': ['MONDO:0000001'], 'applicability': 'Applicable',
            'baseStrength': 'Benign', 'defaultStrength': 'Benign Supporting', 'additionalComments': '',
            'strengthDescriptor': [{'applicability': 'Applicable', 'status': 'approved', 'strength': 'Supporting',
                'text': 'Use for R1 repetitive regions only (amino acids 2-5). '
                'These events can be multiple and large (e.g. 9 plus 15 total 20-30 bp is known, a single 21bp event is common). '
                'Please note that R1 encompasses residues 1 through 6, with UniProt identifying particularly disordered regions between 2-3, 3-4, and 4-5.'}]}


class Bp3PreparationTest(unittest.TestCase):
    def test_full_text_supported_region(self):
        status, reason, rule = derive(clause())
        self.assertEqual('EXECUTABLE', status)
        self.assertEqual('BP3_SUPPORTED_PUBLISHED_REGION_GRAMMAR', reason)
        self.assertEqual((2, 5, 1, 6), tuple(rule[k] for k in ('start', 'end', 'modelRegionStart', 'modelRegionEnd')))

    def test_missing_conflicting_and_extra_conditions_never_executable(self):
        cases = [lambda c: c.update(additionalComments='Not applicable at this time.'),
                 lambda c: c.update(additionalComments='Requires other variants with frequency commensurate with BA1.'),
                 lambda c: c['strengthDescriptor'][0].update(status='not approved'),
                 lambda c: c['strengthDescriptor'][0].update(text=c['strengthDescriptor'][0]['text'] + ' Additional restriction.'),
                 lambda c: c['strengthDescriptor'][0].update(instructionsToUse='Consult attached table.'),
                 lambda c: c['strengthDescriptor'][0].update(text='Use with no specification.'),
                 lambda c: c.update(applicability='Not applicable'),
                 lambda c: c['strengthDescriptor'][0].update(text=c['strengthDescriptor'][0]['text'].replace('2-5', '2-7'))]
        for change in cases:
            c = clause(); change(c)
            self.assertNotEqual('EXECUTABLE', derive(c)[0], c)

    def test_source_and_output_pinned_and_batch_compiled(self):
        fixture = source_fixture.PreparationTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        fixture.entity['ld']['CriteriaCode'][0]['entContent'] = clause()
        fixture.write_source()
        config = {k: fixture.plan[k] for k in ('catalog', 'captureDirectory', 'policyId', 'policyVersion')}
        compiled, audit, plan = prepare(config)
        self.assertEqual(fixture.entity, compiled['specifications'][0]['original'])
        self.assertEqual('EXECUTABLE', compiled['records'][0]['status'])
        self.assertEqual(1, len(audit['clauses']))
        self.assertFalse(compiled['preparation']['priorBa1ApprovalInherited'])
        self.assertFalse(compiled['clinicalValidationCompleted'])
        self.assertNotIn('review', plan['records'][0])

    def test_x_linked_is_bp3_only_and_does_not_infer_subtype(self):
        fixture = source_fixture.PreparationTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        fixture.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance'] = 'X-linked inheritance'
        original = copy.deepcopy(fixture.entity)
        self.assertEqual('X_LINKED', criterion_scopes(fixture.entity, 'ACMG_AMP_2015:BP3')[0]['scope']['moi'])
        self.assertEqual('', criterion_scopes(fixture.entity, 'ACMG_AMP_2015:BP1')[0]['scope']['moi'])
        self.assertEqual(original, fixture.entity)


if __name__ == '__main__':
    unittest.main()
