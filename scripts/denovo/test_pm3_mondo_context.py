import tempfile
import unittest
from pathlib import Path
from copy import deepcopy
from urllib.parse import quote
from model_literature_contract import canonical,digest
from pm3_mondo_context import DirectSubclassContext,BASE,IRI
from pm3_disease_context import DiseaseContext


class MondoContext(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/'local').mkdir()
        def term(ident,label):
            return {'obo_id':ident,'iri':IRI+ident.replace(':','_'),'label':label,
                'ontology_name':'mondo','is_defining_ontology':True,'is_obsolete':False,
                'term_replaced_by':None,'obo_synonym':[]}
        self.child=term('MONDO:0000002','Mild example disorder')
        self.child['obo_synonym']=[{'name':'MED','scope':'hasExactSynonym'},
                                   {'name':'related disorder','scope':'hasRelatedSynonym'}]
        self.url=BASE+'/terms/'+quote(quote(self.child['iri'],safe=''),safe='')+'/parents'
        self.child['_links']={'parents':{'href':self.url}}
        self.parent=term('MONDO:0000001','Example disorder')
        self.metadata={'ontologyId':'mondo','status':'LOADED','version':'2026-10-06',
            'config':{'version':'2026-10-06','versionIri':'http://purl.obolibrary.org/obo/mondo/releases/2026-10-06/mondo-international.owl'}}

    def write(self,name,value,url):
        raw=canonical(value);(self.root/'local'/name).write_bytes(raw)
        return {'path':'local/'+name,'sha256':digest(raw),'sourceUrl':url}

    def reader(self,parents=None):
        page=lambda rows:{'_embedded':{'terms':rows},'page':{'number':0,'totalPages':1,'totalElements':len(rows)}}
        config={'policy':'EXPLICIT_DIRECT_MONDO_SUBCLASS_SAME_GENE_AR_V1','entries':[{
            'term':self.write('term.json',page([self.child]),BASE+'/terms?obo_id=MONDO%3A0000002'),
            'parents':self.write('parents.json',page(parents or [self.parent]),self.url),
            'metadata':self.write('metadata.json',self.metadata,BASE)}]}
        return DirectSubclassContext(self.root,config)

    def context(self,reader):
        d=object.__new__(DiseaseContext);d.names=None;d.subclasses=reader;d.source={'scope':'SYNTHETIC_TEST'}
        d.rows=[{'hgncId':'HGNC:1','symbol':'TEST','mondoId':'MONDO:0000001',
                 'diseaseLabel':'Example disorder','moi':'AR','classification':'Definitive'}]
        return d

    def test_exact_alias_and_subclass_preserved_not_equivalence(self):
        d=self.context(self.reader());r=d.qualify({'reportedDiagnoses':['MED'],'reportedAffectedStatus':'AFFECTED'},
                                                 {'hgncId':'HGNC:1','geneSymbol':'TEST'})
        self.assertEqual('VERIFIED',r['qualification']);self.assertEqual('MONDO:0000001',r['diseaseId'])
        self.assertEqual(['MONDO:0000002'],r['reportedDiseaseIds'])
        self.assertFalse(r['diseaseScopeMapping']['matches'][0]['equivalent'])

    def test_related_names_and_qualifiers_never_become_exact(self):
        reader=self.reader()
        self.assertEqual([],reader.lookup('related disorder'));self.assertEqual([],reader.lookup('suspected MED'))

    def test_context_still_requires_affected_exact_gene_and_ar(self):
        d=self.context(self.reader());case={'reportedDiagnoses':['MED'],'reportedAffectedStatus':'AFFECTED'}
        identity={'hgncId':'HGNC:1','geneSymbol':'TEST'}
        for status in ['UNKNOWN','UNAFFECTED']:
            self.assertNotEqual('VERIFIED',d.qualify({**case,'reportedAffectedStatus':status},identity)['qualification'])
        self.assertNotEqual('VERIFIED',d.qualify(case,{**identity,'geneSymbol':'OTHER'})['qualification'])
        d.rows[0]['moi']='AD';self.assertNotEqual('VERIFIED',d.qualify(case,identity)['qualification'])

    def test_obsolete_wrong_term_and_incomplete_parent_page_rejected(self):
        original=deepcopy(self.child)
        for mutation in [{'is_obsolete':True},{'ontology_name':'other'},{'term_replaced_by':'MONDO:0000001'},
                         {'obo_id':'MONDO:0000003'}]:
            self.child={**original,**mutation}
            with self.assertRaises(ValueError):self.reader()
        self.child=original
        from pm3_mondo_context import page_terms
        with self.assertRaises(ValueError):page_terms({'_embedded':{'terms':[self.parent]},
            'page':{'number':0,'totalPages':2,'totalElements':2}})

    def test_multiple_qualified_parent_contexts_are_not_chosen_by_convenience(self):
        second={**self.parent,'obo_id':'MONDO:0000003','iri':IRI+'MONDO_0000003'}
        d=self.context(self.reader([self.parent,second]));d.rows.append({**d.rows[0],'mondoId':'MONDO:0000003'})
        r=d.qualify({'reportedDiagnoses':['MED'],'reportedAffectedStatus':'AFFECTED'},
                    {'hgncId':'HGNC:1','geneSymbol':'TEST'})
        self.assertEqual('CONFLICT',r['qualification'])

    def test_exact_child_relationship_precedes_parent_and_keeps_its_inheritance(self):
        d=self.context(self.reader())
        d.rows.append({**d.rows[0],'mondoId':'MONDO:0000002','diseaseLabel':'Mild example disorder','moi':'AD'})
        r=d.qualify({'reportedDiagnoses':['MED'],'reportedAffectedStatus':'AFFECTED'},
                    {'hgncId':'HGNC:1','geneSymbol':'TEST'})
        self.assertEqual('PM3_RECESSIVE_CONTEXT_NOT_ESTABLISHED',r['reasonCode'])
        self.assertNotIn('diseaseScopeMapping',r)


if __name__=='__main__':unittest.main()
