import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const RUN_FLAG = 'VARVIS_RUN_RANKING_EXPLANATION_E2E';
const baseUrl =
    process.env.VARVIS_E2E_BASE_URL ||
    'http://localhost:8000/varvis/chromosome-visualization';
const headless = process.env.VARVIS_E2E_HEADLESS !== 'false';
const analysisId = 'synthetic-analysis';

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function loadPlaywright() {
    try {
        return await import('playwright');
    } catch {
        return null;
    }
}

const variant = (variantId, position, reference, alternate, geneSymbol, rankingScore) => ({
    variantId,
    analysisBucket: 'CANDIDATE',
    priorityTier: 'HIGH',
    rankingScore,
    summaryOnly: false,
    fullInterpretationAvailable: true,
    expensiveAnnotationSkipped: false,
    observation: {
        key: { chromosome: '1', position, reference, alternate },
        genotype: '0/1',
        filterStatus: 'PASS',
        depth: 30,
        genotypeQuality: 60,
        alleleBalance: 0.5,
    },
    consequences: [
        {
            source: 'VEP',
            geneSymbol,
            consequence: 'missense_variant',
            impact: 'MODERATE',
            biotype: 'protein_coding',
        },
    ],
    transcriptAnnotations: [],
    evidence: [],
    scoreComponents: {},
    scoreBreakdown: {},
    positiveReasons: [],
    negativeReasons: [],
    filterReasons: [],
    warnings: [],
    rationale: [],
});

const variants = [
    variant('variant-a', 101, 'A', 'G', 'GENEA', 11),
    variant('variant-b', 202, 'C', 'T', 'GENEB', -7),
    variant('variant-c', 303, 'G', 'A', 'GENEC', 0),
    variant('variant-d', 404, 'T', 'C', 'GENED', 3),
];

const contribution = (key, score, direction) => ({
    key,
    score,
    direction,
    reasons: ['D:\\restricted\\patient-canary.vcf'],
    derivedFrom: 'SCORE_COMPONENT',
});

const evidence = (source, availabilityMeaning) => ({
    source,
    resourceVersion: 'restricted-version-canary',
    evidenceType: 'PREDICTION',
    applicability: 'APPLICABLE',
    status: 'AVAILABLE',
    evidenceStrength: 'SUPPORTING',
    explanationCode: 'restricted-explanation-canary',
    retrievedAt: '2026-01-01T00:00:00Z',
    availabilityMeaning,
    details: { path: 'D:\\restricted\\patient-canary.vcf' },
});

const explanation = (variantId, rankingScore, contributions, evidenceBuckets = {}) => ({
    schemaVersion: 'candidate-ranking-explanation-v1',
    analysisId,
    variantId,
    priorityTier: 'HIGH',
    rankingScore,
    tierReason: 'restricted-tier-reason-canary',
    completeness: 'COMPLETE',
    contributions,
    positiveContributions: contributions.filter((item) => item.direction === 'POSITIVE'),
    negativeContributions: contributions.filter((item) => item.direction === 'NEGATIVE'),
    neutralContributions: contributions.filter((item) => item.direction === 'NEUTRAL'),
    availableEvidence: evidenceBuckets.available || [],
    conflictingEvidence: evidenceBuckets.conflicting || [],
    missingEvidence: evidenceBuckets.missing || [],
    warnings: evidenceBuckets.warnings || [],
    provenance: {
        explanationMode: 'DERIVED_FROM_PERSISTED_RANKING_OUTPUT',
        rescored: false,
        path: 'D:\\restricted\\patient-canary.vcf',
    },
    disclaimer: 'restricted-disclaimer-canary',
});

const explanations = {
    'variant-a': explanation('variant-a', 11, [
        contribution('clinical_evidence', 11, 'POSITIVE'),
    ]),
    'variant-b': explanation(
        'variant-b',
        -7,
        [
            contribution('population_frequency', -7, 'NEGATIVE'),
            contribution('genotype_support', null, 'NEUTRAL'),
        ],
        {
            conflicting: [
                evidence('CLINVAR', 'EVIDENCE_REPORTED_CONFLICTING'),
            ],
            missing: [evidence('GNOMAD', 'EVIDENCE_UNAVAILABLE')],
            warnings: [
                'One or more persisted scores were non-finite and were omitted from this explanation.',
            ],
        },
    ),
    'variant-d': {
        ...explanation('variant-d', 3, [
            contribution('computational_prediction', 3, 'POSITIVE'),
        ]),
        analysisId: 'mismatched-analysis',
    },
};

const job = {
    id: analysisId,
    status: 'READY',
    genomeAssembly: 'GRCh38',
    analysisMode: 'WES',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:01Z',
    warnings: [],
};

const jsonResponse = (route, body, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function installRoutes(page, explanationRequests) {
    await page.route(/\/variant-analyses(?:\/|$)/, async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const requestPath = url.pathname;
        const method = request.method();

        if (requestPath === '/variant-analyses/providers' && method === 'GET') {
            await jsonResponse(route, {
                defaultEngine: 'EXOMISER',
                analysisAssemblyPolicy: 'GRCh38',
                normalizationMode: 'COPY_ONLY',
                exomiser: { ready: true, status: 'READY' },
                normalizationProfiles: {},
                vepProfiles: {},
            });
            return;
        }
        if (requestPath === '/variant-analyses/preflight' && method === 'GET') {
            await jsonResponse(route, {
                applicationReady: true,
                readinessStatus: 'READY',
                requiredComponents: [],
                optionalComponents: [],
                blockingErrors: [],
                warnings: [],
            });
            return;
        }
        if (requestPath === '/variant-analyses/sample-detection' && method === 'POST') {
            await jsonResponse(route, {
                samples: ['SYNTHETIC_SAMPLE'],
                assemblyHint: 'GRCh38',
                assemblyConfirmationRequired: false,
                warnings: [],
            });
            return;
        }
        if (requestPath === '/variant-analyses' && method === 'POST') {
            await jsonResponse(route, job, 202);
            return;
        }
        if (requestPath === `/variant-analyses/${analysisId}` && method === 'GET') {
            await jsonResponse(route, job);
            return;
        }
        if (requestPath === `/variant-analyses/${analysisId}/events` && method === 'GET') {
            await jsonResponse(route, []);
            return;
        }
        if (requestPath === `/variant-analyses/${analysisId}/variants` && method === 'GET') {
            await jsonResponse(route, {
                variants,
                total: variants.length,
                page: 1,
                pageSize: 50,
                view: 'CANDIDATES',
                candidateTotal: variants.length,
                frequencyFilteredTotal: 0,
                allTotal: variants.length,
                populationFrequencyThreshold: 0.01,
            });
            return;
        }

        const explanationMatch = requestPath.match(
            new RegExp(`^/variant-analyses/${analysisId}/variants/([^/]+)/ranking-explanation$`),
        );
        if (explanationMatch && method === 'GET') {
            const variantId = decodeURIComponent(explanationMatch[1]);
            explanationRequests.set(variantId, (explanationRequests.get(variantId) || 0) + 1);
            if (variantId === 'variant-a') await sleep(700);
            if (variantId === 'variant-c') {
                await jsonResponse(route, { message: 'restricted backend error canary' }, 404);
                return;
            }
            try {
                await jsonResponse(route, explanations[variantId]);
            } catch {
                // An aborted stale request may close its route before the delayed fulfill.
            }
            return;
        }

        const detailMatch = requestPath.match(
            new RegExp(`^/variant-analyses/${analysisId}/variants/([^/]+)$`),
        );
        if (detailMatch && method === 'GET') {
            const variantId = decodeURIComponent(detailMatch[1]);
            const selected = variants.find((item) => item.variantId === variantId);
            await jsonResponse(route, selected || { message: 'not found' }, selected ? 200 : 404);
            return;
        }

        await jsonResponse(route, { message: 'unmatched synthetic route' }, 404);
    });
}

async function openReadyAnalysis(page, syntheticVcf) {
    await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
    const openButton = page.getByTestId('open-analysis-lab-button');
    if (await openButton.isVisible({ timeout: 30000 }).catch(() => false)) {
        await openButton.click();
    }
    await page.getByTestId('primary-vcf-input').setInputFiles(syntheticVcf);
    await page.getByText('1 sample detected from the VCF header.').waitFor({ timeout: 30000 });
    await page.getByTestId('variant-analysis-submit-button').click();
    await page.getByText('Variant analysis is ready').waitFor({ timeout: 30000 });
    await page.getByTestId('variant-analysis-view-top-candidate').click();
    await page.getByTestId('variant-evidence-detail').waitFor({ timeout: 30000 });
}

async function selectVariant(page, coordinate) {
    await page.locator('.cv-analysis-variant-link', { hasText: coordinate }).click();
    await page.getByTestId('variant-evidence-detail').waitFor({ timeout: 30000 });
}

async function main() {
    if (process.env[RUN_FLAG] !== 'true') {
        console.log(`SKIPPED ranking explanation browser smoke: set ${RUN_FLAG}=true`);
        return;
    }
    const playwright = await loadPlaywright();
    if (!playwright) {
        throw new Error('Playwright is not installed; provide it for this optional browser smoke.');
    }
    const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'varvis-ranking-explanation-'));
    const syntheticVcf = path.join(temporaryRoot, 'synthetic.vcf');
    fs.writeFileSync(
        syntheticVcf,
        '##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSYNTHETIC_SAMPLE\n1\t101\t.\tA\tG\t60\tPASS\t.\tGT\t0/1\n',
        'utf8',
    );
    const browserName = process.env.VARVIS_E2E_BROWSER || 'chromium';
    const browserType = playwright[browserName];
    if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);
    const browser = await browserType.launch({ headless });
    const page = await browser.newPage();
    const explanationRequests = new Map();
    try {
        await installRoutes(page, explanationRequests);
        await openReadyAnalysis(page, syntheticVcf);
        if ([...explanationRequests.values()].reduce((sum, count) => sum + count, 0) !== 0) {
            throw new Error('Ranking explanation was requested before its tab was opened.');
        }

        await page.getByTestId('variant-detail-tab-ranking-explanation').click();
        await page.getByTestId('ranking-explanation-loading').waitFor();
        await selectVariant(page, 'chr1:202');
        await page.getByTestId('variant-detail-tab-ranking-explanation').click();
        await page.getByTestId('candidate-ranking-explanation-panel').waitFor();
        await page.getByTestId('ranking-explanation-total-score').getByText('Score -7').waitFor();
        await page.getByText('Score unavailable').waitFor();
        await sleep(900);
        await page.getByTestId('ranking-explanation-total-score').getByText('Score -7').waitFor();
        if (await page.getByText('Score +11').isVisible().catch(() => false)) {
            throw new Error('A stale candidate explanation replaced the current candidate.');
        }
        if (await page.getByText(/patient-canary|restricted-/i).isVisible().catch(() => false)) {
            throw new Error('Restricted backend text was rendered by the explanation panel.');
        }

        await selectVariant(page, 'chr1:101');
        await selectVariant(page, 'chr1:202');
        await page.getByTestId('variant-detail-tab-ranking-explanation').click();
        await page.getByTestId('candidate-ranking-explanation-panel').waitFor();
        if (explanationRequests.get('variant-b') !== 1) {
            throw new Error('The cached analysisId/variantId explanation was requested again.');
        }

        await selectVariant(page, 'chr1:303');
        await page.getByTestId('variant-detail-tab-ranking-explanation').click();
        await page.getByTestId('ranking-explanation-error').waitFor();
        await page.getByText('No structured ranking explanation is available for this candidate.').waitFor();
        await page.getByText('Variant analysis is ready').waitFor();

        await selectVariant(page, 'chr1:404');
        await page.getByTestId('variant-detail-tab-ranking-explanation').click();
        await page.getByTestId('ranking-explanation-error').waitFor();
        await page.getByText('The explanation response could not be safely verified.').waitFor();
        await page.getByText('Variant analysis is ready').waitFor();

        console.log('PASS ranking explanation lazy/race/cache/404/contract route smoke');
    } finally {
        await browser.close();
        fs.rmSync(temporaryRoot, { recursive: true, force: true });
    }
}

main().catch((error) => {
    console.error(error?.stack || error?.message || error);
    process.exitCode = 1;
});
