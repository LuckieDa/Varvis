import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const drawer = read('src/pages/varvis/ChromosomeVisualization/components/VariantAnalysisDrawer.jsx');
const teachingPanel = read(
    'src/pages/varvis/ChromosomeVisualization/components/TeachingAcmgEvaluationPanel.jsx',
);
const service = read('src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js');
const controller = read('../varvis-server/src/main/java/org/nbscn/varvis/variantanalysis/controller/VariantAnalysisController.java');

// Execute the actual reset callback with setter stubs; catch a retained intronic selection.
const resetBody = drawer.match(/const resetSubmitForm = useCallback\(\(\) => \{([\s\S]*?)\n    \}, \[\]\);/)[1];
let resetScope = 'INCLUDE_INTRONS';
const resetContext = {
    fileInputRef: { current: null },
    selectedFileRef: { current: null },
    TRIO_INPUT_MODE_DISABLED: 'DISABLED',
};
for (const [, setter] of resetBody.matchAll(/\b(set\w+)\(/g)) resetContext[setter] = () => {};
resetContext.setAnalysisScope = (value) => { resetScope = value; };
runInNewContext(resetBody, resetContext);
const { buildVariantAnalysisFormData: buildFormData, submitVariantAnalysis } = runInNewContext(
    service.replace(/^import[^\n]*\n/gm, '').replace(/^export /gm, '') +
        '\n({ buildVariantAnalysisFormData, submitVariantAnalysis })',
    { FormData, axios: { post: async (_url, form) => ({ data: form }) } },
);
const input = { file: new Blob(['synthetic']), sampleId: 'sample' };
const ordinaryUpload = await submitVariantAnalysis(input);
const explicitUpload = await submitVariantAnalysis({ ...input, createNewAnalysis: true });

const checks = [
    { name: 'deletion form reset restores STANDARD after an intronic selection', ok: resetScope === 'STANDARD' },
    { name: 'scope defaults to standard, offers introns without technical inputs', ok: /useState\('STANDARD'\)/.test(drawer) && /value="INCLUDE_INTRONS"/.test(drawer) && /VCF\s+中实际提供/.test(drawer) },
    { name: 'allowed scope flows through multipart and backend request', ok: /formData\.append\('analysisScope', analysisScope\)/.test(service) && /Unsupported analysis scope/.test(service) && /name = "analysisScope"/.test(controller) },
    {
        name: 'single-variant review is opt-in and reaches the multipart request',
        ok: /value="SINGLE_VARIANT_REVIEW"/.test(drawer) &&
            buildFormData(input).get('analysisScope') === 'STANDARD' &&
            buildFormData({ ...input, analysisScope: 'SINGLE_VARIANT_REVIEW' }).get('analysisScope') ===
                'SINGLE_VARIANT_REVIEW',
    },
    { name: 'restored scope is shown with safe legacy fallback', ok: /job\.analysisScopePolicy\?\.scope/.test(drawer) },
    {
        name: 'explicit new submission adds only the new analysis multipart flag for the same file',
        ok: ordinaryUpload.get('createNewAnalysis') === null && explicitUpload.get('createNewAnalysis') === 'true' &&
            (await ordinaryUpload.get('vcfFile').text()) === (await explicitUpload.get('vcfFile').text()),
    },
    {
        name: 'frontend defaults to explicit GRCh38 selection',
        ok: /useState\('GRCh38'\)/.test(drawer),
    },
    {
        name: 'frontend exposes GRCh37 and GRCh38 choices from supported assembly list',
        ok:
            /SUPPORTED_GENOME_ASSEMBLIES\.map/.test(drawer) &&
            /GRCh37/.test(service) &&
            /GRCh38/.test(service),
    },
    {
        name: 'submit handler forwards selected assembly',
        ok: /genomeAssembly:\s*inputGenomeAssembly/.test(drawer),
    },
    {
        name: 'service validates supported assembly before FormData',
        ok:
            /normalizeGenomeAssembly/.test(service) &&
            /Unsupported genome assembly/.test(service) &&
            /buildVariantAnalysisFormData/.test(service),
    },
    {
        name: 'FormData sends genomeAssembly multipart field',
        ok: /formData\.append\('genomeAssembly',\s*normalizedAssembly\)/.test(service),
    },
    {
        name: 'backend endpoint receives genomeAssembly request parameter',
        ok: /@RequestParam\("genomeAssembly"\)\s+String\s+genomeAssembly/.test(controller),
    },
    {
        name: 'READY submission restores the persisted job and candidate list immediately',
        ok:
            /submitted\?\.status === 'READY'/.test(drawer) &&
            /await fetchVariantAnalysis\(submitted\.id\)/.test(drawer) &&
            /await loadVariants\(restored\.id\)/.test(drawer) &&
            /历史分析结果/.test(drawer),
    },
    {
        name: 'obsolete teaching VCF download is absent from UI and public assets',
        ok:
            !/varvis-group-demo\.vcf|下载 ACMG 教学 VCF/.test(drawer) &&
            !/varvis-group-demo\.vcf|下载教学 VCF/.test(teachingPanel) &&
            !existsSync(resolve(root, 'public/demo/group-meeting/varvis-group-demo.vcf')),
    },
    {
        name: 'status UI surfaces pre-ranking telemetry and cap warning',
        ok:
            /latestPreRankingAnnotationSummary/.test(drawer) &&
            /PRE_RANKING_CANDIDATE_CAP_EXCEEDED/.test(drawer) &&
            /selectedForVepCount/.test(drawer) &&
            /skippedByCap/.test(drawer),
    },
];

const failed = checks.filter((check) => !check.ok);
checks.forEach((check) => {
    console.log(`${check.ok ? 'ok' : 'not ok'} - ${check.name}`);
});

if (failed.length) {
    throw new Error(`Variant analysis upload payload validation failed: ${failed.map((check) => check.name).join('; ')}`);
}

console.log('variant_analysis_payload_static_validation_ok');
