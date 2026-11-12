// Optional saved PP1 display; source qualification and patient linkage are backend-owned.
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const list=v=>Array.isArray(v)?v:[];
const sha=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const text=(v,max=600)=>typeof v==='string'&&v.length>0&&v.length<=max&&!v.trim().startsWith('{')&&!v.trim().startsWith('[')&&!/^(?:file:|[A-Za-z]:[\\/]|\\\\)/.test(v);
const decimal=v=>typeof v==='string'&&/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(v);
const sourceConditions=['APPLICABLE_INDEPENDENT_PHENOTYPE_DEFINITION','COMPLETE_REQUIRED_EXAMINATIONS_AND_APPLICABLE_AGE',
    'REQUIRED_DIFFERENTIAL_AND_LOCUS_REVIEW','COMPARABLE_INDEPENDENT_TESTED_COHORT','NONCIRCULAR_ORIGINAL_DIAGNOSIS_AND_SUPPORT'];

export function recordedClinicalAge(value,role){
    if(value?.semanticRole!==role||value.status!=='RECORDED'||value.kind!=='AGE'||typeof value.iso8601duration!=='string')return '';
    const match=/^P(?=.+)(?:([0-9]+)Y)?(?:([0-9]+)M)?(?:([0-9]+)W)?(?:([0-9]+)D)?(?:T(?=.+)(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+(?:\.[0-9]+)?)S)?)?$/.exec(value.iso8601duration);
    if(!match)return '';
    const units=['岁','个月','周','天','小时','分钟','秒'];
    return match.slice(1).map((v,i)=>v===undefined?'':`${v}${units[i]}`).join('');
}

function readableComparison(row){
    if(!object(row))return null;
    const result={};
    for(const key of ['requirement','known','gap','conclusion','suggestion'])if(text(row[key])&&!/^[A-Z][A-Z0-9_]+$/.test(row[key]))result[key]=row[key];
    return result.requirement&&result.known?result:null;
}

function sourceReviewComparisons(value){
    return list(value.comparisons).slice(0,12).flatMap(row=>{
        const matches=list(value.review?.conditions).filter(c=>c.condition===row?.condition);
        if(!sourceConditions.includes(row?.condition)||matches.length!==1||
            !['NOT_SATISFIED','NOT_ADMITTED_CIRCULAR_BASIS'].includes(matches[0].status))return [];
        const result=readableComparison(row);
        return result?[{...result,gapLabel:matches[0].status==='NOT_SATISFIED'?'条件差异':'依据问题'}]:[];
    });
}

function clinicalBindingMatches(value,criterion,context,expected,identityHash){
    const b=value.binding,e=criterion.details?.pp1Facts?.evaluation,l=e?.ledger;
    if(!object(b)||!object(l)||typeof identityHash!=='function'||!text(expected?.candidateId)||!text(expected?.hypothesisId)||!object(context)||
        b.candidateId!==expected.candidateId||b.hypothesisId!==expected.hypothesisId||!sha(b.preparedContextSha256)||
        identityHash(context)!==b.preparedContextSha256||b.bindingIdentitySha256!==l.selectionKey||
        !sha(b.bindingIdentitySha256)||
        !sha(b.sourceRawSha256)||!sha(b.sourceFactsSha256)||b.sourceRawSha256!==value.source?.sourceRef?.rawSha256||
        b.sourceFactsSha256!==value.source?.sourceRef?.factsSha256||value.contribution?.ledgerStatus!==l.status)return false;
    if(value.status==='SOURCE_ONLY'&&b.mode==='REVIEW_ONLY_SOURCE'){
        const p=criterion.details.pp1Facts.contributionPreparation,r=expected.runIdentity;
        if(criterion.status!=='NOT_EVALUATED'||criterion.appliedStrength!=='NONE'||e.status!=='NOT_EVALUATED'||l.status!=='UNKNOWN'||l.pp4Points!==null||
            !object(r)||!text(r.analysisId)||!text(r.runId)||!sha(r.requestSha256)||b.analysisId!==r.analysisId||b.runId!==r.runId||b.requestSha256!==r.requestSha256||
            (Object.hasOwn(r,'archiveSha256')&&(!sha(r.archiveSha256)||r.archiveSha256!==b.archiveSha256))||
            p?.capability!=='TRUSTED_CURRENT_CONTRIBUTION_SNAPSHOT_V1'||p.status!=='CAPTURED'||p.ledgerStatus!=='UNKNOWN'||p.reasonCode!==l.reasonCode||
            !['reviewAuditSha256','reviewSourceIdentitySha256','inventorySha256','inventorySnapshotSha256','archiveSha256','terminalReceiptSha256'].every(k=>sha(b[k]))||
            b.reviewAuditSha256!==p.snapshotSha256||b.reviewSourceIdentitySha256!==p.sourceIdentitySha256||
            Object.hasOwn(b,'contributionSnapshotSha256')||Object.hasOwn(b,'contributionCoverageProvenanceSha256')||
            value.contribution.terminalKind!=='UNAVAILABLE'||value.contribution.pp4Points!==null||Object.hasOwn(value.contribution,'remainingLocusCapacity'))return false;
    }else if(b.contributionSnapshotSha256!==l.contributionSnapshotSha256||b.contributionCoverageProvenanceSha256!==l.inventoryProvenanceSha256||
        !sha(b.contributionSnapshotSha256)||!sha(b.contributionCoverageProvenanceSha256))return false;
    const content={...value};delete content.projectionSha256;
    return sha(value.projectionSha256)&&identityHash(content)===value.projectionSha256;
}

export function savedClinicalDetails(criterion,context,expected,{identityHash}={}){
    const value=criterion?.details?.clinicalContext;
    if(criterion?.criterionId!=='ACMG_AMP_2015:PP1'||value?.schemaVersion!=='saved-clinical-detail-v1'||value.projectionRole!=='DISPLAY_ONLY'||
        value.historyOnly!==true||value.authorityRestorationSupported!==false)return null;
    try{if(!clinicalBindingMatches(value,criterion,context,expected,identityHash))return null;}catch{return null;}
    // A bounded review of original conditions does not identify or qualify the current patient.
    if(value.status==='SOURCE_ONLY'){
        const comparisons=value.binding.mode==='REVIEW_ONLY_SOURCE'?sourceReviewComparisons(value):[];
        return comparisons.length?{status:value.status,title:'原始资料条件核查',rows:[],features:[],comparisons}:null;
    }
    if(value.status==='NOT_RECORDED')return null;
    const comparisons=list(value.comparisons).slice(0,12).map(readableComparison).filter(Boolean);
    if(['BINDING_UNAVAILABLE','CONFLICT'].includes(value.status))return comparisons.length?{
        status:value.status,title:value.status==='CONFLICT'?'病例关联存在冲突':'病例关联待核查',rows:[],features:[],comparisons}:null;
    if(value.status!=='CURRENT_SAMPLE_LINKED'||!object(value.member)||!Array.isArray(value.member.features)||value.member.features.length>40)return null;
    const rows=[],features=[];
    if(text(value.source?.title,300))rows.push({label:'资料来源',value:value.source.title});
    const age=value.review?.independentAssessmentAge;
    if(age?.semanticRole==='QUALIFIED_CLINICAL_ASSESSMENT'&&age.unit==='YEAR'&&decimal(age.value)){
        rows.push({label:'临床评估年龄',value:`${age.value}岁`});
        if(typeof age.observedAt==='string'&&/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}))?$/.test(age.observedAt))
            rows.push({label:'评估日期',value:age.observedAt.slice(0,10)});
    }
    const last=recordedClinicalAge(value.member.lastEncounter,'LAST_ENCOUNTER');
    if(last)rows.push({label:'末次就诊年龄',value:last});
    for(const f of value.member.features){
        if(!/^HP:[0-9]{7}$/.test(f?.id||'')||!['PRESENT','ABSENT'].includes(f.presence))continue;
        features.push({id:f.id,label:text(f.label,300)?f.label:'',presence:f.presence==='PRESENT'?'存在':'明确未见',
            onset:recordedClinicalAge(f.onset,'FEATURE_ONSET')});
    }
    const c=value.contribution,l=criterion.details.pp1Facts.evaluation.ledger;
    const points=['KNOWN','KNOWN_EMPTY'].includes(l.status)&&decimal(c.pp4Points)&&c.pp4Points===l.pp4Points?c.pp4Points:null;
    rows.push({label:'表型证据占用（PP4）',value:points===null?'尚未确定':`${points} 分`});
    if(decimal(c.remainingLocusCapacity)&&c.remainingLocusCapacity===criterion.details.pp1Facts.evaluation.remainingLocusCapacity)
        rows.push({label:'共用额度剩余（PP1/PP4）',value:`${c.remainingLocusCapacity} 分`});
    const count=value.member.totalRecordedFeatures,truncated=value.member.truncated===true&&Number.isSafeInteger(count)&&count>features.length;
    return {status:value.status,title:'临床资料',rows,features,comparisons,
        coverage:truncated?`${features.length}/${count}`:'',contributionPoints:points};
}
