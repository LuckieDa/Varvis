import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { formatVariantCoordinate } from '../utils/variantCoordinate';
import {
    Alert,
    Box,
    Button,
    Checkbox,
    Chip,
    CircularProgress,
    Collapse,
    Dialog,
    Divider,
    FormControl,
    FormControlLabel,
    IconButton,
    InputLabel,
    LinearProgress,
    MenuItem,
    Pagination,
    Select,
    Stack,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableRow,
    TextField,
    Tooltip,
    Typography,
} from '@mui/material';
import BiotechRoundedIcon from '@mui/icons-material/BiotechRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import FileDownloadOutlinedIcon from '@mui/icons-material/FileDownloadOutlined';
import LocationOnOutlinedIcon from '@mui/icons-material/LocationOnOutlined';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import {
    fetchVariantAnalysis,
    fetchVariantAnalysisEnhancement,
    fetchVariantAnalysisEvents,
    fetchPhenotypeGenePriorLookup,
    fetchVariantAnalysisPreflight,
    fetchVariantAnalysisProviderStatus,
    fetchVariantAnalysisCandidate,
    fetchVariantAnalysisCandidateRankingExplanation,
    fetchVariantAnalysisVariants,
    findVariantAnalysisHistory,
    detectVariantAnalysisSamples,
    downloadVariantAnalysisExport,
    removeVariantAnalysis,
    submitAlphaGenomeEnhancement,
    submitVariantAnalysis,
    SUPPORTED_GENOME_ASSEMBLIES,
} from '../services/variantAnalysis';
import CandidateRankingExplanationPanel from './CandidateRankingExplanationPanel';
import AnalysisLoadingNotice from './AnalysisLoadingNotice';
import TopNAcmgEvaluationPanel from './TopNAcmgEvaluationPanel';
import { vepJobExecution, vepStatusExplanation, LOCAL_POPULATION_SCOPE } from '../utils/analysisReadiness';

const ACTIVE_ANALYSIS_STATUSES = new Set([
    'UPLOADED',
    'VALIDATING',
    'NORMALIZING',
    'QC_EXTRACTING',
    'BASE_ANNOTATING',
    'RANKING',
]);
const ACTIVE_ENHANCEMENT_STATUSES = new Set(['QUEUED', 'RUNNING']);
const ACTIVE_TRANSCRIPT_ANNOTATION_STATUSES = new Set(['PENDING', 'RUNNING']);
const ANALYSIS_SCOPE_LABELS = {
    STANDARD: '常规分析',
    INCLUDE_INTRONS: '包含内含子变异',
    SINGLE_VARIANT_REVIEW: '单变异核查',
    UNKNOWN: '来源待核查',
};
const DEFAULT_VARIANT_PAGE_SIZE = 50;
const MAX_CSV_EXPORT_ROWS = 5000;
const MAX_JSON_EXPORT_ROWS = 50;
const VARIANT_PAGE_SIZE_OPTIONS = [25, 50, 100];
const DETAIL_TAB_OVERVIEW = 'OVERVIEW';
const DETAIL_TAB_EVIDENCE = 'EVIDENCE';
const DETAIL_TAB_SCORE = 'SCORE';
const DETAIL_TAB_RANKING_EXPLANATION = 'RANKING_EXPLANATION';
const DETAIL_TAB_TRANSCRIPT = 'TRANSCRIPT';
const DETAIL_TAB_ACMG = 'ACMG';
const DETAIL_TAB_OPTIONS = [
    { value: DETAIL_TAB_OVERVIEW, label: 'Overview' },
    { value: DETAIL_TAB_EVIDENCE, label: 'Evidence' },
    { value: DETAIL_TAB_SCORE, label: 'Score' },
    { value: DETAIL_TAB_RANKING_EXPLANATION, label: 'Ranking explanation' },
    { value: DETAIL_TAB_TRANSCRIPT, label: 'Transcript' },
    { value: DETAIL_TAB_ACMG, label: 'ACMG-ready' },
];
const VARIANT_VIEW_CANDIDATES = 'CANDIDATES';
const VARIANT_VIEW_FREQUENCY_FILTERED = 'FREQUENCY_FILTERED';
const VARIANT_VIEW_ALL = 'ALL';
const VARIANT_FILTER_ALL = 'ALL';
const VARIANT_FILTER_PHENOTYPE_SUPPORTED = 'PHENOTYPE_SUPPORTED';
const PHENOTYPE_SCORE_COMPONENT = 'phenotype_support';
const PHENOTYPE_EVIDENCE_TYPE = 'PHENOTYPE_GENE_PRIOR';
const TRIO_EVIDENCE_SOURCE = 'TRIO';
const TRIO_SCORE_COMPONENT = 'trio_segregation_support';
const RANKING_EXPLANATION_SCHEMA_V1 = 'candidate-ranking-explanation-v1';
const RANKING_EXPLANATION_SCHEMA_V2 = 'candidate-ranking-explanation-v2';
const RANKING_EXPLANATION_SCHEMAS = new Set([
    RANKING_EXPLANATION_SCHEMA_V1,
    RANKING_EXPLANATION_SCHEMA_V2,
]);
const RANKING_EXPLANATION_CACHE_LIMIT = 100;
const RANKING_EXPLANATION_COMPLETENESS = new Set([
    'COMPLETE',
    'LEGACY_PARTIAL',
    'SUMMARY_ONLY',
    'UNAVAILABLE',
]);
const RANKING_EXPLANATION_DIRECTIONS = new Set(['POSITIVE', 'NEGATIVE', 'NEUTRAL']);
const RANKING_EXPLANATION_AVAILABILITY = new Set([
    'EVIDENCE_REPORTED_AVAILABLE',
    'EVIDENCE_REPORTED_CONFLICTING',
    'NO_SOURCE_RECORD_REPORTED',
    'EVIDENCE_NOT_APPLICABLE',
    'EVIDENCE_UNAVAILABLE',
    'EVIDENCE_AVAILABILITY_UNPROVEN',
]);
const RANKING_EXPLANATION_WARNINGS = new Set([
    'One or more persisted scores were non-finite and were omitted from this explanation.',
    'Only a compact candidate summary is available; detailed evidence may be incomplete.',
    'This older ranking contains only a partial persisted explanation.',
    'No persisted ranking breakdown is available; no score was reconstructed.',
]);
const ANALYSIS_VCF_CALL_SCHEMA = 'analysis-vcf-call-v1';
const ANALYSIS_VCF_CALL_MATCH_STATUSES = new Set(['MATCHED', 'NOT_FOUND', 'AMBIGUOUS']);
const ANALYSIS_VCF_CALL_TARGET_ALLELE_CALLS = new Set([
    'HOM_REF',
    'HET_TARGET',
    'HOM_TARGET',
    'HEMI_REF',
    'HEMI_TARGET',
    'TARGET_WITH_OTHER_ALT',
    'OTHER_ALT_ONLY',
    'PARTIAL_TARGET',
    'NO_CALL',
    'UNSUPPORTED',
]);
const ANALYSIS_VCF_CALL_FILTER_EVALUATIONS = new Set(['PASS', 'FILTERED', 'UNASSESSED']);
const ANALYSIS_VCF_CALL_PHASING_STATUSES = new Set([
    'PHASE_SET',
    'RECORD_ONLY',
    'UNPHASED',
    'UNKNOWN',
]);
const ANALYSIS_VCF_CALL_ALLOWED_KEYS = new Set([
    'schemaVersion',
    'matchStatus',
    'targetAlleleCall',
    'candidateRelativeGenotype',
    'targetAlleleIndex',
    'depth',
    'genotypeQuality',
    'referenceDepth',
    'targetAlleleDepth',
    'alleleBalance',
    'filterEvaluation',
    'phasingStatus',
    'phaseSetToken',
    'matchingRecordCount',
    'matchedRecordOrdinal',
    'assembly',
    'artifactRole',
    'provenance',
    'missingFields',
    'invalidFields',
]);
const ANALYSIS_VCF_CALL_FIELD_CODES = new Set([
    'GT',
    'DP',
    'GQ',
    'AD',
    'AB',
    'PS',
    'AD:REF',
    'AD:TARGET',
    'PS:UNPHASED',
]);
const TRIO_INPUT_MODE_DISABLED = 'DISABLED';
const TRIO_INPUT_MODE_JOINT = 'JOINT_VCF';
const ACMG_CODE_ALL = 'ALL';
const BOOLEAN_FILTER_ALL = 'ALL';
const BOOLEAN_FILTER_TRUE = 'TRUE';
const BOOLEAN_FILTER_FALSE = 'FALSE';
const ACMG_CODE_OPTIONS = [
    ACMG_CODE_ALL,
    'BA1',
    'BS1',
    'PM2_candidate',
    'PVS1_candidate',
    'PP3_candidate',
    'BP4_candidate',
    'BP7_candidate',
];
const VARIANT_FILTER_KNOWN_CLINICAL = 'TIER_KNOWN_CLINICAL';
const VARIANT_FILTER_HIGH_PRIORITY = 'TIER_HIGH_PRIORITY';
const VARIANT_FILTER_REVIEW = 'TIER_REVIEW';
const VARIANT_FILTER_HIDE_COMMON = 'HIDE_FILTERED_COMMON';
const VARIANT_FILTER_HIDE_QC = 'HIDE_FILTERED_QC';
const VARIANT_FILTER_CLINVAR_PLP = 'CLINVAR_PLP';
const VARIANT_FILTER_CLINVAR_VUS = 'CLINVAR_VUS';
const VARIANT_FILTER_OPTIONS = [
    { value: VARIANT_FILTER_ALL, label: 'All returned by server' },
    { value: VARIANT_FILTER_KNOWN_CLINICAL, label: 'Tier: known clinical' },
    { value: VARIANT_FILTER_HIGH_PRIORITY, label: 'Tier: high priority' },
    { value: VARIANT_FILTER_REVIEW, label: 'Tier: review' },
    { value: VARIANT_FILTER_HIDE_COMMON, label: 'Hide common frequency' },
    { value: VARIANT_FILTER_HIDE_QC, label: 'Hide QC filtered' },
    { value: VARIANT_FILTER_CLINVAR_PLP, label: 'ClinVar P/LP' },
    { value: VARIANT_FILTER_CLINVAR_VUS, label: 'ClinVar VUS' },
    { value: VARIANT_FILTER_PHENOTYPE_SUPPORTED, label: 'Phenotype-supported' },
];
const VARIANT_BUCKET_ALL = 'ALL';
const VARIANT_BUCKET_OPTIONS = [
    { value: VARIANT_BUCKET_ALL, label: 'All buckets', compactLabel: 'All' },
    { value: 'CANDIDATE', label: 'Candidate', compactLabel: 'Candidate' },
    { value: 'KNOWN_CLINICAL', label: 'Known clinical', compactLabel: 'Known' },
    { value: 'HIGH_PRIORITY', label: 'High priority', compactLabel: 'High' },
    { value: 'REVIEW', label: 'Review', compactLabel: 'Review' },
    { value: 'FILTERED_QC', label: 'Filtered QC', compactLabel: 'QC' },
    { value: 'FILTERED_COMMON', label: 'Filtered common', compactLabel: 'Common' },
    { value: 'FILTERED_NOT_CARRIED', label: 'Not carried', compactLabel: 'Not carried' },
    {
        value: 'DEFERRED_LOW_PRIORITY',
        label: 'Deferred low priority',
        compactLabel: 'Deferred low',
    },
    {
        value: 'DEFERRED_NO_EXPENSIVE_ANNOTATION',
        label: 'Deferred no expensive annotation',
        compactLabel: 'Deferred skip',
    },
];
const EMPTY_PAGE = {
    variants: [],
    total: 0,
    page: 1,
    pageSize: DEFAULT_VARIANT_PAGE_SIZE,
    view: VARIANT_VIEW_CANDIDATES,
    candidateTotal: 0,
    frequencyFilteredTotal: 0,
    allTotal: 0,
    populationFrequencyThreshold: 0.01,
};
const ALPHAGENOME_CONSEQUENCE_TOKENS = [
    'splice',
    'intron',
    'intergenic',
    'upstream',
    'downstream',
    'utr',
    'regulatory',
    'non_coding',
];
const PRE_RANKING_EVENT_PREFIX = 'Pre-ranking transcript annotation ';
const NORMALIZATION_EVENT_PREFIX = 'Reference normalization ';
const MANUAL_HPO_SOURCE = 'MANUAL_CONFIRMED';

const isRequestTimeout = (error) => {
    const message = String(error?.message || '').toLowerCase();
    return error?.code === 'ECONNABORTED' || message.includes('timeout');
};

const isNotFoundError = (error) => Number(error?.response?.status) === 404;

const isCanceledRequest = (error) =>
    error?.code === 'ERR_CANCELED' ||
    error?.name === 'CanceledError' ||
    error?.name === 'AbortError';

const candidateKey = (variant) => String(variant?.candidateId || variant?.variantId || '');

const canonicalKey = (variant) => String(variant?.canonicalVariantId || variant?.variantId || '');

const rankingExplanationCacheKey = (analysisId, candidateId) =>
    JSON.stringify([String(analysisId || ''), String(candidateId || '')]);

const isSafeRankingToken = (value) =>
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 80 &&
    /^[A-Za-z0-9_.-]+$/.test(value);

const finiteScoreOrNull = (value) => {
    if (value == null) return null;
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
};

const nullableInteger = (value, minimum = 0) => {
    if (value == null) return null;
    return Number.isInteger(value) && value >= minimum ? value : undefined;
};

const nullableFiniteNumber = (value, minimum, maximum) => {
    if (value == null) return null;
    if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
    if (minimum != null && value < minimum) return undefined;
    if (maximum != null && value > maximum) return undefined;
    return value;
};

const nullableSafeCallToken = (value, maximumLength = 80) => {
    if (value == null) return null;
    return typeof value === 'string' &&
        value.length > 0 &&
        value.length <= maximumLength &&
        /^[A-Za-z0-9_.:#-]+$/.test(value)
        ? value
        : undefined;
};

const sanitizeCallFieldList = (values) => {
    if (!Array.isArray(values) || values.length > 32) return undefined;
    const safe = values.map((value) => nullableSafeCallToken(value));
    return safe.some((value) => value == null || !ANALYSIS_VCF_CALL_FIELD_CODES.has(value)) ||
        new Set(safe).size !== safe.length
        ? undefined
        : safe;
};

const inferAnalysisVcfTargetAlleleCall = (candidateRelativeGenotype) => {
    const separator = candidateRelativeGenotype.includes('|') ? '|' : '/';
    const tokens = candidateRelativeGenotype.split(separator);
    const counts = tokens.reduce(
        (result, token) => ({ ...result, [token]: (result[token] || 0) + 1 }),
        {},
    );
    const reference = counts.REF || 0;
    const target = counts.TARGET_ALT || 0;
    const other = counts.OTHER_ALT || 0;
    const missing = counts['.'] || 0;
    if (missing === tokens.length) return 'NO_CALL';
    if (target > 0 && missing > 0) return 'PARTIAL_TARGET';
    if (target > 0 && other > 0) return 'TARGET_WITH_OTHER_ALT';
    if (tokens.length === 1 && target === 1) return 'HEMI_TARGET';
    if (tokens.length === 1 && reference === 1) return 'HEMI_REF';
    if (tokens.length > 1 && target === tokens.length) return 'HOM_TARGET';
    if (tokens.length > 1 && reference === tokens.length) return 'HOM_REF';
    if (target > 0 && reference > 0 && other === 0 && missing === 0) return 'HET_TARGET';
    if (target === 0 && other > 0 && missing === 0) return 'OTHER_ALT_ONLY';
    return 'UNSUPPORTED';
};

const analysisVcfPhasingIsConsistent = ({
    targetAlleleCall,
    candidateRelativeGenotype,
    phasingStatus,
    phaseSetToken,
}) => {
    if (targetAlleleCall === 'NO_CALL' || targetAlleleCall === 'UNSUPPORTED') {
        return phasingStatus === 'UNKNOWN' && phaseSetToken == null;
    }
    if (phasingStatus === 'UNKNOWN') return false;
    const phased = candidateRelativeGenotype.includes('|');
    if (phasingStatus === 'PHASE_SET') {
        return phased && phaseSetToken != null;
    }
    if (phaseSetToken != null) return false;
    return phasingStatus === 'RECORD_ONLY' ? phased : phasingStatus === 'UNPHASED' && !phased;
};

const sanitizeAnalysisVcfCallContext = (value, expectedAssembly) => {
    if (value == null) return null;
    if (
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).some((key) => !ANALYSIS_VCF_CALL_ALLOWED_KEYS.has(key)) ||
        value.schemaVersion !== ANALYSIS_VCF_CALL_SCHEMA ||
        !ANALYSIS_VCF_CALL_MATCH_STATUSES.has(value.matchStatus) ||
        value.artifactRole !== 'EXOMISER_ANALYSIS_VCF' ||
        value.provenance !== 'ANALYSIS_VCF_EXACT_ALLELE' ||
        !['GRCh37', 'GRCh38'].includes(expectedAssembly) ||
        value.assembly !== expectedAssembly
    ) {
        return undefined;
    }
    const targetAlleleCall = value.targetAlleleCall == null ? null : value.targetAlleleCall;
    const candidateRelativeGenotype =
        value.candidateRelativeGenotype == null
            ? null
            : typeof value.candidateRelativeGenotype === 'string' &&
                !(
                    value.candidateRelativeGenotype.includes('|') &&
                    value.candidateRelativeGenotype.includes('/')
                ) &&
                /^(?:REF|TARGET_ALT|OTHER_ALT|\.)(?:[|/](?:REF|TARGET_ALT|OTHER_ALT|\.))*$/.test(
                    value.candidateRelativeGenotype,
                )
              ? value.candidateRelativeGenotype
              : undefined;
    const targetAlleleIndex = nullableInteger(value.targetAlleleIndex, 1);
    const depth = nullableInteger(value.depth);
    const genotypeQuality = nullableInteger(value.genotypeQuality);
    const referenceDepth = nullableInteger(value.referenceDepth);
    const targetAlleleDepth = nullableInteger(value.targetAlleleDepth);
    const alleleBalance = nullableFiniteNumber(value.alleleBalance, 0, 1);
    const filterEvaluation = value.filterEvaluation == null ? null : value.filterEvaluation;
    const phasingStatus = value.phasingStatus == null ? null : value.phasingStatus;
    const phaseSetToken =
        value.phaseSetToken == null
            ? null
            : typeof value.phaseSetToken === 'string' && /^[0-9a-f]{24}$/.test(value.phaseSetToken)
              ? value.phaseSetToken
              : undefined;
    const matchingRecordCount = nullableInteger(value.matchingRecordCount);
    const matchedRecordOrdinal = nullableInteger(value.matchedRecordOrdinal, 1);
    const missingFields = sanitizeCallFieldList(value.missingFields);
    const invalidFields = sanitizeCallFieldList(value.invalidFields);
    if (
        (targetAlleleCall != null &&
            !ANALYSIS_VCF_CALL_TARGET_ALLELE_CALLS.has(targetAlleleCall)) ||
        candidateRelativeGenotype === undefined ||
        targetAlleleIndex === undefined ||
        depth === undefined ||
        genotypeQuality === undefined ||
        referenceDepth === undefined ||
        targetAlleleDepth === undefined ||
        alleleBalance === undefined ||
        (filterEvaluation != null && !ANALYSIS_VCF_CALL_FILTER_EVALUATIONS.has(filterEvaluation)) ||
        (phasingStatus != null && !ANALYSIS_VCF_CALL_PHASING_STATUSES.has(phasingStatus)) ||
        phaseSetToken === undefined ||
        matchingRecordCount === undefined ||
        matchedRecordOrdinal === undefined ||
        missingFields === undefined ||
        invalidFields === undefined
    ) {
        return undefined;
    }
    const matchedDetails = [
        targetAlleleCall,
        candidateRelativeGenotype,
        targetAlleleIndex,
        depth,
        genotypeQuality,
        referenceDepth,
        targetAlleleDepth,
        alleleBalance,
        filterEvaluation,
        phasingStatus,
        phaseSetToken,
        matchedRecordOrdinal,
    ];
    if (value.matchStatus === 'MATCHED') {
        if (
            matchingRecordCount !== 1 ||
            targetAlleleCall == null ||
            candidateRelativeGenotype == null ||
            targetAlleleIndex == null ||
            filterEvaluation == null ||
            phasingStatus == null ||
            matchedRecordOrdinal == null ||
            inferAnalysisVcfTargetAlleleCall(candidateRelativeGenotype) !== targetAlleleCall ||
            !analysisVcfPhasingIsConsistent({
                targetAlleleCall,
                candidateRelativeGenotype,
                phasingStatus,
                phaseSetToken,
            })
        ) {
            return undefined;
        }
        if (alleleBalance != null) {
            const denominator = referenceDepth + targetAlleleDepth;
            if (
                referenceDepth == null ||
                targetAlleleDepth == null ||
                denominator <= 0 ||
                Math.abs(targetAlleleDepth / denominator - alleleBalance) > 1e-12
            ) {
                return undefined;
            }
        }
    } else {
        const expectedMatchCount = value.matchStatus === 'NOT_FOUND' ? 0 : 2;
        if (
            matchingRecordCount == null ||
            (value.matchStatus === 'NOT_FOUND'
                ? matchingRecordCount !== expectedMatchCount
                : matchingRecordCount < expectedMatchCount) ||
            matchedDetails.some((field) => field != null) ||
            missingFields.length > 0 ||
            invalidFields.length > 0
        ) {
            return undefined;
        }
    }
    return {
        schemaVersion: value.schemaVersion,
        matchStatus: value.matchStatus,
        targetAlleleCall,
        candidateRelativeGenotype,
        targetAlleleIndex,
        depth,
        genotypeQuality,
        referenceDepth,
        targetAlleleDepth,
        alleleBalance,
        filterEvaluation,
        phasingStatus,
        phaseSetToken,
        matchingRecordCount,
        matchedRecordOrdinal,
        assembly: value.assembly,
        artifactRole: value.artifactRole,
        provenance: value.provenance,
        missingFields,
        invalidFields,
    };
};

const sanitizeRankingContributionList = (values, expectedDirection) => {
    if (!Array.isArray(values) || values.length > 128) return null;
    const result = [];
    for (const value of values) {
        if (
            !value ||
            typeof value !== 'object' ||
            !isSafeRankingToken(value.key) ||
            !RANKING_EXPLANATION_DIRECTIONS.has(value.direction) ||
            value.direction !== expectedDirection
        ) {
            return null;
        }
        const score = finiteScoreOrNull(value.score);
        if (score === undefined) return null;
        result.push({ key: value.key, score, direction: value.direction });
    }
    return result;
};

const sanitizeAllRankingContributions = (values) => {
    if (!Array.isArray(values) || values.length > 128) return null;
    const result = [];
    for (const value of values) {
        if (
            !value ||
            typeof value !== 'object' ||
            !isSafeRankingToken(value.key) ||
            !RANKING_EXPLANATION_DIRECTIONS.has(value.direction)
        ) {
            return null;
        }
        const score = finiteScoreOrNull(value.score);
        if (score === undefined) return null;
        result.push({ key: value.key, score, direction: value.direction });
    }
    return result;
};

const sameRankingContributions = (left, right) =>
    JSON.stringify(left || []) === JSON.stringify(right || []);

const sanitizeRankingEvidenceList = (values, allowedAvailability) => {
    if (!Array.isArray(values) || values.length > 256) return null;
    const result = [];
    for (const value of values) {
        if (
            !value ||
            typeof value !== 'object' ||
            (value.source != null && !isSafeRankingToken(value.source)) ||
            !RANKING_EXPLANATION_AVAILABILITY.has(value.availabilityMeaning) ||
            !allowedAvailability.has(value.availabilityMeaning)
        ) {
            return null;
        }
        result.push({
            source: value.source || null,
            availabilityMeaning: value.availabilityMeaning,
        });
    }
    return result;
};

const sanitizeRankingExplanation = (payload, expectedAnalysisId, expectedCandidate) => {
    const expectedCandidateId = candidateKey(expectedCandidate);
    const expectedCanonicalVariantId = canonicalKey(expectedCandidate);
    const expectedAssembly = expectedCanonicalVariantId.match(/^(GRCh37|GRCh38)[|:]/)?.[1];
    const schemaVersion = payload?.schemaVersion;
    const v2IdentityIsValid =
        schemaVersion === RANKING_EXPLANATION_SCHEMA_V2 &&
        String(payload?.candidateId || '') === expectedCandidateId &&
        String(payload?.canonicalVariantId || '') === expectedCanonicalVariantId;
    const v1IdentityIsValid =
        schemaVersion === RANKING_EXPLANATION_SCHEMA_V1 &&
        expectedCandidateId === expectedCanonicalVariantId &&
        String(payload?.variantId || '') === expectedCanonicalVariantId;
    const analysisVcfCallContext =
        schemaVersion === RANKING_EXPLANATION_SCHEMA_V2
            ? sanitizeAnalysisVcfCallContext(payload?.analysisVcfCallContext, expectedAssembly)
            : payload?.analysisVcfCallContext == null
              ? null
              : undefined;
    if (
        !payload ||
        typeof payload !== 'object' ||
        !RANKING_EXPLANATION_SCHEMAS.has(schemaVersion) ||
        payload.analysisId !== String(expectedAnalysisId || '') ||
        !expectedCandidateId ||
        !(v2IdentityIsValid || v1IdentityIsValid) ||
        analysisVcfCallContext === undefined ||
        !RANKING_EXPLANATION_COMPLETENESS.has(payload.completeness) ||
        payload.provenance?.explanationMode !== 'DERIVED_FROM_PERSISTED_RANKING_OUTPUT' ||
        payload.provenance?.rescored !== false
    ) {
        return null;
    }
    const rankingScore = finiteScoreOrNull(payload.rankingScore);
    if (rankingScore === undefined) return null;
    const contributions = sanitizeAllRankingContributions(payload.contributions);
    const positiveContributions = sanitizeRankingContributionList(
        payload.positiveContributions,
        'POSITIVE',
    );
    const negativeContributions = sanitizeRankingContributionList(
        payload.negativeContributions,
        'NEGATIVE',
    );
    const neutralContributions = sanitizeRankingContributionList(
        payload.neutralContributions,
        'NEUTRAL',
    );
    const availableEvidence = sanitizeRankingEvidenceList(
        payload.availableEvidence,
        new Set(['EVIDENCE_REPORTED_AVAILABLE']),
    );
    const conflictingEvidence = sanitizeRankingEvidenceList(
        payload.conflictingEvidence,
        new Set(['EVIDENCE_REPORTED_CONFLICTING']),
    );
    const missingEvidence = sanitizeRankingEvidenceList(
        payload.missingEvidence,
        new Set([
            'NO_SOURCE_RECORD_REPORTED',
            'EVIDENCE_NOT_APPLICABLE',
            'EVIDENCE_UNAVAILABLE',
            'EVIDENCE_AVAILABILITY_UNPROVEN',
        ]),
    );
    if (
        !positiveContributions ||
        !negativeContributions ||
        !neutralContributions ||
        !contributions ||
        !sameRankingContributions(
            contributions.filter((value) => value.direction === 'POSITIVE'),
            positiveContributions,
        ) ||
        !sameRankingContributions(
            contributions.filter((value) => value.direction === 'NEGATIVE'),
            negativeContributions,
        ) ||
        !sameRankingContributions(
            contributions.filter((value) => value.direction === 'NEUTRAL'),
            neutralContributions,
        ) ||
        !availableEvidence ||
        !conflictingEvidence ||
        !missingEvidence ||
        !Array.isArray(payload.warnings) ||
        payload.warnings.length > 4 ||
        payload.warnings.some((warning) => !RANKING_EXPLANATION_WARNINGS.has(warning))
    ) {
        return null;
    }
    return {
        schemaVersion,
        candidateId: expectedCandidateId,
        canonicalVariantId: expectedCanonicalVariantId,
        completeness: payload.completeness,
        rankingScore,
        positiveContributions,
        negativeContributions,
        neutralContributions,
        availableEvidence,
        conflictingEvidence,
        missingEvidence,
        warnings: [...payload.warnings],
        analysisVcfCallContext,
    };
};

const pollingTimeoutNotice =
    'Status refresh timed out once. The backend analysis can still be running; retrying automatically.';
const analysisRemovedNotice = 'Analysis removed from this view.';
const analysisRemovePendingNotice =
    'Analysis removed from this view. Backend cleanup may still be finishing, so stale status refreshes are ignored.';
const jobNotFoundNotice =
    'The current analysis job is no longer available on the backend. Re-upload the VCF or open a saved ready result.';

const extractSampleIdChoices = (message) => {
    const match = /Available sampleId values:\s*([^\r\n]+)/i.exec(String(message || ''));
    if (!match?.[1]) return [];
    return match[1]
        .split(',')
        .map((value) => value.trim())
        .filter((value) => value && !value.startsWith('...') && !/\(\d+\s+total\)/i.test(value));
};

const mergeSampleIdChoices = (...groups) => {
    const seen = new Set();
    const result = [];
    groups.flat().forEach((value) => {
        const normalized = String(value || '').trim();
        if (!normalized || seen.has(normalized)) return;
        seen.add(normalized);
        result.push(normalized);
    });
    return result;
};

const sampleDisplayLabel = (value) => {
    const normalized = String(value || '').trim();
    if (!normalized) return '-';
    const pathSegments = normalized.split(/[\\/]+/).filter(Boolean);
    const basename = pathSegments[pathSegments.length - 1] || normalized;
    return basename.length > 72 ? `${basename.slice(0, 69)}...` : basename;
};

const sampleDetectionMessage = (result, fallback = '') => {
    if (!result) return fallback;
    if (result.errorCode) {
        return `${result.errorCode}: ${result.errorMessage || 'VCF header read failed.'}`;
    }
    const warnings = Array.isArray(result.warnings) ? result.warnings.filter(Boolean) : [];
    if (warnings.length) return warnings.join(' ');
    const samples = Array.isArray(result.samples) ? result.samples : [];
    if (!samples.length) return 'No sample columns were detected in the VCF header.';
    const assembly = result.assemblyHint
        ? `Detected ${result.assemblyHint} from ${String(result.assemblyConfidence || 'VCF header')
              .toLowerCase()
              .replaceAll('_', ' ')}.`
        : 'Input build requires confirmation.';
    const indexPlan =
        result.indexPreparation === 'PRIVATE_BGZF_TABIX'
            ? ' A private job-local index will be prepared.'
            : '';
    return `${assembly} ${samples.length} sample${samples.length === 1 ? '' : 's'} detected from the VCF header.${indexPlan}`;
};

const sampleDetectionSeverity = (result) => {
    if (!result || result.errorCode) return 'warning';
    if (Array.isArray(result.warnings) && result.warnings.length) return 'warning';
    return 'info';
};

const hasDuplicateRoleMapping = (...values) => {
    const normalized = values.map((value) => String(value || '').trim()).filter(Boolean);
    return normalized.length > new Set(normalized).size;
};

const formatStatus = (value) => String(value || 'NOT_SUBMITTED').replaceAll('_', ' ');

const statusProgress = (status) => {
    const values = {
        UPLOADED: 8,
        VALIDATING: 18,
        NORMALIZING: 38,
        QC_EXTRACTING: 55,
        BASE_ANNOTATING: 72,
        RANKING: 90,
        READY: 100,
        FAILED: 100,
    };
    return values[status] || 0;
};

const formatEventTime = (value) => {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

const formatNumber = (value, fallback = '-') => {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric.toLocaleString() : fallback;
};

const formatScore = (value) => {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '-';
    return Number.isInteger(numeric) ? String(numeric) : numeric.toFixed(3);
};

const formatContribution = (value) => {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '-';
    const sign = numeric > 0 ? '+' : '';
    return `${sign}${formatScore(numeric)}`;
};

const formatBreakdownLabel = (value) =>
    String(value || '')
        .replaceAll('_', ' ')
        .replace(/\b\w/g, (letter) => letter.toUpperCase());

const formatPercent = (value) => {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return '-';
    const percent = numeric * 100;
    const digits = percent < 1 ? 2 : 1;
    return `${percent.toFixed(digits)}%`;
};

const formatDuration = (millis) => {
    const numeric = Number(millis);
    if (!Number.isFinite(numeric)) return '-';
    if (numeric < 1000) return `${numeric} ms`;
    if (numeric < 60000) return `${(numeric / 1000).toFixed(1)} s`;
    return `${(numeric / 60000).toFixed(1)} min`;
};

const formatMetricValue = (value, fallback = '-') => {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return value == null || value === '' ? fallback : String(value);
    if (numeric === 0) return '0';
    if (Math.abs(numeric) < 0.001) return numeric.toExponential(2);
    if (Math.abs(numeric) < 1) {
        return numeric.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
    }
    return numeric.toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
};

const displayValue = (value, fallback = '-') =>
    value == null || value === '' ? fallback : String(value);

const parseHpoTermsInput = (value) =>
    String(value || '')
        .split(/[,\s]+/)
        .map((term) => term.trim())
        .filter(Boolean);

const normalizeHpoId = (value) => {
    const normalized = String(value || '')
        .trim()
        .toUpperCase();
    return /^HP:\d{7}$/.test(normalized) ? normalized : '';
};

const hpoProvenanceForTerms = (terms) =>
    (Array.isArray(terms) ? terms : [])
        .map((term) => normalizeHpoId(term))
        .filter(Boolean)
        .map((hpoId) => ({
            hpoId,
            label: hpoId,
            source: MANUAL_HPO_SOURCE,
            sourceLabel: 'Manual HPO input',
            matchedTerm: 'Manual HPO input',
            confidence: 1,
        }));

const sourceLabel = (source) => {
    const normalized = String(source || '').toUpperCase();
    if (normalized.includes('AI')) return 'Legacy unvalidated local rule match';
    if (normalized.includes('CLINICAL')) return 'Clinical evidence';
    if (normalized.includes('TABLE')) return 'Table keyword';
    if (normalized.includes('MANUAL')) return 'Manual input';
    return source || 'HPO input';
};

const isManualHpoSource = (source) =>
    String(source || '')
        .toUpperCase()
        .includes('MANUAL');

const geneSymbolCandidateFromText = (value) => {
    const token = String(value || '').trim();
    if (!token || token.length > 24) return '';
    if (!/^[A-Za-z][A-Za-z0-9-]{1,23}$/.test(token)) return '';
    if (/^HP:\d{7}$/i.test(token)) return '';
    if (/^chr/i.test(token)) return '';
    return token.toUpperCase();
};

const asTextList = (value) => {
    if (Array.isArray(value)) {
        return value.map((item) => String(item || '').trim()).filter(Boolean);
    }
    if (value == null || value === '') return [];
    return [String(value).trim()].filter(Boolean);
};

const listDisplay = (value) => asTextList(value).join(', ');

const hasPhenotypeSummaryInput = (summary) =>
    Boolean(summary?.enabled) ||
    Number(summary?.inputHpoCount || 0) > 0 ||
    Number(summary?.excludedHpoCount || 0) > 0 ||
    asTextList(summary?.validHpoTerms).length > 0 ||
    asTextList(summary?.invalidHpoTerms).length > 0 ||
    asTextList(summary?.excludedHpoTerms).length > 0;

const evidenceApplicabilityRank = (evidence) => {
    switch (String(evidence?.applicability || '').toUpperCase()) {
        case 'APPLICABLE':
            return 5;
        case 'CONFLICTING':
            return 4;
        case 'NO_RECORD':
            return 3;
        case 'NOT_APPLICABLE':
            return 2;
        case 'ASSEMBLY_UNSUPPORTED':
        case 'UNAVAILABLE':
        case 'PARSE_ERROR':
        case 'TIMEOUT':
            return 1;
        default:
            return 0;
    }
};

const evidenceFor = (variant, source) =>
    (variant?.evidence || []).reduce((best, item) => {
        if (item?.source !== source) return best;
        return !best || evidenceApplicabilityRank(item) > evidenceApplicabilityRank(best)
            ? item
            : best;
    }, null);

const hasApplicableClinvarEvidence = (variant) =>
    evidenceFor(variant, 'CLINVAR')?.applicability === 'APPLICABLE';

const isSupersededClinvarUnavailableEvidence = (variant, evidence) =>
    evidence?.source === 'CLINVAR' &&
    evidence?.applicability === 'UNAVAILABLE' &&
    hasApplicableClinvarEvidence(variant);

const isSupersededClinvarUnavailableWarning = (variant, warning) =>
    hasApplicableClinvarEvidence(variant) &&
    /clinvar.*(?:unavailable|not available)/i.test(String(warning || ''));

const phenotypeEvidenceFor = (variant) =>
    (variant?.evidence || []).find(
        (item) =>
            item?.evidenceType === PHENOTYPE_EVIDENCE_TYPE ||
            item?.details?.evidenceType === PHENOTYPE_EVIDENCE_TYPE,
    ) || null;

const phenotypeScoreComponent = (variant) =>
    variant?.scoreComponents?.[PHENOTYPE_SCORE_COMPONENT] || null;

const phenotypeSupportInfo = (variant) => {
    if (!variant) return null;
    const evidence = phenotypeEvidenceFor(variant);
    const component = phenotypeScoreComponent(variant);
    const fallbackScore = variant?.scoreBreakdown?.[PHENOTYPE_SCORE_COMPONENT];
    const hasReason = [...(variant?.positiveReasons || []), ...(variant?.rationale || [])].some(
        (reason) => /phenotype|hpo/i.test(String(reason || '')),
    );
    if (!evidence && !component && fallbackScore == null && !hasReason) return null;
    const details = evidence?.details || {};
    const geneSymbols = Array.from(
        new Set((variant?.consequences || []).map((item) => item?.geneSymbol).filter(Boolean)),
    );
    return {
        evidence,
        component,
        geneSymbol: details.geneSymbol || geneSymbols.slice(0, 3).join(', ') || '-',
        phenotypeScore: details.phenotypeScore ?? evidence?.rawScore ?? null,
        phenotypeRank: details.phenotypeRank ?? null,
        matchedHpoTerms: asTextList(details.matchedHpoTerms),
        evidenceText: listDisplay(details.evidence),
        source: evidence?.source || details.source || 'Gene phenotype prior',
        sourceVersion: evidence?.sourceVersion || details.sourceVersion || '',
        scoreBoost: details.scoreBoost ?? component?.score ?? fallbackScore ?? null,
    };
};

const trioEvidenceFor = (variant) =>
    (variant?.evidence || []).find(
        (item) =>
            item?.source === TRIO_EVIDENCE_SOURCE ||
            item?.evidenceType === 'SEGREGATION' ||
            item?.details?.rankingEffect === 'EVIDENCE_ONLY_NO_RANKING_WEIGHT_CHANGE',
    ) || null;

const trioEvidenceInfo = (variant) => {
    const evidence = trioEvidenceFor(variant);
    if (!evidence) return null;
    const details = evidence.details || {};
    const contribution = details.trioRankingContribution || null;
    const contributionScore = contribution?.contributionScore ?? contribution?.score ?? null;
    return {
        evidence,
        model: details.model || evidence.label,
        confidence: details.confidence,
        parentalOrigin: details.parentalOrigin,
        probandGenotype: details.probandGenotype,
        fatherGenotype: details.fatherGenotype,
        motherGenotype: details.motherGenotype,
        warnings: asTextList(details.warnings),
        evidenceOnly: details.evidenceOnly !== false,
        rankingEffect: details.rankingEffect,
        hardGatePolicy: details.hardGatePolicy,
        rankingContribution: contribution,
        contributionEnabled: contribution?.enabled === true,
        contributionApplied: contribution?.applied === true,
        contributionDirection: contribution?.direction,
        contributionLevel: contribution?.level,
        contributionScore,
        maxContributionCap: contribution?.maxContributionCap,
        notAppliedReason: contribution?.notAppliedReason,
        hardGateProtected: contribution?.hardGateProtected === true,
        contributionExplanation: contribution?.explanation,
    };
};

const trioContributionScore = (contribution) =>
    contribution?.contributionScore ?? contribution?.score ?? null;

const trioContributionStateLabel = (contribution) => {
    if (!contribution) return 'Explanation only';
    if (contribution.applied === true) return 'Ranking support';
    if (contribution.enabled === false) return 'Explanation only';
    if (contribution.hardGateProtected === true) return 'Hard-gate protected';
    return 'Neutral';
};

const trioContributionSummary = (contribution) => {
    if (!contribution) {
        return 'Trio evidence is shown as explanation only.';
    }
    if (contribution.applied === true) {
        return [
            contribution.direction,
            contribution.level,
            formatContribution(trioContributionScore(contribution)),
        ]
            .filter(Boolean)
            .map((item, index) => (index < 2 ? formatStatus(item) : item))
            .join(' / ');
    }
    if (contribution.enabled === false) {
        return 'Trio weighted ranking is disabled. Trio evidence is shown as explanation only.';
    }
    if (contribution.hardGateProtected === true) {
        return 'Trio support did not override hard gates.';
    }
    return contribution.notAppliedReason
        ? `No ranking contribution: ${formatStatus(contribution.notAppliedReason)}`
        : 'No trio ranking contribution was applied.';
};

const hasApplicableEvidence = (variant, source) =>
    evidenceFor(variant, source)?.applicability === 'APPLICABLE';

const rawVariantTier = (variant) => variant?.tier || variant?.priorityTier || 'UNRESOLVED';

const variantTier = (variant) => {
    const tier = rawVariantTier(variant);
    // Compatibility for results persisted before engine provenance was separated from product tiers.
    return String(tier || '').toUpperCase() === 'EXOMISER' ? 'REVIEW' : tier;
};

const variantScore = (variant) =>
    variant?.totalScore ?? variant?.rankingScore ?? variant?.rankScore ?? null;

const topReason = (variant) => {
    if (String(rawVariantTier(variant)).toUpperCase() === 'EXOMISER') {
        return 'Candidate is ranked for review; inspect the retained evidence before clinical interpretation.';
    }
    return (
        variant?.tierReason ||
        variant?.positiveReasons?.[0] ||
        variant?.negativeReasons?.[0] ||
        variant?.filterReasons?.[0] ||
        variant?.rationale?.[0] ||
        ''
    );
};

const evidenceDetails = (evidence) =>
    evidence?.details && typeof evidence.details === 'object' ? evidence.details : {};

const evidenceProvenance = (evidence) =>
    evidence?.provenance && typeof evidence.provenance === 'object' ? evidence.provenance : {};

const firstEvidenceValue = (...values) =>
    values.find((value) => value != null && String(value).trim() !== '') || null;

const evidenceSourceLabel = (evidence) => {
    const source = firstEvidenceValue(
        evidenceDetails(evidence).source,
        evidenceProvenance(evidence).recordSource,
        evidence?.source,
        evidence?.sourceVersion,
    );
    return String(source || '').toUpperCase() === 'EXOMISER' ? 'Ranking engine' : source || '-';
};

const evidenceStatusValue = (evidence) =>
    firstEvidenceValue(
        evidenceDetails(evidence).status,
        evidence?.status,
        evidence?.applicability,
    ) || 'UNKNOWN';

const isProjectionFailureStatus = (status) => /^LIFTOVER_/.test(String(status || '').toUpperCase());

const evidenceAssemblyPresentation = (evidence) => {
    const details = evidenceDetails(evidence);
    const provenance = evidenceProvenance(evidence);
    const inputAssembly = firstEvidenceValue(details.inputAssembly, details.requestedAssembly);
    const evidenceAssembly = firstEvidenceValue(
        details.evidenceAssembly,
        details.queryAssembly,
        evidence?.assembly,
        provenance.evidenceAssembly,
        details.configuredResourceAssembly,
    );
    const projectionStatus = firstEvidenceValue(details.projectionStatus, details.status);
    const coordinateProjection = String(details.coordinateProjection || '').toUpperCase();
    const projected =
        coordinateProjection.includes('_TO_') ||
        Boolean(details.projectionMethod) ||
        projectionStatus === 'VERIFIED' ||
        isProjectionFailureStatus(projectionStatus);
    if (projected) {
        return {
            kind: projectionStatus === 'VERIFIED' ? 'VERIFIED_PROJECTED' : 'PROJECTION_FAILURE',
            inputAssembly,
            evidenceAssembly,
            projectionStatus: projectionStatus || 'UNKNOWN',
            coordinateProjection: details.coordinateProjection || null,
            projectionMethod: details.projectionMethod || null,
            projectionMethodVersion: details.projectionMethodVersion || null,
            sourceVariant: details.sourceVariant || null,
            projectedVariant: details.projectedVariant || null,
            referenceValidated: details.projectionReferenceValidated,
        };
    }
    if (evidenceAssembly) {
        return {
            kind: 'NATIVE',
            inputAssembly,
            evidenceAssembly,
            projectionStatus: null,
        };
    }
    return { kind: 'LEGACY', inputAssembly: null, evidenceAssembly: null, projectionStatus: null };
};

const evidenceAssemblyLabel = (presentation) => {
    if (presentation.kind === 'VERIFIED_PROJECTED') {
        return [presentation.inputAssembly, presentation.evidenceAssembly]
            .filter(Boolean)
            .join(' → ');
    }
    if (presentation.kind === 'PROJECTION_FAILURE') {
        return formatStatus(presentation.projectionStatus);
    }
    if (presentation.kind === 'NATIVE') return `${presentation.evidenceAssembly} native`;
    return null;
};

const evidenceRecordIdentity = (evidence) => {
    const details = evidenceDetails(evidence);
    return [
        ['Variation ID', details.variationId],
        ['Accession', details.accession],
        ['Allele ID', details.alleleId],
        ['RS ID', details.rsId],
        ['Source record', evidence?.sourceRecordId],
    ]
        .filter(([, value]) => value != null && String(value).trim() !== '')
        .map(([label, value]) => `${label} ${value}`)
        .join(' / ');
};

const evidenceConditionDisplay = (evidence) => {
    const details = evidenceDetails(evidence);
    const conditions = Array.isArray(details.conditions)
        ? details.conditions
              .map((condition) =>
                  firstEvidenceValue(
                      condition?.name,
                      condition?.conditionName,
                      condition?.diseaseName,
                      condition?.id,
                  ),
              )
              .filter(Boolean)
        : [];
    return firstEvidenceValue(conditions.join(', '), details.conditionNames, details.conditionName);
};

const evidenceProvenanceLines = (evidence) => {
    const details = evidenceDetails(evidence);
    const presentation = evidenceAssemblyPresentation(evidence);
    const lines = [
        ['Provider', evidenceSourceLabel(evidence)],
        ['Source version', firstEvidenceValue(evidence?.sourceVersion, details.sourceVersion)],
        ['Resource version', evidence?.resourceVersion],
        ['Evidence assembly', presentation.evidenceAssembly],
        ['Conditions', evidenceConditionDisplay(evidence)],
        ['Review status', details.reviewStatus],
        ['Conflict state', details.conflictState],
    ];
    if (presentation.kind === 'VERIFIED_PROJECTED' || presentation.kind === 'PROJECTION_FAILURE') {
        lines.push(
            ['Input assembly', presentation.inputAssembly],
            ['Original allele', presentation.sourceVariant],
            ['Projected allele', presentation.projectedVariant],
            [
                'Projection',
                [presentation.projectionMethod, presentation.projectionMethodVersion]
                    .filter(Boolean)
                    .join(' / '),
            ],
            ['Projection status', presentation.projectionStatus],
            [
                'REF validation',
                presentation.referenceValidated === true
                    ? 'Verified'
                    : presentation.referenceValidated === false
                      ? 'Failed'
                      : null,
            ],
        );
    }
    lines.push(['Source record', evidenceRecordIdentity(evidence)]);
    return lines.filter(([, value]) => value != null && String(value).trim() !== '');
};

const clinVarLabel = (variant) => evidenceFor(variant, 'CLINVAR')?.label || '';

const buildVariantQueryOptions = (
    filter,
    search,
    acmgCode = ACMG_CODE_ALL,
    acmgOnlySuggested = false,
    acmgManualReviewRequired = false,
    analysisBucket = VARIANT_BUCKET_ALL,
    summaryOnlyFilter = BOOLEAN_FILTER_ALL,
    largeFileModeFilter = BOOLEAN_FILTER_ALL,
    skippedAnnotationFilter = BOOLEAN_FILTER_ALL,
) => {
    const options = {};
    if (filter === VARIANT_FILTER_KNOWN_CLINICAL) options.tier = 'KNOWN_CLINICAL';
    if (filter === VARIANT_FILTER_HIGH_PRIORITY) options.tier = 'HIGH_PRIORITY';
    if (filter === VARIANT_FILTER_REVIEW) options.tier = 'REVIEW';
    if (filter === VARIANT_FILTER_HIDE_COMMON) options.hideFilteredCommon = true;
    if (filter === VARIANT_FILTER_HIDE_QC) options.hideFilteredQc = true;
    if (filter === VARIANT_FILTER_CLINVAR_PLP) options.clinvarClass = 'PLP';
    if (filter === VARIANT_FILTER_CLINVAR_VUS) options.clinvarClass = 'VUS';
    if (filter === VARIANT_FILTER_PHENOTYPE_SUPPORTED) options.hasPhenotypeSupport = true;
    if (acmgCode && acmgCode !== ACMG_CODE_ALL) options.acmgCode = acmgCode;
    if (acmgOnlySuggested) options.acmgStatus = 'SUGGESTED';
    if (acmgManualReviewRequired) options.manualReviewRequired = true;
    if (analysisBucket && analysisBucket !== VARIANT_BUCKET_ALL) {
        options.analysisBucket = analysisBucket;
    }
    if (summaryOnlyFilter === BOOLEAN_FILTER_TRUE) options.summaryOnly = true;
    if (summaryOnlyFilter === BOOLEAN_FILTER_FALSE) options.summaryOnly = false;
    if (largeFileModeFilter === BOOLEAN_FILTER_TRUE) options.largeFileMode = true;
    if (largeFileModeFilter === BOOLEAN_FILTER_FALSE) options.largeFileMode = false;
    if (skippedAnnotationFilter === BOOLEAN_FILTER_TRUE) options.expensiveAnnotationSkipped = true;
    if (skippedAnnotationFilter === BOOLEAN_FILTER_FALSE)
        options.expensiveAnnotationSkipped = false;
    const normalizedSearch = String(search || '').trim();
    if (normalizedSearch) options.search = normalizedSearch;
    return options;
};

const booleanFilterLabel = (value) => {
    if (value === BOOLEAN_FILTER_TRUE) return 'Yes';
    if (value === BOOLEAN_FILTER_FALSE) return 'No';
    return 'Any';
};

const bucketOptionFor = (value) =>
    VARIANT_BUCKET_OPTIONS.find((option) => option.value === value) ||
    VARIANT_BUCKET_OPTIONS.find((option) => option.value === VARIANT_BUCKET_ALL);

const analysisBucket = (variant) => variant?.analysisBucket || VARIANT_BUCKET_OPTIONS[1].value;

const isSummaryOnlyVariant = (variant) =>
    Boolean(variant?.summaryOnly) || variant?.fullInterpretationAvailable === false;

const isSkippedAnnotationVariant = (variant) => Boolean(variant?.expensiveAnnotationSkipped);

const largeFileCountsFrom = (source) => source?.largeFileSummary || source?.counts || {};

const bucketCountsFrom = (page, analysisJob) =>
    page?.counts?.bucketCounts ||
    page?.largeFileSummary?.bucketCounts ||
    analysisJob?.largeFileSummary?.bucketCounts ||
    {};

const evidenceCardKey = (evidence) =>
    [
        evidence?.source,
        evidence?.evidenceType,
        evidence?.applicability,
        evidence?.label,
        evidence?.summary,
        evidence?.details?.sourceFile,
        evidence?.details?.frequencyField,
    ]
        .filter(Boolean)
        .join('|');

const isAlphaGenomeCandidate = (variant) => {
    const consequenceText = (variant?.consequences || [])
        .map((item) => item?.consequence || '')
        .join(' ')
        .toLowerCase();
    return (
        hasApplicableEvidence(variant, 'CADD') &&
        ALPHAGENOME_CONSEQUENCE_TOKENS.some((token) => consequenceText.includes(token))
    );
};

const statusTone = (status) => {
    if (['READY', 'COMPLETED'].includes(status)) return 'success';
    if (['READY_FAST', 'PARTIAL', 'UNAVAILABLE'].includes(status)) return 'warning';
    if (['FAILED', 'ERROR'].includes(status)) return 'error';
    return 'info';
};

const progressiveStageFallback = (job) => {
    if (!job?.status) return '';
    if (job.status === 'READY') return 'READY_FULL';
    if (job.status === 'FAILED') return 'FAILED';
    return 'PREFLIGHT';
};

const progressiveStageMessage = (job) => {
    const stage = String(job?.analysisStage || progressiveStageFallback(job)).toUpperCase();
    if (stage === 'READY_FAST') {
        return 'Base candidate results are visible; compact or skipped evidence remains explicitly marked.';
    }
    if (stage === 'READY_FULL') {
        return 'Candidate enrichment is complete for the available analysis payload.';
    }
    if (stage === 'PARTIAL') {
        return 'Base results are visible, but some provider evidence is partial or unavailable.';
    }
    if (stage === 'FAILED') {
        return 'Analysis failed before a reviewable result became available.';
    }
    return 'Analysis is still preparing the reviewable result set.';
};

const analysisRequestErrorMessage = (error) => {
    const payload = error?.response?.data || {};
    const code = String(payload.errorCode || payload.code || '').trim();
    const message = String(
        payload.message || payload.detail || error?.message || 'Upload failed.',
    ).trim();
    return code ? `${code}: ${message}` : message;
};

const priorityTone = (tier) => {
    const normalized = variantTier({ tier });
    if (normalized === 'KNOWN_CLINICAL') return 'error';
    if (normalized === 'HIGH_PRIORITY') return 'warning';
    if (normalized === 'REVIEW') return 'info';
    if (normalized === 'FILTERED_QC') return 'warning';
    if (normalized === 'FILTERED_COMMON' || normalized === 'POPULATION_FILTERED') return 'default';
    if (normalized === 'BENIGN_LIKELY') return 'success';
    return 'default';
};

const providerTone = (provider) => {
    const label = providerReadinessLabel(provider);
    if (['READY', 'COMPLETED'].includes(label)) return 'success';
    if (
        [
            'PARTIAL',
            'UNAVAILABLE',
            'RESOURCE_UNAVAILABLE',
            'NEEDS SETUP',
            'MISSING_RESOURCE',
            'DISABLED',
        ].includes(label)
    )
        return 'warning';
    if (
        [
            'FAILED',
            'ERROR',
            'INVALID_RESOURCE',
            'INVALID_REFERENCE_PROFILE',
            'ASSEMBLY_MISMATCH',
            'ASSEMBLY_UNSUPPORTED',
        ].includes(label)
    )
        return 'error';
    if (provider?.ready) return 'success';
    if (provider?.enabled) return 'warning';
    return 'default';
};

const providerReadinessLabel = (provider) => {
    if (!provider) return 'UNKNOWN';
    const explicitStatus = String(provider?.status || '').toUpperCase();
    if (
        provider?.source === 'CADD' &&
        provider?.enabled &&
        (provider?.snvReady || provider?.indelReady) &&
        !(provider?.snvReady && provider?.indelReady)
    ) {
        return 'PARTIAL';
    }
    if (
        String(provider?.source || '').toLowerCase() === 'gnomad' &&
        provider?.enabled &&
        provider?.configuredFileCount > 0 &&
        provider?.availableFileCount > 0 &&
        provider?.availableFileCount < provider?.configuredFileCount
    ) {
        return 'PARTIAL';
    }
    if (
        [
            'READY',
            'DISABLED',
            'UNAVAILABLE',
            'PARTIAL',
            'MISSING_RESOURCE',
            'INVALID_RESOURCE',
            'RESOURCE_UNAVAILABLE',
            'INVALID_REFERENCE_PROFILE',
            'ASSEMBLY_MISMATCH',
            'ASSEMBLY_UNSUPPORTED',
            'FAILED',
            'ERROR',
            'PENDING',
            'RUNNING',
            'COMPLETED',
        ].includes(explicitStatus)
    ) {
        return explicitStatus;
    }
    if (provider?.ready) return 'READY';
    if (provider?.enabled) return 'NEEDS SETUP';
    return 'OFFLINE';
};

const parsePreRankingEventFields = (message = '') => {
    const fields = {};
    const normalized = String(message || '');
    const matches = normalized.matchAll(/([A-Za-z][A-Za-z0-9]*)=([^,\s]+)/g);
    Array.from(matches).forEach((match) => {
        const rawValue = match[2];
        const numeric = Number(rawValue);
        fields[match[1]] = Number.isFinite(numeric) && rawValue !== '' ? numeric : rawValue;
    });
    return fields;
};

const latestPreRankingAnnotationSummary = (events = []) => {
    let latest = null;
    (events || []).forEach((event) => {
        const message = String(event?.message || '');
        if (!message.startsWith(PRE_RANKING_EVENT_PREFIX)) return;
        const statusMatch = /^Pre-ranking transcript annotation ([A-Z_]+)/.exec(message);
        latest = {
            ...parsePreRankingEventFields(message),
            status: statusMatch?.[1] || 'UNKNOWN',
            createdAt: event?.createdAt,
        };
    });
    return latest;
};

const latestNormalizationRunSummary = (events = []) => {
    let latest = null;
    (events || []).forEach((event) => {
        const message = String(event?.message || '');
        if (!message.startsWith(NORMALIZATION_EVENT_PREFIX)) return;
        const statusMatch = /^Reference normalization ([A-Z_]+)/.exec(message);
        latest = {
            ...parsePreRankingEventFields(message),
            status: statusMatch?.[1] || 'UNKNOWN',
            createdAt: event?.createdAt,
        };
    });
    return latest;
};

const transcriptSourceLabel = (source) => {
    if (source === 'ANN_ONLY') return 'VCF ANN fallback';
    if (source === 'VEP_PENDING') return 'VEP queued candidate annotation';
    if (source === 'VEP_UNAVAILABLE') return 'VEP candidate annotation';
    if (source === 'VEP_COMPUTED') return 'VEP computed';
    if (source === 'VEP_CACHED') return 'VEP cache hit';
    return source || 'Unknown source';
};

const transcriptStatusTone = (status) => {
    const normalized = String(status || '').toUpperCase();
    if (normalized === 'COMPLETED') return 'success';
    if (['PENDING', 'RUNNING'].includes(normalized)) return 'primary';
    if (normalized === 'NO_RECORD') return 'info';
    if (['UNAVAILABLE', 'PARTIAL', 'NOT_APPLICABLE'].includes(normalized)) return 'warning';
    if (normalized === 'FAILED') return 'error';
    return 'default';
};

const transcriptStatusExplanation = (annotation) => {
    const status = String(annotation?.status || '').toUpperCase();
    const source = String(annotation?.source || '').toUpperCase();
    if (status === 'COMPLETED') {
        if (source.startsWith('VEP')) {
            return 'VEP transcript annotation completed for this candidate.';
        }
        if (source === 'ANN_ONLY') {
            return 'Transcript annotation came from the uploaded VCF ANN field.';
        }
        return 'Transcript annotation completed.';
    }
    if (status === 'PENDING') {
        return 'VEP enrichment is queued for ranked candidate variants; refresh or wait for the background annotation to finish.';
    }
    if (status === 'RUNNING') {
        return 'VEP transcript enrichment is running in the background.';
    }
    if (status === 'NO_RECORD') {
        return 'No transcript record was returned for this source; this is not evidence of low risk.';
    }
    if (status === 'NOT_APPLICABLE') {
        return 'This transcript evidence source is not applicable to this variant type or context.';
    }
    if (status === 'UNAVAILABLE') {
        return 'Transcript resource unavailable; this is not evidence of low risk.';
    }
    if (status === 'FAILED') {
        return 'Transcript enrichment failed for this candidate; base variant ranking remains available.';
    }
    return '';
};

const transcriptStatusSeverity = (status) => {
    const normalized = String(status || '').toUpperCase();
    if (normalized === 'FAILED') return 'error';
    if (['UNAVAILABLE', 'NOT_APPLICABLE'].includes(normalized)) return 'warning';
    return 'info';
};

const isAffirmative = (value) =>
    value === true ||
    ['true', 'yes', 'y', '1', 'mane_select', 'canonical'].includes(
        String(value || '').toLowerCase(),
    );

const transcriptSortWeight = (annotation) => {
    if (isAffirmative(annotation?.maneSelect)) return 0;
    if (isAffirmative(annotation?.manePlusClinical)) return 1;
    if (isAffirmative(annotation?.canonical)) return 2;
    return 3;
};

const LOW_CONTEXT_CONSEQUENCE_TERMS = new Set([
    'intron_variant',
    'intergenic_variant',
    'upstream_gene_variant',
    'downstream_gene_variant',
    '3_prime_utr_variant',
    '5_prime_utr_variant',
    'regulatory_region_variant',
    'non_coding_transcript_variant',
    'nmd_transcript_variant',
]);

const consequenceTokens = (value) =>
    String(value || '')
        .toLowerCase()
        .split(/[^a-z0-9_]+/)
        .filter(Boolean);

const isLowContextConsequence = (annotation) =>
    consequenceTokens(annotation?.consequence).some((token) =>
        LOW_CONTEXT_CONSEQUENCE_TERMS.has(token),
    );

const transcriptSummaryWeight = (annotation) => {
    const impact = String(annotation?.impact || '').toUpperCase();
    if (
        hasHighImpactTranscriptConsequence(annotation) &&
        isProteinCodingTranscriptAnnotation(annotation)
    ) {
        return 0;
    }
    if (impact === 'HIGH' && isProteinCodingTranscriptAnnotation(annotation)) {
        return 1;
    }
    if (impact === 'MODERATE' && isProteinCodingTranscriptAnnotation(annotation)) {
        return 2;
    }
    if (isAffirmative(annotation?.maneSelect)) return 3;
    if (isAffirmative(annotation?.manePlusClinical)) return 4;
    if (isAffirmative(annotation?.canonical)) return 5;
    if (isLowContextConsequence(annotation)) return 20;
    return 10;
};

const rankedTranscriptSummaryItems = (variant) =>
    [...(variant?.consequences || []), ...(variant?.transcriptAnnotations || [])]
        .filter(Boolean)
        .sort((left, right) => {
            const weightDiff = transcriptSummaryWeight(left) - transcriptSummaryWeight(right);
            if (weightDiff !== 0) return weightDiff;
            return transcriptSortWeight(left) - transcriptSortWeight(right);
        });

const detailValue = (details, key) => {
    const value = details?.[key];
    return value == null || value === '' ? null : String(value);
};

const evidenceTone = (applicability) => {
    if (applicability === 'APPLICABLE') return 'success';
    if (applicability === 'UNAVAILABLE') return 'warning';
    if (applicability === 'CONFLICTING') return 'error';
    if (applicability === 'NO_RECORD') return 'info';
    return 'default';
};

const evidenceStatusExplanation = (applicability) => {
    if (applicability === 'UNAVAILABLE') return 'Resource unavailable; this is not low risk.';
    if (applicability === 'NO_RECORD') return 'No matching database record; this is not AF=0.';
    if (applicability === 'CONFLICTING') return 'Conflicting evidence; manual review recommended.';
    return '';
};

const acmgSuggestionStatusTone = (status) => {
    const normalized = String(status || '').toUpperCase();
    if (normalized === 'SUGGESTED') return 'primary';
    if (normalized === 'UNAVAILABLE') return 'warning';
    if (normalized === 'CONFLICTING') return 'error';
    return 'default';
};

const acmgSuggestionKey = (suggestion, index) =>
    [suggestion?.code, suggestion?.strength, suggestion?.status, suggestion?.sourceEvidence, index]
        .filter((value) => value != null && value !== '')
        .join('|');

const acmgCodeList = (suggestions) =>
    Array.from(new Set((suggestions || []).map((suggestion) => suggestion?.code).filter(Boolean)));

const acmgSuggestedCount = (variant) =>
    (variant?.acmgEvidenceSuggestions || []).filter(
        (item) => String(item?.status || '').toUpperCase() === 'SUGGESTED',
    ).length;

const acmgManualReviewCount = (variant) =>
    (variant?.acmgEvidenceSuggestions || []).filter((item) => item?.manualReviewRequired).length;

const evidenceValueDisplay = (variant, source) => {
    const evidence = evidenceFor(variant, source);
    if (!evidence) return '-';
    if (evidence?.applicability !== 'APPLICABLE') return formatStatus(evidence?.applicability);
    if (source === 'GNOMAD') {
        return formatMetricValue(evidence?.details?.value ?? evidence?.rawScore);
    }
    if (source === 'CLINVAR') {
        return evidence?.label || evidence?.details?.value || evidence?.summary || 'Applicable';
    }
    if (evidence?.rawScore != null) return formatScore(evidence.rawScore);
    return displayValue(evidence?.details?.value || evidence?.label, '-');
};

const populationFrequencyDisplay = (variant) => {
    const evidence = evidenceFor(variant, 'GNOMAD');
    const value =
        evidence?.details?.maximumAlleleFrequency ??
        evidence?.details?.populationMaximumAlleleFrequency ??
        evidence?.details?.value ??
        evidence?.rawScore;
    return value == null ? '-' : formatMetricValue(value);
};

const evidenceApplicabilityLabel = (variant, source) =>
    evidenceFor(variant, source)?.applicability || 'UNKNOWN';

const isProteinCodingTranscriptAnnotation = (annotation) => {
    const biotype = String(annotation?.transcriptSource || '')
        .trim()
        .toLowerCase();
    const source = String(annotation?.source || '')
        .trim()
        .toLowerCase();
    const hasProteinDetail = Boolean(
        annotation?.hgvsp ||
            annotation?.proteinPosition ||
            annotation?.aminoAcids ||
            annotation?.codons,
    );
    if (!biotype) {
        return source.includes('vep') ? hasProteinDetail : true;
    }
    if (biotype === 'protein_coding') return true;
    return hasProteinDetail;
};

const hasHighImpactTranscriptConsequence = (annotation) => {
    const consequence = String(annotation?.consequence || '').toLowerCase();
    return [
        'frameshift_variant',
        'stop_gained',
        'splice_acceptor_variant',
        'splice_donor_variant',
        'transcript_ablation',
        'start_lost',
        'stop_lost',
    ].some((term) => consequence.split(/[^a-z0-9_]+/).includes(term));
};

const hasLoadedVariantDetailPayload = (variant) =>
    Boolean(
        variant &&
            ((variant.scoreComponents && Object.keys(variant.scoreComponents).length > 0) ||
                (variant.scoreBreakdown && Object.keys(variant.scoreBreakdown).length > 0) ||
                (Array.isArray(variant.rationale) && variant.rationale.length > 0) ||
                (Array.isArray(variant.positiveReasons) && variant.positiveReasons.length > 0) ||
                (Array.isArray(variant.negativeReasons) && variant.negativeReasons.length > 0) ||
                (Array.isArray(variant.filterReasons) && variant.filterReasons.length > 0)),
    );

const mergeSelectedVariantFromPage = (current, pageVariant) => {
    if (!pageVariant) return current;
    if (hasLoadedVariantDetailPayload(current)) {
        return {
            ...current,
            analysisBucket: pageVariant.analysisBucket || current.analysisBucket,
            priorityTier: pageVariant.priorityTier || current.priorityTier,
            tier: pageVariant.tier || current.tier,
            rankingScore: pageVariant.rankingScore ?? current.rankingScore,
            totalScore: pageVariant.totalScore ?? current.totalScore,
            topCandidate: pageVariant.topCandidate ?? current.topCandidate,
            largeFileMode: pageVariant.largeFileMode ?? current.largeFileMode,
            summaryOnly: current.summaryOnly ?? pageVariant.summaryOnly,
            fullInterpretationAvailable:
                current.fullInterpretationAvailable ?? pageVariant.fullInterpretationAvailable,
        };
    }
    return pageVariant;
};

const transcriptHighlights = (variant) => {
    const annotations = variant?.transcriptAnnotations || [];
    const chips = [];
    if (
        annotations.some(
            (annotation) =>
                ['HIGH', 'MODERATE'].includes(String(annotation?.impact || '').toUpperCase()) &&
                isProteinCodingTranscriptAnnotation(annotation),
        )
    ) {
        chips.push('Transcript impact');
    }
    if (annotations.some((annotation) => isAffirmative(annotation?.maneSelect))) {
        chips.push('MANE Select');
    }
    if (annotations.some((annotation) => isAffirmative(annotation?.manePlusClinical))) {
        chips.push('MANE Plus Clinical');
    }
    if (
        annotations.some(
            (annotation) =>
                String(annotation?.lof || '').toUpperCase() === 'HC' &&
                isProteinCodingTranscriptAnnotation(annotation) &&
                hasHighImpactTranscriptConsequence(annotation),
        )
    ) {
        chips.push('LOFTEE HC support');
    }
    return chips;
};

const rankingNarrative = (variant) => {
    if (!variant) return 'No ranking explanation was returned for this variant.';
    const statements = [];
    const leadingReason = topReason(variant);
    if (leadingReason) statements.push(leadingReason);

    const clinvar = evidenceFor(variant, 'CLINVAR');
    if (clinvar?.applicability === 'APPLICABLE' && clinvar?.label) {
        statements.push(`ClinVar ${clinvar.label} evidence is present.`);
    }

    const gnomad = evidenceFor(variant, 'GNOMAD');
    if (gnomad?.applicability === 'APPLICABLE') {
        statements.push('Population frequency remains within the configured rare-disease gate.');
    }

    const cadd = numericEvidenceScore(variant, 'CADD');
    if (cadd != null && cadd >= 20) {
        statements.push('CADD contributes computational prioritization support.');
    }

    const spliceAi = numericEvidenceScore(variant, 'SPLICEAI');
    if (spliceAi != null) {
        statements.push('SpliceAI contributes splice-impact prediction support.');
    }

    if (phenotypeSupportInfo(variant)) {
        statements.push(
            'Phenotype support contributes candidate prioritization from HPO gene prior.',
        );
    }

    if ((variant?.filterReasons || []).length) {
        statements.push(
            'Filtering notes still apply and should be reviewed before interpretation.',
        );
    }

    return (
        statements.slice(0, 4).join(' ') || 'No ranking explanation was returned for this variant.'
    );
};

const collectReviewNotes = (variant) => {
    if (!variant) return [];
    const notes = [];
    const seen = new Set();
    const gnomadUnavailableNotes = new Set([
        'gnomad unavailable means population frequency cannot be evaluated',
        'gnomad evidence is unavailable; absence is not interpreted as low risk',
        'gnomad is unavailable for this variant; absence is not low risk.',
    ]);
    const add = (message, severity = 'info') => {
        const normalized = String(message || '').trim();
        const gnomadUnavailable = gnomadUnavailableNotes.has(normalized.toLowerCase());
        const display = gnomadUnavailable
            ? 'gnomAD unavailable. Population frequency could not be evaluated.'
            : normalized;
        if (!display || seen.has(display)) return;
        seen.add(display);
        notes.push({ message: display, severity: gnomadUnavailable ? 'warning' : severity });
    };

    (variant?.warnings || []).forEach((warning) => {
        if (!isSupersededClinvarUnavailableWarning(variant, warning)) {
            add(warning, 'warning');
        }
    });
    (variant?.filterReasons || []).forEach((reason) => add(reason, 'warning'));
    if (isSummaryOnlyVariant(variant)) {
        add(
            'This is a summary-only large-file record; absent detailed evidence is not a benign or low-risk finding.',
            'warning',
        );
    }
    if (isSkippedAnnotationVariant(variant)) {
        add(
            variant.expensiveAnnotationSkippedReason ||
                'Expensive annotation was skipped for this record in large-file mode.',
            'info',
        );
    }

    (variant?.evidence || []).forEach((evidence) => {
        if (isSupersededClinvarUnavailableEvidence(variant, evidence)) return;
        if (evidence?.applicability === 'UNAVAILABLE') {
            add(
                `${evidence?.source || 'Evidence'} is unavailable for this variant; absence is not low risk.`,
                'info',
            );
        }
        if (evidence?.applicability === 'NO_RECORD') {
            add(
                `${evidence?.source || 'Evidence'} returned no matching record; this is not equivalent to a benign call.`,
                'info',
            );
        }
        if (evidence?.applicability === 'CONFLICTING') {
            add(
                `${evidence?.source || 'Evidence'} contains conflicting information and needs manual review.`,
                'warning',
            );
        }
    });

    if (acmgManualReviewCount(variant) > 0) {
        add(
            'ACMG-ready suggestions require manual review and do not represent a final ACMG classification.',
            'warning',
        );
    }

    if (
        (variant?.transcriptAnnotations || []).some(
            (annotation) => annotation?.status === 'UNAVAILABLE',
        )
    ) {
        add(
            'Transcript annotation is unavailable for at least one selected transcript; this is not evidence of low risk.',
            'info',
        );
    }

    if (numericEvidenceScore(variant, 'SPLICEAI') != null) {
        add('SpliceAI is a computational splice prediction, not a clinical conclusion.', 'info');
    }

    if (phenotypeSupportInfo(variant)) {
        add(
            'Phenotype support is used for candidate prioritization only. It is not a diagnosis.',
            'info',
        );
    }

    return notes;
};

const variantKey = (variant) => variant?.observation?.key || {};

const variantCoordinate = (variant) => {
    const key = variantKey(variant);
    if (!key?.chromosome || key?.position == null) return canonicalKey(variant) || '-';
    return formatVariantCoordinate(key,canonicalKey(variant) || '-');
};

const normalizeVariantExactKey = (value) => {
    const text = String(value || '')
        .trim()
        .toUpperCase();
    if (!text) return '';
    const withoutBuild = text.replace(/^(GRCH37|GRCH38|HG19|HG38):/, '');
    const parts = withoutBuild.split(':');
    if (parts[0]) {
        parts[0] = parts[0].replace(/^CHR/, '');
    }
    return parts.join(':');
};

const variantExactKey = (variant) => {
    const key = variantKey(variant);
    const chromosome = normalizeVariantExactKey(key?.chromosome).split(':')[0];
    const position = Number(key?.position);
    const reference = String(key?.reference || '')
        .trim()
        .toUpperCase();
    const alternate = String(key?.alternate || '')
        .trim()
        .toUpperCase();
    if (!chromosome || !Number.isFinite(position) || !reference || !alternate) return '';
    return `${chromosome}:${Math.round(position)}:${reference}:${alternate}`;
};

const variantNavigationPayload = (variant, focusMode = 'locate') => {
    const key = variantKey(variant);
    const position = Number(key?.position);
    if (!key?.chromosome || !Number.isFinite(position)) return null;
    const exactKey = variantExactKey(variant) || canonicalKey(variant);
    const geneSymbol = geneSummary(variant);
    return {
        chromosome: key.chromosome,
        segmentRange: { start: position, end: position },
        variantAnchor: {
            exactKey,
            analysisCandidateId: candidateKey(variant) || null,
            analysisVariantId: canonicalKey(variant) || null,
            canonicalVariantId: canonicalKey(variant) || null,
            start: position,
            end: position,
            ref: key.reference,
            alt: key.alternate,
            geneSymbol: geneSymbol && geneSymbol !== '-' ? geneSymbol : null,
            variantType: variant?.observation?.variantType,
            focusMode,
        },
    };
};

const visualSelectionSearchText = (selection) => {
    const chromosome = String(selection?.chromosome || '')
        .trim()
        .replace(/^chr/i, '');
    const position = Number(selection?.position);
    if (chromosome && Number.isFinite(position)) {
        return `chr${chromosome}:${Math.round(position)}`;
    }
    return normalizeVariantExactKey(
        selection?.exactKey || selection?.canonicalVariantId || selection?.variantId || '',
    );
};

const geneSummary = (variant) => {
    const genes = Array.from(
        new Set(
            rankedTranscriptSummaryItems(variant)
                .map((item) => item?.geneSymbol)
                .filter(Boolean),
        ),
    );
    const engineGene =
        variant?.exomiserCandidateFeatures?.geneSymbol || variant?.candidateIdentity?.geneSymbol;
    return genes.slice(0, 3).join(', ') || displayValue(engineGene);
};

const exomiserCandidateContext = (variant) => {
    const value = variant?.exomiserCandidateFeatures;
    return value && typeof value === 'object' ? value : null;
};

const exomiserFieldList = (value) =>
    Array.isArray(value)
        ? value.filter((item) => typeof item === 'string' && item.trim()).map((item) => item.trim())
        : [];

const exomiserScoreSummary = (features) =>
    [
        ['variant', features?.variantScore],
        ['gene combined', features?.geneCombinedScore],
        ['gene phenotype', features?.genePhenotypeScore],
        ['gene variant', features?.geneVariantScore],
    ]
        .filter(([, value]) => value != null && Number.isFinite(Number(value)))
        .map(([label, value]) => `${label} ${formatScore(value)}`)
        .join(' / ');

const consequenceLabel = (variant) => {
    const rankedItems = rankedTranscriptSummaryItems(variant);
    const consequences = Array.from(
        new Set(rankedItems.map((item) => item?.consequence).filter(Boolean)),
    );
    const hasSpecificTranscriptConsequence = rankedItems.some(
        (item) =>
            item?.source && item?.consequence && String(item.consequence).toUpperCase() !== 'INDEL',
    );
    const filtered = hasSpecificTranscriptConsequence
        ? consequences.filter((item) => String(item).toUpperCase() !== 'INDEL')
        : consequences;
    return filtered.slice(0, 2).join('; ') || '-';
};

const numericEvidenceScore = (variant, source) => {
    const rawScore = evidenceFor(variant, source)?.rawScore;
    const numeric = Number(rawScore);
    return Number.isFinite(numeric) ? numeric : null;
};

const downloadBlobFile = (blob, filename) => {
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(url);
};

const ProviderStatusCard = ({ title, provider, detail, children }) => (
    <Box className="cv-analysis-provider-card">
        <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
            <Typography variant="subtitle2">{title}</Typography>
            <Chip
                size="small"
                variant="outlined"
                color={providerTone(provider)}
                label={providerReadinessLabel(provider)}
            />
        </Stack>
        <Typography variant="caption" color="text.secondary">
            {detail || provider?.message || 'Status is not loaded yet.'}
        </Typography>
        {provider?.version ? (
            <Typography variant="caption" color="text.secondary">
                Version: {provider.version}
            </Typography>
        ) : null}
        {provider?.primaryEvidenceSource || provider?.fallbackEvidenceSource ? (
            <Typography variant="caption" className="cv-analysis-provider-source">
                Source:{' '}
                {[provider.primaryEvidenceSource, provider.fallbackEvidenceSource]
                    .filter(Boolean)
                    .join(' / ')}
            </Typography>
        ) : null}
        {provider?.lastChecked ? (
            <Typography variant="caption" color="text.secondary">
                Checked: {formatEventTime(provider.lastChecked)}
            </Typography>
        ) : null}
        {provider?.transcriptAnnotationStatus && provider?.executionEnabled != null ? (
            <Typography variant="caption" color="text.secondary">
                VEP 执行开关: {provider.executionEnabled ? 'enabled' : 'disabled'}
            </Typography>
        ) : null}
        {provider?.transcriptAnnotationStatus ? (
            <Typography variant="caption" color="text.secondary">
                配置默认状态: {formatStatus(provider.transcriptAnnotationStatus)}（不是本次作业状态）
            </Typography>
        ) : null}
        {provider?.warnings?.length ? (
            <Typography variant="caption" color="warning.main">
                Warnings: {provider.warnings.length}
            </Typography>
        ) : null}
        {provider?.explanation && provider.explanation !== provider.message ? (
            <Typography variant="caption" color="text.secondary">
                {provider.explanation}
            </Typography>
        ) : null}
        {children}
    </Box>
);

const PhenotypeProvenancePanel = ({ inputs = [], title = 'HPO provenance' }) => {
    const normalizedInputs = (Array.isArray(inputs) ? inputs : []).filter((input) => input?.hpoId);
    if (!normalizedInputs.length) return null;
    return (
        <Box className="cv-analysis-hpo-provenance-panel">
            <Typography variant="caption" color="text.secondary">
                {title}
            </Typography>
            <Stack gap={0.75}>
                {normalizedInputs.slice(0, 12).map((input, index) => (
                    <Box
                        key={`${input.hpoId}-${input.source || 'source'}-${index}`}
                        className="cv-analysis-hpo-provenance-row"
                    >
                        <Stack direction="row" alignItems="center" gap={0.75} flexWrap="wrap">
                            <Chip size="small" variant="outlined" label={input.hpoId} />
                            <Chip
                                size="small"
                                color={input.excluded ? 'warning' : 'success'}
                                variant="outlined"
                                label={input.excluded ? 'Explicitly absent' : 'Present'}
                            />
                            {input.ontologyStatus ? (
                                <Chip
                                    size="small"
                                    color={statusTone(input.ontologyStatus)}
                                    variant="outlined"
                                    label={`Ontology: ${formatStatus(input.ontologyStatus)}`}
                                />
                            ) : null}
                            <Typography variant="caption">
                                {displayValue(input.label, input.hpoId)}
                            </Typography>
                            <Chip
                                size="small"
                                color={
                                    String(input.source || '').includes('AI')
                                        ? 'warning'
                                        : 'default'
                                }
                                variant="outlined"
                                label={input.sourceLabel || sourceLabel(input.source)}
                            />
                        </Stack>
                        <Typography variant="caption" color="text.secondary">
                            Evidence: {displayValue(input.matchedTerm, 'confirmed HPO input')}
                            {isManualHpoSource(input.source)
                                ? ' / manually confirmed source; backend confidence cap applies'
                                : input.confidence != null
                                  ? ` / confidence ${formatScore(input.confidence)}`
                                  : ''}
                        </Typography>
                        {input.requestedHpoId && input.requestedHpoId !== input.hpoId ? (
                            <Typography variant="caption" color="text.secondary">
                                Requested {input.requestedHpoId}; normalized to {input.hpoId}
                                {input.ontologyLabel ? ` (${input.ontologyLabel})` : ''}
                            </Typography>
                        ) : null}
                    </Box>
                ))}
            </Stack>
        </Box>
    );
};

const PhenotypeSummaryPanel = ({ summary, genePriorLookup, genePriorLookupLoading }) => {
    const validTerms = asTextList(summary?.validHpoTerms);
    const invalidTerms = asTextList(summary?.invalidHpoTerms);
    const excludedTerms = asTextList(summary?.excludedHpoTerms);
    const phenotypeInputs = Array.isArray(summary?.phenotypeInputs) ? summary.phenotypeInputs : [];
    const positiveInputs = phenotypeInputs.filter((input) => !input?.excluded);
    const excludedInputs = phenotypeInputs.filter((input) => input?.excluded);
    const genePriorSummary = summary?.genePriorSummary || {};
    const rescueSummary = summary?.phenotypeRescueSummary || null;
    const warnings = Array.from(
        new Set([...asTextList(summary?.warnings), ...asTextList(genePriorSummary?.warnings)]),
    );
    const topGenePriors = Array.isArray(genePriorSummary?.topGenePriors)
        ? genePriorSummary.topGenePriors
        : [];
    const supportedCandidates = Array.isArray(summary?.topPhenotypeSupportedCandidates)
        ? summary.topPhenotypeSupportedCandidates
        : [];
    const hasPhenotypeInput = hasPhenotypeSummaryInput(summary);
    const statusText = !hasPhenotypeInput
        ? 'No phenotype terms provided'
        : !validTerms.length && excludedTerms.length
          ? 'Explicitly absent phenotype terms captured as observational metadata; not used for ranking.'
          : summary?.rankingApplied
            ? 'Phenotype-aware reranking applied to reviewable candidates.'
            : 'Phenotype input captured, not applied to ranking.';

    return (
        <Box
            className={['cv-analysis-phenotype-card', hasPhenotypeInput ? '' : 'is-empty']
                .filter(Boolean)
                .join(' ')}
        >
            <Stack direction="row" justifyContent="space-between" alignItems="center" gap={1}>
                <div>
                    <Typography variant="subtitle2">Phenotype-aware prioritization</Typography>
                    <Typography variant="caption" color="text.secondary">
                        {statusText}
                    </Typography>
                </div>
                <Chip
                    size="small"
                    color={
                        summary?.rankingApplied ? 'success' : hasPhenotypeInput ? 'info' : 'default'
                    }
                    variant={summary?.rankingApplied ? 'filled' : 'outlined'}
                    label={summary?.rankingApplied ? 'Reranking applied' : 'No ranking boost'}
                />
            </Stack>

            {hasPhenotypeInput && (
                <>
                    <Box className="cv-analysis-phenotype-metrics">
                        <span>
                            <strong>{formatNumber(summary?.inputHpoCount, '0')}</strong>
                            input
                        </span>
                        <span>
                            <strong>{formatNumber(summary?.validHpoCount, '0')}</strong>
                            valid
                        </span>
                        <span>
                            <strong>{formatNumber(summary?.invalidHpoCount, '0')}</strong>
                            invalid
                        </span>
                        <span>
                            <strong>{formatNumber(summary?.duplicateHpoCount, '0')}</strong>
                            duplicates
                        </span>
                        <span>
                            <strong>
                                {formatNumber(
                                    summary?.excludedHpoCount ?? excludedTerms.length,
                                    '0',
                                )}
                            </strong>
                            explicitly absent
                        </span>
                        <span>
                            <strong>
                                {formatNumber(summary?.candidatesWithPhenotypeSupport, '0')}
                            </strong>
                            supported candidates
                        </span>
                    </Box>

                    {validTerms.length ? (
                        <Box className="cv-analysis-hpo-term-list">
                            {validTerms.slice(0, 10).map((term) => (
                                <Chip key={term} size="small" variant="outlined" label={term} />
                            ))}
                        </Box>
                    ) : null}

                    {excludedTerms.length ? (
                        <Box className="cv-analysis-hpo-term-list">
                            {excludedTerms.slice(0, 10).map((term) => (
                                <Chip
                                    key={`excluded-${term}`}
                                    size="small"
                                    color="warning"
                                    variant="outlined"
                                    label={`${term} explicitly absent`}
                                />
                            ))}
                        </Box>
                    ) : null}

                    <PhenotypeProvenancePanel
                        inputs={positiveInputs}
                        title="Confirmed HPO provenance"
                    />
                    <PhenotypeProvenancePanel
                        inputs={excludedInputs}
                        title="Explicitly absent HPO provenance (observational only)"
                    />

                    {invalidTerms.length ? (
                        <Alert severity="warning" className="cv-analysis-inline-alert">
                            Invalid HPO terms: {invalidTerms.join(', ')}
                        </Alert>
                    ) : null}

                    <Box className="cv-analysis-gene-prior-summary">
                        <Stack direction="row" alignItems="center" gap={0.75} flexWrap="wrap">
                            <Typography variant="caption" color="text.secondary">
                                Gene prior:
                            </Typography>
                            <Chip
                                size="small"
                                variant="outlined"
                                color={statusTone(genePriorSummary?.status)}
                                label={formatStatus(genePriorSummary?.status || 'NOT_GENERATED')}
                            />
                            {genePriorSummary?.source ? (
                                <Chip
                                    size="small"
                                    variant="outlined"
                                    label={`${genePriorSummary.source}${
                                        genePriorSummary?.sourceVersion
                                            ? ` / ${genePriorSummary.sourceVersion}`
                                            : ''
                                    }`}
                                />
                            ) : null}
                            <Chip
                                size="small"
                                variant="outlined"
                                label={`${formatNumber(genePriorSummary?.genesWithPrior, '0')} genes`}
                            />
                        </Stack>
                        {topGenePriors.length ? (
                            <Box className="cv-analysis-gene-prior-list">
                                {topGenePriors.slice(0, 5).map((prior, index) => (
                                    <span
                                        key={`${prior?.geneSymbol}-${prior?.phenotypeRank}-${index}`}
                                    >
                                        <strong>{displayValue(prior?.geneSymbol)}</strong>
                                        rank {displayValue(prior?.phenotypeRank)} / score{' '}
                                        {formatScore(prior?.phenotypeScore)}
                                    </span>
                                ))}
                            </Box>
                        ) : (
                            <Typography variant="caption" color="text.secondary">
                                Gene prior status is{' '}
                                {formatStatus(genePriorSummary?.status || 'NOT_GENERATED')}; no gene
                                list is available. No prior or unavailable status is not evidence of
                                benign status or low risk.
                            </Typography>
                        )}
                        {genePriorLookupLoading ? (
                            <Typography variant="caption" color="text.secondary">
                                Checking searched gene against phenotype prior...
                            </Typography>
                        ) : genePriorLookup?.message ? (
                            <Alert
                                severity={genePriorLookup?.inEffectiveTopK ? 'success' : 'info'}
                                className="cv-analysis-inline-alert"
                            >
                                {genePriorLookup.message} No prior or outside-top-K status is not
                                evidence of benign status or low risk.
                            </Alert>
                        ) : null}
                    </Box>

                    {rescueSummary && rescueSummary.intervalResourceStatus !== 'DISABLED' ? (
                        <Alert
                            severity={
                                Number(rescueSummary.rescueRejectedByHardGateCount || 0) > 0
                                    ? 'warning'
                                    : 'info'
                            }
                            className="cv-analysis-inline-alert"
                        >
                            Phenotype-prior rescue: protected{' '}
                            {formatNumber(rescueSummary.rescueCandidateCount, '0')} candidate(s) in{' '}
                            {displayValue(rescueSummary.effectiveAssembly)}. Selected for review:{' '}
                            {formatNumber(rescueSummary.rescuedSelectedCount, '0')}; rejected by
                            hard gate:{' '}
                            {formatNumber(rescueSummary.rescueRejectedByHardGateCount, '0')}. Rescue
                            protects candidate visibility only and does not override hard gates or
                            imply a clinical classification.
                        </Alert>
                    ) : null}

                    {supportedCandidates.length ? (
                        <Box className="cv-analysis-supported-candidate-list">
                            {supportedCandidates.slice(0, 5).map((candidate, index) => (
                                <Chip
                                    key={`${candidateKey(candidate)}-${candidate?.geneSymbol}-${index}`}
                                    size="small"
                                    color="success"
                                    variant="outlined"
                                    label={`${displayValue(candidate?.geneSymbol)} rank ${displayValue(
                                        candidate?.phenotypeRank,
                                    )} boost ${formatContribution(candidate?.scoreBoost)}`}
                                />
                            ))}
                        </Box>
                    ) : null}

                    {warnings.length ? (
                        <Stack gap={0.75}>
                            {warnings.slice(0, 3).map((warning) => (
                                <Alert key={warning} severity="info">
                                    {warning}
                                </Alert>
                            ))}
                        </Stack>
                    ) : null}

                    <Typography variant="caption" color="text.secondary">
                        Phenotype support is used for candidate prioritization only. It is not a
                        diagnosis.
                    </Typography>
                </>
            )}
        </Box>
    );
};

const EvidenceDetailLine = ({ label, value }) =>
    value == null || value === '' ? null : (
        <Typography variant="caption" className="cv-analysis-evidence-detail-line">
            <strong>{label}:</strong> {String(value)}
        </Typography>
    );

const EvidenceAssemblySummary = ({ variant }) => {
    const entries = ['CLINVAR', 'GNOMAD', 'CADD', 'SPLICEAI']
        .map((source) => {
            const evidence = evidenceFor(variant, source);
            if (!evidence) return null;
            const presentation = evidenceAssemblyPresentation(evidence);
            const label = evidenceAssemblyLabel(presentation);
            if (!label) return null;
            return {
                source,
                label,
                status: evidenceStatusValue(evidence),
                kind: presentation.kind,
            };
        })
        .filter(Boolean);
    if (!entries.length) return null;
    return (
        <Box className="cv-analysis-evidence-assembly-summary">
            <Typography variant="caption">Evidence assembly and source</Typography>
            <Stack direction="row" gap={0.75} flexWrap="wrap">
                {entries.map((entry) => (
                    <Tooltip
                        key={`${entry.source}-${entry.label}-${entry.status}`}
                        title={`${entry.source}: ${entry.label}; ${formatStatus(entry.status)}`}
                    >
                        <Chip
                            size="small"
                            variant="outlined"
                            color={entry.kind === 'PROJECTION_FAILURE' ? 'warning' : 'default'}
                            label={`${entry.source}: ${entry.label}`}
                        />
                    </Tooltip>
                ))}
            </Stack>
            <Typography variant="caption" color="text.secondary">
                Candidate coordinates above remain in the uploaded VCF assembly.
            </Typography>
        </Box>
    );
};

const EvidenceCard = ({ evidence }) => {
    const strongestEffect = evidence?.details?.strongestEffect;
    const direction = detailValue(strongestEffect, 'direction');
    const trackName = detailValue(strongestEffect, 'track_name');
    const outputType = detailValue(strongestEffect, 'outputType');
    const statusExplanation = evidenceStatusExplanation(evidence?.applicability);
    const isPhenotypeEvidence = evidence?.evidenceType === PHENOTYPE_EVIDENCE_TYPE;
    const isTrioEvidence =
        evidence?.source === TRIO_EVIDENCE_SOURCE || evidence?.evidenceType === 'SEGREGATION';
    const phenotypeDetails = evidence?.details || {};
    const trioDetails = evidence?.details || {};
    const trioContribution = trioDetails.trioRankingContribution || null;
    const trioContributionValue = trioContributionScore(trioContribution);
    const assemblyPresentation = evidenceAssemblyPresentation(evidence);
    const assemblyLabel = evidenceAssemblyLabel(assemblyPresentation);
    const evidenceStatus = evidenceStatusValue(evidence);
    const provenanceLines = evidenceProvenanceLines(evidence);
    return (
        <Box
            data-testid={isTrioEvidence ? 'trio-evidence-detail' : undefined}
            className={[
                'cv-analysis-evidence-card',
                `is-${String(evidence?.applicability || '').toLowerCase()}`,
                isPhenotypeEvidence ? 'is-phenotype' : '',
                isTrioEvidence ? 'is-trio' : '',
            ]
                .filter(Boolean)
                .join(' ')}
        >
            <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
                <Typography variant="subtitle2">
                    {isPhenotypeEvidence
                        ? 'Phenotype support'
                        : isTrioEvidence
                          ? 'Trio segregation evidence'
                          : evidence?.source || 'Unknown source'}
                </Typography>
                <Stack direction="row" gap={0.5} flexWrap="wrap" justifyContent="flex-end">
                    {isPhenotypeEvidence ? (
                        <Chip
                            size="small"
                            color="success"
                            variant="outlined"
                            label="HPO gene prior"
                        />
                    ) : null}
                    {isTrioEvidence ? (
                        <>
                            <Chip
                                size="small"
                                color="info"
                                variant="outlined"
                                label="Trio contribution"
                            />
                            <Chip
                                size="small"
                                color={trioContribution?.applied ? 'primary' : 'default'}
                                variant="outlined"
                                label={trioContributionStateLabel(trioContribution)}
                            />
                        </>
                    ) : null}
                    <Chip
                        size="small"
                        variant="outlined"
                        color={evidenceTone(evidence?.applicability)}
                        label={formatStatus(evidenceStatus)}
                    />
                    {assemblyLabel ? (
                        <Chip
                            size="small"
                            variant="outlined"
                            color={
                                assemblyPresentation.kind === 'PROJECTION_FAILURE'
                                    ? 'warning'
                                    : 'default'
                            }
                            label={assemblyLabel}
                        />
                    ) : null}
                    {assemblyPresentation.kind === 'VERIFIED_PROJECTED' ? (
                        <Chip
                            size="small"
                            variant="outlined"
                            color="success"
                            label="Projection: verified"
                        />
                    ) : null}
                </Stack>
            </Stack>
            <Typography variant="body2">
                {evidence?.label || evidence?.evidenceType || '-'}
            </Typography>
            <EvidenceDetailLine label="Evidence source" value={evidenceSourceLabel(evidence)} />
            {provenanceLines.length ? (
                <Box className="cv-analysis-evidence-provenance">
                    <Typography variant="caption">Evidence provenance</Typography>
                    <Box className="cv-analysis-evidence-provenance-lines">
                        {provenanceLines.map(([label, value]) => (
                            <EvidenceDetailLine
                                key={`${label}-${value}`}
                                label={label}
                                value={value}
                            />
                        ))}
                    </Box>
                </Box>
            ) : null}
            {isPhenotypeEvidence ? (
                <>
                    <EvidenceDetailLine label="Gene" value={phenotypeDetails.geneSymbol} />
                    <EvidenceDetailLine
                        label="Phenotype score"
                        value={
                            phenotypeDetails.phenotypeScore != null
                                ? formatScore(phenotypeDetails.phenotypeScore)
                                : null
                        }
                    />
                    <EvidenceDetailLine
                        label="Gene prior rank"
                        value={phenotypeDetails.phenotypeRank}
                    />
                    <EvidenceDetailLine
                        label="Matched HPO terms"
                        value={listDisplay(phenotypeDetails.matchedHpoTerms)}
                    />
                    <EvidenceDetailLine
                        label="Score boost"
                        value={
                            phenotypeDetails.scoreBoost != null
                                ? formatContribution(phenotypeDetails.scoreBoost)
                                : null
                        }
                    />
                    <EvidenceDetailLine
                        label="Prior evidence"
                        value={listDisplay(phenotypeDetails.evidence)}
                    />
                </>
            ) : null}
            {isTrioEvidence ? (
                <>
                    <EvidenceDetailLine label="Inheritance candidate" value={trioDetails.model} />
                    <EvidenceDetailLine label="Confidence" value={trioDetails.confidence} />
                    <EvidenceDetailLine
                        label="Parental origin"
                        value={trioDetails.parentalOrigin}
                    />
                    <EvidenceDetailLine
                        label="Proband genotype"
                        value={trioDetails.probandGenotype}
                    />
                    <EvidenceDetailLine
                        label="Father genotype"
                        value={trioDetails.fatherGenotype}
                    />
                    <EvidenceDetailLine
                        label="Mother genotype"
                        value={trioDetails.motherGenotype}
                    />
                    <EvidenceDetailLine
                        label="Partner variants"
                        value={listDisplay(trioDetails.partnerVariantIds)}
                    />
                    <EvidenceDetailLine
                        label="Warnings"
                        value={listDisplay(trioDetails.warnings)}
                    />
                    <EvidenceDetailLine label="Ranking effect" value={trioDetails.rankingEffect} />
                    <EvidenceDetailLine
                        label="Hard gate policy"
                        value={trioDetails.hardGatePolicy}
                    />
                    <EvidenceDetailLine
                        label="Weighted ranking"
                        value={trioContributionSummary(trioContribution)}
                    />
                    <EvidenceDetailLine
                        label="Contribution direction"
                        value={trioContribution?.direction}
                    />
                    <EvidenceDetailLine
                        label="Contribution level"
                        value={trioContribution?.level}
                    />
                    <EvidenceDetailLine
                        label="Contribution score"
                        value={
                            trioContributionValue != null
                                ? formatContribution(trioContributionValue)
                                : null
                        }
                    />
                    <EvidenceDetailLine
                        label="Contribution cap"
                        value={
                            trioContribution?.maxContributionCap != null
                                ? formatContribution(trioContribution.maxContributionCap)
                                : null
                        }
                    />
                    <EvidenceDetailLine
                        label="Not applied reason"
                        value={trioContribution?.notAppliedReason}
                    />
                    <EvidenceDetailLine
                        label="Hard-gate protected"
                        value={
                            trioContribution?.hardGateProtected === true
                                ? 'Yes'
                                : trioContribution
                                  ? 'No'
                                  : null
                        }
                    />
                    <EvidenceDetailLine
                        label="Contribution explanation"
                        value={trioContribution?.explanation}
                    />
                    {trioContribution ? (
                        <Box
                            className="cv-analysis-trio-contribution-summary"
                            data-testid="trio-ranking-contribution"
                        >
                            <Stack direction="row" gap={0.75} flexWrap="wrap">
                                <Chip
                                    size="small"
                                    variant="outlined"
                                    color={trioContribution.applied ? 'primary' : 'default'}
                                    label={trioContributionStateLabel(trioContribution)}
                                />
                                {trioContribution.direction ? (
                                    <Chip
                                        size="small"
                                        variant="outlined"
                                        label={formatStatus(trioContribution.direction)}
                                    />
                                ) : null}
                                {trioContributionValue != null ? (
                                    <Chip
                                        size="small"
                                        variant="outlined"
                                        label={`Score ${formatContribution(trioContributionValue)}`}
                                    />
                                ) : null}
                                {trioContribution.maxContributionCap != null ? (
                                    <Chip
                                        size="small"
                                        variant="outlined"
                                        label={`Cap ${formatContribution(trioContribution.maxContributionCap)}`}
                                    />
                                ) : null}
                            </Stack>
                            {trioContribution.enabled === false ? (
                                <Typography
                                    variant="caption"
                                    color="text.secondary"
                                    data-testid="trio-ranking-disabled-message"
                                >
                                    Trio weighted ranking is disabled. Trio evidence is shown as
                                    explanation only.
                                </Typography>
                            ) : null}
                            {trioContribution.hardGateProtected === true ? (
                                <Typography
                                    variant="caption"
                                    color="text.secondary"
                                    data-testid="trio-hard-gate-policy"
                                >
                                    Trio support did not override hard gates.
                                </Typography>
                            ) : null}
                        </Box>
                    ) : null}
                </>
            ) : null}
            <EvidenceDetailLine label="Status" value={evidenceStatus} />
            <EvidenceDetailLine label="Value" value={evidence?.details?.value} />
            <EvidenceDetailLine label="Match" value={evidence?.details?.matchType} />
            <EvidenceDetailLine label="Checked" value={evidence?.details?.timestamp} />
            {evidence?.rawScore != null ? (
                <Typography variant="caption" className="cv-analysis-evidence-score">
                    Score {formatScore(evidence.rawScore)}
                </Typography>
            ) : null}
            {statusExplanation ? (
                <Alert severity={evidence?.applicability === 'CONFLICTING' ? 'warning' : 'info'}>
                    {statusExplanation}
                </Alert>
            ) : null}
            {direction || trackName || outputType ? (
                <Typography variant="caption" className="cv-analysis-mechanistic-note">
                    {[outputType, trackName, direction].filter(Boolean).join(' / ')}
                </Typography>
            ) : null}
            <Typography variant="caption" color="text.secondary">
                {evidence?.summary || 'No additional summary.'}
            </Typography>
            {isPhenotypeEvidence ? (
                <Alert severity="info" className="cv-analysis-phenotype-safety-note">
                    Phenotype support is used for candidate prioritization only. It is not a
                    diagnosis. Absence of phenotype support does not indicate benign status.
                </Alert>
            ) : null}
            {isTrioEvidence ? (
                <Alert severity="info" className="cv-analysis-phenotype-safety-note">
                    Ranking support only; not a diagnosis. Trio contribution does not override hard
                    gates. Evidence rawScore remains separate from ranking contribution.
                </Alert>
            ) : null}
            <EvidenceDetailLine label="Interpretation" value={evidence?.details?.interpretation} />
        </Box>
    );
};

const TranscriptAnnotationPanel = ({ variant }) => {
    const annotations = [...(variant?.transcriptAnnotations || [])].sort(
        (left, right) => transcriptSortWeight(left) - transcriptSortWeight(right),
    );
    if (!annotations.length) {
        return (
            <Box className="cv-analysis-transcript-panel">
                <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
                    <Typography variant="subtitle2">Transcript annotations</Typography>
                    <Chip size="small" variant="outlined" label="Not available" />
                </Stack>
                <Typography variant="caption" color="text.secondary">
                    No transcript annotation was returned for this variant.
                </Typography>
            </Box>
        );
    }
    return (
        <Box className="cv-analysis-transcript-panel">
            <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
                <Typography variant="subtitle2">Transcript annotations</Typography>
                <Typography variant="caption" color="text.secondary">
                    MANE/canonical transcripts are shown first.
                </Typography>
            </Stack>
            <Box className="cv-analysis-transcript-list">
                {annotations.map((annotation, index) => {
                    const source = transcriptSourceLabel(annotation?.source);
                    const status = annotation?.status || 'UNKNOWN';
                    const isLofteeHc =
                        String(annotation?.lof || '').toUpperCase() === 'HC' &&
                        isProteinCodingTranscriptAnnotation(annotation) &&
                        hasHighImpactTranscriptConsequence(annotation);
                    const statusExplanation = transcriptStatusExplanation(annotation);
                    return (
                        <Box
                            className="cv-analysis-transcript-card"
                            key={[
                                annotation?.transcriptId,
                                annotation?.transcriptAccession,
                                annotation?.source,
                                index,
                            ]
                                .filter(Boolean)
                                .join('|')}
                        >
                            <Stack
                                direction="row"
                                alignItems="center"
                                justifyContent="space-between"
                                gap={1}
                            >
                                <Typography variant="subtitle2">
                                    {displayValue(
                                        annotation?.geneSymbol || annotation?.geneId,
                                        'Transcript',
                                    )}
                                </Typography>
                                <Stack direction="row" gap={0.5} flexWrap="wrap">
                                    {isAffirmative(annotation?.maneSelect) ? (
                                        <Chip size="small" color="primary" label="MANE Select" />
                                    ) : null}
                                    {isAffirmative(annotation?.manePlusClinical) ? (
                                        <Chip
                                            size="small"
                                            color="primary"
                                            variant="outlined"
                                            label="MANE Plus Clinical"
                                        />
                                    ) : null}
                                    {isAffirmative(annotation?.canonical) ? (
                                        <Chip size="small" variant="outlined" label="Canonical" />
                                    ) : null}
                                    {isLofteeHc ? (
                                        <Chip
                                            size="small"
                                            color="warning"
                                            variant="outlined"
                                            label="LOFTEE HC candidate support"
                                        />
                                    ) : null}
                                    <Chip
                                        size="small"
                                        variant="outlined"
                                        color={transcriptStatusTone(status)}
                                        label={formatStatus(status)}
                                    />
                                </Stack>
                            </Stack>
                            <Typography variant="caption" className="cv-analysis-transcript-source">
                                {source}
                                {annotation?.version ? ` / ${annotation.version}` : ''}
                            </Typography>
                            <Box className="cv-analysis-transcript-fields">
                                <span>
                                    <strong>Transcript</strong>
                                    {displayValue(
                                        annotation?.transcriptId || annotation?.transcriptAccession,
                                    )}
                                </span>
                                <span>
                                    <strong>Source</strong>
                                    {displayValue(annotation?.transcriptSource)}
                                </span>
                                <span>
                                    <strong>Consequence</strong>
                                    {displayValue(annotation?.consequence)}
                                </span>
                                <span>
                                    <strong>Impact</strong>
                                    {displayValue(annotation?.impact)}
                                </span>
                                <span>
                                    <strong>HGVSc</strong>
                                    {displayValue(annotation?.hgvsc || annotation?.hgvsCoding)}
                                </span>
                                <span>
                                    <strong>HGVSp</strong>
                                    {displayValue(annotation?.hgvsp || annotation?.hgvsProtein)}
                                </span>
                                <span>
                                    <strong>LoF</strong>
                                    {displayValue(annotation?.lof)}
                                </span>
                                <span>
                                    <strong>LoF filter</strong>
                                    {displayValue(annotation?.lofFilter)}
                                </span>
                                <span>
                                    <strong>LoF flags</strong>
                                    {displayValue(annotation?.lofFlags)}
                                </span>
                                <span>
                                    <strong>Checked</strong>
                                    {displayValue(annotation?.timestamp)}
                                </span>
                            </Box>
                            {statusExplanation ? (
                                <Alert severity={transcriptStatusSeverity(status)}>
                                    {statusExplanation}
                                </Alert>
                            ) : null}
                            {isLofteeHc ? (
                                <Typography variant="caption" color="text.secondary">
                                    LOFTEE HC is displayed as high-confidence LoF candidate support,
                                    not as a standalone clinical pathogenic conclusion.
                                </Typography>
                            ) : null}
                        </Box>
                    );
                })}
            </Box>
        </Box>
    );
};

const AcmgReadyPanel = ({ variant }) => {
    const suggestions = variant?.acmgEvidenceSuggestions || [];
    const suggestedCount = suggestions.filter(
        (item) => String(item?.status || '').toUpperCase() === 'SUGGESTED',
    ).length;
    const manualReviewCount = suggestions.filter((item) => item?.manualReviewRequired).length;
    const codes = acmgCodeList(suggestions);
    return (
        <Box className="cv-analysis-acmg-panel">
            <Stack direction="row" alignItems="center" justifyContent="space-between" gap={1}>
                <Typography variant="subtitle2">ACMG-ready evidence suggestions</Typography>
                <Stack direction="row" gap={0.75} flexWrap="wrap" justifyContent="flex-end">
                    <Chip
                        size="small"
                        color={suggestedCount ? 'primary' : 'default'}
                        variant={suggestedCount ? 'filled' : 'outlined'}
                        label={`${formatNumber(suggestedCount, '0')} suggested`}
                    />
                    <Chip
                        size="small"
                        color={manualReviewCount ? 'warning' : 'default'}
                        variant="outlined"
                        label={`${formatNumber(manualReviewCount, '0')} manual review`}
                    />
                </Stack>
            </Stack>
            <Alert severity="info" className="cv-analysis-acmg-safety-note">
                This area shows ACMG/ClinGen evidence-direction suggestions only, not a final
                clinical classification. PVS1_candidate and PP3_candidate remain candidate evidence
                directions requiring manual review. NO_RECORD is not AF=0; UNAVAILABLE is not low
                risk.
            </Alert>
            {codes.length ? (
                <Typography variant="caption" color="text.secondary">
                    Candidate codes: {codes.join(', ')}
                </Typography>
            ) : null}
            {suggestions.length ? (
                <Box className="cv-analysis-acmg-list">
                    {suggestions.map((suggestion, index) => {
                        const status = suggestion?.status || 'NOT_EVALUATED';
                        const warnings = suggestion?.warnings || [];
                        return (
                            <Box
                                className={`cv-analysis-acmg-card is-${String(status).toLowerCase()}`}
                                key={acmgSuggestionKey(suggestion, index)}
                            >
                                <Stack
                                    direction="row"
                                    alignItems="center"
                                    justifyContent="space-between"
                                    gap={1}
                                >
                                    <Stack direction="row" gap={0.75} flexWrap="wrap">
                                        <Chip
                                            size="small"
                                            color={acmgSuggestionStatusTone(status)}
                                            variant={
                                                String(status).toUpperCase() === 'SUGGESTED'
                                                    ? 'filled'
                                                    : 'outlined'
                                            }
                                            label={formatStatus(status)}
                                        />
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            label={displayValue(suggestion?.code, '-')}
                                        />
                                        {suggestion?.manualReviewRequired ? (
                                            <Chip
                                                size="small"
                                                color="warning"
                                                variant="outlined"
                                                label="Manual review required"
                                            />
                                        ) : null}
                                    </Stack>
                                    <Typography variant="caption" color="text.secondary">
                                        Rule {displayValue(suggestion?.ruleVersion, '-')}
                                    </Typography>
                                </Stack>
                                <Box className="cv-analysis-acmg-fields">
                                    <span>
                                        <strong>Strength</strong>
                                        {displayValue(suggestion?.strength, '-')}
                                    </span>
                                    <span>
                                        <strong>Status</strong>
                                        {displayValue(status, '-')}
                                    </span>
                                    <span>
                                        <strong>Source evidence</strong>
                                        {displayValue(suggestion?.sourceEvidence, '-')}
                                    </span>
                                    <span>
                                        <strong>Manual review</strong>
                                        {suggestion?.manualReviewRequired ? 'Required' : '-'}
                                    </span>
                                </Box>
                                <Typography variant="caption" color="text.secondary">
                                    {displayValue(
                                        suggestion?.explanation,
                                        'No explanation returned.',
                                    )}
                                </Typography>
                                {warnings.length ? (
                                    <Stack gap={0.75}>
                                        {warnings.map((warning) => (
                                            <Alert
                                                key={`${suggestion?.code || index}-${warning}`}
                                                severity="warning"
                                            >
                                                {warning}
                                            </Alert>
                                        ))}
                                    </Stack>
                                ) : null}
                            </Box>
                        );
                    })}
                </Box>
            ) : (
                <Typography variant="caption" color="text.secondary">
                    No ACMG-ready evidence suggestion is available for this variant.
                </Typography>
            )}
        </Box>
    );
};

const ANALYSIS_VCF_CALL_STATUS_COPY = {
    MATCHED: {
        label: 'Matched',
        color: 'success',
        message: 'One exact candidate allele record was found in the actual analysis VCF.',
    },
    NOT_FOUND: {
        label: 'Not found',
        color: 'info',
        message:
            'The exact candidate allele was not found in the actual analysis VCF; this does not mean homozygous reference.',
    },
    AMBIGUOUS: {
        label: 'Ambiguous',
        color: 'warning',
        message:
            'More than one exact candidate allele record was found, so no record-level call was selected.',
    },
};

const AnalysisVcfCallContextCard = ({ context }) => {
    if (!context) return null;
    const status = ANALYSIS_VCF_CALL_STATUS_COPY[context.matchStatus];
    const phase = [
        context.phasingStatus ? formatStatus(context.phasingStatus) : null,
        context.phaseSetToken ? `set ${context.phaseSetToken}` : null,
    ]
        .filter(Boolean)
        .join(' / ');
    const callFields = [
        ['Candidate-relative GT', context.candidateRelativeGenotype],
        ['Target allele call', context.targetAlleleCall && formatStatus(context.targetAlleleCall)],
        [
            'DP / GQ',
            context.depth != null || context.genotypeQuality != null
                ? `${formatNumber(context.depth)} / ${formatNumber(context.genotypeQuality)}`
                : null,
        ],
        [
            'Reference / target AD',
            context.referenceDepth != null || context.targetAlleleDepth != null
                ? `${formatNumber(context.referenceDepth)} / ${formatNumber(
                      context.targetAlleleDepth,
                  )}`
                : null,
        ],
        [
            'Allele balance',
            context.alleleBalance == null ? null : formatPercent(context.alleleBalance),
        ],
        ['VCF filter', context.filterEvaluation && formatStatus(context.filterEvaluation)],
        ['Phase', phase || null],
        [
            'Target ALT / record',
            context.targetAlleleIndex != null || context.matchedRecordOrdinal != null
                ? `${formatNumber(context.targetAlleleIndex)} / ${formatNumber(
                      context.matchedRecordOrdinal,
                  )}`
                : null,
        ],
    ];
    return (
        <Box className="cv-analysis-score-explanation" data-testid="analysis-vcf-call-context">
            <Stack gap={1.25}>
                <Stack direction="row" alignItems="center" gap={0.75} flexWrap="wrap">
                    <Typography variant="subtitle2">Actual analysis VCF call</Typography>
                    <Chip
                        size="small"
                        color={status.color}
                        variant="outlined"
                        data-testid="analysis-vcf-call-match-status"
                        label={status.label}
                    />
                    <Chip size="small" variant="outlined" label={context.assembly} />
                </Stack>
                <Typography variant="body2">{status.message}</Typography>
                {context.matchStatus === 'MATCHED' ? (
                    <Box className="cv-analysis-detail-grid">
                        {callFields.map(([label, value]) => (
                            <Box className="cv-analysis-detail-cell" key={label}>
                                <Typography variant="caption">{label}</Typography>
                                <Typography variant="body2">{value || 'Unavailable'}</Typography>
                            </Box>
                        ))}
                    </Box>
                ) : (
                    <Typography variant="caption" color="text.secondary">
                        Exact matching records: {formatNumber(context.matchingRecordCount, '0')}
                    </Typography>
                )}
                {context.missingFields.length ? (
                    <Alert severity="info" variant="outlined">
                        Missing call fields: {context.missingFields.map(formatStatus).join(', ')}
                    </Alert>
                ) : null}
                {context.invalidFields.length ? (
                    <Alert severity="warning" variant="outlined">
                        Invalid call fields: {context.invalidFields.map(formatStatus).join(', ')}
                    </Alert>
                ) : null}
                <Typography variant="caption" color="text.secondary">
                    Read-only facts from the actual VCF used for this analysis. Candidate-relative
                    genotype labels preserve other ALT alleles. These fields were not used for
                    ranking and do not change candidate order or clinical interpretation.
                </Typography>
            </Stack>
        </Box>
    );
};

const ReasonListSection = ({ title, items, severity = 'info' }) =>
    Array.isArray(items) && items.length ? (
        <Box className="cv-analysis-reason-section">
            <Typography variant="subtitle2">{title}</Typography>
            <Stack gap={0.75}>
                {items.map((item) => (
                    <Alert key={`${title}-${item}`} severity={severity}>
                        {item}
                    </Alert>
                ))}
            </Stack>
        </Box>
    ) : null;

const VariantAnalysisDrawer = ({
    open,
    onClose,
    onVariantNavigate,
    onVariantPreview,
    visualVariantSelection,
}) => {
    const fileInputRef = useRef(null);
    const selectedFileRef = useRef(null);
    const detailSectionRef = useRef(null);
    const activeAnalysisIdRef = useRef('');
    const removedAnalysisIdsRef = useRef(new Set());
    const visualSelectionHandledRef = useRef('');
    const visualSelectionSearchRef = useRef('');
    const preflightRequestIdRef = useRef(0);
    const drawerOpenRef = useRef(open);
    const detailRequestRef = useRef({ sequence: 0, key: '', controller: null });
    const rankingExplanationCacheRef = useRef(new Map());
    const rankingExplanationRequestRef = useRef({ sequence: 0, key: '', controller: null });
    const selectedRankingExplanationKeyRef = useRef('');
    const [selectedFile, setSelectedFile] = useState(null);
    const [historyMatches, setHistoryMatches] = useState(null);
    const [inputGenomeAssembly, setInputGenomeAssembly] = useState('GRCh38');
    const [assemblySelectedManually, setAssemblySelectedManually] = useState(false);
    const [analysisMode, setAnalysisMode] = useState('WES');
    const [analysisScope, setAnalysisScope] = useState('STANDARD');
    const [sampleId, setSampleId] = useState('');
    const [trioInputMode, setTrioInputMode] = useState(TRIO_INPUT_MODE_DISABLED);
    const [trioProbandSampleId, setTrioProbandSampleId] = useState('');
    const [trioFatherSampleId, setTrioFatherSampleId] = useState('');
    const [trioMotherSampleId, setTrioMotherSampleId] = useState('');
    const [trioProbandSex, setTrioProbandSex] = useState('');
    const [trioFatherSex, setTrioFatherSex] = useState('');
    const [trioMotherSex, setTrioMotherSex] = useState('');
    const [trioProbandAffected, setTrioProbandAffected] = useState('');
    const [trioFatherAffected, setTrioFatherAffected] = useState('');
    const [trioMotherAffected, setTrioMotherAffected] = useState('');
    const [hpoTermsInput, setHpoTermsInput] = useState('');
    const [excludedHpoTermsInput, setExcludedHpoTermsInput] = useState('');
    const [detectedSampleIds, setDetectedSampleIds] = useState([]);
    const [sampleDetectionNote, setSampleDetectionNote] = useState('');
    const [primarySampleDetection, setPrimarySampleDetection] = useState(null);
    const [sampleDetectionLoading, setSampleDetectionLoading] = useState('');
    const [candidateLimit, setCandidateLimit] = useState(20);
    const [job, setJob] = useState(null);
    const [restoredHistoryId, setRestoredHistoryId] = useState('');
    const [acmgAnalysisId, setAcmgAnalysisId] = useState('');
    const [restoreAnalysisId, setRestoreAnalysisId] = useState('');
    const [analysisEvents, setAnalysisEvents] = useState([]);
    const [variantsPage, setVariantsPage] = useState(EMPTY_PAGE);
    const [historyRead, setHistoryRead] = useState(null);
    const [candidateRead, setCandidateRead] = useState(null);
    const candidateReadSequence = useRef(0);
    const [variantPageNumber, setVariantPageNumber] = useState(1);
    const [variantPageSize, setVariantPageSize] = useState(DEFAULT_VARIANT_PAGE_SIZE);
    const [variantView, setVariantView] = useState(VARIANT_VIEW_CANDIDATES);
    const [variantFilter, setVariantFilter] = useState(VARIANT_FILTER_ALL);
    const [variantBucket, setVariantBucket] = useState(VARIANT_BUCKET_ALL);
    const [summaryOnlyFilter, setSummaryOnlyFilter] = useState(BOOLEAN_FILTER_ALL);
    const [largeFileModeFilter, setLargeFileModeFilter] = useState(BOOLEAN_FILTER_ALL);
    const [skippedAnnotationFilter, setSkippedAnnotationFilter] = useState(BOOLEAN_FILTER_ALL);
    const [acmgCodeFilter, setAcmgCodeFilter] = useState(ACMG_CODE_ALL);
    const [acmgOnlySuggested, setAcmgOnlySuggested] = useState(false);
    const [acmgManualReviewRequired, setAcmgManualReviewRequired] = useState(false);
    const [variantSearch, setVariantSearch] = useState('');
    const [debouncedVariantSearch, setDebouncedVariantSearch] = useState('');
    const [selectedVariant, setSelectedVariant] = useState(null);
    const [detailLoadingCandidateId, setDetailLoadingCandidateId] = useState('');
    const [detailTab, setDetailTab] = useState(DETAIL_TAB_OVERVIEW);
    const [rankingExplanation, setRankingExplanation] = useState(null);
    const [rankingExplanationLoading, setRankingExplanationLoading] = useState(false);
    const [rankingExplanationErrorCode, setRankingExplanationErrorCode] = useState('');
    const [providersExpanded, setProvidersExpanded] = useState(false);
    const [advancedFiltersExpanded, setAdvancedFiltersExpanded] = useState(false);
    const [enhancement, setEnhancement] = useState(null);
    const [providerStatus, setProviderStatus] = useState(null);
    const [providerStatusError, setProviderStatusError] = useState('');
    const [preflightStatus, setPreflightStatus] = useState(null);
    const [preflightLoading, setPreflightLoading] = useState(false);
    const [preflightError, setPreflightError] = useState('');
    const [phenotypeGenePriorLookup, setPhenotypeGenePriorLookup] = useState(null);
    const [phenotypeGenePriorLookupLoading, setPhenotypeGenePriorLookupLoading] = useState(false);
    const [pollingNotice, setPollingNotice] = useState('');
    const [busy, setBusy] = useState(false);
    const [exportingFormat, setExportingFormat] = useState('');
    const selectedCandidateId = candidateKey(selectedVariant);
    const selectedCanonicalVariantId = canonicalKey(selectedVariant);

    drawerOpenRef.current = open;

    const cancelDetailRequest = useCallback(() => {
        const current = detailRequestRef.current;
        current.controller?.abort();
        detailRequestRef.current = {
            sequence: current.sequence + 1,
            key: '',
            controller: null,
        };
    }, []);

    const cancelRankingExplanationRequest = useCallback(() => {
        const current = rankingExplanationRequestRef.current;
        current.controller?.abort();
        rankingExplanationRequestRef.current = {
            sequence: current.sequence + 1,
            key: '',
            controller: null,
        };
        setRankingExplanationLoading(false);
    }, []);

    const clearRankingExplanationView = useCallback(() => {
        setRankingExplanation(null);
        setRankingExplanationLoading(false);
        setRankingExplanationErrorCode('');
    }, []);

    const clearVariantSelection = useCallback(() => {
        cancelDetailRequest();
        cancelRankingExplanationRequest();
        selectedRankingExplanationKeyRef.current = '';
        clearRankingExplanationView();
        setSelectedVariant(null);
        setDetailLoadingCandidateId('');
        setDetailTab(DETAIL_TAB_OVERVIEW);
    }, [cancelDetailRequest, cancelRankingExplanationRequest, clearRankingExplanationView]);

    const handleDrawerClose = useCallback(() => {
        setAcmgAnalysisId('');
        cancelDetailRequest();
        cancelRankingExplanationRequest();
        rankingExplanationCacheRef.current.clear();
        selectedRankingExplanationKeyRef.current = '';
        clearRankingExplanationView();
        onClose();
    }, [
        cancelDetailRequest,
        cancelRankingExplanationRequest,
        clearRankingExplanationView,
        onClose,
    ]);

    useEffect(() => {
        if (!open) {
            cancelDetailRequest();
            cancelRankingExplanationRequest();
            rankingExplanationCacheRef.current.clear();
            selectedRankingExplanationKeyRef.current = '';
            clearRankingExplanationView();
            return undefined;
        }
        const handleKeyDown = (event) => {
            if (event.key === 'Escape' && !event.defaultPrevented) handleDrawerClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [
        cancelDetailRequest,
        cancelRankingExplanationRequest,
        clearRankingExplanationView,
        handleDrawerClose,
        open,
    ]);

    useEffect(
        () => () => {
            detailRequestRef.current.controller?.abort();
            rankingExplanationRequestRef.current.controller?.abort();
        },
        [],
    );
    const [error, setError] = useState('');

    const variantQueryOptions = useMemo(
        () =>
            buildVariantQueryOptions(
                variantFilter,
                debouncedVariantSearch,
                acmgCodeFilter,
                acmgOnlySuggested,
                acmgManualReviewRequired,
                variantBucket,
                summaryOnlyFilter,
                largeFileModeFilter,
                skippedAnnotationFilter,
            ),
        [
            acmgCodeFilter,
            acmgManualReviewRequired,
            acmgOnlySuggested,
            debouncedVariantSearch,
            largeFileModeFilter,
            skippedAnnotationFilter,
            summaryOnlyFilter,
            variantBucket,
            variantFilter,
        ],
    );
    const trioEnabled = trioInputMode !== TRIO_INPUT_MODE_DISABLED;
    const effectiveTrioProbandSampleId = String(trioProbandSampleId || sampleId || '').trim();
    const sampleIdChoices = useMemo(
        () =>
            mergeSampleIdChoices(
                detectedSampleIds,
                extractSampleIdChoices(error),
                extractSampleIdChoices(job?.errorMessage),
            ),
        [detectedSampleIds, error, job?.errorMessage],
    );
    const trioProbandSampleChoices = useMemo(
        () =>
            mergeSampleIdChoices(
                detectedSampleIds,
                effectiveTrioProbandSampleId ? [effectiveTrioProbandSampleId] : [],
            ),
        [detectedSampleIds, effectiveTrioProbandSampleId],
    );
    const trioFatherSampleChoices = useMemo(
        () =>
            mergeSampleIdChoices(detectedSampleIds, trioFatherSampleId ? [trioFatherSampleId] : []),
        [detectedSampleIds, trioFatherSampleId],
    );
    const trioMotherSampleChoices = useMemo(
        () =>
            mergeSampleIdChoices(detectedSampleIds, trioMotherSampleId ? [trioMotherSampleId] : []),
        [detectedSampleIds, trioMotherSampleId],
    );
    const parsedHpoTerms = useMemo(() => parseHpoTermsInput(hpoTermsInput), [hpoTermsInput]);
    const parsedExcludedHpoTerms = useMemo(
        () => parseHpoTermsInput(excludedHpoTermsInput),
        [excludedHpoTermsInput],
    );
    const invalidHpoTerms = useMemo(
        () => parsedHpoTerms.filter((term) => !normalizeHpoId(term)),
        [parsedHpoTerms],
    );
    const invalidExcludedHpoTerms = useMemo(
        () => parsedExcludedHpoTerms.filter((term) => !normalizeHpoId(term)),
        [parsedExcludedHpoTerms],
    );
    const conflictingHpoTerms = useMemo(() => {
        const present = new Set(parsedHpoTerms.map((term) => normalizeHpoId(term)).filter(Boolean));
        return parsedExcludedHpoTerms
            .map((term) => normalizeHpoId(term))
            .filter((term) => term && present.has(term));
    }, [parsedExcludedHpoTerms, parsedHpoTerms]);
    const hpoProvenance = useMemo(() => hpoProvenanceForTerms(parsedHpoTerms), [parsedHpoTerms]);
    const phenotypeLookupGene = useMemo(
        () => geneSymbolCandidateFromText(debouncedVariantSearch),
        [debouncedVariantSearch],
    );
    const hasJobPhenotypeInput = hasPhenotypeSummaryInput(job?.phenotypeSummary);
    const inputGenomeAssemblySupported = SUPPORTED_GENOME_ASSEMBLIES.includes(inputGenomeAssembly);
    const analysisInputError = useMemo(() => {
        if (!selectedFile) return '';
        if (primarySampleDetection?.errorCode) {
            return 'VCF header validation failed. Select a readable VCF or VCF.GZ file before analysis.';
        }
        if (primarySampleDetection?.assemblyConfidence === 'CONFLICTING_EVIDENCE') {
            return 'The VCF header contains conflicting GRCh37/GRCh38 evidence. Correct the source file before analysis.';
        }
        if (primarySampleDetection?.assemblyConfirmationRequired && !assemblySelectedManually) {
            return 'Confirm the input build (GRCh37 or GRCh38) before analysis.';
        }
        if (!String(sampleId || '').trim()) {
            return 'Select or enter the VCF sample ID before analysis.';
        }
        if (invalidHpoTerms.length) {
            return 'HPO terms must use the format HP: followed by seven digits.';
        }
        if (invalidExcludedHpoTerms.length) {
            return 'Explicitly absent HPO terms must use the format HP: followed by seven digits.';
        }
        if (conflictingHpoTerms.length) {
            return 'The same HPO term cannot be marked both present and explicitly absent.';
        }
        return '';
    }, [
        assemblySelectedManually,
        invalidHpoTerms.length,
        invalidExcludedHpoTerms.length,
        conflictingHpoTerms.length,
        primarySampleDetection?.assemblyConfirmationRequired,
        primarySampleDetection?.assemblyConfidence,
        primarySampleDetection?.errorCode,
        sampleId,
        selectedFile,
    ]);
    const trioValidationError = useMemo(() => {
        if (!trioEnabled) return '';
        if (trioInputMode !== TRIO_INPUT_MODE_JOINT) {
            return 'Family analysis requires one joint VCF containing the proband, father and mother samples.';
        }
        if (!selectedFile) {
            return 'Primary/proband VCF is required for trio evidence.';
        }
        if (
            !effectiveTrioProbandSampleId ||
            !trioFatherSampleId.trim() ||
            !trioMotherSampleId.trim()
        ) {
            return 'Trio evidence requires proband, father and mother sample IDs.';
        }
        if (
            hasDuplicateRoleMapping(
                effectiveTrioProbandSampleId,
                trioFatherSampleId,
                trioMotherSampleId,
            )
        ) {
            return 'Proband, father and mother sample IDs must be distinct.';
        }
        if (
            ![trioProbandSex, trioFatherSex, trioMotherSex].every((value) =>
                ['MALE', 'FEMALE'].includes(value),
            )
        ) {
            return 'Explicit sex is required for proband, father and mother.';
        }
        if (trioFatherSex !== 'MALE' || trioMotherSex !== 'FEMALE') {
            return 'Father must be recorded as male and mother as female for a trio PED.';
        }
        if (
            ![trioProbandAffected, trioFatherAffected, trioMotherAffected].every(
                (value) => value === 'true' || value === 'false',
            )
        ) {
            return 'Explicit affected status is required for proband, father and mother.';
        }
        if (trioInputMode === TRIO_INPUT_MODE_JOINT && detectedSampleIds.length) {
            const missingRole = [
                ['proband', effectiveTrioProbandSampleId],
                ['father', trioFatherSampleId],
                ['mother', trioMotherSampleId],
            ].find(([, value]) => !detectedSampleIds.includes(String(value || '').trim()));
            if (missingRole) {
                return `The ${missingRole[0]} sample is not present in the detected joint VCF samples.`;
            }
        }
        return '';
    }, [
        detectedSampleIds,
        effectiveTrioProbandSampleId,
        selectedFile,
        trioEnabled,
        trioFatherAffected,
        trioFatherSampleId,
        trioFatherSex,
        trioInputMode,
        trioMotherSampleId,
        trioMotherAffected,
        trioMotherSex,
        trioProbandAffected,
        trioProbandSex,
    ]);

    const activateAnalysisJob = useCallback(
        (nextJob) => {
            const nextId = nextJob?.id || '';
            if (activeAnalysisIdRef.current !== nextId) {
                candidateReadSequence.current += 1;
                setCandidateRead(null);
                cancelDetailRequest();
                cancelRankingExplanationRequest();
                rankingExplanationCacheRef.current.clear();
                selectedRankingExplanationKeyRef.current = '';
                clearRankingExplanationView();
            }
            activeAnalysisIdRef.current = nextId;
            if (nextId) {
                removedAnalysisIdsRef.current.delete(nextId);
            }
            setJob(nextJob || null);
        },
        [cancelDetailRequest, cancelRankingExplanationRequest, clearRankingExplanationView],
    );

    const isAnalysisRequestCurrent = useCallback((analysisId) => {
        const normalizedId = String(analysisId || '');
        return (
            Boolean(normalizedId) &&
            activeAnalysisIdRef.current === normalizedId &&
            !removedAnalysisIdsRef.current.has(normalizedId)
        );
    }, []);

    const loadRankingExplanation = useCallback(
        async ({ force = false } = {}) => {
            const analysisId = String(job?.id || '');
            const candidateId = selectedCandidateId;
            if (!drawerOpenRef.current || !analysisId || !candidateId) return;
            const key = rankingExplanationCacheKey(analysisId, candidateId);
            selectedRankingExplanationKeyRef.current = key;
            if (!force && rankingExplanationCacheRef.current.has(key)) {
                const cached = rankingExplanationCacheRef.current.get(key);
                rankingExplanationCacheRef.current.delete(key);
                rankingExplanationCacheRef.current.set(key, cached);
                setRankingExplanation(cached);
                setRankingExplanationErrorCode('');
                setRankingExplanationLoading(false);
                return;
            }
            if (force) {
                rankingExplanationCacheRef.current.delete(key);
            }

            cancelRankingExplanationRequest();
            const controller = new AbortController();
            const sequence = rankingExplanationRequestRef.current.sequence + 1;
            rankingExplanationRequestRef.current = { sequence, key, controller };
            setRankingExplanation(null);
            setRankingExplanationErrorCode('');
            setRankingExplanationLoading(true);
            const requestIsCurrent = () => {
                const current = rankingExplanationRequestRef.current;
                return (
                    !controller.signal.aborted &&
                    drawerOpenRef.current &&
                    current.sequence === sequence &&
                    current.key === key &&
                    selectedRankingExplanationKeyRef.current === key &&
                    isAnalysisRequestCurrent(analysisId)
                );
            };
            try {
                const payload = await fetchVariantAnalysisCandidateRankingExplanation(
                    analysisId,
                    selectedVariant,
                    { signal: controller.signal },
                );
                if (!requestIsCurrent()) return;
                const safeExplanation = sanitizeRankingExplanation(
                    payload,
                    analysisId,
                    selectedVariant,
                );
                if (!safeExplanation) {
                    setRankingExplanationErrorCode('CONTRACT_MISMATCH');
                    return;
                }
                if (rankingExplanationCacheRef.current.size >= RANKING_EXPLANATION_CACHE_LIMIT) {
                    const oldestKey = rankingExplanationCacheRef.current.keys().next().value;
                    rankingExplanationCacheRef.current.delete(oldestKey);
                }
                rankingExplanationCacheRef.current.set(key, safeExplanation);
                setRankingExplanation(safeExplanation);
            } catch (requestError) {
                if (isCanceledRequest(requestError) || !requestIsCurrent()) return;
                if (isNotFoundError(requestError)) {
                    setRankingExplanationErrorCode('NOT_AVAILABLE');
                } else if (isRequestTimeout(requestError)) {
                    setRankingExplanationErrorCode('REQUEST_TIMEOUT');
                } else {
                    setRankingExplanationErrorCode('REQUEST_FAILED');
                }
            } finally {
                if (requestIsCurrent()) {
                    setRankingExplanationLoading(false);
                    rankingExplanationRequestRef.current = {
                        sequence,
                        key,
                        controller: null,
                    };
                }
            }
        },
        [
            cancelRankingExplanationRequest,
            isAnalysisRequestCurrent,
            job?.id,
            selectedCandidateId,
            selectedVariant,
        ],
    );

    const resetAnalysisState = useCallback(
        (removedAnalysisId = '') => {
            const normalizedId = String(removedAnalysisId || activeAnalysisIdRef.current || '');
            if (normalizedId) {
                removedAnalysisIdsRef.current.add(normalizedId);
            }
            cancelDetailRequest();
            cancelRankingExplanationRequest();
            rankingExplanationCacheRef.current.clear();
            selectedRankingExplanationKeyRef.current = '';
            clearRankingExplanationView();
            activeAnalysisIdRef.current = '';
            candidateReadSequence.current += 1;
            setCandidateRead(null);
            setJob(null);
            setRestoredHistoryId('');
            setAnalysisEvents([]);
            setVariantsPage(EMPTY_PAGE);
            setVariantPageNumber(1);
            setVariantPageSize(DEFAULT_VARIANT_PAGE_SIZE);
            setVariantView(VARIANT_VIEW_CANDIDATES);
            setVariantFilter(VARIANT_FILTER_ALL);
            setVariantBucket(VARIANT_BUCKET_ALL);
            setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
            setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
            setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
            setAcmgCodeFilter(ACMG_CODE_ALL);
            setAcmgOnlySuggested(false);
            setAcmgManualReviewRequired(false);
            setVariantSearch('');
            setDebouncedVariantSearch('');
            setSelectedVariant(null);
            setPhenotypeGenePriorLookup(null);
            setPhenotypeGenePriorLookupLoading(false);
            setDetailLoadingCandidateId('');
            setDetailTab(DETAIL_TAB_OVERVIEW);
            setAdvancedFiltersExpanded(false);
            setEnhancement(null);
            setPollingNotice('');
        },
        [cancelDetailRequest, cancelRankingExplanationRequest, clearRankingExplanationView],
    );

    const resetSubmitForm = useCallback(() => {
        selectedFileRef.current = null;
        setSelectedFile(null);
        setRestoredHistoryId('');
        setHistoryMatches(null);
        setInputGenomeAssembly('GRCh38');
        setAssemblySelectedManually(false);
        setAnalysisMode('WES');
        setAnalysisScope('STANDARD');
        setSampleId('');
        setTrioInputMode(TRIO_INPUT_MODE_DISABLED);
        setTrioProbandSampleId('');
        setTrioFatherSampleId('');
        setTrioMotherSampleId('');
        setTrioProbandSex('');
        setTrioFatherSex('');
        setTrioMotherSex('');
        setTrioProbandAffected('');
        setTrioFatherAffected('');
        setTrioMotherAffected('');
        setHpoTermsInput('');
        setExcludedHpoTermsInput('');
        setDetectedSampleIds([]);
        setSampleDetectionNote('');
        setPrimarySampleDetection(null);
        setSampleDetectionLoading('');
        if (fileInputRef.current) {
            fileInputRef.current.value = '';
        }
    }, []);

    const clearMissingAnalysisJob = useCallback(
        (message = jobNotFoundNotice) => {
            resetAnalysisState();
            setError(message);
        },
        [resetAnalysisState],
    );

    const handleSelectFile = useCallback((file) => {
        selectedFileRef.current = file || null;
        setSelectedFile(file || null);
        setRestoredHistoryId('');
        setHistoryMatches(null);
        setAssemblySelectedManually(false);
        setSampleId('');
        setTrioProbandSampleId('');
        setDetectedSampleIds([]);
        setSampleDetectionNote('');
        setPrimarySampleDetection(null);
        setError('');
        setPollingNotice('');
    }, []);

    const handleSelectSampleId = useCallback((value) => {
        setSampleId(value);
        setTrioProbandSampleId((current) => current || value);
        setError('');
    }, []);

    const applySampleDetection = useCallback(
        (role, result) => {
            const samples = Array.isArray(result?.samples) ? result.samples : [];
            const note = sampleDetectionMessage(result);
            setPrimarySampleDetection(result || null);
            setDetectedSampleIds(samples);
            setSampleDetectionNote(note);
            if (result?.assemblyHint && !result?.assemblyConfirmationRequired) {
                setInputGenomeAssembly(result.assemblyHint);
                setAssemblySelectedManually(false);
            }
            if (samples.length === 1) {
                handleSelectSampleId(samples[0]);
                setTrioProbandSampleId((current) => current || samples[0]);
            }
        },
        [handleSelectSampleId],
    );

    const detectSamplesForFile = useCallback(
        async (role, file) => {
            if (!file) return null;
            setSampleDetectionLoading(role);
            try {
                const result = await detectVariantAnalysisSamples(file);
                applySampleDetection(role, result || null);
                return result || null;
            } catch (detectionError) {
                const result = {
                    samples: [],
                    warnings: [],
                    errorCode: 'VCF_HEADER_READ_FAILED',
                    errorMessage:
                        detectionError?.response?.data?.message ||
                        detectionError?.message ||
                        'VCF header read failed.',
                };
                applySampleDetection(role, result);
                return result;
            } finally {
                setSampleDetectionLoading((current) => (current === role ? '' : current));
            }
        },
        [applySampleDetection],
    );

    useEffect(() => {
        if (!selectedFile) return;
        detectSamplesForFile('primary', selectedFile);
    }, [detectSamplesForFile, selectedFile]);

    useEffect(() => {
        const timer = window.setTimeout(() => {
            setVariantPageNumber(1);
            setDebouncedVariantSearch(variantSearch);
        }, 350);
        return () => window.clearTimeout(timer);
    }, [variantSearch]);

    useEffect(() => {
        cancelRankingExplanationRequest();
        const key =
            job?.id && selectedCandidateId
                ? rankingExplanationCacheKey(job.id, selectedCandidateId)
                : '';
        selectedRankingExplanationKeyRef.current = key;
        setRankingExplanation(key ? rankingExplanationCacheRef.current.get(key) || null : null);
        setRankingExplanationErrorCode('');
        if (selectedCandidateId) {
            setDetailTab(DETAIL_TAB_OVERVIEW);
        }
    }, [cancelRankingExplanationRequest, job?.id, selectedCandidateId]);

    const handleDetailTabChange = useCallback(
        (nextTab) => {
            setDetailTab(nextTab);
            if (nextTab === DETAIL_TAB_RANKING_EXPLANATION) {
                loadRankingExplanation();
            }
        },
        [loadRankingExplanation],
    );

    const handleRankingExplanationRetry = useCallback(() => {
        loadRankingExplanation({ force: true });
    }, [loadRankingExplanation]);

    const loadEvents = useCallback(
        async (analysisId) => {
            if (!analysisId) {
                setAnalysisEvents([]);
                return [];
            }
            if (!isAnalysisRequestCurrent(analysisId)) {
                return [];
            }
            try {
                const events = await fetchVariantAnalysisEvents(analysisId);
                if (!isAnalysisRequestCurrent(analysisId)) {
                    return events || [];
                }
                setAnalysisEvents(Array.isArray(events) ? events : []);
                return events || [];
            } catch {
                return [];
            }
        },
        [isAnalysisRequestCurrent],
    );

    const loadVariants = useCallback(
        async (analysisId, options = {}) => {
            if (!isAnalysisRequestCurrent(analysisId)) {
                return EMPTY_PAGE;
            }
            const {
                view = VARIANT_VIEW_CANDIDATES,
                filters = {},
                page = 1,
                pageSize = DEFAULT_VARIANT_PAGE_SIZE,
            } = options || {};
            const normalizedPage = Math.max(1, Number(page) || 1);
            const numericPageSize = Number(pageSize) || DEFAULT_VARIANT_PAGE_SIZE;
            const normalizedPageSize = VARIANT_PAGE_SIZE_OPTIONS.includes(numericPageSize)
                ? numericPageSize
                : DEFAULT_VARIANT_PAGE_SIZE;
            const ticket = ++candidateReadSequence.current;
            const current = () => isAnalysisRequestCurrent(analysisId) && ticket === candidateReadSequence.current;
            setCandidateRead({ analysisId, state: 'LOADING', startedAt: Date.now() });
            try {
            const payload =
                (await fetchVariantAnalysisVariants(
                    analysisId,
                    normalizedPage,
                    normalizedPageSize,
                    view,
                    filters,
                )) || EMPTY_PAGE;
            if (!current()) {
                return payload || EMPTY_PAGE;
            }
            const nextPage = {
                ...EMPTY_PAGE,
                ...payload,
                variants: payload?.variants || [],
                page: payload?.page || normalizedPage,
                pageSize: payload?.pageSize || normalizedPageSize,
                view: payload?.view || view,
            };
            setVariantsPage(nextPage);
            setError('');
            setVariantPageNumber(nextPage.page);
            setVariantPageSize(nextPage.pageSize);
            setSelectedVariant((current) => {
                const currentCandidateId = candidateKey(current);
                if (!currentCandidateId) return null;
                const pageVariant = (nextPage?.variants || []).find(
                    (item) => candidateKey(item) === currentCandidateId,
                );
                return mergeSelectedVariantFromPage(current, pageVariant);
            });
            setCandidateRead({ analysisId, state: 'READY' });
            return nextPage;
            } catch (loadError) {
                if (current()) setCandidateRead({ analysisId, state: 'FAILED', message: analysisRequestErrorMessage(loadError) });
                throw loadError;
            }
        },
        [isAnalysisRequestCurrent],
    );

    const restoreAnalysis = useCallback(
        async (analysisId = restoreAnalysisId) => {
            const id = String(analysisId || '').trim();
            if (!id || busy) return;
            const sameActiveAnalysis = activeAnalysisIdRef.current === id;
            setBusy(true);
            setHistoryRead({ startedAt: Date.now(), stage: '正在读取已保存的分析任务…' });
            setError('');
            setPollingNotice('');
            try {
                const restored = await fetchVariantAnalysis(id);
                setAnalysisEvents([]);
                setVariantsPage(EMPTY_PAGE);
                setVariantPageNumber(1);
                setVariantPageSize(DEFAULT_VARIANT_PAGE_SIZE);
                setVariantView(VARIANT_VIEW_CANDIDATES);
                setVariantFilter(VARIANT_FILTER_ALL);
                setVariantBucket(VARIANT_BUCKET_ALL);
                setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
                setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
                setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
                setAcmgCodeFilter(ACMG_CODE_ALL);
                setAcmgOnlySuggested(false);
                setAcmgManualReviewRequired(false);
                setVariantSearch('');
                setDebouncedVariantSearch('');
                clearVariantSelection();
                setAdvancedFiltersExpanded(false);
                setEnhancement(null);
                activateAnalysisJob(restored);
                setRestoredHistoryId(restored?.id || id);
                setHistoryRead(old => old && ({ ...old, stage: '正在读取历史事件…' }));
                await loadEvents(restored.id);
                if (restored?.status === 'READY' && sameActiveAnalysis) {
                    await loadVariants(restored.id, {
                        view: VARIANT_VIEW_CANDIDATES,
                        filters: {},
                        page: 1,
                        pageSize: DEFAULT_VARIANT_PAGE_SIZE,
                    });
                }
            } catch (restoreError) {
                setError(analysisRequestErrorMessage(restoreError));
            } finally {
                setBusy(false);
                setHistoryRead(null);
            }
        },
        [restoreAnalysisId, busy, activateAnalysisJob, clearVariantSelection, loadEvents, loadVariants],
    );

    const refreshJob = useCallback(async () => {
        if (!job?.id) return;
        const analysisId = job.id;
        try {
            const next = await fetchVariantAnalysis(analysisId);
            if (!isAnalysisRequestCurrent(analysisId)) return;
            activateAnalysisJob(next);
            await loadEvents(next?.id || analysisId);
            if (next?.status === 'READY') {
                await loadVariants(next.id, {
                    view: variantView,
                    filters: variantQueryOptions,
                    page: variantPageNumber,
                    pageSize: variantPageSize,
                });
            }
            if (!isAnalysisRequestCurrent(analysisId)) return;
            setPollingNotice('');
        } catch (refreshError) {
            if (!isAnalysisRequestCurrent(analysisId)) return;
            if (isNotFoundError(refreshError)) {
                clearMissingAnalysisJob();
            } else if (isRequestTimeout(refreshError)) {
                setPollingNotice(pollingTimeoutNotice);
            } else {
                setError(refreshError?.message || 'Could not refresh analysis status.');
            }
        }
    }, [
        activateAnalysisJob,
        clearMissingAnalysisJob,
        job?.id,
        isAnalysisRequestCurrent,
        loadEvents,
        loadVariants,
        variantPageNumber,
        variantPageSize,
        variantQueryOptions,
        variantView,
    ]);

    const loadProviderStatus = useCallback(async () => {
        try {
            setProviderStatus(await fetchVariantAnalysisProviderStatus());
            setProviderStatusError('');
        } catch (statusError) {
            setProviderStatusError(
                statusError?.message || 'Could not load evidence resource status.',
            );
        }
    }, []);

    const loadPreflightStatus = useCallback(async (genomeAssembly) => {
        const requestId = preflightRequestIdRef.current + 1;
        preflightRequestIdRef.current = requestId;
        setPreflightLoading(true);
        setPreflightError('');
        try {
            const next = await fetchVariantAnalysisPreflight(genomeAssembly);
            if (preflightRequestIdRef.current === requestId) {
                setPreflightStatus(next);
            }
            return next;
        } catch (statusError) {
            if (preflightRequestIdRef.current === requestId) {
                setPreflightStatus(null);
                setPreflightError(
                    statusError?.message || 'Could not verify base-analysis readiness.',
                );
            }
            return null;
        } finally {
            if (preflightRequestIdRef.current === requestId) {
                setPreflightLoading(false);
            }
        }
    }, []);

    useEffect(() => {
        if (!open) return;
        loadProviderStatus();
    }, [loadProviderStatus, open]);

    useEffect(() => {
        if (!open) return;
        loadPreflightStatus(inputGenomeAssembly);
    }, [inputGenomeAssembly, loadPreflightStatus, open]);

    useEffect(() => {
        let disposed = false;
        if (!job?.id || !hasJobPhenotypeInput || !phenotypeLookupGene) {
            setPhenotypeGenePriorLookup(null);
            setPhenotypeGenePriorLookupLoading(false);
            return undefined;
        }

        const loadLookup = async () => {
            setPhenotypeGenePriorLookupLoading(true);
            try {
                const lookup = await fetchPhenotypeGenePriorLookup(job.id, phenotypeLookupGene);
                if (!disposed) {
                    setPhenotypeGenePriorLookup(lookup || null);
                }
            } catch {
                if (!disposed) {
                    setPhenotypeGenePriorLookup(null);
                }
            } finally {
                if (!disposed) {
                    setPhenotypeGenePriorLookupLoading(false);
                }
            }
        };

        loadLookup();
        return () => {
            disposed = true;
        };
    }, [hasJobPhenotypeInput, job?.id, phenotypeLookupGene]);

    useEffect(() => {
        if (!job?.id || !ACTIVE_ANALYSIS_STATUSES.has(job.status)) return undefined;
        let disposed = false;
        let timer = null;
        const analysisId = job.id;
        let currentStatus = job.status;
        const schedule = () => {
            timer = window.setTimeout(tick, 1500);
        };
        const tick = async () => {
            try {
                const next = await fetchVariantAnalysis(analysisId);
                if (disposed || !isAnalysisRequestCurrent(analysisId)) return;
                currentStatus = next?.status || currentStatus;
                activateAnalysisJob(next);
                await loadEvents(next?.id || analysisId);
                if (next?.status === 'READY') {
                    await loadVariants(next.id, {
                        view: variantView,
                        filters: variantQueryOptions,
                        page: variantPageNumber,
                        pageSize: variantPageSize,
                    });
                }
                if (!isAnalysisRequestCurrent(analysisId)) return;
                setPollingNotice('');
            } catch (pollError) {
                if (!disposed && isAnalysisRequestCurrent(analysisId)) {
                    if (isNotFoundError(pollError)) {
                        currentStatus = 'FAILED';
                        clearMissingAnalysisJob();
                    } else if (isRequestTimeout(pollError)) {
                        setPollingNotice(pollingTimeoutNotice);
                    } else {
                        setError(pollError?.message || 'Could not refresh analysis status.');
                    }
                }
            } finally {
                if (
                    !disposed &&
                    isAnalysisRequestCurrent(analysisId) &&
                    ACTIVE_ANALYSIS_STATUSES.has(currentStatus)
                ) {
                    schedule();
                }
            }
        };
        schedule();
        return () => {
            disposed = true;
            if (timer) window.clearTimeout(timer);
        };
    }, [
        activateAnalysisJob,
        job?.id,
        job?.status,
        clearMissingAnalysisJob,
        isAnalysisRequestCurrent,
        loadEvents,
        loadVariants,
        variantPageNumber,
        variantPageSize,
        variantQueryOptions,
        variantView,
    ]);

    useEffect(() => {
        if (!job?.id || !enhancement?.id || !ACTIVE_ENHANCEMENT_STATUSES.has(enhancement.status)) {
            return undefined;
        }
        let disposed = false;
        let timer = null;
        const analysisId = job.id;
        let currentStatus = enhancement.status;
        const schedule = () => {
            timer = window.setTimeout(tick, 1500);
        };
        const tick = async () => {
            try {
                const next = await fetchVariantAnalysisEnhancement(analysisId, enhancement.id);
                if (disposed || !isAnalysisRequestCurrent(analysisId)) return;
                currentStatus = next?.status || currentStatus;
                setEnhancement(next);
                if (!ACTIVE_ENHANCEMENT_STATUSES.has(next?.status)) {
                    await loadVariants(analysisId, {
                        view: variantView,
                        filters: variantQueryOptions,
                        page: variantPageNumber,
                        pageSize: variantPageSize,
                    });
                }
                if (!isAnalysisRequestCurrent(analysisId)) return;
                setPollingNotice('');
            } catch (pollError) {
                if (!disposed && isAnalysisRequestCurrent(analysisId)) {
                    if (isNotFoundError(pollError)) {
                        currentStatus = 'FAILED';
                        clearMissingAnalysisJob();
                    } else if (isRequestTimeout(pollError)) {
                        setPollingNotice(pollingTimeoutNotice);
                    } else {
                        setError(pollError?.message || 'Could not refresh AlphaGenome status.');
                    }
                }
            } finally {
                if (
                    !disposed &&
                    isAnalysisRequestCurrent(analysisId) &&
                    ACTIVE_ENHANCEMENT_STATUSES.has(currentStatus)
                ) {
                    schedule();
                }
            }
        };
        schedule();
        return () => {
            disposed = true;
            if (timer) window.clearTimeout(timer);
        };
    }, [
        enhancement?.id,
        enhancement?.status,
        clearMissingAnalysisJob,
        isAnalysisRequestCurrent,
        job?.id,
        loadVariants,
        variantPageNumber,
        variantPageSize,
        variantQueryOptions,
        variantView,
    ]);

    useEffect(() => {
        if (!job?.id || job.status !== 'READY') return;
        let disposed = false;
        const analysisId = job.id;
        clearVariantSelection();
        const loadReadyVariants = async () => {
            try {
                await loadVariants(analysisId, {
                    view: variantView,
                    filters: variantQueryOptions,
                    page: variantPageNumber,
                    pageSize: variantPageSize,
                });
            } catch (loadError) {
                if (disposed || !isAnalysisRequestCurrent(analysisId)) return;
                if (isNotFoundError(loadError)) {
                    clearMissingAnalysisJob();
                } else if (isRequestTimeout(loadError)) {
                    setPollingNotice(pollingTimeoutNotice);
                } else {
                    setError(loadError?.message || 'Could not load variant list.');
                }
            }
        };
        loadReadyVariants();
        return () => {
            disposed = true;
        };
    }, [
        clearVariantSelection,
        clearMissingAnalysisJob,
        isAnalysisRequestCurrent,
        job?.id,
        job?.status,
        loadVariants,
        variantPageNumber,
        variantPageSize,
        variantQueryOptions,
        variantView,
    ]);

    const qcStats = useMemo(() => {
        const qc = job?.qcSummary;
        if (!qc) return [];
        return [
            ['Variants', qc.variantCount],
            ['PASS', qc.passCount],
            ['SNVs', qc.snvCount],
            ['InDels', qc.indelCount],
            ['Filtered', qc.filteredCount],
            ['Samples', qc.sampleCount],
        ];
    }, [job?.qcSummary]);

    const recentEvents = useMemo(() => analysisEvents.slice(-6), [analysisEvents]);
    const latestEvent = recentEvents[recentEvents.length - 1] || null;
    const analysisProgress = statusProgress(job?.status);
    const progressiveStage = job?.analysisStage || progressiveStageFallback(job);
    const vepExecution = useMemo(
        () => vepJobExecution(analysisEvents, Boolean(job?.id)),
        [analysisEvents, job?.id],
    );
    const vepExecutionSummary = vepExecution.summary;
    const preRankingAnnotationSummary = useMemo(
        () => latestPreRankingAnnotationSummary(analysisEvents),
        [analysisEvents],
    );
    const normalizationRunSummary = useMemo(
        () => latestNormalizationRunSummary(analysisEvents),
        [analysisEvents],
    );
    const transcriptAnnotationStatus = vepExecution.status;
    const selectedAssembly = job?.genomeAssembly || inputGenomeAssembly;
    const selectedVepProfile = providerStatus?.vepProfiles?.[selectedAssembly] || null;
    const preflightBlocksSubmission =
        preflightLoading ||
        Boolean(preflightError) ||
        !preflightStatus ||
        preflightStatus.applicationReady === false;
    const optionalLimitationCount = Array.isArray(preflightStatus?.optionalComponents)
        ? preflightStatus.optionalComponents.filter((component) => !component?.ready).length
        : 0;
    const normalizedAssemblyPolicy = String(providerStatus?.analysisAssemblyPolicy || '')
        .replace(/[^A-Za-z0-9]/g, '')
        .toUpperCase();
    const normalizationAssembly =
        normalizationRunSummary?.assembly ||
        (['INPUT', 'PRESERVEINPUT', 'NATIVE'].includes(normalizedAssemblyPolicy)
            ? selectedAssembly
            : 'GRCh38');
    const selectedNormalizationProfile =
        providerStatus?.normalizationProfiles?.[normalizationAssembly] || null;
    const normalizationMode = String(providerStatus?.normalizationMode || 'UNKNOWN').toUpperCase();
    const normalizationProvider = useMemo(() => {
        const bcftoolsEnabled = normalizationMode === 'BCFTOOLS';
        const profileStatus = String(selectedNormalizationProfile?.status || '').toUpperCase();
        const status = bcftoolsEnabled
            ? profileStatus || 'RESOURCE_UNAVAILABLE'
            : normalizationMode === 'COPY_ONLY'
              ? 'DISABLED'
              : 'UNAVAILABLE';
        const profileId = selectedNormalizationProfile?.profileId || 'not frozen';
        return {
            source: 'BCFTOOLS_NORMALIZATION',
            enabled: bcftoolsEnabled,
            ready: bcftoolsEnabled && Boolean(selectedNormalizationProfile?.ready),
            status,
            version: selectedNormalizationProfile?.profileId || null,
            warnings: selectedNormalizationProfile?.warnings || [],
            message: bcftoolsEnabled
                ? `${normalizationAssembly} profile ${profileId}; ${
                      selectedNormalizationProfile?.referenceFileName || 'reference FASTA missing'
                  }.`
                : 'Reference-based normalization is disabled. COPY_ONLY preserves the upload representation and is a visible degraded mode.',
        };
    }, [normalizationAssembly, normalizationMode, selectedNormalizationProfile]);
    const selectedAssemblyIsGrch37 = String(selectedAssembly || '').toUpperCase() === 'GRCH37';
    const preRankingSkippedByCap = Number(preRankingAnnotationSummary?.skippedByCap || 0);
    const vepProfileStatuses = useMemo(() => {
        const profiles = providerStatus?.vepProfiles || {};
        return ['GRCh37', 'GRCh38'].map((assembly) => profiles[assembly]).filter(Boolean);
    }, [providerStatus]);

    useEffect(() => {
        if (
            !job?.id ||
            job.status !== 'READY' ||
            !ACTIVE_TRANSCRIPT_ANNOTATION_STATUSES.has(
                String(transcriptAnnotationStatus || '').toUpperCase(),
            )
        ) {
            return undefined;
        }
        let disposed = false;
        let keepPolling = true;
        const analysisId = job.id;
        const selectedCandidate = selectedCandidateId
            ? {
                  candidateId: selectedVariant?.candidateId,
                  canonicalVariantId: selectedCanonicalVariantId,
                  variantId: selectedVariant?.variantId,
              }
            : null;
        let timer = null;
        const schedule = () => {
            timer = window.setTimeout(tick, 2000);
        };
        const tick = async () => {
            try {
                if (!isAnalysisRequestCurrent(analysisId)) return;
                await loadEvents(analysisId);
                await loadVariants(analysisId, {
                    view: variantView,
                    filters: variantQueryOptions,
                    page: variantPageNumber,
                    pageSize: variantPageSize,
                });
                if (selectedCandidate) {
                    const detail = await fetchVariantAnalysisCandidate(
                        analysisId,
                        selectedCandidate,
                    );
                    if (!disposed && isAnalysisRequestCurrent(analysisId)) {
                        if (
                            detail &&
                            candidateKey(detail) === selectedCandidateId &&
                            (!selectedCanonicalVariantId ||
                                canonicalKey(detail) === selectedCanonicalVariantId)
                        ) {
                            setSelectedVariant(detail);
                        } else {
                            setError(
                                'Candidate detail identity could not be verified during refresh.',
                            );
                        }
                    }
                }
                if (!isAnalysisRequestCurrent(analysisId)) return;
                setPollingNotice('');
            } catch (pollError) {
                if (!disposed && isAnalysisRequestCurrent(analysisId)) {
                    if (isNotFoundError(pollError)) {
                        keepPolling = false;
                        clearMissingAnalysisJob();
                    } else if (isRequestTimeout(pollError)) {
                        setPollingNotice(pollingTimeoutNotice);
                    } else {
                        setError(
                            pollError?.message || 'Could not refresh transcript annotation status.',
                        );
                    }
                }
            } finally {
                if (!disposed && keepPolling && isAnalysisRequestCurrent(analysisId)) {
                    schedule();
                }
            }
        };
        schedule();
        return () => {
            disposed = true;
            if (timer) window.clearTimeout(timer);
        };
    }, [
        job?.id,
        job?.status,
        clearMissingAnalysisJob,
        isAnalysisRequestCurrent,
        loadEvents,
        loadVariants,
        selectedCandidateId,
        selectedCanonicalVariantId,
        selectedVariant?.candidateId,
        selectedVariant?.variantId,
        transcriptAnnotationStatus,
        variantPageNumber,
        variantPageSize,
        variantQueryOptions,
        variantView,
    ]);

    const evidenceSummary = useMemo(() => {
        const variants = variantsPage?.variants || [];
        return {
            total: variants.length,
            caddScored: variants.filter((variant) => hasApplicableEvidence(variant, 'CADD')).length,
            alphaMissenseScored: variants.filter((variant) =>
                hasApplicableEvidence(variant, 'ALPHAMISSENSE'),
            ).length,
            alphaGenomeCandidates: variants.filter(isAlphaGenomeCandidate).length,
        };
    }, [variantsPage?.variants]);
    const acmgReadySummary = useMemo(() => {
        const variants = variantsPage?.variants || [];
        const suggestions = variants.flatMap((variant) => variant?.acmgEvidenceSuggestions || []);
        const suggested = suggestions.filter(
            (item) => String(item?.status || '').toUpperCase() === 'SUGGESTED',
        );
        return {
            candidatesWithSuggestions: variants.filter(
                (variant) => (variant?.acmgEvidenceSuggestions || []).length > 0,
            ).length,
            total: suggestions.length,
            suggested: suggested.length,
            manualReviewRequired: suggestions.filter((item) => item?.manualReviewRequired).length,
            codes: acmgCodeList(suggested),
        };
    }, [variantsPage?.variants]);
    const isFrequencyFilteredView = variantView === VARIANT_VIEW_FREQUENCY_FILTERED;
    const isAllVariantView = variantView === VARIANT_VIEW_ALL;
    const candidateTotal = variantsPage?.candidateTotal ?? 0;
    const frequencyFilteredTotal = variantsPage?.frequencyFilteredTotal ?? 0;
    const allTotal = variantsPage?.allTotal ?? variantsPage?.counts?.all ?? 0;
    const isEngineRankedResult = String(job?.engine || '').toUpperCase() === 'EXOMISER';
    const populationFrequencyThreshold =
        variantsPage?.populationFrequencyThreshold ?? EMPTY_PAGE.populationFrequencyThreshold;
    const variantListTitle = isAllVariantView
        ? '5. All persisted variants'
        : isFrequencyFilteredView
          ? '5. gnomAD frequency-filtered variants'
          : isEngineRankedResult
            ? '5. Ranked candidates'
            : '5. Candidate variants';
    const variantListSubtitle = isAllVariantView
        ? `${formatNumber(variantsPage.total, '0')} variants matching backend filters`
        : isFrequencyFilteredView
          ? `${formatNumber(variantsPage.total, '0')} variants excluded from the rare-disease candidate list`
          : isEngineRankedResult
            ? `${formatNumber(variantsPage.total, '0')} candidates emitted by the configured ranking pipeline`
            : `${formatNumber(variantsPage.total, '0')} rare-disease candidate variants`;
    const displayedVariants = variantsPage?.variants || [];
    const loadedVariantCount = variantsPage?.variants?.length || 0;
    const topLoadedVariant = (variantsPage?.variants || [])[0] || null;
    const topLoadedCandidateId = candidateKey(topLoadedVariant);
    const viewingRestoredAnalysis = Boolean(
        restoredHistoryId && restoredHistoryId === job?.id,
    );
    const currentCandidateRead = candidateRead?.analysisId === job?.id ? candidateRead : null;
    const candidatesLoading = currentCandidateRead?.state === 'LOADING';
    const candidatesFailed = currentCandidateRead?.state === 'FAILED';
    const retryCandidateRead = () => {
        // The task is already known; retry only the failed read and show loading immediately.
        void loadVariants(job.id, { view: variantView, filters: variantQueryOptions,
            page: variantPageNumber, pageSize: variantPageSize }).catch(() => undefined);
    };
    const candidateReadNotice = candidatesLoading ? <AnalysisLoadingNotice
        startedAt={currentCandidateRead.startedAt} title="正在读取候选排名与已有注释…"
        detail={loadedVariantCount > 0 ? '列表更新期间显示上次读取的内容。' : undefined} />
        : candidatesFailed ? <Alert severity="error" action={<Button color="inherit" onClick={retryCandidateRead}>重新读取</Button>}>
            候选列表读取失败：{currentCandidateRead.message}。不能据此认为没有候选变异。
        </Alert> : null;
    const activeVariantPage = variantsPage?.page || variantPageNumber || 1;
    const activeVariantPageSize =
        variantsPage?.pageSize || variantPageSize || DEFAULT_VARIANT_PAGE_SIZE;
    const variantPageCount = Math.max(
        1,
        Math.ceil((variantsPage?.total || 0) / activeVariantPageSize),
    );
    const variantResultStart =
        (variantsPage?.total || 0) > 0 ? (activeVariantPage - 1) * activeVariantPageSize + 1 : 0;
    const variantResultEnd =
        (variantsPage?.total || 0) > 0
            ? Math.min(variantsPage.total, variantResultStart + loadedVariantCount - 1)
            : 0;
    const qcSummary = job?.qcSummary || {};
    const preflightSummary = job?.preflightSummary || {};
    const largeFileSummary = {
        ...largeFileCountsFrom(job),
        ...largeFileCountsFrom(variantsPage),
    };
    const largeFileCarriedAltCount = Number.isFinite(Number(largeFileSummary?.carriedAltCount))
        ? largeFileSummary.carriedAltCount
        : preflightSummary.carriedAltCount;
    const largeFileNotCarriedAltCount = Number.isFinite(
        Number(largeFileSummary?.notCarriedAltCount),
    )
        ? largeFileSummary.notCarriedAltCount
        : preflightSummary.notCarriedAltCount;
    const bucketCounts = bucketCountsFrom(variantsPage, job);
    const largeFileModeActive = Boolean(
        preflightSummary.largeFileMode ||
            Number(largeFileSummary?.largeFileModeCount || 0) > 0 ||
            Number(largeFileSummary?.summaryOnlyCount || 0) > 0 ||
            Number(largeFileSummary?.expensiveAnnotationSkippedCount || 0) > 0,
    );
    const bucketCountFor = (bucket) =>
        bucket === VARIANT_BUCKET_ALL ? allTotal : Number(bucketCounts?.[bucket] || 0);
    const selectedVariantSummaryOnly = isSummaryOnlyVariant(selectedVariant);
    const selectedVariantSkippedAnnotation = isSkippedAnnotationVariant(selectedVariant);
    const detailIsLoading =
        Boolean(detailLoadingCandidateId) && detailLoadingCandidateId === selectedCandidateId;
    const passRate =
        Number(qcSummary.variantCount) > 0
            ? Number(qcSummary.passCount || 0) / Number(qcSummary.variantCount)
            : null;
    const providerReadinessSummary = useMemo(() => {
        if (!providerStatus) {
            return {
                label: 'Not loaded',
                detail: '上游组件配置状态尚未读取。',
            };
        }
        const providers = [
            providerStatus.exomiser,
            normalizationProvider,
            providerStatus.cadd,
            providerStatus.clinVar,
            providerStatus.gnomad,
            providerStatus.spliceAi,
            providerStatus.alphaMissense,
            providerStatus.alphaGenome,
            providerStatus.vep,
            providerStatus.mane,
            providerStatus.loftee,
        ].filter(Boolean);
        const labels = providers.map(providerReadinessLabel);
        const ready = labels.filter((label) => label === 'READY').length;
        const partial = labels.filter((label) => label === 'PARTIAL').length;
        const needsSetup = labels.filter((label) => label === 'NEEDS SETUP').length;
        const offline = labels.filter((label) => label === 'OFFLINE').length;
        const disabled = labels.filter((label) => label === 'DISABLED').length;
        const unavailable = labels.filter((label) =>
            ['UNAVAILABLE', 'RESOURCE_UNAVAILABLE', 'MISSING_RESOURCE'].includes(label),
        ).length;
        const invalid = labels.filter((label) =>
            [
                'FAILED',
                'ERROR',
                'INVALID_RESOURCE',
                'INVALID_REFERENCE_PROFILE',
                'ASSEMBLY_MISMATCH',
                'ASSEMBLY_UNSUPPORTED',
            ].includes(label),
        ).length;
        const detail = [
            partial ? `${partial} partial` : null,
            needsSetup ? `${needsSetup} need setup` : null,
            offline ? `${offline} offline` : null,
            disabled ? `${disabled} disabled` : null,
            unavailable ? `${unavailable} unavailable` : null,
            invalid ? `${invalid} invalid/failed` : null,
        ]
            .filter(Boolean)
            .join(' / ');
        return {
            label: `上游组件 ${ready}/${providers.length} 就绪`,
            detail: detail || '已配置上游组件均就绪；不代表 ACMG 已完成判定。',
            allReady: ready === providers.length,
        };
    }, [normalizationProvider, providerStatus]);
    const analysisResourceSummary = useMemo(() => {
        const baseEngine = providerStatus?.defaultEngine || 'Analysis engine';
        const baseRuntime = providerStatus?.exomiser;
        if (!baseRuntime) {
            return providerReadinessSummary;
        }
        return {
            label: `${baseEngine} ${providerReadinessLabel(baseRuntime)}`,
            detail: `${baseRuntime.message || 'Base runtime status is available.'} ${providerReadinessSummary.detail}`,
        };
    }, [providerReadinessSummary, providerStatus?.defaultEngine, providerStatus?.exomiser]);
    const providerCards = useMemo(
        () => ({
            foundation: [
                {
                    title: 'Exomiser',
                    provider: providerStatus?.exomiser,
                    detail:
                        providerStatus?.exomiser?.message ||
                        'Primary phenotype-driven ranking runtime.',
                },
                {
                    title: 'Reference normalization',
                    provider: normalizationProvider,
                    detail: normalizationProvider.message,
                    children: (
                        <Stack gap={0.5}>
                            <Stack direction="row" gap={0.5} flexWrap="wrap">
                                <Chip
                                    size="small"
                                    variant="outlined"
                                    label={`Mode ${normalizationMode}`}
                                />
                                <Chip
                                    size="small"
                                    variant="outlined"
                                    label={normalizationAssembly || 'Assembly unresolved'}
                                />
                            </Stack>
                            {selectedNormalizationProfile ? (
                                <Typography variant="caption" color="text.secondary">
                                    Contigs{' '}
                                    {selectedNormalizationProfile.contigStyle || 'unresolved'} /{' '}
                                    {formatNumber(selectedNormalizationProfile.contigCount, '0')};
                                    FASTA{' '}
                                    {selectedNormalizationProfile.referenceExists
                                        ? 'ready'
                                        : 'missing'}{' '}
                                    / FAI{' '}
                                    {selectedNormalizationProfile.fastaIndexExists
                                        ? 'ready'
                                        : 'missing'}
                                    .
                                </Typography>
                            ) : null}
                            <Typography
                                variant="caption"
                                color={
                                    selectedNormalizationProfile?.configuredSha256
                                        ? 'text.secondary'
                                        : 'warning.main'
                                }
                            >
                                Reference identity{' '}
                                {selectedNormalizationProfile?.configuredSha256
                                    ? 'metadata configured'
                                    : 'SHA-256 metadata not frozen'}
                                .
                            </Typography>
                            {normalizationRunSummary ? (
                                <Typography variant="caption" color="text.secondary">
                                    Last run {normalizationRunSummary.status}: raw{' '}
                                    {formatNumber(
                                        normalizationRunSummary.rawRecords,
                                        'not recorded',
                                    )}{' '}
                                    / normalized{' '}
                                    {formatNumber(
                                        normalizationRunSummary.normalizedAlleles,
                                        'not recorded',
                                    )}{' '}
                                    / split{' '}
                                    {formatNumber(
                                        normalizationRunSummary.splitRecords,
                                        'not recorded',
                                    )}{' '}
                                    / {formatNumber(normalizationRunSummary.elapsedMs, '0')} ms.
                                </Typography>
                            ) : null}
                            {normalizationRunSummary?.failureCode ? (
                                <Typography variant="caption" color="error.main">
                                    Failure: {normalizationRunSummary.failureCode}
                                </Typography>
                            ) : null}
                        </Stack>
                    ),
                },
            ],
            core: [
                {
                    title: 'ClinVar',
                    provider: providerStatus?.clinVar,
                },
                {
                    title: 'gnomAD · 上游注释',
                    provider: providerStatus?.gnomad,
                    detail: providerStatus?.gnomad?.configuredFileCount
                        ? `${providerStatus.gnomad.availableFileCount || 0}/${
                              providerStatus.gnomad.configuredFileCount
                          } indexed files ready`
                        : providerStatus?.gnomad?.message,
                    children: <Typography variant="caption" color="text.secondary">{LOCAL_POPULATION_SCOPE}</Typography>,
                },
                {
                    title: 'CADD',
                    provider: providerStatus?.cadd,
                    detail: `SNV ${providerStatus?.cadd?.snvReady ? 'ready' : 'missing'} / InDel ${
                        providerStatus?.cadd?.indelReady ? 'ready' : 'missing'
                    }`,
                },
                {
                    title: 'QC',
                    provider: { ready: true, enabled: true, version: 'VCF FORMAT/QC' },
                    detail: 'Parsed from uploaded VCF FILTER, GT, DP, GQ and AD fields.',
                },
            ],
            predictive: [
                {
                    title: 'SpliceAI',
                    provider: providerStatus?.spliceAi,
                },
                {
                    title: 'AlphaMissense',
                    provider: providerStatus?.alphaMissense,
                },
            ],
            transcript: [
                {
                    title: 'VEP',
                    provider: providerStatus?.vep,
                    detail:
                        providerStatus?.vep?.message ||
                        'VEP annotates ranked candidate variants after the base analysis; ANN is used only as fallback when present.',
                    children:
                        providerStatus?.vep?.transcriptAnnotationStatus ||
                        vepProfileStatuses.length ? (
                            <Stack gap={0.5}>
                                {providerStatus?.vep?.transcriptAnnotationStatus ? (
                                    <Typography variant="caption" color="text.secondary">
                                        VEP 运行资源配置；本次执行情况请查看任务的 Transcript annotation 汇总。
                                    </Typography>
                                ) : null}
                                {providerStatus?.analysisAssemblyPolicy ? (
                                    <Typography variant="caption" color="text.secondary">
                                        Policy {providerStatus.analysisAssemblyPolicy}; selected
                                        input {inputGenomeAssembly}
                                    </Typography>
                                ) : null}
                                {vepProfileStatuses.map((profile) => (
                                    <Stack
                                        key={profile.assembly}
                                        direction="row"
                                        gap={0.5}
                                        alignItems="center"
                                        flexWrap="wrap"
                                    >
                                        <Chip
                                            size="small"
                                            variant={
                                                profile.assembly === inputGenomeAssembly
                                                    ? 'filled'
                                                    : 'outlined'
                                            }
                                            color={providerTone(profile)}
                                            label={`${profile.assembly} ${providerReadinessLabel(
                                                profile,
                                            )}`}
                                        />
                                        <Typography variant="caption" color="text.secondary">
                                            cache {profile.cacheAvailable ? 'ready' : 'missing'} /
                                            FASTA {profile.fastaAvailable ? 'ready' : 'missing'}
                                        </Typography>
                                    </Stack>
                                ))}
                            </Stack>
                        ) : null,
                },
                {
                    title: 'MANE',
                    provider: providerStatus?.mane,
                    detail:
                        providerStatus?.mane?.message ||
                        'MANE fields are parsed from VEP output when present.',
                },
                {
                    title: 'LOFTEE',
                    provider: providerStatus?.loftee,
                    detail:
                        providerStatus?.loftee?.message ||
                        'LOFTEE fields are parsed as candidate LoF support only.',
                    children: (
                        <Typography variant="caption" color="text.secondary">
                            HC labels are not displayed as clinical pathogenic conclusions.
                        </Typography>
                    ),
                },
            ],
            optional: [
                {
                    title: 'AlphaGenome',
                    provider: providerStatus?.alphaGenome,
                },
            ],
        }),
        [
            inputGenomeAssembly,
            normalizationMode,
            normalizationProvider,
            normalizationRunSummary,
            providerStatus,
            selectedNormalizationProfile,
            normalizationAssembly,
            vepProfileStatuses,
        ],
    );
    const providerSummaryStats = useMemo(() => {
        const allProviders = Object.values(providerCards)
            .flat()
            .map((item) => item?.provider)
            .filter(Boolean);
        const labels = allProviders.map(providerReadinessLabel);
        const warningCount = allProviders.filter(
            (provider) => (provider?.warnings || []).length > 0,
        ).length;
        return {
            ready: labels.filter((label) => ['READY', 'COMPLETED'].includes(label)).length,
            partial: labels.filter((label) => label === 'PARTIAL').length,
            attention: labels.filter((label) =>
                ['UNAVAILABLE', 'NEEDS SETUP', 'OFFLINE', 'FAILED', 'ERROR'].includes(label),
            ).length,
            warningCount,
        };
    }, [providerCards]);
    const coreProviderNotes = useMemo(
        () =>
            (providerCards.core || [])
                .filter(
                    (item) =>
                        !['READY', 'COMPLETED'].includes(providerReadinessLabel(item.provider)),
                )
                .map((item) => `${item.title} · ${providerReadinessLabel(item.provider)}`),
        [providerCards],
    );
    const activeFilterSummary = useMemo(() => {
        const chips = [];
        if (variantView === VARIANT_VIEW_FREQUENCY_FILTERED) {
            chips.push('View: gnomAD filtered');
        } else if (variantView === VARIANT_VIEW_ALL) {
            chips.push('View: all persisted');
        }
        const filterOption = VARIANT_FILTER_OPTIONS.find(
            (option) => option.value === variantFilter,
        );
        if (variantFilter !== VARIANT_FILTER_ALL && filterOption?.label) {
            chips.push(filterOption.label);
        }
        const bucketOption = bucketOptionFor(variantBucket);
        if (variantBucket !== VARIANT_BUCKET_ALL && bucketOption?.label) {
            chips.push(`Bucket: ${bucketOption.label}`);
        }
        if (summaryOnlyFilter !== BOOLEAN_FILTER_ALL) {
            chips.push(`Summary-only: ${booleanFilterLabel(summaryOnlyFilter)}`);
        }
        if (largeFileModeFilter !== BOOLEAN_FILTER_ALL) {
            chips.push(`Large-file mode: ${booleanFilterLabel(largeFileModeFilter)}`);
        }
        if (skippedAnnotationFilter !== BOOLEAN_FILTER_ALL) {
            chips.push(`Skipped annotation: ${booleanFilterLabel(skippedAnnotationFilter)}`);
        }
        if (debouncedVariantSearch) {
            chips.push(`Search: ${debouncedVariantSearch}`);
        }
        if (acmgCodeFilter !== ACMG_CODE_ALL) {
            chips.push(`ACMG: ${acmgCodeFilter}`);
        }
        if (acmgOnlySuggested) {
            chips.push('ACMG suggested only');
        }
        if (acmgManualReviewRequired) {
            chips.push('Manual review required');
        }
        return chips;
    }, [
        acmgCodeFilter,
        acmgManualReviewRequired,
        acmgOnlySuggested,
        debouncedVariantSearch,
        largeFileModeFilter,
        skippedAnnotationFilter,
        summaryOnlyFilter,
        variantBucket,
        variantFilter,
        variantView,
    ]);
    const selectedVariantReviewNotes = useMemo(
        () => collectReviewNotes(selectedVariant),
        [selectedVariant],
    );
    const selectedTranscriptHighlights = useMemo(
        () => transcriptHighlights(selectedVariant),
        [selectedVariant],
    );
    const selectedPhenotypeSupport = useMemo(
        () => phenotypeSupportInfo(selectedVariant),
        [selectedVariant],
    );
    const selectedTrioEvidence = useMemo(
        () => trioEvidenceInfo(selectedVariant),
        [selectedVariant],
    );
    const selectedExomiserContext = useMemo(
        () => exomiserCandidateContext(selectedVariant),
        [selectedVariant],
    );

    const handleSubmit = useCallback(
        async ({ createNewAnalysis = false } = {}) => {
            if (!selectedFile || busy) return;
            const requestedFile = selectedFile;
            let checkingHistory = createNewAnalysis !== true;
            setBusy(true);
            setError('');
            try {
                if (createNewAnalysis !== true) {
                    setHistoryMatches(null);
                    const history = await findVariantAnalysisHistory({ file: requestedFile });
                    if (selectedFileRef.current !== requestedFile) return;
                    if (
                        history?.matchBasis !== 'EXACT_FILE_SHA256' ||
                        history?.historyOnly !== true ||
                        !Array.isArray(history.matches)
                    ) {
                        throw new Error('无法确认同文件历史查询结果，本次未提交新分析。');
                    }
                    if (history.matches.length) {
                        setHistoryMatches(history.matches);
                        return;
                    }
                }
                checkingHistory = false;
                if (selectedFileRef.current !== requestedFile) return;
                if (createNewAnalysis !== true && ACTIVE_ANALYSIS_STATUSES.has(job?.status)) {
                    setError(
                        '当前分析仍在运行；未找到同文件历史，本次未新建任务。请先读取已有任务或等待当前分析完成。',
                    );
                    return;
                }
                if (!inputGenomeAssemblySupported) {
                    setError('Choose a supported input build: GRCh37 or GRCh38.');
                    return;
                }
                if (analysisInputError || trioValidationError) {
                    setError(analysisInputError || trioValidationError);
                    return;
                }
                const readiness = await loadPreflightStatus(inputGenomeAssembly);
                if (selectedFileRef.current !== requestedFile) return;
                if (!readiness) {
                    setError(
                        'Analysis was not submitted because base-analysis readiness could not be verified.',
                    );
                    return;
                }
                if (!readiness.applicationReady) {
                    setError(
                        readiness.blockingErrors?.[0] ||
                            'Analysis was not submitted because a required component is unavailable.',
                    );
                    return;
                }
                setHistoryMatches(null);
                setAnalysisEvents([]);
                setVariantsPage(EMPTY_PAGE);
                setVariantPageNumber(1);
                setVariantPageSize(DEFAULT_VARIANT_PAGE_SIZE);
                setPollingNotice('');
                setVariantView(VARIANT_VIEW_CANDIDATES);
                setVariantFilter(VARIANT_FILTER_ALL);
                setVariantBucket(VARIANT_BUCKET_ALL);
                setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
                setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
                setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
                setAcmgCodeFilter(ACMG_CODE_ALL);
                setAcmgOnlySuggested(false);
                setAcmgManualReviewRequired(false);
                setVariantSearch('');
                setDebouncedVariantSearch('');
                clearVariantSelection();
                setAdvancedFiltersExpanded(false);
                setEnhancement(null);
                const submitted = await submitVariantAnalysis({
                    file: requestedFile,
                    genomeAssembly: inputGenomeAssembly,
                    analysisMode,
                    analysisScope,
                    createNewAnalysis,
                    sampleId,
                    hpoTerms: parsedHpoTerms,
                    excludedHpoTerms: parsedExcludedHpoTerms,
                    hpoProvenance,
                    trioInput: trioEnabled
                        ? {
                              enabled: true,
                              inputMode: trioInputMode,
                              probandSampleId: effectiveTrioProbandSampleId,
                              fatherSampleId: trioFatherSampleId,
                              motherSampleId: trioMotherSampleId,
                              probandSex: trioProbandSex,
                              fatherSex: trioFatherSex,
                              motherSex: trioMotherSex,
                              probandAffected: trioProbandAffected === 'true',
                              fatherAffected: trioFatherAffected === 'true',
                              motherAffected: trioMotherAffected === 'true',
                              evidenceOnly: true,
                          }
                        : null,
                });
                const restored =
                    submitted?.status === 'READY'
                        ? await fetchVariantAnalysis(submitted.id)
                        : submitted;
                activateAnalysisJob(restored);
                await loadEvents(restored?.id);
                if (restored?.status === 'READY') {
                    await loadVariants(restored.id);
                    if (createNewAnalysis !== true) {
                        setPollingNotice('已读取完全相同输入的历史分析结果，本次没有重复运行。');
                    }
                }
            } catch (submitError) {
                if (selectedFileRef.current === requestedFile) {
                    const failure = analysisRequestErrorMessage(submitError);
                    setError(
                        checkingHistory
                            ? `同文件历史查询失败，本次未提交新分析：${failure}`
                            : failure,
                    );
                }
            } finally {
                setBusy(false);
            }
        },
        [
            activateAnalysisJob,
            analysisMode,
            analysisScope,
            analysisInputError,
            busy,
            clearVariantSelection,
            inputGenomeAssembly,
            inputGenomeAssemblySupported,
            job?.status,
            loadPreflightStatus,
            loadEvents,
            loadVariants,
            effectiveTrioProbandSampleId,
            hpoProvenance,
            parsedHpoTerms,
            parsedExcludedHpoTerms,
            sampleId,
            selectedFile,
            trioEnabled,
            trioFatherSampleId,
            trioFatherSex,
            trioFatherAffected,
            trioInputMode,
            trioMotherSampleId,
            trioMotherSex,
            trioMotherAffected,
            trioProbandSex,
            trioProbandAffected,
            trioValidationError,
        ],
    );

    const handleVariantViewChange = useCallback(
        (nextView) => {
            if (nextView === variantView) return;
            setVariantView(nextView);
            setVariantPageNumber(1);
            clearVariantSelection();
        },
        [clearVariantSelection, variantView],
    );

    const handleClearVariantFilters = useCallback(() => {
        setVariantPageNumber(1);
        setVariantFilter(VARIANT_FILTER_ALL);
        setVariantBucket(VARIANT_BUCKET_ALL);
        setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
        setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
        setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
        setAcmgCodeFilter(ACMG_CODE_ALL);
        setAcmgOnlySuggested(false);
        setAcmgManualReviewRequired(false);
        setVariantSearch('');
        setDebouncedVariantSearch('');
        clearVariantSelection();
    }, [clearVariantSelection]);

    const handleVariantPageChange = useCallback(
        (_event, nextPage) => {
            setVariantPageNumber(Math.max(1, Number(nextPage) || 1));
            clearVariantSelection();
        },
        [clearVariantSelection],
    );

    const handleVariantPageSizeChange = useCallback(
        (event) => {
            setVariantPageSize(Number(event.target.value) || DEFAULT_VARIANT_PAGE_SIZE);
            setVariantPageNumber(1);
            clearVariantSelection();
        },
        [clearVariantSelection],
    );

    const handlePreviewVariant = useCallback(
        (variant) => {
            const payload = variantNavigationPayload(variant, 'preview');
            if (payload) {
                onVariantPreview?.(payload);
            }
        },
        [onVariantPreview],
    );

    const handleSelectVariant = useCallback(
        async (variant, options = {}) => {
            if (options.preview) {
                handlePreviewVariant(variant);
            }
            cancelDetailRequest();
            cancelRankingExplanationRequest();
            setSelectedVariant(variant);
            setDetailTab(DETAIL_TAB_OVERVIEW);
            const analysisId = String(job?.id || '');
            const candidateId = candidateKey(variant);
            const canonicalVariantId = canonicalKey(variant);
            const key =
                analysisId && candidateId
                    ? rankingExplanationCacheKey(analysisId, candidateId)
                    : '';
            selectedRankingExplanationKeyRef.current = key;
            setRankingExplanation(key ? rankingExplanationCacheRef.current.get(key) || null : null);
            setRankingExplanationErrorCode('');
            if (!analysisId || !candidateId) return;
            const controller = new AbortController();
            const sequence = detailRequestRef.current.sequence + 1;
            detailRequestRef.current = { sequence, key, controller };
            setDetailLoadingCandidateId(candidateId);
            try {
                const detail = await fetchVariantAnalysisCandidate(analysisId, variant, {
                    signal: controller.signal,
                });
                const current = detailRequestRef.current;
                if (
                    controller.signal.aborted ||
                    current.sequence !== sequence ||
                    current.key !== key ||
                    selectedRankingExplanationKeyRef.current !== key ||
                    !isAnalysisRequestCurrent(analysisId)
                ) {
                    return;
                }
                if (
                    !detail ||
                    typeof detail !== 'object' ||
                    Array.isArray(detail) ||
                    candidateKey(detail) !== candidateId ||
                    (canonicalVariantId && canonicalKey(detail) !== canonicalVariantId)
                ) {
                    setError('Candidate detail identity could not be verified.');
                    return;
                }
                setSelectedVariant(detail);
            } catch (detailError) {
                const current = detailRequestRef.current;
                if (
                    isCanceledRequest(detailError) ||
                    controller.signal.aborted ||
                    current.sequence !== sequence ||
                    current.key !== key ||
                    selectedRankingExplanationKeyRef.current !== key ||
                    !isAnalysisRequestCurrent(analysisId)
                ) {
                    return;
                }
                if (isNotFoundError(detailError)) {
                    clearVariantSelection();
                    setError(
                        'This candidate is no longer available. The analysis job was left unchanged.',
                    );
                } else {
                    setError('Could not load variant detail.');
                }
            } finally {
                const current = detailRequestRef.current;
                if (
                    current.sequence === sequence &&
                    current.key === key &&
                    selectedRankingExplanationKeyRef.current === key &&
                    isAnalysisRequestCurrent(analysisId)
                ) {
                    setDetailLoadingCandidateId((current) =>
                        current === candidateId ? '' : current,
                    );
                    detailRequestRef.current = { sequence, key, controller: null };
                }
            }
        },
        [
            cancelDetailRequest,
            cancelRankingExplanationRequest,
            clearVariantSelection,
            handlePreviewVariant,
            isAnalysisRequestCurrent,
            job?.id,
        ],
    );

    const handleFocusTopCandidate = useCallback(async () => {
        if (!topLoadedVariant) return;
        await handleSelectVariant(topLoadedVariant);
        window.setTimeout(() => {
            detailSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }, 50);
    }, [handleSelectVariant, topLoadedVariant]);

    useEffect(() => {
        const requestId = visualVariantSelection?.requestId;
        if (!requestId || !job?.id || job.status !== 'READY') return;

        const requestedCandidateId = String(
            visualVariantSelection?.candidateId ||
                visualVariantSelection?.analysisCandidateId ||
                '',
        ).trim();
        const targetKeys = [
            visualVariantSelection?.exactKey,
            visualVariantSelection?.canonicalVariantId,
            visualVariantSelection?.variantId,
            visualVariantSelection?.displayId,
        ]
            .map(normalizeVariantExactKey)
            .filter(Boolean);
        if (!requestedCandidateId && !targetKeys.length) return;

        const signature = `${requestId}:${requestedCandidateId}:${targetKeys.join('|')}`;
        if (!requestedCandidateId) {
            if (visualSelectionHandledRef.current === signature) return;
            visualSelectionHandledRef.current = signature;
            visualSelectionSearchRef.current = signature;
            const searchText = visualSelectionSearchText(visualVariantSelection);
            if (searchText) {
                setVariantView(VARIANT_VIEW_CANDIDATES);
                setVariantFilter(VARIANT_FILTER_ALL);
                setVariantBucket(VARIANT_BUCKET_ALL);
                setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
                setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
                setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
                setVariantSearch(searchText);
            }
            setPollingNotice(
                'Visualization selection identifies an allele but not a candidate row. Review the filtered candidates and select the intended row.',
            );
            return;
        }

        const variants = variantsPage?.variants || [];
        const matchedVariants = variants.filter(
            (variant) => candidateKey(variant) === requestedCandidateId,
        );

        if (matchedVariants.length > 1) {
            if (visualSelectionHandledRef.current === signature) return;
            visualSelectionHandledRef.current = signature;
            setPollingNotice(
                'Multiple Analysis Lab candidates share this canonical variant. Select the intended candidate row in the table.',
            );
            return;
        }

        if (matchedVariants.length === 1) {
            if (visualSelectionHandledRef.current === signature) return;
            visualSelectionHandledRef.current = signature;
            handleSelectVariant(matchedVariants[0], { preview: false });
            setPollingNotice('Visualization selection matched an Analysis Lab candidate.');
            window.setTimeout(() => {
                detailSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }, 50);
            return;
        }

        if (visualSelectionSearchRef.current === signature) return;
        visualSelectionSearchRef.current = signature;
        const searchText = requestedCandidateId;
        if (searchText) {
            setVariantView(VARIANT_VIEW_CANDIDATES);
            setVariantFilter(VARIANT_FILTER_ALL);
            setVariantBucket(VARIANT_BUCKET_ALL);
            setSummaryOnlyFilter(BOOLEAN_FILTER_ALL);
            setLargeFileModeFilter(BOOLEAN_FILTER_ALL);
            setSkippedAnnotationFilter(BOOLEAN_FILTER_ALL);
            setVariantSearch(searchText);
            setPollingNotice(
                'Visualization selection received; searching backend-filtered candidates.',
            );
        }
    }, [handleSelectVariant, job?.id, job?.status, variantsPage?.variants, visualVariantSelection]);

    const handleQuickVariantFilter = useCallback(
        (nextFilter) => {
            setVariantPageNumber(1);
            clearVariantSelection();
            setVariantFilter((current) =>
                current === nextFilter ? VARIANT_FILTER_ALL : nextFilter,
            );
        },
        [clearVariantSelection],
    );

    const handleBucketFilterChange = useCallback(
        (nextBucket) => {
            const normalizedBucket = nextBucket || VARIANT_BUCKET_ALL;
            setVariantPageNumber(1);
            clearVariantSelection();
            setVariantBucket((current) =>
                current === normalizedBucket ? VARIANT_BUCKET_ALL : normalizedBucket,
            );
            if (normalizedBucket !== VARIANT_BUCKET_ALL && variantView !== VARIANT_VIEW_ALL) {
                setVariantView(VARIANT_VIEW_ALL);
            }
        },
        [clearVariantSelection, variantView],
    );

    const handleToggleAcmgSuggested = useCallback(() => {
        setVariantPageNumber(1);
        clearVariantSelection();
        setAcmgOnlySuggested((current) => !current);
    }, [clearVariantSelection]);

    const handleToggleAcmgManualReview = useCallback(() => {
        setVariantPageNumber(1);
        clearVariantSelection();
        setAcmgManualReviewRequired((current) => !current);
    }, [clearVariantSelection]);

    const handleEnhance = useCallback(async () => {
        if (!job?.id || job.status !== 'READY' || busy) return;
        setBusy(true);
        setError('');
        try {
            setEnhancement(await submitAlphaGenomeEnhancement(job.id, candidateLimit));
        } catch (submitError) {
            setError(
                submitError?.response?.data?.message ||
                    submitError?.message ||
                    'Could not submit AlphaGenome enhancement.',
            );
        } finally {
            setBusy(false);
        }
    }, [busy, candidateLimit, job?.id, job?.status]);

    const handleExportVariants = useCallback(
        async (format) => {
            if (!job?.id || job.status !== 'READY' || exportingFormat) return;
            setExportingFormat(format);
            setError('');
            try {
                const result = await downloadVariantAnalysisExport(job.id, format, {
                    limit: format === 'csv' ? MAX_CSV_EXPORT_ROWS : MAX_JSON_EXPORT_ROWS,
                    view: variantView,
                    filters: variantQueryOptions,
                });
                downloadBlobFile(result.blob, result.filename);
            } catch (exportError) {
                setError(
                    exportError?.response?.data?.message ||
                        exportError?.message ||
                        `Could not export ${String(format || '').toUpperCase()} report.`,
                );
            } finally {
                setExportingFormat('');
            }
        },
        [exportingFormat, job?.id, job?.status, variantQueryOptions, variantView],
    );

    const handleRemove = useCallback(async () => {
        if (!job?.id || ACTIVE_ANALYSIS_STATUSES.has(job.status) || busy) return;
        const analysisToRemove = job;
        const analysisId = job.id;
        setBusy(true);
        setError('');
        resetAnalysisState(analysisId);
        try {
            await removeVariantAnalysis(analysisId);
            resetSubmitForm();
            setPollingNotice(analysisRemovedNotice);
        } catch (removeError) {
            if (isNotFoundError(removeError)) {
                resetSubmitForm();
                setPollingNotice(analysisRemovedNotice);
            } else if (isRequestTimeout(removeError)) {
                resetSubmitForm();
                setPollingNotice(analysisRemovePendingNotice);
            } else {
                removedAnalysisIdsRef.current.delete(analysisId);
                activateAnalysisJob(analysisToRemove);
                setError(removeError?.message || 'Could not remove analysis.');
            }
        } finally {
            setBusy(false);
        }
    }, [activateAnalysisJob, busy, job, resetAnalysisState, resetSubmitForm]);

    const handleLocate = useCallback(() => {
        const payload = variantNavigationPayload(selectedVariant, 'locate');
        if (payload) {
            onVariantNavigate?.(payload);
        }
    }, [onVariantNavigate, selectedVariant]);

    const isAnalyzing = ACTIVE_ANALYSIS_STATUSES.has(job?.status);
    const isEnhancing = ACTIVE_ENHANCEMENT_STATUSES.has(enhancement?.status);
    const alphaGenomeReady = providerStatus?.alphaGenome?.ready !== false;
    const alphaGenomeDisabledReason = !alphaGenomeReady
        ? providerStatus?.alphaGenome?.message
        : evidenceSummary.alphaGenomeCandidates < 1
          ? 'No noncoding or splice-related candidate with an applicable CADD score is available.'
          : '';
    const canEnhance =
        job?.status === 'READY' &&
        !busy &&
        !isEnhancing &&
        !isFrequencyFilteredView &&
        alphaGenomeReady &&
        evidenceSummary.alphaGenomeCandidates > 0;
    const canExport = job?.status === 'READY' && !busy && !exportingFormat;
    const enhancementNote =
        enhancement?.status === 'FAILED'
            ? enhancement.errorMessage || 'AlphaGenome enhancement failed.'
            : enhancement?.selectedCandidateCount === 0 && enhancement?.status === 'READY'
              ? 'No variants met the AlphaGenome pre-screen: noncoding/splice consequence plus applicable CADD score.'
              : enhancement?.unavailableCount > 0
                ? 'Some selected candidates could not be enhanced; inspect each evidence card for the provider message.'
                : enhancement?.completedCount > 0
                  ? 'AlphaGenome evidence has been appended to the selected candidates and ranking was refreshed.'
                  : '';

    const renderSampleRoleField = (label, value, onChange, choices, testId) => {
        const normalizedChoices = mergeSampleIdChoices(choices, value ? [value] : []);
        if (!normalizedChoices.length) {
            return (
                <TextField
                    size="small"
                    label={label}
                    value={value}
                    onChange={(event) => onChange(event.target.value)}
                    className="cv-analysis-trio-sample-field"
                    inputProps={testId ? { 'data-testid': testId } : undefined}
                />
            );
        }
        return (
            <FormControl size="small" className="cv-analysis-trio-sample-field">
                <InputLabel>{label}</InputLabel>
                <Select
                    value={value}
                    label={label}
                    onChange={(event) => onChange(event.target.value)}
                    data-testid={testId}
                >
                    {normalizedChoices.map((choice) => (
                        <MenuItem key={choice} value={choice}>
                            {sampleDisplayLabel(choice)}
                        </MenuItem>
                    ))}
                </Select>
            </FormControl>
        );
    };

    const renderTrioAttributeField = (label, value, onChange, options, testId) => (
        <FormControl size="small" className="cv-analysis-trio-sample-field">
            <InputLabel>{label}</InputLabel>
            <Select
                value={value}
                label={label}
                onChange={(event) => onChange(event.target.value)}
                data-testid={testId}
            >
                <MenuItem value="">Select</MenuItem>
                {options.map((option) => (
                    <MenuItem key={option.value} value={option.value}>
                        {option.label}
                    </MenuItem>
                ))}
            </Select>
        </FormControl>
    );

    const renderDetectionSummary = (result, fallback) => {
        const samples = Array.isArray(result?.samples) ? result.samples : [];
        if (!result && !fallback) return null;
        return (
            <Alert severity={sampleDetectionSeverity(result)} className="cv-analysis-inline-alert">
                <Stack gap={0.75}>
                    <Typography variant="caption">
                        {sampleDetectionMessage(result, fallback)}
                    </Typography>
                    {samples.length ? (
                        <Stack direction="row" gap={0.5} flexWrap="wrap">
                            {samples.slice(0, 12).map((sample) => (
                                <Chip
                                    key={sample}
                                    size="small"
                                    label={sampleDisplayLabel(sample)}
                                />
                            ))}
                            {samples.length > 12 ? (
                                <Chip size="small" label={`+${samples.length - 12} more`} />
                            ) : null}
                        </Stack>
                    ) : null}
                </Stack>
            </Alert>
        );
    };

    if (!open) {
        return null;
    }

    return (
        <Box className="cv-analysis-dock">
            <Box className="cv-analysis-panel" aria-busy={busy || isAnalyzing || isEnhancing}>
                <Box className="cv-analysis-header">
                    <Stack direction="row" alignItems="center" gap={1.5}>
                        <span className="cv-analysis-header-icon">
                            <BiotechRoundedIcon />
                        </span>
                        <Typography variant="h6">Variant Analysis Lab</Typography>
                    </Stack>
                    <IconButton aria-label="Close analysis panel" onClick={handleDrawerClose}>
                        <CloseRoundedIcon />
                    </IconButton>
                </Box>

                <Box className="cv-analysis-content">
                    <section className="cv-analysis-section cv-analysis-submit-card">
                        <Stack direction="row" justifyContent="space-between" gap={2}>
                            <Typography variant="subtitle1">
                                {viewingRestoredAnalysis ? '1. 已读取历史分析' : '1. Submit a VCF'}
                            </Typography>
                            <Chip
                                size="small"
                                variant="outlined"
                                color="primary"
                                label={viewingRestoredAnalysis ? '历史任务' : `${inputGenomeAssembly} analysis`}
                            />
                        </Stack>
                        <input
                            ref={fileInputRef}
                            type="file"
                            accept=".vcf,.vcf.gz"
                            hidden
                            data-testid="primary-vcf-input"
                            onChange={(event) =>
                                handleSelectFile(event?.target?.files?.[0] || null)
                            }
                        />
                        {viewingRestoredAnalysis ? (
                            <Alert severity="info" className="cv-analysis-restored-state">
                                <Stack
                                    direction={{ xs: 'column', sm: 'row' }}
                                    justifyContent="space-between"
                                    alignItems={{ sm: 'center' }}
                                    gap={1.5}
                                >
                                    <Box sx={{ minWidth: 0 }}>
                                        <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                                            {job.id} · {formatStatus(job.status)} · {job.genomeAssembly || '版本未记录'} ·{' '}
                                            {job.analysisMode || '模式未记录'} · 样本：
                                            {job.selectedSampleId || job.requestedSampleId || '未记录'} ·{' '}
                                            {ANALYSIS_SCOPE_LABELS[job.analysisScopePolicy?.scope] ?? ANALYSIS_SCOPE_LABELS.STANDARD} ·{' '}
                                            {formatEventTime(job.createdAt)}
                                        </Typography>
                                    </Box>
                                    <Stack className="cv-analysis-restored-actions" direction="row" gap={1}>
                                        <Button
                                            fullWidth
                                            variant="outlined"
                                            onClick={() => {
                                                setRestoredHistoryId('');
                                                resetSubmitForm();
                                                setRestoreAnalysisId('');
                                            }}
                                        >
                                            读取其他历史任务
                                        </Button>
                                        <Button
                                            fullWidth
                                            variant="contained"
                                            disableElevation
                                            onClick={() => {
                                                resetAnalysisState(job.id);
                                                resetSubmitForm();
                                                setRestoreAnalysisId('');
                                            }}
                                        >
                                            新建分析
                                        </Button>
                                    </Stack>
                                </Stack>
                            </Alert>
                        ) : <>
                        {analysisScope === 'INCLUDE_INTRONS' && (
                            <Typography variant="caption">仅分析 VCF 中已记录的内含子位点。</Typography>
                        )}
                        <Stack className="cv-analysis-form-row" direction="row" gap={1.25}>
                            <Button
                                variant="outlined"
                                startIcon={<UploadFileRoundedIcon />}
                                onClick={() => fileInputRef.current?.click?.()}
                            >
                                Choose VCF
                            </Button>
                            <Typography className="cv-analysis-file-name" variant="body2">
                                {selectedFile?.name || 'No file selected'}
                            </Typography>
                            <Button
                                variant="outlined"
                                size="small"
                                disabled={!selectedFile || sampleDetectionLoading === 'primary'}
                                data-testid="primary-sample-detect-button"
                                onClick={() => detectSamplesForFile('primary', selectedFile)}
                            >
                                {sampleDetectionLoading === 'primary'
                                    ? 'Detecting...'
                                    : 'Detect samples'}
                            </Button>
                        </Stack>
                        {analysisInputError ? (
                            <Typography className="cv-analysis-warning" variant="caption">
                                {analysisInputError}
                            </Typography>
                        ) : null}
                        {renderDetectionSummary(primarySampleDetection, sampleDetectionNote)}
                        <Box className="cv-analysis-history-row">
                            <TextField
                                size="small"
                                label="已有分析任务 ID（可选）"
                                value={restoreAnalysisId}
                                onChange={(event) => setRestoreAnalysisId(event.target.value)}
                                fullWidth
                                className="cv-analysis-history-id"
                            />
                            <Button
                                variant="outlined"
                                disabled={!restoreAnalysisId.trim() || busy}
                                onClick={() => restoreAnalysis()}
                            >
                                {historyRead ? '正在读取…' : '读取已有分析'}
                            </Button>
                        </Box>
                        {historyRead && <AnalysisLoadingNotice startedAt={historyRead.startedAt}
                            title={historyRead.stage} />}
                        {job ? (
                            <Typography
                                variant="caption"
                                component="p"
                                sx={{ mt: 1, overflowWrap: 'anywhere' }}
                            >
                                Analysis ID: {job.id}
                            </Typography>
                        ) : null}
                        {historyMatches?.length ? (
                            <Stack
                                gap={1.25}
                                sx={{ mt: 2 }}
                                data-testid="same-file-analysis-history"
                            >
                                <Alert severity="info">
                                    找到 {historyMatches.length} 条字节完全相同的 VCF
                                    任务，本次尚未提交新分析。
                                    读取历史保留原样本、分析条件和资源版本，不代表与当前配置等价。
                                </Alert>
                                {historyMatches.map((match) => (
                                    <Box key={match.id}>
                                        <Typography
                                            variant="body2"
                                            sx={{ overflowWrap: 'anywhere' }}
                                        >
                                            {match.id} · {formatStatus(match.status)} ·{' '}
                                            {match.genomeAssembly}
                                            {' · '}
                                            {match.analysisMode} · 样本：
                                            {match.selectedSampleId ||
                                                match.requestedSampleId ||
                                                '未记录'}
                                            {' · '}
                                            {formatEventTime(match.createdAt)}
                                        </Typography>
                                        <Button
                                            variant="outlined"
                                            size="small"
                                            disabled={busy}
                                            onClick={() => restoreAnalysis(match.id)}
                                        >
                                            {match.status === 'READY'
                                                ? '读取历史结果'
                                                : ACTIVE_ANALYSIS_STATUSES.has(match.status)
                                                  ? '查看运行中任务'
                                                  : '查看历史任务'}
                                        </Button>
                                    </Box>
                                ))}
                                <Button
                                    variant="outlined"
                                    disabled={busy || preflightBlocksSubmission}
                                    data-testid="variant-analysis-create-new-button"
                                    onClick={() => handleSubmit({ createNewAnalysis: true })}
                                >
                                    仍按当前配置新建分析
                                </Button>
                            </Stack>
                        ) : null}
                        <Stack className="cv-analysis-form-row" direction="row" gap={1.25}>
                            <FormControl size="small" className="cv-analysis-build-field">
                                <InputLabel id="cv-analysis-build-label">Input build</InputLabel>
                                <Select
                                    labelId="cv-analysis-build-label"
                                    value={inputGenomeAssembly}
                                    label="Input build"
                                    data-testid="input-build-select"
                                    onChange={(event) => {
                                        setInputGenomeAssembly(event.target.value);
                                        setAssemblySelectedManually(true);
                                    }}
                                >
                                    {SUPPORTED_GENOME_ASSEMBLIES.map((assembly) => (
                                        <MenuItem key={assembly} value={assembly}>
                                            {assembly} / {assembly === 'GRCh37' ? 'hg19' : 'hg38'}
                                        </MenuItem>
                                    ))}
                                </Select>
                            </FormControl>
                            <FormControl size="small" className="cv-analysis-mode-field">
                                <InputLabel id="cv-analysis-mode-label">Mode</InputLabel>
                                <Select
                                    labelId="cv-analysis-mode-label"
                                    value={analysisMode}
                                    label="Mode"
                                    data-testid="analysis-mode-select"
                                    onChange={(event) => setAnalysisMode(event.target.value)}
                                >
                                    <MenuItem value="WES">WES</MenuItem>
                                    <MenuItem value="WGS">WGS</MenuItem>
                                    <MenuItem value="PANEL">Panel</MenuItem>
                                </Select>
                            </FormControl>
                            <FormControl size="small" className="cv-analysis-mode-field">
                                <InputLabel id="cv-analysis-scope-label">分析范围</InputLabel>
                                <Select
                                    labelId="cv-analysis-scope-label"
                                    value={analysisScope}
                                    label="分析范围"
                                    onChange={(event) => setAnalysisScope(event.target.value)}
                                >
                                    <MenuItem value="STANDARD">常规分析（默认）</MenuItem>
                                    <MenuItem value="INCLUDE_INTRONS">包含内含子变异</MenuItem>
                                    <MenuItem value="SINGLE_VARIANT_REVIEW">单变异核查</MenuItem>
                                </Select>
                            </FormControl>
                            <TextField
                                size="small"
                                label="Sample ID"
                                value={sampleId}
                                onChange={(event) => setSampleId(event.target.value)}
                                className="cv-analysis-sample-field"
                                inputProps={{ 'data-testid': 'sample-id-input' }}
                            />
                            <Button
                                variant="contained"
                                disableElevation
                                data-testid="variant-analysis-submit-button"
                                disabled={!selectedFile || busy}
                                onClick={() => handleSubmit()}
                            >
                                Analyze
                            </Button>
                        </Stack>
                        {analysisScope === 'SINGLE_VARIANT_REVIEW' && (
                            <Typography variant="body2">
                                GRCh38 单样本 VCF，须仅包含一个携带 ALT 的常染色体双等位 SNV。
                            </Typography>
                        )}
                        {preflightLoading ? (
                            <Alert severity="info">Checking base-analysis readiness...</Alert>
                        ) : preflightError ? (
                            <Alert severity="warning">
                                New analysis is blocked until readiness can be verified; saved tasks
                                can still be opened: {preflightError}
                            </Alert>
                        ) : preflightStatus ? (
                            <Alert
                                severity={
                                    preflightStatus.applicationReady
                                        ? preflightStatus.readinessStatus === 'READY'
                                            ? 'success'
                                            : 'warning'
                                        : 'error'
                                }
                            >
                                {preflightStatus.applicationReady
                                    ? `${preflightStatus.defaultEngine} base analysis is ready on ${
                                          preflightStatus.runtimePlatform || 'the current host'
                                      } for ${preflightStatus.requestedInputAssembly}. ${
                                          optionalLimitationCount
                                              ? `${optionalLimitationCount} optional component(s) are limited.`
                                              : 'All checked components are ready.'
                                      }`
                                    : `Submission blocked: ${
                                          preflightStatus.blockingErrors?.[0] ||
                                          'a required component is unavailable.'
                                      }`}
                            </Alert>
                        ) : null}
                        <Box className="cv-analysis-trio-input-block">
                            <Stack direction="row" gap={1.25} flexWrap="wrap" alignItems="center">
                                <FormControl size="small" className="cv-analysis-trio-mode-field">
                                    <InputLabel id="cv-analysis-trio-mode-label">
                                        Family mode
                                    </InputLabel>
                                    <Select
                                        labelId="cv-analysis-trio-mode-label"
                                        value={trioInputMode}
                                        label="Family mode"
                                        data-testid="family-mode-select"
                                        onChange={(event) => {
                                            const nextMode = event.target.value;
                                            setTrioInputMode(nextMode);
                                            setError('');
                                        }}
                                    >
                                        <MenuItem value={TRIO_INPUT_MODE_DISABLED}>
                                            Single sample
                                        </MenuItem>
                                        <MenuItem value={TRIO_INPUT_MODE_JOINT}>
                                            Trio joint VCF
                                        </MenuItem>
                                    </Select>
                                </FormControl>
                            </Stack>
                            {trioEnabled ? (
                                <>
                                    <Stack
                                        className="cv-analysis-trio-fields"
                                        direction="row"
                                        gap={1.25}
                                        flexWrap="wrap"
                                    >
                                        {renderSampleRoleField(
                                            'Proband sample ID',
                                            effectiveTrioProbandSampleId,
                                            (value) => {
                                                setTrioProbandSampleId(value);
                                                setSampleId((current) => current || value);
                                            },
                                            trioProbandSampleChoices,
                                            'trio-proband-sample-select',
                                        )}
                                        {renderSampleRoleField(
                                            'Father sample ID',
                                            trioFatherSampleId,
                                            setTrioFatherSampleId,
                                            trioFatherSampleChoices,
                                            'trio-father-sample-select',
                                        )}
                                        {renderSampleRoleField(
                                            'Mother sample ID',
                                            trioMotherSampleId,
                                            setTrioMotherSampleId,
                                            trioMotherSampleChoices,
                                            'trio-mother-sample-select',
                                        )}
                                    </Stack>
                                    <Stack
                                        className="cv-analysis-trio-fields"
                                        direction="row"
                                        gap={1.25}
                                        flexWrap="wrap"
                                    >
                                        {renderTrioAttributeField(
                                            'Proband sex',
                                            trioProbandSex,
                                            setTrioProbandSex,
                                            [
                                                { value: 'MALE', label: 'Male' },
                                                { value: 'FEMALE', label: 'Female' },
                                            ],
                                            'trio-proband-sex-select',
                                        )}
                                        {renderTrioAttributeField(
                                            'Father sex',
                                            trioFatherSex,
                                            setTrioFatherSex,
                                            [
                                                { value: 'MALE', label: 'Male' },
                                                { value: 'FEMALE', label: 'Female' },
                                            ],
                                            'trio-father-sex-select',
                                        )}
                                        {renderTrioAttributeField(
                                            'Mother sex',
                                            trioMotherSex,
                                            setTrioMotherSex,
                                            [
                                                { value: 'MALE', label: 'Male' },
                                                { value: 'FEMALE', label: 'Female' },
                                            ],
                                            'trio-mother-sex-select',
                                        )}
                                        {renderTrioAttributeField(
                                            'Proband status',
                                            trioProbandAffected,
                                            setTrioProbandAffected,
                                            [
                                                { value: 'true', label: 'Affected' },
                                                { value: 'false', label: 'Unaffected' },
                                            ],
                                            'trio-proband-affected-select',
                                        )}
                                        {renderTrioAttributeField(
                                            'Father status',
                                            trioFatherAffected,
                                            setTrioFatherAffected,
                                            [
                                                { value: 'true', label: 'Affected' },
                                                { value: 'false', label: 'Unaffected' },
                                            ],
                                            'trio-father-affected-select',
                                        )}
                                        {renderTrioAttributeField(
                                            'Mother status',
                                            trioMotherAffected,
                                            setTrioMotherAffected,
                                            [
                                                { value: 'true', label: 'Affected' },
                                                { value: 'false', label: 'Unaffected' },
                                            ],
                                            'trio-mother-affected-select',
                                        )}
                                    </Stack>
                                    <Typography variant="caption" color="text.secondary">
                                        Upload one joint VCF containing all three samples. Separate
                                        VCF files are not merged.
                                    </Typography>
                                    {renderDetectionSummary(
                                        primarySampleDetection,
                                        sampleDetectionNote,
                                    )}
                                    {trioValidationError && (
                                        <Alert severity="warning" className="cv-analysis-inline-alert">
                                            {trioValidationError}
                                        </Alert>
                                    )}
                                </>
                            ) : null}
                        </Box>
                        <Box className="cv-analysis-hpo-input-block">
                            <Alert severity="info" className="cv-analysis-inline-alert">
                                Narrative-to-HPO suggestions are not configured.
                            </Alert>
                            <TextField
                                fullWidth
                                multiline
                                minRows={2}
                                maxRows={4}
                                size="small"
                                label="Confirmed HPO terms (optional)"
                                placeholder="HP:0001250, HP:0004322"
                                value={hpoTermsInput}
                                onChange={(event) => setHpoTermsInput(event.target.value)}
                            />
                            {parsedHpoTerms.length ? (
                                <Stack direction="row" gap={0.5} flexWrap="wrap" mt={1}>
                                    {parsedHpoTerms.slice(0, 12).map((term, index) => (
                                        <Chip
                                            key={`${term}-${index}`}
                                            size="small"
                                            variant="outlined"
                                            label={term}
                                        />
                                    ))}
                                </Stack>
                            ) : null}
                            {invalidHpoTerms.length ? (
                                <Alert severity="warning" className="cv-analysis-inline-alert">
                                    Use HPO IDs in the HP:1234567 format.
                                </Alert>
                            ) : null}
                            <TextField
                                fullWidth
                                multiline
                                minRows={2}
                                maxRows={4}
                                size="small"
                                label="Absent HPO terms (optional, review only)"
                                placeholder="HP:0001250, HP:0004322"
                                value={excludedHpoTermsInput}
                                onChange={(event) => setExcludedHpoTermsInput(event.target.value)}
                            />
                            {parsedExcludedHpoTerms.length ? (
                                <Stack direction="row" gap={0.5} flexWrap="wrap" mt={1}>
                                    {parsedExcludedHpoTerms.slice(0, 12).map((term, index) => (
                                        <Chip
                                            key={`excluded-${term}-${index}`}
                                            size="small"
                                            color="warning"
                                            variant="outlined"
                                            label={`${term} absent`}
                                        />
                                    ))}
                                </Stack>
                            ) : null}
                            {invalidExcludedHpoTerms.length ? (
                                <Alert severity="warning" className="cv-analysis-inline-alert">
                                    Explicitly absent terms must use HPO IDs in the HP:1234567
                                    format.
                                </Alert>
                            ) : null}
                            {conflictingHpoTerms.length ? (
                                <Alert severity="error" className="cv-analysis-inline-alert">
                                    Present/absent conflict: {conflictingHpoTerms.join(', ')}
                                </Alert>
                            ) : null}
                            <PhenotypeProvenancePanel
                                inputs={hpoProvenance}
                                title="HPO provenance to submit"
                            />
                        </Box>
                        {sampleIdChoices.length ? (
                            <Box className="cv-analysis-sample-picker">
                                <Typography variant="caption" color="text.secondary">
                                    Sample IDs detected. Multi-sample VCFs require one selected
                                    sample before ranking.
                                </Typography>
                                <Stack direction="row" gap={0.75} flexWrap="wrap">
                                    {sampleIdChoices.map((choice) => (
                                        <Chip
                                            key={choice}
                                            size="small"
                                            clickable
                                            color={sampleId === choice ? 'primary' : 'default'}
                                            variant={sampleId === choice ? 'filled' : 'outlined'}
                                            label={sampleDisplayLabel(choice)}
                                            onClick={() => handleSelectSampleId(choice)}
                                        />
                                    ))}
                                </Stack>
                            </Box>
                        ) : null}
                        {sampleDetectionNote ? (
                            <Typography className="cv-analysis-warning" variant="caption">
                                {sampleDetectionNote}
                            </Typography>
                        ) : null}
                        {inputGenomeAssembly === 'GRCh37' ? (
                            <Typography className="cv-analysis-warning" variant="caption">
                                GRCh37 input is converted to GRCh38 for analysis.
                            </Typography>
                        ) : null}
                        {preflightStatus?.requiredComponents?.length ? (
                            <Stack direction="row" gap={0.75} flexWrap="wrap" alignItems="center">
                                {preflightStatus.requiredComponents.map((component) => (
                                    <Chip
                                        key={component.component}
                                        size="small"
                                        color={providerTone(component)}
                                        variant="outlined"
                                        label={`${component.component} ${providerReadinessLabel(
                                            component,
                                        )}`}
                                    />
                                ))}
                            </Stack>
                        ) : null}
                        </>}
                    </section>

                    {error ? <Alert severity="error">{error}</Alert> : null}
                    {pollingNotice ? <Alert severity="info">{pollingNotice}</Alert> : null}
                    {providerStatusError ? (
                        <Alert severity="warning">{providerStatusError}</Alert>
                    ) : null}

                    {providerStatus ? (
                        <section className="cv-analysis-section cv-analysis-provider-section">
                            <Stack direction="row" justifyContent="space-between" gap={1}>
                                <Typography variant="subtitle1">上游分析工具与数据库状态</Typography>
                                <Stack direction="row" gap={1}>
                                    <Button
                                        size="small"
                                        variant="text"
                                        onClick={() => {
                                            loadProviderStatus();
                                            loadPreflightStatus(inputGenomeAssembly);
                                        }}
                                    >
                                        Refresh
                                    </Button>
                                    <Button
                                        size="small"
                                        variant="outlined"
                                        onClick={() => setProvidersExpanded((current) => !current)}
                                    >
                                        {providersExpanded ? 'Hide details' : 'Show details'}
                                    </Button>
                                </Stack>
                            </Stack>
                            <Box className="cv-analysis-provider-summary">
                                <Stack
                                    direction="row"
                                    justifyContent="space-between"
                                    alignItems="center"
                                    gap={1}
                                    flexWrap="wrap"
                                >
                                    <Stack direction="row" gap={0.75} flexWrap="wrap">
                                        <Chip
                                            size="small"
                                            color={
                                                providerReadinessSummary.allReady
                                                    ? 'success'
                                                    : 'warning'
                                            }
                                            variant="outlined"
                                            label={providerReadinessSummary.label}
                                        />
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color={
                                                providerSummaryStats.attention > 0
                                                    ? 'warning'
                                                    : 'default'
                                            }
                                            label={`${formatNumber(
                                                providerSummaryStats.attention,
                                                '0',
                                            )} attention`}
                                        />
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color={
                                                providerSummaryStats.warningCount > 0
                                                    ? 'warning'
                                                    : 'default'
                                            }
                                            label={`${formatNumber(
                                                providerSummaryStats.warningCount,
                                                '0',
                                            )} warnings`}
                                        />
                                    </Stack>
                                    <Typography variant="caption" color="text.secondary">
                                        {providerReadinessSummary.detail}
                                    </Typography>
                                </Stack>
                                {coreProviderNotes.length ? (
                                    <Alert severity="warning" className="cv-analysis-inline-alert">
                                        {coreProviderNotes[0]}
                                    </Alert>
                                ) : selectedAssemblyIsGrch37 ? (
                                    <Alert severity="info" className="cv-analysis-inline-alert">
                                        This input is GRCh37. Candidate coordinates are shown in the
                                        analysis build after validated conversion; unavailable
                                        evidence is never treated as low risk.
                                    </Alert>
                                ) : null}
                            </Box>
                            <Collapse in={providersExpanded}>
                                <Stack className="cv-analysis-provider-groups" gap={1.25}>
                                    <Box className="cv-analysis-provider-group">
                                        <Typography variant="subtitle2">
                                            Analysis foundation
                                        </Typography>
                                        <Box className="cv-analysis-provider-grid">
                                            {providerCards.foundation.map((item) => (
                                                <ProviderStatusCard
                                                    key={item.title}
                                                    title={item.title}
                                                    provider={item.provider}
                                                    detail={item.detail}
                                                >
                                                    {item.children}
                                                </ProviderStatusCard>
                                            ))}
                                        </Box>
                                    </Box>
                                    <Box className="cv-analysis-provider-group">
                                        <Typography variant="subtitle2">上游基础注释</Typography>
                                        <Box className="cv-analysis-provider-grid">
                                            {providerCards.core.map((item) => (
                                                <ProviderStatusCard
                                                    key={item.title}
                                                    title={item.title}
                                                    provider={item.provider}
                                                    detail={item.detail}
                                                >
                                                    {item.children}
                                                </ProviderStatusCard>
                                            ))}
                                        </Box>
                                    </Box>
                                    <Box className="cv-analysis-provider-group">
                                        <Typography variant="subtitle2">
                                            Predictive evidence
                                        </Typography>
                                        <Box className="cv-analysis-provider-grid">
                                            {providerCards.predictive.map((item) => (
                                                <ProviderStatusCard
                                                    key={item.title}
                                                    title={item.title}
                                                    provider={item.provider}
                                                    detail={item.detail}
                                                >
                                                    {item.children}
                                                </ProviderStatusCard>
                                            ))}
                                        </Box>
                                    </Box>
                                    <Box className="cv-analysis-provider-group">
                                        <Typography variant="subtitle2">
                                            Transcript annotation
                                        </Typography>
                                        <Box className="cv-analysis-provider-grid">
                                            {providerCards.transcript.map((item) => (
                                                <ProviderStatusCard
                                                    key={item.title}
                                                    title={item.title}
                                                    provider={item.provider}
                                                    detail={item.detail}
                                                >
                                                    {item.children}
                                                </ProviderStatusCard>
                                            ))}
                                        </Box>
                                    </Box>
                                    <Box className="cv-analysis-provider-group">
                                        <Typography variant="subtitle2">
                                            Optional enhancement
                                        </Typography>
                                        <Box className="cv-analysis-provider-grid">
                                            {providerCards.optional.map((item) => (
                                                <ProviderStatusCard
                                                    key={item.title}
                                                    title={item.title}
                                                    provider={item.provider}
                                                    detail={item.detail}
                                                >
                                                    {item.children}
                                                </ProviderStatusCard>
                                            ))}
                                        </Box>
                                    </Box>
                                </Stack>
                            </Collapse>
                        </section>
                    ) : null}

                    {job ? (
                        <section className="cv-analysis-section">
                            <Stack direction="row" justifyContent="space-between" gap={1}>
                                <div>
                                    <Typography variant="subtitle1">2. Analysis status</Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {job.originalFileName} / {job.analysisMode}
                                    </Typography>
                                </div>
                                <Stack direction="row" gap={0.5}>
                                    <Tooltip title="Refresh status">
                                        <IconButton
                                            size="small"
                                            aria-label="Refresh analysis status"
                                            onClick={refreshJob}
                                        >
                                            <RefreshRoundedIcon fontSize="small" />
                                        </IconButton>
                                    </Tooltip>
                                    <Tooltip title="Remove analysis">
                                        <span>
                                            <IconButton
                                                size="small"
                                                className="cv-analysis-remove-button"
                                                aria-label="Remove analysis"
                                                disabled={isAnalyzing || busy}
                                                onClick={handleRemove}
                                            >
                                                <DeleteOutlineRoundedIcon fontSize="small" />
                                            </IconButton>
                                        </span>
                                    </Tooltip>
                                </Stack>
                            </Stack>
                            <Stack direction="row" alignItems="center" gap={1} mt={1}>
                                <Chip
                                    size="small"
                                    color={statusTone(job.status)}
                                    label={formatStatus(job.status)}
                                />
                                {isAnalyzing ? <CircularProgress size={16} /> : null}
                                <Typography variant="caption" color="text.secondary">
                                    {latestEvent?.message ||
                                        job.normalizationEngine ||
                                        'Preparing normalization'}
                                </Typography>
                            </Stack>
                            {job.status ? (
                                <Box className="cv-analysis-progress-block">
                                    <LinearProgress
                                        className="cv-analysis-progress"
                                        variant="determinate"
                                        value={analysisProgress}
                                    />
                                    <Typography variant="caption" color="text.secondary">
                                        {analysisProgress}% complete
                                    </Typography>
                                </Box>
                            ) : null}
                            {progressiveStage ? (
                                <Alert
                                    severity={
                                        progressiveStage === 'FAILED'
                                            ? 'error'
                                            : progressiveStage === 'READY_FAST' ||
                                                progressiveStage === 'PARTIAL'
                                              ? 'warning'
                                              : 'info'
                                    }
                                    className="cv-analysis-progressive-alert"
                                >
                                    <Stack
                                        direction="row"
                                        gap={1}
                                        alignItems="center"
                                        flexWrap="wrap"
                                    >
                                        <Chip
                                            size="small"
                                            color={statusTone(progressiveStage)}
                                            label={formatStatus(progressiveStage)}
                                        />
                                        <Typography variant="caption">
                                            {progressiveStageMessage(job)}
                                        </Typography>
                                        {job.enrichmentStatus ? (
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color={statusTone(job.enrichmentStatus)}
                                                label={`Enrichment ${formatStatus(
                                                    job.enrichmentStatus,
                                                )}`}
                                            />
                                        ) : null}
                                    </Stack>
                                    {job.partialEvidenceWarning ? (
                                        <Typography variant="caption" color="text.secondary">
                                            {job.partialEvidenceWarning}
                                        </Typography>
                                    ) : null}
                                </Alert>
                            ) : null}
                            {recentEvents.length ? (
                                <Box className="cv-analysis-stage-list">
                                    {recentEvents.map((event) => (
                                        <Box
                                            key={`${event.id || event.createdAt}-${event.message}`}
                                            className="cv-analysis-stage-row"
                                        >
                                            <Typography
                                                variant="caption"
                                                className="cv-analysis-stage-time"
                                            >
                                                {formatEventTime(event.createdAt)}
                                            </Typography>
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color={statusTone(event.status)}
                                                label={formatStatus(event.status)}
                                            />
                                            <Typography variant="caption">
                                                {event.message || '-'}
                                            </Typography>
                                        </Box>
                                    ))}
                                </Box>
                            ) : null}
                            {job.errorMessage ? (
                                <Alert severity="error">
                                    <Stack gap={1}>
                                        {job.errorCode ? (
                                            <Chip
                                                size="small"
                                                color="error"
                                                variant="outlined"
                                                label={job.errorCode}
                                            />
                                        ) : null}
                                        <span>{job.errorMessage}</span>
                                        {extractSampleIdChoices(job.errorMessage).length ? (
                                            <Stack direction="row" gap={0.75} flexWrap="wrap">
                                                {extractSampleIdChoices(job.errorMessage).map(
                                                    (choice) => (
                                                        <Chip
                                                            key={choice}
                                                            size="small"
                                                            clickable
                                                            color={
                                                                sampleId === choice
                                                                    ? 'primary'
                                                                    : 'default'
                                                            }
                                                            variant={
                                                                sampleId === choice
                                                                    ? 'filled'
                                                                    : 'outlined'
                                                            }
                                                            label={`Use ${choice}`}
                                                            onClick={() =>
                                                                handleSelectSampleId(choice)
                                                            }
                                                        />
                                                    ),
                                                )}
                                            </Stack>
                                        ) : null}
                                    </Stack>
                                </Alert>
                            ) : null}
                            {qcStats.length ? (
                                <Box className="cv-analysis-qc-grid">
                                    {qcStats.map(([label, value]) => (
                                        <Box key={label} className="cv-analysis-qc-cell">
                                            <Typography variant="caption">{label}</Typography>
                                            <Typography variant="subtitle1">
                                                {formatNumber(value)}
                                            </Typography>
                                        </Box>
                                    ))}
                                </Box>
                            ) : null}
                            {job.liftoverStatus === 'PARTIAL_LIFTOVER' ? (
                                <Alert severity="warning" className="cv-analysis-inline-alert">
                                    Input conversion was partial: source{' '}
                                    {formatNumber(job.liftoverSourceRecordCount, '0')}, converted{' '}
                                    {formatNumber(job.liftoverLiftedRecordCount, '0')}, rejected{' '}
                                    {formatNumber(job.liftoverRejectedRecordCount, '0')} variant
                                    records. The original input and rejected records are retained
                                    for audit.
                                </Alert>
                            ) : null}
                            {(job.warnings || []).map((warning) => (
                                <Typography
                                    key={warning}
                                    className="cv-analysis-warning"
                                    variant="caption"
                                >
                                    {warning}
                                </Typography>
                            ))}
                        </section>
                    ) : null}

                    {job?.status === 'READY' ? (
                        <section className="cv-analysis-section cv-analysis-report-section">
                            <div>
                                <Typography variant="subtitle1">
                                    3. Analysis report summary
                                </Typography>
                                <Typography variant="caption" color="text.secondary">
                                    Compact overview for QC, evidence coverage and current ranking.
                                </Typography>
                            </div>
                            <Box className="cv-analysis-summary-grid">
                                <Box className="cv-analysis-summary-card">
                                    <Typography variant="subtitle2">Input & QC</Typography>
                                    <Typography variant="body2">
                                        {job.originalFileName || '-'} / Input{' '}
                                        {job.genomeAssembly || 'GRCh38'}
                                        {job.analysisAssembly &&
                                        job.analysisAssembly !== job.genomeAssembly
                                            ? ` → analysis ${job.analysisAssembly}`
                                            : ''}{' '}
                                        / {job.analysisMode || '-'}
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {qcSummary.variantCount > 0
                                            ? `QC 通过 ${formatNumber(qcSummary.passCount, '0')} / ${formatNumber(qcSummary.variantCount, '0')}（${formatPercent(passRate)}）`
                                            : '未记录完整输入 QC 统计，不能按 0 条通过解释'}
                                    </Typography>
                                </Box>
                                <Box className="cv-analysis-summary-card">
                                    <Typography variant="subtitle2">Candidate triage</Typography>
                                    <Typography variant="body2">
                                        {isEngineRankedResult
                                            ? `${formatNumber(candidateTotal, '0')} 个不同候选变异`
                                            : `${formatNumber(candidateTotal, '0')} rare candidates / ${formatNumber(
                                                  frequencyFilteredTotal,
                                                  '0',
                                              )} gnomAD filtered`}
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {isEngineRankedResult
                                            ? `按变异身份合并；当前筛选含 ${variantsPage?.rawRowTotal ?? '-'} 条原始排名行，遗传模式保留在详情。候选数不等于输入VCF行数。`
                                            : `Frequency gate: ${formatPercent(
                                                  populationFrequencyThreshold,
                                              )} maximum AF.`}
                                    </Typography>
                                </Box>
                                <Box className="cv-analysis-summary-card">
                                    <Typography variant="subtitle2">Evidence coverage</Typography>
                                    <Typography variant="body2">
                                        CADD {formatNumber(evidenceSummary.caddScored, '0')} / AM{' '}
                                        {formatNumber(evidenceSummary.alphaMissenseScored, '0')} /
                                        AlphaGenome eligible{' '}
                                        {formatNumber(evidenceSummary.alphaGenomeCandidates, '0')}
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        Calculated from {formatNumber(evidenceSummary.total, '0')}{' '}
                                        loaded variants in the current view.
                                    </Typography>
                                </Box>
                                <Box className="cv-analysis-summary-card">
                                    <Typography variant="subtitle2">
                                        ACMG-ready suggestions
                                    </Typography>
                                    <Typography variant="body2">
                                        {formatNumber(
                                            acmgReadySummary.candidatesWithSuggestions,
                                            '0',
                                        )}{' '}
                                        candidates / {formatNumber(acmgReadySummary.suggested, '0')}{' '}
                                        suggested /{' '}
                                        {formatNumber(acmgReadySummary.manualReviewRequired, '0')}{' '}
                                        manual review
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {acmgReadySummary.codes.length
                                            ? `Codes: ${acmgReadySummary.codes.join(', ')}`
                                            : 'No ACMG-ready suggestions in the loaded view.'}
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        Evidence-direction suggestions only; no final ACMG clinical
                                        classification is produced.
                                    </Typography>
                                </Box>
                                <Box className="cv-analysis-summary-card">
                                    <Typography variant="subtitle2">Resources</Typography>
                                    <Typography variant="body2">
                                        {analysisResourceSummary.label}
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {analysisResourceSummary.detail}
                                    </Typography>
                                </Box>
                                <PhenotypeSummaryPanel
                                    summary={job?.phenotypeSummary}
                                    genePriorLookup={phenotypeGenePriorLookup}
                                    genePriorLookupLoading={phenotypeGenePriorLookupLoading}
                                />
                                <Box className="cv-analysis-summary-card is-wide">
                                    <Stack
                                        direction="row"
                                        alignItems="center"
                                        gap={1}
                                        flexWrap="wrap"
                                    >
                                        <Typography variant="subtitle2">
                                            VEP transcript enrichment
                                        </Typography>
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color={statusTone(transcriptAnnotationStatus)}
                                            label={formatStatus(transcriptAnnotationStatus)}
                                        />
                                    </Stack>
                                    {vepExecutionSummary ? (
                                        <>
                                            <Typography variant="body2">
                                                selected{' '}
                                                {formatNumber(
                                                    vepExecutionSummary.selectedCount,
                                                    '0',
                                                )}{' '}
                                                / processed{' '}
                                                {formatNumber(
                                                    vepExecutionSummary.processedCount,
                                                    '0',
                                                )}{' '}
                                                / success{' '}
                                                {formatNumber(
                                                    vepExecutionSummary.successCount,
                                                    '0',
                                                )}{' '}
                                                / failed{' '}
                                                {formatNumber(vepExecutionSummary.failedCount, '0')}
                                            </Typography>
                                            <Typography variant="caption" color="text.secondary">
                                                cache hits{' '}
                                                {formatNumber(
                                                    vepExecutionSummary.cacheHitCount,
                                                    '0',
                                                )}{' '}
                                                / elapsed{' '}
                                                {formatDuration(vepExecutionSummary.elapsedMillis)}{' '}
                                                / warnings{' '}
                                                {formatNumber(
                                                    vepExecutionSummary.warningCount,
                                                    '0',
                                                )}
                                            </Typography>
                                        </>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary">
                                            No VEP execution statistics for this job yet.{' '}
                                            {vepStatusExplanation(transcriptAnnotationStatus)}
                                        </Typography>
                                    )}
                                </Box>
                                <Box className="cv-analysis-summary-card is-wide">
                                    <Stack
                                        direction="row"
                                        alignItems="center"
                                        gap={1}
                                        flexWrap="wrap"
                                    >
                                        <Typography variant="subtitle2">
                                            Pre-ranking VEP annotation
                                        </Typography>
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color={statusTone(preRankingAnnotationSummary?.status)}
                                            label={formatStatus(
                                                preRankingAnnotationSummary?.status ||
                                                    'NOT_RECORDED',
                                            )}
                                        />
                                        {selectedVepProfile ? (
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color={providerTone(selectedVepProfile)}
                                                label={`${selectedAssembly} profile`}
                                            />
                                        ) : null}
                                    </Stack>
                                    {preRankingAnnotationSummary ? (
                                        <>
                                            <Typography variant="body2">
                                                selected for VEP{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.selectedForVepCount,
                                                    '0',
                                                )}{' '}
                                                / cap{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.preRankingCandidateCap,
                                                    '0',
                                                )}{' '}
                                                / annotated{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.annotatedAvailable,
                                                    '0',
                                                )}
                                            </Typography>
                                            <Typography variant="caption" color="text.secondary">
                                                cache hit{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.cacheHit,
                                                    '0',
                                                )}{' '}
                                                / miss{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.cacheMiss,
                                                    '0',
                                                )}{' '}
                                                / batches{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.batches,
                                                    '0',
                                                )}{' '}
                                                / unavailable{' '}
                                                {formatNumber(
                                                    preRankingAnnotationSummary.unavailable,
                                                    '0',
                                                )}
                                                {preRankingSkippedByCap > 0
                                                    ? ` / skipped by cap ${formatNumber(
                                                          preRankingSkippedByCap,
                                                          '0',
                                                      )}`
                                                    : ''}
                                            </Typography>
                                        </>
                                    ) : (
                                        <Typography variant="caption" color="text.secondary">
                                            No pre-ranking VEP telemetry has been recorded for this
                                            job. Unavailable annotation is not treated as benign or
                                            low risk.
                                        </Typography>
                                    )}
                                </Box>
                            </Box>
                        </section>
                    ) : null}

                    {job?.status === 'READY' ? (
                        <section className="cv-analysis-section cv-analysis-hero-section">
                            <Stack
                                direction="row"
                                justifyContent="space-between"
                                alignItems="flex-start"
                                gap={2}
                                flexWrap="wrap"
                            >
                                <Typography variant="subtitle1">4. Result overview</Typography>
                                <Stack direction="row" gap={1} flexWrap="wrap">
                                    <Button
                                        variant="outlined"
                                        data-testid="variant-analysis-top20-acmg"
                                        disabled={Boolean(historyRead) || candidatesLoading || candidatesFailed}
                                        onClick={() => setAcmgAnalysisId(job.id)}
                                    >
                                        候选 ACMG 证据评价
                                    </Button>
                                    <Button
                                        variant="contained"
                                        disableElevation
                                        disabled={!topLoadedVariant || Boolean(historyRead) || candidatesLoading || candidatesFailed}
                                        data-testid="variant-analysis-view-top-candidate"
                                        onClick={handleFocusTopCandidate}
                                    >
                                        {selectedCandidateId === topLoadedCandidateId
                                            ? 'Top candidate selected'
                                            : 'View top candidate'}
                                    </Button>
                                </Stack>
                            </Stack>
                            {candidateReadNotice}
                            {topLoadedVariant && !candidatesLoading && !candidatesFailed ? (
                                <Box className="cv-analysis-hero-card">
                                    <Stack
                                        direction="row"
                                        justifyContent="space-between"
                                        alignItems="flex-start"
                                        gap={2}
                                        flexWrap="wrap"
                                    >
                                        <Box className="cv-analysis-hero-copy">
                                            <Typography
                                                variant="caption"
                                                className="cv-analysis-hero-kicker"
                                            >
                                                {isFrequencyFilteredView
                                                    ? 'Top filtered variant'
                                                    : 'Top ranked candidate'}
                                            </Typography>
                                            <Typography
                                                className="cv-analysis-hero-title"
                                                variant="h6"
                                            >
                                                {geneSummary(topLoadedVariant) !== '-'
                                                    ? geneSummary(topLoadedVariant)
                                                    : variantCoordinate(topLoadedVariant)}
                                            </Typography>
                                            <Typography
                                                className="cv-analysis-hero-subtitle"
                                                variant="body2"
                                            >
                                                {variantCoordinate(topLoadedVariant)}
                                            </Typography>
                                        </Box>
                                        <Stack
                                            direction="row"
                                            gap={0.75}
                                            flexWrap="wrap"
                                            justifyContent="flex-end"
                                        >
                                            <Chip
                                                size="small"
                                                color={priorityTone(variantTier(topLoadedVariant))}
                                                label={formatStatus(variantTier(topLoadedVariant))}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color="primary"
                                                label={`Score ${formatScore(
                                                    variantScore(topLoadedVariant),
                                                )}`}
                                            />
                                            {acmgSuggestedCount(topLoadedVariant) > 0 ? (
                                                <Chip
                                                    size="small"
                                                    color="primary"
                                                    variant="outlined"
                                                    label={`${formatNumber(
                                                        acmgSuggestedCount(topLoadedVariant),
                                                        '0',
                                                    )} ACMG suggested`}
                                                />
                                            ) : null}
                                            {acmgManualReviewCount(topLoadedVariant) > 0 ? (
                                                <Chip
                                                    size="small"
                                                    color="warning"
                                                    variant="outlined"
                                                    label="Manual review required"
                                                />
                                            ) : null}
                                        </Stack>
                                    </Stack>
                                    <Typography className="cv-analysis-hero-reason" variant="body2">
                                        {topReason(topLoadedVariant)}
                                    </Typography>
                                    <Box className="cv-analysis-hero-metrics">
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">ClinVar</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(topLoadedVariant, 'CLINVAR')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">gnomAD AF</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(topLoadedVariant, 'GNOMAD')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">CADD</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(topLoadedVariant, 'CADD')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">SpliceAI</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(topLoadedVariant, 'SPLICEAI')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">Transcript</Typography>
                                            <Typography variant="body2">
                                                {transcriptHighlights(topLoadedVariant).join(
                                                    ' / ',
                                                ) || 'Review in transcript tab'}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-hero-metric">
                                            <Typography variant="caption">Next step</Typography>
                                            <Typography variant="body2">
                                                Review evidence, score explanation and transcript
                                                annotations in order.
                                            </Typography>
                                        </Box>
                                    </Box>
                                </Box>
                            ) : !candidatesLoading && !candidatesFailed ? (
                                <Box className="cv-analysis-hero-empty">
                                    <Typography variant="body2">
                                        当前筛选条件下未返回候选变异。可调整筛选条件后重新读取。
                                    </Typography>
                                </Box>
                            ) : null}
                        </section>
                    ) : null}

                    {job?.status === 'READY' ? (
                        <section className="cv-analysis-section cv-analysis-candidate-section">
                            <Stack direction="row" justifyContent="space-between" gap={1}>
                                <div>
                                    <Typography variant="subtitle1">{variantListTitle}</Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {variantListSubtitle}
                                    </Typography>
                                </div>
                                <Stack direction="row" alignItems="center" gap={1} flexWrap="wrap">
                                    <Box className="cv-analysis-view-switch">
                                        <Button
                                            size="small"
                                            variant={
                                                variantView === VARIANT_VIEW_CANDIDATES
                                                    ? 'contained'
                                                    : 'text'
                                            }
                                            disableElevation
                                            onClick={() =>
                                                handleVariantViewChange(VARIANT_VIEW_CANDIDATES)
                                            }
                                        >
                                            {isEngineRankedResult
                                                ? 'Ranked candidates'
                                                : 'Rare candidates'}{' '}
                                            {formatNumber(candidateTotal, '0')}
                                        </Button>
                                        {!isEngineRankedResult ? (
                                            <Button
                                                size="small"
                                                variant={
                                                    variantView === VARIANT_VIEW_FREQUENCY_FILTERED
                                                        ? 'contained'
                                                        : 'text'
                                                }
                                                disableElevation
                                                onClick={() =>
                                                    handleVariantViewChange(
                                                        VARIANT_VIEW_FREQUENCY_FILTERED,
                                                    )
                                                }
                                            >
                                                gnomAD filtered{' '}
                                                {formatNumber(frequencyFilteredTotal, '0')}
                                            </Button>
                                        ) : null}
                                        <Button
                                            size="small"
                                            variant={
                                                variantView === VARIANT_VIEW_ALL
                                                    ? 'contained'
                                                    : 'text'
                                            }
                                            disableElevation
                                            onClick={() =>
                                                handleVariantViewChange(VARIANT_VIEW_ALL)
                                            }
                                        >
                                            All persisted {formatNumber(allTotal, '0')}
                                        </Button>
                                    </Box>
                                    <Button
                                        variant="outlined"
                                        startIcon={<FileDownloadOutlinedIcon />}
                                        disabled={!canExport}
                                        onClick={() => handleExportVariants('csv')}
                                    >
                                        {exportingFormat === 'csv'
                                            ? 'Exporting CSV...'
                                            : `Export CSV (top ${formatNumber(MAX_CSV_EXPORT_ROWS)})`}
                                    </Button>
                                    <Button
                                        variant="outlined"
                                        startIcon={<FileDownloadOutlinedIcon />}
                                        disabled={!canExport}
                                        onClick={() => handleExportVariants('json')}
                                    >
                                        {exportingFormat === 'json'
                                            ? 'Exporting JSON...'
                                            : `Export JSON (top ${MAX_JSON_EXPORT_ROWS})`}
                                    </Button>
                                    {!isFrequencyFilteredView ? (
                                        <>
                                            <TextField
                                                size="small"
                                                type="number"
                                                label="Top candidates"
                                                inputProps={{ min: 1, max: 100 }}
                                                value={candidateLimit}
                                                onChange={(event) =>
                                                    setCandidateLimit(
                                                        Math.min(
                                                            100,
                                                            Math.max(
                                                                1,
                                                                Number(event.target.value) || 20,
                                                            ),
                                                        ),
                                                    )
                                                }
                                                className="cv-analysis-limit-field"
                                            />
                                            <Tooltip
                                                title={
                                                    alphaGenomeDisabledReason ||
                                                    'Submit eligible candidates to AlphaGenome'
                                                }
                                            >
                                                <span>
                                                    <Button
                                                        variant="outlined"
                                                        startIcon={<BiotechRoundedIcon />}
                                                        disabled={!canEnhance}
                                                        onClick={handleEnhance}
                                                    >
                                                        AlphaGenome
                                                    </Button>
                                                </span>
                                            </Tooltip>
                                        </>
                                    ) : null}
                                </Stack>
                            </Stack>
                            <Typography className="cv-analysis-privacy-note" variant="caption">
                                {isFrequencyFilteredView
                                    ? `These variants were fully analyzed but are hidden from the default rare-disease candidate list because gnomAD maximum allele frequency is at or above ${formatPercent(populationFrequencyThreshold)}.`
                                    : isAllVariantView
                                      ? 'All persisted view is intended for bucket review and export filtering. Summary-only or skipped annotation states are execution states, not benign calls.'
                                      : `AlphaGenome receives selected de-identified coordinates and alleles only. Its output is mechanistic support, not a clinical conclusion. CADD scored ${evidenceSummary.caddScored}/${evidenceSummary.total} candidates; AlphaGenome pre-screen has ${evidenceSummary.alphaGenomeCandidates} eligible candidates.`}
                            </Typography>
                            {largeFileModeActive ? (
                                <Alert severity="info" className="cv-analysis-large-file-banner">
                                    <Stack gap={1}>
                                        <Typography variant="subtitle2">
                                            Large-file mode is active for this analysis.
                                        </Typography>
                                        <Stack direction="row" gap={0.75} flexWrap="wrap">
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`Total ${formatNumber(qcSummary.variantCount, '0')}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`PASS ${formatNumber(qcSummary.passCount, '0')}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`Carried ALT ${formatNumber(
                                                    largeFileCarriedAltCount,
                                                    '0',
                                                )}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`ALT not confirmed ${formatNumber(
                                                    largeFileNotCarriedAltCount,
                                                    '0',
                                                )}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`Summary-only ${formatNumber(
                                                    largeFileSummary.summaryOnlyCount,
                                                    '0',
                                                )}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                label={`Full detail ${formatNumber(
                                                    largeFileSummary.fullInterpretationCount,
                                                    '0',
                                                )}`}
                                            />
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color={
                                                    Number(
                                                        largeFileSummary.expensiveAnnotationSkippedCount,
                                                    ) > 0
                                                        ? 'warning'
                                                        : 'default'
                                                }
                                                label={`Skipped expensive annotation ${formatNumber(
                                                    largeFileSummary.expensiveAnnotationSkippedCount,
                                                    '0',
                                                )}`}
                                            />
                                        </Stack>
                                        <Typography variant="caption" color="text.secondary">
                                            {preflightSummary.largeFileModeReason ||
                                                'Cheap-gate and bucket summaries are shown as execution metadata.'}{' '}
                                            Summary-only, deferred, or skipped annotation records
                                            are not benign or low-risk classifications.
                                        </Typography>
                                    </Stack>
                                </Alert>
                            ) : null}
                            {preRankingSkippedByCap > 0 ? (
                                <Alert severity="warning" className="cv-analysis-inline-alert">
                                    PRE_RANKING_CANDIDATE_CAP_EXCEEDED: pre-ranking VEP selected{' '}
                                    {formatNumber(
                                        preRankingAnnotationSummary?.selectedForVepCount,
                                        '0',
                                    )}{' '}
                                    candidates from{' '}
                                    {formatNumber(
                                        preRankingAnnotationSummary?.eligibleCandidates,
                                        '0',
                                    )}{' '}
                                    eligible candidates; {formatNumber(preRankingSkippedByCap, '0')}{' '}
                                    were skipped by the configured cap. Skipped annotation is
                                    execution metadata, not a benign or low-risk classification.
                                </Alert>
                            ) : null}
                            {isFrequencyFilteredView ? (
                                <Alert severity="info" className="cv-analysis-inline-alert">
                                    Frequency-filtered variants are retained for review so evidence
                                    is not lost, but they are not treated as primary rare-disease
                                    candidates.
                                </Alert>
                            ) : null}
                            {!isFrequencyFilteredView && alphaGenomeDisabledReason ? (
                                <Alert severity="info" className="cv-analysis-inline-alert">
                                    {alphaGenomeDisabledReason}
                                </Alert>
                            ) : null}
                            <Box className="cv-analysis-search-row">
                                <TextField
                                    size="small"
                                    label="Search"
                                    placeholder="gene / chr / coordinate / rsID / ClinVar ID"
                                    value={variantSearch}
                                    onChange={(event) => setVariantSearch(event.target.value)}
                                    className="cv-analysis-search-field"
                                />
                                <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
                                    <Button
                                        variant={advancedFiltersExpanded ? 'contained' : 'outlined'}
                                        disableElevation
                                        onClick={() =>
                                            setAdvancedFiltersExpanded((current) => !current)
                                        }
                                    >
                                        {advancedFiltersExpanded
                                            ? 'Hide advanced filters'
                                            : 'Show advanced filters'}
                                    </Button>
                                    <Typography
                                        className="cv-analysis-filter-count"
                                        variant="caption"
                                        color="text.secondary"
                                    >
                                        Showing server page{' '}
                                        {formatNumber(displayedVariants.length, '0')} / filtered
                                        total {formatNumber(variantsPage.total, '0')}
                                    </Typography>
                                </Stack>
                            </Box>
                            {largeFileModeActive ? (
                                <Box className="cv-analysis-bucket-panel">
                                    <Stack
                                        direction="row"
                                        justifyContent="space-between"
                                        alignItems="center"
                                        gap={1}
                                        flexWrap="wrap"
                                    >
                                        <div>
                                            <Typography variant="subtitle2">
                                                Execution buckets
                                            </Typography>
                                            <Typography variant="caption" color="text.secondary">
                                                Counts are global for the analysis, not current-page
                                                counts.
                                            </Typography>
                                        </div>
                                        <Button
                                            variant="text"
                                            size="small"
                                            onClick={() =>
                                                handleBucketFilterChange(VARIANT_BUCKET_ALL)
                                            }
                                        >
                                            Clear bucket
                                        </Button>
                                    </Stack>
                                    <Box className="cv-analysis-bucket-grid">
                                        {VARIANT_BUCKET_OPTIONS.map((option) => (
                                            <Button
                                                key={option.value}
                                                className="cv-analysis-bucket-card"
                                                variant={
                                                    variantBucket === option.value
                                                        ? 'contained'
                                                        : 'outlined'
                                                }
                                                disableElevation
                                                onClick={() =>
                                                    handleBucketFilterChange(option.value)
                                                }
                                            >
                                                <span>{option.compactLabel || option.label}</span>
                                                <strong>
                                                    {formatNumber(
                                                        bucketCountFor(option.value),
                                                        '0',
                                                    )}
                                                </strong>
                                            </Button>
                                        ))}
                                    </Box>
                                </Box>
                            ) : null}
                            <Box className="cv-analysis-quick-filter-row">
                                <Typography variant="caption" color="text.secondary">
                                    Quick filters
                                </Typography>
                                <Stack direction="row" gap={0.75} flexWrap="wrap">
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_KNOWN_CLINICAL
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(VARIANT_FILTER_KNOWN_CLINICAL)
                                        }
                                    >
                                        Known clinical
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_HIGH_PRIORITY
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(VARIANT_FILTER_HIGH_PRIORITY)
                                        }
                                    >
                                        High priority
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_CLINVAR_PLP
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(VARIANT_FILTER_CLINVAR_PLP)
                                        }
                                    >
                                        ClinVar P/LP
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={acmgOnlySuggested ? 'contained' : 'outlined'}
                                        disableElevation
                                        onClick={handleToggleAcmgSuggested}
                                    >
                                        ACMG suggested
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_PHENOTYPE_SUPPORTED
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(
                                                VARIANT_FILTER_PHENOTYPE_SUPPORTED,
                                            )
                                        }
                                    >
                                        HPO-supported
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            acmgManualReviewRequired ? 'contained' : 'outlined'
                                        }
                                        disableElevation
                                        onClick={handleToggleAcmgManualReview}
                                    >
                                        Manual review
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_HIDE_COMMON
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(VARIANT_FILTER_HIDE_COMMON)
                                        }
                                    >
                                        Hide common
                                    </Button>
                                    <Button
                                        size="small"
                                        variant={
                                            variantFilter === VARIANT_FILTER_HIDE_QC
                                                ? 'contained'
                                                : 'outlined'
                                        }
                                        disableElevation
                                        onClick={() =>
                                            handleQuickVariantFilter(VARIANT_FILTER_HIDE_QC)
                                        }
                                    >
                                        Hide QC
                                    </Button>
                                    <Button
                                        variant="text"
                                        size="small"
                                        onClick={handleClearVariantFilters}
                                    >
                                        Clear filters
                                    </Button>
                                </Stack>
                            </Box>
                            {activeFilterSummary.length ? (
                                <Box className="cv-analysis-active-filter-summary">
                                    {activeFilterSummary.map((item) => (
                                        <Chip
                                            key={item}
                                            size="small"
                                            variant="outlined"
                                            label={item}
                                        />
                                    ))}
                                </Box>
                            ) : null}
                            <Collapse in={advancedFiltersExpanded}>
                                <Box className="cv-analysis-filter-row cv-analysis-filter-panel">
                                    <FormControl size="small" className="cv-analysis-filter-field">
                                        <InputLabel id="cv-analysis-filter-label">
                                            Filter
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-filter-label"
                                            value={variantFilter}
                                            label="Filter"
                                            onChange={(event) => {
                                                setVariantPageNumber(1);
                                                clearVariantSelection();
                                                setVariantFilter(event.target.value);
                                            }}
                                        >
                                            {VARIANT_FILTER_OPTIONS.map((option) => (
                                                <MenuItem key={option.value} value={option.value}>
                                                    {option.label}
                                                </MenuItem>
                                            ))}
                                        </Select>
                                    </FormControl>
                                    <FormControl
                                        size="small"
                                        className="cv-analysis-acmg-code-field"
                                    >
                                        <InputLabel id="cv-analysis-acmg-code-label">
                                            ACMG code
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-acmg-code-label"
                                            value={acmgCodeFilter}
                                            label="ACMG code"
                                            onChange={(event) => {
                                                setVariantPageNumber(1);
                                                clearVariantSelection();
                                                setAcmgCodeFilter(event.target.value);
                                            }}
                                        >
                                            {ACMG_CODE_OPTIONS.map((code) => (
                                                <MenuItem key={code} value={code}>
                                                    {code === ACMG_CODE_ALL
                                                        ? 'Any ACMG-ready code'
                                                        : code}
                                                </MenuItem>
                                            ))}
                                        </Select>
                                    </FormControl>
                                    <FormControl size="small" className="cv-analysis-filter-field">
                                        <InputLabel id="cv-analysis-summary-only-label">
                                            Summary-only
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-summary-only-label"
                                            value={summaryOnlyFilter}
                                            label="Summary-only"
                                            onChange={(event) => {
                                                setVariantPageNumber(1);
                                                clearVariantSelection();
                                                setSummaryOnlyFilter(event.target.value);
                                            }}
                                        >
                                            <MenuItem value={BOOLEAN_FILTER_ALL}>Any</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_TRUE}>Yes</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_FALSE}>No</MenuItem>
                                        </Select>
                                    </FormControl>
                                    <FormControl size="small" className="cv-analysis-filter-field">
                                        <InputLabel id="cv-analysis-large-mode-label">
                                            Large-file mode
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-large-mode-label"
                                            value={largeFileModeFilter}
                                            label="Large-file mode"
                                            onChange={(event) => {
                                                setVariantPageNumber(1);
                                                clearVariantSelection();
                                                setLargeFileModeFilter(event.target.value);
                                            }}
                                        >
                                            <MenuItem value={BOOLEAN_FILTER_ALL}>Any</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_TRUE}>Yes</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_FALSE}>No</MenuItem>
                                        </Select>
                                    </FormControl>
                                    <FormControl size="small" className="cv-analysis-filter-field">
                                        <InputLabel id="cv-analysis-skipped-annotation-label">
                                            Skipped annotation
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-skipped-annotation-label"
                                            value={skippedAnnotationFilter}
                                            label="Skipped annotation"
                                            onChange={(event) => {
                                                setVariantPageNumber(1);
                                                clearVariantSelection();
                                                setSkippedAnnotationFilter(event.target.value);
                                            }}
                                        >
                                            <MenuItem value={BOOLEAN_FILTER_ALL}>Any</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_TRUE}>Yes</MenuItem>
                                            <MenuItem value={BOOLEAN_FILTER_FALSE}>No</MenuItem>
                                        </Select>
                                    </FormControl>
                                    <FormControlLabel
                                        className="cv-analysis-filter-check"
                                        control={
                                            <Checkbox
                                                size="small"
                                                checked={acmgOnlySuggested}
                                                onChange={(event) => {
                                                    setVariantPageNumber(1);
                                                    clearVariantSelection();
                                                    setAcmgOnlySuggested(event.target.checked);
                                                }}
                                            />
                                        }
                                        label="Only suggested ACMG-ready"
                                    />
                                    <FormControlLabel
                                        className="cv-analysis-filter-check"
                                        control={
                                            <Checkbox
                                                size="small"
                                                checked={acmgManualReviewRequired}
                                                onChange={(event) => {
                                                    setVariantPageNumber(1);
                                                    clearVariantSelection();
                                                    setAcmgManualReviewRequired(
                                                        event.target.checked,
                                                    );
                                                }}
                                            />
                                        }
                                        label="Manual review required"
                                    />
                                </Box>
                            </Collapse>
                            {!isFrequencyFilteredView && enhancement ? (
                                <Stack
                                    className="cv-analysis-enhancement-strip"
                                    direction="column"
                                    alignItems="flex-start"
                                    gap={1}
                                >
                                    <Stack direction="row" alignItems="center" gap={1}>
                                        <Chip
                                            size="small"
                                            color={statusTone(enhancement.status)}
                                            label={`AlphaGenome ${formatStatus(enhancement.status)}`}
                                        />
                                        {isEnhancing ? <CircularProgress size={15} /> : null}
                                        <Typography variant="caption">
                                            selected {enhancement.selectedCandidateCount || 0} /
                                            completed {enhancement.completedCount || 0} /
                                            unavailable {enhancement.unavailableCount || 0}
                                        </Typography>
                                    </Stack>
                                    {enhancementNote ? (
                                        <Typography variant="caption" color="text.secondary">
                                            {enhancementNote}
                                        </Typography>
                                    ) : null}
                                </Stack>
                            ) : null}
                            {candidatesLoading ? (
                                <Box sx={{ mb: 1, px: 1.5, py: 1, bgcolor: '#f4f8ff', borderRadius: 1 }}>
                                    <Typography role="status" variant="caption" color="text.secondary">
                                        {loadedVariantCount > 0
                                            ? '正在读取本次筛选或分页的排名列表，下方暂为上次结果，请等待更新…'
                                            : '正在读取候选排名列表与已有注释，请稍候…'}
                                    </Typography>
                                    <LinearProgress sx={{ mt: 0.75, borderRadius: 1 }} aria-label="候选列表加载中" />
                                </Box>
                            ) : null}
                            <TableContainer className="cv-analysis-table-wrap" aria-busy={candidatesLoading}>
                                <Table size="small" stickyHeader className="cv-analysis-inheritance-table">
                                    <TableHead>
                                        <TableRow>
                                            <TableCell align="right">#</TableCell>
                                            <TableCell>Variant</TableCell>
                                            <TableCell>Bucket</TableCell>
                                            <TableCell>Tier</TableCell>
                                            <TableCell>Why it stands out</TableCell>
                                            <TableCell>ClinVar</TableCell>
                                            <TableCell>gnomAD AF</TableCell>
                                            <TableCell align="right">Score</TableCell>
                                            <TableCell>Mode</TableCell>
                                            <TableCell>GT / FILTER</TableCell>
                                            <TableCell sx={{ minWidth: 210, whiteSpace: 'nowrap' }}>Inheritance</TableCell>
                                        </TableRow>
                                    </TableHead>
                                    <TableBody>
                                        {candidatesLoading && !loadedVariantCount ? (
                                            <TableRow>
                                                <TableCell colSpan={11} align="center" sx={{ py: 5, color: 'text.secondary' }}>
                                                    候选列表读取完成后将在这里显示；当前正在加载，不代表没有变异。
                                                </TableCell>
                                            </TableRow>
                                        ) : null}
                                        {displayedVariants.map((variant, index) => {
                                            const key = variant?.observation?.key || {};
                                            const rowCandidateId = candidateKey(variant);
                                            const observation = variant?.observation || {};
                                            const bucket = analysisBucket(variant);
                                            const bucketOption = bucketOptionFor(bucket);
                                            const summaryOnly = isSummaryOnlyVariant(variant);
                                            const skippedAnnotation =
                                                isSkippedAnnotationVariant(variant);
                                            return (
                                                <TableRow
                                                    key={
                                                        rowCandidateId ||
                                                        `${activeVariantPage}-${index}`
                                                    }
                                                    hover
                                                    className={[
                                                        'cv-analysis-table-row',
                                                        rowCandidateId === topLoadedCandidateId
                                                            ? 'is-top-candidate'
                                                            : '',
                                                        selectedCandidateId === rowCandidateId
                                                            ? 'is-current-selection'
                                                            : '',
                                                        summaryOnly ? 'is-summary-only' : '',
                                                        skippedAnnotation
                                                            ? 'is-skipped-annotation'
                                                            : '',
                                                    ]
                                                        .filter(Boolean)
                                                        .join(' ')}
                                                    selected={
                                                        selectedCandidateId === rowCandidateId
                                                    }
                                                    onClick={event => {
                                                        if (!event.target.closest('details')) handleSelectVariant(variant, { preview: true });
                                                    }}
                                                >
                                                    <TableCell align="right">
                                                        {formatNumber(
                                                            (activeVariantPage - 1) *
                                                                activeVariantPageSize +
                                                                index +
                                                                1,
                                                            '0',
                                                        )}
                                                    </TableCell>
                                                    <TableCell>
                                                        <Button
                                                            className="cv-analysis-variant-link"
                                                            onClick={(event) => {
                                                                event.stopPropagation();
                                                                handleSelectVariant(variant, {
                                                                    preview: true,
                                                                });
                                                            }}
                                                        >
                                                            {formatVariantCoordinate({chromosome:key.chromosome,position:key.position})}{' '}
                                                            <small>
                                                                {key.reference || '-'}&gt;
                                                                {key.alternate || '-'}
                                                            </small>
                                                        </Button>
                                                        {rowCandidateId === topLoadedCandidateId ? (
                                                            <Chip
                                                                className="cv-analysis-top-chip"
                                                                size="small"
                                                                variant="outlined"
                                                                color="primary"
                                                                label={
                                                                    isFrequencyFilteredView
                                                                        ? 'Top filtered'
                                                                        : 'Top candidate'
                                                                }
                                                            />
                                                        ) : null}
                                                        <Typography
                                                            variant="caption"
                                                            className="cv-analysis-table-meta"
                                                        >
                                                            {geneSummary(variant)} |{' '}
                                                            {consequenceLabel(variant)}
                                                        </Typography>
                                                    </TableCell>
                                                    <TableCell>
                                                        <Chip
                                                            size="small"
                                                            variant="outlined"
                                                            color={
                                                                bucket === 'KNOWN_CLINICAL'
                                                                    ? 'error'
                                                                    : bucket === 'HIGH_PRIORITY'
                                                                      ? 'warning'
                                                                      : bucket === 'CANDIDATE' ||
                                                                          bucket === 'REVIEW'
                                                                        ? 'primary'
                                                                        : 'default'
                                                            }
                                                            label={
                                                                bucketOption?.compactLabel || bucket
                                                            }
                                                        />
                                                    </TableCell>
                                                    <TableCell>
                                                        <Chip
                                                            size="small"
                                                            color={priorityTone(
                                                                variantTier(variant),
                                                            )}
                                                            label={formatStatus(
                                                                variantTier(variant),
                                                            )}
                                                        />
                                                    </TableCell>
                                                    <TableCell>
                                                        <Tooltip title={topReason(variant) || '-'}>
                                                            <Typography
                                                                variant="caption"
                                                                className="cv-analysis-table-reason"
                                                            >
                                                                {topReason(variant) || '-'}
                                                            </Typography>
                                                        </Tooltip>
                                                    </TableCell>
                                                    <TableCell>
                                                        <Chip
                                                            size="small"
                                                            variant="outlined"
                                                            color={evidenceTone(
                                                                evidenceApplicabilityLabel(
                                                                    variant,
                                                                    'CLINVAR',
                                                                ),
                                                            )}
                                                            label={
                                                                clinVarLabel(variant) ||
                                                                formatStatus(
                                                                    evidenceApplicabilityLabel(
                                                                        variant,
                                                                        'CLINVAR',
                                                                    ),
                                                                )
                                                            }
                                                        />
                                                    </TableCell>
                                                    <TableCell>
                                                        <Chip
                                                            size="small"
                                                            variant="outlined"
                                                            color={evidenceTone(
                                                                evidenceApplicabilityLabel(
                                                                    variant,
                                                                    'GNOMAD',
                                                                ),
                                                            )}
                                                            label={evidenceValueDisplay(
                                                                variant,
                                                                'GNOMAD',
                                                            )}
                                                        />
                                                    </TableCell>
                                                    <TableCell align="right">
                                                        <Typography className="cv-analysis-score-cell">
                                                            {formatScore(variantScore(variant))}
                                                        </Typography>
                                                    </TableCell>
                                                    <TableCell>
                                                        <Stack
                                                            gap={0.5}
                                                            className="cv-analysis-signal-stack"
                                                        >
                                                            <Chip
                                                                size="small"
                                                                variant="outlined"
                                                                color={
                                                                    summaryOnly
                                                                        ? 'warning'
                                                                        : 'success'
                                                                }
                                                                label={
                                                                    summaryOnly
                                                                        ? 'Summary only'
                                                                        : 'Full detail'
                                                                }
                                                            />
                                                            {skippedAnnotation ? (
                                                                <Tooltip
                                                                    title={
                                                                        variant.expensiveAnnotationSkippedReason ||
                                                                        'Expensive annotation skipped in large-file mode.'
                                                                    }
                                                                >
                                                                    <Chip
                                                                        size="small"
                                                                        variant="outlined"
                                                                        color="warning"
                                                                        label="Skipped annotation"
                                                                    />
                                                                </Tooltip>
                                                            ) : null}
                                                        </Stack>
                                                    </TableCell>
                                                    <TableCell>
                                                        <Typography
                                                            variant="caption"
                                                            className="cv-analysis-sample-meta"
                                                        >
                                                            {observation.genotype || '-'} /{' '}
                                                            {observation.filterStatus || '-'}
                                                        </Typography>
                                                        <Typography
                                                            variant="caption"
                                                            className="cv-analysis-table-meta"
                                                        >
                                                            {observation.depth != null
                                                                ? `DP ${observation.depth}`
                                                                : 'DP -'}{' '}
                                                            |{' '}
                                                            {observation.genotypeQuality != null
                                                                ? `GQ ${observation.genotypeQuality}`
                                                                : 'GQ -'}
                                                        </Typography>
                                                    </TableCell>
                                                    <TableCell sx={{ minWidth: 210 }}>
                                                        <details>
                                                            <summary style={{ cursor: 'pointer', listStyle: 'none' }}>
                                                                <Chip size="small" color="primary" variant="outlined"
                                                                    label={`${[...new Set((variantsPage?.candidateContexts?.[rowCandidateId] || [variant]).map(row => row.candidateIdentity?.modeOfInheritance || 'Unknown'))].join(' / ')} · Details`} />
                                                            </summary>
                                                            {(variantsPage?.candidateContexts?.[rowCandidateId] || [variant]).map(row => <Stack key={candidateKey(row)} spacing={0.5} sx={{ my: 1 }}>
                                                                <Typography variant="body2">{row.candidateIdentity?.geneSymbol || geneSummary(row)} · {row.candidateIdentity?.modeOfInheritance || 'Inheritance not recorded'}</Typography>
                                                                <Typography variant="caption">{consequenceLabel(row)}</Typography>
                                                                <Button size="small" onClick={() => handleSelectVariant(row, { preview: true })}>View context details</Button>
                                                            </Stack>)}
                                                        </details>
                                                    </TableCell>
                                                </TableRow>
                                            );
                                        })}
                                    </TableBody>
                                </Table>
                                {!loadedVariantCount && !candidatesLoading && !candidatesFailed ? (
                                    <Typography className="cv-analysis-empty" variant="body2">
                                        {isFrequencyFilteredView
                                            ? 'No variants were filtered by the gnomAD rare-disease threshold.'
                                            : 'No variants were returned for the current backend filters.'}
                                    </Typography>
                                ) : null}
                            </TableContainer>
                            {variantsPage.total > 0 ? (
                                <Box className="cv-analysis-pagination-row">
                                    <Typography variant="caption" color="text.secondary">
                                        Showing {formatNumber(variantResultStart, '0')}-
                                        {formatNumber(variantResultEnd, '0')} of{' '}
                                        {formatNumber(variantsPage.total, '0')}
                                    </Typography>
                                    <Pagination
                                        size="small"
                                        count={variantPageCount}
                                        page={Math.min(activeVariantPage, variantPageCount)}
                                        onChange={handleVariantPageChange}
                                        showFirstButton
                                        showLastButton
                                    />
                                    <FormControl
                                        size="small"
                                        className="cv-analysis-page-size-field"
                                    >
                                        <InputLabel id="cv-analysis-page-size-label">
                                            Rows
                                        </InputLabel>
                                        <Select
                                            labelId="cv-analysis-page-size-label"
                                            value={activeVariantPageSize}
                                            label="Rows"
                                            onChange={handleVariantPageSizeChange}
                                        >
                                            {VARIANT_PAGE_SIZE_OPTIONS.map((option) => (
                                                <MenuItem key={option} value={option}>
                                                    {option}
                                                </MenuItem>
                                            ))}
                                        </Select>
                                    </FormControl>
                                </Box>
                            ) : null}
                        </section>
                    ) : null}

                    {selectedVariant ? (
                        <section
                            ref={detailSectionRef}
                            className="cv-analysis-section cv-analysis-detail-section"
                            data-testid="variant-evidence-detail"
                        >
                            <Stack direction="row" justifyContent="space-between" gap={1}>
                                <div>
                                    <Typography variant="subtitle1">6. Evidence detail</Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        {selectedCanonicalVariantId}
                                        {selectedCandidateId !== selectedCanonicalVariantId
                                            ? ` · Candidate ${selectedCandidateId}`
                                            : ''}
                                    </Typography>
                                </div>
                                <Stack direction="row" gap={0.5}>
                                    <Button
                                        size="small"
                                        variant="outlined"
                                        startIcon={<LocationOnOutlinedIcon />}
                                        onClick={handleLocate}
                                    >
                                        Locate
                                    </Button>
                                    <Tooltip title="Close evidence detail">
                                        <IconButton
                                            aria-label="Close evidence detail"
                                            size="small"
                                            onClick={clearVariantSelection}
                                        >
                                            <CloseRoundedIcon fontSize="small" />
                                        </IconButton>
                                    </Tooltip>
                                </Stack>
                            </Stack>
                            <Divider className="cv-analysis-divider" />
                            <Box className="cv-analysis-top-explanation">
                                <Stack
                                    direction="row"
                                    alignItems="center"
                                    justifyContent="space-between"
                                    gap={1}
                                    flexWrap="wrap"
                                >
                                    <Stack
                                        direction="row"
                                        alignItems="center"
                                        gap={1}
                                        flexWrap="wrap"
                                    >
                                        <Typography variant="subtitle2">
                                            {selectedCandidateId === topLoadedCandidateId
                                                ? 'Top candidate explanation'
                                                : 'Variant explanation'}
                                        </Typography>
                                        <Chip
                                            size="small"
                                            color={priorityTone(variantTier(selectedVariant))}
                                            label={formatStatus(variantTier(selectedVariant))}
                                        />
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color="primary"
                                            label={`Score ${formatScore(
                                                variantScore(selectedVariant),
                                            )}`}
                                        />
                                        <Chip
                                            size="small"
                                            variant="outlined"
                                            color={
                                                selectedVariantSummaryOnly ? 'warning' : 'success'
                                            }
                                            label={
                                                selectedVariantSummaryOnly
                                                    ? 'Summary only'
                                                    : 'Full detail'
                                            }
                                        />
                                        {selectedVariantSkippedAnnotation ? (
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color="warning"
                                                label="Skipped annotation"
                                            />
                                        ) : null}
                                        {selectedPhenotypeSupport ? (
                                            <Chip
                                                size="small"
                                                color="success"
                                                variant="outlined"
                                                label="Phenotype support"
                                            />
                                        ) : null}
                                        {selectedTrioEvidence ? (
                                            <Chip
                                                size="small"
                                                color="info"
                                                variant="outlined"
                                                label="Trio evidence"
                                            />
                                        ) : null}
                                    </Stack>
                                    <Stack direction="row" gap={0.5} flexWrap="wrap">
                                        {selectedTranscriptHighlights.map((item) => (
                                            <Chip
                                                key={item}
                                                size="small"
                                                variant="outlined"
                                                color="info"
                                                label={item}
                                            />
                                        ))}
                                        {detailIsLoading ? (
                                            <Chip
                                                size="small"
                                                variant="outlined"
                                                color="info"
                                                label="Loading detail"
                                            />
                                        ) : null}
                                    </Stack>
                                </Stack>
                                {detailIsLoading ? (
                                    <LinearProgress className="cv-analysis-detail-loading" />
                                ) : null}
                                <Typography variant="body2">
                                    {topReason(selectedVariant) ||
                                        'No ranking explanation was returned for this variant.'}
                                </Typography>
                            </Box>
                            {selectedVariantSummaryOnly ? (
                                <Alert severity="warning" className="cv-analysis-inline-alert">
                                    This record uses a lightweight summary payload for large-file
                                    execution. Missing detailed provider evidence in this panel does
                                    not imply benignity or low risk.
                                </Alert>
                            ) : null}
                            {selectedVariantSkippedAnnotation ? (
                                <Alert severity="info" className="cv-analysis-inline-alert">
                                    {selectedVariant.expensiveAnnotationSkippedReason ||
                                        'Expensive annotation was skipped for this record in large-file mode.'}
                                </Alert>
                            ) : null}
                            <Box className="cv-analysis-detail-tabs">
                                {DETAIL_TAB_OPTIONS.map((tab) => (
                                    <Button
                                        key={tab.value}
                                        size="small"
                                        variant={detailTab === tab.value ? 'contained' : 'text'}
                                        disableElevation
                                        data-testid={`variant-detail-tab-${String(tab.value)
                                            .toLowerCase()
                                            .replace(/_/g, '-')}`}
                                        onClick={() => handleDetailTabChange(tab.value)}
                                    >
                                        {tab.label}
                                    </Button>
                                ))}
                            </Box>

                            {detailTab === DETAIL_TAB_OVERVIEW ? (
                                <>
                                    <Box className="cv-analysis-detail-grid">
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">Coordinate</Typography>
                                            <Typography variant="body2">
                                                {variantCoordinate(selectedVariant)}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">Gene</Typography>
                                            <Typography variant="body2">
                                                {geneSummary(selectedVariant)}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">Consequence</Typography>
                                            <Typography variant="body2">
                                                {consequenceLabel(selectedVariant)}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">
                                                Execution bucket
                                            </Typography>
                                            <Typography variant="body2">
                                                {bucketOptionFor(analysisBucket(selectedVariant))
                                                    ?.label || analysisBucket(selectedVariant)}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">
                                                GT / DP / GQ / FILTER
                                            </Typography>
                                            <Typography variant="body2">
                                                {[
                                                    selectedVariant?.observation?.genotype || '-',
                                                    selectedVariant?.observation?.depth != null
                                                        ? `DP ${selectedVariant.observation.depth}`
                                                        : 'DP -',
                                                    selectedVariant?.observation?.genotypeQuality !=
                                                    null
                                                        ? `GQ ${selectedVariant.observation.genotypeQuality}`
                                                        : 'GQ -',
                                                    selectedVariant?.observation?.filterStatus ||
                                                        '-',
                                                ].join(' / ')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-detail-cell">
                                            <Typography variant="caption">AB / INFO AF</Typography>
                                            <Typography variant="body2">
                                                {[
                                                    selectedVariant?.observation?.alleleBalance !=
                                                    null
                                                        ? `AB ${formatScore(
                                                              selectedVariant.observation
                                                                  .alleleBalance,
                                                          )}`
                                                        : 'AB -',
                                                    `INFO AF ${populationFrequencyDisplay(
                                                        selectedVariant,
                                                    )}`,
                                                ].join(' / ')}
                                            </Typography>
                                        </Box>
                                    </Box>
                                    <Box className="cv-analysis-overview-grid">
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">ClinVar</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(selectedVariant, 'CLINVAR')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">gnomAD AF</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(selectedVariant, 'GNOMAD')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">CADD</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(selectedVariant, 'CADD')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">SpliceAI</Typography>
                                            <Typography variant="body2">
                                                {evidenceValueDisplay(selectedVariant, 'SPLICEAI')}
                                            </Typography>
                                        </Box>
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">ACMG-ready</Typography>
                                            <Typography variant="body2">
                                                {acmgSuggestedCount(selectedVariant)
                                                    ? `${formatNumber(
                                                          acmgSuggestedCount(selectedVariant),
                                                          '0',
                                                      )} suggested`
                                                    : 'No suggestion'}
                                            </Typography>
                                        </Box>
                                        {selectedPhenotypeSupport || hasJobPhenotypeInput ? (
                                            <Box className="cv-analysis-overview-card is-phenotype">
                                                <Typography variant="caption">
                                                    Phenotype support
                                                </Typography>
                                                <Typography variant="body2">
                                                    {selectedPhenotypeSupport
                                                        ? [
                                                              selectedPhenotypeSupport.geneSymbol,
                                                              selectedPhenotypeSupport.phenotypeRank
                                                                  ? `rank ${selectedPhenotypeSupport.phenotypeRank}`
                                                                  : null,
                                                              selectedPhenotypeSupport.phenotypeScore !=
                                                              null
                                                                  ? `score ${formatScore(
                                                                        selectedPhenotypeSupport.phenotypeScore,
                                                                    )}`
                                                                  : null,
                                                          ]
                                                              .filter(Boolean)
                                                              .join(' / ')
                                                        : 'No phenotype support'}
                                                </Typography>
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    {selectedPhenotypeSupport
                                                        ? 'Candidate prioritization only.'
                                                        : 'Absence of support does not indicate benign status.'}
                                                </Typography>
                                            </Box>
                                        ) : null}
                                        {selectedTrioEvidence ? (
                                            <Box className="cv-analysis-overview-card is-trio">
                                                <Typography variant="caption">
                                                    Trio evidence
                                                </Typography>
                                                <Typography variant="body2">
                                                    {[
                                                        selectedTrioEvidence.model,
                                                        selectedTrioEvidence.confidence,
                                                        selectedTrioEvidence.contributionApplied
                                                            ? `${formatStatus(selectedTrioEvidence.contributionDirection)} ${formatContribution(selectedTrioEvidence.contributionScore)}`
                                                            : selectedTrioEvidence.notAppliedReason
                                                              ? `No rank change: ${formatStatus(selectedTrioEvidence.notAppliedReason)}`
                                                              : selectedTrioEvidence.parentalOrigin,
                                                    ]
                                                        .filter(Boolean)
                                                        .map(formatStatus)
                                                        .join(' / ')}
                                                </Typography>
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    {selectedTrioEvidence.contributionApplied
                                                        ? 'Ranking support only; no hard-gate override.'
                                                        : trioContributionSummary(
                                                              selectedTrioEvidence.rankingContribution,
                                                          )}
                                                </Typography>
                                            </Box>
                                        ) : null}
                                        {selectedExomiserContext ? (
                                            <Box
                                                className="cv-analysis-overview-card"
                                                data-testid="exomiser-candidate-context"
                                            >
                                                <Typography variant="caption">
                                                    Exomiser candidate context
                                                </Typography>
                                                <Typography variant="body2">
                                                    {[
                                                        selectedExomiserContext.geneSymbol,
                                                        selectedExomiserContext.rawModeOfInheritance,
                                                        selectedExomiserContext.normalizedModeOfInheritance &&
                                                        selectedExomiserContext.normalizedModeOfInheritance !==
                                                            selectedExomiserContext.rawModeOfInheritance
                                                            ? `normalized ${selectedExomiserContext.normalizedModeOfInheritance}`
                                                            : null,
                                                        selectedExomiserContext.geneMoiGroupRank !=
                                                        null
                                                            ? `native gene/MOI group rank ${selectedExomiserContext.geneMoiGroupRank} (not a global variant rank)`
                                                            : null,
                                                    ]
                                                        .filter(Boolean)
                                                        .join(' / ') ||
                                                        'Structured fields unavailable'}
                                                </Typography>
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    {exomiserScoreSummary(
                                                        selectedExomiserContext,
                                                    ) || 'No finite engine score reported'}
                                                </Typography>
                                                {(selectedExomiserContext.functionalClass ||
                                                    selectedExomiserContext.hgvs) && (
                                                    <Typography
                                                        variant="caption"
                                                        color="text.secondary"
                                                    >
                                                        {[
                                                            selectedExomiserContext.functionalClass,
                                                            selectedExomiserContext.hgvs,
                                                        ]
                                                            .filter(Boolean)
                                                            .join(' / ')}
                                                    </Typography>
                                                )}
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    Native Exomiser fields; shown for provenance
                                                    only and not rescored.
                                                </Typography>
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    {[
                                                        selectedExomiserContext.engineVersion
                                                            ? `engine ${selectedExomiserContext.engineVersion}`
                                                            : null,
                                                        selectedExomiserContext.geneRowMatchStrategy
                                                            ? `gene link ${formatStatus(
                                                                  selectedExomiserContext.geneRowMatchStrategy,
                                                              )}`
                                                            : null,
                                                    ]
                                                        .filter(Boolean)
                                                        .join(' / ')}
                                                </Typography>
                                                {exomiserFieldList(
                                                    selectedExomiserContext.missingFields,
                                                ).length ? (
                                                    <Typography
                                                        variant="caption"
                                                        color="text.secondary"
                                                    >
                                                        Missing engine fields:{' '}
                                                        {exomiserFieldList(
                                                            selectedExomiserContext.missingFields,
                                                        ).join(', ')}
                                                    </Typography>
                                                ) : null}
                                                {exomiserFieldList(
                                                    selectedExomiserContext.invalidFields,
                                                ).length ? (
                                                    <Typography variant="caption" color="error">
                                                        Invalid or conflicting engine fields:{' '}
                                                        {exomiserFieldList(
                                                            selectedExomiserContext.invalidFields,
                                                        ).join(', ')}
                                                    </Typography>
                                                ) : null}
                                            </Box>
                                        ) : null}
                                        <Box className="cv-analysis-overview-card">
                                            <Typography variant="caption">
                                                Transcript cues
                                            </Typography>
                                            <Typography variant="body2">
                                                {selectedTranscriptHighlights.join(' / ') ||
                                                    'Review transcript tab'}
                                            </Typography>
                                        </Box>
                                    </Box>
                                    <EvidenceAssemblySummary variant={selectedVariant} />
                                    {selectedVariantReviewNotes.length ? (
                                        <Box className="cv-analysis-review-notes">
                                            <Typography variant="subtitle2">
                                                Review notes
                                            </Typography>
                                            <Stack gap={0.75}>
                                                {selectedVariantReviewNotes.map((item) => (
                                                    <Alert
                                                        key={item.message}
                                                        severity={item.severity}
                                                    >
                                                        {item.message}
                                                    </Alert>
                                                ))}
                                            </Stack>
                                        </Box>
                                    ) : null}
                                    <ReasonListSection
                                        title="Cheap gate reasons"
                                        items={selectedVariant.cheapGateReasons}
                                        severity="info"
                                    />
                                </>
                            ) : null}

                            {detailTab === DETAIL_TAB_EVIDENCE ? (
                                <>
                                    {selectedVariantSummaryOnly &&
                                    !(selectedVariant.evidence || []).length ? (
                                        <Alert
                                            severity="warning"
                                            className="cv-analysis-inline-alert"
                                        >
                                            Full provider evidence was not materialized for this
                                            summary-only record. Use bucket, genotype/QC, and cheap
                                            gate reasons as execution metadata only.
                                        </Alert>
                                    ) : null}
                                    <Box className="cv-analysis-evidence-grid">
                                        {(selectedVariant.evidence || []).map((evidence) => (
                                            <EvidenceCard
                                                key={evidenceCardKey(evidence)}
                                                evidence={evidence}
                                            />
                                        ))}
                                    </Box>
                                </>
                            ) : null}

                            {detailTab === DETAIL_TAB_SCORE ? (
                                <>
                                    <Box className="cv-analysis-score-explanation">
                                        <Typography variant="subtitle2">
                                            Why this variant is ranked here
                                        </Typography>
                                        <Typography variant="body2">
                                            {rankingNarrative(selectedVariant)}
                                        </Typography>
                                        <Typography variant="caption" color="text.secondary">
                                            Computational scores and database matches support
                                            transparent triage only; they do not produce a final
                                            clinical conclusion by themselves.
                                        </Typography>
                                    </Box>
                                    {Object.keys(selectedVariant.scoreComponents || {}).length ? (
                                        <Box
                                            className="cv-analysis-score-breakdown"
                                            data-testid="variant-score-breakdown"
                                        >
                                            <Typography variant="subtitle2">
                                                Score components
                                            </Typography>
                                            <Box className="cv-analysis-component-grid">
                                                {Object.entries(
                                                    selectedVariant.scoreComponents || {},
                                                ).map(([key, component]) => (
                                                    <Box
                                                        key={key}
                                                        className={[
                                                            'cv-analysis-component-card',
                                                            key === PHENOTYPE_SCORE_COMPONENT
                                                                ? 'is-phenotype'
                                                                : '',
                                                            key === TRIO_SCORE_COMPONENT
                                                                ? 'is-trio'
                                                                : '',
                                                        ]
                                                            .filter(Boolean)
                                                            .join(' ')}
                                                        data-testid={
                                                            key === TRIO_SCORE_COMPONENT
                                                                ? 'trio-score-breakdown'
                                                                : undefined
                                                        }
                                                    >
                                                        <Stack
                                                            direction="row"
                                                            justifyContent="space-between"
                                                            gap={1}
                                                        >
                                                            <Typography variant="caption">
                                                                {formatBreakdownLabel(key)}
                                                            </Typography>
                                                            <Chip
                                                                size="small"
                                                                variant="outlined"
                                                                color={
                                                                    key ===
                                                                    PHENOTYPE_SCORE_COMPONENT
                                                                        ? 'success'
                                                                        : key ===
                                                                            TRIO_SCORE_COMPONENT
                                                                          ? 'info'
                                                                          : Number(
                                                                                  component?.score,
                                                                              ) < 0
                                                                            ? 'warning'
                                                                            : 'primary'
                                                                }
                                                                label={formatContribution(
                                                                    component?.score,
                                                                )}
                                                            />
                                                        </Stack>
                                                        {(component?.reasons || []).map(
                                                            (reason) => (
                                                                <Typography
                                                                    key={`${key}-${reason}`}
                                                                    variant="caption"
                                                                    color="text.secondary"
                                                                >
                                                                    {reason}
                                                                </Typography>
                                                            ),
                                                        )}
                                                        {key === PHENOTYPE_SCORE_COMPONENT ? (
                                                            <Typography
                                                                variant="caption"
                                                                color="text.secondary"
                                                            >
                                                                From HPO/gene prior support; not a
                                                                final clinical conclusion.
                                                            </Typography>
                                                        ) : null}
                                                        {key === TRIO_SCORE_COMPONENT ? (
                                                            <Typography
                                                                variant="caption"
                                                                color="text.secondary"
                                                            >
                                                                Ranking support only; not a
                                                                diagnosis. Does not override hard
                                                                gates. Evidence rawScore remains
                                                                separate from this contribution.
                                                            </Typography>
                                                        ) : null}
                                                    </Box>
                                                ))}
                                            </Box>
                                        </Box>
                                    ) : Object.keys(selectedVariant.scoreBreakdown || {}).length ? (
                                        <Box
                                            className="cv-analysis-score-breakdown"
                                            data-testid="variant-score-breakdown"
                                        >
                                            <Typography variant="subtitle2">
                                                Score breakdown
                                            </Typography>
                                            <Stack direction="row" flexWrap="wrap" gap={0.75}>
                                                {Object.entries(
                                                    selectedVariant.scoreBreakdown || {},
                                                ).map(([key, value]) => (
                                                    <Chip
                                                        key={key}
                                                        size="small"
                                                        variant="outlined"
                                                        color={
                                                            key === PHENOTYPE_SCORE_COMPONENT
                                                                ? 'success'
                                                                : key === TRIO_SCORE_COMPONENT
                                                                  ? 'info'
                                                                  : Number(value) < 0
                                                                    ? 'warning'
                                                                    : 'primary'
                                                        }
                                                        label={`${formatBreakdownLabel(
                                                            key,
                                                        )} ${formatContribution(value)}`}
                                                    />
                                                ))}
                                            </Stack>
                                        </Box>
                                    ) : selectedVariantSummaryOnly ? (
                                        <Alert
                                            severity="warning"
                                            className="cv-analysis-inline-alert"
                                        >
                                            Detailed score components are unavailable in the compact
                                            summary payload. This does not downgrade the variant.
                                        </Alert>
                                    ) : null}
                                    <ReasonListSection
                                        title="Positive reasons"
                                        items={selectedVariant.positiveReasons}
                                        severity="success"
                                    />
                                    <ReasonListSection
                                        title="Negative reasons"
                                        items={selectedVariant.negativeReasons}
                                        severity="warning"
                                    />
                                    <ReasonListSection
                                        title="Filter reasons"
                                        items={selectedVariant.filterReasons}
                                        severity="warning"
                                    />
                                    <ReasonListSection
                                        title="Warnings"
                                        items={selectedVariant.warnings}
                                        severity="warning"
                                    />
                                    {(selectedVariant.rationale || []).map((item) => (
                                        <Typography
                                            key={item}
                                            className="cv-analysis-rationale"
                                            variant="caption"
                                        >
                                            {item}
                                        </Typography>
                                    ))}
                                </>
                            ) : null}

                            {detailTab === DETAIL_TAB_RANKING_EXPLANATION ? (
                                <Stack gap={2}>
                                    <CandidateRankingExplanationPanel
                                        explanation={rankingExplanation}
                                        loading={rankingExplanationLoading}
                                        errorCode={rankingExplanationErrorCode}
                                        onRetry={handleRankingExplanationRetry}
                                    />
                                    <AnalysisVcfCallContextCard
                                        context={rankingExplanation?.analysisVcfCallContext}
                                    />
                                </Stack>
                            ) : null}

                            {detailTab === DETAIL_TAB_TRANSCRIPT ? (
                                <TranscriptAnnotationPanel variant={selectedVariant} />
                            ) : null}

                            {detailTab === DETAIL_TAB_ACMG ? (
                                <AcmgReadyPanel variant={selectedVariant} />
                            ) : null}
                        </section>
                    ) : null}
                </Box>
            </Box>
            <Dialog
                open={Boolean(
                    acmgAnalysisId && acmgAnalysisId === job?.id && job?.status === 'READY',
                )}
                onClose={() => setAcmgAnalysisId('')}
                fullWidth
                maxWidth="xl"
                className="cv-acmg-workspace-dialog"
                aria-labelledby="analysis-lab-acmg-title"
            >
                {acmgAnalysisId && acmgAnalysisId === job?.id && job?.status === 'READY' && (
                    <TopNAcmgEvaluationPanel key={acmgAnalysisId} analysisId={acmgAnalysisId} candidateCount={candidateTotal}
                        onReturnToCandidates={() => setAcmgAnalysisId('')} />
                )}
            </Dialog>
        </Box>
    );
};

export default VariantAnalysisDrawer;
