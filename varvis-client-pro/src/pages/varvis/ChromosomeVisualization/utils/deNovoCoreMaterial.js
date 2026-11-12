export { validCoreMaterial } from './acmgInput';
export const coreGroups = {
    paper: '论文与可用范围', case: '病例与来源', gene: '基因', variant: '变异身份',
    proband: '先证者检测', mother: '母亲检测', father: '父亲检测', testing: '检测方法与充分性',
    parentage: '亲子关系', phenotype: '临床表现', family: '家系背景', overlap: '事件与重复病例',
};
const names = {
    identity: '论文编号', availability: '论文可用范围', supplements: '附件', label: '病例编号', origin: '病例来源', familyId: '家系编号',
    symbol: '基因符号', representations: '原始变异写法', resolvedAllele: '核实后的等位基因', targetResult: '目标变异检测结果', genotype: '基因型', mosaicism: '嵌合情况',
    method: '方法', memberId: '原文成员编号', probandMethod: '先证者检测方法', motherMethod: '母亲检测方法', fatherMethod: '父亲检测方法',
    participants: '检测对象', qualifiedForTarget: '能否支持目标检测判断', limitations: '检测限制', maternity: '母系亲缘', paternity: '父系亲缘',
    positive: '明确阳性表现', negative: '明确阴性表现', diagnosis: '诊断', diagnosisCertainty: '诊断确定程度', age: '年龄', onset: '发病时间',
    qtcMs: '校正 QT 间期', trioExome: '三人组外显子测序',
    history: '家族史', compatibility: '家系兼容性', siblings: '同胞', eventId: '事件身份', independence: '病例独立性', independentOf: '原文核对的独立家系', previousReports: '既往报道', followUpReports: '重复报道',
    allele: '评价等位基因', hgncId: '标准基因编号', diseaseId: '疾病背景', inheritance: '遗传方式', variantScope: '变异范围',
    geneDiseaseApplicability: '基因与疾病适用性', phenotypeCategory: '表型评分类别', policyId: '适用政策', policyVersion: '政策版本',
    expertPolicyStatus: '专家规范适用性', allowAssumedParentage: '是否允许未确认亲缘', phaseRequired: '是否需要相位', specialFamilyModel: '特殊家系情况',
    secondAlleles: '第二等位基因', classification: '分类', phase: '相位', classificationBasis: '分类依据', eventPerson: '事件个体', sex: '性别', pedigree: '家系',
    carrierStatus: '携带状态', affectedStatus: '患病状态', individuals: '涉及个体', tissuesAndAssays: '组织与检测', alleleFractions: '等位基因比例', relationships: '关系', eventGrouping: '事件归组',
};
const findingNames = {
    QT_PROLONGATION: 'QT 延长', UNEXPLAINED_SUDDEN_DEATH_UNDER_40: '40 岁前不明原因猝死',
    SWIMMING_ASSOCIATED_EVENT: '游泳相关发作', LQT1_CHARACTERISTIC_T_WAVE: 'LQT1 特征性 T 波',
};
export function coreFieldLabel(path) {
    const parts = path.split('/');
    if (parts.length === 5 && ['phenotype', 'family'].includes(parts[2]) && Object.hasOwn(findingNames, parts[4]))
        return `${terms[parts[3]] || parts[3]} · ${findingNames[parts[4]]}`;
    return `${parts[1] === 'groups' ? coreGroups[parts[2]] || parts[2] : parts[1] === 'context' ? '判定上下文' : parts[1] === 'conditions' ? '触发条件' : '条件补充'} · ${names[parts.at(-1)] || parts.at(-1)}`;
}
const terms = { PRESENT: '检出目标变异', ABSENT: '未检出目标变异', CONFIRMED: '已确认', ASSUMED_NOT_CONFIRMED: '未获确认', ASSUMED_NOT_TESTED: '未做亲缘检测', ASSUMED_NOT_REPORTED: '未报告亲缘检测',
    NOT_TESTED: '未做目标变异检测', NOT_REPORTED: '已核范围未报告目标检测结果', QUALITY_FAILED: '目标检测质量不足',
    MATERNALLY_INHERITED: '来自母亲', PATERNALLY_INHERITED: '来自父亲',
    CONFLICT: '存在冲突', INCONCLUSIVE: '结论不明', AUTHOR_COHORT: '作者研究病例', CITED_ONLY: '仅引用他人病例', PREVIOUSLY_REPORTED_REEXAMINED: '既往报道后复查',
    COMPATIBLE: '相容', UNRESOLVED: '尚未核实', PARTIAL: '部分范围', MAIN_AVAILABLE: '正文可用', NOT_APPLICABLE: '不适用',
    UNIQUE_WITHIN_CHECKED_SCOPE: '已检查范围内未重复', POSSIBLE_OVERLAP: '可能重复', KNOWN_DUPLICATE: '已知重复',
    VERIFIED_FOLLOW_UP_SAME_EVENT: '同一先证者的随访，按原始检测计一次',
    SANGER: 'Sanger 测序', EXOME: '外显子组测序', TRIO_EXOME: '先证者及双亲均完成测序',
    TARGETED_SEQUENCING: '目标位点测序', TARGETED_GENOTYPING: '目标位点分型', UNKNOWN: '尚未确定',
    HIGHLY_SPECIFIC: '高度特异', CONSISTENT: '一致', CONSISTENT_HIGH_HETEROGENEITY: '一致但遗传异质性高', NOT_CONSISTENT: '不一致',
    PROBAND: '先证者', MOTHER: '母亲', FATHER: '父亲', BOTH_PARENTS: '双亲', FAMILY: '家系', COHORT: '队列', PAPER: '论文', CONTEXT: '判定上下文', UNCLEAR: '对象不明',
    NONE_IDENTIFIED_IN_CHECKED_SCOPE: '检查范围内未发现', SMALL_SEQUENCE_VARIANT: '小型序列变异', APPLICABLE: '适用', GENERAL_ALLOWED: '允许通用规则',
};
export const coreValue = value => value === null ? '尚未取得' : Array.isArray(value) ? (value.length ? value.map(coreValue).join('；') : '已记录为空（不自动等同阴性）')
    : typeof value === 'object' ? JSON.stringify(value) : typeof value === 'boolean' ? (value ? '是' : '否') : terms[value] || String(value);
export function publishedConditionValue(field) {
    if (field.path === '/groups/phenotype/qtcMs' && typeof field.value === 'number') return `${field.value} ms`;
    if (field.path === '/groups/testing/trioExome') return coreValue(field.value);
    if (/^\/groups\/(phenotype|family)\/(PROBAND|MOTHER|FATHER|FAMILY)\//.test(field.path))
        return ({ PRESENT: '有记录', ABSENT: '明确未见', CONFLICT: '记录冲突' })[field.value];
    return undefined;
}
export function coreFactState(field) {
    if (field.verification === 'CONFLICT') return '存在冲突，不能采用';
    if (field.availability === 'NOT_EXTRACTED') return '尚未提取或核实，不代表原文没有';
    if (field.availability === 'NOT_REPORTED_IN_CHECKED_SCOPE') return '仅在已检查范围内未报告';
    if (field.availability === 'SOURCE_UNAVAILABLE') return '来源尚不可用';
    if (field.availability === 'NOT_APPLICABLE') return '当前不适用';
    return ({ VERIFIED: '已核实', UNVERIFIED: '已取得候选，尚未核实', UNSUPPORTED: '当前规则尚不能核实' })[field.verification];
}
export function coreBlockerText(blocker) {
    const categories = { KNOWLEDGE_MISSING: '缺少已配置且适用的疾病或规则知识', DATA_MISSING: '该项病例资料尚未充分核实',
        EXTRACTION_OR_REVIEW_INCOMPLETE: '提取或复核尚未完成', SOURCE_UNAVAILABLE: '原始资料不可用', CONFLICT: '资料存在冲突',
        SOFTWARE_UNSUPPORTED: '当前程序尚未支持', RULE_NOT_APPLICABLE: '规则不适用', EXECUTION_FAILURE: '执行未成功', REVIEW_REQUIRED: '需要进一步核定' };
    return `${categories[blocker.category] || '资格尚未满足'}；${blocker.meaning === 'NO_CLAIM_OF_SOURCE_ABSENCE' ? '不能据此断言论文没有记录' : blocker.meaning === 'DECLARED_SOURCE_SCOPE_ONLY' ? '仅限已经检查的资料范围' : '需要满足此项资格'}。`;
}
