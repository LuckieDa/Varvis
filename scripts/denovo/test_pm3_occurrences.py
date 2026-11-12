import unittest
from copy import deepcopy
from unittest.mock import patch
from pm3_occurrences import attach


class Occurrences(unittest.TestCase):
    def source(self,text='Patients 1 and 2 were unrelated.'):
        return {'pmcid':'PMC123','main':{'sourceSha256':'a'*64,'units':[
            {'unitId':'methods','kind':'BODY_PARAGRAPH','sections':['Methods'],'text':text}], 'tables':[]}}

    def case(self,label='1'):
        return {'caseLabel':label,'facts':[], 'primarySourceQualification':{'qualification':'VERIFIED'}}

    def test_original_case_does_not_need_family_id_but_is_not_worldwide_independence(self):
        with patch('pm3_observations.ownership',return_value={'anchor':{}}):
            result=attach(self.source(),{'cases':[self.case()]})['cases'][0]['occurrenceQualification']
        self.assertEqual('VERIFIED',result['qualification'])
        self.assertIsNone(result['familyIdentity'])
        self.assertEqual('NOT_ESTABLISHED',result['crossPaperIndependence'])
        self.assertEqual([],result['unrelatedGroups'])

    def test_unrelated_members_are_source_explicit_not_distinct_labels(self):
        for text,expected in [('Patients 1 and 2 were unrelated.',1),
                ('Patients 1 and 2 were not unrelated.',0),('Patients 1 and 3 were unrelated.',0),
                ('Patients 1 and 1 were unrelated.',0),('Patients 1 and 2 may be unrelated.',0)]:
            with self.subTest(text=text),patch('pm3_observations.ownership',return_value={'anchor':{}}):
                result=attach(self.source(text),{'cases':[self.case('1'),self.case('2')]})
            self.assertEqual(expected,len(result['cases'][0]['occurrenceQualification']['unrelatedGroups']))
        source=self.source();source['main']['units'][0]['sections']=['Discussion']
        with patch('pm3_observations.ownership',return_value={'anchor':{}}):
            result=attach(source,{'cases':[self.case('1'),self.case('2')]})
        self.assertEqual([],result['cases'][0]['occurrenceQualification']['unrelatedGroups'])

    def test_family_identifier_uses_exact_owned_cell_and_preserves_relation_questions(self):
        case=self.case();case['facts']=[{'field':'familyIdentity','subject':'FAMILY','sourceValue':'F12',
            'citation':{'unitId':'cell','quote':'F12'},'issues':[]}]
        own={'cell':{'method':'EXPLICIT_UNIQUE_CASE_ROW','columnHeading':'Family ID','cellText':'F12'}}
        with patch('pm3_observations.ownership',return_value=own):
            result=attach(self.source(),{'cases':[deepcopy(case)]})['cases'][0]['occurrenceQualification']
        self.assertEqual('F12',result['familyIdentity'])
        case['facts'].append({'field':'previousReport','sourceValue':'reported before',
            'citation':{'unitId':'cell','quote':'reported before'}})
        with patch('pm3_observations.ownership',return_value=own):
            result=attach(self.source(),{'cases':[case]})['cases'][0]['occurrenceQualification']
        self.assertEqual('UNKNOWN',result['qualification'])
        self.assertTrue(result['unresolvedRelationClaims'])

    def test_source_case_id_is_stable_across_changed_source_bytes(self):
        source=self.source();observations={'cases':[self.case()]}
        with patch('pm3_observations.ownership',return_value={'anchor':{}}):
            first=attach(source,deepcopy(observations))
            source['main']['sourceSha256']='b'*64
            second=attach(source,deepcopy(observations))
        self.assertEqual(first['cases'][0]['occurrenceQualification']['sourceCaseId'],
                         second['cases'][0]['occurrenceQualification']['sourceCaseId'])


if __name__=='__main__':unittest.main()
