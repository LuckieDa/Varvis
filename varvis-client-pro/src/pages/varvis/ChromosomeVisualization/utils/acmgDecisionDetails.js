import { reasonExplanation, validCoreMaterial, pp1ContributionPreparationView } from './acmgInput';
import { resultPresentation } from './acmgResultsWorkbench';

// A read-only projection of saved requirements. This is not a validator,
// scoring rule, extraction pass or recovery planner. No facts are inferred
// from free-text reasons, other backgrounds or a search through raw criteria.
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const items = value => Array.isArray(value) ? value.filter(object) : [];
const strings = value => Array.isArray(value) ? value.filter(v => typeof v === 'string') : [];
const own = (value,key) => object(value) && Object.hasOwn(value,key);
const pick = (value,keys) => Object.fromEntries(keys.filter(key=>own(value,key)).map(key=>[key,value[key]]));
const text = value => typeof value === 'string' ? value : value == null ? '未保存' : JSON.stringify(value);
const sourceRows = value => items(value).map(source=>pick(source,['quote','sourceText','pmcid','paperId','sourceId','unitId','locator','sourceLocator','xpath','sourceSha256','sha256','contentSha256','url','version','role','kind','mapping','fieldPath','start','end','originalName','archiveMemberName','page','sheet','cell']));
const refs = value => sourceRows(value).filter(source=>Object.keys(source).length);
const dedupe = values => [...new Map(values.map(value=>[JSON.stringify(value),value])).values()];
const unread = '未保存逐项已有事实；请核对下方模块事实与原件，不能由原因文字补造。';
const unknownDifference = '未保存单独的条件差异；不据此推断资料不存在或证据未满足。';
const noNext = '本条未保存具体下一步或可执行动作；不在读取历史时启动取证。';
const safeCore = value => { try { return validCoreMaterial(value); } catch { return false; } };

function needRow(need,origin) {
    return { origin, label: need.label || need.reasonCode || '已保存的待核条件',
        requirement: own(need,'requirement') ? need.requirement : own(need,'expected') ? need.expected : '本条未保存具体规则要求。',
        expected: own(need,'expected') ? need.expected : undefined,
        known: own(need,'knownFacts') ? need.knownFacts : own(need,'known') ? need.known : unread,
        difference: own(need,'actual') ? need.actual : unknownDifference,
        impact: need.blockingEffect === 'BLOCKS_SCORING' ? '阻止当前证据评分（保存标记 BLOCKS_SCORING）' : own(need,'blockingEffect') ? need.blockingEffect : '本条未保存单独的影响说明。',
        nextStep: own(need,'route') ? need.route : noNext,
        field: need.scope, reasonCodes: [need.reasonCode].filter(v=>typeof v==='string'),
        sources: refs([...items(need.sourceLocators),...items(need.sourceRefs),...items(need.knownFacts).flatMap(fact=>items(fact.sourceRefs))]) };
}

function pp1Rows(facts,comparisons,conflicts) {
    const e=facts.evaluation;
    if(!object(e)||e.schemaVersion!=='pp1-segregation-evaluation-v2')return;
    comparisons.push(...items(e.needs).map(need=>needRow(need,'PP1 正式评价')),
        ...items(e.clinicalObservationReview?.needs).map(need=>needRow(need,'PP1 同次临床观察')));
    for(const q of items(facts.qualifications)) {
        const source=q.sourceIdentity?.pmcid || 'PP1 来源';
        comparisons.push(...items(q.clinicalAssessments?.needs).map(need=>needRow(need,source+' 临床核查')),
            ...items(q.ascertainmentReview?.needs).map(need=>needRow(need,source+' 取样核查')));
        for(const [family,review]of Object.entries(object(q.ascertainmentReview?.families)?q.ascertainmentReview.families:{})) {
            if(!object(review))continue;
            for(const need of items(review.unresolved))comparisons.push({...needRow(need,source+' 家系取样核查'),
                field:family,known:own(need,'knownFacts')?need.knownFacts:review.sufficientConditions ?? unread,
                sources:refs([...items(need.sourceRefs),...items(review.sufficientConditions).flatMap(v=>items(v.sourceRefs))])});
            for(const conflict of items(review.conflicts))conflicts.push({origin:source+' 取样支持与限制冲突',
                field:family,reasonCode:conflict.reasonCode,
                left:{label:'支持声明',value:conflict.supportSourceRefs,sources:refs(conflict.supportSourceRefs)},
                right:{label:'限制声明',value:conflict.restrictionSourceRefs,sources:refs(conflict.restrictionSourceRefs)},
                impact:'保存的支持与限制并存；不自行选择有利来源。'});
        }
    }
}

function literatureRows(material,comparisons,conflicts,issues) {
    if(!safeCore(material)) {
        issues.push({kind:'DISPLAY',severity:'warning',title:'病例资料对照未通过结构核查',detail:'保留原始资料；不将未核验字段展示为已核实事实。'});return;
    }
    if(['DIFFERENT_TARGET','UNRESOLVED'].includes(material.selectionMatch)) {
        issues.push({kind:'SCOPE',severity:'info',title:material.selectionMatch==='DIFFERENT_TARGET'?'非当前目标资料未混入条件对照':'资料目标身份尚未核实，未并入当前条件对照',detail:material.caseKey});return;
    }
    const fields=material.fields;
    for(const f of fields.filter(field=>field.verification==='CONFLICT'))conflicts.push({origin:'病例资料字段冲突',field:f.path,
        reasonCode:f.issues.join('；'),left:{label:'保存的冲突记录值',value:f.value,sources:refs([...items(f.sourceEvidence),...items(f.citations)])},
        right:{label:'另一方资料',value:'当前记录未将冲突双方分别保存；请核对原句，不能补造另一方值。',sources:[]},
        impact:'字段保持 CONFLICT，不能将其当作已核实事实。'});
    for(const item of items(material.explanation?.items)) {
        const actual=strings(item.observedFieldPaths).map(path=>fields.find(field=>field.path===path)).filter(Boolean)
            .map(field=>({field:field.path,value:field.value,verification:field.verification,availability:field.availability}));
        comparisons.push({origin:material.caseKey,label:item.label,requirement:item.requirement,
            known:actual.length?actual:'本条未登记已经核实的相关字段。',difference:item.explanation,
            distinction:item.distinction,impact:item.effect,nextStep:item.nextStep,
            field:strings(item.fieldPaths),reasonCodes:strings(item.reasonCodes),
            sources:refs([...strings(item.observedFieldPaths).flatMap(path=>{
                const field=fields.find(f=>f.path===path);return [...items(field?.sourceEvidence),...items(field?.citations)];}),...items(item.checkedScope)])});
    }
    if(!material.explanation)for(const blocker of items(material.blockers)) {
        const field=fields.find(f=>f.path===blocker.path);
        comparisons.push({origin:material.caseKey,label:blocker.code,requirement:'本条仅保存字段阻断，未保存独立规则要求。',
            known:field?pick(field,['path','value','availability','verification']):unread,difference:blocker.meaning,
            impact:'该字段在保存的 blockers 中；不由本页重新判断资格。',nextStep:noNext,field:blocker.path,
            reasonCodes:[blocker.code,...strings(blocker.causeCodes)],sources:refs([...items(field?.sourceEvidence),...items(field?.citations)])});
    }
}

function pm3Rows(facts,comparisons) {
    if(facts.schemaVersion!=='pm3-selected-facts-v1')return;
    for(const contribution of items(facts.assessment?.calculation?.contributions))for(const q of items(contribution.requirements)) {
        comparisons.push({origin:'PM3 保存的观察要求',label:q.reason || q.reasonCode || '采用条件',
            requirement:q.requirement ?? q.expected ?? '当前要求仅保存原因代码，未保存具体规则条件。',
            known:q.knownFacts ?? q.known ?? unread,difference:q.actual ?? q.status ?? unknownDifference,
            impact:q.effect ?? '保留本条条件的原状态；不由此重算观察分数。',nextStep:q.nextStep ?? noNext,
            field:q.path ?? contribution.observationId,reasonCodes:[q.reason,q.reasonCode].filter(v=>typeof v==='string'),
            sources:refs(q.sourceRefs)});
    }
    for(const row of items(facts.caseFacts)) {
        const blocker=row.qualificationBlocker;
        if(object(blocker))comparisons.push({origin:'PM3 '+(row.pmcid || '')+' '+(row.caseLabel || '原文观察'),label:blocker.causeReasonCode || blocker.reasonCode,
            requirement:blocker.requirement ?? '本条保存首个资格阻断；未保存独立规则条件对照。',
            known:items(row.reportedFacts).map(fact=>pick(fact,['field','sourceValue','producer','citation'])),
            difference:blocker.actual ?? blocker.status ?? unknownDifference,impact:pick(blocker,['dependentChecks','reasonCode','causeReasonCode']),
            nextStep:blocker.nextStep ?? noNext,field:blocker.path,reasonCodes:[blocker.reasonCode,blocker.causeReasonCode].filter(v=>typeof v==='string'),
            sources:refs(items(row.reportedFacts).flatMap(fact=>[...items(fact.supportingCitations),...(object(fact.citation)?[fact.citation]:[])]))});

    }
}

// The caller supplies this property only after the population source/profile
// validator accepted its selected entry. Projection below also binds the saved
// trace and condition identifiers; it never evaluates an inequality itself.
function populationRows(entry,comparisons,issues) {
    const value=entry.savedPopulationResult;
    if(!object(value))return;
    const q=value.qualityDecision,e=value.evaluation,p=value.preparation?.policy,t=e?.deterministicTrace;
    if(q?.status!=='READY'||t?.executionStatus!=='COMPLETED')return;
    const issue=detail=>issues.push({kind:'DISPLAY',severity:'error',title:'BS1 条件与保存轨迹的对应待核查',detail});
    const selectedMode=value.preparation?.selectedMode;
    if(e?.criterionId!=='ACMG_AMP_2015:BS1'||!object(p)||!object(q.facts)||!Array.isArray(p.tiers)||
        !Array.isArray(t.steps)||!Array.isArray(t.parameters)||typeof selectedMode!=='string'||
        selectedMode!==value.context?.moi||e.contextId!==value.context?.contextId||
        entry.evaluation?.criterionId!=='ACMG_AMP_2015:BS1'||entry.evaluation.contextId!==e.contextId||
        entry.evaluation.evaluationResult?.status!==e.evaluationResult?.status) {
        issue('当前条目的证据或背景身份与保存的人口结果不同，未将其条件并入对照。');return;
    }
    const modes=items(t.parameters).filter(parameter=>parameter.name==='selectedMode');
    if(modes.length!==1||modes[0].value!==selectedMode) { issue('selectedMode 参数与当前准备背景不一致。');return; }
    const tiers=items(p.tiers).filter(tier=>tier.moi===selectedMode).sort((a,b)=>a.priority-b.priority);
    const steps=items(t.steps),parameters=items(t.parameters),operators={GT:'大于',GTE:'大于或等于',LT:'小于',LTE:'小于或等于'};
    const sources=refs([...items(p.sources),...items(p.sourceRefs),...items(p.knowledgeRecord?.qualification?.sourceBindings)]);
    const field={contextId:value.context.contextId,diseaseId:value.context.disease?.mondoId,selectedMode,
        policyId:p.policyId,metricId:p.statistic?.metricId,dataset:value.sourceRouting?.dataset ?? value.dataScope?.dataset};
    const add=(condition,stepCode,tier)=>{
        const saved=steps.filter(step=>step.stepCode===stepCode);
        if(saved.length>1||saved.some(step=>!['TRUE','FALSE','UNKNOWN'].includes(step.outcome))) {
            issue({stepCode,detail:'重复或无法识别的条件执行结果。'});return;
        }
        if(!['AF','FAF95','AC','AN'].includes(condition.fact)||!own(operators,condition.operator)||typeof condition.decimal!=='string') {
            issue({stepCode,detail:'保存条件未提供受支持的事实、比较符或十进制阈值，未补造。'});return;
        }
        if(tier) {
            const name=`tier.${tier.tierId}.${condition.fact}.${condition.operator}`,pin=parameters.filter(parameter=>parameter.name===name);
            if(saved.length&&(pin.length!==1||pin[0].value!==condition.decimal||pin[0].unit!==condition.unit)||!saved.length&&pin.length) {
                issue({stepCode,parameterName:name,policyThreshold:condition.decimal,savedParameters:pin,
                    detail:'保存的条件参数与政策对应不同，不能选择另一背景或自行重算。'});return;
            }
        }
        const known=pick(q.facts,['AF','FAF95','AC','AN','ancestry','karyotype']);
        const actual=own(q.facts,condition.fact)?q.facts[condition.fact]:`未保存 ${condition.fact} 事实值；不能补零或从另一统计代替。`;
        comparisons.push({origin:'BS1 当前背景已保存条件',label:stepCode,
            requirement:`${condition.fact} ${operators[condition.operator]} ${condition.decimal}（${condition.unit ?? '未保存单位'}）`,
            expected:pick(condition,['fact','operator','decimal','unit','basis','originalText','sourceLocator']),
            known:{...known,conditionFact:condition.fact,conditionActual:actual},
            difference:{fact:condition.fact,actual,requiredOperator:condition.operator,threshold:condition.decimal,
                savedOutcome:saved.length?saved[0].outcome:'未保存该条件的执行结果；不推断满足或不满足。',
                statement:'只展示保存的 TRUE/FALSE/UNKNOWN，不在前端计算或改变判断。'},
            impact:`保存结论 ${e.evaluationResult?.status ?? '未保存'} / ${e.appliedStrength ?? '未保存'}；轨迹 ${t.executionStatus}。条件结果不替代最终采用结论。`,
            nextStep:'核对本条保存政策、统计来源及完整轨迹；读取详情不重新评价。',field:{...field,tierId:tier?.tierId},
            reasonCodes:[e.evaluationResult?.reasonCode].filter(code=>typeof code==='string'),sources});
    };
    items(p.commonConditions).forEach((condition,index)=>add(condition,`BS1_COMMON_CONDITION_${index+1}`));
    tiers.forEach((tier,index)=>items(tier.conditions).forEach((condition,conditionIndex)=>add(condition,`BS1_TIER_${index+1}_CONDITION_${conditionIndex+1}`,tier)));
}

// The complete escape proof and its current-context arithmetic/source bindings
// are validated by acmgInput before this entry arrives. This projects the saved
// selected numeric clause, operands and caps; it does not rehash a partial rule
// into an original policy identity or grant a new strength.
function pvs1EscapeRows(criterion,comparisons,issues) {
    const f=criterion?.details?.pvs1Facts,p=f?.escapeAdoptionProof;
    if(!object(p)||p.status!=='AVAILABLE')return;
    const rule=p.selectedRule,check=p.ruleCheck,effect=p.proteinEffect,cap=p.strengthDerivation;
    const integer=value=>Number.isSafeInteger(value)&&value>=0;
    const strength=value=>['SUPPORTING','MODERATE','STRONG','VERY_STRONG'].includes(value);
    const issue=()=>issues.push({kind:'DISPLAY',severity:'warning',title:'PVS1 逃逸采用证明缺少可展示的条件对照',
        detail:'保留已有结论；未完整保存所选数值条款、蛋白改变、核查结果、来源对应或强度上限，不补写条件。'});
    if(criterion.criterionId!=='ACMG_AMP_2015:PVS1'||criterion.status!=='MET'||f.nmdPrediction!=='NMD_NOT_PREDICTED'||
        p.schemaVersion!=='pvs1-escape-adoption-proof-v1'||p.scientificApprovalCreated!==false||p.rescoringPerformed!==false||
        !object(rule)||!object(check)||check.status!=='MATCH'||!object(effect)||!object(cap)||
        typeof rule.ruleId!=='string'||!strength(rule.strength)||!['sourceRuleStrength','parentPolicyCap','adoptedStrength'].every(key=>strength(cap[key]))||
        ['spliceScopeCap','outcomeReviewCap','localMaximumStrength'].some(key=>own(cap,key)&&!strength(cap[key]))||
        cap.sourceRuleStrength!==rule.strength||cap.adoptedStrength!==criterion.appliedStrength||
        !['firstAlteredResidue','referenceProteinLength','alteredProteinLength','referenceResiduesAffected','netProteinShortening'].every(key=>integer(effect[key]))||
        !Array.isArray(p.sources)||!Array.isArray(rule.sourceIds)||!rule.sourceIds.length) {issue();return;}
    let requirement;
    if(rule.predicate==='CRITICAL_REGION'&&['start','end','minimumAffectedResidues'].every(key=>integer(rule[key])&&rule[key]>0))
        requirement=`所选关键区间 ${rule.start}–${rule.end} 至少影响 ${rule.minimumAffectedResidues} 个参考蛋白残基。`;
    else if(rule.predicate==='PROTEIN_LOSS_FRACTION'&&['numerator','denominator'].every(key=>integer(rule[key])&&rule[key]>0)&&
        ['GT','LT'].includes(rule.comparison)&&['REFERENCE_TAIL_ALTERED_OR_REMOVED','NET_PROTEIN_SHORTENING'].includes(rule.metric))
        requirement=`${rule.metric==='REFERENCE_TAIL_ALTERED_OR_REMOVED'?'参考尾部受影响或移除残基比例':'净蛋白缩短比例'} ${rule.comparison==='GT'?'大于':'小于'} ${rule.numerator}/${rule.denominator}。`;
    else {issue();return;}
    const sourceIds=[...new Set([...strings(rule.sourceIds),...strings(p.review?.sourceIds),
        ...Object.values(object(p.localReviews)?p.localReviews:{}).flatMap(review=>strings(review?.sourceIds))])];
    const sources=sourceIds.map(id=>items(p.sources).find(source=>source.sourceId===id));
    if(sources.some(source=>!source)||sources.length!==sourceIds.length) {issue();return;}
    comparisons.push({origin:'PVS1 已保存逃逸采用条款',label:rule.ruleId,requirement,
        expected:pick(rule,['ruleId','predicate','start','end','minimumAffectedResidues','metric','numerator','denominator','comparison','strength','sourceIds']),
        known:pick(effect,['firstAlteredResidue','referenceProteinLength','alteredProteinLength','referenceResiduesAffected','netProteinShortening']),
        difference:{savedRuleCheck:check,note:'MATCH 与数值操作数取后端保存证明；本页不重算条款或修改原判定。'},
        impact:pick(cap,['sourceRuleStrength','parentPolicyCap','spliceScopeCap','outcomeReviewCap','localMaximumStrength','adoptedStrength']),
        nextStep:'按所选条款、来源摘要和已保存审核记录核对原件；本页不重新取证、评分或创建审批。',
        field:{policy:p.policy,scope:p.scope,originalBindings:pick(p.bindings,['policySha256','selectedRuleSha256','transcriptModelSha256','evaluationId'])},
        reasonCodes:[p.reasonCode].filter(code=>typeof code==='string'),sources:refs(sources)});
}

// The adoption proof has already passed the current method/context reader.
// These rows expose its actual branches and observations, not a second event
// calculator. In particular no VAF, relation or fixed strength is inferred.
function deNovoAdoptionRows(criterion,comparisons,issues) {
    const f=criterion?.details?.deNovoFacts,p=f?.adoptionProof;
    if(!object(p))return;
    const receipt=items(f.calculationBindings).filter(binding=>binding.criterionId===criterion.criterionId);
    if(receipt.length===1)issues.push({kind:'EXECUTION_RECEIPT',severity:'info',title:'声明、运行与实际执行记录',
        detail:{...pick(receipt[0],['criterionId','declaredCalculatorVersion','runtimeCalculatorVersion','executedCalculatorVersion','compatibilityMode','selectorPolicySha256','phenotypePolicySha256']),
            executionNote:own(receipt[0],'executedCalculatorVersion')?'实际执行版本取保存回执，不由声明或运行版本推算。':'本条未保存 executedCalculatorVersion；声明及运行版本不证明已执行。'}});
    if(p.projectionStatus!=='COMPLETE'||p.status!=='ADOPTED') {
        issues.push({kind:'METHOD_PROOF',severity:p.projectionStatus==='INCOMPLETE'?'warning':'info',title:'新发专项采用证明的保存状态',
            detail:{...pick(p,['status','projectionStatus','reasonCode','basis','limitations']),
                events:items(p.events).map(event=>pick(event,['ordinal','disposition','status','reasonCode'])),
                note:'未据本证明显示已采用的专项条件；原 NOT_MET/NOT_EVALUATED、取证或执行状态分别保留，不改作病例资料不足。'}});return;
    }
    if(p.selectedCriterion!==criterion.criterionId?.split(':').pop()||criterion.status!=='MET') {
        issues.push({kind:'SHARED_EVENT',severity:'info',title:'共同事件按所选证据保留，不重复采用',detail:{
            currentCriterion:criterion.criterionId,currentStatus:criterion.status,currentStrength:criterion.appliedStrength,
            sharedSelectedCriterion:p.selectedCriterion,sharedAdoptedStrength:p.appliedStrength,
            note:'已采用证明属于保存的共同事件；它不生成当前未采用或未选择的证据结果。'}});return;
    }
    const issue=()=>issues.push({kind:'DISPLAY',severity:'warning',title:'新发采用证明的逐项对照未完整取得',
        detail:'保留原保存结论与原件；条款、条件、观察或来源字段不足以直接展示，未补造亲缘、检测或强度。'});
    const policy=p.policy;
    if(p.schemaVersion!=='denovo-adoption-proof-v1'||!object(policy)||!['TISSUE_DIFFERENTIAL_PS2','SVI_1_1_COMBINED_PS2','RASOPATHY_POINTS','SVI_1_1_PUBLISHED_POINTS'].includes(policy.eventMode)||
        !Array.isArray(p.events)||!Array.isArray(policy.sources)||!policy.sources.length||p.appliedStrength!==criterion.appliedStrength) {issue();return;}
    const prepared=[],clauses=refs(policy.sources),fields={...pick(policy,['eventMode','policyId','policyVersion','phenotypePolicySha256','clauseIdentityMethod','materialClass'])};
    const source=value=>Array.isArray(value)&&value.length>0&&value.every(object);
    const impact=event=>({currentCriterion:criterion.criterionId,savedStatus:criterion.status,savedStrength:criterion.appliedStrength,
        sharedSelectedCriterion:p.selectedCriterion,calculationMethod:p.calculationMethod,sharedAdoptedStrength:p.appliedStrength,
        ...pick(event,['adoptedStrength']),...pick(f,['rawQuarterPoints','effectiveQuarterPoints','strengthAdjustment']),
        note:p.calculationMethod==='EXPERT_FIXED_STRENGTH'?'固定强度按保存采用结果展示；零 SVI 积分不表示未采用。':'保留原始积分、实际选择及强度；本页不重新计分。'});
    const add=(event,label,requirement,known,difference,sources)=>prepared.push({origin:'新发已保存采用条件',label:`事件 ${event.ordinal} · ${label}`,
        requirement,expected:{eventMode:policy.eventMode,conditionBranch:event.conditionBranch,clauseIdentityMethod:policy.clauseIdentityMethod},known,difference,
        impact:impact(event),nextStep:'按条款来源和逐字段原件位置核对；本页不新增审批、重算 VAF 或再次启动取证。',
        field:{...fields,eventOrdinal:event.ordinal,condition:label},reasonCodes:[event.reasonCode].filter(code=>typeof code==='string'),sources:dedupe([...clauses,...refs(sources)])});
    for(const event of items(p.events)) {
        if(event.disposition!=='COUNTED') {
            issues.push({kind:'METHOD_EVENT',severity:'info',title:'同组未采用的观察保持原状态',detail:pick(event,['ordinal','disposition','status','reasonCode'])});continue;
        }
        if(policy.eventMode==='TISSUE_DIFFERENTIAL_PS2') {
            const c1=event.c1,c2=event.c2,parents=event.parentalAvailability;
            if(!['C1_AND_C2','C1_ONLY','PARENTS_UNAVAILABLE_AND_C2'].includes(event.conditionBranch)||!object(c1)||!object(c2)||!object(parents)||
                !['MET','NOT_ESTABLISHED'].includes(c1.status)||!['MET','NOT_ESTABLISHED'].includes(c2.status)||
                !source(c1.sources)||!source(parents.sources)||!['AVAILABLE','UNAVAILABLE','UNKNOWN'].includes(parents.value)||
                !Array.isArray(c2.observations)||c2.observations.some(observation=>!object(observation)||!source(observation.sources))) {issue();return;}
            add(event,'C1 三联体与检测',
                '按所选组织差异条款核对 C1：目标检测、母父关系、方法、靶区覆盖、检测能力与质量；亲本不可用不能代替阴性检测或亲缘确认。',
                pick(c1,['probandTarget','motherTarget','fatherTarget','maternity','paternity','confirmedParentage','maternityMethod','paternityMethod','method','targetCoverage','detectionCapability','quality']),
                {savedConditionStatus:c1.status,conditionBranch:event.conditionBranch,note:'状态与事实均按保存证明展示；未建立不等于原文没有。'},c1.sources);
            add(event,'亲本可用性',
                '采用分支使用已保存的亲本可用性记录；AVAILABLE、UNAVAILABLE、UNKNOWN 保留各自含义，不补造检测结果。',
                {parentalAvailability:parents.value},{conditionBranch:event.conditionBranch,savedAvailability:parents.value},parents.sources);
            add(event,'C2 组织观察与检测',
                '按已保存 C2 比较方法核对同一事件个体、不同组织及检测能力与受累/对照观察；本页不设新的等位比例阈值。',
                {...pick(c2,['comparisonMethod','samePerson','distinctTissues','detectionAdequate']),
                    observations:items(c2.observations).map(observation=>pick(observation,['observationOrdinal','role','personRole','altReads','totalReads','limit','method','quality']))},
                {...pick(c2,['status','limitation']),conditionBranch:event.conditionBranch,note:'数值为已保存 reads/limit；不重算 VAF，也不把未建立比较解释为资料不存在。'},
                items(c2.observations).flatMap(observation=>items(observation.sources)));
        } else {
            const c=event.combined;
            const rasopathy=policy.eventMode==='RASOPATHY_POINTS';
            const published=policy.eventMode==='SVI_1_1_PUBLISHED_POINTS';
            const branch=published?'SOURCE_BOUND_PUBLISHED_SVI_POINTS':rasopathy?'SOURCE_BOUND_RASOPATHY_POINTS':'SOURCE_BOUND_COMBINED_PS2';
            if(event.conditionBranch!==branch||!object(c)||c.clauseMode!==policy.eventMode||c.status!=='MET'||!source(c.sources)||
                ((rasopathy||published)&&(!Number.isSafeInteger(c.rawQuarterPoints)||c.rawQuarterPoints<=0))||
                (published&&c.familyHistory!=='COMPATIBLE')) {issue();return;}
            add(event,rasopathy||published?`${p.selectedCriterion} 专家组积分条款`:'组合 PS2 采用条款',
                published?'核对目标变异检测、亲缘、病例表型和家族史，按适用规则读取事件积分。':
                    rasopathy?'按本次适用的专家组条款核对先证者和双亲的目标变异检测、亲缘确认、表型和单事件积分。':
                        '核对实际采用的组合 PS2 条款身份、目标检测、原亲缘状态、表型和积分；是否允许未完全确证亲缘只按所选条款记录。',
                pick(c,['clauseMode','allowAssumedParentage','probandTarget','motherTarget','fatherTarget','maternity','paternity','confirmedParentage','phenotypeCategory','rawQuarterPoints','familyHistory']),
                {savedConditionStatus:c.status,confirmedParentage:c.confirmedParentage,allowAssumedParentage:c.allowAssumedParentage,
                    note:'保存的未确证亲缘保持原值，不改写成 CONFIRMED，也不从强度反推积分。'},c.sources);
        }
    }
    comparisons.push(...prepared);
}

/** Only the caller-selected entry participates. No other background is read. */
export function decisionDetails(entry) {
    const value=object(entry)?entry:{},issues=[],comparisons=[],conflicts=[];
    const saved=object(value.savedCriterion)?value.savedCriterion:object(value.pp1Criterion)?value.pp1Criterion:undefined;
    const evaluation=object(value.evaluation)?value.evaluation:undefined;
    const result=saved || evaluation;
    const original={criterionId:result?.criterionId,status:saved?.status ?? evaluation?.evaluationResult?.status,
        strength:saved?.appliedStrength ?? evaluation?.appliedStrength,reasonCode:saved?.reasonCode ?? evaluation?.evaluationResult?.reasonCode};
    const length=value.proteinLengthProofView;
    const lengthVisible=object(length)&&['ACMG_AMP_2015:PM4','ACMG_AMP_2015:BP3'].includes(saved?.criterionId)&&
        length.criterionId===saved.criterionId&&length.expectedBindingMode==='SELECTED_V3_AND_EVALUATION_V2'&&
        ['status','reasonCode','appliedStrength'].every(key=>length.result?.[key]===saved[key])&&
        length.clinicalValidationCompleted===false&&length.createsScientificApproval===false&&
        length.sourceAuthentication==='SERVER_ACTUAL_MATERIAL_REPROJECTION_REQUIRED';
    const lengthAvailability=lengthVisible&&length.availabilityOnly===true&&value.displayOutcome?.kind==='UNAVAILABLE'&&
        strings(value.presentationIssues).includes('PROTEIN_LENGTH_ADOPTION_PUBLIC_PROOF_UNAVAILABLE');
    const coding=value.pvs1CodingProofView,codingFacts=saved?.details?.pvs1Facts;
    const codingVisible=object(coding)&&saved?.criterionId==='ACMG_AMP_2015:PVS1'&&coding.criterionId===saved.criterionId&&
        ['status','reasonCode','appliedStrength'].every(key=>coding.result?.[key]===saved[key])&&
        coding.factsMethodVersion===codingFacts?.methodVersion&&coding.proofPointer==='#/escapeAdoptionProof'&&
        coding.assessmentStatus===codingFacts?.codingEscapeAssessment?.status&&coding.proofStatus===codingFacts?.escapeAdoptionProof?.status&&
        coding.sourceQualifiedResearch===codingFacts?.codingEscapeAssessment?.sourceQualifiedResearch&&
        coding.parentSourceQualifiedResearch===codingFacts?.sourceQualifiedResearch&&
        coding.clinicalValidationCompleted===false&&coding.scientificApprovalCreated===false&&coding.rescoringPerformed===false&&
        coding.sourceAuthentication==='SERVER_SOURCE_QUALIFICATION_AND_ORIGINAL_REPLAY_REQUIRED';
    const codingAvailability=codingVisible&&coding.availabilityOnly===true&&value.displayOutcome?.kind==='UNAVAILABLE'&&
        strings(value.presentationIssues).some(code=>['PVS1_CODING_ESCAPE_PUBLIC_FACTS_UNAVAILABLE',
            'PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_UNAVAILABLE'].includes(code));
    if(value.displayIssue&&!lengthAvailability&&!codingAvailability)issues.push({kind:'DISPLAY',severity:'error',title:'保存结果的展示或身份核验未通过',
        detail:value.summary || '当前保存字段尚不能由本页独立核验；原始状态与来源保留，不据此改判。',codes:strings(value.presentationIssues)});
    if(Array.isArray(value.executionDiagnostics)&&value.executionDiagnostics.length)issues.push({kind:'EXECUTION',severity:'error',
        title:'来源执行未通过或仅供诊断回放',detail:value.executionDiagnostics});
    if(Array.isArray(value.presentationIssues)&&value.presentationIssues.length&&!value.displayIssue)issues.push({kind:'DISPLAY',severity:'error',
        title:'保存字段核验问题',detail:strings(value.presentationIssues)});
    const trusted=!value.displayIssue;
    if(lengthVisible&&(trusted||lengthAvailability))issues.unshift({kind:'PROTEIN_LENGTH_CURRENT',
        severity:lengthAvailability?'warning':length.result.status==='MET'?'success':length.result.status==='NOT_MET'?'info':'warning',
        title:lengthAvailability?'公开采用证明暂不可用':'当前序列与规则核查',proteinLengthProof:length,
        codes:[length.proofReasonCode,length.unmetCondition].filter(Boolean)});
    if(codingVisible&&(trusted||codingAvailability))issues.unshift({kind:'PVS1_CODING_CURRENT',
        severity:codingAvailability?'warning':coding.result.status==='MET'?'success':'info',
        title:codingAvailability?'PVS1 编码逃逸证明尚不可采用':'PVS1 编码逃逸资料与采用条件',pvs1CodingProof:coding,
        codes:[coding.assessmentReasonCode,coding.proofReasonCode,coding.sourceQualification?.reasonCode].filter(Boolean)});
    if(trusted) {
        const details=object(saved?.details)?saved.details:{};
        const contribution=pp1ContributionPreparationView(saved);
        if(contribution?.valid&&contribution.systemGap)issues.unshift({kind:'SYSTEM_INTEGRATION',severity:'warning',
            title:'PP1 可信采用清单未就绪（系统接线）',contributionPreparation:contribution.preparation,
            detail:{pp4Points:contribution.pp4Points,ledgerStatus:contribution.preparation.ledgerStatus},
            codes:[contribution.preparation.reasonCode]});
        populationRows(value,comparisons,issues);
        pvs1EscapeRows(saved,comparisons,issues);
        deNovoAdoptionRows(saved,comparisons,issues);
        const pp1=details.pp1Facts || value.pp1LiteratureFacts;
        if(object(pp1))pp1Rows(pp1,comparisons,conflicts);
        const pm3=details.pm3Facts || value.pm3LiteratureFacts;
        if(object(pm3))pm3Rows(pm3,comparisons);
        const materials=Array.isArray(value.coreMaterials)?value.coreMaterials:details.deNovoFacts?.coreMaterials;
        for(const material of items(materials))literatureRows(material,comparisons,conflicts,issues);
        for(const event of items(details.deNovoFacts?.events))for(const comparison of items(event.factComparisons))conflicts.push({
            origin:'新发事件 '+text(event.ordinal)+' 来源差异',field:comparison.memberRole,reasonCode:comparison.reasonCode,
            left:{label:'普通来源结果',value:comparison.ordinaryResult,sources:strings(event.sourceRefs)},
            right:{label:'特殊事件来源结果',value:comparison.specialEventResult,sources:strings(event.sourceRefs)},
            impact:'保留来源差异；按保存的事件状态 '+text(event.disposition)+' 展示，不重新采用或计分。'});
        const explanation=reasonExplanation(saved || evaluation,details.explanation || evaluation?.explanation);
        if(explanation&&!comparisons.length)comparisons.push({origin:'已保存规则说明',label:original.reasonCode,requirement:explanation.missing,
            known:unread,difference:unknownDifference,impact:explanation.impact,nextStep:noNext,reasonCodes:[original.reasonCode],sources:[]});
        const preparation=details.materialPreparation;
        if(object(preparation)&&preparation.schemaVersion==='material-preparation-summary-v1'&&preparation.reasonCode===original.reasonCode&&
            preparation.moduleResultPointer===details.modulePointer) {
            if(preparation.moduleStatus==='FAILED')issues.push({kind:'EXECUTION',severity:'error',title:'资料模块执行失败',
                detail:pick(preparation,['module','moduleStatus','reasonCode','moduleResultPointer','capturedDocuments'])});
        }
    }
    // Reuse the shared category only. A process label is not a source fact or
    // a licence to invent missing clinical conditions, failure stages or actions.
    const presentation=resultPresentation(value),category=presentation.outcome.kind;
    const critical=new Set(['CONFLICT','EXECUTION_FAILED','CONFIGURATION','DISABLED','QUALITY_REJECTED','UNSUPPORTED','POLICY_NOT_READY']);
    if(!value.displayIssue&&critical.has(category)&&!(category==='EXECUTION_FAILED'&&issues.some(issue=>issue.kind==='EXECUTION'))&&
        !(category==='CONFIGURATION'&&issues.some(issue=>issue.kind==='SYSTEM_INTEGRATION'))) {
        const missingSpecifics={CONFLICT:conflicts.length?'双方原值与出处按已保存内容列于下方。':'原记录未将冲突双方的字段、值和出处分别保存，不能补造。',
            EXECUTION_FAILED:'原记录未单独保存失败阶段、最后成功点或可恢复性，不从错误文字推造。',
            CONFIGURATION:'原记录未单独保存具体未就绪的资源项；以原代码及下方绑定记录为准。',
            DISABLED:'仅保留本次运行所报状态；本页不改变开关或启用外部能力。',
            QUALITY_REJECTED:'质量拒绝不能解释为科学阴性；具体门槛与实际值仅按已保存资料展示。',
            UNSUPPORTED:'当前支持边界按原结果保留；未保存的算法细节不补写。',
            POLICY_NOT_READY:'规范接线状态按原结果保留；不回退其他规则或自行采用。'};
        issues.unshift({kind:category,severity:['CONFLICT','EXECUTION_FAILED'].includes(category)?'error':'warning',
            title:presentation.outcome.label+'（保存原因分类）',detail:{reasonCode:original.reasonCode,particulars:missingSpecifics[category]}});
    }
    return {original,issues:dedupe(issues),comparisons:dedupe(comparisons),conflicts:dedupe(conflicts),
        comparisonState:!trusted?'UNVERIFIED':comparisons.length?'SAVED':'NOT_SAVED',
        scopeStatement:'仅展示当前变异、当前背景的已保存事实与说明；读取详情不启动分析、取证或恢复任务。',
        fallbackNote:!trusted?'本记录尚未通过展示核查，条件对照不作为可信事实展示；原始值仍保留。':
            '本页未取得可直接核验的逐项条件对照；旧记录未保存条件对照这类结构时，不重新提取或补写历史事实，也不将其解释为病例资料不足。已有模块字段及原件引用见下方。'};
}
