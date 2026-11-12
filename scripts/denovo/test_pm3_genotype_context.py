import unittest
from copy import deepcopy
from test_pm3_automatic_facts import source
from pm3_observations import ownership
from pm3_genotype_context import facts
from pm3_source_context import table_context_facts
from pm3_case_semantics import qualify


PARA = ('<p>The analyzed patients were mainly compound heterozygous (17/18), and one was homozygous '
        '(c.2C&gt;G; c.2C&gt;G). <xref ref-type="table" rid="g">Table 1</xref> shows the genotypes '
        'in our cohort.</p>')
ROW = '<tr><td>1</td><td>c.2C&gt;G</td><td>c.2C&gt;G</td></tr>'
TABLE = ('<table-wrap id="g"><caption><p>EXAMPLE genotypes.</p></caption><table><thead><tr>'
         '<th>Case</th><th>Allele 1</th><th>Allele 2</th></tr></thead><tbody>'+ROW+
         '</tbody></table></table-wrap>')


def observation(s):
    scope=ownership(s,'1'); ff=table_context_facts(s,scope)+facts(s,scope)
    cells=[u for u in s['units'] if u['text']=='c.2C>G']
    for field,u in zip(('targetVariant','partnerVariant'),cells):
        ff.append({'field':field,'sourceValue':u['text'],'subject':'PROBAND','issues':[],
            'citation':{'unitId':u['unitId'],'quote':u['text']},'ownership':scope[u['unitId']]})
    return {'caseLabel':'1','facts':ff}


class GenotypeContext(unittest.TestCase):
    def test_unique_explicit_homozygous_genotype_is_case_bound(self):
        s=source(PARA+TABLE); c=observation(s)
        r=qualify(c,{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})
        self.assertEqual('REPORTED_HOMOZYGOUS',r['observations'][0]['kind'])
        self.assertIsNone(r['observations'][0]['partnerRepresentation'])
        proof=next(f for f in c['facts'] if f['field']=='reportedZygosity')
        self.assertEqual(3,len(proof['supportingCitations']))
        self.assertFalse(proof['ownership']['proof']['independenceEstablished'])

    def test_explicit_gene_in_correlation_caption_is_not_query_gene(self):
        s=source(PARA+TABLE.replace('EXAMPLE genotypes.','Correlation between EXAMPLE variants and biomarker levels in 18 patients.'))
        self.assertEqual('REPORTED_HOMOZYGOUS',qualify(observation(s),{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})['observations'][0]['kind'])
        self.assertEqual([],qualify(observation(s),{'geneSymbol':'OTHER','variantAliases':['c.2C>G']})['observations'])
        from pm3_source_context import caption_gene
        for caption in ['Correlation between EXAMPLE and OTHER variants and levels in 18 patients.',
                        'Possible correlation between EXAMPLE variants and levels in 18 patients.']:
            self.assertIsNone(caption_gene(caption))

    def test_duplicate_strings_alone_do_not_establish_homozygosity(self):
        s=source(TABLE); r=qualify(observation(s),{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})
        self.assertEqual([],r['observations'])

    def test_missing_wrong_xref_prior_cohort_and_negated_claim_rejected(self):
        for para in [PARA.replace('rid="g"','rid="other"'),PARA.replace('ref-type="table"','ref-type="fig"'),
                     PARA.replace('our cohort','another cohort'),PARA.replace('one was','one was not'),
                     PARA.replace('The analyzed patients','The previous patients'),
                     PARA.replace('c.2C&gt;G; c.2C&gt;G','c.2C&gt;G; c.8A&gt;T')]:
            s=source(para+TABLE)
            self.assertEqual([],facts(s,ownership(s,'1')))

    def test_ambiguous_rows_and_merged_rows_do_not_link(self):
        for table in [TABLE.replace(ROW,ROW+ROW.replace('>1<','>2<')),
                      TABLE.replace('<td>1','<td rowspan="2">1'),
                      TABLE.replace('Allele 2','Predicted allele')]:
            s=source(PARA+table);self.assertEqual([],facts(s,ownership(s,'1')))

    def test_mismatched_reported_proteins_do_not_become_homozygous(self):
        s=source(PARA+TABLE);c=observation(s)
        for field,value in [('targetVariant','p.T1S'),('partnerVariant','p.T1A')]:
            f=deepcopy(next(f for f in c['facts'] if f['field']==field))
            f['sourceValue']=value;f['citation']['quote']=value;f['ownership']['cellText']=value;c['facts'].append(f)
        self.assertEqual([],qualify(c,{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']})['observations'])


if __name__=='__main__':unittest.main()
