import React, { useState } from 'react';
import {
    Alert,
    Box,
    Button,
    Checkbox,
    Chip,
    FormControlLabel,
    LinearProgress,
    MenuItem,
    Paper,
    Stack,
    TextField,
    Typography,
} from '@mui/material';
import { alleleText, backgroundRows, backgroundSourceLinks, MOI_LABELS } from '../utils/acmgBackground';
import './acmgWorkspace.less';
import AnalysisLoadingNotice from './AnalysisLoadingNotice';

export default function AcmgGuidedPreparation({
    topN,
    maximumN,
    onCount,
    snapshot,
    options,
    inputs,
    onInput,
    onPrepare,
    onStart,
    onBack,
    confirmed,
    onConfirm,
    disabled,
    busy,
    feedback,
    stage,
    notes = [],
    dataClass,
    onDataClass,
    pending,
    controlPanel,
    paused,
    timing,
    entryLoading,
    foregroundRead,
}) {
    const [activeId, setActiveId] = useState('');
    const [filter, setFilter] = useState('ALL');
    const [search, setSearch] = useState('');
    const rows = backgroundRows(options, snapshot?.literature?.contexts || [], inputs);
    const visible = rows.filter(
        (r) =>
            (filter === 'ALL' ||
                (filter === 'ATTENTION' ? r.issues.length > 0 : r.status === filter)) &&
            `${r.candidate.geneSymbol} ${alleleText(r.candidate)}`
                .toLowerCase()
                .includes(search.toLowerCase()),
    );
    const active = visible.find((r) => r.candidate.candidateId === activeId) || visible[0];
    const ready = rows.filter((r) => r.status === 'MATCHED').length;
    const choices = rows.filter((r) => r.status === 'MULTIPLE').length;
    const prepared = snapshot && String(options?.requestedN) === topN;
    const running = Boolean(stage) && busy;
    const phase = running ? 2 : prepared ? 1 : 0;
    const steps = ['选择变异', '核对身份与背景', '自动取证与核查', '查看保存结果'];
    const attentionNotes = notes.filter(note => note.attention);
    const noteCount = notes.reduce((total, note) => total + note.count, 0);
    const attentionCount = attentionNotes.reduce((total, note) => total + note.count, 0);
    return (
        <Box className="acmg-workspace">
            <Paper variant="outlined" className="acmg-hero">
                <Stack
                    direction="row"
                    justifyContent="space-between"
                    alignItems="flex-start"
                    gap={2}
                    flexWrap="wrap"
                >
                    <Box>
                        <Typography variant="h5" fontWeight={700}>
                            ACMG 证据分析
                        </Typography>
                    </Box>
                    <Chip
                        label={`${options?.enabledCriteria?.length || 18} 条证据`}
                        color="primary"
                        variant="outlined"
                    />
                </Stack>
                <Box component="ol" className="acmg-steps" aria-label="ACMG 分析步骤">
                    {steps.map((label, i) => (
                        <li
                            key={label}
                            aria-current={phase === i ? 'step' : undefined}
                            className={phase === i ? 'is-active' : phase > i ? 'is-complete' : ''}
                        >
                            <span>{i + 1}</span>
                            <strong>{label}</strong>
                        </li>
                    ))}
                </Box>
            </Paper>
            {feedback && (
                <Alert severity={feedback.severity}>
                    {feedback.title}：{feedback.message}
                </Alert>
            )}
            {entryLoading && <AnalysisLoadingNotice startedAt={entryLoading.startedAt}
                title="正在读取分析信息与运行状态…">
                <Typography variant="body2" sx={{ mt: 1 }}>入口与来源信息：{entryLoading.options}；历史与运行状态：{entryLoading.history}。</Typography>
            </AnalysisLoadingNotice>}
            {!entryLoading && foregroundRead && !running && <AnalysisLoadingNotice
                startedAt={foregroundRead.startedAt}
                title={foregroundRead.operation === 'prepare' ? '正在准备所选变异的身份与背景…' : '正在读取保存信息…'} />}
            {controlPanel}
            {pending && (
                <Alert severity="warning">
                    提交状态尚未确认，请在历史记录中核对，不要重复启动。
                </Alert>
            )}
            {running && (
                <Paper variant="outlined" className="acmg-section" aria-live="polite">
                    <Typography fontWeight={700}>{paused ? '分析已暂停' : '任务处理状态'}</Typography>
                    {!paused && <LinearProgress sx={{ my: 2 }} />}
                    <Typography variant="body2">{stage}</Typography>
                </Paper>
            )}
            {timing?.stages?.length > 0 && <Box component="details" sx={{p:1.5,border:'1px solid #dce5f3',borderRadius:1.5}}>
                <summary style={{cursor:'pointer'}}>阶段耗时（含等待与暂停）</summary>
                {timing.stages.map(row=><Typography key={row.stage} variant="body2" sx={{mt:0.5}}>
                    {({BACKGROUND_VERIFY:'背景核对',SOURCE_SNAPSHOT:'文献来源快照',SOURCE_RECOVERY:'来源恢复与校验',VERIFY_EVALUATE:'资料核查与评价',SAVE:'结果保存',SAVED:'保存完成',EXISTING_RUN:'已有运行'})[row.stage.split(':')[0]] || row.stage.split(':')[0]}
                    {row.stage.includes(':') ? ` · ${row.stage.slice(row.stage.indexOf(':')+1)}` : ''}：{(row.elapsedMillis/1000).toFixed(2)} 秒
                </Typography>)}
                {(timing.operations || []).map(row=><Typography key={row.operation} variant="caption" display="block" sx={{mt:0.5}}>
                    {({FORMAL_CANDIDATES:'候选读取',BACKGROUND_OPTIONS:'背景准备',BA1_VERIFIED_HISTORY:'BA1 历史检索'})[row.operation] || row.operation}：{row.calls} 次 · {(row.elapsedMillis/1000).toFixed(2)} 秒
                </Typography>)}
            </Box>}
            {!prepared && (
                <Paper variant="outlined" className="acmg-section">
                    <Typography variant="h6">01 · 选择本次分析范围</Typography>
                    <Stack
                        direction={{ xs: 'column', sm: 'row' }}
                        spacing={2}
                        alignItems={{ sm: 'center' }}
                        sx={{ mt: 3 }}
                    >
                        <TextField
                            label="分析变异数量"
                            type="number"
                            size="small"
                            value={topN}
                            disabled={disabled}
                            sx={{ width: 180 }}
                            inputProps={{ min: 1, max: maximumN }}
                            onChange={(e) => onCount(e.target.value)}
                            helperText={`当前最多 ${maximumN} 个`}
                        />
                        <Button
                            variant="contained"
                            disableElevation
                            disabled={
                                disabled || !/^[1-9][0-9]*$/.test(topN) || Number(topN) > maximumN
                            }
                            onClick={onPrepare}
                        >
                            {entryLoading ? '正在读取入口…' : busy ? '正在处理…'
                                : feedback?.reconfirmBackground ? '重新准备背景' : '准备身份与背景'}
                        </Button>
                    </Stack>
                </Paper>
            )}
            {prepared && !running && (
                <>
                    <Box className="acmg-stats">
                        {[
                            [rows.length, '本次不同变异', 'primary'],
                            [ready, '单一疾病背景', 'success'],
                            [choices, '多个背景分别分析', 'primary'],
                            [rows.length - ready - choices, '背景尚未确定', 'neutral'],
                        ].map(([n, label, tone]) => (
                            <Paper variant="outlined" className={`acmg-stat ${tone}`} key={label}>
                                <strong>{n}</strong>
                                <span>{label}</span>
                            </Paper>
                        ))}
                    </Box>
                    <Paper variant="outlined" className="acmg-section">
                        <Stack
                            direction="row"
                            justifyContent="space-between"
                            gap={1}
                            alignItems="center"
                            flexWrap="wrap"
                        >
                            <Box>
                                <Typography variant="h6">02 · 核对身份与已有背景</Typography>
                            </Box>
                            <Button disabled={disabled} onClick={onBack}>
                                修改分析数量
                            </Button>
                        </Stack>
                        {snapshot.literature?.availability !== 'AVAILABLE' && (
                            <Alert severity="warning" sx={{ mt: 2 }}>
                                补充文献背景暂未读到；普通关系仍可用。可重新准备，不能把读取失败当成不存在相关疾病。
                            </Alert>
                        )}
                        <details className="acmg-disclosure">
                            <summary>本次共用信息：样本、参考版本与已有表型</summary>
                            <Typography variant="body2">
                                样本：{options.sampleId || '未记录'} · 输入：
                                {options.inputAssembly || '未记录'} · 分析：
                                {options.analysisAssembly || '未恢复'}
                            </Typography>
                            <Typography variant="body2">
                                已保存的有效 HPO：
                                {options.commonBackground?.phenotypeSummary?.validHpoTerms?.join(
                                    '、',
                                ) || '未记录有效 HPO，不能据此认定没有症状'}
                            </Typography>
                            <Typography variant="body2">
                                明确排除的 HPO：
                                {options.commonBackground?.phenotypeSummary?.excludedHpoTerms?.join(
                                    '、',
                                ) || '未记录明确的阴性表型'}
                            </Typography>
                            {!!options.commonBackground?.phenotypeSummary?.invalidHpoTerms
                                ?.length && (
                                <Typography variant="body2" color="warning.main">
                                    未通过校验的 HPO：
                                    {options.commonBackground.phenotypeSummary.invalidHpoTerms.join(
                                        '、',
                                    )}
                                    ，不会当作有效表型。
                                </Typography>
                            )}
                            <Typography variant="caption">
                                沿用上传时保存的信息，不重新运行
                                Exomiser。性别、家系亲缘和诊断不从基因或排名推定；涉及病例事实的规则仍需核实对应来源。
                            </Typography>
                        </details>
                        <Box className="acmg-master-detail">
                            <Box className="acmg-sidebar">
                                <TextField
                                    size="small"
                                    label="搜索基因或坐标"
                                    value={search}
                                    onChange={(e) => setSearch(e.target.value)}
                                    fullWidth
                                />
                                <Stack direction="row" gap={1} sx={{ my: 1.5 }}>
                                    <Button
                                        size="small"
                                        variant={filter === 'ALL' ? 'contained' : 'outlined'}
                                        onClick={() => setFilter('ALL')}
                                    >
                                        全部 {rows.length}
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={filter === 'ATTENTION' ? 'contained' : 'outlined'}
                                        onClick={() => setFilter('ATTENTION')}
                                    >
                                        准备资料待核查 {rows.filter((r) => r.issues.length).length}
                                    </Button>
                                </Stack>
                                <Box className="acmg-variant-list" aria-label="背景候选列表">
                                    {visible.map((r) => (
                                        <Button
                                            key={r.candidate.candidateId}
                                            className={`acmg-variant-button ${active?.candidate.candidateId === r.candidate.candidateId ? 'selected' : ''}`}
                                            onClick={() => setActiveId(r.candidate.candidateId)}
                                            aria-pressed={
                                                active?.candidate.candidateId ===
                                                r.candidate.candidateId
                                            }
                                        >
                                            <span>
                                                <strong>
                                                    #{r.candidate.rank} ·{' '}
                                                    {r.candidate.geneSymbol || '基因未确定'}
                                                </strong>
                                                <small>{alleleText(r.candidate)}</small>
                                                <small>
                                                    {r.status === 'MATCHED'
                                                        ? '已匹配单一疾病背景'
                                                        : r.status === 'MULTIPLE'
                                                          ? `${r.backgrounds.length} 个背景分别分析`
                                                          : '背景尚未确定'}
                                                </small>
                                            </span>
                                        </Button>
                                    ))}
                                    {!visible.length && (
                                        <Typography variant="body2" sx={{ p: 2 }}>
                                            没有符合筛选的变异。
                                        </Typography>
                                    )}
                                </Box>
                            </Box>
                            {active && (
                                <Box className="acmg-detail" key={active.candidate.candidateId}>
                                    <Typography variant="h6">
                                        {active.candidate.geneSymbol || '基因未确定'}{' '}
                                        <Typography
                                            component="span"
                                            variant="body2"
                                            color="text.secondary"
                                        >
                                            #{active.candidate.rank}
                                        </Typography>
                                    </Typography>
                                    <Typography className="acmg-identity">
                                        {options.analysisAssembly || options.inputAssembly} ·{' '}
                                        {alleleText(active.candidate)}
                                    </Typography>
                                    <Box className="acmg-field-block">
                                        <Typography fontWeight={600}>
                                            {active.status === 'MULTIPLE'
                                                ? '对应多个疾病背景，后续将分别分析'
                                                : active.status === 'MATCHED'
                                                  ? '已匹配疾病背景'
                                                  : '疾病背景尚未确定，由系统探索相关背景'}
                                        </Typography>
                                        {active.backgrounds.map(({ choice, input, state }) => (
                                            <Paper
                                                variant="outlined"
                                                sx={{ p: 2, my: 1.5 }}
                                                key={choice.id}
                                            >
                                                <Typography fontWeight={600}>
                                                    {choice.label}
                                                </Typography>
                                                <Stack
                                                    direction="row"
                                                    gap={1}
                                                    flexWrap="wrap"
                                                    sx={{ my: 1 }}
                                                >
                                                    <Chip
                                                        size="small"
                                                        variant="outlined"
                                                        label={
                                                            choice.moi
                                                                ? MOI_LABELS[choice.moi] ||
                                                                  choice.moi
                                                                : '遗传方式尚未确定'
                                                        }
                                                    />
                                                    <Chip
                                                        size="small"
                                                        variant="outlined"
                                                        label={
                                                            choice.moi
                                                                ? '独立分析背景'
                                                                : '保留资料缺项'
                                                        }
                                                    />
                                                </Stack>
                                                <details className="acmg-disclosure">
                                                    <summary>
                                                        查看背景来源、已有注释与可选病例信息
                                                    </summary>
                                                    <Typography variant="body2">
                                                        {choice.mondoId} ·{' '}
                                                        {choice.relationship
                                                            ? '本地疾病关系目录'
                                                            : '已发布文献规范'}
                                                    </Typography>
                                                    {choice.relationship?.reportLocator?.startsWith(
                                                        'https://',
                                                    ) && (
                                                        <a
                                                            href={choice.relationship.reportLocator}
                                                            target="_blank"
                                                            rel="noreferrer"
                                                        >
                                                            查看疾病关系出处
                                                        </a>
                                                    )}
                                                    {backgroundSourceLinks(choice)
                                                        .map((source, i) => (
                                                            <Typography key={i} variant="body2">
                                                                <a
                                                                    href={source.url}
                                                                    target="_blank"
                                                                    rel="noreferrer"
                                                                >
                                                                    {source.label}
                                                                </a>
                                                            </Typography>
                                                        ))}
                                                    {!!choice.inheritanceUnspecifiedSources
                                                        ?.length && (
                                                        <Typography variant="caption">
                                                            部分补充来源未注明遗传方式；这里只合并出处，未据此推定遗传方式。
                                                        </Typography>
                                                    )}
                                                    <Typography variant="body2" sx={{ my: 1 }}>
                                                        已绑定转录本：
                                                        {state.transcript?.versionedId ||
                                                            '尚未绑定兼容转录本；已有注释仍保留，后续按规则核查'}
                                                    </Typography>
                                                    <TextField
                                                        select
                                                        fullWidth
                                                        size="small"
                                                        label="当前背景的患病状态"
                                                        value={input.affectedStatus || 'UNKNOWN'}
                                                        disabled={disabled || !choice.moi}
                                                        sx={{ my: 1.5 }}
                                                        onChange={(e) =>
                                                            onInput(active.candidate.candidateId, {
                                                                backgroundInputs:
                                                                    active.backgrounds.map((b) => ({
                                                                        ...b.input,
                                                                        choiceId: b.choice.id,
                                                                        ...(b.choice.id ===
                                                                        choice.id
                                                                            ? {
                                                                                  affectedStatus:
                                                                                      e.target
                                                                                          .value,
                                                                              }
                                                                            : {}),
                                                                    })),
                                                            })
                                                        }
                                                    >
                                                        <MenuItem value="UNKNOWN">
                                                            未知／没有可靠资料
                                                        </MenuItem>
                                                        <MenuItem value="AFFECTED">
                                                            已有资料确认患病
                                                        </MenuItem>
                                                        <MenuItem value="UNAFFECTED">
                                                            已有资料确认未患病
                                                        </MenuItem>
                                                    </TextField>
                                                </details>
                                            </Paper>
                                        ))}
                                    </Box>
                                    {!!active.issues.length && (
                                        <Alert severity="info">
                                            <Typography fontWeight={600} variant="body2">
                                                本变异尚待解决
                                            </Typography>
                                            {active.issues.map((i) => (
                                                <Typography variant="body2" key={i}>
                                                    {i}
                                                </Typography>
                                            ))}
                                            <Typography variant="caption">
                                                缺项仅限制依赖它的规则，不会把所有证据判为阴性。
                                            </Typography>
                                        </Alert>
                                    )}
                                    <details className="acmg-disclosure">
                                        <summary>正式来源与原有遗传模式</summary>
                                        <Typography
                                            variant="body2"
                                            sx={{ overflowWrap: 'anywhere' }}
                                        >
                                            {active.candidate.candidateId} ·{' '}
                                            {active.candidate.variantId}
                                        </Typography>
                                        <Typography variant="body2">
                                            排名中保存的 GT：
                                            {active.candidate.savedObservation?.genotype ||
                                                '未记录'}{' '}
                                            · DP：
                                            {active.candidate.savedObservation?.depth ?? '未记录'} ·
                                            GQ：
                                            {active.candidate.savedObservation?.genotypeQuality ??
                                                '未记录'}{' '}
                                            · FILTER：
                                            {active.candidate.savedObservation?.filter || '未记录'}
                                        </Typography>
                                        <Typography variant="body2">
                                            排名模式：
                                            {active.candidate.candidateIdentity
                                                ?.modeOfInheritance || '未记录'}
                                        </Typography>
                                        <Typography
                                            variant="caption"
                                            sx={{ overflowWrap: 'anywhere' }}
                                        >
                                            来源摘要：
                                            {active.candidate.recommendation
                                                ?.executionSourceSha256 || '未取得'}
                                        </Typography>
                                    </details>
                                </Box>
                            )}
                        </Box>
                        <Box className="acmg-confirm">
                            <TextField
                                select
                                label="本次数据类别"
                                size="small"
                                value={dataClass}
                                disabled={disabled}
                                onChange={(e) => onDataClass(e.target.value)}
                                sx={{ minWidth: 240 }}
                            >
                                <MenuItem value="PRIVACY_BOUNDED_RUNTIME">
                                    隐私受控病例分析
                                </MenuItem>
                                <MenuItem value="SYNTHETIC">公开位点与合成测试病例</MenuItem>
                            </TextField>
                            <FormControlLabel
                                control={
                                    <Checkbox
                                        checked={confirmed}
                                        disabled={disabled}
                                        onChange={(e) => onConfirm(e.target.checked)}
                                    />
                                }
                                label="我已了解本次分析范围；系统将按各背景分别核查，未知信息保持未知，不代表患者诊断。"
                            />
                            <Stack
                                direction={{ xs: 'column', sm: 'row' }}
                                spacing={2}
                                alignItems={{ sm: 'center' }}
                            >
                                <Button
                                    variant="contained"
                                    disableElevation
                                    disabled={disabled || !confirmed || !rows.length}
                                    onClick={onStart}
                                >
                                    选择证据并分析
                                </Button>
                                <Button disabled={disabled} onClick={onPrepare}>
                                    重新准备背景
                                </Button>
                            </Stack>
                        </Box>
                        {confirmed && (
                            <Alert severity="success" sx={{ mt: 2 }}>
                                本次背景已确认
                            </Alert>
                        )}
                    </Paper>
                </>
            )}
            {noteCount > 0 && (
                <Paper component="details" variant="outlined" className="acmg-material-preparation">
                    <summary>
                        资料准备
                        <span>{noteCount} 条记录</span>
                        {attentionCount > 0 && <span className="is-attention">{attentionCount} 条待关注</span>}
                    </summary>
                    <ul aria-label="资料准备明细">
                        {(attentionCount > 0 ? attentionNotes : notes).map(note => (
                            <li key={JSON.stringify([note.label, note.status, note.detail])}>
                                <strong>{note.label}{note.count > 1 && `（${note.count} 条）`}</strong>
                                <span>{note.status}{note.detail && ` · ${note.detail}`}</span>
                            </li>
                        ))}
                    </ul>
                </Paper>
            )}
        </Box>
    );
}
