import copy,unittest
from bp1_region_policy import derive,HEADER,PREFIX,SUFFIX

class RegionGrammarTest(unittest.TestCase):
    def clause(self,gene='ANY1'):
        return {'label':'BP1','applicability':'Applicable','baseStrength':'Benign','defaultStrength':'Benign Supporting',
            'instructionsToUse':HEADER,'additionalComments':None,'gene':[gene],
            'strengthDescriptor':[{'applicability':'Applicable','strength':'Strong','instructionsToUse':None,
                'text':PREFIX+gene+' first region aa 2-101; '+gene+' other region aa 201-250'+SUFFIX}]}
    def test_common_grammar_uses_source_ranges_and_strength_for_any_symbol(self):
        for gene in ['ANY1','OTHER2']:
            r=derive(self.clause(gene));self.assertEqual(r['appliedStrength'],'STRONG')
            self.assertEqual(r['excludedProteinRegions'][1],{'label':'other region','start':201,'end':250})
            self.assertEqual(r['distanceBp'],10000)
    def test_extra_conditions_no_longer_match_complete_grammar(self):
        for field in ['additionalComments','instructionsToUse','baseStrength','defaultStrength']:
            c=self.clause();c[field]='Extra source requirement'
            with self.subTest(field=field),self.assertRaises(ValueError):derive(c)
    def test_ambiguous_strength_does_not_take_default(self):
        c=self.clause();c['strengthDescriptor'].append(copy.deepcopy(c['strengthDescriptor'][0]))
        with self.assertRaises(ValueError):derive(c)
    def test_ranges_and_threshold_cannot_be_relaxed(self):
        for replacement in ['ANY1 first region aa 2-101; ANY1 other region aa 100-250','ANY1 first region aa 0-101','ANY1 first region aa 2-100001','ANY1 region 2-10']:
            c=self.clause();c['strengthDescriptor'][0]['text']=PREFIX+replacement+SUFFIX
            with self.subTest(replacement=replacement),self.assertRaises(ValueError):derive(c)
        c=self.clause();c['strengthDescriptor'][0]['text']=c['strengthDescriptor'][0]['text'].replace('≤0.1','≤0.2')
        with self.assertRaises(ValueError):derive(c)

if __name__=='__main__':unittest.main()
