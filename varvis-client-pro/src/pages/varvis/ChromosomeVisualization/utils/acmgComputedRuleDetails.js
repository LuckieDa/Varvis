import { displayStrength } from './evidencePresentation';

const operators = { GT: '>', GTE: '≥', LT: '<', LTE: '≤', EQ: '=', GREATER_THAN: '>', GREATER_THAN_OR_EQUAL: '≥', LESS_THAN: '<', LESS_THAN_OR_EQUAL: '≤' };
const outcomes = { TRUE: '满足', FALSE: '未满足', UNKNOWN: '待核实', MATCH: '满足', NO_MATCH: '未满足',
    SUPPORTED: '满足', NOT_SUPPORTED: '未满足', PASS: '满足', FAIL: '未满足', INDETERMINATE: '待核实',
    MET: '满足', NOT_MET: '未满足', NOT_APPLICABLE: '不适用', NOT_EVALUATED: '待核实', UNAVAILABLE: '待核实' };
const list = value => Array.isArray(value) ? value : [];
const number = value => typeof value === 'number' && Number.isFinite(value) || typeof value === 'string' && /^[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[-+]?\d+)?$/i.test(value);
const length = value => number(value) ? `${value} aa` : '';
const range = (start, end) => number(start) && number(end) ? `${start === end ? start : `${start}–${end}`} aa` : '';
const limit = (metric, operator, value) => metric && operators[operator] && number(value) ? `${metric} ${operators[operator]} ${value}` : '';
const row = (label, requirement, actual, outcome = '') => ({ label, requirement, actual: actual || '', outcome: outcome || (!actual ? '待核实' : '') });

function pm2SavedRows(rows) {
    const hasLocus = rows.some(r => r.label==='位点检测记录' && ['已检出','该位点有检测记录'].includes(r.actual));
    return rows.map(r => {
        const locus = r.label==='位点检测记录';
        const actual = locus && r.actual==='已检出' ? '该位点有检测记录' : r.actual;
        if(r.outcome) return {...r,actual};
        // Historical summaries lacked outcomes for detection, integer AN and regions.
        let outcome;
        if(r.label==='精确变异查询' && r.requirement==='未检出当前精确变异' && actual==='未检出记录') outcome='满足';
        if(locus && hasLocus) outcome='满足';
        if(locus && actual==='未检出记录') outcome='未满足';
        if(hasLocus && r.requirement==='位于区域内' && ['位于区域内','位于区域外'].includes(actual)) outcome=actual===r.requirement?'满足':'未满足';
        const an = r.requirement.match(/^AN ([>≥]) (\d{1,30})$/);
        if(hasLocus && an && /^\d{1,30}$/.test(actual) && !rows.some(x=>x.label==='检测量阈值资料冲突')){
            const count=BigInt(actual), minimum=BigInt(an[2]);
            outcome=(an[1]==='>'?count>minimum:count>=minimum)?'满足':'未满足';
        }
        return {...r,actual,outcome:outcome||'待核实'};
    });
}

function populationRows(input) {
    const {code, facts, fields, entry, read, ba1, adopted, reason} = input;
    const value = label => fields.find(f => f.label === label)?.value || '';
    if (code === 'PM2') {
        const absent = facts.queryStatus === 'NO_RECORD';
        const count = read(facts.detection?.adjAn);
        const measurements = list(facts.measurements);
        const frequency = value('人群频率') || value('总体频率 AF');
        return [
            row('精确变异查询', '核对当前精确变异的频率或未检出记录', read(facts.queryStatus)),
            ...(absent ? [row('位点检测记录', '该位点有检测记录', value('检测覆盖核查'), facts.detection?.status === 'HIT' ? '满足' : ''),
                row('检测等位基因数 AN', '达到本次规则要求的检测量', count),
                row('覆盖区域', '符合适用规则的区域条件', '')] : [
                row('频率或计数', '符合本次适用的低频或计数条件', frequency || read(measurements.find(m => m.metricId === 'gnomad.exomes.ac')?.rawValue))]),
        ];
    }
    if (code === 'BA1' && entry.ba1ConditionView) {
        const view = entry.ba1ConditionView;
        const f = view.frequencyRequirement || {}, c = view.individualCountRequirement || {};
        const reading = metric => list(view.frequencyReadings).find(r => r.metricId === metric && r.status === 'AVAILABLE')?.rawValue;
        const group = reading('gnomad.joint.faf95max.ancestry');
        const count = view.observedHomozygoteStatistic;
        const bounds = view.countSourceQualification?.additionalNativeBounds;
        const observed = [count?.qualificationStatus === 'QUALIFIED_LOWER_BOUND' && number(count.rawValue) ? `纯合 ≥ ${count.rawValue} 人` : '',
            bounds?.hemizygoteLowerBound?.status === 'QUALIFIED_LOWER_BOUND' ? `半合子 ≥ ${bounds.hemizygoteLowerBound.value} 人` : '',
            bounds?.personUpperBound?.status === 'QUALIFIED_UPPER_BOUND' ? `人数上界 ≤ ${bounds.personUpperBound.value} 人` : ''].filter(Boolean).join(' · ');
        return [row('频率或人数条件', '两项满足其一，并通过共用质量与适用性核查', outcomes[view.truth] || ''),
            row('人群频率 FAF95', limit('FAF95', f.comparator, f.value) || '达到适用频率门槛', read(reading(f.metric)), outcomes[view.frequencyTruth]),
            ...(number(f.minimumAn) ? [row('同祖源检测量', limit('AN', f.minimumAnComparator, f.minimumAn) || '达到适用检测量', group ? read(reading(`gnomad.joint.ancestry.${group}.an`)) : '')] : []),
            row('纯合或半合子人数', limit('人数', c.operator, c.value) || '达到适用人数门槛', observed, outcomes[view.countTruth])];
    }
    const raw = code === 'BS1' ? entry.savedPopulationResult || entry.raw : entry.raw;
    const policy = raw?.preparation?.policy || raw?.policy;
    const stats = policy?.statistic;
    const trace = raw?.evaluation?.deterministicTrace;
    const mode = raw?.preparation?.selectedMode;
    const tierRows = list(policy?.tiers).filter(t => t.moi === (mode || raw.context?.moi)).sort((a,b)=>a.priority-b.priority).flatMap((tier, i) =>
        list(tier.conditions).flatMap((condition, j) => {
            const requirement = limit(condition.fact, condition.operator, condition.decimal);
            const bound = mode && trace?.executionStatus==='COMPLETED' && list(trace.parameters).some(p=>p.name==='selectedMode'&&p.value===mode) &&
                list(trace.parameters).some(p=>p.name===`tier.${tier.tierId}.${condition.fact}.${condition.operator}`&&p.value===condition.decimal);
            const check = bound && list(trace.steps).find(s=>s.stepCode===`BS1_TIER_${i+1}_CONDITION_${j+1}`)?.outcome;
            return requirement ? [row(`${condition.fact} · ${displayStrength(tier.strength)}`, requirement, read(raw.qualityDecision?.facts?.[condition.fact]), outcomes[check])] : [];
        }));
    const threshold = limit('FAF95',ba1?.operator,ba1?.threshold) || read(facts.threshold);
    const frequency = read(ba1?.frequency) || value('FAF95') || value('过滤频率 FAF95') || value('频率 AF');
    const exception={HIT:'命中例外清单',NOT_HIT:'未命中例外清单'}[ba1?.exception];
    const ba1Steps = code === 'BA1' && ba1?.localExomes && ['ADOPTED_MET', 'NOT_MET'].includes(input.presentation.mainKind)
        ? list(entry.evaluation?.deterministicTrace?.steps) : [];
    const ba1Outcome = (...codes) => {
        const matches = ba1Steps.filter(step => codes.includes(step?.stepCode));
        const outcome = matches.length === 1 ? matches[0].outcome : '';
        return ['TRUE', 'FALSE', 'UNKNOWN'].includes(outcome) ? outcomes[outcome] : '';
    };
    return [
        ...(tierRows.length ? tierRows : [row('人群频率', threshold || (code === 'BS1' ? '高于本疾病与遗传模式的适用阈值' : '达到适用的独立良性频率阈值'),
            frequency, ba1Outcome('FREQUENCY_COMPARATOR_SATISFIED', 'FREQUENCY_GREATER_THAN_THRESHOLD') || (adopted && frequency ? '满足' : ''))]),
        row('检测等位基因数 AN', limit('AN', stats?.eligibleAn?.operator, stats?.eligibleAn?.decimal) || limit('AN',ba1?.minimumAnOperator,ba1?.minimumAn) || '达到对应人群的检测量要求',
            read(ba1?.an ?? facts.AN), ba1Outcome('SELECTED_ANCESTRY_AN_SUFFICIENT') || (reason==='BS1_LOCAL_AN_INSUFFICIENT'?'未满足':adopted&&number(ba1?.an??facts.AN)?'满足':'')),
        row('资料质量', '精确变异、人群和质量过滤符合本次规则', value('质量状态') || value('质量'),
            ba1Outcome('EXACT_PASS_AND_READINESS_CONFIRMED') || (adopted&&(value('质量状态')||value('质量'))?'满足':'')),
        ...(code === 'BA1' ? [row('例外变异', '未命中本次规则的例外清单', exception || value('例外检查') || read(facts.exceptionStatus),
            ba1?.exception==='HIT'?'未满足':ba1?.exception==='NOT_HIT'?'满足':'')] : []),
    ];
}

function lengthRows({code, facts, entry, rna, consequence, reason, adopted, read}) {
    const proof = entry.proteinLengthProofView;
    const change = rna?.details || proof?.change || facts;
    const stop = code === 'PM4' && (proof?.rule?.branch === 'SEQUENCE_DERIVED_STOP_LOSS' || /STOP_LOSS/.test(facts.scope || '') ||
        /STOP_LOSS/.test(rna?.details?.branch || '') || reason === 'PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET' || reason === 'PM4_SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION_MET');
    const delta = change.netAminoAcidChange ?? facts.netAminoAcidChange;
    const before = length(change.referenceProteinLength ?? facts.referenceProteinLength);
    const after = length(change.alteredProteinLength ?? facts.alteredProteinLength);
    const proteinChange = [before && after ? `${before} → ${after}` : before || after, number(delta) ? `净变化 ${Number(delta) > 0 ? '+' : ''}${delta} aa` : ''].filter(Boolean).join(' · ');
    const lengthOutcome = number(delta) ? (stop ? Number(delta) > 0 : Number(delta) !== 0) ? '满足' : '未满足' : '';
    const rows = [row('变异后果', stop ? '原终止密码子丢失' : code === 'BP3' ? '属于允许的框内长度改变' : '属于允许的终止丢失或框内长度改变',
        [stop?'终止密码子丢失':consequence, read(facts.predictedHgvsp)].filter(Boolean).join(' · '), adopted && (stop || consequence) ? '满足' : ''),
    row('蛋白长度', stop ? '蛋白在原终止位点之后继续延长' : '核实蛋白长度净变化', proteinChange, lengthOutcome)];
    if (stop) {
        const position = change.alteredStopCdsPosition ?? facts.alteredStopCdsPosition;
        const resolved = change.newTerminationResolved ?? facts.newTerminationResolved;
        rows.push(row('新终止位点', '能在对应序列中确定新的终止密码子',
            number(position) ? `CDS 第 ${position} 位` : resolved === true || rna && adopted ? '已定位' : '',
            resolved === true || rna && adopted ? '满足' : '待核实'));
        return rows;
    }
    const region = facts.regionState || proof?.rule?.regionState;
    const regionText = { NON_REPEAT_SUPPORTED: '受审资料覆盖完整影响区间', OPERATIONAL_NON_REPEAT_SUPPORTED: '已核对区域与序列，按本次规则推定非重复',
        REPEAT_OVERLAP: '与重复区域重叠', QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION: '位于合格重复区，未发现已知功能区冲突',
        EXCLUDED: '区域条件不符合', UNKNOWN: '区域资格尚未核实' }[region];
    const known = [range(facts.queryRange?.start ?? proof?.rule?.queryRange?.start, facts.queryRange?.end ?? proof?.rule?.queryRange?.end),
        regionText || read(facts.repeatObservation)].filter(Boolean).join(' · ');
    const regionOutcome = code === 'PM4' ? { NON_REPEAT_SUPPORTED: '满足', OPERATIONAL_NON_REPEAT_SUPPORTED: '满足', REPEAT_OVERLAP: '未满足' }[region]
        : { QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION: '满足', EXCLUDED: '未满足' }[region];
    rows.push(row('蛋白区域', code === 'BP3' ? '位于符合规则的重复区，且无已知功能区域冲突' : '框内变异的影响区间符合非重复条件', known, regionOutcome));
    return rows;
}

function variantRows({code, facts, details, consequence, adopted, read, sections, rna, reason}) {
    const match = actual => adopted && actual ? '满足' : '';
    const ptc = details.pm5PtcFacts;
    const route = details.applicabilityRouting;
    if (code === 'PM5' && !ptc && route?.branch === 'GENOMIC_PTC') {
        return [row('变异后果', '直接产生提前终止密码子', consequence,
            route.status === 'NOT_APPLICABLE' ? '不适用' : ''),
            row('适用专家规范', '核对基因、疾病与转录本', route.specification,
                route.status === 'UNRESOLVED' ? '待核查' : '')];
    }
    if (['PM5','PVS1'].includes(code) && ptc?.schemaVersion === 'published-pm5-ptc-facts-v1') {
        const position = Number.isInteger(ptc.stopProteinPosition) ? `蛋白第 ${ptc.stopProteinPosition} 位终止` : '';
        const exon = Number.isInteger(ptc.stopExon) ? `第 ${ptc.stopExon} 外显子` : '';
        const pvs1 = ptc.pvs1Status === 'MET' ? displayStrength(ptc.pvs1Strength) :
            ptc.pvs1Status === 'NOT_APPLICABLE' ? '不适用' : '';
        const weight = ptc.pm5Strength === 'NONE' ? '无 PM5 权重' : ptc.pm5Strength ? displayStrength(ptc.pm5Strength) : '';
        return [
            row('提前终止密码子', '核实当前变异直接引起的提前终止与所在外显子',
                [position, exon].filter(Boolean).join(' · '), position && exon ? '满足' : ''),
            row('同背景 PVS1', '符合当前专家组的 PVS1 条件', pvs1, outcomes[ptc.pvs1Status]),
            ...(code === 'PM5' ? [row('外显子权重', '采用终止密码子所在外显子的已发布权重',
                weight, ptc.pm5Strength === 'NONE' ? '未满足' : adopted && weight ? '满足' : '')] : []),
        ];
    }
    if (code === 'PS1' || code === 'PM5') {
        const splice = code === 'PS1' && details.spliceComparisonFacts?.schemaVersion === 'ps1-splice-facts-v1';
        const relationship = splice ? '与参考变异造成相同剪接事件，并符合规定的预测比较条件' : code === 'PS1' ? '与合格参考变异产生相同的氨基酸改变' : '与合格参考变异位于同一氨基酸位置，但替换不同';
        const comparison = splice && details.spliceComparisonFacts.comparison;
        const event = comparison?.targetEvent || details.spliceComparisonFacts?.targetEvent;
        const counts = [number(facts.comparableReferences) ? `可比较 ${facts.comparableReferences} 条` : '', number(facts.criterionMatches) ? `匹配 ${facts.criterionMatches} 条` : '',
            Number(facts.unresolvedCandidates) > 0 ? `待核 ${facts.unresolvedCandidates} 条` : ''].filter(Boolean).join(' · ');
        const completeNoMatch = facts.searchStatus === 'COMPLETE' && Number(facts.comparableReferences) > 0 &&
            Number(facts.unresolvedCandidates) === 0 && number(facts.criterionMatches) && Number(facts.criterionMatches) === 0;
        return [row('参考变异资格', '取得适用背景下有独立致病依据的参考变异', counts, adopted&&counts?'满足':''),
            row(splice ? '剪接影响比较' : '蛋白变化比较', relationship,
                splice ? [read(event?.kind || event?.type), counts].filter(Boolean).join(' · ') : counts || consequence,
                completeNoMatch ? '未满足' : match(counts || event))];
    }
    if (code === 'PM1') {
        const deletion = facts.proteinDeletion;
        if (deletion) {
            const affected = deletion.confirmedDeletedResidues;
            const possible = deletion.possibleDeletedResidues;
            const actual = affected ? range(affected.start, affected.end) : '';
            return [row('框内缺失', '由参考序列和选定转录本核实蛋白缺失',
                [deletion.proteinHgvs, number(deletion.deletedAminoAcids) ? `缺失 ${deletion.deletedAminoAcids} aa` : ''].filter(Boolean).join(' · '),
                possible ? '满足' : ''),
            ...list(deletion.domains).map(domain => row(domain.name, `完整覆盖功能区 ${range(domain.start, domain.end)}`,
                actual || (possible ? `可能影响 ${range(possible.start, possible.end)}` : ''),
                ({ COMPLETE:'满足', DISJOINT:'未满足', PARTIAL_OR_AMBIGUOUS:'待核实' })[domain.outcome]))];
        }
        const scope = range(facts.selectedRule?.start, facts.selectedRule?.end);
        const actual = length(facts.proteinPosition);
        return [row('蛋白位置', scope ? `位于 ${scope} 的适用热点或关键区域` : '位于适用规范确认的热点、关键残基或关键区域', actual, match(actual)),
            ...list(facts.crossCriterionConditionCheck?.checks).flatMap(check => {
                const target = check.required?.criterionId?.split(':').pop();
                if (!/^[A-Z]{2,3}[1-9]$/.test(target || '')) return [];
                const strength=check.required?.minimumStrength?displayStrength(check.required.minimumStrength):'';
                const exclusion=check.required?.relation==='EXCLUDE_IF_MET_AT_LEAST';
                const requirement=strength?`${target} ${exclusion?'不得达到':'至少达到'}${strength}级`:`满足规范对 ${target} 的要求`;
                const actual=[read(check.available?.status),check.available?.appliedStrength&&check.available.appliedStrength!=='NONE'?displayStrength(check.available.appliedStrength):''].filter(Boolean).join(' · ');
                return [row(`${target} 前置条件`, requirement, actual, outcomes[check.status])];
            })];
    }
    if (code === 'BP7') {
        const position = read(facts.positionStatus);
        const geometry = facts.geometry, rule = facts.positionRule;
        let positionRequirement = '属于适用规则允许的同义或内含子位置';
        let distance = number(facts.spliceDistance) ? `距剪接位点 ${facts.spliceDistance} nt` : '';
        if (rule?.consequenceClass==='SYNONYMOUS' && number(rule.minimumFromExonEnd)) {
            positionRequirement = `距外显子末端 ≥ ${rule.minimumFromExonEnd} nt${rule.excludeFirstExonBase?'，且不在外显子首位':''}`;
            if(number(geometry?.zeroBasedDistanceFromExonEnd)&&number(geometry?.zeroBasedDistanceFromExonStart))
                distance=`距末端 ${geometry.zeroBasedDistanceFromExonEnd} nt · 距起点 ${geometry.zeroBasedDistanceFromExonStart} nt`;
        } else if (rule?.consequenceClass==='INTRONIC' && number(rule.minimumDonorDistance) && number(rule.minimumAcceptorDistance)) {
            positionRequirement = `距供体 ≥ ${rule.minimumDonorDistance} nt，距受体 ≥ ${rule.minimumAcceptorDistance} nt`;
            if(number(geometry?.donorDistance)&&number(geometry?.acceptorDistance))distance=`距供体 ${geometry.donorDistance} nt · 距受体 ${geometry.acceptorDistance} nt`;
        }
        const splice = facts.spliceCondition;
        return [row('变异后果与位置', positionRequirement, [consequence || read(facts.consequence), distance || position].filter(Boolean).join(' · '),
            {ELIGIBLE:'满足',EXCLUDED:'不适用'}[facts.positionStatus]||outcomes[facts.positionStatus]),
            row('剪接影响', number(splice?.benignMaximum) ? `同次剪接预测满足 BP4，SpliceAI 分值 ≤ ${splice.benignMaximum}` : '满足适用的剪接预测或实验条件',
                read(splice?.rawScore ?? facts.spliceScore), outcomes[splice?.bp4Status]),
            row('RNA 实验核查', '按规则核对已知的剪接实验结果', {REVIEW_REQUIRED:'已有原文记录，待核对适用性',NO_MATCH_IN_DECLARED_CATALOG:'已核目录无匹配实验'}[facts.experimentCheckStatus]||read(facts.experimentCheckStatus),
                facts.experimentCheckStatus==='NO_MATCH_IN_DECLARED_CATALOG'?'满足':''),
            ...(facts.conservation!=null?[row('保守性', '满足该分支的保守性条件', read(facts.conservation))]:[])];
    }
    if (code === 'PP2') {
        const missense = facts.conditions?.missenseMechanism?.conclusion, benign = facts.conditions?.lowBenignMissense?.conclusion;
        const verifiedMissense = ['PP2_PUBLISHED_AUTHORITY_SPECTRUM_V1', 'PP2_ACGS_CONSTRAINT_NEGATIVE_V1']
            .includes(facts.authoritySpectrum?.method) && consequence;
        return [row('变异后果', '当前背景和转录本符合适用条款的变异范围', consequence, verifiedMissense ? '满足' : ''),
            row('错义致病机制', '适用疾病有可信的错义致病机制依据', read(missense), outcomes[missense]),
            row('良性错义背景', '适用规范要求的低良性错义背景已核实', read(benign), outcomes[benign])];
    }
    if (code === 'BP1') {
        const truncating = facts.truncatingConditionCheck;
        if (facts.scope === 'PUBLISHED_TRUNCATING_GOF_CONDITIONS') {
            if (truncating?.status === 'NOT_APPLICABLE' && truncating.available?.variantEffect === 'VERIFIED_PURE_CODING_MISSENSE')
                return [row('变异后果', '属于当前规范规定的预测截短', '纯编码错义', '不适用')];
            const established = Boolean(truncating?.structuredLof) || truncating?.lofDiseaseCorrelation?.status === 'CONTRAINDICATION_ESTABLISHED';
            return [row('序列后果', '属于当前规范要求的预测截短分支', consequence || (truncating?.sequenceConsequence?.status === 'TRUNCATION_CANDIDATE' ? '预测编码截短' : '')),
                row('功能缺失关联', '核实当前基因无已建立的功能缺失疾病关联', established ? '已有明确的功能缺失疾病关联' : '', established ? '未满足' : '')];
        }
        const region=facts.regionConditionCheck;
        const excluded=list(region?.excludedProteinRegions).map(r=>range(r.start,r.end)).filter(Boolean).join('、');
        const maximum=facts.maximumAllowedDelta ?? region?.maximumRawDelta;
        return [row('变异后果', '属于本次适用的错义范围', consequence || read(facts.proteinVariantKey)),
            row('疾病机制', '适用疾病背景支持条款要求的致病机制', facts.knowledgeDisposition === 'NOT_APPLICABLE' ? '当前疾病机制不符合规范范围' : read(facts.knowledgeStatus),
                facts.knowledgeDisposition === 'NOT_APPLICABLE' ? '不适用' : ''),
            ...(excluded?[row('蛋白位置', `位于规范排除区间之外（${excluded}）`, length(region.proteinPosition), region.status==='NOT_APPLICABLE'?'不适用':'')]:[]),
            row('剪接影响', number(maximum)?`SpliceAI 最大变化分值 ≤ ${maximum}`:'符合本次适用规范的剪接条件', read(facts.maximumObservedDelta),
                reason==='BP1_PUBLISHED_SPLICE_CONDITION_NOT_MET'||reason==='BP1_PROJECT_SPLICE_PREDICTION_REQUIRES_REVIEW'?'未满足':adopted&&number(facts.maximumObservedDelta)?'满足':''),
            row('功能资料', '完成规范要求的功能资料核查', read(facts.functionalScreen?.status))];
    }
    const savedNonsense = facts.sequenceDerivationStatus === 'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE'
        && number(facts.cdsPosition) && number(facts.ptcStart) && number(facts.ptcEnd)
        && facts.coordinateSystem !== 'ALTERED_CDS';
    const mechanism = read(facts.variantMechanism || facts.branch || (savedNonsense ? 'NONSENSE' : '')) || consequence;
    const checkRows = sections.filter(s => s.title === 'NMD 逃逸规则条件').map(s => row('NMD 逃逸后的蛋白影响',
        s.rows.find(r => r.label === '规则要求')?.value, s.rows.find(r => r.label === '已有信息')?.value, s.rows.find(r => r.label === '核查结果')?.value));
    const start = sections.find(s => s.title === '起始密码子丢失核查');
    const startPreparation = sections.find(s => s.title === '起始丢失资料核查');
    const startFact = label => start?.rows.find(r => r.label === label)?.value || startPreparation?.rows.find(r => r.label === label)?.value;
    const codons = [startFact('参考起始密码子'), startFact('改变后起始密码子')].filter(Boolean).join(' → ');
    const initiationRows = !start && !startPreparation ? [] : [
        row('起始密码子', '核对选定转录本的起始密码子是否丢失', codons),
        row('替代转录本', '核对是否存在可保留功能的替代转录本', startFact('功能替代核查') || startFact('替代转录本核查')),
        row('上游致病参考', '按适用规则核对下游起始位置之前的独立致病参考', startFact('合格独立致病参考数') || startFact('上游参考核查')),
    ];
    return [row('变异后果', '属于可核查的功能缺失分支', mechanism, match(mechanism)),
        row('基因与疾病机制', '有适用的功能缺失机制依据', facts.sourceQualifiedResearch === true ? '机制依据已通过来源核查' : read(facts.knowledgeStatus), facts.sourceQualifiedResearch === true ? '满足' : ''),
        ...(rna?.preparation?.sourceEvidenceKind ? [row('RNA 来源', '实验或预测后果与当前转录本及规则对应', read(rna.preparation.sourceEvidenceKind))] : []),
        ...initiationRows,
        ...checkRows.filter(r => r.requirement),
        ...(facts.nmdPrediction ? [row('无义介导降解（NMD）', '核查提前终止是否触发降解，并应用对应分支',
            read(facts.nmdPrediction), match(read(facts.nmdPrediction)))] : [])];
}

export function computedRuleRows(input) {
    const {code, kind, entry, presentation, summary, read, consequence, pm2Rows, predictionSections, missing} = input;
    if (kind === 'LITERATURE') return [];
    if (entry.displayIssue) return [row('保存资料核查', '核实同次运行的结果与当前变异及背景相符', summary, '待核实')];
    const reason = presentation.reasonCode;
    const adopted = presentation.mainKind === 'ADOPTED_MET';
    const state = { NOT_MET: '未满足', NOT_APPLICABLE: '不适用', NO_CONCLUSION: '待核实' }[presentation.mainKind];
    const data = {...input, adopted, reason, consequence};
    let rows;
    if (code === 'PM2' && pm2Rows.length) rows = pm2SavedRows(pm2Rows);
    else if (kind === 'POPULATION') rows = populationRows(data);
    else if (kind === 'PREDICTION') {
        const mechanisms = code === 'BP4' ? input.facts.requiredBp4Mechanisms : input.facts.primaryMechanism;
        rows = [row('适用预测机制', code === 'BP4' ? '适用机制均取得合格的无害预测' : '适用机制的合格预测支持有害影响', read(mechanisms),
            adopted&&read(mechanisms)?'满足':'')];
        if (!predictionSections.length) rows.push(row('预测分值与阈值', '取得与当前变异和转录本匹配的预测及适用区间', ''));
    } else if (code === 'PM4' || code === 'BP3') rows = lengthRows(data);
    else rows = variantRows(data);
    const blockingLabels = {
        PM2_ALL_SITES_AN_INSUFFICIENT: '检测等位基因数 AN', PM2_ALL_SITES_LOCUS_NOT_DETECTED: '位点检测记录',
        PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING: '位点检测记录', PM2_ALL_SITES_REGION_EXCLUDED: '覆盖区域',
        BA1_EXCEPTION_HIT: '例外变异', BS1_DISEASE_PARAMETERS_MISSING: '人群频率', BS1_LOCAL_AN_INSUFFICIENT: '检测等位基因数 AN',
        ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE: '变异后果', ROUTING_MISSENSE_REQUIRED: '变异后果',
        PVS1_CODING_BRANCH_NOT_APPLICABLE: '变异后果', PVS1_CONSEQUENCE_NOT_APPLICABLE: '变异后果',
        PVS1_MECHANISM_SOURCE_NOT_FOUND: '基因与疾病机制', BP7_CONSEQUENCE_NOT_APPLICABLE: '变异后果与位置',
        BP7_POSITION_SPLICE_PROXIMAL: '变异后果与位置', BP7_INTRONIC_SPLICE_PROXIMAL: '变异后果与位置',
        BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED: 'RNA 实验核查', BP1_SOURCE_NOT_APPLICABLE: '疾病机制',
        BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE: '变异后果',
        PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET: '蛋白区域', PM4_NON_REPEAT_QUALIFIED_COVERAGE_GAP: '蛋白区域',
        PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED: '蛋白区域',
    };
    const label = blockingLabels[reason];
    const blocked = state && rows.find(r => r.label === label);
    if (blocked) {
        if (blocked.outcome!=='未满足'&&blocked.outcome!=='资料冲突') blocked.outcome = state;
        if (summary && !blocked.actual.includes(summary)) blocked.actual = [blocked.actual,summary].filter(Boolean).join('\n');
    } else if (state && summary) rows.push(row('本次判定', missing || '完成适用条件核查', summary, state));
    return rows;
}
