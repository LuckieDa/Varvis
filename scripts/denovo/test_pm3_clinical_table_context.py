import unittest
from copy import deepcopy
from test_pm3_automatic_facts import source
from pm3_observations import ownership
from pm3_clinical_table_context import context, accepts


def fixture():
    s = source('''<p>The biochemical and clinical parameters of the patients are reported in the
Supplementary Materials (S4). <xref ref-type="app" rid="app4">Supplementary Materials (S4)</xref></p>
<table-wrap id="genotypes"><caption><p>EXAMPLE genotypes.</p></caption><table><thead><tr>
<th>Proband</th><th>Sex</th><th>Age at onset/first event</th><th>Variant</th></tr></thead><tbody>
<tr><td>#7</td><td>F</td><td>birth</td><td>c.2C&gt;G</td></tr>
<tr><td>#8</td><td>M</td><td>2 years</td><td>c.4C&gt;G</td></tr></tbody></table></table-wrap>
<app id="app4"><p>Supplementary Materials S4: Clinical characteristics of EXAMPLE samples;</p>
<supplementary-material><media xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="data.zip"/></supplementary-material></app>'''.replace('\n',' '))
    values = {'A2':'Case','B2':'Sex','C2':'Age at onset/first event','D2':'Metabolic Phenotype (registry)**',
              'A3':'N° 7','B3':'F','C3':'birth','D3':'mED','A4':'N° 8','B4':'M','C4':'2 years','D4':'cED',
              'A6':'mED: mild example disease; cED: classic example disease;',
              'A7':'** patients were assigned to these three phenotype categories according to their pre-treatment plasma metabolite levels or dietary metabolite tolerance.'}
    s['units'] += [{'unitId':'attachment:'+cell,'kind':'SUPPLEMENT_CELL','text':text,
        'sourceSha256':'b'*64,'archiveMemberSha256':'c'*64,'archiveMemberName':'Supplementary Materials S4.xlsx',
        'originalName':'data.zip','sheet':'Clinical','cell':cell,'cellSemantics':'TEXT','sheetState':'visible'}
        for cell,text in values.items()]
    return s


def change(s, cell, **values):
    next(u for u in s['units'] if u.get('cell') == cell).update(values)


class ClinicalContext(unittest.TestCase):
    def result(self,s):return context(s,ownership(s,'7'),'7')

    def test_exact_cohort_join_keeps_measurement_note_and_subtype(self):
        r=self.result(fixture())
        self.assertEqual(['mild example disease','affected'],[f['sourceValue'] for f in r['facts']])
        self.assertTrue(all(accepts(f,'7') for f in r['facts']))
        self.assertEqual(['7','8'],r['bindings'][0]['caseLabels'])
        self.assertFalse(r['primarySourceVerified'])
        self.assertEqual('mED',r['facts'][0]['citation']['quote'])
        self.assertEqual(10,len(r['facts'][0]['supportingCitations']))

    def test_case_number_alone_or_partial_cohort_does_not_join(self):
        for cell,text in [('B4','F'),('C4','3 years'),('A4','N° 9'),('B3','unknown')]:
            s=fixture();change(s,cell,text=text);self.assertEqual([],self.result(s)['facts'])

    def test_database_header_without_measured_classification_is_insufficient(self):
        for text in ['** predicted from the variants in a database.',
                     '** patients were not assigned to these categories.',
                     '* patients were assigned to these three phenotype categories according to their pre-treatment plasma metabolite levels or dietary metabolite tolerance.']:
            s=fixture();change(s,'A7',text=text);self.assertEqual([],self.result(s)['facts'])

    def test_uncertain_mixed_or_normal_phenotypes_do_not_become_disease(self):
        for text in ['mED/cED','possible mED','NA']:
            s=fixture();change(s,'D3',text=text);self.assertEqual([],self.result(s)['facts'])
        s=fixture();change(s,'A6',text='mED: normal range;');self.assertEqual([],self.result(s)['facts'])

    def test_wrong_archive_member_or_hidden_formula_cells_rejected(self):
        for values in [{'archiveMemberName':'Supplementary Materials S5.xlsx'},
                       {'sheetState':'hidden'},{'cellSemantics':'FORMULA'},{'mergedCell':True}]:
            s=fixture();change(s,'D3',**values);self.assertEqual([],self.result(s)['facts'])

    def test_wrong_native_link_never_binds(self):
        s=fixture();s['raw']=s['raw'].replace(b'rid="app4"',b'rid="other"')
        self.assertEqual([],self.result(s)['facts'])

    def test_duplicate_clinical_classification_is_ambiguous(self):
        s=fixture(); extra=deepcopy(next(u for u in s['units'] if u.get('cell')=='A6'))
        extra.update(unitId='attachment:A8',cell='A8',text='mED: other disease;');s['units'].append(extra)
        self.assertEqual([],self.result(s)['facts'])

    def test_project_preserves_the_cross_table_proof(self):
        from pm3_observations import project
        from bundle_model_contract import cite
        s=fixture();anchor=next(u for u in s['units'] if u['text']=='#7')
        checked={'pmcid':s['pmcid'],'bundleSha256':s['bundleSha256'],'criterion':'PM3',
            'cases':[{'caseLabel':'7','caseCitation':cite(s,{'unitId':anchor['unitId'],'quote':'#7'}),
                      'caseOriginProposal':'UNCLEAR','originCitations':[],'fields':[]}],
            'extractionCoverageProposal':'CONTEXT_INSUFFICIENT'}
        case=project(s,[checked])['cases'][0]
        facts=[f for f in case['facts'] if f['field']=='diagnosis']
        self.assertEqual('mild example disease',facts[0]['sourceValue'])
        self.assertEqual('UNKNOWN',case['primarySourceQualification']['qualification'])
        self.assertEqual('SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN',facts[0]['ownership']['method'])

    def test_primary_recruitment_requires_count_and_local_source_not_case_number(self):
        prefix='A total of 7 DNA samples consisting of controls, probands and their parents were recruited by the Example Hospital. The test group consisted of 2 patients. '
        for text,expected in [(prefix,True),(prefix.replace('2 patients','3 patients'),False),
                              (prefix+'These were previously reported cases. ',False)]:
            s=fixture();s['raw']=s['raw'].replace(b'<p>The biochemical',('<p>'+text+'The biochemical').encode())
            from prepare_literature import prepare,sha
            doc=prepare(s['raw'],s['pmcid'],sha(s['raw']))
            s['main']=doc;s['units']=[{**u,'sourceSha256':sha(s['raw'])} for u in doc['units']]+[u for u in s['units'] if u['kind']=='SUPPLEMENT_CELL']
            self.assertEqual(expected,self.result(s)['primarySourceVerified'])


if __name__=='__main__':unittest.main()
