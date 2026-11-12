import copy
import json
import pathlib
import tempfile
import unittest
import prepare_applicability_sources as mod
from test_assemble_materials import AssemblyTest


def entity():
    return {'entId':'GN900','entContent':{'version':'1.0.0','states':[{'name':'Released','current':True}],
             'notes':'TEST_ONLY: alternative transcript may rescue; see attachment'},
        'ld':{'Gene':[{'entContent':{'HGNC':{'symbol':'TEST','hgnc_id':'HGNC:123'}}}],
              'RuleSet':[{'entContent':{'genes':[{'gene':'TEST','diseases':[{'preferredMondoId':'MONDO:0000001',
                 'preferredModeOfInheritance':'Autosomal dominant inheritance'}], 'preferredTranscript':'NM_000001.2'}]}}],
              'CriteriaCode':[{'entContent':{'label':'PVS1','gene':['TEST'],'disease':['MONDO:0000001'],
                  'instructionsToUse':'Not for every exon. NM_000001.2 is an example, NM_000002 lacks a version.',
                  'strengthDescriptor':[{'strength':'Very Strong','applicability':'Not applicable','text':'See external decision tree'}]}},
                  {'entContent':{'label':'PS3','additionalComments':'Do not combine with PVS1'}}],
              'File':[{'fileName':'tree.pdf','url':'https://example.org/tree.pdf'}]}}


class ApplicabilityTest(unittest.TestCase):
    def setUp(self):
        self.fixture=AssemblyTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.root=self.fixture.root
        plan=__import__('assemble_materials').plan(self.fixture.config)
        self.assembly=__import__('assemble_materials').finish(plan,'a'*64,self.fixture.batch(plan))
        self.e=entity();self.update()

    def update(self):
        raw={'data':[self.e]};source=self.fixture.write('raw.json',raw)
        ref={'rawRef':'raw.json','rawSha256':source['sha256'],'jsonPointer':'/data/0','sourceUrl':'https://example.org/GN900'}
        cat={'schemaVersion':'a01-ba1-cspec-snapshot-v1','metadata':{'status':'COMPLETE','selectedReleasedCount':1},
             'entries':[{'specificationId':'GN900','rawRef':'raw.json','rawSha256':source['sha256'],'source':ref}]}
        cat['sha256']=mod.digest(cat)
        self.config={'schemaVersion':'pvs1-applicability-supply-input-v1','sources':{
            'assembly':self.fixture.write('assembly.json',self.assembly),'catalog':self.fixture.write('catalog.json',cat)},'captureDirectory':str(self.root)}

    def result(self):return mod.compile_sources(self.config)

    def test_complete_graph_and_negative_clause_preserved_without_adoption(self):
        r=self.result();self.assertEqual(self.e,r['specifications'][0]['original'])
        self.assertEqual(1,r['counts']['backgroundsWithExactScopedSource']);self.assertEqual(0,r['counts']['newlyAdoptedBackgrounds'])
        self.assertFalse(r['runtimeEligible']);self.assertFalse(r['clinicalAcceptance'])
        self.assertEqual('Not applicable',r['specifications'][0]['original']['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['applicability'])

    def test_missing_inheritance_does_not_inherit_g2p(self):
        self.e['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0].pop('preferredModeOfInheritance');self.update();r=self.result()
        self.assertEqual('INHERITANCE_NOT_PROVIDED',r['entries'][0]['scopeStatus']);self.assertEqual([],r['backgrounds'][0]['exactEntryIds'])
        self.assertEqual(1,len(r['backgrounds'][0]['unresolvedEntryIds']))

    def test_other_inheritance_is_not_fuzzy_matched(self):
        self.e['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance']='Autosomal recessive inheritance';self.update();b=self.result()['backgrounds'][0]
        self.assertEqual([],b['exactEntryIds']);self.assertEqual(1,len(b['differentInheritanceEntryIds']))

    def test_other_disease_cannot_borrow_source(self):
        self.e['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredMondoId']='MONDO:0000002';self.e['ld']['CriteriaCode'][0]['entContent']['disease']=['MONDO:0000002'];self.update()
        b=self.result()['backgrounds'][0];self.assertEqual('NO_MATCH_IN_LOADED_SPECIALIST_SNAPSHOT',b['status'])

    def test_clause_disagrees_with_ruleset_is_unresolved(self):
        self.e['ld']['CriteriaCode'][0]['entContent']['disease']=['MONDO:0000002'];self.update();r=self.result()
        self.assertEqual('CLAUSE_SCOPE_UNRESOLVED',r['entries'][0]['scopeStatus']);self.assertEqual([],r['backgrounds'][0]['exactEntryIds'])

    def test_missing_clause_disease_preserves_parent_context_not_unrestricted_scope(self):
        self.e['ld']['CriteriaCode'][0]['entContent'].pop('disease');self.update();r=self.result()
        self.assertEqual('EXACT',r['entries'][0]['scopeStatus'])
        self.assertEqual('RULESET_DISEASE_CONTEXT_ONLY',r['entries'][0]['clauseScopeRelation'])
        self.assertFalse(r['runtimeEligible'])

    def test_transcript_versions_and_examples_are_not_relevance(self):
        r=self.result();mentions=r['specifications'][0]['transcriptMentions']
        self.assertTrue(any(m['accession']=='NM_000001.2' and m['versionSpecified'] for m in mentions))
        self.assertTrue(any(m['accession']=='NM_000002' and not m['versionSpecified'] for m in mentions))
        for m in mentions:
            self.assertEqual(m['accession'],mod.pointer(self.e,m['jsonPointer'])[m['characterStart']:m['characterEnd']])
            self.assertEqual('MENTION_ONLY_NOT_TRANSCRIPT_RELEVANCE',m['role'])

    def test_new_disease_is_data_only_and_both_source_clauses_survive(self):
        self.e['ld']['CriteriaCode'].append(copy.deepcopy(self.e['ld']['CriteriaCode'][0]));self.update();r=self.result()
        self.assertEqual(2,len(r['entries']));self.assertEqual(2,len(r['backgrounds'][0]['exactEntryIds']))

    def test_curated_and_mined_publications_stay_separate(self):
        records=[{'recordId':'test','sourceRecordSha256':'f'*64,'sourceRecord':{'molecular mechanism support':'inferred',
                  'publications':'123; not-a-pmid','additional mined publications':'456','comments':'Only specific domains'}}]
        r=mod.evidence_details(records)[0];self.assertEqual('inferred',r['support']);self.assertEqual('123',r['curatedPublications'][0]['pmid'])
        self.assertIsNone(r['curatedPublications'][1]['pmid']);self.assertEqual('456',r['minedPublicationLeads'][0]['pmid']);self.assertEqual('Only specific domains',r['comments'])

    def test_modified_raw_source_is_rejected(self):
        (self.root/'raw.json').write_text('{}');self.assertRaisesRegex(ValueError,'INPUT_SHA_MISMATCH',self.result)

    def test_unreleased_source_is_rejected(self):
        self.e['entContent']['states'][0]['name']='Draft';self.update();self.assertRaisesRegex(ValueError,'SOURCE_NOT_RELEASED',self.result)

    def test_source_set_count_or_catalog_seal_cannot_be_faked(self):
        f=self.root/'catalog.json';cat=json.loads(f.read_text());cat['metadata']['selectedReleasedCount']=2
        self.config['sources']['catalog']=self.fixture.write('catalog.json',cat);self.assertRaisesRegex(ValueError,'SOURCE_CATALOG_INVALID',self.result)
        cat['sha256']=mod.digest({k:v for k,v in cat.items() if k!='sha256'});self.config['sources']['catalog']=self.fixture.write('catalog.json',cat)
        self.assertRaisesRegex(ValueError,'SOURCE_SPECIFICATION_SET_INCOMPLETE',self.result)

    def test_ambiguous_gene_id_rejected(self):
        self.e['ld']['Gene'].append({'entContent':{'HGNC':{'symbol':'TEST','hgnc_id':'HGNC:456'}}});self.update()
        self.assertRaisesRegex(ValueError,'CONFLICTING_SCOPE_GENE_IDENTITY',self.result)

    def test_path_escape_rejected(self):
        self.assertRaisesRegex(ValueError,'RESOURCE_PATH_INVALID',mod.child,self.root,'../raw.json')

    def test_output_never_overwrites(self):
        mod.write_new(self.root/'result.json',self.result());self.assertRaises(FileExistsError,mod.write_new,self.root/'result.json',{})


if __name__=='__main__':unittest.main()
