// Consume saved PM3 conditions. This module does not score observations or qualify sources.
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const list=v=>Array.isArray(v)?v:[];
const text=v=>typeof v==='string'&&v.length>0&&v.length<=32768;
const sha=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const pointer=v=>typeof v==='string'&&v.length<=32768&&(v===''||v.startsWith('/'));
const canonical=v=>Array.isArray(v)?v.map(canonical):object(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
const same=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
const policy='CLINGEN_SVI_PM3_2019_V1';
const sviUrl='https://www.clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf';
const sviSha='17877e54edd58decfe2c374b3d3e2103e52752ea808d48eb7147d007f1ca4c70';
const caseEligibility='SVI_WITH_RELEASED_CASE_ELIGIBILITY';
const families=['SVI_SOURCE_REVIEWED_GENERAL','SVI_EXPLICIT_DELEGATION','SVI_PHASE_METHOD','SVI_PHASE_AND_APPROVED_PARTNER',caseEligibility,'NOT_APPLICABLE'];
const phenotypeId='PUBLISHED_CASE_PHENOTYPE';
const targetClassificationId='SAME_RELEASE_TARGET_CLASSIFICATION';
const partnerClassificationId='SAME_RELEASE_PARTNER_CLASSIFICATION';
const independenceId='PUBLISHED_TARGET_INDEPENDENT_PARTNER';
const caseConditionIds=[phenotypeId,targetClassificationId,partnerClassificationId,independenceId];
const blockingStatuses=['MISSING_INFORMATION','REQUIREMENT_NOT_MET','RULE_NOT_IMPLEMENTED','CONFLICT'];
const execution={version:'pm3-published-conditions-4',mathPolicy:policy,sourceFormat:'PINNED_RELEASED_CSPEC_RAW_AND_ORIGINAL_SCV',program:'EXPLICIT_SVI_PHASE_AND_APPROVED_PARTNER'};

export const pm3V4BindingClaim=b=>b?.version==='pm3-selected-literature-v4';
export function pm3V4BindingValid(b){
    return pm3V4BindingClaim(b)&&b.assessmentVersion==='pm3-literature-assessment-v3'&&same(b.publishedConditions,execution);
}

function policyValid(p,context,identityHash){
    if(p?.schemaVersion!=='pm3-specification-context-v2'||!['SELECTED','NOT_APPLICABLE'].includes(p.status)||
        p.clinicalApproval!==false||p.businessPublishable!==false||!Array.isArray(p.matches)||p.matches.length>1||
        p.context?.geneSymbol!==context?.gene?.symbol||p.context?.hgncId!==context?.gene?.hgncId||
        p.context?.diseaseId!==context?.disease?.mondoId||p.context?.inheritance!=='AR'||context?.moi!=='AUTOSOMAL_RECESSIVE'||
        p.generalPolicySource!==sviUrl||!sha(p.source?.catalogRawSha256)||p.source?.scope!=='PINNED_RELEASED_LOCAL_SNAPSHOT'||
        p.source.worldwideAbsenceClaim!==false||p.source.priorBa1ApprovalInherited!==false)return false;
    if(!p.matches.length)return p.status==='SELECTED'&&p.policy===policy&&p.reasonCode==='PM3_GENERAL_WITH_NO_MATCH_IN_LOCAL_SNAPSHOT'&&
        same(p.generalPolicy,{policy,version:'1.0',sourceUrl:sviUrl,sourceRawSha256:sviSha,basis:'SOURCE_REVIEWED_FIXED_SVI_IMPLEMENTATION'});
    const m=p.matches[0];
    const recessiveScope=m.scope?.moi==='AUTOSOMAL_RECESSIVE'||m.scope?.moi===''&&m.inheritanceSupply==='EXACT_QUALIFIED_GENE_DISEASE_RELATIONSHIP';
    if(!text(m.specificationKey)||m.status!==p.status||m.scope?.hgncId!==p.context.hgncId||m.scope?.mondoId!==p.context.diseaseId||
        !recessiveScope||!sha(m.source?.rawSha256)||!pointer(m.source?.jsonPointer)||!text(m.clausePointer)||
        !sha(m.clauseSha256)||!object(m.clause)||typeof identityHash!=='function'||identityHash(m.clause)!==m.clauseSha256)return false;
    if(p.status==='NOT_APPLICABLE')return true;
    if(m.program?.family===caseEligibility&&(m.reviewedCaseRule?.specificationKey!==m.specificationKey||
        m.reviewedCaseRule?.releaseSha256!==m.source.rawSha256||m.reviewedCaseRule?.pm3Pointer!==m.clausePointer||
        !['casePoints','classificationPoints'].every(k=>sha(m.reviewedAttachments?.[k]?.sha256)&&
            m.reviewedAttachments[k].sha256===m.reviewedCaseRule.attachments?.[k]?.sha256)))return false;
    return p.policy===policy&&m.program?.schemaVersion==='pm3-published-condition-program-v1'&&m.program.version==='1'&&
        m.program.mathPolicy===policy&&m.program.clinicalApproval===false&&families.includes(m.program.family)&&
        m.program.family!=='SVI_SOURCE_REVIEWED_GENERAL'&&m.program.family!=='NOT_APPLICABLE';
}

function conditionValid(c,policies,row){
    const v=c.publishedConditions;
    if(!v)return Boolean(c.qualificationBlocker||c.interpretationStatus==='CASE_SEMANTICS_UNRESOLVED');
    if(v.schemaVersion!==execution.version||v.mathPolicy!==policy||!['QUALIFIED','NOT_QUALIFIED','CONFLICT'].includes(v.status)||
        !Array.isArray(v.requirements)||v.requirements.length>32)return false;
    const statuses=['SOURCE_PHASE_ESTABLISHED','UNKNOWN_PHASE_WEIGHT','SATISFIED',...blockingStatuses];
    if(v.requirements.some(r=>!['QUALIFIED_PHASE_METHOD','PARTNER_APPROVED_BY_SPECIFICATION_ISSUER','PUBLISHED_SOURCE_AND_SCOPE',...caseConditionIds].includes(r?.conditionId)||
        !statuses.includes(r.status)||!(text(r.required)||object(r.required))))return false;
    // Failed source verification is a saved negative diagnostic, never a positive qualification.
    if(!v.family||!v.source)return row.status==='NOT_EVALUATED'&&v.status!=='QUALIFIED'&&
        v.requirements.some(r=>r.conditionId==='PUBLISHED_SOURCE_AND_SCOPE'&&['MISSING_INFORMATION','RULE_NOT_IMPLEMENTED','CONFLICT'].includes(r.status));
    if(!families.includes(v.family))return false;
    const selected=policies.find(p=>v.family==='SVI_SOURCE_REVIEWED_GENERAL'?!p.matches.length:
        p.matches[0]?.specificationKey===v.specificationKey);
    if(!selected||c.diseaseId!==selected.context.diseaseId)return false;
    if(v.family==='SVI_SOURCE_REVIEWED_GENERAL'){
        if(!same(v.source,selected.generalPolicy)||!same(v.catalogScope,selected.source))return false;
    }else{
        const m=selected.matches[0];
        if(m.scope.moi===''){
            const d=c.diseaseContextCheck,relations=d?.matchedRelationships;
            if(d?.qualification!=='VERIFIED'||d.inheritance!=='AR'||d.diseaseId!==selected.context.diseaseId||!Array.isArray(relations)||!relations.length||
                relations.some(r=>r.hgncId!==selected.context.hgncId||r.symbol!==selected.context.geneSymbol||r.mondoId!==selected.context.diseaseId||r.moi!=='AR'))return false;
        }
        if(v.source.rawSha256!==m.source.rawSha256||v.source.entityPointer!==m.source.jsonPointer||
            v.source.clausePointer!==m.clausePointer||v.source.clauseSha256!==m.clauseSha256||
            (v.family==='NOT_APPLICABLE'?selected.status!=='NOT_APPLICABLE':v.family!==m.program?.family))return false;
        if(v.family===caseEligibility&&!same(v.publishedTables,m.reviewedAttachments))return false;
    }
    const blocked=v.requirements.some(r=>blockingStatuses.includes(r.status));
    if(v.status==='QUALIFIED'&&(blocked||v.family==='NOT_APPLICABLE'))return false;
    if(v.status==='QUALIFIED'){
        const phase=v.requirements.filter(r=>r.conditionId==='QUALIFIED_PHASE_METHOD');
        const approval=v.requirements.filter(r=>r.conditionId==='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER');
        const needsApproval=v.family==='SVI_PHASE_AND_APPROVED_PARTNER'&&!isHomo(c)&&phase[0]?.observed!=='CIS';
        if(phase.length!==1||approval.length!==(needsApproval?1:0))return false;
        const caseConditions=v.family!==caseEligibility?[]:phase[0].observed==='CIS'?[phenotypeId]:
            isHomo(c)?[phenotypeId,targetClassificationId]:caseConditionIds;
        if(v.requirements.length!==1+approval.length+caseConditions.length||!caseConditions.every(id=>
            v.requirements.filter(r=>r.conditionId===id&&r.status==='SATISFIED').length===1))return false;
        if(v.family===caseEligibility){
            const checks=v.requirements.find(r=>r.conditionId===phenotypeId);
            if(!list(checks?.observed).some(route=>route.qualification==='VERIFIED'&&list(route.sourceFacts).some(f=>
                f.qualification==='VERIFIED'&&list(c.reportedFacts).some(reportedFact=>same(reportedFact,f.fact)))))return false;
        }
        if(!((phase[0].status==='SOURCE_PHASE_ESTABLISHED'&&['TRANS','CIS'].includes(phase[0].observed))||
            (phase[0].status==='UNKNOWN_PHASE_WEIGHT'&&phase[0].observed==='UNKNOWN')||
            (isHomo(c)&&phase[0].status==='SATISFIED'&&phase[0].phaseEvidenceStatus==='PHASE_NOT_REQUIRED'&&phase[0].observed==='UNKNOWN')))return false;
        if(needsApproval&&approval[0].status!=='SATISFIED')return false;
    }
    if(v.status==='CONFLICT'&&!v.requirements.some(r=>r.status==='CONFLICT'))return false;
    if(v.status==='NOT_QUALIFIED'&&!blocked&&v.family!=='NOT_APPLICABLE')return false;
    return true;
}

export function pm3V4FactsValid(row,profile,context,identityHash){
    if(!pm3V4BindingValid(profile?.moduleBindings?.pm3))return false;
    const f=row?.details?.pm3Facts;
    if(!f?.assessment?.calculation)return row.status==='NOT_EVALUATED'&&row.appliedStrength==='NONE';
    if(!sha(f.sourceSnapshotSha256)||!Array.isArray(f.policySelections)||!f.policySelections.length||
        !f.policySelections.every(p=>policyValid(p,context,identityHash))||!Array.isArray(f.caseFacts)||!f.caseFacts.length)return false;
    if(f.assessment.diseaseId!==context?.disease?.mondoId||f.caseFacts.some(c=>!/^PMC[1-9][0-9]*$/.test(c.pmcid||'')||
        !text(c.caseLabel)||!conditionValid(c,f.policySelections,row)))return false;
    // A rule-condition check is not the final PM3 result. Only the saved calculation is adopted.
    if(row.status==='MET')for(const contribution of list(f.assessment.calculation.contributions).filter(c=>c.awardedQuarterPoints>0)){
        if(list(contribution.requirements).length)return false;
        const matching=f.caseFacts.filter(c=>observationMatches(c,contribution,f.caseFacts));
        if(!matching.some(c=>c.publishedConditions?.status==='QUALIFIED'&&
            c.primarySourceQualification?.qualification==='VERIFIED'&&c.diseaseContextCheck?.qualification==='VERIFIED'&&
            c.rarityQualification?.target?.qualification==='VERIFIED'&&same(c.rarityQualification.target.queryAllele,context.variant?.evidenceQueryAllele)&&
            (isHomo(c)||c.rarityQualification?.partner?.qualification==='VERIFIED'&&c.partnerQualification?.independentClassification==='VERIFIED')&&
            (c.publishedConditions.family!==caseEligibility||(isHomo(c)?['target']:['target','partner']).every(role=>{
                const p=c.rarityQualification?.[role]?.populationEvidence,b=c.publishedTranscriptBinding?.[role];
                return p?.frequencyMethod==='JOINT_POPMAX_FAF95_INCLUSIVE'&&p.outcome==='EXACT_ALLELE'&&
                    text(p.popmaxFaf95)&&text(p.inclusiveFrequencyThreshold)&&Number.isFinite(Number(p.popmaxFaf95))&&
                    Number(p.popmaxFaf95)<=Number(p.inclusiveFrequencyThreshold)&&
                    same(b?.allele,c.rarityQualification[role].queryAllele);
            }))&&
            !list(c.publishedConditions.requirements).some(r=>['CIS','CONFLICT'].includes(r.observed))))return false;
    }
    return true;
}

const labels={VERIFIED:'已核实',UNKNOWN:'尚未确定',UNRESOLVED:'尚未核实',CONFLICT:'资料冲突',FAILED:'未满足',
    PATHOGENIC:'致病',LIKELY_PATHOGENIC:'可能致病',VUS:'意义未明',BENIGN:'良性',LIKELY_BENIGN:'可能良性',
    TRANS:'反式',CIS:'顺式',SOURCE_PHASE_ESTABLISHED:'相位已有原文依据',UNKNOWN_PHASE_WEIGHT:'未证实相位，按未定相位规则处理',
    CONFIRMED_TRANS:'确证反式',CONFIRMED_CIS:'确证顺式',REPORTED_UNDETERMINED:'原文明确未定相',
    PHASE_ORIGINAL_MISSING:'缺少可核对的相位原件',PHASE_CONFLICT:'相位资料冲突',PHASE_NOT_REQUIRED:'纯合观察无需分相',
    SATISFIED:'已满足',MISSING_INFORMATION:'资料不足',RULE_NOT_IMPLEMENTED:'适用规范尚未支持',
    COUNTED:'已计入',NOT_COUNTED:'未计入',DUPLICATE:'重复观察，未重复计入',NOT_APPLICABLE:'不适用',REQUIREMENT_NOT_MET:'未满足',
    QUALIFIED:'条件已核实',NOT_QUALIFIED:'条件未通过',SOURCE_APPROVAL_QUALIFIED:'专家组批准及适用规范已核实',NOT_ESTABLISHED:'尚未核实',
    APPLIED:'原文明确采用',USE_DENIED_OR_NOT_ESTABLISHED:'原文否认采用或未能确认采用',REFERENCE_ONLY_OR_UNINTERPRETED:'仅引用，尚未确认采用'};
const status=v=>labels[v]||'尚未确定';
const unique=rows=>rows.filter((r,i)=>r.value&&rows.findIndex(x=>x.label===r.label&&x.value===r.value)===i);
const reported=(c,field)=>[...new Set(list(c.reportedFacts).filter(f=>f.field===field).map(f=>f.sourceValue??f.value).filter(text))].join('；');
const isHomo=c=>{
    const values=[...new Set(list(c.reportedFacts).filter(f=>f.field==='reportedZygosity'&&text(f.value)).map(f=>f.value.trim().toLowerCase()))];
    return values.length===1&&values[0]==='homozygous';
};
const source=(citation,pmcid)=>{
    if(!citation||!text(citation.quote)||!text(citation.unitId))return null;
    const figure=citation.unitId.endsWith('/caption[1]')&&
        /^[A-Za-z][A-Za-z0-9_.-]{0,79}$/.test(citation.sourceObjectId||'')&&
        /^Figure [1-9][0-9]*$/.test(citation.sourceLabel||'');
    const article=`https://pmc.ncbi.nlm.nih.gov/articles/${pmcid}/`;
    return {pmcid,title:figure?`${pmcid} · ${citation.sourceLabel}`:pmcid,locator:citation.unitId,
        quote:citation.quote,url:figure?`${article}figure/${citation.sourceObjectId}/`:article};
};
const caseSources=c=>list(c.reportedFacts).flatMap(f=>[f.citation,...list(f.supportingCitations)].map(s=>source(s,c.pmcid))).filter(Boolean);
const phaseSources=(c,r)=>list(r.sourceFacts).map(f=>source(f.citation,c.pmcid)).filter(Boolean);
const phenotypeRoutes={
    ABSENT_OR_SEVERELY_DECREASED_ROD_ERG:'视杆 ERG 无反应或显著降低',
    CONGENITAL_NYCTALOPIA_OR_NIGHT_BLINDNESS:'先天性夜盲',
    LCA_OR_EARLY_ONSET_RETINAL_DYSTROPHY:'Leber 先天性黑矇或早发视网膜营养不良',
};
const missingPublishedMaterials={
    PM3_PUBLISHED_RELEASE_INDEX_UNAVAILABLE:'未取得可核对的发布规范索引。',
    PM3_PUBLISHED_RELEASE_SOURCE_UNAVAILABLE:'未取得适用版本的规范原件。',
    PM3_PUBLISHED_ATTACHMENT_ORIGINAL_UNAVAILABLE:'未取得适用版本的规则附件原件。',
    PM3_PARTNER_DEPENDENCY_REVIEW_UNAVAILABLE:'未取得该专家分类记录的依赖复核资料。',
};

const requirements={
    PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED:['病例身份与独立性','确认病例来源，同一病例或相关观察不重复计数。','病例来源或独立性尚未核实。','核对病例标识、家系关系及文献间病例重复情况。'],
    PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED:['疾病背景','病例为患病者，并与当前隐性疾病背景对应。','患病状态或疾病背景尚未核实。','核对原文诊断、患病状态与遗传方式。'],
    PM3_TARGET_IDENTITY_UNRESOLVED:['当前变异','原文变异与当前变异准确对应。','当前变异的对应关系尚未核实。','核对参考序列、转录本及变异表示。'],
    PM3_PARTNER_IDENTITY_UNRESOLVED:['另一变异','另一变异身份明确，且与当前变异不同。','另一变异的对应关系尚未核实。','核对另一变异的原文表示与参考序列。'],
    PM3_TARGET_RARITY_NOT_ESTABLISHED:['当前变异稀有性','当前变异满足本次适用的稀有性要求。','当前变异的稀有性资格尚未核实。','核对适用人群规范、频率及检测覆盖资料。'],
    PM3_PARTNER_RARITY_NOT_ESTABLISHED:['另一变异稀有性','另一变异满足本次适用的稀有性要求。','另一变异的稀有性资格尚未核实。','核对另一变异的适用人群规范、频率及检测覆盖资料。'],
    PM3_PARTNER_CLASSIFICATION_INDEPENDENCE_UNRESOLVED:['伙伴分类独立性','另一变异的分类不能循环依赖当前变异提供的证据。','分类的独立依据尚未核实。','核对原始分类依据及其与当前变异的依赖关系。'],
    PM3_PARTNER_CLASSIFICATION_MISSING:['伙伴分类','核实另一变异在当前疾病背景中的分类。','另一变异的分类尚未核实。','核对原始分类记录、疾病及采用依据。'],
    PM3_PARTNER_SPECIFICATION_APPROVAL_REQUIRED:['专家组批准','适用规范要求指定专家组批准另一变异，并采用对应规范。','尚未核实完整的批准与规范采用资料。','核对专家组原始分类记录及采用的规范。'],
    PM3_PHASE_CONFLICT:['相位资料冲突','相位记录及其检测依据一致。','原文相位资料存在冲突。','核对家系检测、分子相位方法及原文记录。'],
    PM3_PHASE_ORIGINAL_MISSING:['相位原件缺失','需要同一病例两处等位基因的分相原件或明确未定相的原文。','本次资料缺少可核对的相位记录。','核对正文、补表或家系检测记录。'],
    PM3_OCCURRENCE_CONFLICT:['同病例记录冲突','同一病例的基因型、相位及相关记录一致。','同一病例的记录存在冲突。','核对同一病例的各处原始记录。'],
    PM3_HOMOZYGOUS_OBSERVATION_CONFLICT:['纯合观察冲突','纯合观察与同病例的基因型及相位记录一致。','纯合观察存在不相容记录。','核对原文基因型及相位记录。'],
    PM3_CIS_NOT_APPLICABLE:['顺式观察','本次双变异分支不采用已证实顺式观察。','','无需将顺式观察补作反式；可核对其他独立观察。'],
    PM3_PARTNER_CLASSIFICATION_NOT_ELIGIBLE:['伙伴分类不适用','另一变异的分类符合本次规范允许的范围。','','核对其他符合规范的独立观察。'],
};

function observationMatches(c,contribution,cases){
    const refs=list(contribution.sourceReferences),own=new Set(caseSources(c).map(s=>`${s.pmcid}#${s.locator}`));
    if(!refs.length||!refs.every(r=>own.has(r)))return false;
    // Shared table/legend references must not attach a contribution to another patient.
    return cases.filter(other=>other.pmcid!==c.pmcid||other.caseLabel!==c.caseLabel)
        .every(other=>!refs.every(r=>new Set(caseSources(other).map(s=>`${s.pmcid}#${s.locator}`)).has(r)));
}

// The saved-result adapter exposes these facts only after validating the PM3 row.
export function reviewedPm3CombinationRule(entry){
    const facts=entry?.pm3LiteratureFacts, calculation=facts?.assessment?.calculation;
    if(entry?.pm3ConditionsVersion!=='pm3-selected-literature-v4'||calculation?.status!=='MET')return undefined;
    const cases=list(facts.caseFacts), counted=list(calculation.contributions).filter(c=>c.awardedQuarterPoints>0);
    if(!counted.length)return undefined;
    const ruleIds=new Set();
    for(const contribution of counted){
        const matches=cases.filter(c=>c.publishedConditions?.status==='QUALIFIED'&&observationMatches(c,contribution,cases));
        if(!matches.length)return undefined;
        for(const {publishedConditions: condition} of matches){
            const table=condition.publishedTables?.classificationPoints;
            const ruleSet=/^\/ld\/RuleSet\/([0-9]+)\/ld\/File\/[0-9]+$/.exec(table?.pointer||'');
            if(condition.family!==caseEligibility||!text(condition.specificationKey)||!sha(table?.sha256)||!ruleSet)return undefined;
            ruleIds.add(`${condition.specificationKey}/RuleSet/${ruleSet[1]}`);
        }
    }
    return ruleIds.size===1?[...ruleIds][0]:undefined;
}

function knownFor(reason,c){
    if(reason==='PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED')return `病例来源：${status(c.primarySourceQualification?.qualification)}；与其他病例的独立性：${c.primarySourceQualification?.independenceEstablished===true?'已核实':'尚未核实'}`;
    if(reason==='PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED')return [reported(c,'diagnosis'),reported(c,'affectedStatus'),status(c.diseaseContextCheck?.qualification)].filter(Boolean).join(' · ');
    if(reason==='PM3_TARGET_IDENTITY_UNRESOLVED')return reported(c,'targetVariant')||'尚未取得可对应的原文变异。';
    if(reason==='PM3_PARTNER_IDENTITY_UNRESOLVED')return reported(c,'partnerVariant')||'尚未取得可对应的另一变异。';
    if(reason.includes('RARITY')){
        const q=c.rarityQualification?.[reason==='PM3_TARGET_RARITY_NOT_ESTABLISHED'?'target':'partner'];
        if(q?.scope==='GNOMAD_JOINT_EXOMES_GENOMES_V4_1'&&
            ['PM3_PUBLISHED_JOINT_INDEXED_SOURCE_REQUIRED','PM3_PUBLISHED_JOINT_EXACT_SITE_REQUIRED'].includes(q.reasonCode))
            return '未取得该位点的 gnomAD v4.1 联合查询回执。';
        if(q?.scope==='GNOMAD_JOINT_EXOMES_GENOMES_V4_1'&&q.populationEvidence?.frequencyMethod==='JOINT_POPMAX_FAF95_INCLUSIVE')
            return q.populationEvidence.popmaxFaf95?`联合 PopMax FAF95：${q.populationEvidence.popmaxFaf95}；上限：${q.populationEvidence.inclusiveFrequencyThreshold}`:
                '缺少两者之一：精确等位基因的联合记录、或可核实的最高群体 FAF95。';
        return `稀有性资格：${status(q?.qualification)}`+(q?.scope==='LOCAL_GNOMAD_EXOMES_ONLY'?'；来源范围：本地 gnomAD 外显子组':'')+
            (q?.reasonCode==='PM2_SPECIALIST_POLICY_REVIEW_REQUIRED'?'；适用人群规范尚未完成审核':'');
    }
    if(reason.includes('CLASSIFICATION'))return `另一变异原始分类：${status(c.partnerQualification?.sourceClassification)}；分类独立性：${status(c.partnerQualification?.independentClassification)}`;
    if(reason.includes('PHASE')||reason.includes('HOMOZYGOUS'))return [reported(c,'reportedZygosity'),reported(c,'phaseText')].filter(Boolean).join('；')||'原文相位记录尚未核实。';
    return [reported(c,'targetVariant'),reported(c,'partnerVariant')].filter(Boolean).join(' · ')||'已有病例观察资料。';
}

export function pm3ConditionDetails(facts,{current=false}={}){
    if(facts?.schemaVersion!=='pm3-selected-facts-v1'||(!current&&!list(facts.caseFacts).some(c=>c.publishedConditions?.schemaVersion===execution.version)))return null;
    const rows=[],sections=[],comparisons=[],conflicts=[],sources=[],cases=list(facts.caseFacts),contributions=list(facts.assessment?.calculation?.contributions);
    const count=facts.assessment?.calculation;
    if(Number.isSafeInteger(count?.quarterPoints))rows.push({label:'本次 PM3 观察积分',value:String(count.quarterPoints/4)});
    for(const p of list(facts.policySelections)){
        const m=list(p.matches)[0];
        const release=m?.source?.apiVersion,specification=m?.specificationKey;
        const title=release&&specification?`${specification.split('@')[0]}@${release}`:specification;
        rows.push({label:'适用规范',value:title||(!list(p.matches).length&&p.generalPolicy?.policy===policy?'ClinGen SVI · PM3（2019）':'')});
        const url=m?.source?.sourceUrl||p.generalPolicy?.sourceUrl;
        if(typeof url==='string'&&url.startsWith('https://'))sources.push({title:title||'ClinGen SVI · PM3',url});
        for(const [key,label] of [['casePoints','PM3 病例积分表'],['classificationPoints','分类证据组合表']]){
            const attachment=m?.reviewedAttachments?.[key];
            if(attachment?.url?.startsWith('https://'))sources.push({title:label,url:attachment.url,locator:attachment.locator});
        }
    }
    const seen=new Set();
    for(const c of cases){
        const v=c.publishedConditions;if(c.targetMatch==='DIFFERENT_TARGET')continue;
        const title=`${c.pmcid} · 病例 ${c.caseLabel}`,core=[],localSources=caseSources(c),homo=isHomo(c);
        const cis=c.phaseEvidenceStatus==='CONFIRMED_CIS'||list(v?.requirements).some(r=>r.conditionId==='QUALIFIED_PHASE_METHOD'&&r.observed==='CIS');
        const zygosity=reported(c,'reportedZygosity');
        core.push({label:'观察类型',value:homo?'纯合观察':({'compound heterozygous':'复合杂合',heterozygous:'杂合'})[zygosity.toLowerCase()]||zygosity||'尚未确定'},
            {label:'当前变异（原文）',value:reported(c,'targetVariant')},
            {label:'另一变异（原文）',value:homo?'本项纯合观察不要求另一个伙伴变异':reported(c,'partnerVariant')},
            {label:'病例来源核查',value:status(c.primarySourceQualification?.qualification)},
            {label:'与其他病例的独立性',value:c.primarySourceQualification?.independenceEstablished===true?'已核实独立性':'尚未核实'});
        if(!homo&&c.phaseEvidenceStatus)core.push({label:'核实相位',value:status(c.phaseEvidenceStatus)});
        if(v?.family===caseEligibility){
            core.push({label:'原文表型诊断',value:reported(c,'diagnosis')},
                {label:'原文分子诊断类别',value:reported(c,'reportedMolecularDiagnosisCategory')});
            for(const [role,label] of [['target','当前变异'],['partner','另一变异']]){
                const tx=c.publishedTranscriptBinding?.[role],q=c.rarityQualification?.[role],p=q?.populationEvidence;
                if(tx?.ruleTranscript)core.push({label:`${label}转录本`,value:[...new Set([tx.reportedTranscript,tx.ruleTranscript])].join(' → ')});
                if(p?.frequencyMethod==='JOINT_POPMAX_FAF95_INCLUSIVE'&&p.popmaxFaf95){
                    core.push({label:`${label}联合 PopMax FAF95`,value:`${p.popmaxFaf95}（${p.popmaxFaf95Ancestry}；上限 ${p.inclusiveFrequencyThreshold}） · ${status(q.qualification)}`});
                    if(p.source?.sourceUrl?.startsWith('https://'))sources.push({title:`gnomAD v4.1 联合 · ${label}`,url:p.source.sourceUrl,
                        locator:`chr${q.queryAllele?.contig}:${q.queryAllele?.position} ${q.queryAllele?.reference}>${q.queryAllele?.alternate}`});
                }
            }
            const partner=c.partnerQualification;
            if(Number.isSafeInteger(partner?.independentPoints))core.push({label:'去除依赖后的伙伴分类积分',value:String(partner.independentPoints)});
            for(const criterion of list(partner?.assertionAssessments)){
                if(criterion.status==='EXCLUDED_TARGET_DEPENDENT'){
                    core.push({label:'排除的伙伴依据',value:`${criterion.sourceCriterion} · 与当前变异相关（${list(criterion.sourcePmids).map(id=>'PMID '+id).join('、')}）`});
                    const citation=criterion.targetCitation;
                    if(citation?.sourceUrl)sources.push({title:citation.scv,url:citation.sourceUrl,locator:citation.locator,quote:citation.quote});
                }
            }
        }
        if(!v&&c.phaseEvidenceStatus==='REPORTED_UNDETERMINED')core.push({
            label:'相位原文',value:list(c.reportedFacts).filter(f=>f.field==='phaseText').map(f=>f.citation?.quote).filter(text).join('\n')});
        if(!homo&&!cis){
            core.push({label:'另一变异原始分类',value:c.partnerQualification?.sourceClassification?status(c.partnerQualification.sourceClassification):list(c.partnerQualification?.sourceClassificationLabels).join('／')},
                {label:'另一变异可采用分类',value:status(c.partnerQualification?.classification)},
                {label:'伙伴分类独立性',value:status(c.partnerQualification?.independentClassification)});
            for(const a of list(c.partnerQualification?.sourceAssertions)){
                const use=a.criteriaListUse;if(!use||!['CONFLICT','UNKNOWN'].includes(use.status))continue;
                const ref={title:a.scv,url:a.sourceUrl,locator:use.sourceLocator,quote:use.originalText};
                core.push({label:'伙伴分类原文核查',value:use.status==='CONFLICT'?'分类依据清单与实际采用声明冲突':'分类依据清单是否用于最终分类尚未确定'});
                if(use.status==='CONFLICT')conflicts.push({title:`${title} · 伙伴分类依据采用冲突`,field:'分类依据清单',
                    left:{label:'完整分类记录',value:use.originalText,sources:[ref]},
                    right:{label:'否认采用的原文',value:use.statement,sources:[{...ref,quote:use.statement}]},
                    conclusion:'本次未将被原文否认的依据清单用于确认伙伴分类独立性。'});
                else comparisons.push({title:`${title} · 伙伴分类依据采用`,requirement:'另一变异的分类依据明确用于最终分类，并独立于当前变异。',
                    known:use.originalText,gap:'尚未确认该清单实际用于最终分类。',conclusion:'本次尚不能据此确认伙伴分类独立性。',
                    suggestion:'核对原始最终分类及实际采用的证据清单。',sources:[ref]});
            }
        }
        if(!v&&c.qualificationBlocker){
            const {reasonCode,causeReasonCode}=c.qualificationBlocker;
            const software=['PM3_PUBLISHED_CONDITIONS_NOT_IMPLEMENTED','PM3_SUBTYPE_SPECIFICATION_REQUIRES_EXACT_CONTEXT'].includes(reasonCode);
            const missing=missingPublishedMaterials[causeReasonCode||reasonCode];
            comparisons.push({title:`${title} · ${software?'适用规范尚未支持':missing?'发布规范资料':'观察资格核查'}`,
                requirement:'当前变异与疾病背景对应，且本次适用的 PM3 规范与采用条件已经核实。',
                known:[reported(c,'targetVariant'),reported(c,'diagnosis')].filter(Boolean).join(' · ')||'已有原文病例资料。',
                gap:software?'':missing||'该观察的采用资格尚未核实。',conclusion:software?'当前软件尚未支持适用规范，本次未采用此观察。':'本次暂未采用此观察。',
                suggestion:software?'补齐该适用规范的程序支持。':'核对当前变异、疾病背景及适用规范。',sources:localSources,software});
        }
        for(const r of list(v?.requirements)){
            if(r.conditionId===phenotypeId){
                core.push({label:'病例表型资格',value:status(r.status)});
                for(const route of list(r.observed)){
                    for(const f of list(route.sourceFacts))if(f.qualification!=='UNKNOWN'&&f.fact?.citation?.quote)
                        core.push({label:phenotypeRoutes[route.route]||'表型原文',value:`${f.fact.citation.quote} · ${status(f.qualification)}`});
                }
                if(r.status!=='SATISFIED')comparisons.push({title:`${title} · 病例表型资格`,requirement:Object.values(phenotypeRoutes).join('；'),
                    known:list(r.observed).map(route=>`${phenotypeRoutes[route.route]||'表型'}：${status(route.qualification)}`).join('；'),
                    gap:r.status==='MISSING_INFORMATION'?'缺少同一病例可核对的合格表型记录。':'',
                    conclusion:'本次未采用此观察。',sources:localSources});
                continue;
            }
            if([targetClassificationId,partnerClassificationId].includes(r.conditionId)){
                const label=r.conditionId===targetClassificationId?'当前变异':'另一变异',scv=r.observed;
                core.push({label:`${label}同版分类`,value:scv?.scv?`${scv.scv} · ${scv.version}` :status(r.status)});
                if(scv?.sourceUrl)sources.push({title:scv.scv,url:scv.sourceUrl,locator:scv.sourceLocator});
                if(r.status!=='SATISFIED')comparisons.push({title:`${title} · ${label}同版分类`,requirement:'等位基因须有同一发布版本、同一疾病背景的分类。',
                    gap:r.status==='MISSING_INFORMATION'?'缺少符合范围的原始分类记录。':'',conclusion:'本次未采用此观察。',sources:localSources});
                continue;
            }
            if(r.conditionId===independenceId){
                if(r.status!=='SATISFIED')comparisons.push({title:`${title} · 伙伴分类独立性`,requirement:'剔除依赖当前变异的依据后，重新核查伙伴分类。',
                    known:list(c.partnerQualification?.missingCriteria).join('、'),
                    gap:r.status==='MISSING_INFORMATION'?missingPublishedMaterials[r.reasonCode]||'缺少可核对的分类依据或比较病例资料。':'',
                    conclusion:'本次未采用此观察。',sources:localSources});
                continue;
            }
            if(r.conditionId==='QUALIFIED_PHASE_METHOD'){
                if(!homo&&!c.phaseEvidenceStatus)core.push({label:'核实相位',value:status(r.observed)});
                if(!homo)core.push({label:'相位处理',value:status(r.status)},
                    {label:'相位检测原文',value:phaseSources(c,r).map(s=>s.quote).join('\n')});
                if(r.status==='CONFLICT'){
                    const refs=phaseSources(c,r);
                    conflicts.push({title:`${title} · 相位资料冲突`,field:'相位',
                        left:{label:'原文记录一',value:refs[0]?.quote||status(r.reported),sources:refs.slice(0,1)},
                        right:{label:'原文记录二',value:refs.slice(1).map(s=>s.quote).join('\n')||status(r.observed),sources:refs.slice(1)},
                        conclusion:'本次不采用存在冲突的观察。'});
                }
            }
            if(r.conditionId==='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER'){
                core.push({label:'指定专家组',value:list(r.required?.issuerNames).join('／')},
                    {label:'专家批准核查',value:status(r.status)},
                    {label:'要求采用的规范',value:r.required?.documentDoi||v?.specificationKey});
                for(const a of list(r.observed)){
                    const assertion=list(c.partnerQualification?.sourceAssertions).find(x=>x.scv===a.scv);
                    core.push({label:'批准记录',value:[a.scv,a.submitterName,a.classification].filter(text).join(' · ')});
                    const statements=list(a.methodStatements);
                    for(const s of statements)core.push({label:'规范采用原文',value:s.statement});
                    if(a.status==='CONFLICT'){
                        const applied=statements.filter(s=>s.status==='APPLIED'),denied=statements.filter(s=>s.status==='USE_DENIED_OR_NOT_ESTABLISHED');
                        const refs=items=>items.map(s=>({title:a.scv,url:assertion?.sourceUrl,locator:s.locator,quote:s.statement}));
                        conflicts.push({title:`${title} · 伙伴规范采用记录冲突`,field:'规范采用',
                            left:{label:'采用记录',value:applied.map(s=>s.statement).join('\n'),sources:refs(applied)},
                            right:{label:'否认或未确认采用的记录',value:denied.map(s=>s.statement).join('\n'),sources:refs(denied)},
                            conclusion:'本次未将相互矛盾的规范采用记录视为专家批准已满足。'});
                    }
                    if(assertion?.sourceUrl)sources.push({title:a.scv,url:assertion.sourceUrl});
                }
            }
            if(['MISSING_INFORMATION','RULE_NOT_IMPLEMENTED','CONFLICT'].includes(r.status)){
                if(r.phaseEvidenceStatus==='PHASE_ORIGINAL_MISSING')continue;
                const software=r.status==='RULE_NOT_IMPLEMENTED',phase=r.conditionId==='QUALIFIED_PHASE_METHOD';
                comparisons.push({title:`${title} · ${software?'适用规范尚未支持':phase?'相位核查':'规范采用核查'}`,
                    requirement:phase?'相位结论具有对应的亲本或分子检测依据。':r.conditionId==='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER'
                        ?'指定专家组批准另一变异，并按适用规范进行分类。':'适用规范的原件、范围及完整条件已核实。',
                    known:phase?status(r.observed):r.conditionId==='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER'
                        ?list(r.observed).map(a=>[a.scv,a.submitterName,a.classification,status(a.status)].filter(text).join(' · ')).join('；')||'尚未取得可核对的批准记录。'
                        :v?.specificationKey||'已有相关规范记录。',
                    gap:software||r.status==='CONFLICT'?'':'尚未核实完整的规则资料。',
                    conclusion:software?'当前软件尚未支持本规范条件，本次不能采用此观察。':r.status==='CONFLICT'?'本次不能采用冲突观察。':'该条件尚未通过；不能据此采用此观察。',
                    suggestion:software?'补齐适用规范的程序支持。':'核对对应原始规范、分类记录或家系检测资料。',sources:localSources,software});
            }
        }
        const matched=contributions.filter(x=>observationMatches(c,x,cases));
        for(const x of matched){
            core.push({label:'观察采用情况',value:status(x.qualificationStatus)});
            if(Number.isSafeInteger(x.awardedQuarterPoints))core.push({label:'实际计入的 PM3 积分',value:String(x.awardedQuarterPoints/4)});
            for(const q of list(x.requirements)){
                const def=requirements[q.reason];if(!def)continue;
                const conflict=q.status==='CONFLICT',missing=q.status==='MISSING_INFORMATION';
                comparisons.push({title:`${title} · ${def[0]}`,requirement:def[1],known:knownFor(q.reason,c),
                    gap:missing?def[2]:'',conclusion:conflict?'本次不采用存在冲突的观察。':q.status==='NOT_APPLICABLE'?'本观察不适用本次规则。':missing?'该条件尚未核实，此观察暂未计入。':'本观察未满足该项条件。',
                    suggestion:def[3],sources:localSources});
            }
        }
        const signature=JSON.stringify([c.pmcid,c.caseLabel,core]);
        if(!seen.has(signature)){seen.add(signature);sections.push({title,rows:unique(core)});}
    }
    const calcStatus=count?.status;
    const summary=calcStatus==='MET'?'本次已采用符合条件的双等位基因观察。':conflicts.length?'相关病例资料存在冲突，本次未采用冲突观察。':
        comparisons.some(r=>r.software)?'适用规范的部分条件尚未获当前程序支持。':calcStatus==='NOT_APPLICABLE'?'本次观察不适用 PM3 规则。':
        calcStatus==='NOT_MET'?'已核实的观察未达到本次 PM3 判定要求。':'部分观察的采用条件尚未核实。';
    return {rows:unique(rows),sections,comparisons:comparisons.filter((r,i)=>comparisons.findIndex(x=>same(x,r))===i),conflicts,sources,summary};
}
