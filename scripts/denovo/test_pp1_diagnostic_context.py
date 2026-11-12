"""Disease-bound criteria are reusable knowledge, not case-pack diagnoses."""
from copy import deepcopy
import unittest

from pp1_diagnostic_context import published_bindings
from pp1_specification_context import select_specifications
from test_pp1_specification_context import specification, context, family, result, chosen
from test_pp1_segregation import add


def with_criteria():
    spec = specification()
    spec['original']['ld']['CriteriaCode'].append({'entContent': {
        'label':'PP4','gene':['GENE1'],'geneType':'nuclear','baseStrength':'Pathogenic',
        'defaultStrength':'Pathogenic Supporting', 'strengthDescriptor':[
            {'strength':'Supporting','applicability':'Applicable with VCEP specification',
             'specificationType':'Disease-specific',
             'text':'* Use if patient fulfils revised Example criteria.\n* Can be used if any of the family members have a highly specific phenotype.'}]}})
    return spec


def diagnosed_family():
    q=family()
    q['observations']=[o for o in q['observations'] if not (o['kind']=='diagnosis' and o['memberKey']!='I-1')]
    for member in ('II-1','II-2'): add(q,member,'reportedDiagnosticCriteria','revised Example criteria')
    return q


class DiagnosticBinding(unittest.TestCase):
    def test_reuses_published_disease_scope_and_preserves_both_sources(self):
        c=context(with_criteria());q=diagnosed_family();before=deepcopy(q)
        e=result(q,c)
        self.assertEqual('MET',e['status']);self.assertEqual(before,q)
        self.assertEqual(2,len(e['clinicalAssessments']))
        for row in e['clinicalAssessments']:
            self.assertEqual('AFFECTED',row['status']);self.assertTrue(row['sourceRefs'])
            self.assertIn('/strengthDescriptor/0/text',row['knowledgeBinding']['jsonPointer'])
            self.assertEqual('MONDO:0000001',row['knowledgeBinding']['scope']['diseaseId'])
            self.assertFalse(row['independentClinicalDiagnosisPerformed'])
        self.assertTrue(any(r for r in e['events'][0]['sourceRefs']))
        self.assertEqual(0,e['ledger']['pp4Points'])

    def test_no_gene_case_or_criteria_name_whitelist(self):
        spec=with_criteria();nodes=spec['original']['ld']['CriteriaCode']
        for node in nodes:node['entContent']['gene']=['UNRELATED']
        gene=spec['original']['ld']['RuleSet'][0]['entContent']['genes'][0]
        gene.update(gene='UNRELATED');gene['diseases'][0].update(preferredMondoId='MONDO:0000002',preferredTitle='Other disease')
        spec['original']['ld']['Gene'][0]['entContent']['HGNC']['symbol']='UNRELATED'
        nodes[1]['entContent']['strengthDescriptor'][0]['text']='* Use if patient meets Alternative diagnostic criteria.'
        c=select_specifications([spec],{}, {**chosen(),'geneSymbol':'UNRELATED','diseaseId':'MONDO:0000002'})
        self.assertEqual('QUALIFIED',c['status'])
        self.assertEqual('Alternative diagnostic criteria',c['policy']['diagnosticCriteriaBindings'][0]['criteriaName'])

    def test_unmapped_or_older_named_criteria_not_silently_equated(self):
        for value in ('Example criteria','old Example criteria','other criteria'):
            q=diagnosed_family()
            for o in q['observations']:
                if o['kind']=='reportedDiagnosticCriteria':o['value']=value
            e=result(q,context(with_criteria()))
            self.assertEqual([],e['clinicalAssessments'])
            self.assertIn('PP1_REPORTED_DIAGNOSTIC_CRITERIA_NOT_BOUND',[n['reasonCode'] for n in e['needs']])

    def test_only_typographic_case_and_whitespace_are_normalized(self):
        q=diagnosed_family()
        for o in q['observations']:
            if o['kind']=='reportedDiagnosticCriteria':o['value']='Revised  Example Criteria'
        self.assertEqual('MET',result(q,context(with_criteria()))['status'])

    def test_unaffected_conflict_is_not_overwritten(self):
        q=diagnosed_family();add(q,'II-1','clinical','UNAFFECTED')
        e=result(q,context(with_criteria()))
        self.assertEqual('NOT_EVALUATED',e['status'])
        self.assertIn('PP1_CLINICAL_CONFLICT',[n['reasonCode'] for n in e['needs']])
        self.assertIn('CONFLICT',[x['status'] for x in e['clinicalAssessments']])

    def test_noncarrier_counterexample_is_retained(self):
        q=diagnosed_family()
        for o in q['observations']:
            if o['kind']=='genotype' and o['memberKey']=='II-1':o['value']='NONCARRIER'
        self.assertIn('PP1_AFFECTED_NONCARRIER',[n['reasonCode'] for n in result(q,context(with_criteria()))['needs']])

    def test_unknown_conditions_do_not_disable_pp1_or_manufacture_mapping(self):
        mutations=[lambda c:c.update(instructionsToUse='Require independent diagnosis'),
                   lambda c:c.update(additionalComments='Only for adults'),
                   lambda c:c.update(unrecognizedCondition='extra'),
                   lambda c:c['strengthDescriptor'][0].update(text='* Use if patient fulfils revised Example criteria. Except children.'),
                   lambda c:c['strengthDescriptor'][0].update(text='* Use if patient fulfils possible Example criteria.'),
                   lambda c:c['strengthDescriptor'][0].update(specificationType='Strength'),
                   lambda c:c['strengthDescriptor'][0].update(instructionsToUse='With independent confirmation'),
                   lambda c:c.update(disease=['MONDO:0000099'])]
        for mutate in mutations:
            spec=with_criteria();mutate(spec['original']['ld']['CriteriaCode'][1]['entContent']);c=context(spec)
            self.assertEqual('QUALIFIED',c['status'])
            self.assertEqual([],c['policy']['diagnosticCriteriaBindings'])
            self.assertTrue(c['policy']['diagnosticBindingNeeds'])

    def test_other_disease_scope_duplicate_or_draft_cannot_lend_mapping(self):
        spec=with_criteria();gene=spec['original']['ld']['RuleSet'][0]['entContent']['genes'][0]
        gene['diseases'].append({'preferredMondoId':'MONDO:0000002','preferredTitle':'Other'})
        self.assertFalse(context(spec)['policy']['diagnosticCriteriaBindings'])
        spec=with_criteria();spec['original']['ld']['CriteriaCode'].append(deepcopy(spec['original']['ld']['CriteriaCode'][1]))
        self.assertFalse(context(spec)['policy']['diagnosticCriteriaBindings'])
        spec=with_criteria();spec['original']['entContent']['states'][0]['name']='Draft'
        self.assertNotIn('policy',context(spec))

    def test_unimplemented_published_conditions_are_not_reported_as_absent_data(self):
        spec=with_criteria();spec['original']['ld']['CriteriaCode'][1]['entContent']['additionalComments']='Only for adults'
        e=result(diagnosed_family(),context(spec))
        pending=[n for n in e['needs'] if n['reasonCode']=='PP1_DIAGNOSTIC_MAPPING_UNIMPLEMENTED']
        self.assertEqual(2,len(pending))
        self.assertTrue(all(n['route']=='SOFTWARE_OR_SOURCE_REVIEW' and n['sourceLocators'] for n in pending))
        self.assertFalse(e['clinicalAssessments'])

    def test_binding_cannot_cross_disease_or_override_method_definition(self):
        for change in ('scope','definition','duplicate'):
            c=context(with_criteria())
            if change=='scope':c['policy']['diagnosticCriteriaBindings'][0]['scope']['diseaseId']='MONDO:0000002'
            elif change=='definition':c['policy']['affectedDefinition']='OTHER_DEFINITION'
            else:c['policy']['diagnosticCriteriaBindings']*=2
            self.assertFalse(result(diagnosed_family(),c)['clinicalAssessments'])

    def test_criteria_do_not_supply_missing_proband(self):
        q=diagnosed_family();q['observations']=[o for o in q['observations'] if o['kind']!='proband']
        q['unresolved']=[{'familyKey':'F1','fieldId':'F04.2','sourceValue':'I-1 was referred for testing.',
                          'sourceRefs':[{'unitId':'referral','quote':'I-1 was referred for testing.'}]}]
        e=result(q,context(with_criteria()))
        self.assertEqual('NOT_EVALUATED',e['status']);self.assertFalse(e['events'])
        need=next(n for n in e['needs'] if n['reasonCode']=='PP1_EXPERT_COUNT_PROBAND_UNRESOLVED')
        self.assertTrue(any(f['kind']=='unqualifiedAscertainmentClaim' for f in need['knownFacts']))
        self.assertTrue(any(r.get('unitId')=='referral' for r in need['sourceLocators']))


if __name__=='__main__':unittest.main()
