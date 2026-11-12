from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from test_core_context_review import LocalFixture
from pm3_identity import resolve_case, representation


class Identity(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.fixture=LocalFixture(Path(self.tmp.name))
    def case(self):
        return {'caseLabel':'1','semantics':{'observations':[{
            'geneSymbol':'EXAMPLE','targetRepresentation':'c.2C>G','partnerRepresentation':'c.3C>T',
            'reportedTranscripts':['NM_000001.1']}]}}
    def test_both_alleles_resolve_without_reported_protein_or_patient_partner(self):
        row=resolve_case(self.case(),self.fixture.resolver())[0]
        self.assertEqual('LOCAL_IDENTITIES_VERIFIED',row['status'])
        self.assertEqual(101,row['identities']['target']['allele']['position'])
        self.assertEqual(102,row['identities']['partner']['allele']['position'])
        self.assertEqual('T1T',row['identities']['partner']['proteinChange'])
        self.assertNotEqual(row['alleleIds']['target'],row['alleleIds']['partner'])
        self.assertFalse(row['businessPublishable'])
    def test_missing_transcript_never_selects_mane_by_preference(self):
        c=self.case();c['semantics']['observations'][0]['reportedTranscripts']=[]
        row=resolve_case(c,self.fixture.resolver())[0]
        self.assertEqual({},row['identities']);self.assertEqual(2,len(row['issues']))
    def test_protein_only_stops_before_any_reference_query(self):
        from unittest.mock import Mock
        c=self.case();c['semantics']['observations'][0].update(targetRepresentation='p.A403V',partnerRepresentation='p.I65T')
        resolver=Mock();r=resolve_case(c,resolver)[0]
        self.assertEqual({},r['identities']);resolver.resolve_coding.assert_not_called()
        self.assertEqual({'PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_MISSING'},{x['reasonCode'] for x in r['issues']})
    def test_explicit_transcript_and_conflicting_column(self):
        self.assertEqual(('NM_000001.1','c.2C>G'),representation('NM_000001.1:c.2C>G',[]))
        with self.assertRaisesRegex(ValueError,'CONFLICT'):
            representation('NM_000001.1:c.2C>G',['NM_000001.2'])
    def test_wrong_selected_target_cannot_reuse_resolved_key(self):
        row=resolve_case(self.case(),self.fixture.resolver(),{'assembly':'GRCh37'})[0]
        self.assertNotIn('target',row['identities']);self.assertNotIn('alleleIds',row)
        self.assertEqual('PM3_SELECTED_TARGET_IDENTITY_MISMATCH',row['issues'][0]['reasonCode'])
    def test_same_allele_and_unsupported_hgvs_remain_explicit(self):
        c=self.case();o=c['semantics']['observations'][0];o['partnerRepresentation']=o['targetRepresentation']
        row=resolve_case(c,self.fixture.resolver())[0]
        self.assertEqual('PM3_SAME_ALLELE_IS_NOT_DISTINCT_PARTNER',row['issues'][0]['reasonCode'])
        o['partnerRepresentation']='c.3delC'
        row=resolve_case(c,self.fixture.resolver())[0]
        self.assertEqual('DENOVO_IDENTITY_CODING_SNV_REQUIRED',row['issues'][0]['reasonCode'])
    def test_no_protein_requirement_does_not_weaken_explicit_contradiction(self):
        resolver=self.fixture.resolver()
        result=resolver.resolve_coding('EXAMPLE','c.2C>G','NM_000001.1')
        self.assertEqual('T1S',result['proteinChange'])
        with self.assertRaisesRegex(ValueError,'PROTEIN_CONSEQUENCE_MISMATCH'):
            resolver.resolve_coding('EXAMPLE','c.2C>G','NM_000001.1','T1A')

    def test_paired_paper_alias_uses_existing_clinvar_link_and_reference_not_mane_default(self):
        from test_literature_source_assertions import RECORD,VCV,PMID
        self.fixture.config['clinvar']=[{**self.fixture.put('clinvar.xml',RECORD),'accession':VCV}]
        c=self.case();c['paperPmid']=PMID;o=c['semantics']['observations'][0]
        o.update(targetRepresentation='c.123A>G',partnerRepresentation=None,reportedTranscripts=[],reportedProteins={'target':'T41A'})
        r=resolve_case(c,self.fixture.resolver())[0]
        self.assertEqual('LOCAL_IDENTITIES_VERIFIED',r['status']);self.assertEqual(123,r['identities']['target']['allele']['position'])
        self.assertTrue(r['identities']['target']['alias']['matches'][0]['pubmed'])
        c['paperPmid']='1';self.assertEqual({},resolve_case(c,self.fixture.resolver())[0]['identities'])
        c['paperPmid']=PMID;o['reportedProteins']['target']='T41G'
        self.assertEqual({},resolve_case(c,self.fixture.resolver())[0]['identities'])

    def test_reported_protein_is_checked_when_explicit_transcript_exists(self):
        c=self.case();c['semantics']['observations'][0]['reportedProteins']={'target':'T1A'}
        r=resolve_case(c,self.fixture.resolver())[0]
        self.assertNotIn('target',r['identities']);self.assertIn('partner',r['identities'])
        self.assertEqual('DENOVO_IDENTITY_PROTEIN_CONSEQUENCE_MISMATCH',r['issues'][0]['reasonCode'])

    def test_intronic_snv_resolves_on_both_strands(self):
        case = self.case()
        observation = case['semantics']['observations'][0]
        observation.update(targetRepresentation='c.1+4A>G', partnerRepresentation=None)
        forward = LocalFixture(Path(self.tmp.name), split=True)
        expected = {'assembly': 'GRCh38', 'contig': '1', 'position': 104, 'reference': 'A', 'alternate': 'G'}
        row = resolve_case(case, forward.resolver(), expected)[0]
        self.assertEqual('MATCHED', row['selectedTargetBinding'])
        self.assertEqual(expected, row['identities']['target']['allele'])
        self.assertNotIn('proteinChange', row['identities']['target'])

        reverse = LocalFixture(Path(self.tmp.name), strand='-')
        reverse.annotation['exons'] = [
            {'cdsStart': 1, 'cdsEnd': 1, 'genomicStart': 200, 'genomicEnd': 200},
            {'cdsStart': 2, 'cdsEnd': 3, 'genomicStart': 100, 'genomicEnd': 101},
        ]
        reverse.seal_annotation()
        observation['targetRepresentation'] = 'c.2-4T>C'
        expected['position'] = 105
        row = resolve_case(case, reverse.resolver(), expected)[0]
        self.assertEqual('MATCHED', row['selectedTargetBinding'])
        self.assertEqual(expected, row['identities']['target']['allele'])

    def test_intronic_snv_rejects_wrong_anchor_reference_and_out_of_range_offset(self):
        case = self.case()
        observation = case['semantics']['observations'][0]
        observation['partnerRepresentation'] = None
        fixture = LocalFixture(Path(self.tmp.name), split=True)
        for notation, reason in [
            ('c.2+4A>G', 'PM3_INTRONIC_BOUNDARY_UNRESOLVED'),
            ('c.1+100A>G', 'PM3_INTRONIC_OFFSET_OUT_OF_RANGE'),
            ('c.1+4C>G', 'PM3_INTRONIC_REFERENCE_MISMATCH'),
        ]:
            with self.subTest(notation=notation):
                observation['targetRepresentation'] = notation
                row = resolve_case(case, fixture.resolver())[0]
                self.assertEqual({}, row['identities'])
                self.assertEqual(reason, row['issues'][0]['reasonCode'])

if __name__=='__main__':unittest.main()
