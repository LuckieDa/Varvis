"""Public operational state from actual synthetic PP1 paper tasks, not new scoring."""
from copy import deepcopy
from pathlib import Path
import ast
import unittest

import evaluate_pp1_snapshot as snapshot
from model_literature_contract import canonical, digest, parse
import test_process_pp1_paper
from test_pp1_reading import Resolver
from test_pp1_segregation import context


class T02Pp1Projection(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paper=test_process_pp1_paper.PaperTask();cls.paper.setUp()
        cls.addClassCleanup(cls.paper.doCleanups)
        cls.parent=cls.document('cold',False)
        cls.child=cls.document('reviewed',True)
        cls.source=__import__('bundle_model_contract').load(cls.paper.fixture.library,'PMC123',cls.paper.fixture.pin,'PP1')

    @classmethod
    def document(cls,name,allow):
        p=cls.paper;f=p.fixture;result=p.run_paper(name,allow_model=allow)
        artifacts=parse((f.root/name/'artifacts.json').read_bytes())['acceptedArtifacts']
        return {'taskId':digest(name.encode()),'resultSha256':digest(canonical(result)),
          'request':{'pmcid':'PMC123','bundleSha256':f.pin,'analysisBinding':deepcopy(p.binding),
             'target':deepcopy(f.target),'selectionSha256':digest(canonical(p.binding['selection']))},
          'result':result,'artifacts':[{'path':str(Path(x).relative_to(f.root)),'sha256':digest(Path(x).read_bytes())} for x in artifacts]}

    def project(self,doc):
        return snapshot._public_source_coverage(doc,self.source)

    def changed(self,change):
        doc=deepcopy(self.child);change(doc);doc['resultSha256']=digest(canonical(doc['result']))
        return doc

    def test_actual_cold_task_exposes_system_required_available_and_difference(self):
        row=self.project(self.parent);state=row['readingState']
        self.assertEqual('AVAILABLE',state['projection']['status'])
        self.assertEqual('literature-stage-public-v1',row['projectionVersion'])
        self.assertEqual(self.parent['resultSha256'],row['capturedResultSha256'])
        self.assertEqual('CAPTURED_PAPER_RESULT_CANONICAL_SHA256',row['bindingPinKind'])
        self.assertEqual(1,state['required']['sourceReviewArtifactsAtLeast'])
        self.assertEqual(0,state['available']['acceptedReviewArtifacts'])
        gap=state['stageGaps'][0]
        self.assertEqual(('SYSTEM_STAGE','SOURCE_REVIEW'),(gap['kind'],gap['stage']))
        self.assertEqual(1,gap['difference']['missingMinimumReviewArtifacts'])
        self.assertEqual({'pmcid':'PMC123','bundleSha256':self.paper.fixture.pin},gap['scope'])
        self.assertFalse(state['wholePaperAbsenceClaim'])

    def test_actual_reviewed_child_preserves_source_pins_without_raw_evidence(self):
        doc=deepcopy(self.child);before=canonical(doc);row=self.project(doc)
        state=row['readingState'];self.assertEqual('AVAILABLE',state['projection']['status'])
        self.assertEqual([],state['stageGaps'])
        self.assertEqual(1,state['available']['acceptedReviewArtifacts'])
        self.assertEqual(self.child['result']['readingState']['checkpoint'],state['checkpoint'])
        self.assertEqual(self.child['request']['target'],row['target'])
        self.assertEqual(before,canonical(doc))

    def test_arbitrary_extras_paths_quotes_and_labels_do_not_leave_progress_row(self):
        private='D:/private/PATIENT_SECRET/file.json'
        def mutate(d):
            state=d['result']['readingState'];state['extra']={'quote':private}
            state['required']['extra']=private;state['available']['extra']=private
            state['checkpoint']['artifactPath']=private
            state['recovery']['message']=private
            state['missing']=[{'needId':'f'*64,'reasonCode':'PP1_TEST_REQUIRED','route':'LITERATURE',
               'blockingEffect':'BLOCKS_SCORING','scope':{'memberLabel':private},
               'requirement':private,'knownFacts':[{'value':private}], 'sourceLocators':[{'quote':private}], 'extra':private}]
            d['result']['reading']['readingPlan']['extra']=private
            d['result']['reading']['readingPlan']['pendingSourceReview']={'parentPath':private,'quote':private}
        row=self.project(self.changed(mutate));encoded=canonical(row).decode()
        self.assertNotIn(private,encoded);self.assertNotIn('PATIENT_SECRET',encoded)
        self.assertNotIn('quote',encoded);self.assertNotIn('memberLabel',encoded)
        need=row['readingState']['missing'][0]
        self.assertEqual((1,1),(need['knownFactCount'],need['sourceLocatorCount']))
        self.assertEqual(digest(canonical({'memberLabel':private})),need['scopeSha256'])

    def test_wrong_capture_or_source_binding_withholds_diagnostics(self):
        changes=[lambda d:d.update(taskId='../private'),lambda d:d['result'].update(pmcid='PMC999'),
          lambda d:d['result'].update(bundleSha256='0'*64),
          lambda d:d['result']['readingState']['checkpoint'].update(pmcid='PMC999'),
          lambda d:d['result']['readingState']['checkpoint'].update(bundleSha256='0'*64),
          lambda d:d['result']['target'].update(geneSymbol='OTHER'),
          lambda d:d['result']['target'].update(variantAliases=['c.9A>G']),
          lambda d:d['result'].update(analysisBindingSha256='0'*64)]
        for change in changes:
            with self.subTest(change=change):
                state=self.project(self.changed(change))['readingState']
                self.assertEqual('WITHHELD',state['projection']['status'])
                self.assertNotIn('available',state)
        doc=deepcopy(self.child);doc['resultSha256']='0'*64
        self.assertEqual('WITHHELD',self.project(doc)['readingState']['projection']['status'])

    def test_missing_union_scope_and_limit_keep_true_counts_without_raw_values(self):
        scopes=[None,'PRIVATE_MEMBER', ['PRIVATE_FAMILY'], {'memberLabel':'PRIVATE_MEMBER'}]
        def mutate(d):
            d['result']['readingState']['missing']=[{'needId':digest(str(i).encode()),'reasonCode':'PP1_REQUIRED',
              'route':'LITERATURE','blockingEffect':'BLOCKS_SCORING','scope':scopes[i%4],
              'knownFacts':[],'sourceLocators':[]} for i in range(140)]
        state=self.project(self.changed(mutate))['readingState']
        self.assertEqual(128,len(state['missing']))
        self.assertEqual((140,12),(state['projection']['missingTotal'],state['projection']['missingOmitted']))
        for i in range(4):self.assertEqual(digest(canonical(scopes[i])),state['missing'][i]['scopeSha256'])
        self.assertNotIn('PRIVATE_MEMBER',canonical(state).decode())
        self.assertLess(len(canonical(state)),262144)

    def test_boolean_count_and_inconsistent_gap_are_not_recast_as_zero(self):
        for change in [lambda d:d['result']['readingState']['available'].update(textUnits=True),
             lambda d:d['result']['readingState']['available'].update(remainingCallCapacity=65),
             lambda d:d['result']['readingState']['available'].update(acceptedReviewArtifacts=0),
             lambda d:d['result']['readingState']['recovery'].update(automaticRetryAllowed=True)]:
            self.assertEqual('WITHHELD',self.project(self.changed(change))['readingState']['projection']['status'])
        doc=deepcopy(self.parent);doc['result']['readingState']['stageGaps'][0]['difference']['missingMinimumReviewArtifacts']=0
        doc['resultSha256']=digest(canonical(doc['result']))
        self.assertEqual('WITHHELD',self.project(doc)['readingState']['projection']['status'])

    def test_old_missing_state_is_explicit_and_does_not_rerun_or_change_score(self):
        doc=deepcopy(self.child);doc['result'].pop('readingState');doc['resultSha256']=digest(canonical(doc['result']))
        f=self.paper.fixture
        result=snapshot.calculate(f.root,f.library,f.root,[doc],self.paper.binding,context(),Resolver())
        self.assertEqual('NOT_RECORDED',result['sourceCoverage'][0]['readingState']['projection']['status'])
        self.assertEqual(1,result['evaluation']['awardedPP1Points'])
        self.assertEqual((0,0),(result['modelCalls'],result['networkRequests']))
        self.assertFalse(any(n.get('kind')=='SYSTEM_STAGE' for n in result['evaluation']['needs']))

    def test_versioned_request_allows_its_short_spelling_but_not_different_version(self):
        def mutate(d):
            d['request']['target']['variantAliases']=['NM_000001.2:c.1A>G']
            d['result']['target']['variantAliases']=['NM_000001.2:c.1A>G','c.1A>G']
        doc=self.changed(mutate)
        self.assertEqual('AVAILABLE',self.project(doc)['readingState']['projection']['status'])
        doc['result']['target']['variantAliases']=['NM_000001.3:c.1A>G'];doc['resultSha256']=digest(canonical(doc['result']))
        self.assertEqual('WITHHELD',self.project(doc)['readingState']['projection']['status'])

    def test_malformed_progress_and_target_control_text_cannot_escape_or_crash_scoring(self):
        doc=self.changed(lambda d:d['result'].update(reading='D:/private/SECRET'))
        self.assertIsNone(self.project(doc)['readingPlan'])
        doc=self.changed(lambda d:d['request']['target'].update(variantAliases=['c.1A>G\nSECRET']))
        row=self.project(doc)
        self.assertNotIn('target',row)
        self.assertEqual('WITHHELD',row['readingState']['projection']['status'])
        self.assertNotIn('SECRET',canonical(row).decode())

    def test_printed_hgvs_spaces_are_preserved_but_control_characters_are_rejected(self):
        doc=self.changed(lambda d:d['request']['target'].update(variantAliases=['c.1A > G']))
        row=self.project(doc)
        self.assertEqual('AVAILABLE',row['readingState']['projection']['status'])
        self.assertEqual(['c.1A > G'],row['target']['variantAliases'])
        for character in ('\t','\r','\n',chr(0),chr(31),chr(127)):
            doc=self.changed(lambda d:d['request']['target'].update(variantAliases=['c.1A'+character+'>G']))
            self.assertEqual('WITHHELD',self.project(doc)['readingState']['projection']['status'])

    def test_printed_protein_parentheses_use_existing_notation_without_false_mismatch(self):
        def mutate(d):
            d['request']['target']['variantAliases']=['NP_000001.2:p.Glu7Val']
            d['result']['target']['variantAliases']=['p(Glu7 Val)']
        row=self.project(self.changed(mutate))
        self.assertEqual('AVAILABLE',row['readingState']['projection']['status'])
        self.assertEqual(['NP_000001.2:p.Glu7Val'],row['target']['variantAliases'])

    def test_material_coverage_unsafe_filename_is_withheld_without_changing_missing_count(self):
        source=deepcopy(self.source);source['attachments']=[{'originalName':'D:/private/secret.pdf','sha256':'c'*64,'status':'ARCHIVED_NOT_PARSED','extra':'PRIVATE'}]
        row=snapshot._public_source_coverage(self.child,source);coverage=row['materialCoverage']
        self.assertEqual(1,coverage['unparsedFileCount'])
        self.assertNotIn('originalName',coverage['unparsedFiles'][0])
        self.assertEqual(1,coverage['unparsedFileNamesWithheld'])
        self.assertNotIn('D:/private',canonical(row).decode())

    def rich_need(self, **extra):
        return {'needId':'e'*64,'reasonCode':'PP1_MEMBER_EVENT_INPUT_INCOMPLETE','route':'LITERATURE',
                'blockingEffect':'MAY_ADD_EVENT','knownFacts':[],'sourceLocators':[], 'scope':None, **extra}

    def with_need(self, need):
        return self.changed(lambda d:d['result']['readingState'].update(missing=[need]))

    def test_typed_known_values_are_shown_but_the_complete_source_paragraph_is_withheld(self):
        paragraph='Family PRIVATE_MEMBER has c.1A>G. This whole paragraph must not become a typed fact.'
        facts=[{'kind':'genotype','value':'HETEROZYGOUS'},{'kind':'proband','value':True},
               {'kind':'age','value':42},{'kind':'parentOf','value':'a'*64},
               {'kind':'reportedVariant','value':'NM_000001.2:c.1A>G'},
               {'kind':'reportedVariant','value':paragraph},{'kind':'unknownKind','value':True},
               {'kind':'genotype','value':'PRIVATE_MEMBER is a carrier'}]
        need=self.project(self.with_need(self.rich_need(knownFacts=facts)))['readingState']['missing'][0]
        self.assertEqual(['ENUM','BOOLEAN','INTEGER','OPAQUE_MEMBER_ID','HGVS_LITERAL'],[f['valueType'] for f in need['knownFacts']])
        self.assertEqual([f['value'] for f in facts[:5]],[f['value'] for f in need['knownFacts']])
        self.assertTrue(all(f['interpretation']=='CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION' for f in need['knownFacts']))
        self.assertEqual({'status':'PARTIAL','total':8,'displayed':5,'withheld':3,'omitted':0,
            'reasonCode':'FREE_TEXT_OR_UNSUPPORTED_TYPE_WITHHELD'},need['knownFactsProjection'])
        self.assertNotIn('PRIVATE_MEMBER',canonical(need).decode())

    def test_scope_required_inputs_and_requirement_come_from_native_fields_and_static_program_catalog(self):
        scope={'pmcid':'PMC123','bundleSha256':self.paper.fixture.pin,'mainSha256':self.source['main']['sourceSha256'],
               'familyKey':'a'*64,'memberKey':'b'*64,'claimId':'c'*64,'memberLabel':'PRIVATE_MEMBER',
               'missingInputs':['CLINICAL_STATUS','TARGET_GENOTYPE']}
        raw=self.rich_need(scope=scope,requirement='INJECTED PRIVATE TEXT',ruleClause='PRIVATE/path')
        need=self.project(self.with_need(raw))['readingState']['missing'][0]
        self.assertEqual({'pmcid':'PMC123','bundleSha256':self.paper.fixture.pin,'mainSha256':self.source['main']['sourceSha256']},need['scope']['paper'])
        self.assertEqual(('a'*64,'b'*64,'c'*64),(need['scope']['familyKey'],need['scope']['memberKey'],need['scope']['claimId']))
        self.assertEqual(['CLINICAL_STATUS','TARGET_GENOTYPE'],need['requiredInputs'])
        self.assertEqual('AVAILABLE',need['requiredInputsProjection']['status'])
        self.assertEqual('PARTIAL',need['scopeProjection']['status'])
        self.assertEqual(snapshot.REQUIREMENTS[raw['reasonCode']],need['requirement'])
        self.assertEqual({'status':'AVAILABLE','source':'PROGRAM_REQUIREMENTS_CATALOG'},need['requirementProjection'])
        self.assertNotIn('memberLabel',canonical(need).decode());self.assertNotIn('INJECTED',canonical(need).decode())
        self.assertNotIn('ruleClause',need)
        raw['reasonCode']='PP1_UNRECOGNIZED_TEST_REQUIREMENT'
        need=self.project(self.with_need(raw))['readingState']['missing'][0]
        self.assertNotIn('requirement',need);self.assertEqual('NOT_RECORDED',need['requirementProjection']['status'])

    def test_native_opaque_scope_scalars_and_lists_do_not_gain_invented_member_roles(self):
        for raw in ['a'*64,['a'*64,'b'*64]]:
            need=self.project(self.with_need(self.rich_need(scope=raw)))['readingState']['missing'][0]
            self.assertEqual(raw,need['scope'])
            self.assertIn(need['scopeProjection']['kind'],('OPAQUE_ID','OPAQUE_ID_LIST'))
            self.assertNotIn('memberKey',canonical(need).decode());self.assertNotIn('familyKey',canonical(need).decode())

    def test_registered_source_units_and_pin_only_references_are_distinct_from_unverified_locators(self):
        unit=self.source['units'][0];uid=unit['unitId'];pin=unit['sourceSha256']
        refs=[{'unitId':uid,'quote':'PRIVATE SOURCE QUOTE','sourceSha256':pin},
              {'sourceSha256':pin}, {'unitId':uid,'sourceSha256':'f'*64},
              {'unitId':'D:/private/secret','sourceSha256':pin}]
        need=self.project(self.with_need(self.rich_need(sourceLocators=refs)))['readingState']['missing'][0]
        self.assertEqual(['JATS_UNIT','SOURCE_PIN_ONLY'],[r['kind'] for r in need['sourceLocators']])
        self.assertEqual('MATCHED_REGISTERED_SOURCE_UNIT',need['sourceLocators'][0]['bindingStatus'])
        self.assertEqual(uid,need['sourceLocators'][0]['unitId']);self.assertEqual(pin,need['sourceLocators'][0]['sourceSha256'])
        self.assertEqual('REGISTERED_PIN_WITHOUT_VERIFIED_LOCATOR',need['sourceLocators'][1]['bindingStatus'])
        self.assertEqual({'status':'PARTIAL','total':4,'displayed':2,'withheld':2,'omitted':0},need['sourceLocatorProjection'])
        self.assertNotIn('PRIVATE',canonical(need).decode());self.assertNotIn('quote',canonical(need).decode())

    def test_fact_and_locator_limits_keep_exact_total_withheld_and_omitted_counts(self):
        unit=self.source['units'][0];fact={'kind':'proband','value':True};ref={'unitId':unit['unitId']}
        need=self.project(self.with_need(self.rich_need(knownFacts=[fact]*10,sourceLocators=[ref]*10)))['readingState']['missing'][0]
        self.assertEqual((10,8,0,2),tuple(need['knownFactsProjection'][k] for k in ('total','displayed','withheld','omitted')))
        self.assertEqual((10,8,0,2),tuple(need['sourceLocatorProjection'][k] for k in ('total','displayed','withheld','omitted')))
        self.assertEqual((10,10),(need['knownFactCount'],need['sourceLocatorCount']))

    def test_whole_literal_matching_does_not_extract_variant_from_prose_or_arbitrary_accession(self):
        facts=[{'kind':'reportedVariant','value':v} for v in ['A c.1A>G variant was found',
            'c.1A>G PRIVATE','PRIVATE_MEMBER:p.Glu7Val','NP_000001.2:p.Glu7Val']]
        need=self.project(self.with_need(self.rich_need(knownFacts=facts)))['readingState']['missing'][0]
        self.assertEqual(1,len(need['knownFacts']));self.assertEqual('NP_000001.2:p.Glu7Val',need['knownFacts'][0]['value'])
        self.assertEqual(3,need['knownFactsProjection']['withheld'])

    def test_malformed_system_gap_scope_is_withheld_without_changing_calculated_science(self):
        f=self.paper.fixture
        baseline=snapshot.calculate(f.root,f.library,f.root,[self.child],self.paper.binding,context(),Resolver())['evaluation']
        for malformed in (None,[],'PRIVATE_MEMBER_SCOPE'):
            with self.subTest(scope=malformed):
                doc=deepcopy(self.child)
                gap=deepcopy(self.parent['result']['readingState']['stageGaps'][0]);gap['scope']=malformed
                doc['result']['readingState']['stageGaps']=[gap];doc['resultSha256']=digest(canonical(doc['result']))
                result=snapshot.calculate(f.root,f.library,f.root,[doc],self.paper.binding,context(),Resolver())
                self.assertEqual('WITHHELD',result['sourceCoverage'][0]['readingState']['projection']['status'])
                self.assertEqual(baseline,result['evaluation'])
                self.assertEqual((0,0),(result['modelCalls'],result['networkRequests']))

    def test_display_route_fallback_mirrors_the_existing_readonly_producer_literal(self):
        source=ast.parse(Path(__file__).with_name('pp1_segregation.py').read_text(encoding='utf-8'))
        found=[]
        for node in ast.walk(source):
            if (isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr=='get'
                    and isinstance(node.func.value,ast.Dict) and node.args and isinstance(node.args[0],ast.Name)
                    and node.args[0].id=='route'):
                found.append(ast.literal_eval(node.func.value))
        self.assertEqual(1,len(found))
        self.assertEqual(found[0],snapshot._ROUTE_REQUIREMENTS)
        self.assertEqual(6,len(snapshot._ROUTE_REQUIREMENTS))

    def test_normal_context_and_identity_needs_use_static_route_text_without_raw_override(self):
        for code,route in [('PP1_QUALIFIED_CONTEXT_UNAVAILABLE','KNOWLEDGE'),
                           ('PP1_LOCAL_IDENTITY_PROVIDER_UNAVAILABLE','IDENTITY_RESOURCE')]:
            with self.subTest(code=code):
                self.assertNotIn(code,snapshot.REQUIREMENTS)
                raw=self.rich_need(reasonCode=code,route=route,requirement='PRIVATE INJECTED REQUIREMENT')
                need=self.project(self.with_need(raw))['readingState']['missing'][0]
                self.assertEqual(snapshot._ROUTE_REQUIREMENTS[route],need['requirement'])
                self.assertEqual({'status':'AVAILABLE','source':'PROGRAM_ROUTE_REQUIREMENTS_CATALOG',
                    'fallbackRoute':route},need['requirementProjection'])
                self.assertNotIn('PRIVATE',canonical(need).decode())
        raw=self.rich_need(reasonCode='PP1_UNRECOGNIZED_TEST_REQUIREMENT',route='UNKNOWN_ROUTE',
                           requirement='PRIVATE INJECTED REQUIREMENT')
        need=self.project(self.with_need(raw))['readingState']['missing'][0]
        self.assertNotIn('requirement',need)
        self.assertEqual('NOT_RECORDED',need['requirementProjection']['status'])

    def test_actual_calculate_keeps_scientific_result_separate_from_public_stage_state(self):
        f=self.paper.fixture;doc=deepcopy(self.child)
        result=snapshot.calculate(f.root,f.library,f.root,[doc],self.paper.binding,context(),Resolver())
        self.assertEqual('AVAILABLE',result['sourceCoverage'][0]['readingState']['projection']['status'])
        self.assertEqual(1,result['evaluation']['awardedPP1Points'])
        self.assertEqual(1,len(result['evaluation']['events']))
        self.assertFalse(any(n.get('kind')=='SYSTEM_STAGE' for n in result['evaluation']['needs']))

if __name__=='__main__':unittest.main()
