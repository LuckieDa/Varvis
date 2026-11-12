import unittest
from copy import deepcopy
from pm3_case_semantics import qualify


class Semantics(unittest.TestCase):
    def case(self, homo=False):
        entries = [('geneSymbol','GENE1','Gene'),('targetVariant','c.123A>G','Variant 1'),
                   ('reportedZygosity','homozygous' if homo else 'compound heterozygous','Zygosity')]
        if not homo: entries.append(('partnerVariant','c.456C>T','Variant 2'))
        return {'caseLabel':'1','facts':[{'field':name,'sourceValue':value,'subject':'PROBAND','issues':[],
            'citation':{'unitId':str(i),'quote':value},'ownership':{'method':'EXPLICIT_UNIQUE_CASE_ROW',
            'container':'table1','columnHeading':heading,'cellText':value}} for i,(name,value,heading) in enumerate(entries)]}
    target={'geneSymbol':'GENE1','variantAliases':['c.123A>G']}
    def test_missing_partner_explains_assembly_gap_without_discarding_verified_facts(self):
        case=self.case();case['facts']=case['facts'][:2]
        result=qualify(case,self.target)
        self.assertEqual([],result['observations'])
        self.assertIn('PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED',result['issues'])
        gap=result['assemblyGaps'][0]
        self.assertEqual('CURRENT_CASE_CONTAINER_NOT_WHOLE_PAPER',gap['scope'])
        self.assertEqual([['partnerVariant'],['reportedZygosity']],gap['requiredAnyOf'])
        self.assertNotIn('requiredFields',gap)
        self.assertEqual(2,len(result['verifiedLiteralFacts']))
    def test_two_alleles_never_imply_trans(self):
        result=qualify(self.case(),self.target)
        self.assertEqual('REPORTED_GENOTYPE_VERIFIED',result['status'])
        self.assertEqual('UNKNOWN',result['observations'][0]['phase'])
        self.assertFalse(result['observations'][0]['businessPublishable'])
    def test_unowned_quote_is_retained_with_specific_gap_not_exception(self):
        case=self.case();case['facts'][-1]['ownership']=None
        result=qualify(case,self.target)
        self.assertEqual([],result['observations'])
        finding=result['unverifiedFindings'][0]
        self.assertEqual('PM3_CASE_ATTRIBUTION_UNRESOLVED',finding['reasonCode'])
        self.assertEqual('c.456C>T',finding['citation']['quote'])
        self.assertIsNone(finding['columnHeading'])
    def test_printed_hgvs_spacing_is_normalized_but_quote_and_negation_preserved(self):
        case=self.case()
        for i,value in ((1,'c.123 A > G'),(3,'c.456 C > T')):
            f=case['facts'][i];f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']=value
        o=qualify(case,self.target)['observations'][0]
        self.assertEqual('c.123A>G',o['targetRepresentation'])
        f=next(x for x in o['facts'] if x['field']=='targetVariant')
        self.assertEqual('c.123 A > G',f['reportedValue']);self.assertEqual(f['reportedValue'],f['citation']['quote'])
        bad=case['facts'][3];bad['sourceValue']=bad['citation']['quote']=bad['ownership']['cellText']='not c.456 C > T'
        self.assertEqual([],qualify(case,self.target)['observations'])
    def test_homozygous_needs_no_partner(self):
        result=qualify(self.case(True),self.target)
        self.assertEqual('REPORTED_HOMOZYGOUS',result['observations'][0]['kind'])
    def test_cannot_clip_parent_or_negative_cell(self):
        for text in ('mother: c.456C>T','c.456C>T not detected','possibly c.456C>T'):
            case=self.case();case['facts'][-1]['ownership']['cellText']=text
            self.assertEqual([],qualify(case,self.target)['observations'])
    def test_cross_table_or_gene_cannot_form_pair(self):
        case=self.case();case['facts'][-1]['ownership']['container']='table2'
        self.assertEqual([],qualify(case,self.target)['observations'])
        self.assertEqual([],qualify(self.case(),{'geneSymbol':'GENE2','variantAliases':['c.123A>G']})['observations'])
    def test_parent_subject_or_same_allele_rejected(self):
        case=self.case();case['facts'][-1]['subject']='MOTHER'
        self.assertEqual([],qualify(case,self.target)['observations'])
        case=self.case();f=case['facts'][-1]
        f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='c.123A>G'
        self.assertEqual([],qualify(case,self.target)['observations'])
    def test_homozygous_and_partner_conflict_not_silently_chosen(self):
        case=self.case();f=case['facts'][2]
        f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='homozygous'
        self.assertEqual([],qualify(case,self.target)['observations'])
    def test_explicit_parental_allele_columns_establish_reported_trans(self):
        case=self.case()
        for index,heading in [(1,'Paternal allele'),(3,'Maternal allele')]:
            case['facts'][index]['ownership'].update(columnHeading=heading,headingUnitId='header-'+str(index))
        result=qualify(case,self.target)
        self.assertEqual('TRANS',result['observations'][0]['phase'])
        self.assertFalse(result['observations'][0]['businessPublishable'])
        case['facts'][3]['ownership']['columnHeading']='Paternal allele'
        self.assertEqual('UNKNOWN',qualify(case,self.target)['observations'][0]['phase'])
    def test_parent_genotype_heading_does_not_assert_transmission(self):
        case=self.case();case['facts'][3]['ownership'].update(columnHeading='Mother genotype',headingUnitId='h')
        self.assertEqual([],qualify(case,self.target)['observations'])
    def test_clinical_cells_are_case_owned_and_keep_unknown_or_negative(self):
        case=self.case()
        import copy
        for name,value,heading in [('diagnosis','MONDO:0000001','Disease ID'),('affectedStatus','unaffected','Affected status')]:
            fact=copy.deepcopy(case['facts'][0]);fact.update(field=name,sourceValue=value)
            fact['citation'].update(unitId=name,quote=value);fact['ownership'].update(columnHeading=heading,cellText=value)
            case['facts'].append(fact)
        observation=qualify(case,self.target)['observations'][0]
        self.assertEqual(['MONDO:0000001'],observation['reportedDiagnoses'])
        self.assertEqual('UNAFFECTED',observation['reportedAffectedStatus'])
        case['facts'][-1]['ownership']['container']='other-case-table'
        self.assertEqual('UNKNOWN',qualify(case,self.target)['observations'][0]['reportedAffectedStatus'])

    def add_parent(self,case,text='c.123A>G not detected; c.456C>T detected',parent='Mother'):
        fact=deepcopy(case['facts'][0]);fact.update(field='parentalTransmission',sourceValue=text,subject=parent.upper())
        fact['citation'].update(unitId='parent-'+str(len(case['facts'])),quote=text)
        fact['ownership'].update(columnHeading=parent+' testing',headingUnitId='parent-header',cellText=text)
        case['facts'].append(fact);return fact

    def test_one_parent_both_tested_only_one_detected(self):
        for parent in ('Mother','Father'):
            for text in ('c.123A>G not detected; c.456C>T detected','c.123A>G detected; c.456C>T not detected'):
                case=self.case();self.add_parent(case,text,parent)
                o=qualify(case,self.target)['observations'][0]
                self.assertEqual('TRANS',o['phase'])
                self.assertEqual('ONE_PARENT_BOTH_ALLELES_TESTED_ONLY_ONE_DETECTED',o['phaseBasis'])
                self.assertEqual(text,o['facts'][-1]['citation']['quote'])

    def test_unmentioned_uncertain_wrong_alleles_or_both_positive_not_one_parent_proof(self):
        for text in ('c.123A>G detected','c.123A>G detected; c.456C>T possibly not detected',
                     'c.123A>G detected; c.999C>T not detected','c.123A>G detected; c.456C>T detected',
                     'c.123A>G not detected; c.456C>T not detected'):
            case=self.case();self.add_parent(case,text)
            self.assertEqual('UNKNOWN',qualify(case,self.target)['observations'][0]['phase'])

    def test_parent_ownership_and_full_quote_required(self):
        for mutate in (lambda f:f.update(subject='FATHER'),lambda f:f['ownership'].update(container='other-table'),
                       lambda f:f['ownership'].update(headingUnitId=None),lambda f:f['ownership'].update(cellText='possibly '+f['sourceValue']),
                       lambda f:f.update(issues=['ASSAY_LIMITATION'])):
            case=self.case();fact=self.add_parent(case);mutate(fact)
            self.assertEqual('UNKNOWN',qualify(case,self.target)['observations'][0]['phase'])

    def test_conflicting_parental_tests_cannot_be_silently_used(self):
        case=self.case();self.add_parent(case)
        self.add_parent(case,'c.123A>G detected; c.456C>T detected')
        self.assertEqual('CONFLICT',qualify(case,self.target)['observations'][0]['phase'])
        case=self.case();self.add_parent(case)
        case['facts'][1]['ownership'].update(columnHeading='Maternal allele',headingUnitId='h1')
        case['facts'][3]['ownership'].update(columnHeading='Paternal allele',headingUnitId='h2')
        self.assertEqual('CONFLICT',qualify(case,self.target)['observations'][0]['phase'])

    def test_shared_table_caption_gene_and_exact_transcript(self):
        case=self.case();gene=case['facts'][0]
        caption='GENE1 genotypes in the study. Variants are reported relative to NM_000001.1.'
        gene['ownership']={'method':'SHARED_TABLE_CAPTION_SCOPE','container':'table1','captionText':caption}
        gene['citation']['quote']=caption
        transcript=deepcopy(gene);transcript.update(field='transcript',sourceValue='NM_000001.1');case['facts'].append(transcript)
        o=qualify(case,self.target)['observations'][0]
        self.assertEqual(['NM_000001.1'],o['reportedTranscripts'])
        gene['ownership']['container']='other-table';transcript['ownership']['container']='other-table'
        self.assertEqual([],qualify(case,self.target)['observations'])

    def test_caption_mentions_not_equal_global_scope_or_diagnosis(self):
        for caption in ('Variants of GENE1 and GENE2','No GENE1 genotypes were reported','GENE2 genotypes with a comparison to GENE1'):
            case=self.case();f=case['facts'][0];f['citation']['quote']=caption
            f['ownership']={'method':'SHARED_TABLE_CAPTION_SCOPE','container':'table1','captionText':caption}
            self.assertEqual([],qualify(case,self.target)['observations'])
        case=self.case();f=deepcopy(case['facts'][0]);f.update(field='diagnosis',sourceValue='MONDO:0000001')
        f['ownership']={'method':'SHARED_TABLE_CAPTION_SCOPE','container':'table1','captionText':'GENE1 genotypes in MONDO:0000001'}
        f['citation']['quote']=f['ownership']['captionText'];case['facts'].append(f)
        self.assertEqual([],qualify(case,self.target)['observations'][0]['reportedDiagnoses'])

    def test_protein_notation_can_form_reported_pair_but_not_genomic_identity(self):
        case=self.case()
        for i,value in ((1,'p.A403V'),(3,'p.I65T')):
            f=case['facts'][i];f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']=value
        target={'geneSymbol':'GENE1','variantAliases':['p.Ala403Val']}
        o=qualify(case,target)['observations'][0]
        self.assertEqual('p.A403V',o['targetRepresentation']);self.assertEqual('NOT_RESOLVED',o['genomicIdentityStatus'])
        f=case['facts'][3];f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='p.Ala403Val'
        self.assertEqual([],qualify(case,target)['observations'])

    def test_same_role_coding_and_protein_are_kept_for_later_sequence_check(self):
        case=self.case();f=deepcopy(case['facts'][1]);f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='p.T41A'
        case['facts'].append(f)
        o=qualify(case,self.target)['observations'][0]
        self.assertEqual('c.123A>G',o['targetRepresentation']);self.assertEqual('T41A',o['reportedProteins']['target'])
        self.assertEqual('NOT_RESOLVED',o['genomicIdentityStatus'])
        f=deepcopy(f);f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='p.Thr41Ala';case['facts'].append(f)
        self.assertEqual('T41A',qualify(case,self.target)['observations'][0]['reportedProteins']['target'])
        f=deepcopy(f);f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='p.T41G';case['facts'].append(f)
        self.assertEqual([],qualify(case,self.target)['observations'])

    def test_conflicting_coding_representations_cannot_be_chosen_by_query_match(self):
        case=self.case();f=deepcopy(case['facts'][1]);f['sourceValue']=f['citation']['quote']=f['ownership']['cellText']='c.123A>T';case['facts'].append(f)
        result=qualify(case,self.target)
        self.assertEqual([],result['observations'])
        self.assertIn('PM3_VARIANT_REPRESENTATIONS_UNRESOLVED',result['issues'])

if __name__=='__main__': unittest.main()
