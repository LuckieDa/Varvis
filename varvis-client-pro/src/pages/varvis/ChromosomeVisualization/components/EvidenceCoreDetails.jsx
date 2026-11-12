import React, { useEffect, useState } from 'react';
import { Alert, Box, Chip, CircularProgress, Link, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import axios from 'axios';
import { buildEvidenceDetailView } from '../utils/evidenceDetailView';

const sourceCache=new Map();
const sourceIdentity=({pmcid,url,locator,quote,figure})=>
    JSON.stringify([pmcid||url,locator,quote,figure?.sourceSha256,figure?.reviewSha256,figure?.caseLabel]);
const docxLocator=/^attachment:[a-f0-9]{64}:word\/document\.xml:\/document\/body\/(?:p\[[1-9]\d*\]|tbl\[[1-9]\d*\]\/tr\[[1-9]\d*\]\/tc\[[1-9]\d*\])$/;
const sha256=/^[a-f0-9]{64}$/;
const reviewedFigure=figure=>Boolean(figure&&sha256.test(figure.sourceSha256)&&sha256.test(figure.reviewSha256)&&
    typeof figure.caseLabel==='string'&&figure.caseLabel.trim()&&figure.caseLabel.length<=256);

export function renderableLiteratureReferences(sources) {
    const refs=new Map();
    for(const {pmcid,locator,quote,figure} of sources){
        if(!/^PMC[1-9]\d*$/.test(pmcid||'')||typeof locator!=='string'||!locator.trim()||locator.length>2048||
            typeof quote!=='string'||!quote.trim()||quote.length>12000)continue;
        const ref={pmcid,locator,quote};
        if(reviewedFigure(figure))ref.figure=figure;
        else if(!(locator.startsWith('/article[1]/')||docxLocator.test(locator)))continue;
        refs.set(sourceIdentity(ref),ref);
    }
    return [...refs.values()];
}
const assetUrl=(value,pmcid,kind,figure)=>{
    if(typeof value!=='string'||!/^PMC[1-9][0-9]*$/.test(pmcid||''))return null;
    const key=kind==='image'&&reviewedFigure(figure)?figure.sourceSha256:'[a-f0-9]{24}';
    const prefix=`/variant-analyses/acmg/source-view/papers/${pmcid}/${key}/`;
    const suffix=kind==='image'?'regions/[a-f0-9]{64}\\.png':'fulltext(?:#source-[a-f0-9]{20})?';
    return new RegExp(`^${prefix}${suffix}$`).test(value)?axios.getUri({url:value}):null;
};

export function evidenceSourceRows(sources,regions=[],failedImages=new Set()) {
    const displayed=new Set();
    return (sources||[]).flatMap(source=>{
        const region=regions.find(r=>sourceIdentity(r)===sourceIdentity(source));
        const imageUrl=assetUrl(region?.imageUrl,source.pmcid,'image',source.figure);
        const showImage=Boolean(imageUrl&&!failedImages.has(imageUrl));
        const key=showImage?imageUrl:sourceIdentity(source);
        if(displayed.has(key))return [];displayed.add(key);
        return [{source,key,imageUrl,showImage,fullTextUrl:assetUrl(region?.fullTextUrl,source.pmcid,'fulltext')||source.url,
            location:region?.locationLabel||source.location||'原文段落'}];
    });
}

export function cacheableSourceRegions(refs,regions) {
    return refs.every(ref=>regions.some(row=>sourceIdentity(row)===sourceIdentity(ref)&&
        row.status==='AVAILABLE'&&assetUrl(row.imageUrl,ref.pmcid,'image',ref.figure)));
}

function lookupSources(refs) {
    if(!refs.length)return Promise.resolve([]);
    const key=JSON.stringify(refs);
    if(sourceCache.has(key))return sourceCache.get(key);
    if(sourceCache.size>=96)sourceCache.delete(sourceCache.keys().next().value);
    const request=(async()=>{const regions=[];
        // Keep successful batches when another article or a supplement cannot render.
        for(let i=0;i<refs.length;i+=24){
            try{
                const r=await axios.post('/variant-analyses/acmg/source-view/regions',{references:refs.slice(i,i+24)},{timeout:60000});
                if(Array.isArray(r.data?.regions))regions.push(...r.data.regions);
            }catch(_){ /* The affected references keep their original citations. */ }
        }
        if(!cacheableSourceRegions(refs,regions))sourceCache.delete(key);
        return regions;
    })().catch(()=>{sourceCache.delete(key);return [];});
    sourceCache.set(key,request);return request;
}

function FactTable({rows}) {
    if(!rows?.length)return null;
    return <Table size="small" aria-label="核心判定资料" sx={{tableLayout:'fixed','& td':{borderColor:'#e5e7eb',py:1.4,fontSize:16,lineHeight:1.7,verticalAlign:'top'},'& tr:last-child td':{borderBottom:0}}}>
        <TableBody>{rows.map((row,i)=><TableRow key={`${row.label}:${i}`}>
            <TableCell sx={{width:'34%',color:'#526278',fontWeight:600,pl:0}}>{row.href
                ? <Link href={row.href} target="_blank" rel="noopener noreferrer">{row.label}</Link> : row.label}</TableCell>
            <TableCell sx={{pr:0,color:'#243b59',overflowWrap:'anywhere',whiteSpace:'pre-line'}}>{row.value}
                {row.state&&<Chip size="small" label={row.state} variant="outlined" sx={{ml:1,color:'#94651e',borderColor:'#d9c49f',fontSize:13}} />}
            </TableCell>
        </TableRow>)}</TableBody>
    </Table>;
}

function Sources({sources,grouped=false,linkTitles=false}) {
    const [regions,setRegions]=useState([]),[loading,setLoading]=useState(false),[failedImages,setFailedImages]=useState(new Set());
    const identity=JSON.stringify(sources||[]);
    useEffect(()=>{let current=true;setRegions([]);setFailedImages(new Set());
        const refs=renderableLiteratureReferences(JSON.parse(identity));
        if(!refs.length){setLoading(false);return ()=>{current=false;};}
        setLoading(true);lookupSources(refs).then(rows=>{if(current){setRegions(rows);setLoading(false);}});
        return ()=>{current=false;};
    },[identity]);
    if(!sources?.length)return null;
    const rows=evidenceSourceRows(sources,regions,failedImages);
    const fullTexts=new Map();
    if(grouped)for(const {source,fullTextUrl,showImage} of rows){
        if(!fullTextUrl)continue;
        const id=source.pmcid||source.pmid||source.url||fullTextUrl;
        const priority=showImage?(source.locator.startsWith('attachment:')?2:1):0;
        if(!fullTexts.has(id)||priority>fullTexts.get(id).priority)
            fullTexts.set(id,{url:fullTextUrl,priority,label:source.pmcid||(source.pmid?`PMID ${source.pmid}`:source.title)});
    }
    return <Stack spacing={1.75} data-testid="evidence-original-sources">
        {grouped&&<Stack direction="row" justifyContent="space-between" alignItems="baseline" gap={1} useFlexGap flexWrap="wrap">
            <Typography sx={{fontSize:17,fontWeight:650}}>原文依据</Typography>
            <Stack direction="row" gap={1.5} useFlexGap flexWrap="wrap">
                {[...fullTexts].map(([id,{url,label}])=><Link key={id} href={url} target="_blank" rel="noopener noreferrer" sx={{fontSize:15,fontWeight:600}}>
                    {fullTexts.size===1?'查看完整原文':label}
                </Link>)}
            </Stack>
        </Stack>}
        {loading&&<Stack direction="row" spacing={1} alignItems="center" role="status"><CircularProgress size={18}/><Typography sx={{fontSize:15,color:'#526278'}}>正在准备原文截图…</Typography></Stack>}
        {rows.filter(row=>!grouped||row.showImage||row.source.quote).map(({source,key,imageUrl,showImage,fullTextUrl:sourceFullText,location},i)=>{
            return <Box key={`${key}:${i}`} sx={{minWidth:0}}>
                <Stack direction="row" gap={1} justifyContent="space-between" useFlexGap flexWrap="wrap" alignItems="baseline" sx={{mb:1}}>
                    {linkTitles&&sourceFullText&&!source.quote?
                        <Link href={sourceFullText} target="_blank" rel="noopener noreferrer" sx={{fontSize:15,fontWeight:650}}>{source.title}</Link>:
                        <>
                            <Typography sx={{fontSize:15,fontWeight:650,color:'#415975'}}>{grouped?
                                (fullTexts.size>1&&source.pmcid?`${source.pmcid} · ${location}`:location):`${source.title}${source.location?` · ${source.location}`:''}`}</Typography>
                            {!grouped&&sourceFullText&&<Link href={sourceFullText} target="_blank" rel="noopener noreferrer" sx={{fontSize:15,fontWeight:600}}>查看完整原文</Link>}
                        </>}
                </Stack>
                {showImage?<Box component="img" src={imageUrl} alt={`${source.title} · ${location}`} loading="lazy"
                    onError={()=>setFailedImages(current=>new Set([...current,imageUrl]))}
                    sx={{display:'block',width:'100%',height:'auto',border:'1px solid #e5e7eb',borderRadius:1.5,background:'#fff'}} />
                    :!loading&&source.quote&&<Box sx={{px:2,py:1.5,bgcolor:'#f6f8fb',borderRadius:1.5}}>
                        <Typography component="div" sx={{fontSize:14,fontWeight:650,color:'#795826',mb:0.5}}>原文截图暂不可用</Typography>
                        <Typography component="blockquote" sx={{m:0,fontSize:16,lineHeight:1.8,whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{source.quote}</Typography>
                    </Box>}
            </Box>;
        })}
    </Stack>;
}

function Requirements({view}) {
    return <Box><Typography component="h3" sx={{m:0,fontSize:18,fontWeight:700,color:'#263e5d'}}>规则需要</Typography>
        <Typography sx={{mt:1,fontSize:16,lineHeight:1.8}}>{view.requirement}</Typography></Box>;
}

const ruleBadge = {
    '满足':{color:'#245941',bgcolor:'#e8f4ed'},
    '未满足':{color:'#9b3435',bgcolor:'#fbecec'},
    '资料冲突':{color:'#9b3435',bgcolor:'#fbecec'},
    '待核实':{color:'#7c5520',bgcolor:'#fbf1df'},
    '明细不全':{color:'#7c5520',bgcolor:'#fbf1df'},
};

function RuleComparison({title, rows}) {
    if (!rows?.length) return null;
    return <Box data-testid="evidence-rule-actual-comparison">
        {title && <Typography component="h3" sx={{m:0,mb:1,fontSize:18,fontWeight:700,color:'#263e5d'}}>{title}</Typography>}
        <Table size="small" aria-label={title || '规则条件对照'} sx={{tableLayout:'fixed','& th,& td':{fontSize:16,lineHeight:1.7,px:1,py:1.25,borderColor:'#e5e7eb',verticalAlign:'top',overflowWrap:'anywhere'}}}>
            <TableHead><TableRow>
                <TableCell sx={{width:'28%',fontWeight:700}}>核查项目</TableCell>
                <TableCell sx={{width:'34%',fontWeight:700}}>规则要求</TableCell>
                <TableCell sx={{fontWeight:700}}>当前变异</TableCell>
            </TableRow></TableHead>
            <TableBody>{rows.map((row,i)=>{
                const status=row.outcome||'待核实';
                return <TableRow key={`${row.label}:${i}`} data-rule-conflict={row.conflict||undefined}
                    sx={row.conflict?{backgroundColor:'#fff1f0','& td':{color:'#a12d2b',fontWeight:650}}:undefined}>
                    <TableCell component="th" scope="row" sx={{fontWeight:600}}>{row.label}</TableCell>
                    <TableCell>{row.requirement}</TableCell>
                    <TableCell><Stack direction="row" alignItems="baseline" gap={0.75} useFlexGap flexWrap="wrap">
                        <Box component="span" sx={{whiteSpace:'pre-line'}}>{row.actual||'未保存'}</Box>
                        <Box component="span" data-rule-outcome={status} sx={{display:'inline-block',px:0.85,py:0.1,borderRadius:1,
                            whiteSpace:'nowrap',fontSize:13,fontWeight:650,...(ruleBadge[status]||{color:'#526278',bgcolor:'#eef2f6'})}}>{status}</Box>
                    </Stack></TableCell>
                </TableRow>;
            })}</TableBody>
        </Table>
    </Box>;
}

function ClinicalDetails({view}) {
    if(!view)return null;
    const content=<Stack spacing={2} data-testid="saved-clinical-detail">
        <Typography component="h3" sx={{m:0,fontSize:18,fontWeight:700,color:'#263e5d'}}>{view.title}</Typography>
        <FactTable rows={view.rows}/>
        {!!view.features.length&&<Table size="small" aria-label="本次病例临床表型" sx={{tableLayout:'fixed','& th,& td':{borderColor:'#e5e7eb',fontSize:16,lineHeight:1.7,py:1.25,px:1,verticalAlign:'top',overflowWrap:'anywhere'},'& tr:last-child td':{borderBottom:0}}}>
            <TableHead><TableRow><TableCell sx={{width:'50%',fontWeight:700}}>临床表型</TableCell><TableCell sx={{width:'20%',fontWeight:700}}>有无</TableCell><TableCell sx={{fontWeight:700}}>该项起病年龄</TableCell></TableRow></TableHead>
            <TableBody>{view.features.map((feature,i)=><TableRow key={`${feature.id}:${i}`}><TableCell>{feature.label&&<Typography sx={{fontSize:16,lineHeight:1.7}}>{feature.label}</Typography>}<Typography sx={{fontSize:feature.label?14:16,color:feature.label?'#526278':'inherit',lineHeight:1.7}}>{feature.id}</Typography></TableCell><TableCell>{feature.presence}</TableCell><TableCell>{feature.onset||'—'}</TableCell></TableRow>)}</TableBody>
        </Table>}
        {view.coverage&&<Typography sx={{fontSize:15,color:'#94651e'}}>表型记录展示范围：{view.coverage}</Typography>}
        {view.comparisons.map((row,i)=><Box key={`clinical-condition:${i}`}><FactTable rows={[
            {label:'规则需要',value:row.requirement},{label:'已有信息',value:row.known},{label:row.gapLabel||'缺少信息',value:row.gap},
            {label:'结论',value:row.conclusion},{label:'建议',value:row.suggestion}].filter(r=>r.value)}/></Box>)}
    </Stack>;
    return view.status==='CONFLICT'?<Alert severity="error" sx={{borderRadius:2,'& .MuiAlert-message':{width:'100%'}}}>{content}</Alert>:content;
}

/** User-facing saved facts only; no JSON widgets, scoring, model calls or task resumption. */
export default function EvidenceCoreDetails({card,entry,candidate}) {
    const matching=(candidate?.attempts||[]).filter(a=>a.hypothesisId&&a.hypothesisId===entry.hypothesisId);
    const context=matching.length===1?matching[0].context:undefined;
    const view=buildEvidenceDetailView(card,entry,context,{candidateId:candidate?.candidateId});
    if(!view)return null;
    const comparisons=view.comparisons.filter(row=>row.requirement&&row.known);
    return <Stack spacing={3} data-testid={`evidence-core-details-${view.code}`}>
        {view.conflicts.map((conflict,i)=><Alert severity="error" key={`conflict:${i}`} sx={{borderRadius:2,'& .MuiAlert-message':{width:'100%',minWidth:0}}}>
            <Typography sx={{fontSize:18,fontWeight:700,mb:1}}>{conflict.title}{conflict.field?` · ${conflict.field}`:''}</Typography>
            <FactTable rows={[{label:conflict.left.label,value:conflict.left.value||'未保存具体值'},{label:conflict.right.label,value:conflict.right.value||'未保存具体值'},{label:'结论',value:conflict.conclusion}].filter(r=>r.value)}/>
            <Sources sources={[...conflict.left.sources,...conflict.right.sources]}/>
        </Alert>)}
        {view.failure.map((failure,i)=><Alert severity="error" key={`failure:${i}`} sx={{borderRadius:2}}><Typography sx={{fontSize:17,fontWeight:650}}>{failure.title}</Typography>{failure.detail&&<Typography sx={{mt:1,fontSize:16,lineHeight:1.8}}>{failure.detail}</Typography>}</Alert>)}
        <Requirements view={view}/>
        <RuleComparison title={view.ruleLabel} rows={view.ruleRows}/>
        {view.ruleDetailsMissing&&<Alert severity="warning">本次保存摘要缺少规则阈值；需从同次运行原件读取，不能用当前规则补写。</Alert>}
        {!!view.fields.length&&<Box><Typography component="h3" sx={{m:0,mb:1,fontSize:18,fontWeight:700,color:'#263e5d'}}>{view.needs||view.kind==='LITERATURE'?'已有信息':'核心判定资料'}</Typography><FactTable rows={view.fields}/></Box>}
        {view.needs&&!view.fields.length&&!view.ruleRows.length&&<Box><Typography component="h3" sx={{m:0,mb:1,fontSize:18,fontWeight:700,color:'#263e5d'}}>已有信息</Typography>{!view.sections.length&&!view.papers.length&&<Typography sx={{fontSize:16,lineHeight:1.8}}>尚未取得可核对的核心资料。</Typography>}</Box>}
        <ClinicalDetails view={view.clinical}/>
        {view.sections.map((section,i)=><Box key={`${section.title}:${i}`}>
            <Stack direction="row" gap={1} alignItems="center" useFlexGap flexWrap="wrap" sx={{mb:1}}><Typography component="h3" sx={{m:0,fontSize:18,fontWeight:700,color:'#263e5d'}}>{section.title}</Typography>
                {section.role&&<Chip size="small" label={section.role} variant="outlined" sx={{fontSize:13,color:section.role.startsWith('支持 ')?'#3d7861':'#526278',borderColor:'#d0d8e1'}}/>}</Stack>
            <FactTable rows={section.rows}/>
            <RuleComparison rows={section.ruleRows}/>
            <Sources sources={section.sources} linkTitles={!!section.ruleRows?.length}/>
        </Box>)}
        {!!view.papers.length&&<Box><Typography component="h3" sx={{m:0,mb:1.5,fontSize:19,fontWeight:700,color:'#263e5d'}}>资料信息提取</Typography>
            <Stack spacing={2}>{view.papers.map(paper=><Paper variant="outlined" key={paper.id} sx={{p:2.25,borderColor:'#e1e5eb',borderRadius:2.5}}>
                <Typography component="h4" sx={{m:0,mb:1,fontSize:18,fontWeight:700,color:'#263e5d'}}>{paper.sources.find(s=>s.title&&s.title!==paper.title)?.title||paper.title}</Typography>
                {paper.sources.some(s=>s.title&&s.title!==paper.title)&&<Typography sx={{fontSize:14,color:'#526278',mb:1}}>{paper.title}</Typography>}
                {paper.cases.filter(item=>item.rows.length).map(item=><Box key={item.id} sx={{mt:1.5}}>{item.label&&<Typography sx={{fontSize:16,fontWeight:650,color:'#425975',mb:.5}}>{item.label}</Typography>}<FactTable rows={item.rows}/></Box>)}
                {!!paper.sources.length&&<Box sx={{mt:2}}><Sources sources={paper.sources} grouped/></Box>}
            </Paper>)}</Stack>
        </Box>}
        {view.needs&&!comparisons.length&&(view.missing||!view.ruleRows.length)&&<Box data-testid="evidence-requirement-comparison"><FactTable rows={[
            {label:'缺少信息',value:view.missing||'本次结果尚未保存具体缺项，需核对取证或核查记录。'},
            {label:'结论',value:view.conclusion},{label:'建议',value:view.suggestion}].filter(row=>row.value)}/></Box>}
        {view.needs&&comparisons.map((row,i)=><Paper data-testid="evidence-requirement-comparison" variant="outlined" key={`condition:${i}`} sx={{p:2,borderRadius:2,borderColor:'#e1e5eb'}}>
            <Typography sx={{fontSize:17,fontWeight:700,mb:1}}>{row.title}</Typography>
            <FactTable rows={[{label:'规则需要',value:row.requirement},{label:'已有信息',value:row.known},{label:'缺少信息',value:row.gap},{label:'结论',value:row.conclusion},{label:'建议',value:row.suggestion}].filter(r=>r.value)}/>
            <Sources sources={row.sources}/>
        </Paper>)}
        {!!view.sources.length&&<Box><Typography component="h3" sx={{m:0,mb:1.5,fontSize:18,fontWeight:700,color:'#263e5d'}}>判定出处</Typography><Sources sources={view.sources} linkTitles={view.code==='PM2'}/></Box>}
        {view.adopted&&!view.basisAvailable&&<Alert severity="warning">已保存采用结论，但本次记录未附可核对的核心依据字段。</Alert>}
    </Stack>;
}
