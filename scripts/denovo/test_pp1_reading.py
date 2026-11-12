"""End-to-end synthetic public-source extraction -> replay -> qualification -> score.
No live model calls. These are not patient records or adopted disease policies.
"""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from pp1_literature_contract import VERSION, AUDIT_VERSION, claim_id, audit_check
from run_pp1_literature_model import run as extract, collect
from pp1_source_qualification import qualify, assertive, genomic_identity
from pp1_reading_loop import run, assess
from test_pp1_segregation import ALLELE, selection, context, ledger
from test_bundle_model import configuration, response
from test_literature_acquisition import jats
from prepare_literature import prepare
from acquire_public_literature import source_bundle
from paper_library_registration import register
from bundle_model_contract import load


class Resolver:
    def resolve_coding(self,gene,change,tx):
        if gene!='GENE1' or tx!='NM_000001.1' or change!='c.1A>G': raise ValueError('EXACT_TRANSCRIPT_UNAVAILABLE')
        return {'allele':deepcopy(ALLELE),'transcript':tx,'method':'SYNTHETIC_TEST_IDENTITY_NOT_REAL_REFERENCE'}


class Pipeline(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.library=self.root/'library';self.library.mkdir()
        text=('Family F1: GENE1 NM_000001.1:c.1A&gt;G. '
              'Only one candidate variant was identified in family F1. '
              'Candidate assessment in family F1 used SYNTHETIC complete candidate assessment. '
              'I-1 is heterozygous for c.1A&gt;G. I-1 is diagnosed with TEST disease. '
              'II-1 is diagnosed with TEST disease. I-1 is the father of II-1. '
              'The proband II-1 was ascertained.')
        extra='II-1 is heterozygous for c.1A&gt;G.'
        raw=jats(False).replace(b'<p>A public source.</p>',f'<p>{text}</p><p>{extra}</p><p>Laboratory follow-up for family F1.</p>'.encode())
        files,bundle=source_bundle({'pmcid':'PMC123'},raw,prepare(raw,'PMC123',digest(raw)),None,False)
        self.pin=register(self.library,'PMC123',files,bundle,{'sha256':digest(raw),'acquisitionUrl':'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'},criteria=('PP1',))['bundleSha256']
        self.source=load(self.library,'PMC123',self.pin,criterion='PP1')
        self.seed=next(u for u in self.source['units'] if 'Family F1:' in u['text'])
        self.extra=next(u for u in self.source['units'] if u['text']=='II-1 is heterozygous for c.1A>G.')
        self.target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
        self.cfg=self.root/'config.json';self.config=configuration();self.cfg.write_bytes(canonical(self.config))
        (self.root/'key.txt').write_bytes(b'SYNTHETIC_TEST_KEY')
        self.transports=0
        self.facts=[self.f('F02.5','FAMILY','Only one candidate variant was identified in family F1.'),
            self.f('F06.5','FAMILY','Candidate assessment in family F1 used SYNTHETIC complete candidate assessment.'),
            self.f('F06.4','I-1','I-1 is heterozygous for c.1A>G.'),
            self.f('F05.2','I-1','I-1 is diagnosed with TEST disease.'),
            self.f('F05.2','II-1','II-1 is diagnosed with TEST disease.'),
            self.f('F07.1','I-1','I-1 is the father of II-1.',related=['II-1'],kind='PARENT_OF'),
            self.f('F04.2','II-1','The proband II-1 was ascertained.'),
            self.f('F06.4','II-1','II-1 is heterozygous for c.1A>G.',unit=self.extra)]
    def f(self,field,member,quote,related=None,kind='NONE',unit=None):
        citation = 'I-1 is heterozygous for c.1A>G. ' + quote if quote=='I-1 is diagnosed with TEST disease.' else quote
        return {'fieldId':field,'familyLabel':'F1','memberLabel':member,'sourceValue':quote,
            'relatedMembers':related or [],'relationKind':kind,'citations':[{'unitId':(unit or self.seed)['unitId'],'quote':citation}]}
    def candidate(self,ids):
        return {'schemaVersion':VERSION,'families':[{'familyLabel':'F1',
            'anchor':{'unitId':self.seed['unitId'],'quote':'Family F1:'},
            'members':[{'memberLabel':m,'anchor':{'unitId':self.seed['unitId'],'quote':m}} for m in ['I-1','II-1']]}],
            'facts':[deepcopy(f) for f in self.facts if f['citations'][0]['unitId'] in ids],
            'coverage':'REVIEWED_SUPPLIED_SCOPE','groupChecks':[{'group':f'F{n:02}','outcome':'UNRESOLVED'} for n in range(1,11)]}
    def audit(self,c):
        return {'schemaVersion':AUDIT_VERSION,'audits':[{'claimId':claim_id(f),'verdict':'SUPPORTED','reason':'Exact synthetic source.',
            'evidence':f['citations']} for f in c['facts']],'additions':[],'additionalFamilies':[],
            'coverage':c['coverage'],'groupChecks':c['groupChecks']}
    def transport(self,c,k,body):
        self.transports+=1;request=parse(parse(body)['messages'][1]['content'])
        candidate=self.candidate([u['unitId'] for u in request['units']])
        return response(self.audit(request['original']) if 'original' in request else candidate)
    def seed_artifacts(self):
        args=(self.library,'PMC123',self.pin,self.target,[self.seed['unitId']],self.config,self.cfg,self.root/'budget')
        extract(*args,self.root/'seed-extract',execute=True,transport=self.transport)
        extract(*args,self.root/'seed-audit',previous=self.root/'seed-extract/artifact.json',execute=True,transport=self.transport)
        return [self.root/'seed-audit/artifact.json']
    def loop(self,artifacts,folder='loop',**kw):
        return run(self.library,artifacts,selection(),kw.pop('context',context()),kw.pop('ledger',ledger()),Resolver(),
            self.config,self.cfg,self.root/'budget',self.root/folder,transport=self.transport,**kw)
    def test_missing_then_read_audit_score_and_cache_reuse(self):
        a=self.seed_artifacts()
        first=assess(self.library,a,selection(),context(),ledger(),Resolver())[-1]
        self.assertEqual('NOT_EVALUATED',first['status'])
        r=self.loop(a,execute=True)
        self.assertEqual(('QUALIFIED_EVIDENCE_REACHED','MET',1),
            (r['stopReason'],r['evaluation']['status'],r['evaluation']['awardedPP1Points']))
        self.assertEqual(2,r['modelAttempts']);self.assertEqual(4,self.transports)
        self.assertEqual(1,len(r['evaluation']['events']));self.assertTrue(r['evaluation']['events'][0]['sourceRefs'])
        event=r['evaluation']['events'][0]
        self.assertEqual(('II-1','F1','PMC123'),(event['memberLabel'],event['familyLabel'],event['pmcid']))
        self.assertTrue(all(ref['pmcid']=='PMC123' and ref['bundleSha256']==self.pin for ref in event['sourceRefs']))
        again=self.loop(a,'cache-loop',execute=False)
        self.assertEqual('MET',again['evaluation']['status']);self.assertEqual(0,again['modelAttempts']);self.assertEqual(4,self.transports)
        restored=self.loop(r['acceptedArtifacts'],'restored-loop')
        self.assertEqual('MET',restored['evaluation']['status']);self.assertFalse(restored['rounds'])

    def test_rejected_member_anchor_preserved_and_other_block_continues(self):
        artifacts = self.seed_artifacts()
        from pp1_reading_loop import plan as real_plan
        audits = 0
        def transport(c, k, body):
            nonlocal audits
            self.transports += 1
            request = parse(parse(body)['messages'][1]['content'])
            candidate = self.candidate([u['unitId'] for u in request['units']])
            if 'original' not in request:
                return response(candidate)
            audits += 1
            audited = self.audit(request['original'])
            if audits == 1:
                family = deepcopy(candidate['families'][0])
                family['members'][0]['memberLabel'] = 'I-9'
                audited['additionalFamilies'] = [family]
            return response(audited)
        def two_blocks(source, result, qualification, target, attempted=()):
            reading = real_plan(source, result, qualification, target, attempted)
            # Two distinct scopes over genuine synthetic source units. Only the
            # schedule is fixed; model receipts, source checks and scoring run.
            if len(attempted) < 2:
                ids = [self.seed['unitId'], self.extra['unitId']] if not attempted else [u['unitId'] for u in source['units']]
                reading['selected'] = {'chunkKey': f'block-{len(attempted)}', 'unitIds': ids}
            return reading
        with patch('pp1_reading_loop.plan', side_effect=two_blocks):
            result = run(self.library, artifacts, selection(), context(), ledger(), Resolver(),
                self.config, self.cfg, self.root/'budget', self.root/'recovery',
                execute=True, transport=transport)
        self.assertEqual(('MET', 4, 6), (result['evaluation']['status'], result['modelAttempts'], self.transports), result)
        self.assertEqual(1, len(result['evaluation']['events']))
        self.assertEqual(2, len(result['acceptedArtifacts']))
        rejected = result['rejectedArtifacts'][0]
        self.assertEqual('PP1_MEMBER_NOT_ANCHORED', rejected['reasonCode'])
        self.assertTrue(Path(rejected['artifactPath']).is_file())
        self.assertNotIn(rejected['artifactPath'], result['acceptedArtifacts'])
        self.assertEqual('SOURCE_REJECTED_CONTINUE', result['rounds'][0]['status'])
        self.assertFalse(result['readingPlan']['wholePaperAbsenceClaim'])

    def test_rejected_addition_review_stays_pending_without_repeat(self):
        artifacts = self.seed_added_result()
        def transport(c, k, body):
            self.transports += 1
            request = parse(parse(body)['messages'][1]['content'])
            audited = self.audit(request['original'])
            family = deepcopy(self.candidate([self.seed['unitId']])['families'][0])
            family['members'][0]['memberLabel'] = 'I-9'
            audited['additionalFamilies'] = [family]
            return response(audited)
        result = run(self.library, artifacts, selection(), context(), ledger(), Resolver(),
            self.config, self.cfg, self.root/'budget', self.root/'rejected-addition',
            execute=True, transport=transport)
        self.assertEqual(('SOURCE_REVIEW_REJECTED_PENDING', 1, 3),
            (result['stopReason'], result['modelAttempts'], self.transports))
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual(1, len(result['acceptedArtifacts']))
        self.assertTrue(result['readingPlan']['pendingSourceReview']['claimIds'])
        self.assertEqual('PP1_MEMBER_NOT_ANCHORED', result['readingPlan']['sourceReviewFailures'][0]['reasonCode'])
    def seed_added_result(self):
        ids = [u['unitId'] for u in self.source['units']]
        candidate = self.candidate(ids)
        added = candidate['facts'].pop()
        audit = self.audit(candidate); audit['additions'] = [added]
        responses = iter((candidate, audit))
        def initial(*unused):
            self.transports += 1
            return response(next(responses))
        args = (self.library, 'PMC123', self.pin, self.target, ids, self.config, self.cfg, self.root/'budget')
        extract(*args, self.root/'added-extract', execute=True, transport=initial)
        extract(*args, self.root/'added-audit', previous=self.root/'added-extract/artifact.json',
                execute=True, transport=initial)
        return [self.root/'added-audit/artifact.json']

    def test_added_result_is_reviewed_without_new_block_or_extraction(self):
        artifacts = self.seed_added_result()
        before = assess(self.library, artifacts, selection(), context(), ledger(), Resolver())[-1]
        self.assertTrue(any(n['reasonCode'] == 'PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW' for n in before['needs']))
        result = self.loop(artifacts, execute=True)
        self.assertEqual(('MET', 1, 3), (result['evaluation']['status'], result['modelAttempts'], self.transports))
        self.assertEqual(['REVIEW_ADDITIONS'], [r['kind'] for r in result['rounds']])
        self.assertIsNone(result['rounds'][0]['extractionCacheHit'])
        self.assertIsNone(result['readingPlan']['pendingSourceReview'])
        again = self.loop(artifacts, 'added-cache', execute=False)
        self.assertEqual(('MET', 0, 3), (again['evaluation']['status'], again['modelAttempts'], self.transports))
        last_only = self.loop([result['acceptedArtifacts'][-1]], 'last-only', execute=False)
        self.assertEqual(('MET', 0, 3), (last_only['evaluation']['status'], last_only['modelAttempts'], self.transports))

    def test_pending_addition_cannot_bypass_budget_or_disabled_model(self):
        artifacts = self.seed_added_result()
        for folder, options, stop in [('disabled', {}, 'PP1_READING_MODEL_DISABLED_CACHE_MISSING'),
                                     ('no-budget', {'execute': True, 'maximum_calls': 0}, 'PP1_READING_CALL_LIMIT')]:
            with self.subTest(folder=folder):
                result = self.loop(artifacts, folder, **options)
                self.assertEqual(stop, result['stopReason'])
                self.assertEqual(0, result['modelAttempts']); self.assertEqual(2, self.transports)
                self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
                self.assertTrue(result['readingPlan']['pendingSourceReview']['claimIds'])
                self.assertNotIn('parentPath', result['readingPlan']['pendingSourceReview'])

    def test_known_external_gap_does_not_pay_for_pending_addition(self):
        artifacts = self.seed_added_result()
        result = self.loop(artifacts, execute=True, context={'status': 'KNOWLEDGE_MISSING'})
        self.assertEqual(('EXTERNAL_REQUIREMENT', 0, 2),
                         (result['stopReason'], result['modelAttempts'], self.transports))

    def test_pending_addition_does_not_bypass_an_unrelated_source_conflict(self):
        artifacts = self.seed_added_result()
        def conflicting(*args):
            source, inventory, qualified, evaluated = assess(*args)
            evaluated['needs'].append({'needId':'source-conflict','reasonCode':'PP1_SOURCE_CONFLICT',
                'route':'SOURCE_REVIEW','blockingEffect':'BLOCKS_SCORING','scope':{}})
            return source, inventory, qualified, evaluated
        with patch('pp1_reading_loop.assess', side_effect=conflicting):
            result = self.loop(artifacts, execute=True)
        self.assertEqual(('EXTERNAL_REQUIREMENT', 0, 2),
                         (result['stopReason'], result['modelAttempts'], self.transports))

    def test_cancel_stops_pending_review(self):
        artifacts = self.seed_added_result()
        result = self.loop(artifacts, execute=True, cancelled=lambda: True)
        self.assertEqual(('USER_CANCELLED', 0, 2),
                         (result['stopReason'], result['modelAttempts'], self.transports))
    def test_context_gap_stops_before_any_extra_model_call(self):
        a=self.seed_artifacts();r=self.loop(a,execute=True,context={'status':'KNOWLEDGE_MISSING'})
        self.assertEqual('EXTERNAL_REQUIREMENT',r['stopReason']);self.assertEqual(2,self.transports)
    def test_call_limit_keeps_unreviewed_extraction_not_score(self):
        a=self.seed_artifacts();r=self.loop(a,execute=True,maximum_calls=1)
        self.assertEqual('PP1_READING_CALL_LIMIT',r['stopReason']);self.assertEqual('NOT_EVALUATED',r['evaluation']['status'])
        self.assertEqual(3,self.transports);self.assertEqual(1,len(r['acceptedArtifacts']))
    def test_unparsed_image_not_reported_as_all_material_reviewed(self):
        artifacts=self.seed_artifacts()
        original=assess(self.library,artifacts,selection(),context(),ledger(),Resolver())
        source,inventory,q,e=deepcopy(original)
        source['coverage']={'status':'PACKAGE_ACQUIRED','parsedAttachments':0,'unparsedAttachments':1}
        source['attachments']=[{'originalName':'pedigree.jpg','sha256':'a'*64,
            'status':'ARCHIVED_NOT_PARSED','reasonCode':'PAPER_ATTACHMENT_FORMAT_UNSUPPORTED',
            'privatePath':'DO_NOT_EXPORT'}]
        q['reviewedUnitIds']=[u['unitId'] for u in source['units']]
        with patch('pp1_reading_loop.assess',return_value=(source,inventory,q,e)):
            result=self.loop(artifacts)
        self.assertEqual('SOURCE_FORMAT_REVIEW_REQUIRED',result['stopReason'])
        self.assertEqual(0,result['modelAttempts'])
        self.assertEqual(0,result['readingPlan']['remainingChunks'])
        coverage=result['readingPlan']['materialCoverage']
        self.assertEqual('pedigree.jpg',coverage['unparsedFiles'][0]['originalName'])
        self.assertFalse(coverage['wholePaperAbsenceClaim'])
        self.assertNotIn('DO_NOT_EXPORT',str(coverage))
    def test_time_limit_checked_before_transmission(self):
        a=self.seed_artifacts();r=self.loop(a,execute=True,seconds=1)
        self.assertEqual('PP1_READING_TIME_LIMIT',r['stopReason']);self.assertEqual(2,self.transports)
    def test_cancelled_not_source_absent(self):
        a=self.seed_artifacts();r=self.loop(a,execute=True,cancelled=lambda:True)
        self.assertEqual('USER_CANCELLED',r['stopReason']);self.assertEqual(2,self.transports)
    def test_offline_cache_miss_not_a_paid_retry(self):
        a=self.seed_artifacts();r=self.loop(a,execute=False)
        self.assertEqual('PP1_READING_MODEL_DISABLED_CACHE_MISSING',r['stopReason']);self.assertEqual(2,self.transports)
    def test_tampered_raw_artifact_refused(self):
        a=self.seed_artifacts();path=a[0];data=parse(path.read_bytes());data['candidate']['audits'][0]['verdict']='AMBIGUOUS';path.write_bytes(canonical(data))
        with self.assertRaises(ValueError):self.loop(a)
    def qualified(self,facts=None):
        ids=[self.seed['unitId'],self.extra['unitId']];c=self.candidate(ids)
        if facts is not None:c['facts']=facts
        inv=audit_check(self.source,c,self.audit(c),ids,self.target)
        return qualify(self.source,inv,selection(),Resolver())
    def test_source_full_chain_native_not_model_scores(self):
        q=self.qualified();self.assertTrue(q['identity']);self.assertTrue(any(r['kind']=='parentOf' for r in q['observations']))
        self.assertTrue(any(r['kind']=='genotype' and r['value']=='HETEROZYGOUS' for r in q['observations']))
    def test_performed_mark_not_carrier(self):
        f=self.f('F06.2','II-1','II-1')
        q=self.qualified([f]);self.assertFalse(any(r['kind']=='genotype' for r in q['observations']))
    def test_wrong_subject_proposal_does_not_take_other_result(self):
        f=self.f('F06.4','II-1','I-1 is heterozygous for c.1A>G.')
        q=self.qualified([f]);self.assertFalse(q['observations'])
    def test_same_coding_wrong_transcript_not_equal(self):
        c=self.candidate([self.seed['unitId'],self.extra['unitId']]);inv=audit_check(self.source,c,self.audit(c),[self.seed['unitId'],self.extra['unitId']],self.target)
        class Wrong:
            def resolve_coding(self,*args):return {'allele':{**ALLELE,'position':101}}
        q=qualify(self.source,inv,selection(),Wrong())
        self.assertEqual('PP1_SELECTED_ALLELE_MISMATCH',q['identityIssue']);self.assertFalse(any(r['kind']=='genotype' for r in q['observations']))
    def test_source_supported_but_unparsed_remains_visible(self):
        q=self.qualified([self.f('F05.2','II-1','The proband II-1 was ascertained.')])
        self.assertTrue(q['unresolved']);self.assertTrue(q['unresolved'][0]['sourceRefs'])
    def test_quoted_substring_cannot_hide_negation_or_hypothesis(self):
        quote='II-1 is heterozygous for c.1A>G.'
        for prefix in ('It is not true that ','If ','It is possible that ','We asked whether '):
            with self.subTest(prefix=prefix):self.assertFalse(assertive(prefix+quote,quote,0))
        self.assertTrue(assertive('Father was healthy. '+quote,quote,0))
    def test_genomic_source_pair_uses_reference_not_intronic_guess(self):
        from types import SimpleNamespace
        class Ref:
            identity={'scope':'SYNTHETIC'};observations=[]
            def recheck(self):pass
        class Local:
            reference=Ref()
            def load(self):pass
            def base(self,c,p):return 'A'
        source={'units':[{'unitId':'U','text':'GENE1 c.1+1A>G (g.hg38.chr1:100A>G).','sourceSha256':'a'*64}]}
        r=genomic_identity(source,{'U'},selection(),'c.1+1A>G',Local())
        self.assertEqual(ALLELE,r['allele']);self.assertTrue(r['sourceRefs'])
        source['units'][0]['text']+=' Also c.2A>G.'
        with self.assertRaisesRegex(ValueError,'PAIR_MISSING_OR_AMBIGUOUS'):
            genomic_identity(source,{'U'},selection(),'c.1+1A>G',Local())
        source['units'][0]['text']='GENE1 c.1+1A>G (g.hg38.chr1:100C>T).'
        with self.assertRaisesRegex(ValueError,'TARGET_MISMATCH'):
            genomic_identity(source,{'U'},selection(),'c.1+1A>G',Local())


if __name__=='__main__':unittest.main()
