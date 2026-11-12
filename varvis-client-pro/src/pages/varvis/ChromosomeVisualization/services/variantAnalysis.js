import axios from 'axios';
import { watchSubmissionProgress } from '../utils/acmgRunFeedback';

const ANALYSIS_BASE = '/variant-analyses';
const UPLOAD_TIMEOUT_MS = 10 * 60 * 1000;
const ANALYSIS_READ_TIMEOUT_MS = 120 * 1000;
const ANALYSIS_DELETE_TIMEOUT_MS = 120 * 1000;
const SAMPLE_DETECTION_TIMEOUT_MS = 60 * 1000;
const SAMPLE_DETECTION_PREFIX_BYTES = 8 * 1024 * 1024;
const CSV_EXPORT_TIMEOUT_MS = 5 * 60 * 1000;
export const SUPPORTED_GENOME_ASSEMBLIES = ['GRCh38', 'GRCh37'];
const SEMANTIC_FALSE_FILTER_KEYS = new Set([
    'hasAcmgSuggestion',
    'manualReviewRequired',
    'hasPhenotypeSupport',
]);

const shouldIncludeParam = (key, value) =>
    value !== undefined &&
    value !== null &&
    value !== '' &&
    (value !== false || SEMANTIC_FALSE_FILTER_KEYS.has(key));

const filenameFromContentDisposition = (value) => {
    if (!value) return '';
    const encodedMatch = /filename\*=UTF-8''([^;]+)/i.exec(value);
    if (encodedMatch?.[1]) return decodeURIComponent(encodedMatch[1].trim());
    const plainMatch = /filename="?([^";]+)"?/i.exec(value);
    return plainMatch?.[1]?.trim() || '';
};

const exportParams = ({ limit = 50, view = 'CANDIDATES', filters = {} } = {}) => {
    const params = { limit, view };
    Object.entries(filters || {}).forEach(([key, value]) => {
        if (shouldIncludeParam(key, value)) {
            params[key] = value;
        }
    });
    return params;
};

export const normalizeGenomeAssembly = (value = 'GRCh38') => {
    const normalized = String(value || '').trim();
    if (!SUPPORTED_GENOME_ASSEMBLIES.includes(normalized)) {
        throw new Error(
            `Unsupported genome assembly "${normalized || '(empty)'}"; choose GRCh37 or GRCh38.`,
        );
    }
    return normalized;
};

export const buildVariantAnalysisFormData = ({
    file,
    genomeAssembly = 'GRCh38',
    analysisMode = 'WES',
    analysisScope = 'STANDARD',
    createNewAnalysis = false,
    sampleId = '',
    hpoTerms = [],
    excludedHpoTerms = [],
    hpoProvenance = [],
    trioInput = null,
}) => {
    if (!file) throw new Error('VCF file is required');
    const normalizedAssembly = normalizeGenomeAssembly(genomeAssembly);
    if (!['STANDARD', 'INCLUDE_INTRONS', 'SINGLE_VARIANT_REVIEW'].includes(analysisScope))
        throw new Error('Unsupported analysis scope');
    const formData = new FormData();
    formData.append('vcfFile', file);
    formData.append('genomeAssembly', normalizedAssembly);
    formData.append('analysisMode', analysisMode);
    formData.append('analysisScope', analysisScope);
    if (createNewAnalysis === true) formData.append('createNewAnalysis', 'true');
    if (sampleId.trim()) formData.append('sampleId', sampleId.trim());
    (Array.isArray(hpoTerms) ? hpoTerms : []).forEach((term) => {
        const normalized = String(term || '').trim();
        if (normalized) formData.append('hpoTerms', normalized);
    });
    (Array.isArray(excludedHpoTerms) ? excludedHpoTerms : []).forEach((term) => {
        const normalized = String(term || '').trim();
        if (normalized) formData.append('excludedHpoTerms', normalized);
    });
    (Array.isArray(hpoProvenance) ? hpoProvenance : []).forEach((entry) => {
        if (!entry?.hpoId) return;
        formData.append('hpoProvenance', JSON.stringify(entry));
    });
    if (trioInput?.enabled) {
        formData.append('trioEnabled', 'true');
        formData.append('trioInputMode', trioInput.inputMode || 'JOINT_VCF');
        formData.append('trioEvidenceOnly', 'true');
        if (trioInput.probandSampleId?.trim()) {
            formData.append('trioProbandSampleId', trioInput.probandSampleId.trim());
        }
        if (trioInput.fatherSampleId?.trim()) {
            formData.append('trioFatherSampleId', trioInput.fatherSampleId.trim());
        }
        if (trioInput.motherSampleId?.trim()) {
            formData.append('trioMotherSampleId', trioInput.motherSampleId.trim());
        }
        ['proband', 'father', 'mother'].forEach((role) => {
            const sex = trioInput[`${role}Sex`];
            const affected = trioInput[`${role}Affected`];
            if (sex) formData.append(`trio${role[0].toUpperCase()}${role.slice(1)}Sex`, sex);
            if (affected === true || affected === false) {
                formData.append(
                    `trio${role[0].toUpperCase()}${role.slice(1)}Affected`,
                    String(affected),
                );
            }
        });
        if (trioInput.minDepthForConfirmedRef) {
            formData.append('trioMinDepthForConfirmedRef', trioInput.minDepthForConfirmedRef);
        }
        if (trioInput.minGqForConfirmedRef) {
            formData.append('trioMinGqForConfirmedRef', trioInput.minGqForConfirmedRef);
        }
    }
    return formData;
};

export const fetchVariantAnalysisProviderStatus = async () => {
    const response = await axios.get(`${ANALYSIS_BASE}/providers`, { timeout: 20000 });
    return response?.data || null;
};

export const fetchVariantAnalysisPreflight = async (genomeAssembly = 'GRCh38') => {
    const response = await axios.get(`${ANALYSIS_BASE}/preflight`, {
        params: { genomeAssembly: normalizeGenomeAssembly(genomeAssembly) },
        timeout: 20000,
    });
    return response?.data || null;
};

export const detectVariantAnalysisSamples = async (file) => {
    if (!file) throw new Error('VCF file is required for sample detection');
    const formData = new FormData();
    const payload =
        Number(file.size || 0) > SAMPLE_DETECTION_PREFIX_BYTES
            ? file.slice(0, SAMPLE_DETECTION_PREFIX_BYTES, file.type || 'application/octet-stream')
            : file;
    formData.append('vcfFile', payload, file.name || 'sample.vcf');
    const response = await axios.post(`${ANALYSIS_BASE}/sample-detection`, formData, {
        timeout: SAMPLE_DETECTION_TIMEOUT_MS,
    });
    return response?.data || null;
};

export const submitVariantAnalysis = async ({
    file,
    genomeAssembly = 'GRCh38',
    analysisMode = 'WES',
    analysisScope = 'STANDARD',
    createNewAnalysis = false,
    sampleId = '',
    hpoTerms = [],
    excludedHpoTerms = [],
    hpoProvenance = [],
    trioInput = null,
}) => {
    const formData = buildVariantAnalysisFormData({
        file,
        genomeAssembly,
        analysisMode,
        analysisScope,
        createNewAnalysis,
        sampleId,
        hpoTerms,
        excludedHpoTerms,
        hpoProvenance,
        trioInput,
    });
    const response = await axios.post(ANALYSIS_BASE, formData, { timeout: UPLOAD_TIMEOUT_MS });
    return response?.data || null;
};

export const findVariantAnalysisHistory = async ({ file }) => {
    if (!file) throw new Error('VCF file is required');
    const formData = new FormData();
    formData.append('vcfFile', file);
    const response = await axios.post(`${ANALYSIS_BASE}/history`, formData, {
        timeout: UPLOAD_TIMEOUT_MS,
    });
    return response?.data || null;
};

export const fetchPhenotypeGenePriorLookup = async (analysisId, gene) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/phenotype-prior`,
        {
            params: { gene },
            timeout: ANALYSIS_READ_TIMEOUT_MS,
        },
    );
    return response?.data || null;
};

export const fetchVariantAnalysis = async (analysisId) => {
    const response = await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}`, {
        timeout: 20000,
    });
    return response?.data || null;
};

export const fetchVariantAnalysisEvents = async (analysisId) => {
    const response = await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/events`, {
        timeout: 20000,
    });
    return response?.data || [];
};

export const fetchVariantAnalysisVariants = async (
    analysisId,
    page = 1,
    pageSize = 100,
    view = 'CANDIDATES',
    filters = {},
) => {
    const params = { page, pageSize, view };
    Object.entries(filters || {}).forEach(([key, value]) => {
        if (shouldIncludeParam(key, value)) {
            params[key] = value;
        }
    });
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/variants/grouped`,
        {
            params,
            timeout: ANALYSIS_READ_TIMEOUT_MS,
        },
    );
    return response?.data || null;
};

export const fetchVariantAnalysisVariant = async (analysisId, variantId, { signal } = {}) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/variants/${encodeURIComponent(variantId)}`,
        { signal, timeout: ANALYSIS_READ_TIMEOUT_MS },
    );
    return response?.data || null;
};

const candidateRouteIdentity = (candidate) => {
    const candidateId = String(candidate?.candidateId || '').trim();
    const variantId = String(
        typeof candidate === 'string'
            ? candidate
            : candidate?.variantId || candidate?.canonicalVariantId || '',
    ).trim();
    return { candidateId, variantId };
};

export const fetchVariantAnalysisCandidate = async (analysisId, candidate, { signal } = {}) => {
    const identity = candidateRouteIdentity(candidate);
    if (!identity.candidateId) {
        return fetchVariantAnalysisVariant(analysisId, identity.variantId, { signal });
    }
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/candidates/${encodeURIComponent(
            identity.candidateId,
        )}`,
        { signal, timeout: ANALYSIS_READ_TIMEOUT_MS },
    );
    return response?.data || null;
};

export const fetchVariantAnalysisRankingExplanation = async (
    analysisId,
    variantId,
    { signal } = {},
) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/variants/${encodeURIComponent(
            variantId,
        )}/ranking-explanation`,
        {
            signal,
            timeout: ANALYSIS_READ_TIMEOUT_MS,
        },
    );
    return response?.data || null;
};

export const fetchVariantAnalysisCandidateRankingExplanation = async (
    analysisId,
    candidate,
    { signal } = {},
) => {
    const identity = candidateRouteIdentity(candidate);
    if (!identity.candidateId) {
        return fetchVariantAnalysisRankingExplanation(analysisId, identity.variantId, { signal });
    }
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/candidates/${encodeURIComponent(
            identity.candidateId,
        )}/ranking-explanation`,
        {
            signal,
            timeout: ANALYSIS_READ_TIMEOUT_MS,
        },
    );
    return response?.data || null;
};

export const downloadVariantAnalysisExport = async (
    analysisId,
    format,
    { limit = 50, view = 'CANDIDATES', filters = {} } = {},
) => {
    const normalizedFormat = String(format || '').toLowerCase();
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/exports/${normalizedFormat}`,
        {
            params: exportParams({ limit, view, filters }),
            responseType: 'blob',
            timeout: normalizedFormat === 'csv' ? CSV_EXPORT_TIMEOUT_MS : 60000,
        },
    );
    return {
        blob: response?.data,
        filename:
            filenameFromContentDisposition(response?.headers?.['content-disposition']) ||
            `varvis-${String(analysisId || 'analysis').slice(0, 8)}-${
                normalizedFormat === 'csv' ? 'all-filtered' : 'top50'
            }.${normalizedFormat}`,
    };
};

export const submitAlphaGenomeEnhancement = async (analysisId, candidateLimit = 20) => {
    const response = await axios.post(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/enhancements`,
        null,
        {
            params: {
                provider: 'ALPHAGENOME',
                candidateLimit,
            },
            timeout: 20000,
        },
    );
    return response?.data || null;
};

export const fetchVariantAnalysisEnhancement = async (analysisId, enhancementId) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/enhancements/${encodeURIComponent(enhancementId)}`,
        { timeout: 20000 },
    );
    return response?.data || null;
};

export const removeVariantAnalysis = async (analysisId) => {
    await axios.delete(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}`, {
        timeout: ANALYSIS_DELETE_TIMEOUT_MS,
    });
};

export const fetchAcmgBackground = async (analysisId, topN) => {
    const response = await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/background-options`,
        { params: { topN }, timeout: ANALYSIS_READ_TIMEOUT_MS });
    return response.data;
};

export const submitConfirmedAcmgRun = async (analysisId, declaration, backgroundSha256, onProgress = () => undefined) => {
    const operationId = globalThis.crypto.randomUUID();
    const root = `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg`;
    const read = async () => {
        const { data } = await axios.get(`${root}/operations/${operationId}/progress`, { timeout: 15000 });
        if (data?.analysisId !== analysisId || data?.operationId !== operationId) throw new Error('进度身份不一致');
        return data;
    };
    const stop = watchSubmissionProgress({ read, onProgress });
    try {
        const response = await axios.post(`${root}/confirmed-runs`,
            { confirmed: true, backgroundSha256, declaration },
            { timeout: 15 * 60 * 1000, headers: { 'X-Varvis-Progress-Id': operationId, 'X-Varvis-Result-View': 'index' } });
        return response.data;
    } catch (error) {
        stop();
        // A fast rejection or a lost response may arrive before the next scheduled GET.
        try { onProgress(await read()); } catch (_) { /* Preserve the submission error. */ }
        throw error;
    } finally { stop(); }
};

export const fetchAcmgControl = async (analysisId, runId) =>
    (await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/control`, { timeout: 15000 })).data;
export const commandAcmgControl = async (analysisId, runId, action) =>
    (await axios.post(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/control/${action}`, null, { timeout: 30000 })).data;

export const submitAcmgBusinessRun = async (analysisId, declaration, workflow = 'CURRENT') => {
    const response = await axios.post(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs`,
        declaration,
        { timeout: 15 * 60 * 1000, ...(workflow !== 'CURRENT' ? { params: { workflow } } : {}) },
    );
    return response.data;
};

export const fetchAcmgInputOptions = async (analysisId, topN = 20, workflow = 'CURRENT') => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/input-options`,
        { params: { topN, ...(workflow !== 'CURRENT' ? { workflow } : {}) }, timeout: ANALYSIS_READ_TIMEOUT_MS },
    );
    return response.data;
};

export const fetchAcmgBusinessRuns = async (analysisId) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs`,
        { timeout: ANALYSIS_READ_TIMEOUT_MS },
    );
    return response.data;
};

export const fetchAcmgBusinessRun = async (analysisId, runId, sourceViewSha256) => {
    const response = await axios.get(
        `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}`,
        { timeout: 6 * 60 * 1000, ...(sourceViewSha256 ? { params: { sourceViewSha256 } } : {}) },
    );
    return response.data;
};

export const fetchAcmgResultView = async (analysisId, runId) => {
    const response = await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/view`,
        { timeout: 6 * 60 * 1000 });
    return response.data;
};

export const fetchAcmgResultBackground = async (run, candidateId, backgroundIndex) => {
    const response = await axios.get(`${ANALYSIS_BASE}/${encodeURIComponent(run.analysisId)}/acmg/runs/${encodeURIComponent(run.runId)}/view/background`,
        { params: { candidateId, backgroundIndex, sourceSha256: run.sourceViewSha256 }, timeout: 6 * 60 * 1000 });
    return response.data;
};

export const splicingExperimentCheckRequest = async (analysisId, runId, method, suffix = '') => {
    const url = `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/splicing-checks${suffix}`;
    const response = await axios({ method, url, timeout: ANALYSIS_READ_TIMEOUT_MS });
    return response.data;
};

export const functionalReviewRequest = async (analysisId, runId, method, data = null, suffix = '') => {
    const url = `${ANALYSIS_BASE}/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/functional-reviews${suffix}`;
    const response = await axios({ method, url, data, timeout: ANALYSIS_READ_TIMEOUT_MS });
    return response.data;
};
