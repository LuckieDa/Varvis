export const criterionId = code => `ACMG_AMP_2015:${code}`;
export const EVIDENCE_GROUPS = [
    { title: '人群频率', items: [['BA1', '人群频率过高'], ['BS1', '频率高于疾病预期'], ['PM2', '低频或充分检测后未检出']] },
    { title: '计算与剪接预测', items: [['PP3', '计算预测支持有害影响'], ['BP4', '计算预测支持无有害影响'], ['BP7', '限定同义或内含子变异']] },
    { title: '蛋白变化与机制', items: [['PS1', '相同蛋白或剪接影响'], ['PM5', '同残基不同替换'], ['PVS1', '功能缺失'], ['PM4', '蛋白长度改变'], ['BP3', '重复区框内变异'], ['PM1', '热点或关键功能区域'], ['PP2', '错义致病机制'], ['BP1', '截短主导背景下的错义变异']] },
    { title: '病例与家系文献', items: [['PS2', '亲子关系确认的新生变异'], ['PM6', '亲子关系未确认的新生变异'], ['PM3', '隐性疾病反式或相关病例'], ['PP1', '家系共分离']] },
];
export const orderedSelection = (selected, available) => available.filter(id => selected.includes(id));
export function toggleGroup(selected, ids, available) {
    const remove = ids.every(id => selected.includes(id));
    return available.filter(id => remove ? selected.includes(id) && !ids.includes(id) : selected.includes(id) || ids.includes(id));
}
