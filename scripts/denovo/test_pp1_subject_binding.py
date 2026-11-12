"""Synthetic source tests: generic subject grammar, no real-case whitelist."""
import unittest
import test_pp1_native_results as native
from pp1_source_qualification import local_subject_facts


class SubjectBinding(unittest.TestCase):
    qualify = native.NativeResults.qualify

    def values(self, prose, field, member='III:3', quote=None):
        q=self.qualify(prose,member,quote=quote,field=field)
        return [(x['kind'],x['value']) for x in q['observations']]

    def test_explicit_proband_role_separated_by_family_and_description(self):
        self.assertIn(('proband',True),self.values('Proband in family F1 is a female (III:3).','F04.2'))
        self.assertFalse(self.values('Possible proband in family F1 is a female (III:3).','F04.2'))

    def test_local_pronoun_diagnosis_retains_named_antecedent(self):
        text='Proband in family F1 is a female (III:3). She was a 26-year-old woman who was diagnosed with TEST disease.'
        q=self.qualify(text,'III:3',quote=text.split('. ',1)[1],field='F05.2')
        self.assertIn('TEST disease',[x['value'] for x in q['observations'] if x['kind']=='diagnosis'])
        self.assertTrue(any('III:3' in r['quote'] for x in q['observations'] for r in x['sourceRefs']))

    def test_pronoun_cannot_jump_past_other_people_or_uncertainty(self):
        for tail in ['Her mother was examined. She was diagnosed with TEST disease.',
                     'The patient (II:2) was examined. She was diagnosed with TEST disease.',
                     'She reported that Anna was diagnosed with TEST disease.',
                     'She was not diagnosed with TEST disease.',
                     'She might have been diagnosed with TEST disease.']:
            text='Proband in family F1 is a female (III:3). '+tail
            self.assertFalse(self.values(text,'F05.2',quote=tail))

    def test_named_collective_requires_complete_explicit_members(self):
        text='All three patients (I:1, II:2 and III:3) carried c.1A>G.'
        for m in ['I:1','II:2','III:3']:
            self.assertIn(('genotype','CARRIER'),self.values(text,'F06.3',m))
        for text in ['All three patients carried c.1A>G. III:3 was examined.',
                     'All two patients (I:1, II:2 and III:3) carried c.1A>G.',
                     'Not all three patients (I:1, II:2 and III:3) carried c.1A>G.',
                     'All three patients (I:1, II:2 and III:3) might carry c.1A>G.']:
            self.assertFalse(self.values(text,'F06.3'))

    def test_relative_diagnosis_is_not_parentage_or_a_genotype(self):
        anchor='The index patient in pedigree P9 is a male (III:3).'
        quote='His mother and grandfather are all TEST disease patients'
        facts=list(local_subject_facts(anchor+' '+quote+'.',quote,['III:3','mother','grandfather']))
        self.assertEqual([('mother','diagnosis','TEST disease'),('grandfather','diagnosis','TEST disease')],
                         [x[:3] for x in facts])
        self.assertTrue(all(anchor in x[3] for x in facts))
        self.assertFalse(list(local_subject_facts(quote+'.',quote,['mother','grandfather'])))
        self.assertFalse(list(local_subject_facts(anchor+' His mother was examined. '+quote+'.',
                                                 quote,['III:3','mother','grandfather'])))

    def test_outer_group_negation_cannot_be_hidden_by_shorter_quote(self):
        quoted='All three patients (I:1, II:2 and III:3) carried c.1A>G.'
        self.assertFalse(self.values('Whether '+quoted,'F06.3',quote=quoted))

    def test_explicit_local_disease_abbreviation_not_global_synonym(self):
        anchor='Proband in family F1 is a female (III:3).'
        diagnosis='She was diagnosed with TEST disease (TD).'
        relative='Her mother and grandfather are all TD patients.'
        text=anchor+' '+diagnosis+' '+relative
        facts=list(local_subject_facts(text,text,['III:3','mother','grandfather']))
        self.assertEqual(['TEST disease']*3,[x[2] for x in facts if x[1]=='diagnosis'])
        self.assertTrue(all(diagnosis in x[3] for x in facts if x[1]=='diagnosis'))
        unbound=list(local_subject_facts(anchor+' '+relative,relative,['III:3','mother','grandfather']))
        self.assertEqual(['TD','TD'],[x[2] for x in unbound])
        self.assertEqual([],[x[2] for x in
            local_subject_facts(anchor+' She was diagnosed with TEST disease (suspected).',
                                'She was diagnosed with TEST disease (suspected).',['III:3']) if x[1]=='diagnosis'])

if __name__=='__main__':unittest.main()
