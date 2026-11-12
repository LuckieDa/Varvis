import EvidenceDecisionSummary from './EvidenceDecisionSummary';
import SavedSourceTaskLinks from './SavedSourceTaskLinks';
import SavedSourceTaskPanel from './SavedSourceTaskPanel';
import { backgroundEvidenceCards, savedBackgrounds, presentationCounts, resultPresentation, sortResultRows } from '../utils/acmgResultsWorkbench';
import Pp4PhenotypeSummary from './Pp4PhenotypeSummary';
import DeNovoCoreMaterialView from './DeNovoCoreMaterialView';
import { Pm3SavedFacts } from './Pm3LiteraturePanel';
import { Pp1SavedFacts } from './Pp1LiteraturePanel';
import Bs4FamilySummary from './Bs4FamilySummary';
import Pp1FamilySummary from './Pp1FamilySummary';
import React, { useId, useState } from 'react';
import {
    Accordion,
    Alert,
    AccordionDetails,
    AccordionSummary,
    Box,
    Button,
    Chip,
    Link,
    MenuItem,
    Paper,
    Stack,
    Typography,
    TextField,
} from '@mui/material';
import { evidenceOutcome, evidenceSummary, evidenceDetailValue, reasonHelp, localBatchScopeClaim } from '../utils/acmgInput';
import { displayStrength, hasDisplayValue, sharedDeNovoMaterials } from '../utils/evidencePresentation';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SplicingExperimentPrompt from './SplicingExperimentPrompt';
import FunctionalExperimentReview from './FunctionalExperimentReview';
import EvidenceCoreDetails from './EvidenceCoreDetails';

/** One layout for every enabled rule. Candidate/attempt scope is resolved only by the display adapter. */
export default function AcmgEvidenceMatrix({
    candidate,
    enabledCriteria,
    experimentCheck,
    experimentUnavailable,
    run,
    backgroundIndex,
    onBackgroundChange,
    showBackgroundSelector = true,
    sourceTaskContext,
}) {
    const [selectedBackground, setSelectedBackground] = useState(null);
    const viewOwner = `${run?.runId || ''}:${candidate.candidateId}`;
    const materialId = useId();
    const backgrounds = savedBackgrounds(candidate);
    const index = backgroundIndex ?? (selectedBackground?.owner === viewOwner ? selectedBackground.index : 0);
    const chooseBackground = next => { setSelectedBackground({owner:viewOwner,index:next}); onBackgroundChange?.(next); };
    const cards = backgroundEvidenceCards(candidate, enabledCriteria, run, index);
    const shared = sharedDeNovoMaterials(cards);
    const rows = sortResultRows(cards.flatMap(card => card.entries.map((entry,position)=>({card,entry,index:position,outcome:resultPresentation(entry).outcome}))));
    const counts = presentationCounts(rows);
    const ordered = [...cards].sort((a,b)=>rows.findIndex(row=>row.card===a)-rows.findIndex(row=>row.card===b));
    return (
        <Box
            sx={{
                display: 'flex',
                flexDirection: 'column',
                gap: 1,
                my: 2,
            }}
        >
            {showBackgroundSelector && <TextField select size="small" label="当前保存背景" value={index} onChange={event=>chooseBackground(Number(event.target.value))}>
                {backgrounds.map(background=><MenuItem key={background.index} value={background.index}>{background.label} · {background.identity}</MenuItem>)}
            </TextField>}
            <Typography variant="body2" data-testid="evidence-counts">
                当前背景 · {counts.map(item=>`${item.count} 项${item.label}`).join(' · ')}。四类合计 {rows.length} 项证据结果；采用及过程标签不额外计数，不合并其他背景。
            </Typography>
            {ordered.map((card) => (
                <Paper variant="outlined" key={card.id} sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                    <Stack spacing={0}>
                        {card.entries.map((entry, i) => {
                            const presentation=resultPresentation(entry);
                            return <Accordion key={`${entry.hypothesisId || 'allele'}-${i}`} disableGutters elevation={0}>
                                <AccordionSummary expandIcon={<ExpandMoreIcon />} aria-label={`${card.code} · ${presentation.mainLabel} · 查看依据`}>
                                <Stack spacing={0.5} sx={{ width: '100%', minWidth: 0 }}>
                                <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
                                    <Typography fontWeight={600}>{card.code}</Typography>
                                    <Chip
                                        label={presentation.mainLabel}
                                        color={
                                            presentation.mainKind==='ADOPTED_MET'
                                                ? 'success'
                                                : 'default'
                                        }
                                    />
                                    <Chip size="small" variant="outlined" label={presentation.adoption.label} />
                                    <Chip size="small" variant="outlined" label={presentation.progress.label} />
                                    {presentation.mainKind==='NO_CONCLUSION' && <Chip size="small" label={presentation.outcome.label} />}
                                    {presentation.mainKind==='ADOPTED_MET' && entry.evaluation?.appliedStrength && entry.evaluation.appliedStrength !== 'NONE' &&
                                        <Typography variant="body2">实际强度：{displayStrength(entry.evaluation.appliedStrength)}</Typography>}
                                </Stack>
                                <Typography variant="body2">{evidenceSummary(entry)}</Typography>
                                {card.entries.length > 1 && <Typography variant="caption">{entry.scopeLabel || `假设 ${i + 1}`} · {entry.contextLabel}</Typography>}
                                </Stack>
                                </AccordionSummary>
                                <AccordionDetails><EvidenceEntryDetails key={`${viewOwner}:${index}:${card.id}:${entry.hypothesisId || i}`} card={card} entry={entry} i={i} candidate={candidate} run={run} sourceTaskContext={sourceTaskContext}
                                    experimentCheck={experimentCheck} experimentUnavailable={experimentUnavailable}
                                    sharedMaterialAction={shared.some(group => group.entries.includes(entry)) && <Button variant="outlined" onClick={(event) => {
                                        const index = shared.findIndex(group => group.entries.includes(entry));
                                        const element = event.currentTarget.ownerDocument.getElementById(`${materialId}-${index}`);
                                        if (element) { element.open = true; element.scrollIntoView({ behavior:'smooth', block:'start' }); }
                                    }}>查看 PS2 / PM6 共用病例资料与原文依据</Button>} /></AccordionDetails>
                            </Accordion>;
                        })}
                    </Stack>
                </Paper>
            ))}
            {shared.map((group, index) => <Box component="details" id={`${materialId}-${index}`} key={index}
                sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 2, '& > summary': { cursor: 'pointer', fontWeight: 600 } }}>
                <summary>PS2 / PM6 共用文献病例资料（{group.materials.length} 份）</summary>
                <Typography variant="body2" sx={{ my: 1 }}>相同资料只展示一次；两项证据的结果分别保留，不重复计分。</Typography>
                <DeNovoCoreMaterialView materials={group.materials} />
            </Box>)}
        </Box>
    );
}

export function EvidenceEntryDetails({card,entry,i=0,candidate,run,experimentCheck,experimentUnavailable,sharedMaterialAction,sourceTaskContext,concise=false}) {
    const [sourceView, setSourceView] = useState(null);
    const owner = `${run?.analysisId}:${run?.runId}:${candidate?.candidateId}:${card.id}:${entry.hypothesisId || i}:${entry.contextLabel || ''}`;
    if (sourceView?.owner === owner) return <SavedSourceTaskPanel link={sourceView.link} run={run}
        sourceTaskContext={sourceTaskContext} onReturn={() => setSourceView(null)} />;
    if (concise) return <Stack spacing={3}>
        <EvidenceCoreDetails card={card} entry={entry} candidate={candidate}/>
        {resultPresentation(entry).mainKind==='NO_CONCLUSION' && <SavedSourceTaskLinks concise card={card} entry={entry} run={run} onOpen={link=>setSourceView({owner,link})}/>}
    </Stack>;
return <Stack spacing={1}>
                                <EvidenceDecisionSummary entry={entry} concise={concise} />
                                <SavedSourceTaskLinks card={card} entry={entry} run={run} onOpen={link => setSourceView({owner,link})} />

                                {!concise && !entry.displayIssue && <Typography variant="body2" color="text.secondary">
                                    后续处理：{evidenceOutcome(entry.evaluation).next}
                                </Typography>}
                                {entry.scopeLabel && (
                                    <Typography variant="subtitle2">{entry.scopeLabel}</Typography>
                                )}
                                {!concise && entry.contextLabel && (
                                    <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>
                                        {entry.contextLabel}
                                    </Typography>
                                )}
                                {!localBatchScopeClaim(run) && card.id !== 'ACMG_AMP_2015:BA1' &&
                                    card.id !== 'ACMG_AMP_2015:PM2' && card.id !== 'ACMG_AMP_2015:BS1' && (
                                        <SplicingExperimentPrompt
                                            candidate={candidate}
                                            evaluation={entry.evaluation}
                                            snapshot={experimentCheck}
                                            unavailable={experimentUnavailable}
                                        />
                                    )}
                                {!entry.scopeLabel && card.entries.length > 1 && (
                                    <Typography variant="caption">假设 {i + 1}</Typography>
                                )}
                                {!concise && entry.reasons.map((b, j) => reasonHelp(b.reasonCode || b, entry.reasonScope, entry.strictReasonHelp) !== evidenceSummary(entry) && (
                                    <Typography variant="caption" component="div" key={j}>
                                        {reasonHelp(b.reasonCode || b, entry.reasonScope, entry.strictReasonHelp)} · {b.reasonCode || b}
                                    </Typography>
                                ))}
                                {!entry.savedCriterion && !!entry.presentationIssues?.length && <Alert severity="error">
                                    <Typography fontWeight={700}>展示核验发现不一致，当前分值未采用</Typography>
                                    {entry.presentationIssues.map(code=><Typography key={code} variant="body2" sx={{overflowWrap:'anywhere'}}>
                                        {({ DENOVO_FACT_COMPARISON_CONTRADICTION:'来源字段对比与事件采用状态互相矛盾，请核对下方对比原值。',
                                            DENOVO_SOURCE_DISCOVERY_INVALID:'原件定位的摘要、范围或记录绑定不一致。',
                                            DENOVO_ADJUSTED_ROW_MISMATCH:'保存强度与积分及降档规则不一致。',
                                            DENOVO_PUBLIC_METHOD_BINDING_INVALID:'所选运行方法与资料绑定不一致。' })[code] || '保存字段未满足当前方法的展示校验，请核对完整字段。'} · {code}
                                    </Typography>)}
                                </Alert>}
                                {!entry.savedCriterion && entry.details.some(([label]) => label.endsWith('已核来源差异')) && <Alert severity="warning">
                                    <Typography fontWeight={700}>来源资料存在差异，本事件未采用</Typography>
                                    {entry.details.filter(([label]) => label.endsWith('已核来源差异')).map(([label,value],index) =>
                                        <Typography key={`${label}:${index}`} variant="body2" sx={{overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>
                                            {label}：{evidenceDetailValue(value)}
                                        </Typography>)}
                                </Alert>}
                                {!entry.savedCriterion && !!entry.executionDiagnostics?.length && <Alert severity="error">
                                    <Typography fontWeight={700}>来源执行与回放用途（保存声明）</Typography>
                                    {entry.executionDiagnostics.map(([label,value]) => <Typography key={label} variant="body2" sx={{overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>{label}：{evidenceDetailValue(value)}</Typography>)}
                                </Alert>}
                                {card.id === 'ACMG_AMP_2015:PP4' && <Pp4PhenotypeSummary core={entry.raw} />}
                                {card.id === 'ACMG_AMP_2015:BS4' && <Bs4FamilySummary core={entry.raw} />}
                                {card.id === 'ACMG_AMP_2015:PP1' && <Pp1FamilySummary core={entry.raw} />}
                                {card.id === 'ACMG_AMP_2015:PM3' && !entry.displayIssue && <Pm3SavedFacts value={entry.pm3LiteratureFacts} />}
                                {card.id === 'ACMG_AMP_2015:PP1' && !entry.displayIssue && <Pp1SavedFacts value={entry.pp1LiteratureFacts} criterion={entry.pp1Criterion} />}
                                {['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'].includes(card.id) && !entry.displayIssue && <>
                                    {entry.literatureDecision && entry.literatureDecision.status === entry.evaluation?.evaluationResult?.status &&
                                        entry.literatureDecision.reasonCode === entry.evaluation?.evaluationResult?.reasonCode &&
                                        <Typography variant="body2">{entry.literatureDecision.summary}</Typography>}
                                    {sharedMaterialAction || <DeNovoCoreMaterialView materials={entry.coreMaterials} />}
                                </>}
                                <Accordion variant="outlined">
                                    <AccordionSummary expandIcon={<ExpandMoreIcon />}>完整证据字段与来源标识</AccordionSummary>
                                    <AccordionDetails>
                                        <Stack spacing={1}>
                                            {entry.details.filter(([label, value]) => !label.endsWith('已核来源差异') && hasDisplayValue(value)).map(([label, value]) => (
                                                <Typography
                                                    variant="body2"
                                                    key={label}
                                                    sx={{ overflowWrap: 'anywhere' }}
                                                >
                                                    {label}：{evidenceDetailValue(value)}
                                                </Typography>
                                            ))}
                                            {entry.links?.map((link) => (
                                                <Link
                                                    key={`${link.url}:${link.label}`}
                                                    href={link.url}
                                                    target="_blank"
                                                    rel="noopener noreferrer"
                                                >
                                                    {link.label}
                                                </Link>
                                            ))}
                                            {entry.evaluation?.evaluationResult?.reasonCode && (
                                                <Typography variant="body2">
                                                    {entry.strictReasonHelp ? evidenceSummary(entry) : reasonHelp(
                                                        entry.evaluation.evaluationResult
                                                            .reasonCode,
                                                        entry.reasonScope,
                                                        entry.strictReasonHelp,
                                                    )}
                                                </Typography>
                                            )}
                                            {entry.candidateChecks && (
                                                <Accordion variant="outlined">
                                                    <AccordionSummary>候选层检查（不替代当前背景结论）</AccordionSummary>
                                                    <AccordionDetails>
                                                        <Typography>{evidenceSummary(entry.candidateChecks)}</Typography>
                                                        {entry.candidateChecks.details.filter(([, value]) => hasDisplayValue(value)).map(([label, value]) => (
                                                            <Typography variant="body2" key={label} sx={{ overflowWrap: 'anywhere' }}>
                                                                {label}：{evidenceDetailValue(value)}
                                                            </Typography>
                                                        ))}
                                                    </AccordionDetails>
                                                </Accordion>
                                            )}
                                            {card.id === 'ACMG_AMP_2015:PS3' && (
                                                <FunctionalExperimentReview
                                                    run={run}
                                                    value={entry.raw}
                                                />
                                            )}
                                        </Stack>
                                    </AccordionDetails>
                                </Accordion>

</Stack>;
}
