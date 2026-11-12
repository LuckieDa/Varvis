import unittest
from copy import deepcopy
from pm3_pdf_observations import rows, accepts
from pm3_literature_contract import review, VERSION
from pm3_observations import project
from pm3_case_semantics import qualify
import test_bundle_model


class AnonymousRows(unittest.TestCase):
    def setUp(self):
        self.f=test_bundle_model.BundleModelTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.source=deepcopy(self.f.source)
        self.units=[]
        for n,text in enumerate(('Allele 1 Allele 2 Phe load',
                'p.R3*‡§ c.7C>T p.A4V|| c.11C>T Yes 515 87 Yes positive',
                'p.R3*‡§ c.7C>T p.A4V|| c.11C>T Yes 420 74 No'),1):
            self.units.append({'unitId':f'attachment:pdf:line:{n}','sourceSha256':'b'*64,
                'text':text,'page':1,'sourcePart':'pdf','sourcePath':f'/page[1]/line[{n}]',
                'kind':'SUPPLEMENT_PARAGRAPH','pdfTextOnly':True,'bbox':[0,n*20,500,n*20+10]})
        self.source['units']+=self.units

    def candidate(self):
        r=rows(self.units)[0]
        return {'schemaVersion':VERSION,'coverage':'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments':[{'materialKind':'INDIVIDUAL_CASE_MATERIAL','citation':{'unitId':r['unitId'],'quote':r['quote']}}],
            'cases':[{'caseLabel':r['caseLabel'],'caseOrigin':'UNCLEAR','originCitations':[],
                'caseCitation':{'unitId':r['unitId'],'quote':r['quote']},'fields':[
                    {'field':'targetVariant','sourceValue':'c.11C>T','subject':'PROBAND',
                     'citation':{'unitId':r['unitId'],'quote':'c.11C>T'}},
                    {'field':'partnerVariant','sourceValue':'c.7C>T','subject':'PROBAND',
                     'citation':{'unitId':r['unitId'],'quote':'c.7C>T'}}]}]}

    def test_rows_are_locators_not_patient_or_family_identity(self):
        r=rows(self.units)
        self.assertEqual(2,len(r));self.assertNotEqual(r[0]['caseLabel'],r[1]['caseLabel'])
        self.assertEqual('ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID',r[0]['identityKind'])
        self.assertEqual('‡§',r[0]['alleles'][0]['sourceMarkers'])
        checked=review(self.source,self.candidate())
        case=project(self.source,[checked])['cases'][0]
        self.assertEqual('NOT_REPORTED',case['patientIdentifier'])
        self.assertEqual('UNKNOWN',case['primarySourceQualification']['qualification'])
        self.assertTrue(all(accepts(f) for f in case['facts']))
        self.assertEqual([],qualify(case,{'geneSymbol':'GENE1','variantAliases':['c.11C>T']})['observations'])

    def test_wrong_model_label_and_neighbour_quote_are_rejected(self):
        c=self.candidate();c['cases'][0]['caseLabel']='Patient-1'
        with self.assertRaisesRegex(ValueError,'CASE_LABEL_NOT_ANCHORED'):review(self.source,c)
        c=self.candidate();c['cases'][0]['caseCitation']['unitId']=self.units[2]['unitId']
        c['cases'][0]['caseCitation']['quote']=self.units[2]['text']
        with self.assertRaisesRegex(ValueError,'CASE_LABEL_NOT_ANCHORED'):review(self.source,c)

    def test_negation_missing_or_ambiguous_header_cannot_make_a_case(self):
        self.assertEqual([],rows(self.units[1:]))
        self.assertEqual([],rows(self.units+[deepcopy(self.units[0])]))
        altered=deepcopy(self.units);altered[0]['page']=2
        self.assertEqual([],rows(altered))
        altered=deepcopy(self.units);altered[1]['text']+=' not detected';altered[2]['text']='Example '+altered[2]['text']
        self.assertEqual([],rows(altered))

    def test_review_feedback_identifies_quote_and_role_errors_without_facts(self):
        from pm3_literature_contract import proposal_findings
        c=self.candidate();fs=c['cases'][0]['fields']
        fs[0]['field']='partnerVariant';fs[1]['field']='targetVariant'
        fs.append({'field':'geneSymbol','sourceValue':'GENE1','citation':{'quote':'a disease'}})
        r=proposal_findings(c,{'geneSymbol':'GENE1','variantAliases':['c.11C>T']})
        self.assertEqual({'VALUE_NOT_IN_QUOTE','TARGET_PARTNER_ROLES_APPEAR_REVERSED'},{v['reasonCode'] for v in r})
        self.assertTrue(all('replacement' not in v for v in r))


if __name__=='__main__':unittest.main()
