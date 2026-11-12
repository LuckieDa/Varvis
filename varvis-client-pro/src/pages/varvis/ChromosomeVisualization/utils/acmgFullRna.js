// Reads server-projected full RNA use. Transport consistency never creates source qualification.
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const own=(v,k)=>object(v)&&Object.hasOwn(v,k);
const sha=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const text=v=>typeof v==='string'&&v.length>0&&v.length<=512;
const reason=v=>typeof v==='string'&&/^[A-Z][A-Z0-9_]{1,180}$/.test(v);
const integer=v=>Number.isSafeInteger(v);
const required=(value,code)=>{if(!value)throw Error(code);};
const METHOD='SOURCE_BOUND_FULL_RNA_CONSEQUENCE';
const applicationVersions=['1.0.0','1.1.0'];
const pvs1AssessmentVersions={'1.9.0':'1.0.0','1.10.0':'1.1.0','1.10.1':'1.1.1'};
const ids=['PVS1','PM4','BP3'];
const factKeys={PVS1:'pvs1Facts',PM4:'proteinLengthFacts',BP3:'bp3Facts'};
const strength={NONE:0,SUPPORTING:1,MODERATE:2,STRONG:3,VERY_STRONG:4};
const codeOf=c=>c?.criterionId?.split(':').pop();
const same=(a,b,hash)=>hash(a)===hash(b);
const body=(v,key)=>Object.fromEntries(Object.entries(v).filter(([k])=>k!==key));

// A wire-method check only. It does not qualify a source, execute a rule or adopt evidence.
export function fullRnaPvs1MethodValid(f,cap,claim,originalEvaluation){
    if(typeof cap?.pvs1Version!=='string'||!own(pvs1AssessmentVersions,cap.pvs1Version)||f?.methodVersion!==cap.pvs1Version)return false;
    const a=f.fullRnaAssessment;
    if(object(a)&&(a.schemaVersion!=='pvs1-full-rna-assessment-v1'||a.methodVersion!==pvs1AssessmentVersions[cap.pvs1Version]||
        !['MET','NOT_MET','NOT_EVALUATED','NOT_APPLICABLE'].includes(a.status)))return false;
    // Older known tuples omit these later display fields; absence makes no execution claim.
    if(!own(f,'evaluationState')&&!own(f,'evaluationMethodVersion'))return true;
    if(f.evaluationState==='EXECUTED')return f.evaluationMethodVersion===cap.pvs1Version&&object(a)&&
        (originalEvaluation===undefined||object(originalEvaluation)&&originalEvaluation.deterministicTrace?.evaluatorVersion===f.evaluationMethodVersion&&
            originalEvaluation.deterministicTrace?.evaluatorId==='transcript-truncating-nmd-pvs1');
    return f.evaluationState==='NOT_EXECUTED'&&f.evaluationMethodVersion===null&&!object(a)&&
        claim?.originalStatus==='NOT_EVALUATED'&&claim.originalStrength==='NONE'&&claim.application==='NOT_ADOPTED'&&
        (originalEvaluation===undefined||object(originalEvaluation)&&originalEvaluation.evaluationResult?.status==='NOT_EVALUATED'&&!text(originalEvaluation.deterministicTrace?.evaluatorVersion));
}

// Typed completion is only one part of the source/current-run proof checked below.
export function fullRnaCompletedExclusionValid(f,cap,claim,originalEvaluation){
    const p=f?.fullRnaUse,a=f?.fullRnaAssessment,b=p?.preparation?.binding;
    return p?.methodVersion==='1.1.0'&&cap?.methodVersion==='1.1.0'&&cap.pvs1Version==='1.10.1'&&
        claim?.criterionId==='ACMG_AMP_2015:PVS1'&&claim.completedExclusion===true&&
        claim.originalStatus==='NOT_EVALUATED'&&claim.originalStrength==='NONE'&&claim.application==='NOT_ADOPTED'&&
        claim.originalApplicability==='NOT_APPLICABLE'&&claim.originalExecutionStatus==='COMPLETED'&&
        f.methodVersion==='1.10.1'&&f.evaluationState==='EXECUTED'&&f.evaluationMethodVersion==='1.10.1'&&
        a?.schemaVersion==='pvs1-full-rna-assessment-v1'&&a.methodVersion==='1.1.1'&&a.status==='NOT_APPLICABLE'&&
        sha(b?.originalSha256)&&sha(b.mechanicalEffectSha256)&&sha(b.factsSha256)&&
        a.sourceOriginalSha256===b.originalSha256&&a.mechanicalEffectSha256===b.mechanicalEffectSha256&&a.factsSha256===b.factsSha256&&
        (originalEvaluation===undefined||object(originalEvaluation)&&originalEvaluation.evaluationResult?.status==='NOT_EVALUATED'&&
            originalEvaluation.appliedStrength==='NONE'&&originalEvaluation.criterionApplicability?.status==='NOT_APPLICABLE'&&
            originalEvaluation.deterministicTrace?.executionStatus==='COMPLETED'&&
            originalEvaluation.deterministicTrace?.evaluatorId==='transcript-truncating-nmd-pvs1'&&
            originalEvaluation.deterministicTrace?.evaluatorVersion==='1.10.1');
}

// Reads a saved start-loss assessment; it never selects a start or supplies protein lengths.
export function fullRnaInitiationPositiveValid(assessment,initiation,sequence,claim,assessmentKind){
    return assessmentKind==='INITIATION_LOSS'&&assessment?.schemaVersion==='pvs1-full-rna-assessment-v1'&&['1.1.0','1.1.1'].includes(assessment.methodVersion)&&
        assessment.status==='MET'&&assessment.adoptedStrength===claim?.originalStrength&&claim.originalStatus==='MET'&&strength[claim.originalStrength]>0&&
        initiation?.schemaVersion==='pvs1-full-rna-initiation-assessment-v1'&&initiation.methodVersion==='1.0.0'&&
        initiation.status==='MET'&&strength[initiation.strength]>=strength[claim.originalStrength]&&
        sha(assessment.sourceOriginalSha256)&&sha(assessment.mechanicalEffectSha256)&&
        initiation.sourceOriginalSha256===assessment.sourceOriginalSha256&&initiation.mechanicalEffectSha256===assessment.mechanicalEffectSha256&&
        sequence?.nativeStart?.retainedContiguously===false&&
        ['referenceRnaLength','alteredRnaLength','referenceProteinLength'].every(k=>integer(sequence[k])&&sequence[k]>0);
}

export function fullRnaCriterionClaim(c){
    const code=codeOf(c),f=c?.details?.[factKeys[code]];
    return ids.includes(code)&&(own(c?.details,'fullRnaUse')||own(f,'fullRnaUse')||f?.method===METHOD);
}
function contextId(c,hash,v3){
    const common={scope:c.scope,alleleId:c.variant.alleleId,hgncId:c.gene.hgncId,mondoId:c.disease.mondoId,moi:c.moi,
        transcript:{accession:c.transcript.accession,version:c.transcript.version}};
    return 'context-sha256-'+hash(v3?{domain:'varvis-evidence-context-v3',schemaVersion:'evidence-context-v3',dataClass:c.dataClass,...common,patientContext:c.patientContext}:
        {...common,patientContextId:c.patientContext.patientContextId,familyContextId:c.patientContext.familyContextId??null});
}
function caller(expected,hash,code){
    const c=expected?.context,profile=expected?.executionProfile;
    required(typeof hash==='function'&&text(expected?.analysisId)&&sha(expected?.executionSourceSha256)&&text(expected?.candidateId)&&text(expected?.hypothesisId)&&
        Array.isArray(expected.enabledCriteria)&&new Set(expected.enabledCriteria).size===expected.enabledCriteria.length&&expected.enabledCriteria.includes('ACMG_AMP_2015:'+code)&&object(profile)&&
        same(profile.enabledCriteria,expected.enabledCriteria,hash)&&Array.isArray(expected.criteria),'FULL_RNA_CALLER_REQUIRED');
    required(['evidence-context-v2','evidence-context-v3'].includes(c?.schemaVersion)&&object(c.variant)&&object(c.scope)&&object(c.gene)&&object(c.disease)&&object(c.patientContext)&&
        c.scope.analysisDomain==='GERMLINE'&&c.scope.diseaseModel==='MENDELIAN'&&c.scope.variantScope==='SMALL_SEQUENCE_VARIANT'&&text(c.patientContext.patientContextId)&&text(c.gene.hgncId)&&text(c.disease.mondoId)&&text(c.moi)&&
        text(c.transcript?.accession)&&integer(c.transcript.version)&&c.transcript.version>0&&c.contextId===contextId(c,hash,c.schemaVersion==='evidence-context-v3'),'FULL_RNA_CALLER_CONTEXT_INVALID');
    const n=c.variant.normalization,a=c.variant.evidenceQueryAllele;
    required(n?.refValidation==='MATCH'&&sha(n.referenceSequenceSha256)&&a?.assembly==='GRCh38'&&text(a.contig)&&integer(a.position)&&a.position>0&&
        /^[ACGT]{1,50}$/.test(a.reference||'')&&/^[ACGT]{1,50}$/.test(a.alternate||'')&&a.reference!==a.alternate&&
        c.variant.alleleId==='allele-sha256-'+hash({normalizedAllele:c.variant.normalizedAllele,normalizer:{method:n.method,methodVersion:n.methodVersion,
            referenceSequenceSha256:n.referenceSequenceSha256,refValidation:n.refValidation}}),'FULL_RNA_CALLER_ALLELE_INVALID');
    const runtime=profile.moduleBindings?.[code.toLowerCase()]?.runtimeBinding,cap=runtime?.fullRnaExecution;
    required(sha(runtime?.configurationSha256)&&applicationVersions.includes(cap?.methodVersion)&&cap.capabilityConfigured===true&&!own(cap,'sourceIndexConfigured')&&!own(cap,'materialIdentitySha256')&&cap.preparationVersion==='1.1.0'&&
        typeof cap.pvs1Version==='string'&&own(pvs1AssessmentVersions,cap.pvs1Version)&&cap.pm4Version==='1.0.0'&&cap.bp3Version==='1.0.0'&&cap.archiveVersion==='1.0.0'&&object(cap.declaredPins)&&Object.entries(cap.declaredPins).every(([k,v])=>
            ['pvs1-bundle-file-sha256','pvs1-bundle-sha256','transcript-material-manifest-sha256','gencode-transcript-index-manifest-sha256','reference-receipt-sha256'].includes(k)&&(v===''||sha(v))),'FULL_RNA_RUNTIME_BINDING_INVALID');
    required(expected.fullRnaBatchIdentity?.schemaVersion==='local-evidence-batch-binding-v1'&&same(expected.fullRnaBatchIdentity.fullRnaExecution,cap,hash),'FULL_RNA_BATCH_CAPABILITY_MISMATCH');
    const observed=expected.fullRnaObservedSourceIdentity;
    required(expected.fullRnaBatchSourceSha256===expected.executionSourceSha256&&observed?.schemaVersion==='full-rna-observed-source-identity-v1'&&
        ['OBSERVED','UNAVAILABLE'].includes(observed.status)&&observed.archiveRequired===true&&[true,'UNKNOWN'].includes(observed.sourceIndexConfigured)&&
        (!own(observed,'methodVersion')||observed.methodVersion==='1.1.0')&&(!own(observed,'materialIdentitySha256')||sha(observed.materialIdentitySha256)), 'FULL_RNA_OBSERVED_ROOT_REQUIRED');
    return {...c,schemaVersion:'evidence-context-v2',contextId:contextId(c,hash,false)};
}
function core(c,expected,hash){
    const code=codeOf(c),d=c.details,f=d?.[factKeys[code]],p=d?.fullRnaUse;
    required(c.criterionId==='ACMG_AMP_2015:'+code,'FULL_RNA_CRITERION_ID_INVALID');
    const evaluationContext=caller(expected,hash,code);
    const cap=expected.executionProfile.moduleBindings[code.toLowerCase()].runtimeBinding.fullRnaExecution;
    required(f?.schemaVersion===(code==='PVS1'?'pvs1-selected-facts-v1':code==='PM4'?'pm4-general-facts-v1':'bp3-general-facts-v1')&&
        f.method===METHOD&&f.methodVersion===(code==='PVS1'?cap.pvs1Version:cap[code.toLowerCase()+'Version'])&&f.clinicalValidationCompleted===false&&!own(f,'publicProjectionIssue'), 'FULL_RNA_FACT_METHOD_INVALID');
    required(p?.schemaVersion==='full-rna-current-use-v1'&&p.methodVersion===cap.methodVersion&&sha(p.ledgerSha256)&&sha(p.proofSha256)&&
        p.proofSha256===hash(body(p,'proofSha256'))&&same(f.fullRnaUse,p,hash)&&p.executionSourceSha256===expected.executionSourceSha256&&
        p.candidateId===expected.candidateId&&p.hypothesisId===expected.hypothesisId,'FULL_RNA_CURRENT_BINDING_INVALID');
    const claim=p.claim,prep=p.preparation,a=f.fullRnaAssessment;
    if(p.methodVersion==='1.1.0'||own(f,'fullRnaDetails')||own(p,'decisionDetailsSha256')) required(f.fullRnaDetails?.schemaVersion==='full-rna-decision-details-v1'&&
        f.fullRnaDetails.methodVersion===p.methodVersion&&sha(p.decisionDetailsSha256)&&hash(f.fullRnaDetails)===p.decisionDetailsSha256,'FULL_RNA_DECISION_DETAILS_BINDING_INVALID');
    required(claim?.criterionId===c.criterionId&&claim.modulePointer===d.modulePointer&&
        new RegExp('^/modules/'+code.toLowerCase()+'/results/(?:0|[1-9][0-9]{0,5})$').test(claim.modulePointer||'')&&
        sha(claim.evaluationSha256)&&claim.evaluationSha256===d.originalResultSha256&&
        (!own(d,'originalResult')||hash(d.originalResult)===claim.evaluationSha256)&&
        ['MET','NOT_MET','NOT_EVALUATED'].includes(claim.originalStatus)&&own(strength,claim.originalStrength)&&
        (claim.originalStatus==='MET'?strength[claim.originalStrength]>0:claim.originalStrength==='NONE')&&
        (!own(claim,'originalReasonCode')||reason(claim.originalReasonCode))&&reason(claim.normalizedReasonCode)&&
        claim.normalizedReasonCode===(claim.originalReasonCode||(['MET','NOT_MET'].includes(claim.originalStatus)?'LOCAL_BATCH_CRITERION_'+claim.originalStatus:code+'_RESULT_UNAVAILABLE'))&&
        ['ADOPTED','NOT_ADOPTED','NOT_COUNTED','CONFLICT'].includes(claim.application),'FULL_RNA_ORIGINAL_EVALUATION_BINDING_INVALID');
    if(code==='PVS1')required(fullRnaPvs1MethodValid(f,cap,claim,d.originalResult),'FULL_RNA_PVS1_METHOD_CONTRACT_INVALID');
    required(prep?.schemaVersion==='full-rna-selected-source-summary-v1'&&prep.methodVersion==='1.1.0'&&prep.layer==='NEUTRAL_SOURCE_PREPARATION'&&
        prep.criterionConsumerStatus==='NOT_RUN_BY_NEUTRAL_PREPARATION'&&['criterionAdoptionGranted','functionalQualificationGranted','phaseQualificationGranted'].every(k=>prep[k]===false)&&
        prep.executionSourceSha256===expected.executionSourceSha256&&prep.contextSha256===hash(sha(prep.selectedProjectionSha256)?evaluationContext:expected.context)&&text(prep.status)&&reason(prep.reasonCode)&&object(prep.binding), 'FULL_RNA_PREPARATION_BINDING_INVALID');
    const ready=prep.status==='READY',b=prep.binding;
    if(ready){
        required(observedOriginalMatches(prep,expected.fullRnaObservedSourceIdentity),'FULL_RNA_OBSERVED_SOURCE_MISMATCH');
        required(sha(prep.selectedProjectionSha256)&&text(prep.entryId)&&['originalSha256','contextSha256','modelSha256','referenceRnaSha256','alteredRnaSha256','mechanicalEffectSha256','factsSha256','resultSha256','topologySha256'].every(k=>sha(b[k]))&&
            b.contextSha256===prep.contextSha256&&b.eligibilityStage==='PINNED_STRUCTURED_RESEARCH_ORIGINAL_REPLAYED_NOT_CRITERION_OR_PHASE_ADOPTION'&&
            p.sourceOriginalSha256===b.originalSha256&&p.mechanicalEffectSha256===b.mechanicalEffectSha256&&p.factsSha256===b.factsSha256&&
            prep.sourceRecord?.sha256===b.originalSha256&&text(prep.sourceRecord.sourceId)&&text(prep.sourceRecord.version)&&/^https?:\/\/[^\s]+$/.test(prep.sourceRecord.url||''),'FULL_RNA_SOURCE_FACT_BINDING_INVALID');
    }else required(claim.originalStatus==='NOT_EVALUATED'&&claim.originalStrength==='NONE'&&claim.application==='NOT_ADOPTED'&&
        (!own(prep,'selectedProjectionSha256')||prep.selectedProjectionSha256===''||sha(prep.selectedProjectionSha256)), 'FULL_RNA_UNREADY_CANNOT_ADOPT');
    if(object(a))required(a.schemaVersion===code.toLowerCase()+'-full-rna-assessment-v1'&&a.methodVersion===(code==='PVS1'?pvs1AssessmentVersions[cap.pvs1Version]:'1.0.0')&&
        ['MET','NOT_MET','NOT_EVALUATED','NOT_APPLICABLE'].includes(a.status),'FULL_RNA_ASSESSMENT_INVALID');
    if(f.fullRnaDetails?.assessmentKind==='INITIATION_LOSS'||own(f.fullRnaDetails,'initiation')){
        const i=f.fullRnaDetails.initiation;
        required(code==='PVS1'&&['1.10.0','1.10.1'].includes(cap.pvs1Version)&&ready&&
            i?.schemaVersion==='pvs1-full-rna-initiation-assessment-v1'&&i.methodVersion==='1.0.0'&&
            i.sourceOriginalSha256===b.originalSha256&&i.mechanicalEffectSha256===b.mechanicalEffectSha256,
        'FULL_RNA_INITIATION_DETAILS_BINDING_INVALID');
    }
    const positive=claim.originalStatus==='MET';
    if(positive){
        required(ready&&a?.status==='MET'&&a.sourceOriginalSha256===b.originalSha256&&a.mechanicalEffectSha256===b.mechanicalEffectSha256&&
            a.factsSha256===b.factsSha256&&(code==='PVS1'?a.adoptedStrength===claim.originalStrength:
                claim.originalStrength===(code==='PM4'?'MODERATE':'SUPPORTING')),'FULL_RNA_POSITIVE_ASSESSMENT_REQUIRED');
        const sequence=prep.sequenceEffect,stop=sequence?.firstStop;
        const initiation=code==='PVS1'&&f.fullRnaDetails?.assessmentKind==='INITIATION_LOSS'&&
            fullRnaInitiationPositiveValid(a,f.fullRnaDetails.initiation,sequence,claim,f.fullRnaDetails.assessmentKind);
        required(initiation||sequence?.nativeStart?.retainedContiguously===true&&integer(sequence.referenceRnaLength)&&sequence.referenceRnaLength>0&&
            integer(sequence.alteredRnaLength)&&sequence.alteredRnaLength>0&&integer(sequence.referenceProteinLength)&&sequence.referenceProteinLength>0&&
            integer(sequence.alteredProteinLength)&&sequence.alteredProteinLength>=0&&integer(sequence.netProteinLengthChange)&&
            sequence.netProteinLengthChange===sequence.alteredProteinLength-sequence.referenceProteinLength&&integer(stop?.alteredStartRna1)&&
            stop.alteredStartRna1>0&&stop.alteredEndRna1===stop.alteredStartRna1+2&&stop.alteredEndRna1<=sequence.alteredRnaLength,'FULL_RNA_POSITIVE_SEQUENCE_INCONSISTENT');
        if(code!=='PVS1')required(['SOURCE_BOUND_RNA_INFRAME_LENGTH_CHANGE',...(code==='PM4'?['SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION']:[])].includes(a.branch)&&
            integer(a.netAminoAcidChange)&&a.netAminoAcidChange!==0&&a.netAminoAcidChange===sequence.netProteinLengthChange, 'FULL_RNA_LENGTH_BRANCH_INVALID');
        const assay=prep.assayConditions;
        required(assay?.evidenceKind==='OBSERVED_RNA'&&['alleleAttribution','assayQuality','tissueRelevance'].every(k=>assay[k]==='ASSESSED')&&
            assay.residualNormalTranscript==='NOT_DETECTED_IN_ASSESSED_SCOPE'&&assay.outcomeMultiplicity==='SINGLE_SUPPORTED_OUTCOME','FULL_RNA_POSITIVE_ASSAY_INVALID');
    }
    const completedExclusion=claim.completedExclusion===true;
    if(own(claim,'completedExclusion')||own(claim,'originalApplicability')||own(claim,'originalExecutionStatus'))required(ready&&
        fullRnaCompletedExclusionValid(f,cap,claim,d.originalResult), 'FULL_RNA_COMPLETED_EXCLUSION_INVALID');
    const derived=['NOT_COUNTED','CONFLICT'].includes(claim.application);
    required(c.status===(completedExclusion?'NOT_APPLICABLE':derived?'NOT_EVALUATED':claim.originalStatus)&&c.appliedStrength===(derived?'NONE':claim.originalStrength)&&
        c.reasonCode===(derived?claim.reasonCode:claim.normalizedReasonCode),'FULL_RNA_DERIVED_RESULT_MISMATCH');
    const {accession,version}=evaluationContext.transcript;
    const selectedTranscript=accession.endsWith('.'+version)?accession:accession+'.'+version;
    return {code,claim,preparation:prep,assessment:a,details:f.fullRnaDetails,proof:p,ready,positive,selectedTranscript,
        completedExclusion,inapplicable:completedExclusion||a?.status==='NOT_APPLICABLE'};
}
function observedOriginalMatches(prep,observed){
    if(observed.status!=='OBSERVED'||observed.sourceIndexConfigured!==true||!sha(observed.materialIdentitySha256)||
        !sha(observed.indexSha256)||!Array.isArray(observed.sources)||observed.sources.length>1024)return false;
    const rows=observed.sources.filter(row=>row?.entryId===prep.entryId),row=rows[0];
    return rows.length===1&&row.status==='READ'&&row.observedSha256===prep.binding.originalSha256&&
        sha(row.bindingSha256)&&integer(row.bytes)&&row.bytes>0;
}
export function inspectFullRnaCriterion(c,expected,{identityHash}={}){
    if(!fullRnaCriterionClaim(c))return {claimed:false,valid:true,issues:[]};
    try{
        const v=core(c,expected,identityHash),peers=expected.criteria.filter(fullRnaCriterionClaim);
        required(peers.filter(r=>r.criterionId===c.criterionId).length===1&&peers.find(r=>r.criterionId===c.criterionId)===c,'FULL_RNA_CURRENT_ROW_REQUIRED');
        const views=peers.map(row=>core(row,expected,identityHash));
        required(new Set(views.map(r=>r.code)).size===views.length&&views.every(r=>r.proof.ledgerSha256===v.proof.ledgerSha256&&
            same(r.preparation,v.preparation,identityHash)&&r.proof.mechanicalEffectSha256===v.proof.mechanicalEffectSha256),'FULL_RNA_PEER_LEDGER_MISMATCH');
        const positives=views.filter(r=>r.positive),pvs1=positives.find(r=>r.code==='PVS1'),pm4=positives.find(r=>r.code==='PM4'),bp3=positives.find(r=>r.code==='BP3');
        const conflict=bp3&&(pvs1||pm4),winner=pvs1&&pm4?(strength[pvs1.claim.originalStrength]>=strength[pm4.claim.originalStrength]?'PVS1':'PM4'):null;
        for(const row of views){
            const app=!row.positive?'NOT_ADOPTED':conflict?'CONFLICT':winner&&row.code!==winner?'NOT_COUNTED':'ADOPTED';
            required(row.claim.application===app,'FULL_RNA_ASSIGNMENT_MISMATCH');
            if(app==='CONFLICT')required(row.claim.reasonCode==='FULL_RNA_OPPOSITE_EFFECT_EVIDENCE_CONFLICT'&&!own(row.claim,'assignedTo'),'FULL_RNA_CONFLICT_BINDING_INVALID');
            else if(app==='NOT_COUNTED')required(row.claim.assignedTo==='ACMG_AMP_2015:'+winner&&row.claim.reasonCode==='FULL_RNA_SHARED_EFFECT_ASSIGNED_TO_'+winner,'FULL_RNA_ASSIGNMENT_TARGET_INVALID');
            else required(!own(row.claim,'assignedTo')&&!own(row.claim,'reasonCode'),'FULL_RNA_UNEXPECTED_ASSIGNMENT');
        }
        let preparation=v.preparation;
        // Old unadopted records can contain observations from a source read after the request was bound.
        if(!v.ready&&own(preparation.binding,'originalSha256')&&!observedOriginalMatches(preparation,expected.fullRnaObservedSourceIdentity)){
            preparation={...preparation,binding:{},reasonCode:'FULL_RNA_SOURCE_CHANGED_AFTER_REQUEST_IDENTITY'};
            delete preparation.sourceRecord;
            delete preparation.assayConditions;
        }
        return {claimed:true,valid:true,issues:[],view:{...v,preparation,application:v.claim.application,assignedTo:v.claim.assignedTo?.split(':').pop(),
            peers:views.map(r=>({code:r.code,status:r.claim.originalStatus,strength:r.claim.originalStrength,application:r.claim.application}))}};
    }catch(error){return {claimed:true,valid:false,issues:[reason(error?.message)?error.message:'FULL_RNA_PUBLIC_CONTRACT_INVALID']};}
}
const projections=new WeakMap();
export function rememberFullRnaProjection(evaluation,criterion,expected,inspection){
    if(evaluation&&inspection?.claimed&&inspection.valid)projections.set(evaluation,{criterion,expected,view:inspection.view,
        projection:JSON.stringify(evaluation),source:JSON.stringify(criterion),caller:JSON.stringify(expected)});
}
export function fullRnaEntryView(entry){
    const marker=projections.get(entry?.evaluation);
    return marker&&entry.savedCriterion===marker.criterion&&JSON.stringify(entry.evaluation)===marker.projection&&
        JSON.stringify(marker.criterion)===marker.source&&JSON.stringify(marker.expected)===marker.caller?marker.view:null;
}
export const fullRnaSourceAdmissionFailed=i=>i?.sourceDocumentsQualified===false
    ||/^PVS1_RNA_INITIATION_(?:POLICY_NOT_REVIEWED|TRANSCRIPT_SCOPE_INVALID|SELECTED_TRANSCRIPT_OUTSIDE_SCOPE|SOURCE_BINDINGS_INVALID|SOURCE_REVIEW_REQUIRED|SOURCE_DOCUMENT_SET_MISMATCH|SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE|SOURCE_DOCUMENT_FORMAT_INVALID|SOURCE_ORIGINAL_REQUIRED|SOURCE_ORIGINAL_SHA_MISMATCH|SOURCE_JSON_INVALID|SOURCE_REVIEW_NOT_QUALIFIED|SOURCE_PRODUCT_SCOPE_MISMATCH|PRIMARY_ORIGINAL_REQUIRED|SOURCE_LOCATION_INVALID|SOURCE_ROLE_AMBIGUOUS|SOURCE_OR_INPUT_INVALID)$/.test(i?.reasonCode||'');
export const fullRnaDeclaredStart=i=>fullRnaSourceAdmissionFailed(i)
    ||(!Array.isArray(i?.reviewedSourceIds)&&!i?.sourceSpecifiedStart)
    ||/^PVS1_RNA_INITIATION_(?:FUNCTIONAL_ALTERNATIVE_CONFIRMED|FUNCTION_FINDING_INVALID|FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE|FUNCTIONAL_RESCUE_FACTS_CONFLICT|SOURCE_START_STATUS_INVALID|SOURCE_START_POSITION_INVALID|SOURCE_START_NOT_ATG|SOURCE_TRANSLATION_WITNESS_MISMATCH)$/.test(i?.reasonCode||'')
    ?null:i?.sourceDeclaredStartReview;
export const fullRnaRescueReviewConflict=view=>view?.application==='NOT_ADOPTED'
    &&view.claim?.normalizedReasonCode==='PVS1_RNA_INITIATION_FUNCTIONAL_RESCUE_FACTS_CONFLICT';
export function fullRnaPending(view){
    const code=view.claim.normalizedReasonCode;
    const known=view.ready?'已保存与本次变异对应的 RNA 后果、序列资料和判定来源。':'本次资料准备尚未完成，保留已保存的核查结果。';
    let gap='当前规则所需的完整审核及适用资料。',suggestion='核对本次规则条件及对应原文资料。',conclusion='当前条件尚未完成核查，本项暂不采用。';
    if(view.preparation.configurationReasonCode){
        const cause=view.preparation.configurationReasonCode;gap='';
        conclusion=/PIN/.test(cause)?'RNA 资料的固定校验值配置不符合要求，本次未采用证据。':
            /SHA|BYTES_CHANGED/.test(cause)?'RNA 资料与校验值未通过一致性核查，本次未采用证据。':
            /JSON|SCHEMA|FORMAT|INDEX/.test(cause)?'RNA 资料格式或索引未通过核查，本次未采用证据。':
            /REFERENCE/.test(cause)?'参考序列及来源未通过核查，本次未采用证据。':
            /READ|IO|NOT_FOUND|MISSING/.test(cause)?'当前 RNA 资料文件无法读取，本次未采用证据。':'受控 RNA 资料配置未就绪，本次未采用证据。';
        suggestion='核对受控资料配置、文件及校验结果后重试。';
    }
    else if(view.preparation.reasonCode==='FULL_RNA_SOURCE_CHANGED_AFTER_REQUEST_IDENTITY'){
        return {title:'RNA 原文完整性核查',requirement:'运行前登记和核查时读取的 RNA 原文应一致。',
            known:'两次读取的原文校验值不一致。',gap:'',conclusion:'RNA 原文在本次运行中发生变化，本项未采用。',
            suggestion:'核对原文来源后重新运行。',sources:[]};
    }
    else if(view.preparation.reasonCode==='FULL_RNA_SOURCE_ORIGINAL_SHA_MISMATCH'){
        return {title:'RNA 原文完整性核查',
            requirement:'RNA 原文应与本次登记的来源记录一致。',
            known:'本次读取的 RNA 原文与已登记的校验值不一致。',
            gap:'',conclusion:'RNA 原文与已登记的校验值不一致，本项未采用。',
            suggestion:'核对本次引用的原文文件及来源记录。',sources:[]};
    }
    else if(view.preparation.reasonCode==='FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED'){
        return {title:'等位基因来源核查',
            requirement:'确认 RNA 资料来自本次核查的同一个等位基因。',
            known:'本次 RNA 资料未通过等位基因来源核查。',
            gap:'资料与当前等位基因的可核对对应关系。',
            conclusion:'当前无法确认 RNA 产物来自本次变异，暂不采用该条证据。',
            suggestion:'核对原始样本、等位基因及 RNA 实验的对应关系。',sources:[]};
    }
    else if(view.preparation.reasonCode==='FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED'){
        const residual=view.preparation.assayConditions?.residualNormalTranscript;
        return {title:'多种 RNA 后果核查',
            requirement:'本规则需要确认当前变异只有一个有实验证据支持的 RNA 后果。',
            known:'本次资料记录了同一变异的多种 RNA 后果。'+
                (residual==='DETECTED_IN_ASSESSED_SCOPE'?'本次检查范围内也记录了正常转录本。':''),
            gap:'',conclusion:'已观察到多种 RNA 后果，当前软件尚不支持联合判定，本项未采用。',
            suggestion:'分别核对各产物的来源、比例和疾病相关功能。',sources:[]};
    }
    else if(/ASSAYQUALITY|ASSAY_QUALITY/.test(code)){gap='RNA 试验质量的核查结论。';suggestion='核对原文中的试验设计、对照及质量记录。';}
    else if(/TISSUERELEVANCE|TISSUE_RELEVANCE/.test(code)){gap='试验组织与本次疾病机制的相关性。';suggestion='核对取样组织及适用性依据。';}
    else if(/RESIDUAL/.test(code)){
        const fromParent=code==='PVS1_FULL_RNA_RESIDUAL_TRANSCRIPT_UNRESOLVED';
        const residual=(fromParent?view.details?.rule?.parentReview:view.preparation.assayConditions)?.residualNormalTranscript;
        const requirement='在适用的检查范围内，未检出当前变异等位基因产生的残余正常转录本。';
        const detected=residual==='DETECTED_IN_ASSESSED_SCOPE';
        return {title:'残余正常转录本核查',requirement,
            known:detected?'本次检查范围内已记录检出正常转录本。':known,
            gap:detected?'':'尚缺当前变异等位基因的残余正常转录本核查结论。',
            conclusion:detected?'当前检出记录未满足残余正常转录本的判定条件，本项暂不采用。':'残余正常转录本尚未核实，本项暂不采用。',
            suggestion:'核对原文是否能区分正常转录本来自哪个等位基因。',sources:[]};
    }
    else if(fullRnaRescueReviewConflict(view)){
        conclusion='功能救援审核记录冲突，本次不采用 PVS1。';
        gap='';suggestion='核对两份记录采用的 RNA 产物、实验及疾病范围。';
    }
    else if(code==='PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE'){
        const review=view.details?.initiation;
        const checks=review?.sourceDocumentsQualified===true&&review.reasonCode===code?review.functionalReviewConditions||{}:{};
        const startStatus={
            SOURCE_REVIEWED_PUTATIVE:'来源记录了候选起始，实际翻译尚未证实。',
            UNKNOWN:'来源记录的翻译起始尚未确定。',
            NOT_ASSESSED:'来源记录尚未评估翻译起始。',
        }[review?.availableStartStatus];
        const available=[
            checks.observedStartReported===true?'来源记录报告了实测起始。':checks.observedStartReported===false&&typeof startStatus==='string'&&startStatus,
            checks.physiologicalRelevanceReviewed===true&&'已记录疾病机制相关性审核。',
            checks.sameRnaProductExperimentReviewed===true&&'已记录同一异常 RNA 产物的功能实验审核。',
        ].filter(Boolean).join('');
        const missing=[
            ['observedStartReported','异常 RNA 产物的实测起始位置'],
            ['physiologicalRelevanceReviewed','实验结果与当前疾病机制的相关性审核'],
            ['sameRnaProductExperimentReviewed','针对同一异常 RNA 产物的功能实验审核'],
        ].filter(([key])=>checks[key]===false).map(([,label])=>label);
        return {title:'功能救援实验核查',
            requirement:'实测异常 RNA 产物的起始，并核查同一产物的功能实验及其与当前疾病机制的相关性。',
            known:available||known,gap:missing.join('；')||'本次保存记录未列明缺失的具体审核项目。',
            conclusion:'功能救援实验审核未完成，本次不采用 PVS1。',
            suggestion:'核对原文中的起始观察、受测产物与功能结果。',sources:[]};
    }
    else if(code==='PVS1_RNA_INITIATION_PARENT_FUNCTIONAL_RESCUE_REVIEW_REQUIRED'){
        return {title:'功能救援审核',
            requirement:'上层 PVS1 审核须记录功能救援已排除，或明确以本次起始丢失审核为准。',
            known,gap:'上层 PVS1 审核的功能救援结论。',
            conclusion:'功能救援审核尚未完成，本次不采用 PVS1。',
            suggestion:'核对上层结论与本次 RNA 及蛋白功能资料是否一致。',sources:[]};
    }
    else if(/FUNCTIONAL_RESCUE/.test(code)){gap='排除功能救援的完整后果审核。';suggestion='核对是否存在仍能保留功能的转录本或蛋白后果。';}
    else if(code==='PVS1_RNA_INITIATION_SELECTED_TRANSCRIPT_OUTSIDE_SCOPE'){
        const scope=view.details?.initiation?.transcriptReviewScope;
        const selected=/^(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*$/.test(scope?.selectedTranscript||'')?scope.selectedTranscript:'';
        return {title:'转录本适用范围核查',
            requirement:'本次转录本及版本须纳入该起始丢失规则的审核范围。',
            known:selected?`本次转录本 ${selected} 未纳入该规则的审核范围。`:'本次转录本未纳入该规则的审核范围。',
            gap:'适用于本次转录本及版本的规则审核。',
            conclusion:'当前范围不足以判定功能救援，本次不采用 PVS1。',
            suggestion:'核对转录本版本，并补齐适用范围审核。',sources:[]};
    }
    else if(/INITIATION/.test(code)){
        const i=view.details?.initiation||{};
        gap='可采用的翻译起始及其来源依据。';conclusion='起始丢失分支的适用条件尚未完成核查，本项暂不采用。';suggestion='核对适用于该 RNA 产物的起始、替代转录本及独立参考资料。';
        if(/TRANSCRIPT_REVIEW_REQUIRED|TRANSCRIPT_REVIEW_INCOMPLETE/.test(code)){gap='声明范围内每个转录本的功能及是否受当前 RNA 事件影响的完整审核。';suggestion='补齐声明范围内的转录本审核及对应原文依据。';}
        else if(/FUNCTION_SOURCE_LOCATION_INVALID|FUNCTION_REVIEW_SCOPE_UNRESOLVED|TRANSCRIPT_SET_MISMATCH/.test(code)){
            gap='转录本与本次 RNA 事件的对应关系，以及经核对的原文依据。';
            suggestion='核对对应转录本、功能记录和原文位置。';
        }
        else if(/UPSTREAM_SOURCE_LOCATION_INVALID/.test(code)){
            gap='可核实原文位置的上游参考资料。';
            suggestion='核对该参考记录及其对应引文。';
        }
        else if(/SOURCE_START_REQUIRED/.test(code)){gap='由来源指定的实际或候选起始位置，以及该产物的序列核对资料。';suggestion='核对原文指定的起始位置及完整翻译产物资料。';}
        else if(/UPSTREAM_REVIEW_REQUIRED|UPSTREAM_REVIEW_INCOMPLETE/.test(code)){gap='合格独立致病参考，或对应上游区间的完整审核。';suggestion='补齐该参考 CDS 区间的独立参考及范围审核资料。';}
        else if(code==='PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE'){
            gap='部分原文或功能报告尚未纳入本次审核。';
            conclusion='当前资料未完成来源审核，暂不采用功能救援结论。';
            suggestion='核对未纳入的资料是否适用于同一变异和 RNA 产物，并补齐审核记录。';
        }
        else if(/SOURCE_DOCUMENT_SET_MISMATCH|SOURCE_ORIGINAL_SHA_MISMATCH/.test(code)){gap='';conclusion='起始分支的原始来源资料未通过完整性核查，本项暂不采用。';suggestion='核对本次指定的原文资料及其一致性。';}
        else if(/EARLIER_(?:ALTERED_)?INFRAME_MET_REQUIRES_SEPARATE_RULE/.test(code)){gap='';conclusion='发现更早的同框 ATG，当前候选起始的规则不能直接沿用；尚未据此确认实际翻译。';suggestion='取得适用于更早 ATG 的独立来源和规则后重新核查。';}
        if(!fullRnaSourceAdmissionFailed(i)&&i.sourceSpecifiedStart?.startStatus==='SOURCE_REVIEWED_PUTATIVE')return {title:'起始丢失判定条件',requirement:'核查来源指定的起始、功能性替代转录本及本次上游参考范围。',known:'已有来源审核的候选起始；该候选本身尚未证明实际翻译。',gap,conclusion,suggestion,sources:[]};
    }
    else if(/TERMINATION/.test(code)){gap='完整产物中可确认的终止位置。';conclusion='终止位置尚未确定，蛋白长度保持未知，本项暂不采用。';suggestion='核对完整 RNA 产物及终止位置资料。';}
    else if(/TOPOLOGY|JUNCTION/.test(code)){gap='改变后 RNA 的完整连接点及相关规则条件。';suggestion='核对连接点原文资料和 NMD 适用条件。';}
    else if(code==='PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE'){
        gap='';conclusion='本次 RNA 后果超出适用规范已核定的位置范围，暂不评价 PVS1。';
        suggestion='核对是否有覆盖该位置的规则。';
    }
    else if(code==='PVS1_POLICY_POSITION_UNRESOLVED'){
        gap='适用规范要求的位置尚未核实。';conclusion='位置条件无法核查，暂不评价 PVS1。';
        suggestion='核对规则要求的变异影响区间或提前终止位置。';
    }
    else if(/KNOWN_CSPEC|FULL_RNA_SCOPE_REQUIRES|FULL_RNA_CLAUSE/.test(code)){gap='';conclusion='当前程序尚未接通该专家规范的完整 RNA 条款，本项暂不采用。';suggestion='接通适用条款后重新核查。';}
    else if(/REGION|REPEAT|FUNCTION_KNOWLEDGE/.test(code)){gap='本次实际受影响区间的重复与功能资格资料。';suggestion='核对对应区间的独立区域审核及原始出处。';}
    else if(/SOURCE|PREPARATION|BINDING/.test(code)){gap='与当前背景和变异相符、可完整核查的来源资料。';suggestion='核对本次来源读取、身份与序列重放记录。';}
    return {title:'本次判定条件',requirement:'RNA 后果、资料适用性及本项独立规则条件均须通过核查。',known,gap,conclusion,suggestion,sources:[]};
}

export function fullRnaSummary(view){
    if(view.application==='NOT_COUNTED')return `相同 RNA 后果已计入 ${view.assignedTo}，本项不重复计分。`;
    if(view.application==='CONFLICT')return '相同 RNA 后果同时支持相反方向的证据，暂不采用。';
    if(view.application==='ADOPTED')return ({PVS1:'完整 RNA 后果满足本次功能缺失规则。',PM4:'完整 RNA 后果满足本次蛋白长度改变规则。',BP3:'在框 RNA 后果符合重复区域及无已知功能条件。'})[view.code];
    if(view.completedExclusion)return view.claim.normalizedReasonCode==='PVS1_FULL_RNA_PROTEIN_UNCHANGED'?
        '改变后的蛋白序列保持不变，本项不适用。':view.claim.normalizedReasonCode==='PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED'?
        '已确认不受当前 RNA 事件影响的功能性替代转录本，本项不适用。':view.claim.normalizedReasonCode==='PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED'?
            '该 RNA 产物的功能保留已确认，本项不适用。':'完整 RNA 后果已完成核查，本项不适用。';
    if(view.inapplicable)return '完整 RNA 后果不适用当前证据分支。';
    if(view.claim.originalStatus==='NOT_MET')return '已有 RNA 后果或区域资料不满足本次证据条件。';
    return fullRnaPending(view).conclusion;
}
