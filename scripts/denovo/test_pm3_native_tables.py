import unittest
from test_pm3_automatic_facts import source
from test_pm3_genotype_context import PARA,TABLE
from pm3_native_tables import cases
from pm3_observations import native_projection


class NativeTables(unittest.TestCase):
    target={'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']}

    def test_typed_source_cells_supply_context_without_model_or_target_inference(self):
        for gene in ['GENE1','GENE2']:
            def table(zyg):
                columns=[('Case','1'),('Gene',gene),('Variant','c.2C&gt;G'),('Transcript','NM_000001.1'),
                         ('Zygosity',zyg),('Diagnosis','Example disorder'),('Affected status','affected')]
                return '<table-wrap id="g"><table><thead><tr>'+''.join('<th>'+k+'</th>' for k,v in columns)+\
                    '</tr></thead><tbody><tr>'+''.join('<td>'+v+'</td>' for k,v in columns)+\
                    '</tr></tbody></table></table-wrap>'
            target={'geneSymbol':gene,'variantAliases':['c.2C>G']}
            c=native_projection(source(table('homozygous')),target,1)['cases'][0]
            o=c['semantics']['observations'][0]
            self.assertEqual('REPORTED_HOMOZYGOUS',o['kind'])
            self.assertEqual(['Example disorder'],o['reportedDiagnoses'])
            self.assertEqual('AFFECTED',o['reportedAffectedStatus'])
            self.assertFalse(c['nativeStructuredIntake']['modelReviewed'])
            bad=native_projection(source(table('possibly homozygous')),target,1)['cases'][0]
            self.assertEqual([],bad['semantics']['observations'])

    def test_source_native_genotype_is_not_a_model_review_or_score(self):
        s=source(PARA+TABLE);r=native_projection(s,self.target,5)
        self.assertEqual([],r['processedChunks']);self.assertFalse(r['allChunksProcessed'])
        c=r['cases'][0];self.assertFalse(c['nativeStructuredIntake']['modelReviewed'])
        self.assertEqual('REPORTED_HOMOZYGOUS',c['semantics']['observations'][0]['kind'])
        self.assertEqual('UNKNOWN',c['occurrenceQualification']['qualification'])
        self.assertEqual([],c['semantics']['observations'][0]['reportedDiagnoses'])

    def test_target_does_not_supply_gene_or_homozygosity(self):
        for text in [TABLE,(PARA+TABLE).replace('EXAMPLE genotypes.','Another table.')]:
            r=native_projection(source(text),self.target,1)
            self.assertEqual([],r['cases'][0]['semantics']['observations'])

    def test_other_allele_is_retained_not_selected_away(self):
        s=source(TABLE.replace('<td>c.2C&gt;G</td><td>c.2C&gt;G</td>',
                               '<td>c.8A&gt;T</td><td>c.2C&gt;G</td>'))
        r=native_projection(s,self.target,1)['cases'][0]['semantics']['observations'][0]
        self.assertEqual('c.8A>T',r['partnerRepresentation']);self.assertEqual('UNKNOWN',r['phase'])

    def test_ambiguous_heading_merged_duplicate_and_partial_value_rejected(self):
        for text in [TABLE.replace('Allele 2','Predicted effect'),
                     TABLE.replace('<td>1','<td rowspan="2">1'),
                     TABLE.replace('</tbody>','<tr><td>1</td><td>c.2C&gt;G</td><td>c.2C&gt;G</td></tr></tbody>'),
                     TABLE.replace('c.2C&gt;G','possible c.2C&gt;G')]:
            r=native_projection(source(text),self.target,1)
            self.assertFalse(any(c['semantics']['observations'] for c in r['cases']))

    def test_unknown_target_never_creates_cases(self):
        self.assertEqual([],cases(source(TABLE),{'geneSymbol':'EXAMPLE','variantAliases':['c.44A>T']}))


if __name__=='__main__':unittest.main()
