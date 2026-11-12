// Read-only projection of the saved HCM method. No classification, strength or score is calculated here.
const array=value=>Array.isArray(value)?value:[];
const text=value=>typeof value==='string'?value:'';
const rows=values=>values.filter(row=>row.value);
const labelOf=(labels,key)=>typeof key==='string'&&Object.hasOwn(labels,key)?labels[key]:'';
const outcomeLabels={
    SUPPORTS_OTHER_PHENOTYPE:'已有资料支持其他疾病表型关联',
    NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE:'本次已完成范围内未发现其他疾病表型的支持资料',
    UNKNOWN:'其他疾病表型关联尚未核实',CONFLICT:'疾病关联资料存在冲突',INVALID:'核查资料与当前对象不一致',
};
const scopeLabels={EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS:'目标变异的专家原记录',TARGET_LITERATURE_RELATIONSHIP_REVIEW:'目标变异的文献病例关联'};
const scopeStates={COMPLETED_DECLARED_TARGET_SCOPE:'已完成本次声明范围',INCOMPLETE:'尚未完成',SOFTWARE_UNSUPPORTED:'判定规则尚未接通',
    COMPLETE:'已完成本次声明范围',UNAVAILABLE:'原始资料尚未就绪'};
const pendingLabels={
    PP2_TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED:'尚未建立当前变异的专家记录核查范围',
    PP2_OTHER_PHENOTYPE_CASE_RELATIONSHIP_NOT_IMPLEMENTED:'文献病例与疾病表型的关联尚未完成规则核查',
    PP2_OTHER_PHENOTYPE_DISEASE_RELATIONSHIP_UNRESOLVED:'资料所述疾病与本次疾病背景的关系尚未核实',
    PP2_NATIVE_READER_UNAVAILABLE:'原始资料读取未完成',PP2_NATIVE_READER_IMPLEMENTATION_UNAVAILABLE:'原始资料读取能力尚未就绪',
    PP2_SOURCE_RAW_PIN_MISMATCH:'原始资料的摘要校验未通过',
    PP2_HCM_OTHER_PHENOTYPE_SOURCE_UNAVAILABLE:'其他疾病表型的核查资料尚未就绪',
    PP2_HCM_OTHER_PHENOTYPE_OBSERVATION_REQUIRED:'本次运行尚未取得其他疾病表型的来源观察',
    PP2_WORKFLOW_CONFIGURATION_UNAVAILABLE:'文献取证配置尚未就绪',
    PP2_WORKFLOW_NOT_ENABLED:'本次文献取证能力未启用',
    PP2_WORKFLOW_CONFIGURATION_PIN_MISMATCH:'文献取证配置在本次运行中发生变化，尚未完成核查',
    PP2_AUTOMATIC_SOURCE_OBSERVATION_TIMEOUT:'本次原始资料查询超时',
    PP2_AUTOMATIC_SOURCE_OBSERVATION_FAILED:'本次原始资料查询执行失败',
    PP2_AUTOMATIC_DISCOVERY_SCOPE_UNAVAILABLE:'尚未取得当前变异的文献检索记录',
    PVS1_START_LOSS_AUTO_PUBLIC_NETWORK_DISABLED:'专家原记录的公网获取能力未启用',
    PUBLIC_NETWORK_DISABLED:'专家原记录的公网获取能力未启用',
    PVS1_START_LOSS_AUTO_SOURCE_UNAVAILABLE_OR_INVALID:'专家原始资料获取失败或返回内容无效',
    EREPO_GENE_PAGE_UNAVAILABLE_OR_INVALID:'专家记录目录尚有无法读取或核实的分页',
    PP2_AUTO_NATIVE_ENUMERATION_INCOMPLETE:'当前变异的专家原记录范围尚未完成核查',
    PP2_AUTO_NATIVE_GRCH38_SNV_SCOPE_UNSUPPORTED:'当前资料查询不支持本次参考版本或变异类型',
    PP2_OTHER_PHENOTYPE_DECLARED_RETRIEVAL_SCOPE_INCOMPLETE:'目标变异的文献检索范围尚未完成核查',
    PP2_DISCOVERY_ORIGINALS_UNAVAILABLE:'本次检索缺少可核对的原始响应资料',
    PP2_DISCOVERY_TARGET_ALIAS_IDENTITY_NOT_VERIFIED:'检索所用变异别名尚未与本次精确变异对应',
    PP2_OTHER_PHENOTYPE_QUESTIONS_NOT_ASSESSED:'尚未核清文中疾病过程、关联依据及其他可能解释',
    PP2_READING_LEDGER_INVALID:'文献阅读记录未通过完整性核对',
    PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE:'疾病关联问题资料尚未提取完整',
    PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED:'精确变异对应、疾病过程及疾病关联依据尚未完成核查',
    PP2_READING_MODEL_DISABLED_CACHE_MISSING:'模型未启用，且没有可复用的提取记录',
    PP2_READING_NEW_WORK_LIMIT:'本次新增提取任务额度已用尽',
    PP2_READING_TIME_BUDGET:'本次文献提取时间额度已用尽',
    PP2_READING_ARCHIVE_TECHNICAL_LIMIT:'原文资料超出本次归档容量限制',
    PP2_READING_EXTRACTION_INCOMPLETE:'文中相关资料尚未提取完整',
    PP2_READING_RELATIONSHIP_EMPTY_EXTRACTION_IS_NOT_ABSENCE:'提取记录为空，尚不能确认疾病关联资料已核清',
    PP2_READING_RELATIONSHIP_RECORD_WITHOUT_EXTRACTED_FACTS:'部分资料记录尚未提取到可核对的字段',
    PP2_READING_ATTACHMENT_NOT_FULLY_READ:'相关附件尚未完整读取',
    PP2_READING_ATTACHMENT_COVERAGE_INCOMPLETE:'相关附件的读取范围尚未核清',
    PP2_READING_QUESTION_METHOD_PIN_MISMATCH:'问题提取方法的来源校验未通过',
    PP2_READING_LEDGER_PIN_INVALID:'文献阅读记录的完整性校验未通过',
    PP2_READING_ORIGINAL_PIN_INVALID:'原文资料与保存摘要不一致',
    PP2_READING_ORIGINALS_UNAVAILABLE:'原文资料尚未取得或无法读取',
    PP2_READING_SCOPE_INTEGRITY_OR_EXECUTION_FAILED:'文献读取范围的核对或执行未完成',
    PP2_RELATIONSHIP_CHUNK_PROFILE_OR_PURPOSE_INVALID:'部分资料记录不属于本次疾病关联提取方法',
    PP2_LITERATURE_EXACT_SOURCE_GENE_TRANSCRIPT_CODING_UNRESOLVED:'原文中的基因、转录本与编码变异尚未唯一对应',
    PP2_LITERATURE_EXACT_VERSIONED_CODING_SNV_REQUIRED:'缺少可核对的带版本转录本与编码变异表示',
    PP2_LITERATURE_UNIQUE_SOURCE_OWNED_ALLELE_ASSERTION_UNRESOLVED:'尚未核实这条变异报告属于该病例本人',
    PP2_LITERATURE_OBSERVATION_SOURCE_OWNERSHIP_UNRESOLVED:'原文资料的病例或成员归属尚未核实',
    PP2_LITERATURE_CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED:'原文涉及亲属资料，该条记录的对象尚未核实',
    PP2_LITERATURE_REPEATED_CASE_LABEL_ACROSS_TABLES_UNRESOLVED:'不同表格使用了相同病例编号，病例身份尚未核实',
    PP2_LITERATURE_AFFIRMATIVE_SOURCE_HCM_CLINICAL_BACKGROUND_NOT_ESTABLISHED:'尚未核实原文是否明确将 HCM 诊断归于该病例',
    PP2_LITERATURE_SOURCE_HCM_DIAGNOSIS_REVISED_OR_EXCLUDED:'原文的 HCM 诊断曾被修订或排除，需核对后续记录',
    PP2_LITERATURE_ADDITIONAL_NON_HCM_PRIMARY_DIAGNOSIS:'同一病例另有非 HCM 诊断，疾病关联尚未核实',
    PP2_LITERATURE_ADDITIONAL_OR_UNRESOLVED_SOURCE_DIAGNOSIS:'同一病例的其他诊断尚未核实',
    PP2_LITERATURE_CURRENT_ASSOCIATION_AND_LOCAL_REFERENCE_CONFLICT:'同一编码变异在两份已核参考映射中对应的等位基因不一致',
    PP2_LITERATURE_SOURCE_TRANSCRIPT_CONFLICT:'原文中的转录本信息不一致',
    PP2_LITERATURE_NON_HCM_OBSERVATION_CAUSAL_RELATIONSHIP_NOT_ESTABLISHED:'文中报告了其他疾病，尚无已核实的变异因果依据',
    PP2_LITERATURE_AGGREGATE_FUNCTIONAL_OR_AUTHOR_MATERIAL_NEEDS_INDEPENDENT_CONTEXT:'队列、功能研究或作者讨论尚需核对其与本次变异和疾病的对应关系',
    PP2_LITERATURE_AUTHOR_CLAIM_DIRECTION_OR_OBJECT_NOT_INDEPENDENTLY_RESOLVED:'作者主张的对象或疾病关联方向尚未核实',
    PP2_LITERATURE_ALTERNATIVE_OR_CONTRADICTORY_EXPLANATION_UNRESOLVED:'其他解释或相反资料尚未核清',
    PP2_LITERATURE_SUPPORTING_REFERENCE_NOT_INDEPENDENTLY_REVIEWED:'支持性引用尚未独立核查',
    PP2_LITERATURE_KNOWN_TARGET_REFERENCES_NOT_REVIEWED:'仍有与目标变异相关的已知引文尚未核查',
    PP2_LITERATURE_RELEVANT_VISUAL_MATERIAL_NOT_REVIEWED:'相关图示尚未核查',
    PP2_LITERATURE_ATTACHMENT_SOURCE_SCOPE_UNRESOLVED:'附件资料的范围尚未核实',
    PP2_LITERATURE_DECLARED_SOURCE_UNITS_WITHOUT_QUALIFIED_OBSERVATION_REVIEW:'部分原文段落或图表的证据归属尚未核查',
    PP2_LITERATURE_OBSERVATION_RELATIONSHIP_UNRESOLVED:'部分文中观察与目标变异或疾病的关联尚未核实',
};
const classifications={Pathogenic:'致病', 'Likely pathogenic':'可能致病',Benign:'良性','Likely benign':'可能良性'};
const conflictLabels={PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT:'原专家断言与对应来源记录不一致',
    PP2_OTHER_PHENOTYPE_RELATIONSHIP_CONFLICT:'资料对疾病表型关联的记录不一致'};
const conflictFields={classification:'分类',disease:'疾病',recordVersion:'记录版本',assertion:'专家断言',
    diseaseId:'疾病编号',curationVersion:'审核记录版本',curationId:'审核记录编号',variationId:'ClinVar 变异编号',gene:'基因',diseaseConcept:'疾病编号与名称'};
const factLabels={geneSymbol:'基因',variantText:'文中变异',transcript:'转录本',proteinChange:'蛋白改变',
    reportedDiagnosis:'文中疾病',reportedPhenotype:'临床表现',reportedInheritance:'遗传方式',reportedFamilyId:'家系编号',
    reportedFatherGenotype:'父亲基因型',reportedMotherGenotype:'母亲基因型',reportedProbandTargetResult:'先证者变异检测',
    reportedMotherTargetResult:'母亲变异检测',reportedFatherTargetResult:'父亲变异检测',reportedProbandGenotype:'先证者基因型',
    reportedAge:'年龄',reportedOnset:'起病情况',reportedDiagnosisCertainty:'文中疾病确定程度'};
const subjectLabels={MOTHER:'母亲',FATHER:'父亲',PROBAND:'先证者'};
const relationshipFields={
    reportedClinicalDefinition:['DISEASE_CONTEXT','疾病定义'],reportedDiseaseCourse:['DISEASE_CONTEXT','疾病过程'],
    reportedClinicalAssessmentBasis:['DISEASE_CONTEXT','临床判断依据'],reportedSubject:['SOURCE_SUBJECT','资料所述对象'],
    reportedVariantDiseaseStatement:['AUTHOR_ASSERTION','作者所述关联'],reportedRelationshipBasis:['REPORTED_BASIS','报告的关联依据'],
    reportedStudyDesign:['STUDY_OBSERVATION','研究设计'],reportedObservationOrResult:['STUDY_OBSERVATION','观察或实验结果'],
    reportedComparatorOrControl:['STUDY_OBSERVATION','对照资料'],reportedAlternativeExplanation:['ALTERNATIVES_AND_REFERENCES','其他解释'],
    reportedLimitationsOrContradiction:['ALTERNATIVES_AND_REFERENCES','局限或相反结果'],
    reportedSupportingReference:['ALTERNATIVES_AND_REFERENCES','支持性引用'],
};
const materialKinds={INDIVIDUAL_CASE:'个体病例资料',FAMILY:'家系资料',COHORT:'队列研究',FUNCTIONAL_STUDY:'功能研究',
    AUTHOR_DISCUSSION:'作者讨论',CITED_MATERIAL:'引用资料',UNCLEAR:'尚未明确'};
const targetMatches={EXACT:'已对应本次精确变异',NON_TARGET:'已核实为其他变异',UNRESOLVED:'尚未核实'};
const countRange=(displayed,total)=>Number.isSafeInteger(displayed)&&Number.isSafeInteger(total)&&displayed>=0&&total>displayed;
const literal=value=>typeof value==='string'&&!['{','['].includes(value.trimStart()[0])&&!value.includes('\u0000')?value:'';
const internalValue=value=>/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(value)||['UNKNOWN','UNAVAILABLE','UNRESOLVED','MISSING'].includes(value);
const scalar=value=>{
    if(typeof value==='number')return Number.isFinite(value)?String(value):'';
    if(typeof value==='boolean')return value?'是':'否';
    const result=literal(value).trim();
    return result&&result.length<=600&&!internalValue(result)?labelOf(classifications,result)||result:'';
};
const sourceRecord=value=>{
    const result=literal(value).trim();
    return /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$/.test(result)&&!internalValue(result)?result:'';
};
function conflictSide(side,fallback) {
    const record=sourceRecord(side?.sourceRecordId),label=scalar(side?.label)||fallback;
    const sources=array(side?.sourceIds).flatMap(value=>{
        if(typeof value!=='string')return [];
        try {
            const url=new URL(value);
            return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?
                [{url:value,title:record?`来源记录 ${record}`:'来源记录'}]:[];
        }catch{return [];}
    });
    return {label:record?`${label} · ${record}`:label,value:scalar(side?.value),sources};
}
const paperSource=(paper,citation)=>({pmcid:/^PMC[1-9][0-9]*$/.test(paper.pmcid||'')?paper.pmcid:undefined,
    url:/^PMC[1-9][0-9]*$/.test(paper.pmcid||'')?`https://pmc.ncbi.nlm.nih.gov/articles/${paper.pmcid}/`:
        /^[1-9][0-9]*$/.test(paper.pmid||'')?`https://pubmed.ncbi.nlm.nih.gov/${paper.pmid}/`:'',
    title:literal(paper.title)||text(paper.pmcid)||'文献资料',unitId:text(citation?.unitId),quote:text(citation?.quote)});

function relationshipRecords(paper,paperIndex,sources,boundedMethod) {
    return array(paper.relationshipRecords).flatMap((record,index)=>{
        const values=rows([{label:'资料类型线索',value:labelOf(materialKinds,record.materialKindProposal)},
            {label:'变异对应核查',value:labelOf(targetMatches,record.targetMatch)},
            {label:'文中病例背景',value:boundedMethod&&record.clinicalDisposition==='CURRENT_TARGET_HCM_PROCESS'?'已核实为 HCM 病程':''}]);
        for(const [field,[group,label]] of Object.entries(relationshipFields))for(const fact of array(record.facts)){
            const value=literal(fact.value);
            if(fact.field!==field||fact.group!==group||!value||!text(fact.citation?.unitId)||!text(fact.citation?.quote))continue;
            values.push({label,value});sources.push(paperSource(paper,fact.citation));
        }
        if(record.anchor?.quote&&record.anchor?.unitId)sources.push(paperSource(paper,record.anchor));
        if(record.factsTruncated===true&&countRange(record.displayedFacts,record.factsInSource))
            values.push({label:'本记录字段范围',value:`${record.displayedFacts} / ${record.factsInSource} 项`});
        if(boundedMethod&&array(record.qualificationGaps).length){
            const gaps=[...new Set(record.qualificationGaps.map(gap=>labelOf(pendingLabels,gap?.reasonCode)||'本记录尚有未完成的核查'))];
            values.push({label:'缺少信息',value:gaps.join('；')});
        }
        return values.length?[{id:`${paperIndex}:material:${record.chunkIndex}:${record.recordOrdinal}:${index}`,
            label:`资料记录 ${index+1}`,rows:values}]:[];
    });
}

function paperSuggestion(codes,questionComplete) {
    if(codes.some(code=>['PP2_READING_QUESTION_METHOD_PIN_MISMATCH','PP2_READING_LEDGER_PIN_INVALID','PP2_READING_ORIGINAL_PIN_INVALID'].includes(code)))
        return '核对原文与阅读记录，修复校验问题后重新提取。';
    if(codes.some(code=>['PP2_READING_NEW_WORK_LIMIT','PP2_READING_TIME_BUDGET','PP2_READING_MODEL_DISABLED_CACHE_MISSING','PP2_READING_ARCHIVE_TECHNICAL_LIMIT'].includes(code)))
        return '处理提取能力或额度限制，继续尚未完成的文献读取。';
    return questionComplete?'核对精确变异与文中对象、疾病过程及关联依据。':'完成原文、相关图表和补充材料的读取与核查。';
}

// The saved projection carries literal facts, not a grant of disease/causal qualification.
function literatureDetails(assessment) {
    if(assessment?.methodVersion!=='pp2-literature-assessment-v1')return {papers:[],comparisons:[],fields:[]};
    const papers=[],comparisons=[],questionMethod=assessment.readingMethodVersion==='1.2.0';
    const boundedMethod=questionMethod&&assessment.qualificationVersion==='pp2-bounded-literature-qualification-v1';
    array(assessment.papers).forEach((paper,paperIndex)=>{
        const sources=[],cases=[];
        const labels=new Map(),seenLabels=new Map();
        array(paper.cases).forEach(item=>{const label=literal(item.caseLabel);if(label)labels.set(label,(labels.get(label)||0)+1);});
        array(paper.cases).forEach((item,index)=>{
            const values=rows([{label:'变异对应核查',value:labelOf(targetMatches,item.targetMatch)}]);
            array(item.facts).forEach(fact=>{
                const label=labelOf(factLabels,fact.field),value=literal(fact.value);
                if(!label||!value||!text(fact.citation?.unitId)||!text(fact.citation?.quote))return;
                const subject=labelOf(subjectLabels,fact.subject);
                const prefix=subject&&fact.subject!=='PROBAND'&&!label.startsWith(subject)?subject:
                    !subject&&fact.field.startsWith('reported')?'对象未明确':'';
                values.push({label:prefix?`${prefix}／${label}`:label,value});
                sources.push(paperSource(paper,fact.citation));
            });
            if(item.caseCitation?.quote&&item.caseCitation?.unitId)sources.push(paperSource(paper,item.caseCitation));
            if(item.factsTruncated===true&&Number.isInteger(item.factsInSource)&&Number.isInteger(item.displayedFacts))
                values.push({label:'本卡字段范围',value:`${item.displayedFacts} / ${item.factsInSource} 项`});
            const label=literal(item.caseLabel),ordinal=(seenLabels.get(label)||0)+1;seenLabels.set(label,ordinal);
            if(values.length)cases.push({id:`${paperIndex}:${item.chunkIndex}:${item.caseOrdinal}:${index}`,
                label:label?`病例 ${label}${labels.get(label)>1?` · 记录 ${ordinal}`:''}`:`病例记录 ${index+1}`,rows:values});
        });
        if(questionMethod)cases.push(...relationshipRecords(paper,paperIndex,sources,boundedMethod));
        const coverage=questionMethod?paper.questionCoverage:null;
        const questionComplete=coverage?.status==='RAW_QUESTION_SCOPE_READ';
        const metadata=rows([
            {label:'疾病关联核查',value:boundedMethod&&paper.relationshipStatus==='COMPLETE_DECLARED_SCOPE'?'已完成本次声明范围':
                paper.relationshipStatus==='NOT_ASSESSED'||boundedMethod&&paper.relationshipStatus==='INCOMPLETE'?'尚未完成':''},
            {label:'问题资料提取',value:questionMethod?questionComplete?'本次范围已完成提取':coverage?.status==='INCOMPLETE'?'尚未完成提取':'':''},
            {label:'问题提取进度',value:questionMethod&&Number.isSafeInteger(coverage?.expectedPlans)&&coverage.expectedPlans>0&&
                Number.isSafeInteger(coverage?.checkedPlans)&&coverage.checkedPlans>=0&&coverage.checkedPlans<=coverage.expectedPlans?
                `${coverage.checkedPlans} / ${coverage.expectedPlans} 项`:''},
            {label:'尚未核查图示',value:questionMethod&&Number.isSafeInteger(paper.unreviewedVisualCaptionCount)&&paper.unreviewedVisualCaptionCount>0?
                `${paper.unreviewedVisualCaptionCount} 项`:''},
            {label:'本卡病例范围',value:paper.casesTruncated===true&&Number.isInteger(paper.casesInSource)&&Number.isInteger(paper.displayedCases)?
                `${paper.displayedCases} / ${paper.casesInSource} 条`:''},
            {label:'本卡资料记录范围',value:questionMethod&&paper.relationshipRecordsTruncated===true&&countRange(paper.displayedRelationshipRecords,paper.relationshipRecordsInSource)?
                `${paper.displayedRelationshipRecords} / ${paper.relationshipRecordsInSource} 条`:''},
            {label:'本卡资料字段范围',value:questionMethod&&paper.relationshipFactsTruncated===true&&countRange(paper.displayedRelationshipFacts,paper.relationshipFactsInSource)?
                `${paper.displayedRelationshipFacts} / ${paper.relationshipFactsInSource} 项`:''},
        ]);
        if(metadata.length)cases.unshift({id:`${paperIndex}:scope`,label:'',rows:metadata});
        const identity=text(paper.pmcid)||text(paper.pmid)||String(paperIndex+1);
        if(!sources.length)sources.push(paperSource(paper));
        // Only contained quotes in the same source unit collapse. Disjoint quotes never join.
        const distinct=sources.filter((source,index)=>!sources.some((other,otherIndex)=>
            source.unitId===other.unitId&&source.quote&&other.quote&&other.quote.includes(source.quote)&&
            (other.quote.length>source.quote.length||otherIndex<index)));
        papers.push({id:`hcm:${identity}:${paperIndex}`,title:literal(paper.title)||text(paper.pmcid)||'文献资料',cases,sources:distinct});
        const codes=[...new Set(array(paper.gaps).map(gap=>gap?.reasonCode))];
        if(questionMethod&&paper.relationshipStatus==='NOT_ASSESSED'&&!codes.length)
            codes.push(questionComplete?'PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED':'PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE');
        const gaps=[...new Set(codes.map(code=>labelOf(pendingLabels,code)||'尚有未完成的资料核查'))];
        if(gaps.length)
            comparisons.push({title:`疾病关联核查 · ${text(paper.pmcid)||`文献 ${paperIndex+1}`}`,
                requirement:'核对精确变异与文中对象、疾病过程、关联依据和其他可能解释。',
                known:cases.some(item=>item.label)?'已提取文中资料及对应原文。':'本次已有文献阅读记录。',
                gap:gaps.join('；'),
                conclusion:questionMethod&&!questionComplete?'问题资料提取尚未完成，当前不能完成疾病关联核查。':'当前资料尚不能完成其他疾病表型关联的核查。',
                suggestion:paperSuggestion(codes,questionComplete),sources:[]});
    });
    const fields=rows([
        {label:'本次文献范围',value:Number.isInteger(assessment.papersInScope)?`${assessment.papersInScope} 篇`:''},
        {label:'已完成关联核查',value:Number.isInteger(assessment.assessedPapers)?`${assessment.assessedPapers} 篇`:''},
    ]);
    return {papers,comparisons,fields};
}

export function pp2HcmDetails(facts,{mainKind,reasonCode}={}) {
    if(facts?.schemaVersion!=='pp2-general-facts-v1'||facts.method!=='PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1'
        ||facts.methodVersion!=='1.0.0'||facts.scope!=='PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT')return null;
    const review=facts.otherPhenotypeReview,scope=facts.hcmSourceMethod?.scope||{};
    const literature=literatureDetails(review?.literatureAssessment);
    const scopeRows=array(review?.requiredScope).filter(item=>labelOf(scopeLabels,item.scope)).map(item=>{
        let value=labelOf(scopeStates,item.status)||'尚未核实';
        if(Number.isInteger(item.targetRecords)&&item.targetRecords>0&&Number.isInteger(item.qualifiedRecords)
            &&item.qualifiedRecords>=0&&item.qualifiedRecords<=item.targetRecords)value+=` · 已完成资格核查 ${item.qualifiedRecords} / ${item.targetRecords} 条`;
        return {label:labelOf(scopeLabels,item.scope),value};
    });
    const pending=[...new Set(array(review?.pending).map(item=>labelOf(pendingLabels,item?.reasonCode)).filter(Boolean))];
    if(!review&&labelOf(pendingLabels,reasonCode))pending.push(labelOf(pendingLabels,reasonCode));
    if(review?.outcome==='INVALID')pending.push('资料对象与当前变异或疾病背景的对应关系尚未核实');
    if(!pending.length&&!['SUPPORTS_OTHER_PHENOTYPE','NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE','CONFLICT'].includes(review?.outcome))
        pending.push('尚无完整的目标变异疾病关联核查结果');
    const fields=rows([
        {label:'规范所用依据',value:'肥厚型心肌病（HCM）病例中的罕见错义变异富集资料'},
        {label:'适用规范',value:text(facts.specificationKey)},
        {label:'执行转录本',value:text(facts.executionTranscript)},
        {label:'规范首选转录本',value:text(scope.preferredTranscript)||text(facts.declaredTranscript)},
        {label:'规范列名的 Ensembl 转录本',value:text(scope.unversionedSourceEnsembl)},
        {label:'其他疾病表型核查',value:labelOf(outcomeLabels,review?.outcome)||'尚无本次核查记录'},
        ...scopeRows,
        ...literature.fields,
    ]);
    const sections=array(review?.qualifiedEvidence).map((item,index)=>({title:`其他疾病关联资料 ${index+1}`,rows:rows([
        {label:'原专家断言',value:/^SCV\d+\.\d+$/.test(item.scv||'')?item.scv:''},
        {label:'原记录疾病',value:text(item.disease?.label)||text(item.disease?.id)},
        {label:'专家原分类',value:labelOf(classifications,item.originalClassification)},
    ])})).filter(section=>section.rows.length);
    const conflicts=[];
    array(review?.conflicts).forEach((item,index)=>{
        const reason=labelOf(conflictLabels,item.reasonCode)||'原始资料存在尚未解决的不一致';
        const differences=Array.isArray(item.differences)?item.differences:[item];
        let displayed=false;
        differences.forEach(difference=>{
            const field=labelOf(conflictFields,difference.field);
            if(!field&&(Array.isArray(item.differences)||difference.field!==undefined))return;
            const left=conflictSide(difference.left,'原记录一'),right=conflictSide(difference.right,'原记录二');
            if(!left.value&&!right.value)return;
            conflicts.push({title:`疾病关联冲突 ${index+1}`,field:field||'疾病关联资料',left,right,conclusion:reason});displayed=true;
        });
        if(!displayed)sections.push({title:`冲突资料 ${index+1}`,rows:rows([
            {label:'原专家断言',value:/^SCV\d+\.\d+$/.test(item.scv||'')?item.scv:''},
            {label:'来源记录',value:sourceRecord(item.referenceId)},
            {label:'原记录疾病',value:scalar(item.disease?.label)||scalar(item.disease?.id)},
            {label:'冲突内容',value:reason},
        ])});
    });
    let summary='';
    if(mainKind==='ADOPTED_MET'&&review?.outcome==='NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE')
        summary='符合本次肥厚型心肌病（HCM）专家规范的错义变异条件，已完成范围内未发现支持其他疾病表型的资料。';
    else if(mainKind==='NOT_MET'&&review?.outcome==='SUPPORTS_OTHER_PHENOTYPE')
        summary='已有资料支持该变异与其他疾病表型相关，未满足本次 PP2 的附加条件。';
    else if(mainKind==='NO_CONCLUSION')summary=review?.outcome==='CONFLICT'?'疾病关联资料存在冲突，本次尚不能采用 PP2。':
        review?.outcome==='INVALID'?'核查资料与当前变异或疾病背景不一致，本次不采用 PP2。':'其他疾病表型关联的核查尚未完成，本次尚不能采用 PP2。';
    const known=scopeRows.map(row=>`${row.label}：${row.value}`).join('；')||'已保存本次适用规范及转录本范围';
    return {title:'肥厚型心肌病（HCM）病例富集与疾病关联核查',fields,sections,conflicts,summary,papers:literature.papers,
        requirement:'变异属于本次肥厚型心肌病（HCM）专家规范允许的错义范围，病例富集依据适用，并完成当前变异与其他疾病表型关联的核查。',
        missing:pending.join('；'),comparisons:mainKind==='NO_CONCLUSION'?[...literature.comparisons,{title:'其他疾病表型关联',
            requirement:'核对当前变异的专家原记录与文献病例，明确其他疾病表型关联及本次实际核查范围。',
            known,gap:pending.join('；'),conclusion:summary,suggestion:review?.outcome==='CONFLICT'?'核对冲突记录对应的原专家断言与原文。':
                review?.outcome==='INVALID'?'核对资料对应的变异、疾病背景与本次分析对象。':'完成尚未核查的目标资料及病例疾病关联核查。',sources:[]}]:[]};
}
