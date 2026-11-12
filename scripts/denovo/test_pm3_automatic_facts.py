from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from prepare_literature import prepare,sha
from model_literature_contract import canonical,digest
from test_core_context_review import LocalFixture
from pm3_source_aliases import crosswalks
from pm3_identity import resolve_case
from pm3_observations import ownership
from pm3_prose_facts import native_facts
from pm3_case_semantics import qualify
from pm3_disease_names import ExactDiseaseNames,URL,SET_ID


def source(body):
    raw=('<article><front><article-meta><article-id pub-id-type="pmcid">123</article-id><title-group><article-title>Synthetic study</article-title></title-group></article-meta></front>'
         '<body><sec><title>Results</title>'+body+'</sec></body></article>').encode()
    doc=prepare(raw,'PMC123',sha(raw))
    return {'main':doc,'raw':raw,'units':[{**u,'sourceSha256':sha(raw)} for u in doc['units']],
            'pmcid':'PMC123','bundleSha256':'a'*64}


def table(extra='',gene='EXAMPLE',protein='p.Thr1Ser',coding='c.2C&gt;G',tx='NM_000001.1'):
    return ('<table-wrap id="t"><caption><p>'+gene+' variants</p></caption><table><thead><tr>'
        '<th>Protein change</th><th>cDNA change</th><th>Transcript</th></tr></thead><tbody>'
        '<tr><td>'+protein+'</td><td>'+coding+'</td><td>'+tx+'</td></tr>'+extra+'</tbody></table></table-wrap>')


class SourceIdentity(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.f=LocalFixture(Path(self.temp.name))
    def case(self,s):
        return {'caseLabel':'1','sourceAlleleCrosswalks':crosswalks(s),'semantics':{'observations':[{
            'geneSymbol':'EXAMPLE','targetRepresentation':'p.T1S','partnerRepresentation':None,'reportedTranscripts':[]}]}}
    def test_source_crosswalk_resolves_with_reference_not_selected_key(self):
        c=self.case(source(table()));r=resolve_case(c,self.f.resolver())[0]
        self.assertEqual('LOCAL_IDENTITIES_VERIFIED',r['status'])
        self.assertEqual(101,r['identities']['target']['allele']['position'])
        self.assertGreater(len(r['identities']['target']['sourceProteinCrosswalk'][0]['citations']),3)
        r=resolve_case(c,self.f.resolver(),{'wrong':'key'})[0]
        self.assertEqual('PM3_SELECTED_TARGET_IDENTITY_MISMATCH',r['issues'][0]['reasonCode'])
    def test_two_possible_dna_records_never_choose_convenient_candidate(self):
        extra='<tr><td>p.Thr1Ser</td><td>c.1A&gt;T</td><td>NM_000001.1</td></tr>'
        r=resolve_case(self.case(source(table(extra))),self.f.resolver())[0]
        self.assertEqual('PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_AMBIGUOUS',r['issues'][0]['reasonCode'])
    def test_crosswalk_ref_gene_and_transcript_contradictions(self):
        for change,expected in [({'coding':'c.2A&gt;G'},'CODING_REFERENCE_MISMATCH'),
                                ({'coding':'c.2C&gt;T'},'PROTEIN_CONSEQUENCE_MISMATCH'),
                                ({'tx':'NM_999999.1'},'EXACT_MANE_MAPPING_UNAVAILABLE')]:
            r=resolve_case(self.case(source(table(**change))),self.f.resolver())[0]
            self.assertTrue(r['issues'][0]['reasonCode'].endswith(expected))
        self.assertEqual({},resolve_case(self.case(source(table(gene='OTHER'))),self.f.resolver())[0]['identities'])
    def test_malformed_or_merged_alias_table_is_not_used(self):
        for html in [table().replace('Protein change','Possibly protein change'),
                     table().replace('<td>p.','<td rowspan="2">p.')]:
            self.assertEqual([],crosswalks(source(html)))
        for html in [table(tx='unknown'),table(coding='not c.2C&gt;G')]:
            c=self.case(source(html));self.assertEqual('UNRESOLVED',c['sourceAlleleCrosswalks'][0]['qualification'])
            self.assertEqual('PM3_PROTEIN_SOURCE_CODING_LINK_UNRESOLVED',resolve_case(c,self.f.resolver())[0]['issues'][0]['reasonCode'])
    def test_incomplete_alternative_not_silently_dropped(self):
        extra='<tr><td>p.Thr1Ser</td><td>c.1A&gt;T</td><td>unknown</td></tr>'
        r=resolve_case(self.case(source(table(extra))),self.f.resolver())[0]
        self.assertEqual({},r['identities'])
        self.assertEqual('PM3_PROTEIN_SOURCE_CODING_LINK_UNRESOLVED',r['issues'][0]['reasonCode'])
    def test_explicit_transcript_conflict_not_overwritten_by_mapping(self):
        c=self.case(source(table()));c['semantics']['observations'][0]['reportedTranscripts']=['NM_000001.2']
        self.assertEqual('PM3_TRANSCRIPT_CONFLICT',resolve_case(c,self.f.resolver())[0]['issues'][0]['reasonCode'])


class Prose(unittest.TestCase):
    text='Patient 1 carried compound heterozygous variants NM_000001.1:c.2C&gt;G and NM_000001.1:c.3C&gt;T in EXAMPLE.'
    target={'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']}
    def result(self,text):
        s=source('<p>'+text+'</p>');facts=native_facts(s,ownership(s,'1'),'1',self.target)
        return qualify({'caseLabel':'1','facts':facts},self.target)
    def test_complete_case_sentence_plus_diagnosis_keeps_phase_unknown(self):
        r=self.result(self.text+' Patient 1 was diagnosed with Test disorder.')
        o=r['observations'][0];self.assertEqual('UNKNOWN',o['phase'])
        self.assertEqual(['Test disorder'],o['reportedDiagnoses']);self.assertEqual('AFFECTED',o['reportedAffectedStatus'])
        self.assertTrue(all(f['citation']['unitId'] for f in o['facts']))
    def test_homozygous_prose_does_not_need_partner(self):
        o=self.result('Patient 1 was homozygous for NM_000001.1:c.2C&gt;G in EXAMPLE.')['observations'][0]
        self.assertEqual('REPORTED_HOMOZYGOUS',o['kind'])
    def test_negation_qualifiers_other_case_and_unsupported_tails_rejected(self):
        for text in [self.text.replace('carried','possibly carried'),self.text.replace('Patient 1','Patient 2'),
                     self.text+' This finding was not confirmed.',self.text+' Patient 2 was diagnosed with Test disorder.',
                     self.text+' Patient 1 was diagnosed with possible Test disorder.',self.text.replace('EXAMPLE','OTHER')]:
            self.assertEqual([],self.result(text)['observations'])
    def test_reviewed_case_flows_through_collect_and_identity_without_reextracting(self):
        from unittest.mock import patch
        from pm3_observations import collect
        s=source('<p>'+self.text+'</p>');unit=next(u for u in s['units'] if u['kind']=='BODY_PARAGRAPH')
        checked={'criterion':'PM3','pmcid':'PMC123','bundleSha256':'a'*64,'extractionCoverageProposal':'EXTRACTED_WITHIN_TARGET',
            'cases':[{'caseLabel':'1','caseCitation':{'unitId':unit['unitId'],'quote':unit['text']},
                'caseOriginProposal':'UNCLEAR','originCitations':[],'fields':[]}]}
        artifact={'binding':{'pmcid':'PMC123','bundleSha256':'a'*64,'target':self.target,'chunkIndex':0,'chunkCount':1,'stage':'SOURCE_REVIEW'},'review':checked}
        with patch('run_pm3_literature_model.replay',return_value=artifact),patch('pm3_observations.load',return_value=s):
            result=collect('synthetic-library',['synthetic-artifact'])
        c=result['cases'][0]
        self.assertEqual('PROPOSED',c['fields']['targetVariant']['status'])
        with tempfile.TemporaryDirectory() as t:
            r=resolve_case(c,LocalFixture(Path(t)).resolver())[0]
            self.assertEqual('LOCAL_IDENTITIES_VERIFIED',r['status'])
            self.assertEqual('UNKNOWN',r['reportedObservation']['phase'])
        # A source review removing a case cannot be replaced by native discovery.
        artifact['review']={**checked,'cases':[]}
        with patch('run_pm3_literature_model.replay',return_value=artifact),patch('pm3_observations.load',return_value=s):
            self.assertEqual([],collect('synthetic-library',['synthetic-artifact'])['cases'])


class Names(unittest.TestCase):
    def mapping(self,root,rows):
        version='http://purl.obolibrary.org/obo/mondo/releases/2026-10-06/mappings/mondo_exactmatch_orphanet.sssom.tsv'
        raw=('# mapping_set_id: '+SET_ID+'\n# mapping_set_version: '+version+'\n'
          'subject_id\tsubject_label\tpredicate_id\tobject_id\tobject_label\tmapping_justification\n'+rows).encode()
        (root/'local').mkdir(exist_ok=True);(root/'local/mapping.tsv').write_bytes(raw)
        return {'path':'local/mapping.tsv','sha256':digest(raw),'sourceUrl':URL,'version':version}
    def test_only_exact_mapping_with_provenance_not_fuzzy_or_broad(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);spec=self.mapping(root,'MONDO:0000001\tFirst disease\tskos:exactMatch\tOrphanet:1\tOld disease name\tsemapv:ManualMappingCuration\n'
              'MONDO:0000002\tSecond disease\tskos:broadMatch\tOrphanet:2\tBroad disease\tsemapv:ManualMappingCuration\n')
            n=ExactDiseaseNames(root,spec)
            self.assertEqual('MONDO:0000001',n.lookup(' Old   disease name ')[0]['mapping']['subject_id'])
            self.assertEqual([],n.lookup('suspected Old disease name'));self.assertEqual([],n.lookup('Broad disease'))
            self.assertEqual(spec['sha256'],n.lookup('Orphanet:1')[0]['source']['sha256'])
            with self.assertRaisesRegex(ValueError,'SOURCE_BINDING'):
                ExactDiseaseNames(root,{**spec,'version':'wrong'})
    def test_global_ambiguous_name_does_not_select_by_gene(self):
        from pm3_disease_context import DiseaseContext
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);spec=self.mapping(root,''.join(f'MONDO:000000{i}\tDisease {i}\tskos:exactMatch\tOrphanet:{i}\tShared name\tsemapv:ManualMappingCuration\n' for i in (1,2)))
            supply=DiseaseContext.__new__(DiseaseContext);supply.names=ExactDiseaseNames(root,spec);supply.source={};supply.subclasses=None
            supply.rows=[{'hgncId':'HGNC:1','symbol':'EXAMPLE','mondoId':'MONDO:0000001','diseaseLabel':'Disease 1','moi':'AR','classification':'Definitive'}]
            ctx=supply.qualify({'reportedDiagnoses':['Shared name'],'reportedAffectedStatus':'AFFECTED'},{'hgncId':'HGNC:1','geneSymbol':'EXAMPLE'})
            self.assertEqual('CONFLICT',ctx['qualification'])
            ctx=supply.qualify({'reportedDiagnoses':['Orphanet:1'],'reportedAffectedStatus':'AFFECTED'},{'hgncId':'HGNC:1','geneSymbol':'EXAMPLE'})
            self.assertEqual('VERIFIED',ctx['qualification'])


if __name__=='__main__':unittest.main()
