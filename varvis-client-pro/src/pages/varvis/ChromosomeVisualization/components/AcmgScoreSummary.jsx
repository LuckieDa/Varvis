import React from 'react';
import { Alert, Box, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { classificationLevels, classifyEvidenceScore, scoreLabel } from '../utils/acmgEvidenceScore';

export default function AcmgScoreSummary({ score, identity, backgrounds = [], backgroundIndex = 0, onBackgroundChange }) {
    if (!score && !identity) return null;
    const classification = classifyEvidenceScore(score);
    const color = score?.status === 'REVIEW' ? '#916021' : score?.status === 'STANDALONE' || score?.total < 0 ? '#277060' : '#315eaa';
    return <>
        <Paper variant="outlined" data-testid="acmg-score-summary" sx={{mt:identity ? 0 : 2,p:{xs:2,md:2.5},borderRadius:score ? '14px 14px 0 0' : 3,borderColor:'#dce5f3',background:'linear-gradient(110deg,#f0f5ff,#fff 85%)',position:identity ? {md:'sticky'} : 'relative',top:12,zIndex:2,boxShadow:identity ? '0 5px 18px rgba(38,67,109,.06)' : 'none'}}>
            <Box sx={{display:'grid',gridTemplateColumns:identity ? {xs:'1fr',sm:'minmax(0,1fr) auto'} : '1fr',gap:{xs:2,md:3},alignItems:'start'}}>
                {identity && <Box sx={{minWidth:0}} data-testid="acmg-variant-identity">
                    <Typography component="h2" sx={{m:0,fontSize:{xs:28,md:32},fontWeight:800,lineHeight:1.25,color:'#203b60',overflowWrap:'anywhere'}}>{identity.gene}</Typography>
                    <Typography data-testid="acmg-variant-coordinate" sx={{mt:1,fontSize:{xs:19,md:22},fontWeight:650,lineHeight:1.5,color:'#263f60',overflowWrap:'anywhere'}}>{identity.coordinate}</Typography>
                    <Stack direction="row" useFlexGap flexWrap="wrap" gap={1} sx={{mt:1,color:'#526882'}}>
                        {[identity.assembly,identity.transcript,identity.moi].filter(Boolean).map(value=><Typography key={value} sx={{fontSize:15,fontWeight:500}}>{value}</Typography>)}
                    </Stack>
                    {identity.hgvs && <Typography sx={{mt:.5,fontSize:16,color:'#425b79',overflowWrap:'anywhere'}}>{identity.hgvs}</Typography>}
                    {backgrounds.length > 0 && <Box sx={{mt:1.5}}>
                        <Typography sx={{fontSize:14,color:'#526882',mb:.5}}>疾病背景</Typography>
                        <Stack direction="row" useFlexGap flexWrap="wrap" gap={.75}>
                            {backgrounds.map(b=><Button key={b.index} variant={b.index===backgroundIndex ? 'contained' : 'outlined'}
                                aria-pressed={b.index===backgroundIndex} onClick={()=>onBackgroundChange?.(b.index)} disableElevation
                                sx={{fontSize:17,fontWeight:650,textTransform:'none',textAlign:'left',justifyContent:'flex-start',whiteSpace:'normal',lineHeight:1.45,py:.75,px:1.25,borderRadius:1.5,maxWidth:'100%',overflowWrap:'anywhere'}}>{b.label}</Button>)}
                        </Stack>
                    </Box>}
                </Box>}
                <Box sx={{minWidth:identity ? {sm:200} : 0,maxWidth:identity ? {sm:360} : undefined,textAlign:identity ? {xs:'left',sm:'right'} : 'left'}}>
                    <Typography sx={{fontSize:15,fontWeight:650,color:'#526882'}}>ACMG 建议分类</Typography>
                    <Typography data-testid="acmg-classification" data-classification={score ? classification.code || 'PENDING' : 'LOADING'} component="div" sx={{mt:.5,fontSize:{xs:25,md:28},fontWeight:800,color:classification.color,lineHeight:1.4}}>
                        {score ? `${classification.label}${classification.code ? ` · ${classification.code}` : ''}` : '结果读取中'}
                    </Typography>
                    {score && <Typography component="div" sx={{mt:.5,fontSize:score.total === null ? 20 : 32,fontWeight:800,lineHeight:1.4,color}}>{scoreLabel(score)}<Typography component="span" sx={{ml:1,fontSize:14,fontWeight:500,color:'#526882'}}>ACMG 得分</Typography></Typography>}
                    {score?.status === 'SCORED' && score.pm1Pp3 && <Chip label={`PM1 与 PP3 合计 +${score.pm1Pp3.points} 分`} size="small" variant="outlined" sx={{mt:1,ml:.5}} />}
                    {classification.provisional && <Chip label="证据待补全" size="small" variant="outlined" sx={{mt:1,fontSize:14,fontWeight:600,color:'#976614',borderColor:'#d9bd7c',bgcolor:'#fff8e6'}} />}
                    {score && classification.rule && <Typography data-testid="acmg-classification-rule" title={classification.rule.title || classification.rule.label} sx={{mt:1,fontSize:15,color:'#526882',overflowWrap:'anywhere'}}>判定规则：{classification.rule.label}</Typography>}
                    {classification.conflict && <Chip label="致病与良性证据冲突" size="small" variant="outlined" sx={{mt:1,fontSize:14,color:'#976614',borderColor:'#d9bd7c'}} />}
                </Box>
            </Box>
        </Paper>
        {score && <Paper variant="outlined" sx={{p:1.5,borderTop:0,borderRadius:'0 0 14px 14px',borderColor:'#dce5f3',mb:2}}>
            <Box role="list" aria-label="ACMG 五级致病性分类" sx={{display:'grid',gridTemplateColumns:'repeat(5,minmax(0,1fr))',gap:.5}}>
                {classificationLevels.map(level=><Box key={level.code} role="listitem" aria-current={classification.code===level.code ? 'step' : undefined}
                    sx={{textAlign:'center',py:1,px:.5,borderBottom:`4px solid ${level.color}`,borderRadius:'6px 6px 0 0',background:classification.code===level.code ? level.background : 'transparent',color:level.color}}>
                    <Typography sx={{fontSize:16,fontWeight:classification.code===level.code ? 750 : 500}}>{level.label}</Typography>
                    <Typography sx={{fontSize:14,fontWeight:classification.code===level.code ? 750 : 500}}>{level.code}</Typography>
                </Box>)}
            </Box>
            {score.issues.length > 0 && <Alert severity="warning" sx={{mt:1.5}}>{score.issues.join(' ')}</Alert>}
            {classification.reason && !score.issues.length && <Alert severity="warning" sx={{mt:1.5}}>{classification.reason}</Alert>}
        </Paper>}
    </>;
}
