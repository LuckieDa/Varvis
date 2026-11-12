import assert from 'node:assert/strict';
import fs from 'node:fs';
import frontendModuleUrl from './frontend-module-url.cjs';
const raw = fs.readFileSync(
    new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url),
    'utf8',
);
const {
    acmgRequestError,
    annotationSelectable,
    automaticCaseForm,
    enabledCriteria,
    currentBusinessCriteria,
    pm1ScopeClaim,
    pm1SavedRun,
    declarationDraftIdentity,
    declaredLiteratureContext,
    populationPreparation,
    evidenceCards,
    evidenceSummary,
    evidenceDetailValue,
    recommendationLabel,
    reasonHelp,
    pm2ContextPreparationDetails,
    relationshipClassificationLabel,
    ba1Details,
    buildCaseDeclaration,
    buildSimplifiedCaseDeclaration,
    candidateInputStatus,
    contextTranscriptRecommendation,
    criterionLabel,
    emptyHypothesis,
    hasTranscriptPreferenceDifference,
    hypothesisIdOf,
    prepareSimplifiedInputs,
    recommendedTranscript,
    transcriptOptions,
    validateInputOptions,
    validateSavedRun,
} = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
let checks = 0;
const filter = process.env.VARVIS_CONTRACT_FILTER ? new RegExp(process.env.VARVIS_CONTRACT_FILTER) : null;
const test = (label, run) => {
    if (filter && !filter.test(label)) return;
    run();
    checks++;
    console.log(`PASS ${label}`);
};

test('current server scope has no historical or failure fallback', () => {
    const ids = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2'];
    const current = { enabledCriteria: ids, executionProfile: { contextPreparation: 'POPULATION_CONTEXT_ONLY_V1', enabledCriteria: ids } };
    assert.deepEqual(currentBusinessCriteria(current), ids);
    assert.deepEqual(currentBusinessCriteria(null), []);
    assert.deepEqual(currentBusinessCriteria({ enabledCriteria: [...ids, 'ACMG_AMP_2015:PVS1'] }), []);
    assert.deepEqual(currentBusinessCriteria({ ...current, enabledCriteria: [] }), []);
    const parsed = currentBusinessCriteria(current);
    parsed.reverse();
    assert.deepEqual(currentBusinessCriteria(current), ids); // Display consumers cannot mutate the snapshot.
    const rawPrediction = { enabledCriteria: ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'],
        executionProfile: { profileId: 'acmg-pp3-bp4-development-v1', profileVersion: '1.0.0',
            contextPreparation: 'PREDICTION_CONTEXT_ONLY_V1', enabledCriteria: ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'] } };
    assert.deepEqual(currentBusinessCriteria(rawPrediction), rawPrediction.enabledCriteria);
    const legacy = { ba1: {}, attempts: [{ pvs1: { evaluationResult: { status: 'MET' } }, pm2: {} }] };
    const before = JSON.stringify(legacy);
    assert.deepEqual(evidenceCards(legacy, currentBusinessCriteria(current)).map((v) => v.id), ids);
    assert.equal(JSON.stringify(legacy), before);
});

test('successful compact rows do not invent missing-evidence warnings', () => {
    assert.equal(evidenceSummary({ evaluation: { evaluationResult: { status: 'MET' } }, reasons: [] }), '满足本次适用条件');
    assert.match(evidenceSummary({ evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PM2_DISEASE_CATALOG_NO_RECORD' } } }), /目录.*未命中/);
});

test('draft changes across analysis, Top-N, data class and candidate background do not identify saved statements', () => {
    const base = declarationDraftIdentity('a', 20, 'SYNTHETIC', { c: { stableRelationshipKey: 'r1' } });
    for (const args of [['b', 20, 'SYNTHETIC'], ['a', 5, 'SYNTHETIC'], ['a', 20, 'PRIVACY_BOUNDED_RUNTIME']])
        assert.notEqual(base, declarationDraftIdentity(...args, { c: { stableRelationshipKey: 'r1' } }));
    assert.notEqual(base, declarationDraftIdentity('a', 20, 'SYNTHETIC', { c: { stableRelationshipKey: 'r2' } }));
});

test('absence coverage keeps independent sources and zero false null without invented variant frequency', () => {
    const candidate = { attempts: [{ pm2: { evidence: { queryOutcome: { status: 'NO_RECORD' }, measurements: [] },
        allSitesEvidence: [{ source: { sourceId: 'EXOMES' }, resourceExecution: { status: 'SUCCEEDED' }, queryOutcome: { status: 'HIT' }, measurements: [
            { metricId: 'gnomad.exomes.allSites.adjAn', normalizedValue: 0 },
            { metricId: 'gnomad.exomes.allSites.regionFlag.outside_broad_capture_region', normalizedValue: false },
        ] }, { source: { sourceId: 'GENOMES' }, resourceExecution: { status: 'FAILED' }, measurements: [
            { metricId: 'gnomad.genomes.allSites.adjAn', normalizedValue: null },
        ] }] } }] };
    const before = JSON.stringify(candidate);
    const details = Object.fromEntries(evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0].details);
    assert.equal(details['gnomad.exomes.allSites.adjAn'], 0);
    assert.equal(details['gnomad.exomes.allSites.regionFlag.outside_broad_capture_region'], false);
    assert.equal(evidenceDetailValue(details['gnomad.exomes.allSites.regionFlag.outside_broad_capture_region']), 'false');
    assert.equal(evidenceDetailValue(0), 0);
    assert.equal(evidenceDetailValue(null), '未记录');
    assert.notEqual(details['gnomad.genomes.allSites.adjAn'], '0');
    assert.doesNotMatch(details['人群频率'], /^0[（ ]/);
    assert.match(details['实际取证分支'], /无记录/);
    assert.match(details['检测 AN 含义'], /不相加/);
    assert.equal(JSON.stringify(candidate), before);
});
test('database wait and HTTP timeout give recovery guidance without claiming model failure', () => {
    assert.match(
        acmgRequestError({ response: { data: { message: 'ACMG_DATABASE_BUSY' } } }),
        /数据库等待锁超时/,
    );
    assert.match(acmgRequestError({ code: 'ECONNABORTED' }), /后台可能仍在运行/);
    assert.match(acmgRequestError({ code: 'ETIMEDOUT' }), /先读取保存结果/);
    assert.equal(acmgRequestError({ message: 'Existing failure' }), 'Existing failure');
});
const row = (i) => {
    const value = {
        rank: i + 1,
        candidateId: `candidate-${i}`,
        geneSymbol: 'BRAF',
        catalog: {
            hgncId: 'HGNC:1097',
            relationships: [
                {
                    hgncId: 'HGNC:1097',
                    mondoId: 'MONDO:0015280',
                    moi: 'AD',
                    diseaseLabel: 'BRAF-related disorder',
                    classification: 'Definitive',
                    reportLocator: 'catalog/braf-1',
                },
            ],
        },
        transcriptAnnotations: [
            {
                transcriptAccession: 'ENST00000646891.2',
                geneSymbol: 'BRAF',
                source: 'VEP',
                version: '116.0',
                status: 'COMPLETED',
                maneSelect: true,
            },
        ],
    };
    value.recommendation = {
        recommendationId: `server-rec-${i}`,
        policyId: 'ORDINARY_CATALOG_AND_AM_IDENTITY',
        policyVersion: '1.0.0',
        policyHash: 'server-policy-hash',
        hypothesisId: `server-candidate-${i}-h1`,
        generatedAt: '2026-09-07T10:00:00Z',
        executionSourceSha256: 'server-source-hash',
        status: 'READY_FOR_CONFIRMATION',
        relationshipStatus: 'AUTO_RECOMMENDED',
        transcriptStatus: 'RESOURCE_COMPATIBLE_RECOMMENDATION',
        stableRelationshipKey: `stable-key-${i}`,
        recommendedAnnotationId: `annotation-${i}`,
        relationships: [
            {
                ...value.catalog.relationships[0],
                stableRelationshipKey: `stable-key-${i}`,
                selectable: true,
            },
        ],
        annotations: [
            { annotationId: `annotation-${i}`, annotationIndex: 0, identityCompatible: true },
        ],
        resourceCompatibleTranscript: { annotationId: `annotation-${i}`, annotationIndex: 0 },
    };
    return value;
};
const options = (n) => ({
    analysisId: 'ordinary',
    requestedN: 20,
    selectedCount: n,
    sampleId: 'NON_TEACHING_SAMPLE',
    candidates: Array.from({ length: n }, (_, i) => row(i)),
    candidateIds: Array.from({ length: n }, (_, i) => `candidate-${i}`),
});
const form = {
    declarationVersion: 'case-v1',
    patientContextId: 'patient-context-ordinary-case',
    sex: 'UNKNOWN',
    recordLocator: 'case-records/decl-1',
    dataClass: 'PRIVACY_BOUNDED_RUNTIME',
};
const h = {
    ...emptyHypothesis(row(0)),
    mondoId: 'MONDO:0015280',
    moi: 'AD',
    affectedStatus: 'UNKNOWN',
    transcriptIndex: '0',
    selectionReason: 'Explicit test confirmation of the annotated MANE reference; not a diagnosis',
    recordLocator: 'case-records/decl-1/h1',
};
test('fewer than 20 and formal Top-20 without hash, teaching sample or gene restrictions', () => {
    [1, 3, 20].forEach((n) =>
        assert.equal(validateInputOptions(options(n), 'ordinary').selectedCount, n),
    );
});
test('ordinary declarations are not synthetic and contain no trusted client objects', () => {
    const value = buildCaseDeclaration(options(1), 'ordinary', form, { 'candidate-0': [h] });
    assert.equal(value.dataClass, 'PRIVACY_BOUNDED_RUNTIME');
    assert.equal(value.sampleId, 'NON_TEACHING_SAMPLE');
    assert.equal(value.hypotheses[0].transcript.version, 2);
    assert.equal(value.patient.familyContextId, null);
    for (const text of [
        'SYNTHETIC',
        'READY',
        'SourceRef',
        'sha256',
        'executionWitness',
        'contextId',
    ])
        assert.ok(!JSON.stringify(value).includes(text));
});
test('legacy manual builder keeps missing hypothesis and transcript explicit', () => {
    const value = buildCaseDeclaration(options(2), 'ordinary', form, {
        'candidate-0': [emptyHypothesis(row(0))],
    });
    assert.equal(value.topN, 20);
    assert.deepEqual(value.hypotheses, []);
    assert.equal(emptyHypothesis(row(0)).transcriptIndex, '');
});
test('UI only applies versioned backend recommendations using stable keys', () => {
    const o = options(1);
    const prepared = prepareSimplifiedInputs(o);
    assert.deepEqual(prepared['candidate-0'], {
        recommendationId: 'server-rec-0',
        stableRelationshipKey: 'stable-key-0',
        annotationId: 'annotation-0',
        literatureContext: null,
    });
    assert.equal(declaredLiteratureContext(o.candidates[0], prepared['candidate-0'], {
        ...o, enabledCriteria: ['ACMG_AMP_2015:PP1'],
    }), null, 'catalog and MANE recommendations never invent a literature disease background');
    assert.equal(candidateInputStatus(o.candidates[0], prepared['candidate-0']).ready, true);
    assert.equal(
        recommendedTranscript(o.candidates[0]).transcript.versionedId,
        'ENST00000646891.2',
    );
    delete o.candidates[0].recommendation;
    assert.equal(
        recommendedTranscript(o.candidates[0]),
        null,
        'MANE alone cannot create a frontend recommendation',
    );
    assert.equal(
        candidateInputStatus(o.candidates[0], prepareSimplifiedInputs(o)['candidate-0']).ready,
        false,
    );
});
test('blank choices never alias the first catalog or annotation row', () => {
    const status = candidateInputStatus(row(0), { stableRelationshipKey: '', annotationId: '' });
    assert.equal(status.ready, false);
    assert.equal(status.relationship, undefined);
});
test('review-required stable relationship choice survives array reorder; changed recommendation resets confirmation', () => {
    const o = options(1);
    const rec = o.candidates[0].recommendation;
    rec.relationships.push({
        ...rec.relationships[0],
        stableRelationshipKey: 'second-key',
        mondoId: 'MONDO:0000002',
    });
    rec.relationshipStatus = 'REVIEW_REQUIRED';
    rec.stableRelationshipKey = null;
    const prepared = prepareSimplifiedInputs(o);
    assert.equal(prepared['candidate-0'].stableRelationshipKey, '');
    prepared['candidate-0'].stableRelationshipKey = 'second-key';
    rec.relationships.reverse();
    assert.equal(
        candidateInputStatus(o.candidates[0], prepareSimplifiedInputs(o, prepared)['candidate-0'])
            .relationship.mondoId,
        'MONDO:0000002',
    );
    rec.recommendationId = 'server-rec-revised';
    assert.equal(prepareSimplifiedInputs(o, prepared)['candidate-0'].stableRelationshipKey, '');
});
test('simplified request generates technical provenance but preserves strict declarations', () => {
    const o = options(1);
    const prepared = prepareSimplifiedInputs(o);
    const value = buildSimplifiedCaseDeclaration(o, 'ordinary', prepared);
    assert.deepEqual(value, buildSimplifiedCaseDeclaration(o, 'ordinary', prepared));
    assert.equal(value.declarationVersion, 'ordinary-ui-v3');
    assert.equal(automaticCaseForm('ordinary').declarationVersion, 'ordinary-ui-v3');
    assert.equal(
        automaticCaseForm('ordinary').recordLocator,
        'analysis/ordinary/acmg/ordinary-ui-v3',
    );
    assert.equal(value.patient.source.recordLocator, 'analysis/ordinary/acmg/ordinary-ui-v3');
    assert.equal(value.recommendations[0].policyHash, 'server-policy-hash');
    assert.equal(value.hypotheses[0].hypothesis.hypothesisId, 'server-candidate-0-h1');
    assert.equal(value.patient.patientContextId, 'patient-context-ordinary');
    assert.equal(value.patient.sex, 'UNKNOWN');
    assert.equal(value.patient.familyContextId, null);
    assert.equal(value.hypotheses[0].affectedStatus, 'UNKNOWN');
    assert.equal(value.hypotheses[0].hypothesis.mondoId, 'MONDO:0015280');
    assert.equal(
        value.hypotheses[0].transcript.selectionPolicy,
        'ORDINARY_CATALOG_AND_AM_IDENTITY@1.0.0',
    );
    assert.match(value.hypotheses[0].transcript.selectionReason, /No score\/class was consulted/);
    assert.deepEqual(automaticCaseForm('ordinary'), automaticCaseForm('ordinary'));
});
test('simplified request blocks empty and forged catalog choices before submission', () => {
    const o = options(1);
    assert.throws(() =>
        buildSimplifiedCaseDeclaration(o, 'ordinary', {
            'candidate-0': { stableRelationshipKey: '', annotationId: '' },
        }),
    );
    const prepared = prepareSimplifiedInputs(o);
    o.candidates[0].recommendation.relationships[0].hgncId = 'HGNC:9999';
    assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', prepared));
});
test('same allele separate rows, local IDs reused, multiple hypotheses retain order', () => {
    const value = buildCaseDeclaration(options(2), 'ordinary', form, {
        'candidate-0': [h, { ...h, hypothesisId: 'h2' }],
        'candidate-1': [h],
    });
    assert.deepEqual(
        value.hypotheses.map((v) => [v.hypothesis.candidateId, v.hypothesis.hypothesisId]),
        [
            ['candidate-0', 'h1'],
            ['candidate-0', 'h2'],
            ['candidate-1', 'h1'],
        ],
    );
});
test('duplicate local hypotheses rejected', () =>
    assert.throws(() =>
        buildCaseDeclaration(options(1), 'ordinary', form, { 'candidate-0': [h, h] }),
    ));
test('task identity, order, duplicate rows and invalid Top-N rejected', () => {
    for (const mutate of [
        (o) => {
            o.analysisId = 'other';
        },
        (o) => o.candidates.reverse(),
        (o) => {
            o.candidateIds[1] = o.candidateIds[0];
        },
        (o) => {
            o.requestedN = 1.5;
        },
    ]) {
        const o = options(2);
        mutate(o);
        assert.throws(() => validateInputOptions(o, 'ordinary'));
    }
});
test('version, source, gene and annotation ambiguity never silently repaired', () => {
    for (const update of [
        { transcriptAccession: 'ENST00000646891' },
        { transcriptAccession: 'ENST00000646891.4294967297' },
        { version: '' },
        { source: '' },
        { geneSymbol: 'KRAS' },
        { status: 'FAILED' },
    ]) {
        const c = row(0);
        Object.assign(c.transcriptAnnotations[0], update);
        assert.equal(transcriptOptions(c)[0].eligible, false);
    }
    const c = row(0);
    c.transcriptAnnotations.push({ ...c.transcriptAnnotations[0] });
    assert.ok(transcriptOptions(c).every((t) => !t.eligible));
});
test('server-derived HGVSc version is reused without rewriting the raw annotation', () => {
    const c = row(0);
    const raw = c.transcriptAnnotations[0];
    raw.transcriptAccession = 'ENST00000646891';
    raw.transcriptId = 'ENST00000646891';
    raw.hgvsCoding = 'ENST00000646891.2:c.10A>T';
    c.transcriptChoices = [
        { annotationIndex: 0, versionedTranscriptId: 'ENST00000646891.2', transcriptVersion: 2 },
    ];
    const before = JSON.stringify(c);
    const choice = transcriptOptions(c)[0];
    assert.equal(choice.eligible, true);
    assert.equal(choice.versionedId, 'ENST00000646891.2');
    assert.equal(JSON.stringify(c), before);
});
test('missing transcript preserved; no default replacement', () =>
    assert.equal(
        buildCaseDeclaration(options(1), 'ordinary', form, {
            'candidate-0': [{ ...h, transcriptIndex: '' }],
        }).hypotheses[0].transcript,
        null,
    ));
test('missing sex or source blocked, UNKNOWN is explicit', () => {
    for (const delta of [
        { sex: '' },
        { recordLocator: '' },
        { recordLocator: '/private/case' },
        { recordLocator: 'C:\\case' },
    ])
        assert.throws(() =>
            buildCaseDeclaration(options(1), 'ordinary', { ...form, ...delta }, {}),
        );
});
test('revision and explicit retry are distinct while repeat bytes are stable', () => {
    const build = (f = form, retry) =>
        buildCaseDeclaration(options(1), 'ordinary', f, { 'candidate-0': [h] }, retry);
    assert.deepEqual(build(), build());
    assert.notDeepEqual(build({ ...form, declarationVersion: 'case-v2' }), build());
    assert.equal(build(form, 'previous-run').retryOf, 'previous-run');
});
test('saved run association and order checked without altering nodes', () => {
    const r = {
        analysisId: 'ordinary',
        candidateIds: ['a', 'b'],
        candidates: [{ candidateId: 'a' }, { candidateId: 'b' }],
    };
    const before = JSON.stringify(r);
    assert.equal(validateSavedRun(r, 'ordinary'), r);
    assert.equal(JSON.stringify(r), before);
    assert.throws(() => validateSavedRun(r, 'other'));
    r.candidates.reverse();
    assert.throws(() => validateSavedRun(r, 'ordinary'));
});
test('missing and failure never displayed as NOT_MET', () => {
    assert.equal(criterionLabel(null), '原因待核实');
    assert.equal(criterionLabel({ evaluationResult: { status: 'NOT_EVALUATED' } }), '原因待核实');
    assert.equal(criterionLabel({ evaluationResult: { status: 'NOT_MET' } }), '已评价 · 未满足');
});
test('actual dispatch wrapper keeps local hypothesis identity and old source-failure fallback', () => {
    assert.equal(
        hypothesisIdOf({ preflight: { original: { hypothesisId: 'local-h1' } } }),
        'local-h1',
    );
    assert.equal(hypothesisIdOf({ hypothesisId: 'old-h1' }), 'old-h1');
});
test('invalid patient/family identity is stopped before a request', () => {
    for (const delta of [
        { patientContextId: 'case-without-contract-prefix' },
        { familyContextId: 'family' },
    ]) {
        assert.throws(() =>
            buildCaseDeclaration(options(1), 'ordinary', { ...form, ...delta }, {}),
        );
    }
});
test('backend manifest drives three rules; old runs remain two rules without mutation', () => {
    const ids = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4', 'ACMG_AMP_2015:BA1'];
    assert.deepEqual(enabledCriteria({ enabledCriteria: ids }), ids);
    const old = { analysisId: 'ordinary', status: 'COMPLETED' };
    assert.deepEqual(enabledCriteria(old), ids.slice(0, 2));
    assert.equal(old.enabledCriteria, undefined);
});
test('BA1 can be submitted with no disease or transcript choices and no new form fields', () => {
    const input = options(1);
    input.enabledCriteria = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4', 'ACMG_AMP_2015:BA1'];
    input.candidates[0].catalog.relationships = [];
    input.candidates[0].recommendation.relationships = [];
    input.candidates[0].recommendation.annotations = [];
    input.candidates[0].transcriptAnnotations = [];
    const request = buildSimplifiedCaseDeclaration(
        input,
        'ordinary',
        prepareSimplifiedInputs(input),
    );
    assert.equal(request.hypotheses.length, 0);
    assert.equal(request.ba1, undefined);
    assert.equal(request.executionProfile, undefined);
});
test('BA1 details use the returned policy and exact values, never another UI threshold', () => {
    const value = {
        policy: {
            metricId: 'profile.metric',
            threshold: { rawValue: '0.123456789' },
            comparisonOperator: 'GREATER_THAN',
        },
        evidence: {
            source: { sourceId: 'GNOMAD_PROFILE_RESOURCE', resourceVersion: 'pinned' },
            measurements: [{ metricId: 'profile.metric', rawValue: '0.0123456789' }],
        },
        evaluation: {
            evaluationResult: { status: 'NOT_MET' },
            deterministicTrace: {
                parameters: [
                    { name: 'frequency.ancestry', value: 'nfe' },
                    { name: 'frequency.selectedAncestryAn', value: '9000' },
                ],
            },
        },
    };
    const details = ba1Details(value);
    assert.equal(details.threshold, '0.123456789');
    assert.equal(details.frequency, '0.0123456789');
    assert.equal(details.an, '9000');
    assert.equal(details.ancestry, 'nfe');
    assert.equal(details.exception, undefined);
    assert.equal(criterionLabel(details.evaluation), '已评价 · 未满足');
    assert.equal(ba1Details(null).frequency, undefined);
});
test('ordinary view uses one dynamic matrix without a BA1-specific area or teaching download', () => {
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.match(panel, /enabledCriteria\(run\)/);
    assert.match(
        panel,
        /<CandidateEvidenceResults[^>]*candidate=\{candidate\}[^>]*criteria=\{displayedCriteria\}[^>]*run=\{run\}/,
    );
    assert.match(panel, /<AcmgResultsWorkbench[^>]*run=\{run\}[^>]*criteria=\{displayedCriteria\}/);
    assert.match(panel, /<AcmgEvidenceMatrix[^>]*candidate=\{candidate\}[^>]*enabledCriteria=\{criteria\}[^>]*run=\{run\}[^>]*backgroundIndex=\{index\}/);
    assert.match(panel, /pm1ScopeClaim\(run\) \? pm1SavedRun\(run\) \? enabledCriteria\(run\) : \[\]/);
    // Saved-scope compatibility is exercised with actual SSR in local-batch1-contract-smoke.mjs.
    assert.ok(!panel.includes('Ba1Result'));
    const matrix = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.match(matrix, /backgroundEvidenceCards\(candidate, enabledCriteria, run, index\)/);
    assert.match(matrix, /flexDirection: 'column'/);
    assert.ok(!matrix.includes("id === 'ACMG_AMP_2015:BA1'"));
    assert.ok(!panel.includes("{['PP3', 'BP4'].map"));
    assert.ok(!panel.includes('下载 ACMG 教学 VCF'));
    assert.ok(!panel.includes('!readyCount ||'));
    assert.match(panel, /历史规则范围/);
});
test('compact matrix preserves saved nodes, independent states and actionable data gaps', () => {
    const ids = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
    const c = {
        ba1: {
            evaluation: {
                evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'BA1_EXCEPTION_HIT' },
            },
            evidence: {
                measurements: [
                    {
                        metricId: 'gnomad.recordInputReadinessReasonCodes',
                        rawValue: 'SELECTED_ANCESTRY_AN_MISSING|FAF95_UNAVAILABLE',
                    },
                ],
            },
        },
        attempts: [
            {
                hypothesisId: 'first',
                criterionEvaluations: [
                    { criterionId: ids[1], evaluationResult: { status: 'NOT_MET' } },
                    {
                        criterionId: ids[2],
                        evaluationResult: { status: 'MET' },
                        appliedStrength: 'SUPPORTING',
                    },
                ],
            },
        ],
    };
    const before = JSON.stringify(c);
    const cards = evidenceCards(c, ids);
    assert.deepEqual(
        cards.map((card) => card.id),
        ids,
    );
    assert.deepEqual(
        cards.map((card) => criterionLabel(card.entries[0].evaluation)),
        ['不适用：命中 BA1 例外', '已评价 · 未满足', '已评价 · 满足'],
    );
    assert.ok(
        cards[0].entries[0].details.some(
            ([label, value]) =>
                label === '数据缺口' && value.includes('SELECTED_ANCESTRY_AN_MISSING'),
        ),
    );
    assert.equal(criterionLabel(evidenceCards({}, ids)[0].entries[0].evaluation), '原因待核实');
    assert.equal(JSON.stringify(c), before);
    assert.equal(evidenceCards({}, enabledCriteria({})).length, 2);
});
test('no compatible transcript is explicit before submission while BA1 still runs', () => {
    const o = options(1);
    o.enabledCriteria = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
    const rec = o.candidates[0].recommendation;
    rec.annotations = [];
    rec.recommendedAnnotationId = null;
    rec.resourceCompatibleTranscript = null;
    rec.transcriptStatus = 'NO_COMPATIBLE_TRANSCRIPT';
    rec.status = 'NO_COMPATIBLE_TRANSCRIPT';
    assert.equal(
        buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o)).hypotheses.length,
        0,
    );
    assert.match(recommendationLabel(rec.status), /没有精确兼容/);
    assert.match(recommendationLabel('REVIEW_REQUIRED'), /人工复核/);
    assert.match(recommendationLabel('RESOURCE_COMPATIBLE_RECOMMENDATION'), /计算资源兼容/);
});
test('identical preferred and compatible transcripts keep ordinary collapse behavior', () => {
    const c = row(0);
    c.recommendation.clinicalPreferredTranscript = {
        ...c.recommendation.resourceCompatibleTranscript,
    };
    const before = JSON.stringify(c);
    assert.equal(hasTranscriptPreferenceDifference(c), false);
    assert.equal(
        candidateInputStatus(c, prepareSimplifiedInputs({ candidates: [c] })[c.candidateId]).ready,
        true,
    );
    assert.equal(JSON.stringify(c), before);
});
test('different preferred and compatible transcripts are visible without changing selection', () => {
    const c = row(0);
    c.recommendation.clinicalPreferredTranscript = { annotationId: 'clinical-preferred' };
    const before = JSON.stringify(c);
    assert.equal(hasTranscriptPreferenceDifference(c), true);
    assert.equal(JSON.stringify(c), before);
    assert.equal(
        prepareSimplifiedInputs({ candidates: [c] })[c.candidateId].annotationId,
        'annotation-0',
    );
    // Safe versioned identity comparison when an older projection lacks annotation IDs.
    c.recommendation = {
        annotationPreferred: { versionedTranscriptId: 'ENST00000646891.1' },
        resourceCompatibleTranscript: { versionedTranscriptId: 'ENST00000646891.2' },
    };
    assert.equal(hasTranscriptPreferenceDifference(c), true);
    c.recommendation.annotationPreferred.versionedTranscriptId = 'ENST00000646891.2';
    assert.equal(hasTranscriptPreferenceDifference(c), false);
});
test('current preparation shows separate BA1 and PM2 states and one explicit confirmation', () => {
    const panel = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx', import.meta.url), 'utf8');
    assert.match(panel, /preparation.ba1/);
    assert.match(panel, /preparation.pm2/);
    // Saved calibration, association, ordinary preparation, REVEL and fixed PS2 have separate confirmations.
    assert.match(panel, /associationOnly \? <>/);
    assert.match(panel, /calibrationOnly/);
    assert.equal((panel.match(/<Checkbox\b/g) || []).length, 5);
    assert.match(panel, /revelEnabled && <>/);
    assert.ok(!panel.includes('SplicingExperimentCheckPanel'));
    assert.ok(!panel.includes('HNF1A GN017'));
    const candidates = [row(0), row(1), row(2)];
    candidates[0].recommendation.clinicalPreferredTranscript = { annotationId: 'different' };
    candidates[1].recommendation.clinicalPreferredTranscript = { annotationId: 'annotation-1' };
    assert.equal(candidates.filter(hasTranscriptPreferenceDifference).length, 1);
});
test('legacy runs without recommendation fields remain readable and are not assigned a difference', () => {
    const legacy = { analysisId: 'ordinary', status: 'COMPLETED' };
    const before = JSON.stringify(legacy);
    assert.equal(validateSavedRun(legacy, 'ordinary'), legacy);
    for (const value of [
        undefined,
        {},
        { recommendation: {} },
        { recommendation: { clinicalPreferredTranscript: { annotationId: 'old' } } },
    ])
        assert.equal(hasTranscriptPreferenceDifference(value), false);
    assert.deepEqual(enabledCriteria(legacy), ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4']);
    assert.equal(JSON.stringify(legacy), before);
});
test('persisted recommendation blockers have exact Chinese explanations', () => {
    const expected = {
        NO_COMPATIBLE_TRANSCRIPT:
            '没有满足当前预测路径要求的带版本转录本；请检查匹配或剪接位置的具体原因。',
        CANDIDATE_MOI_REQUIRED: '候选结果缺少可核验的遗传模式。',
        CANDIDATE_MOI_UNMAPPABLE: '候选遗传模式不能无损映射到当前证据合同。',
        CANDIDATE_MOI_CONFLICT: '候选来源中的遗传模式互相冲突。',
        CANDIDATE_RELATIONSHIP_MOI_CONFLICT: '候选遗传模式与该基因—疾病关系不一致。',
        NO_ELIGIBLE_RELATIONSHIP: '当前目录中没有可用于该证据上下文的合格关系。',
        DISEASE_RELATIONSHIP_REVIEW_REQUIRED: '存在多个或不确定的基因—疾病关系，需要复核。',
        TRANSCRIPT_REVIEW_REQUIRED: '存在多个兼容转录本，当前不能自动确定。',
    };
    for (const [code, explanation] of Object.entries(expected))
        assert.equal(reasonHelp(code), explanation);
});
test('saved PP3/BP4 cards retain separate raw blockers without inventing evaluations', () => {
    const ids = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
    const blockers = [
        { reasonCode: 'CANDIDATE_MOI_CONFLICT' },
        { reasonCode: 'NO_COMPATIBLE_TRANSCRIPT' },
    ];
    const saved = {
        attempts: [{ blockers, criterionEvaluations: [], atomicEvidence: [], context: null }],
    };
    const before = JSON.stringify(saved);
    const cards = evidenceCards(saved, ids);
    assert.deepEqual(
        cards.map((c) => c.id),
        ids,
    );
    for (const card of cards.slice(1)) {
        assert.deepEqual(card.entries[0].reasons, blockers);
        assert.equal(card.entries[0].evaluation, undefined);
        assert.equal(criterionLabel(card.entries[0].evaluation), '原因待核实');
    }
    const matrix = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.match(
        matrix,
        /entry\.reasons\.map\([\s\S]*?component="div"[\s\S]*?reasonHelp\(b.reasonCode \|\| b, entry.reasonScope(?:, entry.strictReasonHelp)?\)[\s\S]*?\{b.reasonCode \|\| b\}/,
    );
    assert.equal(JSON.stringify(saved), before);
});
test('legacy explicit-input missing reason explains confirmation without changing saved status', () => {
    const old = { attempts: [{ blockers: [{ reasonCode: 'EXPLICIT_INPUT_MISSING' }] }] };
    const entry = evidenceCards(old, ['ACMG_AMP_2015:PP3'])[0].entries[0];
    assert.equal(entry.reasons[0].reasonCode, 'EXPLICIT_INPUT_MISSING');
    assert.match(reasonHelp(entry.reasons[0].reasonCode), /显式确认.*解释背景/);
    assert.doesNotMatch(reasonHelp(entry.reasons[0].reasonCode), /^查看原始原因|材料或资格不足/);
    assert.equal(criterionLabel(entry.evaluation), '原因待核实');
});
test('four-rule splice cards retain BP4 dependency and VEP position without AM labels', () => {
    const candidate = {
        attempts: [
            {
                transcriptInput: {
                    predictionPath: 'SPLICEAI_INTRONIC',
                    hgvsc: 'ENST000001.1:c.100+7A>G',
                    intronicSignedOffset: 7,
                },
                atomicEvidence: [
                    {
                        source: { sourceId: 'SPLICEAI_RAW' },
                        evidenceId: 'splice-evidence',
                        measurements: [
                            { metricId: 'spliceai.maximumRawDeltaScore', rawValue: '0.01' },
                        ],
                    },
                ],
                criterionEvaluations: ['PP3', 'BP4', 'BP7'].map((id) => ({
                    criterionId: `ACMG_AMP_2015:${id}`,
                    evaluationResult: { status: id === 'PP3' ? 'NOT_MET' : 'MET' },
                })),
            },
        ],
    };
    const cards = evidenceCards(
        candidate,
        ['PP3', 'BP4', 'BA1', 'BP7'].map((id) => `ACMG_AMP_2015:${id}`),
    );
    assert.deepEqual(
        cards.map((c) => c.code),
        ['PP3', 'BP4', 'BA1', 'BP7'],
    );
    assert.equal(cards[0].entries[0].details[0][0], 'SpliceAI 最大分数');
    assert.equal(cards[0].entries[0].details[0][1], '0.01');
    const bp7 = cards[3].entries[0];
    assert.equal(bp7.details.find(([k]) => k === '依赖 BP4')[1], '已评价 · 满足');
    assert.match(bp7.details.find(([k]) => k === '内含子位置')[1], /偏移 7 bp/);
    assert.ok(!bp7.details.some(([k]) => k.includes('AlphaMissense')));
});
test('evidence details show saved scores and thresholds without JSON dumps or internal identifiers', () => {
    const candidate = {
        attempts: [
            {
                transcriptInput: {
                    predictionPath: 'SPLICEAI_INTRONIC',
                    hgvsc: 'ENST000001.1:c.100-30A>G',
                    intronicSignedOffset: -30,
                },
                atomicEvidence: [
                    {
                        source: { sourceId: 'SPLICEAI_RAW' },
                        measurements: [
                            { metricId: 'spliceai.maximumRawDeltaScore', rawValue: '0.04' },
                        ],
                    },
                ],
                criterionEvaluations: ['PP3', 'BP4', 'BP7'].map((code) => ({
                    criterionId: `ACMG_AMP_2015:${code}`,
                    evaluationResult: { status: code === 'PP3' ? 'NOT_MET' : 'MET' },
                    deterministicTrace: {
                        parameters: [
                            { name: 'threshold.pp3.minInclusive', value: '0.234' },
                            { name: 'threshold.bp4.maxInclusive', value: '0.123' },
                            { name: 'threshold.intronicAcceptorMaximum', value: '-25' },
                            { name: 'threshold.intronicDonorMinimum', value: '9' },
                        ],
                    },
                })),
            },
        ],
    };
    const before = JSON.stringify(candidate);
    const cards = evidenceCards(
        candidate,
        ['PP3', 'BP4', 'BP7'].map((c) => `ACMG_AMP_2015:${c}`),
    );
    assert.equal(cards[0].entries[0].details.find(([k]) => k === '本次阈值')[1], '≥ 0.234');
    assert.equal(cards[1].entries[0].details.find(([k]) => k === '本次阈值')[1], '≤ 0.123');
    assert.match(
        cards[2].entries[0].details.find(([k]) => k === '内含子位置')[1],
        /≤ -25 或 ≥ 9 bp/,
    );
    for (const card of cards) {
        assert.ok(card.entries[0].details.length <= 6);
        assert.ok(
            !card.entries[0].details.some(([label]) =>
                /引用|哈希|resourceExecution|resourceAvailability|queryOutcome|resourceApplicability/.test(
                    label,
                ),
            ),
        );
    }
    assert.equal(JSON.stringify(candidate), before);
    const matrix = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.ok(!matrix.includes('JSON.stringify'));
    assert.ok(!matrix.includes('component="pre"'));
    assert.match(matrix, /reasonCode &&/); // No missing-evidence warning on successful results with no reason code.
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.match(panel, /下载本次原始结果 JSON/);
});
test('outer BP7 nonapplicability is a display fallback and does not mutate saved output', () => {
    const candidate = {
        attempts: [
            {
                transcriptInput: { predictionPath: 'ALPHAMISSENSE_MISSENSE' },
                bp7Disposition: {
                    status: 'NOT_EVALUATED',
                    applicability: 'NOT_APPLICABLE',
                    reasonCode: 'BP7_CONSEQUENCE_NOT_APPLICABLE',
                },
            },
        ],
    };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:BP7'])[0].entries[0];
    assert.equal(criterionLabel(entry.evaluation), '不适用');
    assert.equal(JSON.stringify(candidate), before);
});
test('missing BP7 input does not claim AM execution and intronic choices show server HGVSc', () => {
    const entry = evidenceCards({ attempts: [{}] }, ['ACMG_AMP_2015:BP7'])[0].entries[0];
    assert.equal(entry.details[0][1], '未取得可执行预测路径');
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.match(panel, /t\.hgvsp\s*\|\|\s*t\.hgvsc/);
});
test('PM2 is a saved population card with its own policy and no input mutation', () => {
    const candidate = { attempts: [{ pm2: {
        evaluation: { evaluationResult: { status: 'MET' }, appliedStrength: 'SUPPORTING',
            deterministicTrace: { parameters: [
                { name: 'frequency.raw', value: '0.00000068' },
                { name: 'threshold.raw', value: '0.00000156' },
                { name: 'comparison.operator', value: 'LESS_THAN_OR_EQUAL' },
                { name: 'frequency.metricId', value: 'gnomad.joint.faf95max' },
            ] } },
        policy: { authority: { specificationId: 'GN078', authorityVersion: '1.1' } },
        evidence: { source: { resourceVersion: 'gnomAD joint v4.1 GRCh38' }, queryOutcome: { status: 'HIT' } },
    } }] };
    const before = JSON.stringify(candidate);
    const card = evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0];
    assert.equal(card.code, 'PM2（支持级）');
    assert.equal(card.entries[0].evaluation.evaluationResult.status, 'MET');
    assert.match(card.entries[0].details.find(([label]) => label === '本次阈值')[1], /≤ 0\.00000156/);
    assert.equal(JSON.stringify(candidate), before);
    const missing = evidenceCards({ attempts: [{ pm2: { evaluationResult: {
        status: 'NOT_EVALUATED', reasonCode: 'PM2_ABSENCE_DETECTION_INSUFFICIENT',
    } } }] }, ['ACMG_AMP_2015:PM2'])[0].entries[0];
    assert.equal(missing.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.match(reasonHelp('PM2_ABSENCE_DETECTION_INSUFFICIENT'), /不能视为频率为零/);
});
test('PM2 converted saved object owns its reasons and preserves missing history fields', () => {
    const context = { gene: { hgncId: 'HGNC:12687' }, disease: { mondoId: 'MONDO:0008667' }, moi: 'AUTOSOMAL_DOMINANT',
        variant: { inputAllele: { assembly: 'GRCh37' }, evidenceQueryAllele: { assembly: 'GRCh38' } } };
    const saved = { context, policy: { allowWellCoveredAbsence: false },
        evidence: { queryOutcome: { status: 'NO_RECORD' } }, allSitesConfigurationIssue: 'PM2_ALL_SITES_NOT_CONFIGURED',
        evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PM2_ABSENCE_DETECTION_INSUFFICIENT' } } };
    const candidate = { ba1: { evaluationResult: { status: 'MET' } }, attempts: [{
        blockers: [{ reasonCode: 'PRE_RANKING_LIFTOVER_UNSUPPORTED' }], pm2Context: context, pm2ContextBlockers: [], pm2: saved,
    }] };
    const roundTrip = JSON.parse(JSON.stringify(candidate));
    const entry = evidenceCards(roundTrip, ['ACMG_AMP_2015:PM2'])[0].entries[0];
    assert.equal(entry.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(entry.evaluation.appliedStrength, undefined);
    assert.deepEqual(entry.reasons, []);
    assert.equal(entry.details.find(([label]) => label === '输入 / 查询参考基因组')[1], 'GRCh37 / GRCh38');
    assert.match(entry.details.find(([label]) => label === '目录关系')[1], /HGNC:12687/);
    delete roundTrip.attempts[0].pm2;
    assert.equal(evidenceCards(roundTrip, ['ACMG_AMP_2015:PM2'])[0].entries[0].evaluation, undefined);
});
test('PM2 count policies and policy gaps retain observed data without inventing a frequency cutoff', () => {
    const candidate = { attempts: [{ pm2: {
        evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED' },
        evidence: { queryOutcome: { status: 'HIT' }, measurements: [
            { metricId: 'gnomad.joint.ac', rawValue: '1' },
            { metricId: 'gnomad.joint.an', rawValue: '2000' },
            { metricId: 'gnomad.joint.af', rawValue: '0.0005' },
        ] },
    } }] };
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0];
    assert.equal(criterionLabel(entry.evaluation), '判定资料不足');
    assert.match(entry.details[0][1], /0\.0005.*仅展示/);
    assert.equal(entry.details[2][1], '1 / 2000');
    assert.match(reasonHelp('PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED'), /不会使用全基因统一阈值/);
    candidate.attempts[0].pm2.policy = { maximumAlleleCount: '1', minimumObservedAlleleNumber: '2000' };
    const count = evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0];
    assert.equal(count.details[1][1], 'AC ≤ 1；AN ≥ 2000');
    candidate.attempts[0].pm2.knownSpecifications = [{ specificationId: 'GN_SYNTHETIC', version: '1', sourceUrl: 'https://example.org/pm2-policy' }];
    candidate.attempts[0].pm2.evidence.resourceExecution = { status: 'TIMEOUT' };
    const failed = evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0];
    assert.match(failed.details.find(([label]) => label === '待接通专用规范')[1], /GN_SYNTHETIC v1 https:\/\/example.org\/pm2-policy/);
    assert.equal(failed.details.find(([label]) => label === '人口查询执行')[1], '查询超时');
});
for (const frequencyPurpose of [false, true]) {
    test(`${frequencyPurpose ? 'HNF1A frequency' : 'PM2-only'} can confirm its disease relationship without choosing a prediction transcript`, () => {
        const o = options(1);
        const c = o.candidates[0];
        c.recommendation.resourceCompatibleTranscript = null;
        c.recommendation.recommendedAnnotationId = null;
        c.recommendation.transcriptStatus = 'REVIEW_REQUIRED';
        c.recommendation.pm2ContextPreparation = {
            available: true,
            purpose: 'PM2_ONLY_NOT_PREDICTION_CONFIRMATION',
            clinicalAnnotation: { versionedTranscriptId: 'ENST00000646891.2' },
            eligibleRelationshipKeys: ['stable-key-0'],
        };
        if (frequencyPurpose) c.recommendation.hnf1aFrequencyContextPreparation = {
            ...c.recommendation.pm2ContextPreparation,
            purpose: 'HNF1A_DISEASE_BA1_BS1_AND_PM2_NOT_PREDICTION_CONFIRMATION',
            selectionPolicy: 'HNF1A_FREQUENCY_VERIFIED_CLINICAL_ANNOTATION@1.0.0',
        };
        const prepared = prepareSimplifiedInputs(o);
        const status = candidateInputStatus(c, prepared[c.candidateId]);
        assert.equal(status.ready, false);
        assert.equal(status.pm2Ready, true);
        const declaration = buildSimplifiedCaseDeclaration(o, 'ordinary', prepared);
        assert.deepEqual(declaration.hypotheses, []);
        assert.equal(declaration.recommendations[0].stableRelationshipKey, 'stable-key-0');
        assert.equal(declaration.recommendations[0].annotationId, null);
        assert.equal(candidateInputStatus(c, { ...prepared[c.candidateId], stableRelationshipKey: '' }).pm2Ready, false);
        c.recommendation.executionSourceSha256 = null;
        assert.equal(candidateInputStatus(c, prepared[c.candidateId]).pm2Ready, false);
        assert.match(reasonHelp('PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED'), /预测规则仍需确认/);
        assert.equal(reasonHelp('PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED'),
            '当前仅确认人口频率用途；PVS1 仍需确认其疾病相关转录本。');
    });
}

test('PVS1 displays saved mechanism inputs and preserves unimplemented NMD branches', () => {
    const candidate = { attempts: [{ pvs1: {
        evaluation: { evaluationResult: { status: 'MET' }, appliedStrength: 'VERY_STRONG' },
        policy: { authority: { specificationId: 'GN017', authorityVersion: '3.1' } },
        resolvedInput: { nmdPrediction: 'PREDICTED_NMD', cdsPosition: 511,
            ptcStart: 511, ptcEnd: 513, distanceToLastJunction: 1255,
            refCodon: 'CGA', altCodon: 'TGA', exonOrdinal: 2, exonTotal: 10 },
    } }] };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PVS1'])[0].entries[0];
    assert.equal(entry.evaluation.appliedStrength, 'VERY_STRONG');
    assert.match(entry.details[0][1], /GN017 \/ 3\.1/);
    assert.match(entry.details[5][1], /1255/);
    assert.match(entry.details[6][1], /非实验/);
    assert.equal(JSON.stringify(candidate), before);
    const absent = evidenceCards({ attempts: [{ pvs1: { evaluationResult: {
        status: 'NOT_EVALUATED', reasonCode: 'PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED',
    } } }] }, ['ACMG_AMP_2015:PVS1'])[0].entries[0];
    assert.equal(absent.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.match(reasonHelp('PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED'), /不代表良性/);
});
for (const predictionPath of ['PVS1_STOP_GAINED_CONTEXT', 'PVS1_FRAMESHIFT_CONTEXT']) {
    test(`${predictionPath} confirms disease transcript without claiming prediction compatibility`, () => {
        const value = options(1);
        const candidate = value.candidates[0];
        candidate.geneSymbol = 'HNF1A';
        candidate.catalog.hgncId = 'HGNC:11621';
        Object.assign(candidate.transcriptAnnotations[0], {
            geneSymbol: 'HNF1A', transcriptAccession: 'ENST00000257555.11',
            consequence: predictionPath === 'PVS1_FRAMESHIFT_CONTEXT' ? 'frameshift_variant' : 'stop_gained',
        });
        Object.assign(candidate.recommendation.relationships[0], {
            hgncId: 'HGNC:11621', mondoId: 'MONDO:0015967', moi: 'AD',
        });
        Object.assign(candidate.recommendation.annotations[0], {
            predictionPath, predictionCompatible: false, contextSelectable: true,
            versionedTranscriptId: 'ENST00000257555.11',
        });
        candidate.recommendation.resourceCompatibleTranscript = { ...candidate.recommendation.annotations[0] };
        candidate.recommendation.pm2ContextPreparation = {
            available: true, clinicalAnnotation: { ...candidate.recommendation.annotations[0] },
            eligibleRelationshipKeys: ['stable-key-0'],
        };
        const before = JSON.stringify(value);
        const inputs = prepareSimplifiedInputs(value);
        const status = candidateInputStatus(candidate, inputs[candidate.candidateId]);
        assert.equal(status.ready, true);
        assert.equal(status.pvs1ContextOnly, true);
        assert.equal(status.ps1Pm5Only, false);
        assert.equal(recommendedTranscript(candidate, status.relationship).kind, 'PVS1_CONTEXT_RECOMMENDATION');
        const declaration = buildSimplifiedCaseDeclaration(value, 'ordinary', inputs);
        assert.equal(declaration.hypotheses.length, 1);
        assert.match(declaration.hypotheses[0].transcript.selectionReason, /confirmed PVS1 disease transcript context/);
        assert.match(declaration.hypotheses[0].transcript.selectionReason, /prediction compatibility was not established/);
        assert.doesNotMatch(declaration.hypotheses[0].transcript.selectionReason, /computational resource compatibility,/);
        inputs[candidate.candidateId].annotationId = '';
        const pm2Only = candidateInputStatus(candidate, inputs[candidate.candidateId]);
        assert.equal(pm2Only.ready, false);
        assert.equal(pm2Only.pm2Ready, true);
        assert.equal(pm2Only.pvs1ContextOnly, false);
        assert.deepEqual(buildSimplifiedCaseDeclaration(value, 'ordinary', inputs).hypotheses, []);
        assert.equal(JSON.stringify(value), before);
    });
}

test('PVS1 frameshift displays altered CDS stop and junction separately from reference mapping', () => {
    for (const netLengthChange of [1, -1]) {
        const candidate = { attempts: [{ pvs1: {
            evaluation: { evaluationResult: { status: 'MET' }, appliedStrength: 'VERY_STRONG' },
            resolvedInput: {
                coordinateSystem: 'ALTERED_CDS', nmdPrediction: 'PREDICTED_NMD',
                cdsPosition: 1159, ptcStart: 1183, ptcEnd: 1185, ptcExonOrdinal: 6, exonTotal: 10,
                netLengthChange, firstChangedProteinPosition: 388, terminationDistance: 8,
                ptcReferenceStart: 1183 - netLengthChange, ptcReferenceEnd: 1185 - netLengthChange,
                referenceLastJunctionCdsPosition: 1768, mutantLastJunctionCdsPosition: 1768 + netLengthChange,
                distanceToLastJunction: 1768 + netLengthChange - 1185,
                sequenceDerivationStatus: 'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE',
            },
        } }] };
        const before = JSON.stringify(candidate);
        const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PVS1'])[0].entries[0];
        const details = Object.fromEntries(entry.details);
        assert.equal(details['提前终止位置'], '1183–1185（改变后 CDS）');
        assert.equal(details['末次外显子连接位置'], `${1768 + netLengthChange}（改变后 CDS）；1768（参考 CDS）`);
        assert.equal(details['新终止对应参考位置 / 外显子'], `c.${1183 - netLengthChange}–${1185 - netLengthChange}；6 / 10`);
        assert.equal(details['首个改变氨基酸 / 终止相对编号'], '388 / fsTer8');
        assert.match(details['移码序列核验'], /等位基因、编码与蛋白注释一致/);
        assert.equal(JSON.stringify(candidate), before);
    }
    const incomplete = evidenceCards({ attempts: [{ pvs1: {
        evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PVS1_TERMINATION_UNRESOLVED' },
        resolvedInput: { coordinateSystem: 'ALTERED_CDS' },
    } }] }, ['ACMG_AMP_2015:PVS1'])[0].entries[0];
    assert.equal(criterionLabel(incomplete.evaluation), '判定资料不足');
    assert.equal(Object.fromEntries(incomplete.details)['提前终止位置'], '未取得–未取得（改变后 CDS）');
    assert.equal(Object.fromEntries(incomplete.details)['NMD 判断'], '未完成本分支判断');
    const insertedPtc = evidenceCards({ attempts: [{ pvs1: { resolvedInput: {
        coordinateSystem: 'ALTERED_CDS', ptcStart: 364, ptcEnd: 366,
        ptcReferenceStart: 364, ptcReferenceMiddle: null, ptcReferenceEnd: 365,
        ptcReferenceMapping: 'CONTAINS_INSERTED_BASES', ptcExonOrdinal: 2, exonTotal: 10,
    } } }] }, ['ACMG_AMP_2015:PVS1'])[0].entries[0];
    assert.equal(Object.fromEntries(insertedPtc.details)['提前终止位置'], '364–366（改变后 CDS）');
    assert.equal(Object.fromEntries(insertedPtc.details)['新终止对应参考位置 / 外显子'],
        'c.364–365；2 / 10；终止密码子含插入碱基，部分无参考对应');
    assert.match(reasonHelp('PVS1_TERMINATION_UNRESOLVED'), /不足以确定移码后的首次终止位置/);
    assert.match(reasonHelp('PVS1_HGVSC_SEQUENCE_MISMATCH'), /改变后序列不一致/);
    assert.match(reasonHelp('PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED'), /尚未接入/);
    assert.match(reasonHelp('PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED'), /仅确认人口频率用途/);
    assert.match(reasonHelp('PVS1_NATIVE_SNV_REQUIRED'), /单核苷酸替换/);
});

test('PS1 and PM5 share the matrix but preserve independent outcomes and evidence sources', () => {
    const ids = ['PP3', 'BP4', 'BA1', 'BP7', 'PS1', 'PM5'].map((code) => `ACMG_AMP_2015:${code}`);
    const comparison = {
        context: { transcript: { accession: 'NM_000001', version: 2 } },
        policy: {
            id: 'missense-scientific-policy',
            version: '2',
            authorityUrl: 'https://example.org/policy',
        },
        references: [
            {
                scv: 'SCV000001.2',
                vcv: 'VCV000001.3',
                classification: 'Pathogenic',
                independentEvidenceStatus: 'VERIFIED',
                independentEvidenceReason: 'PS3 and segregation reviewed without PS1/PM5',
                sourceUrl: 'https://example.org/reference',
            },
        ],
        spliceEvidence: [
            { role: 'TARGET', method: 'SpliceAI', status: 'VERIFIED', maximumScore: '0.02' },
            { role: 'REFERENCE', method: 'SpliceAI', status: 'VERIFIED', maximumScore: '0.03' },
        ],
        atomicEvidence: [
            {
                measurements: [
                    { metricId: 'vep.proteinRelation', rawValue: 'SAME_AMINO_ACID_CHANGE' },
                ],
            },
        ],
        criterionEvaluations: [
            {
                criterionId: ids[4],
                evaluationResult: { status: 'MET' },
                appliedStrength: 'MODERATE',
            },
            {
                criterionId: ids[5],
                evaluationResult: { status: 'NOT_MET' },
                appliedStrength: 'NONE',
            },
        ],
    };
    const candidate = {
        attempts: [
            {
                hypothesisId: 'one',
                criterionEvaluations: [
                    { criterionId: ids[0], evaluationResult: { status: 'MET' } },
                ],
                missenseComparison: comparison,
            },
        ],
    };
    const before = JSON.stringify(candidate);
    const cards = evidenceCards(candidate, ids);
    assert.deepEqual(
        cards.map((card) => card.id),
        ids,
    );
    assert.equal(cards[0].entries[0].evaluation, candidate.attempts[0].criterionEvaluations[0]);
    const ps1 = cards[4].entries[0];
    const pm5 = cards[5].entries[0];
    assert.equal(ps1.evaluation, comparison.criterionEvaluations[0]);
    assert.equal(ps1.evaluation.appliedStrength, 'MODERATE');
    assert.equal(criterionLabel(pm5.evaluation), '已评价 · 未满足');
    assert.match(ps1.details.find(([label]) => label === '检索到的蛋白关系')[1], /相同氨基酸/);
    assert.match(ps1.details.find(([label]) => label === '参考变异')[1], /SCV000001.2/);
    assert.match(ps1.details.find(([label]) => label === '独立致病依据')[1], /without PS1\/PM5/);
    assert.match(ps1.details.find(([label]) => label === '剪接核验')[1], /目标.*0.02.*参考.*0.03/);
    assert.match(ps1.details.find(([label]) => label === '证据依赖')[1], /不自动叠加强度/);
    assert.equal(ps1.links.length, 2);
    assert.ok(!ps1.details.some(([label]) => label.includes('AlphaMissense 分数')));
    assert.equal(JSON.stringify(candidate), before);
});
test('independent reference details preserve the source label and cannot create an evaluation', () => {
    const id = 'ACMG_AMP_2015:PS1';
    const candidate = { attempts: [{ missenseComparison: {
        references: [{ scv: 'SCV1.1', sourceAssertion: { reviewStatus: 'criteria provided, single submitter' },
            qualification: { reviewId: 'SYNTHETIC_REVIEW', status: 'REVIEWED', specificationVersion: 'TEST_ONLY',
                evidence: [{ code: 'PS3', strength: 'STRONG', sourceLocator: 'test figure 1' }] } }],
        criterionEvaluations: [{ criterionId: id, evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON' } }],
    } }] };
    const before = JSON.stringify(candidate);
    const card = evidenceCards(candidate, [id])[0].entries[0];
    const detail = card.details.find(([label]) => label === '参考原始证据核验')[1];
    assert.match(detail, /single submitter/); assert.match(detail, /PS3 STRONG/); assert.match(detail, /test figure 1/);
    assert.equal(card.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(JSON.stringify(candidate), before);
    for (const reason of ['REFERENCE_QUALIFICATION_POLICY_UNSUPPORTED', 'REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED',
        'REFERENCE_INDEPENDENT_REVIEW_MISSING', 'REFERENCE_INDEPENDENT_SPECIFICATION_MISMATCH',
        'REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH', 'REFERENCE_INDEPENDENT_SOURCE_UNBOUND',
        'REFERENCE_INDEPENDENT_EVIDENCE_INCOMPLETE', 'REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT',
        'REFERENCE_INDEPENDENT_STRENGTH_UNSUPPORTED', 'REFERENCE_INDEPENDENT_CRITERIA_MISMATCH',
        'REFERENCE_INDEPENDENT_EVIDENCE_INSUFFICIENT', 'REFERENCE_INDEPENDENT_CLASSIFICATION_MISMATCH'])
        assert.match(reasonHelp(reason), /参考|证据/);
});

test('independent reference adoption preserves classification layers and exports full source navigation', () => {
    const id = 'ACMG_AMP_2015:PM5';
    const comparison = {
        references: [{ scv: 'SCV1.1', classification: 'PATHOGENIC', adoptedClassification: 'LIKELY_PATHOGENIC',
            sourceAssertion: { classification: 'Pathogenic', comment: 'unchanged original' },
            qualification: { schemaVersion: 'missense-independent-reference-v2', adoptedClassification: 'LIKELY_PATHOGENIC' },
            expertEvidence: { use: 'SOURCE_NAVIGATION_ONLY', record: { reviewStatus: 'UNREVIEWED',
                appliedComparisonCriteria: ['PS1'], gapReasons: ['ORIGINAL_LABEL_MISSING'], original: { text: 'original text' } } },
        }],
        classificationConstraint: { referenceClassification: 'LIKELY_PATHOGENIC', maximumTargetClassification: 'LIKELY_PATHOGENIC' },
        criterionEvaluations: [{ criterionId: id, evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED' } }],
    };
    const candidate = { attempts: [{ missenseComparison: comparison }] };
    const original = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, [id])[0].entries[0];
    assert.match(entry.details.find(([label]) => label === '参考分类分层')[1], /原始分类 PATHOGENIC.*采用 LIKELY_PATHOGENIC/);
    assert.match(entry.details.find(([label]) => label === '专家资料来源与缺项')[1], /UNREVIEWED.*PS1.*ORIGINAL_LABEL_MISSING/);
    assert.match(entry.details.find(([label]) => label === '最终分类上限')[1], /已阻断/);
    assert.equal(entry.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(JSON.stringify(candidate), original);
    for (const reason of ['REFERENCE_ADOPTION_CLASSIFICATION_ROUTE_NOT_ENABLED', 'REFERENCE_ADOPTION_CLASSIFICATION_CHANGE_UNSUPPORTED',
        'MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED', 'MISSENSE_CLASSIFICATION_CEILING_POLICY_UNSUPPORTED',
        'EXPERT_EVIDENCE_BINDING_INVALID', 'EXPERT_EVIDENCE_IDENTITY_MISMATCH', 'EXPERT_EVIDENCE_ASSERTION_MISMATCH',
        'EXPERT_EVIDENCE_DRAFT_REQUIRED', 'EXPERT_EVIDENCE_SOURCE_UNBOUND', 'EXPERT_EVIDENCE_INDEPENDENT_REVIEW_REQUIRED'])
        assert.notEqual(reasonHelp(reason), reason);
});

test('missense missing, negative, and nonapplicable results remain distinct from AM outcomes', () => {
    const id = 'ACMG_AMP_2015:PM5';
    const candidate = {
        attempts: [
            {
                criterionEvaluations: [
                    { criterionId: 'ACMG_AMP_2015:PP3', evaluationResult: { status: 'MET' } },
                ],
                blockers: ['EXPLICIT_INPUT_MISSING'],
            },
        ],
    };
    const missing = evidenceCards(candidate, [id])[0].entries[0];
    assert.equal(criterionLabel(missing.evaluation), '原因待核实');
    assert.deepEqual(missing.reasons, ['EXPLICIT_INPUT_MISSING']);
    assert.match(missing.details.find(([label]) => label === '独立致病依据')[1], /未核实/);
    for (const status of ['NOT_MET', 'NOT_EVALUATED']) {
        candidate.attempts[0].missenseComparison = {
            blockers: ['REFERENCE_INDEPENDENCE_UNVERIFIED'],
            criterionEvaluations: [{ criterionId: id, evaluationResult: { status } }],
        };
        const entry = evidenceCards(candidate, [id])[0].entries[0];
        assert.equal(entry.evaluation.evaluationResult.status, status);
        assert.deepEqual(entry.reasons, ['REFERENCE_INDEPENDENCE_UNVERIFIED']);
    }
    candidate.attempts[0].missenseComparison.criterionEvaluations[0].criterionApplicability = {
        status: 'NOT_APPLICABLE',
    };
    assert.equal(criterionLabel(evidenceCards(candidate, [id])[0].entries[0].evaluation), '不适用');
});
test('saved rule discovery uses evidence nodes only and manifests remain authoritative', () => {
    const ps1 = 'ACMG_AMP_2015:PS1';
    const pm5 = 'ACMG_AMP_2015:PM5';
    const run = {
        candidates: [
            {
                attempts: [
                    {
                        missenseComparison: {
                            criterionEvaluations: [
                                { criterionId: ps1 },
                                { criterionId: pm5 },
                                { criterionId: ps1 },
                            ],
                        },
                    },
                ],
            },
        ],
    };
    assert.deepEqual(enabledCriteria(run), [ps1, pm5]);
    run.enabledCriteria = ['ACMG_AMP_2015:BP4'];
    assert.deepEqual(enabledCriteria(run), ['ACMG_AMP_2015:BP4']);
    assert.deepEqual(
        enabledCriteria({
            candidates: [
                { attempts: [{ missenseComparison: { blockers: ['RESOURCE_UNAVAILABLE'] } }] },
            ],
        }),
        ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'],
    );
});
test('missense source links are read-only HTTPS and opening the panel does not evaluate', () => {
    const candidate = {
        attempts: [
            {
                missenseComparison: {
                    policy: { authorityUrl: 'javascript:alert(1)' },
                    references: [
                        { sourceUrl: 'file:///private' },
                        { sourceUrl: 'https://example.org/ref' },
                    ],
                },
            },
        ],
    };
    assert.deepEqual(evidenceCards(candidate, ['ACMG_AMP_2015:PS1'])[0].entries[0].links, [
        { label: '参考来源 ', url: 'https://example.org/ref' },
    ]);
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    const effect = panel.slice(panel.indexOf('useEffect(() =>'), panel.indexOf('const act ='));
    assert.ok(!effect.includes('submitAcmgBusinessRun'));
    assert.ok(!effect.includes('submit('));
    assert.match(panel, /const initialN = candidateCount > 0 \? Math\.min\(20, candidateCount\) : 20/);
    assert.match(effect, /loadOptions\(initialN\)/);
    assert.match(panel, /不生成最终五级分类/);
    assert.ok(!panel.includes('三规则运行'));
});
test('VCEP scope relationships are labelled separately from gene disease validity', () => {
    assert.equal(
        relationshipClassificationLabel({ classification: 'VCEP_APPLICABLE' }),
        'VCEP 规范适用关系',
    );
    assert.equal(
        relationshipClassificationLabel({ relationshipType: 'VCEP_SPECIFICATION_SCOPE' }),
        'VCEP 规范适用关系',
    );
    assert.equal(relationshipClassificationLabel({ classification: 'Definitive' }), 'Definitive');
    assert.equal(relationshipClassificationLabel({}), '未取得');
});
const ps1Pm5OnlyOptions = () => {
    const value = options(1);
    const rec = value.candidates[0].recommendation;
    rec.resourceCompatibleTranscript = null;
    rec.annotations[0].identityCompatible = false;
    rec.annotations[0].ps1Pm5Compatible = true;
    rec.annotations[0].contextSelectable = true;
    rec.ps1Pm5CompatibleTranscript = {
        ...rec.annotations[0],
        versionedTranscriptId: 'ENST00000646891.2',
    };
    rec.transcriptStatus = 'PS1_PM5_TRANSCRIPT_RECOMMENDATION';
    Object.assign(rec.relationships[0], {
        relationshipType: 'VCEP_SPECIFICATION_SCOPE',
        mondoId: 'MONDO:0021060',
        moi: 'AD',
    });
    return value;
};
test('PS1 PM5 transcript fallback prepares its disease context without claiming AM compatibility', () => {
    const value = ps1Pm5OnlyOptions();
    const candidate = value.candidates[0];
    const before = JSON.stringify(value);
    const input = prepareSimplifiedInputs(value);
    const status = candidateInputStatus(candidate, input[candidate.candidateId]);
    assert.equal(status.ready, true);
    assert.equal(status.ps1Pm5Only, true);
    assert.equal(
        recommendedTranscript(candidate, status.relationship).kind,
        'PS1_PM5_COMPATIBLE_RECOMMENDATION',
    );
    const declaration = buildSimplifiedCaseDeclaration(value, 'ordinary', input);
    assert.equal(declaration.hypotheses[0].transcript.version, 2);
    assert.equal(declaration.hypotheses[0].hypothesis.mondoId, 'MONDO:0021060');
    assert.match(
        declaration.hypotheses[0].transcript.selectionReason,
        /AlphaMissense compatibility was not established/,
    );
    assert.equal(candidate.recommendation.annotations[0].identityCompatible, false);
    assert.equal(candidate.recommendation.resourceCompatibleTranscript, null);
    assert.equal(JSON.stringify(value), before);
});
test('PS1 PM5 transcript fallback cannot cross disease gene MOI or selectable scope', () => {
    for (const changed of [
        { mondoId: 'MONDO:0015280' },
        { hgncId: 'HGNC:4827' },
        { moi: 'AR' },
        { relationshipType: 'GENE_DISEASE_VALIDITY' },
        { selectable: false },
    ]) {
        const value = ps1Pm5OnlyOptions();
        const candidate = value.candidates[0];
        const relationship = candidate.recommendation.relationships[0];
        Object.assign(relationship, changed);
        const input = prepareSimplifiedInputs(value);
        assert.equal(
            annotationSelectable(candidate.recommendation.annotations[0], relationship),
            false,
        );
        assert.equal(contextTranscriptRecommendation(candidate, relationship), null);
        assert.equal(candidateInputStatus(candidate, input[candidate.candidateId]).ready, false);
        assert.throws(
            () => buildSimplifiedCaseDeclaration(value, 'ordinary', input),
            /尚无可提交候选/,
        );
    }
});
test('AM recommendation keeps priority and fallback UI states the separate rule boundary', () => {
    const value = ps1Pm5OnlyOptions();
    const candidate = value.candidates[0];
    candidate.recommendation.annotations[0].identityCompatible = true;
    candidate.recommendation.resourceCompatibleTranscript = {
        ...candidate.recommendation.annotations[0],
    };
    const relationship = candidate.recommendation.relationships[0];
    assert.equal(
        contextTranscriptRecommendation(candidate, relationship),
        candidate.recommendation.resourceCompatibleTranscript,
    );
    assert.equal(
        recommendedTranscript(candidate, relationship).kind,
        'RESOURCE_COMPATIBLE_RECOMMENDATION',
    );
    assert.equal(
        candidateInputStatus(candidate, prepareSimplifiedInputs(value)[candidate.candidateId])
            .ps1Pm5Only,
        false,
    );
    const panel = fs.readFileSync(
        new URL(
            '../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
            import.meta.url,
        ),
        'utf8',
    );
    assert.ok(!panel.includes("PS1/PM5 转录本可用"));
    assert.match(panel, /annotationSelectable\(t, relationship\)/);
    assert.match(panel, /annotationSelectable\(\s*annotation,\s*selected,?\s*\)/);
    assert.match(recommendationLabel('PS1_PM5_TRANSCRIPT_RECOMMENDATION'), /不代表 AM 兼容/);
});
test('RNA literature review states bounded unknown evidence and safe sources without changing results', () => {
    const comparison = {
        references: [
            {
                scv: 'SCV001424734.1',
                rnaReview: {
                    status: 'REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED',
                    rnaEffect: 'UNKNOWN',
                    normalRnaClaimed: false,
                    reviewedOn: '2026-09-09',
                    sourceUrls: ['https://example.org/rna', 'javascript:alert(1)'],
                },
            },
        ],
        criterionEvaluations: [
            { criterionId: 'ACMG_AMP_2015:PM5', evaluationResult: { status: 'NOT_MET' } },
        ],
        rnaReviewEvidence: [
            {
                reviewStatus: 'UNRESOLVED',
                sourceUrl: 'https://example.org/concern',
                description: '公开预测疑点，非 RNA 实验',
            },
        ],
    };
    const candidate = { attempts: [{ missenseComparison: comparison }] };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0].entries[0];
    assert.match(
        entry.details.find(([key]) => key === 'RNA 文献核验')[1],
        /有限来源检索.*RNA 影响仍未知.*不代表 RNA 正常/,
    );
    assert.match(
        entry.details.find(([key]) => key === '公开剪接疑点')[1],
        /UNRESOLVED.*非 RNA 实验/,
    );
    assert.equal(entry.evaluation.evaluationResult.status, 'NOT_MET');
    assert.ok(entry.links.some((link) => link.url === 'https://example.org/rna'));
    assert.ok(entry.links.some((link) => link.url === 'https://example.org/concern'));
    assert.ok(entry.links.every((link) => link.url.startsWith('https://')));
    assert.equal(JSON.stringify(candidate), before);
    comparison.references[0].rnaReview.normalRnaClaimed = true;
    const invalid = evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0].entries[0];
    assert.match(invalid.details.find(([key]) => key === 'RNA 文献核验')[1], /核验尚不充分/);
    assert.match(reasonHelp('PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW'), /尚未解决/);
});
test('synonymous BP7 shows saved splice score and oriented boundary evidence concisely', () => {
    const candidate = {
        attempts: [
            {
                transcriptInput: {
                    predictionPath: 'SPLICEAI_SYNONYMOUS',
                    hgvsc: 'ENST000001.4:c.30A>G',
                },
                atomicEvidence: [
                    {
                        source: { sourceId: 'SPLICEAI_RAW' },
                        measurements: [
                            { metricId: 'spliceai.maximumRawDeltaScore', rawValue: '0.04' },
                        ],
                    },
                ],
                criterionEvaluations: ['BP4', 'BP7'].map((code) => ({
                    criterionId: `ACMG_AMP_2015:${code}`,
                    evaluationResult: { status: 'MET' },
                    deterministicTrace: {
                        parameters: [
                            { name: 'position.synonymousAtExonFirstBase', value: 'false' },
                            { name: 'position.synonymousDistanceFromExonEnd', value: '60' },
                            { name: 'threshold.synonymousMinimumEndDistance', value: '3' },
                        ],
                    },
                })),
            },
        ],
    };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:BP7'])[0].entries[0];
    assert.deepEqual(entry.details[0], ['SpliceAI 最大分数', '0.04']);
    assert.match(
        entry.details.find(([label]) => label === '同义变异位置')[1],
        /首位：否；距末位 60 bp.*≥ 3/,
    );
    assert.ok(entry.details.length <= 6);
    assert.equal(JSON.stringify(candidate), before);
});
test('joint saved fallback discovers population and PVS1 without adding current rules', () => {
    const value = { candidates: [{ attempts: [{
        pm2: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PM2_POLICY_MISSING' } },
        pvs1: { evaluation: { criterionId: 'ACMG_AMP_2015:PVS1', evaluationResult: { status: 'MET' } } },
    }] }] };
    assert.deepEqual(enabledCriteria(value), ['ACMG_AMP_2015:PM2', 'ACMG_AMP_2015:PVS1']);
    value.enabledCriteria = ['ACMG_AMP_2015:PM2'];
    assert.deepEqual(enabledCriteria(value), value.enabledCriteria);
});
test('population fallback cannot hide an explicitly scoped missense recommendation', () => {
    const value = ps1Pm5OnlyOptions();
    const candidate = value.candidates[0];
    candidate.recommendation.resourceCompatibleTranscript = {
        ...candidate.recommendation.annotations[0], identityCompatible: true, predictionPath: 'POPULATION_ONLY',
    };
    const relationship = candidate.recommendation.relationships[0];
    assert.equal(contextTranscriptRecommendation(candidate, relationship), candidate.recommendation.ps1Pm5CompatibleTranscript);
    const status = candidateInputStatus(candidate, prepareSimplifiedInputs(value)[candidate.candidateId]);
    assert.equal(status.ready, true);
    assert.equal(status.ps1Pm5Only, true);
});

test('HNF1A REVEL confirms server-selected transcript without requiring an AM recommendation', () => {
    const value = options(1);
    const candidate = value.candidates[0];
    candidate.geneSymbol = 'HNF1A';
    candidate.catalog.hgncId = 'HGNC:11621';
    Object.assign(candidate.transcriptAnnotations[0], {
        geneSymbol: 'HNF1A', transcriptAccession: 'ENST00000257555.11', consequence: 'missense_variant',
    });
    Object.assign(candidate.recommendation.relationships[0], {
        hgncId: 'HGNC:11621', mondoId: 'MONDO:0015967', moi: 'AD',
    });
    Object.assign(candidate.recommendation.annotations[0], {
        predictionPath: 'HNF1A_REVEL', identityReason: 'HNF1A_REVEL_TRANSCRIPT_CONTEXT',
        versionedTranscriptId: 'ENST00000257555.11',
    });
    candidate.recommendation.resourceCompatibleTranscript = { ...candidate.recommendation.annotations[0] };
    const before = JSON.stringify(value);
    const inputs = prepareSimplifiedInputs(value);
    const status = candidateInputStatus(candidate, inputs[candidate.candidateId]);
    assert.equal(status.ready, true);
    assert.equal(recommendedTranscript(candidate, status.relationship).kind, 'HNF1A_REVEL_CONTEXT_RECOMMENDATION');
    assert.match(recommendationLabel('HNF1A_REVEL_CONTEXT_RECOMMENDATION'), /分数及资源身份在评价时核验/);
    assert.equal(buildSimplifiedCaseDeclaration(value, 'ordinary', inputs).hypotheses.length, 1);
    assert.match(reasonHelp('HNF1A_REVEL_TRANSCRIPT_CONTEXT'), /不以 AM 可用性为前提/);
    assert.equal(JSON.stringify(value), before);
});

const revelAttempt = (score = '0.15') => ({
    transcriptInput: {
        predictionPath: 'HNF1A_REVEL', tool: 'REVEL', hgvsp: 'ENSP00000257555.5:p.Arg263Cys',
        policyAuthority: { specificationId: 'GN017', authorityVersion: '3.1',
            sourceUrl: 'https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1' },
    },
    context: { transcript: { accession: 'ENST00000257555', version: 11 } },
    atomicEvidence: [{ source: { sourceId: 'REVEL_HNF1A_2021', resourceVersion: '2021-05-03' },
        measurements: [{ metricId: 'revel.score', rawValue: score }] }],
    criterionEvaluations: ['PP3', 'BP4'].map((code) => ({
        criterionId: `ACMG_AMP_2015:${code}`,
        appliedStrength: code === 'BP4' ? 'SUPPORTING' : 'NONE',
        evaluationResult: { status: code === 'BP4' ? 'MET' : 'NOT_MET' },
        deterministicTrace: { parameters: [
            { name: 'threshold', value: code === 'PP3' ? '0.70' : '0.15' },
            { name: 'comparison', value: code === 'PP3' ? 'GE' : 'LE' },
        ] },
    })),
    bp7Disposition: { status: 'NOT_EVALUATED', applicability: 'NOT_APPLICABLE',
        reasonCode: 'BP7_CONSEQUENCE_NOT_APPLICABLE' },
});

test('REVEL cards preserve returned score, thresholds, strength and authority without AM labels', () => {
    const candidate = { attempts: [revelAttempt()] };
    const before = JSON.stringify(candidate);
    const cards = evidenceCards(candidate, ['PP3', 'BP4', 'BP7'].map((code) => `ACMG_AMP_2015:${code}`));
    const pp3 = cards[0].entries[0];
    const bp4 = cards[1].entries[0];
    assert.deepEqual(pp3.details[0], ['REVEL 分数', '0.15']);
    assert.equal(pp3.details.find(([key]) => key === '本次阈值')[1], '≥ 0.70');
    assert.equal(bp4.details.find(([key]) => key === '本次阈值')[1], '≤ 0.15');
    assert.equal(bp4.evaluation.appliedStrength, 'SUPPORTING');
    assert.equal(bp4.details.find(([key]) => key === '适用规范')[1], 'GN017 / 3.1');
    assert.equal(bp4.links[0].url, candidate.attempts[0].transcriptInput.policyAuthority.sourceUrl);
    assert.ok(!pp3.details.some(([key]) => key.includes('AlphaMissense')));
    assert.match(cards[2].entries[0].details[0][1], /REVEL；不能作为 BP7 剪接依赖/);
    assert.equal(cards[2].entries[0].details.find(([key]) => key === '依赖 BP4')[1], '未取得可用于 BP7 的剪接 BP4');
    assert.equal(criterionLabel(cards[2].entries[0].evaluation), '不适用');
    assert.equal(JSON.stringify(candidate), before);
});

test('missing REVEL record never borrows an AM score or manufactures thresholds and safe links', () => {
    const attempt = revelAttempt();
    attempt.atomicEvidence = [{ source: { sourceId: 'ALPHAMISSENSE_V1' }, measurements: [
        { metricId: 'alphamissense.amPathogenicity', rawValue: '0.01' },
    ] }];
    attempt.criterionEvaluations = [];
    attempt.blockers = [{ reasonCode: 'REVEL_NO_RECORD' }];
    attempt.transcriptInput.policyAuthority.sourceUrl = 'javascript:alert(1)';
    const entry = evidenceCards({ attempts: [attempt] }, ['ACMG_AMP_2015:BP4'])[0].entries[0];
    assert.deepEqual(entry.details[0], ['REVEL 分数', '无可用分数']);
    assert.equal(entry.details.find(([key]) => key === '本次阈值')[1], '未记录');
    assert.equal(criterionLabel(entry.evaluation), '原因待核实');
    assert.deepEqual(entry.links, []);
    assert.match(reasonHelp('REVEL_NO_RECORD'), /不能作为 BP4 满足依据/);
    assert.match(reasonHelp('REVEL_RESOURCE_UNAVAILABLE'), /不回退 AM/);
    assert.match(reasonHelp('REVEL_RECORD_CONFLICT'), /不会挑选有利分数/);
    assert.match(reasonHelp('REVEL_SPLICE_BRANCH_NOT_IMPLEMENTED'), /剪接分支尚未接入/);
});

test('REVEL middle scores display both saved NOT_MET outcomes without classifying the variant', () => {
    const attempt = revelAttempt('0.42');
    for (const evaluation of attempt.criterionEvaluations) {
        evaluation.evaluationResult.status = 'NOT_MET';
        evaluation.appliedStrength = 'NONE';
    }
    const entries = evidenceCards({ attempts: [attempt] }, ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'])
        .map((card) => card.entries[0]);
    for (const entry of entries) {
        assert.deepEqual(entry.details[0], ['REVEL 分数', '0.42']);
        assert.equal(criterionLabel(entry.evaluation), '已评价 · 未满足');
        assert.doesNotMatch(JSON.stringify(entry.details), /最终 VUS|最终良性|最终致病/);
    }
});

test('HNF1A comparison exposes Grantham direction, historical reference and non-additive status', () => {
    const comparison = {
        references: [
            {
                scv: 'SCV002499518.2',
                classification: 'Likely Pathogenic',
                targetGrantham: 215,
                referenceGrantham: 101,
                originalSpecificationVersion: '3.0.0',
                evaluationDate: '2025-07-24',
                clinvarAssertionMethod: 'MDEP historical method',
                erepoId: 'public-reference',
                erepoUrl:
                    'https://erepo.clinicalgenome.org/evrepo/ui/interpretation/public-reference',
            },
        ],
        criterionEvaluations: [
            {
                criterionId: 'ACMG_AMP_2015:PM5',
                appliedStrength: 'SUPPORTING',
                evaluationResult: { status: 'MET' },
            },
        ],
    };
    const candidate = { attempts: [{ missenseComparison: comparison }] };
    const original = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0].entries[0];
    assert.match(
        entry.details.find(([key]) => key === 'Grantham 距离')[1],
        /野生型→目标 215；野生型→参考 101/,
    );
    assert.match(
        entry.details.find(([key]) => key === '参考原始分类版本')[1],
        /Likely Pathogenic.*2025-07-24.*3.0.0/,
    );
    assert.match(
        entry.details.find(([key]) => key === '证据依赖')[1],
        /REVEL.*不是本系统对目标的最终分类/,
    );
    assert.ok(entry.links.some((link) => link.label.startsWith('专家论据')));
    assert.equal(JSON.stringify(candidate), original);
    assert.match(
        reasonHelp('PS1_PM5_OVERLAPPING_SUPPORT_NOT_INDEPENDENT'),
        /不再重复采用.*不表示生物学阴性/,
    );
});

test('HNF1A BS1 and disease BA1 preserve FAF ancestry, missing values and the general BA1 scope', () => {
    const atomic = { source: { resourceVersion: 'gnomAD joint v4.1 GRCh38' }, queryOutcome: { status: 'HIT' }, measurements: [
        { metricId: 'gnomad.joint.faf95max', rawValue: '4.42800e-05' },
        { metricId: 'gnomad.joint.faf95max.ancestry', rawValue: 'nfe' },
        { metricId: 'gnomad.joint.groupmax.ancestry', rawValue: 'afr' },
        { metricId: 'gnomad.joint.ancestry.nfe.ac', rawValue: '65' },
        { metricId: 'gnomad.joint.ancestry.nfe.an', rawValue: '1180056' },
        { metricId: 'gnomad.joint.qc.statUnionPValue', rawValue: '0.3' },
    ] };
    const candidate = { ba1: { evaluation: { criterionId: 'ACMG_AMP_2015:BA1', evaluationResult: { status: 'NOT_MET' } } }, attempts: [{
        context: { gene: { hgncId: 'HGNC:11621' } }, hnf1aFrequency: { evidence: atomic,
            policy: { officialConditions: { BS1: { operator: 'GREATER_THAN_OR_EQUAL', decimal: '0.000033' }, BA1: { operator: 'GREATER_THAN_OR_EQUAL', decimal: '0.0001' } } },
            criterionEvaluations: [
                { criterionId: 'ACMG_AMP_2015:BA1', evaluationResult: { status: 'NOT_MET' } },
                { criterionId: 'ACMG_AMP_2015:BS1', evaluationResult: { status: 'MET' }, appliedStrength: 'STRONG' },
            ],
        },
    }] };
    const cards = evidenceCards(candidate, ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1']);
    assert.equal(cards[0].entries.length, 2);
    assert.equal(cards[1].entries[0].evaluation.evaluationResult.status, 'MET');
    assert.match(JSON.stringify(cards[1].entries[0].details), /nfe：65 \/ 1180056/);
    assert.match(JSON.stringify(cards[1].entries[0].details), /差异 p 0.3/);
    assert.ok(enabledCriteria({ candidates: [candidate] }).includes('ACMG_AMP_2015:BS1'));
    atomic.queryOutcome.status = 'NO_RECORD'; atomic.measurements = [];
    candidate.attempts[0].hnf1aFrequency.criterionEvaluations[1].evaluationResult = { status: 'NOT_EVALUATED', reasonCode: 'NO_RECORD_IS_NOT_FREQUENCY_ZERO' };
    const missing = evidenceCards(candidate, ['ACMG_AMP_2015:BS1'])[0].entries[0];
    assert.equal(missing.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.match(JSON.stringify(missing.details), /未取得.*95%/);
    assert.match(JSON.stringify(missing.details), /NO_RECORD.*无记录不等于频率为零/);
    assert.equal(enabledCriteria({ candidates: [{ ba1: candidate.ba1 }] }).includes('ACMG_AMP_2015:BS1'), false);
});

test('BA1 visible scope follows envelope origin across all statuses, multiple contexts and old saves', () => {
    for (const status of ['MET', 'NOT_MET', 'NOT_EVALUATED']) {
        const candidate = { ba1: { evaluation: { evaluationResult: { status } } }, attempts: [1, 2].map((i) => ({
            context: { gene: { hgncId: 'HGNC:11621' }, disease: { mondoId: `MONDO:${i}` }, moi: 'AUTOSOMAL_DOMINANT' },
            hnf1aFrequency: { criterionEvaluations: [{ criterionId: 'ACMG_AMP_2015:BA1', evaluationResult: { status } }] },
        })) };
        const before = JSON.stringify(candidate);
        const entries = evidenceCards(candidate, ['ACMG_AMP_2015:BA1'])[0].entries;
        assert.deepEqual(entries.map((e) => e.scopeLabel), ['疾病 BA1', '疾病 BA1', '通用 BA1（等位基因）']);
        assert.notEqual(entries[0].contextLabel, entries[1].contextLabel);
        assert.equal(JSON.stringify(candidate), before);
    }
    for (const candidate of [{}, { ba1: { evaluationResult: { status: 'NOT_EVALUATED' } } }]) {
        assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BA1'])[0].entries[0].scopeLabel, '通用 BA1（等位基因）');
    }
    const missing = evidenceCards({ attempts: [{ hnf1aFrequency: { criterionEvaluations: [
        { criterionId: 'ACMG_AMP_2015:BA1', evaluationResult: { status: 'NOT_EVALUATED' } },
    ] } }] }, ['ACMG_AMP_2015:BA1'])[0].entries;
    assert.equal(missing[0].scopeLabel, '疾病 BA1');
    assert.equal(missing[0].contextLabel, '疾病上下文未确认');
    assert.equal(missing[1].scopeLabel, '通用 BA1（等位基因）');
});

test('functional facts keep paired ranges and calculation separate from adoption without mutating saved nodes', () => {
    const facts = JSON.parse(fs.readFileSync(new URL('../../varvis-server/src/main/resources/acmg/hnf1a-functional-facts-v1.json', import.meta.url), 'utf8'));
    const value = { evaluation: { criterionId: 'ACMG_AMP_2015:PS3', evaluationResult: { status: 'MET' }, appliedStrength: 'MODERATE', review: { status: 'REQUIRED', decision: 'NONE' } }, factRecord: facts.records[0], factPackage: facts };
    const candidate = { attempts: [{ functional: value }] };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PS3'])[0].entries[0];
    assert.equal(entry.raw, value);
    assert.equal(entry.evaluation.review.decision, 'NONE');
    assert.match(entry.details.find(([label]) => label === '发表结果')[1], /20–35% WT.*45–60% WT/);
    assert.match(entry.details.find(([label]) => label === 'BS3 状态')[1], /未启用/);
    assert.equal(JSON.stringify(candidate), before);
    assert.deepEqual(enabledCriteria({ candidates: [candidate] }), ['ACMG_AMP_2015:PS3']);
});
test('functional hold and missing data never become NOT_MET or a benign BS3 result', () => {
    for (const value of [undefined, { evaluation: { criterionId: 'ACMG_AMP_2015:PS3', evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'HOLD_INTERMEDIATE' } } }]) {
        const candidate = { attempts: [{ functional: value }] };
        const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PS3'])[0].entries[0];
        assert.notEqual(entry.evaluation?.evaluationResult?.status, 'NOT_MET');
        assert.notEqual(entry.evaluation?.criterionId, 'ACMG_AMP_2015:BS3');
    }
});

test('joint ten-rule display preserves independent evidence scopes and the saved historical manifest', () => {
    const criterion = (code, status = 'NOT_EVALUATED') => ({
        criterionId: `ACMG_AMP_2015:${code}`, evaluationResult: { status },
    });
    const criteria = ['PP3', 'BP4', 'BP7', 'PM2', 'BA1', 'PS1', 'PM5', 'PVS1', 'BS1', 'PS3']
        .map((code) => `ACMG_AMP_2015:${code}`);
    const attempt = {
        ...revelAttempt(),
        context: { gene: { hgncId: 'HGNC:11621' }, disease: { mondoId: 'MONDO:0015967' }, moi: 'AUTOSOMAL_DOMINANT' },
        pm2: { evaluation: criterion('PM2') },
        pvs1: { evaluation: criterion('PVS1') },
        missenseComparison: { criterionEvaluations: [criterion('PS1'), criterion('PM5')] },
        hnf1aFrequency: { criterionEvaluations: [criterion('BA1'), criterion('BS1', 'MET')] },
        functional: { evaluation: { ...criterion('PS3', 'MET'), review: { status: 'REQUIRED', decision: 'NONE' } } },
    };
    attempt.criterionEvaluations.push(criterion('BP7'));
    const candidate = { ba1: { evaluation: criterion('BA1', 'NOT_MET') }, attempts: [attempt] };
    const run = { enabledCriteria: criteria, candidates: [candidate] };
    const before = JSON.stringify(run);
    const cards = evidenceCards(candidate, enabledCriteria(run));
    assert.equal(cards.length, 10);
    assert.equal(new Set(cards.map((card) => card.id)).size, 10);
    assert.deepEqual(new Set(enabledCriteria({ candidates: [candidate] })), new Set(criteria));
    const card = (code) => cards.find((item) => item.id === `ACMG_AMP_2015:${code}`);
    assert.deepEqual(card('BA1').entries.map((entry) => entry.scopeLabel), ['疾病 BA1', '通用 BA1（等位基因）']);
    assert.equal(card('BS1').entries[0].evaluation, attempt.hnf1aFrequency.criterionEvaluations[1]);
    assert.equal(card('PS3').entries[0].raw, attempt.functional);
    assert.equal(card('PS3').entries[0].evaluation.review.decision, 'NONE');
    assert.deepEqual(card('BP4').entries[0].details[0], ['REVEL 分数', '0.15']);
    assert.equal(card('PVS1').entries[0].raw, attempt.pvs1);
    assert.equal(card('PM5').entries[0].raw, attempt.missenseComparison);
    assert.equal(JSON.stringify(run), before);
    const historical = { ...run, enabledCriteria: criteria.slice(0, 8) };
    assert.deepEqual(enabledCriteria(historical), criteria.slice(0, 8));
    assert.equal(evidenceCards(candidate, enabledCriteria(historical)).length, 8);
    assert.ok(!enabledCriteria(run).includes('ACMG_AMP_2015:BS3'));
    assert.ok(!enabledCriteria(run).includes('ACMG_AMP_2015:PM1'));
});

test('generalized population cards use selected disease policy while preserving historical HNF1A objects', () => {
    const ids = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'];
    const legacy = { criterionEvaluations: ids.map((criterionId) => ({ criterionId, evaluationResult: { status: 'NOT_EVALUATED' } })) };
    const ruleResults = ids.map((criterionId) => ({
        criterionEvaluations: [{ criterionId, evaluationResult: { status: 'MET' } }],
        policy: { policyId: 'synthetic-MYH7-policy', policyVersion: '1',
            sources: [{ url: 'https://example.org/synthetic-cspec' }],
            officialConditions: { BA1: { operator: 'GREATER_THAN', decimal: '0.001' }, BS1: { operator: 'GREATER_THAN_OR_EQUAL', decimal: '0.0001' } },
            exceptionReview: { scope: 'Synthetic bounded source review only' } },
        policySelection: { outcome: 'SPECIALIZED_POLICY_SELECTED', catalogId: 'synthetic', catalogVersion: '1' },
    }));
    const candidate = { attempts: [{ context: { gene: { symbol: 'MYH7', hgncId: 'HGNC:7577' },
        disease: { mondoId: 'MONDO:0005045' }, moi: 'AUTOSOMAL_DOMINANT' },
        hnf1aFrequency: legacy, populationFrequency: { clauseRouting: Object.fromEntries(ids.map((id) => [id, 'CURRENT_CATALOG_RESULT'])), ruleResults,
            criterionEvaluations: ruleResults.flatMap((result) => result.criterionEvaluations) } }] };
    const original = JSON.stringify(candidate);
    const cards = evidenceCards(candidate, ids);
    assert.equal(cards[0].entries[0].evaluation.evaluationResult.status, 'MET');
    assert.equal(cards[1].entries[0].raw, ruleResults[1]);
    const detail = (label) => cards[0].entries[0].details.find(([key]) => key === label)?.[1];
    assert.match(detail('疾病作用域'), /MYH7.*MONDO:0005045/);
    assert.equal(detail('本次阈值'), '> 0.001（比例）');
    assert.doesNotMatch(detail('政策'), /GN017|HNF1A/);
    assert.equal(detail('规范来源'), 'https://example.org/synthetic-cspec');
    assert.equal(JSON.stringify(candidate), original);
    assert.deepEqual(new Set(enabledCriteria({ candidates: [candidate] })), new Set(ids));
    candidate.attempts[0].populationFrequency.clauseRouting['ACMG_AMP_2015:BS1'] = 'PREEXISTING_POLICY_OUTSIDE_CATALOG_SCOPE';
    assert.equal(evidenceCards(candidate, ids)[1].entries[0].raw, legacy);
    assert.equal(evidenceCards(candidate, ids)[0].entries[0].raw, ruleResults[0]);
});

test('new functional policy displays its measured unit, exact scope and adoption boundary', () => {
    const fixture = JSON.parse(fs.readFileSync(new URL('../../varvis-server/src/test/resources/acmg/generalization/functional-pten-developer-synthetic-model-v1.json', import.meta.url), 'utf8'));
    const record = fixture.facts.records.find((row) => row.proteinVariantKey === 'R130Q');
    const value = { bundleIdentity: { schemaVersion: 'reviewed-rule-bundle-binding-v1' }, policy: fixture.policy,
        factPackage: fixture.facts, factRecord: record, adoptionSupported: false,
        evaluation: { criterionId: 'ACMG_AMP_2015:PS3', evaluationResult: { status: 'MET' } } };
    const candidate = { attempts: [{ functional: value }] };
    const original = JSON.stringify(candidate);
    const card = evidenceCards(candidate, ['ACMG_AMP_2015:PS3'])[0];
    assert.equal(card.code, 'PS3');
    const details = Object.fromEntries(card.entries[0].details);
    assert.match(details['规则适用范围'], /PTEN.*MONDO:0017623/);
    assert.match(details['发表结果'], /-2\.0401641100000001.*NATURAL_LOG_WT_NORMALIZED_FITNESS/);
    assert.match(details['计证条件'], /≤ -1\.11/);
    assert.match(details['采用状态'], /尚未接通人工采用/);
    assert.doesNotMatch(JSON.stringify(details), /HNF1A|Oxford|HeLa|rat-albumin/);
    assert.equal(JSON.stringify(candidate), original);
    value.factRecord = { publishedRange: { lower: '20', upper: '40', lowerInclusive: true, upperInclusive: false, unit: 'percent_of_study_WT' } };
    assert.match(Object.fromEntries(evidenceCards(candidate, ['ACMG_AMP_2015:PS3'])[0].entries[0].details)['发表结果'], /\[20, 40\)/);
});

test('new policy refusal and PVS1 scope remain readable without legacy facts or transcript aliases', () => {
    const binding = { schemaVersion: 'reviewed-rule-bundle-binding-v1' };
    const refusal = { bundleIdentity: binding, evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'REVIEWED_RULE_CONFIGURATION_UNAVAILABLE' } };
    const missing = evidenceCards({ attempts: [{ functional: refusal }] }, ['ACMG_AMP_2015:PS3'])[0].entries[0];
    assert.equal(missing.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    const pvs1 = { bundleIdentity: binding, policy: { policyId: 'PTEN_SOURCE_POLICY', policyVersion: '1',
        mechanism: { mechanismId: 'PHTS_LOF' }, nmdScope: { relevantExonOrdinals: [1, 2, 3], engineeringSupportedExonOrdinals: [2] } },
        resolvedInput: { selectedTranscript: 'NM_000314.8' } };
    const details = Object.fromEntries(evidenceCards({ attempts: [{ pvs1 }] }, ['ACMG_AMP_2015:PVS1'])[0].entries[0].details);
    assert.equal(details['转录本'], 'NM_000314.8');
    assert.equal(details['相关外显子 / 当前实现范围'], '1, 2, 3 / 2');
});

test('optional rule context declaration retains explicit bound transcript confirmation without AM claims', () => {
    const value = options(1), candidate = value.candidates[0];
    const annotation = candidate.recommendation.annotations[0];
    Object.assign(annotation, { identityCompatible: false, ruleContextReady: true,
        predictionPath: 'REVIEWED_RULE_CONTEXT', ruleContextSelectionPolicy: `REVIEWED_RULE_CONTEXT@1.0.0:${'a'.repeat(64)}` });
    candidate.recommendation.resourceCompatibleTranscript = { ...annotation };
    const inputs = prepareSimplifiedInputs(value);
    assert.equal(recommendedTranscript(candidate).kind, 'RULE_CONTEXT_RECOMMENDATION');
    assert.equal(recommendationLabel('RULE_CONTEXT_RECOMMENDATION'), '规则评估上下文可供确认（具体证据仍需核验）');
    assert.match(fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx', import.meta.url), 'utf8'), /确认上下文不代表证据成立/);
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ruleContextOnly, true);
    const declaration = buildSimplifiedCaseDeclaration(value, 'ordinary', inputs);
    assert.equal(declaration.hypotheses[0].transcript.selectionPolicy, annotation.ruleContextSelectionPolicy);
    assert.match(declaration.hypotheses[0].transcript.selectionReason, /each criterion still requires independent/);
    assert.ok(!declaration.hypotheses[0].transcript.selectionReason.includes('resource identity compatible'));
    inputs[candidate.candidateId].annotationId = '';
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ready, false);
    assert.throws(() => buildSimplifiedCaseDeclaration(value, 'ordinary', inputs), /尚无可提交候选/);
    annotation.ruleContextSelectionPolicy = 'PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0';
    assert.equal(annotationSelectable(annotation), false);
});

test('prediction context declaration preserves pure VEP capability without AM compatibility claims', () => {
    const value = options(1), candidate = value.candidates[0];
    const annotation = candidate.recommendation.annotations[0];
    Object.assign(annotation, { identityCompatible: false, predictionContextReady: true,
        predictionPath: 'REVIEWED_PREDICTION_CONTEXT', predictionContextSelectionPolicy: `REVIEWED_PREDICTION_CONTEXT@1.0.0:${'b'.repeat(64)}` });
    candidate.recommendation.resourceCompatibleTranscript = { ...annotation };
    const inputs = prepareSimplifiedInputs(value);
    assert.equal(recommendedTranscript(candidate).kind, 'PREDICTION_CONTEXT_RECOMMENDATION');
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).predictionContextOnly, true);
    const declaration = buildSimplifiedCaseDeclaration(value, 'ordinary', inputs);
    assert.equal(declaration.hypotheses[0].transcript.selectionPolicy, annotation.predictionContextSelectionPolicy);
    assert.match(declaration.hypotheses[0].transcript.selectionReason, /no predictor score or identity lookup/);
    annotation.predictionContextSelectionPolicy = 'PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0';
    assert.equal(annotationSelectable(annotation), false);
});

test('A01 BA1 uses only current selections, preserves four states and does not mutate old source results', () => {
    const id = 'ACMG_AMP_2015:BA1';
    const evaluation = (status) => ({ criterionId: id, evaluationResult: { status }, appliedStrength: status === 'MET' ? 'STAND_ALONE' : 'NONE' });
    const states = ['MET', 'NOT_MET', 'NOT_EVALUATED', 'NOT_EVALUATED'];
    const selections = ['SELECTED', 'SELECTED', 'BLOCKED', 'NOT_APPLICABLE'];
    const candidate = {
        candidateId: 'synthetic-candidate',
        ba1ResultSelection: { legacyResultsRole: 'SOURCE_ONLY', fallbackAllowed: false },
        ba1: {
            evaluation: evaluation('NOT_EVALUATED'),
            generalization: { selection: { status: 'BLOCKED', reasonCode: 'BA1_CONTEXT_REQUIRED' },
                requiredInputs: [{ field: 'disease', reason: 'Confirm applicable disease' }] },
            contextResults: states.map((status, i) => ({ contextId: `ctx-${i}`, evaluation: evaluation(status),
                selection: { status: selections[i], reasonCode: `SYNTHETIC_${i}`, requiredInputs: [{ field: 'old-scope-only', reason: 'fallback' }],
                    sources: [{ sourceId: 'official', url: 'https://example.test/specification' }] },
                requiredInputs: i === 2 ? [{ field: 'selectedAncestryAN', reason: 'Observed AN is missing' }] : [] })),
        },
        attempts: states.map((unused, i) => ({ candidateId: 'synthetic-candidate',
            ba1ResultSelection: { schemaVersion: 'ba1-effective-result-selection-v1', effectiveResultPath: `/ba1/contextResults/${i}`, fallbackAllowed: false, legacyResultsRole: 'SOURCE_ONLY' },
            context: { contextId: `ctx-${i}`, gene: { hgncId: 'HGNC:1' },
            disease: { mondoId: `MONDO:000000${i}` }, moi: 'AD' },
            hnf1aFrequency: { criterionEvaluations: [evaluation('MET')] } })),
    };
    const before = JSON.stringify(candidate);
    const card = evidenceCards(candidate, [id])[0];
    assert.equal(card.entries.length, 1);
    assert.equal(card.selectedView, 'candidate', 'Multiple backgrounds cannot be chosen by favorable outcome');
    const entries = [card.entries[0], ...states.map((unused, i) => evidenceCards(candidate, [id], `ctx-${i}`)[0].entries[0])];
    assert.deepEqual(entries.slice(1).map((entry) => criterionLabel(entry.evaluation)),
        ['已评价 · 满足', '已评价 · 未满足', '原因待核实', '不适用']);
    assert.equal(criterionLabel(entries[0].evaluation), '判定资料不足');
    assert.ok(entries.every((entry) => !entry.scopeLabel.includes('疾病 BA1')));
    assert.equal(Object.fromEntries(entries[3].details)['所需输入'], 'selectedAncestryAN：Observed AN is missing');
    assert.ok(!Object.fromEntries(entries[3].details)['所需输入'].includes('old-scope-only'));
    assert.equal(Object.fromEntries(entries[1].details)['所需输入'], '无额外缺项声明');
    assert.equal(entries[1].links[0].url, 'https://example.test/specification');
    assert.match(Object.fromEntries(entries[0].details)['证据关系'], /不补位、不叠加/);
    assert.equal(JSON.stringify(candidate), before);
});
test('A01 BA1 technical failures and absent selection cannot borrow old MET or another context evaluation', () => {
    const id = 'ACMG_AMP_2015:BA1';
    const met = { criterionId: id, evaluationResult: { status: 'MET' }, appliedStrength: 'STAND_ALONE' };
    const candidate = {
        ba1ResultSelection: { legacyResultsRole: 'SOURCE_ONLY', fallbackAllowed: false },
        ba1: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'BA1_BUSINESS_EXECUTION_DISABLED' } },
        attempts: [{ hnf1aFrequency: { criterionEvaluations: [met] } }],
    };
    let entries = evidenceCards(candidate, [id])[0].entries;
    assert.equal(entries.length, 1);
    assert.equal(criterionLabel(entries[0].evaluation), '所需能力未启用');
    assert.equal(entries[0].evaluation.appliedStrength, 'NONE');
    candidate.ba1 = { evaluation: met, generalization: { selection: { status: 'SELECTED' } },
        contextResults: [{ contextId: 'missing-evaluation', selection: { status: 'SELECTED' } }] };
    entries = evidenceCards(candidate, [id], 'candidate')[0].entries;
    assert.equal(criterionLabel(entries[0].evaluation), '已评价 · 满足');
    assert.equal(criterionLabel(evidenceCards(candidate, [id], 'missing-evaluation')[0].entries[0].evaluation), '执行或核查失败');
    assert.equal(evidenceCards(candidate, [id], 'missing-evaluation')[0].entries[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    candidate.ba1.contextResults[0].evaluation = met;
    candidate.ba1.evaluationResult = { status: 'NOT_EVALUATED', reasonCode: 'BA1_EVALUATION_IDENTITY_MISMATCH' };
    entries = evidenceCards(candidate, [id], 'candidate')[0].entries;
    assert.ok(entries.every((entry) => criterionLabel(entry.evaluation) === '执行或核查失败'));
    assert.ok(entries.every((entry) => entry.evaluation.evaluationResult.reasonCode === 'BA1_EVALUATION_IDENTITY_MISMATCH'));
    delete candidate.ba1.evaluationResult;
    delete candidate.ba1.generalization.selection;
    assert.equal(criterionLabel(evidenceCards(candidate, [id])[0].entries[0].evaluation), '执行或核查失败');
    assert.equal(evidenceCards(candidate, [id])[0].entries[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    assert.match(reasonHelp('BA1_BUSINESS_EXECUTION_DISABLED'), /BA1.*关闭/);
    assert.match(reasonHelp('BA1_CATALOG_CONFIGURATION_INVALID'), /旧 BA1.*不会补位/);
});

test('BA1 disease-only submission binds current server recommendation without inventing a transcript declaration', () => {
    const value = options(1), candidate = value.candidates[0];
    const rec = candidate.recommendation;
    rec.ba1DiseaseContextPreparation = { available: true, transcriptConfirmed: false,
        selectionPolicy: 'BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0',
        clinicalAnnotation: { versionedTranscriptId: 'ENST00000646891.2' },
        eligibleRelationshipKeys: [rec.stableRelationshipKey] };
    const inputs = prepareSimplifiedInputs(value);
    inputs[candidate.candidateId].annotationId = '';
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ba1Ready, true);
    const declaration = buildSimplifiedCaseDeclaration(value, 'ordinary', inputs);
    assert.equal(declaration.hypotheses.length, 0);
    assert.equal(declaration.recommendations[0].annotationId, null);
    assert.equal(declaration.recommendations[0].recommendationId, rec.recommendationId);
    assert.equal(declaration.recommendations[0].stableRelationshipKey, rec.stableRelationshipKey);
    rec.hnf1aFrequencyContextPreparation = { eligibleRelationshipKeys: [rec.stableRelationshipKey] };
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ba1Ready, false);
    delete rec.hnf1aFrequencyContextPreparation;
    rec.ba1DiseaseContextPreparation.selectionPolicy = 'PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0';
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ba1Ready, false);
    rec.ba1DiseaseContextPreparation.selectionPolicy = 'BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0';
    rec.ba1DiseaseContextPreparation.clinicalAnnotation = null;
    assert.equal(candidateInputStatus(candidate, inputs[candidate.candidateId]).ba1Ready, false);
    assert.match(reasonHelp('BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED'), /PM2、BS1.*未获得/);
    const page = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx', import.meta.url), 'utf8');
    assert.match(page, /仅确认 BA1 疾病背景（不确认转录本）/);
    assert.match(page, /也不授权\s*PM2/);
    assert.match(page, /缺项不回退通用规范/);
    assert.match(page, /仅 BA1 背景不授权\s*PM2/);
    const blocked = { criterionId: 'ACMG_AMP_2015:BS1', evaluationResult: { status: 'NOT_EVALUATED',
        reasonCode: 'BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED' } };
    const card = evidenceCards({ attempts: [{ context: { gene: { hgncId: 'HGNC:1097' }, disease: { mondoId: 'MONDO:0015280' }, moi: 'AD' },
        hnf1aFrequency: { criterionEvaluations: [blocked] } }] }, ['ACMG_AMP_2015:BS1'])[0];
    assert.doesNotMatch(JSON.stringify(card.entries[0].details), /HNF1A|GN017/);
    assert.equal(criterionLabel(card.entries[0].evaluation), '原因待核实');
});

test('A01 incomplete evaluation still displays observed values and the exact selected AN comparator', () => {
    const ba1 = { generalization: { selection: { status: 'BLOCKED', reasonCode: 'SYNTHETIC_QC_GAP' },
        effectivePolicy: { frequencyRule: { metricId: 'gnomad.joint.faf95max', comparisonOperator: 'GREATER_THAN_OR_EQUAL',
            threshold: { rawValue: '0.007' }, minimumSelectedAncestryAlleleNumber: { rawValue: '2000', comparisonOperator: 'GREATER_THAN' } } } },
        evidence: { measurements: Object.entries({ 'gnomad.joint.faf95max': '0.009', 'gnomad.joint.faf95max.ancestry': 'nfe',
            'gnomad.joint.ancestry.nfe.ac': '100', 'gnomad.joint.ancestry.nfe.an': '10000', 'gnomad.joint.ancestry.nfe.af': '0.01',
            'gnomad.joint.exomes.filtersPresence': '.', 'gnomad.joint.genomes.filtersPresence': 'MISSING' })
            .map(([metricId, rawValue]) => ({ metricId, rawValue })) } };
    const before = JSON.stringify(ba1);
    const d = ba1Details(ba1);
    assert.equal(d.frequency, '0.009'); assert.equal(d.ac, '100'); assert.equal(d.af, '0.01');
    assert.equal(d.threshold, '0.007'); assert.equal(d.minimumAnOperator, 'GREATER_THAN');
    const card = evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0];
    assert.match(JSON.stringify(card.details), /要求 > 2000/);
    assert.match(JSON.stringify(card.details), /外显子 .；基因组 MISSING/);
    assert.equal(JSON.stringify(ba1), before);
    ba1.subtypeFilterEvidence = { results: [{ subtype: 'exomes', version: '4.1', filter: 'PASS', status: 'VERIFIED_PASS',
        url: 'https://example.test/native-exomes' }, { subtype: 'genomes', version: '4.1', filter: '.', status: 'NONPASS',
        url: 'https://example.test/native-genomes' }] };
    const supplemented = evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0];
    assert.match(JSON.stringify(supplemented.details), /外显子组 v4.1：PASS \/ 明确通过/);
    assert.match(JSON.stringify(supplemented.details), /基因组 v4.1：. \/ 未通过/);
    assert.equal(supplemented.links.length, 2);
    assert.equal(criterionLabel(supplemented.evaluation), '原因待核实');
    assert.match(reasonHelp('BA1_GENE_MAPPING_REQUIRED'), /全部相关.*HGNC.*重叠基因/);
    assert.match(reasonHelp('BA1_EXOMES_FILTER_INPUT_MISSING'), /同版、同等位基因/);
});

test('PM1 recommendation remains selectable without prediction compatibility under the exact disease', () => {
    const annotation = { pm1Compatible: true, identityCompatible: false };
    const relationship = { selectable: true, hgncId: 'HGNC:11621', mondoId: 'MONDO:0015967', moi: 'AD' };
    assert.equal(annotationSelectable(annotation, relationship), true);
    assert.equal(annotationSelectable(annotation, { ...relationship, mondoId: 'MONDO:0000001' }), false);
    assert.equal(contextTranscriptRecommendation({ recommendation: { pm1CompatibleTranscript: annotation } }, relationship), annotation);
});
test('PM1 cards distinguish strongest residue, supporting region, negative and missing historical output', () => {
    for (const [strength, match, label] of [
        ['MODERATE', 'GN017_DIRECT_DNA_BINDING_RESIDUE', /直接 DNA 结合残基/],
        ['SUPPORTING', 'GN017_REGION_107_174', /107–174/],
        ['NONE', 'GN017_TAD_EXCLUDED', /明确不予 PM1 支持/],
    ]) {
        const pm1 = { resolvedInput: { proteinPosition: 112, matchedPolicy: match },
            storedAnnotation: { hgvsProtein: 'ENSP00000257555.5:p.Pro112Leu' },
            evaluation: { criterionId: 'ACMG_AMP_2015:PM1', appliedStrength: strength, evaluationResult: { status: strength === 'NONE' ? 'NOT_MET' : 'MET' } } };
        const candidate = { attempts: [{ pm1 }] };
        const before = JSON.stringify(candidate);
        const cards = evidenceCards(candidate, ['ACMG_AMP_2015:PM1']);
        assert.equal(cards.length, 1);
        assert.equal(cards[0].entries[0].evaluation.appliedStrength, strength);
        assert.match(JSON.stringify(cards[0].entries[0].details), label);
        assert.equal(JSON.stringify(candidate), before);
    }
    const old = evidenceCards({ attempts: [{}] }, ['ACMG_AMP_2015:PM1'])[0].entries[0];
    assert.equal(old.evaluation, undefined);
    assert.match(reasonHelp('PM1_PROTEIN_CODON_MISMATCH'), /不一致/);
});

test('BA1 readiness reasons distinguish sample count, count conflict, exception gaps and frequency discordance', () => {
    const codes = ['SELECTED_ANCESTRY_AN_BELOW_MINIMUM', 'POPULATION_FREQUENCY_COUNT_AF_CONFLICT',
        'BA1_EXCEPTION_MAPPING_INCOMPLETE', 'POPULATION_FREQUENCY_DISCORDANT_REVIEW_REQUIRED'];
    const ba1 = { generalization: { selection: { status: 'SELECTED' } },
        evidence: { measurements: [{ metricId: 'gnomad.recordInputReadinessReasonCodes', rawValue: codes.join('|') }] },
        evaluation: { evaluationResult: { status: 'NOT_EVALUATED' } } };
    const entry = evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0];
    assert.ok(codes.every((code) => entry.reasons.some((reason) => reason.reasonCode === code)));
    assert.match(reasonHelp(codes[0]), /AN/); assert.match(reasonHelp(codes[1]), /AC.*AN.*AF/);
    assert.match(reasonHelp(codes[2]), /例外/);
    assert.doesNotMatch(reasonHelp(codes[3]), /CSpec/);
    assert.equal(criterionLabel(entry.evaluation), '原因待核实');
});

test('BA1 converted context selection uses exact saved association and never an unrelated context or stale path', () => {
    const authority = (path) => ({ schemaVersion: 'ba1-effective-result-selection-v1', effectiveResultPath: path,
        fallbackAllowed: false, legacyResultsRole: 'SOURCE_ONLY' });
    const candidate = { candidateId: 'synthetic-candidate', ba1ResultSelection: authority('/ba1'), ba1: {
        generalization: { selection: { status: 'BLOCKED', reasonCode: 'BA1_CONTEXT_REQUIRED' } },
        evaluation: { evaluationResult: { status: 'NOT_EVALUATED' } },
        contextResults: [{ contextId: 'converted-context', selection: { status: 'SELECTED' },
            evaluation: { evaluationResult: { status: 'NOT_MET' } } }] }, attempts: [{
        candidateId: 'synthetic-candidate', context: null, ba1Context: { contextId: 'converted-context', disease: { mondoId: 'MONDO:0015967' } },
        ba1ResultSelection: authority('/ba1/contextResults/0') }] };
    const cards = () => evidenceCards(candidate, ['ACMG_AMP_2015:BA1'], 'converted-context')[0].entries;
    assert.equal(cards().length, 1);
    assert.equal(criterionLabel(cards()[0].candidateChecks.evaluation), '判定资料不足');
    assert.equal(criterionLabel(cards()[0].evaluation), '已评价 · 未满足');
    assert.match(cards()[0].contextLabel, /MONDO:0015967/);
    candidate.attempts[0].ba1ResultSelection.effectiveResultPath = '/ba1/contextResults/1';
    assert.equal(criterionLabel(cards()[0].evaluation), '执行或核查失败');
    candidate.attempts[0].ba1ResultSelection.effectiveResultPath = '/ba1/contextResults/0';
    candidate.attempts[0].ba1Context.contextId = 'other-context';
    assert.equal(cards()[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    candidate.attempts[0].ba1Context.contextId = 'converted-context';
    candidate.ba1.contextResults.push(structuredClone(candidate.ba1.contextResults[0]));
    assert.ok(cards().every((entry) => criterionLabel(entry.evaluation) === '执行或核查失败'));
    candidate.ba1.contextResults.pop();
    delete candidate.attempts[0].ba1ResultSelection;
    assert.equal(cards()[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    candidate.attempts[0].ba1ResultSelection = authority('/ba1/contextResults/0');
    delete candidate.attempts[0].ba1ResultSelection.schemaVersion;
    assert.equal(cards()[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    candidate.attempts[0].ba1ResultSelection = authority('/ba1/contextResults/0');
    candidate.attempts[0].candidateId = 'different-candidate';
    assert.equal(cards()[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
    candidate.attempts[0].candidateId = candidate.candidateId;
    const duplicate = structuredClone(candidate.attempts[0]);
    duplicate.ba1Context.contextId = 'another-background';
    candidate.attempts.push(duplicate);
    assert.equal(cards()[0].evaluation.evaluationResult.reasonCode, 'BA1_SAVED_RESULT_ASSOCIATION_INVALID');
});

test('BA1 conversion details preserve two assemblies and PASS carries no failure guidance', () => {
    const ba1 = { generalization: { selection: { status: 'SELECTED' } },
        evaluation: { evaluationResult: { status: 'NOT_MET' } }, conversionSource: { status: 'VERIFIED',
            originalAllele: { assembly: 'GRCh37', contig: '1', position: 100, reference: 'A', alternate: 'T' },
            analysisAllele: { assembly: 'GRCh38', contig: '1', position: 120, reference: 'A', alternate: 'T' } },
        subtypeFilterEvidence: { results: [{ subtype: 'exomes', status: 'VERIFIED_PASS', filter: 'PASS', reasonCode: 'VERIFIED_PASS' }] } };
    const before = JSON.stringify(ba1);
    const entry = evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0];
    const details = Object.fromEntries(entry.details);
    assert.match(details['原始上传位点'], /GRCh37 1:100 A>T/);
    assert.match(details['实际证据查询位点'], /GRCh38 1:120 A>T/);
    assert.doesNotMatch(details['原生过滤补证'], /未评价|失败|补充|核对/);
    assert.equal(criterionLabel(entry.evaluation), '已评价 · 未满足');
    assert.equal(JSON.stringify(ba1), before);
    assert.match(reasonHelp('CONVERSION_ORIGINAL_REF_MISMATCH'), /REF/);
    assert.match(reasonHelp('CONVERSION_MAPPING_AMBIGUOUS'), /映射/);
});

test('saved BA1 mapping failure retains location and independent quality evidence with historical fallback', () => {
    const ba1 = { generalization: { selection: { status: 'BLOCKED', reasonCode: 'BA1_GENE_MAPPING_REQUIRED', geneMapping: {
        diagnosticVersion: 'ba1-gene-mapping-diagnostics-v1', totalAnnotationCount: 3, checkedAnnotationCount: 2,
        uncheckedAnnotationCount: 1, sources: [{ annotationIndex: 0 }], failure: { annotationIndex: 1,
            reasonCode: 'BA1_MAPPING_ENSEMBL_NOT_FOUND', field: 'geneId', geneId: 'ENSG_SYNTHETIC_MISSING',
            geneSymbol: null, source: 'VEP_CACHED', version: '116.0', status: 'COMPLETED', ensemblMatches: [], symbolMatches: [] },
    } } }, evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'BA1_GENE_MAPPING_REQUIRED' } },
    recordInputReadinessReasonCodes: ['FILTER_NOT_PASS'], evidence: { measurements: [
        { metricId: 'gnomad.joint.filter', rawValue: 'EXOMES_FILTERED' },
        { metricId: 'gnomad.joint.exomes.filters', rawValue: 'AS_VQSR' },
        { metricId: 'gnomad.joint.exomes.filtersPresence', rawValue: 'AS_VQSR' },
    ] } };
    const before = JSON.stringify(ba1);
    const details = Object.fromEntries(evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0].details);
    assert.match(details['映射失败位置'], /第 2 条注释（索引 1）/);
    assert.match(details['基因映射进度'], /尚未检查 1 条/);
    assert.match(details['失败项标识'], /ENSG_SYNTHETIC_MISSING.*geneSymbol 缺失/);
    assert.match(details['映射查找结果'], /无匹配/);
    assert.match(details['质量'], /EXOMES_FILTERED/);
    assert.match(details['子类型字段'], /AS_VQSR/);
    assert.match(details['补充要求'], /仍须独立满足/);
    assert.equal(JSON.stringify(ba1), before);
    delete ba1.generalization.selection.geneMapping.diagnosticVersion;
    const historical = Object.fromEntries(evidenceCards({ ba1 }, ['ACMG_AMP_2015:BA1'])[0].entries[0].details);
    assert.match(historical['基因映射诊断'], /历史结果未保存具体失败位置/);
    assert.equal(historical['映射失败位置'], undefined);
});

test('PM2 displays separate saved catalog, transcript and policy checks without inventing historical details', () => {
    const diagnostics = {
        schemaVersion: 'pm2-context-preparation-v1', candidateId: 'candidate-0', hgncId: 'HGNC:1', geneSymbol: 'SYNTHETIC',
        reasonCode: 'PM2_DISEASE_CATALOG_NO_RECORD',
        relationshipCheck: { catalogRecordCount: 0, selectableRecordCount: 0, resourceId: 'SYNTHETIC_CATALOG', resourceVersion: '1', snapshotSha256: 'a'.repeat(64) },
        transcriptCheck: { sameGeneCompletedVepCount: 12, contextAnnotationCount: 0, status: 'DISEASE_RELATIONSHIP_REQUIRED_BEFORE_CONTEXT_SELECTION' },
        policyCheck: { geneRecordCount: 0, relationshipMatchCount: 0, profileSha256: 'b'.repeat(64) },
    };
    const candidate = { attempts: [{ pm2: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: diagnostics.reasonCode }, contextPreparation: diagnostics } }] };
    const before = JSON.stringify(candidate);
    const details = Object.fromEntries(evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0].details);
    assert.match(details['历史准备诊断／阶段未标明'], /当前加载.*疾病关系目录/);
    assert.match(details['转录本准备'], /VEP 注释 12.*不表示 VEP 没有转录本/);
    assert.match(details['PM2 政策查找'], /政策未命中不代表不存在疾病关系/);
    assert.match(details['疾病关系资源'], /SYNTHETIC_CATALOG @ 1/);
    assert.deepEqual(pm2ContextPreparationDetails(undefined), []);
    assert.deepEqual(pm2ContextPreparationDetails({ schemaVersion: 'unknown' }), []);
    assert.equal(JSON.stringify(candidate), before);
    delete candidate.attempts[0].pm2.contextPreparation;
    const historic = Object.fromEntries(evidenceCards(candidate, ['ACMG_AMP_2015:PM2'])[0].entries[0].details);
    assert.equal(historic['疾病关系查找'], undefined);
});

test('preparation does not convert absent catalog data into a request to repeat empty confirmation', () => {
    const candidate = row(0);
    candidate.recommendation.relationships = [];
    candidate.recommendation.pm2ContextPreparation = { diagnostics: {
        reasonCode: 'PM2_DISEASE_CATALOG_NO_RECORD', policyCheck: { relationshipMatchCount: 0 },
    } };
    const state = populationPreparation(candidate, {}, false);
    assert.match(state.pm2, /目录.*未命中/);
    assert.equal(state.pm2Code, 'PM2_DISEASE_CATALOG_NO_RECORD');
    assert.match(state.ba1, /独立评价/);
    const unavailable = populationPreparation(candidate, {}, false, { executionProfile: { ba1RuntimeBinding: {
        catalog: { status: 'UNAVAILABLE', reasonCode: 'BA1_CATALOG_NOT_CONFIGURED' },
    } } });
    assert.notEqual(unavailable.ba1, state.ba1);
    assert.equal(unavailable.pm2, state.pm2);
});

test('saved PM2 preparation has explicit final semantics without interpreting evaluation status', () => {
    const ready = { schemaVersion: 'pm2-context-preparation-v2', stage: 'POST_SUBMISSION', status: 'READY',
        contextId: 'saved-context', transcriptCheck: { status: 'VALIDATED_EVIDENCE_CONTEXT' } };
    assert.match(JSON.stringify(pm2ContextPreparationDetails(ready, true)), /就绪/);
    const blocked = { ...ready, status: 'BLOCKED', reasonCode: 'PM2_CONTEXT_CONFIRMATION_REQUIRED' };
    assert.match(JSON.stringify(pm2ContextPreparationDetails(blocked, true)), /受阻/);
    const old = { schemaVersion: 'pm2-context-preparation-v1', reasonCode: 'PM2_CONTEXT_CONFIRMATION_REQUIRED' };
    assert.match(JSON.stringify(pm2ContextPreparationDetails(old, true)), /历史准备诊断／阶段未标明/);
    assert.doesNotMatch(JSON.stringify(pm2ContextPreparationDetails(old)), /历史准备诊断/);
    const before = JSON.stringify(old);
    for (const status of ['MET', 'NOT_EVALUATED']) {
        const card = evidenceCards({ attempts: [{ pm2: { contextPreparation: old,
            evaluation: { evaluationResult: { status } } } }] }, ['ACMG_AMP_2015:PM2'])[0];
        assert.match(JSON.stringify(card.entries[0].details), /历史准备诊断/);
    }
    assert.equal(JSON.stringify(old), before);
});

test('both explicit data classes survive ordinary assembly and saved labels are independent of draft', () => {
    for (const dataClass of ['SYNTHETIC', 'PRIVACY_BOUNDED_RUNTIME']) {
        const value = options(1);
        const inputs = prepareSimplifiedInputs(value);
        Object.values(inputs).forEach((input) => { input.confirmed = true; });
        const before = JSON.stringify(inputs);
        const request = buildSimplifiedCaseDeclaration(value, 'ordinary', inputs, undefined, dataClass);
        assert.equal(request.dataClass, dataClass);
        assert.equal(JSON.stringify(inputs), before);
    }
    const panel = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx', import.meta.url), 'utf8');
    assert.match(panel, /run.dataClass/);
    assert.equal((panel.match(/setDataClass\(/g) || []).length, 4,
        'Initialization, legacy explicit selector, verified fixed material and current workflow explicit selector may update it');
    assert.match(panel, /onDataClass=\{value=>\{setDataClass\(value\);setConfirmed\(false\);\}\}/);
    assert.match(panel, /setDataClass\(\(value\.pp4Preparation \|\| value\.bs4Preparation \|\| value\.pp1Preparation \|\| value\.bp2Preparation \|\| value\.pm3Preparation \|\| value\.pm6Preparation \|\| value\.ps2Preparation\)\.materialBinding\.context\.dataClass\)/);
});

test('acceptance scope prepares BS1 independently and requires its new ordinary declaration', () => {
    const o = options(1);
    o.enabledCriteria = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2', 'ACMG_AMP_2015:BS1'];
    o.executionProfile = { contextPreparation: 'POPULATION_CONTEXT_ONLY_V1', enabledCriteria: o.enabledCriteria,
        requiredDeclaration: 'ordinary-ba1-pm2-bs1-v1' };
    const c = o.candidates[0];
    c.recommendation.resourceCompatibleTranscript = null;
    c.recommendation.annotations = [];
    c.recommendation.bs1ContextPreparation = { eligibleRelationshipKeys: ['stable-key-0'], policyOptions: [
        { stableRelationshipKey: 'stable-key-0', available: true, transcriptRequirement: 'NOT_REQUIRED_BY_POLICY' },
    ] };
    const inputs = { 'candidate-0': { stableRelationshipKey: 'stable-key-0', annotationId: null } };
    assert.equal(candidateInputStatus(c, inputs['candidate-0']).bs1Ready, true);
    assert.equal(candidateInputStatus(c, inputs['candidate-0']).pm2Ready, false);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', inputs, undefined, 'SYNTHETIC');
    assert.equal(request.declarationVersion, 'ordinary-ba1-pm2-bs1-v1');
    assert.deepEqual(request.hypotheses, []);
    assert.equal(request.recommendations[0].stableRelationshipKey, 'stable-key-0');
    assert.deepEqual(currentBusinessCriteria(o), o.enabledCriteria);
    c.recommendation.bs1ContextPreparation.policyOptions[0].transcriptRequirement = 'REQUIRED';
    assert.equal(candidateInputStatus(c, inputs['candidate-0']).bs1Ready, false);
    assert.equal(candidateInputStatus(c, {}).bs1Ready, false);
    delete o.executionProfile.requiredDeclaration;
    assert.equal(buildSimplifiedCaseDeclaration(o, 'ordinary', inputs).declarationVersion, 'ordinary-ui-v3');
});

test('one BS1 card selects saved backgrounds without mixing strength, quality or identity', () => {
    // Independent synthetic EXOMES inputs: the old unqualified JOINT shape is
    // deliberately rejected below, never relabeled into this qualified fixture.
    const allele = { assembly: 'GRCh38', contig: '1', position: 100, reference: 'A', alternate: 'G' };
    const make = (id, strength, status) => ({ hypothesisId: id, bs1: {
        candidateId: 'c1', hypothesisId: id, dataClass: 'SYNTHETIC',
        dataScope: { policyId: 'BS1_LOCAL_EXOMES_V1', version: '1.0.0', dataset: 'EXOMES', release: '4.1' },
        context: { contextId: `ctx-${id}`, disease: { mondoId: id === 'h1' ? 'MONDO:0000001' : 'MONDO:0000002' },
            moi: 'AD', variant: { evidenceQueryAllele: { ...allele } } },
        observation: { queryAllele: { ...allele }, carrier: { queryAllele: { ...allele } } },
        evidence: { contextId: `ctx-${id}`, measurements: [{ metricId: 'gnomad.exomes.ancestry.nfe.af', rawValue: '0.2' }] },
        preparation: { selectedMode: 'AD', policy: { policyId: 'SYNTHETIC_BS1_BACKGROUND_TEST_ONLY', version: '1.0.0',
            statistic: { metricId: 'gnomad.exomes.faf95max' }, tiers: [{ moi: 'AD', strength: 'SUPPORTING',
                conditions: [{ fact: 'FAF95', operator: 'GT', decimal: status === 'MET' ? '0.01' : '0.2' }] }] } },
        evaluation: { criterionId: 'ACMG_AMP_2015:BS1', contextId: `ctx-${id}`, appliedStrength: strength, evaluationResult: { status },
            deterministicTrace: { executionStatus: 'COMPLETED', evaluatorVersion: 'SYNTHETIC_BS1_BACKGROUND_TEST_ONLY',
                steps: [{ stepCode: 'SYNTHETIC_FAF95_CONDITION', outcome: status === 'MET' ? 'TRUE' : 'FALSE' }] } },
        qualityDecision: { status: 'READY', facts: { ancestry: 'nfe', FAF95: '0.1', AN: '10000', AC: '2000' },
            contract: { contractId: 'bs1-local-exomes-quality-v1' },
            filterCondition: { observedFilter: 'PASS', basis: 'EXOMES_FILTER_DEFINITION' } },
    } });
    const candidate = { candidateId: 'c1', attempts: [make('h1', 'SUPPORTING', 'MET'), make('h2', 'NONE', 'NOT_MET')] };
    const before = JSON.stringify(candidate);
    const cards = evidenceCards(candidate, ['ACMG_AMP_2015:BS1'], undefined, '0');
    assert.equal(cards.length, 1); assert.equal(cards[0].entries.length, 1);
    assert.equal(cards[0].entries[0].evaluation.appliedStrength, 'SUPPORTING');
    const details = Object.fromEntries(cards[0].entries[0].details);
    assert.equal(details.FAF95, '0.1'); assert.equal(details['同祖源普通 AF（辅助）'], '0.2');
    assert.match(details['质量语义'], /本地 exomes 原生 PASS.*同祖源 FAF95、AC、AN、AF.*无记录不当作零/);
    assert.match(details['证据关系'], /不能相加/);
    assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'], undefined, '1')[0].entries[0].evaluation.evaluationResult.status, 'NOT_MET');
    assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'], undefined, 'gone')[0].entries[0].evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(JSON.stringify(candidate), before);
    candidate.attempts[0].bs1.evidence.contextId = 'wrong';
    assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'])[0].entries[0].evaluation.evaluationResult.reasonCode, 'BS1_SAVED_RESULT_ASSOCIATION_INVALID');
    const unqualifiedJoint = { candidateId: 'c1', attempts: [{ hypothesisId: 'legacy-joint', bs1: {
        candidateId: 'c1', hypothesisId: 'legacy-joint', context: { contextId: 'ctx-joint' },
        evidence: { contextId: 'ctx-joint', measurements: [{ metricId: 'gnomad.joint.ancestry.nfe.af', rawValue: '0.2' }] },
        evaluation: { contextId: 'ctx-joint', appliedStrength: 'SUPPORTING', evaluationResult: { status: 'MET' } },
    } }] };
    const jointBefore = JSON.stringify(unqualifiedJoint);
    const rejectedJoint = evidenceCards(unqualifiedJoint, ['ACMG_AMP_2015:BS1'])[0].entries[0];
    assert.equal(rejectedJoint.evaluation.evaluationResult.reasonCode, 'BS1_SAVED_RESULT_ASSOCIATION_INVALID');
    assert.equal(rejectedJoint.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(JSON.stringify(unqualifiedJoint), jointBefore);
});

test('missing BS1 declaration preserves its cause across legacy string-null and current JSON-null', () => {
    for (const hypothesisId of [null, 'null']) {
        const candidate = { candidateId: 'c', attempts: [{ hypothesisId: null, bs1: { candidateId: 'c', hypothesisId,
            evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'EXPLICIT_INPUT_MISSING' } } } }] };
        assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'])[0].entries[0].evaluation.evaluationResult.reasonCode, 'EXPLICIT_INPUT_MISSING');
        candidate.attempts[0].bs1.evaluation.evaluationResult.status = 'MET';
        if (hypothesisId === 'null') assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'])[0].entries[0].evaluation.evaluationResult.reasonCode, 'BS1_SAVED_RESULT_ASSOCIATION_INVALID');
    }
});

test('formal profile retains explicit three-rule purpose and privacy default; old saved scope stays two-rule', () => {
    const o = options(1);
    o.enabledCriteria = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2', 'ACMG_AMP_2015:BS1'];
    o.executionProfile = { profileId: 'acmg-explicit-business-ba1-pm2-bs1-v1', profileVersion: '4.0.0',
        formalEnabled: true, contextPreparation: 'POPULATION_CONTEXT_ONLY_V1', enabledCriteria: o.enabledCriteria,
        requiredDeclaration: 'ordinary-ba1-pm2-bs1-v1' };
    assert.deepEqual(currentBusinessCriteria(o), o.enabledCriteria);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', {});
    assert.equal(request.declarationVersion, 'ordinary-ba1-pm2-bs1-v1');
    assert.equal(request.dataClass, 'PRIVACY_BOUNDED_RUNTIME');
    assert.deepEqual(enabledCriteria({ enabledCriteria: ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2'] }),
        ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2']);
});

test('BS1 detail shows the saved release locator and marks a header digest as partial', () => {
    const policy = JSON.parse(fs.readFileSync(new URL('../../varvis-server/src/main/resources/acmg/bs1-local-release-v1.json', import.meta.url), 'utf8')).policies[0];
    const candidate = { candidateId: 'c', attempts: [{ hypothesisId: null, bs1: { candidateId: 'c', hypothesisId: null,
        qualityDecision: { contract: policy.qualityContract },
        evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'EXPLICIT_INPUT_MISSING' } } } }] };
    const detail = Object.fromEntries(evidenceCards(candidate, ['ACMG_AMP_2015:BS1'])[0].entries[0].details);
    assert.match(detail['发布 header 来源'], /headers\/12/);
    assert.match(detail['header 摘要范围'], /不是整个/);
    assert.equal(detail['分歧保护性质'], 'LOCAL_ENGINEERING_SAFEGUARD');
});

test('isolated prediction declaration and displayed calibration never expand formal scope', () => {
    const o = options(1), c = o.candidates[0], a = c.recommendation.annotations[0];
    Object.assign(a, { identityCompatible: false, predictionContextReady: true,
        predictionPath: 'REVIEWED_PREDICTION_CONTEXT', predictionContextSelectionPolicy: `REVIEWED_PREDICTION_CONTEXT@1.0.0:${'b'.repeat(64)}` });
    c.recommendation.resourceCompatibleTranscript = { ...a };
    o.enabledCriteria = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
    o.executionProfile = { enabledCriteria: o.enabledCriteria, contextPreparation: 'PREDICTION_CONTEXT_ONLY_V1',
        requiredDeclaration: 'ordinary-pp3-bp4-development-v1', formalEnabled: false };
    assert.deepEqual(currentBusinessCriteria(o), o.enabledCriteria);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o));
    assert.equal(request.declarationVersion, 'ordinary-pp3-bp4-development-v1');
    const prediction = { schemaVersion: 'prediction-rule-result-v1', association: { rawScore: '0.972' },
        calibration: { comparisonSemantics: 'DISPLAY_INTERVALS_REGISTERED', source: { doi: '10.1016/j.gim.2025.101402', locator: 'Table 1' } },
        plan: { model: 'ALPHAMISSENSE', primaryMechanism: 'PROTEIN_MISSENSE' }, runtimeIdentity: { syntheticGridOnly: true },
        criteria: { PP3: { status: 'MET', appliedStrength: 'MODERATE', selectedMechanism: 'PROTEIN_MISSENSE' },
            BP4: { status: 'NOT_EVALUATED', reasonCode: 'BP4_REQUIRED_MECHANISM_UNRESOLVED', appliedStrength: 'NONE' } },
        mechanisms: { PROTEIN_MISSENSE: { status: 'CALIBRATED', direction: 'HARMFUL', sourceStrength: 'THREE_POINT', sourcePointValue: 3 },
            SPLICING: { status: 'NOT_EVALUATED', reasonCode: 'SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED' } } };
    const candidate = { attempts: [{ hypothesisId: 'synthetic', prediction }] };
    const cards = evidenceCards(candidate, o.enabledCriteria), details = Object.fromEntries(cards[0].entries[0].details);
    assert.equal(details['已关联分数'], '0.972'); assert.equal(details['实际应用强度'], 'MODERATE');
    assert.equal(details['PROTEIN_MISSENSE 来源分档 / 点值'], 'THREE_POINT / 3');
    assert.match(details['剪接机制'], /尚未证明兼容/); assert.match(details['验证用途'], /合成/);
    assert.equal(cards[1].entries[0].evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.equal(evidenceCards(candidate, ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:PM2', 'ACMG_AMP_2015:BS1']).length, 3);
    assert.match(reasonHelp('CALIBRATION_PRECISION_UNRESOLVED'), /不能四舍五入/);
});

test('raw AM rows remain visible without association and local adoption coverage is explicit', () => {
    const prediction = { observation: { status: 'HIT', rows: [{ transcriptId: 'ENST.1', proteinVariant: 'V37I', rawScore: '0.1558' }] },
        association: { status: 'IDENTITY_UNRESOLVED', reasonCode: 'TRANSCRIPT_PROTEIN_IDENTITY_UNRESOLVED' },
        stages: { firstUnresolvedStage: 'ASSOCIATION' },
        effectRestrictions: { coverage: 'INCOMPLETE', restriction: 'UNKNOWN', reasonCode: 'EFFECT_REVIEW_STORE_UNAVAILABLE' },
        criteria: { PP3: { status: 'NOT_EVALUATED' } } };
    const details = Object.fromEntries(evidenceCards({ attempts: [{ prediction }] }, ['ACMG_AMP_2015:PP3'])[0].entries[0].details);
    assert.match(details['原始分数（逐行，尚不代表已关联）'], /0\.1558/);
    assert.equal(details['已关联分数'], '尚未关联');
    assert.equal(details['首个未完成阶段'], 'ASSOCIATION');
    assert.match(details['采用引用原因'], /不能据此认定/);
    assert.match(details['采用引用范围'], /不表示外部没有实验/);
    assert.doesNotThrow(() => evidenceCards({ attempts: [{ prediction: { criteria: {} } }] }, ['ACMG_AMP_2015:PP3']));
});

const pm4Options = () => {
    const o = options(2), b = { schemaVersion: 'reviewed-rule-bundle-binding-pm4-v2', status: 'CONFIGURED',
        bundleId: 'TEST_ONLY', bundleVersion: '1', bundleSha256: 'a'.repeat(64), catalogSha256: 'b'.repeat(64),
        criterionIds: ['ACMG_AMP_2015:PM4'], algorithms: { 'simple-inframe-length-pm4': '1.0.0' },
        automaticTrigger: false, classificationEnabled: false, adoptionSupported: false };
    const c = { schemaVersion: 'reviewed-rule-context-preparation-pm4-v2', version: '2.0.0',
        mode: 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION', automaticTrigger: false, classificationEnabled: false,
        criterionPolicySelected: false, predictionCompatibilityClaimed: false, reviewedRuleBinding: b };
    o.enabledCriteria = ['ACMG_AMP_2015:PM4']; o.requiredDeclaration = 'ordinary-pm4-inframe-v1';
    o.executionProfile = { profileId: 'acmg-pm4-inframe-development-v1', profileVersion: '1.0.0',
        requiredDeclaration: o.requiredDeclaration, enabledCriteria: [...o.enabledCriteria],
        formalEnabled: false, automaticTrigger: false, classificationEnabled: false, adoptionSupported: false,
        reviewedRuleBinding: b, ruleContextPreparationBinding: c, manifestSha256: 'c'.repeat(64) };
    o.candidates.forEach((candidate) => {
        const r = candidate.recommendation; r.ruleContextPreparationBinding = structuredClone(c);
        Object.assign(r.annotations[0], { identityCompatible: false, ruleContextReady: true, predictionPath: 'REVIEWED_RULE_CONTEXT',
            ruleContextSelectionPolicy: `REVIEWED_RULE_CONTEXT@2.0.0:${'d'.repeat(64)}` });
    });
    return o;
};
test('PM4 explicit v2 scope uses server selection identities and PM4 confirmation language', () => {
    const o = pm4Options(), inputs = prepareSimplifiedInputs(o), before = JSON.stringify(o);
    assert.deepEqual(currentBusinessCriteria(o), ['ACMG_AMP_2015:PM4']);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', inputs, undefined, 'SYNTHETIC');
    assert.equal(request.declarationVersion, 'ordinary-pm4-inframe-v1');
    assert.equal(request.hypotheses[0].transcript.selectionPolicy, `REVIEWED_RULE_CONTEXT@2.0.0:${'d'.repeat(64)}`);
    assert.match(request.hypotheses[0].transcript.selectionReason, /limited PM4 protein length change/);
    assert.doesNotMatch(request.hypotheses[0].transcript.selectionReason, /AlphaMissense|population evidence/);
    assert.equal(request.recommendations[0].recommendationId, o.candidates[0].recommendation.recommendationId);
    assert.equal(JSON.stringify(o), before);
    inputs[o.candidates[0].candidateId].annotationId = '';
    assert.equal(buildSimplifiedCaseDeclaration(o, 'ordinary', inputs).hypotheses.length, 1);
    inputs[o.candidates[1].candidateId].annotationId = '';
    assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', inputs), /尚无可提交/);
});
test('PM4 unknown or conflicting identity cannot fall through to generic or population submission', () => {
    for (const change of [o => { o.executionProfile.profileVersion = '2.0.0'; },
        o => { o.requiredDeclaration = 'ordinary-ui-v3'; }, o => { o.enabledCriteria = ['ACMG_AMP_2015:BA1']; },
        o => { o.executionProfile.ruleContextPreparationBinding.version = '9.0.0'; },
        o => { o.candidates[0].recommendation.ruleContextPreparationBinding.reviewedRuleBinding.bundleSha256 = 'e'.repeat(64); },
        o => { o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@3.0.0:${'d'.repeat(64)}`; }]) {
        const o = pm4Options(); change(o);
        assert.deepEqual(currentBusinessCriteria(o), []);
        assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o)), /PM4/);
    }
});
test('saved PM4 statuses, missing models and zero facts remain read-only independent of current scope', () => {
    for (const [status, strength, reason] of [['MET', 'MODERATE', 'PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE'],
        ['NOT_MET', 'NONE', 'PM4_REPEAT_REGION'], ['NOT_EVALUATED', undefined, 'PM4_MODEL_MISSING']]) {
        const evaluation = { evaluationResult: { status, reasonCode: reason }, appliedStrength: strength };
        const pm4 = status === 'NOT_EVALUATED' ? evaluation : { evaluation, resolvedInput: { referenceEditStart0: 0, netAminoAcidChange: 0 } };
        const candidate = { attempts: [{ pm4 }] }, run = { candidates: [candidate] }, before = JSON.stringify(run);
        assert.deepEqual(enabledCriteria(run), ['ACMG_AMP_2015:PM4']);
        const entry = evidenceCards(candidate, enabledCriteria(run))[0].entries[0];
        assert.equal(entry.evaluation.evaluationResult.status, status); assert.equal(entry.evaluation.appliedStrength, strength);
        assert.ok(entry.reasons.includes(reason)); assert.match(reasonHelp(reason), /本次|缺少/);
        if (status !== 'NOT_EVALUATED') assert.equal(Object.fromEntries(entry.details)['净氨基酸变化'], 0);
        assert.equal(JSON.stringify(run), before); assert.deepEqual(currentBusinessCriteria(run), []);
    }
});

const ps1Options = () => {
    const o = pm4Options();
    // Distinct synthetic v3 identity. Archive-based independent POST validation is in the PS1 replay.
    o.requiredDeclaration = 'ordinary-ps1-missense-v1'; o.enabledCriteria = ['ACMG_AMP_2015:PS1'];
    const p = o.executionProfile, b = p.reviewedRuleBinding, c = p.ruleContextPreparationBinding;
    Object.assign(p, { profileId: 'acmg-ps1-missense-development-v1', requiredDeclaration: o.requiredDeclaration, enabledCriteria: [...o.enabledCriteria] });
    Object.assign(b, { schemaVersion: 'reviewed-rule-bundle-binding-ps1-v3', criterionIds: [...o.enabledCriteria],
        algorithms: { MissenseComparisonRuntime: '1.0.0' }, runtimeBindingSha256: 'e'.repeat(64) });
    Object.assign(c, { schemaVersion: 'reviewed-rule-context-preparation-ps1-v3', version: '3.0.0' });
    o.candidates.forEach(({ recommendation: r }) => {
        r.ruleContextPreparationBinding = structuredClone(c);
        Object.assign(r.annotations[0], { contextSelectable: true, ps1Pm5Compatible: false,
            ruleContextSelectionPolicy: `REVIEWED_RULE_CONTEXT@3.0.0:${'d'.repeat(64)}` });
    });
    return o;
};
const alternateContext = (o, path) => {
    o.candidates.forEach((candidate) => {
        const r = candidate.recommendation, a = r.annotations[0];
        Object.assign(a, { ruleContextReady: false, contextSelectable: false,
            identityCompatible: false, ps1Pm5Compatible: false, predictionPath: path });
        if (path === 'REVIEWED_PREDICTION_CONTEXT') {
            Object.assign(a, { predictionContextReady: true,
                predictionContextSelectionPolicy: `REVIEWED_PREDICTION_CONTEXT@1.0.0:${'f'.repeat(64)}` });
        } else {
            a.pm1Compatible = true;
            candidate.catalog.hgncId = 'HGNC:11621';
            Object.assign(r.relationships[0], { hgncId: 'HGNC:11621', mondoId: 'MONDO:0015967', moi: 'AD' });
        }
    });
};
test('PS1 rejects prediction and PM1 selectable paths instead of borrowing their authorization', () => {
    const failures = [];
    for (const path of ['REVIEWED_PREDICTION_CONTEXT', 'POPULATION_ONLY']) {
        const o = ps1Options(); alternateContext(o, path);
        const inputs = prepareSimplifiedInputs(o);
        // Prove these are genuinely selectable legacy paths, not invalid test selections.
        assert.ok(o.candidates.every(c => candidateInputStatus(c, inputs[c.candidateId]).ready));
        if (currentBusinessCriteria(o).length) failures.push(`${path}: PS1 still enabled`);
        let rejected = false;
        try { buildSimplifiedCaseDeclaration(o, 'ordinary', inputs); }
        catch (e) { assert.match(e.message, /PS1/); rejected = true; }
        if (!rejected) failures.push(`${path}: PS1 declaration built`);
    }
    assert.deepEqual(failures, []);
});
test('legacy prediction and PM1 declarations retain their own authorization', () => {
    for (const path of ['REVIEWED_PREDICTION_CONTEXT', 'POPULATION_ONLY']) {
        const o = options(1); alternateContext(o, path);
        const r = o.candidates[0].recommendation;
        const request = buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o));
        assert.equal(request.hypotheses.length, 1);
        assert.equal(request.hypotheses[0].transcript.selectionPolicy, path === 'REVIEWED_PREDICTION_CONTEXT'
            ? r.annotations[0].predictionContextSelectionPolicy : `${r.policyId}@${r.policyVersion}`);
        assert.notEqual(request.declarationVersion, 'ordinary-ps1-missense-v1');
    }
});
test('PS1 explicit identity and v3 context keep server references and exact declaration', () => {
    // Distinct original tokens must survive per candidate, even without scientific materials.
    const o = ps1Options();
    o.candidates[1].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@3.0.0:${'e'.repeat(64)}`;
    const inputs = prepareSimplifiedInputs(o), before = JSON.stringify(o);
    assert.deepEqual(currentBusinessCriteria(o), ['ACMG_AMP_2015:PS1']);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', inputs, undefined, 'SYNTHETIC');
    assert.equal(request.declarationVersion, 'ordinary-ps1-missense-v1');
    assert.deepEqual(request.hypotheses.map(hypothesis => hypothesis.transcript.selectionPolicy),
        o.candidates.map(candidate => candidate.recommendation.annotations[0].ruleContextSelectionPolicy));
    assert.match(request.hypotheses[0].transcript.selectionReason, /limited PS1 protein substitution/);
    assert.doesNotMatch(request.hypotheses[0].transcript.selectionReason, /AlphaMissense|population evidence|PM4/);
    assert.equal(request.recommendations[0].recommendationId, o.candidates[0].recommendation.recommendationId);
    assert.equal(JSON.stringify(o), before);
    inputs[o.candidates[0].candidateId].annotationId = '';
    inputs[o.candidates[1].candidateId].annotationId = '';
    assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', inputs), /尚无可提交/);
});
test('PS1 unknown or conflicting identities and v2/v4 tokens cannot authorize population fallback', () => {
    for (const change of [o => { o.executionProfile.profileVersion = '9.0.0'; },
        o => { o.executionProfile.profileId = 'unknown'; o.executionProfile.contextPreparation = 'POPULATION_CONTEXT_ONLY_V1'; },
        o => { o.requiredDeclaration = 'ordinary-ui-v3'; }, o => { o.enabledCriteria.push('ACMG_AMP_2015:PM5'); },
        o => { o.executionProfile.reviewedRuleBinding.status = 'UNAVAILABLE'; },
        o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = {}; },
        o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '2.0.0'; },
        o => { o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@2.0.0:${'d'.repeat(64)}`; },
        o => { o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@4.0.0:${'d'.repeat(64)}`; },
        o => { o.candidates[0].recommendation.annotations[0].contextSelectable = false; },
        o => { Object.assign(o.candidates[0].recommendation.annotations[0], { ruleContextReady: false,
            contextSelectable: false, identityCompatible: true, ruleContextSelectionPolicy: 'LEGACY_COMPATIBILITY' }); }]) {
        const o = ps1Options(); change(o);
        assert.deepEqual(currentBusinessCriteria(o), []);
        assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o)), /PS1/);
    }
    const joint = { enabledCriteria: ['ACMG_AMP_2015:PS1', 'ACMG_AMP_2015:PM5'], executionProfile: { profileId: 'joint-v8' } };
    assert.deepEqual(enabledCriteria(joint), joint.enabledCriteria);
    assert.deepEqual(currentBusinessCriteria(joint), []); // Legacy joint display is not new PS1 submission authority.
});
test('PS1 minimum refusal and actual strengths retain reasons without changing saved run', () => {
    for (const [status, strength, reason] of [['MET', 'STRONG'], ['MET', 'MODERATE'], ['NOT_MET', 'NONE'],
        ['NOT_EVALUATED', undefined, 'PS1_REFERENCE_MATERIALS_MISSING'], ['NOT_EVALUATED', 'NONE', 'MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE']]) {
        const evaluation = { criterionId: 'ACMG_AMP_2015:PS1', evaluationResult: { status, reasonCode: reason }, appliedStrength: strength };
        const candidate = { attempts: [{ missenseComparison: { criterionEvaluations: [evaluation] } }] };
        const run = { candidates: [candidate] }, before = JSON.stringify(run);
        const cards = evidenceCards(candidate, enabledCriteria(run)); assert.equal(cards.length, 1);
        assert.equal(cards[0].code, 'PS1'); assert.equal(cards[0].entries[0].evaluation.appliedStrength, strength);
        assert.equal(cards[0].entries[0].evaluation.evaluationResult.status, status);
        if (reason) { assert.ok(cards[0].entries[0].reasons.includes(reason)); assert.match(reasonHelp(reason), /未评价/); }
        assert.equal(JSON.stringify(run), before);
    }
});

const pm5Options = () => {
    const o = ps1Options();
    o.requiredDeclaration = 'ordinary-pm5-missense-v1'; o.enabledCriteria = ['ACMG_AMP_2015:PM5'];
    const p = o.executionProfile, b = p.reviewedRuleBinding, c = p.ruleContextPreparationBinding;
    Object.assign(p, { profileId: 'acmg-pm5-missense-development-v1', requiredDeclaration: o.requiredDeclaration, enabledCriteria: [...o.enabledCriteria] });
    Object.assign(b, { schemaVersion: 'reviewed-rule-bundle-binding-pm5-v4', criterionIds: [...o.enabledCriteria] });
    Object.assign(c, { schemaVersion: 'reviewed-rule-context-preparation-pm5-v4', version: '4.0.0' });
    o.candidates.forEach(({ recommendation: r }, i) => {
        r.ruleContextPreparationBinding = structuredClone(c);
        r.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@4.0.0:${(i ? 'e' : 'd').repeat(64)}`;
    });
    return o;
};
test('PM5 v4 preserves exact per-candidate tokens and server references without science confirmation', () => {
    const o = pm5Options(), before = JSON.stringify(o), inputs = prepareSimplifiedInputs(o);
    assert.deepEqual(currentBusinessCriteria(o), ['ACMG_AMP_2015:PM5']);
    const request = buildSimplifiedCaseDeclaration(o, 'ordinary', inputs, undefined, 'SYNTHETIC');
    assert.equal(request.declarationVersion, 'ordinary-pm5-missense-v1');
    assert.equal(request.hypotheses.length, 2);
    request.hypotheses.forEach((h, i) => {
        assert.equal(h.transcript.selectionPolicy, o.candidates[i].recommendation.annotations[0].ruleContextSelectionPolicy);
        assert.match(h.transcript.selectionReason, /PM5 same-residue different-substitution/);
        for (const key of ['recommendationId', 'policyVersion', 'policyHash', 'generatedAt'])
            assert.equal(request.recommendations[i][key], o.candidates[i].recommendation[key]);
        assert.equal(request.recommendations[i].annotationId, inputs[o.candidates[i].candidateId].annotationId);
        assert.equal(request.recommendations[i].stableRelationshipKey, inputs[o.candidates[i].candidateId].stableRelationshipKey);
    });
    assert.equal(JSON.stringify(o), before);
    inputs[o.candidates[0].candidateId].annotationId = '';
    assert.equal(buildSimplifiedCaseDeclaration(o, 'ordinary', inputs).hypotheses.length, 1);
    inputs[o.candidates[1].candidateId].annotationId = '';
    assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', inputs), /尚无可提交/);
});
test('PM5 rejects conflicting identities and all alternate selectable authorization paths', () => {
    const mutations = [
        o => { o.executionProfile.profileVersion = '2.0.0'; },
        o => { o.executionProfile.profileId = 'unknown'; },
        o => { o.requiredDeclaration = 'ordinary-ps1-missense-v1'; },
        o => { o.enabledCriteria = ['ACMG_AMP_2015:BA1']; },
        o => { o.executionProfile.enabledCriteria.push('ACMG_AMP_2015:PS1'); },
        o => { o.executionProfile.reviewedRuleBinding.status = 'UNAVAILABLE'; },
        o => { o.executionProfile.reviewedRuleBinding.runtimeBindingSha256 = ''; },
        o => { o.executionProfile.manifestSha256 = 'unknown'; },
        o => { o.executionProfile.reviewedRuleBinding.algorithms.MissenseComparisonRuntime = '2'; },
        o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = {}; },
        o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '3.0.0'; },
        o => { o.candidates[0].recommendation.annotations[0].contextSelectable = false; },
        ...['1.0.0', '2.0.0', '3.0.0', '5.0.0'].map(version => o => {
            o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@${version}:${'d'.repeat(64)}`;
        }),
        ...['REVIEWED_PREDICTION_CONTEXT', 'POPULATION_ONLY'].map(p => o => alternateContext(o, p)),
        ...['identityCompatible', 'ps1Pm5Compatible'].map(flag => o => {
            o.candidates.forEach(c => {
                Object.assign(c.recommendation.annotations[0], { ruleContextReady: false, contextSelectable: false,
                    identityCompatible: false, ps1Pm5Compatible: false, [flag]: true, ruleContextSelectionPolicy: 'OLD' });
                if (flag === 'ps1Pm5Compatible') Object.assign(c.recommendation.relationships[0],
                    { relationshipType: 'VCEP_SPECIFICATION_SCOPE', hgncId: 'HGNC:1097', mondoId: 'MONDO:0021060', moi: 'AD' });
                assert.equal(annotationSelectable(c.recommendation.annotations[0], c.recommendation.relationships[0]), true);
            });
        }),
        ...['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].map(flag => o => { o.executionProfile[flag] = true; }),
    ];
    for (const mutate of mutations) {
        const o = pm5Options(); mutate(o);
        assert.deepEqual(currentBusinessCriteria(o), []);
        assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o)), /PM5|PS1/);
    }
});
test('PM5 and PS1 tokens cannot cross identities while legacy joint PM5 stays distinct', () => {
    for (const [factory, version] of [[ps1Options, '4.0.0'], [pm5Options, '3.0.0']]) {
        const o = factory(); o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@${version}:${'d'.repeat(64)}`;
        assert.deepEqual(currentBusinessCriteria(o), []);
        assert.throws(() => buildSimplifiedCaseDeclaration(o, 'ordinary', prepareSimplifiedInputs(o)), /PM5|PS1/);
    }
    const joint = options(1); joint.enabledCriteria = ['ACMG_AMP_2015:PS1', 'ACMG_AMP_2015:PM5'];
    joint.executionProfile = { profileId: 'joint-v8', enabledCriteria: [...joint.enabledCriteria] };
    assert.deepEqual(currentBusinessCriteria(joint), []);
    assert.notEqual(buildSimplifiedCaseDeclaration(joint, 'ordinary', prepareSimplifiedInputs(joint)).declarationVersion, 'ordinary-pm5-missense-v1');
});
test('PM5 saved policy decisions preserve strengths reasons matrix scores and missing fields', () => {
    for (const [status, strength, reason] of [['MET', 'MODERATE', 'INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET'],
        ['MET', 'SUPPORTING', 'INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET'], ['NOT_MET', 'NONE', 'NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON'],
        ['NOT_EVALUATED', undefined, 'PM5_REFERENCE_MATERIALS_MISSING'], ['NOT_EVALUATED', 'NONE', 'MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE']]) {
        const evaluation = { criterionId: 'ACMG_AMP_2015:PM5', evaluationResult: { status }, appliedStrength: strength };
        const value = { criterionEvaluations: [evaluation] };
        if (strength === undefined) evaluation.evaluationResult.reasonCode = reason;
        else Object.assign(value, { scientificDecision: { status, appliedStrength: strength, reasonCode: reason },
            policy: { rules: { PM5: { mode: 'SUBSTITUTION_MATRIX_SINGLE_REFERENCE', sourceIds: ['TEST_ONLY'] } } },
            proteinComparisons: [{ referenceId: 'TEST_ONLY', matrixKind: 'GRANTHAM', comparison: 'TARGET_GTE_REFERENCE', targetSubstitutionScore: 0, referenceSubstitutionScore: 40, matrixSha256: 'test-source', appliedStrength: strength }] });
        const candidate = { attempts: [{ missenseComparison: value }] }, before = JSON.stringify(candidate);
        const cards = evidenceCards(candidate, ['ACMG_AMP_2015:PM5']), entry = cards[0].entries[0], details = Object.fromEntries(entry.details);
        assert.equal(cards.length, 1); assert.equal(cards[0].code, 'PM5');
        assert.equal(entry.evaluation.appliedStrength, strength); assert.ok(entry.reasons.includes(reason));
        if (strength === undefined) { assert.equal(details['矩阵/关系比较'], '未记录'); assert.match(reasonHelp(reason), /未评价/); }
        else { assert.match(details['矩阵/关系比较'], /目标分数 0 \/ 参考分数 40/); assert.match(details['矩阵/关系比较'], /不表示通过/); assert.match(details['比较依据及来源'], /test-source/); }
        assert.equal(JSON.stringify(candidate), before);
    }
});

test('reviewed missense decisions keep their Chinese meaning in summaries and reasons', () => {
    const failures = [];
    for (const [code, status, strengths, expected, forbidden] of [
        ['INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET', 'MET', ['MODERATE', 'SUPPORTING'], /参考独立性.*目标.*参考.*剪接.*已满足.*强度.*保存结果/, /未通过|缺少|CSpec|人工复核/],
        ['NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON', 'NOT_MET', ['NONE'], /未找到.*当前政策.*已审核蛋白比较支持.*未满足/, /CSpec|人工复核|良性|搜索.*不完整/],
    ]) {
        for (const strength of strengths) {
            const entry = { evaluation: { criterionId: 'ACMG_AMP_2015:PM5', evaluationResult: { status }, appliedStrength: strength }, reasons: [code] };
            const before = JSON.stringify(entry);
            for (const text of [reasonHelp(code), evidenceSummary(entry)]) {
                if (!expected.test(text) || forbidden.test(text)) failures.push(`${code}/${strength}: ${text}`);
            }
            assert.equal(JSON.stringify(entry), before);
            assert.equal(entry.reasons[0], code);
        }
    }
    assert.deepEqual(failures, [], 'Exact missense meaning must precede generic failure fallbacks');
});
test('PM5 direct residue operator does not display missing matrix as missing evidence', () => {
    const comparison = { referenceId: 'TEST_ONLY', ruleMode: 'SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE', appliedStrength: 'MODERATE' };
    const candidate = { attempts: [{ missenseComparison: {
        criterionEvaluations: [{ criterionId: 'ACMG_AMP_2015:PM5', evaluationResult: { status: 'MET' }, appliedStrength: 'MODERATE' }],
        proteinComparisons: [comparison],
    } }] };
    const before = JSON.stringify(candidate);
    const entry = evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0].entries[0];
    const text = Object.fromEntries(entry.details)['矩阵/关系比较'];
    assert.match(text, /同一残基.*不同替换.*不要求替换矩阵分数.*MODERATE/);
    assert.doesNotMatch(text, /目标分数|矩阵 未记录|参考分数/);
    assert.equal(JSON.stringify(candidate), before);
});
test('missense reason fixes preserve real refusal and legacy PS1 explanations', () => {
    for (const [code, expected] of [
        ['PM5_REFERENCE_MATERIALS_MISSING', /缺失.*未评价/],
        ['MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE', /不完整.*未评价.*不能作为完整阴性/],
        ['REFERENCE_NOT_INDEPENDENT', /未通过独立性核验.*不能/],
        ['SPLICE_EVIDENCE_UNAVAILABLE', /不可用.*不能/],
        ['CONVERSION_ORIGINAL_REF_MISMATCH', /REF/],
        ['RESOURCE_UNAVAILABLE', /资源未就绪或执行失败/],
        ['TRUSTED_REFERENCE_AND_SPLICE_CONDITIONS_MET', /可信参考.*剪接条件均通过/],
        ['EXPERT_ORIGINAL_SPECIFICATION_MISMATCH', /原分类.*规范版本.*不能.*重新评价/],
        ['EXPERT_ORIGINAL_SPECIFICATION_SOURCE_UNBOUND', /规范文件.*来源.*版本.*摘要.*不能/],
        ['EXPERT_ORIGINAL_SPECIFICATION_ROUTE_REQUIRED', /未明确启用.*不能.*绕过/],
    ]) assert.match(reasonHelp(code), expected);
    const ps1 = { evaluation: { criterionId: 'ACMG_AMP_2015:PS1', evaluationResult: { status: 'MET', reasonCode: 'TRUSTED_REFERENCE_AND_SPLICE_CONDITIONS_MET' }, appliedStrength: 'STRONG' } };
    assert.equal(evidenceSummary(ps1), '可信参考、蛋白关系及剪接条件均通过本次规范核验。');
});

const pm1Options = () => {
    const o = pm5Options(), p = o.executionProfile, b = p.reviewedRuleBinding, c = p.ruleContextPreparationBinding;
    o.requiredDeclaration = 'ordinary-pm1-region-v1'; o.enabledCriteria = ['ACMG_AMP_2015:PM1'];
    Object.assign(p, { profileId: 'acmg-pm1-region-development-v1', requiredDeclaration: o.requiredDeclaration, enabledCriteria: [...o.enabledCriteria] });
    Object.assign(b, { schemaVersion: 'reviewed-rule-bundle-binding-pm1-v5', criterionIds: [...o.enabledCriteria], algorithms: { 'reviewed-protein-region-pm1': '1.0.0' } });
    delete b.runtimeBindingSha256;
    Object.assign(c, { schemaVersion: 'reviewed-rule-context-preparation-pm1-v5', version: '5.0.0' });
    o.candidates.forEach((candidate, i) => {
        const r = candidate.recommendation, a = r.annotations[0], t = candidate.transcriptAnnotations[0], hex = (i ? 'e' : 'd').repeat(64);
        Object.assign(r, { candidateId: candidate.candidateId, analysisId: o.analysisId, requiredDeclaration: o.requiredDeclaration,
            recommendationId: `recommendation-${hex}`, policyHash: 'a'.repeat(64), executionSourceSha256: 'b'.repeat(64),
            recommendedAnnotationId: `annotation-${hex}`, ruleContextPreparationBinding: structuredClone(c) });
        Object.assign(a, { annotationId: r.recommendedAnnotationId, annotationSourceSha256: 'c'.repeat(64),
            versionedTranscriptId: t.transcriptAccession, geneSymbol: t.geneSymbol, annotationSource: t.source,
            annotationSourceVersion: t.version, ruleContextSelectionPolicy: `REVIEWED_RULE_CONTEXT@5.0.0:${hex}` });
    });
    return o;
};
test('PM1 v5 exact identity preserves candidate tokens without runtimeBindingSha256 or science approval', () => {
    const o = pm1Options(), before = JSON.stringify(o), inputs = prepareSimplifiedInputs(o);
    assert.deepEqual(currentBusinessCriteria(o), ['ACMG_AMP_2015:PM1']);
    const request = buildSimplifiedCaseDeclaration(o, o.analysisId, inputs, undefined, 'SYNTHETIC');
    assert.equal(request.declarationVersion, 'ordinary-pm1-region-v1');
    request.hypotheses.forEach((h, i) => {
        assert.equal(h.transcript.selectionPolicy, o.candidates[i].recommendation.annotations[0].ruleContextSelectionPolicy);
        assert.match(h.transcript.selectionReason, /PM1 reviewed protein region/);
        assert.doesNotMatch(h.transcript.selectionReason, /AlphaMissense|PM5|PS1|population evidence/);
        assert.equal(request.recommendations[i].annotationId, inputs[o.candidates[i].candidateId].annotationId);
    });
    assert.equal(JSON.stringify(o), before);
    const saved = { ...o, declarationVersion: request.declarationVersion }; delete saved.requiredDeclaration;
    assert.ok(pm1SavedRun(saved));
});
test('PM1 rejects mixed identities, flags, incomplete annotations and all older selectable paths', () => {
    const mutations = [
        o => { o.executionProfile.profileId = 'unknown'; }, o => { o.executionProfile.profileVersion = '9'; },
        o => { o.requiredDeclaration = 'ordinary-ui-v3'; }, o => { o.executionProfile.requiredDeclaration = 'ordinary-ui-v3'; },
        o => { o.enabledCriteria.push('ACMG_AMP_2015:PS1'); }, o => { o.executionProfile.enabledCriteria = ['ACMG_AMP_2015:PM5']; },
        ...['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].map(k => o => { o.executionProfile[k] = true; }),
        ...['manifestSha256'].map(k => o => { o.executionProfile[k] = ''; }),
        ...['bundleSha256', 'catalogSha256', 'status', 'schemaVersion'].map(k => o => { o.executionProfile.reviewedRuleBinding[k] = ''; }),
        o => { o.executionProfile.reviewedRuleBinding.algorithms = { MissenseComparisonRuntime: '1.0.0' }; },
        o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = {}; },
        o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '4.0.0'; },
        o => { o.candidates[0].recommendation.candidateId = o.candidates[1].candidateId; },
        ...['annotationSourceSha256', 'annotationId', 'versionedTranscriptId', 'annotationSourceVersion'].map(k => o => { o.candidates[0].recommendation.annotations[0][k] = ''; }),
        ...['1', '2', '3', '4'].map(v => o => { o.candidates[0].recommendation.annotations[0].ruleContextSelectionPolicy = `REVIEWED_RULE_CONTEXT@${v}.0.0:${'d'.repeat(64)}`; }),
        ...['REVIEWED_PREDICTION_CONTEXT', 'POPULATION_ONLY'].map(p => o => alternateContext(o, p)),
    ];
    for (const mutate of mutations) {
        const o = pm1Options(); mutate(o);
        assert.deepEqual(currentBusinessCriteria(o), []);
        assert.throws(() => buildSimplifiedCaseDeclaration(o, o.analysisId, prepareSimplifiedInputs(o)), /PM1/);
    }
    console.log(`  ${mutations.length} PM1 identity negative controls`);
});
test('PM1 v5 cannot cross to PM4 PS1 PM5 or legacy declarations in either builder', () => {
    for (const make of [options.bind(null, 2), pm4Options, ps1Options, pm5Options]) {
        const o = make(), a = o.candidates[0].recommendation.annotations[0];
        Object.assign(a, { ruleContextReady: true, contextSelectable: true, predictionPath: 'REVIEWED_RULE_CONTEXT',
            ruleContextSelectionPolicy: `REVIEWED_RULE_CONTEXT@5.0.0:${'d'.repeat(64)}` });
        assert.throws(() => buildSimplifiedCaseDeclaration(o, o.analysisId, prepareSimplifiedInputs(o)), /PM1|PM4|PS1|PM5/);
        assert.throws(() => buildCaseDeclaration(o, o.analysisId, { ...form, declarationVersion: 'ordinary-pm1-region-v1' }, {}), /PM1/);
    }
    const o = pm1Options();
    assert.throws(() => buildCaseDeclaration(o, o.analysisId, form, {}), /PM1/);
    assert.throws(() => buildCaseDeclaration(o, o.analysisId, { ...form, declarationVersion: o.requiredDeclaration },
        { 'candidate-0': [{ ...h, selectionPolicy: o.candidates[1].recommendation.annotations[0].ruleContextSelectionPolicy }] }), /PM1/);
    const inputs = prepareSimplifiedInputs(o);
    inputs['candidate-0'].annotationId = inputs['candidate-1'].annotationId;
    assert.throws(() => buildSimplifiedCaseDeclaration(o, o.analysisId, inputs), /PM1/);
    const stale = prepareSimplifiedInputs(o); stale['candidate-0'].recommendationId = stale['candidate-1'].recommendationId;
    assert.throws(() => buildSimplifiedCaseDeclaration(o, o.analysisId, stale), /PM1/);
});
test('PM1 new saved identity is distinct from legacy HNF1A and PM1-containing joint results', () => {
    for (const legacy of [{ enabledCriteria: ['ACMG_AMP_2015:PM1'] }, { executionProfile: { profileId: 'joint-v8' }, enabledCriteria: ['ACMG_AMP_2015:PS1', 'ACMG_AMP_2015:PM1'] }]) {
        assert.equal(pm1ScopeClaim(legacy), false); assert.equal(pm1SavedRun(legacy), false);
    }
    const damaged = pm1Options(); damaged.executionProfile.reviewedRuleBinding.status = 'UNAVAILABLE';
    assert.equal(pm1SavedRun({ ...damaged, declarationVersion: damaged.requiredDeclaration }), false);
});
test('PM1 exact reason text distinguishes success, unknown science and explicit negatives', () => {
    assert.match(reasonHelp('PM1_REVIEWED_REGION_MATCH'), /PM1 满足.*实际强度/);
    assert.doesNotMatch(reasonHelp('PM1_REVIEWED_REGION_MATCH'), /需要.*人工复核|HNF1A|GN017/);
    for (const code of ['PM1_REGION_COVERAGE_INCOMPLETE', 'PM1_REGION_QUALIFICATION_UNKNOWN', 'PM1_DISTRIBUTION_UNKNOWN',
        'PM1_ORDINARY_DOMAIN_NOT_QUALIFIED', 'PM1_REGION_REVIEW_MISSING', 'PM1_POLICY_REVIEW_MISSING', 'PM1_MODEL_MISSING', 'PM1_MODEL_SHA_MISMATCH', 'PM1_POLICY_AUTHORITY_MISSING'])
        assert.match(reasonHelp(code), /未评价/);
    for (const code of ['PM1_EXPLICIT_EXCLUSION', 'PM1_BENIGN_DISTRIBUTION_NOT_MET', 'PM1_PATHOGENIC_DISTRIBUTION_NOT_MET'])
        assert.match(reasonHelp(code), /未满足/);
});
assert.ok(checks > 0, 'No ordinary input contract checks matched the filter');
console.log(`${checks}/${checks} ordinary input contract checks passed`);
