import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, Chip, Drawer, LinearProgress, List, ListItemButton, Paper, Stack, TextField, Typography } from '@mui/material';
import { displayStrength } from '../utils/evidencePresentation';
import { backgroundEvidenceCards, resultPresentation, presentationCounts, mainResultKinds, resultStatusCounts, resultStyle, savedBackgrounds, savedVariantLabel, sortResultRows } from '../utils/acmgResultsWorkbench';
import { EvidenceEntryDetails } from './AcmgEvidenceMatrix';
import SampleCallSummary from './SampleCallSummary';
import { fetchAcmgResultBackground } from '../services/variantAnalysis';
import { createBackgroundReader, isResultIndex } from '../utils/acmgResultView';
import { classifyEvidenceScore, readScoreSummaries, representativeScore, scoreBackground, scoreEvidenceCards, scoreLabel, signedScore, sortScoredCandidates } from '../utils/acmgEvidenceScore';
import AcmgScoreSummary from './AcmgScoreSummary';
import { workbenchEvidenceCopy, workbenchVariantIdentity } from '../utils/acmgWorkbenchCopy';

const criterionNames = { BA1:'人群频率过高', BS1:'频率高于疾病预期', PM2:'低频或充分检测后未检出',
    PP3:'计算预测支持有害影响', BP4:'计算预测支持无有害影响', PS1:'相同蛋白或剪接影响', PM5:'同残基不同替换',
    BP7:'限定同义或内含子变异', PVS1:'功能缺失', PM4:'蛋白长度改变', BP3:'限定重复区框内变异',
    PM1:'热点或关键功能区域', PP2:'错义致病机制', BP1:'截短主导背景中的错义变异',
    PS2:'亲子关系已确认的新生变异', PM6:'亲子关系未确认的新生变异', PM3:'隐性背景双等位基因', PP1:'家系共分离' };
const shortCriterionNames = {PM2:'低频证据',PP3:'计算预测',BP4:'计算预测',PS1:'相同影响参考',PM5:'同位点替换参考',
    BP7:'同义与内含子变异',BP3:'重复区框内变异',PM1:'热点与关键区域',BP1:'错义变异背景',PS2:'新生变异',PM6:'新生变异',PM3:'双等位基因'};

export default function AcmgResultsWorkbench({ run, criteria, sourceTaskContext }) {
    return <Workbench key={`${run.runId}:${run.sourceViewSha256 || 'complete'}`} run={run} criteria={criteria} sourceTaskContext={sourceTaskContext} />;
}

function Workbench({ run, criteria, sourceTaskContext }) {
    const candidates = useMemo(() => run.candidates || [], [run.candidates]);
    const [selectedCandidate, setSelected] = useState(null), [selectedBackground, setBackgroundIndex] = useState(null);
    const [search, setSearch] = useState(''), [filter, setFilter] = useState('ALL'), [detail, setDetail] = useState(null);
    const readBackground = useMemo(() => createBackgroundReader(fetchAcmgResultBackground), []);
    const [loaded, setLoaded] = useState(null), [loadError, setLoadError] = useState(''), [retry, setRetry] = useState(0);
    const [sortMode, setSortMode] = useState('SCORE');
    const [scoreRequest, setScoreRequest] = useState(0), [scoreRead, setScoreRead] = useState(()=>isResultIndex(run)
        ? {state:'LOADING',done:0,total:candidates.reduce((sum,c)=>sum+savedBackgrounds(c).length,0)} : null);
    const labels = useMemo(() => candidates.map(savedVariantLabel), [candidates]);
    const lazy = isResultIndex(run);
    const completeScores = useMemo(() => lazy ? [] : candidates.map(c => representativeScore(c,
        savedBackgrounds(c).map(b => scoreBackground(c, criteria, run, b.index)))), [lazy,candidates,criteria,run]);
    useEffect(() => {
        if (!lazy) return undefined;
        let cancelled = false;
        setScoreRead({state:'LOADING',done:0,total:candidates.reduce((sum,c)=>sum+savedBackgrounds(c).length,0)});
        readScoreSummaries(run, criteria, readBackground, progress=>{
            if (!cancelled) setScoreRead({state:'LOADING',...progress});
        }, ()=>!cancelled).then(value=>{
            if (!cancelled) setScoreRead({state:value.failures.length ? 'FAILED' : 'READY',...value});
        }).catch(error=>{if(!cancelled) setScoreRead({state:'FAILED',failures:[{message:error.message || '读取失败'}]});});
        return ()=>{cancelled=true;};
    }, [lazy,scoreRequest,run,criteria,readBackground,candidates]);
    const scores = useMemo(()=>lazy ? scoreRead?.scores || [] : completeScores,[lazy,scoreRead,completeScores]);
    const rankingReady = !lazy || scoreRead?.state === 'READY';
    const orderedCandidates = useMemo(() => sortScoredCandidates(candidates,scores,rankingReady ? sortMode : 'ORIGINAL'), [candidates,scores,rankingReady,sortMode]);
    const selected = selectedCandidate ?? orderedCandidates[0]?.index ?? 0;
    const candidate = candidates[selected];
    const backgrounds = useMemo(() => candidate ? savedBackgrounds(candidate) : [], [candidate]);
    const backgroundIndex = selectedBackground ?? (sortMode==='SCORE' && rankingReady ? scores[selected]?.backgroundIndex ?? 0 : 0);
    const changeSort = mode => {
        setSelected(selected); setBackgroundIndex(backgroundIndex);
        setSortMode(mode);
    };
    useEffect(() => {
        if (!lazy || !candidate) return undefined;
        let cancelled = false;
        setLoaded(null); setLoadError('');
        readBackground(run,candidate.candidateId,backgroundIndex).then(value => {
            if (!cancelled) setLoaded(value);
        }).catch(error => { if (!cancelled) setLoadError(error?.response?.data?.message || error.message || '读取失败'); });
        return () => { cancelled = true; };
    }, [run, candidate, backgroundIndex, lazy, readBackground, retry]);
    // Selection identity is checked during render too: never flash a previous background before effect cleanup.
    const ready = !lazy || (loaded?.candidateId === candidate?.candidateId && loaded?.backgroundIndex === backgroundIndex);
    const evidenceRun = lazy ? loaded : run;
    const evidenceCandidate = lazy ? loaded?.candidates?.[0] : candidate;
    const cards = useMemo(() => ready && evidenceCandidate ? backgroundEvidenceCards(evidenceCandidate, criteria, evidenceRun, backgroundIndex) : [], [ready,evidenceCandidate,criteria,evidenceRun,backgroundIndex]);
    const currentScore = useMemo(()=>ready ? scoreEvidenceCards(cards,
        backgrounds[backgroundIndex]?.attempt?.context || backgrounds[backgroundIndex]?.attempt?.bs1Context || backgrounds[backgroundIndex]?.attempt?.ba1Context) : null,
        [ready,cards,backgrounds,backgroundIndex]);
    const pointByCode = new Map((currentScore?.items || []).map(item=>[item.code,item]));
    const rows = sortResultRows(cards.flatMap(card => card.entries.map((entry, index) => {
        const presentation = resultPresentation(entry);
        return { card, entry, index, outcome: presentation.outcome, presentation, copy:workbenchEvidenceCopy(card,entry,presentation) };
    })));
    const counts = presentationCounts(rows), preciseCounts = resultStatusCounts(rows);
    const visible = rows.filter(row => filter === 'ALL' || filter === `MAIN:${row.presentation.mainKind}` || filter === `DETAIL:${row.outcome.kind}`);
    const filterLabel = filter.startsWith('MAIN:') ? mainResultKinds.find(item => `MAIN:${item.kind}` === filter)?.label : resultStyle(filter.replace('DETAIL:', '')).label;
    const detailStyle = resultStyle(detail?.outcome.kind);
    const identity = workbenchVariantIdentity(candidate,backgroundIndex);
    if (!candidate) return <Alert severity="info">本次没有已保存的变异结果，可查看原始 JSON。</Alert>;
    const chooseVariant = index => { setSelected(index); setBackgroundIndex(sortMode==='SCORE' && rankingReady ? scores[index]?.backgroundIndex || 0 : 0); setFilter('ALL'); setDetail(null); };
    return <Box data-testid="acmg-results-workbench" sx={{ display:'grid', gridTemplateColumns:{xs:'1fr',md:'280px minmax(0, 1fr)'}, gap:2, alignItems:'start','& .MuiTypography-body2':{fontSize:16},'& .MuiTypography-caption':{fontSize:14},'& .MuiChip-label':{fontSize:14} }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" gap={2} useFlexGap flexWrap="wrap" sx={{gridColumn:'1 / -1'}}>
            <Typography variant="h6" fontWeight={700}>变异证据排序</Typography>
            <Stack direction="row" gap={1} role="group" aria-label="变异排序方式">
                <Button variant={sortMode==='ORIGINAL' ? 'contained' : 'outlined'} aria-pressed={sortMode==='ORIGINAL'} onClick={()=>changeSort('ORIGINAL')} disableElevation>原始候选排序</Button>
                <Button variant={sortMode==='SCORE' ? 'contained' : 'outlined'} aria-pressed={sortMode==='SCORE'} onClick={()=>changeSort('SCORE')} disableElevation>ACMG得分排序</Button>
            </Stack>
        </Stack>
        {scoreRead?.state==='LOADING' && <Box role="status" sx={{gridColumn:'1 / -1'}}>
            <Typography variant="body2">正在汇总 ACMG 得分 · {scoreRead.done} / {scoreRead.total} 个背景</Typography>
            <LinearProgress variant="determinate" value={scoreRead.total ? 100*scoreRead.done/scoreRead.total : 0} sx={{mt:1}} />
        </Box>}
        {scoreRead?.state==='FAILED' && <Alert severity="error" sx={{gridColumn:'1 / -1'}} action={<Button onClick={()=>setScoreRequest(value=>value+1)}>重新读取</Button>}>
            {scoreRead.failures.length} 个背景得分读取失败，当前保留原始候选顺序。{scoreRead.failures[0]?.message}
        </Alert>}
        <Paper variant="outlined" component="nav" aria-label="选择保存的变异" sx={{ p:1.5, borderRadius:2, position:{md:'sticky'}, top:12 }}>
            <Typography fontWeight={700} sx={{ mb:1 }}>变异结果 <Typography component="span" color="text.secondary">{candidates.length}</Typography></Typography>
            <TextField fullWidth size="small" label="搜索基因或坐标" value={search} onChange={e=>setSearch(e.target.value)} />
            <List sx={{ maxHeight:{xs:220,md:'70vh'}, overflow:'auto', mt:1 }}>
                {orderedCandidates.map(({candidate:c,index,score},position)=>({c,index,score,position,classification:classifyEvidenceScore(score),sideIdentity:workbenchVariantIdentity(c,score?.backgroundIndex ?? 0)})).filter(({c,index}) => `${labels[index].gene} ${labels[index].allele} ${c.candidateId}`.toLowerCase().includes(search.toLowerCase())).map(({c,index,score,position,classification,sideIdentity}) =>
                    <ListItemButton key={c.candidateId} selected={index === selected} aria-current={index === selected ? 'true' : undefined} onClick={()=>chooseVariant(index)}
                        sx={{ display:'block', p:1.5, mb:0.5, borderRadius:1.5, border:'1px solid', borderColor:index === selected ? '#b6ccee' : 'transparent', '&.Mui-selected':{background:'#eef5ff'} }}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" gap={1} useFlexGap flexWrap="wrap">
                            <Typography sx={{fontSize:18,fontWeight:750}} color={index === selected ? 'primary' : 'text.primary'}>#{sortMode==='SCORE' && rankingReady ? position+1 : c.rank ?? index+1} · {sideIdentity.gene || labels[index].gene}</Typography>
                            <Chip size="small" label={scoreLabel(score)} sx={{borderRadius:1,fontWeight:750,'& .MuiChip-label':{fontSize:16},color:score?.total<0 ? '#277060' : '#315eaa',bgcolor:'#edf3ff'}} />
                        </Stack>
                        <Typography sx={{fontSize:15,lineHeight:1.55,mt:.5,overflowWrap:'anywhere',color:'#344f70'}}>{sideIdentity.coordinate}</Typography>
                        {score && <Chip size="small" label={`${classification.label}${classification.provisional && classification.code ? ' · 待补全' : ''}`} sx={{mt:.5,borderRadius:1,fontWeight:600,color:classification.color,bgcolor:classification.background}} />}
                        {score && <Typography title={savedBackgrounds(c)[score.backgroundIndex]?.label} sx={{mt:.75,fontSize:15,lineHeight:1.5,color:'#526982',display:'-webkit-box',WebkitLineClamp:2,WebkitBoxOrient:'vertical',overflow:'hidden'}}>{savedBackgrounds(c)[score.backgroundIndex]?.label}</Typography>}
                        <Typography sx={{mt:.75,fontSize:13,color:'#63758c'}}>{sortMode==='SCORE' && rankingReady ? `原排名 #${c.rank ?? index+1} · ` : ''}{savedBackgrounds(c).length} 个背景</Typography>
                    </ListItemButton>)}
                {!labels.some((l,i)=>`${l.gene} ${l.allele} ${candidates[i].candidateId}`.toLowerCase().includes(search.toLowerCase())) && <Typography sx={{p:1}}>没有匹配的变异</Typography>}
            </List>
        </Paper>
        <Paper variant="outlined" component="section" aria-label="当前背景证据" sx={{ p:{xs:1.5,md:2.5}, borderRadius:3, minWidth:0, borderColor:'#dbe5f3', boxShadow:'0 5px 24px rgba(40,72,120,.04)' }}>
            <AcmgScoreSummary score={currentScore} identity={{...identity,gene:identity.gene || labels[selected].gene}} backgrounds={backgrounds} backgroundIndex={backgroundIndex}
                onBackgroundChange={index=>{setSelected(selected);setBackgroundIndex(index);setFilter('ALL');setDetail(null);}} />
            <SampleCallSummary candidate={candidate} />
            {!ready && <Alert severity={loadError ? 'error' : 'info'} sx={{mt:2}} role="status">
                {loadError ? `当前背景证据读取失败：${loadError}` : '正在读取当前背景的证据与出处…'}
                {loadError && <Button onClick={()=>setRetry(value=>value+1)}>重新读取</Button>}
            </Alert>}
            {ready && <>
            <Box role="group" aria-label="按证据状态筛选" sx={{display:'grid',gridTemplateColumns:{xs:'repeat(2,minmax(0,1fr))',sm:'repeat(auto-fit,minmax(136px,1fr))'},gap:1,mt:1,mb:2}}>
                {counts.map(item=>
                    <Button key={item.kind} data-testid={`status-filter-${item.kind}`} variant="outlined" aria-label={`${item.label} ${item.count} 项`} aria-pressed={filter===`MAIN:${item.kind}`}
                        onClick={()=>setFilter(filter===`MAIN:${item.kind}`?'ALL':`MAIN:${item.kind}`)} sx={{p:1.3,borderColor:filter===`MAIN:${item.kind}`?item.color:item.border,background:item.background,color:item.color,display:'block',textAlign:'left',textTransform:'none',borderRadius:2,boxShadow:filter===`MAIN:${item.kind}`?`inset 0 0 0 1px ${item.color}`:'none','&:hover':{background:item.background,borderColor:item.color},'&:focus-visible':{outline:'2px solid #315fa9',outlineOffset:2}}}>
                        <Typography component="span" display="block" fontSize={28} fontWeight={750} lineHeight={1.3}>{item.count}</Typography><Typography component="span" sx={{fontSize:14,fontWeight:600}}>{item.label}</Typography>
                    </Button>)}
            </Box>
            <Box component="details" sx={{my:1.5}}><summary style={{cursor:'pointer',fontSize:15}}>按原因筛选</summary>
                <Stack direction="row" gap={1} useFlexGap flexWrap="wrap" sx={{mt:1}}>
                    {preciseCounts.filter(item=>item.count>0).map(item=><Button key={item.kind} size="small" variant={filter===`DETAIL:${item.kind}`?'contained':'outlined'}
                        aria-pressed={filter===`DETAIL:${item.kind}`} onClick={()=>setFilter(filter===`DETAIL:${item.kind}`?'ALL':`DETAIL:${item.kind}`)}>
                        {item.label} {item.count}
                    </Button>)}
                </Stack>
            </Box>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{mb:1}}>
                <Typography sx={{fontSize:18,fontWeight:750}}>证据结果 · {criteria.length} 条{filter!=='ALL' ? ` · ${filterLabel} ${visible.length} 项` : ''}</Typography>
                <Button size="small" onClick={()=>setFilter('ALL')} disabled={filter==='ALL'}>显示全部</Button>
            </Stack>
            </>}
            <Stack spacing={1}>
                {visible.map(row=><Box key={`${row.card.id}:${row.index}`} data-testid="evidence-result-row" sx={{display:'grid',gridTemplateColumns:{xs:'minmax(0,1fr) auto',sm:'120px minmax(0,1fr) auto'},gap:1.5,p:2,border:'1px solid #e0e8f2',borderRadius:2,alignItems:'center',borderLeft:`3px solid ${resultStyle(row.outcome.kind).color}`,background:'#fff','&:hover':{borderColor:resultStyle(row.outcome.kind).border,boxShadow:'0 3px 14px rgba(35,64,110,.06)'}}}>
                    <Box><Typography sx={{fontSize:20,fontWeight:800}} color="#233e65">{row.card.code}</Typography><Typography sx={{fontSize:14,lineHeight:1.5,mt:.5}} color="text.secondary">{shortCriterionNames[row.card.code] || criterionNames[row.card.code] || row.card.code}</Typography></Box>
                    <Box sx={{minWidth:0,gridColumn:{xs:'1 / -1',sm:'auto'},gridRow:{xs:2,sm:'auto'}}}>
                        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
                            <Chip size="small" label={row.copy.label} sx={{background:resultStyle(row.outcome.kind).background,color:resultStyle(row.outcome.kind).color,borderRadius:1,fontWeight:650}} />
                            {row.presentation.mainKind==='ADOPTED_MET' && <Typography sx={{fontSize:15,fontWeight:700,color:'#354d70'}}>
                                {displayStrength(row.entry.evaluation.appliedStrength)}{pointByCode.has(row.card.code) ? pointByCode.get(row.card.code).standalone ? ' · 独立良性' : ` · ${signedScore(pointByCode.get(row.card.code).points)} 分` : ''}
                            </Typography>}
                        </Stack>
                        {row.copy.summary && <Typography sx={{fontSize:16,lineHeight:1.7,mt:.75,overflowWrap:'anywhere',color:'#354c69'}}>{row.copy.summary}</Typography>}
                    </Box>
                    <Button variant="outlined" onClick={()=>setDetail(row)} aria-label={`${row.card.code} 详情`} sx={{gridColumn:{xs:2,sm:'auto'},gridRow:{xs:1,sm:'auto'},minWidth:64,borderColor:'#cddcf1',borderRadius:1.5}}>详情</Button>
                </Box>)}
                {ready && !visible.length && <Alert severity="info">当前背景没有此状态的证据。</Alert>}
            </Stack>
        </Paper>
        <Drawer anchor="right" open={Boolean(detail)} onClose={()=>setDetail(null)} sx={{zIndex:theme=>theme.zIndex.modal+1}} BackdropProps={{sx:{backgroundColor:'rgba(26,45,76,.32)',backdropFilter:'blur(3px)'}}}
            PaperProps={{role:'dialog','aria-modal':true,'aria-labelledby':'evidence-detail-title',sx:{width:{xs:'100%',sm:800},maxWidth:{xs:'100%',sm:'calc(100% - 32px)'},height:{xs:'100%',sm:'calc(100% - 32px)'},m:{xs:0,sm:2},borderRadius:{xs:0,sm:3},border:'1px solid #dbe5f3',background:'#f6f9fd',boxShadow:'0 16px 70px rgba(21,43,79,.23)'}}}>
            {detail && <>
                <Box sx={{position:'sticky',top:0,zIndex:1,backgroundColor:detailStyle.color,backgroundImage:'linear-gradient(115deg,transparent,rgba(255,255,255,.14))',color:'#fff',p:{xs:2,sm:2.5}}}>
                    <Stack direction="row" justifyContent="space-between" alignItems="center" gap={1} useFlexGap flexWrap="wrap">
                        <Typography sx={{fontSize:15,fontWeight:600}}>证据详情</Typography>
                        <Button size="small" onClick={()=>setDetail(null)} sx={{color:'#fff',border:'1px solid #ffffff90',background:'#ffffff12'}}>关闭并返回</Button>
                    </Stack>
                    <Typography id="evidence-detail-title" variant="h5" fontWeight={700} sx={{mt:1}}>{detail.card.code} <Typography component="span" fontWeight={400} fontSize={17}>· {criterionNames[detail.card.code] || '证据详情'}</Typography></Typography>
                    <Typography sx={{mt:1,fontSize:23,fontWeight:750,color:'#fff'}}>{identity.gene || labels[selected].gene}</Typography>
                    <Typography sx={{mt:.5,fontSize:18,color:'#fff',overflowWrap:'anywhere'}}>{identity.coordinate}</Typography>
                    {identity.hgvs&&<Typography sx={{mt:.5,fontSize:16,color:'#fff',overflowWrap:'anywhere'}}>{identity.hgvs}</Typography>}
                    <Typography sx={{mt:.5,fontSize:16,color:'rgba(255,255,255,.9)'}}>{backgrounds[backgroundIndex]?.label}</Typography>
                </Box>
                <Stack spacing={2} sx={{p:2.5,overflowWrap:'anywhere'}}>
                    <Paper variant="outlined" sx={{p:2.5,borderRadius:2.5,borderColor:detailStyle.border,borderTop:`4px solid ${detailStyle.color}`,background:`linear-gradient(130deg,${detailStyle.background},#fff)`}}>
                        <Stack direction="row" gap={1} useFlexGap flexWrap="wrap" alignItems="center"><Chip label={detail.presentation.mainLabel} sx={{color:detailStyle.color,background:detailStyle.background,fontWeight:700,border:'1px solid',borderColor:detailStyle.border}} />
                            {detail.presentation.mainKind==='ADOPTED_MET' && detail.entry.evaluation?.appliedStrength && detail.entry.evaluation.appliedStrength!=='NONE' && <Typography fontWeight={700}>实际强度：{displayStrength(detail.entry.evaluation.appliedStrength)}</Typography>}
                        </Stack>
                        <Typography sx={{mt:1.5,fontSize:17,fontWeight:600,lineHeight:1.8}}>{detail.copy.summary || detail.copy.label}</Typography>
                    </Paper>
                    <Box sx={{px:1}}><Typography sx={{fontSize:16,color:'#526882'}}>{[identity.assembly,identity.transcript,identity.moi].filter(Boolean).join(' · ')}</Typography></Box>
                    <SampleCallSummary candidate={evidenceCandidate} />
                    <Paper variant="outlined" sx={{p:{xs:2,sm:3},borderRadius:2.5,borderColor:'#e1e5eb'}}>
                        <EvidenceEntryDetails key={`${evidenceRun?.runId}:${evidenceCandidate?.candidateId}:${backgroundIndex}:${detail.card.id}:${detail.index}`} concise sourceTaskContext={sourceTaskContext} card={detail.card} entry={detail.entry} i={detail.index} candidate={evidenceCandidate} run={evidenceRun} />
                    </Paper>
                </Stack>
            </>}
        </Drawer>
    </Box>;
}
