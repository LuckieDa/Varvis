import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync(
    new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgTeaching.js', import.meta.url),
    'utf8',
);
const {
    buildTeachingDeclaration,
    resolveTeachingAnalysisId,
    validateTeachingJob,
    summarizeAcmgBlockers,
} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const manifest = JSON.parse(
    fs.readFileSync(
        new URL('../public/demo/group-meeting/demo-manifest.json', import.meta.url),
        'utf8',
    ),
);
const candidates = ['HBB', 'GJB2']
    .flatMap((gene) => manifest.variants.filter((v) => v.gene === gene).slice(0, 10))
    .map((v, i) => ({
        candidateId: `candidate-${i + 1}`,
        exomiserCandidateFeatures: { geneSymbol: v.gene },
        observation: {
            key: {
                chromosome: v.contig,
                position: v.position,
                reference: v.reference,
                alternate: v.alternate,
            },
        },
    }));
const job = {
    status: 'READY',
    genomeAssembly: 'GRCh38',
    analysisAssembly: 'GRCh38',
    fileSha256: manifest.vcfSha256,
    selectedSampleId: manifest.sampleId,
    rankingMode: manifest.rankingMode,
};
let checks = 0;
function check(name, run) {
    run();
    checks++;
    console.log(`PASS ${name}`);
}

check('current Analysis Lab job wins over URL and previous teaching job', () => {
    assert.equal(
        resolveTeachingAnalysisId('current-job', '?analysisId=other-job', 'old-job'),
        'current-job',
    );
});
check('standalone compatibility preserves explicit URL then saved job', () => {
    assert.equal(resolveTeachingAnalysisId('', '?analysisId=url-job', 'saved-job'), 'url-job');
    assert.equal(resolveTeachingAnalysisId('', '', 'saved-job'), 'saved-job');
});
check('native teaching job passes', () => validateTeachingJob(job, manifest));
for (const [field, value] of [
    ['genomeAssembly', 'GRCh37'],
    ['analysisAssembly', 'GRCh37'],
    ['fileSha256', '0'.repeat(64)],
    ['selectedSampleId', 'another-sample'],
    ['rankingMode', 'EXOMISER_G1'],
]) {
    check(`reject mismatched ${field}`, () =>
        assert.throws(() => validateTeachingJob({ ...job, [field]: value }, manifest)),
    );
}
check('declaration preserves all 20 candidate identities and order', () => {
    const declaration = buildTeachingDeclaration(manifest, candidates);
    assert.equal(declaration.topN, 20);
    assert.equal(declaration.dataClass, 'SYNTHETIC');
    assert.equal(declaration.sampleId, manifest.sampleId);
    assert.deepEqual(
        declaration.hypotheses.map((h) => h.hypothesis.candidateId),
        candidates.map((c) => c.candidateId),
    );
    for (let i = 0; i < candidates.length; i++) {
        const gene = manifest.geneDeclarations.find(
            (d) => d.gene === candidates[i].exomiserCandidateFeatures.geneSymbol,
        );
        const input = declaration.hypotheses[i];
        assert.equal(input.hypothesis.hgncId, gene.hgncId);
        assert.equal(input.hypothesis.mondoId, gene.mondoId);
        assert.equal(`${input.transcript.accession}.${input.transcript.version}`, gene.transcript);
    }
});
check('ranking input remains untouched', () => {
    const before = structuredClone(candidates);
    buildTeachingDeclaration(manifest, candidates);
    assert.deepEqual(candidates, before);
});
for (const count of [0, 19, 21]) {
    check(`reject ${count} candidates`, () =>
        assert.throws(() =>
            buildTeachingDeclaration(
                manifest,
                Array.from({ length: count }, (_, i) => ({
                    ...candidates[i % 20],
                    candidateId: `candidate-${i}`,
                })),
            ),
        ),
    );
}
for (const [name, mutate] of [
    [
        'duplicate identity',
        (rows) => {
            rows[1].candidateId = rows[0].candidateId;
        },
    ],
    [
        'missing identity',
        (rows) => {
            delete rows[0].candidateId;
        },
    ],
    [
        'unknown allele',
        (rows) => {
            rows[0].observation.key.position = 1;
        },
    ],
    [
        'wrong gene binding',
        (rows) => {
            rows[0].exomiserCandidateFeatures.geneSymbol = 'GJB2';
        },
    ],
]) {
    check(`reject ${name}`, () => {
        const changed = structuredClone(candidates);
        mutate(changed);
        assert.throws(() => buildTeachingDeclaration(manifest, changed));
    });
}
check('disabled execution is explained and counted once per candidate', () => {
    const blocked = {
        candidates: [
            {
                attempts: [
                    { blockers: [{ reasonCode: 'ACMG_BUSINESS_EXECUTION_DISABLED' }] },
                    { blockers: [{ reasonCode: 'ACMG_BUSINESS_EXECUTION_DISABLED' }] },
                ],
            },
        ],
    };
    const before = structuredClone(blocked);
    const summary = summarizeAcmgBlockers(blocked);
    assert.equal(summary[0].count, 1);
    assert.match(summary[0].message, /没有查询 AM 分数或执行规则/);
    assert.deepEqual(blocked, before);
});
check('unknown blockers keep their source code and successes add no blocker', () => {
    assert.deepEqual(summarizeAcmgBlockers({ candidates: [{ attempts: [{ blockers: [] }] }] }), []);
    const result = summarizeAcmgBlockers({
        candidates: [{ attempts: [{ blockers: [{ reasonCode: 'FUTURE_BLOCKER' }] }] }],
    });
    assert.equal(result[0].code, 'FUTURE_BLOCKER');
});
check('teaching controls do not contain corrupted question-mark labels', () => {
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/TeachingAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.doesNotMatch(source + panel, /\?{3,}/);
    assert.match(panel, /查看依据/);
    assert.match(panel, /查看第/);
});
console.log(
    `ACMG teaching input contract: ${checks}/${checks} PASS (synthetic fixtures; no provider calls).`,
);
