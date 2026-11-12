from copy import deepcopy
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch
import unittest
from literature_context_options import options,supply_inheritance,published_modes,resolve_inheritance
from test_pp1_specification_context import specification

class ContextOptions(unittest.TestCase):
    def test_supplementary_resource_failure_does_not_erase_explicit_published_moi(self):
        a=specification();b=deepcopy(a)
        b['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0].update(
            preferredMondoId='MONDO:0000002', preferredModeOfInheritance='Autosomal Dominant')
        with patch('literature_context_options.SpecificationContext',return_value=SimpleNamespace(specifications=[a,b],source={})), \
             patch('pm3_disease_context.DiseaseContext',side_effect=ValueError('BAD_DIGEST')):
            v=options(Path('.'),{'pp1Knowledge':{'kind':'PUBLISHED_CSPEC'},'pm3DiseaseContext':{'configured':True}})
        self.assertEqual(['RESOURCE_UNAVAILABLE','VERIFIED'],[c['inheritanceResolution']['status'] for c in v['contexts']])
        self.assertEqual(['AD'],v['contexts'][1]['publishedInheritance'])
    def test_structured_inheritance_and_unmapped_terms_remain_distinct(self):
        self.assertEqual((['AD'], 'VERIFIED'), published_modes({'modesOfInheritance':[{'modeOfInheritance':'Autosomal dominant inheritance'}]}))
        self.assertEqual((['XLR'], 'VERIFIED'), published_modes({'preferredModeOfInheritance':'X-linked recessive inheritance'}))
        self.assertEqual(([], 'MISSING'), published_modes({}))
        self.assertEqual(([], 'UNMAPPED'), published_modes({'preferredModeOfInheritance':'not mapped'}))
        self.assertEqual('CONFLICT', published_modes({'preferredModeOfInheritance':'Autosomal Dominant',
            'modesOfInheritance':[{'modeOfInheritance':'Autosomal Recessive'}]})[1])
    def test_independent_published_modes_remain_separate_valid_backgrounds(self):
        item={'publishedInheritance':['AD','AR'],'sources':[{'inheritanceDeclaration':{'modes':[m],'status':'VERIFIED'}} for m in ['AD','AR']]}
        resolve_inheritance(item)
        self.assertEqual(['AD','AR'],item['publishedInheritance'])
        self.assertEqual('VERIFIED',item['inheritanceResolution']['status'])
        self.assertTrue(item['inheritanceResolution']['automaticSelectionAllowed'])
    def test_unmapped_scope_is_retained_with_source_not_silently_dropped(self):
        s=specification();s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance']='unsupported term'
        with patch('literature_context_options.SpecificationContext',return_value=SimpleNamespace(specifications=[s],source={})):
            v=options(Path('.'),{'pp1Knowledge':{'kind':'PUBLISHED_CSPEC'}})
        self.assertEqual(1,len(v['contexts']))
        self.assertEqual('UNMAPPED',v['contexts'][0]['inheritanceResolution']['status'])
        self.assertTrue(v['contexts'][0]['sources'][0]['scopePointer'])
    def test_exact_identity_supply_preserves_source_and_does_not_infer_patient(self):
        item={'hgncId':'HGNC:1','geneSymbol':'ONE','mondoId':'MONDO:0000001','publishedInheritance':[],'sources':[]}
        row={'hgncId':'HGNC:1','symbol':'ONE','mondoId':'MONDO:0000001','moi':'AD','classification':'Definitive'}
        source=SimpleNamespace(rows=[row],source={'catalogSha256':'a'*64})
        supply_inheritance(item,source)
        self.assertEqual(['AD'],item['publishedInheritance'])
        self.assertEqual('/0',item['inheritanceSupply']['matches'][0]['catalogPointer'])
        for change in [{'mondoId':'MONDO:0000002'},{'hgncId':'HGNC:2'},{'symbol':'OTHER'}]:
            unknown={**item,'publishedInheritance':[],'sources':[]}
            supply_inheritance(unknown,SimpleNamespace(rows=[{**row,**change}],source={}))
            self.assertEqual([],unknown['publishedInheritance'])
    def test_weak_relationships_do_not_supply_but_exact_alternative_modes_remain_distinct(self):
        row={'hgncId':'HGNC:1','symbol':'ONE','mondoId':'MONDO:0000001','moi':'AD','classification':'Strong'}
        for rows in [[{**row,'classification':'Disputed'}],[{**row,'moi':''}]]:
            item={'hgncId':'HGNC:1','geneSymbol':'ONE','mondoId':'MONDO:0000001','publishedInheritance':[],'sources':[]}
            supply_inheritance(item,SimpleNamespace(rows=rows,source={}))
            self.assertEqual([],item['publishedInheritance'])
            self.assertEqual('UNRESOLVED',item['inheritanceSupply']['status'])
        item['publishedInheritance']=['AR']
        supply_inheritance(item,SimpleNamespace(rows=[row],source={}))
        self.assertEqual(['AD','AR'],item['publishedInheritance'])
        self.assertEqual('VERIFIED',item['inheritanceSupply']['status'])
    def test_multiple_genes_diseases_and_missing_moi_not_inferred(self):
        a=specification();b=deepcopy(a);b['specificationKey']='GN99998@1.0'
        b['original']['ld']['Gene'][0]['entContent']['HGNC']={'symbol':'OTHER','hgnc_id':'HGNC:2'}
        g=b['original']['ld']['RuleSet'][0]['entContent']['genes'][0];g['gene']='OTHER'
        g['diseases'][0].update(preferredMondoId='MONDO:0000002',preferredTitle='Other disease',preferredModeOfInheritance='Autosomal Recessive')
        with patch('literature_context_options.SpecificationContext',return_value=SimpleNamespace(specifications=[a,b],source={'scope':'TEST'})):
            v=options(Path('.'),{'pp1Knowledge':{'kind':'PUBLISHED_CSPEC'}})
        self.assertEqual(2,len(v['contexts']));self.assertEqual([],v['contexts'][0]['publishedInheritance'])
        self.assertEqual(['AR'],v['contexts'][1]['publishedInheritance'])
        self.assertTrue(all(c['sources'] and len(c['contextId'])==64 for c in v['contexts']))
        self.assertFalse(v['clinicalApproval']);self.assertEqual(0,v['networkRequests'])
    def test_unmapped_or_missing_identity_excluded_and_source_failure_not_hidden(self):
        s=specification();s['original']['ld']['Gene']=[]
        with patch('literature_context_options.SpecificationContext',return_value=SimpleNamespace(specifications=[s],source={})):
            self.assertFalse(options(Path('.'),{'pp1Knowledge':{'kind':'PUBLISHED_CSPEC'}})['contexts'])
        with patch('literature_context_options.SpecificationContext',side_effect=ValueError('SOURCE_CHANGED')):
            with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):options(Path('.'),{'pp1Knowledge':{'kind':'PUBLISHED_CSPEC'}})

if __name__=='__main__':unittest.main()
