"""Fixed scientific counterexamples and source-to-event/reading integration.

All positive policies and families in this module are synthetic test fixtures.
Real papers are separately replayed; no fixture is a clinical adoption record.
"""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from model_literature_contract import canonical, digest, parse
from pp1_segregation import evaluate
from pp1_source_qualification import qualify
from pp1_context import select, VERSION as CONTEXT_VERSION, METHOD


ALLELE = {'assembly':'GRCh38','contig':'1','position':100,'reference':'A','alternate':'G'}


def selection(moi='AD'):
    return {'selectionKey':'SYNTHETIC_SELECTION','geneSymbol':'GENE1','diseaseId':'TEST:1',
            'inheritance':moi,'allele':deepcopy(ALLELE)}


def context(moi='AD', complete=False):
    return {'status':'QUALIFIED','dataClass':'SYNTHETIC_TEST','policy':{
        'scope':{k:selection(moi)[k] for k in ('geneSymbol','diseaseId','inheritance')},
        'method':METHOD,'geneDiseaseValidity':'DEFINITIVE','expertPolicyStatus':'GENERAL_ALLOWED',
        'affectedTerms':['TEST disease'],'penetrance':'COMPLETE' if complete else 'AFFECTED_ONLY',
        'minimumUnaffectedAge':18,'phenocopy':'HEURISTIC_APPLICABLE','locusModel':'HETEROGENEOUS',
        'candidateAssessmentMethods':['SYNTHETIC complete candidate assessment']}}


def ledger(moi='AD', pp4=0):
    s = selection(moi)
    return {'status':'KNOWN','selectionKey':s['selectionKey'],'scope':{k:s[k] for k in ('geneSymbol','diseaseId','inheritance')},
            'allele':deepcopy(ALLELE),'pp4Points':pp4,'eventIds':[],'exclusiveFactIds':[]}


def fixture(moi='AD', family='F1'):
    q = {'target':selection(moi),'identity':{'allele':deepcopy(ALLELE)},'identityIssue':None,
            'sourceIdentity':{'pmcid':'PMC123','bundleSha256':'a'*64},
            'reviewedUnitIds':[],'families':{family:{'members':{}}},'observations':[],'unresolved':[]}
    add(q,'FAMILY','candidateMethod','SYNTHETIC complete candidate assessment',family)
    return q


def add(q, member, kind, value, family='F1'):
    q['families'].setdefault(family,{'members':{}})['members'][member] = member
    row = {'familyKey':family,'memberKey':member,'memberLabel':member,'kind':kind,'value':value,
           'sourceRefs':[{'unitId':'SYNTHETIC/'+member,'quote':str(value)}]}
    row['id'] = digest(canonical(row)); q['observations'].append(row)
    return row


def ad(children=1):
    q = fixture(); add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE')
    add(q,'I-1','genotype','HETEROZYGOUS'); add(q,'I-1','diagnosis','TEST disease')
    for n in range(children):
        m = f'II-{n+1}'
        add(q,m,'genotype','HETEROZYGOUS'); add(q,m,'diagnosis','TEST disease'); add(q,'I-1','parentOf',m)
    return q


class Events(unittest.TestCase):
    def evaluate(self,q,ctx=None,log=None):
        moi=q['target']['inheritance']
        return evaluate([q],ctx or context(moi),log or ledger(moi))
    def test_single_proband_parent_transmission_not_blanket_subtracted(self):
        q=ad(); add(q,'II-1','proband',True); r=self.evaluate(q)
        self.assertEqual(('MET','SUPPORTING','1'),(r['status'],r['strength'],r['rawSegregationPoints']))

    def test_reported_criteria_missing_mapping_is_not_reported_as_absent_source(self):
        q=ad();q['observations']=[o for o in q['observations'] if o['kind']!='diagnosis']
        add(q,'II-1','reportedDiagnosticCriteria','revised Example criteria')
        r=self.evaluate(q)
        self.assertEqual('NOT_EVALUATED',r['status'])
        issue=next(n for n in r['needs'] if n['reasonCode']=='PP1_REPORTED_DIAGNOSTIC_CRITERIA_NOT_BOUND')
        self.assertEqual('KNOWLEDGE',issue['route'])
        self.assertEqual('revised Example criteria',issue['knownFacts'][0]['value'])
        self.assertTrue(issue['sourceLocators'])
    def test_carrier_headcount_without_relationship_not_events(self):
        q=ad(); q['observations']=[r for r in q['observations'] if r['kind']!='parentOf']
        r=self.evaluate(q); self.assertEqual(('NOT_EVALUATED',0),(r['status'],len(r['events'])))
    def test_duplicate_citations_reverse_edges_do_not_add(self):
        q=ad(); q['observations']+=deepcopy(q['observations']); add(q,'II-1','childOf','I-1')
        self.assertEqual('1',self.evaluate(q)['rawSegregationPoints'])
    def test_unrelated_incomplete_relative_does_not_remove_event(self):
        q=ad(); add(q,'III-9','age',2); r=self.evaluate(q)
        self.assertEqual('MET',r['status']); self.assertTrue(any(n['blockingEffect']=='MAY_ADD_EVENT' for n in r['needs']))
    def test_generic_positive_and_zygosity_are_not_conflicting(self):
        q=ad(); add(q,'II-1','genotype','CARRIER'); self.assertEqual('MET',self.evaluate(q)['status'])
    def test_conflicting_genotype_not_cherry_picked(self):
        q=ad(); add(q,'II-1','genotype','NONCARRIER'); self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_affected_noncarrier_preserved_and_blocks_family(self):
        q=ad(); add(q,'II-2','genotype','NONCARRIER'); add(q,'II-2','diagnosis','TEST disease')
        r=self.evaluate(q); self.assertEqual(0,r['awardedPP1Points']); self.assertTrue(any(n['reasonCode']=='PP1_AFFECTED_NONCARRIER' for n in r['needs']))
    def test_incomplete_penetrance_keeps_unaffected_carrier_without_counting(self):
        q=ad(); add(q,'II-2','genotype','HETEROZYGOUS'); add(q,'II-2','clinical','UNAFFECTED')
        r=self.evaluate(q); self.assertEqual('1',r['rawSegregationPoints']); self.assertTrue(r['cautions'])
    def test_complete_penetrance_adult_unaffected_carrier_blocks(self):
        q=ad(); add(q,'II-2','genotype','HETEROZYGOUS'); add(q,'II-2','clinical','UNAFFECTED');add(q,'II-2','age',30)
        self.assertEqual('NOT_EVALUATED',self.evaluate(q,context(complete=True))['status'])
    def test_underage_unaffected_not_conflict_or_contribution(self):
        q=ad(); add(q,'II-2','genotype','HETEROZYGOUS'); add(q,'II-2','clinical','UNAFFECTED');add(q,'II-2','age',2)
        self.assertEqual('1',self.evaluate(q,context(complete=True))['rawSegregationPoints'])
    def test_unaffected_age_and_complete_penetrance_are_conditional(self):
        for age,complete,expected in [(30,True,'2'),(2,True,'1'),(30,False,'1'),(None,True,'1')]:
            q=ad();add(q,'II-2','genotype','NONCARRIER');add(q,'II-2','clinical','UNAFFECTED');add(q,'I-1','parentOf','II-2')
            if age is not None:add(q,'II-2','age',age)
            with self.subTest(age=age,complete=complete):self.assertEqual(expected,self.evaluate(q,context(complete=complete))['rawSegregationPoints'])
    def test_candidate_scope_unknown_is_not_single_candidate(self):
        q=ad();q['observations']=[r for r in q['observations'] if r['kind']!='candidateScope']
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_pp4_capacity_and_single_pp1_strength(self):
        for pp4,points,strength in [(0,4,'STRONG'),(1,4,'STRONG'),(2,2,'MODERATE'),(4,1,'SUPPORTING'),(5,0,None)]:
            with self.subTest(pp4=pp4):
                r=self.evaluate(ad(6),log=ledger(pp4=pp4)); self.assertEqual((points,strength),(r['awardedPP1Points'],r['strength']))
                self.assertLessEqual(points+pp4,5)
    def test_unsupported_ledger_or_different_disease_cannot_be_zero(self):
        self.assertEqual('NOT_EVALUATED',evaluate([ad()],context(),None)['status'])
        log=ledger();log['scope']['diseaseId']='OTHER'
        with self.assertRaisesRegex(ValueError,'LEDGER_SCOPE'):self.evaluate(ad(),log=log)
    def test_different_context_not_borrowed(self):
        c=context();c['policy']['scope']['geneSymbol']='OTHER'
        with self.assertRaisesRegex(ValueError,'CONTEXT_MISMATCH'):self.evaluate(ad(),c)
    def test_used_event_and_shared_fact_not_recounted(self):
        q=ad();r=self.evaluate(q)
        for key,vals in [('eventIds',[r['events'][0]['eventId']]),('exclusiveFactIds',r['events'][0]['factIds'][:1])]:
            log=ledger();log[key]=vals
            self.assertEqual('0',self.evaluate(q,log=log)['rawSegregationPoints'])
    def test_unknown_science_context_not_model_approval(self):
        self.assertEqual('NOT_EVALUATED',evaluate([ad()],{'status':'KNOWLEDGE_MISSING'},ledger())['status'])
    def test_high_yield_locus_does_not_stack_pp1(self):
        c=context();c['policy']['locusModel']='HIGH_YIELD_HOMOGENEOUS'
        r=self.evaluate(ad(6),c);self.assertEqual(('NOT_MET',0),(r['status'],r['awardedPP1Points']))
    def test_cycle_rejected(self):
        q=ad();add(q,'II-1','parentOf','I-1')
        self.assertTrue(any(n['reasonCode']=='PP1_COMPLEX_OR_CYCLIC_PEDIGREE' for n in self.evaluate(q)['needs']))
    def test_both_parents_affected_origin_not_guessed(self):
        q=ad();add(q,'I-2','genotype','HETEROZYGOUS');add(q,'I-2','diagnosis','TEST disease');add(q,'I-2','parentOf','II-1')
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_generic_parent_positive_is_not_a_half_probability_meiosis(self):
        q=ad()
        for r in q['observations']:
            if r['memberKey']=='I-1' and r['kind']=='genotype':r['value']='CARRIER'
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_other_parent_unaffected_carrier_still_origin_ambiguity(self):
        q=ad();add(q,'I-2','genotype','HETEROZYGOUS');add(q,'I-2','clinical','UNAFFECTED');add(q,'I-2','parentOf','II-1')
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_cross_paper_not_assumed_independent(self):
        q=ad();other=deepcopy(q);other['sourceIdentity']['pmcid']='PMC999'
        r=evaluate([q,other],context(),ledger())
        self.assertEqual(('MET','1'),(r['status'],r['rawSegregationPoints']))
        self.assertTrue(any(n['reasonCode']=='PP1_CROSS_PAPER_INDEPENDENCE_UNRESOLVED'
                            and n['blockingEffect']=='MAY_ADD_EVENT' for n in r['needs']))
    def test_cross_paper_uses_whole_family_lower_bound_not_a_sum(self):
        q=ad();other=ad(2);other['sourceIdentity']['pmcid']='PMC999'
        other['families']['F2']=other['families'].pop('F1')
        for row in other['observations']:row['familyKey']='F2'
        r=evaluate([q,other],context(),ledger())
        self.assertEqual(('MET','2'),(r['status'],r['rawSegregationPoints']))
        self.assertEqual({'F2'},{e['familyKey'] for e in r['events']})
        reverse=evaluate([other,q],context(),ledger())
        self.assertEqual(r['awardedPP1Points'],reverse['awardedPP1Points'])
    def test_cross_paper_adverse_fact_is_not_hidden_by_best_family(self):
        q=ad(4);other=ad();other['sourceIdentity']['pmcid']='PMC999'
        add(other,'II-9','genotype','NONCARRIER');add(other,'II-9','diagnosis','TEST disease')
        r=evaluate([q,other],context(),ledger())
        self.assertEqual(('NOT_EVALUATED',0),(r['status'],r['awardedPP1Points']))
    def ar(self,affected=1,unaffected=0):
        q=fixture('AR');add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE')
        for p in ('I-1','I-2'):add(q,p,'genotype','HETEROZYGOUS')
        add(q,'II-0','proband',True)
        for n in range(affected+1+unaffected):
            m=f'II-{n}';add(q,m,'genotype','HOMOZYGOUS' if n<=affected else 'HETEROZYGOUS')
            if n<=affected:add(q,m,'diagnosis','TEST disease')
            else:add(q,m,'clinical','UNAFFECTED');add(q,m,'age',30)
            for p in ('I-1','I-2'):add(q,p,'parentOf',m)
        return q
    def test_ar_anchor_not_scored_additional_affected_two(self):
        self.assertEqual('2',self.evaluate(self.ar())['rawSegregationPoints'])
        self.assertEqual('0',self.evaluate(self.ar(0))['rawSegregationPoints'])
    def test_ar_decimal_accumulation_before_floor(self):
        r=self.evaluate(self.ar(0,3),context('AR',True))
        self.assertEqual(('1.2',1),(r['rawSegregationPoints'],r['awardedPP1Points']))
    def test_ar_phase_parents_not_unaffected_contributions(self):
        q=self.ar()
        for p in ('I-1','I-2'):add(q,p,'clinical','UNAFFECTED');add(q,p,'age',30)
        # AR heterozygous parents are expected, not penetrance violations.
        self.assertEqual('2',self.evaluate(q,context('AR',True))['rawSegregationPoints'])
    def test_ar_missing_parent_cannot_establish_phase(self):
        q=self.ar();q['observations']=[r for r in q['observations'] if not(r['memberKey']=='I-2' and r['kind']=='genotype')]
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_ar_explicit_trans_full_siblings_no_partner_classification_or_trio_gate(self):
        q=fixture('AR');add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE');add(q,'II-1','proband',True)
        pair={'alleles':[deepcopy(ALLELE),{**ALLELE,'position':200}],'phase':'IN_TRANS','basis':'EXPLICIT_SOURCE_PHASE_AND_LOCAL_IDENTITIES'}
        for m in ('II-1','II-2'):
            add(q,m,'biallelic',deepcopy(pair));add(q,m,'diagnosis','TEST disease')
        add(q,'II-2','fullSiblingOf','II-1')
        r=self.evaluate(q);self.assertEqual(('MET','2','MODERATE'),(r['status'],r['rawSegregationPoints'],r['strength']))
        q['observations'][-1]['kind']='siblingOf'
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_ar_different_partner_and_cis_not_same_genotype(self):
        q=fixture('AR');add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE');add(q,'II-1','proband',True)
        for n in (1,2):
            add(q,f'II-{n}','biallelic',{'alleles':[deepcopy(ALLELE),{**ALLELE,'position':n+200}],'phase':'IN_TRANS','basis':'DIRECT'})
            add(q,f'II-{n}','diagnosis','TEST disease')
        add(q,'II-2','fullSiblingOf','II-1')
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_formal_lod_is_not_variant_bayes_factor(self):
        q=fixture();add(q,'FAMILY','authorLod',5)
        self.assertEqual(0,self.evaluate(q)['awardedPP1Points'])
    def test_only_one_candidate_claim_without_adequate_assessment_not_enough(self):
        q=ad();q['observations']=[r for r in q['observations'] if r['kind']!='candidateMethod']
        r=self.evaluate(q);self.assertEqual('NOT_EVALUATED',r['status'])
        self.assertTrue(any(n['reasonCode']=='PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED' for n in r['needs']))
    def test_other_candidate_statement_cannot_be_ignored(self):
        q=ad();add(q,'FAMILY','candidateScopeUnresolved','Another possible candidate was reported.')
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_unparsed_contributing_claim_does_not_get_silently_discarded(self):
        q=ad();q['unresolved']=[{'reasonCode':'PP1_TYPED_SEMANTICS_UNRESOLVED','memberKey':'II-1',
            'fieldId':'F06.3','claimId':'a'*64,'sourceRefs':[{'unitId':'U','quote':'Ambiguous target result'}]}]
        r=self.evaluate(q);self.assertEqual('NOT_EVALUATED',r['status'])
        self.assertTrue(any(n['sourceLocators'] and n['reasonCode']=='PP1_CONTRIBUTING_SOURCE_SEMANTICS_UNRESOLVED' for n in r['needs']))
    def test_unreviewed_adverse_addition_blocks_a_previously_positive_result(self):
        q=ad(); q['unresolved']=[{'claimId':'a'*64,'familyKey':'F1','memberKey':'II-1','memberLabel':'II-1',
            'fieldId':'F09.1','reviewStatus':'PENDING_ADDITION_REVIEW','sourceValue':'A possible conflicting observation.',
            'sourceRefs':[{'unitId':'U','quote':'A possible conflicting observation.'}]}]
        r=self.evaluate(q)
        self.assertEqual(('NOT_EVALUATED',0),(r['status'],r['awardedPP1Points']))
        n=next(n for n in r['needs'] if n['reasonCode']=='PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW')
        self.assertEqual('SOURCE_REVIEW',n['route']);self.assertTrue(n['sourceLocators'])
    def test_optional_age_addition_does_not_block_already_affected_event(self):
        q=ad(); q['unresolved']=[{'claimId':'b'*64,'familyKey':'F1','memberKey':'II-1','memberLabel':'II-1',
            'fieldId':'F05.4','reviewStatus':'PENDING_ADDITION_REVIEW','sourceValue':'20 years old','sourceRefs':[]}]
        self.assertEqual('MET',self.evaluate(q)['status'])
    def test_pending_native_phenotype_and_method_cannot_be_ignored(self):
        for field in ('F05.3','F06.5'):
            with self.subTest(field=field):
                q=ad();q['unresolved']=[{'claimId':'b'*64,'familyKey':'F1','memberKey':'II-1','memberLabel':'II-1',
                    'fieldId':field,'reviewStatus':'PENDING_ADDITION_REVIEW','sourceValue':'Unreviewed qualification detail','sourceRefs':[]}]
                self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_unaffected_age_relevant_to_complete_penetrance_is_reviewed(self):
        q=ad();add(q,'II-2','clinical','UNAFFECTED');add(q,'II-2','age',40)
        q['unresolved']=[{'claimId':'c'*64,'familyKey':'F1','memberKey':'II-2','memberLabel':'II-2',
            'fieldId':'F05.4','reviewStatus':'PENDING_ADDITION_REVIEW','sourceValue':'10 years old','sourceRefs':[]}]
        result=self.evaluate(q,context(complete=True))
        self.assertTrue(any(n['reasonCode']=='PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW' for n in result['needs']))
        self.assertEqual('MET',self.evaluate(q,context(complete=False))['status'])
    def test_native_phenotype_requires_specific_adopted_definition(self):
        q=ad();q['observations']=[r for r in q['observations'] if r['kind']!='diagnosis']
        add(q,'I-1','nativePhenotypes',['TEST disease']);add(q,'II-1','nativePhenotypes',['TEST disease'])
        self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
        c=context();c['policy']['affectedNativeTableTerms']=['TEST disease']
        self.assertEqual('MET',self.evaluate(q,c)['status'])
    def test_xlr_male_hemizygosity_and_mother(self):
        q=fixture('XLR');add(q,'FAMILY','candidateScope','SINGLE_REPORTED_CANDIDATE')
        add(q,'I-2','sex','FEMALE');add(q,'I-2','genotype','HETEROZYGOUS');add(q,'I-2','parentOf','II-1')
        add(q,'II-1','sex','MALE');add(q,'II-1','genotype','HEMIZYGOUS');add(q,'II-1','diagnosis','TEST disease')
        self.assertEqual('1',self.evaluate(q)['rawSegregationPoints'])
        add(q,'II-1','sex','FEMALE');self.assertEqual('NOT_EVALUATED',self.evaluate(q)['status'])
    def test_unsupported_inheritance_has_software_reason(self):
        q=ad();q['target']=selection('MT')
        r=self.evaluate(q);self.assertTrue(any(n['reasonCode']=='PP1_INHERITANCE_METHOD_UNSUPPORTED' for n in r['needs']))


class Context(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.root.joinpath('source.txt').write_bytes(b'SYNTHETIC policy, no clinical adoption.')
        row=context()['policy'];row.update(validFrom='2026-01-01',validThrough='2027-01-01',policyId='TEST',
            sources={'S':{'path':'source.txt','sha256':digest(self.root.joinpath('source.txt').read_bytes()),'quote':'SYNTHETIC policy','locator':'line1'}},
            decisionSources={k:['S'] for k in ('method','geneDiseaseValidity','expertPolicyStatus','affectedTerms','penetrance','phenocopy','locusModel','candidateAssessmentMethods')})
        self.doc={'schemaVersion':CONTEXT_VERSION,'entries':[row]};self.write()
    def write(self):
        raw=canonical(self.doc);self.root.joinpath('catalog.json').write_bytes(raw)
        receipt=canonical({'catalogSha256':digest(raw),'status':'ACCEPTED','scope':'SYNTHETIC_TEST'})
        self.root.joinpath('adoption.json').write_bytes(receipt)
        self.config={'catalog':{'path':'catalog.json','sha256':digest(raw)},'adoption':{'path':'adoption.json','sha256':digest(receipt)}}
    def test_exact_context_selected_with_pins(self):
        self.assertEqual('QUALIFIED',select(self.root,self.config,selection(),'2026-10-26')['status'])
    def test_no_catalog_reports_knowledge_gap(self):
        self.assertEqual('KNOWLEDGE_MISSING',select(self.root,None,selection())['status'])
    def test_different_disease_no_silent_general_fallback(self):
        s=selection();s['diseaseId']='OTHER';self.assertEqual('KNOWLEDGE_MISSING',select(self.root,self.config,s)['status'])
    def test_stale_or_changed_sources_refused(self):
        with self.assertRaisesRegex(ValueError,'EXPIRED'):select(self.root,self.config,selection(),'2028-01-01')
        self.root.joinpath('source.txt').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'PIN'):select(self.root,self.config,selection(),'2026-10-26')
    def test_unimplemented_expert_policy_not_replaced_by_general(self):
        self.doc['entries'][0]['expertPolicyStatus']='APPLICABLE_UNRESOLVED';self.write()
        with self.assertRaisesRegex(ValueError,'EXPERT_POLICY'):select(self.root,self.config,selection(),'2026-10-26')
    def test_dosage_not_gene_disease_validity(self):
        self.doc['entries'][0]['geneDiseaseValidity']='HI_SUFFICIENT';self.write()
        with self.assertRaisesRegex(ValueError,'VALIDITY'):select(self.root,self.config,selection(),'2026-10-26')


if __name__=='__main__':unittest.main()
