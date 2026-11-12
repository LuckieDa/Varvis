from copy import deepcopy
import unittest
from pm3_partner_dependencies import audit
from pm3_partner_qualification import qualify
import test_pm3_partner_qualification as fixtures


class Dependencies(unittest.TestCase):
    def statement(self):
        return {'sourceId':'VCV123.1','xpath':'/Classification[1]/Comment[1]',
            'status':'EXPLICIT_SOURCE_LIST',
            'criteria':[{'code':'PM3','original':'PM3_Strong'}, {'code':'PP4','original':'PP4'},
                        {'code':'PS3','original':'PS3'}],
            'originalText':'PM3_Strong: Detected with R10W (P), and IVS2-1 (LP). (PMID:123); '
                'PP4: The phenotype was specific. PS3: Activity was reduced. '
                'In summary this variant meets criteria to be classified as pathogenic.'}

    def test_complete_list_reports_every_dependency_and_source(self):
        statement=self.statement();before=deepcopy(statement)
        rows=audit(statement)
        self.assertEqual(['PM3','PP4','PS3'],[r['code'] for r in rows])
        self.assertEqual(['R10W','IVS2-1'],[r['reportedName'] for r in rows[0]['comparisonNames']])
        self.assertEqual(['123'],rows[0]['sourcePmids'])
        self.assertEqual('EVENT_OR_OTHER_DEPENDENCY_UNRESOLVED',rows[1]['status'])
        self.assertEqual('INTRINSIC_CRITERION_DECLARED',rows[2]['status'])
        self.assertEqual(statement,before)
        for row in rows:
            self.assertIn(row['sourceText'],statement['originalText'])
            self.assertEqual(statement['xpath'],row['sourceLocator'])

    def test_target_alias_is_only_review_flag_and_absence_is_not_proof(self):
        result=audit(self.statement(),{'proteinChange':'Arg10Trp'})[0]
        self.assertTrue(result['comparisonNames'][0]['targetAliasMatch'])
        self.assertEqual('COMPARISON_DEPENDENCY_UNRESOLVED',result['status'])
        result=audit(self.statement(),{'proteinChange':'A20V'})[0]
        self.assertFalse(any(c.get('targetAliasMatch') for c in result['comparisonNames']))
        self.assertEqual('COMPARISON_DEPENDENCY_UNRESOLVED',result['status'])

    def test_unparsed_text_and_duplicate_sections_are_not_complete(self):
        for replacement in ('Detected with R10W (P), among others.', 'Not detected with R10W (P).'):
            statement=self.statement();statement['originalText']=statement['originalText'].replace(
                'Detected with R10W (P), and IVS2-1 (LP). (PMID:123)',replacement)
            result=audit(statement)[0]
            self.assertEqual([],result['comparisonNames'])
        statement=self.statement();statement['originalText']='PM3_Strong: other. '+statement['originalText']
        self.assertIn('PM3_PARTNER_CRITERION_NARRATIVE_MISSING_OR_AMBIGUOUS',audit(statement)[0]['issues'])

    def test_strength_mismatch_and_absent_narrative_stay_unresolved(self):
        for text in ('',self.statement()['originalText'].replace('PM3_Strong:','PM3:')):
            statement=self.statement();statement['originalText']=text
            result=audit(statement)[0]
            self.assertNotIn('sourceText',result)
            self.assertIn('PM3_PARTNER_CRITERION_NARRATIVE_MISSING_OR_AMBIGUOUS',result['issues'])

    def test_qualification_does_not_hide_event_dependency_or_adopt_names(self):
        source=fixtures.Qualification().source();source['assertions'][0]['criteriaStatements']=[self.statement()]
        result=qualify(source,fixtures.Qualification.context)
        self.assertEqual('UNKNOWN',result['classification'])
        self.assertIn('PM3_PARTNER_COMPARISON_DEPENDENCY_UNRESOLVED',result['issues'])
        self.assertIn('PM3_PARTNER_EVENT_OR_OTHER_CRITERIA_DEPENDENCY_NOT_IMPLEMENTED',result['issues'])
        self.assertEqual(3,len(result['assertionAssessments'][0]['criterionDependencies']))
        # A separate, concordant assertion with a complete intrinsic basis remains usable.
        other=fixtures.Qualification().source()['assertions'][0];other['scv']='SCV124.1'
        source['assertions'].append(other)
        result=qualify(source,fixtures.Qualification.context)
        self.assertEqual(['SCV124.1'],result['qualifiedScvs'])
        self.assertEqual('PATHOGENIC',result['classification'])


if __name__=='__main__':unittest.main()
