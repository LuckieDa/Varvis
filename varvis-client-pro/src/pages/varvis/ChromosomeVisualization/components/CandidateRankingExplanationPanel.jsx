import React from 'react';
import {
    Alert,
    Box,
    Button,
    Chip,
    CircularProgress,
    Divider,
    Stack,
    Typography,
} from '@mui/material';

const COMPLETENESS_COPY = {
    COMPLETE: {
        label: 'Complete persisted explanation',
        severity: 'success',
        message: 'All currently supported persisted contribution fields are available.',
    },
    LEGACY_PARTIAL: {
        label: 'Legacy partial explanation',
        severity: 'warning',
        message: 'This result predates part of the structured explanation contract.',
    },
    SUMMARY_ONLY: {
        label: 'Summary-only explanation',
        severity: 'warning',
        message: 'Only the compact persisted candidate summary is available.',
    },
    UNAVAILABLE: {
        label: 'Explanation unavailable',
        severity: 'info',
        message: 'No persisted ranking breakdown is available. No score was reconstructed.',
    },
};

const ERROR_COPY = {
    CONTRACT_MISMATCH: 'The explanation response could not be safely verified.',
    NOT_AVAILABLE: 'No structured ranking explanation is available for this candidate.',
    REQUEST_TIMEOUT: 'The explanation request timed out. The existing ranking is unchanged.',
    REQUEST_FAILED: 'The explanation could not be loaded. The existing ranking is unchanged.',
};

const COMPONENT_LABELS = {
    clinical_evidence: 'Clinical evidence',
    population_frequency: 'Population frequency',
    predicted_consequence: 'Predicted consequence',
    computational_prediction: 'Computational prediction',
    splice_prediction: 'Splice prediction',
    qc_quality: 'Variant quality',
    genotype_support: 'Genotype support',
    conflict_penalty: 'Evidence conflict',
    phenotype_support: 'Phenotype support',
    trio_segregation_support: 'Trio segregation support',
    pathogenicity: 'Pathogenicity support',
    population: 'Population evidence',
    splice: 'Splice evidence',
    legacy_positive_reasons: 'Legacy positive support',
    legacy_negative_reasons: 'Legacy negative support',
    legacy_rationale: 'Legacy ranking rationale',
};

const SOURCE_LABELS = {
    CLINVAR: 'ClinVar',
    GNOMAD: 'gnomAD',
    CADD: 'CADD',
    SPLICEAI: 'SpliceAI',
    ALPHAMISSENSE: 'AlphaMissense',
    ALPHAGENOME: 'AlphaGenome',
    VEP: 'VEP',
    EXOMISER: 'Exomiser',
    TRIO: 'Trio evidence',
    PHENOTYPE_GENE_PRIOR: 'Phenotype-gene prior',
};

const AVAILABILITY_COPY = {
    EVIDENCE_REPORTED_AVAILABLE: 'Evidence was reported as available.',
    EVIDENCE_REPORTED_CONFLICTING: 'The source reported conflicting evidence.',
    NO_SOURCE_RECORD_REPORTED:
        'The source reported no matching record; this is not a benign result.',
    EVIDENCE_NOT_APPLICABLE: 'This evidence source is not applicable to the candidate.',
    EVIDENCE_UNAVAILABLE: 'The evidence source was unavailable; absence is not a negative result.',
    EVIDENCE_AVAILABILITY_UNPROVEN: 'Evidence availability could not be established.',
};

const DIRECTION_COPY = {
    POSITIVE: { label: 'Positive', color: 'success' },
    NEGATIVE: { label: 'Negative', color: 'warning' },
    NEUTRAL: { label: 'Neutral', color: 'default' },
};

const fixedWarningCopy = (warning) => {
    const value = String(warning || '');
    if (value.includes('non-finite')) {
        return 'One or more persisted scores were unavailable and were not displayed.';
    }
    if (value.includes('compact candidate summary')) {
        return 'Detailed evidence may be incomplete because this is a compact candidate summary.';
    }
    if (value.includes('older ranking')) {
        return 'This older result contains only a partial persisted explanation.';
    }
    if (value.includes('No persisted ranking breakdown')) {
        return 'No persisted ranking breakdown is available; the interface did not reconstruct one.';
    }
    return 'The server reported an additional explanation limitation; restricted details are not displayed.';
};

const scoreDisplay = (score) => {
    if (score == null || typeof score !== 'number' || !Number.isFinite(score)) {
        return 'unavailable';
    }
    const normalized = Object.is(score, -0) ? 0 : score;
    const rendered = Number.isInteger(normalized)
        ? String(normalized)
        : normalized.toFixed(3).replace(/0+$/, '').replace(/\.$/, '');
    return normalized > 0 ? `+${rendered}` : rendered;
};

const ContributionGroup = ({ title, direction, contributions = [] }) => {
    if (!contributions.length) return null;
    const directionCopy = DIRECTION_COPY[direction] || DIRECTION_COPY.NEUTRAL;
    return (
        <Box data-testid={`ranking-explanation-${String(direction).toLowerCase()}`}>
            <Typography variant="subtitle2">{title}</Typography>
            <Stack gap={1} mt={0.75}>
                {contributions.map((contribution, index) => (
                    <Box
                        key={`${direction}-${contribution?.key || 'component'}-${index}`}
                        className="cv-analysis-component-card"
                    >
                        <Stack
                            direction="row"
                            justifyContent="space-between"
                            alignItems="center"
                            gap={1}
                        >
                            <Typography variant="body2">
                                {COMPONENT_LABELS[contribution?.key] ||
                                    'Other persisted ranking component'}
                            </Typography>
                            <Stack direction="row" alignItems="center" gap={0.75}>
                                <Chip
                                    size="small"
                                    variant="outlined"
                                    color={directionCopy.color}
                                    label={directionCopy.label}
                                />
                                <Chip
                                    size="small"
                                    data-testid="ranking-explanation-contribution-score"
                                    label={`Score ${scoreDisplay(contribution?.score)}`}
                                />
                            </Stack>
                        </Stack>
                    </Box>
                ))}
            </Stack>
        </Box>
    );
};

const EvidenceGroup = ({ title, tone, evidence = [] }) => {
    if (!evidence.length) return null;
    return (
        <Box>
            <Stack direction="row" alignItems="center" gap={0.75} mb={0.75}>
                <Typography variant="subtitle2">{title}</Typography>
                <Chip size="small" color={tone} variant="outlined" label={evidence.length} />
            </Stack>
            <Stack gap={0.75}>
                {evidence.map((item, index) => (
                    <Alert key={`${title}-${index}`} severity={tone} variant="outlined">
                        <Typography variant="body2">
                            {SOURCE_LABELS[item?.source] || 'Other evidence source'}
                        </Typography>
                        <Typography variant="caption">
                            {AVAILABILITY_COPY[item?.availabilityMeaning] ||
                                AVAILABILITY_COPY.EVIDENCE_AVAILABILITY_UNPROVEN}
                        </Typography>
                    </Alert>
                ))}
            </Stack>
        </Box>
    );
};

const CandidateRankingExplanationPanel = ({
    explanation,
    loading = false,
    errorCode = '',
    onRetry,
}) => {
    if (loading) {
        return (
            <Stack
                data-testid="ranking-explanation-loading"
                direction="row"
                alignItems="center"
                gap={1}
                py={2}
            >
                <CircularProgress size={18} />
                <Typography variant="body2">Loading the persisted ranking explanation…</Typography>
            </Stack>
        );
    }

    if (errorCode) {
        return (
            <Alert
                severity={errorCode === 'NOT_AVAILABLE' ? 'info' : 'warning'}
                data-testid="ranking-explanation-error"
                action={
                    onRetry ? (
                        <Button color="inherit" size="small" onClick={onRetry}>
                            Retry
                        </Button>
                    ) : null
                }
            >
                {ERROR_COPY[errorCode] || ERROR_COPY.REQUEST_FAILED}
            </Alert>
        );
    }

    if (!explanation) {
        return (
            <Alert severity="info" data-testid="ranking-explanation-empty">
                Open this tab to request the persisted ranking explanation.
            </Alert>
        );
    }

    const completeness =
        COMPLETENESS_COPY[explanation.completeness] || COMPLETENESS_COPY.UNAVAILABLE;
    const warningCopy = [...new Set((explanation.warnings || []).map(fixedWarningCopy))];

    return (
        <Stack gap={2} data-testid="candidate-ranking-explanation-panel">
            <Box className="cv-analysis-score-explanation">
                <Stack direction="row" alignItems="center" gap={0.75} flexWrap="wrap">
                    <Typography variant="subtitle2">Persisted ranking explanation</Typography>
                    <Chip
                        size="small"
                        variant="outlined"
                        color={completeness.severity}
                        label={completeness.label}
                    />
                    <Chip
                        size="small"
                        data-testid="ranking-explanation-total-score"
                        label={`Score ${scoreDisplay(explanation.rankingScore)}`}
                    />
                </Stack>
                <Typography variant="body2">{completeness.message}</Typography>
            </Box>

            <ContributionGroup
                title="Positive contributions"
                direction="POSITIVE"
                contributions={explanation.positiveContributions}
            />
            <ContributionGroup
                title="Negative contributions"
                direction="NEGATIVE"
                contributions={explanation.negativeContributions}
            />
            <ContributionGroup
                title="Neutral or unavailable contributions"
                direction="NEUTRAL"
                contributions={explanation.neutralContributions}
            />

            <Divider />
            <Box className="cv-analysis-component-grid">
                <EvidenceGroup
                    title="Available evidence"
                    tone="success"
                    evidence={explanation.availableEvidence}
                />
                <EvidenceGroup
                    title="Conflicting evidence"
                    tone="warning"
                    evidence={explanation.conflictingEvidence}
                />
                <EvidenceGroup
                    title="Missing or unavailable evidence"
                    tone="info"
                    evidence={explanation.missingEvidence}
                />
            </Box>

            {warningCopy.length ? (
                <Stack gap={0.75} data-testid="ranking-explanation-warnings">
                    {warningCopy.map((warning) => (
                        <Alert key={warning} severity="warning" variant="outlined">
                            {warning}
                        </Alert>
                    ))}
                </Stack>
            ) : null}

            <Alert severity="info" variant="outlined">
                This view explains persisted ranking output only. It does not rescore, reclassify,
                change candidate order, or provide a clinical diagnosis.
            </Alert>
        </Stack>
    );
};

export default CandidateRankingExplanationPanel;
