"""Synthetic raw receipts, real replay/cache; no external model or network."""
import unittest
from unittest.mock import patch
from pathlib import Path
from model_literature_contract import canonical, parse
from pp1_reviewed_cache import register, restore, folder, target_key
from test_pp1_reading import Pipeline, Resolver
from test_pp1_segregation import selection, context, ledger
from process_pp1_paper import process


class ReviewedCache(unittest.TestCase):
    def test_discovery_exact_reviewed_target_only_and_tamper_visible(self):
        from pp1_reviewed_cache import discover
        from copy import deepcopy
        row=register(self.f.library,self.budget,[self.artifact])[0]
        result=discover(self.f.library,self.budget,self.f.target)
        self.assertEqual(['PMC123'],[r['pmcid'] for r in result['candidates']])
        self.assertEqual('LOCAL_REVIEWED_ARTIFACT',result['candidates'][0]['origins'][0]['kind'])
        wrong=deepcopy(self.f.target);wrong['geneSymbol']='OTHER'
        self.assertFalse(discover(self.f.library,self.budget,wrong)['candidates'])
        wrong=deepcopy(self.f.target);wrong['variantAliases']=['c.999A>T']
        self.assertFalse(discover(self.f.library,self.budget,wrong)['candidates'])
        value=parse(Path(row['path']).read_bytes());value['receipt']['responseSha256']='0'*64
        Path(row['path']).write_bytes(canonical(value))
        failed=discover(self.f.library,self.budget,self.f.target)
        self.assertFalse(failed['candidates']);self.assertIn('PP1_LOCAL_REVIEW_ENTRY_REJECTED',failed['issues'])
    def test_only_cosmetic_notation_equivalence_can_share_receipts(self):
        a={'geneSymbol':'GENE1','variantAliases':['c.1A > G','p(Glu1 = )']}
        b={'geneSymbol':'GENE1','variantAliases':['p.Glu1=','c.1A>G','p.(Glu1=)']}
        self.assertEqual(target_key(a),target_key(b))
        for other in [{'geneSymbol':'OTHER','variantAliases':b['variantAliases']},
                {'geneSymbol':'GENE1','variantAliases':['c.1A>C','p.Glu1=']},
                {'geneSymbol':'GENE1','variantAliases':['NM_123.1:c.1A>G','p.Glu1=']}]:
            self.assertNotEqual(target_key(a),target_key(other))

    def test_spaced_query_restores_original_without_resigning(self):
        register(self.f.library,self.budget,[self.artifact])
        target={**self.f.target,'variantAliases':[a.replace('>',' > ') for a in self.f.target['variantAliases']]}
        paths=restore(self.f.library,self.budget,'PMC123',self.f.pin,target,self.f.root/'spaced')
        self.assertEqual(paths[0].read_bytes(),self.artifact.read_bytes())

    def setUp(self):
        self.f=Pipeline();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.f.seed_artifacts()
        self.artifact=self.f.root/'seed-audit/artifact.json'
        self.budget=self.f.root/'budget'

    def test_restore_is_byte_exact_and_registration_idempotent(self):
        a=register(self.f.library,self.budget,[self.artifact]*2)
        self.assertEqual(a[0],a[1])
        paths=restore(self.f.library,self.budget,'PMC123',self.f.pin,self.f.target,self.f.root/'restored')
        self.assertEqual(len(paths),1);self.assertEqual(paths[0].read_bytes(),self.artifact.read_bytes())

    def test_review_is_reused_with_a_new_budget_and_legacy_copies_are_deduplicated(self):
        from pp1_reviewed_cache import discover
        item=register(self.f.library,self.budget,[self.artifact])[0]
        self.assertTrue(Path(item['path']).is_relative_to(self.f.library/'reviewed-evidence'/'PMC123'))
        old=folder(self.budget,'PMC123',self.f.pin,self.f.target)
        old.mkdir(parents=True)
        (old/Path(item['path']).name).write_bytes(self.artifact.read_bytes())
        fresh=self.f.root/'fresh-budget'
        self.assertEqual(len(discover(self.f.library,fresh,self.f.target)['candidates']),1)
        self.assertEqual(len(restore(self.f.library,fresh,'PMC123',self.f.pin,self.f.target,self.f.root/'new')),1)
        self.assertEqual(len(restore(self.f.library,self.budget,'PMC123',self.f.pin,self.f.target,self.f.root/'old')),1)

    def test_other_target_or_source_cannot_reuse(self):
        register(self.f.library,self.budget,[self.artifact])
        for pin,target in [('a'*64,self.f.target),(self.f.pin,{**self.f.target,'geneSymbol':'OTHER'})]:
            self.assertEqual(restore(self.f.library,self.budget,'PMC123',pin,target,self.f.root/'absent'),[])

    def test_changed_cached_bytes_rejected(self):
        item=register(self.f.library,self.budget,[self.artifact])[0]
        Path(item['path']).write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'PP1_REVIEW_CACHE_CHANGED'):
            restore(self.f.library,self.budget,'PMC123',self.f.pin,self.f.target,self.f.root/'bad')

    def test_extraction_is_not_a_review(self):
        with self.assertRaisesRegex(ValueError,'PP1_REVIEW_CACHE_AUDIT_REQUIRED'):
            register(self.f.library,self.budget,[self.f.root/'seed-extract/artifact.json'])

    def test_receipt_tampering_rejected_before_register(self):
        raw=parse(self.artifact.read_bytes());raw['receipt']['responseSha256']='a'*64
        path=self.f.root/'tampered.json';path.write_bytes(canonical(raw))
        with self.assertRaisesRegex(ValueError,'RESPONSE_CHANGED'):register(self.f.library,self.budget,[path])

    def test_paper_uses_reviewed_facts_and_current_context_without_initial_model(self):
        register(self.f.library,self.budget,[self.artifact])
        binding={'schemaVersion':'pp1-analysis-binding-v1','selection':selection(),'ledger':ledger()}
        with patch('process_pp1_paper.model_run',side_effect=AssertionError('INITIAL_MODEL_FORBIDDEN')):
            result=process(self.f.library,'PMC123',self.f.pin,self.f.target,binding,context(),None,
                self.f.config,self.f.cfg,self.budget,self.f.root/'paper',maximum_rounds=0,maximum_calls=0)
        self.assertEqual(result['reviewedCacheArtifacts'],1)
        self.assertEqual(result['modelAttempts'],0)
        self.assertEqual(result['status'],'EVALUATED')
        self.assertIn('needs',result['evaluation'])

    def test_cached_positive_is_not_reused_when_current_knowledge_is_unavailable(self):
        completed=self.f.loop([self.artifact],execute=True)
        self.assertEqual(completed['evaluation']['status'],'MET')
        register(self.f.library,self.budget,completed['acceptedArtifacts'])
        binding={'schemaVersion':'pp1-analysis-binding-v1','selection':selection(),'ledger':ledger()}
        with patch('process_pp1_paper.model_run',side_effect=AssertionError('INITIAL_MODEL_FORBIDDEN')):
            result=process(self.f.library,'PMC123',self.f.pin,self.f.target,binding,
                {'status':'UNAVAILABLE','reasonCode':'PP1_CONTEXT_UNAVAILABLE'},Resolver(),
                self.f.config,self.f.cfg,self.budget,self.f.root/'changed-context',maximum_rounds=0,maximum_calls=0)
        self.assertEqual(result['modelAttempts'],0)
        self.assertEqual(result['evaluation']['status'],'NOT_EVALUATED')
        self.assertTrue(any(n['reasonCode']=='PP1_CONTEXT_UNAVAILABLE' for n in result['evaluation']['needs']))


if __name__=='__main__':unittest.main()
