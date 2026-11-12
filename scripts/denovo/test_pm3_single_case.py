import unittest
from copy import deepcopy
from test_pm3_automatic_facts import source
from pm3_single_case import report,ownership,native_facts,hints
from pm3_case_semantics import qualify
from pm3_literature_contract import review,VERSION

CLINICAL='<sec><title>Case presentation</title><p>The proband is an 11‐year‐old female who presented to the clinic with seizures.</p></sec>'
GENETIC=('<sec><title>Sequencing analysis</title><p>We identified a novel homozygous variant '
 '(NM_000001.1:c.2C&gt;G) in the EXAMPLE gene. Sanger sequencing subsequently confirmed this homozygous variant '
 'in this individual. Both parents carried a heterozygous variant.</p></sec>')
DISEASE=('<sec><title>Introduction</title><p>Example deficiency (ED, MIM 123456) is a recessive disorder.</p></sec>'
 '<sec><title>Conclusion</title><p>In the present study, a novel homozygous mutation was recognized in the EXAMPLE gene '
 'in a girl manifesting typical ED symptoms. Like earlier studies, this case was diagnosed by sequencing.</p></sec>')
TARGET={'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']}

class SingleCase(unittest.TestCase):
    def test_literal_genotype_and_explicit_compatible_disease(self):
        s=source(CLINICAL+GENETIC+DISEASE);ff=native_facts(s,'proband',TARGET)
        obs=qualify({'caseLabel':'proband','facts':ff},TARGET)['observations'][0]
        self.assertEqual('REPORTED_HOMOZYGOUS',obs['kind'])
        self.assertEqual(['NM_000001.1'],obs['reportedTranscripts'])
        self.assertEqual(['Example deficiency'],obs['reportedDiagnoses'])
        self.assertEqual('AFFECTED',obs['reportedAffectedStatus'])
        self.assertEqual('UNKNOWN',obs['phase'])
        self.assertTrue(all(f['supportingCitations'] for f in ff))

    def test_no_case_from_title_alone_or_multiple_proband_sections(self):
        for body in [GENETIC,CLINICAL+CLINICAL+GENETIC,
                     CLINICAL.replace('seizures.','seizures. Another patient had similar symptoms.')+GENETIC,
                     CLINICAL+GENETIC+GENETIC]:
            with self.subTest(body=body):self.assertIsNone(report(source(body)))

    def test_uncertain_previous_negated_conflicting_genotype_excluded(self):
        for mutation in [GENETIC.replace('identified','previously reported'),
                         GENETIC.replace('novel','possible'),GENETIC.replace('confirmed','did not confirm'),
                         GENETIC.replace('Both parents','c.8A&gt;T was also detected. Both parents')]:
            self.assertIsNone(report(source(CLINICAL+mutation)))

    def test_no_diagnosis_from_gene_or_abbreviation_definition_alone(self):
        for disease in ['',DISEASE.split('<sec><title>Conclusion')[0],
                        DISEASE.replace('typical ED','possible ED'),DISEASE.replace('this case was diagnosed','this case was suspected')]:
            obs=qualify({'caseLabel':'proband','facts':native_facts(source(CLINICAL+GENETIC+disease),'proband',TARGET)},TARGET)['observations'][0]
            self.assertEqual([],obs['reportedDiagnoses']);self.assertEqual('UNKNOWN',obs['reportedAffectedStatus'])

    def test_wrong_target_and_fake_case_label_never_owned(self):
        s=source(CLINICAL+GENETIC+DISEASE)
        self.assertEqual({},ownership(s,'presentation'))
        self.assertEqual([],native_facts(s,'proband',{**TARGET,'geneSymbol':'OTHER'}))
        self.assertEqual([],native_facts(s,'proband',{**TARGET,'variantAliases':['c.8A>T']}))

    def test_abstract_review_tables_and_other_sections_do_not_become_primary_genotype(self):
        other=source(CLINICAL+GENETIC)
        for u in other['main']['units']:
            if u['text'].startswith('We identified '):u['kind']='ABSTRACT_PARAGRAPH'
        self.assertIsNone(report(other))
        s=source(CLINICAL+GENETIC+DISEASE)
        self.assertEqual(1,len(ownership(s,'proband')))
        self.assertEqual(1,len(hints(s['main']['units'])))

    def test_source_checked_model_case_anchor_is_actual_genotype_unit(self):
        s=source(CLINICAL+GENETIC+DISEASE);s.update(coverage={},attachments=[])
        u=report(s)['anchor'];q={'unitId':u['unitId'],'quote':u['text']}
        c={'schemaVersion':VERSION,'coverage':'EXTRACTED_WITHIN_TARGET','sourceAssessments':[{'materialKind':'INDIVIDUAL_CASE_MATERIAL','citation':q}],
           'cases':[{'caseLabel':'proband','caseOrigin':'AUTHOR_COHORT','caseCitation':q,'originCitations':[q],
                     'fields':[{'field':'targetVariant','sourceValue':'c.2C>G','subject':'PROBAND','citation':q}]}]}
        self.assertEqual('proband',review(s,c)['cases'][0]['caseLabel'])
        from pm3_observations import project,qualify_projected
        result=qualify_projected(s,project(s,[review(s,c)],TARGET),TARGET)
        case=result['cases'][0]
        self.assertEqual('VERIFIED',case['primarySourceQualification']['qualification'])
        self.assertEqual('VERIFIED',case['occurrenceQualification']['qualification'])
        self.assertEqual('NOT_ESTABLISHED',case['occurrenceQualification']['crossPaperIndependence'])
        self.assertEqual('NOT_REPORTED',case['patientIdentifier'])
        c['cases'][0]['caseLabel']='presentation'
        with self.assertRaisesRegex(ValueError,'CASE_LABEL_NOT_ANCHORED'):review(s,c)

    def test_current_genotype_is_scheduled_before_abstract_without_dropping_it(self):
        from pm3_chunk_priority import order
        from model_literature_contract import canonical
        s=source(CLINICAL+GENETIC);u=report(s)['anchor']
        abstract={**u,'unitId':'abstract','kind':'ABSTRACT_PARAGRAPH'}
        payload=lambda unit:{'messages':[{}, {'content':canonical({'units':[unit],
            'workUnit':{'targetUnitIds':[unit['unitId']]}}).decode()}]}
        self.assertEqual([1,0],order([payload(abstract),payload(u)],TARGET))

if __name__=='__main__':unittest.main()
