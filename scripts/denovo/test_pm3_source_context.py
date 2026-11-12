import unittest
from copy import deepcopy
from prepare_literature import prepare,sha
from pm3_observations import ownership,project
from pm3_source_context import table_context_facts
from pm3_case_semantics import qualify


class TableContext(unittest.TestCase):
    def source(self,caption='GENE1 genotypes. Variants are reported relative to NM_000001.1.'):
        raw=('''<article><front><article-meta><article-id pub-id-type="pmcid">123</article-id>
            <article-id pub-id-type="pmid">456</article-id><title-group><article-title>Study</article-title></title-group>
            </article-meta></front><body><table-wrap id="t1"><caption><p>'''+caption+'''</p></caption>
            <table><thead><tr><th>PtID</th><th>Mutation 1</th><th>Mutation 2</th></tr></thead>
            <tbody><tr><td>148</td><td>c.123A&gt;G</td><td>c.456C&gt;T</td></tr></tbody></table></table-wrap></body></article>''').encode()
        doc=prepare(raw,'PMC123',sha(raw))
        return {'main':doc,'units':[{**u,'sourceSha256':sha(raw)} for u in doc['units']],
                'pmcid':'PMC123','bundleSha256':'a'*64}

    def test_native_caption_needs_no_second_model_extraction(self):
        s=self.source();scope=ownership(s,'148');facts=table_context_facts(s,scope)
        self.assertEqual(['geneSymbol','transcript'],[f['field'] for f in facts])
        self.assertTrue(all(f['producer']=='SOURCE_NATIVE_TABLE_CONTEXT' and f['citation']['sourceSha256'] for f in facts))
        # An existing reviewed case receives these source-native facts without
        # editing its original model response or assuming clinical diagnosis.
        checked={'criterion':'PM3','pmcid':'PMC123','bundleSha256':'a'*64,'extractionCoverageProposal':'EXTRACTED_WITHIN_TARGET',
          'cases':[{'caseLabel':'148','caseCitation':{'unitId':next(k for k,v in scope.items() if v.get('cellText')=='148')},
                    'caseOriginProposal':'UNCLEAR','originCitations':[],'fields':[]}]}
        result=project(s,[checked]);self.assertEqual('456',result['cases'][0]['paperPmid'])
        self.assertEqual(2,len(result['cases'][0]['facts']));self.assertEqual([],checked['cases'][0]['fields'])
        self.assertEqual([],qualify(result['cases'][0],{'geneSymbol':'GENE1','variantAliases':['c.123A>G']})['observations'])

    def test_caption_cannot_create_case_or_borrow_other_scope(self):
        s=self.source();self.assertEqual([],table_context_facts(s,ownership(s,'999')))
        s=self.source('Variants of GENE1 and GENE2; NM_000001.1.')
        self.assertEqual([],table_context_facts(s,ownership(s,'148')))

    def test_transcript_conflict_or_mention_without_explicit_relation_stays_missing(self):
        for caption in ('GENE1 genotypes. See NM_000001.1.',
                        'GENE1 genotypes. Variants are reported relative to NM_000001.1. Alternative NM_000002.2.'):
            s=self.source(caption);facts=table_context_facts(s,ownership(s,'148'))
            self.assertEqual(['geneSymbol'],[f['field'] for f in facts])
        s=self.source();raw=deepcopy(s);u=next(u for u in raw['units'] if u['kind']=='CAPTION');u['text']='Changed title'
        with self.assertRaises(ValueError):table_context_facts(raw,ownership(s,'148'))

if __name__=='__main__':unittest.main()
