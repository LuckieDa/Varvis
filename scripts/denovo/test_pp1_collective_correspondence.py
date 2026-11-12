"""Closed sets must be source-bound, not inferred from equal counts alone."""
from copy import deepcopy
import unittest
from pp1_family_correspondence import across_unit_groups, explicit_role_edges, collective_assertive


def fixture():
    intro='Proband in family eight is II-3. She was diagnosed with TEST syndrome (TS). Her mother is a TS patient. All two TS patients in this family were examined.'
    quote='all two TS patients had c.7G>A mutation in GENE'
    result='c.7G>A had not been reported before, '+quote+'.'
    def cell(text,index,uid):return dict(text=text,sourceCellIndex=index,unitId=uid,rowspan=1,colspan=1)
    cells=[cell('Family',0,'h1'),cell('Mutations',1,'h2'),cell('8',0,'f8'),cell('c.7G>A',1,'v')]
    source={'pmcid':'PMC123','bundleSha256':'a'*64,'main':{'tables':[{'rows':[{'cells':cells[:2]},{'cells':cells[2:]}]}]},
            'units':[{'unitId':'intro','text':intro},{'unitId':'result','text':result}]+[{'unitId':c['unitId'],'text':c['text']} for c in cells]}
    q={'identity':{'status':'VERIFIED'},'families':{'f':{'sourceFamilyLabel':'Family 8','members':{'II-3':'p','mother':'m'}}},
       'observations':[],'unresolved':[{'claimId':'collective','reasonCode':'PP1_TYPED_SEMANTICS_UNRESOLVED'}],
       'reviewedUnitIds':[u['unitId'] for u in source['units']]}
    for label,key in [('II-3','p'),('mother','m')]:
        q['observations'].append({'id':key,'familyKey':'f','memberKey':key,'memberLabel':label,'kind':'diagnosis',
            'value':'TEST syndrome','claimIds':[key], 'sourceRefs':[{'unitId':'intro','quote':intro}]})
    q['observations'].append({'id':'prob','familyKey':'f','memberKey':'p','memberLabel':'II-3','kind':'proband',
         'value':True,'claimIds':['proband'],'sourceRefs':[{'unitId':'intro','quote':intro}]})
    i={'target':{'variantAliases':['c.7G>A']},'facts':[{'familyKey':'f','claimId':'collective','raw':{'fieldId':'F06.3'},
       'sourceAudit':{'verdict':'SUPPORTED'},'issues':[], 'sourceRefs':[{'unitId':'result','quote':quote}]}]}
    return source,i,q


class Collective(unittest.TestCase):
    def test_cross_paragraph_set_uses_table_and_preserves_proof(self):
        s,i,q=fixture();across_unit_groups(s,i,q);explicit_role_edges(s,q)
        carriers=[o for o in q['observations'] if o['kind']=='genotype']
        self.assertEqual({'p','m'},{o['memberKey'] for o in carriers})
        self.assertEqual({'intro','result','h1','h2','f8','v'},{r['unitId'] for r in carriers[0]['sourceRefs']})
        self.assertEqual(1,len([o for o in q['observations'] if o['kind']=='parentOf']))
        self.assertFalse(q['unresolved'])
        across_unit_groups(s,i,q)
        self.assertEqual(2,len([o for o in q['observations'] if o['kind']=='genotype']))

    def test_cross_paragraph_negative_controls(self):
        for change in ('count','no-table','unreviewed','merged','other-family','same-allele-two-families',
                       'diagnosis-conflict','no-identity','different-allele','negated','conditional','unknown-acronym','unreviewed-fact'):
            with self.subTest(change=change):
                s,i,q=fixture()
                if change=='count':s['units'][0]['text']=s['units'][0]['text'].replace('All two','All three')
                if change=='no-table':s['main']['tables']=[]
                if change=='unreviewed':q['reviewedUnitIds'].remove('h1')
                if change=='merged':s['main']['tables'][0]['rows'][1]['cells'][0]['rowspan']=2
                if change=='other-family':s['units'][0]['text']+=' Family seven is also included.'
                if change=='same-allele-two-families':
                    row=deepcopy(s['main']['tables'][0]['rows'][1]);row['cells'][0]['text']='7';s['main']['tables'][0]['rows'].append(row)
                if change=='diagnosis-conflict':
                    row=deepcopy(q['observations'][0]);row['value']='Other disease';q['observations'].append(row)
                if change=='no-identity':q['identity']=None
                if change=='different-allele':i['target']['variantAliases']=['c.7G>T']
                if change=='negated':s['units'][1]['text']='It is not true that '+i['facts'][0]['sourceRefs'][0]['quote']
                if change=='conditional':s['units'][1]['text']='If c.7G>A had not been reported before, '+i['facts'][0]['sourceRefs'][0]['quote']
                if change=='unknown-acronym':
                    for row in q['observations']:
                        for r in row['sourceRefs']:r['quote']=r['quote'].replace('(TS)','(XX)')
                if change=='unreviewed-fact':i['facts'][0]['issues']=['ADDED_SOURCE_FACT_REQUIRES_REVIEW']
                across_unit_groups(s,i,q)
                self.assertFalse([o for o in q['observations'] if o['kind']=='genotype'])

    def test_publication_history_is_not_test_negation(self):
        quote='all two TS patients had c.7G>A'
        self.assertTrue(collective_assertive('It had not been reported before, '+quote,quote,0))
        for prefix in ('It is not true that ', 'If it had not been reported before, ', 'We could find that ', 'Neither report established that '):
            self.assertFalse(collective_assertive(prefix+quote,quote,0),prefix)

    def test_parent_role_does_not_invent_grandparent_or_adoption_edges(self):
        for text in ('An adoptive mother.', 'A stepmother.', 'A possible father.'):
            s,i,q=fixture();s['units'][0]['text']+=' '+text;explicit_role_edges(s,q)
            self.assertFalse([o for o in q['observations'] if o['kind']=='parentOf'])
        s,i,q=fixture();q['observations'][1]['memberLabel']='grandmother';explicit_role_edges(s,q)
        self.assertFalse([o for o in q['observations'] if o['kind']=='parentOf'])

if __name__=='__main__':unittest.main()
