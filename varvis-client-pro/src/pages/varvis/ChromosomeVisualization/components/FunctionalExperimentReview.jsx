import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { functionalReviewRequest } from '../services/variantAnalysis';

function checkedReview(data, analysisId, runId) {
    if (data?.analysisId !== analysisId || data?.runId !== runId || !Array.isArray(data.records)
        || data.records.some((s) => s.analysisId !== analysisId || s.runId !== runId
            || typeof s.reviewRecord?.subjectId !== 'string' || typeof s.reviewRecord?.actor?.actorId !== 'string'
            || typeof s.reviewRecord?.rationaleSummary !== 'string'
            || !['ACCEPTED', 'REJECTED', 'NONE'].includes(s.reviewRecord?.decision))) {
        throw new Error('FUNCTIONAL_REVIEW_RESPONSE_SCOPE_MISMATCH');
    }
    return data;
}

/** Adoption is separate from calculation and overall case confirmation. */
export default function FunctionalExperimentReview({ run, value }) {
    if (value?.adoptionSupported === false) return null;
    const evaluationId = value?.evaluation?.evaluationId;
    if (!run?.analysisId || !run?.runId || !evaluationId) return null;
    // Remount both the saved state and draft when the subject or material changes.
    const key = JSON.stringify([run.analysisId, run.runId, evaluationId, value.profileSha256,
        value.atomicEvidence?.[0]?.source?.resourceSha256]);
    return <FunctionalSubjectReview key={key} run={run} value={value} />;
}

function FunctionalSubjectReview({ run, value }) {
    const [saved, setSaved] = useState(null);
    const [failure, setFailure] = useState('');
    const [notice, setNotice] = useState('');
    const [busy, setBusy] = useState(false);
    const [actor, setActor] = useState('');
    const [role, setRole] = useState('CURATOR');
    const [decision, setDecision] = useState('NONE');
    const [rationale, setRationale] = useState('');
    const [reload, setReload] = useState(0);
    const live = useRef(true);
    const submitting = useRef(false);
    const evaluationId = value?.evaluation?.evaluationId;
    useEffect(() => {
        live.current = true;
        return () => { live.current = false; };
    }, []);
    useEffect(() => {
        let active = true;
        setSaved(null);
        setFailure('');
        setNotice('');
        if (run?.analysisId && run?.runId && evaluationId) {
            functionalReviewRequest(run.analysisId, run.runId, 'get')
                .then((data) => { if (active) setSaved(checkedReview(data, run.analysisId, run.runId)); })
                .catch(() => { if (active) setFailure('无法读取采用记录；请重新读取保存结果。'); });
        }
        return () => { active = false; };
    }, [run?.analysisId, run?.runId, evaluationId, reload]);
    if (!evaluationId || !run) return null;
    const records = (saved?.records || []).filter((s) => s.reviewRecord?.subjectId === evaluationId);
    const previousIds = new Set(records.map((s) => s.reviewRecord.previousReviewRecordId));
    const latest = records.find((s) => !previousIds.has(s.reviewRecord.reviewRecordId));
    const stale = Boolean(saved && saved.currentProfileSha256 !== value.profileSha256);
    const met = value.evaluation.evaluationResult?.status === 'MET'
        && value.evaluation.criterionApplicability?.status === 'APPLICABLE'
        && value.evaluation.conflict?.status === 'NONE';
    const labels = { ACCEPTED: '已采用', REJECTED: '已拒绝', NONE: '待补资料' };
    const locked = busy || !saved || stale || Boolean(failure);
    const edit = (setter) => (event) => { setNotice(''); setter(event.target.value); };
    const submit = async () => {
        if (locked || submitting.current || (decision === 'ACCEPTED' && !met)
            || !/^reviewer-[a-z0-9][a-z0-9._-]{7,95}$/.test(actor.trim()) || !rationale.trim()) return;
        submitting.current = true;
        setBusy(true); setFailure(''); setNotice('');
        let posted = false;
        try {
            await functionalReviewRequest(run.analysisId, run.runId, 'post', {
                evaluationId,
                factPackageSha256: value.atomicEvidence?.[0]?.source?.resourceSha256,
                profileSha256: value.profileSha256,
                previousReviewRecordId: latest?.reviewRecord.reviewRecordId || null,
                decision, actorId: actor.trim(), role, rationale: rationale.trim(),
            });
            posted = true;
            if (!live.current) return;
            const refreshed = checkedReview(await functionalReviewRequest(run.analysisId, run.runId, 'get'), run.analysisId, run.runId);
            if (live.current) {
                setSaved(refreshed);
                setNotice('本次采用记录已保存并重新读取。');
            }
        } catch {
            if (live.current) {
                setSaved(null);
                setFailure(posted ? '已收到保存响应，但尚未核实最新采用状态；请重新读取，勿重复决定。'
                    : '本次保存结果未确认，或资料/前序记录已变化；请重新读取后复核。');
            }
        } finally { submitting.current = false; if (live.current) setBusy(false); }
    };
    const download = async () => {
        try {
            const data = await functionalReviewRequest(run.analysisId, run.runId, 'get', null, '/export');
            if (!live.current) return;
            checkedReview(data, run.analysisId, run.runId);
            if (data.calculation?.analysisId !== run.analysisId || data.calculation?.runId !== run.runId)
                throw new Error('FUNCTIONAL_EXPORT_SCOPE_MISMATCH');
            const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
            const link = document.createElement('a'); link.href = url; link.download = 'functional-review.json'; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch { if (live.current) setFailure('实验与采用记录导出失败；请重新读取后核对。'); }
    };
    return (
        <Stack spacing={1}>
            <Typography variant="subtitle2">功能实验采用</Typography>
            <Typography variant="body2">
                {!saved ? (failure ? '采用状态尚未核实' : '正在读取采用记录…')
                    : latest ? `已保存记录：${labels[latest.reviewRecord.decision]} · ${latest.reviewRecord.actor.actorId} · ${latest.reviewRecord.rationaleSummary}`
                        : '已读取：尚无人工采用决定；系统请求审阅不代表待补意见或已采用。'}
            </Typography>
            <Typography variant="caption">计算满足不代表已采用或最终临床结论。审阅者由当前填写者声明。</Typography>
            <Alert severity="info">BS3 本次未启用：Q175R 的剪接适用性待核，D526N 的双条件差异保留。</Alert>
            {stale && <Alert severity="warning">这是旧资料/政策的历史结果；原采用记录保留，不能沿用于当前版本。</Alert>}
            {failure && <Alert severity="error">{failure}</Alert>}
            {notice && !failure && <Alert severity="success">{notice}</Alert>}
            {busy && <Typography variant="body2">正在保存并核实采用记录…</Typography>}
            {failure && <Button disabled={busy} onClick={() => setReload((n) => n + 1)}>重新读取采用记录</Button>}
            <Typography variant="caption">以下是本次待提交表单，与上方已保存记录分开。</Typography>
            <TextField disabled={locked} size="small" label="审阅者标识" placeholder="reviewer-your-name" value={actor} onChange={edit(setActor)} />
            <TextField disabled={locked} select size="small" label="审阅角色" value={role} onChange={edit(setRole)}>
                <MenuItem value="CURATOR">资料审阅者</MenuItem>
                <MenuItem value="LAB_SCIENTIST">实验室科学人员</MenuItem>
                <MenuItem value="GENETICIST">遗传学专业人员</MenuItem>
                <MenuItem value="SYSTEM_ADMINISTRATOR">系统管理员（工程验证）</MenuItem>
            </TextField>
            <TextField disabled={locked} select size="small" label="本次决定" value={decision} onChange={edit(setDecision)}>
                <MenuItem value="NONE">待补资料</MenuItem>
                <MenuItem value="ACCEPTED" disabled={!met}>采用本实验的 PS3 计算结果</MenuItem>
                <MenuItem value="REJECTED">拒绝采用</MenuItem>
            </TextField>
            <TextField disabled={locked} multiline size="small" label="采用、拒绝或待补理由" value={rationale} inputProps={{ maxLength: 1000 }} onChange={edit(setRationale)} />
            <Button onClick={submit} disabled={locked || (decision === 'ACCEPTED' && !met) || !/^reviewer-[a-z0-9][a-z0-9._-]{7,95}$/.test(actor.trim()) || !rationale.trim()}>保存本次采用记录</Button>
            <Button onClick={download} disabled={busy || !saved}>下载实验、计算和采用记录</Button>
        </Stack>
    );
}
