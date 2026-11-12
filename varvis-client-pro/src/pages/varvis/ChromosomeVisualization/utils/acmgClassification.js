import catalog from './acmgClassificationCatalog.json';

export const classificationLevels = [
    {code:'B',label:'良性',english:'Benign',color:'#257465',background:'#edf8f3'},
    {code:'LB',label:'可能良性',english:'Likely benign',color:'#277d96',background:'#edf7fb'},
    {code:'VUS',label:'意义未明',english:'Uncertain significance',color:'#976614',background:'#fff8e6'},
    {code:'LP',label:'可能致病',english:'Likely pathogenic',color:'#bf672e',background:'#fff2e8'},
    {code:'P',label:'致病',english:'Pathogenic',color:'#bd4050',background:'#fff0f2'},
];

export const GENERAL_CLASSIFICATION_RULES = {
    id:'ACMG_AMP_2015_CLINGEN_SVI_COMBINATIONS', label:'ACMG/AMP · ClinGen 通用规则',
    type:'GENERAL', status:'SELECTED',
    sources:[
        {url:'https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/',locator:'Table 5'},
        {url:'https://www.clinicalgenome.org/site/assets/files/7438/variant_curation_sop_v3_2_oct_2022.pdf',locator:'Appendix III'},
    ],
};
const pathogenic = /^(PVS1|PS[1-4]|PM[1-6]|PP[1-4])$/;
const benign = /^(BA1|BS[1-4]|BP[1-57])$/; // PP5/BP6 are not independent evidence (ClinGen).
const weights = {SUPPORTING:1,MODERATE:2,STRONG:4,VERY_STRONG:8};
const pm1Pp3Codes = new Set(['PM1','PP3']);
const strengthNames = {'Very Strong':'VERY_STRONG',Strong:'STRONG',Moderate:'MODERATE',Supporting:'SUPPORTING','Stand Alone':'STAND_ALONE'};
const inferences = {Pathogenic:'P','Likely Pathogenic':'LP',Benign:'B','Likely Benign':'LB','Uncertain Significance':'VUS'};
const noRule = (reason, extra={}) => ({status:'REVIEW',type:'UNRESOLVED',id:null,label:'规范待核查',reason,...extra});
const sameGene = (scope, gene) => gene?.hgncId ? scope.hgncId === gene.hgncId && (!gene.symbol || scope.gene === gene.symbol)
    : Boolean(gene?.symbol && scope.gene === gene.symbol);

// Only official gene/disease/inheritance associations select a specialist rule.
// Preferred transcripts are recommendations, not an invented exclusive restriction.
// Other diseases, fuzzy names and other genes never supply a matching specification.
export function selectClassificationRules(context, inventory=catalog) {
    if (!context) return GENERAL_CLASSIFICATION_RULES;
    if (context.scope?.analysisDomain !== 'GERMLINE' || context.scope?.diseaseModel !== 'MENDELIAN' ||
        context.scope?.variantScope !== 'SMALL_SEQUENCE_VARIANT')
        return noRule('当前变异范围不属于本次接入的胚系孟德尔小变异分类。');
    if ((!context.gene?.hgncId && !context.gene?.symbol) || !/^MONDO:[0-9]{7}$/.test(context.disease?.mondoId || '') ||
        !['AUTOSOMAL_DOMINANT','AUTOSOMAL_RECESSIVE','X_LINKED','X_LINKED_DOMINANT','X_LINKED_RECESSIVE','Y_LINKED'].includes(context.moi))
        return noRule('基因、疾病背景或遗传模式尚未完整确定。');
    if (inventory?.schemaVersion !== 'acmg-classification-catalog-v1' || !Array.isArray(inventory.specifications) ||
        !inventory.specifications.length || inventory.source?.scope !== 'PINNED_RELEASED_CSPEC_INVENTORY' ||
        !/^[a-f0-9]{64}$/.test(inventory.sha256 || ''))
        return noRule('专家组规范目录不可用。');
    const matches = [], unresolved = [];
    for (const spec of inventory.specifications) for (const scope of spec.scopes) {
        if (context.gene.hgncId && scope.hgncId === context.gene.hgncId && context.gene.symbol && scope.gene !== context.gene.symbol)
            return noRule('基因名称与标准标识不一致。');
        if (!sameGene(scope,context.gene)) continue;
        if (!scope.disease || !scope.inheritance.length || scope.inheritance.some(m=>m.startsWith('UNRESOLVED:'))) {
            unresolved.push(spec); continue;
        }
        if (scope.disease !== context.disease.mondoId) continue;
        if (scope.inheritance.includes(context.moi)) { if (!matches.includes(spec)) matches.push(spec); }
        else if (scope.inheritance.includes('X_LINKED') && context.moi.startsWith('X_LINKED') ||
            context.moi === 'X_LINKED' && scope.inheritance.some(m=>m.startsWith('X_LINKED'))) unresolved.push(spec);
    }
    if (matches.length > 1) return noRule('存在多份同时适用的专家组规范，需核对适用范围。',{specifications:matches.map(s=>s.id)});
    if (unresolved.length) return noRule('相关专家组规范的疾病或遗传模式范围尚需核对。',{specifications:unresolved.map(s=>s.id)});
    if (!matches.length) return {...GENERAL_CLASSIFICATION_RULES,selectionBasis:'NO_MATCH_IN_PINNED_CSPEC_INVENTORY',catalogSha256:inventory.sha256};
    const spec = matches[0];
    if (spec.geneType !== 'nuclear') return noRule('所选专家组规范不属于本次接入的核基因分类。');
    const rule = {...spec,type:'VCEP',status:'SELECTED',catalogSha256:inventory.sha256,
        label:`ClinGen ${context.gene.symbol || context.gene.hgncId} · v${spec.version}`};
    const keys = Object.keys(spec.rules || {});
    if (keys.length !== 1 || !['mainRules','pointRules'].includes(keys[0]))
        return noRule('适用专家组规范的组合规则尚不能自动计算。',{...rule,status:'REVIEW'});
    return rule;
}

// Minimum counts from ACMG/AMP Table 5. ClinGen SOP Appendix III additionally
// accepts one Very Strong + one Supporting for Likely Pathogenic.
const generalCombinations = [
    ['P',{VERY_STRONG:1,STRONG:1}], ['P',{VERY_STRONG:1,MODERATE:2}],
    ['P',{VERY_STRONG:1,MODERATE:1,SUPPORTING:1}], ['P',{VERY_STRONG:1,SUPPORTING:2}],
    ['P',{STRONG:2}], ['P',{STRONG:1,MODERATE:3}],
    ['P',{STRONG:1,MODERATE:2,SUPPORTING:2}], ['P',{STRONG:1,MODERATE:1,SUPPORTING:4}],
    ['LP',{VERY_STRONG:1,MODERATE:1}], ['LP',{VERY_STRONG:1,SUPPORTING:1}],
    ['LP',{STRONG:1,MODERATE:1}], ['LP',{STRONG:1,SUPPORTING:2}],
    ['LP',{MODERATE:3}], ['LP',{MODERATE:2,SUPPORTING:2}], ['LP',{MODERATE:1,SUPPORTING:4}],
];
const predicate = (expression,value) => {
    const match = /^(>=|<=|==|>|<)(-?\d+)$/.exec(expression || '');
    if (!match) throw Error('规范中存在尚未支持的组合条件。');
    const n=Number(match[2]);
    return ({'>=':value>=n,'<=':value<=n,'==':value===n,'>':value>n,'<':value<n})[match[1]];
};
const pairKey = (code,strength) => `${code}:${strength === 'STANDALONE' ? 'STAND_ALONE' : strength}`;

// ClinGen SVI PP3/BP4 guidance, Table 4. The cap is on joint evidence, not the saved criterion strengths.
export function pm1Pp3Cap(items) {
    const pm1=items.find(item=>item.code==='PM1'),pp3=items.find(item=>item.code==='PP3');
    const rawPoints=(weights[pm1?.strength] || 0)+(weights[pp3?.strength] || 0);
    return pm1 && pp3 && rawPoints>weights.STRONG ? {rawPoints,points:weights.STRONG} : null;
}

function specialistDecision(items,rule) {
    const approved = new Set();
    const found = [];
    for (const item of items) {
        const criterion=rule.criteria?.find(c=>c.code===item.code);
        // A reviewed PM3 result may use the exact released combination table
        // when the inventory has no approved descriptors. The pair must still
        // occur in an explicit combination below; other gaps remain unresolved.
        const useReleasedCombination=Boolean(rule.rules.mainRules && item.code==='PM3' && rule.id &&
            item.reviewedPm3RuleId===rule.id && criterion?.strengths?.length===0);
        if (!criterion?.applicable || (!useReleasedCombination &&
            !criterion.strengths.includes(item.strength === 'STANDALONE' ? 'STAND_ALONE' : item.strength)))
            throw Error(`${item.code} 的采用强度不符合所选专家组规范，需重新核查该证据。`);
    }
    if (rule.rules.mainRules) {
        if (!Array.isArray(rule.rules.mainRules) || !rule.rules.mainRules.length) throw Error('所选专家组规范没有可计算的组合规则。');
        for (const r of rule.rules.mainRules) {
            if (!inferences[r.inference] || !Array.isArray(r.conditions) || !r.conditions.length) throw Error('专家组组合规则结构不完整。');
            const conditions = r.conditions.map(c => {
                const [direction,strength] = (c.partitionPath || '').split('.');
                const appliedStrength=strengthNames[strength];
                if (!['Pathogenic','Benign'].includes(direction) || !appliedStrength || !Array.isArray(c.applicableCriteriaCodes) ||
                    !c.applicableCriteriaCodes.length) throw Error('专家组组合规则包含尚未支持的证据分组。');
                const codes=new Set(c.applicableCriteriaCodes.map(code => {
                    const parts=/^((?:PVS|PS|PM|PP|BA|BS|BP)\d+)(?:_(.+))?$/.exec(code);
                    if (!parts || !(direction==='Pathogenic' ? /^(PVS1|PS[1-4]|PM[1-6]|PP[1-5])$/ : /^(BA1|BS[1-4]|BP[1-7])$/).test(parts[1]) ||
                        parts[2] && parts[2].toUpperCase().replaceAll(' ','_') !== appliedStrength)
                        throw Error('专家组组合规则包含尚未支持的证据代码或强度。');
                    approved.add(pairKey(parts[1],appliedStrength)); return parts[1];
                }));
                // Validate every condition, including rules that do not match this variant.
                predicate(c.condition,0);
                return predicate(c.condition,items.filter(i=>codes.has(i.code) && pairKey(i.code,i.strength) === pairKey(i.code,appliedStrength)).length);
            });
            if (conditions.every(Boolean)) found.push({code:inferences[r.inference],id:r.rule});
        }
    } else {
        const pointRules=rule.rules.pointRules;
        if (!pointRules || Object.keys(pointRules).some(k=>k!=='items') || !Array.isArray(pointRules.items) || !pointRules.items.length)
            throw Error('所选专家组积分组合规则尚不能自动计算。');
        for (const c of rule.criteria || []) if (c.applicable) for (const strength of c.strengths) approved.add(pairKey(c.code,strength));
        const total=items.reduce((sum,i)=>{
            if (i.standalone) return sum;
            const points=rule.criteria.find(c=>c.code===i.code).points?.[i.strength];
            if (!Number.isSafeInteger(points) || points===0 || (points>0)!==pathogenic.test(i.code))
                throw Error(`${i.code} 的专家组积分换算尚未明确。`);
            return sum+points;
        },0);
        for (const r of pointRules.items) {
            if (!inferences[r.inference] || !Array.isArray(r.conditions) || !r.conditions.length) throw Error('专家组积分分类区间不完整。');
            const conditions=r.conditions.map(c=>predicate(c.condition,total));
            if (conditions.every(Boolean)) found.push({code:inferences[r.inference],id:r.rule});
        }
        if (!items.some(i=>i.standalone) && new Set(found.map(r=>r.code)).size!==1)
            throw Error('当前专家组积分未落入唯一的已发布分类区间。');
    }
    const unsupported=items.find(i=>!approved.has(pairKey(i.code,i.strength)));
    if (unsupported) throw Error(`${unsupported.code} 的采用强度不符合所选专家组规范，需重新核查该证据。`);
    if (items.some(i=>i.code==='BA1' && i.standalone)) return {code:'B',matchedRules:['BA1']};
    const p=found.find(r=>r.code==='P') || found.find(r=>r.code==='LP');
    const b=found.find(r=>r.code==='B') || found.find(r=>r.code==='LB');
    // Combination-based specifications keep conflicting directions unresolved.
    // An expert point model is applied only when the source explicitly defines it.
    const conflict=Boolean(p && b) || Boolean(rule.rules.mainRules && items.some(i=>pathogenic.test(i.code)) && items.some(i=>benign.test(i.code)));
    return {code:conflict?'VUS':p?.code || b?.code || 'VUS',conflict,matchedRules:found.map(r=>r.id)};
}

export function classifyAdoptedEvidence(score,rule=GENERAL_CLASSIFICATION_RULES) {
    const provisional=(score?.pendingCount || 0)>0;
    const pending={code:null,label:'待分类',color:'#657389',background:'#f1f4f8',provisional,rule};
    if (!score) return pending;
    if (score.status==='REVIEW') return {...pending,label:'分类待核查',reason:score.issues?.join(' ')};
    if (!rule || typeof rule!=='object') return {...pending,label:'规范待核查',reason:'未取得可核对的分类规则。'};
    if (rule.status!=='SELECTED') return {...pending,label:'规范待核查',reason:rule.reason};
    if (!['GENERAL','VCEP'].includes(rule.type)) return {...pending,label:'规范待核查',reason:'尚未接通此类分类规则。'};
    if (!['SCORED','STANDALONE'].includes(score.status) || !Array.isArray(score.items) || !score.items.length) return pending;
    if (score.status==='SCORED' && !Number.isSafeInteger(score.total)) return {...pending,label:'分类待核查',reason:'保存的得分数值无效。'};
    const seen=new Set();
    for (const i of score.items) {
        if (seen.has(i.code) || !(pathogenic.test(i.code)||benign.test(i.code)) ||
            i.code==='BA1' && !i.standalone || i.code!=='BA1' && i.standalone ||
            !(i.code==='BA1' && i.standalone && ['STAND_ALONE','STANDALONE'].includes(i.strength)) && !weights[i.strength])
            return {...pending,label:'分类待核查',reason:'采用证据的代码、强度或独立性尚需核查。'};
        seen.add(i.code);
    }
    let decision;
    if (rule.type==='VCEP') {
        try { decision=specialistDecision(score.items,rule); }
        catch (error) { return {...pending,label:'规范待核查',reason:error.message}; }
    } else {
        const p=score.items.filter(i=>pathogenic.test(i.code)), b=score.items.filter(i=>benign.test(i.code));
        if (p.length && b.length) decision={code:'VUS',conflict:true,matchedRules:[]};
        else if (b.some(i=>i.code==='BA1' && i.standalone)) decision={code:'B',matchedRules:['BA1']};
        else if (b.length) {
            const strong=b.filter(i=>i.strength==='STRONG').length, supporting=b.filter(i=>i.strength==='SUPPORTING').length;
            decision={code:strong>=2?'B':strong>=1&&supporting>=1 || supporting>=2?'LB':'VUS',matchedRules:[]};
        } else {
            const combined=pm1Pp3Cap(p);
            const items=combined ? [...p.filter(i=>!pm1Pp3Codes.has(i.code)),{strength:'STRONG'}] : p;
            const count=Object.fromEntries(Object.keys(weights).map(s=>[s,items.filter(i=>i.strength===s).length]));
            const match=generalCombinations.find(([,needs])=>Object.entries(needs).every(([s,n])=>count[s]>=n));
            decision={code:match?.[0] || 'VUS',matchedRules:match?[`PVS:${count.VERY_STRONG}/PS:${count.STRONG}/PM:${count.MODERATE}/PP:${count.SUPPORTING}`]:[]};
        }
    }
    return {...classificationLevels.find(level=>level.code===decision.code),...decision,provisional,rule};
}
