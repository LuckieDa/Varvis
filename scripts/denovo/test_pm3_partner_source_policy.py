"""Synthetic source-policy regressions; not real clinical adoption."""
from copy import deepcopy
import unittest
from pm3_partner_qualification import qualify
import test_pm3_partner_qualification as fixtures


class SourcePolicy(unittest.TestCase):
    context=fixtures.Qualification.context

    def source(self):
        source=fixtures.Qualification().source()
        row=source['assertions'][0]
        row.update(reviewStatus='criteria provided, single submitter',vcv='VCV000000123.1',
                   sourceSha256='a'*64,sourceLocator='/ClinicalAssertion[1]',
                   sourceAccession={'Accession':'SCV123','Version':'1','OrgID':'1234',
                                    'SubmitterName':'Synthetic laboratory'})
        row['criteriaStatements'][0].update(sourceId='VCV000000123',
            xpath='/ClinicalAssertion[1]/Classification[1]/Comment[1]',
            originalText='ACMG/AMP criteria applied: PS3, PM2, PP3.')
        return source

    def test_traceable_nonexpert_explicit_intrinsic_basis(self):
        source=self.source();before=deepcopy(source)
        result=qualify(source,self.context)
        self.assertEqual('VERIFIED',result['independentClassification'])
        self.assertEqual('PATHOGENIC',result['classification'])
        self.assertEqual('SOURCE_CRITERIA_PROVIDED_VERIFIED_BASIS',result['assertionAssessments'][0]['qualificationRoute'])
        self.assertEqual(source,before)

    def test_label_stars_or_partial_source_are_not_sufficient(self):
        for field in ('sourceSha256','sourceAccession','sourceLocator'):
            source=self.source();del source['assertions'][0][field]
            self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'],field)
        for change in ({'criteriaStatements':[]},{'reviewStatus':'no assertion criteria provided'}):
            source=self.source();source['assertions'][0].update(change)
            self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'])
        source=self.source();source['assertions'][0]['sourceAccession']['Version']='2'
        self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'])
        source=self.source();source['assertions'][0]['criteriaStatements'][0]['criteria'].append({'code':'PM3'})
        self.assertEqual('UNKNOWN',qualify(source,self.context)['classification'])

    def test_pathogenic_likely_pathogenic_preserve_lower_supported_weight(self):
        source=self.source();other=deepcopy(source['assertions'][0])
        other.update(scv='SCV124.1',sourceClassification='Likely pathogenic')
        other['sourceAccession']['Accession']='SCV124'
        source['assertions'].append(other)
        result=qualify(source,self.context)
        self.assertEqual('VERIFIED',result['independentClassification'])
        self.assertEqual('LIKELY_PATHOGENIC',result['classification'])
        self.assertEqual(['SCV123.1','SCV124.1'],result['qualifiedScvs'])
        self.assertEqual(['Likely pathogenic','Pathogenic'],result['sourceClassificationLabels'])

    def test_unqualified_pathogenic_cannot_upgrade_qualified_lp(self):
        source=self.source();row=source['assertions'][0];row['sourceClassification']='Likely pathogenic'
        other=deepcopy(row);other.update(scv='SCV124.1',sourceClassification='Pathogenic',criteriaStatements=[])
        source['assertions'].append(other)
        self.assertEqual('LIKELY_PATHOGENIC',qualify(source,self.context)['classification'])
        for label in ('Uncertain significance','Benign','Likely benign'):
            other['sourceClassification']=label
            self.assertEqual('CONFLICT',qualify(source,self.context)['independentClassification'])

    def test_unqualified_lp_conservatively_limits_pathogenic_weight(self):
        source=self.source();other=deepcopy(source['assertions'][0])
        other.update(scv='SCV124.1',sourceClassification='Likely pathogenic',criteriaStatements=[])
        source['assertions'].append(other)
        result=qualify(source,self.context)
        self.assertEqual('LIKELY_PATHOGENIC',result['classification'])
        self.assertEqual(['SCV123.1'],result['qualifiedScvs'])
        self.assertEqual('P_LP_CONSERVATIVE_LOWER_WEIGHT',result['classificationSelection'])

    def test_expert_final_list_survives_matching_narrative_and_approval_note(self):
        source = self.source()
        row = source['assertions'][0]
        row['reviewStatus'] = 'reviewed by expert panel'
        row['sourceAccession']['SubmitterName'] = 'Synthetic Variant Curation Expert Panel, ClinGen'
        prose = 'This variant has an established loss-of-function effect (PVS1). It is rare (PM2_Supporting). '
        summary = ('In summary, this variant meets the criteria to be classified as pathogenic for example disorder '
                   'based on the ACMG/AMP criteria applied, as specified by the Synthetic Variant Curation Expert Panel '
                   '(Specifications Version 2.0.0): PVS1, PM2_Supporting. ')
        approval = '(Classification approved by the Synthetic Variant Curation Expert Panel on January 1, 2025)'

        def assess(narrative, ending):
            comment = narrative + summary + ending
            row['criteriaStatements'][0].update(status='UNPARSED_SOURCE_STATEMENT', originalText=comment, criteria=[])
            row['comments'] = [{'xpath': row['criteriaStatements'][0]['xpath'], 'text': comment}]
            return qualify(source, self.context)

        result = assess(prose, approval)
        self.assertEqual('VERIFIED', result['independentClassification'])
        statement = result['sourceAssertions'][0]['criteriaStatements'][0]
        self.assertEqual(prose + summary + approval, statement['originalText'])
        self.assertEqual(['PVS1', 'PM2'], [c['code'] for c in statement['criteria']])
        for narrative, ending in [
            (prose, approval + ' The criteria were not applied.'),
            (prose, approval.replace('January 1', 'January 2')),
            (prose + 'It was observed in trans (PM3). ', approval),
            (prose + 'The criteria were not applied (PVS1). ', approval),
        ]:
            with self.subTest(narrative=narrative, ending=ending):
                self.assertEqual('UNKNOWN', assess(narrative, ending)['independentClassification'])


if __name__=='__main__':unittest.main()
