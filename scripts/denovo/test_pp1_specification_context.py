"""Synthetic published-rule shapes and affected-relative-count counterexamples."""
from copy import deepcopy
import unittest

from pp1_specification_context import select_specifications, compile_count, INSTRUCTIONS, METHOD
from pp1_segregation import evaluate
from test_pp1_segregation import fixture, selection, ledger, add


def specification():
    c = {'label':'PP1','gene':['GENE1'],'geneType':'nuclear','baseStrength':'Pathogenic',
         'defaultStrength':'Pathogenic Supporting','instructionsToUse':INSTRUCTIONS,
         'strengthDescriptor':[{'strength':s,'applicability':'Applicable with VCEP specification','text':v}
              for s,v in [('Supporting','2-3 affected individuals.'),('Moderate','4 affected individuals.'),('Strong','≥5 affected individuals.')]]}
    c['strengthDescriptor'] += [{'strength':s,'applicability':'Not Applicable for this VCEP','text':''}
                                for s in ('Very Strong','Stand Alone')]
    return {'specificationKey':'GN99999@1.0','source':{'rawSha256':'a'*64,'sourceUrl':'SYNTHETIC'},'original':{
        'entContent':{'states':[{'name':'Released','current':True}]},'ld':{
            'Gene':[{'entContent':{'HGNC':{'symbol':'GENE1','hgnc_id':'HGNC:1'}}}],
            'RuleSet':[{'entContent':{'genes':[{'gene':'GENE1','preferredTranscript':'NM_000001',
                'diseases':[{'preferredMondoId':'MONDO:0000001','preferredTitle':'TEST disease'}]}]}}],
            'CriteriaCode':[{'entContent':c}]}}}


def chosen():
    return {**selection(),'diseaseId':'MONDO:0000001'}


def context(spec=None):
    return select_specifications([spec or specification()], {'scope':'SYNTHETIC_NOT_CLINICAL'}, chosen())


def family(n=2):
    q=fixture();q['target']=chosen();q['identity']['transcript']='NM_000001.7'
    q['observations']=[]
    add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE')
    add(q,'I-1','proband',True);add(q,'I-1','diagnosis','TEST disease');add(q,'I-1','genotype','CARRIER')
    for i in range(n):
        add(q,f'II-{i+1}','diagnosis','TEST disease');add(q,f'II-{i+1}','genotype','CARRIER')
        add(q,'I-1','parentOf',f'II-{i+1}')
    return q


def result(q=None, c=None, log=None):
    q=q or family();l=ledger();l['scope']['diseaseId']='MONDO:0000001'
    return evaluate([q],c or context(),log or l)


class Policies(unittest.TestCase):
    def test_explicit_exact_pp4_exclusion_only_completes_a_fresh_unoccupied_ledger(self):
        from pp1_published_contribution import checked_ledger
        spec = specification()
        rules = spec['original']['ld']
        rules['RuleSet'][0]['entContent']['genes'][0]['diseases'][0].update(
            preferredModeOfInheritance='Autosomal dominant inheritance')
        pp4 = {'label': 'PP4', 'gene': ['GENE1'], 'applicability': 'Not applicable',
               'additionalComments': 'The phenotype can also occur in other conditions.',
               'strengthDescriptor': [{'strength': 'Supporting', 'applicability': 'Not applicable', 'text': ''}]}
        rules['CriteriaCode'].append({'entContent': pp4})
        fresh = {**ledger(), 'status': 'UNKNOWN', 'pp4Points': None,
                 'reasonCode': 'PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE'}
        fresh['scope']['diseaseId'] = 'MONDO:0000001'
        checked = checked_ledger(fresh, context(spec))
        self.assertEqual(('KNOWN_EMPTY', 0), (checked['status'], checked['pp4Points']))
        self.assertEqual('/ld/CriteriaCode/1', checked['publishedPp4Exclusion']['clausePointer'])
        for change in ({'eventIds': ['allocated']}, {'reasonCode': 'PP1_OTHER_SOURCE_UNAVAILABLE'},
                       {'scope': {'diseaseId': 'MONDO:0000002'}}):
            prior = {**fresh, **change}
            self.assertEqual(prior, checked_ledger(prior, context(spec)))
        pp4['strengthDescriptor'][0]['applicability'] = 'Applicable'
        self.assertEqual(fresh, checked_ledger(fresh, context(spec)))

    def test_exact_scope_and_omitted_inheritance_are_explicit(self):
        c=context();self.assertEqual('QUALIFIED',c['status']);self.assertEqual(METHOD,c['policy']['method'])
        self.assertFalse(c['clinicalApproval']);self.assertNotIn('adoptionSha256',c)
        self.assertTrue(c['policy']['inheritanceBasis'].startswith('NOT_SPECIFIED'))
        self.assertEqual([2,4,5],[c['policy']['thresholds'][s]['minimum'] for s in ('SUPPORTING','MODERATE','STRONG')])
    def test_different_gene_or_disease_not_borrowed(self):
        for field,value in [('geneSymbol','OTHER'),('diseaseId','MONDO:0000002')]:
            self.assertEqual('KNOWLEDGE_MISSING',select_specifications([specification()],{}, {**chosen(),field:value})['status'])
    def test_compiler_does_not_name_gene_or_fix_threshold_numbers(self):
        s=specification();g=s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]
        g['gene']='NEWGENE';s['original']['ld']['Gene'][0]['entContent']['HGNC']['symbol']='NEWGENE'
        c=s['original']['ld']['CriteriaCode'][0]['entContent'];c['gene']=['NEWGENE']
        for d,t in zip(c['strengthDescriptor'],['3-4 affected individuals.','5-6 affected individuals.','≥7 affected individuals.']):d['text']=t
        c=select_specifications([s],{}, {**chosen(),'geneSymbol':'NEWGENE'})
        self.assertEqual('QUALIFIED',c['status']);self.assertEqual(7,c['policy']['thresholds']['STRONG']['minimum'])
    def test_extra_or_changed_conditions_do_not_fallback(self):
        for mutate in [lambda c:c.update(additionalComments='Only after functional testing'),
                       lambda c:c.update(instructionsToUse=INSTRUCTIONS+' New condition.'),
                       lambda c:c.update(unknownCondition=True),
                       lambda c:c['strengthDescriptor'][1].update(text='6 affected individuals.'),
                       lambda c:c['strengthDescriptor'][0].update(instructionsToUse='Require LOD')]:
            s=specification();mutate(s['original']['ld']['CriteriaCode'][0]['entContent'])
            self.assertEqual('METHOD_UNSUPPORTED',context(s)['status'])
    def test_unreleased_and_conflicting_specs_rejected(self):
        s=specification();s['original']['entContent']['states'][0]['name']='Draft'
        self.assertEqual('METHOD_UNSUPPORTED',context(s)['status'])
        self.assertEqual('KNOWLEDGE_CONFLICT',select_specifications([specification(),specification()],{},chosen())['status'])
    def test_explicit_inheritance_conflict_not_omitted(self):
        s=specification();s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance']='Autosomal recessive inheritance'
        self.assertEqual('METHOD_UNSUPPORTED',context(s)['status'])
    def test_attached_unimplemented_conditions_remain_a_gap(self):
        s=specification();s['original']['ld']['File']=[{'text':'extra method table'}]
        self.assertEqual('METHOD_UNSUPPORTED',context(s)['status'])
    def test_secondary_inheritance_declarations_cannot_be_ignored(self):
        for mode in ['Autosomal recessive inheritance', 'Unmapped inheritance']:
            s=specification();d=s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
            d['modesOfInheritance']=[{'modeOfInheritance':mode}]
            self.assertEqual('METHOD_UNSUPPORTED',context(s)['status'])
        s=specification();d=s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        d['modesOfInheritance']=[{'modeOfInheritance':'Autosomal dominant inheritance'}]
        self.assertEqual('QUALIFIED',context(s)['status'])


class Counts(unittest.TestCase):
    def test_affected_obligate_carrier_can_cross_published_threshold_once(self):
        q = family(1)
        add(q, 'I-1', 'genotype', 'HETEROZYGOUS')
        add(q, 'I-0', 'diagnosis', 'TEST disease')
        add(q, 'I-0', 'parentOf', 'I-1')
        baseline = result(q)
        self.assertEqual(('NOT_MET', 0), (baseline['status'], baseline['awardedPP1Points']))
        origin = add(q, 'I-1', 'alleleOriginParent', 'I-0')
        q['observations'].append(deepcopy(origin))
        scored = result(q)
        event, = scored['events']
        self.assertEqual(('MET', 'SUPPORTING', 1), (scored['status'], scored['strength'], scored['awardedPP1Points']))
        self.assertEqual((2, 1, ['I-0']), (event['affectedRelativesCount'], event['obligateCarrierCount'], event['obligateCarrierLabels']))
        self.assertIn(origin['id'], event['factIds'])
        self.assertEqual(baseline['events'][0]['eventId'], event['eventId'])
        self.assertNotIn('I-1', event['memberKeys'])
        for stage in ('UNKNOWN_LEDGER', 'EXCLUSIVE_ORIGIN', 'REUSE_FAMILY', 'NO_EDGE', 'NO_HETEROZYGOUS_RESULT',
                      'NO_AFFECTED_PROOF', 'CONFLICTING_ORIGIN', 'NEGATIVE_PARENT', 'DIRECT_PARENT', 'OLD_POLICY'):
            with self.subTest(stage=stage):
                material, policy = deepcopy(q), context()
                log = ledger();log['scope']['diseaseId'] = 'MONDO:0000001'
                if stage == 'UNKNOWN_LEDGER': log['status'] = 'UNKNOWN'
                if stage == 'EXCLUSIVE_ORIGIN': log['exclusiveFactIds'] = [origin['id']]
                if stage == 'REUSE_FAMILY': log['eventIds'] = [event['eventId']]
                if stage == 'NO_EDGE': material['observations'] = [r for r in material['observations'] if not (r['memberKey']=='I-0' and r['kind']=='parentOf')]
                if stage == 'NO_HETEROZYGOUS_RESULT': material['observations'] = [r for r in material['observations'] if r['value']!='HETEROZYGOUS']
                if stage == 'NO_AFFECTED_PROOF': material['observations'] = [r for r in material['observations'] if not (r['memberKey']=='I-0' and r['kind']=='diagnosis')]
                if stage == 'CONFLICTING_ORIGIN': add(material, 'I-1', 'alleleOriginParent', 'I-2')
                if stage == 'NEGATIVE_PARENT': add(material, 'I-0', 'genotype', 'NONCARRIER')
                if stage == 'DIRECT_PARENT': add(material, 'I-0', 'genotype', 'HETEROZYGOUS')
                if stage == 'OLD_POLICY': policy['policy']['obligateCarriersImplemented'] = False
                checked = result(material, policy, log)
                self.assertEqual(1 if stage == 'DIRECT_PARENT' else 0, checked['awardedPP1Points'])
                if stage == 'UNKNOWN_LEDGER':
                    self.assertEqual('1', checked['rawSegregationPoints'])
                    self.assertEqual('NOT_EVALUATED', checked['status'])
                elif stage in {'CONFLICTING_ORIGIN','NEGATIVE_PARENT'}:
                    self.assertEqual('NOT_EVALUATED', checked['status'])
                    self.assertIn('PP1_OBLIGATE_CARRIER_ORIGIN_CONFLICT', [n['reasonCode'] for n in checked['needs']])
                if stage != 'UNKNOWN_LEDGER':
                    self.assertFalse(any(e.get('obligateCarrierCount') for e in checked['events']))

    def test_optional_member_needs_have_stable_order(self):
        q=family()
        for member in ('unknown-z','unknown-a','unknown-m'):
            add(q,member,'sex','UNKNOWN')
        needs=[n['scope']['memberKey'] for n in result(q)['needs']
               if n['reasonCode']=='PP1_MEMBER_EVENT_INPUT_INCOMPLETE'
               and 'memberLabel' not in n['scope']]
        self.assertEqual(['unknown-a','unknown-m','unknown-z'],needs)

    def test_published_count_does_not_require_universal_single_candidate_statement(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='candidateScope']
        e=result(q)
        self.assertEqual('MET',e['status'])
        self.assertNotIn('PP1_REASONABLE_CANDIDATE_SCOPE_UNKNOWN',[n['reasonCode'] for n in e['needs']])
        self.assertFalse(any(o['kind']=='candidateScope' for o in q['observations']))
        add(q,'FAMILY','candidateScopeUnresolved','Another possible disease-causing variant was reported.')
        self.assertEqual('NOT_EVALUATED',result(q)['status'])

    def test_old_or_unknown_candidate_policy_still_requires_scope(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='candidateScope']
        for rule in (None,'UNIMPLEMENTED'):
            c=context();c['policy']['candidateHandling']=rule
            self.assertEqual('NOT_EVALUATED',result(q,c)['status'])

    def test_thresholds_count_affected_relatives_excluding_proband(self):
        for n,s,p in [(1,None,0),(2,'SUPPORTING',1),(3,'SUPPORTING',1),(4,'MODERATE',2),(5,'STRONG',4),(8,'STRONG',4)]:
            with self.subTest(n=n):
                e=result(family(n));self.assertEqual((s,p),(e['strength'],e['awardedPP1Points']))
                self.assertEqual(n,e['events'][0]['affectedRelativesCount']);self.assertEqual(str(p),e['events'][0]['rawPoints'])
                self.assertNotIn('I-1',e['events'][0]['memberKeys']);self.assertTrue(e['events'][0]['sourceRefs'])
    def test_count_does_not_require_heterozygosity_or_only_parent_child_edges(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='parentOf']
        add(q,'II-1','fullSiblingOf','II-2')
        self.assertEqual('MET',result(q)['status'])
    def test_unknown_or_unaffected_bridge_and_missing_relationship_not_counted(self):
        for mutation in ('missing-edges','unknown-bridge','unaffected-bridge'):
            q=family()
            if mutation=='missing-edges': q['observations']=[o for o in q['observations'] if o['kind']!='parentOf']
            else:
                q['observations']=[o for o in q['observations'] if not(o['memberKey']=='I-1' and o['kind']=='diagnosis')]
                if mutation=='unaffected-bridge':add(q,'I-1','clinical','UNAFFECTED')
            e=result(q);self.assertEqual('NOT_EVALUATED',e['status'])
            self.assertIn('PP1_EXPERT_COUNT_RELATIONSHIP_UNRESOLVED',[n['reasonCode'] for n in e['needs']])
    def test_missing_proband_or_multiple_probands_block(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='proband']
        self.assertEqual('NOT_EVALUATED',result(q)['status'])
        q=family();add(q,'II-1','proband',True);self.assertEqual('NOT_EVALUATED',result(q)['status'])
    def test_missing_proband_does_not_hide_clinical_or_genotype_reading_needs(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='proband'
            and not (o['memberKey']=='II-1' and o['kind'] in {'diagnosis','genotype'})]
        e=result(q)
        self.assertEqual('NOT_EVALUATED',e['status'])
        self.assertIn('PP1_EXPERT_COUNT_PROBAND_UNRESOLVED',[n['reasonCode'] for n in e['needs']])
        needs=[n for n in e['needs'] if n['reasonCode']=='PP1_MEMBER_EVENT_INPUT_INCOMPLETE']
        self.assertEqual(1,len(needs));self.assertEqual('II-1',needs[0]['scope']['memberLabel'])
        self.assertEqual(['CLINICAL_STATUS','TARGET_GENOTYPE'],needs[0]['scope']['missingInputs'])
        self.assertFalse([n for n in result()['needs'] if n['reasonCode']=='PP1_MEMBER_EVENT_INPUT_INCOMPLETE'])
    def test_carrier_without_qualified_disease_does_not_count(self):
        q=family();q['observations']=[o for o in q['observations'] if o['kind']!='diagnosis']
        self.assertEqual('NOT_EVALUATED',result(q)['status'])
    def test_affected_noncarrier_cannot_hide_behind_other_positive_relatives(self):
        q=family();add(q,'II-3','genotype','NONCARRIER');add(q,'II-3','diagnosis','TEST disease')
        self.assertEqual('NOT_EVALUATED',result(q)['status'])
    def test_transcript_scope_requires_same_base_and_exact_version_when_specified(self):
        q=family();q['identity']['transcript']='NM_000002.1';self.assertEqual('NOT_EVALUATED',result(q)['status'])
        c=context();c['policy']['transcriptRestrictions']=['NM_000001.8']
        self.assertEqual('NOT_EVALUATED',result(family(),c)['status'])
    def test_other_candidates_and_cautions_not_silently_ignored(self):
        for kind,value in [('candidateScopeUnresolved','another variant'),('caution','possible adoption')]:
            q=family();add(q,'FAMILY',kind,value);self.assertEqual('NOT_EVALUATED',result(q)['status'])
    def test_same_observation_and_multiple_families_do_not_inflate_counts(self):
        q=family();q['observations']*=2;self.assertEqual(1,result(q)['awardedPP1Points'])
        second=family();second['families']={'F2':second['families']['F1']}
        for o in second['observations']:o['familyKey']='F2'
        q=family();q['families'].update(second['families']);q['observations']+=second['observations']
        self.assertEqual(1,result(q)['awardedPP1Points'])
    def test_existing_pp4_capacity_and_event_reuse_still_apply(self):
        l=ledger();l['scope']['diseaseId']='MONDO:0000001';l['pp4Points']=4
        e=result(family(5),log=l);self.assertEqual(1,e['awardedPP1Points'])
        l['eventIds']=[e['events'][0]['eventId']]
        self.assertEqual(0,result(family(5),log=l)['awardedPP1Points'])
    def test_contradictory_parentage_cannot_be_used_as_connected_count(self):
        q=family();add(q,'II-1','parentOf','I-1')
        self.assertEqual('NOT_EVALUATED',result(q)['status'])
        q=family();add(q,'II-2','parentOf','II-1');add(q,'II-3','parentOf','II-1')
        self.assertEqual('NOT_EVALUATED',result(q)['status'])
    def test_unimplemented_method_never_uses_simple_scoring(self):
        c=context();c['policy']['method']='UNKNOWN';self.assertEqual('NOT_EVALUATED',result(c=c)['status'])
    def test_new_selection_or_added_relatives_does_not_create_another_allocated_group(self):
        old=result()['events'][0]['eventId']
        q=family(5);q['target']['selectionKey']='another-selection'
        l=ledger();l['scope']['diseaseId']='MONDO:0000001';l['selectionKey']='another-selection'
        self.assertEqual(old,result(q,log=l)['events'][0]['eventId'])
        l['eventIds']=[old]
        self.assertEqual(0,result(q,log=l)['awardedPP1Points'])


if __name__=='__main__':unittest.main()
