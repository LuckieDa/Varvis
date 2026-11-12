import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RUN_FLAG = 'VARVIS_RUN_TRIO_WEIGHTED_E2E';
const scenario = process.env.VARVIS_TRIO_WEIGHTED_E2E_SCENARIO || 'disabled';
const baseUrl = process.env.VARVIS_E2E_BASE_URL || 'http://localhost:8000/varvis/chromosome-visualization';
const headless = process.env.VARVIS_E2E_HEADLESS !== 'false';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const trioFixtureRoot = path.join(repoRoot, 'varvis-server', 'src', 'test', 'resources', 'trio');

const fixtures = {
    jointDeNovo: path.join(trioFixtureRoot, 'joint_trio_de_novo.synthetic.vcf'),
    probandSingle: path.join(trioFixtureRoot, 'three_single_proband.synthetic.vcf'),
    fatherMissing: path.join(trioFixtureRoot, 'three_single_father_missing.synthetic.vcf'),
    motherSingle: path.join(trioFixtureRoot, 'three_single_mother.synthetic.vcf'),
};

function skip(message) {
    console.log(`SKIPPED trio weighted browser smoke: ${message}`);
}

async function loadPlaywright() {
    try {
        return await import('playwright');
    } catch (error) {
        return null;
    }
}

function assertFixture(file) {
    if (!fs.existsSync(file)) {
        throw new Error(`Missing synthetic fixture: ${file}`);
    }
}

async function selectMuiOption(page, testId, optionName) {
    await page.getByTestId(testId).click();
    await page.getByRole('option', { name: optionName }).click();
}

async function maybeSelectSample(page, testId, sampleId) {
    const control = page.getByTestId(testId);
    await control.waitFor({ timeout: 30000 });
    const tagName = await control.evaluate((node) => node.tagName.toLowerCase());
    if (tagName === 'input' || tagName === 'textarea') {
        await control.fill(sampleId);
        return;
    }
    await selectMuiOption(page, testId, sampleId);
}

async function openAnalysisLab(page) {
    await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
    const openButton = page.getByTestId('open-analysis-lab-button');
    if (await openButton.isVisible({ timeout: 30000 }).catch(() => false)) {
        await openButton.click();
    }
    await page.getByTestId('primary-vcf-input').waitFor({ timeout: 30000 });
}

async function waitForReady(page) {
    await page.getByText('Variant analysis is ready').waitFor({ timeout: 180000 });
    await page.getByTestId('variant-analysis-view-top-candidate').click();
    await page.getByTestId('variant-evidence-detail').waitFor({ timeout: 30000 });
}

async function assertSafetyCopy(page) {
    await page.getByText('Ranking support only; not a diagnosis.').waitFor({ timeout: 30000 });
    await page.getByText('Evidence rawScore remains separate from ranking contribution.').waitFor({ timeout: 30000 });
    const misleading = page.getByText(/pathogenic confirmed|clinical diagnosis confirmed/i);
    if (await misleading.isVisible({ timeout: 1000 }).catch(() => false)) {
        throw new Error('Misleading diagnosis/pathogenic confirmation copy is visible.');
    }
}

async function runJointDeNovo(page) {
    assertFixture(fixtures.jointDeNovo);
    await openAnalysisLab(page);
    await page.getByTestId('primary-vcf-input').setInputFiles(fixtures.jointDeNovo);
    await selectMuiOption(page, 'input-build-select', 'GRCh37 / hg19');
    await selectMuiOption(page, 'family-mode-select', 'Trio joint VCF');
    await page.getByTestId('primary-sample-detect-button').click();
    await page.getByText('3 samples detected from the VCF header.').waitFor({ timeout: 60000 });
    await maybeSelectSample(page, 'trio-proband-sample-select', 'proband');
    await maybeSelectSample(page, 'trio-father-sample-select', 'father');
    await maybeSelectSample(page, 'trio-mother-sample-select', 'mother');
    await page.getByTestId('variant-analysis-submit-button').click();
    await waitForReady(page);
}

async function runThreeSingleNeutral(page) {
    Object.values(fixtures).forEach(assertFixture);
    await openAnalysisLab(page);
    await page.getByTestId('primary-vcf-input').setInputFiles(fixtures.probandSingle);
    await selectMuiOption(page, 'input-build-select', 'GRCh37 / hg19');
    await selectMuiOption(page, 'family-mode-select', 'Trio three VCFs');
    await page.getByTestId('trio-father-vcf-input').setInputFiles(fixtures.fatherMissing);
    await page.getByTestId('trio-mother-vcf-input').setInputFiles(fixtures.motherSingle);
    await page.getByTestId('primary-sample-detect-button').click();
    await page.getByTestId('trio-father-sample-detect-button').click();
    await page.getByTestId('trio-mother-sample-detect-button').click();
    await maybeSelectSample(page, 'trio-proband-sample-select', 'proband');
    await maybeSelectSample(page, 'trio-father-sample-select', 'father');
    await maybeSelectSample(page, 'trio-mother-sample-select', 'mother');
    await page.getByTestId('variant-analysis-submit-button').click();
    await waitForReady(page);
}

async function assertDisabled(page) {
    await page.getByTestId('trio-evidence-detail').waitFor({ timeout: 30000 });
    await page.getByText('DE_NOVO_CANDIDATE').waitFor({ timeout: 30000 });
    await page.getByText('SUPPORTED').waitFor({ timeout: 30000 });
    await page.getByTestId('trio-ranking-disabled-message').waitFor({ timeout: 30000 });
    await page.getByText('Trio weighted ranking is disabled. Trio evidence is shown as explanation only.').waitFor();
    await assertSafetyCopy(page);
}

async function assertWeighted(page) {
    await page.getByTestId('trio-evidence-detail').waitFor({ timeout: 30000 });
    await page.getByText('DE_NOVO_CANDIDATE').waitFor({ timeout: 30000 });
    await page.getByText('SUPPORTED').waitFor({ timeout: 30000 });
    await page.getByTestId('trio-ranking-contribution').getByText('BOOST').waitFor({ timeout: 30000 });
    await page.getByTestId('trio-ranking-contribution').getByText('Score +2').waitFor();
    await page.getByTestId('trio-ranking-contribution').getByText('Cap +2').waitFor();
    await page.getByTestId('variant-detail-tab-score').click();
    await page.getByTestId('trio-score-breakdown').waitFor({ timeout: 30000 });
    await page.getByText('Trio Segregation Support').waitFor();
    await assertSafetyCopy(page);
}

async function assertNeutral(page) {
    await page.getByTestId('trio-evidence-detail').waitFor({ timeout: 30000 });
    await page.getByText('UNOBSERVED').waitFor({ timeout: 30000 });
    await page.getByText(/PARENT UNOBSERVED|No ranking contribution/i).waitFor({ timeout: 30000 });
    await assertSafetyCopy(page);
}

async function main() {
    if (process.env[RUN_FLAG] !== 'true') {
        skip(`set ${RUN_FLAG}=true to run against an already started frontend/backend`);
        return;
    }
    const playwright = await loadPlaywright();
    if (!playwright) {
        throw new Error('Playwright is not installed. Install/provide playwright before running this optional browser smoke.');
    }
    const browserName = process.env.VARVIS_E2E_BROWSER || 'chromium';
    const browserType = playwright[browserName];
    if (!browserType) {
        throw new Error(`Unsupported Playwright browser type: ${browserName}`);
    }
    const browser = await browserType.launch({ headless });
    const page = await browser.newPage();
    try {
        if (scenario === 'neutral') {
            await runThreeSingleNeutral(page);
            await assertNeutral(page);
        } else {
            await runJointDeNovo(page);
            if (scenario === 'weighted') {
                await assertWeighted(page);
            } else if (scenario === 'disabled') {
                await assertDisabled(page);
            } else {
                throw new Error(`Unsupported scenario: ${scenario}`);
            }
        }
        console.log(`PASS trio weighted browser smoke scenario=${scenario}`);
    } finally {
        await browser.close();
    }
}

main().catch((error) => {
    console.error(error?.stack || error?.message || error);
    process.exitCode = 1;
});
