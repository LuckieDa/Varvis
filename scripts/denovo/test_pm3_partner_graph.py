"""Synthetic target-dependence graphs, including cycles and source changes."""
import unittest
from copy import deepcopy
from unittest.mock import Mock
from pm3_partner_graph import PartnerDependencyGraph
import test_pm3_partner_qualification as fixtures


def allele(pos):return {'assembly':'GRCh38','contig':'1','position':pos,'reference':'C','alternate':'T'}


def source(names=None, label='Pathogenic'):
    s=fixtures.Qualification().source();r=s['assertions'][0];r['sourceClassification']=label
    if names is not None:
        r['criteriaStatements']=[{'sourceId':'VCV123.1','xpath':'/Classification[1]/Comment[1]',
            'status':'EXPLICIT_SOURCE_LIST','criteria':[{'code':'PM3','original':'PM3_Strong'}],
            'originalText':'PM3_Strong: Detected with '+', '.join(n+' (P)' for n in names)+
                '. (PMID:123); In summary this variant meets criteria'}]
    return s


class Graph(unittest.TestCase):
    def graph(self, records=None, **limits):
        self.sources=Mock();self.sources.lookup.side_effect=lambda a,g:(records or {}).get(a['position'],source())
        self.identity=Mock();self.identity.resolve_coding.side_effect=lambda g,c,t:{
            'geneSymbol':g,'transcript':t,'allele':allele(int(c[2:-3]))}
        return PartnerDependencyGraph(self.sources,self.identity,None,{'allele':allele(1)},'TEST',
                                      fixtures.Qualification.context,**limits)

    def assess(self, graph, names):return graph.assess(source(names),{'allele':allele(10)})

    def test_exact_recursive_source_basis_and_shared_node_reuse(self):
        graph=self.graph({20:source(['NM_000001.1:c.30C>T'])})
        result=self.assess(graph,['NM_000001.1:c.20C>T','NM_000001.1:c.30C>T'])
        self.assertEqual('VERIFIED',result['independentClassification'])
        self.assertEqual('PATHOGENIC',result['classification'])
        self.assertEqual(2,self.sources.lookup.call_count)
        self.assertEqual(2,len(result['dependencyGraph']['nodes']))
        dependency=result['assertionAssessments'][0]['criterionDependencies'][0]
        self.assertEqual('EXACT_COMPARISON_DEPENDENCIES_TARGET_INDEPENDENT',dependency['status'])
        self.assertEqual([20,30],[c['resolution']['allele']['position'] for c in dependency['comparisonNames']])

    def test_target_and_cycles_rejected_before_querying_protected_node(self):
        for name in ('NM_000001.1:c.1C>T','NM_000001.1:c.10C>T'):
            graph=self.graph();result=self.assess(graph,[name])
            self.assertEqual('UNKNOWN',result['classification']);self.sources.lookup.assert_not_called()
        graph=self.graph({20:source(['NM_000001.1:c.1C>T'])})
        result=self.assess(graph,['NM_000001.1:c.20C>T'])
        self.assertEqual('UNKNOWN',result['classification'])
        self.assertEqual(1,self.sources.lookup.call_count)
        child=result['dependencyGraph']['nodes'][0]['qualification']
        self.assertIn('PM3_DEPENDENCY_USES_TARGET_VARIANT',child['issues'])

    def test_source_labels_unresolved_events_and_aliases_not_silently_adopted(self):
        graph=self.graph({20:source(label='Likely pathogenic')})
        result=self.assess(graph,['NM_000001.1:c.20C>T'])
        self.assertIn('PM3_DEPENDENCY_SOURCE_CLASSIFICATION_CHANGED',result['issues'])
        graph=self.graph();result=self.assess(graph,['R20W'])
        self.assertIn('PM3_DEPENDENCY_EXACT_CODING_NAME_REQUIRED',result['issues'])
        self.sources.lookup.assert_not_called()
        s=source(['NM_000001.1:c.20C>T']);s['assertions'][0]['criteriaStatements'][0]['criteria'].append({'code':'PP4'})
        graph=self.graph();result=graph.assess(s,{'allele':allele(10)})
        self.assertIn('PM3_PARTNER_EVENT_OR_OTHER_CRITERIA_DEPENDENCY_NOT_IMPLEMENTED',result['issues'])
        self.assertEqual('UNKNOWN',result['classification'])

    def test_budget_and_incomplete_sources_are_explicit(self):
        graph=self.graph(max_nodes=1)
        result=self.assess(graph,['NM_000001.1:c.20C>T','NM_000001.1:c.30C>T'])
        self.assertIn('PM3_DEPENDENCY_NODE_LIMIT',result['issues']);self.assertEqual(1,self.sources.lookup.call_count)
        graph=self.graph({20:source(['NM_000001.1:c.30C>T'])},max_depth=2)
        result=self.assess(graph,['NM_000001.1:c.20C>T'])
        self.assertEqual('UNKNOWN',result['classification']);self.assertEqual(1,self.sources.lookup.call_count)
        graph=self.graph({20:{'status':'SOURCE_READ_INCOMPLETE','assertions':[]}})
        result=self.assess(graph,['NM_000001.1:c.20C>T'])
        self.assertIn('PM3_DEPENDENCY_CLASSIFICATION_UNRESOLVED',result['issues'])

    def test_input_is_unchanged(self):
        s=source(['NM_000001.1:c.20C>T']);before=deepcopy(s)
        self.graph().assess(s,{'allele':allele(10)})
        self.assertEqual(before,s)

    def test_failed_acquisition_is_not_repeated_for_shared_dependency(self):
        graph=self.graph();self.sources.lookup.side_effect=OSError('private location')
        result=self.assess(graph,['NM_000001.1:c.20C>T','NM_000001.1:c.20C>T'])
        self.assertEqual(1,self.sources.lookup.call_count)
        self.assertEqual('UNKNOWN',result['classification'])
        self.assertEqual('FAILED',result['dependencyGraph']['nodes'][0]['status'])
        self.assertNotIn('private location',str(result))


if __name__=='__main__':unittest.main()
