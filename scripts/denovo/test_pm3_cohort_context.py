import unittest
import test_pm3_source_context as table_tests
from prepare_literature import prepare,sha
from pm3_observations import ownership
from pm3_cohort_context import cohort_context
from pm3_case_semantics import qualify
import test_pm3_case_semantics as semantics_tests


class Cohort(unittest.TestCase):
    def source(self,enrol='Patients diagnosed with disease X were enrolled in a clinical study.',
               link='Table 1 presents genotypes of all patients.',extra=''):
        s=table_tests.TableContext().source()
        # Build an independent source with a real table label and explicit links.
        raw=('''<article><front><article-meta><article-id pub-id-type="pmcid">123</article-id>
        <title-group><article-title>Study</article-title></title-group></article-meta></front><body>
        <sec><title>Methods</title><p>'''+enrol+'''</p></sec><sec><title>Results</title><p>'''+link+'''</p><p>'''+extra+'''</p></sec>
        <table-wrap id="t1"><label>Table 1</label><table><thead><tr><th>PtID</th><th>Mutation 1</th></tr></thead>
        <tbody><tr><td>148</td><td>c.123A&gt;G</td></tr></tbody></table></table-wrap></body></article>''').encode()
        doc=prepare(raw,'PMC123',sha(raw))
        s.update(raw=raw,main=doc,units=[{**u,'sourceSha256':sha(raw)} for u in doc['units']]);return s

    def test_explicit_chain_and_source_citations(self):
        s=self.source();result=cohort_context(s,ownership(s,'148'))
        self.assertEqual('SOURCE_COHORT_BOUND',result['status'])
        self.assertEqual(['diagnosis','affectedStatus'],[f['field'] for f in result['facts']])
        self.assertEqual(3,len(result['bindings'][0]['citations']))
        case=semantics_tests.Semantics().case();container=result['bindings'][0]['container']
        for f in case['facts']:f['ownership']['container']=container
        case['facts']+=result['facts'];o=qualify(case,semantics_tests.Semantics.target)['observations'][0]
        self.assertEqual(['disease X'],o['reportedDiagnoses']);self.assertEqual('AFFECTED',o['reportedAffectedStatus'])
        self.assertEqual('UNKNOWN',o['phase']);self.assertEqual('NOT_RESOLVED',o['genomicIdentityStatus'])

    def test_uncertain_mixed_negated_and_other_table_rejected(self):
        for enrol,link,extra in [
            ('Patients diagnosed with possible disease X were enrolled in a study.','Table 1 presents genotypes of all patients.',''),
            ('Patients diagnosed with disease X or disease Y were enrolled in a study.','Table 1 presents genotypes of all patients.',''),
            ('Patients without disease X were enrolled in a study.','Table 1 presents genotypes of all patients.',''),
            ('Patients diagnosed with disease X were enrolled in a previous study.','Table 1 presents genotypes of all patients.',''),
            ('Patients diagnosed with disease X were enrolled in a study.','Table 2 presents genotypes of all patients.',''),
            ('Patients diagnosed with disease X were enrolled in a study.','Table 1 presents genotypes of some patients.',''),
            ('Patients diagnosed with disease X were enrolled in a study.','Table 1 presents genotypes of all patients, except the controls.',''),
            ('Patients diagnosed with disease X were enrolled in a study.','Table 1 presents genotypes of all patients.','We included healthy controls.'),
            ('Patients diagnosed with disease X were enrolled in a study.','Table 1 presents genotypes of all patients.','Patients diagnosed with disease Y were enrolled in a second trial.')]:
            with self.subTest(enrol=enrol,link=link,extra=extra):
                s=self.source(enrol,link,extra);r=cohort_context(s,ownership(s,'148'))
                self.assertEqual([],r['facts']);self.assertTrue(r['relatedStatements'])

    def test_absent_case_and_conflicting_case_status(self):
        s=self.source();self.assertEqual([],cohort_context(s,ownership(s,'999'))['facts'])
        result=cohort_context(s,ownership(s,'148'));case=semantics_tests.Semantics().case()
        for f in case['facts']:f['ownership']['container']=result['bindings'][0]['container']
        from copy import deepcopy
        negative=deepcopy(case['facts'][0]);negative.update(field='affectedStatus',sourceValue='unaffected')
        negative['ownership'].update(columnHeading='Affected status',cellText='unaffected');negative['citation']['quote']='unaffected'
        case['facts']+=result['facts']+[negative]
        self.assertEqual('CONFLICT',qualify(case,semantics_tests.Semantics.target)['observations'][0]['reportedAffectedStatus'])

    def test_existing_model_diagnosis_is_reconciled_without_changing_model(self):
        from pm3_observations import project
        from copy import deepcopy
        s=self.source();native=cohort_context(s,ownership(s,'148'))['facts'][0]
        f={k:deepcopy(native[k]) for k in ('field','sourceValue','subject','issues','citation')}
        checked={'criterion':'PM3','pmcid':'PMC123','bundleSha256':'a'*64,'extractionCoverageProposal':'EXTRACTED_WITHIN_TARGET',
                 'cases':[{'caseLabel':'148','caseCitation':native['supportingCitations'][-1],
                           'caseOriginProposal':'UNCLEAR','originCitations':[],'fields':[f]}]}
        before=deepcopy(checked);c=project(s,[checked])['cases'][0]
        diagnosis=[f for f in c['facts'] if f['field']=='diagnosis']
        self.assertEqual(1,len(diagnosis));self.assertEqual('EXPLICIT_COHORT_TABLE_CASE',diagnosis[0]['ownership']['method'])
        self.assertEqual(before,checked)
        self.assertFalse(any(f['field']=='diagnosis' for f in qualify(c,semantics_tests.Semantics.target)['unverifiedFindings']))

    def test_duplicate_table_label_cannot_bind(self):
        s=self.source();s['raw']=s['raw'].replace(b'</body>',b'<table-wrap id="other"><label>Table 1</label></table-wrap></body>')
        self.assertEqual([],cohort_context(s,ownership(s,'148'))['facts'])

if __name__=='__main__':unittest.main()
