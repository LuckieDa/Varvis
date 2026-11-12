"""Bounded target-exclusion checks over source-declared comparison dependencies.

Only explicit versioned RefSeq coding names are resolved. Protein aliases or
legacy IVS names remain gaps; a source label or a different name is not proof.
The graph reuses source classifications, never removes criteria or recomputes P/LP.
"""
from copy import deepcopy
import re
from model_literature_contract import canonical,digest
from pm3_partner_qualification import qualify


class PartnerDependencyGraph:
    def __init__(self, sources, identity, names, target, gene, context, max_nodes=12, max_depth=3):
        self.sources,self.identity,self.names=sources,identity,names
        self.target,self.gene,self.context=target,gene,context
        self.max_nodes,self.max_depth=max_nodes,max_depth
        self.active=[];self.nodes={};self.memo={};self.failures={}

    def key(self,allele):return digest(canonical(allele))

    def assess(self,sources,partner,case_observation=None):
        if not self.target or not isinstance(self.target.get('allele'),dict):
            return qualify(sources,self.context,self.names,self.target)
        self.active=[self.key(partner['allele'])]
        try:result=qualify(sources,self.context,self.names,self.target,self.resolve_dependency,case_observation)
        finally:self.active=[]
        result['automaticScope']='TRACEABLE_EXPLICIT_FINAL_BASIS_WITH_BOUNDED_EXACT_COMPARISON_DEPENDENCIES'
        result['dependencyGraph']={'method':'BOUNDED_EXACT_SOURCE_TARGET_EXCLUSION_V1',
            'targetAllele':deepcopy(self.target['allele']),'maximumNodes':self.max_nodes,
            'maximumDepth':self.max_depth,'nodes':list(self.nodes.values()),
            'scope':'EXPLICIT_VERSIONED_CODING_COMPARATORS; UNRESOLVED_EVENTS_REMAIN_BLOCKED'}
        return result

    def resolve_dependency(self,dependency):
        result=deepcopy(dependency)
        if result['status']!='COMPARISON_DEPENDENCY_UNRESOLVED' or not result.get('comparisonNames'):
            return result
        problems=[]
        for comparison in result['comparisonNames']:
            key=None;reserved=False
            name=comparison['reportedName']
            match=re.fullmatch(r'(NM_[0-9]+\.[1-9][0-9]*):(c\.[1-9][0-9]*[ACGT]>[ACGT])',name)
            if not match:
                comparison['resolution']={'status':'UNKNOWN','reasonCode':'PM3_DEPENDENCY_EXACT_CODING_NAME_REQUIRED'}
                problems.append('PM3_DEPENDENCY_EXACT_CODING_NAME_REQUIRED');continue
            if len(self.active)>=self.max_depth:
                comparison['resolution']={'status':'UNKNOWN','reasonCode':'PM3_DEPENDENCY_DEPTH_LIMIT'}
                problems.append('PM3_DEPENDENCY_DEPTH_LIMIT');continue
            try:
                identity=self.identity.resolve_coding(self.gene,match[2],match[1])
                allele=identity['allele'];key=self.key(allele)
                if identity.get('geneSymbol')!=self.gene:
                    raise ValueError('PM3_DEPENDENCY_GENE_MISMATCH')
                if key==self.key(self.target['allele']):
                    raise ValueError('PM3_DEPENDENCY_USES_TARGET_VARIANT')
                if key in self.active:raise ValueError('PM3_DEPENDENCY_CYCLE')
                if key in self.failures:raise ValueError(self.failures[key])
                if key not in self.memo:
                    if len(self.nodes)>=self.max_nodes:raise ValueError('PM3_DEPENDENCY_NODE_LIMIT')
                    # Reserve before acquisition, including failures. Never restart
                    # the node budget after a failed lookup or nested exception.
                    self.nodes[key]={'allele':deepcopy(allele),'identity':identity,'status':'CHECKING'}
                    reserved=True
                    self.active.append(key)
                    try:
                        source=self.sources.lookup(allele,self.gene)
                        checked=qualify(source,self.context,self.names,self.target,self.resolve_dependency)
                    finally:self.active.pop()
                    self.nodes[key].update(status='CHECKED',sources=source,qualification=checked)
                    self.memo[key]=checked
                checked=self.memo[key]
                comparison['resolution']={'status':checked['independentClassification'],
                    'nodeKey':key,'allele':deepcopy(allele),'classification':checked['classification'],
                    'qualifiedScvs':checked.get('qualifiedScvs',[]),'issues':checked.get('issues',[])}
                expected={'P':{'PATHOGENIC'},'LP':{'LIKELY_PATHOGENIC'},
                          'P/LP':{'PATHOGENIC','LIKELY_PATHOGENIC'},'VUS':{'VUS'}}[comparison['sourceLabel']]
                if checked['independentClassification']!='VERIFIED':
                    problems.append('PM3_DEPENDENCY_CLASSIFICATION_UNRESOLVED')
                elif checked['classification'] not in expected:
                    problems.append('PM3_DEPENDENCY_SOURCE_CLASSIFICATION_CHANGED')
                else:
                    comparison.update(identityStatus='VERIFIED',classificationIndependence='VERIFIED')
            except (ValueError,OSError,KeyError,TypeError) as error:
                code=str(error)
                if not re.fullmatch(r'[A-Z][A-Z0-9_]{1,150}',code):code='PM3_DEPENDENCY_RESOURCE_FAILURE'
                if reserved and self.nodes[key]['status']=='CHECKING':
                    self.nodes[key].update(status='FAILED',reasonCode=code)
                    self.failures[key]=code
                comparison['resolution']={'status':'UNKNOWN','reasonCode':code}
                problems.append(code)
        if not problems:
            result['status']='EXACT_COMPARISON_DEPENDENCIES_TARGET_INDEPENDENT'
            result['issues']=[]
        else:result['issues']=sorted(set(result['issues']+problems))
        return result
