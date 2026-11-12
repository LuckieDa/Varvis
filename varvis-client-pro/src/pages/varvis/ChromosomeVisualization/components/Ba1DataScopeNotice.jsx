import React from 'react';
import { Alert } from '@mui/material';

export default function Ba1DataScopeNotice({ scope, bs1Scope, pm2Enabled = false }) {
    if (!scope) return null;
    if (scope.policyId !== 'BA1_LOCAL_EXOMES_V1' || scope.version !== '1.0.0' || scope.dataset !== 'EXOMES') {
        return <Alert severity="warning">BA1 数据范围声明不受支持；请查看完整原始记录，勿据此采用结果。</Alert>;
    }
    const localBs1 = bs1Scope?.policyId === 'BS1_LOCAL_EXOMES_V1' && bs1Scope.version === '1.0.0' &&
        bs1Scope.dataset === 'EXOMES' && bs1Scope.release === scope.release;
    const routedBs1 = bs1Scope?.policyId === 'POLICY_ROUTED_POPULATION_V1' && bs1Scope.dataset === 'POLICY_SELECTED';
    if (routedBs1) return <Alert severity="info">
        BA1 本地模式使用 gnomAD {scope.release} EXOMES；BS1 按各背景已核政策分别选择 JOINT 或 EXOMES，实际数据集、频率口径及来源见当前背景详情，不能跨数据集相加或借用患者性别。
        {pm2Enabled ? 'PM2 独立核验其来源、检测量及适用条件。' : ''}政策路由声明不表示已有可采用结果；未完成、失败与未启用按各项保存事实展示。这不是最终变异分类。
    </Alert>;
    return <Alert severity="info">
        {localBs1 ? 'BA1／BS1' : 'BA1'} 本地模式：仅使用 gnomAD {scope.release} 外显子组数据。满足／不满足均指本地数据政策下的判定；缺项保留未评价原因。
        未包含基因组和联合频率，未评估外显子组与基因组间的差异。本模式不查询远程数据。
        {localBs1 ? <>BS1 按确认的疾病与遗传方式匹配阈值；缺少适用政策时明确未评价。
            {pm2Enabled ? 'PM2 在同次运行中独立核验本地频率、检测量及适用条件。' : 'PM2 暂不执行。'}</> : 'PM2／BS1 暂不执行。'}
        这不是最终变异分类，临床采用尚未核定。
    </Alert>;
}
