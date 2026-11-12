import React, { useEffect, useMemo, useState } from 'react';
import { formatVariantCoordinate } from '../utils/variantCoordinate';
import {
    Alert,
    Box,
    Button,
    Checkbox,
    Chip,
    CircularProgress,
    Container,
    Dialog,
    DialogContent,
    DialogTitle,
    Divider,
    FormControlLabel,
    LinearProgress,
    Paper,
    Stack,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    Typography,
} from '@mui/material';
import {
    fetchAcmgBusinessRun,
    fetchAcmgBusinessRuns,
    fetchVariantAnalysis,
    fetchVariantAnalysisCandidate,
    fetchVariantAnalysisPreflight,
    fetchVariantAnalysisVariants,
    submitAcmgBusinessRun,
    submitVariantAnalysis,
} from '../services/variantAnalysis';
import {
    buildTeachingDeclaration,
    resolveTeachingAnalysisId,
    summarizeAcmgBlockers,
    validateTeachingJob,
} from '../utils/acmgTeaching';

const MATERIALS = '/demo/group-meeting';
const STORAGE_KEY = 'varvis-group-demo-analysis-v1';
const codeOf = (evaluation) =>
    String(evaluation?.criterionId || '')
        .split(':')
        .pop();
const evaluationOf = (attempt, code) =>
    attempt?.criterionEvaluations?.find((e) => codeOf(e) === code);
const scoreOf = (attempt) =>
    attempt?.atomicEvidence
        ?.flatMap((e) => e.measurements || [])
        .find((m) => m.metricId === 'alphamissense.amPathogenicity')?.rawValue;
const geneOf = (row) =>
    row?.exomiserCandidateFeatures?.geneSymbol || row?.candidateIdentity?.geneSymbol;
const keyOf = (row) => row?.observation?.key;
const alleleOf = (row) => {
    const key = keyOf(row);
    return formatVariantCoordinate(key,row?.variantId);
};
const errorText = (error) =>
    error?.response?.data?.message || error?.message || '请求失败，请检查本地服务。';

function ResultChip({ attempt, code, executed }) {
    const status = evaluationOf(attempt, code)?.evaluationResult?.status;
    const options = {
        MET: ['满足', 'success'],
        NOT_MET: ['未满足', 'default'],
        NOT_EVALUATED: ['无法评价', 'warning'],
    };
    const [label, color] =
        options[status] || (executed ? ['未执行', 'warning'] : ['待执行', 'default']);
    return (
        <Chip
            size="small"
            label={label}
            color={color}
            variant={status === 'MET' ? 'filled' : 'outlined'}
        />
    );
}

function DownloadJsonButton({ value, filename, children }) {
    const url = useMemo(
        () =>
            URL.createObjectURL(
                new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }),
            ),
        [value],
    );
    useEffect(() => () => URL.revokeObjectURL(url), [url]);
    return (
        <Button variant="outlined" component="a" href={url} download={filename}>
            {children}
        </Button>
    );
}

function CriterionDetails({ attempt, code, executed }) {
    const evaluation = evaluationOf(attempt, code);
    const parameters = evaluation?.deterministicTrace?.parameters || [];
    const threshold = parameters.find(
        (p) =>
            p.name ===
            (code === 'BP4' ? 'threshold.bp4.maxInclusive' : 'threshold.pp3.minInclusive'),
    )?.value;
    const labels = {
        RESOURCE_FOUR_AXES_READY: '数据源执行、可用性、查询与适用性检查',
        EXACT_ALLELE_CONFIRMED: '坐标及 REF/ALT 精确匹配',
        TRANSCRIPT_PROTEIN_EXACT_MATCH_CONFIRMED: '版本化转录本与蛋白变化匹配',
        BP4_MAXIMUM_SCORE_SATISFIED: 'BP4 分数条件',
        PP3_MINIMUM_SCORE_SATISFIED: 'PP3 分数条件',
    };
    const reason =
        evaluation?.evaluationResult?.reasonCode || evaluation?.criterionApplicability?.reasonCode;
    return (
        <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" spacing={2} alignItems="center">
                <Typography fontWeight={600}>
                    {code} ·{' '}
                    {code === 'BP4' ? '偏良性方向的计算预测支持' : '偏致病方向的计算预测支持'}
                </Typography>
                <ResultChip attempt={attempt} code={code} executed={executed} />
            </Stack>
            {threshold !== undefined && (
                <Typography sx={{ my: 1 }}>
                    本次服务器采用的条件：AM 分数 {code === 'BP4' ? '≤' : '≥'} {threshold}
                    ；实际分数为 {scoreOf(attempt)}。
                    {evaluation?.evaluationResult?.status === 'MET'
                        ? '该条件及前置检查均通过。'
                        : evaluation?.evaluationResult?.status === 'NOT_MET'
                          ? '本条规则未满足。'
                          : '当前未完成评价，不能按未满足解释。'}
                </Typography>
            )}
            {(evaluation?.deterministicTrace?.steps || [])
                .filter((s) => labels[s.stepCode])
                .filter((s) => !s.stepCode.startsWith(code === 'BP4' ? 'PP3_' : 'BP4_'))
                .map((step) => (
                    <Typography
                        key={step.stepCode}
                        variant="body2"
                        color="text.secondary"
                        sx={{ mt: 0.5 }}
                    >
                        {labels[step.stepCode]}：
                        {step.outcome === 'TRUE'
                            ? '通过'
                            : step.outcome === 'FALSE'
                              ? '未通过'
                              : step.outcome}
                    </Typography>
                ))}
            {reason && (
                <Typography variant="body2" sx={{ mt: 1 }}>
                    原因：{reason}
                </Typography>
            )}
            {(attempt?.blockers || []).map((b, index) => (
                <Typography key={`${b.reasonCode}-${index}`} variant="body2" sx={{ mt: 1 }}>
                    阻断原因：{b.reasonCode}
                </Typography>
            ))}
            {evaluation && (
                <Typography variant="caption" component="p" sx={{ mt: 1 }}>
                    证据强度：
                    {evaluation.appliedStrength === 'SUPPORTING'
                        ? 'Supporting（支持级）'
                        : '未施加证据强度'}
                    。 本结果仅属于这条规则，不是最终分类。
                </Typography>
            )}
        </Paper>
    );
}

export default function TeachingAcmgEvaluationPanel({ analysisId = '' }) {
    const embedded = Boolean(analysisId);
    const [manifest, setManifest] = useState(null);
    const [preflight, setPreflight] = useState(null);
    const [file, setFile] = useState(null);
    const [fileValid, setFileValid] = useState(false);
    const [job, setJob] = useState(null);
    const [candidates, setCandidates] = useState([]);
    const [candidateTotal, setCandidateTotal] = useState(0);
    const [run, setRun] = useState(null);
    const [busy, setBusy] = useState('');
    const [error, setError] = useState('');
    const [connectionNotice, setConnectionNotice] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [detail, setDetail] = useState(null);

    useEffect(() => {
        let cancelled = false;
        Promise.all([
            fetch(`${MATERIALS}/demo-manifest.json`).then((r) => {
                if (!r.ok) throw new Error('教学材料尚未准备，请先运行演示准备脚本。');
                return r.json();
            }),
            fetchVariantAnalysisPreflight('GRCh38'),
        ])
            .then(([materials, readiness]) => {
                if (cancelled) return;
                setManifest(materials);
                setPreflight(readiness);
                // An Analysis Lab invocation is bound to its current job, never browser history.
                const saved = resolveTeachingAnalysisId(
                    analysisId,
                    window.location.search,
                    localStorage.getItem(STORAGE_KEY),
                );
                if (saved) setJob({ id: saved, status: 'LOADING' });
            })
            .catch((e) => {
                if (!cancelled) setError(errorText(e));
            });
        return () => {
            cancelled = true;
        };
    }, [analysisId]);

    useEffect(() => {
        if (!job?.id || !manifest) return undefined;
        let cancelled = false;
        let timer;
        const refresh = async () => {
            try {
                const current = await fetchVariantAnalysis(job.id);
                if (cancelled) return;
                setConnectionNotice('');
                validateTeachingJob(current, manifest);
                setJob(current);
                if (current.status === 'READY') {
                    const page = await fetchVariantAnalysisVariants(current.id, 1, 20);
                    const selected = [];
                    for (let i = 0; i < page.variants.length; i += 4) {
                        selected.push(
                            ...(await Promise.all(
                                page.variants
                                    .slice(i, i + 4)
                                    .map((c) => fetchVariantAnalysisCandidate(current.id, c)),
                            )),
                        );
                    }
                    const runs = await fetchAcmgBusinessRuns(current.id);
                    let savedRun = null;
                    if (runs.length) {
                        try {
                            savedRun = await fetchAcmgBusinessRun(current.id, runs[0].runId);
                        } catch (readError) {
                            savedRun = { ...runs[0], resultsAvailable: false };
                            if (!cancelled)
                                setError(`历史规则结果暂时无法读回：${errorText(readError)}`);
                        }
                    }
                    if (!cancelled) {
                        setCandidates(selected);
                        setCandidateTotal(page.candidateTotal);
                        setRun(savedRun);
                        if (selected.length < 20)
                            setError(
                                `实际候选只有 ${selected.length} 条，尚未满足 Top-20 演示验收。`,
                            );
                    }
                    return;
                }
                if (current.status === 'FAILED') {
                    setError(current.errorMessage || current.errorCode || '分析失败');
                    return;
                }
                timer = setTimeout(refresh, 3000);
            } catch (e) {
                if (!cancelled) {
                    if (e?.isAxiosError && (!e.response || e.response.status >= 500)) {
                        setConnectionNotice(
                            '本地服务响应较慢，正在重新读取任务状态；不会重新提交计算。',
                        );
                        timer = setTimeout(refresh, 5000);
                    } else setError(errorText(e));
                }
            }
        };
        refresh();
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [job?.id, manifest]);

    const rows = useMemo(
        () =>
            candidates.map((candidate, i) => ({
                candidate,
                rank: i + 1,
                attempt: run?.candidates?.find((c) => c.candidateId === candidate.candidateId)
                    ?.attempts?.[0],
            })),
        [candidates, run],
    );
    const completed =
        run?.resultsAvailable !== false &&
        ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run?.status);
    const retryAvailable = ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run?.status);
    const blockers = summarizeAcmgBlockers(run);
    const orderValid =
        !run?.candidateIds ||
        (run.candidateIds.length === candidates.length &&
            run.candidateIds.every((id, i) => id === candidates[i]?.candidateId));
    const counts = (code, status) =>
        rows.filter((r) => evaluationOf(r.attempt, code)?.evaluationResult?.status === status)
            .length;
    const chooseFile = async (event) => {
        const selected = event.target.files?.[0];
        setFile(selected || null);
        setFileValid(false);
        setError('');
        if (!selected || !manifest) return;
        const bytes = await selected.arrayBuffer();
        const digest = await crypto.subtle.digest('SHA-256', bytes);
        const hash = [...new Uint8Array(digest)]
            .map((b) => b.toString(16).padStart(2, '0'))
            .join('');
        const valid = hash === manifest.vcfSha256;
        setFileValid(valid);
        if (!valid) setError('此页面只接受配套教学 VCF；文件内容与材料清单不一致。');
    };
    const upload = async () => {
        setBusy('upload');
        setError('');
        try {
            const created = await submitVariantAnalysis({
                file,
                genomeAssembly: 'GRCh38',
                analysisMode: 'WES',
                sampleId: manifest.sampleId,
                hpoTerms: manifest.hpoTerms,
            });
            localStorage.setItem(STORAGE_KEY, created.id);
            setCandidates([]);
            setCandidateTotal(0);
            setConnectionNotice('');
            setRun(null);
            setConfirmed(false);
            setJob(created);
        } catch (e) {
            setError(errorText(e));
        } finally {
            setBusy('');
        }
    };
    const evaluate = async (retry = false) => {
        setBusy('evaluate');
        setError('');
        try {
            if (run && !retry) {
                setRun(await fetchAcmgBusinessRun(job.id, run.runId));
            } else {
                const declaration = buildTeachingDeclaration(manifest, candidates);
                if (retry) {
                    declaration.retryOf = run.runId;
                    declaration.declarationVersion = `group-demo-v1-retry-${run.runId}`;
                }
                setRun(await submitAcmgBusinessRun(job.id, declaration));
            }
        } catch (e) {
            setError(`${errorText(e)}。可刷新页面读回已有运行；不会自动重试评价。`);
        } finally {
            setBusy('');
        }
    };
    const active = job && !['READY', 'FAILED'].includes(job.status);
    const currentStep = completed ? 3 : candidates.length ? 2 : job ? 1 : 0;
    return (
        <Box
            sx={{
                minHeight: embedded ? 0 : '100vh',
                bgcolor: '#f7f9fc',
                color: '#17263d',
                fontFamily: "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif",
                '& .MuiPaper-root': { border: '1px solid #dbe4ee', borderRadius: 2 },
                '& .MuiTableHead-root': { bgcolor: '#eef3f8' },
                '& .MuiTableCell-head': { color: '#607089', fontWeight: 600 },
                '& .MuiButton-root': { textTransform: 'none' },
                '& .MuiButton-containedPrimary': { bgcolor: '#1d5fae' },
                py: embedded ? 2 : 4,
            }}
        >
            <Container maxWidth="lg">
                {!embedded && (
                    <Stack
                        direction="row"
                        justifyContent="space-between"
                        alignItems="center"
                        sx={{ mb: 3 }}
                    >
                        <Typography variant="h4" fontWeight={700}>
                            从候选变异到规则证据
                        </Typography>
                        <Chip label="合成教学样本" color="primary" variant="outlined" />
                    </Stack>
                )}
                {embedded && (
                    <Typography variant="body2" sx={{ mb: 2 }}>
                        当前任务：{analysisId}
                    </Typography>
                )}
                {error && (
                    <Alert severity="error" onClose={() => setError('')} sx={{ mb: 2 }}>
                        {error}
                    </Alert>
                )}
                {connectionNotice && (
                    <Alert severity="warning" sx={{ mb: 2 }}>
                        {connectionNotice}
                    </Alert>
                )}
                {blockers.length > 0 && (
                    <Alert severity="warning" sx={{ mb: 2 }}>
                        <Typography fontWeight={600}>
                            本次规则运行存在阻断，不能将未执行视为规则未满足。
                        </Typography>
                        {blockers.map((blocker) => (
                            <Typography key={blocker.code} variant="body2" sx={{ mt: 0.5 }}>
                                {blocker.message} 影响 {blocker.count} 条候选（{blocker.code}）。
                            </Typography>
                        ))}
                    </Alert>
                )}
                {!orderValid && (
                    <Alert severity="error">规则结果与当前候选顺序不一致，停止展示关联结果。</Alert>
                )}
                <Box
                    sx={{
                        display: 'grid',
                        gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
                        gap: 2,
                        mb: 3,
                    }}
                >
                    {[
                        ['当前候选', `${candidates.length} / 20`, '#1d5fae'],
                        [
                            'PP3 满足',
                            completed && orderValid ? counts('PP3', 'MET') : '—',
                            '#b86a0b',
                        ],
                        [
                            'BP4 满足',
                            completed && orderValid ? counts('BP4', 'MET') : '—',
                            '#16815f',
                        ],
                        [
                            '无法评价的候选',
                            completed && orderValid
                                ? rows.filter((row) =>
                                      ['PP3', 'BP4'].some(
                                          (code) =>
                                              !evaluationOf(row.attempt, code) ||
                                              evaluationOf(row.attempt, code).evaluationResult
                                                  ?.status === 'NOT_EVALUATED',
                                      ),
                                  ).length
                                : '—',
                            '#607089',
                        ],
                    ].map(([label, value, color]) => (
                        <Paper key={label} elevation={0} sx={{ p: 2 }}>
                            <Typography variant="caption" color="text.secondary">
                                {label}
                            </Typography>
                            <Typography variant="h5" fontWeight={650} sx={{ color, mt: 0.5 }}>
                                {value}
                            </Typography>
                        </Paper>
                    ))}
                </Box>
                {!embedded && (
                    <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ mb: 3 }}>
                        {[
                            '上传教学 VCF',
                            '获得真实 Top-20',
                            '执行 PP3 / BP4',
                            '查看证据与结果',
                        ].map((label, i) => (
                            <Paper
                                key={label}
                                elevation={0}
                                sx={{
                                    flex: 1,
                                    p: 2,
                                    border: '1px solid',
                                    borderColor: currentStep === i ? '#237b8d' : '#e0e5eb',
                                    borderRadius: 2,
                                }}
                            >
                                <Typography fontWeight={600}>{label}</Typography>
                            </Paper>
                        ))}
                    </Stack>
                )}
                {!embedded && (
                    <Paper elevation={0} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
                        <Typography variant="h6" fontWeight={650} gutterBottom>
                            1 · 上传与候选排名
                        </Typography>
                        <Typography color="text.secondary" sx={{ mb: 2 }}>
                            GRCh38 · {manifest?.variantCount || '—'} 条教学 SNV · HBB / GJB2
                        </Typography>
                        <Stack
                            direction="row"
                            spacing={2}
                            useFlexGap
                            flexWrap="wrap"
                            alignItems="center"
                        >
                            <Button variant="outlined" component="label">
                                选择 VCF
                                <input
                                    type="file"
                                    accept=".vcf"
                                    hidden
                                    onChange={chooseFile}
                                    disabled={!manifest || Boolean(busy)}
                                />
                            </Button>
                            <Typography variant="body2">{file?.name || '尚未选择文件'}</Typography>
                            {fileValid && (
                                <Chip size="small" color="success" label="教学文件校验通过" />
                            )}
                            <Button
                                variant="contained"
                                disabled={
                                    !fileValid ||
                                    !preflight?.applicationReady ||
                                    Boolean(busy) ||
                                    Boolean(active)
                                }
                                onClick={upload}
                            >
                                上传并运行排名
                            </Button>
                        </Stack>
                        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
                            本机预检：
                            {preflight
                                ? preflight.applicationReady
                                    ? 'Exomiser 提交条件已就绪'
                                    : '尚未就绪'
                                : '检查中'}
                            {preflight?.blockingErrors?.length
                                ? ` · ${preflight.blockingErrors.join('；')}`
                                : ''}
                        </Typography>
                        {job && (
                            <Box sx={{ mt: 2 }}>
                                <Divider sx={{ mb: 2 }} />
                                <Typography>
                                    分析状态：{job.status} · 候选总数：{candidateTotal || '—'} ·
                                    引擎：
                                    {job.engine || 'EXOMISER'}
                                </Typography>
                                <Typography variant="caption" color="text.secondary">
                                    任务 {job.id}
                                </Typography>
                                {active && (
                                    <>
                                        <LinearProgress sx={{ mt: 2 }} />
                                        <Typography variant="body2" sx={{ mt: 1 }}>
                                            正在实际执行排名与注释，完成后自动加载前 20 条。
                                        </Typography>
                                    </>
                                )}
                            </Box>
                        )}
                    </Paper>
                )}
                <Paper elevation={0} sx={{ p: 3, mb: 3, borderRadius: 2 }}>
                    <Typography variant="h6" fontWeight={650} gutterBottom>
                        对正式 Top-20 执行 PP3 / BP4
                    </Typography>
                    <Typography color="text.secondary" sx={{ mb: 2 }}>
                        复用系统排名，逐条核验变异、转录本及证据来源。教学疾病假设和转录本显式指定如下：
                    </Typography>
                    <Stack spacing={1} sx={{ mb: 2 }}>
                        {manifest?.geneDeclarations.map((d) => (
                            <Typography key={d.gene} variant="body2">
                                <strong>{d.gene}</strong> · {d.mondoId} · 常染色体隐性 ·{' '}
                                {d.transcript}
                            </Typography>
                        ))}
                    </Stack>
                    <FormControlLabel
                        control={
                            <Checkbox
                                checked={confirmed}
                                onChange={(e) => setConfirmed(e.target.checked)}
                            />
                        }
                        label="确认本次使用上述模拟病例声明；这些声明不代表临床诊断。"
                    />
                    <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" sx={{ mt: 2 }}>
                        <Button
                            variant="contained"
                            disabled={
                                candidates.length !== 20 ||
                                (!run && !confirmed) ||
                                Boolean(busy) ||
                                !orderValid
                            }
                            onClick={() => evaluate()}
                        >
                            {busy === 'evaluate'
                                ? '规则评价中…'
                                : completed
                                  ? '读取已保存结果'
                                  : run
                                    ? '读取执行状态'
                                    : '评价 Top-20 · PP3 / BP4'}
                        </Button>
                        {retryAvailable && (
                            <Button
                                variant="outlined"
                                disabled={
                                    candidates.length !== 20 ||
                                    !confirmed ||
                                    Boolean(busy) ||
                                    !orderValid
                                }
                                onClick={() => evaluate(true)}
                            >
                                修复后重新评价（保留旧记录）
                            </Button>
                        )}
                        {busy === 'evaluate' && <CircularProgress size={24} />}
                        {completed && orderValid && (
                            <DownloadJsonButton
                                value={run}
                                filename="varvis-top20-acmg-result.json"
                            >
                                下载实际规则结果
                            </DownloadJsonButton>
                        )}
                    </Stack>
                    {run && (
                        <Typography variant="body2" sx={{ mt: 2 }}>
                            运行状态：{run.status} · provider 调用 {run.providerCalls ?? '—'} ·
                            evaluator 调用 {run.evaluatorCalls ?? '—'}
                        </Typography>
                    )}
                </Paper>
                <Paper elevation={0} sx={{ p: 3, borderRadius: 2 }}>
                    <Stack
                        direction="row"
                        spacing={2}
                        justifyContent="space-between"
                        alignItems="center"
                        sx={{ mb: 2 }}
                    >
                        <Typography variant="h6" fontWeight={650}>
                            3 · 候选与规则结果
                        </Typography>
                        {completed && orderValid && (
                            <Stack direction="row" spacing={1}>
                                <Chip label={`PP3 满足 ${counts('PP3', 'MET')}`} />
                                <Chip
                                    color="success"
                                    variant="outlined"
                                    label={`BP4 满足 ${counts('BP4', 'MET')}`}
                                />
                            </Stack>
                        )}
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                        “未满足”只表示该条规则条件未达到；“无法评价”表示证据不足或输入被阻断。点击任意一行查看依据。
                    </Typography>
                    <TableContainer>
                        <Table size="small" aria-label="Top-20 ACMG 规则结果">
                            <TableHead>
                                <TableRow>
                                    {[
                                        '排名',
                                        '变异（GRCh38）',
                                        '基因',
                                        '排名分数',
                                        'AM 分数',
                                        'PP3',
                                        'BP4',
                                        '依据',
                                    ].map((h) => (
                                        <TableCell key={h}>{h}</TableCell>
                                    ))}
                                </TableRow>
                            </TableHead>
                            <TableBody>
                                {orderValid &&
                                    rows.map((row) => (
                                        <TableRow
                                            key={row.candidate.candidateId}
                                            hover
                                            onClick={() => setDetail(row)}
                                            sx={{ cursor: 'pointer' }}
                                        >
                                            <TableCell>{row.rank}</TableCell>
                                            <TableCell sx={{ fontFamily: 'monospace' }}>
                                                {alleleOf(row.candidate)}
                                            </TableCell>
                                            <TableCell>{geneOf(row.candidate)}</TableCell>
                                            <TableCell>
                                                {row.candidate.rankingScore?.toFixed(4) ?? '—'}
                                            </TableCell>
                                            <TableCell>{scoreOf(row.attempt) ?? '—'}</TableCell>
                                            <TableCell>
                                                <ResultChip
                                                    attempt={row.attempt}
                                                    code="PP3"
                                                    executed={completed}
                                                />
                                            </TableCell>
                                            <TableCell>
                                                <ResultChip
                                                    attempt={row.attempt}
                                                    code="BP4"
                                                    executed={completed}
                                                />
                                            </TableCell>
                                            <TableCell>
                                                <Button
                                                    size="small"
                                                    aria-label={`查看第 ${row.rank} 条候选的规则依据`}
                                                    onClick={() => setDetail(row)}
                                                >
                                                    查看依据
                                                </Button>
                                            </TableCell>
                                        </TableRow>
                                    ))}
                            </TableBody>
                        </Table>
                    </TableContainer>
                    {!rows.length && (
                        <Typography color="text.secondary" sx={{ textAlign: 'center', py: 5 }}>
                            上传后，这里展示系统实际产生的候选排名。
                        </Typography>
                    )}
                </Paper>
            </Container>
            <Dialog open={Boolean(detail)} onClose={() => setDetail(null)} fullWidth maxWidth="md">
                <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', gap: 2 }}>
                    {detail
                        ? `排名 ${detail.rank} · ${geneOf(detail.candidate)} · ${alleleOf(detail.candidate)}`
                        : ''}
                    <Button onClick={() => setDetail(null)}>关闭</Button>
                </DialogTitle>
                <DialogContent dividers>
                    <Stack spacing={2}>
                        <Typography>
                            实际 AM 分数：{scoreOf(detail?.attempt) ?? '未取得分数'}
                        </Typography>
                        <Typography>
                            所选转录本：
                            {detail?.attempt?.context?.transcript?.accession ||
                                detail?.attempt?.preflight?.original?.transcript?.accession ||
                                '请查看下方实际证据'}
                            {detail?.attempt?.context?.transcript?.version
                                ? `.${detail.attempt.context.transcript.version}`
                                : ''}
                        </Typography>
                        {['PP3', 'BP4'].map((code) => (
                            <CriterionDetails
                                key={code}
                                attempt={detail?.attempt}
                                code={code}
                                executed={completed}
                            />
                        ))}
                        {detail?.attempt && (
                            <DownloadJsonButton
                                value={detail.attempt}
                                filename={`candidate-${detail.rank}-evidence.json`}
                            >
                                下载本条原始证据与评价依据
                            </DownloadJsonButton>
                        )}
                    </Stack>
                </DialogContent>
            </Dialog>
        </Box>
    );
}
