import { evidenceSummary } from './acmgInput';
import { resultPresentation, savedBackgroundBinding, savedContextAllele, savedVariantLabel } from './acmgResultsWorkbench';
import { formatVariantCoordinate } from './variantCoordinate';
import { fullRnaEntryView, fullRnaSummary } from './acmgFullRna';
import { pp2HcmDetails } from './acmgPp2HcmDetails';

// Presentation only. Stored facts, adoption, strengths, scores and exports are untouched.
const cleanText = value => typeof value === 'string' ? value
    .replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, '')
    .replace(/满足本次规则条件；?实际强度和依据以保存结果为准。?/g, '')
    .replace(/具体原因见已保存证据详情。?/g, '')
    .replace(/\bEXOMES\b/g, '外显子组').replace(/\bSNV\b/g, '单核苷酸变异')
    .replace(/\s+/g, ' ').trim().replace(/^[；，。\s]+/, '') : '';

const adoptedCopy = {
    PP3:'计算预测支持有害影响。', BP4:'计算预测支持无有害影响。',
    PM1:'热点或关键功能区域证据提供致病支持。',
    PS1:'相同蛋白或剪接影响的参考证据提供致病支持。',
    PM5:'同一氨基酸位置的其他替换证据提供致病支持。',
    PVS1:'功能缺失证据提供致病支持。', PM4:'蛋白长度改变证据提供致病支持。',
    PP2:'当前疾病背景中的错义致病机制证据已采用。',
    PS2:'亲子关系已确认的新生变异证据已采用。',
    PM6:'亲子关系未确认的新生变异证据已采用。',
    PM3:'隐性疾病背景的双等位基因证据已采用。', PP1:'家系共分离证据提供致病支持。',
    BA1:'人群频率达到当前规则的独立良性条件。', BS1:'人群频率高于当前疾病规则允许的范围。',
    BP1:'当前背景中的错义变异证据提供良性支持。',
    BP3:'重复区域内的框内变异证据提供良性支持。', BP7:'同义或内含子变异证据提供良性支持。',
};
const excludedCopy = {
    ROUTING_PM3_NON_RECESSIVE_CONTEXT:'当前疾病背景不是隐性遗传，不适用此项证据。',
    ROUTING_MISSENSE_REQUIRED:'所选转录本未注释为错义变异，不适用当前错义分支。',
    PM5_PTC_SPLICE_MECHANISM_EXCLUDED:'所选专家组规范的 PM5 仅采用直接编码提前终止，当前变异进入剪接分支。',
    PM5_PTC_DIRECT_GENOMIC_STOP_REQUIRED:'当前变异后果不符合该专家组的提前终止条件。',
    PM5_PTC_PVS1_EXCLUDED_BY_RULE:'当前终止位置属于专家组的 PVS1 排除范围，PM5 不适用。',
    PVS1_PUBLISHED_PTC_RULE_EXCLUDED:'当前终止位置属于专家组的 PVS1 排除范围。',
    ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE:'所选转录本未注释出本分支要求的蛋白长度改变。',
    PVS1_CODING_BRANCH_NOT_APPLICABLE:'当前注释不属于本次功能缺失判定分支。',
    PVS1_CONSEQUENCE_NOT_APPLICABLE:'当前注释不属于本次功能缺失判定分支。',
    BP7_CONSEQUENCE_NOT_APPLICABLE:'当前注释不属于本次同义或内含子变异判定分支。',
};
const statuses = {
    MISSING:'资料不足', QUALITY_REJECTED:'资料质量未通过', POLICY_NOT_READY:'规范待接通',
    NO_RECORD:'未取得匹配记录', INCOMPLETE:'取证未完成', UNRESOLVED:'结果待核查',
    DISABLED:'能力未启用', CONFIGURATION:'资源未就绪', UNAVAILABLE:'资料暂不可用',
    CONFLICT:'资料冲突', UNSUPPORTED:'暂未支持', EXECUTION_FAILED:'执行失败',
};
const unresolvedCopy = {
    BS1_DISEASE_PARAMETERS_MISSING:'疾病参数不完整，需核对患病率、外显率及基因／变异贡献比例。',
    DENOVO_AFFECTED_STATUS_REQUIRED:'当前样本的患病状态尚未满足本分支要求。',
    PVS1_MECHANISM_SOURCE_NOT_FOUND:'尚未取得当前基因与疾病背景的功能缺失机制依据。',
    PP1_LITERATURE_REQUIREMENTS_UNRESOLVED:'文献资料尚未满足当前背景的身份、家系或共享计分条件。',
    ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE:'当前程序尚未支持这类变异的 PM1 判定。',
    PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE:'当前预测流程尚未支持所选变异后果。',
    PM4_COMPLEX_ALLELE_UNSUPPORTED:'当前程序尚未支持这类复杂变异的序列处理。',
    CLAUSE_SEARCH_SCOPE_NOT_COVERED:'尚未接通适用于当前背景的判定规范。',
    PP3_ALLOWED_MECHANISM_UNRESOLVED:'用于判断有害影响的预测机制尚未完成核查。',
    BP4_REQUIRED_MECHANISM_UNRESOLVED:'用于判断无有害影响的必要预测机制尚未完成核查。',
    NO_RECORD_IS_NOT_FREQUENCY_ZERO:'未取得当前变异的精确匹配频率记录。',
    PM3_NO_COMPATIBLE_LITERATURE_OBSERVATIONS:'尚未取得与当前变异和背景匹配、可采用的病例观察。',
    BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED:'已匹配到原文记录的剪接改变，BP7 暂不采用。',
};

export function workbenchEvidenceCopy(card, entry, presentation = resultPresentation(entry)) {
    const p = presentation, code = card.code, rna=fullRnaEntryView(entry);
    const hcm=code==='PP2'&&!entry?.displayIssue?pp2HcmDetails(entry?.savedCriterion?.details?.pp2Facts,
        {mainKind:p.mainKind,reasonCode:p.reasonCode}):null;
    if(hcm?.summary)return {label:p.mainLabel,summary:hcm.summary};
    if(rna)return {label:rna.application==='CONFLICT'?'资料冲突':rna.application==='NOT_COUNTED'?'不重复计分':rna.application==='ADOPTED'?'已采用':p.mainKind==='NOT_MET'?'未满足':p.mainKind==='NOT_APPLICABLE'?'不适用':p.outcome.label,summary:fullRnaSummary(rna)};
    if (p.mainKind === 'ADOPTED_MET') return { label:'已采用', summary:
        p.reasonCode === 'PM5_PUBLISHED_GENOMIC_PTC_RULE_MET'
            ? '同背景 PVS1 已满足，按提前终止密码子所在外显子的权重采用 PM5。'
            : code === 'BA1' && entry.ba1ConditionView?.status==='MET'
            ? '本次频率或人数条件满足已采用的独立良性规则。'
            : code === 'PM2' && p.reasonCode === 'PM2_PROJECT_LOCAL_ABSENCE_MET'
            ? '本次外显子组数据未检出该变异，位点检测覆盖符合要求。'
            : adoptedCopy[code] || cleanText(evidenceSummary(entry)) };
    if (p.mainKind === 'NOT_MET') return {label:'未满足',summary:cleanText(evidenceSummary(entry))};
    if (p.mainKind === 'NOT_APPLICABLE') return {label:'不适用',summary:excludedCopy[p.reasonCode] || cleanText(evidenceSummary(entry))};
    if (p.adoption.kind === 'NOT_COUNTED') return { label:'不重复计分', summary:p.adoption.targetCriterion
        ? `该共同事件归属 ${p.adoption.targetCriterion}。` : '该资料不再重复计分。' };
    if (p.outcome.kind === 'REVIEW') return {label:p.adoption.kind==='REJECTED' ? '复核未通过' : p.adoption.kind==='OVERRIDDEN' ? '采用结果待核查' : '待复核',
        summary:p.adoption.kind==='REJECTED' ? '本项复核未通过，暂未采用。' : p.adoption.kind==='OVERRIDDEN' ? '复核已覆盖原评价，需要核对最终采用结果。' : '本项尚未完成采用复核，暂不计分。'};
    if (p.progress.kind === 'DISPLAY_REVIEW' || p.adoption.kind === 'DISPLAY_UNVERIFIED')
        return {label:p.outcome.kind==='UNSUPPORTED' || p.outcome.kind==='UNAVAILABLE' ? p.outcome.label : '结果待核查',
            summary:entry?.displayIssue ? cleanText(evidenceSummary(entry)) || '保存记录尚未通过核验。' : '保存结果的身份、范围或采用记录尚未通过核验。'};
    if (p.status==='MET') return {label:'尚未采用',summary:'本项尚未形成可采用结果，暂不计分。'};
    return { label:statuses[p.outcome.kind] || p.outcome.label, summary:unresolvedCopy[p.reasonCode] || cleanText(evidenceSummary(entry)) ||
        (p.outcome.kind==='EXECUTION_FAILED' ? '执行失败，具体失败原因待核查。' : '具体判定原因尚待核查。') };
}

export function workbenchVariantIdentity(candidate, backgroundIndex = 0) {
    const attempt = candidate?.attempts?.[backgroundIndex];
    const context = attempt?.context || attempt?.bs1Context || attempt?.ba1Context;
    const binding = context ? null : savedBackgroundBinding(candidate, attempt);
    const allele = savedContextAllele(context) || (binding && savedVariantLabel(candidate).variant);
    const transcript = context?.transcript;
    const accession = transcript?.accession;
    const moi = context?.moi || binding?.normalizedMoi;
    return {
        gene:context?.gene?.symbol || context?.gene?.hgncId || binding?.symbol || binding?.hgncId || '',
        coordinate:formatVariantCoordinate(allele,candidate?.candidateId || ''),
        assembly:allele?.assembly || '',
        transcript:transcript?.versionedId || (accession ? `${accession}${transcript.version != null && !accession.includes('.') ? `.${transcript.version}` : ''}` : ''),
        hgvs:[transcript?.annotation?.hgvsc,transcript?.annotation?.hgvsp].filter(value=>typeof value==='string' && value.trim()).join(' · '),
        moi:({AUTOSOMAL_DOMINANT:'AD',AUTOSOMAL_RECESSIVE:'AR',X_LINKED:'X-linked',X_LINKED_DOMINANT:'XLD',X_LINKED_RECESSIVE:'XLR',MITOCHONDRIAL:'Mitochondrial'})[moi] || moi || '',
        diseaseId:context?.disease?.mondoId || binding?.mondoId || '',
    };
}
