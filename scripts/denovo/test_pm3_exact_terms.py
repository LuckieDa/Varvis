"""Generic exact-term adoption: synthetic distinct diseases and conflict controls."""
import unittest
from copy import deepcopy
from test_pm3_mondo_context import MondoContext
from pm3_disease_names import ExactMondoTerms, CombinedDiseaseNames
from pm3_mondo_context import BASE


class ExactTerms(MondoContext):
    def exact(self, terms=None):
        entries=[]
        for i,term in enumerate(terms or [self.child]):
            entries.append({'term':self.write(f'exact-{i}.json',{'_embedded':{'terms':[term]},
                'page':{'number':0,'totalPages':1,'totalElements':1}},BASE+'/terms?obo_id='+term['obo_id']),
                'metadata':self.write('meta.json',self.metadata,BASE)})
        return ExactMondoTerms(self.root,{'entries':entries})

    def test_exact_synonym_qualifies_without_parent_mapping(self):
        d=self.context(None);d.names=self.exact();d.rows[0].update(mondoId='MONDO:0000002')
        r=d.qualify({'reportedDiagnoses':['  med  '],'reportedAffectedStatus':'AFFECTED'},
                    {'hgncId':'HGNC:1','geneSymbol':'TEST'})
        self.assertEqual('VERIFIED',r['qualification'])
        self.assertEqual('MONDO:0000002',r['diseaseId'])
        self.assertNotIn('diseaseScopeMapping',r)
        self.assertEqual([],d.names.lookup('related disorder'))
        self.assertEqual([],d.names.lookup('suspected MED'))

    def test_disease_alias_conflict_is_not_resolved_using_target_gene(self):
        second=deepcopy(self.parent);second['obo_synonym']=deepcopy(self.child['obo_synonym'])
        d=self.context(None);d.names=CombinedDiseaseNames([self.exact([self.child,second])])
        r=d.qualify({'reportedDiagnoses':['MED'],'reportedAffectedStatus':'AFFECTED'},
                    {'hgncId':'HGNC:1','geneSymbol':'TEST'})
        self.assertEqual('CONFLICT',r['qualification'])

    def test_obsolete_and_metadata_mismatch_rejected(self):
        self.child['is_obsolete']=True
        with self.assertRaises(ValueError):self.exact()
        self.child['is_obsolete']=False;self.metadata['config']['version']='2025-01-01'
        with self.assertRaises(ValueError):self.exact()


if __name__=='__main__':unittest.main()
