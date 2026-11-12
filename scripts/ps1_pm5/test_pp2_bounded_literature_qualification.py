"""Source-native structural and actual reference controls; no real clinical claims."""
from copy import deepcopy
from pathlib import Path
import os,shutil,sys,tempfile,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'denovo'))
from prepare_literature import prepare
from model_literature_contract import digest,canonical
from test_literature_acquisition import jats
from test_core_context_review import LocalFixture
from pp2_bounded_literature_qualification import qualify_paper, qualify_read_scope
import test_pp2_relation_reading as actual_reader

class BoundedQualificationTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
  self.fixture=LocalFixture(Path(self.temp.name)); self.resolver=self.fixture.resolver()
  resolved=self.resolver.resolve_coding('EXAMPLE','c.2C>G','NM_000001.1')
  self.target=dict(allele=resolved['allele'],geneSymbol='EXAMPLE',transcript='NM_000001.1',coding='c.2C>G',
    nativeModelSha256='a'*64,alleleAssociationSha256='b'*64,contextSha256='c'*64,hcmPolicyBindingSha256='d'*64)
  self.coverage=dict(status='RAW_QUESTION_SCOPE_READ')
 def data(self, clinical='Case 1 was diagnosed with hypertrophic cardiomyopathy.', coding='c.2C>G',label='1',extra=None):
  text=clinical+' Case '+label+' carried EXAMPLE NM_000001.1:'+coding+'.'
  if extra:text+=' '+' '.join(value for _,value in extra)
  raw=jats(False).replace(b'<p>A public source.</p>',('<sec><title>Case '+label+'</title><p>'+text+'</p></sec>').encode())
  doc=prepare(raw,'PMC123',digest(raw)); unit=next(u for u in doc['units'] if u['kind']=='BODY_PARAGRAPH')
  cite=dict(unitId=unit['unitId'],quote=unit['text'],sourceSha256=doc['sourceSha256'])
  fields=[dict(field=n,sourceValue=v,citation=deepcopy(cite),subject='PROBAND') for n,v in [('geneSymbol','EXAMPLE'),('transcript','NM_000001.1'),('variantText',coding)]]
  core=dict(cases=[dict(caseLabel=label,fields=fields,caseCitation=deepcopy(cite),caseOriginProposal='UNCLEAR')])
  qfacts=[dict(field='reportedClinicalDefinition',sourceValue=clinical,citation=deepcopy(cite))]
  if extra:qfacts.extend(dict(field=k,sourceValue=v,citation=deepcopy(cite)) for k,v in extra)
  q=dict(questionVersion='pp2-relationship-material-v1',workUnitSha256='e'*64,
   workUnit=dict(targetUnitIds=[unit['unitId']]),extractionCoverageProposal='EXTRACTED_WITHIN_TARGET',
   records=[dict(materialKindProposal='INDIVIDUAL_CASE',anchor=deepcopy(cite),facts=qfacts)],sourceAssessments=[dict(materialKindProposal='INDIVIDUAL_CASE',citation=deepcopy(cite))])
  source=dict(pmcid='PMC123',main=doc,bundleSha256='f'*64,units=doc['units'],coverage={},attachments=[])
  return source,[core],[q]
 def run_data(self,data,resolver=True,**kwargs):
  s,c,q=data; return qualify_paper(s,c,q,self.target,self.coverage,self.resolver if resolver else None,**kwargs)
 def test_actual_reference_same_target_and_explicit_hcm_only_terminates_that_observation(self):
  r=self.run_data(self.data()); self.assertEqual('COMPLETE_DECLARED_SCOPE',r['scopeStatus'])
  self.assertEqual('CURRENT_TARGET_HCM_PROCESS',r['observations'][0]['disposition'])
  self.assertEqual('EXACT',r['observations'][0]['targetMatch']); self.assertFalse(r['positiveOtherPhenotypeGranted'])
  self.assertTrue(r['observations'][0]['referenceReads']); self.assertTrue(r['observations'][0]['clinicalEvidenceRefs'])
 def test_actual_reference_resolved_different_allele_can_be_terminal_without_disease_causality(self):
  r=self.run_data(self.data(coding='c.3C>T')); row=r['observations'][0]
  self.assertEqual('EXACT_NON_TARGET',row['disposition']); self.assertEqual('NON_TARGET',row['targetMatch'])
  self.assertNotEqual(row['normalizedSourceAllele'],row['normalizedTargetAllele']); self.assertTrue(row['referenceReads'])
 def test_nonmatching_alias_without_reference_is_unknown_not_non_target(self):
  r=self.run_data(self.data(coding='c.3C>T'),resolver=False)
  self.assertEqual('UNRESOLVED',r['observations'][0]['targetMatch']); self.assertEqual('INCOMPLETE',r['scopeStatus'])
 def test_current_opaque_material_same_alias_fallback_is_not_available_for_other_alias(self):
  r=self.run_data(self.data(),resolver=False); self.assertEqual('EXACT',r['observations'][0]['targetMatch'])
  self.assertEqual('CURRENT_TARGET_HCM_PROCESS',r['observations'][0]['disposition'])
  self.assertTrue(r['observations'][0]['materialIdentity']); self.assertFalse(r['positiveOtherPhenotypeGranted'])
 def test_context_hcm_does_not_turn_generic_or_primary_dcm_case_into_hcm(self):
  for text in ['Case 1 was diagnosed with cardiomyopathy.','Case 1 was diagnosed with primary dilated cardiomyopathy.']:
   with self.subTest(text=text):
    r=self.run_data(self.data(clinical=text)); self.assertEqual('EXACT',r['observations'][0]['targetMatch'])
    self.assertEqual('UNRESOLVED',r['observations'][0]['disposition']); self.assertEqual('INCOMPLETE',r['scopeStatus'])
 def test_carrier_de_novo_and_author_causal_sentence_never_grants_other(self):
  r=self.run_data(self.data(clinical='Case 1 was diagnosed with primary dilated cardiomyopathy.',extra=[('reportedVariantDiseaseStatement','This variant was causative and de novo.')]))
  self.assertEqual('UNRESOLVED',r['observations'][0]['disposition']); self.assertFalse(r['positiveOtherPhenotypeGranted'])
 def test_family_suspected_and_negative_hcm_are_not_terminal_hcm(self):
  for text in ['Case 1 was not diagnosed with hypertrophic cardiomyopathy.','Case 1 had suspected hypertrophic cardiomyopathy.','Case 1 had a family history of hypertrophic cardiomyopathy.']:
   with self.subTest(text=text): self.assertEqual('UNRESOLVED',self.run_data(self.data(clinical=text))['observations'][0]['disposition'])
 def test_explicit_previously_hcm_then_dilation_is_not_primary_dcm(self):
  text='Case 1 was previously diagnosed with hypertrophic cardiomyopathy and later progressed to a dilated phase.'
  r=self.run_data(self.data(clinical=text)); self.assertEqual('CURRENT_TARGET_HCM_PROCESS',r['observations'][0]['disposition'])
 def test_cannot_borrow_another_case_clinical_facts_or_relative_disease(self):
  r=self.run_data(self.data(clinical="Case 1's mother was diagnosed with hypertrophic cardiomyopathy."))
  self.assertEqual('UNRESOLVED',r['observations'][0]['disposition'])
  s,c,q=self.data(); q[0]['records'][0]['anchor']['unitId']='/not-in-source'
  r=self.run_data((s,c,q)); self.assertEqual('UNRESOLVED',r['observations'][0]['disposition'])
 def test_unassigned_cohort_and_empty_extraction_stay_unknown(self):
  s,c,q=self.data(); q[0]['records']=[]
  r=self.run_data((s,c,q)); self.assertEqual('INCOMPLETE',r['scopeStatus'])
  self.assertTrue(any('EMPTY_EXTRACTION' in g['reasonCode'] for g in r['gaps']))
  s,c,q=self.data(); c[0]['cases'][0]['caseLabel']='foreign'
  self.assertEqual('UNRESOLVED',self.run_data((s,c,q))['observations'][0]['disposition'])
 def test_known_reference_visual_attachment_and_partial_output_block_scope(self):
  data=self.data(extra=[('reportedSupportingReference','Smith et al. support this association.')])
  self.assertEqual('INCOMPLETE',self.run_data(data)['scopeStatus'])
  self.assertEqual('INCOMPLETE',self.run_data(self.data(),reading_state={'unreviewedVisualCaptionUnitIds':['figure1']})['scopeStatus'])
  s,c,q=self.data(); s['coverage']={'unparsedAttachments':1}
  self.assertEqual('INCOMPLETE',self.run_data((s,c,q))['scopeStatus'])
  s,c,q=self.data(); q[0]['extractionCoverageProposal']='MORE_RECORDS_OR_FIELDS'
  self.assertEqual('INCOMPLETE',self.run_data((s,c,q))['scopeStatus'])
 def test_missing_or_unversioned_source_transcript_and_protein_only_do_not_prove_other(self):
  for value in ['NM_000001','ENSTunknown']:
   s,c,q=self.data(); c[0]['cases'][0]['fields'][1]['sourceValue']=value
   self.assertEqual('UNRESOLVED',self.run_data((s,c,q))['observations'][0]['targetMatch'])
  s,c,q=self.data(); c[0]['cases'][0]['fields'][2]['sourceValue']='p.Thr1Ser'
  self.assertEqual('UNRESOLVED',self.run_data((s,c,q))['observations'][0]['targetMatch'])

 def test_one_versioned_original_hgvs_does_not_require_redundant_transcript_field(self):
  for inline,want in [('NM_000001.1:c.2C>G','EXACT'),('c.2C>G','UNRESOLVED')]:
   with self.subTest(inline=inline):
    s,c,q=self.data(); fields=c[0]['cases'][0]['fields']
    fields[2]['sourceValue']=inline; del fields[1]
    row=self.run_data((s,c,q))['observations'][0]
    self.assertEqual(want,row['targetMatch'])
    if want=='EXACT': self.assertEqual(3,len(row['referenceReads']))

 def test_same_member_hcm_withdrawal_or_error_or_hcm_like_cannot_be_terminal(self):
  for text in ['Case 1 was diagnosed with hypertrophic cardiomyopathy. This diagnosis was later excluded.',
               'Case 1 was diagnosed with hypertrophic cardiomyopathy in error.',
               'Case 1 was diagnosed with hypertrophic cardiomyopathy-like disease.',
               'Case 1 was diagnosed with hypertrophic cardiomyopathy. Case 1 was later diagnosed with primary dilated cardiomyopathy.']:
   with self.subTest(text=text):self.assertEqual('UNRESOLVED',self.run_data(self.data(clinical=text))['observations'][0]['disposition'])
 def test_no_family_history_is_not_a_withdrawal_of_actual_patient_hcm(self):
  s,c,q=self.data(clinical='Case 1 was diagnosed with hypertrophic cardiomyopathy. There was no family history of heart disease.')
  q[0]['records'][0]['facts'][0]['sourceValue']='hypertrophic cardiomyopathy'
  r=self.run_data((s,c,q));self.assertEqual('CURRENT_TARGET_HCM_PROCESS',r['observations'][0]['disposition'])
 def test_parental_variant_cannot_be_borrowed_even_when_model_calls_it_proband(self):
  s,c,q=self.data()
  # An actual parsed source has only mother's carrier sentence, no current-case identity sentence.
  text='Case 1 was diagnosed with hypertrophic cardiomyopathy. His mother carried EXAMPLE NM_000001.1:c.2C>G.'
  xml=jats(False).replace(b'<p>A public source.</p>',('<sec><title>Case 1</title><p>'+text+'</p></sec>').encode())
  doc=prepare(xml,'PMC123',digest(xml));u=next(u for u in doc['units'] if u['kind']=='BODY_PARAGRAPH')
  s.update(main=doc,units=doc['units'])
  ref=dict(unitId=u['unitId'],quote=u['text'],sourceSha256=doc['sourceSha256'])
  for f in c[0]['cases'][0]['fields']:f['citation']=deepcopy(ref)
  q[0]['workUnit']['targetUnitIds']=[u['unitId']];q[0]['records'][0]['anchor']=deepcopy(ref)
  for f in q[0]['records'][0]['facts']:f['citation']=deepcopy(ref)
  q[0]['sourceAssessments'][0]['citation']=deepcopy(ref)
  r=self.run_data((s,c,q));self.assertEqual('UNRESOLVED',r['observations'][0]['targetMatch'])
 def test_function_and_cohort_proposal_cannot_inherit_clinical_case_identity(self):
  for kind in ['FUNCTIONAL_STUDY','COHORT','AUTHOR_DISCUSSION','CITED_MATERIAL']:
   s,c,q=self.data();q[0]['records'][0]['materialKindProposal']=kind
   self.assertEqual('UNRESOLVED',self.run_data((s,c,q))['observations'][0]['disposition'])
 def test_unassigned_source_assessment_is_not_erased_by_a_good_case_record(self):
  s,c,q=self.data();q[0]['sourceAssessments'].append(dict(materialKindProposal='COHORT',citation=deepcopy(q[0]['records'][0]['anchor'])))
  r=self.run_data((s,c,q));self.assertEqual('INCOMPLETE',r['scopeStatus'])
  self.assertTrue(any('SOURCE_ASSESSMENT_MATERIAL' in g['reasonCode'] for g in r['gaps']))
 def test_known_current_association_disagreement_is_not_other_allele_terminal(self):
  self.target['allele']={**self.target['allele'],'position':102}
  r=self.run_data(self.data());row=r['observations'][0]
  self.assertEqual('UNRESOLVED',row['targetMatch']);self.assertEqual('SOURCE_CONFLICT',row['identityDisposition'])
  self.assertNotEqual(row['normalizedSourceAllele'],row['normalizedTargetAllele']);self.assertTrue(row['referenceReads'])
 def test_same_clinical_hcm_fact_is_retained_while_unresolved_author_claim_stays_pending(self):
  r=self.run_data(self.data(extra=[('reportedVariantDiseaseStatement','We believe this variant is associated with a distinct disease.')]))
  row=r['observations'][0];self.assertEqual('CURRENT_TARGET_HCM_PROCESS',row['clinicalDisposition'])
  self.assertEqual('UNRESOLVED',row['disposition']);self.assertEqual('INCOMPLETE',r['scopeStatus'])

 def test_additional_diagnosis_is_not_erased_when_question_reports_only_hcm(self):
  s,c,q=self.data(clinical='Case 1 was diagnosed with hypertrophic cardiomyopathy. Case 1 was also diagnosed with Fabry disease.')
  q[0]['records'][0]['facts'][0]['sourceValue']='hypertrophic cardiomyopathy'
  r=self.run_data((s,c,q)); row=r['observations'][0]
  self.assertEqual('UNRESOLVED',row['disposition'])
  self.assertTrue(any('ADDITIONAL_OR_UNRESOLVED' in g['reasonCode'] for g in row['gaps']))

 def test_same_source_multiple_owned_variants_does_not_exclude_the_target_by_omission(self):
  s,c,q=self.data(coding='c.3C>T',extra=[('reportedObservationOrResult','Case 1 carried EXAMPLE NM_000001.1:c.2C>G.')])
  q[0]['records'][0]['facts'].pop()
  r=self.run_data((s,c,q))
  self.assertEqual('UNRESOLVED',r['observations'][0]['targetMatch'])
  self.assertTrue(any('UNIQUE_SOURCE_OWNED_ALLELE' in g['reasonCode'] for g in r['observations'][0]['gaps']))

 def test_repeated_native_table_patient_number_does_not_join_two_observations(self):
  from literature_case_structure import tables
  body='<table-wrap><label>Table 1</label><table><thead><tr><th>Patient</th><th>Diagnosis</th></tr></thead><tbody><tr><td>1</td><td>hypertrophic cardiomyopathy</td></tr></tbody></table></table-wrap>'
  body+='<table-wrap><label>Table 2</label><table><thead><tr><th>Patient</th><th>Variant</th></tr></thead><tbody><tr><td>1</td><td>NM_000001.1:c.2C&gt;G</td></tr></tbody></table></table-wrap>'
  xml=jats(False).replace(b'<p>A public source.</p>',body.encode())
  doc=prepare(xml,'PMC123',digest(xml)); found=tables(doc)
  self.assertEqual(2,len(found)); self.assertNotEqual(found[0]['tableId'],found[1]['tableId'])
  cite=deepcopy(found[1]['caseCitation'])
  source=dict(pmcid='PMC123',main=doc,bundleSha256='f'*64,units=doc['units'],coverage={},attachments=[])
  q=dict(questionVersion='pp2-relationship-material-v1',workUnitSha256='e'*64,
      workUnit=dict(targetUnitIds=[cite['unitId']]),extractionCoverageProposal='EXTRACTED_WITHIN_TARGET',
      records=[dict(materialKindProposal='INDIVIDUAL_CASE',anchor=cite,
          facts=[dict(field='reportedSubject',sourceValue=cite['quote'],citation=cite)])])
  row=self.run_data((source,[],[q]))['observations'][0]
  self.assertEqual('UNRESOLVED',row['targetMatch'])
  self.assertTrue(any('REPEATED_CASE_LABEL' in g['reasonCode'] for g in row['gaps']))

 def test_actual_read_scope_replays_each_artifact_and_detects_removed_chunk_even_if_plan_also_removed(self):
  fixture=actual_reader.RelationReadingTest()
  try:
   fixture.setUp()
   candidates=fixture.candidates(); raw=fixture.run_scope(candidates=candidates,known=[])
   self.assertTrue(raw['readingComplete'],raw.get('pending')); self.assertTrue(raw['papers'][0]['relationshipChunks'])
   def replay(ledger):
    return qualify_read_scope(fixture.config_path,digest(fixture.config_path.read_bytes()),ledger,fixture.root/'first',self.target,[],candidates)
   checked=replay(raw)
   self.assertTrue(checked['papers'][0]['observations'],checked['papers'][0]['gaps'])
   self.assertEqual('INCOMPLETE',checked['scopeStatus'])  # actual cohort record, no clinical authority
   mutant=deepcopy(raw); mutant['papers'][0]['relationshipChunks'].pop(); mutant['papers'][0]['relationshipWorkPlans'].pop()
   rejected=replay(mutant)
   self.assertIn('PP2_QUALIFICATION_ALL_ARTIFACT_CHUNKS_REQUIRED',[g['reasonCode'] for g in rejected['papers'][0]['gaps']])
  finally:fixture.doCleanups()

 def test_hcm_like_and_second_dcm_label_do_not_establish_same_hcm_progression(self):
  for text in [
    'Case 1 was previously diagnosed with hypertrophic cardiomyopathy-like disease and later developed ventricular dilation.',
    'Case 1 was previously diagnosed with hypertrophic cardiomyopathy in error and later developed ventricular dilation.',
    'Case 1 was previously diagnosed with hypertrophic cardiomyopathy and later developed dilated cardiomyopathy.',
   ]:
   with self.subTest(text=text):
    r=self.run_data(self.data(clinical=text))
    self.assertEqual('EXACT',r['observations'][0]['targetMatch'])
    self.assertEqual('UNRESOLVED',r['observations'][0]['disposition'])

 def test_negated_current_carrier_assertion_cannot_be_rewritten_as_proband_genotype(self):
  s,c,q=self.data()
  text='Case 1 was diagnosed with hypertrophic cardiomyopathy. Case 1 carried no detectable EXAMPLE NM_000001.1:c.2C>G.'
  xml=jats(False).replace(b'<p>A public source.</p>',('<sec><title>Case 1</title><p>'+text+'</p></sec>').encode())
  doc=prepare(xml,'PMC123',digest(xml));u=next(u for u in doc['units'] if u['kind']=='BODY_PARAGRAPH')
  s.update(main=doc,units=doc['units'])
  ref=dict(unitId=u['unitId'],quote=u['text'],sourceSha256=doc['sourceSha256'])
  for f in c[0]['cases'][0]['fields']:f['citation']=deepcopy(ref)
  q[0]['records'][0]['anchor']=deepcopy(ref)
  for f in q[0]['records'][0]['facts']:f['citation']=deepcopy(ref)
  q[0]['sourceAssessments'][0]['citation']=deepcopy(ref)
  row=self.run_data((s,c,q))['observations'][0]
  self.assertEqual('UNRESOLVED',row['targetMatch'])
  self.assertFalse(row['identityEvidenceRefs'])

 def test_maternal_question_result_cannot_borrow_proband_hcm_in_same_original_paragraph(self):
  s,c,q=self.data(extra=[('reportedObservationOrResult','His mother also carried the same variant.')])
  row=self.run_data((s,c,q))['observations'][0]
  self.assertEqual('UNRESOLVED',row['targetMatch'])
  self.assertEqual('UNRESOLVED',row['disposition'])
  self.assertIn('PP2_LITERATURE_CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED',[g['reasonCode'] for g in row['gaps']])

 def test_narrow_maternal_literal_still_has_the_original_member_in_its_sentence(self):
  s,c,q=self.data(extra=[('reportedObservationOrResult','His mother also carried NM_000001.1:c.2C>G.')])
  record=q[0]['records'][0]; ref=dict(record['anchor'],quote='also carried NM_000001.1:c.2C>G.')
  record.update(anchor=ref,facts=[dict(field='reportedObservationOrResult',sourceValue='c.2C>G',citation=ref)])
  q[0]['sourceAssessments'][0]['citation']=ref
  result=self.run_data((s,c,q));row=result['observations'][0]
  self.assertEqual('UNRESOLVED',row['targetMatch']);self.assertEqual('UNRESOLVED',row['disposition'])
  self.assertEqual('INCOMPLETE',result['scopeStatus']);self.assertFalse(row['clinicalEvidenceRefs'])
  self.assertIn('PP2_LITERATURE_CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED',[g['reasonCode'] for g in row['gaps']])

 def test_proven_non_target_case_genotype_does_not_close_unresolved_relationship_dependencies(self):
  for fact,value,expected in [
    ('reportedVariantDiseaseStatement','A variant was proposed to cause another disorder.','AUTHOR_CLAIM_DIRECTION_OR_OBJECT_NOT_INDEPENDENTLY_RESOLVED'),
    ('reportedSupportingReference','The cited publication describes this association.','SUPPORTING_REFERENCE_NOT_INDEPENDENTLY_REVIEWED'),
    ('reportedAlternativeExplanation','The patient may have a second causal explanation.','ALTERNATIVE_OR_CONTRADICTORY_EXPLANATION_UNRESOLVED'),
   ]:
   with self.subTest(fact=fact):
    result=self.run_data(self.data(coding='c.3C>T',extra=[(fact,value)])); row=result['observations'][0]
    self.assertEqual('NON_TARGET',row['targetMatch']);self.assertTrue(row['referenceReads'])
    self.assertEqual('UNRESOLVED',row['disposition']);self.assertEqual('INCOMPLETE',result['scopeStatus'])
    self.assertIn('PP2_LITERATURE_'+expected,[g['reasonCode'] for g in row['gaps']])

 def test_nonzero_exact_source_case_closes_actual_reader_scope_without_creating_clinical_causality(self):
  from model_literature_contract import parse
  from paper_download_transport import url_for
  fixture=actual_reader.RelationReadingTest()
  try:
   fixture.setUp()
   references=LocalFixture(fixture.root)
   fixture.config['coreContext']=references.put('core-context.json',canonical(dict(
    schemaVersion='denovo-core-context-config-v1',identity=references.config)))
   clinical='Case 1 was diagnosed with hypertrophic cardiomyopathy.'
   text=clinical+' Case 1 carried EXAMPLE NM_000001.1:c.2C>G.'
   raw_source=jats(False).replace(b'<p>A public source.</p>',('<sec><title>Case 1</title><p>'+text+'</p></sec>').encode())
   fixture.cache_response(url_for('PMC123','fullTextXML'),raw_source)
   def response(c,unused_key,body):
    request=parse(body); content=parse(request['messages'][1]['content'])
    target_ids=set(content['workUnit']['targetUnitIds'])
    unit=next(u for u in content['units'] if u['unitId'] in target_ids and u['text']==text)
    quote=lambda value: dict(unitId=unit['unitId'],quote=value)
    if 'questionContract' in content:
     candidate=dict(schemaVersion='pp2-relationship-material-candidates-v1',
        sourceIdentity=content['questionContract']['sourceIdentity'],workUnitSha256=content['questionContract']['workUnitSha256'],
        records=[dict(materialKind='INDIVIDUAL_CASE',anchor=quote('Case 1 was diagnosed'),
            facts=[dict(field='reportedClinicalDefinition',sourceValue='hypertrophic cardiomyopathy',citation=quote(clinical))])],
        sourceAssessments=[dict(materialKind='INDIVIDUAL_CASE',citation=quote(clinical))],coverage='EXTRACTED_WITHIN_TARGET')
    else:
     candidate=dict(schemaVersion='denovo-core-material-candidates-v1',coverage='EXTRACTED_WITHIN_TARGET',
        cases=[dict(caseLabel='1',caseCitation=quote('Case 1 was diagnosed'),caseOrigin='UNCLEAR',originCitations=[],
            fields=[dict(field=field,subject='PROBAND',sourceValue=value,citation=quote('Case 1 carried EXAMPLE NM_000001.1:c.2C>G.'))
                for field,value in [('geneSymbol','EXAMPLE'),('transcript','NM_000001.1'),('variantText','c.2C>G')]])],
        sourceAssessments=[dict(materialKind='INDIVIDUAL_CASE_MATERIAL',citation=quote(clinical))])
    return canonical(dict(model=c['expectedResponseModel'],usage=dict(prompt_tokens=10,completion_tokens=20,total_tokens=30),
        choices=[dict(finish_reason='stop',message=dict(content=canonical(candidate).decode()))]))
   # Citation-only is a real nonempty scope. Candidate-count zero is not the
   # existing no-papers shortcut.
   known=fixture.known
   raw=fixture.run_scope(candidates=[],known=known,model_transport=response)
   self.assertTrue(raw['readingComplete'],raw.get('pending'))
   checked=qualify_read_scope(fixture.config_path,digest(fixture.config_path.read_bytes()),raw,fixture.root/'first',self.target,known,[])
   self.assertEqual('COMPLETE_DECLARED_SCOPE',checked['scopeStatus'],checked['papers'])
   self.assertEqual((0,1),(checked['declaredCandidateCount'],checked['declaredKnownCitationCount']))
   row=checked['papers'][0]['observations'][0]
   self.assertEqual(('EXACT','CURRENT_TARGET_HCM_PROCESS'),(row['targetMatch'],row['disposition']))
   self.assertTrue(row['referenceReads']); self.assertFalse(checked['positiveOtherPhenotypeGranted'])
   output=os.environ.get('VARVIS_PP2_BOUNDED_SAMPLE')
   if output:
    root=Path(output);root.mkdir(parents=True,exist_ok=True)
    (root/'reading-ledger.json').write_bytes(canonical(raw))
    (root/'bounded-review.json').write_bytes(canonical(checked))
    shutil.copytree(fixture.root/'first'/'originals',root/'originals',dirs_exist_ok=True)
  finally:fixture.doCleanups()

if __name__=='__main__':unittest.main()
