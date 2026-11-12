import React from 'react';
import { Alert, Box, Chip, Paper, Stack, Typography } from '@mui/material';
import { decisionDetails } from '../utils/acmgDecisionDetails';

const show=value=>value===undefined?'未保存':typeof value==='string'?value:JSON.stringify(value,null,2);
function SavedValue({label,value}) {
    const text=show(value),limit=6000;
    return <Box sx={{minWidth:0}}><Typography variant="body2" sx={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>
        <strong>{label}：</strong>{text.slice(0,limit)}
    </Typography>{text.length>limit&&<Typography variant="caption" color="warning.main">本字段仅展示前6000字符；完整值和出处保留在原始结果。</Typography>}</Box>;
}
function SavedSources({sources}) {
    if(!sources?.length)return <Typography variant="caption">本条未单独保存可展示的出处；请核对下方原件引用。</Typography>;
    return <Stack spacing={0.5} sx={{mt:0.5}}>{sources.slice(0,6).map((source,index)=><SavedValue key={index} label={`出处 ${index+1}`} value={source}/>)}
        {sources.length>6&&<Typography variant="caption">此处展示6 / {sources.length}处来源，完整引用保留在下方模块与原始结果。</Typography>}</Stack>;
}
function ProteinLengthCurrent({view}) {
    const result={MET:'已评价满足',NOT_MET:'已评价未满足',NOT_EVALUATED:'尚未评价'}[view.result.status]||view.result.status;
    const branch={SEQUENCE_DERIVED_STOP_LOSS:'参考序列推导的终止延长',OBSERVED_REPEAT_OVERLAP_EXCLUDES_PM4:'重复区重叠排除PM4',
        REVIEWED_NON_REPEAT:'受审非重复区条件',REVIEWED_REPEAT_AND_FUNCTION:'受审重复区及功能条件'}[view.rule?.branch]||view.rule?.branch;
    const change=view.change;
    return <Stack spacing={0.75} data-testid="protein-length-current-summary">
        <Stack direction="row" gap={0.75} useFlexGap flexWrap="wrap">
            <Chip size="small" variant="outlined" label={`当前材料 ${view.materialStatus}`}/>
            <Chip size="small" variant="outlined" label={view.annotationCorrespondence==='COMPLETE_SEQUENCE_COMPARISON_PASSED'?'完整序列比较通过':`当前比较 ${view.annotationCorrespondence}`}/>
            <Chip size="small" variant="outlined" label={`${result} · ${view.proofStatus==='AVAILABLE'?'证明可核对':'证明暂不可采用'}`}/>
        </Stack>
        <Typography variant="body2">材料就绪、比较通过和证据成立分别核对；证明可核对也可能对应已评价未满足，不产生额外科学或临床资格。</Typography>
        {view.availabilityOnly&&<Typography variant="body2">原判定和强度保留；当前公开采用证明不足，本页暂不采用，不将其改为患者资料缺失或未满足。</Typography>}
        <SavedValue label="原保存结果 / 强度" value={`${view.result.status} / ${view.result.appliedStrength}`}/>
        {change&&<>
            <SavedValue label="原始DNA编辑净变化" value={`${change.netNucleotideChange} nt`}/>
            <SavedValue label="序列推导的蛋白变化" value={`${change.referenceProteinLength} → ${change.alteredProteinLength} aa；净变化 ${change.netAminoAcidChange} aa`}/>
            {change.netNucleotideChangeMeaning==='EDIT_DELTA_EXCLUDING_PREDICTED_THREE_PRIME_EXTENSION'&&<Typography variant="body2">终止延长来自参考序列的有界3′预测；CDS延长不等于DNA插入，也不是实际RNA或蛋白实验。</Typography>}
        </>}
        {view.rule?<><SavedValue label="本条规则分支" value={branch}/><SavedValue label="规则条件与出处" value={view.rule}/></>
            :<SavedValue label={view.result.status==='NOT_EVALUATED'?'尚未满足的规则条件':'采用证明核对缺口'} value={view.unmetCondition||(view.result.status==='NOT_EVALUATED'?view.result.reasonCode:view.proofReasonCode)}/>}
        <SavedValue label="当前材料原因 / 证明原因" value={`${view.materialReasonCode} / ${view.proofReasonCode}`}/>
        {view.initialRouting&&<Typography variant="caption">原取证前检查是保留的初始路由，不覆盖这里的当前序列及完整比较结果。</Typography>}
        <Box component="details"><summary>核对选择背景、评价背景及公开出处</summary><Stack spacing={0.5} sx={{mt:1}}>
            <SavedValue label="所选背景 ID（v3）" value={view.selectedContextId}/><SavedValue label="实际评价背景 ID（v2）" value={view.evaluationContextId}/>
            <SavedValue label="当前材料与结果绑定" value={view.binding}/><SavedSources sources={view.sourcePins}/>
        </Stack></Box>
        <Typography variant="caption">本页核对公开内容及对应关系；摘要值本身不认证来源，实际材料与保存结果须由服务端核验。原始状态、来源和历史保持。</Typography>
    </Stack>;
}

function Pvs1CodingCurrent({view}) {
    const qualified=view.parentSourceQualifiedResearch===true&&view.sourceQualifiedResearch===true&&view.sourceQualification?.status==='VERIFIED';
    return <Stack spacing={0.75} data-testid="pvs1-coding-current-summary">
        <Stack direction="row" gap={0.75} useFlexGap flexWrap="wrap">
            <Chip size="small" variant="outlined" label={`编码资料 ${view.assessmentStatus}`}/>
            <Chip size="small" variant="outlined" label={`采用证明 ${view.proofStatus}`}/>
            <Chip size="small" variant="outlined" label={`来源资格 ${view.sourceQualification?.status}`}/>
        </Stack>
        <Typography variant="body2">资料和证明 AVAILABLE 表示公开结构可核对；来源资格须另行核实，并保留原规则、适用背景和强度上限。</Typography>
        {view.availabilityOnly&&<Typography variant="body2">原判定和强度保留；当前公开资料或来源资格不足以支持页面采用，不改为患者资料缺失或已评价未满足。</Typography>}
        <SavedValue label="原保存结果 / 强度" value={`${view.result.status} / ${view.result.appliedStrength}`}/>
        <SavedValue label="来源资格核查" value={{parentSourceQualifiedResearch:view.parentSourceQualifiedResearch,
            codingSourceQualifiedResearch:view.sourceQualifiedResearch,...view.sourceQualification}}/>
        {view.positions&&<>
            <SavedValue label="编码机制 / 坐标体系" value={`${view.mechanism} / ${view.coordinateSystem}`}/>
            <SavedValue label="变异、蛋白及提前终止位置" value={{cdsPosition:view.positions.cdsPosition,proteinPosition:view.positions.proteinPosition,
                firstChangedProteinPosition:view.positions.firstChangedProteinPosition,ptcSpan:view.positions.ptcSpan,referencePtc:view.positions.referencePtc}}/>
            <SavedValue label="归一化参考事件" value={view.positions.referenceEvent}/>
            <SavedValue label="HGVS 对应事件" value={view.positions.hgvscEvent}/>
            <SavedValue label="规则所用参考事件位置（保留顺序及重复）" value={view.referenceEventPositions}/>
            <SavedValue label="本次采用条款及条件结果" value={{rule:view.selectedRule,result:view.ruleCheck}}/>
            <SavedValue label="原条款强度 / 父规则上限 / 最终采用强度" value={view.strengthDerivation}/>
            <SavedValue label="原声明的位置条件" value={view.positionConditions}/>
            <Typography variant="body2">NOT_DECLARED 表示原政策未声明该位置条件，不是核查通过。参考提前终止位置不能由改变后位置代替；插入碱基也不补造参考坐标。</Typography>
        </>}
        <SavedValue label="当前资料原因 / 证明原因" value={`${view.assessmentReasonCode} / ${view.proofReasonCode}`}/>
        <Box component="details"><summary>核对背景、转录本模型及公开出处</summary><Stack spacing={0.5} sx={{mt:1}}>
            <SavedValue label="当前背景 ID" value={view.expectedContextId}/><SavedValue label="事实方法版本" value={view.factsMethodVersion}/>
            {view.positions&&<><SavedValue label="转录本模型事实" value={view.transcriptModelFacts}/><SavedValue label="采用范围及原件绑定" value={{scope:view.scope,bindings:view.bindings}}/>
                <SavedValue label="逐条规则结果" value={view.ruleChecks}/><SavedSources sources={view.sources}/></>}
            <SavedValue label="证明位置（相对于 pvs1Facts）" value={view.proofPointer}/>
        </Stack></Box>
        <Typography variant="caption">{qualified?'保存的双重来源资格及原有条件已对应。':'当前来源资格按保存原值保留。'}这里只读核对公开字段，不重算证据、不授予额外科学或临床资格；原件真实性与服务端实际重放仍须独立核验。</Typography>
    </Stack>;
}

/** First-screen saved decision facts; no actions, requests or score mutations. */
export default function EvidenceDecisionSummary({entry,concise=false}) {
    const view=decisionDetails(entry),limit=8;
    if(concise&&!view.issues.length&&!view.conflicts.length&&!view.comparisons.length)return null;
    return <Stack spacing={1.5} data-testid="evidence-decision-summary">
        <Typography variant="subtitle1" fontWeight={700}>判定核查与条件对照</Typography>
        {view.issues.slice(0,limit).map((issue,index)=><Alert severity={issue.severity} key={`issue:${index}`} sx={{'& .MuiAlert-message':{width:'100%',minWidth:0}}}>
            <Typography fontWeight={700}>{issue.title}</Typography>{issue.contributionPreparation ? <Stack spacing={0.75}>
                <Typography variant="body2">这是系统可信采用来源的接线缺口；不作为患者资料缺失，也不能由重复文献检索替代。</Typography>
                <SavedValue label="规则需要" value={issue.contributionPreparation.requirement}/>
                <SavedValue label="本次已有" value={issue.contributionPreparation.finding}/>
                <SavedValue label="目前情况与差异" value={issue.contributionPreparation.gap}/>
                <SavedValue label="对判定的影响" value={issue.contributionPreparation.impact}/>
                <SavedValue label="下一步系统接线" value={issue.contributionPreparation.nextStep}/>
                <SavedValue label="来源捕获状态 / 账本核验状态" value={`${issue.contributionPreparation.status} / ${issue.contributionPreparation.ledgerStatus}`}/>
                <SavedValue label="已采用 PP4 贡献（原保存值）" value={issue.detail.pp4Points}/>
                <Typography variant="caption">null 保持未知；CAPTURED 仅表示已捕获来源，不表示账本已核定或证据已采用。未选择 PP4 也不默认其贡献为零。</Typography>
            </Stack> : issue.proteinLengthProof ? <ProteinLengthCurrent view={issue.proteinLengthProof}/> : issue.pvs1CodingProof ? <Pvs1CodingCurrent view={issue.pvs1CodingProof}/> : <SavedValue label="保存信息" value={issue.detail}/>}
            {!!issue.codes?.length&&<SavedValue label="核验代码" value={issue.codes}/>}</Alert>)}
        {view.conflicts.slice(0,limit).map((conflict,index)=><Alert severity="error" key={`conflict:${index}`} sx={{'& .MuiAlert-message':{width:'100%',minWidth:0}}}>
            <Typography fontWeight={700}>{conflict.origin}</Typography><SavedValue label="冲突字段或范围" value={conflict.field}/>
            <SavedValue label={conflict.left.label} value={conflict.left.value}/><SavedSources sources={conflict.left.sources}/>
            <SavedValue label={conflict.right.label} value={conflict.right.value}/><SavedSources sources={conflict.right.sources}/>
            <SavedValue label="影响" value={conflict.impact}/><SavedValue label="原原因代码" value={conflict.reasonCode}/></Alert>)}
        {!concise&&<SavedValue label="原保存状态、强度及原因" value={view.original}/>}
        {view.comparisons.length?<Stack spacing={1}>{view.comparisons.slice(0,limit).map((row,index)=><Paper variant="outlined" sx={{p:1.5,minWidth:0}} key={`comparison:${index}`}>
            <Stack spacing={0.75}><Typography fontWeight={600}>{row.origin} · {show(row.label)}</Typography>
                <SavedValue label="规则需要" value={row.requirement}/>{row.expected!==undefined&&<SavedValue label="需要达到的条件" value={row.expected}/>}
                <SavedValue label="本次已有" value={row.known}/><SavedValue label="目前情况与差异" value={row.difference}/>
                {row.distinction&&<SavedValue label="事实边界" value={row.distinction}/>}<SavedValue label="对判定的影响" value={row.impact}/>
                <SavedValue label="下一步或核查路径" value={row.nextStep}/>{row.field!==undefined&&<SavedValue label="字段或成员范围" value={row.field}/>}
                <SavedValue label="原原因代码" value={row.reasonCodes}/><SavedSources sources={row.sources}/>
            </Stack></Paper>)}</Stack>:!concise&&<Alert severity="info">{view.fallbackNote}</Alert>}
        {(view.issues.length>limit||view.conflicts.length>limit||view.comparisons.length>limit)&&<Typography variant="caption">
            首屏每类最多展示{limit}项（问题{view.issues.length}、冲突{view.conflicts.length}、条件对照{view.comparisons.length}）；其余完整事实和出处见下方模块资料及原始结果。
        </Typography>}
        {!concise&&<Typography variant="caption" color="text.secondary">{view.scopeStatement}</Typography>}
    </Stack>;
}
