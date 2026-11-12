const geneOf = (row) =>
    row?.exomiserCandidateFeatures?.geneSymbol || row?.candidateIdentity?.geneSymbol;
const keyOf = (row) => row?.observation?.key;

const declarationSource = (locator) => ({
    source: 'SYNTHETIC_GROUP_MEETING_DECLARATION',
    version: '1.0.0',
    recordLocator: locator,
});

export function buildTeachingDeclaration(manifest, candidates) {
    const ids = candidates.map((candidate) => candidate?.candidateId);
    if (
        ids.length !== 20 ||
        ids.some((id) => typeof id !== 'string' || !id.trim()) ||
        new Set(ids).size !== ids.length
    ) {
        throw new Error('需要 20 条身份完整且不重复的候选变异，未提交规则评价。');
    }
    const hypotheses = candidates.map((candidate) => {
        const key = keyOf(candidate);
        const gene = geneOf(candidate);
        const allele = manifest.variants.find(
            (v) =>
                key &&
                v.contig.replace(/^chr/, '') === key.chromosome.replace(/^chr/, '') &&
                v.position === key.position &&
                v.reference === key.reference &&
                v.alternate === key.alternate &&
                v.gene === gene,
        );
        const declared = manifest.geneDeclarations.find((d) => d.gene === gene);
        if (!allele || !declared) throw new Error('候选与教学声明不匹配，未提交规则评价。');
        const dot = declared.transcript.lastIndexOf('.');
        return {
            affectedStatus: declared.affectedStatus,
            hypothesis: {
                candidateId: candidate.candidateId,
                hypothesisId: 'teaching-hypothesis',
                hgncId: declared.hgncId,
                mondoId: declared.mondoId,
                moi: declared.moi,
                provenance: declarationSource(`demo-manifest.json#gene=${gene}`),
            },
            transcript: {
                accession: declared.transcript.slice(0, dot),
                version: Number(declared.transcript.slice(dot + 1)),
                selectionPolicy: 'EXPLICIT_GROUP_DEMO_COMPATIBILITY_V1',
                annotationSource: 'VEP',
                annotationSourceVersion: '116.0',
                selectionReason: '教学材料显式指定公开资源兼容转录本，非疾病特异临床选择。',
                source: declarationSource(`demo-manifest.json#gene=${gene}/transcript`),
            },
        };
    });
    return {
        topN: 20,
        declarationVersion: 'group-demo-v1',
        sampleId: manifest.sampleId,
        dataClass: 'SYNTHETIC',
        patient: { ...manifest.patient, source: declarationSource('demo-manifest.json#patient') },
        hypotheses,
    };
}

export function resolveTeachingAnalysisId(currentId, search, savedId) {
    return currentId || new URLSearchParams(search).get('analysisId') || savedId || '';
}

export function validateTeachingJob(job, manifest) {
    if (
        job.fileSha256 !== manifest.vcfSha256 ||
        job.genomeAssembly !== 'GRCh38' ||
        (job.analysisAssembly && job.analysisAssembly !== 'GRCh38') ||
        (job.status === 'READY' &&
            (job.selectedSampleId !== manifest.sampleId ||
                job.rankingMode !== manifest.rankingMode))
    ) {
        throw new Error('当前任务不符合 GRCh38 教学 VCF、样本及无 HPO 排名要求，未提交规则评价。');
    }
}

export function summarizeAcmgBlockers(run) {
    const messages = {
        ACMG_BUSINESS_EXECUTION_DISABLED:
            '该次运行时，后端未启用 PP3/BP4 显式评价，没有查询 AM 分数或执行规则。',
        ACMG_TRUSTED_RUNTIME_CONFIGURATION_UNAVAILABLE: '规则所需的本地资源或来源回执配置不完整。',
        EXECUTION_SOURCE_UNAVAILABLE: '该分析缺少可信执行来源，需要在配置修复后重新分析 VCF。',
    };
    const counts = new Map();
    for (const candidate of run?.candidates || []) {
        const codes = new Set(
            (candidate.attempts || []).flatMap((attempt) =>
                (attempt.blockers || []).map((blocker) => blocker.reasonCode).filter(Boolean),
            ),
        );
        for (const code of codes) counts.set(code, (counts.get(code) || 0) + 1);
    }
    return [...counts].map(([code, count]) => ({
        code,
        count,
        message: messages[code] || '证据或输入未通过前置核验，请查看该候选的依据。',
    }));
}
