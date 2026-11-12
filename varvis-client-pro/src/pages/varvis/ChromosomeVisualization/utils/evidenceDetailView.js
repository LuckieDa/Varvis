import { resultPresentation } from './acmgResultsWorkbench';
import { workbenchEvidenceCopy } from './acmgWorkbenchCopy';
import { decisionDetails } from './acmgDecisionDetails';
import { coreFieldLabel, coreFactState, publishedConditionValue, validCoreMaterial } from './deNovoCoreMaterial';
import { displayStrength } from './evidencePresentation';
import { formatVariantCoordinate } from './variantCoordinate';
import { ba1Details, reasonExplanation, ps2IdentityHash } from './acmgInput';
import { fullRnaEntryView, fullRnaCriterionClaim, fullRnaPending, fullRnaRescueReviewConflict, fullRnaSourceAdmissionFailed, fullRnaDeclaredStart } from './acmgFullRna';
import { pm3ConditionDetails } from './acmgPm3Conditions';
import { savedClinicalDetails } from './acmgSavedClinicalDetails';
import { pp2HcmDetails } from './acmgPp2HcmDetails';
import { computedRuleRows } from './acmgComputedRuleDetails';

const list=value=>Array.isArray(value)?value:[];
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const at=(value,path)=>path.split('.').reduce((current,key)=>current?.[key],value);
const present=value=>value!==undefined&&value!==null&&value!=='';
const terms={
    TRUE:'满足',FALSE:'未满足',UNKNOWN:'尚未确定',VERIFIED:'已核实',UNVERIFIED:'待核实',CONFLICT:'存在冲突',
    MET:'满足',NOT_MET:'未满足',NOT_EVALUATED:'尚未评价',NOT_APPLICABLE:'不适用',READY:'已就绪',BLOCKED:'未就绪',
    SUPPORTED:'支持',NOT_SUPPORTED:'不支持',QUALIFIED:'符合要求',UNRESOLVED:'尚未核实',HIT:'已检出',NO_RECORD:'未检出记录',
    CALIBRATED:'已完成校准',HARMFUL:'支持有害影响',BENIGN:'支持无害影响',UNINFORMATIVE:'未达到有效阈值',NONE:'无',
    SUPPORTING:'支持',MODERATE:'中等',STRONG:'强',VERY_STRONG:'非常强',STAND_ALONE:'独立',
    PROTEIN_MISSENSE:'错义蛋白影响',SPLICING:'剪接影响',ALPHAMISSENSE:'AlphaMissense',SPLICEAI:'SpliceAI',REVEL:'REVEL',
    missense_variant:'错义变异',stop_lost:'终止密码子丢失',stop_gained:'提前终止',start_lost:'起始密码子丢失',
    frameshift_variant:'移码变异',inframe_insertion:'框内插入',inframe_deletion:'框内缺失',protein_altering_variant:'蛋白改变变异',
    synonymous_variant:'同义变异',intron_variant:'内含子变异',splice_donor_variant:'剪接供体变异',
    splice_acceptor_variant:'剪接受体变异',splice_region_variant:'剪接区变异',
    mother:'母亲',father:'父亲',grandfather:'祖父／外祖父',grandmother:'祖母／外祖母',proband:'先证者',
    RAW:'原始分数',MASKED:'掩蔽分数',EXOMES:'外显子组',GENOMES:'基因组',JOINT:'外显子组与基因组联合数据',
    PRESENT:'检出目标变异',ABSENT:'未检出目标变异',ABSENT_QUALIFIED:'未检出目标变异（检测合格）',CONFIRMED:'已确认',ASSUMED_NOT_CONFIRMED:'未确认',ASSUMED_NOT_TESTED:'未做亲缘检测',
    NOT_TESTED:'未做检测',QUALITY_FAILED:'检测质量不足',MATERNALLY_INHERITED:'来自母亲',PATERNALLY_INHERITED:'来自父亲',
    ASSUMED_NOT_REPORTED:'未报告亲缘检测',AUTOSOMAL_DOMINANT:'常染色体显性',AUTOSOMAL_RECESSIVE:'常染色体隐性',
    X_LINKED:'X 连锁',X_LINKED_DOMINANT:'X 连锁显性',X_LINKED_RECESSIVE:'X 连锁隐性',
    IN_TRANS:'反式',TRANS:'反式',IN_CIS:'顺式',CIS:'顺式',HOMOZYGOUS:'纯合',COMPOUND_HETEROZYGOUS:'复合杂合',
    AFFECTED:'患病',UNAFFECTED:'未患病',NOT_REPORTED:'未报告',PROBAND:'先证者',MOTHER:'母亲',FATHER:'父亲',
    SAME_AMINO_ACID:'相同氨基酸改变',SAME_RESIDUE_DIFFERENT_AMINO_ACID:'同一残基的不同氨基酸替换',
    KNOWN_REPEAT:'已知重复区',QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION:'符合规范的重复区域',
    OUTSIDE_REPEAT:'不在重复区域',REPEAT_OVERLAP:'与重复区域重叠',CRITICAL_REGION:'关键功能区域',HOTSPOT:'热点',
    CRITICAL_FUNCTIONAL_REGION:'关键功能区域',PREDICTED_NMD:'预计触发 NMD',NMD_NOT_PREDICTED:'预计不触发 NMD',
    READY_FOR_EVALUATION:'资料已核实',UNIQUE_WITHIN_CHECKED_SCOPE:'检查范围内未重复',POSSIBLE_OVERLAP:'可能重复',KNOWN_DUPLICATE:'重复病例',
    VERIFIED_FOLLOW_UP_SAME_EVENT:'同一先证者的随访，按原始检测计一次',COMPATIBLE:'相容',
    HIGHLY_SPECIFIC:'高度特异表型',RASOPATHY_COMPATIBLE:'符合 RASopathy 表型',CONSISTENT:'表型一致',CONSISTENT_HIGH_HETEROGENEITY:'表型一致，遗传异质性较高',NOT_CONSISTENT:'表型不一致',
    CURRENT_CASE:'当前病例',LITERATURE_CASE:'文献病例',COUNTED:'已计入',ZERO_SUPPORT:'未形成有效贡献',DUPLICATE:'重复观察，未计入',EXCLUDED:'未采用',
    START_LOSS:'起始密码子丢失',NONSENSE:'无义变异',FRAMESHIFT:'移码变异',SPLICE_CDS_DELETION:'剪接导致编码区缺失',
    FOUND_FUNCTIONAL_ALTERNATIVE:'已发现功能相关的替代转录本',NOT_FOUND_IN_ASSESSED_SET:'核查集合内未发现功能性替代',
    QUALIFYING_FOUND:'已找到合格独立参考',NONE_IN_ASSESSED_SOURCES:'核查来源内未发现合格独立参考',NOT_REQUIRED:'无需此项',
    OBSERVED_RNA:'RNA 实验观察',PREDICTED_RNA:'RNA 后果预测',PRESERVED:'保持阅读框',ALTERED:'阅读框改变',
    SINGLE_EXON:'单外显子',LAST_JUNCTION_IN_3_PRIME_UTR:'末次连接点位于 3′UTR',MATCH:'符合',NO_MATCH:'不符合',PASS:'通过',
    NATIVE_DONOR_LOSS:'天然供体位点丢失预测',NATIVE_ACCEPTOR_LOSS:'天然受体位点丢失预测',
    NO_WEAKER:'当前预测不弱于参考',PUBLISHED_RELATIVE_TEN_PERCENT:'适用条款允许相对 10% 容差',
    PVS1_STRONG:'PVS1（强）',PVS1_MODERATE:'PVS1（中等）',PVS1_SUPPORTING:'PVS1（支持）',
    UNAVAILABLE:'不可用',DISABLED:'未启用',FAILED:'执行失败',INCOMPLETE:'尚未完成',ELIGIBLE:'位置符合范围',
    WITNESS_VALIDATED:'已核实独立致病参考',NO_QUALIFYING_REFERENCE_WITHIN_SCOPE:'限定范围内无合格独立致病参考',
    NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET:'核查集合内无合格功能性替代',GENERIC_FACTS_QUALIFIED:'通用起始丢失资料已核实',
};
const valueFields={
    symbol:'基因',hgncId:'基因编号',transcript:'转录本',accession:'转录本',hgvsc:'DNA 变化',hgvsp:'蛋白变化',
    proteinVariant:'蛋白变化',proteinPosition:'蛋白位置',position:'位置',start:'起点',end:'终点',
    status:'状态',conclusion:'核查结果',direction:'预测方向',appliedStrength:'采用强度',strength:'强度',
    AF:'频率 AF',FAF95:'过滤频率 FAF95',AC:'变异等位基因数 AC',AN:'检测等位基因数 AN',ancestry:'人群',karyotype:'核型',
    conditionFact:'统计量',conditionActual:'实际值',actual:'实际值',threshold:'阈值',decimal:'阈值',operator:'比较条件',
    requiredOperator:'比较条件',savedOutcome:'核查结果',fact:'统计量',unit:'单位',field:'字段',value:'内容',availability:'资料情况',verification:'核查情况',
    score:'分值',rawScore:'原始分值',cutoff:'阈值',lower:'下限',upper:'上限',minimum:'下限',maximum:'上限',
    referenceProteinLength:'参考蛋白长度',alteredProteinLength:'改变后蛋白长度',netAminoAcidChange:'氨基酸净变化',
    cdsPosition:'编码位置',firstChangedProteinPosition:'首个改变的氨基酸位置',referencePtc:'参考终止位置',
    expected:'规则要求',known:'已有信息',requirement:'规则要求',missing:'缺少信息',finding:'已有信息',gap:'差异',impact:'结论',nextStep:'建议',
    memberLabel:'家系成员',memberRole:'成员身份',memberKey:'家系成员',affectedStatus:'临床状态',targetResult:'目标变异检测',age:'年龄',
    maternal:'母亲检测',paternal:'父亲检测',maternity:'母系亲缘',paternity:'父系亲缘',phase:'相位',method:'核实方法',
    probandTarget:'先证者检测',motherTarget:'母亲检测',fatherTarget:'父亲检测',confirmedParentage:'亲子关系已确认',phenotypeCategory:'表型对应情况',
    familyHistory:'家族史',
    reason:'原因',summary:'结论',message:'原因',reasonText:'原因',particulars:'具体情况',
    stage:'失败阶段',error:'错误',cause:'原因',moduleStatus:'模块状态',ledgerStatus:'采用清单状态',
};
const operators={GT:'>',GTE:'≥',LT:'<',LTE:'≤',EQ:'='};

/** Bounded field rendering, never a JSON serialization or a traversal of unknown keys. */
export function readableEvidenceValue(value,depth=0) {
    if (!present(value)||depth>4) return '';
    if (typeof value==='boolean') return value?'是':'否';
    if (typeof value==='number') return Number.isFinite(value)?String(value):'';
    if (typeof value==='string') {
        if (value.trim().startsWith('{')||value.trim().startsWith('[')) { try {return readableEvidenceValue(JSON.parse(value),depth+1);} catch {return '';} }
        if (/^(?:context|evaluation|evidence|review)-sha256-|^[a-f0-9]{64}$|^\/(?:modules|private|bindings)\//.test(value)) return '';
        if (terms[value]) return terms[value];
        if (/^\/(?:groups|context|conditions|extensions)\//.test(value)) return coreFieldLabel(value);
        if (/^(?:NM_|NR_|NP_|ENST|ENSP|HGNC:|MONDO:|PMC)[0-9]/.test(value)) return value;
        if (operators[value]) return operators[value];
        if (/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(value)) return '';
        return value;
    }
    if (Array.isArray(value)) return [...new Set(value.map(v=>readableEvidenceValue(v,depth+1)).filter(Boolean))].join('；');
    if (!object(value)) return '';
    if(present(value.lower)&&present(value.upper)&&typeof value.lowerInclusive==='boolean'&&typeof value.upperInclusive==='boolean')
        return `${value.lower} ${value.lowerInclusive?'≤':'<'} 分值 ${value.upperInclusive?'≤':'<'} ${value.upper}`;
    if ((value.contig||value.chromosome)&&value.position) return formatVariantCoordinate(value);
    if (value.fact && value.operator && present(value.decimal)) return `${value.fact} ${operators[value.operator]||''} ${value.decimal}`.trim();
    return Object.entries(valueFields).filter(([key])=>Object.hasOwn(value,key))
        .map(([key,label])=>{const text=readableEvidenceValue(value[key],depth+1);return text?`${label}：${text}`:'';}).filter(Boolean).join('；');
}

const definition=(title,requirement,fields,kind='FACTS')=>({title,requirement,fields,kind});
// Field catalog covers the actual CURRENT18 criteria. Requirements are explanatory,
// not thresholds or a second implementation of the scientific evaluators.
export const evidenceDetailDefinitions={
    BA1:definition('人群频率与独立良性条件','有效人群频率达到适用的独立良性阈值，并完成数据质量、检测样本量及例外变异核查。',[
        ['数据来源','dataset'],['数据版本','release'],['所用人群','ancestry'],['核型','karyotype'],['频率 AF','AF'],['过滤频率 FAF95','FAF95'],['变异等位基因数 AC','AC'],['检测等位基因数 AN','AN'],['适用阈值','threshold'],['例外变异核查','exceptionStatus'],
    ],'POPULATION'),
    BS1:definition('疾病背景与频率阈值','有效频率高于当前疾病背景和遗传模式允许的阈值。',[
        ['数据来源','dataset'],['数据版本','release'],['所用人群','ancestry'],['核型','karyotype'],['频率 AF','AF'],['过滤频率 FAF95','FAF95'],['变异等位基因数 AC','AC'],['检测等位基因数 AN','AN'],['疾病频率阈值','threshold'],['患病率','prevalence'],['外显率','penetrance'],
    ],'POPULATION'),
    PM2:definition('低频与检测覆盖','当前数据集中的低频或未检出结果、样本量及位点检测覆盖符合本次适用规则。',[
        ['数据来源','dataset'],['数据版本','release'],['精确变异查询','queryStatus'],['人群频率','frequency'],['检测等位基因数 AN','detection.adjAn'],['检测覆盖核查','detection.status'],['是否分祖源统计','detection.ancestryStratified'],
    ],'POPULATION'),
    PP3:definition('预测结果与采用阈值','模型、变异机制、转录本和校准区间适用，所用预测结果支持有害影响。',[
        ['主要评价机制','primaryMechanism'],['所选转录本','transcriptMaterial.transcript'],
    ],'PREDICTION'),
    BP4:definition('无害预测与机制覆盖','适用规则要求的各变异机制均取得合格结果，并支持无有害影响。',[
        ['主要评价机制','primaryMechanism'],['需完成的机制','requiredBp4Mechanisms'],['所选转录本','transcriptMaterial.transcript'],
    ],'PREDICTION'),
    PS1:definition('相同影响的参考变异','合格的致病参考变异与当前变异产生相同氨基酸改变，或符合规范的相同剪接影响。',[
        ['参考检索状态','searchStatus'],['找到的参考变异数','discoveredCandidates'],['可比较参考数','comparableReferences'],['符合本条比较条件数','criterionMatches'],['排除参考数','excludedCandidates'],['尚待核查参考数','unresolvedCandidates'],
    ],'REFERENCE'),
    PM5:definition('同一残基的不同替换','同一氨基酸残基上的不同替换具有合格致病依据，并满足规范的独立性及强度条件。',[
        ['参考检索状态','searchStatus'],['找到的参考变异数','discoveredCandidates'],['可比较参考数','comparableReferences'],['符合本条比较条件数','criterionMatches'],['排除参考数','excludedCandidates'],['尚待核查参考数','unresolvedCandidates'],
    ],'REFERENCE'),
    BP7:definition('同义／内含子变异核查','变异属于规范允许的同义或内含子范围，并满足剪接、保守性及其他适用条件。',[
        ['变异后果','consequence'],['所选转录本','selectedTranscriptId'],['位置范围核查','positionStatus'],['实验资料核查','experimentCheckStatus'],['功能影响核查','functionalCheckStatus'],['资料目录情况','catalogSourceStatus'],['距剪接位点距离','spliceDistance'],['剪接预测结果','spliceScore'],['保守性结果','conservation'],
    ]),
    PVS1:definition('功能缺失机制与后果','疾病机制支持功能缺失；所选转录本、变异后果及对应分支条件支持本次采用强度。',[
        ['所选转录本','transcript'],['蛋白变化','predictedHgvsp'],['功能缺失机制资料','knowledgeStatus'],['机制资料是否通过来源核查','sourceQualifiedResearch'],['提前终止是否触发 NMD','nmdPrediction'],['核查分支','branch'],['规则强度上限','policyStrengthCap'],['已核实外显子数','materialSupply.verifiedExonCount'],
        ['提前终止起点（CDS）','ptcStart'],['提前终止终点（CDS）','ptcEnd'],['终止密码子所在外显子','ptcExonOrdinals'],
        ['末次外显子连接位置（CDS）','lastJunctionCdsPosition'],['至最后连接点距离（nt）','distanceToLastJunction'],['受影响外显子序号','exonOrdinal'],['外显子总数','exonTotal'],['参考 CDS 长度（nt）','codingEditComposition.referenceCdsLength'],['改变后 CDS 长度（nt）','codingEditComposition.alteredCdsLength'],
    ],'SEQUENCE'),
    PM4:definition('蛋白长度变化','蛋白长度发生改变，且变异范围、区域和排除条件满足当前适用分支。',[
        ['所选转录本','transcript'],['预测蛋白变化','predictedHgvsp'],['参考蛋白长度（aa）','referenceProteinLength'],['改变后蛋白长度（aa）','alteredProteinLength'],['氨基酸净变化（aa）','netAminoAcidChange'],['新的终止位置已解析','newTerminationResolved'],['重复区域核查','regionState'],['新终止密码子编码位置','alteredStopCdsPosition'],
    ],'SEQUENCE'),
    BP3:definition('重复区域与框内长度变化','框内长度改变位于符合规范的重复区域，并完成关键功能区域及其他排除条件核查。',[
        ['所选转录本','transcript'],['氨基酸净变化（aa）','netAminoAcidChange'],['重复区核查','repeatObservation'],['区域资格','regionState'],['区域名称','selectedRule.regionName'],['区域起点（aa）','selectedRule.start'],['区域终点（aa）','selectedRule.end'],['变异影响起点（aa）','queryRange.start'],['变异影响终点（aa）','queryRange.end'],['相关功能资料条数','functionalLeadCount'],
    ],'SEQUENCE'),
    PM1:definition('热点与关键功能区域','当前蛋白位置落入适用规范确认的热点、关键残基或关键功能区域，并满足该条款条件。',[
        ['所选转录本','transcript'],['蛋白位置（aa）','proteinPosition'],['参考氨基酸','refAminoAcid'],['替换氨基酸','altAminoAcid'],['适用规范','specificationKey'],['区域起点（aa）','selectedRule.start'],['区域终点（aa）','selectedRule.end'],['区域类型','selectedRule.regionType'],['条款采用强度','selectedRule.strength'],
    ]),
    PP2:definition('错义致病机制与良性变异背景','当前疾病以错义变异为重要致病机制，且良性错义变异较少；相关条款和疾病背景适用。',[
        ['所选转录本','executionTranscript'],['规范转录本','declaredTranscript'],['适用规范','specificationKey'],['错义致病机制','conditions.missenseMechanism.conclusion'],['良性错义变异背景','conditions.lowBenignMissense.conclusion'],['机制资料核查','knowledgeStatus'],
    ]),
    BP1:definition('截短主导机制与错义变异排除条件','疾病背景以截短变异为主要机制，当前变异属于错义，并满足适用规范要求的剪接及功能排除条件。',[
        ['所选转录本','transcript'],['蛋白变化','proteinVariantKey'],['适用规范','sourceSpecification'],['机制资料核查','knowledgeStatus'],['适用性结论','knowledgeDisposition'],['最大剪接变化分值','maximumObservedDelta'],['相关功能资料核查','functionalScreen.status'],
    ]),
    PS2:definition('亲缘已确认的新生事件','先证者检出目标变异，父母检测及检测充分性支持新生；亲子关系、疾病表型与独立事件条件符合规范。',[],'LITERATURE'),
    PM6:definition('亲缘未确认的新生事件','先证者及父母检测支持新生，但亲缘未确认；其余疾病表型、检测充分性与独立事件条件符合适用规范。',[],'LITERATURE'),
    PM3:definition('隐性背景的双等位基因观察','隐性疾病背景下，反式观察须核对另一等位基因的分类与相位；纯合观察按适用规范单独核查，并确认病例独立性。',[
        ['本次文献观察积分','assessment.calculation.quarterPoints','quarter'],['当前疾病背景','assessment.diseaseId'],
    ],'LITERATURE'),
    PP1:definition('家系共分离','家系成员的目标变异检测、同次临床状态、年龄和亲属关系已对应，并满足遗传模式及独立共分离计分条件。',[
        ['共分离积分','evaluation.awardedPP1Points'],['保存的证据强度','evaluation.strength'],['家系事件数','evaluation.events','length'],
    ],'LITERATURE'),
};

function cleanSentence(value) {
    const text=readableEvidenceValue(value);
    if (!text || /^(?:未保存可|本条未登记|本页未取得|仅展示当前|读取详情不|保留本条|不由本页|本条仅保存)/.test(text)) return '';
    return text.replace(/\b(?:context|evaluation|evidence|review)-sha256-[a-f0-9]+\b/g,'')
        .replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g,'').replace(/；?实际强度和依据以保存结果为准。?/g,'')
        .replace(/满足本次规则条件[；。]?/g,'').replace(/\s+/g,' ').trim();
}
function fieldRows(def,facts) {
    return def.fields.flatMap(([label,path,mode])=>{
        const value=at(facts,path);
        const text=mode==='quarter'&&typeof value==='number'?String(value/4):mode==='length'&&Array.isArray(value)?String(value.length):readableEvidenceValue(value);
        return text?[{label,value:text}]:[];
    });
}

function reviewedPm1Rows(facts) {
    const proof=facts.reviewedRegion,region=proof.region;
    if (proof.assessments) return recalculatedPm1Rows(proof);
    const change=`p.${facts.refAminoAcid}${facts.proteinPosition}${facts.altAminoAcid}`;
    const row=(label,requirement,actual,outcome)=>({label,requirement,actual,outcome});
    if (!region) return [row('蛋白位置','位于已核实的热点或关键功能区',change,
        proof.reviewedCoverage?'未满足':'待核实')];
    const variants=role=>list(region.observations).filter(o=>role.includes(o.role))
        .filter(o=>o.role!=='INDEPENDENT_PATHOGENIC'||o.proteinPosition!==facts.proteinPosition||o.altAminoAcid!==facts.altAminoAcid)
        .map(o=>`p.${o.refAminoAcid}${o.proteinPosition}${o.altAminoAcid}`);
    const pathogenic=variants(['INDEPENDENT_PATHOGENIC']);
    const benign=variants(['QUALIFIED_BENIGN']),conflicts=variants(['CONFLICTING']),unresolved=variants(['UNRESOLVED']);
    const survey=region.benignSurvey==='COMPLETE';
    return [
        row('蛋白位置',`${region.label} · ${region.start}–${region.end} aa`,change,'满足'),
        row('区域资格','已核实为同疾病背景的热点或关键功能区',region.reviewRationale,
            ({QUALIFIED:'满足',NOT_QUALIFIED:'未满足',PENDING:'待核实'})[region.reviewStatus]),
        row('区域致病资料','有独立于当前变异的致病错义观察',pathogenic.join('、')||'尚未取得合格资料',pathogenic.length?'满足':'待核实'),
        row('区域良性资料','核查良性错义分布及分类冲突',[...benign,...conflicts,...unresolved].join('、')||
            (survey?'未见经核实的相关良性错义变异':'核查未完成'),
        benign.length?'未满足':conflicts.length?'资料冲突':unresolved.length||!survey?'待核实':'满足'),
    ];
}

const pm1RegionalIssues = {
    PM1_REVIEWED_REGION_BENIGN_OBSERVATION: '区域内存在已核实的相关良性错义变异',
    PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED: '区域内有分类冲突',
    PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE: '部分对照的分类或背景待核实',
    PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE: '区域分布核查未完成',
    PM1_REVIEWED_REGION_REVIEW_PENDING: '关键区域资格待核实',
    PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING: '缺少独立致病对照',
    PM1_REGION_ENRICHMENT_CALIBRATION_REQUIRED: '缺少适用的强度校准依据',
    PM1_REGION_ENRICHMENT_DATA_INSUFFICIENT: '区域或背景缺少可比较的对照',
    PM1_REGION_ENRICHMENT_BELOW_THRESHOLD: '未达到适用的富集门槛',
};

function recalculatedPm1Rows(proof) {
    return proof.assessments.map(region => {
        const counts = region.comparisonCounts;
        const thresholds = list(region.calibration?.levels).map(level =>
            `${displayStrength(level.strength)}要求 ${level.minimumIndependentPathogenic} 个独立致病对照、OR ≥ ${level.minimumOddsRatio}、P ≤ ${level.maximumPValue}`);
        const enrichment = region.enrichment;
        const actual = [`${region.start}–${region.end} aa`,
            `区域内独立致病 ${counts.pathogenicInside} · 已核良性 ${counts.benignInside}`];
        if (region.method === 'MISSENSE_ENRICHMENT') {
            actual.push(`背景独立致病 ${counts.pathogenicOutside} · 已核良性 ${counts.benignOutside}`);
            if (enrichment) actual.push(`OR ${enrichment.oddsRatio === 'INFINITE' ? '∞' : enrichment.oddsRatio} · 单侧 P ${enrichment.pValue}`);
        }
        if (region.status === 'MET') actual.push(`可用强度 ${displayStrength(region.appliedStrength)}`);
        else actual.push(...(region.issues.length ? region.issues : [region.reasonCode]).map(issue => pm1RegionalIssues[issue]).filter(Boolean));
        return {
            label: region.label,
            requirement: thresholds.length ? thresholds.join(' · ') :
                region.method === 'DOCUMENTED_REGION' ? '当前疾病的已证实区域 · 无相关良性错义对照' : '适用的富集强度门槛',
            actual: actual.join(' · '),
            outcome: region.status === 'MET' ? '满足' : region.status === 'NOT_MET' ? '未满足' :
                counts.conflictingInside ? '资料冲突' : '待核实',
        };
    });
}

function pm1ComparisonRows(proof) {
    const roles = {INDEPENDENT_PATHOGENIC:'合格致病对照',QUALIFIED_BENIGN:'合格良性对照',CONFLICTING:'分类冲突',
        UNRESOLVED:'资料待核实',EXCLUDED_TARGET:'当前变异不计入',EXCLUDED_DIFFERENT_BACKGROUND:'不同疾病背景',
        EXCLUDED_DEPENDENT:'依据不独立',EXCLUDED_UNCERTAIN:'意义未明，不计入'};
    const classifications = {PATHOGENIC:'致病',LIKELY_PATHOGENIC:'可能致病',BENIGN:'良性',LIKELY_BENIGN:'可能良性',
        UNCERTAIN_SIGNIFICANCE:'意义未明'};
    const backgrounds = {MATCHED:'当前疾病',DIFFERENT:'其他疾病',UNRESOLVED:'疾病待核实'};
    return proof.observations.map(o => {
        const location = proof.assessments.filter(region => o.proteinPosition >= region.start && o.proteinPosition <= region.end)
            .map(region => region.label);
        return {
            label: `p.${o.refAminoAcid}${o.proteinPosition}${o.altAminoAcid}`,
            value: [roles[o.role], location.join('、') || '候选区域外',
                ...o.assertions.map(a => `${classifications[a.classification]}（${backgrounds[a.diseaseScope]}） ${a.rationale}`)].join(' · '),
        };
    });
}

function pm1ClinVarRows(facts, context) {
    const intake=facts.clinvarIntake;
    if (!intake) return [];
    if (!['pm1-clinvar-intake-v1','pm1-clinvar-intake-v2'].includes(intake.schemaVersion) || intake.usedForScoring!==false ||
        intake.transcript!==facts.transcript || intake.protein!==facts.protein ||
        intake.mondoId!==context?.disease?.mondoId || intake.moi!==context?.moi ||
        ps2IdentityHash(intake)!==facts.currentEvaluationBinding?.clinvarIntakeSha256)
        return [{label:'资料核查',value:'保存的对照资料无法核实'}];
    if (intake.status!=='ENUMERATED') {
        const cause={CLINVAR_UPSTREAM_PROVIDER_DISABLED:'本地 ClinVar 未启用',
            CLINVAR_UPSTREAM_RESOURCE_UNAVAILABLE:'缺少本地 ClinVar 文件或索引',
            CLINVAR_UPSTREAM_INTERVAL_LIMIT_OR_GAP:'转录本区间不完整或超出查询范围',
            CLINVAR_UPSTREAM_LIMIT_OR_INTERRUPTED:'查询达到范围上限或被中断',
            CLINVAR_UPSTREAM_RESOURCE_CHANGED:'查询期间来源文件发生变化'};
        return [{label:'资料核查',value:cause[intake.reasonCode]||'未能完成本地 ClinVar 查询'}];
    }
    const labels={PATHOGENIC:'致病／可能致病',BENIGN:'良性／可能良性',CONFLICT:'分类冲突',UNCERTAIN:'意义未明',OTHER:'其他分类'};
    const statuses={SUBMISSIONS_UNAVAILABLE:'缺少可核对的提交明细',SAME_DISEASE_CONFLICT:'同疾病提交分类冲突',
        AGGREGATE_CONFLICT:'ClinVar 汇总分类冲突',INDEPENDENT_BASIS_UNVERIFIED:'有同疾病提交，独立依据待核',
        AGGREGATE_CONFLICT_SCOPE_UNVERIFIED:'汇总有分歧，部分提交的疾病背景待核',
        DISEASE_SCOPE_PARTIALLY_VERIFIED:'部分提交的疾病背景待核',
        DIFFERENT_DISEASE:'其他疾病提交',DISEASE_SCOPE_UNVERIFIED:'提交的疾病背景待核'};
    const regions=list(intake.regions), records=list(intake.records);
    const usable=records.filter(row=>/^[1-9]\d{0,9}$/.test(row?.variationId||'')&&typeof row.proteinChange==='string');
    const inside=usable.filter(row=>list(row.insideRegionIds).length);
    const outside=usable.filter(row=>!list(row.insideRegionIds).length);
    const shown=inside.length&&outside.length?[...inside.slice(0,12),...outside.slice(0,12)]:usable.slice(0,24);
    const count=Number.isSafeInteger(intake.candidateRecordCount)?intake.candidateRecordCount:records.length;
    const coverage=intake.referenceSearchComplete?'':' · 查询不完整';
    const rows=[{label:'检索结果',value:`${count} 条候选 · 列出 ${shown.length} 条 · 未计入 PM1${coverage}`},
        ...regions.map(region=>({label:region.label,value:`区内 ${region.candidateRecordsInside} 条 · 区外 ${region.candidateRecordsOutside} 条`}))];
    if (intake.diseaseMappingSource?.status==='UNAVAILABLE') rows.push({label:'疾病编号核对',value:'本地疾病对应资料不可用，外部编号尚待核实'});
    return rows.concat(shown.map(row=>{
        const locations=regions.filter(region=>list(row.insideRegionIds).includes(region.regionId)).map(region=>region.label);
        const location=locations.join('、')||(regions.length?'候选区域外':'尚无已核定区域');
        const diseaseCounts=Number.isSafeInteger(row.unresolvedDiseaseSubmissionCount)
            ? `同疾病 ${row.sameDiseaseSubmissionCount} 条，其他疾病 ${row.otherDiseaseSubmissionCount} 条，背景待核 ${row.unresolvedDiseaseSubmissionCount} 条`:'';
        return {label:row.proteinChange,href:`https://www.ncbi.nlm.nih.gov/clinvar/variation/${row.variationId}/`,
            value:[location,labels[row.aggregateClassification],diseaseCounts,statuses[row.reviewStatus]].filter(Boolean).join(' · ')};
    }));
}

function scalarSources(values) {
    const unique=new Map();
    for(const s of list(values)) {
        if (!object(s)) continue;
        const pmcid=[s.pmcid,s.paperId,s.sourceId,s.url].map(v=>v?.match?.(/\bPMC[1-9][0-9]*\b/)?.[0]).find(Boolean);
        const pmid=[s.pmid,s.paperId,s.sourceId,s.url].map(v=>String(v||'').match(/^(?:PMID[:-]|https?:\/\/pubmed\.ncbi\.nlm\.nih\.gov\/)?([1-9][0-9]{0,14})\/?$/)?.[1]).find(Boolean);
        const url=typeof s.url==='string'&&/^https?:\/\//.test(s.url)?s.url:pmcid?`https://pmc.ncbi.nlm.nih.gov/articles/${pmcid}/`:pmid?`https://pubmed.ncbi.nlm.nih.gov/${pmid}/`:'';
        const locationValue=s.unitId||s.locator||s.xpath;
        const locator=typeof locationValue==='string'?locationValue:'',quote=!s.scopeOnly&&typeof s.quote==='string'?s.quote:'';
        if(!url&&!quote)continue;
        const figure=s.figure;
        const key=JSON.stringify([pmcid||pmid||url,locator,quote.replace(/\s+/g,' ').trim(),figure]);
        const kind=locator.startsWith('attachment:')?(/\/tbl\[/.test(locator)?'补充材料表格':'补充材料段落'):
            /\/table-wrap\[/.test(locator)?'原文表格':/\/fig\[/.test(locator)?(/\/caption\[/.test(locator)?'原文图注':'原文图示及图注'):quote?'原文段落':'';
        if(!unique.has(key))unique.set(key,{pmcid,pmid,url,locator,quote,title:typeof s.title==='string'?s.title:pmcid||(pmid?`PMID ${pmid}`:'资料来源'),location:s.locationLabel||s.location||kind,
            ...(figure?{figure}:{})});
    }
    return [...unique.values()];
}
const pm2RuleTitles={
    'https://www.nature.com/articles/gim201530':'ACMG/AMP 变异解读指南',
    'https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity':'ClinGen SVI · PM2 稀有性建议',
};
function pm2RuleSources(rule) {
    return list(rule?.sourceUrls).flatMap(url=>{
        const host=typeof url==='string'&&url.match(/^https:\/\/([^/?#\s]+)/)?.[1];
        if(!host)return [];
        const title=`PM2 规则出处 · ${host}`;
        return [{title,url}];
    });
}
function pm2DetailSources(sources) {
    const locations=sources.map(source=>{
        const page=source.url.split('#',1)[0];
        const section=source.url.slice(page.length+1);
        const path=page.split('?',1)[0];
        const query=page.slice(path.length+1);
        const ruleUrl=path.replace(/\/$/,'').replace(/^https?:\/\/[^/]+/i,origin=>origin.toLowerCase());
        return {source,section,ruleUrl,query,pageKey:JSON.stringify([ruleUrl,query]),linkOnly:!source.locator&&!source.quote};
    });
    const specificPages=new Set(locations.filter(location=>location.linkOnly&&location.section).map(location=>location.pageKey));
    const seen=new Set();
    return locations.flatMap(({source,section,ruleUrl,query,pageKey,linkOnly})=>{
        if(linkOnly&&!section&&specificPages.has(pageKey))return [];
        const key=JSON.stringify([pageKey,section,source.locator||'',source.quote||'']);
        if(seen.has(key))return [];
        seen.add(key);
        const spec=ruleUrl.match(/^https:\/\/cspec\.genome\.network\/cspec\/ui\/svi\/doc\/(GN\d+)$/)?.[1];
        const version=spec&&new URLSearchParams(query).get('version');
        const title=pm2RuleTitles[ruleUrl]||(spec?`ClinGen 专家组规范 · ${spec}${version?` v${version}`:''}`:'');
        return [title?{...source,title}:source];
    });
}
const uniqueRows=rows=>[...new Map(rows.filter(r=>r.value).map(r=>[`${r.label}:${r.value}`,r])).values()];
const deNovoEventReasons={DENOVO_PARENTAL_TARGET_PRESENT:'亲本检出同一目标变异',
    DENOVO_MATERNAL_TARGET_TEST_REQUIRED:'缺少母亲的合格目标位点检测',DENOVO_PATERNAL_TARGET_TEST_REQUIRED:'缺少父亲的合格目标位点检测',
    DENOVO_TARGET_TEST_QUALITY_FAILED:'目标位点检测质量不足',DENOVO_TARGET_TEST_CONFLICT:'目标位点检测结果冲突',
    DENOVO_PHENOTYPE_NOT_CONSISTENT:'病例表型与当前疾病不一致',
    DENOVO_PHENOTYPE_BASIS_REQUIRED:'缺少可用于核对当前疾病的个体表型资料',
    DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED:'已核个体表型不足以满足所选规范的表型条件',
    DENOVO_PHENOTYPE_SOURCE_CONFLICT:'同一病例的表型记录冲突',
    DENOVO_PHENOTYPE_POLICY_CONFLICT:'病例同时符合规范中互不相容的表型条件',
    DENOVO_PUBLISHED_FAMILY_HISTORY_REQUIRED:'缺少规范要求的父母表型或家族史记录',
    DENOVO_PUBLISHED_FAMILY_HISTORY_CONFLICT:'同一病例的家族史记录冲突',
    DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET:'已记录规范要求排除的家族史',
    DENOVO_PUBLISHED_CODING_VARIANT_BASIS_REQUIRED:'缺少当前转录本的编码变异依据',
    DENOVO_PUBLISHED_CASE_SOURCE_NOT_IMPLEMENTED:'当前资料来源尚未接入该专规的病例条件',
    DENOVO_EXPERT_POSITIVE_POINTS_UNIMPLEMENTED:'当前专家规则的正向计分条件尚未接通',
    DENOVO_LITERATURE_BETWEEN_CASE_INDEPENDENCE_UNRESOLVED:'病例之间的独立性待核实',
    DENOVO_EXPERT_STRENGTH_SCOPE_UNRESOLVED:'专家组条款与积分表的强度适用范围待核'};
const fieldNames={geneSymbol:'基因',transcript:'转录本',targetVariant:'文中变异',reportedZygosity:'合子状态',diagnosis:'文中疾病',affectedStatus:'临床状态',
    parentalTransmission:'父母携带情况',testingMethod:'检测方法',partnerVariant:'另一变异',partnerClassification:'另一变异分类',reportedPhase:'相位',phaseMethod:'相位核实方法',testingLimitations:'检测限制',classificationBasis:'分类依据'};

function literatureCards(entry,details) {
    const papers=[];
    const materials=entry.coreMaterials||details.deNovoFacts?.coreMaterials;
    for(const m of list(materials)) {
        if(!validCoreMaterial(m)||m.selectionMatch!=='EXACT')continue;
        const rows=[],sources=[];
        const linkedFollowUp=m.fields.some(f=>f.path==='/groups/overlap/eventGrouping'&&f.value==='VERIFIED_FOLLOW_UP_SAME_EVENT');
        for(const f of m.fields) {
            if(!/^\/groups\/(?:case|gene|variant|proband|mother|father|testing|parentage|phenotype|family|overlap)\//.test(f.path))continue;
            const value=publishedConditionValue(f)||readableEvidenceValue(f.value)||(f.path==='/groups/phenotype/positive'&&
                f.availability==='NOT_REPORTED_IN_CHECKED_SCOPE'?'未报告个体表型':'');
            const summarizedByGrouping=linkedFollowUp&&f.path==='/groups/overlap/independence'&&f.value==='UNIQUE_WITHIN_CHECKED_SCOPE';
            if(value&&!summarizedByGrouping)rows.push({label:coreFieldLabel(f.path).replace(' · ','／'),value,state:f.verification==='VERIFIED'&&f.availability==='REPORTED'?'':coreFactState(f)});
            const evidence=list(f.sourceEvidence);
            sources.push(...evidence,...list(f.citations).filter(c=>!evidence.some(s=>
                s.sourceSha256===c.sourceSha256&&s.locator===c.locator&&s.quote===c.quote)));
        }
        const pmcid=(m.caseKey||'').match(/PMC[1-9][0-9]*/)?.[0];
        const caseLabel=(m.caseKey||'').split('#').slice(1).join('#');
        const references=sources.map(s=>{
            const reference={...s,pmcid:s.pmcid||s.paperId||pmcid};
            if(pmcid&&s.paperId===pmcid&&m.dataClass==='PUBLIC_PAPER_RESEARCH'&&s.role==='PAPER_ATTACHMENT'&&s.kind==='SOURCE_UNIT')
                reference.figure={sourceSha256:s.sourceSha256,reviewSha256:m.ledgerSha256,caseLabel};
            return reference;
        });
        papers.push({id:m.caseKey,title:pmcid||'病例资料',caseLabel,rows:uniqueRows(rows),sources:scalarSources(references)});
    }
    const pm3=entry.pm3LiteratureFacts||details.pm3Facts;
    for(const c of list(pm3?.caseFacts)) {
        if(c.targetMatch==='DIFFERENT_TARGET')continue;
        const targetUnresolved=['UNRESOLVED','UNKNOWN','NOT_CHECKED'].includes(c.targetMatch);
        const rows=list(c.reportedFacts).flatMap(f=>{
            if(c.publishedConditions?.schemaVersion==='pm3-published-conditions-4'&&['targetVariant','partnerVariant','reportedZygosity','phaseText'].includes(f.field))return [];
            const value=readableEvidenceValue(present(f.sourceValue)?f.sourceValue:f.value);
            return value?[{label:fieldNames[f.field]||'病例事实',value,state:targetUnresolved?'目标对应关系待核查':c.interpretationStatus==='CASE_SEMANTICS_UNRESOLVED'?'含义待核查':''}]:[];
        });
        const sources=list(c.reportedFacts).flatMap(f=>[...(f.citation?[f.citation]:[]),...list(f.supportingCitations)]);
        papers.push({id:`${c.pmcid}:${c.caseKey||c.caseLabel||papers.length}`,title:c.pmcid||'文献病例',caseLabel:c.caseLabel||'',rows:uniqueRows(rows),sources:scalarSources(sources.map(s=>({...s,pmcid:c.pmcid})))});
    }
    const pp1=entry.pp1LiteratureFacts||details.pp1Facts;
    for(const event of list(pp1?.evaluation?.events)) {
        const members=typeof event.memberLabel==='string'?event.memberLabel.split(/[、,;；]/).map(v=>terms[v.trim()]||v.trim()).join('、'):'';
        const rows=uniqueRows([
            {label:'家系',value:readableEvidenceValue(event.familyLabel)},
            {label:'计入的患病携带亲属数',value:readableEvidenceValue(event.affectedRelativesCount)},
            {label:'其中必然携带者',value:Number.isInteger(event.obligateCarrierCount)&&event.obligateCarrierCount>0
                ?readableEvidenceValue(list(event.obligateCarrierLabels).join('、')||event.obligateCarrierCount):''},
            {label:'计入成员',value:members},
            {label:'计入传递',value:event.informativeMeioses===1?[readableEvidenceValue(event.parentLabel),members].filter(Boolean).join(' → '):''},
            {label:'原始共分离贡献',value:readableEvidenceValue(event.rawPoints)},
            {label:'本事件证据强度',value:event.expertStrength?displayStrength(event.expertStrength):''},
        ]);
        papers.push({id:`${event.pmcid}:${event.familyLabel||event.eventId}`,title:event.pmcid||'家系资料',caseLabel:event.familyLabel||'',rows,
            sources:scalarSources(list(event.sourceRefs).map(s=>({...s,pmcid:s.pmcid||event.pmcid})))});
    }
    for(const proof of list(pp1?.evaluation?.meiosisAssessment?.familyIndependenceEvidence)) {
        const sources=scalarSources(proof.sourceRefs), pmcid=sources[0]?.pmcid;
        if(sources.length)papers.push({id:`${pmcid||'PP1'}:family-independence`,title:pmcid||'家系资料',caseLabel:'家系独立性',rows:[],sources});
    }
    for(const q of list(pp1?.qualifications)) {
        const rows=[],sources=[];
        for(const o of [...list(q.observations),...list(q.unresolved)]) {
            const member=readableEvidenceValue(o.memberLabel);
            const fields=[['成员','memberLabel'],['临床状态','affectedStatus'],['目标变异检测','targetResult'],['年龄','age'],['检测方法','testingMethod'],['亲属关系','relationship']];
            for(const [label,path]of fields){const value=readableEvidenceValue(o[path]);if(value)rows.push({label:member&&path!=='memberLabel'?`${member}／${label}`:label,value});}
            sources.push(...list(o.sourceRefs));
        }
        const pmcid=q.sourceIdentity?.pmcid;
        papers.push({id:`${pmcid}:${q.familyId||q.caseKey||papers.length}`,title:pmcid||'家系资料',caseLabel:q.familyId||'',rows:uniqueRows(rows),sources:scalarSources(sources.map(s=>({...s,pmcid})))});
    }
    // Keep case identity while showing each paper's original regions only once.
    const grouped=new Map(),cases=new Map();
    for(const paper of papers.filter(p=>p.rows.length||p.sources.length)){
        const previous=cases.get(paper.id);
        if(previous){previous.rows=uniqueRows([...previous.rows,...paper.rows]);previous.sources=scalarSources([...previous.sources,...paper.sources]);}
        else cases.set(paper.id,{...paper});
    }
    for(const paper of cases.values()) {
        const key=/^PMC[1-9][0-9]*$/.test(paper.title)?paper.title:paper.id;
        if(!grouped.has(key))grouped.set(key,{id:key,title:paper.title,cases:[],sources:[]});
        const group=grouped.get(key);group.cases.push({id:paper.id,label:paper.caseLabel,rows:paper.rows});group.sources.push(...paper.sources);
    }
    for(const group of grouped.values())group.sources=scalarSources(group.sources);
    return [...grouped.values()];
}

const articleKey=source=>source.pmcid||(source.pmid?`PMID ${source.pmid}`:'');

function withArticleSources(papers,references){
    const groups=new Map(papers.map(paper=>[paper.id,paper]));
    for(const source of references){
        const key=articleKey(source);
        if(!key)continue;
        const existing=[...groups.values()].find(paper=>paper.id===key||paper.sources.some(s=>articleKey(s)===key));
        if(existing)existing.sources.push(source);
        else groups.set(key,{id:key,title:key,cases:[],sources:[source]});
    }
    return [...groups.values()].map(paper=>({...paper,sources:scalarSources(paper.sources)}));
}

function predictionRows(facts,code,details,presentation,context) {
    const adopted=presentation.mainKind==='ADOPTED_MET';
    const rule=facts.publishedRule;
    const [spec,version]=rule?.specificationKey?.split('@')||[];
    const publishedSource=/^GN\d+$/.test(spec)&&/^\d+\.\d+(\.\d+)?$/.test(version)
        ? {title:`ClinGen ${spec} v${version}`,url:`https://cspec.genome.network/cspec/ui/svi/doc/${spec}?version=${version}`}
        : null;
    const publishedThreshold=code==='PP3'?rule?.revelThresholds?.pp3Minimum:rule?.revelThresholds?.bp4Maximum;
    if(publishedSource&&facts.route==='BLOCKED'){
        const reason=list(facts.mechanisms)[0]?.reason;
        return [{title:'专家预测规范',sources:[publishedSource],ruleRows:[{
            label:rule.publishedTranscript?'转录本':'适用条款',
            requirement:rule.publishedTranscript||publishedSource.title,
            actual:rule.relation==='PREDICTION_PUBLISHED_TRANSCRIPT_MISMATCH'
                ? readableEvidenceValue(context?.transcript?.accession&&`${context.transcript.accession}.${context.transcript.version}`)||reason
                :reason,
            outcome:'待核实',
        }]}];
    }
    return list(facts.mechanisms).flatMap(m=>{
        const model=terms[m.model];
        if(!['PROTEIN_MISSENSE','SPLICING'].includes(m.mechanism)||m.model==='NOT_REQUIRED')return [];
        const alpha=m.model==='ALPHAMISSENSE', calibrated=m.status==='CALIBRATED';
        const selected=code==='PP3'&&adopted&&details.selectedPredictionMechanism===m.mechanism;
        const required=selected||(code==='PP3'?list(facts.allowedPp3Mechanisms).includes(m.mechanism):list(facts.requiredBp4Mechanisms).includes(m.mechanism));
        const supports=calibrated&&m.direction===(code==='PP3'?'HARMFUL':'BENIGN');
        const band=readableEvidenceValue(m.thresholds||m.calibrationInterval);
        const score=readableEvidenceValue(m.values?.rawScore ?? m.rawScore ?? m.score);
        const otherScores=Object.entries(m.values?.scores||{}).flatMap(([key,value])=>
            /^(?:DS|DP)_(?:AG|AL|DG|DL)$/.test(key)&&present(value)?[`${key} ${readableEvidenceValue(value)}`]:[]).join(' · ');
        const actual=score||otherScores;
        const direction=m.direction==='BENIGN'&&m.mechanism==='SPLICING'?'支持无明显剪接影响':readableEvidenceValue(m.direction);
        const strength=m.appliedStrength&&m.appliedStrength!=='NONE'?displayStrength(m.appliedStrength):'';
        const tx=readableEvidenceValue(facts.transcriptMaterial?.transcript);
        const change=alpha?readableEvidenceValue(context?.transcript?.annotation?.hgvsp):'';
        const failure=cleanSentence(m.reason||m.observationQualificationReason);
        const expected=m.model==='REVEL'&&publishedThreshold?`REVEL ${code==='PP3'?'≥':'≤'} ${publishedThreshold}`:band;
        const ruleRows=[
            ...(alpha&& (tx||change)?[{label:'预测对象',requirement:'分值对应所选转录本上的氨基酸替换',actual:[tx,change].filter(Boolean).join(' · '),outcome:calibrated&&tx?'满足':'待核实'}]:[]),
            {label:'原始分值',requirement:expected||'核对本次使用的校准区间',
                actual:actual||(calibrated?'原始分值未保存':failure||'未取得可用分值'),outcome:!calibrated?'待核实':!actual||!expected?'明细不全':publishedThreshold&&!supports?'未满足':'满足'},
            {label:'预测结论',requirement:required?(code==='PP3'?'支持有害影响':'支持无有害影响'):'记录该机制的预测结果',
                actual:calibrated?[direction,strength?`校准强度：${strength}`:''].filter(Boolean).join(' · '):failure||readableEvidenceValue(m.status),
                outcome:calibrated&&required?(supports?'满足':'未满足'):m.status==='NOT_APPLICABLE'?'不适用':calibrated?'已记录':'待核实'},
        ];
        const sources=publishedSource&&m.model==='REVEL'?[publishedSource]:alpha?[
            {title:'AlphaMissense 模型原文',url:'https://deepmind.google/research/publications/21083/'},
            ...(facts.route==='GENERAL_LOCAL_ALPHAMISSENSE'?[{title:'PP3／BP4 分值校准原文',url:'https://doi.org/10.1016/j.gim.2025.101402'}]:[]),
        ]:[];
        return [{title:[terms[m.mechanism],model].filter(Boolean).join(' · '),
            role:selected?'已用于 PP3':supports?`支持 ${code} 方向`:'其他机制核查',ruleRows,sources}];
    });
}

function populationFacts(entry,details,code) {
    if(code==='PM2')return details.populationFacts||{};
    const raw=code==='BS1'?entry.savedPopulationResult||entry.raw:entry.raw;
    const facts=raw?.qualityDecision?.facts||raw?.facts||raw?.preparation?.facts||{};
    const scope=raw?.dataScope||raw?.sourceRouting||raw?.preparation?.policy?.statistic||{};
    const policy=raw?.preparation?.policy||raw?.policy||{};
    return {...facts,dataset:scope.dataset||policy.dataset,release:scope.release||policy.release,
        threshold:policy.threshold??policy.frequencyThreshold,
        prevalence:policy.parameters?.prevalence,penetrance:policy.parameters?.penetrance,
        exceptionStatus:raw?.exceptionCheck?.status};
}

function ba1ConditionDetails(view){
    const labels={TRUE:'满足',FALSE:'未满足',UNKNOWN:'尚不能确定'};
    const operators={GREATER_THAN:'>',GREATER_THAN_OR_EQUAL:'≥',LESS_THAN:'<',LESS_THAN_OR_EQUAL:'≤'};
    const f=view.frequencyRequirement||{},c=view.individualCountRequirement||{},readings=list(view.frequencyReadings);
    const reading=id=>{const values=readings.filter(r=>r.metricId===id);return values.length===1&&values[0].status==='AVAILABLE'?readableEvidenceValue(values[0].rawValue):'';};
    const group=reading('gnomad.joint.faf95max.ancestry'),qualified=view.countSourceQualification;
    const count=view.observedHomozygoteStatistic,bounds=qualified?.additionalNativeBounds;
    const lowerBound=count?.qualificationStatus==='QUALIFIED_LOWER_BOUND';
    const hemi=bounds?.hemizygoteLowerBound,upper=bounds?.personUpperBound,nativeV2=qualified?.version==='2.0.0';
    const rows=[{label:'适用条件',value:'频率条件或人数条件满足其一，并通过共用质量与适用性核查'},
        {label:'条件总结果',value:labels[view.truth]}];
    const sections=[{title:'频率条件',rows:uniqueRows([
        {label:'规则要求',value:present(f.value)?`FAF95 ${operators[f.comparator]||''} ${f.value}`:''},
        {label:'FAF95 实际值',value:reading(f.metric)||'未取得合格读数'},
        {label:'FAF95 所用人群',value:group},
        {label:'祖源检测量要求',value:present(f.minimumAn)?`AN ${operators[f.minimumAnComparator]||''} ${f.minimumAn}`:''},
        {label:'同祖源 AN',value:group?reading(`gnomad.joint.ancestry.${group}.an`):''},
        {label:'频率条件结果',value:labels[view.frequencyTruth]},
    ])},{title:'人数条件',rows:uniqueRows([
        {label:'规则要求',value:present(c.value)?`纯合或半合子人数 ${operators[c.operator]||''} ${c.value}（可按规范合计）`:''},
        {label:lowerBound?'已核实纯合人数下限':'纯合人数原始读数',value:present(count?.rawValue)?`${lowerBound?'≥ ':''}${count.rawValue} 人${lowerBound?'':'（资格尚未核实）'}`:'未取得合格人数资料'},
        {label:nativeV2?'已核实半合子人数下限':'半合子人数',value:hemi?.status==='QUALIFIED_LOWER_BOUND'?`≥ ${hemi.value} 人（XY，非 PAR）`:
            nativeV2?'未取得合格下限':list(qualified?.missing).includes('HEMIZYGOTE_INDIVIDUALS')?'尚未核实':''},
        {label:'人数上界',value:nativeV2?upper?.status==='QUALIFIED_UPPER_BOUND'?`≤ ${upper.value} 人`:'未取得合格上界':''},
        {label:'计数范围',value:view.countScopeCompleteness==='UNVERIFIED_OR_PARTIAL'?
            nativeV2?'按已核下界和上界核查；完整精确人数尚未建立':'部分范围；当前计数为下限':''},
        {label:'人数条件结果',value:labels[view.countTruth]},
    ])}];
    const missingLabels={HEMIZYGOTE_INDIVIDUALS:'半合子人数尚未核实',COMPLETE_INDIVIDUAL_COUNT_COVERAGE:'完整计数范围尚未核实',
        COMPLETE_EXACT_HOMO_HEMI_COUNT_COVERAGE:'完整精确纯合及半合子人数尚未核实',
        SOURCE_QUALIFIED_XY_NONPAR_HEMIZYGOTE_STATISTICS:'缺少合格的 XY 非 PAR 半合子统计',
        COMPLETE_ALL_RAW_CALLSET_PERSON_UPPER_BOUND:'缺少覆盖全体原始调用的人数上界',
        DIPLOID_HOMOZYGOTE_INDIVIDUAL_STATISTIC:'缺少纯合人数原生统计',
        SOURCE_DEFINED_HOMOZYGOTE_STATISTIC:'缺少来源定义明确的纯合人数',HOMOZYGOTE_INDIVIDUALS:'纯合人数尚未核实',
        EXACT_TARGET_ALT_RELEASE_PROFILE_AND_RAW_REPLAY:'精确等位基因、版本与原始记录尚未核实'};
    let missing=list(qualified?.missing).map(v=>missingLabels[v]).filter(Boolean);
    if(view.status==='NOT_EVALUATED'&&view.frequencyTruth==='UNKNOWN'&&readings.some(r=>r.status!=='AVAILABLE'))missing.push('频率或对应人群检测量缺少合格读数');
    if(view.status==='NOT_EVALUATED'&&!missing.length)missing=['共用质量或适用性条件尚未通过，当前不能采用频率或人数结果'];
    return {rows,sections,missing:[...new Set(missing)].join('；'),sources:view.sources};
}

function bs1StrataDetails(raw){
    const policy=raw?.preparation?.policy,quality=raw?.qualityDecision,selection=quality?.frequencySelection;
    if(!policy||!selection||!['bs1-joint-sex-stratified-frequency-v1','bs1-joint-sex-stratified-frequency-v2'].includes(selection.version))return {sections:[],sources:[]};
    const stat=policy.statistic||{},op={GT:'>',GTE:'≥',LT:'<',LTE:'≤'};
    const measure=(m,key)=>m?.[key]?.state==='PRESENT'?readableEvidenceValue(m[key].rawValue):'未取得合格读数';
    const requirements=[present(stat.eligibleAn?.decimal)?`AN ${op[stat.eligibleAn.operator]||''} ${stat.eligibleAn.decimal}`:'',
        present(stat.eligibleAc?.decimal)?`AC ${op[stat.eligibleAc.operator]||''} ${stat.eligibleAc.decimal}`:''].filter(Boolean).join('；');
    const sections=list(selection.populations).map(row=>({title:`人群 ${row.ancestry} · ${row.karyotype}`,rows:uniqueRows([
        {label:'分层纳入条件',value:requirements},
        {label:'目标变异等位基因数 AC',value:measure(row.sourceStratum?.measurements,'AC')},
        {label:'检测等位基因数 AN',value:measure(row.sourceStratum?.measurements,'AN')},
        {label:stat.sourceTerm==='AF'?'频率 AF':'过滤频率 FAF95',value:measure(row.sourceStratum?.measurements,stat.sourceTerm==='AF'?'AF':'faf95')},
        {label:'本次分层结果',value:row.status==='ELIGIBLE'?'符合纳入条件':row.reasonCode==='BS1_SEX_AC_INSUFFICIENT'?'目标变异等位基因数未达到要求，已排除':
            row.reasonCode==='BS1_SEX_AN_INSUFFICIENT'?'检测等位基因数未达到要求，已排除':'尚不能确定'},
        {label:'最大值选择',value:row.status==='ELIGIBLE'&&row.ancestry===selection.selectedAncestry&&row.karyotype===selection.selectedKaryotype?'本次采用的最高合格频率':''},
    ])}));
    for(const tier of list(policy.tiers).filter(t=>t.moi===raw.context?.moi)){
        const rows=list(tier.conditions).map(c=>({label:`${c.fact} 条件`,value:present(c.decimal)?`${c.fact} ${op[c.operator]||''} ${c.decimal}；实际 ${readableEvidenceValue(quality.facts?.[c.fact])||'未取得'}`:''}));
        if(rows.some(r=>r.value))sections.push({title:`疾病频率规则 · ${displayStrength(tier.strength)}`,rows:uniqueRows(rows)});
    }
    return {sections,sources:list(policy.sources)};
}

const legacyRnaFindingFailed=i=>!Array.isArray(i?.reviewedSourceIds)
    &&/^PVS1_RNA_INITIATION_(?:FUNCTION_SOURCE_LOCATION_INVALID|FUNCTION_REVIEW_SCOPE_UNRESOLVED|TRANSCRIPT_SET_MISMATCH)$/.test(i?.reasonCode||'');
const legacyRnaCitationFailed=i=>legacyRnaFindingFailed(i)||!Array.isArray(i?.reviewedSourceIds)
    &&i?.reasonCode==='PVS1_RNA_INITIATION_UPSTREAM_SOURCE_LOCATION_INVALID';
function legacyRnaUnusedSources(i) {
    if(Array.isArray(i?.reviewedSourceIds))return new Set();
    const usedRole={PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED:'RESTART_REVIEW',
        PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED:'TRANSCRIPT_FUNCTION_REVIEW'}[i?.reasonCode];
    return new Set(usedRole?list(i.sourceChecks).filter(row=>row?.role&&row.role!=='PRIMARY_EVIDENCE'&&row.role!==usedRole).map(row=>row.sourceId):[]);
}
function fullRnaInitiationSections(view,showQuotes){
    const i=view.details?.initiation;
    if(i?.schemaVersion!=='pvs1-full-rna-initiation-assessment-v1'||i.methodVersion!=='1.0.0')return [];
    const bool=v=>v===true?'是':v===false?'否':'',num=v=>Number.isSafeInteger(v)?String(v):'';
    const start=(v,sequenceChecked=false)=>({OBSERVED:sequenceChecked?'原文报告的起始，已核对序列':'原文报告的起始',
        SOURCE_REVIEWED_PUTATIVE:'资料审核的候选起始，未证明实际翻译',UNKNOWN:'尚未确定',NOT_ASSESSED:'尚未检查'})[v]||'';
    const functionFinding=v=>({FUNCTION_PRESERVED_IN_ASSESSED_SCOPE:'检查范围内功能保留',FUNCTION_LOSS_SUPPORTED:'支持功能丧失',UNKNOWN:'功能尚未确定'})[v]||'';
    const sourceAdmissionFailed=fullRnaSourceAdmissionFailed(i);
    const selected=sourceAdmissionFailed?{}:i.sourceSpecifiedStart||{},declared=fullRnaDeclaredStart(i)||{},scope=i.transcriptReviewScope||{},upstream=i.upstreamReviewScope||{};
    const sections=[{title:'起始与替代转录本核查',rows:uniqueRows([
        {label:'本次分支结论',value:view.completedExclusion?'已核查，不适用 PVS1':view.claim.originalStatus==='MET'&&i.status==='MET'?`满足 · ${displayStrength(view.claim.originalStrength)}`:'尚未完成判定'},
        {label:'资料记载的起始',value:start(declared.startStatus)},
        {label:'资料记载的起始位置（改变后 RNA）',value:num(declared.alteredStartRna1)},
        {label:'资料记载的功能结果',value:functionFinding(declared.functionFinding)},
        {label:'本次核对的起始',value:start(selected.startStatus,true)},
        {label:'本次核对的起始位置（改变后 RNA）',value:num(selected.alteredStartRna1)},
        {label:'参考 CDS 中的再起始位置',value:num(i.referenceRestartCdsPosition)},
        {label:'本次转录本核查完整',value:bool(i.transcriptSetComplete)},
        {label:'尚未解决的转录本记录数',value:num(scope.unresolvedCount)},
        {label:'上游参考核查完整',value:bool(i.upstreamScopeComplete)},
        {label:'所需上游核查区间（参考 CDS）',value:Number.isSafeInteger(upstream.requiredCdsStart)&&Number.isSafeInteger(upstream.requiredCdsEnd)?`${upstream.requiredCdsStart}–${upstream.requiredCdsEnd}`:''},
        {label:'本次范围内的合格独立参考数',value:num(i.qualifyingReferenceCount)},
        {label:'更早的改变后同框 ATG 位置（RNA）',value:num(i.earlierAlteredInframeMetRnaPosition)},
    ])}];
    const findings=sourceAdmissionFailed||legacyRnaFindingFailed(i)?[]:list(i.transcriptFindings);
    const reviewedFindings=findings.filter(row=>row?.qualification==='REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE');
    const isSelected=row=>row.transcript===view.selectedTranscript;
    for(const row of reviewedFindings.filter(row=>!isSelected(row)).slice(0,20)){
        const rows=uniqueRows([
            {label:'具有相关功能',value:bool(row.functionallyRelevant)},
            {label:'不受当前 RNA 事件影响',value:bool(row.targetUnaffected)},
            {label:'功能与事件范围审核',value:'已完成对应范围审核'},
        ]);
        const tx=readableEvidenceValue(row.transcript);
        if(tx&&rows.length)sections.push({title:`替代转录本 · ${tx}`,rows});
    }
    const roles={PRIMARY_EVIDENCE:'原文资料',RESTART_REVIEW:'起始资料',UPSTREAM_REVIEW:'上游参考资料',TRANSCRIPT_FUNCTION_REVIEW:'转录本功能资料'};
    const checks=list(i.sourceChecks).slice(0,16).filter(row=>roles[row.role]).map(row=>({label:roles[row.role],value:row.status==='RAW_SHA_MATCH'?'已核对原文一致性':'原文一致性尚未通过核查'}));
    if(checks.length)sections.push({title:'起始资料来源核查',rows:uniqueRows(checks)});
    // Old projections collected excerpts across documents without retaining the owning review.
    const oldSelectedFinding=!Array.isArray(i.reviewedSourceIds)&&reviewedFindings.some(isSelected);
    const quotes=!showQuotes||sourceAdmissionFailed||legacyRnaCitationFailed(i)||legacyRnaUnusedSources(i).size||oldSelectedFinding?[]:list(i.sourceQuotes).slice(0,8)
        .filter(row=>typeof row?.text==='string'&&row.text.trim()&&!['{','['].includes(row.text.trimStart()[0]));
    if(quotes.length)sections.push({title:'起始判定原文',rows:quotes.map((row,index)=>({label:`原文摘录 ${index+1}`,value:row.text.slice(0,600)}))});
    return sections.filter(section=>section.rows.length);
}

function fullRnaConflictBasis(view){
    const i=view.details?.initiation;
    const fallback={value:'发现功能保留的 RNA 产物或功能性替代转录本。',sources:[]};
    if(!fullRnaRescueReviewConflict(view)||i?.sourceDocumentsQualified!==true||i.reasonCode!==view.claim.normalizedReasonCode)return fallback;
    const start=i.sourceSpecifiedStart;
    const transcript=list(i.transcriptFindings).find(row=>row?.qualification==='REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE'
        &&row.functionallyRelevant===true&&row.targetUnaffected===true&&/^(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*$/.test(row.transcript||'')
        &&row.transcript!==view.selectedTranscript);
    const value=start?.startStatus==='OBSERVED'&&Number.isSafeInteger(start.alteredStartRna1)&&start.alteredStartRna1>0
        ?`功能实验支持改变后 RNA 第 ${start.alteredStartRna1} 位起始的产物保留疾病相关功能。`
        :transcript?`转录本 ${transcript.transcript} 保留疾病相关功能，且不受本次 RNA 事件影响。`:fallback.value;
    const sources=scalarSources(list(i.sourceQuotes).slice(0,8).flatMap(row=>{
        if(typeof row?.text!=='string'||!row.text.trim()||['{','['].includes(row.text.trimStart()[0])||!list(i.reviewedSourceIds).includes(row.sourceId))return [];
        const check=list(i.sourceChecks).find(check=>check?.sourceId===row.sourceId&&check.role==='PRIMARY_EVIDENCE'
            &&check.status==='RAW_SHA_MATCH'&&check.actualRawSha256===row.sourceRawSha256&&/^[a-f0-9]{64}$/.test(row.sourceRawSha256||''));
        if(!check)return [];
        const source=list(view.details.policySources).find(source=>source?.sourceId===row.sourceId&&source.sha256===row.sourceRawSha256);
        return [{title:'功能救援原文',quote:row.text.slice(0,600),url:source?.url}];
    }));
    return {value,sources};
}

export function fullRnaDetails(view){
    const p=view.preparation,s=p.sequenceEffect||{},t=p.topology||{},d=view.details||{},n=d.nmd||{},rule=d.rule||{};
    const conflictBasis=fullRnaConflictBasis(view);
    const quantity=(value,unit='')=>Number.isSafeInteger(value)?`${value}${unit}`:'尚未确定';
    const state=value=>({ASSESSED:'已核查',NOT_DETECTED_IN_ASSESSED_SCOPE:'在本次检查范围内未检出',DETECTED_IN_ASSESSED_SCOPE:'在本次检查范围内检出',
        SINGLE_SUPPORTED_OUTCOME:'已支持单一后果',MULTIPLE_OUTCOMES:'已观察到多种后果',EXCLUDED_IN_REVIEWED_SCOPE:'在已审核范围内排除',REVIEWED_FOR_PVS1_SPLICE:'已完成本次 PVS1 审核',
        MET:'满足',NOT_MET:'未满足',NOT_EVALUATED:'尚未评价',NOT_APPLICABLE:'不适用',READY:'已就绪',COMPLETE:'完整',UNKNOWN:'尚未确定',
        NON_REPEAT_SUPPORTED:'非重复区域条件满足',REPEAT_OVERLAP:'与重复区域重叠',QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION:'重复及无已知功能条件满足',
        EXCLUDED:'不符合区域条件',REPEAT:'重复区域',NON_REPEAT:'非重复区域',NO_KNOWN_FUNCTION:'无已知功能',KNOWN_FUNCTION:'存在已知功能',
        REVIEWED_APPLICABLE:'已审核适用',NOT_GRANTED_BY_OPERATOR:'需由本次规则单独核查'})[value]||'尚未确定';
    const coordinate=(x,start,end)=>Number.isSafeInteger(x?.[start])?`${x[start]}${Number.isSafeInteger(x[end])?`–${x[end]}`:''}`:'尚未确定';
    const stop=s.nativeStop||{},start=s.nativeStart||{},first=s.firstStop||{};
    const stopState=stop.retainedContiguously===false?'原终止密码子未完整保留':stop.retainedContiguously===true?
        stop.inReadingFrameFromRetainedNativeStart===false?'原终止密码子碱基保留，但不在当前阅读框':stop.inReadingFrameFromRetainedNativeStart===true?'原终止密码子保留且位于当前阅读框':'原终止密码子碱基保留，阅读框尚未确定':'尚未确定';
    const rows=[
        {label:'原始 RNA 长度（nt）',value:quantity(s.referenceRnaLength)},{label:'改变后 RNA 长度（nt）',value:quantity(s.alteredRnaLength)},
        {label:'参考 CDS 长度（nt）',value:quantity(s.referenceCdsLength)},{label:'编辑后参考 CDS 区间长度（nt）',value:quantity(s.editedReferenceCdsIntervalLength)},
        {label:'参考蛋白长度（aa）',value:quantity(s.referenceProteinLength)},{label:'改变后蛋白长度（aa）',value:quantity(s.alteredProteinLength)},
        {label:'蛋白长度净变化（aa）',value:quantity(s.netProteinLengthChange)},
        {label:'原始起始密码子',value:start.retainedContiguously===true?'完整保留':start.retainedContiguously===false?d.initiation?'未完整保留':'未完整保留，翻译起始尚未确定':'尚未确定'},
        {label:'原始终止密码子',value:stopState},
        {label:'原终止密码子位置（参考 RNA）',value:coordinate(stop,'referenceStartRna1','referenceEndRna1')},
        {label:'首个终止密码子位置（改变后 RNA）',value:coordinate(first,'alteredStartRna1','alteredEndRna1')},
        {label:'首个终止密码子来源',value:({REFERENCE_BASES:'参考序列碱基',INSERTED_OR_REPLACEMENT_BASES:'插入或替换的碱基',MIXED:'参考与插入／替换碱基共同组成'})[first.baseOrigin]||'尚未确定'},
        {label:'连接点拓扑',value:state(t.topologyCompleteness)},
        {label:'最后连接点位置（改变后 RNA，零基边界）',value:t.topologyCompleteness==='COMPLETE'?quantity(t.lastJunctionRnaPosition0):'尚未确定'},
        {label:view.completedExclusion?'本次判定':'原始判定',value:view.completedExclusion?'已核查，不适用 PVS1':`${state(view.claim.originalStatus)}${view.claim.originalStrength!=='NONE'?` · ${displayStrength(view.claim.originalStrength)}`:''}`},
        ...(view.application==='NOT_COUNTED'?[{label:'本次采用',value:`相同后果已计入 ${view.assignedTo}，本项不重复计分`}]:[]),
        ...(view.application==='CONFLICT'?[{label:'本次采用',value:'存在相反方向证据冲突，暂不采用'}]:[]),
    ];
    const sections=object(p.assayConditions)?[{title:'RNA 资料核查',rows:['alleleAttribution','assayQuality','tissueRelevance','residualNormalTranscript','outcomeMultiplicity'].map((k,i)=>({label:['等位归因','试验质量','组织相关性','残余正常转录本','支持的 RNA 后果'][i],value:state(p.assayConditions?.[k])}))}]:[];
    const sources=p.sourceRecord?.url?[{title:'RNA 资料原文',url:p.sourceRecord.url}]:[];
    if(view.code==='PVS1'&&view.ready){
        const parent=rule.parentReview||{};
        sections.push({title:'PVS1 判定条件',rows:uniqueRows([
            {label:'本次判定分支',value:view.completedExclusion&&view.claim.normalizedReasonCode==='PVS1_FULL_RNA_PROTEIN_UNCHANGED'?'蛋白序列未改变':
                ({INITIATION_LOSS:'起始密码子丢失',IN_FRAME:'在框蛋白后果',NMD:'NMD',NMD_ESCAPE:'NMD 逃逸'})[d.assessmentKind]||'尚未确定'},
            {label:'规则强度上限',value:rule.appliedStrength?displayStrength(rule.appliedStrength):''},
            {label:'剪接范围强度上限',value:rule.scope?.maximumStrength?displayStrength(rule.scope.maximumStrength):''},
            {label:'完整后果审核强度上限',value:parent.maximumStrength?displayStrength(parent.maximumStrength):''},
            {label:'功能救援核查',value:state(parent.functionalRescue)},
        ])});
        const positionLabels={REFERENCE_EVENT_SPAN:'RNA 受影响位置（参考 CDS）',
            REFERENCE_PTC_SPAN:'提前终止密码子位置（参考 CDS）',ALTERED_PTC_SPAN:'提前终止密码子位置（改变后 CDS）'};
        const positionResults={PASS:'通过',OUTSIDE_SUPPORTED_RANGE:'超出范围',UNRESOLVED:'位置无法核实'};
        const positionRows=list(rule.positionChecks).flatMap(check=>{
            const label=positionLabels[check?.coordinate],result=positionResults[check?.status];
            if(typeof label!=='string'||typeof result!=='string')return [];
            const bounds=[Number.isSafeInteger(check.minimumInclusive)?`≥ ${check.minimumInclusive}`:'',
                Number.isSafeInteger(check.maximumExclusive)?`< ${check.maximumExclusive}`:''].filter(Boolean).join(' 且 ');
            if(!bounds)return [];
            const first=check.firstCheckedPosition,last=check.lastCheckedPosition;
            const actual=Number.isSafeInteger(first)&&Number.isSafeInteger(last)?first===last?String(first):`${first}–${last}`:'尚未核实';
            return [{label,value:`规则 ${bounds} · 实际 ${actual} · ${result}`}];
        });
        if(positionRows.length)sections.push({title:'规则位置核查',rows:positionRows});
        sections.push(...fullRnaInitiationSections(view,!fullRnaRescueReviewConflict(view)));
        if(['NMD','NMD_ESCAPE'].includes(d.assessmentKind))sections.push({title:'NMD 条件与实际结果',rows:uniqueRows([
            {label:'NMD 判定',value:n.nmdPrediction==='PREDICTED_NMD'?'预测发生 NMD':d.assessmentKind==='NMD_ESCAPE'?'按 NMD 逃逸分支核查':'尚未确定'},
            {label:'实际 PTC 位置（改变后编码区）',value:coordinate(n,'ptcStart','ptcEnd')},
            {label:'PTC 至最后连接点距离（nt）',value:quantity(n.distanceToLastJunction)},
            {label:'本次 NMD 距离要求（nt）',value:Number.isSafeInteger(n.rule?.minimumDistanceToLastJunction)?`≥ ${n.rule.minimumDistanceToLastJunction}`:''},
            {label:'本次最小 CDS 位置',value:quantity(n.rule?.minimumCdsPosition)},
            {label:'长外显子条件',value:n.rule?.longExonScope==='EXCLUDE_ABOVE_MAXIMUM'?`长度不超过 ${quantity(n.rule.maximumExonLength)} nt`:
                n.rule?.longExonScope==='REVIEWED_WITHOUT_LENGTH_LIMIT'?'已审核，不设该项长度上限':''},
        ])});
        for(const [kind,branch]of [['IN_FRAME',d.inFrame],['NMD_ESCAPE',d.escape]])if(d.assessmentKind===kind&&object(branch)){
            const selected=branch.selectedRule||{};
            sections.push({title:kind==='IN_FRAME'?'在框后果判定依据':'NMD 逃逸判定依据',rows:uniqueRows([
                {label:'净减少氨基酸数（aa）',value:Number.isSafeInteger(branch.deletedProteinResidues)?quantity(branch.deletedProteinResidues):''},
                {label:'参考蛋白长度（aa）',value:Number.isSafeInteger(branch.referenceProteinLength)?quantity(branch.referenceProteinLength):''},
                {label:'改变后蛋白长度（aa）',value:Number.isSafeInteger(branch.alteredProteinLength)?quantity(branch.alteredProteinLength):''},
                {label:'实际采用强度',value:branch.strength?displayStrength(branch.strength):selected.strength?displayStrength(selected.strength):''},
                {label:'命中的规则',value:selected.predicate==='CRITICAL_REGION'?'关键功能区域':selected.predicate==='DELETED_PROTEIN_FRACTION'?'删除蛋白比例':''},
                {label:'规则关键区间（参考蛋白）',value:Number.isSafeInteger(selected.start)?`${selected.start}–${selected.end} aa`:''},
                {label:'所需完整删除残基数',value:Number.isSafeInteger(selected.minimumAffectedResidues)?quantity(selected.minimumAffectedResidues):''},
                {label:'规则比例条件',value:Number.isSafeInteger(selected.numerator)&&Number.isSafeInteger(selected.denominator)?`${selected.comparison==='GT'?'>':selected.comparison==='LT'?'<':''} ${selected.numerator}/${selected.denominator}`:''},
                ...list(branch.ruleChecks).filter(check=>check.status==='MATCH'||check.matched===true).flatMap(check=>[
                    {label:'实际删除比例',value:Number.isSafeInteger(check.actualNumerator)&&Number.isSafeInteger(check.actualDenominator)?`${check.actualNumerator}/${check.actualDenominator}`:''},
                    {label:'关键区域实际完整删除残基数',value:Number.isSafeInteger(check.affectedResiduesInRegion)?quantity(check.affectedResiduesInRegion):Number.isSafeInteger(check.affectedResidues)?quantity(check.affectedResidues):''},
                ]),
            ])});
        }
    }
    for(const [i,r] of list(d.regionChecks).entries()){
        const range=r.referenceProteinRange;
        sections.push({title:`区域条件${Number.isSafeInteger(range?.start)?` · 参考蛋白 ${range.start}–${range.end} aa`:` ${i+1}`}`,rows:uniqueRows([
            {label:'区域核查结果',value:state(r.regionState)},{label:'重复属性',value:r.repeatStatus?state(r.repeatStatus):''},
            {label:'功能属性',value:r.functionalStatus?state(r.functionalStatus):r.functionalConclusion?state(r.functionalConclusion):''},
            {label:'区域资格审核',value:r.coverage?.review?.status?state(r.coverage.review.status):r.coverage?.status?state(r.coverage.status):''},
            ...list(r.reviewedIntervals).map(interval=>({label:`已审核参考区间 ${interval.start}–${interval.end} aa`,value:[interval.repeatStatus?state(interval.repeatStatus):'',interval.functionalStatus?state(interval.functionalStatus):''].filter(Boolean).join('；')})),
        ])});
        for(const interval of list(r.reviewedIntervals))for(const url of list(interval.sourceLocators))sources.push({title:'区域判定出处',url});
    }
    if(d.source?.url)sources.push({title:'适用规则出处',url:d.source.url});
    const initiation=d.initiation;
    const reviewed=Array.isArray(initiation?.reviewedSourceIds)?new Set(initiation.reviewedSourceIds):null;
    const failed=fullRnaSourceAdmissionFailed(initiation)||legacyRnaCitationFailed(initiation);
    const unusedLegacySources=legacyRnaUnusedSources(initiation);
    const initiationIds=new Set([...list(initiation?.sourceDocumentSet?.requiredSourceIds),
        ...list(initiation?.sourceChecks).map(row=>row?.sourceId)].filter(Boolean));
    const conflictUrls=new Set(conflictBasis.sources.map(source=>source.url).filter(Boolean));
    for(const source of list(d.policySources)){
        if(!source?.url||failed&&!initiationIds.size||unusedLegacySources.has(source.sourceId))continue;
        if(initiationIds.has(source.sourceId)&&(failed||reviewed&&!reviewed.has(source.sourceId)))continue;
        if(conflictUrls.has(source.url))continue;
        sources.push({title:'判定规范出处',url:source.url});
    }
    return {rows:uniqueRows(object(p.sequenceEffect)?rows:rows.filter(r=>['原始判定','本次采用'].includes(r.label))),sections,sources,conflictBasis};
}

function bp1TruncatingDetails(facts){
    const q=facts.truncatingConditionCheck;
    if(!object(q))return {rows:[],sources:[]};
    const unsupported={
        BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW:'现有模板未形成合格的原生科学审核结果。',
        BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED:'软件尚不能核定原生资料所覆盖的基因及疾病范围。',
        BP1_NO_LOF_ATTACHMENT_INTERPRETATION_UNSUPPORTED:'软件尚不能解释该规范附件的相关性资料。',
    }[q.reasonCode];
    const relation=q.lofDiseaseCorrelation,qualified=q.noEstablishedLofQualification,structured=q.structuredLof;
    const rows=uniqueRows([
        {label:'序列后果核查',value:q.sequenceConsequence?.status==='TRUNCATION_CANDIDATE'?'预测编码截短':''},
        {label:'功能缺失已证实',value:q.sequenceConsequence?.functionalLossEstablishedBySequence===false?'尚未由该序列结果证实':''},
        {label:'基因 LoF 疾病关联',value:structured||relation?.status==='CONTRAINDICATION_ESTABLISHED'?'已存在明确关联':relation?.status==='UNKNOWN'?'尚未核定':''},
        {label:'已建立关联的疾病',value:readableEvidenceValue(structured?.diseaseLabel||relation?.relation?.diseaseLabel)},
        {label:'原文关联的基因',value:readableEvidenceValue(relation?.sourceMention?.namedGeneSymbol)},
        {label:'本次背景的基因',value:readableEvidenceValue(relation?.sourceMention?.selectedGeneSymbol)},
        {label:'原文主体对应',value:relation?.sourceMention?.subjectMatchesSelectedScope===true?'与本次基因相同':
            relation?.sourceMention?.subjectMatchesSelectedScope===false?'属于其他基因，不能用于本次基因的关联结论':''},
        {label:'规范原件读取',value:qualified?.sourceTransport?.status==='CHECKED'?'已完成':''},
        {label:'同基因其他疾病核查',value:qualified?.reviewCoverage?.sameGeneOtherDiseasesChecked===false?'尚未完成':
            qualified?.reviewCoverage?.sameGeneOtherDiseasesChecked===true?'已完成':''},
        {label:'无 LoF 关联资格',value:qualified?.absenceQualificationGranted===false?'尚未建立':''},
        {label:'原生资料解释',value:unsupported?'软件暂未支持':''},
    ]);
    const sources=relation?.sourceBinding?.sourceUrl?[{title:'专家规范原文',url:relation.sourceBinding.sourceUrl}]:[];
    for(const source of list(structured?.mechanismSources)){
        if(source?.url)sources.push({title:'功能缺失关联出处',url:source.url});
    }
    for(const review of list(qualified?.sourceReviews).slice(0,16)){
        const source=review?.sourceBinding;
        if(source?.sourceUrl) sources.push({title:`已读取资料${source.specificationKey?` · ${source.specificationKey}`:''}`,url:source.sourceUrl});
    }
    return {rows,sources,
        unsupported,requirement:'当前变异须属于预测截短，并有合格资料支持该基因尚无已建立的功能缺失（LoF）疾病关联。',
        known:'本次规范、转录本与预测编码截短已有记录。',conclusion:unsupported?`${unsupported} 本条证据保留未评价。`:'',
        suggestion:unsupported?'接通对应原生资料解释能力后，重新核查本条证据。':''};
}

function pvs1UnresolvedDetails(facts, criterion, context) {
    const a=facts.unresolvedAssessment, transcript=context?.transcript;
    if(a?.schemaVersion!=='pvs1-unresolved-assessment-v1'||criterion?.status!=='NOT_EVALUATED'||
        criterion.appliedStrength!=='NONE'||a.reasonCode!==criterion.reasonCode||
        a.transcript!==`${transcript?.accession}.${transcript?.version}`)return null;
    const has=key=>list(a.missingFacts).includes(key);
    const check=(label,requirement,actual,outcome='待核实')=>({label,requirement,actual,outcome});
    const exonLabel=ordinal=>`第 ${ordinal} 外显子`;
    const signed=n=>n<0?`−${-n}`:`+${n}`;
    if(a.kind==='SPLICE'&&['PVS1_SPLICE_OUTCOME_UNRESOLVED','PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED',
        'PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED'].includes(a.reasonCode)){
        const p=a.candidateProduct;
        const event={EXON_SKIPPING:'跳过',EXON_SHORTENING:'缩短'}[p?.eventType];
        const frame={ALTERED:'改变阅读框',PRESERVED:'保持阅读框'}[p?.readingFrame];
        const evidence={PREDICTED_CANONICAL:'预测',OBSERVED_RNA:'RNA 实验记录'}[a.evidenceKind];
        if(!event||!frame||!evidence||![p.exonOrdinal,p.deleteStartCds,p.deleteEndCds,p.deletedCodingBases].every(n=>Number.isInteger(n)&&n>0)
            ||p.deleteEndCds-p.deleteStartCds+1!==p.deletedCodingBases)return null;
        const product=`${evidence}${exonLabel(p.exonOrdinal)}${event}，编码区缺失 ${p.deletedCodingBases} nt（c.${p.deleteStartCds}–${p.deleteEndCds}），${frame}。`;
        const candidates=list(a.nearbyInFrameSites).filter(site=>site?.status==='SEQUENCE_CANDIDATE'
            &&['DONOR','ACCEPTOR'].includes(site.siteType)&&Number.isInteger(site.offsetNt)&&site.offsetNt!==0&&Math.abs(site.offsetNt)<=20);
        const nearby=['DONOR','ACCEPTOR'].flatMap(type=>{
            const sites=candidates.filter(site=>site.siteType===type);
            const name=type==='DONOR'?'供体':'受体';
            return sites.length?[`相对原${name} ${sites.map(site=>`${signed(site.offsetNt)} nt`).join('、')} 为可能保留阅读框的候选${name}。`]:[];
        }).join('');
        const rows=[check('候选剪接产物','确定该等位基因的异常剪接产物及阅读框',product)];
        if(nearby||has('SPLICE_PRODUCT_DISTINCTION')||has('SPLICE_FUNCTIONAL_RESCUE'))
            rows.push(check('替代剪接与功能补救','区分候选产物，核实替代位点是否使用及其后果',
                nearby+(has('SPLICE_PRODUCT_DISTINCTION')?'缺少区分候选产物及其使用情况的依据。':'缺少核定功能补救的依据。')));
        if(has('RESIDUAL_NORMAL_TRANSCRIPT'))rows.push(check('残余正常转录本','核实该等位基因的残余正常转录本及其影响',
            a.residualNormalTranscript==='NOT_MEASURED'?'该等位基因的残余正常转录本尚未测量。':'该等位基因的残余正常转录本情况或其影响尚待核实。'));
        return {requirement:'核对所选转录本的剪接产物及可能保留功能的转录本。',rows,sources:list(a.sources)};
    }
    if(a.kind==='ESCAPE'&&a.reasonCode==='PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED'&&a.scopeStatus==='NO_ADOPTED_ESCAPE_SCOPE'){
        const exons=list(a.affectedExonOrdinals).filter(n=>Number.isInteger(n)&&n>0);
        const affected=exons.map(exonLabel).join('、');
        if(!affected||![a.firstAlteredResidue,a.referenceProteinLength,a.alteredProteinLength].every(n=>Number.isInteger(n)&&n>0))return null;
        const protein=`${affected}，首个改变残基 ${a.firstAlteredResidue}，预测蛋白长度 ${a.referenceProteinLength} → ${a.alteredProteinLength} aa。`;
        const rows=[check('NMD 逃逸后的蛋白影响','核对预计逃逸的提前终止及受影响区域',protein,'已核实'),
            check('现行规则范围','在该背景与转录本上采用适用的 NMD 逃逸条款',
                a.candidateReviewStatus==='UNREVIEWED'?'现行政策未采用该逃逸范围，已有候选范围待核定。':'现行政策未采用该逃逸范围。')];
        if(has('EXON_POPULATION_LOF_TOLERANCE')){
            const reviewed=list(a.exonAssessments).filter(e=>exons.includes(e?.ordinal)&&e.biologicalRelevance==='PRESENT'&&e.populationLof==='UNKNOWN');
            for(const e of reviewed)rows.push(check(`${exonLabel(e.ordinal)}人群 LoF 耐受`,
                '核实同一转录本中该外显子的人群功能缺失耐受情况',
                `${exonLabel(e.ordinal)}已核为相关外显子，缺少该外显子的人群 LoF 耐受资料。`));
        }
        return {requirement:'结合预计逃逸的截短后果与当前可采用的逃逸条件。',rows,sources:list(a.sources)};
    }
    return null;
}

function pvs1Details(facts) {
    const rows=fieldRows({fields:[['所选转录本','selectedTranscript'],['变异机制','variantMechanism'],['转录本拓扑','transcriptTopology.kind'],
        ['RNA 后果依据','spliceAssessment.evidenceKind'],['RNA 阅读框','spliceAssessment.rnaReadingFrame'],
        ['框内净减少氨基酸数','inFrameAssessment.netCodonDeletion'],['首个受影响残基','inFrameAssessment.firstTouchedReferenceResidue'],
        ['最后受影响残基','inFrameAssessment.lastTouchedReferenceResidue'],['NMD 逃逸条件核查','escapeAssessment.status']]},facts);
    const sections=[],sources=[];
    for(const assessment of [facts.startLossAssessment,facts.expertAssessment,facts.automaticAssessment].filter(object)){
        const s=assessment.sequence||assessment;
        const startRows=fieldRows({fields:[['所选转录本','selectedTranscript'],['起始密码子 CDS 位置','cdsPosition'],['参考起始密码子','referenceCodon'],
            ['改变后起始密码子','alternateCodon'],['候选下游起始 CDS 位置','candidateRestartCdsPosition'],['候选下游起始蛋白位置','candidateRestartProteinPosition'],
            ['替代转录本核查','transcriptReviewStatus'],['替代转录本核查已完成','transcriptReviewComplete'],['上游参考核查','upstreamReferenceReviewStatus'],
            ['上游限定范围核查已完成','upstreamSearchComplete'],['合格独立致病参考数','qualifyingReferenceCount']]},s);
        startRows.push(...fieldRows({fields:[['已核实功能性替代','sourceQualifiedTranscriptAssessment.functionalAlternativeFound']]},assessment));
        if(startRows.length)sections.push({title:'起始密码子丢失核查',rows:startRows});
        sources.push(...list(assessment.sources));
        if(typeof assessment.ruleSource==='string')sources.push({url:assessment.ruleSource});
        for(const assertion of list(assessment.expertAssertions?.entries)){
            const claims=assertion.startLossRule?.claims||assertion.claims;
            const refs=list(claims).map(claim=>({...assertion.source,quote:claim.matchedParagraph||claim.sourceText||'',locator:claim.sourceLocator||''}));
            sources.push(...refs);
            if(!refs.length&&object(assertion.source))sources.push(assertion.source);
        }
    }
    for(const preparation of [facts.automaticPreparation,facts.automaticAssessment?.preparation].filter(object)){
        const preparationRows=fieldRows({fields:[['功能替代核查','transcriptReview.status'],['上游参考核查','upstreamReview.status'],
            ['替代转录本核查已完成','transcriptReview.complete'],['合格独立致病参考数','upstreamReview.qualifyingReferenceCount'],
            ['上游限定范围核查已完成','upstreamReview.complete'],['限定范围阴性已核实','upstreamReview.negativeCoverageGranted'],
            ['功能缺失机制核查','mechanismAssessment.status']]},preparation);
        if(preparationRows.length)sections.push({title:'起始丢失资料核查',rows:preparationRows});
        for(const item of [preparation,preparation.transcriptReview,preparation.upstreamReview,preparation.mechanismAssessment].filter(object)){
            sources.push(...list(item.sources));if(object(item.binding?.source))sources.push(item.binding.source);
        }
        for(const document of list(preparation.transcriptReview?.functionalLiterature?.documents)){
            if(!/^PMC[1-9][0-9]*$/.test(document.pmcid||''))continue;
            const observations=list(document.observations).filter(row=>typeof row.sourceQuote==='string'&&row.sourceQuote);
            if(!observations.length)sources.push({pmcid:document.pmcid});
            for(const row of observations.slice(0,8))sources.push({
                pmcid:document.pmcid,unitId:row.unitId,quote:row.sourceQuote,
            });
        }
    }
    const proof=facts.escapeAdoptionProof;
    if(object(proof)){
        const effectRows=fieldRows({fields:[['首个改变的残基','firstAlteredResidue'],['参考蛋白长度（aa）','referenceProteinLength'],
            ['改变后蛋白长度（aa）','alteredProteinLength'],['受影响的参考残基数','referenceResiduesAffected'],['蛋白净缩短（aa）','netProteinShortening']]},proof.proteinEffect);
        if(effectRows.length)sections.push({title:'NMD 逃逸后的蛋白影响',rows:effectRows});
        for(const check of list(proof.ruleChecks)){
            const r=check.rule||{};let requirement='',actual='';
            if(r.predicate==='CRITICAL_REGION'&&[r.start,r.end,r.minimumAffectedResidues].every(present)){
                requirement=`蛋白区域 ${r.start}–${r.end}；受影响残基数 ≥ ${r.minimumAffectedResidues}`;
                actual=readableEvidenceValue(check.affectedResiduesInRegion);
            }else if(r.predicate==='PROTEIN_LOSS_FRACTION'&&present(r.numerator)&&present(r.denominator)){
                requirement=`${r.metric==='NET_PROTEIN_SHORTENING'?'蛋白净缩短比例':'参考蛋白受影响比例'} ${operators[r.comparison]||''} ${r.numerator}/${r.denominator}`;
                if(present(check.actualNumerator)&&present(check.actualDenominator))actual=`${check.actualNumerator}/${check.actualDenominator}`;
            }
            if(requirement)sections.push({title:'NMD 逃逸规则条件',rows:uniqueRows([{label:'规则要求',value:requirement},{label:'已有信息',value:actual},{label:'核查结果',value:readableEvidenceValue(check.status)}])});
        }
        for(const condition of list(proof.positionConditions?.escape?.conditions)){
            const coordinate={REFERENCE_EVENT_SPAN:'参考序列事件位置',REFERENCE_PTC_SPAN:'参考序列终止位置',ALTERED_PTC_SPAN:'改变后终止位置'}[condition.coordinate];
            if(!coordinate)continue;
            const requirement=[present(condition.minimumInclusive)?`位置 ≥ ${condition.minimumInclusive}`:'',present(condition.maximumExclusive)?`位置 < ${condition.maximumExclusive}`:''].filter(Boolean).join('；');
            if(requirement)sections.push({title:coordinate,rows:uniqueRows([{label:'规则要求',value:requirement},{label:'已有信息',value:readableEvidenceValue(condition.checkedPositions)},{label:'核查结果',value:readableEvidenceValue(condition.status)}])});
        }
        sources.push(...list(proof.sources));
    }
    sources.push(...list(facts.sources),...list(facts.spliceAssessment?.sourceRecords));
    return {rows,sections,sources};
}

function spliceReferenceDetails(facts) {
    const comparison=facts.comparison||{},rows=fieldRows({fields:[['所选转录本','transcript'],['参考变异提交','referenceScv'],['比较基线','baseline'],['RNA 实验观察','observedRna']]},facts);
    rows.push(...fieldRows({fields:[['相似性要求','similarityMode']]},comparison));
    const events=[];
    for(const [label,event]of [['当前变异',comparison.targetEvent||facts.targetEvent],['参考变异',comparison.referenceEvent]])if(object(event))
        events.push({title:`${label}剪接预测`,rows:fieldRows({fields:[['事件','event'],['基因组位置','position'],['原始分值','score']]},event)});
    const names={AG:'受体位点增加',AL:'受体位点丢失',DG:'供体位点增加',DL:'供体位点丢失'};
    for(const [kind,label]of Object.entries(names)){
        const target=comparison.targetEvent?.profile?.[kind],reference=comparison.referenceEvent?.profile?.[kind];
        if(!object(target)&&!object(reference))continue;
        events.push({title:`${label}比较`,rows:uniqueRows([
            {label:'当前分值',value:readableEvidenceValue(target?.score)},{label:'当前位置',value:readableEvidenceValue(target?.position)},
            {label:'参考分值',value:readableEvidenceValue(reference?.score)},{label:'参考位置',value:readableEvidenceValue(reference?.position)}])});
    }
    const sources=facts.referenceScv?[{title:'ClinVar 参考提交',url:`https://www.ncbi.nlm.nih.gov/clinvar/?term=${encodeURIComponent(facts.referenceScv)}`}]:[];
    return {rows,sections:events,sources};
}

export function buildEvidenceDetailView(card,entry,context,displayContext) {
    const code=card.code||card.id?.split(':').pop(),def=evidenceDetailDefinitions[code];
    if(!def)return null;
    const presentation=resultPresentation(entry),copy=workbenchEvidenceCopy(card,entry,presentation),decision=decisionDetails(entry);
    const saved=entry.savedCriterion||entry.raw?.criteria?.find(c=>c.criterionId===card.id),details=saved?.details||{};
    const clinical=code==='PP1'&&!entry.displayIssue?savedClinicalDetails(saved,context,
        {candidateId:displayContext?.candidateId,hypothesisId:entry.hypothesisId,runIdentity:entry.clinicalDisplayScope},{identityHash:ps2IdentityHash}):null;
    const factKey={PP3:'predictionMechanisms',BP4:'predictionMechanisms',PS1:'referenceFacts',PM5:'referenceFacts',BP7:'bp7',PVS1:'pvs1Facts',PM4:'proteinLengthFacts',BP3:'bp3Facts',PM1:'pm1Facts',PP2:'pp2Facts',BP1:'bp1Facts',PS2:'deNovoFacts',PM6:'deNovoFacts',PM3:'pm3Facts',PP1:'pp1Facts'}[code];
    let facts=details[factKey]||{};
    const ptcRoute=code==='PM5'&&details.applicabilityRouting?.branch==='GENOMIC_PTC'?details.applicabilityRouting:null;
    const genomicPtc=!entry.displayIssue&&['PM5','PVS1'].includes(code)
        ? details.pm5PtcFacts?.schemaVersion==='published-pm5-ptc-facts-v1'?details.pm5PtcFacts:ptcRoute : null;
    if(code==='PM4'&&!Object.keys(facts).length)facts=details.pm4Facts||{};
    if(code==='PM3')facts=entry.pm3LiteratureFacts||facts;
    if(code==='PP1')facts=entry.pp1LiteratureFacts||facts;
    if(def.kind==='POPULATION')facts=populationFacts(entry,details,code);
    const pvs1Gap=code==='PVS1'&&!entry.displayIssue?pvs1UnresolvedDetails(facts,saved,context):null;
    const recordedSplice=code==='BP7'&&!entry.displayIssue&&presentation.mainKind!=='ADOPTED_MET'&&facts.experimentCheckStatus==='REVIEW_REQUIRED'&&
        list(facts.experimentSources).some(source=>typeof source.url==='string');
    const pm3Condition=code==='PM3'&&!entry.displayIssue?pm3ConditionDetails(facts,{current:entry.pm3ConditionsVersion==='pm3-selected-literature-v4'}):null;
    const pp2Hcm=code==='PP2'&&!entry.displayIssue?pp2HcmDetails(facts,{mainKind:presentation.mainKind,reasonCode:entry.evaluation?.evaluationResult?.reasonCode}):null;
    const rna=!entry.displayIssue?fullRnaEntryView(entry):null,rnaClaim=fullRnaCriterionClaim(saved);
    const fields=entry.displayIssue||rnaClaim||pm3Condition||pp2Hcm?[]:fieldRows(def,facts),sections=[],branchSources=[];
    const pp2Spectrum=code==='PP2'&&!entry.displayIssue&&facts.proteinVariantKey&&
        ['PP2_PUBLISHED_AUTHORITY_SPECTRUM_V1','PP2_ACGS_CONSTRAINT_NEGATIVE_V1'].includes(facts.authoritySpectrum?.method);
    if(pp2Spectrum&&facts.authoritySpectrum.method==='PP2_PUBLISHED_AUTHORITY_SPECTRUM_V1')fields.push(
        {label:'错义机制原文',value:readableEvidenceValue(facts.conditions?.missenseMechanism?.sourceText)},
        {label:'错义背景原文',value:readableEvidenceValue(facts.conditions?.lowBenignMissense?.sourceText)});
    const pp2Constraint=pp2Spectrum&&facts.authoritySpectrum.method==='PP2_ACGS_CONSTRAINT_NEGATIVE_V1'
        ?facts.conditions?.lowBenignMissense?.measurement:null;
    if(pp2Constraint&&Number.isFinite(pp2Constraint.expected)&&Number.isFinite(pp2Constraint.z))fields.push(
        {label:'错义机制依据',value:readableEvidenceValue(facts.conditions?.missenseMechanism?.sourceText)},
        {label:'gnomAD 错义约束',value:`v${pp2Constraint.release} · ${pp2Constraint.transcript} · 观察 ${pp2Constraint.observed} / 预期 ${pp2Constraint.expected.toFixed(4)} · Z ${pp2Constraint.z.toFixed(4)}（要求 ≥ ${pp2Constraint.threshold}）`});
    const domainDeletion=code==='PM1'&&!entry.displayIssue?facts.proteinDeletion:null;
    const reviewedPm1=code==='PM1'&&!entry.displayIssue&&['pm1-reviewed-regions-v1','pm1-reviewed-regions-v2'].includes(facts.reviewedRegion?.schemaVersion)
        ? facts.reviewedRegion : null;
    if(reviewedPm1) branchSources.push({title:'ACMG/AMP · PM1',url:'https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/',locator:'Table 3'},
        ...list(reviewedPm1.sources).map(source=>({...source,locationLabel:source.locator})));
    if(reviewedPm1?.observations?.length) sections.push({title:'区域对照变异',rows:pm1ComparisonRows(reviewedPm1)});
    if(code==='PM1'&&!entry.displayIssue) {
        const rows=pm1ClinVarRows(facts,context);
        if(rows.length) sections.push({title:'本地 ClinVar 候选对照',rows});
    }
    if(domainDeletion){
        branchSources.push({title:`ClinGen 专家组规范 · ${domainDeletion.specificationKey}`,url:domainDeletion.sourceUrl},
            {title:'专家组框内缺失证据',url:domainDeletion.consequenceSourceUrl});
    }
    if(genomicPtc){
        branchSources.push({title:'ClinGen · 专家组规范',url:genomicPtc.sourceUrl||genomicPtc.ruleSourceUrl});
        if(genomicPtc.tableUrl)branchSources.push({title:'Table 4 · 终止外显子权重',url:genomicPtc.tableUrl,locationLabel:genomicPtc.tableRow});
    }
    if(code==='PP1'&&!entry.displayIssue){
        const assessment=facts.evaluation?.meiosisAssessment;
        if(Number.isInteger(assessment?.informativeMeioses)&&Number.isInteger(assessment?.qualifiedFamilyCount)){
            const thresholds=['SUPPORTING','MODERATE','STRONG'].flatMap(strength=>{
                const row=assessment.thresholds?.[strength];
                if(!Number.isInteger(row?.minimum)||(row.maximum!==null&&!Number.isInteger(row.maximum)))return [];
                const range=row.maximum===null?`≥${row.minimum}`:row.maximum===row.minimum?String(row.minimum):`${row.minimum}–${row.maximum}`;
                return [{label:`${displayStrength(strength)}门槛`,value:`${range} 次${row.minimumIndependentFamilies>1?` · 至少 ${row.minimumIndependentFamilies} 个独立家系`:''}`}];
            });
            sections.push({title:'信息性传递计数',rows:[
                {label:'信息性传递',value:`${assessment.informativeMeioses} 次`},
                {label:'计入家系',value:`${assessment.qualifiedFamilyCount} 个`},
                ...thresholds,
            ]});
            const clause=facts.evaluation.context?.policy?.ruleClause;
            const rule=typeof clause==='string'&&clause.match(/^(GN\d+)@([\d.]+)\//);
            if(rule)branchSources.push({title:'ClinGen · PP1 专家组规范',url:`https://cspec.genome.network/cspec/ui/svi/doc/${rule[1]}?version=${rule[2]}`});
        }
        const attachments=list(facts.evaluation?.context?.ruleAttachments);
        const states={REFERENCE_NOT_REGISTERED:'待取得附件原件',REGISTERED_FILE_UNAVAILABLE:'已核对发布登记，待取得附件原件',
            VERIFIED_CONDITIONS_NOT_IMPLEMENTED:'原件已核对，附件条件尚未接入',VERIFIED_CONDITIONS_IMPLEMENTED:'已核对并应用附件条件',
            VERIFICATION_FAILED:'附件校验未通过'};
        const rows=attachments.map(a=>({label:readableEvidenceValue(a.label),value:states[a.status]})).filter(r=>r.label&&r.value);
        if(rows.length){
            sections.push({title:'规则附件',rows});
            branchSources.push(...attachments.flatMap(a=>a.ruleSourceUrl?[{title:'发布规范与附件登记',url:a.ruleSourceUrl}]:[]));
            branchSources.push(...attachments.flatMap(a=>a.fileSourceUrl?[{title:readableEvidenceValue(a.label),url:a.fileSourceUrl}]:[]));
        }
        const featureLabels={EPISTAXIS:'自发反复鼻出血',TELANGIECTASES:'典型部位多发毛细血管扩张',
            VISCERAL_LESIONS:'内脏血管病变',FAMILY_HISTORY:'合格一等亲家族史'};
        const phenotype=list(facts.evaluation?.clinicalAssessments).filter(a=>a.basis==='HHT_CURACAO_AFFECTED_ONLY');
        if(phenotype.length){
            const qualification={AFFECTED:'符合',UNRESOLVED:'资料待核',CONFLICT:'资料冲突'};
            sections.push({title:'Curaçao 患病资格',rows:phenotype.map(a=>({
                label:readableEvidenceValue(a.memberLabel),
                value:[qualification[a.status],a.reportedDefiniteClinicalCriteria?'原文报告满足临床标准':'',
                    ...list(a.verifiedManifestations).map(key=>featureLabels[key])].filter(Boolean).join(' · '),
            })).filter(r=>r.label&&r.value)});
        }
    }
    if(pp2Hcm){fields.push(...pp2Hcm.fields);sections.push(...pp2Hcm.sections);}
    if(pm3Condition){fields.push(...pm3Condition.rows);sections.push(...pm3Condition.sections);branchSources.push(...pm3Condition.sources);}
    const rnaDetails=rna?fullRnaDetails(rna):null;
    if(rnaDetails){fields.push(...rnaDetails.rows);sections.push(...rnaDetails.sections);branchSources.push(...rnaDetails.sources);}
    const ba1Condition=code==='BA1'&&!entry.displayIssue&&entry.ba1ConditionView?ba1ConditionDetails(entry.ba1ConditionView):null;
    if(ba1Condition){fields.push(...ba1Condition.rows);sections.push(...ba1Condition.sections);branchSources.push(...ba1Condition.sources);}
    if(code==='BS1'&&!entry.displayIssue){const strata=bs1StrataDetails(entry.savedPopulationResult);sections.push(...strata.sections);branchSources.push(...strata.sources);}
    const bp1Condition=code==='BP1'&&!entry.displayIssue&&facts.scope==='PUBLISHED_TRUNCATING_GOF_CONDITIONS'?bp1TruncatingDetails(facts):null;
    if(bp1Condition){fields.push(...bp1Condition.rows);branchSources.push(...bp1Condition.sources);}
    if(!entry.displayIssue&&!rnaClaim&&code==='PVS1'){
        const branch=pvs1Details(facts);fields.push(...branch.rows);sections.push(...branch.sections);branchSources.push(...branch.sources);
        if(pvs1Gap)branchSources.push(...pvs1Gap.sources);
    }
    if(!entry.displayIssue&&code==='BP7'){
        for(const source of list(facts.experimentSources))if(typeof source.url==='string')
            branchSources.push({...source,title:'RNA 实验原文',locationLabel:source.locator});
    }
    if(!entry.displayIssue&&code==='BP1'){
        for(const match of list(facts.functionalScreen?.matches))if(match.sourceUrl)
            branchSources.push({sourceId:match.sourceId,url:match.sourceUrl,title:'功能实验原文'});
    }
    if(!entry.displayIssue&&code==='PS1'&&details.spliceComparisonFacts?.schemaVersion==='ps1-splice-facts-v1'){
        const branch=spliceReferenceDetails(details.spliceComparisonFacts);fields.push(...branch.rows);sections.push(...branch.sections);branchSources.push(...branch.sources);
    }
    if(def.kind==='POPULATION'){
        const dataset=fields.find(row=>row.label==='数据来源');if(dataset)dataset.value=`gnomAD · ${dataset.value}`;
    }
    if(code==='PM2'&&!entry.displayIssue){
        const detection=fields.find(row=>row.label==='检测覆盖核查');if(detection&&facts.detection?.status==='HIT')detection.value='该位点有检测记录';
        const metrics={'gnomad.exomes.af':'总体频率 AF','gnomad.exomes.ac':'总体变异等位基因数 AC','gnomad.exomes.an':'总体检测等位基因数 AN','gnomad.exomes.faf95max':'最高过滤频率 FAF95','gnomad.exomes.faf95max.ancestry':'FAF95 所用人群'};
        for(const measurement of list(facts.measurements))if(metrics[measurement.metricId]&&present(measurement.rawValue))
            fields.push({label:metrics[measurement.metricId],value:readableEvidenceValue(measurement.rawValue)});
    }
    const ba1TranscriptLabels=code==='BA1'?['规范指定转录本','MANE 对应']:[];
    const populationLabels=code==='BA1'?['专家规范',...ba1TranscriptLabels,'质量','数据范围','例外检查','数据来源','查询状态',
        ...(ba1Condition?[]:['人群频率','本次阈值','祖源 / AN','同祖源 AC / AF'])]:
        code==='BS1'?['阈值与比较','FAF95','FAF 所选祖源','同祖源 AN / AC','同祖源普通 AF（辅助）','质量状态','exomes 原生 FILTER','查询结果']:[];
    if(!entry.displayIssue)for(const [label,value] of entry.details||[])if(populationLabels.includes(label)){
        const text=ba1TranscriptLabels.includes(label)?readableEvidenceValue(value):cleanSentence(value);
        if(text&&!fields.some(r=>r.label===label||r.value===text))fields.push({label,value:text});
    }
    const predictionSections=!entry.displayIssue&&def.kind==='PREDICTION'?predictionRows(facts,code,details,presentation,context):[];
    sections.push(...predictionSections);
    if(!entry.displayIssue&&['PS2','PM6'].includes(code))for(const event of list(facts.events))sections.push({title:`新生事件 ${event.ordinal||sections.length+1}`,rows:uniqueRows([
        {label:'资料来源',value:readableEvidenceValue(event.sourceKind)},
        {label:'亲子关系已确认',value:readableEvidenceValue(event.confirmedParentage)},
        {label:'表型对应情况',value:readableEvidenceValue(event.phenotypeCategories||event.phenotypeCategory)},
        {label:'事件采用情况',value:readableEvidenceValue(event.disposition)},
        {label:'未计入原因',value:[...new Set([event.reasonCode,...list(event.issues)])].map(reason=>deNovoEventReasons[reason]).filter(Boolean).join('；')},
        {label:'病例新生证据积分（汇总前）',value:typeof event.rawQuarterPoints==='number'?String(event.rawQuarterPoints/4):''},
    ])});
    if(!entry.displayIssue&&def.kind==='REFERENCE')for(const ref of list(facts.references))sections.push({title:`参考变异 ${ref.variationId||''}`.trim(),rows:uniqueRows([
        {label:'蛋白影响关系',value:readableEvidenceValue(ref.proteinRelation)},
        ...(code==='PS1'&&ref.nativeProteinComparison?.target&&ref.nativeProteinComparison?.reference?[
            {label:'当前与参考的蛋白替换',value:`${ref.nativeProteinComparison.target} / ${ref.nativeProteinComparison.reference}`},
            {label:'本次处理',value:ref.nativeProteinComparison.independentReference?'独立参考已核实，替换不同':'替换不同，排除本次 PS1 比较'},
        ]:[]),
        {label:'ClinVar 断言数',value:readableEvidenceValue(ref.sourceAssertionCount)},
    ]),sources:ref.variationId?[{title:'ClinVar 参考记录',url:`https://www.ncbi.nlm.nih.gov/clinvar/variation/${encodeURIComponent(ref.variationId)}/`}]:[]});
    const protein=entry.proteinLengthProofView;
    if(!entry.displayIssue&&protein?.change)fields.push(...[['参考蛋白长度（aa）','referenceProteinLength'],['改变后蛋白长度（aa）','alteredProteinLength'],['氨基酸净变化（aa）','netAminoAcidChange']]
        .map(([label,key])=>({label,value:readableEvidenceValue(protein.change[key])})).filter(r=>r.value));
    const coding=entry.pvs1CodingProofView;
    if(!entry.displayIssue&&coding?.positions)fields.push(...[['编码位置','cdsPosition'],['蛋白位置','proteinPosition'],['参考终止位置','referencePtc']]
        .map(([label,key])=>({label,value:readableEvidenceValue(coding.positions[key])})).filter(r=>r.value));
    const articleReferences=entry.displayIssue?[]:scalarSources([...list(facts.sources),...list(facts.sourceRefs),...branchSources]);
    const casePapers=entry.displayIssue?[]:pp2Hcm?pp2Hcm.papers.map(paper=>({...paper,sources:scalarSources(paper.sources)})):literatureCards(entry,details);
    const papers=withArticleSources(casePapers,articleReferences);
    const paperSourceKeys=new Set(papers.flatMap(p=>p.sources.map(s=>`${s.pmcid||s.url}:${s.locator}:${s.quote}`)));
    const comparisons=pm3Condition?pm3Condition.comparisons.map(row=>({...row,sources:scalarSources(row.sources)})):rna?.application==='NOT_ADOPTED'&&rna.claim.originalStatus==='NOT_EVALUATED'&&!rna.inapplicable?[fullRnaPending(rna)]:bp1Condition?.unsupported?[{title:'原生资料解释',requirement:bp1Condition.requirement,known:bp1Condition.known,
        gap:'',conclusion:bp1Condition.conclusion,suggestion:bp1Condition.suggestion,sources:[]}]:decision.comparisons.filter(r=>r.origin!=='已保存规则说明'&&!/^保存/.test(r.label||'')).map(r=>({
        title:cleanSentence(r.label)||'判定条件',requirement:cleanSentence(r.requirement),known:readableEvidenceValue(r.known),
        gap:cleanSentence(r.difference),conclusion:cleanSentence(r.impact),suggestion:cleanSentence(r.nextStep),sources:scalarSources(r.sources).filter(s=>!paperSourceKeys.has(`${s.pmcid||s.url}:${s.locator}:${s.quote}`)),
    })).filter(r=>r.requirement||r.known||r.gap);
    if(pp2Hcm)comparisons.unshift(...pp2Hcm.comparisons);
    if(recordedSplice)comparisons.splice(0,comparisons.length,{
        title:'RNA 实验核查',requirement:'核对是否已有原始实验报告剪接改变。',
        known:'与当前变异和转录本匹配的原文报告了剪接改变。',
        gap:'需按当前疾病背景复核实验的适用性。',conclusion:'当前不采用 BP7。',sources:[],
    });
    const conflicts=decision.conflicts.map(c=>({title:cleanSentence(c.origin)||'资料冲突',field:cleanSentence(c.field),
        left:{label:c.left.label,value:readableEvidenceValue(c.left.value),sources:scalarSources(c.left.sources)},
        right:{label:c.right.label,value:readableEvidenceValue(c.right.value),sources:scalarSources(c.right.sources)},conclusion:cleanSentence(c.impact)}));
    if(pp2Hcm)conflicts.push(...pp2Hcm.conflicts);
    if(pm3Condition)conflicts.push(...pm3Condition.conflicts.map(c=>({...c,left:{...c.left,sources:scalarSources(c.left.sources)},right:{...c.right,sources:scalarSources(c.right.sources)}})));
    if(rna?.application==='CONFLICT')conflicts.unshift({title:'相同 RNA 后果的相反方向证据',field:'证据采用',
        left:{label:'致病方向',value:rna.peers.filter(p=>['PVS1','PM4'].includes(p.code)&&p.status==='MET').map(p=>`${p.code} · ${displayStrength(p.strength)}`).join('；'),sources:[]},
        right:{label:'良性方向',value:'BP3 · 支持',sources:[]},conclusion:'两种方向存在冲突，本次均不采用。'});
    const rescueReviewConflict=fullRnaRescueReviewConflict(rna);
    if(rescueReviewConflict)conflicts.unshift({title:'功能救援审核冲突',
        left:{label:'本次起始资料审核',value:rnaDetails.conflictBasis.value,sources:rnaDetails.conflictBasis.sources},
        right:{label:'上层功能缺失审核',value:'同一背景已记录排除功能救援。',sources:[]},
        conclusion:fullRnaPending(rna).conclusion});
    const needs=presentation.mainKind==='NO_CONCLUSION'&&!rescueReviewConflict&&!['NOT_COUNTED','CONFLICT'].includes(rna?.application);
    const explanation=reasonExplanation(saved||entry.evaluation,details.explanation||entry.evaluation?.explanation);
    const missing=needs&&!bp1Condition?.unsupported?pp2Hcm?.missing|| ((pm3Condition||recordedSplice)?comparisons.map(r=>r.gap).filter(Boolean).join('；'):ba1Condition?.missing||cleanSentence(explanation?.missing)||comparisons.map(r=>r.gap).filter(Boolean).join('；')):'';
    const basisAvailable=Boolean(fields.length||sections.length||papers.length);
    if(context&&!entry.displayIssue){
        const tx=context.transcript;
        const contextRows=[
            {label:'当前遗传模式',value:readableEvidenceValue(context.moi)},
            {label:'所选转录本',value:tx?.accession?`${tx.accession}${tx.version&&!tx.accession.includes('.')?'.'+tx.version:''}`:''},
            {label:'变异后果',value:readableEvidenceValue(tx?.annotation?.consequence||tx?.annotation?.consequences)},
        ];
        for(const row of contextRows)if(row.value&&!fields.some(f=>f.label===row.label))fields.push(row);
    }
    const populationRule=code==='PM2'&&!entry.displayIssue&&details.populationRule?.schemaVersion==='pm2-rule-display-v1'?details.populationRule:null;
    const sources=scalarSources([...pm2RuleSources(populationRule),...(entry.links||[]),...articleReferences])
        .filter(source=>!articleReferences.some(ref=>articleKey(ref)&&articleKey(ref)===articleKey(source)));
    const eventMode=facts.adoptionProof?.policy?.eventMode;
    const deNovoPolicy=!entry.displayIssue&&['RASOPATHY_POINTS','SVI_1_1_PHENOTYPE_ONLY','SVI_1_1_PUBLISHED_POINTS'].includes(eventMode)
        ? list(facts.specializedPolicyAdoptions).find(row=>row?.criterionId===card.id&&row.status==='SELECTED'&&row.eventMode===eventMode)
        : null;
    const deNovoRule=deNovoPolicy?.catalogEntryId?.match?.(/^(GN\d+)-(\d+(?:\.\d+)+)-(PS2|PM6)$/);
    const specification=facts.specificationKey||facts.sourceSpecification||
        (deNovoRule&&deNovoRule[3]===code&&deNovoRule[2]===deNovoPolicy.policyVersion?`${deNovoRule[1]}@${deNovoRule[2]}`:null);
    if(typeof specification==='string'&&/^GN\d+@[\d.]+$/.test(specification)){
        const [id,version]=specification.split('@');sources.push({title:`ClinGen 专家组规范 · ${specification}`,url:`https://cspec.genome.network/cspec/ui/svi/doc/${id}?version=${version}`});
    }
    // Known non-target material is excluded above and must not become a patient-data gap.
    const summary=recordedSplice?'已匹配到原文记录的剪接改变，当前不采用 BP7。':
        pp2Hcm?.summary||pm3Condition?.summary||(bp1Condition?.unsupported?bp1Condition.conclusion:cleanSentence(copy.summary)||cleanSentence(entry.summary)||copy.label);
    const pm2Rows=list(populationRule?.conditions).map(row=>{
        const conflict=typeof row.label==='string'&&row.label.includes('资料冲突');
        return {label:cleanSentence(row.label),requirement:cleanSentence(row.requirement),actual:readableEvidenceValue(row.actual),conflict,
            outcome:conflict?'资料冲突':row.recordedOutcome==='TRUE'?'满足':row.recordedOutcome==='FALSE'?'未满足':row.recordedOutcome==='UNKNOWN'?'待核实':''};
    }).filter(row=>row.label&&row.requirement);
    const requirement=pvs1Gap?.requirement||(domainDeletion?'核实框内缺失及受影响的蛋白区间，确认完整覆盖适用规范的功能区。':'')||
        (reviewedPm1?'核对该错义变化是否位于当前疾病的突变热点或关键功能区，以及区域内的致病与良性资料。':'')||
        (genomicPtc?(code==='PM5'?'当前变异直接产生提前终止密码子，同一背景下 PVS1 成立，且终止外显子具有适用权重。':
        '当前变异直接产生提前终止密码子，并符合适用专家组的 PVS1 条件。'):'')||pp2Hcm?.requirement||bp1Condition?.requirement||
        (ba1Condition?'适用规范允许频率或人数条件满足其一；同时完成共用质量、适用性与例外核查。':
        code==='PM4'&&facts.regionState==='NOT_APPLICABLE_STOP_LOSS'?'原终止密码子丢失，且所选转录本可推导出新的终止位点和蛋白延长。':def.requirement);
    const consequence=pp2Spectrum?`纯编码错义 · ${facts.proteinVariantKey}`:
        readableEvidenceValue(context?.transcript?.annotation?.consequence||context?.transcript?.annotation?.consequences);
    const ruleRows=(reviewedPm1?reviewedPm1Rows(facts):pvs1Gap?.rows)||computedRuleRows({code,kind:def.kind,entry,details,facts,fields,sections,presentation,summary,rna,consequence,missing,
        ba1:code==='BA1'&&!entry.displayIssue?ba1Details(entry.raw):null,
        read:readableEvidenceValue,pm2Rows,predictionSections});
    const lengthFields=['参考蛋白长度（aa）','改变后蛋白长度（aa）','氨基酸净变化（aa）','新的终止位置已解析','新终止密码子编码位置','预测蛋白变化','重复区域核查'];
    const coreFields=pvs1Gap?fields.filter(r=>!['机制资料是否通过来源核查','规则强度上限'].includes(r.label)):
        bp1Condition?fields.filter(r=>!['机制资料核查','适用性结论'].includes(r.label)):
        code==='PM4'&&ruleRows.length?fields.filter(r=>!lengthFields.includes(r.label)):fields;
    const detailSections=code==='PVS1'&&ruleRows.some(r=>r.label==='NMD 逃逸后的蛋白影响')?sections.filter(s=>s.title!=='NMD 逃逸规则条件'):sections;
    return {code,title:pp2Hcm?.title||def.title,kind:def.kind,presentation,summary,requirement,fields:uniqueRows(coreFields),sections:detailSections,papers,clinical,
        sources:code==='PM2'?pm2DetailSources(sources):sources,
        ruleRows,ruleLabel:populationRule?.route==='GENERAL_ABSENCE_ONLY'?'gnomAD 外显子组未检出判定条件':`${code} 条件对照`,
        ruleDetailsMissing:code==='PM2'&&presentation.mainKind==='ADOPTED_MET'&&!pm2Rows.length,
        needs,missing,conclusion:summary,suggestion:needs?cleanSentence(presentation.outcome.next):'',comparisons,conflicts,
        failure:decision.issues.filter(i=>['EXECUTION','EXECUTION_FAILED','SYSTEM_INTEGRATION','CONFIGURATION','DISPLAY'].includes(i.kind)||
            (i.proteinLengthProof||i.pvs1CodingProof)?.availabilityOnly).map(i=>({title:cleanSentence(i.title),detail:readableEvidenceValue(i.detail)})),
        adopted:presentation.mainKind==='ADOPTED_MET',basisAvailable};
}
